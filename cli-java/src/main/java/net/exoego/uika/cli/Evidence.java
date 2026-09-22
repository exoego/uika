package net.exoego.uika.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Runtime class-load evidence ({@code --class-load-log}). Opt-in logs from a JVM run of the
 * CURRENT, not yet upgraded, build. Typically the base branch's test suite run with
 * {@code -Xlog:class+load=info:file=...} and stored as a CI artifact for the dependency PR to
 * read. A ⚠️ violation whose referencing class shows up in the log is promoted. The class
 * provably loads, and reflection-driven loading, the static walk's blind spot, is exactly
 * what such a log captures.
 *
 * <p>Promote-only, the same stance reachability takes. An observed load lifts a violation
 * out of ⚠️, but absence of a load entry proves nothing beyond the observed runs, so nothing
 * is ever demoted or dropped on this input. The one deliberate consumer of absence is
 * {@link #draftExcludes}, which only ever writes a file for a human to review and says so in
 * every reason it drafts.
 */
final class Evidence {
    private Evidence() {}

    /**
     * Longest line the parser will look at. JVM log lines are short. The cap exists so a
     * stray file with no newline (a binary dropped into the log directory) is skipped in
     * bounded memory instead of being buffered whole.
     */
    static final int MAX_LINE = 64 * 1024;

    /** What the logs recorded for one loaded class. */
    static final class LoadRecord {
        /** Load line(s) only, no cause stack consumed yet. */
        static final LoadRecord LOADED = new LoadRecord(false, null);

        private final boolean stacked;
        private final String trigger;

        private LoadRecord(boolean stacked, String trigger) {
            this.stacked = stacked;
            this.trigger = trigger;
        }

        /**
         * A {@code class+load+cause} stack was consumed (first stack with frames wins) and
         * reduced to its trigger. Null when every frame was loading machinery.
         */
        static LoadRecord stacked(String trigger) {
            return new LoadRecord(true, trigger);
        }

        String trigger() {
            return trigger;
        }
    }

    static final class LoadEvidence {
        /**
         * Slashed internal name to what was observed. Slashed so lookups match
         * {@code Violation.sourceClass} without converting per violation. Only the composed
         * trigger is retained per class, never raw frames. A cause-mode log
         * ({@code -XX:LogClassLoadingCauseFor=*}, the shape README recommends for test
         * suites) names every class the run loaded, and retaining even a capped stack for
         * each of tens of thousands of classes cost hundreds of MB for data {@code apply}
         * never read.
         */
        private final Map<String, LoadRecord> loaded;
        /** The log paths the evidence came from (pre-joined), for notes and drafted reasons. */
        private final String sources;

        LoadEvidence(Map<String, LoadRecord> loaded, String sources) {
            this.loaded = loaded;
            this.sources = sources;
        }

        int distinctClasses() {
            return loaded.size();
        }

        String sources() {
            return sources;
        }

        LoadRecord observed(String slashed) {
            return loaded.get(slashed);
        }
    }

    /**
     * Streaming state for one open {@code class+load+cause} stack block. The trigger is
     * computed as the frames pass by, never retained, so cause-mode logs cost one record per
     * class, and a delegation chain of any depth still yields its trigger (a fixed retention
     * cap used to lose the trigger past 32 machinery frames).
     */
    private static final class StackBlock {
        /** Slashed class the block is for. */
        final String className;
        /**
         * Whether this block owns the class's stack slot (first stack with frames wins, and
         * a class whose stack was already consumed has later blocks read and dropped).
         */
        final boolean fresh;

        boolean framesSeen;
        /** First frame outside the loading machinery, the trigger candidate. */
        String firstUseful;
        /**
         * First non-machinery, non-reflective frame, sought only when {@code firstUseful} is
         * a reflective API. "Class.forName" alone says how, the caller says who.
         */
        String caller;

        StackBlock(String className, boolean fresh) {
            this.className = className;
            this.fresh = fresh;
        }
    }

    /** Per-file parser state. The map of observations plus the open stack block, if any. */
    static final class Parser {
        private final Map<String, LoadRecord> loaded;
        private StackBlock current;

        Parser(Map<String, LoadRecord> loaded) {
            this.loaded = loaded;
        }

        /** Close the open stack block, reducing its frames to the composed trigger. */
        void closeBlock() {
            StackBlock block = current;
            current = null;
            if (block == null || !(block.fresh && block.framesSeen)) {
                return;
            }
            String trigger = block.firstUseful;
            if (trigger != null && isReflective(trigger) && block.caller != null) {
                // "java.lang.Class.forName(Class.java:100)" to "java.lang.Class.forName".
                // The reflective API's own source location says nothing, and the caller
                // keeps its own.
                int paren = trigger.indexOf('(');
                String api = paren < 0 ? trigger : trigger.substring(0, paren);
                trigger = api + " from " + block.caller;
            }
            loaded.put(block.className, LoadRecord.stacked(trigger));
        }

        private void openBlock(String className) {
            // First stack wins. A class several loaders define logs several stacks, and the
            // first is the one that pulled the class in.
            LoadRecord existing = loaded.get(className);
            boolean fresh = existing == null || !existing.stacked;
            loaded.putIfAbsent(className, LoadRecord.LOADED);
            current = new StackBlock(className, fresh);
        }

        private void frame(String frame) {
            StackBlock block = current;
            if (block == null) {
                return;
            }
            block.framesSeen = true;
            if (!block.fresh || isMachinery(frame)) {
                return;
            }
            if (block.firstUseful == null) {
                block.firstUseful = frame;
            } else if (isReflective(block.firstUseful) && block.caller == null && !isReflective(frame)) {
                block.caller = frame;
            }
        }
    }

    /**
     * Parse one or more class-load logs. Accepted per line, leniently:
     *
     * <ul>
     *   <li>JDK unified logging with any decorators, such as
     *       {@code [0.1s][info][class,load] a.b.C source: ...}. A line whose tags decorator
     *       names another stream (gc, jit) is skipped, so a log file shared with other
     *       {@code -Xlog} streams works.
     *   <li>{@code class+load+cause} blocks. {@code Java stack when loading a.b.C:} followed
     *       by {@code at ...} frames. The native variant marks the class loaded, and its
     *       frames are not Java frames.
     *   <li>Undecorated class-name lines, dotted or slashed. Plain lists and
     *       {@code -XX:DumpLoadedClassList} classlists.
     * </ul>
     *
     * <p>Anything else is ignored rather than an error. These files are produced by a JVM,
     * can interleave with other output, and get truncated by the crashes worth studying, so
     * a strict parser would reject exactly the interesting runs.
     *
     * <p>A directory reads every regular file under it (recursively, in sorted order for
     * determinism, symlinks followed, a symlink cycle is a clean error). Parallel test JVMs
     * write per-process files ({@code %p} in the {@code -Xlog} file name, because each JVM
     * truncates a shared file on open), and a downloaded CI artifact unpacks to a directory,
     * so the directory is the natural unit to pass.
     */
    static LoadEvidence load(List<String> paths) {
        Map<String, LoadRecord> loaded = new HashMap<>();
        for (String path : paths) {
            Path file = Path.of(path);
            // Evidence is data another job produces, not configuration, so a path that is
            // not there is an expected operational state. The artifact was not downloaded on
            // a laptop, on a fork PR, or before the base branch ever collected any. An empty
            // DIRECTORY already degraded to no evidence here, so failing on the absent path
            // was the same situation answered two different ways. Loud, because promotion is
            // what silently stops happening, and a typo in the path looks identical.
            if (!Files.exists(file)) {
                Out.warn("no class-load evidence at " + path + "; nothing will be promoted from it");
                continue;
            }
            if (Files.isDirectory(file)) {
                walk(path, path, file, new ArrayList<>(), loaded);
            } else {
                parseFile(path, file, loaded);
            }
        }
        return new LoadEvidence(loaded, String.join(", ", paths));
    }

    private record Ancestor(Path real, String display) {}

    /**
     * Children in file-name byte order, a directory's contents where the directory sorts.
     * That order decides which of two stacks for one class is the first. Messages follow
     * walkdir, whose IO errors name the cause twice once the context chain is printed.
     *
     * @param root the path as the user spelled it
     * @param display {@code root} plus the names walked so far
     */
    private static void walk(
            String root, String display, Path dir, List<Ancestor> ancestors, Map<String, LoadRecord> loaded) {
        List<Path> children = new ArrayList<>();
        Path real;
        try {
            real = dir.toRealPath();
            for (Ancestor ancestor : ancestors) {
                if (ancestor.real.equals(real)) {
                    throw new UikaException("cannot read class-load log directory " + root
                            + ": File system loop found: " + display + " points to an ancestor " + ancestor.display);
                }
            }
            try (Stream<Path> listing = Files.list(dir)) {
                listing.forEach(children::add);
            }
        } catch (IOException e) {
            throw walkError(root, display, UikaException.describe(e));
        }
        children.sort(Comparator.comparing(p -> p.getFileName().toString(), Text::compareUtf8));
        ancestors.add(new Ancestor(real, display));
        for (Path child : children) {
            String name = child.getFileName().toString();
            String childDisplay = display.endsWith("/") ? display + name : display + "/" + name;
            if (Files.isDirectory(child)) {
                walk(root, childDisplay, child, ancestors, loaded);
            } else if (Files.isRegularFile(child)) {
                // Binary JFR recordings live next to their plugin-converted text in the
                // intended flow. Skip them by name instead of byte-scanning up to 250MB each
                // for newlines. An explicitly passed `.jfr` path is still read (and skipped
                // line by line), so text evidence deliberately named `.jfr` keeps a way in.
                int dot = name.lastIndexOf('.');
                if (dot > 0 && name.substring(dot + 1).equals("jfr")) {
                    continue;
                }
                parseFile(childDisplay, child, loaded);
            } else if (!Files.exists(child) && Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                throw walkError(root, childDisplay, "No such file or directory (os error 2)");
            }
        }
        ancestors.remove(ancestors.size() - 1);
    }

    private static UikaException walkError(String root, String at, String io) {
        return new UikaException(
                "cannot read class-load log directory " + root + ": IO error for operation on " + at + ": " + io + ": " + io);
    }

    private static void parseFile(String display, Path path, Map<String, LoadRecord> loaded) {
        Parser parser = new Parser(loaded);
        byte[] line = new byte[MAX_LINE];
        try (InputStream in = Files.newInputStream(path)) {
            Lines lines = new Lines(in);
            while (true) {
                int n = lines.read(line);
                if (n == 0) {
                    break;
                }
                if (n == MAX_LINE && line[n - 1] != '\n') {
                    // Oversized line (a binary in the directory). Skip to the next newline
                    // in bounded memory, treat it like any other unrecognized line.
                    do {
                        n = lines.read(line);
                    } while (n != 0 && line[n - 1] != '\n');
                    parser.closeBlock();
                    continue;
                }
                parseLine(trimEnd(new String(line, 0, n, StandardCharsets.UTF_8)), parser);
            }
        } catch (IOException e) {
            throw new UikaException("cannot read class-load log " + display, e);
        }
        // EOF ends an open stack block like any other block boundary.
        parser.closeBlock();
    }

    /** Hands out at most {@link #MAX_LINE} bytes a call, so no line is ever held whole. */
    private static final class Lines {
        private final InputStream in;
        private final byte[] chunk = new byte[1 << 16];
        private int pos;
        private int limit;

        Lines(InputStream in) {
            this.in = in;
        }

        /** Fills {@code line} up to and including the next newline. Zero at EOF. */
        int read(byte[] line) throws IOException {
            int n = 0;
            while (n < line.length) {
                if (pos == limit) {
                    limit = Math.max(0, in.read(chunk));
                    pos = 0;
                    if (limit == 0) {
                        break;
                    }
                }
                int max = Math.min(limit - pos, line.length - n);
                int taken = 0;
                boolean newline = false;
                while (taken < max && !newline) {
                    newline = chunk[pos + taken++] == '\n';
                }
                System.arraycopy(chunk, pos, line, n, taken);
                pos += taken;
                n += taken;
                if (newline) {
                    break;
                }
            }
            return n;
        }
    }

    static void parseLine(String line, Parser parser) {
        // Strip unified-logging decorator groups. A tags decorator naming another stream
        // means the line belongs to a different `-Xlog` output sharing the file. Tags are
        // matched as exact comma-separated members ("class" and "load" both present), because
        // substring matching also caught the class,loader,* family. Only a multi-member
        // group counts as a foreign tags decorator, since a single-word group is just as
        // likely a level ([info]) or hostname decorator, and a tags-less decorator set
        // (-Xlog:class+load=info:file=x:time) must still fall through to the token parse.
        String rest = trimStart(line);
        boolean trusted = false;
        boolean foreign = false;
        boolean isNative = false;
        while (rest.startsWith("[")) {
            int end = rest.indexOf(']', 1);
            if (end < 0) {
                break;
            }
            String group = rest.substring(1, end);
            if (group.indexOf(',') >= 0) {
                boolean classSeen = false;
                boolean loadSeen = false;
                for (String tag : group.split(",", -1)) {
                    switch (Reach.trim(tag)) {
                        case "class" -> classSeen = true;
                        case "load" -> loadSeen = true;
                        case "native" -> isNative = true;
                        default -> {}
                    }
                }
                if (classSeen && loadSeen) {
                    trusted = true;
                } else {
                    foreign = true;
                }
            }
            rest = trimStart(rest.substring(end + 1));
        }
        if (foreign && !trusted) {
            // A foreign line is skipped outright. It must not end an open stack block,
            // because streams interleave per line and the cause block continues after it.
            return;
        }
        rest = Reach.trim(rest);
        if (rest.startsWith("Java stack when loading ")) {
            parser.closeBlock();
            // The JVM itself printed the header, so the name is trusted like a tagged line.
            String name = normalize(trimEndMatches(rest.substring("Java stack when loading ".length()), ":"), true);
            if (name != null) {
                parser.openBlock(name);
            }
            return;
        }
        if (rest.startsWith("Native stack when loading ")) {
            parser.closeBlock();
            String name = normalize(trimEndMatches(rest.substring("Native stack when loading ".length()), ":"), true);
            if (name != null) {
                parser.loaded.putIfAbsent(name, LoadRecord.LOADED);
            }
            return;
        }
        // Native stack frames share the class,load,cause,native tags with their header, and
        // they are not class names. The frame-kind letters ("V  [libjvm.so+0x...]",
        // "j  java.lang.Thread.run()V+8") would pass the trusted single-segment path and
        // register bogus default-package classes an obfuscated jar could then collide with.
        if (isNative) {
            return;
        }
        if (rest.startsWith("at ")) {
            // A frame belongs to the open stack block and is never read as a loaded class.
            parser.frame(Reach.trim(rest.substring(3)));
            return;
        }
        // Monitor annotations ("- locked <0x...> (a java.lang.Object)") interleave the frames
        // of a real stack (observed on JDK 25, synchronized loader frames carry one). They
        // are part of the block, never its end. Reading one as a terminator cut every stack
        // at its first synchronized loader frame and silently lost the trigger.
        if (parser.current != null && rest.startsWith("- ")) {
            return;
        }
        parser.closeBlock();
        if (rest.isEmpty()) {
            return;
        }
        int tokenEnd = 0;
        while (tokenEnd < rest.length() && !Reach.isWhitespace(rest.charAt(tokenEnd))) {
            tokenEnd++;
        }
        String name = normalize(trimEndMatches(rest.substring(0, tokenEnd), ":,;"), trusted);
        if (name != null) {
            parser.loaded.putIfAbsent(name, LoadRecord.LOADED);
        }
    }

    private static String trimStart(String s) {
        int start = 0;
        while (start < s.length() && Reach.isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }

    static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Reach.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static String trimEndMatches(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * A token is kept as a class name when every '.'/'/'-separated segment is a plausible
     * Java identifier. Not digit-first, ASCII letters/digits/'_'/'$' plus anything non-ASCII
     * (JVM names are barely restricted, and Kotlin/Scala/obfuscators emit non-ASCII names).
     * That rejects the numeric tokens sharing the shape in mixed logs (IPs, versions) while
     * keeping inner classes.
     *
     * <p>A bare token needs at least two segments, since a default-package class is
     * indistinguishable from a stray word. A {@code trusted} token, one the JVM itself
     * labeled via a {@code [class,load]} tags decorator or a stack-block header, is a class
     * name by construction, so a single segment (default package) is accepted. Dots
     * normalize to the slashed internal form violations use.
     */
    static String normalize(String token, boolean trusted) {
        int segments = 1;
        boolean segmentStart = true;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '.' || c == '/') {
                if (segmentStart) {
                    return null;
                }
                segments++;
                segmentStart = true;
                continue;
            }
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$' || c >= 0x80;
            if (!letter && (segmentStart || c < '0' || c > '9')) {
                return null;
            }
            segmentStart = false;
        }
        if (segmentStart || segments < (trusted ? 1 : 2)) {
            return null;
        }
        return token.replace('.', '/');
    }

    /**
     * The one application site, promote-only. A violation whose referencing class was
     * observed loading gains {@code observedLoading} and, when a cause stack was captured,
     * its trigger frame. The command layer applies this to the final violation set, after
     * per-module merging and before the exit decision, so the check itself and the verdicts
     * stream stay untouched.
     */
    static void apply(List<Violation> violations, LoadEvidence evidence) {
        for (Violation v : violations) {
            LoadRecord record = evidence.observed(Intern.str(v.sourceClass));
            if (record != null) {
                v.observedLoading = true;
                // Promote-only holds across evidence sets too. A later stack-less observation
                // must not clear a trigger an earlier log established.
                if (record.trigger() != null) {
                    v.loadTrigger = record.trigger();
                }
            }
        }
    }

    /**
     * Class-loading machinery. The JDK's loader packages, plus any frame whose method is a
     * loader hook. The method-name rule is what catches custom loaders. A build tool's or
     * app server's loader sits in the delegation chain under its own package (a real JDK 25
     * run put {@code com.sun.tools.javac.launcher.MemoryClassLoader.loadClass} there), and
     * its delegation frames say nothing about what pulled the class in.
     */
    static boolean isMachinery(String frame) {
        if (frame.startsWith("java.lang.ClassLoader.")
                || frame.startsWith("java.security.SecureClassLoader.")
                || frame.startsWith("java.net.URLClassLoader.")
                || frame.startsWith("jdk.internal.loader.")) {
            return true;
        }
        int paren = frame.indexOf('(');
        String method = paren < 0 ? frame : frame.substring(0, paren);
        method = method.substring(method.lastIndexOf('.') + 1);
        return method.equals("loadClass") || method.equals("findClass") || method.equals("defineClass");
    }

    /**
     * The reflective APIs whose presence explains a missing static edge. Reflection frames
     * are deliberately kept as triggers. They are the answer to why the static walk missed
     * the edge, and when one tops the stack its first non-reflective caller is named too.
     */
    static boolean isReflective(String frame) {
        return frame.startsWith("java.lang.Class.forName")
                || frame.startsWith("java.util.ServiceLoader")
                || frame.startsWith("java.lang.reflect.")
                || frame.startsWith("jdk.internal.reflect.")
                || frame.startsWith("sun.reflect.")
                || frame.startsWith("java.lang.invoke.");
    }

    /** Escape for a TOML basic (double-quoted) string. */
    private static String tomlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Report owns the same mapping for the rendering. Kept local so the drafts do not wait
    // on it, and the two must keep naming classes identically.
    private static String dotted(String name) {
        return name.replace('/', '.');
    }

    /** The phrase both files uika writes to a draft path open with. */
    private static final String DRAFT_MARKER = "uika --draft-exclude-file";

    /**
     * Truncate the draft file before the check runs, mirroring {@code --verdicts-json}'s
     * create-upfront contract. A run that errors mid-scan leaves this marker, never a stale
     * draft from an earlier run for a human to review.
     */
    static void createDraftPlaceholder(String path) {
        // Path drops a trailing slash, so "new/" would otherwise become a regular file named new.
        if (path.endsWith("/")) {
            throw new UikaException("cannot write draft exclude file " + path + ": Is a directory (os error 21)");
        }
        Path file = Path.of(path);
        // Content, not path identity. The caller's real-path comparison misses a HARD LINK to
        // the exclude file.
        String existing = existingText(file, path);
        if (existing != null) {
            int newline = existing.indexOf('\n');
            String first = newline < 0 ? existing : existing.substring(0, newline);
            if (!Reach.trim(existing).isEmpty() && !first.contains(DRAFT_MARKER)) {
                throw new UikaException("refusing to overwrite " + path
                        + ": it was not written by --draft-exclude-file. Draft to a new path and merge.");
            }
        }
        // This runs BEFORE the scan, so a missing directory would fail the whole check
        // rather than just the write.
        String parent = parentOf(path);
        if (parent != null) {
            try {
                Files.createDirectories(Path.of(parent));
            } catch (IOException e) {
                throw new UikaException("cannot create directory " + parent + " for the draft exclude file", e);
            }
        }
        write(path, "# uika --draft-exclude-file: the check did not complete; no rules were drafted.\n");
    }

    /** The file's text, or null when no file is there. */
    private static String existingText(Path file, String path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            // A file it cannot read cannot be shown to be ours. A directory is left for the write to name.
            if (Files.isRegularFile(file)) {
                throw new UikaException("cannot read draft exclude file " + path, e);
            }
            return null;
        }
        // Lenient on purpose. A byte that is not UTF-8 decodes to U+FFFD, which is neither
        // blank nor the marker, so such a file is refused.
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The path as spelled minus its last name, or null when nothing is left to create. */
    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        while (slash > 1 && path.charAt(slash - 1) == '/') {
            slash--;
        }
        return slash == 0 ? "/" : path.substring(0, slash);
    }

    private static void write(String path, String content) {
        try {
            Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (Files.isDirectory(Path.of(path))) {
                throw new UikaException("cannot write draft exclude file " + path + ": Is a directory (os error 21)");
            }
            throw new UikaException("cannot write draft exclude file " + path, e);
        }
    }

    private record SymbolKey(String owner, String name, String descriptor) {
        static final Comparator<SymbolKey> ORDER = (a, b) -> {
            int c = Text.compareUtf8(a.owner, b.owner);
            if (c != 0) {
                return c;
            }
            if ((a.name == null) != (b.name == null)) {
                return a.name == null ? -1 : 1;
            }
            if (a.name == null) {
                return 0;
            }
            c = Text.compareUtf8(a.name, b.name);
            return c != 0 ? c : Text.compareUtf8(a.descriptor, b.descriptor);
        };
    }

    private static final class Users {
        boolean draftable = true;
        final TreeSet<String> classes = new TreeSet<>(Text::compareUtf8);
    }

    /**
     * Write draft {@code --exclude-file} rules for the symbols whose EVERY violation stayed
     * ⚠️ with no observed load. Grouped by the referenced symbol because that is what an
     * exclude rule matches. A symbol that also breaks a reachable (or observed) class must
     * not be drafted, since the rule would waive that real break too. A member-less rule is
     * wider still, {@link Exclude#filter} reads it as "the owner outright", member
     * violations included, so a class-level symbol is only drafted when every symbol on its
     * owner is draftable. Every reason opens with REVIEW and states exactly what the
     * evidence does and does not show. Returns the number of drafted rules. The file is
     * always written, so a requested draft is never silently absent.
     */
    static int draftExcludes(List<Violation> violations, Boolean appRootsMatched, LoadEvidence evidence, String path) {
        boolean axis = Tier.reachableAxisValid(appRootsMatched);
        // Drafted rules are ordered by symbol string value.
        TreeMap<SymbolKey, Users> bySymbol = new TreeMap<>(SymbolKey.ORDER);
        for (Violation v : violations) {
            boolean hasMember = v.reference.hasMember();
            SymbolKey key = new SymbolKey(
                    Intern.str(v.reference.owner()),
                    hasMember ? Intern.str(MemberKey.name(v.reference.member())) : null,
                    hasMember ? Intern.str(MemberKey.descriptor(v.reference.member())) : null);
            Users users = bySymbol.computeIfAbsent(key, k -> new Users());
            users.draftable &= Tier.of(v, axis) == Tier.UNPROVEN;
            users.classes.add(dotted(Intern.str(v.sourceClass)));
        }
        TreeSet<String> undraftableOwners = new TreeSet<>();
        for (Map.Entry<SymbolKey, Users> entry : bySymbol.entrySet()) {
            if (!entry.getValue().draftable) {
                undraftableOwners.add(entry.getKey().owner);
            }
        }

        String logs = evidence.sources();
        StringBuilder out = new StringBuilder();
        int drafted = 0;
        out.append("# Draft exclude rules generated by uika --draft-exclude-file.\n")
                .append("# Basis: no static path from the application reaches the referencing classes, and\n")
                .append("# none was observed loading in: ")
                .append(logs)
                .append('\n')
                .append("# Absence of a load entry proves nothing beyond the observed runs. Review each\n")
                .append("# entry and delete any you cannot justify before committing this file.\n");
        for (Map.Entry<SymbolKey, Users> entry : bySymbol.entrySet()) {
            SymbolKey symbol = entry.getKey();
            Users users = entry.getValue();
            if (!users.draftable || (symbol.name == null && undraftableOwners.contains(symbol.owner))) {
                continue;
            }
            drafted++;
            out.append('\n');
            out.append("[[exclude]]\n");
            out.append("owner = \"").append(tomlEscape(symbol.owner)).append("\"\n");
            if (symbol.name != null) {
                out.append("member = \"").append(tomlEscape(symbol.name)).append("\"\n");
                out.append("descriptor = \"").append(tomlEscape(symbol.descriptor)).append("\"\n");
            }
            List<String> shown = new ArrayList<>();
            for (String user : users.classes) {
                if (shown.size() == 3) {
                    break;
                }
                shown.add(user);
            }
            int more = users.classes.size() - shown.size();
            String list = String.join(", ", shown);
            if (more > 0) {
                list += " and " + more + " more";
            }
            out.append("reason = \"")
                    .append(tomlEscape("REVIEW: referenced only by " + list
                            + "; no static path from the application reaches them and none was observed loading in "
                            + logs))
                    .append("\"\n");
        }
        if (drafted == 0) {
            out.append("\n# Nothing to draft: every violation is reachable, observed loading, or latent.\n");
        }
        write(path, out.toString());
        return drafted;
    }
}
