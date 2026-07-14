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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UikaPluginIntegrationTest {
    @TempDir
    Path projectDir;

    @Test
    void writesClasspathDumpFromGeneratedProject() throws Exception {
        Path output = projectDir.resolve("classpath.json");
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "dummy-uika-consumer"
                include("app")
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }
                """);
        Path appDir = projectDir.resolve("app");
        write(appDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                }

                tasks.named("uikaDumpModuleClasspath") {
                    dependsOn("classes")
                }
                """);
        write(appDir.resolve("src/main/java/example/App.java"), """
                package example;

                public final class App {
                    public String message() {
                        return "ok";
                    }
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(
                        ":app:classes",
                        "uikaDumpClasspath",
                        "--stacktrace",
                        "-PuikaOutput=" + output)
                .withPluginClasspath()
                .forwardOutput()
                .build();

        assertTaskSuccess(result, ":app:classes");
        assertTaskSuccess(result, ":app:uikaDumpModuleClasspath");
        assertTaskSuccess(result, ":uikaDumpClasspath");
        assertTrue(Files.isRegularFile(output), "classpath dump was not written: " + output);

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        assertEquals(2, ((Number) doc.get("version")).intValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modules = (List<Map<String, Object>>) doc.get("modules");
        Map<String, Object> appModule = modules.stream()
                .filter(module -> Objects.equals(":app", module.get("module")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(":app module is missing from " + modules));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classesDirs =
                (List<Map<String, Object>>) appModule.get("classesDirs");
        assertFalse(classesDirs.isEmpty(), ":app classesDirs is empty");

        String firstClassesDir = rootedPath(doc, classesDirs.get(0));
        String expectedSuffix = "app/build/classes/java/main";
        assertTrue(firstClassesDir.endsWith(expectedSuffix),
                () -> "expected classes dir to end with " + expectedSuffix
                        + ", got " + firstClassesDir);
    }

    @Test
    void secondRunPicksUpDependencyChanges() throws Exception {
        Path output = projectDir.resolve("classpath.json");
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "dummy-uika-consumer"
                include("app")
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }
                """);
        Path appDir = projectDir.resolve("app");
        write(appDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                }

                dependencies {
                    implementation(files("libs/first.jar"))
                    if (providers.gradleProperty("uikaTestExtraJar").isPresent) {
                        implementation(files("libs/second.jar"))
                    }
                }
                """);
        Files.createDirectories(appDir.resolve("libs"));
        Files.write(appDir.resolve("libs/first.jar"), new byte[0]);
        Files.write(appDir.resolve("libs/second.jar"), new byte[0]);

        BuildResult first = runDump(output);
        assertTaskSuccess(first, ":app:uikaDumpModuleClasspath");
        assertTrue(artifactPaths(output).stream().anyMatch(p -> p.endsWith("first.jar")),
                "first.jar is missing from the initial dump");
        assertFalse(artifactPaths(output).stream().anyMatch(p -> p.endsWith("second.jar")),
                "second.jar should not be in the initial dump");

        BuildResult second = runDump(output, "-PuikaTestExtraJar=true");
        assertTaskSuccess(second, ":app:uikaDumpModuleClasspath");
        assertTrue(artifactPaths(output).stream().anyMatch(p -> p.endsWith("second.jar")),
                "dump does not reflect the dependency added after the first run");
    }

    private BuildResult runDump(Path output, String... extraArgs) {
        List<String> args = Stream.concat(
                Stream.of("uikaDumpClasspath", "--stacktrace", "-PuikaOutput=" + output),
                Arrays.stream(extraArgs)).toList();
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(args)
                .withPluginClasspath()
                .forwardOutput()
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> artifactPaths(Path output) {
        Map<String, Object> doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        return artifacts.stream().map(a -> rootedPath(doc, a)).toList();
    }

    @SuppressWarnings("unchecked")
    private static String rootedPath(Map<String, Object> doc, Map<String, Object> rootedPath) {
        List<String> roots = (List<String>) doc.get("roots");
        int root = ((Number) rootedPath.get("root")).intValue();
        return roots.get(root) + rootedPath.get("path");
    }

    private static void assertTaskSuccess(BuildResult result, String taskPath) {
        var task = result.task(taskPath);
        assertNotNull(task, "task did not run: " + taskPath);
        assertEquals(TaskOutcome.SUCCESS, task.getOutcome(),
                () -> "task " + taskPath + " did not succeed");
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
