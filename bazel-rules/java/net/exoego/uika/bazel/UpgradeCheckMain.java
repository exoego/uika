package net.exoego.uika.bazel;

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
        if (args.length > 0 && "jfr-jvmopt".equals(args[0])) {
            // Guarded like the flags below: an empty directory argument would create and
            // record into the workspace root.
            printJfrJvmOpt(args.length > 1 ? Manifest.flagValue(args, 1) : "uika/jfr");
            return;
        }

        Path before = null;
        Path after = null;
        String failOn = property("uika.failOn");
        var excludeFiles = paths(property("uika.excludeFiles"));
        var classLoadLogs = new ArrayList<Path>();
        Path draftExcludeFile = null;
        Path jfr = null;
        var mergedClasspath = Boolean.getBoolean("uika.mergedClasspath");
        // Raw, never through overrideRelease: that helper folds 0 into "unset", which is the
        // dump's meaning of 0. Here 0 has to stay distinguishable, because it is the off
        // switch (effectiveJdkRelease answers null for it and the flag is omitted).
        var wanted = Integer.getInteger("uika.jdkRelease", -1);

        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                // Every value through Manifest.flagValue, like DumpMain and MergeMain. A bare
                // args[++i] reads past the end on a trailing flag, and takes an empty string
                // as a path: an unset shell variable in `--before "$BASELINE"` arrives here
                // as "", which workspacePath turns into the workspace ROOT rather than
                // failing, and the CLI is handed a directory where a dump belongs.
                case "--before" -> before = Manifest.workspacePath(Manifest.flagValue(args, ++i));
                case "--after" -> after = Manifest.workspacePath(Manifest.flagValue(args, ++i));
                // Raw value, unlike its neighbours: an empty --failOn is DROPPED the way
                // every other integration drops it, and the way the rule attribute already
                // does (defs.bzl formats `fail_on or ""` and property() maps "" to null).
                // Rejecting it would make Bazel the one tool where
                // `--failOn "$UIKA_FAIL_ON"` with the variable unset is a hard error.
                // The trailing-flag read stays guarded.
                case "--failOn" -> failOn = Manifest.flagOptional(args, ++i);
                case "--excludeFile" ->
                        excludeFiles.add(Manifest.workspacePath(Manifest.flagValue(args, ++i)));
                case "--classLoadLog" ->
                        classLoadLogs.add(Manifest.workspacePath(Manifest.flagValue(args, ++i)));
                case "--jfr" -> jfr = Manifest.workspacePath(Manifest.flagValue(args, ++i));
                case "--draftExcludeFile" ->
                        draftExcludeFile = Manifest.workspacePath(Manifest.flagValue(args, ++i));
                case "--jdkRelease" -> wanted = Manifest.flagRelease(args, ++i);
                // A PAIR, unlike every other flag here, because this one is a boolean and
                // the attribute it overrides can already be True. Bazel users read
                // --noFoo as the off switch (--nocache_test_results is in this ruleset's
                // own JFR recipe), so the rule that a run-time flag wins over the attribute
                // of the same name holds in both directions.
                case "--mergedClasspath" -> mergedClasspath = true;
                case "--noMergedClasspath" -> mergedClasspath = false;
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (before == null || after == null) {
            throw new IllegalArgumentException(
                    "usage: bazel run //:your_check -- --before <a.json> --after <b.json>");
        }

        List<Path> evidence = new ArrayList<>(classLoadLogs);
        if (jfr != null) {
            evidence.add(jfr);
        }
        if (!evidence.isEmpty()) {
            // Recordings are converted HERE, never by the CLI: the CLI is JVM-free and must
            // not read binary JFR. The WHOLE list goes through the converter, the shape
            // every sibling integration uses: a recording handed to --classLoadLog converts
            // too (the CLI skips .jfr names silently, so forwarding it raw loses the
            // evidence with no symptom), while a text log or a directory entry passes
            // through with any recordings found under it appended. The conversions land
            // under the knob directory itself, which rewrite() handles by design (it
            // deletes its own stale output first).
            Path workBase = jfr != null ? jfr : evidence.get(0);
            evidence = JfrEvidence.rewrite(evidence, workDirFor(workBase), System.out::println);
        }

        JdkSource jdk = JdkSource.current();
        Integer release = UikaCli.effectiveJdkRelease(
                wantedRelease(wanted), jdk, System.out::println);
        var status = UikaCli.runUpgradeCheck(cliBinary(), before, after, failOn, excludeFiles,
                release, jdk, evidence, draftExcludeFile, mergedClasspath, System.out::println);
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
     *
     * <p>Which is also why the value is checked first, through the same
     * {@link JfrEvidence#valueNamesRecording} the Gradle, sbt and Mill injectors use.
     * {@code createDirectories} on an existing regular file throws a raw
     * FileAlreadyExistsException naming neither uika nor the subcommand, and the likely
     * mistakes both land there: pointing this at the {@code --jfr} value from the consume
     * side, or at a text log. Unlike its three siblings this one only ever COLLECTS, so a
     * recording is an error here rather than a skip -- consumption has its own flag.
     */
    private static void printJfrJvmOpt(String directory) throws IOException {
        Path dir = Manifest.workspacePath(directory);
        if (JfrEvidence.valueNamesRecording(dir)) {
            throw new IllegalArgumentException("jfr-jvmopt wants a directory to record INTO,"
                    + " and a test JVM cannot record into an existing recording: " + dir
                    + " (pass it to --jfr on the check instead, which is the consuming side)");
        }
        if (Files.isRegularFile(dir)) {
            throw new IllegalArgumentException(
                    "jfr-jvmopt wants a directory to record into, but " + dir + " is a file");
        }
        Files.createDirectories(dir);
        System.out.println("--jvmopt=" + UikaCli.jfrClassLoadJvmArg(dir));
    }

    /**
     * Where converted recordings land. Inside the knob directory (or beside a file-valued
     * entry), so one path is enough. Preferring --jfr keeps the pre-existing location when
     * both knobs are given.
     */
    static Path workDirFor(Path entry) {
        Path base = Files.isDirectory(entry) ? entry : entry.getParent();
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
    static Integer wantedRelease(int wanted) throws IOException {
        if (wanted >= 0) {
            return wanted;
        }
        String releases = System.getProperty("uika.releases");
        if (releases == null || releases.isEmpty()) {
            return DumpFormat.buildJvmRelease();
        }
        var modules = Manifest.parse(
                Manifest.resolveRunfile(releases), null, Manifest::resolveRunfile);
        return DumpFormat.dumpRelease(modules);
    }

    /**
     * The uika binary. UIKA_CLI_PATH wins over the downloaded one so a build can point at a
     * binary it already has, which is also how this repository tests against its own
     * freshly built CLI.
     *
     * <p>Through the shared {@link UikaCli#binaryOverride()} rather than a local getenv, so
     * a value that is not a file, or a file that lost its executable bit on an artifact
     * round trip, fails here naming the variable instead of inside ProcessBuilder with no
     * cause in sight. The four JVM plugins already go through it.
     */
    private static Path cliBinary() {
        Path override = UikaCli.binaryOverride();
        if (override != null) {
            return override;
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

    /**
     * The paths {@code -Duika.excludeFiles} names, trimmed, with blanks dropped.
     *
     * <p>Package-private for {@link ManifestSelfTest}. The integration test drives the whole
     * rule and proves the values reach the CLI, but the Bazel coverage number measures the
     * unit test alone -- the ITs run {@code bazel run} in a temp workspace, out of its
     * reach -- so the splitting itself needs a test here to be measured at all. It earns
     * one either way: no sibling integration joins its exclude list into a single property,
     * so this is the only place that has to take one apart.
     */
    static List<Path> paths(String commaSeparated) {
        var paths = new ArrayList<Path>();
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
