package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.UikaCli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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
 * caller read out of {@code bazel info}. The execution root is passed in rather than looked
 * up here so this stays a plain JVM tool with no {@code bazel} binary on its path, and so the
 * caller can pass the configuration flags its sweep build used.
 */
public final class MergeMain {
    private MergeMain() {}

    private static final String FRAGMENT_SUFFIX = ".uika-manifest.tsv";

    public static void main(String[] args) throws IOException {
        Path execroot = null;
        var output = "uika/classpath.json";
        String materialize = null;
        var fragmentDirs = new ArrayList<Path>();
        Integer override = UikaCli.overrideRelease(Integer.getInteger("uika.jdkRelease", 0));

        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--execroot" -> execroot = Path.of(Manifest.flagValue(args, ++i));
                case "--fragments" -> fragmentDirs.add(Path.of(Manifest.flagValue(args, ++i)));
                case "--output", "-o" -> output = Manifest.flagValue(args, ++i);
                case "--materialize" -> materialize = Manifest.flagValue(args, ++i);
                case "--jdkRelease" ->
                        override = UikaCli.overrideRelease(Manifest.flagRelease(args, ++i));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (execroot == null || fragmentDirs.isEmpty()) {
            throw new IllegalArgumentException("usage: --execroot \"$(bazel info execution_root)\""
                    + " --fragments \"$(bazel info bazel-bin)\" [--output <path>]");
        }

        var root = execroot;
        List<Module> modules = new ArrayList<>();
        var seen = new LinkedHashSet<String>();
        for (Path fragment : fragments(fragmentDirs)) {
            for (Module module : Manifest.parse(
                    fragment, override, raw -> Manifest.resolveExecroot(root, raw))) {
                // Overlapping --fragments roots can yield the same module twice. Taking the
                // first keeps one module per name, which is what upgrade-check pairs on.
                if (seen.add(module.path())) {
                    modules.add(module);
                }
            }
        }
        if (modules.isEmpty()) {
            throw new IllegalStateException("no " + FRAGMENT_SUFFIX + " fragments under "
                    + fragmentDirs + "; run the aspect with --output_groups=uika_dump first");
        }

        var roots = new ArrayList<String>();
        if (materialize != null) {
            Path directory = Manifest.workspacePath(materialize);
            modules = Materialize.into(modules, directory);
            roots.add(directory.toString());
        }
        roots.addAll(Manifest.externalRoots(modules));

        Path target = Manifest.workspacePath(output);
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                DumpFormat.writeV2(modules, roots, DumpFormat.dumpRelease(modules)),
                StandardCharsets.UTF_8);
        System.out.println("uika classpath dump: " + target + " (" + modules.size() + " modules)");
    }

    /**
     * Every fragment under the given directories, sorted across ALL of them rather than within
     * each, so the first-wins dedupe above does not depend on the order the flags were passed.
     *
     * <p>The start directory is resolved first because {@code Files.walk} does not descend a
     * symlinked start, while {@code Files.isDirectory} follows one. Without that,
     * {@code --fragments bazel-bin} (the workspace convenience symlink, and the spelling the
     * flag is named after) finds nothing and reports it as a sweep that was never run.
     */
    private static List<Path> fragments(List<Path> directories) throws IOException {
        var found = new ArrayList<Path>();
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var walk = Files.walk(directory.toRealPath())) {
                walk.filter(p -> p.getFileName().toString().endsWith(FRAGMENT_SUFFIX))
                        .forEach(found::add);
            }
        }
        found.sort(null);
        return found;
    }
}
