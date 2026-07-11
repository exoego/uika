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

        // Stub leaves a marker next to the --before file; ProcessBuilder.inheritIO output
        // is not reliably visible in the TestKit build output.
        publishStubCli(CLEAN_VERSION, """
                #!/bin/sh
                echo ran > "$3.marker"
                exit 0
                """);
        publishStubCli(VIOLATION_VERSION, """
                #!/bin/sh
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
    }

    @Test
    void violationExitCodeFailsTheBuild() {
        BuildResult result = runner(VIOLATION_VERSION).buildAndFail();

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

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
