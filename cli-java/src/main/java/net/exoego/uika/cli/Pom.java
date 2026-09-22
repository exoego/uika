package net.exoego.uika.cli;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads {@code <optional>} out of a dependency's POM, to keep upgrade-check advice honest.
 *
 * <p>When an upgrade drops a coordinate entirely, {@link Suggest} used to tell the user that
 * the referencing artifact "still needs it" and to upgrade to a release that no longer
 * requires it. For a dependency the referencing artifact declares
 * {@code <optional>true</optional>} both halves are wrong. It was never required transitively,
 * and a release dropping it will never exist because optional integrations (logging facades,
 * alternative serialization backends) are permanent design choices.
 * See https://github.com/exoego/uika/issues/96.
 *
 * <p>The POM read here is ALREADY on disk beside the JAR being scanned. No network and no
 * resolver, and a missing POM falls back to the original wording. Having the build plugins
 * resolve POM metadata into the dump would be authoritative, but it needs a dump-format change
 * plus every plugin implementation, and would not help the dumps already in CI caches.
 *
 * <p>This is a string scan on purpose. Its behaviour on malformed input is the specification
 * and was differential-tested over thousands of real POMs, so do not swap in an XML parser.
 * The Rust original cuts the text at byte offsets. Every cut here sits directly beside ASCII
 * markup, so a UTF-16 index never splits a surrogate pair. Blanked regions differ in length
 * from Rust when they hold non-ASCII text, which nothing can observe.
 */
final class Pom {
    private Pom() {}

    /**
     * Every {@code "group:name"} this POM declares optional without also requiring it
     * unconditionally.
     *
     * <p>One pass answers every coordinate, so the caller reads a POM once per referencing
     * artifact rather than once per removed coordinate.
     *
     * <p>Deliberately loose in two directions, because the caller only rewords advice and
     * never suppresses a violation.
     *
     * <ul>
     *   <li>A declaration inside a {@code <profile>} counts, and profile activation is not
     *       evaluated. google-auth declares its slf4j-api optional inside an
     *       {@code activeByDefault} profile, which is the shape that motivated this.
     *   <li>Inherited declarations are not followed. A parent POM declaring the dependency
     *       optional reads as not-optional here, which keeps the original wording. That is
     *       the safe direction.
     * </ul>
     *
     * <p>It is NOT loose about a coordinate declared both ways. An always-active declaration
     * that is not optional settles it, because profile looseness is otherwise the
     * false-positive direction. netty-transport-native-epoll requires
     * netty-transport-native-unix-common at top level and additionally declares the
     * classifier-ed native variant optional inside its OS profiles.
     *
     * <p>Namespace-PREFIXED element names ({@code <m:dependency>}) are not matched, so such a
     * POM reads as not-optional. That is the safe direction, and Maven tooling writes the
     * default namespace.
     */
    static Set<String> optionalDependencies(String pom) {
        // <dependencyManagement> sets versions for dependencies declared elsewhere and a
        // plugin's <dependencies> belongs to the plugin, so neither is a dependency of this
        // artifact. Blanking rather than collecting spans is what makes an unterminated one
        // swallow the remainder, the direction that reads as not-optional.
        String text = blankElement(blankUninterpreted(pom), "dependencyManagement");
        text = blankElement(text, "plugins");

        Set<String> optional = declarations(text, true);
        if (optional.isEmpty()) {
            return optional;
        }
        // Second pass over the always-active declarations only. <profiles> is blanked rather
        // than span-tested for the same reason as above.
        optional.removeAll(declarations(blankElement(text, "profiles"), false));
        return optional;
    }

    /** Whether {@code pom} declares {@code group:name} as an optional dependency. */
    static boolean declaresOptional(String pom, String group, String name) {
        return optionalDependencies(pom).contains(group + ":" + name);
    }

    /**
     * The {@code "group:name"} of every {@code <dependency>} in {@code text} whose
     * {@code <optional>} is {@code true} when {@code wantOptional}, or is not, when it is false.
     *
     * <p>A block carrying a {@code <classifier>} is skipped either way. A classifier names a
     * different artifact file, so its optionality says nothing about the plain coordinate.
     */
    static Set<String> declarations(String text, boolean wantOptional) {
        Set<String> found = new HashSet<>();
        int cursor = 0;
        while (true) {
            int start = findElement(text, cursor, "dependency");
            if (start < 0) {
                break;
            }
            int end = elementEnd(text, start, "dependency");
            if (end < 0) {
                break;
            }
            cursor = end;
            // An <exclusion> carries its own groupId/artifactId, which would otherwise be read
            // as the dependency's own coordinate. Blanked rather than truncated at, because
            // <optional> is free to come after the exclusions and netty puts it there.
            String block = blankElement(text.substring(start, end), "exclusions");
            String classifier = childText(block, "classifier");
            if ((classifier != null && !classifier.isEmpty())
                    || "true".equals(childText(block, "optional")) != wantOptional) {
                continue;
            }
            String group = childText(block, "groupId");
            String name = childText(block, "artifactId");
            if (group != null && name != null) {
                found.add(group + ":" + name);
            }
        }
        return found;
    }

    /**
     * The POM sitting next to {@code jar} in a local artifact cache, or null.
     *
     * <p>Two layouts are probed, which together cover the caches uika actually scans against.
     * The POM as a direct sibling of the JAR (Maven {@code ~/.m2}, Coursier), and the POM one
     * directory over (Gradle's {@code modules-2/files-2.1/<g>/<n>/<v>/<sha1>/}, which gives
     * each artifact of a version its own checksum directory).
     *
     * <p>The sibling-directory walk requires the two directories above {@code jar} to spell
     * {@code <n>/<v>}, because the file name alone does not identify an artifact. Without the
     * check, a jar in any flat {@code lib/} directory answers with a same-named POM from a
     * neighbouring directory that belongs to a different group. It also makes the walk
     * Gradle-only, which is where it pays. In a Maven layout the sibling probe has already
     * covered the one path it could match.
     */
    static String locate(String jar, String name, String version) {
        String fileName = name + "-" + version + ".pom";
        try {
            // A bare file name stops here. Probing it would make the sibling read relative to
            // the CWD, and a stray POM in whatever directory the CLI was invoked from must not
            // decide the advice.
            Path dir = withoutInnerDots(Path.of(jar)).getParent();
            if (dir == null) {
                return null;
            }
            Path sibling = dir.resolve(fileName);
            if (Files.isRegularFile(sibling)) {
                return sibling.toString();
            }

            Path versionDir = dir.getParent();
            if (versionDir == null || !isNamed(versionDir, version) || !isNamed(versionDir.getParent(), name)) {
                return null;
            }
            // Lowest path wins rather than whatever the listing yields first. Two checksum
            // directories can both hold the POM, and the advice built from it is part of the
            // report's grouping key. Candidates differ only in the checksum directory, and
            // Rust orders paths per component, so that one name is what gets compared.
            Path found = null;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(versionDir)) {
                for (Path entry : entries) {
                    if (entry.equals(dir)) {
                        continue;
                    }
                    Path candidate = entry.resolve(fileName);
                    if (Files.isRegularFile(candidate)
                            && (found == null || Text.compareUtf8(checksumDir(candidate), checksumDir(found)) < 0)) {
                        found = candidate;
                    }
                }
            } catch (IOException | DirectoryIteratorException e) {
                // A listing that fails part way keeps what it has, like Rust's flatten().
            }
            return found == null ? null : found.toString();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String checksumDir(Path candidate) {
        return candidate.getParent().getFileName().toString();
    }

    /**
     * Rust's {@code Path} drops every {@code .} component except a leading one before it
     * answers {@code parent()} or {@code file_name()}. {@link Path} keeps them, which would
     * shift the {@code <n>/<v>} check by one directory.
     */
    private static Path withoutInnerDots(Path path) {
        Path out = path.getRoot();
        for (int i = 0; i < path.getNameCount(); i++) {
            Path part = path.getName(i);
            if (out != null && part.toString().equals(".")) {
                continue;
            }
            out = out == null ? part : out.resolve(part);
        }
        return out == null ? path : out;
    }

    /** Rust's {@code file_name()} is None for a path ending in {@code ..}, so that never matches. */
    private static boolean isNamed(Path dir, String expected) {
        if (dir == null || dir.getFileName() == null) {
            return false;
        }
        String last = dir.getFileName().toString();
        return !last.equals("..") && last.equals(expected);
    }

    private static final String[][] REGIONS = {{"<!--", "-->"}, {"<![CDATA[", "]]>"}, {"<?", "?>"}};

    /**
     * Blanks the regions whose contents are not markup, which are {@code <!-- ... -->},
     * {@code <![CDATA[ ... ]]>} and {@code <? ... ?>}. Each can legally contain something that
     * reads like a dependency declaration. A POM's {@code <description>} is free to wrap
     * example XML in CDATA, and scanning it would be a way this file reports optional for a
     * dependency the artifact never declared. A DTD internal subset can carry the same thing
     * inside an {@code <!ENTITY>}, but modern Maven rejects a DOCTYPE outright, so it is not
     * blanked.
     *
     * <p>Blanking rather than deleting keeps every other offset in the result equal to the
     * same offset in the input, so removing a region can never splice two neighbouring
     * elements together.
     */
    static String blankUninterpreted(String pom) {
        char[] out = null;
        int rest = 0;
        while (true) {
            // Whichever region opens first, so a "<!--" inside CDATA (and vice versa) cannot
            // reopen a region that is already skipped.
            int open = -1;
            String[] region = null;
            for (String[] candidate : REGIONS) {
                int at = pom.indexOf(candidate[0], rest);
                if (at >= 0 && (open < 0 || at < open)) {
                    open = at;
                    region = candidate;
                }
            }
            if (region == null) {
                break;
            }
            int close = pom.indexOf(region[1], open + region[0].length());
            // Unterminated means everything from here on is inside the region.
            int end = close < 0 ? pom.length() : close + region[1].length();
            if (out == null) {
                out = pom.toCharArray();
            }
            Arrays.fill(out, open, end, ' ');
            rest = end;
        }
        return out == null ? pom : new String(out);
    }

    /**
     * Index of the next {@code <tag>} open tag in {@code text} at or after {@code from}, or -1.
     * Matches {@code <tag>} and {@code <tag attr=..>} but not {@code <tagFoo>}, and ignores
     * self-closing {@code <tag/>} (an empty element declares nothing).
     *
     * <p>The end of the attribute list is the first {@code >}, and XML permits an unescaped
     * {@code >} inside an attribute value (only {@code <} and {@code &} are forbidden), so
     * {@code <dependency a="x>" />} reads as opening rather than self-closing. Known and left
     * alone. It merges an empty element into the next block, which needs a contrived POM to
     * reach an {@code <optional>}.
     */
    static int findElement(String text, int from, String tag) {
        String open = "<" + tag;
        int i = from;
        while (true) {
            int at = text.indexOf(open, i);
            if (at < 0) {
                return -1;
            }
            int after = at + open.length();
            char c = after < text.length() ? text.charAt(after) : 0;
            if (c == '>') {
                return at;
            }
            if (isAsciiWhitespace(c)) {
                // An attribute list still opens the element unless it self-closes.
                int close = text.indexOf('>', after);
                if (close < 0) {
                    return -1;
                }
                if (text.charAt(close - 1) != '/') {
                    return at;
                }
                i = close;
            } else {
                i = after;
            }
        }
    }

    /** Rust's {@code u8::is_ascii_whitespace}, which leaves out the vertical tab. */
    private static boolean isAsciiWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    /**
     * Index just past the {@code </tag>} matching the element at {@code from}, counting nested
     * opens of the same tag, or -1.
     */
    static int elementEnd(String text, int from, String tag) {
        String close = "</" + tag + ">";
        int depth = 0;
        int i = from;
        while (true) {
            int nextOpen = findElement(text, i, tag);
            int nextClose = text.indexOf(close, i);
            if (nextClose < 0) {
                return -1;
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                i = nextOpen + 1 + tag.length();
            } else if (depth <= 1) {
                // depth 1 is the element's own open tag. depth 0 means findElement never
                // matched it (a leading <tag/>, or an attribute list with no >), so bound at
                // the first close.
                return nextClose + close.length();
            } else {
                depth--;
                i = nextClose + close.length();
            }
        }
    }

    /**
     * Text of the first {@code <tag>} inside {@code block}, trimmed, or null. Nesting is not
     * tracked, which is fine for the leaf elements read here ({@code groupId},
     * {@code artifactId}, {@code classifier}, {@code optional}) once the caller has blanked
     * {@code <exclusions>}.
     */
    static String childText(String block, String tag) {
        int open = findElement(block, 0, tag);
        if (open < 0) {
            return null;
        }
        int tagEnd = block.indexOf('>', open);
        int close = block.indexOf("</" + tag + ">", tagEnd + 1);
        if (close < 0) {
            return null;
        }
        return Reach.trim(block.substring(tagEnd + 1, close));
    }

    /** {@code text} with every {@code <tag>...</tag>} region replaced by spaces, preserving length. */
    static String blankElement(String text, String tag) {
        // Rust searches the text it is blanking. Searching the input is the same, because the
        // scan never looks behind the cursor and everything blanked so far is behind it.
        char[] out = null;
        int cursor = 0;
        while (true) {
            int start = findElement(text, cursor, tag);
            if (start < 0) {
                break;
            }
            // An unclosed element swallows the remainder. Losing a later <optional> reads as
            // not-optional, which keeps the original advice. That is the safe direction on a
            // malformed POM.
            int end = elementEnd(text, start, tag);
            if (end < 0) {
                end = text.length();
            }
            if (out == null) {
                out = text.toCharArray();
            }
            Arrays.fill(out, start, end, ' ');
            cursor = end;
        }
        return out == null ? text : new String(out);
    }
}
