package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.JfrEvidence;
import net.exoego.uika.plugin.core.UikaCli;
import net.exoego.uika.plugin.core.UikaCli.JdkSource;

import java.io.IOException;
import java.nio.file.Files;
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
        if (args.length > 0 && args[0].equals("jfr-jvmopt")) {
            printJfrJvmOpt(args.length > 1 ? args[1] : "uika/jfr");
            return;
        }

        Path before = null;
        Path after = null;
        String failOn = property("uika.failOn");
        List<Path> excludeFiles = paths(property("uika.excludeFiles"));
        List<Path> classLoadLogs = new ArrayList<>();
        Path draftExcludeFile = null;
        Path jfr = null;
        // Raw, never through overrideRelease: that helper folds 0 into "unset", which is the
        // dump's meaning of 0. Here 0 has to stay distinguishable, because it is the off
        // switch (effectiveJdkRelease answers null for it and the flag is omitted).
        int wanted = Integer.getInteger("uika.jdkRelease", -1);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--before" -> before = Manifest.workspacePath(args[++i]);
                case "--after" -> after = Manifest.workspacePath(args[++i]);
                case "--failOn" -> failOn = args[++i];
                case "--excludeFile" -> excludeFiles.add(Manifest.workspacePath(args[++i]));
                case "--classLoadLog" -> classLoadLogs.add(Manifest.workspacePath(args[++i]));
                case "--jfr" -> jfr = Manifest.workspacePath(args[++i]);
                case "--draftExcludeFile" -> draftExcludeFile = Manifest.workspacePath(args[++i]);
                case "--jdkRelease" -> wanted = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (before == null || after == null) {
            throw new IllegalArgumentException(
                    "usage: bazel run //:your_check -- --before <a.json> --after <b.json>");
        }

        if (jfr != null) {
            // Recordings are converted HERE, never by the CLI: the CLI is JVM-free and must
            // not read binary JFR. The conversions land under the knob directory itself,
            // which rewrite() handles by design (it deletes its own stale output first).
            classLoadLogs.addAll(JfrEvidence.rewrite(
                    List.of(jfr), workDirFor(jfr), System.out::println));
        }

        JdkSource jdk = JdkSource.current();
        Integer release = UikaCli.effectiveJdkRelease(
                wantedRelease(wanted), jdk, System.out::println);
        int status = UikaCli.runUpgradeCheck(cliBinary(), before, after, failOn, excludeFiles,
                release, jdk, classLoadLogs, draftExcludeFile, System.out::println);
        System.exit(status);
    }

    /**
     * Prints the Bazel flag that makes every test JVM record its class loads.
     *
     * <p>Printed rather than documented, so the README recipe cannot drift from the format
     * the converter expects. The Maven plugin has the opposite arrangement, a hand-written
     * argLine that has to be kept in step by hand, because no mojo can inject into surefire.
     * Bazel needs no injection at all, since --jvmopt already reaches every test JVM.
     *
     * <p>Creating the directory is part of the job. Given a MISSING PARENT, JFR aborts JVM
     * startup and the mistake is loud; given an existing parent and a missing leaf it
     * silently records to a single file at that path instead, every fork clobbering the last.
     */
    private static void printJfrJvmOpt(String directory) throws IOException {
        Path dir = Manifest.workspacePath(directory);
        Files.createDirectories(dir);
        System.out.println("--jvmopt=" + UikaCli.jfrClassLoadJvmArg(dir));
    }

    /** Where converted recordings land. Inside the knob directory, so one path is enough. */
    private static Path workDirFor(Path jfr) {
        Path base = JfrEvidence.isRecording(jfr) ? jfr.getParent() : jfr;
        return base.resolve(JfrEvidence.WORK_DIR_NAME);
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
     *
     * <p>A non-negative value skips the derivation entirely, 0 included: 0 switches the
     * layer off, and only a negative value means "derive".
     */
    private static Integer wantedRelease(int wanted) throws IOException {
        if (wanted >= 0) {
            return wanted;
        }
        String releases = System.getProperty("uika.releases");
        if (releases == null || releases.isEmpty()) {
            return DumpFormat.buildJvmRelease();
        }
        List<Module> modules = Manifest.parse(
                Manifest.resolveRunfile(releases), null, Manifest::resolveRunfile);
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
