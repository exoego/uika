package net.exoego.uika.gradle;

import groovy.json.JsonSlurper;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises uikaResolveClasspath against a fake Maven repository: a dump listing an artifact
 * whose file does not exist here is rehydrated by fetching the artifact through the build's
 * own repositories. Also pins configuration-cache compatibility (the missing notations are
 * wired at configuration time by the plugin).
 */
final class ResolveClasspathTaskIntegrationTest {
    @TempDir
    Path projectDir;

    @TempDir
    Path repoDir;

    @Test
    void rehydratesMissingArtifactAndReusesConfigurationCache() throws Exception {
        Path jar = repoDir.resolve("example/stub-lib/1.0.0/stub-lib-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[]{0x50, 0x4b, 0x05, 0x06});

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
        Path input = write(projectDir.resolve("before.json"), """
                {"modules":[{"module":":","classesDirs":[],"artifacts":[
                    {"group":"example","name":"stub-lib","version":"1.0.0",
                     "file":"/nonexistent/stub-lib-1.0.0.jar"}]}]}
                """);
        Path output = projectDir.resolve("before-local.json");

        BuildResult first = run(input, output);
        assertTaskSuccess(first);
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                () -> "no configuration cache entry was stored:\n" + first.getOutput());
        assertRewrittenToLocalJar(output);

        Files.delete(output);
        BuildResult second = run(input, output);
        assertTaskSuccess(second);
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                () -> "the configuration cache entry was not reused:\n" + second.getOutput());
        assertRewrittenToLocalJar(output);
    }

    private BuildResult run(Path input, Path output) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(
                        "uikaResolveClasspath",
                        "--configuration-cache",
                        "--stacktrace",
                        "-PuikaInput=" + input,
                        "-PuikaResolveOutput=" + output)
                .withPluginClasspath()
                .forwardOutput()
                .build();
    }

    private static void assertTaskSuccess(BuildResult result) {
        var task = result.task(":uikaResolveClasspath");
        assertNotNull(task, "task did not run");
        assertEquals(TaskOutcome.SUCCESS, task.getOutcome());
    }

    /** The v2 output must point the artifact at a real local file fetched by Gradle. */
    @SuppressWarnings("unchecked")
    private static void assertRewrittenToLocalJar(Path output) {
        Map<String, Object> doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        List<String> roots = (List<String>) doc.get("roots");
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        assertEquals(1, artifacts.size(), "unexpected artifacts: " + artifacts);
        Map<String, Object> artifact = artifacts.get(0);
        String path = roots.get(((Number) artifact.get("root")).intValue())
                + artifact.get("path");
        assertTrue(path.endsWith("stub-lib-1.0.0.jar"), path);
        assertTrue(Files.isRegularFile(Path.of(path)),
                "artifact was not rewritten to a fetched local file: " + path);
    }

    private static Path write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
