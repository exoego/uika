package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.UikaCli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reads the tab-separated manifest that a {@code uika_dump} or {@code uika_upgrade_check}
 * target builds, resolving every runfiles path to the real file it points at.
 *
 * <p>Line-oriented rather than JSON so this side needs no JSON parser. jvm-plugin-core
 * deliberately has none, because every other front end borrows one from its build tool and
 * Bazel would otherwise be the only reason to add a dependency here.
 */
final class Manifest {
    private Manifest() {}

    /**
     * The modules a manifest describes.
     *
     * <p>{@code resolve} turns a path field into the file it names. A manifest built for a
     * {@code bazel run} target names runfiles, and one built by the sweep aspect names
     * execution-root-relative paths, which is the only difference between the two.
     *
     * <p>A release-only manifest (the one {@code uika_upgrade_check} builds) carries no
     * {@code classes} or {@code dep} lines, so the modules come back with empty lists and
     * only their release filled in. That is the whole point of reusing this parser for it.
     */
    static List<Module> parse(Path manifest, Integer override, Function<String, Path> resolve)
            throws IOException {
        List<Module> modules = new ArrayList<>();
        Builder current = null;
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields[0].equals("module")) {
                if (current != null) {
                    modules.add(current.build(override));
                }
                current = new Builder(fields[1]);
                continue;
            }
            if (current == null) {
                throw new IllegalArgumentException("manifest line before any module: " + line);
            }
            switch (fields[0]) {
                case "toolchain" -> current.toolchainRelease = fields[1];
                case "javacopt" -> current.javacopts.add(fields[1]);
                case "classes" -> current.classes.add(resolve.apply(fields[1]).toString());
                case "dep" -> current.artifacts.add(new Artifact(
                        emptyToNull(fields[1]),
                        emptyToNull(fields[2]),
                        emptyToNull(fields[3]),
                        resolve.apply(fields[5]).toString(),
                        emptyToNull(fields[4])));
                default -> throw new IllegalArgumentException("unknown manifest line: " + line);
            }
        }
        if (current != null) {
            modules.add(current.build(override));
        }
        return modules;
    }

    private static final class Builder {
        private final String label;
        private final List<String> javacopts = new ArrayList<>();
        private final List<String> classes = new ArrayList<>();
        private final List<Artifact> artifacts = new ArrayList<>();
        private String toolchainRelease;

        Builder(String label) {
            this.label = label;
        }

        Module build(Integer override) {
            return new Module(label, classes, artifacts, release(override));
        }

        /**
         * The API release this target compiles for: its own {@code javacopts} if they pin
         * one, else the Java toolchain's target version. Read the spelling that pins the
         * API, never the one that names the compiler -- Bazel's ordinary shape runs a recent
         * toolchain against an older target, just as Gradle's does. An explicit override
         * replaces both, because it is a statement about the whole build.
         */
        private Integer release(Integer override) {
            if (override != null) {
                return override;
            }
            Integer declared = UikaCli.declaredRelease(javacopts);
            return declared != null ? declared : UikaCli.parseRelease(toolchainRelease);
        }
    }

    /**
     * The real absolute path of a runfiles entry.
     *
     * <p>Two path conventions reach this method. A jar arrives as a Starlark {@code
     * short_path}, which is {@code pkg/file} in the main repository and {@code ../repo/file}
     * elsewhere, so it resolves against the working directory of a {@code bazel run} (the
     * main repository's runfiles directory). A file named in a {@code jvm_flags} entry
     * arrives as an {@code rlocationpath}, which always carries the repository prefix and so
     * resolves one level up. Rather than track which is which, try the bases in order and
     * take the one that exists.
     *
     * <p>{@code toRealPath} is what makes the result usable at all. The runfiles entry is a
     * symlink into the output base or an external repository, and only its target survives
     * the next build.
     */
    static Path resolveRunfile(String path) {
        for (Path base : runfilesBases()) {
            Path candidate = base.resolve(path).normalize();
            if (Files.exists(candidate)) {
                try {
                    return candidate.toRealPath();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("cannot find " + path + " in the runfiles of this target;"
                + " run it with `bazel run`, not by executing the launcher directly");
    }

    private static List<Path> runfilesBases() {
        List<Path> bases = new ArrayList<>();
        Path cwd = Paths.get("").toAbsolutePath();
        bases.add(cwd);
        if (cwd.getParent() != null) {
            bases.add(cwd.getParent());
        }
        for (String variable : new String[] {"RUNFILES_DIR", "JAVA_RUNFILES"}) {
            String value = System.getenv(variable);
            if (value != null && !value.isEmpty()) {
                bases.add(Paths.get(value));
            }
        }
        return bases;
    }

    /**
     * Where a path given on the command line lands. A relative one resolves against the
     * workspace the user ran from, never the runfiles directory that happens to be the
     * working directory of a {@code bazel run}. Writing into the runfiles tree would put the
     * file somewhere the next build is free to delete.
     */
    static Path workspacePath(String path) {
        String workspace = System.getenv("BUILD_WORKSPACE_DIRECTORY");
        Path resolved = Paths.get(path);
        if (resolved.isAbsolute() || workspace == null) {
            return resolved.toAbsolutePath();
        }
        return Paths.get(workspace).resolve(resolved);
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
