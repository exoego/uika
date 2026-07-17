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
