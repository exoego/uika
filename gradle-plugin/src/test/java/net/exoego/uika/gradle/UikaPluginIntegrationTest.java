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
import java.util.LinkedHashMap;
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
        var output = projectDir.resolve("classpath.json");
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "dummy-uika-consumer"
                include("app")
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }
                """);
        var appDir = projectDir.resolve("app");
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

        var result = GradleRunner.create()
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
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        assertEquals(2, ((Number) doc.get("version")).intValue());
        // upgrade-check compares this across the before/after dumps to check the JDK move
        // too. This project declares no target, so what it compiles against IS the build JVM.
        assertEquals(Runtime.version().feature(),
                ((Number) doc.get("jdkRelease")).intValue(),
                "dump must record the release the application runs on");

        @SuppressWarnings("unchecked")
        var modules = (List<Map<String, Object>>) doc.get("modules");
        var appModule = modules.stream()
                .filter(module -> Objects.equals(":app", module.get("module")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(":app module is missing from " + modules));

        @SuppressWarnings("unchecked")
        var classesDirs =
                (List<Map<String, Object>>) appModule.get("classesDirs");
        assertFalse(classesDirs.isEmpty(), ":app classesDirs is empty");

        String firstClassesDir = rootedPath(doc, classesDirs.get(0));
        var expectedSuffix = "app/build/classes/java/main";
        assertTrue(firstClassesDir.endsWith(expectedSuffix),
                () -> "expected classes dir to end with " + expectedSuffix
                        + ", got " + firstClassesDir);
    }

    /// The dump records the release each module compiles for, not one value for the build:
    /// upgrade-check pairs them module by module, so a build that mixes releases gets its
    /// JDK move scoped to the modules that made it. The dump-level value is the lowest of
    /// them, the fallback for a module that declares nothing (like the root here).
    @Test
    void recordsTheReleaseEachModuleCompilesFor() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMixedReleaseProject();

        runDump(output);

        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        assertEquals(11, ((Number) doc.get("jdkRelease")).intValue(),
                "the dump-level release must be the lowest any module declares");
        assertEquals(11, moduleRelease(doc, ":older"));
        assertEquals(17, moduleRelease(doc, ":newer"));
        // The root applies no java plugin, so it dumps nothing at all and contributes no
        // release. Every module that IS dumped carries one: getTargetCompatibility() falls
        // back to the toolchain's language version, so a Java project always declares a
        // target even when the build script does not name one.
        assertTrue(moduleReleases(doc).values().stream().allMatch(Objects::nonNull),
                () -> "a dumped module carries no release: " + moduleReleases(doc));
    }

    /// The derivation only sees what the build declares, so a project compiling for 11 and
    /// shipping on 21 has no other way to say so. -PuikaJdkRelease replaces what every
    /// module declares, because it is a statement about the whole build.
    @Test
    void jdkReleasePropertyOverridesWhatTheModulesDeclare() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMixedReleaseProject();

        runDump(output, "-PuikaJdkRelease=21");

        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        assertEquals(21, ((Number) doc.get("jdkRelease")).intValue());
        assertEquals(Map.of(":older", 21, ":newer", 21), moduleReleases(doc));

        // 0 only switches the JDK API layer off. Recording nothing would take JDK move
        // detection down with it, which is a different feature.
        Files.delete(output);
        runDump(output, "-PuikaJdkRelease=0");

        @SuppressWarnings("unchecked")
        var derived = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        assertEquals(11, ((Number) derived.get("jdkRelease")).intValue());
        assertEquals(Map.of(":older", 11, ":newer", 17), moduleReleases(derived));
    }

    /// A below-floor override is dropped, and used to be dropped in silence on the dump
    /// side while the check side explained the same decision. Once for the build, not once
    /// per module: the property is build-wide.
    @Test
    void aBelowFloorJdkReleasePropertyExplainsItself() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMixedReleaseProject();

        var result = runDump(output, "-PuikaJdkRelease=5");

        var line = "ignoring jdkRelease 5";
        assertTrue(result.getOutput().contains(line),
                () -> "expected the dropped-override notice:\n" + result.getOutput());
        var occurrences = result.getOutput().split(java.util.regex.Pattern.quote(line), -1).length - 1;
        assertEquals(1, occurrences,
                () -> "the build-wide property explained itself " + occurrences + " times");
        // Dropped, so every module keeps what it compiles for.
        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        assertEquals(Map.of(":older", 11, ":newer", 17), moduleReleases(doc));
    }

    @Test
    void malformedJdkReleasePropertyFailsWithAUikaMessage() throws Exception {
        writeMixedReleaseProject();

        // A typo, and the bare spelling (Gradle sets a bare -P to the empty string). The
        // property is parsed during apply(), so an unguarded parse killed EVERY invocation,
        // `gradle tasks` included, with a raw NumberFormatException naming no uika anything.
        for (String property : List.of("-PuikaJdkRelease=eleven", "-PuikaJdkRelease")) {
            var result = GradleRunner.create()
                    .withProjectDir(projectDir.toFile())
                    .withArguments("tasks", property)
                    .withPluginClasspath()
                    .buildAndFail();
            assertTrue(result.getOutput().contains("-PuikaJdkRelease wants a whole number"),
                    "expected the uika-named parse error for " + property + ":\n"
                            + result.getOutput());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> moduleReleases(Map<String, Object> doc) {
        var modules = (List<Map<String, Object>>) doc.get("modules");
        var releases = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> module : modules) {
            releases.put((String) module.get("module"),
                    module.get("jdkRelease") instanceof Number n ? n.intValue() : null);
        }
        return releases;
    }

    @SuppressWarnings("unchecked")
    private static Integer moduleRelease(Map<String, Object> doc, String modulePath) {
        var modules = (List<Map<String, Object>>) doc.get("modules");
        var module = modules.stream()
                .filter(m -> Objects.equals(modulePath, m.get("module")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(modulePath + " is missing from " + modules));
        return module.get("jdkRelease") instanceof Number n ? n.intValue() : null;
    }

    /// Two subprojects targeting different releases, each through a different mechanism,
    /// and a root that applies no java plugin and so declares nothing.
    private void writeMixedReleaseProject() throws IOException {
        Files.createDirectories(projectDir.resolve("older"));
        Files.createDirectories(projectDir.resolve("newer"));
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "mixed"
                include("older", "newer")
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins { id("net.exoego.uika") }
                """);
        write(projectDir.resolve("older/build.gradle.kts"), """
                plugins { java }
                tasks.withType<JavaCompile>().configureEach { options.release = 11 }
                """);
        write(projectDir.resolve("newer/build.gradle.kts"), """
                plugins { java }
                java { targetCompatibility = JavaVersion.VERSION_17 }
                """);
    }

    @Test
    void secondRunPicksUpDependencyChanges() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeToggleJarProject();

        var first = runDump(output);
        assertTaskSuccess(first, ":app:uikaDumpModuleClasspath");
        assertTrue(artifactPaths(output).stream().anyMatch(p -> p.endsWith("first.jar")),
                "first.jar is missing from the initial dump");
        assertFalse(artifactPaths(output).stream().anyMatch(p -> p.endsWith("second.jar")),
                "second.jar should not be in the initial dump");

        var second = runDump(output, "-PuikaTestExtraJar=true");
        assertTaskSuccess(second, ":app:uikaDumpModuleClasspath");
        assertTrue(artifactPaths(output).stream().anyMatch(p -> p.endsWith("second.jar")),
                "dump does not reflect the dependency added after the first run");
    }

    /// Multi-module: a project dependency is attributed to its producing module ("project"
    /// key), and the default uikaBuildOutputs wiring builds the dependency jar and the
    /// module's own classes before dumping (no manual dependsOn, no pre-build step).
    @Test
    void attributesProjectDependenciesAndBuildsOutputsByDefault() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMultiModuleProject();

        var result = runDump(output);
        assertTaskSuccess(result, ":lib:jar");
        assertTaskSuccess(result, ":app:compileJava");
        assertTaskSuccess(result, ":app:uikaDumpModuleClasspath");
        assertAppAttributesLib(output);
    }

    /// -PuikaBuildOutputs=false keeps the old resolution-only dump: nothing is compiled,
    /// and the unbuilt project-dependency jar still appears in the dump with its project
    /// attribution, so the CLI can warn about (or substitute) the missing file instead of
    /// silently losing the module from the classpath.
    @Test
    void buildOutputsOptOutSkipsCompilation() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMultiModuleProject();

        var result = runDump(output, "-PuikaBuildOutputs=false");
        assertTrue(result.task(":lib:jar") == null,
                ":lib:jar must not run with uikaBuildOutputs=false");
        assertTrue(result.task(":app:compileJava") == null,
                ":app:compileJava must not run with uikaBuildOutputs=false");
        assertTaskSuccess(result, ":app:uikaDumpModuleClasspath");
        assertUnbuiltLibAttributed(output);
    }

    /// The dump invocation is configuration-cache compatible: the first run stores an
    /// entry, the second reuses it and still re-resolves (the dump is rewritten), and a
    /// dependency toggled through a gradle property invalidates the entry, so a reused
    /// cache can never pin a stale classpath.
    @Test
    void configurationCacheReusesDumpAndStaysCorrect() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeToggleJarProject();

        var first = runDump(output, "--configuration-cache");
        assertTaskSuccess(first, ":app:uikaDumpModuleClasspath");
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                () -> "no configuration cache entry was stored:\n" + first.getOutput());
        assertTrue(artifactPaths(output).stream().anyMatch(p -> p.endsWith("first.jar")),
                "first.jar is missing from the initial dump");

        Files.delete(output);
        var second = runDump(output, "--configuration-cache");
        assertTaskSuccess(second, ":app:uikaDumpModuleClasspath");
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                () -> "the configuration cache entry was not reused:\n" + second.getOutput());
        assertTrue(artifactPaths(output).stream().anyMatch(p -> p.endsWith("first.jar")),
                "first.jar is missing from the cache-reuse dump");

        var third = runDump(output, "--configuration-cache", "-PuikaTestExtraJar=true");
        assertTaskSuccess(third, ":app:uikaDumpModuleClasspath");
        assertTrue(artifactPaths(output).stream().anyMatch(p -> p.endsWith("second.jar")),
                "dump does not reflect the dependency added after the cached run");
    }

    /// Project attribution and the buildOutputs dependsOn wiring survive the configuration
    /// cache: a reused entry still builds the dependency jar and dumps the attribution.
    @Test
    void configurationCacheReusesMultiModuleDump() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMultiModuleProject();

        var first = runDump(output, "--configuration-cache");
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                () -> "no configuration cache entry was stored:\n" + first.getOutput());
        assertTaskSuccess(first, ":lib:jar");
        assertTaskSuccess(first, ":app:uikaDumpModuleClasspath");
        assertAppAttributesLib(output);

        Files.delete(output);
        var second = runDump(output, "--configuration-cache");
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                () -> "the configuration cache entry was not reused:\n" + second.getOutput());
        assertTaskSuccess(second, ":app:uikaDumpModuleClasspath");
        assertAppAttributesLib(output);
    }

    /// The resolution-only dump is configuration-cache compatible too. Its entries are
    /// resolved eagerly at configuration time (the resolution provider refuses any query
    /// while a producer task has not run), so the unbuilt project jar keeps its
    /// attribution on both the store and the reuse run, and still nothing is compiled.
    @Test
    void configurationCacheReusesResolutionOnlyDump() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMultiModuleProject();

        var first = runDump(output, "--configuration-cache", "-PuikaBuildOutputs=false");
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                () -> "no configuration cache entry was stored:\n" + first.getOutput());
        assertTrue(first.task(":lib:jar") == null,
                ":lib:jar must not run with uikaBuildOutputs=false");
        assertTaskSuccess(first, ":app:uikaDumpModuleClasspath");
        assertUnbuiltLibAttributed(output);

        Files.delete(output);
        var second = runDump(output, "--configuration-cache", "-PuikaBuildOutputs=false");
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                () -> "the configuration cache entry was not reused:\n" + second.getOutput());
        assertTrue(second.task(":lib:jar") == null,
                ":lib:jar must not run on the cache-reuse run");
        assertTaskSuccess(second, ":app:uikaDumpModuleClasspath");
        assertUnbuiltLibAttributed(output);
    }

    /** The unbuilt :lib jar is listed with its project attribution (the file need not exist). */
    @SuppressWarnings("unchecked")
    private static void assertUnbuiltLibAttributed(Path output) {
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        var artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        var libArtifact = artifacts.stream()
                .filter(a -> Objects.equals(":lib", a.get("project")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "unbuilt :lib jar missing from the resolution-only dump: " + artifacts));
        assertTrue(rootedPath(doc, libArtifact).endsWith("lib.jar"));
    }

    /** The :app module's dump attributes the :lib project-dependency jar and lists built classesDirs. */
    private static void assertAppAttributesLib(Path output) throws IOException {
        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        @SuppressWarnings("unchecked")
        var modules = (List<Map<String, Object>>) doc.get("modules");
        var appModule = modules.stream()
                .filter(module -> Objects.equals(":app", module.get("module")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(":app module is missing from " + modules));
        @SuppressWarnings("unchecked")
        var artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        @SuppressWarnings("unchecked")
        var refs = (List<Number>) appModule.get("artifactRefs");
        var libArtifact = refs.stream()
                .map(i -> artifacts.get(i.intValue()))
                .filter(a -> Objects.equals(":lib", a.get("project")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        ":app has no artifact attributed to :lib in " + artifacts));
        String libPath = rootedPath(doc, libArtifact);
        assertTrue(libPath.endsWith("lib.jar"), libPath);
        assertTrue(Files.isRegularFile(Path.of(libPath)),
                "the dependsOn wiring must have built " + libPath);
        @SuppressWarnings("unchecked")
        var classesDirs =
                (List<Map<String, Object>>) appModule.get("classesDirs");
        assertFalse(classesDirs.isEmpty(), ":app classesDirs is empty: " + appModule);
    }

    /** Single app module with a second file dependency toggled by -PuikaTestExtraJar. */
    private void writeToggleJarProject() throws IOException {
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "dummy-uika-consumer"
                include("app")
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }
                """);
        var appDir = projectDir.resolve("app");
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
    }

    private void writeMultiModuleProject() throws IOException {
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "dummy-uika-consumer"
                include("app")
                include("lib")
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }
                """);
        write(projectDir.resolve("lib/build.gradle.kts"), """
                plugins {
                    java
                }
                """);
        write(projectDir.resolve("lib/src/main/java/example/Lib.java"), """
                package example;

                public final class Lib {
                    public String name() {
                        return "lib";
                    }
                }
                """);
        write(projectDir.resolve("app/build.gradle.kts"), """
                plugins {
                    java
                }

                dependencies {
                    implementation(project(":lib"))
                }
                """);
        write(projectDir.resolve("app/src/main/java/example/App.java"), """
                package example;

                public final class App {
                    public String message() {
                        return new Lib().name();
                    }
                }
                """);
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
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        var artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        return artifacts.stream().map(a -> rootedPath(doc, a)).toList();
    }

    @SuppressWarnings("unchecked")
    private static String rootedPath(Map<String, Object> doc, Map<String, Object> rootedPath) {
        var roots = (List<String>) doc.get("roots");
        var root = ((Number) rootedPath.get("root")).intValue();
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
