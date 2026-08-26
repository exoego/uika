package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Copies the jars a dump names out of Bazel's output tree, so the dump outlives it. */
final class Materialize {
    private Materialize() {}

    /**
     * Copies every jar the dump names into one directory and rewrites the dump to point
     * there.
     *
     * <p>A dump names jars under {@code bazel-out}, which is build output rather than source.
     * They survive a lock file change in the same tree, but not a {@code bazel clean}, a fresh
     * output base, or another machine, which is exactly the baseline-as-CI-artifact flow. When
     * the changed pair's OLD jar is missing the check does not degrade, it fails: uika exits 2
     * with "cannot open ...". Measured in bazel-rules/it/run-maven.sh, which asserts both
     * halves. Copying the classpath out of bazel-out is the fix, and it makes the dump
     * portable to another machine as a bonus.
     *
     * <p>Hard links where the filesystem allows it, so the common case costs no space at all.
     */
    static List<Module> into(List<Module> modules, Path directory)
            throws IOException {
        Files.createDirectories(directory);
        Map<String, String> moved = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>();
        List<Module> result = new ArrayList<>(modules.size());
        for (Module module : modules) {
            List<String> classes = new ArrayList<>(module.classesDirs().size());
            for (String source : module.classesDirs()) {
                classes.add(one(source, directory, moved, taken));
            }
            List<Artifact> artifacts = new ArrayList<>(module.artifacts().size());
            for (Artifact artifact : module.artifacts()) {
                artifacts.add(new Artifact(
                        artifact.group(),
                        artifact.name(),
                        artifact.version(),
                        one(artifact.file(), directory, moved, taken),
                        artifact.project()));
            }
            result.add(new Module(module.path(), classes, artifacts, module.jdkRelease()));
        }
        return result;
    }

    private static String one(String source, Path directory,
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
        // Every Bazel classpath entry is a jar: JavaInfo and JavaRuntimeClasspathInfo both
        // model the classpath as jars, and a directory can only reach a target through
        // runfiles. Refusing one loudly beats the two silent alternatives, since
        // Files.copy on a directory would produce an EMPTY one and report success.
        if (Files.isDirectory(from)) {
            throw new IOException("cannot materialize " + from + ": it is a directory, and a"
                    + " Bazel classpath entry is always a jar");
        }
        Path to = directory.resolve(candidate);
        Files.deleteIfExists(to);
        try {
            Files.createLink(to, from);
        } catch (IOException | UnsupportedOperationException crossDeviceOrUnsupported) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
        moved.put(source, to.toString());
        return to.toString();
    }
}
