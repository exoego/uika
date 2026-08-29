package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises uikaUpgradeCheck against a fake Maven repository containing stub uika-cli ZIPs
 * whose "binary" is a shell script (skipped on Windows for that reason).
 */
final class UpgradeCheckTaskIntegrationTest {
    private static final String CLEAN_VERSION = "9.9.9";
    private static final String VIOLATION_VERSION = "9.9.8";

    @TempDir
    Path projectDir;

    @TempDir
    Path repoDir;

    private Path before;
    private Path after;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("windows"),
                "stub binary is a shell script");

        // The marker proves the stub ran; the echoed lines must reach the build output
        // through the task's logger (inherited stdio dies with the daemon). The args file
        // captures the full invocation so tests can assert the flags passed to the CLI
        // ($3 is the --before path).
        publishStubCli(CLEAN_VERSION, """
                #!/bin/sh
                echo ran > "$3.marker"
                echo "$@" > "$3.args"
                echo "${UIKA_JDK:-}" > "$3.env"
                echo "uika-stub: dependency changes: 0"
                exit 0
                """);
        publishStubCli(VIOLATION_VERSION, """
                #!/bin/sh
                echo "VIOLATION in stub.jar"
                exit 1
                """);

        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "dummy-uika-consumer"
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }

                repositories {
                    maven {
                        url = uri("%s")
                        metadataSources { artifact() }
                    }
                }
                """.formatted(repoDir.toUri()));
        before = Files.writeString(projectDir.resolve("before.json"), "{}");
        after = Files.writeString(projectDir.resolve("after.json"), "{}");
    }

    @Test
    void resolvesExtractsAndRunsCli() throws Exception {
        var result = runner(CLEAN_VERSION).build();

        var task = result.task(":uikaUpgradeCheck");
        assertNotNull(task, "task did not run");
        assertEquals(TaskOutcome.SUCCESS, task.getOutcome());
        assertTrue(Files.exists(Path.of(before + ".marker")),
                "stub binary was not executed with --before " + before);
        assertTrue(result.getOutput().contains("uika-stub: dependency changes: 0"),
                () -> "CLI output did not reach the build log:\n" + result.getOutput());
        // Default policy is the strictest; the plugin passes it explicitly.
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--fail-on any"),
                () -> "expected default --fail-on any in CLI invocation: " + args);
    }

    @Test
    void passesFailOnToCli() throws Exception {
        var result = runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION,
                        "-PuikaFailOn=reachable")
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaUpgradeCheck").getOutcome());
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--fail-on reachable"),
                () -> "-PuikaFailOn was not forwarded to the CLI: " + args);
    }

    @Test
    void failOnConfigurableFromBuildScript() throws Exception {
        // Declarative config in build.gradle.kts (the task DSL), no -PuikaFailOn.
        write(projectDir.resolve("build.gradle.kts"), """
                import net.exoego.uika.gradle.UpgradeCheckTask

                plugins {
                    id("net.exoego.uika")
                }

                repositories {
                    maven {
                        url = uri("%s")
                        metadataSources { artifact() }
                    }
                }

                tasks.withType<UpgradeCheckTask>().configureEach {
                    failOn.set("reachable")
                }
                """.formatted(repoDir.toUri()));

        var result = runner(CLEAN_VERSION).build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaUpgradeCheck").getOutcome());
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--fail-on reachable"),
                () -> "build-script failOn was not forwarded to the CLI: " + args);
    }

    @Test
    void passesExcludeFileToCli() throws Exception {
        Path excludeFile = write(projectDir.resolve("uika-exclude.toml"), """
                [[exclude]]
                owner = "lib/C"
                reason = "test"
                """);

        var result = runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION,
                        "-PuikaExcludeFile=" + excludeFile)
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaUpgradeCheck").getOutcome());
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--exclude-file " + excludeFile),
                () -> "-PuikaExcludeFile was not forwarded to the CLI: " + args);
    }

    @Test
    void excludeFilesConfigurableFromBuildScript() throws Exception {
        Path excludeFile = write(projectDir.resolve("uika-exclude.toml"), """
                [[exclude]]
                owner = "lib/C"
                reason = "test"
                """);
        // Declarative config in build.gradle.kts (the task DSL), no -PuikaExcludeFile.
        write(projectDir.resolve("build.gradle.kts"), """
                import net.exoego.uika.gradle.UpgradeCheckTask

                plugins {
                    id("net.exoego.uika")
                }

                repositories {
                    maven {
                        url = uri("%s")
                        metadataSources { artifact() }
                    }
                }

                tasks.withType<UpgradeCheckTask>().configureEach {
                    excludeFiles.from("%s")
                }
                """.formatted(repoDir.toUri(), excludeFile.toString().replace("\\", "\\\\")));

        var result = runner(CLEAN_VERSION).build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaUpgradeCheck").getOutcome());
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--exclude-file " + excludeFile),
                () -> "build-script excludeFiles was not forwarded to the CLI: " + args);
    }

    @Test
    void passesClassLoadLogAndDraftFileToCli() throws Exception {
        Path logDir = Files.createDirectories(projectDir.resolve("load-logs"));
        var draft = projectDir.resolve("uika-draft.toml");
        var result = runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION,
                        "-PuikaJfr=" + logDir,
                        "-PuikaDraftExcludeFile=" + draft)
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaUpgradeCheck").getOutcome());
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--class-load-log " + logDir),
                () -> "-PuikaJfr was not forwarded to the CLI: " + args);
        assertTrue(args.contains("--draft-exclude-file " + draft),
                () -> "-PuikaDraftExcludeFile was not forwarded to the CLI: " + args);
    }

    /// -PuikaJfr points Test tasks and the check at one directory: every Test JVM
    /// gets the StartFlightRecording flag recording jdk.ClassLoad there (JFR generates
    /// pid-unique file names), and without the property test JVMs stay untouched.
    @Test
    void classLoadLogPropertyInjectsTestJvmArgs() throws Exception {
        var logDir = projectDir.resolve("load-logs");
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("net.exoego.uika")
                }

                repositories {
                    maven {
                        url = uri("%s")
                        metadataSources { artifact() }
                    }
                }

                tasks.register("printTestJvmArgs") {
                    val args = tasks.test.get().allJvmArgs
                    doLast { println("TEST_JVM_ARGS=" + args) }
                }
                """.formatted(repoDir.toUri()));

        var with = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("printTestJvmArgs", "-PuikaJfr=" + logDir)
                .withPluginClasspath()
                .forwardOutput()
                .build();
        // Composed by the same core helper the plugin calls.
        String expected = UikaCli.jfrClassLoadJvmArg(logDir);
        assertTrue(with.getOutput().contains(expected),
                () -> "expected " + expected + " in test JVM args:\n" + with.getOutput());

        var without = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("printTestJvmArgs")
                .withPluginClasspath()
                .forwardOutput()
                .build();
        assertTrue(!without.getOutput().contains("-XX:StartFlightRecording"),
                () -> "test JVM args must stay untouched without the property:\n"
                        + without.getOutput());
    }

    /// A .jfr value on the knob is converted before the CLI runs: the recording itself
    /// never reaches the JVM-free CLI, the converted text (with the probe class and its
    /// stack) does. The recording is REAL, made in this test JVM; the probe is compiled
    /// at runtime (JfrTestRecordings explains why a member class cannot serve).
    @Test
    void convertsAJfrRecordingBeforeInvokingTheCli() throws Exception {
        var probe = "UikaJfrProbeGradle";
        var jfr = projectDir.resolve("rec.jfr");
        net.exoego.uika.plugin.core.JfrTestRecordings.recordFreshClassLoad(
                projectDir, jfr, probe);

        var result = runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION,
                        "-PuikaJfr=" + jfr)
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaUpgradeCheck").getOutcome());
        // The injection skip must be said out loud: a collect run against a .jfr value
        // records nothing, and without this line the empty artifact has no symptom.
        assertTrue(result.getOutput().contains("consumption-only"),
                () -> "expected the consumption-only notice in the build output:\n"
                        + result.getOutput());
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(!args.contains("rec.jfr"),
                () -> "the raw recording must not reach the CLI: " + args);
        String converted = null;
        var words = args.trim().split(" ");
        for (var i = 0; i + 1 < words.length; i++) {
            if ("--class-load-log".equals(words[i])) {
                converted = words[i + 1];
            }
        }
        assertTrue(converted != null && converted.contains("jfr-class-load"),
                "expected a converted log under jfr-class-load in: " + args);
        String text = Files.readString(Path.of(converted));
        assertTrue(text.contains("Java stack when loading " + probe + ":"),
                () -> "converted log lost the probe load:\n" + text);
    }

    /// The bare -PuikaJfr default is a lazy provider: a build script relocating
    /// layout.buildDirectory AFTER the plugins block must land both halves — the injected
    /// test JVM flag and the check task's --class-load-log — under the final location,
    /// never under the pre-relocation default.
    @Test
    void bareClassLoadLogFollowsARelocatedBuildDirectory() throws Exception {
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("net.exoego.uika")
                }

                layout.buildDirectory = file("custom-build")

                repositories {
                    maven {
                        url = uri("%s")
                        metadataSources { artifact() }
                    }
                }

                tasks.register("printTestJvmArgs") {
                    val args = tasks.test.get().allJvmArgs
                    doLast { println("TEST_JVM_ARGS=" + args) }
                }
                """.formatted(repoDir.toUri()));

        var injected = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("printTestJvmArgs", "-PuikaJfr")
                .withPluginClasspath()
                .forwardOutput()
                .build();
        // toRealPath: Gradle canonicalizes the project directory (macOS /var vs
        // /private/var), and the bare default is derived from it.
        var relocated = projectDir.toRealPath()
                .resolve("custom-build").resolve("uika").resolve("jfr");
        String expected = UikaCli.jfrClassLoadJvmArg(relocated);
        assertTrue(injected.getOutput().contains(expected),
                () -> "expected " + expected + " in test JVM args:\n" + injected.getOutput());

        var checked = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION,
                        "-PuikaJfr")
                .withPluginClasspath()
                .forwardOutput()
                .build();
        assertEquals(TaskOutcome.SUCCESS, checked.task(":uikaUpgradeCheck").getOutcome());
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--class-load-log " + relocated),
                () -> "the check must read the relocated default: " + args);
    }

    @Test
    void jdkReleaseDefaultsToTheBuildJvmClamped() throws Exception {
        // No java plugin in the test project, so the default derivation falls to the JVM
        // running the build (the TestKit daemon uses this JVM), clamped by one because a
        // JDK's ct.sym never contains its own release.
        runner(CLEAN_VERSION).build();

        var expected = Runtime.version().feature() - 1;
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--jdk-release " + expected),
                () -> "expected derived --jdk-release " + expected + " in CLI invocation: " + args);
        // The CLI must read ct.sym from the build JVM, not whatever JAVA_HOME the
        // environment happens to export.
        // The release and UIKA_JDK are one decision, so the exported home has to be the JDK
        // the release was clamped against, not merely non-empty.
        var env = Files.readString(Path.of(before + ".env")).trim();
        assertEquals(System.getProperty("java.home"), env,
                "UIKA_JDK must be the JDK whose ct.sym the release was clamped against");
    }

    @Test
    void jdkReleaseDerivedFromTargetCompatibility() throws Exception {
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("net.exoego.uika")
                }

                java {
                    targetCompatibility = JavaVersion.VERSION_11
                }

                repositories {
                    maven {
                        url = uri("%s")
                        metadataSources { artifact() }
                    }
                }
                """.formatted(repoDir.toUri()));

        runner(CLEAN_VERSION).build();

        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--jdk-release 11"),
                () -> "expected --jdk-release 11 from targetCompatibility: " + args);
    }

    @Test
    void jdkReleaseIsTheLowestAnySubprojectTargets() throws Exception {
        // A multi-module root usually applies no java plugin at all, which used to fall
        // straight through to the JVM running the build and report a release no module
        // compiles against. One flag serves the whole run, so the lowest target wins.
        // Each subproject declares its release in its OWN build script, and through a
        // different mechanism: `options.release` is what Gradle documents as pinning the API,
        // and the toolchain next to it is the COMPILER JDK, which must not be mistaken for
        // the release the bytecode runs on. Gradle refuses to configure a subproject whose
        // directory does not exist.
        Files.createDirectories(projectDir.resolve("older"));
        Files.createDirectories(projectDir.resolve("newer"));
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "root"
                include("older", "newer")
                """);
        write(projectDir.resolve("older/build.gradle.kts"), """
                plugins { java }
                java { toolchain { languageVersion = JavaLanguageVersion.of(%d) } }
                tasks.withType<JavaCompile>().configureEach { options.release = 11 }
                """.formatted(Runtime.version().feature()));
        write(projectDir.resolve("newer/build.gradle.kts"), """
                plugins { java }
                java { targetCompatibility = JavaVersion.VERSION_17 }
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }

                repositories {
                    maven {
                        url = uri("%s")
                        metadataSources { artifact() }
                    }
                }
                """.formatted(repoDir.toUri()));

        // With the configuration cache on, because deriving this reads other projects'
        // extensions and realizes their compileJava from a task input provider, and that is
        // exactly what a cache entry has to survive.
        runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--configuration-cache",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION)
                .build();

        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--jdk-release 11"),
                () -> "expected the lowest subproject release (11), not 17, the toolchain, "
                        + "or the build JVM: " + args);
    }

    @Test
    void jdkReleasePropertyOverridesAndZeroDisables() throws Exception {
        runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION,
                        "-PuikaJdkRelease=11")
                .build();
        String overridden = Files.readString(Path.of(before + ".args"));
        assertTrue(overridden.contains("--jdk-release 11"),
                () -> "-PuikaJdkRelease was not forwarded to the CLI: " + overridden);

        runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION,
                        "-PuikaJdkRelease=0")
                .build();
        String disabled = Files.readString(Path.of(before + ".args"));
        assertTrue(!disabled.contains("--jdk-release"),
                () -> "-PuikaJdkRelease=0 must disable the JDK API layer: " + disabled);
    }

    /// The root tasks have no data dependencies on each other, so a single invocation must
    /// order the check after the dump that writes its after file (mustRunAfter). Without
    /// the ordering the check could start first and fail on the missing input.
    @Test
    void singleInvocationRunsDumpBeforeCheck() throws Exception {
        var afterDump = projectDir.resolve("after-dump.json");
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(
                        "uikaDumpClasspath",
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaOutput=" + afterDump,
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + afterDump,
                        "-PuikaCliVersion=" + CLEAN_VERSION)
                .withPluginClasspath()
                .forwardOutput()
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaDumpClasspath").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaUpgradeCheck").getOutcome());
        List<String> order = result.getTasks().stream().map(BuildTask::getPath).toList();
        assertTrue(order.indexOf(":uikaDumpClasspath") < order.indexOf(":uikaUpgradeCheck"),
                () -> "the check ran before the dump: " + order);
    }

    /// The check invocation is configuration-cache compatible: the CLI ZIP's detached
    /// configuration is wired at configuration time, and a reused entry still runs the CLI.
    @Test
    void configurationCacheReusesUpgradeCheck() throws Exception {
        // The draft/log properties are set on purpose: getDraftExcludeFile must stay
        // @Internal, and this second-run-still-executes assertion is the regression guard
        // for it — an @OutputFile here registered an output, made the second invocation
        // UP-TO-DATE, and silently skipped the whole check. Without the property set the
        // task has no output files either way and the guard asserts nothing.
        Path logDir = Files.createDirectories(projectDir.resolve("cc-load-logs"));
        var draft = projectDir.resolve("cc-draft.toml");
        String[] args = {
                "uikaUpgradeCheck",
                "--configuration-cache",
                "--stacktrace",
                "-PuikaBefore=" + before,
                "-PuikaAfter=" + after,
                "-PuikaCliVersion=" + CLEAN_VERSION,
                "-PuikaJfr=" + logDir,
                "-PuikaDraftExcludeFile=" + draft};
        var first = runner(CLEAN_VERSION)
                .withArguments(args)
                .build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":uikaUpgradeCheck").getOutcome());
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                () -> "no configuration cache entry was stored:\n" + first.getOutput());
        assertTrue(Files.exists(Path.of(before + ".marker")), "stub binary was not executed");

        Files.delete(Path.of(before + ".marker"));
        var second = runner(CLEAN_VERSION)
                .withArguments(args)
                .build();
        assertEquals(TaskOutcome.SUCCESS, second.task(":uikaUpgradeCheck").getOutcome());
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                () -> "the configuration cache entry was not reused:\n" + second.getOutput());
        assertTrue(Files.exists(Path.of(before + ".marker")),
                "stub binary was not executed on the cache-reuse run");
    }

    @Test
    void violationExitCodeFailsTheBuild() {
        var result = runner(VIOLATION_VERSION).buildAndFail();

        assertTrue(result.getOutput().contains("VIOLATION in stub.jar"),
                () -> "CLI violation report did not reach the build log:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("broken references"),
                () -> "unexpected failure output:\n" + result.getOutput());
    }

    private GradleRunner runner(String cliVersion) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(
                        "uikaUpgradeCheck",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + cliVersion)
                .withPluginClasspath()
                .forwardOutput();
    }

    /** Lays out repoDir like a Maven repository: net/exoego/uika/uika-cli/<v>/uika-cli-<v>-<classifier>.zip. */
    private void publishStubCli(String version, String script) throws IOException {
        String classifier = UikaCli.platformClassifier();
        var dir = repoDir.resolve("net/exoego/uika/uika-cli/" + version);
        Files.createDirectories(dir);
        var zip = dir.resolve("uika-cli-" + version + "-" + classifier + ".zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("uika-" + version + "-" + classifier + "/uika"));
            out.write(script.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static Path write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
