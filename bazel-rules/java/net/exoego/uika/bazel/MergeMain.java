package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.UikaCli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Merges the per-target fragments a sweep produced into one uika v2 dump.
 *
 * <p>The sweep exists because a rule cannot expand {@code //...}, so a workspace of any size
 * would otherwise have to list its deployable targets by hand. An aspect can be applied to a
 * pattern from the command line, and it drops one fragment per matched target into the
 * {@code uika_dump} output group.
 *
 * <p>Unlike {@link DumpMain} there is no runfiles tree here, so the fragments name paths
 * relative to the execution root and this merges them against the {@code --execroot} the
 * caller read out of {@code bazel info}. That is also why the sweep cannot be a
 * {@code bazel run} target itself, since a nested {@code bazel info} would block on the
 * server lock.
 */
public final class MergeMain {
    private MergeMain() {}

    private static final String FRAGMENT_SUFFIX = ".uika-manifest.tsv";

    public static void main(String[] args) throws IOException {
        Path execroot = null;
        String output = "uika/classpath.json";
        String materialize = null;
        List<Path> fragmentDirs = new ArrayList<>();
        Integer override = UikaCli.overrideRelease(Integer.getInteger("uika.jdkRelease", 0));

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--execroot" -> execroot = Path.of(args[++i]);
                case "--fragments" -> fragmentDirs.add(Path.of(args[++i]));
                case "--output", "-o" -> output = args[++i];
                case "--materialize" -> materialize = args[++i];
                case "--jdkRelease" -> override = UikaCli.overrideRelease(Integer.valueOf(args[++i]));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (execroot == null || fragmentDirs.isEmpty()) {
            throw new IllegalArgumentException("usage: --execroot \"$(bazel info execution_root)\""
                    + " --fragments \"$(bazel info bazel-bin)\" [--output <path>]");
        }

        Path root = execroot;
        List<Module> modules = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Path fragment : fragments(fragmentDirs)) {
            for (Module module : Manifest.parse(fragment, override, raw -> root.resolve(raw))) {
                // A target matched by two patterns produces the same fragment twice. Taking
                // the first keeps one module per name, which is what upgrade-check pairs on.
                if (seen.putIfAbsent(module.path(), Boolean.TRUE) == null) {
                    modules.add(module);
                }
            }
        }
        if (modules.isEmpty()) {
            throw new IllegalStateException("no " + FRAGMENT_SUFFIX + " fragments under "
                    + fragmentDirs + "; run the aspect with --output_groups=uika_dump first");
        }

        List<String> roots = new ArrayList<>();
        if (materialize != null) {
            Path directory = Manifest.workspacePath(materialize);
            modules = Materialize.into(modules, directory);
            roots.add(directory.toString());
        }
        roots.addAll(externalRoots(modules));

        Path target = Manifest.workspacePath(output);
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                DumpFormat.writeV2(modules, roots, DumpFormat.dumpRelease(modules)),
                StandardCharsets.UTF_8);
        System.out.println("uika classpath dump: " + target + " (" + modules.size() + " modules)");
    }

    private static List<Path> fragments(List<Path> directories) throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(p -> p.getFileName().toString().endsWith(FRAGMENT_SUFFIX))
                        .sorted()
                        .forEach(found::add);
            }
        }
        return found;
    }

    /** Same root-table seeding as the rule-based dump; see DumpMain. */
    private static List<String> externalRoots(List<Module> modules) {
        List<String> roots = new ArrayList<>();
        for (Module module : modules) {
            for (Artifact artifact : module.artifacts()) {
                int external = artifact.file().indexOf("/external/");
                if (external >= 0) {
                    String root = artifact.file().substring(0, external + "/external/".length());
                    if (!roots.contains(root)) {
                        roots.add(root);
                    }
                }
            }
        }
        return roots;
    }
}
