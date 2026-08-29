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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises uikaResolveClasspath against a fake Maven repository: a dump listing an artifact
 * whose file does not exist here is rehydrated by fetching the artifact through the build's
 * own repositories. Also pins configuration-cache compatibility (the missing notations are
 * wired at configuration time by the plugin, and the dump content is a tracked input).
 */
final class ResolveClasspathTaskIntegrationTest {
    @TempDir
    Path projectDir;

    @TempDir
    Path repoDir;

    @Test
    void rehydratesMissingArtifactAndReusesConfigurationCache() throws Exception {
        publishStubJar("stub-lib");
        publishStubJar("stub-lib2");
        writeProject();
        Path input = write(projectDir.resolve("before.json"), dumpReferencing("stub-lib"));
        Path output = projectDir.resolve("before-local.json");

        BuildResult first = run(input, output).build();
        assertTaskSuccess(first);
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                () -> "no configuration cache entry was stored:\n" + first.getOutput());
        assertRewrittenToLocalJars(output, "stub-lib-1.0.0.jar");

        Files.delete(output);
        BuildResult second = run(input, output).build();
        assertTaskSuccess(second);
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                () -> "the configuration cache entry was not reused:\n" + second.getOutput());
        assertRewrittenToLocalJars(output, "stub-lib-1.0.0.jar");

        // The dump content is a tracked configuration input: adding a second missing
        // artifact must invalidate the entry so its detached configuration is set up.
        // A stale reuse would leave stub-lib2 unresolved at its /nonexistent path.
        write(input, dumpReferencing("stub-lib", "stub-lib2"));
        Files.delete(output);
        BuildResult third = run(input, output).build();
        assertTaskSuccess(third);
        assertTrue(third.getOutput().contains("Configuration cache entry stored"),
                () -> "a changed dump must store a new entry, not reuse:\n" + third.getOutput());
        assertRewrittenToLocalJars(output, "stub-lib-1.0.0.jar", "stub-lib2-1.0.0.jar");
    }

    /// The input dump must exist before the build starts. When a task produces it
    /// mid-build, the plugin saw nothing at configuration time, so the action must fail
    /// with the explicit message instead of silently skipping the fetch.
    @Test
    void failsExplicitlyWhenInputAppearsMidBuild() throws Exception {
        writeProject();
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }

                val makeDump by tasks.registering {
                    val dump = layout.projectDirectory.file("before.json").asFile
                    doLast {
                        dump.writeText(
                            "{\\"modules\\":[{\\"module\\":\\":\\",\\"classesDirs\\":[]," +
                            "\\"artifacts\\":[{\\"group\\":\\"example\\",\\"name\\":\\"stub-lib\\"," +
                            "\\"version\\":\\"1.0.0\\",\\"file\\":\\"/nonexistent/stub-lib-1.0.0.jar\\"}]}]}")
                    }
                }

                tasks.named("uikaResolveClasspath") {
                    dependsOn(makeDump)
                }
                """);
        Path input = projectDir.resolve("before.json");
        Path output = projectDir.resolve("before-local.json");

        BuildResult result = run(input, output).buildAndFail();
        assertTrue(result.getOutput().contains("did not exist when the build was configured"),
                () -> "expected the explicit mid-build error:\n" + result.getOutput());
    }

    /// An artifact absent from every repository (an expired snapshot, say) must fail the
    /// task rather than write a dump naming another machine's path: rehydration success is
    /// what makes the workflow skip its checkout-based fallback, and the check exits 2 on
    /// a compared-pair jar it cannot open.
    @Test
    void failsWhenAnArtifactCannotBeResolved() throws Exception {
        publishStubJar("stub-lib");
        writeProject();
        Path input = write(projectDir.resolve("before.json"),
                dumpReferencing("stub-lib", "stub-unavailable"));
        Path output = projectDir.resolve("before-local.json");

        BuildResult result = run(input, output).buildAndFail();
        assertTrue(result.getOutput().contains(
                        "could not resolve example:stub-unavailable:1.0.0"),
                () -> "expected the per-artifact warning:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("1 artifact(s) could not be resolved"),
                () -> "expected the failure naming the unresolved count:\n" + result.getOutput());
        assertTrue(Files.notExists(output),
                "no output must be written for a partially rehydrated dump");
    }

    private void writeProject() throws IOException {
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
    }

    private void publishStubJar(String name) throws IOException {
        Path jar = repoDir.resolve("example/" + name + "/1.0.0/" + name + "-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[]{0x50, 0x4b, 0x05, 0x06});
    }

    /** v1 dump whose artifacts all point at nonexistent local paths. */
    private static String dumpReferencing(String... names) {
        StringBuilder artifacts = new StringBuilder();
        for (String name : names) {
            if (artifacts.length() > 0) {
                artifacts.append(',');
            }
            artifacts.append("""
                    {"group":"example","name":"%s","version":"1.0.0",
                     "file":"/nonexistent/%s-1.0.0.jar"}""".formatted(name, name));
        }
        return """
                {"modules":[{"module":":","classesDirs":[],"artifacts":[%s]}]}
                """.formatted(artifacts);
    }

    private GradleRunner run(Path input, Path output) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(
                        "uikaResolveClasspath",
                        "--configuration-cache",
                        "--stacktrace",
                        "-PuikaInput=" + input,
                        "-PuikaResolveOutput=" + output)
                .withPluginClasspath()
                .forwardOutput();
    }

    private static void assertTaskSuccess(BuildResult result) {
        var task = result.task(":uikaResolveClasspath");
        assertNotNull(task, "task did not run");
        assertEquals(TaskOutcome.SUCCESS, task.getOutcome());
    }

    /** The v2 output must point every artifact at a real local file fetched by Gradle. */
    @SuppressWarnings("unchecked")
    private static void assertRewrittenToLocalJars(Path output, String... jarNames) {
        Map<String, Object> doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        List<String> roots = (List<String>) doc.get("roots");
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        assertEquals(jarNames.length, artifacts.size(), "unexpected artifacts: " + artifacts);
        Set<String> paths = artifacts.stream()
                .map(a -> roots.get(((Number) a.get("root")).intValue()) + a.get("path"))
                .collect(Collectors.toSet());
        for (String jarName : jarNames) {
            String match = paths.stream()
                    .filter(p -> p.endsWith(jarName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            jarName + " missing from rewritten dump: " + paths));
            assertTrue(Files.isRegularFile(Path.of(match)),
                    "artifact was not rewritten to a fetched local file: " + match);
        }
    }

    private static Path write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
