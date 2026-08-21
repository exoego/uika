package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;

/**
 * uika integration plugin.
 * When applied to the root project, it creates:
 * - uikaDumpModuleClasspath in each module (writes that module's resolved classpath as a JSON fragment)
 * - uikaDumpClasspath in the root (merges fragments into one JSON file)
 * Gradle does not allow resolving other projects' configurations at execution time, so
 * resolution must happen in each module's own task.
 *
 * <p>All project state the tasks need is wired in as task properties after each project
 * evaluates ({@code afterEvaluate} + {@code TaskProvider.configure}, so user configuration
 * from the build script is already applied and the wiring only runs for realized tasks).
 * The task actions never touch {@code getProject()}, which keeps every task
 * configuration-cache compatible.
 *
 * <p>Usage (CI for dependency update PRs):
 * <pre>
 *   git checkout base && ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json
 *   git checkout head && ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
 *   ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
 * </pre>
 */
public class UikaPlugin implements Plugin<Project> {
    /**
     * The JDK API release the checked application most plausibly runs on: the root project's
     * toolchain language version when configured, else its target compatibility, else the JVM
     * running the build (a multi-module root often has no java plugin at all).
     */
    static Integer defaultJdkRelease(Project root) {
        JavaPluginExtension java = root.getExtensions().findByType(JavaPluginExtension.class);
        if (java != null) {
            var languageVersion = java.getToolchain().getLanguageVersion();
            if (languageVersion.isPresent()) {
                return languageVersion.get().asInt();
            }
            return Integer.parseInt(java.getTargetCompatibility().getMajorVersion());
        }
        return Runtime.version().feature();
    }

    /** One non-transitive detached configuration for a single notation. Detached because the
     * plugin must not mutate the build's own configurations, and one per notation because
     * multiple versions of a module in one configuration would be conflict-resolved down to
     * the highest. */
    private static Configuration detachedFor(Project root, String notation) {
        ModuleDependency dependency =
                (ModuleDependency) root.getDependencies().create(notation);
        dependency.setTransitive(false);
        Configuration configuration =
                root.getConfigurations().detachedConfiguration(dependency);
        configuration.setTransitive(false);
        return configuration;
    }

    @Override
    public void apply(Project root) {
        String configurationName = root.findProperty("uikaConfiguration") instanceof String s
                ? s
                : "runtimeClasspath";

        TaskProvider<MergeClasspathTask> merge =
                root.getTasks().register("uikaDumpClasspath", MergeClasspathTask.class, task -> {
                    task.setGroup("uika");
                    task.setDescription("Merge resolved classpaths for all modules into uika JSON");
                    Object override = root.findProperty("uikaOutput");
                    if (override != null) {
                        task.getOutputFile().set(root.file(override.toString()));
                    } else {
                        task.getOutputFile().convention(
                                root.getLayout().getBuildDirectory().file("uika/classpath.json"));
                    }
                    task.getRootDirPath().set(root.getProjectDir().getAbsolutePath());
                });

        TaskProvider<ResolveClasspathTask> resolve =
                root.getTasks().register("uikaResolveClasspath", ResolveClasspathTask.class, task -> {
                    task.setGroup("uika");
                    task.setDescription("Rehydrate a classpath dump from another environment into real paths here (Gradle fetches missing JARs)");
                    Object input = root.findProperty("uikaInput");
                    if (input != null) {
                        task.getInputFile().set(root.file(input.toString()));
                    }
                    Object output = root.findProperty("uikaResolveOutput");
                    if (output != null) {
                        task.getOutputFile().set(root.file(output.toString()));
                    } else {
                        task.getOutputFile().convention(
                                root.getLayout().getBuildDirectory().file("uika/classpath-local.json"));
                    }
                    task.getRootDirPath().set(root.getProjectDir().getAbsolutePath());
                    task.getWiredAtConfiguration().convention(false);
                });
        // The missing notations come from the input dump's content, so resolution must be
        // set up at configuration time (a detached configuration per notation) for the task
        // action to stay configuration-cache compatible. After evaluation so a build-script
        // inputFile.set() is visible; the input therefore must exist before the build starts.
        // The content is read through providers.fileContents, which registers it as a
        // configuration input: a cached entry is never reused for a changed dump.
        root.afterEvaluate(r -> resolve.configure(task -> {
            String content = root.getProviders()
                    .fileContents(task.getInputFile())
                    .getAsText()
                    .getOrNull();
            if (content == null) {
                return;
            }
            for (String notation
                    : ResolveClasspathTask.wantedNotations(ResolveClasspathTask.parseModules(content))) {
                task.getResolvedFiles().addAll(detachedFor(root, notation).getIncoming()
                        .artifactView(view -> view.lenient(true))
                        .getArtifacts()
                        .getResolvedArtifacts()
                        .map(ResolveClasspathTask::toResolvedFiles));
            }
            task.getWiredAtConfiguration().set(true);
        }));

        // Computed ONCE and shared by the Test-task injection and the uikaUpgradeCheck
        // wiring below: evaluating the bare-property default (the root build directory)
        // twice at different configuration phases let a script that relocates
        // layout.buildDirectory after the plugins block make the tests write one
        // directory while the check read another.
        File classLoadLogDir = classLoadLogDir(root);

        TaskProvider<UpgradeCheckTask> upgradeCheck =
                root.getTasks().register("uikaUpgradeCheck", UpgradeCheckTask.class, task -> {
                    task.setGroup("uika");
                    task.setDescription("Run uika upgrade-check between two dumps (the CLI binary is fetched via this build's repositories)");
                    Object before = root.findProperty("uikaBefore");
                    if (before != null) {
                        task.getBeforeFile().set(root.file(before.toString()));
                    }
                    Object after = root.findProperty("uikaAfter");
                    if (after != null) {
                        task.getAfterFile().set(root.file(after.toString()));
                    }
                    Object cliVersion = root.findProperty("uikaCliVersion");
                    if (cliVersion != null) {
                        task.getCliVersion().set(cliVersion.toString());
                    } else {
                        // Default to the plugin's own version (Implementation-Version in the plugin jar),
                        // so bumping the plugin coordinate also bumps the CLI.
                        String own = UikaPlugin.class.getPackage().getImplementationVersion();
                        if (own != null) {
                            task.getCliVersion().convention(own);
                        }
                    }
                    Object failOn = root.findProperty("uikaFailOn");
                    task.getFailOn().convention(failOn != null ? failOn.toString() : "any");
                    Object excludeFile = root.findProperty("uikaExcludeFile");
                    if (excludeFile != null) {
                        task.getExcludeFiles().from(root.file(excludeFile.toString()));
                    }
                    if (classLoadLogDir != null) {
                        task.getClassLoadLogs().from(classLoadLogDir);
                    }
                    Object draftExcludeFile = root.findProperty("uikaDraftExcludeFile");
                    if (draftExcludeFile != null) {
                        task.getDraftExcludeFile().set(root.file(draftExcludeFile.toString()));
                    }
                    Object jdkRelease = root.findProperty("uikaJdkRelease");
                    if (jdkRelease != null) {
                        task.getJdkRelease().set(Integer.parseInt(jdkRelease.toString()));
                    } else {
                        // The build knows its JDK, so the JDK API layer defaults ON here (the bare
                        // CLI keeps it opt-in): toolchain, else target compatibility, else the JVM
                        // running the build. UikaCli.effectiveJdkRelease clamps at execution time.
                        // The provider is an @Input, so the configuration cache evaluates it while
                        // the project is still available.
                        task.getJdkRelease().convention(
                                root.getProviders().provider(() -> defaultJdkRelease(root)));
                    }
                    task.getInstallDir().convention(root.getLayout().getBuildDirectory().dir("uika/cli"));
                    // The root tasks carry no data dependencies on each other, but a single
                    // invocation (dump + resolve + check) must run the check last so it
                    // reads the files the others just wrote. Soft ordering only: standalone
                    // invocations are unaffected, and the referenced tasks are not pulled
                    // into the graph.
                    task.mustRunAfter(merge, resolve);
                });
        // The CLI ZIP's detached configuration is created after evaluation, when the version
        // (convention, -PuikaCliVersion, or a build-script override) is final. Absent version
        // stays unwired; the action reports the friendly error.
        root.afterEvaluate(r -> upgradeCheck.configure(task -> {
            if (!task.getCliVersion().isPresent()) {
                return;
            }
            String classifier;
            try {
                classifier = UikaCli.platformClassifier();
            } catch (IllegalStateException unsupportedPlatform) {
                // Wiring runs whenever the task is realized (IDE sync, `gradle tasks`),
                // so an unsupported platform must not fail here; the task action calls
                // platformClassifier() again and reports the same error on execution.
                return;
            }
            String notation = UikaCli.GROUP + ":" + UikaCli.ARTIFACT + ":"
                    + task.getCliVersion().get() + ":" + classifier + "@zip";
            task.getCliZip().from(detachedFor(root, notation));
        }));

        // -PuikaClassLoadLog=<dir> makes every Test task write a runtime class-load log
        // into <dir> (one file per process — %p — because -Xlog truncates a shared file on
        // open), which is exactly what uikaUpgradeCheck reads back as --class-load-log.
        // One property serves both phases: the base branch's test run collects, the
        // dependency PR's check consumes. Three deliberate choices, each verified against
        // Gradle 9.7.0:
        // - jvmArgumentProviders, not jvmArgs: a build script's `jvmArgs = listOf(...)`
        //   setter replaces the list and silently discarded the injected flag; provider
        //   args survive it and still show in getAllJvmArgs().
        // - upToDateWhen(false) + doNotCacheIf: provider args are not fingerprinted, and
        //   an UP-TO-DATE or FROM-CACHE Test task forks no JVM — the collect run would
        //   upload an empty artifact with no symptom (promote-only evidence). Collection
        //   is a side effect that needs a real run, so asking for it defeats both.
        // - the doFirst mkdirs is load-bearing: -Xlog aborts JVM startup when the log
        //   file's directory does not exist.
        // Configuration only touches task properties and lambdas capturing a File/String,
        // so the tasks stay configuration-cache compatible.
        if (classLoadLogDir != null) {
            File dir = classLoadLogDir;
            root.allprojects(p -> p.getTasks()
                    .withType(org.gradle.api.tasks.testing.Test.class)
                    .configureEach(test -> {
                        String prefix = (p.getPath().equals(":") ? "root" : p.getPath())
                                .replaceAll("[^A-Za-z0-9._-]", "_")
                                + "-" + test.getName();
                        String arg = UikaCli.classLoadLogJvmArg(dir.toPath(), prefix);
                        test.getJvmArgumentProviders().add(() -> java.util.List.of(arg));
                        test.getOutputs().upToDateWhen(t -> false);
                        test.getOutputs().doNotCacheIf(
                                "uika class-load log collection needs a real JVM run",
                                t -> true);
                        test.doFirst("uika class-load log directory", t -> dir.mkdirs());
                    }));
        }

        root.allprojects(p -> {
            TaskProvider<DumpModuleClasspathTask> moduleTask = p.getTasks().register(
                    "uikaDumpModuleClasspath", DumpModuleClasspathTask.class, task -> {
                        task.setDescription("Write this module's resolved classpath as a uika JSON fragment");
                        task.getOutputFile().convention(
                                p.getLayout().getBuildDirectory().file("uika/module-classpath.json"));
                        task.getConfigurationName().convention(configurationName);
                        task.getModulePath().set(p.getPath());
                        task.getEmptyDump().convention(false);
                        // Build the outputs the dump refers to (project-dependency jars and
                        // this module's own classes) before dumping, so the CLI never scans
                        // a classpath with unbuilt holes. Opt out with
                        // -PuikaBuildOutputs=false for a resolution-only dump. One lazy
                        // provider, evaluated at task-graph time: the java plugin may not be
                        // applied yet at registration, and the property may be set after
                        // apply. A Configuration is Buildable, so depending on it builds
                        // project dependencies' jars; the main SourceSetOutput builds this
                        // module's classes (matching the dumped classesDirs).
                        task.dependsOn(p.provider(() -> {
                            if (!buildOutputs(root)) {
                                return java.util.List.of();
                            }
                            var dependencies = new java.util.ArrayList<>();
                            var conf = p.getConfigurations().findByName(
                                    task.getConfigurationName().get());
                            if (conf != null && conf.isCanBeResolved()) {
                                dependencies.add(conf);
                            }
                            var main = DumpModuleClasspathTask.mainSourceSet(p);
                            if (main != null) {
                                dependencies.add(main.getOutput());
                            }
                            return dependencies;
                        }));
                    });
            // Wire the module's state in once every project is evaluated, so the java
            // plugin, any build-script configuration (configurationName, uikaBuildOutputs),
            // and the dependency projects' outgoing variants are all settled. The lenient
            // artifact view lists project-dependency JARs even when they have not been
            // built (the CLI falls back to the producing module's classesDirs).
            p.getGradle().projectsEvaluated(gradle -> moduleTask.configure(task -> {
                Configuration conf =
                        p.getConfigurations().findByName(task.getConfigurationName().get());
                JavaPluginExtension javaExt =
                        p.getExtensions().findByType(JavaPluginExtension.class);
                task.getEmptyDump().set(javaExt == null && conf == null);
                SourceSet main = DumpModuleClasspathTask.mainSourceSet(p);
                if (main != null) {
                    task.getClassesDirs().from(main.getOutput().getClassesDirs());
                    task.getCompilableSources().from(
                            main.getAllSource().minus(main.getResources()));
                }
                if (conf != null && conf.isCanBeResolved()) {
                    var artifacts = conf.getIncoming()
                            .artifactView(view -> view.lenient(true))
                            .getArtifacts();
                    if (buildOutputs(root)) {
                        // Default: the dependsOn wiring builds the producer tasks, so the
                        // resolution provider may resolve lazily at execution time (in
                        // parallel across module tasks).
                        task.getArtifactEntries().set(artifacts.getResolvedArtifacts()
                                .map(DumpModuleClasspathTask::toEntries));
                    } else {
                        // Resolution-only dump: unbuilt project jars must still be listed,
                        // but the resolution provider refuses any query (configuration or
                        // execution time) while a producer task has not run. Iterate the
                        // ArtifactCollection directly instead, now, at configuration time;
                        // that is plain eager resolution without the producer guard, and
                        // the extracted entries are what the configuration cache stores.
                        task.getArtifactEntries().set(
                                DumpModuleClasspathTask.toEntries(artifacts.getArtifacts()));
                    }
                }
            }));
            merge.configure(m -> m.getFragments().from(moduleTask));
        });
    }

    private static boolean buildOutputs(Project root) {
        return !"false".equals(String.valueOf(root.findProperty("uikaBuildOutputs")));
    }

    /**
     * The directory named by {@code -PuikaClassLoadLog}, or null when the property is
     * absent. An empty value (bare {@code -PuikaClassLoadLog}) means the default
     * {@code <root build dir>/uika/class-load}, so a local collect-then-check loop needs no
     * path at all. A value naming an existing regular file is rejected here with the
     * property's own vocabulary: the CLI flag accepts a log file, but this knob also
     * drives Test-task collection, which composes {@code <value>/<prefix>-%p.log} — a
     * file value would make every test JVM abort at startup with an -Xlog error that
     * never mentions uika.
     */
    private static File classLoadLogDir(Project root) {
        Object value = root.findProperty("uikaClassLoadLog");
        if (value == null) {
            return null;
        }
        String path = value.toString();
        if (path.isEmpty()) {
            return root.getLayout().getBuildDirectory().dir("uika/class-load")
                    .get().getAsFile();
        }
        File dir = root.file(path);
        if (dir.isFile()) {
            throw new org.gradle.api.GradleException(
                    "-PuikaClassLoadLog must name a directory (test JVMs write per-process"
                            + " log files into it), but " + dir + " is a file");
        }
        return dir;
    }
}
