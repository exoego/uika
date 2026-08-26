package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.UikaCli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        Path manifest = Manifest.resolveRunfile(required("uika.manifest"));
        Integer override = UikaCli.overrideRelease(Integer.getInteger("uika.jdkRelease", 0));

        String output = "uika/classpath.json";
        String materialize = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> output = args[++i];
                case "--materialize" -> materialize = args[++i];
                case "--jdkRelease" -> override = UikaCli.overrideRelease(Integer.valueOf(args[++i]));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        List<Module> modules = Manifest.parse(manifest, override);
        List<String> roots = new ArrayList<>();
        if (materialize != null) {
            Path directory = Manifest.workspacePath(materialize);
            modules = materialize(modules, directory);
            roots.add(directory.toString());
        }
        roots.addAll(externalRoots(modules));

        Path target = Manifest.workspacePath(output);
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                DumpFormat.writeV2(modules, roots, DumpFormat.dumpRelease(modules)),
                StandardCharsets.UTF_8);
        System.out.println("uika classpath dump: " + target);
    }

    /**
     * Copies every jar the dump names into one directory and rewrites the dump to point
     * there.
     *
     * <p>Bazel discards an external repository and refetches it when its lockfile changes, so
     * the jars a baseline dump names are gone by the time the PR job compares against it. uika
     * treats a jar it cannot open as a warning, which means the failure shows up as FEWER
     * findings rather than as an error. Copying the baseline's classpath out of Bazel's reach
     * is the fix, and it makes the dump portable to another machine as a bonus.
     *
     * <p>Hard links where the filesystem allows it, so the common case costs no space at all.
     */
    private static List<Module> materialize(List<Module> modules, Path directory)
            throws IOException {
        Files.createDirectories(directory);
        Map<String, String> moved = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>();
        List<Module> result = new ArrayList<>(modules.size());
        for (Module module : modules) {
            List<String> classes = new ArrayList<>(module.classesDirs().size());
            for (String source : module.classesDirs()) {
                classes.add(materializeOne(source, directory, moved, taken));
            }
            List<Artifact> artifacts = new ArrayList<>(module.artifacts().size());
            for (Artifact artifact : module.artifacts()) {
                artifacts.add(new Artifact(
                        artifact.group(),
                        artifact.name(),
                        artifact.version(),
                        materializeOne(artifact.file(), directory, moved, taken),
                        artifact.project()));
            }
            result.add(new Module(module.path(), classes, artifacts, module.jdkRelease()));
        }
        return result;
    }

    private static String materializeOne(String source, Path directory,
            Map<String, String> moved, Set<String> taken) throws IOException {
        String already = moved.get(source);
        if (already != null) {
            return already;
        }
        Path from = Path.of(source);
        // Two artifacts can share a file name (the same jar at two versions, or a
        // build output named like a dependency), and the second must not overwrite the first.
        String name = from.getFileName().toString();
        String candidate = name;
        for (int n = 2; !taken.add(candidate); n++) {
            candidate = n + "-" + name;
        }
        Path to = directory.resolve(candidate);
        Files.deleteIfExists(to);
        if (Files.isDirectory(from)) {
            copyTree(from, to);
        } else {
            try {
                Files.createLink(to, from);
            } catch (IOException | UnsupportedOperationException crossDeviceOrUnsupported) {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        moved.put(source, to.toString());
        return to.toString();
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path destination = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Root prefixes worth collapsing in the dump: the directory each external repository's
     * jars sit under. The v2 root table shortens every path under them, and an external repo
     * path is long because it carries the whole download URL.
     */
    private static List<String> externalRoots(List<Module> modules) {
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

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("missing -D" + property + "; use the uika_dump rule");
        }
        return value;
    }
}
