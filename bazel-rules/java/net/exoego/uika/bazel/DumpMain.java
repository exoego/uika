package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
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
import java.util.Set;

/**
 * Turns the classpath manifest a {@code uika_dump} target builds into a uika v2 dump.
 *
 * <p>Run by {@code bazel run //:your_dump}, never as a build action. That is the whole point:
 * a dump names absolute paths, and an action's output is cacheable and may be replayed on
 * another machine or into another output base, so an action must never write one. Under
 * {@code bazel run} the classpath is laid out as a runfiles tree of symlinks, and resolving
 * each one gives the real absolute path with no {@code bazel info execution_root} guessing.
 */
public final class DumpMain {
    private DumpMain() {}

    public static void main(String[] args) throws IOException {
        Path manifest = resolveRunfile(required("uika.manifest"));
        Integer override = UikaCli.overrideRelease(Integer.getInteger("uika.jdkRelease", 0));

        String output = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> output = args[++i];
                case "--jdkRelease" -> override = UikaCli.overrideRelease(Integer.valueOf(args[++i]));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        List<Module> modules = parse(manifest, override);
        Path target = outputPath(output);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(
                target,
                DumpFormat.writeV2(modules, preferredRoots(modules), DumpFormat.dumpRelease(modules)),
                StandardCharsets.UTF_8);
        System.out.println("uika classpath dump: " + target);
    }

    /**
     * Where a {@code --output} lands. A relative path resolves against the workspace the user
     * ran from, never the runfiles directory that happens to be the working directory of a
     * {@code bazel run}. Writing into the runfiles tree would put the dump somewhere the next
     * build is free to delete.
     */
    private static Path outputPath(String output) {
        String workspace = System.getenv("BUILD_WORKSPACE_DIRECTORY");
        Path path = Paths.get(output == null ? "uika/classpath.json" : output);
        if (path.isAbsolute() || workspace == null) {
            return path.toAbsolutePath();
        }
        return Paths.get(workspace).resolve(path);
    }

    private static List<Module> parse(Path manifest, Integer override) throws IOException {
        List<Module> modules = new ArrayList<>();
        String label = null;
        List<String> javacopts = new ArrayList<>();
        String toolchainRelease = null;
        List<String> classes = new ArrayList<>();
        List<Artifact> artifacts = new ArrayList<>();

        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "module" -> {
                    if (label != null) {
                        modules.add(module(label, classes, artifacts,
                                release(javacopts, toolchainRelease, override)));
                    }
                    label = f[1];
                    javacopts = new ArrayList<>();
                    toolchainRelease = null;
                    classes = new ArrayList<>();
                    artifacts = new ArrayList<>();
                }
                case "toolchain" -> toolchainRelease = f[1];
                case "javacopt" -> javacopts.add(f[1]);
                case "classes" -> classes.add(resolveRunfile(f[1]).toString());
                case "dep" -> artifacts.add(new Artifact(
                        emptyToNull(f[1]),
                        emptyToNull(f[2]),
                        emptyToNull(f[3]),
                        resolveRunfile(f[5]).toString(),
                        emptyToNull(f[4])));
                default -> throw new IllegalArgumentException("unknown manifest line: " + line);
            }
        }
        if (label != null) {
            modules.add(module(label, classes, artifacts,
                    release(javacopts, toolchainRelease, override)));
        }
        return modules;
    }

    private static Module module(String label, List<String> classes, List<Artifact> artifacts,
            Integer release) {
        return new Module(label, classes, artifacts, release);
    }

    /**
     * The API release a target compiles for: its own {@code javacopts} if they pin one, else
     * the java toolchain's target version. Read the spelling that pins the API, never the one
     * that names the compiler -- Bazel's recommended shape runs a recent toolchain against an
     * older target just as Gradle's does. An explicit override replaces both, because it is a
     * statement about the whole build.
     */
    private static Integer release(List<String> javacopts, String toolchainRelease,
            Integer override) {
        if (override != null) {
            return override;
        }
        Integer declared = UikaCli.declaredRelease(javacopts);
        return declared != null ? declared : UikaCli.parseRelease(toolchainRelease);
    }

    /**
     * Root prefixes worth collapsing in the dump: the directory each external repository's
     * jars sit under. The v2 root table shortens every path under them, and an external repo
     * path is long (it carries the whole download URL).
     */
    private static List<String> preferredRoots(List<Module> modules) {
        Set<String> roots = new LinkedHashSet<>();
        for (Module module : modules) {
            for (Artifact artifact : module.artifacts()) {
                int external = artifact.file().indexOf("/external/");
                if (external >= 0) {
                    roots.add(artifact.file().substring(0, external + "/external/".length()));
                }
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * The real absolute path of a runfiles entry.
     *
     * <p>Two path conventions reach this method. A jar arrives as a Starlark {@code
     * short_path}, which is {@code pkg/file} in the main repository and {@code ../repo/file}
     * elsewhere, so it resolves against the working directory of a {@code bazel run} (the main
     * repository's runfiles directory). The manifest arrives as an {@code rlocationpath},
     * which always carries the repository prefix and so resolves one level up. Rather than
     * track which is which, try the bases in order and take the one that exists: the two
     * conventions cannot both resolve to a file for the same input.
     *
     * <p>{@code toRealPath} is what makes the dump usable at all -- the runfiles entry is a
     * symlink into the output base or an external repository, and only its target survives
     * the next build.
     */
    private static Path resolveRunfile(String path) {
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

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("missing -D" + property + "; use the uika_dump rule");
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
