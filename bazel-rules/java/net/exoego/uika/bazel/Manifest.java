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
import java.util.LinkedHashSet;
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
        var modules = new ArrayList<Module>();
        Builder current = null;
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            var fields = line.split("\t", -1);
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
            var candidate = base.resolve(path).normalize();
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
        var bases = new ArrayList<Path>();
        var cwd = Paths.get("").toAbsolutePath();
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

    /**
     * The real absolute path of an execution-root-relative entry, which is what a sweep
     * fragment names.
     *
     * <p>Two bases, tried in order, for the same reason {@link #resolveRunfile} tries
     * several. Most entries sit under the execution root, but an external repository reaches
     * it only through a symlink Bazel replants on every invocation, keeping the repositories
     * that invocation needs and pruning the rest. The merge is itself an invocation, so it
     * prunes repositories the sweep build had. The output base holds those repositories and
     * is never replanted, so it serves as the second base.
     *
     * <p>What this reaches is a jar that is a SOURCE file in an external module, such as a
     * {@code java_import} of a checked-in jar in a module you depend on, whose fragment path
     * is a bare {@code external/<repo>/...}. rules_jvm_external is not affected, because its
     * processed jars sit under {@code bazel-out} and the execution root's {@code bazel-out}
     * is a real directory rather than part of the forest. Measured end to end on 9.2.0.
     *
     * <p>{@code toRealPath} for the reason {@link #resolveRunfile} gives. Neither integration
     * workspace produces a bare {@code external/} path, so neither can reach this.
     */
    static Path resolveExecroot(Path execroot, String path) {
        for (Path base : executionBases(execroot)) {
            var candidate = base.resolve(path).normalize();
            if (Files.exists(candidate)) {
                try {
                    return candidate.toRealPath();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("cannot find " + path + " under " + execroot
                + "; build the sweep and run the merge against the same output base, and"
                + " pass every configuration flag the sweep build used to `bazel info`");
    }

    private static List<Path> executionBases(Path execroot) {
        var bases = new ArrayList<Path>();
        bases.add(execroot);
        // <output base>/execroot/<workspace name>, so the output base is two levels up.
        var workspaceDir = execroot.getParent();
        if (workspaceDir != null && workspaceDir.getParent() != null) {
            bases.add(workspaceDir.getParent());
        }
        return bases;
    }

    /**
     * Root prefixes worth collapsing in the dump: the directory each external repository's
     * jars sit under. The v2 root table shortens every path under them, and an external repo
     * path is long because it carries the whole download URL.
     */
    static List<String> externalRoots(List<Module> modules) {
        var roots = new LinkedHashSet<String>();
        for (Module module : modules) {
            for (Artifact artifact : module.artifacts()) {
                var external = artifact.file().indexOf("/external/");
                if (external >= 0) {
                    roots.add(artifact.file().substring(0, external + "/external/".length()));
                }
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * The value of the flag at {@code index}, rejecting both a trailing flag and an empty one.
     * The documented recipe substitutes {@code $(bazel info ...)} into these, so a failed
     * {@code bazel info} arrives here as an empty string, and {@code Path.of("")} is a
     * perfectly good relative path that would silently resolve every entry against the
     * working directory.
     */
    static String flagValue(String[] args, int index) {
        if (index >= args.length || args[index].isEmpty()) {
            throw new IllegalArgumentException("missing value for " + args[index - 1]);
        }
        return args[index];
    }

    private static Integer release(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--jdkRelease wants a whole number, got " + value);
        }
    }

    /** The {@code --jdkRelease} value, named in the error rather than left to {@code valueOf}. */
    static Integer flagRelease(String[] args, int index) {
        String raw = flagValue(args, index);
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--jdkRelease wants a whole number, got " + raw);
        }
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
