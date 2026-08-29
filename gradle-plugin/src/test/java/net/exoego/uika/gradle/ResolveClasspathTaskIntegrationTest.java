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
        var output = projectDir.resolve("before-local.json");

        var first = run(input, output).build();
        assertTaskSuccess(first);
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                () -> "no configuration cache entry was stored:\n" + first.getOutput());
        assertRewrittenToLocalJars(output, "stub-lib-1.0.0.jar");

        Files.delete(output);
        var second = run(input, output).build();
        assertTaskSuccess(second);
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                () -> "the configuration cache entry was not reused:\n" + second.getOutput());
        assertRewrittenToLocalJars(output, "stub-lib-1.0.0.jar");

        // The dump content is a tracked configuration input: adding a second missing
        // artifact must invalidate the entry so its detached configuration is set up.
        // A stale reuse would leave stub-lib2 unresolved at its /nonexistent path.
        write(input, dumpReferencing("stub-lib", "stub-lib2"));
        Files.delete(output);
        var third = run(input, output).build();
        assertTaskSuccess(third);
        assertTrue(third.getOutput().contains("Configuration cache entry stored"),
                () -> "a changed dump must store a new entry, not reuse:\n" + third.getOutput());
        assertRewrittenToLocalJars(output, "stub-lib-1.0.0.jar", "stub-lib2-1.0.0.jar");
    }

    /// A Maven-produced dump writes coordinates AND the project key on reactor
    /// dependencies. Those entries stay out of the repository fetch: their coordinates may
    /// name a stale PUBLISHED release, and a fetched file would permanently bypass the
    /// CLI's fallback to the producing module's classesDirs. Left untouched, the
    /// attribution also survives, so the CLI keeps excluding the coordinates from the
    /// version diff instead of reporting the reactor dependency as Removed.
    @Test
    void leavesReactorAttributedArtifactsAloneAndKeepsTheirAttribution() throws Exception {
        publishStubJar("stub-lib");
        publishStubJar("reactor-lib");
        writeProject();
        Path input = write(projectDir.resolve("before.json"), """
                {"modules":[{"module":":","classesDirs":[],"artifacts":[
                  {"group":"example","name":"stub-lib","version":"1.0.0",
                   "file":"/nonexistent/stub-lib-1.0.0.jar"},
                  {"group":"example","name":"reactor-lib","version":"1.0.0",
                   "file":"/nonexistent/reactor-lib-1.0.0.jar","project":":lib"}]}]}
                """);
        var output = projectDir.resolve("before-local.json");

        var result = run(input, output).build();
        assertTaskSuccess(result);
        // Pins three things at once: the external entry really was rewritten (a lenient
        // fetch that silently failed would leave it intact and this test vacuous), the
        // reactor entry was neither fetched nor counted unresolved, and nothing warned.
        assertTrue(result.getOutput().contains("(1 rewritten, 0 unresolved)"),
                () -> "expected exactly the external entry rewritten:\n" + result.getOutput());

        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        @SuppressWarnings("unchecked")
        var artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        var reactor = artifacts.stream()
                .filter(artifact -> "reactor-lib".equals(artifact.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("reactor-lib missing from " + artifacts));
        assertEquals(":lib", reactor.get("project"),
                "the project attribution was dropped");
        @SuppressWarnings("unchecked")
        var roots = (List<String>) doc.get("roots");
        assertEquals("/nonexistent/reactor-lib-1.0.0.jar",
                roots.get(((Number) reactor.get("root")).intValue()) + reactor.get("path"),
                "the reactor entry must pass through untouched, published jars included");
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
        var input = projectDir.resolve("before.json");
        var output = projectDir.resolve("before-local.json");

        var result = run(input, output).buildAndFail();
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
        var output = projectDir.resolve("before-local.json");

        var result = run(input, output).buildAndFail();
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
        var jar = repoDir.resolve("example/" + name + "/1.0.0/" + name + "-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        // A real empty zip, not just the 4-byte EOCD signature: anything that ever opens
        // the jar (an artifact transform, dependency verification) rejects a truncated one.
        byte[] emptyZip = new byte[22];
        emptyZip[0] = 0x50;
        emptyZip[1] = 0x4b;
        emptyZip[2] = 0x05;
        emptyZip[3] = 0x06;
        Files.write(jar, emptyZip);
    }

    /** v1 dump whose artifacts all point at nonexistent local paths. */
    private static String dumpReferencing(String... names) {
        var artifacts = new StringBuilder();
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
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        var roots = (List<String>) doc.get("roots");
        var artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        assertEquals(jarNames.length, artifacts.size(), "unexpected artifacts: " + artifacts);
        var paths = artifacts.stream()
                .map(a -> roots.get(((Number) a.get("root")).intValue()) + a.get("path"))
                .collect(Collectors.toSet());
        for (String jarName : jarNames) {
            var match = paths.stream()
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
