package net.exoego.uika.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JDK API model, serving two opt-in features.
 *
 * <p>{@code --jdk-release N} layers one release under both resolution scopes, so hierarchy
 * escapes into JDK types conclude instead of ending Unknown. {@code --jdk-release-old N
 * --jdk-release-new M} makes the JDK upgrade itself the compared pair.
 *
 * <p>ct.sym ships with every JDK and holds API stub class files for historical releases;
 * the stubs are regular class files. The running JDK's own release is not in ct.sym, so it
 * comes from jmods instead.
 *
 * <p>ct.sym entry layouts: 12+ is {@code <codes>/<module>/<binary/name>.sig} (module dirs
 * contain a '.'); 9-11 is {@code <codes>/<binary/name>.sig}; 8 is unsupported. Codes are
 * base-36 digits, one per release, and real files keep '6'/'7' in joint dirs, so the codes
 * charset accepts ALL base-36 digits even though only 8 to 35 are selectable.
 */
final class Jdk {
    static final int MIN_RELEASE = 8;
    static final int MAX_RELEASE = 35;

    private Jdk() {}

    static char releaseCode(int release) {
        if (release < MIN_RELEASE || release > MAX_RELEASE) {
            return 0;
        }
        return Character.toUpperCase(Character.forDigit(release, 36));
    }

    /** The release a code digit names, or -1. */
    static int codeRelease(char code) {
        int value = Character.digit(code, 36);
        return value >= 8 ? value : -1;
    }

    /** Keeps joint dirs containing '6'/'7' while rejecting "9-modules", "META-INF" and the like. */
    static boolean isCodesDir(String codes) {
        if (codes.isEmpty()) {
            return false;
        }
        for (int i = 0; i < codes.length(); i++) {
            char c = codes.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'A' && c <= 'Z')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits one ct.sym entry into {codes, class name}, or null for anything that is not a
     * class stub (directories, module-info, system-modules, the JDK 8 layout).
     */
    static String[] parseEntry(String entry) {
        int slash = entry.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String codes = entry.substring(0, slash);
        if (!isCodesDir(codes)) {
            return null;
        }
        String rest = entry.substring(slash + 1);
        String classPath = rest;
        int next = rest.indexOf('/');
        if (next >= 0 && rest.substring(0, next).indexOf('.') >= 0) {
            classPath = rest.substring(next + 1);
        }
        if (!classPath.endsWith(".sig")) {
            return null;
        }
        String name = classPath.substring(0, classPath.length() - 4);
        if (name.equals("module-info")) {
            return null;
        }
        return new String[] {codes, name};
    }

    /** The ct.sym file inside a JDK home (or the path itself when it is a file), or null. */
    static Path ctSymIn(Path home) {
        if (Files.isRegularFile(home)) {
            return home;
        }
        Path ctSym = home.resolve("lib").resolve("ct.sym");
        return Files.isRegularFile(ctSym) ? ctSym : null;
    }

    /**
     * UIKA_JDK is authoritative when set: a bad explicit pin surfaces as an error instead of
     * silently analyzing against a different JDK's data. JAVA_HOME is the fallback.
     */
    static Path findHome() {
        for (String variable : new String[] {"UIKA_JDK", "JAVA_HOME"}) {
            String value = Env.get(variable);
            if (value != null && !value.isEmpty()) {
                return Path.of(value);
            }
        }
        return null;
    }

    static Path findCtSym() {
        Path home = findHome();
        return home == null ? null : ctSymIn(home);
    }

    /** The feature version of the JDK at {@code home}, from its {@code release} file, or -1. */
    static int installedFeature(Path home) {
        List<String> lines;
        try {
            lines = Files.readAllLines(home.resolve("release"), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        for (String line : lines) {
            if (!line.startsWith("JAVA_VERSION=")) {
                continue;
            }
            String value = line.substring("JAVA_VERSION=".length());
            while (value.startsWith("\"")) {
                value = value.substring(1);
            }
            while (value.endsWith("\"")) {
                value = value.substring(0, value.length() - 1);
            }
            // 1.8.0_x for 8, otherwise the leading component is the feature version.
            String first = value.split("[._-]", -1)[0];
            try {
                int n = Integer.parseUnsignedInt(first);
                if (n != 1) {
                    return n;
                }
                String[] dotted = value.split("\\.", -1);
                return dotted.length > 1 ? Integer.parseUnsignedInt(dotted[1]) : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** The indexer for {@code --jdk-release}, or null when the flag was not given. */
    static Indexer indexerFor(Integer release) {
        if (release == null) {
            return null;
        }
        Path ctSym = findCtSym();
        if (ctSym == null) {
            String pinned = Env.get("UIKA_JDK");
            String hint = pinned != null && !pinned.isEmpty()
                    ? "UIKA_JDK is set to " + pinned + " but it is not a ct.sym file and has no lib/ct.sym"
                    : "set UIKA_JDK to a JDK home or a ct.sym file (checked first), or JAVA_HOME to a JDK home";
            throw new UikaException("--jdk-release " + release + " needs a JDK: " + hint);
        }
        return Indexer.open(ctSym, release);
    }

    /**
     * The whole API of one JDK release, for using a JDK upgrade as the checked pair. Older
     * than the running JDK comes from ct.sym; the running JDK's own release from jmods, which
     * is a SUPERSET (unexported internals included). That only errs toward silence while it
     * is the NEW side; as the OLD side against a ct.sym new side it would invent removals.
     */
    static ApiIndex releaseIndex(int release, List<String> warnings) {
        Path home = findHome();
        if (home == null) {
            throw new UikaException("--jdk-release-old/--jdk-release-new need a JDK: set UIKA_JDK to a JDK home "
                    + "(checked first) or JAVA_HOME");
        }
        if (installedFeature(home) == release) {
            return jmodsIndex(home, release, warnings);
        }
        Path ctSym = ctSymIn(home);
        if (ctSym == null) {
            throw new UikaException("no lib/ct.sym under " + home + " and its release is not " + release);
        }
        try (Indexer indexer = Indexer.open(ctSym, release)) {
            return indexer.fetchAll(warnings);
        }
    }

    /** Whether {@code release} is the running JDK's own, and so served from jmods. */
    static boolean isInstalledRelease(int release) {
        Path home = findHome();
        return home != null && installedFeature(home) == release;
    }

    private static ApiIndex jmodsIndex(Path home, int release, List<String> warnings) {
        Path dir = home.resolve("jmods");
        List<Path> jmods = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (p.getFileName().toString().endsWith(".jmod")) {
                    jmods.add(p);
                }
            }
        } catch (IOException e) {
            throw new UikaException(
                    "release " + release + " is this JDK's own, which ct.sym never carries, so it must come from " + dir
                            + " (absent in a JRE or a jlink'd runtime)",
                    e);
        }
        // Deterministic first-wins across modules, and deterministic warnings.
        Collections.sort(jmods);
        ApiIndex index = new ApiIndex();
        Input.onPool(() -> {
            for (Path path : jmods) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    List<ZipEntry> classes = new ArrayList<>();
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith("classes/") && name.endsWith(".class") && !name.endsWith("module-info.class")) {
                            classes.add(entry);
                        }
                    }
                    // A release is tens of thousands of classes, so the stubs of one module
                    // are read and parsed in parallel. Inserted in entry order all the same.
                    ClassApi[] apis = new ClassApi[classes.size()];
                    String[] failures = new String[classes.size()];
                    List<java.util.concurrent.RecursiveAction> tasks = new ArrayList<>();
                    int step = 64;
                    for (int from = 0; from < apis.length; from += step) {
                        int start = from;
                        int end = Math.min(apis.length, from + step);
                        tasks.add(new java.util.concurrent.RecursiveAction() {
                            private static final long serialVersionUID = 1L;

                            @Override
                            protected void compute() {
                                Scratch scratch = Scratch.current();
                                for (int i = start; i < end; i++) {
                                    ZipEntry entry = classes.get(i);
                                    try {
                                        int length = Input.readEntry(zip, entry, scratch);
                                        scratch.parser.parse(scratch.classBytes, length);
                                        ClassApi api = Extract.extractApi(scratch.parser, scratch);
                                        levelToCtSymFidelity(api);
                                        apis[i] = api;
                                    } catch (IOException | ClassParser.FormatException e) {
                                        failures[i] = path + "!" + entry.getName() + ": " + UikaException.describe(e);
                                    }
                                }
                            }
                        });
                    }
                    java.util.concurrent.ForkJoinTask.invokeAll(tasks);
                    for (int i = 0; i < apis.length; i++) {
                        if (apis[i] != null) {
                            index.insertIfAbsent(apis[i]);
                        } else {
                            warnings.add(failures[i]);
                        }
                    }
                } catch (IOException e) {
                    throw new UikaException("not a zip: " + path, e);
                }
            }
        });
        return index;
    }

    /**
     * Drops what a jmods class file carries and a ct.sym stub does not. Stubs strip
     * PermittedSubclasses (java.lang.constant.ConstantDesc has been sealed since 12 and its
     * stub has none), so keeping it would report every sealed JDK class as newly sealed.
     * NestHost IS in stubs, so it stays.
     */
    static void levelToCtSymFidelity(ClassApi api) {
        api.permitted = null;
    }

    /**
     * Lazily fetching view of one release inside ct.sym. The archive stays open: the central
     * directory is parsed once and closure levels read entries by name.
     */
    static final class Indexer implements AutoCloseable {
        private final ZipFile archive;
        /** Class name ("java/lang/String") to ct.sym entry path. Deliberately not interned. */
        private final Map<String, String> entries;

        private Indexer(ZipFile archive, Map<String, String> entries) {
            this.archive = archive;
            this.entries = entries;
        }

        static Indexer open(Path ctSym, int release) {
            char code = releaseCode(release);
            if (code == 0) {
                throw new UikaException(
                        "unsupported --jdk-release " + release + " (not between " + MIN_RELEASE + " and " + MAX_RELEASE + ")");
            }
            if (!Files.exists(ctSym)) {
                throw new UikaException("cannot open ct.sym: " + ctSym, new java.nio.file.NoSuchFileException(ctSym.toString()));
            }
            if (!Files.isReadable(ctSym)) {
                throw new UikaException("cannot open ct.sym: " + ctSym, new java.nio.file.AccessDeniedException(ctSym.toString()));
            }
            ZipFile archive;
            try {
                archive = new ZipFile(ctSym.toFile());
            } catch (IOException e) {
                throw new UikaException("not a zip: " + ctSym, e);
            }
            Map<String, String> entries = new HashMap<>();
            TreeSet<Integer> releases = new TreeSet<>();
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements()) {
                String entry = all.nextElement().getName();
                String[] parsed = parseEntry(entry);
                if (parsed == null) {
                    continue;
                }
                if (parsed[0].indexOf(code) >= 0) {
                    entries.put(parsed[1], entry);
                } else if (entries.isEmpty()) {
                    for (int i = 0; i < parsed[0].length(); i++) {
                        int r = codeRelease(parsed[0].charAt(i));
                        if (r >= 0) {
                            releases.add(r);
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                try {
                    archive.close();
                } catch (IOException ignored) {
                    // the real error follows
                }
                if (releases.isEmpty()) {
                    throw new UikaException(
                            "no release-coded API stubs found in " + ctSym + " (pre-JDK-9 ct.sym layout, or not a ct.sym)");
                }
                StringBuilder available = new StringBuilder();
                for (int r : releases) {
                    if (available.length() > 0) {
                        available.append(", ");
                    }
                    available.append(r);
                }
                throw new UikaException("release " + release + " not present in " + ctSym + " (available: " + available
                        + "; the installed JDK's own release is served from its runtime image, not ct.sym, so pick an older one)");
            }
            return new Indexer(archive, entries);
        }

        /** Every stub of the opened release, for JDK-pair mode: the whole release IS the library. */
        ApiIndex fetchAll(List<String> warnings) {
            IntSet roots = new IntSet(entries.size());
            for (String name : entries.keySet()) {
                roots.add(Intern.intern(name));
            }
            return fetchClosure(roots, warnings);
        }

        /**
         * An index holding the transitive super/interface closure of {@code roots}, reading
         * only the needed stubs. Roots outside ct.sym are skipped (they stay Unknown). Read
         * and parse failures become warnings; the class is then simply absent, which the
         * old-relative gate keeps conservative.
         */
        ApiIndex fetchClosure(IntSet roots, List<String> warnings) {
            ApiIndex index = new ApiIndex();
            IntSet requested = new IntSet();
            List<Integer> pending = new ArrayList<>();
            for (int root : roots.toArray()) {
                if (entries.containsKey(Intern.str(root)) && requested.add(root)) {
                    pending.add(root);
                }
            }
            while (!pending.isEmpty()) {
                // Fetched in string order: symbol ids vary run to run, and the warning
                // order is user-visible.
                pending.sort(Intern::compare);
                List<Integer> level = pending;
                pending = new ArrayList<>();
                // Stubs are small, and a whole release is thousands of them, so a level is
                // read and parsed in parallel and folded in afterwards in order.
                ClassApi[] apis = new ClassApi[level.size()];
                String[] failures = new String[level.size()];
                List<java.util.concurrent.RecursiveAction> tasks = new ArrayList<>();
                int step = 64;
                for (int from = 0; from < apis.length; from += step) {
                    int start = from;
                    int end = Math.min(apis.length, from + step);
                    tasks.add(new java.util.concurrent.RecursiveAction() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        protected void compute() {
                            Scratch scratch = Scratch.current();
                            for (int i = start; i < end; i++) {
                                String text = Intern.str(level.get(i));
                                try {
                                    ZipEntry entry = archive.getEntry(entries.get(text));
                                    int length = Input.readEntry(archive, entry, scratch);
                                    scratch.parser.parse(scratch.classBytes, length);
                                    apis[i] = Extract.extractApi(scratch.parser, scratch);
                                } catch (IOException | ClassParser.FormatException e) {
                                    failures[i] = "ct.sym!" + text + ": " + UikaException.describe(e);
                                }
                            }
                        }
                    });
                }
                Input.onPool(() -> java.util.concurrent.ForkJoinTask.invokeAll(tasks));
                for (int i = 0; i < apis.length; i++) {
                    ClassApi api = apis[i];
                    if (api == null) {
                        warnings.add(failures[i]);
                        continue;
                    }
                    if (api.superName != Intern.NONE) {
                        request(api.superName, requested, pending);
                    }
                    for (int iface : api.interfaces) {
                        request(iface, requested, pending);
                    }
                    index.insertIfAbsent(api);
                }
            }
            return index;
        }

        private void request(int next, IntSet requested, List<Integer> pending) {
            if (next != Scope.objectSym() && entries.containsKey(Intern.str(next)) && requested.add(next)) {
                pending.add(next);
            }
        }

        @Override
        public void close() {
            try {
                archive.close();
            } catch (IOException ignored) {
                // read-only archive
            }
        }
    }
}
