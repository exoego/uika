package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.gradle.testkit.runner.BuildResult;
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
        BuildResult result = runner(CLEAN_VERSION).build();

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
        BuildResult result = runner(CLEAN_VERSION)
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

        BuildResult result = runner(CLEAN_VERSION).build();

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

        BuildResult result = runner(CLEAN_VERSION)
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

        BuildResult result = runner(CLEAN_VERSION).build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":uikaUpgradeCheck").getOutcome());
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--exclude-file " + excludeFile),
                () -> "build-script excludeFiles was not forwarded to the CLI: " + args);
    }

    @Test
    void jdkReleaseDefaultsToTheBuildJvmClamped() throws Exception {
        // No java plugin in the test project, so the default derivation falls to the JVM
        // running the build (the TestKit daemon uses this JVM), clamped by one because a
        // JDK's ct.sym never contains its own release.
        runner(CLEAN_VERSION).build();

        int expected = Runtime.version().feature() - 1;
        String args = Files.readString(Path.of(before + ".args"));
        assertTrue(args.contains("--jdk-release " + expected),
                () -> "expected derived --jdk-release " + expected + " in CLI invocation: " + args);
        // The CLI must read ct.sym from the build JVM, not whatever JAVA_HOME the
        // environment happens to export.
        String env = Files.readString(Path.of(before + ".env")).trim();
        assertTrue(!env.isEmpty(), "UIKA_JDK was not exported to the CLI process");
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

    /// The check invocation is configuration-cache compatible: the CLI ZIP's detached
    /// configuration is wired at configuration time, and a reused entry still runs the CLI.
    @Test
    void configurationCacheReusesUpgradeCheck() throws Exception {
        BuildResult first = runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--configuration-cache",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION)
                .build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":uikaUpgradeCheck").getOutcome());
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                () -> "no configuration cache entry was stored:\n" + first.getOutput());
        assertTrue(Files.exists(Path.of(before + ".marker")), "stub binary was not executed");

        Files.delete(Path.of(before + ".marker"));
        BuildResult second = runner(CLEAN_VERSION)
                .withArguments(
                        "uikaUpgradeCheck",
                        "--configuration-cache",
                        "--stacktrace",
                        "-PuikaBefore=" + before,
                        "-PuikaAfter=" + after,
                        "-PuikaCliVersion=" + CLEAN_VERSION)
                .build();
        assertEquals(TaskOutcome.SUCCESS, second.task(":uikaUpgradeCheck").getOutcome());
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                () -> "the configuration cache entry was not reused:\n" + second.getOutput());
        assertTrue(Files.exists(Path.of(before + ".marker")),
                "stub binary was not executed on the cache-reuse run");
    }

    @Test
    void violationExitCodeFailsTheBuild() {
        BuildResult result = runner(VIOLATION_VERSION).buildAndFail();

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
        Path dir = repoDir.resolve("net/exoego/uika/uika-cli/" + version);
        Files.createDirectories(dir);
        Path zip = dir.resolve("uika-cli-" + version + "-" + classifier + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
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
