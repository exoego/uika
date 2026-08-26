package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.UikaCli;
import net.exoego.uika.plugin.core.UikaCli.JdkSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@code uika upgrade-check} over a before/after pair of dumps.
 *
 * <p>The binary itself comes from the {@code @uika_cli} repository, so Bazel's repository
 * cache holds it and a second run needs no network. {@code UIKA_CLI_PATH} still overrides
 * it, which is what the integration test uses to run the freshly built debug binary.
 */
public final class UpgradeCheckMain {
    private UpgradeCheckMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        Path before = null;
        Path after = null;
        String failOn = property("uika.failOn");
        List<Path> excludeFiles = paths(property("uika.excludeFiles"));
        List<Path> classLoadLogs = new ArrayList<>();
        Path draftExcludeFile = null;
        Integer override = UikaCli.overrideRelease(Integer.getInteger("uika.jdkRelease", 0));

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--before" -> before = Manifest.workspacePath(args[++i]);
                case "--after" -> after = Manifest.workspacePath(args[++i]);
                case "--failOn" -> failOn = args[++i];
                case "--excludeFile" -> excludeFiles.add(Manifest.workspacePath(args[++i]));
                case "--classLoadLog" -> classLoadLogs.add(Manifest.workspacePath(args[++i]));
                case "--draftExcludeFile" -> draftExcludeFile = Manifest.workspacePath(args[++i]);
                case "--jdkRelease" -> override = UikaCli.overrideRelease(Integer.valueOf(args[++i]));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (before == null || after == null) {
            throw new IllegalArgumentException(
                    "usage: bazel run //:your_check -- --before <a.json> --after <b.json>");
        }

        JdkSource jdk = JdkSource.current();
        Integer release = UikaCli.effectiveJdkRelease(
                wantedRelease(override), jdk, System.out::println);
        int status = UikaCli.runUpgradeCheck(cliBinary(), before, after, failOn, excludeFiles,
                release, jdk, classLoadLogs, draftExcludeFile, System.out::println);
        System.exit(status);
    }

    /**
     * The release to ask the JDK API layer for.
     *
     * <p>Derived from what the checked targets compile for, exactly as the dump's per-module
     * releases are, because the check target builds the same manifest with only the release
     * lines in it. The LOWEST of them, since one flag serves a run that checks every module
     * and under-claiming costs unverified references while over-claiming drops findings.
     * With no targets listed there is nothing to read and it falls through to the JVM
     * running this tool, which is Bazel's own Java runtime.
     */
    private static Integer wantedRelease(Integer override) throws IOException {
        String releases = System.getProperty("uika.releases");
        if (releases == null || releases.isEmpty()) {
            return override != null ? override : DumpFormat.buildJvmRelease();
        }
        List<Module> modules = Manifest.parse(Manifest.resolveRunfile(releases), override);
        return DumpFormat.dumpRelease(modules);
    }

    /**
     * The uika binary. UIKA_CLI_PATH wins over the downloaded one so a build can point at a
     * binary it already has, which is also how this repository tests against its own
     * freshly built CLI.
     */
    private static Path cliBinary() {
        String override = System.getenv("UIKA_CLI_PATH");
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        String property = System.getProperty("uika.cli");
        if (property == null || property.isEmpty()) {
            throw new IllegalStateException(
                    "missing -Duika.cli; use the uika_upgrade_check rule");
        }
        return Manifest.resolveRunfile(property);
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<Path> paths(String commaSeparated) {
        List<Path> paths = new ArrayList<>();
        if (commaSeparated != null) {
            for (String entry : commaSeparated.split(",")) {
                if (!entry.isBlank()) {
                    paths.add(Manifest.workspacePath(entry.trim()));
                }
            }
        }
        return paths;
    }
}
