package net.exoego.uika.gradle;

import groovy.json.JsonSlurper;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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

    /// The version diff runs on these coordinates. An external dependency must keep them and
    /// must not be attributed to a project.
    @Test
    void writesExternalDependencyCoordinates() throws Exception {
        var output = projectDir.resolve("classpath.json");
        var repo = projectDir.resolve("repo");
        var jar = repo.resolve("example/stub-lib/1.0.0/stub-lib-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[0]);
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "dummy-uika-consumer"
                include("app")
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("net.exoego.uika")
                }
                """);
        write(projectDir.resolve("app/build.gradle.kts"), """
                plugins {
                    java
                }

                repositories {
                    maven {
                        url = uri("%s")
                        metadataSources { artifact() }
                    }
                }

                dependencies {
                    implementation("example:stub-lib:1.0.0")
                }
                """.formatted(repo.toUri()));

        runDump(output);

        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        @SuppressWarnings("unchecked")
        var artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        var stub = artifacts.stream()
                .filter(a -> Objects.equals("stub-lib", a.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("stub-lib is missing from " + artifacts));
        assertEquals("example", stub.get("group"));
        assertEquals("1.0.0", stub.get("version"));
        assertFalse(stub.containsKey("project"), () -> "attributed to a project: " + stub);
        assertTrue(rootedPath(doc, stub).endsWith("stub-lib-1.0.0.jar"), stub::toString);
    }

    /// A module built by two compilers has one classes dir for each, and the CLI must scan
    /// both. Groovy stands in for Kotlin because it compiles without the network.
    @Test
    void listsEveryBuiltClassesDir() throws Exception {
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
        write(projectDir.resolve("app/build.gradle.kts"), """
                plugins {
                    groovy
                }

                dependencies {
                    implementation(localGroovy())
                }
                """);
        write(projectDir.resolve("app/src/main/java/example/App.java"), """
                package example;

                public final class App {
                }
                """);
        write(projectDir.resolve("app/src/main/groovy/example/Greeter.groovy"), """
                package example

                class Greeter {
                }
                """);

        runDump(output);

        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        @SuppressWarnings("unchecked")
        var modules = (List<Map<String, Object>>) doc.get("modules");
        var appModule = modules.stream()
                .filter(module -> Objects.equals(":app", module.get("module")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(":app module is missing from " + modules));
        @SuppressWarnings("unchecked")
        var classesDirs = ((List<Map<String, Object>>) appModule.get("classesDirs")).stream()
                .map(dir -> rootedPath(doc, dir))
                .toList();
        assertEquals(2, classesDirs.size(), classesDirs::toString);
        assertTrue(classesDirs.stream().anyMatch(d -> d.endsWith("app/build/classes/java/main")),
                classesDirs::toString);
        assertTrue(classesDirs.stream().anyMatch(d -> d.endsWith("app/build/classes/groovy/main")),
                classesDirs::toString);
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

    /// -PuikaConfiguration naming something a module cannot resolve used to leave that
    /// module in the dump with its classesDirs and ZERO artifacts, silently: the emptyDump
    /// guard only covers projects with no Java at all, so the check then reported nothing
    /// broken for a module it was handed no classpath for.
    @Test
    void unresolvableConfigurationPropertyFailsInsteadOfEmptyingTheDump() throws Exception {
        writeMixedReleaseProject();

        // Absent everywhere, and present but not resolvable (implementation is a bucket you
        // declare into). Both produced the same silent empty artifact list.
        for (String name : List.of("nosuchConfiguration", "implementation")) {
            var result = GradleRunner.create()
                    .withProjectDir(projectDir.toFile())
                    .withArguments("uikaDumpClasspath", "--stacktrace",
                            "-PuikaOutput=" + projectDir.resolve("classpath.json"),
                            "-PuikaConfiguration=" + name)
                    .withPluginClasspath()
                    .buildAndFail();
            // The message must name the project, which is the whole point of it.
            assertTrue(result.getOutput().contains("-PuikaConfiguration"),
                    () -> "expected the uika-named error for " + name + ":\n"
                            + result.getOutput());
            // Naming the project is the message's whole value; which module trips first
            // is scheduling, so assert the shape rather than a particular path.
            assertTrue(result.getOutput().contains("project :"),
                    () -> "the error does not name the offending project for " + name + ":\n"
                            + result.getOutput());
        }
    }

    /// A resolvable non-default configuration passes the guard and is the one the dump reads:
    /// asked for compileClasspath, the dump holds a compileOnly jar and not a runtimeOnly one,
    /// the reverse of what the default runtimeClasspath gives.
    @Test
    void aResolvableConfigurationPropertySelectsWhatIsDumped() throws Exception {
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
                    compileOnly(files("libs/compile-only.jar"))
                    runtimeOnly(files("libs/runtime-only.jar"))
                }
                """);
        Files.createDirectories(appDir.resolve("libs"));
        Files.write(appDir.resolve("libs/compile-only.jar"), new byte[0]);
        Files.write(appDir.resolve("libs/runtime-only.jar"), new byte[0]);
        var output = projectDir.resolve("classpath.json");

        runDump(output);
        var byDefault = artifactPaths(output);
        assertTrue(byDefault.stream().anyMatch(p -> p.endsWith("runtime-only.jar")),
                "runtimeClasspath holds the runtimeOnly jar: " + byDefault);
        assertFalse(byDefault.stream().anyMatch(p -> p.endsWith("compile-only.jar")),
                "runtimeClasspath does not hold the compileOnly jar: " + byDefault);

        runDump(output, "-PuikaConfiguration=compileClasspath");
        var compile = artifactPaths(output);
        assertTrue(compile.stream().anyMatch(p -> p.endsWith("compile-only.jar")),
                "compileClasspath holds the compileOnly jar: " + compile);
        assertFalse(compile.stream().anyMatch(p -> p.endsWith("runtime-only.jar")),
                "compileClasspath does not hold the runtimeOnly jar: " + compile);
    }

    /// The failure belongs to the dump, not to the build. This same file catches an
    /// unsupported platform while wiring for the same reason: the per-module task is
    /// realized by `gradle tasks` and by IDE sync, and a bad value for a dump-only property
    /// must not break those.
    @Test
    void anUnresolvableConfigurationDoesNotBreakUnrelatedInvocations() throws Exception {
        writeMixedReleaseProject();

        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("tasks", "-PuikaConfiguration=nosuchConfiguration")
                .withPluginClasspath()
                .build();

        assertEquals(TaskOutcome.SUCCESS,
                Objects.requireNonNull(result.task(":tasks")).getOutcome());
    }

    /// The exemption is keyed on the java PLUGIN, which is what creates runtimeClasspath.
    /// JavaPluginExtension would be wrong: java-base creates it without a source set or a
    /// runtimeClasspath, so an Android or convention-plugin module would fail a build that
    /// never asked for a different configuration.
    @Test
    void aJavaBaseProjectIsExemptFromTheGuard() throws Exception {
        Files.createDirectories(projectDir.resolve("base"));
        write(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "mixed"
                include("base")
                """);
        write(projectDir.resolve("build.gradle.kts"), """
                plugins { id("net.exoego.uika") }
                """);
        // java-base gives the project a JavaPluginExtension and no runtimeClasspath.
        write(projectDir.resolve("base/build.gradle.kts"), """
                plugins { `java-base` }
                """);

        var output = projectDir.resolve("classpath.json");
        runDump(output, "-PuikaConfiguration=nosuchConfiguration");

        assertTrue(Files.exists(output), "dump was not written: " + output);
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

    /// Multi-module: a project dependency is dumped as its classes and resources directories,
    /// attributed to its producing module ("project" key), and the default uikaBuildOutputs
    /// wiring builds those directories and the module's own classes before dumping (no manual
    /// dependsOn, no pre-build step). The dependency's jar is never zipped: the directories
    /// are what the runtime classpath holds anyway, and zipping is the avoidable cost of a
    /// large multi-module build.
    @Test
    void attributesProjectDependenciesAndBuildsOutputsByDefault() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMultiModuleProject();

        var result = runDump(output);
        assertTrue(result.task(":lib:jar") == null, ":lib:jar must not run: the dump lists directories");
        assertTaskSuccess(result, ":lib:compileJava");
        assertTaskSuccess(result, ":lib:processResources");
        assertTaskSuccess(result, ":app:compileJava");
        assertTaskSuccess(result, ":app:uikaDumpModuleClasspath");
        assertAppAttributesLib(output);
    }

    /// A producer without resources never creates build/resources/main. The built dump drops
    /// the directory that is not there instead of naming a path the CLI cannot open, and
    /// still lists the classes directory.
    @Test
    void aProjectDependencyWithoutResourcesIsDumpedAsItsClassesAlone() throws Exception {
        var output = projectDir.resolve("classpath.json");
        writeMultiModuleProject(false);

        var result = runDump(output);
        assertTaskSuccess(result, ":lib:compileJava");
        assertTaskSuccess(result, ":app:uikaDumpModuleClasspath");
        var libPaths = libPathsListedByApp(output);
        assertEquals(1, libPaths.size(), ":app must list :lib's classes directory alone: " + libPaths);
        assertTrue(libPaths.get(0).endsWith(classesDir("lib")), libPaths.get(0));
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
        assertTrue(first.task(":lib:jar") == null, ":lib:jar must not run: the dump lists directories");
        assertTaskSuccess(first, ":lib:compileJava");
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

    /** The unbuilt :lib directories are listed with their project attribution (they need not exist). */
    @SuppressWarnings("unchecked")
    private static void assertUnbuiltLibAttributed(Path output) {
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        var artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        var libPaths = artifacts.stream()
                .filter(a -> Objects.equals(":lib", a.get("project")))
                .map(a -> rootedPath(doc, a))
                .toList();
        assertEquals(2, libPaths.size(), "unbuilt :lib directories missing from the resolution-only dump: " + artifacts);
        assertTrue(libPaths.get(0).endsWith(classesDir("lib")), libPaths.get(0));
        assertTrue(libPaths.get(1).endsWith(resourcesDir("lib")), libPaths.get(1));
    }

    private static String classesDir(String module) {
        return module + File.separator + "build" + File.separator + "classes" + File.separator + "java" + File.separator + "main";
    }

    private static String resourcesDir(String module) {
        return module + File.separator + "build" + File.separator + "resources" + File.separator + "main";
    }

    /** The :app module's dump attributes the :lib project-dependency jar and lists built classesDirs. */
    private static void assertAppAttributesLib(Path output) throws IOException {
        var libPaths = libPathsListedByApp(output);
        assertEquals(2, libPaths.size(), ":app must list :lib's classes and resources directories: " + libPaths);
        // Classes first, then resources: the order the classpath has them.
        assertTrue(libPaths.get(0).endsWith(classesDir("lib")), libPaths.get(0));
        assertTrue(libPaths.get(1).endsWith(resourcesDir("lib")), libPaths.get(1));
        for (String libPath : libPaths) {
            assertTrue(Files.isDirectory(Path.of(libPath)), "the dependsOn wiring must have built " + libPath);
        }
        assertTrue(Files.isRegularFile(Path.of(libPaths.get(1), "META-INF", "services", "example.Spi")),
                "the resources directory carries the service file the CLI reads providers from");
        @SuppressWarnings("unchecked")
        var classesDirs =
                (List<Map<String, Object>>) appModule(output).get("classesDirs");
        assertFalse(classesDirs.isEmpty(), ":app classesDirs is empty: " + appModule(output));
    }

    /** The paths :app's artifact list attributes to :lib, in the order the dump has them. */
    private static List<String> libPathsListedByApp(Path output) throws IOException {
        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        @SuppressWarnings("unchecked")
        var artifacts = (List<Map<String, Object>>) doc.get("artifacts");
        @SuppressWarnings("unchecked")
        var refs = (List<Number>) appModule(output).get("artifactRefs");
        return refs.stream()
                .map(i -> artifacts.get(i.intValue()))
                .filter(a -> Objects.equals(":lib", a.get("project")))
                .map(a -> rootedPath(doc, a))
                .toList();
    }

    private static Map<String, Object> appModule(Path output) throws IOException {
        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) new JsonSlurper().parse(output.toFile());
        @SuppressWarnings("unchecked")
        var modules = (List<Map<String, Object>>) doc.get("modules");
        return modules.stream()
                .filter(module -> Objects.equals(":app", module.get("module")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(":app module is missing from " + modules));
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
        writeMultiModuleProject(true);
    }

    private void writeMultiModuleProject(boolean libHasResources) throws IOException {
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
        // java-library, the shape a producer has in a real build: a consumer then compiles
        // against its classes, so nothing in the dump's task graph needs the jar. With the
        // plain java plugin the consumer's compileJava itself wants the jar, which is
        // Gradle's own dependency and not the dump's.
        write(projectDir.resolve("lib/build.gradle.kts"), """
                plugins {
                    `java-library`
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
        if (libHasResources) {
            // A resource, so :lib's resources directory exists and the dump can be seen to list it.
            write(projectDir.resolve("lib/src/main/resources/META-INF/services/example.Spi"), "example.Lib\n");
        }
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
