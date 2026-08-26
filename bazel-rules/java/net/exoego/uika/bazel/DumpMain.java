package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.UikaCli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
                case "--output", "-o" -> output = Manifest.flagValue(args, ++i);
                case "--materialize" -> materialize = Manifest.flagValue(args, ++i);
                case "--jdkRelease" ->
                        override = UikaCli.overrideRelease(Manifest.flagRelease(args, ++i));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        List<Module> modules = Manifest.parse(manifest, override, Manifest::resolveRunfile);
        List<String> roots = new ArrayList<>();
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
        System.out.println("uika classpath dump: " + target);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("missing -D" + property + "; use the uika_dump rule");
        }
        return value;
    }
}
