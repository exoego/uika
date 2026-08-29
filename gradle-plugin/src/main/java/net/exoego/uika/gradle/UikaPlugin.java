package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;

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
    /** What every project the java plugin touches resolves, and what {@code -PuikaConfiguration}
     * replaces. Named because {@link #requireResolvable} treats it as the one name that needs no
     * guard. */
    private static final String DEFAULT_CONFIGURATION = "runtimeClasspath";

    /**
     * The JDK API release the checked application runs on, the LOWEST any project in the
     * build targets.
     *
     * <p>The lowest, because {@code --jdk-release} is one flag for a run that checks every
     * module, and a mixed-toolchain build has no single right answer. Under-claiming turns a
     * member that exists at runtime into NotFound on both sides, which stays unreported as an
     * Unknown. Over-claiming makes a member the runtime does not have resolve cleanly and
     * loses the finding with nothing to show for it. The dump keeps each module's own
     * release next to it ({@link DumpModuleClasspathTask#getJdkRelease}), which is what lets
     * upgrade-check scope a JDK move to the modules that made it; the flag stays one value
     * because the layer it switches on is process-wide.
     *
     * <p>The root project alone is not enough. A multi-module root usually has no java plugin,
     * which used to fall straight through to the JVM running the build.
     */
    static Integer defaultJdkRelease(Project root) {
        Integer lowest = null;
        for (Project project : root.getAllprojects()) {
            Integer release = declaredRelease(project);
            if (release != null) {
                lowest = lowest == null ? release : Math.min(lowest, release);
            }
        }
        // No project declares a servable target, so the build JVM is the only evidence there is.
        return lowest == null ? Runtime.version().feature() : lowest;
    }

    /**
     * {@code -PuikaJdkRelease} as a number, or null when the property is absent.
     *
     * <p>Parse failures become a uika-named error on purpose: this runs during
     * {@code apply()}, so a typo or the bare spelling (Gradle sets a bare {@code -P} to the
     * empty string) would otherwise kill every invocation, {@code gradle tasks} included,
     * with a raw NumberFormatException naming no uika anything.
     */
    static Integer jdkReleaseProperty(Project root) {
        var value = root.findProperty("uikaJdkRelease");
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new GradleException(
                    "-PuikaJdkRelease wants a whole number (0 disables the JDK API layer), got \""
                            + value + "\"");
        }
    }

    /**
     * The release ONE module records in the dump: {@code -PuikaJdkRelease} when it is set,
     * else what that project compiles for.
     *
     * <p>The override replaces every module's own value rather than sitting beside them,
     * because it is a statement about the whole build. It exists here for the case the
     * derivation cannot see: a build compiling {@code --release 11} that ships on a 21
     * runtime has no other way to say so, and without it upgrade-check would never notice
     * that runtime moving.
     */
    private static Integer dumpJdkRelease(Project root, Project project) {
        Integer override = UikaCli.overrideRelease(jdkReleaseProperty(root));
        return override != null ? override : declaredRelease(project);
    }

    /**
     * What one project's bytecode runs on, or null when it declares nothing servable.
     *
     * <p>{@code options.release} first, because it is the only one of the three that Gradle
     * documents as pinning the API, and the toolchain is deliberately not consulted at all.
     * The toolchain is the COMPILER JDK: Gradle's own recommended shape pairs a 21 toolchain
     * with an 11 target, and reading the toolchain there claimed 21 for bytecode that runs on
     * 11. {@code getTargetCompatibility()} already falls back to the toolchain's language
     * version when nothing else is set, so dropping the toolchain branch loses no case, and
     * it never forces toolchain provisioning the way the compile task's own
     * {@code targetCompatibility} would.
     */
    static Integer declaredRelease(Project project) {
        var java =
                project.getExtensions().findByType(JavaPluginExtension.class);
        if (java == null) {
            return null;
        }
        var compile = project.getTasks().findByName(JavaPlugin.COMPILE_JAVA_TASK_NAME);
        if (compile instanceof JavaCompile javaCompile) {
            var release = javaCompile.getOptions().getRelease().getOrNull();
            if (release != null) {
                return UikaCli.parseRelease(release.toString());
            }
        }
        // getMajorVersion() has already normalized VERSION_1_8 to "8", so unlike the other
        // plugins there is no "1.x" spelling left to filter. The floor check does that job.
        return UikaCli.parseRelease(java.getTargetCompatibility().getMajorVersion());
    }

    /** One non-transitive detached configuration for a single notation. Detached because the
     * plugin must not mutate the build's own configurations, and one per notation because
     * multiple versions of a module in one configuration would be conflict-resolved down to
     * the highest. */
    private static Configuration detachedFor(Project root, String notation) {
        var dependency =
                (ModuleDependency) root.getDependencies().create(notation);
        dependency.setTransitive(false);
        var configuration =
                root.getConfigurations().detachedConfiguration(dependency);
        configuration.setTransitive(false);
        return configuration;
    }

    @Override
    public void apply(Project root) {
        String configurationName = root.findProperty("uikaConfiguration") instanceof String s
                ? s
                : DEFAULT_CONFIGURATION;

        var merge =
                root.getTasks().register("uikaDumpClasspath", MergeClasspathTask.class, task -> {
                    task.setGroup("uika");
                    task.setDescription("Merge resolved classpaths for all modules into uika JSON");
                    var override = root.findProperty("uikaOutput");
                    if (override != null) {
                        task.getOutputFile().set(root.file(override.toString()));
                    } else {
                        task.getOutputFile().convention(
                                root.getLayout().getBuildDirectory().file("uika/classpath.json"));
                    }
                    task.getRootDirPath().set(root.getProjectDir().getAbsolutePath());
                });

        var resolve =
                root.getTasks().register("uikaResolveClasspath", ResolveClasspathTask.class, task -> {
                    task.setGroup("uika");
                    task.setDescription("Rehydrate a classpath dump from another environment into real paths here (Gradle fetches missing JARs)");
                    var input = root.findProperty("uikaInput");
                    if (input != null) {
                        task.getInputFile().set(root.file(input.toString()));
                    }
                    var output = root.findProperty("uikaResolveOutput");
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
            var content = root.getProviders()
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

        // ONE shared provider for the Test-task injection and the uikaUpgradeCheck wiring
        // below, resolved lazily by both consumers: evaluating the bare-property default
        // (the root build directory) twice at different configuration phases let a script
        // that relocates layout.buildDirectory after the plugins block make the tests
        // write one directory while the check read another — and resolving it eagerly at
        // apply time would capture the pre-relocation build directory for both.
        var jfrDir = jfrDir(root);

        var upgradeCheck =
                root.getTasks().register("uikaUpgradeCheck", UpgradeCheckTask.class, task -> {
                    task.setGroup("uika");
                    task.setDescription("Run uika upgrade-check between two dumps (the CLI binary is fetched via this build's repositories)");
                    var before = root.findProperty("uikaBefore");
                    if (before != null) {
                        task.getBeforeFile().set(root.file(before.toString()));
                    }
                    var after = root.findProperty("uikaAfter");
                    if (after != null) {
                        task.getAfterFile().set(root.file(after.toString()));
                    }
                    var cliVersion = root.findProperty("uikaCliVersion");
                    if (cliVersion != null) {
                        task.getCliVersion().set(cliVersion.toString());
                    } else {
                        // Default to the plugin's own version (Implementation-Version in the plugin jar),
                        // so bumping the plugin coordinate also bumps the CLI.
                        var own = UikaPlugin.class.getPackage().getImplementationVersion();
                        if (own != null) {
                            task.getCliVersion().convention(own);
                        }
                    }
                    var failOn = root.findProperty("uikaFailOn");
                    task.getFailOn().convention(failOn != null ? failOn.toString() : "any");
                    var excludeFile = root.findProperty("uikaExcludeFile");
                    if (excludeFile != null) {
                        task.getExcludeFiles().from(root.file(excludeFile.toString()));
                    }
                    if (jfrDir != null) {
                        task.getClassLoadLogs().from(jfrDir);
                    }
                    var draftExcludeFile = root.findProperty("uikaDraftExcludeFile");
                    if (draftExcludeFile != null) {
                        task.getDraftExcludeFile().set(root.file(draftExcludeFile.toString()));
                    }
                    Integer jdkRelease = jdkReleaseProperty(root);
                    if (jdkRelease != null) {
                        task.getJdkRelease().set(jdkRelease);
                    } else {
                        // The build knows its JDK, so the JDK API layer defaults ON here (the bare
                        // CLI keeps it opt-in): the lowest release any project targets, else the
                        // JVM running the build. UikaCli.effectiveJdkRelease clamps at execution
                        // time to what the build JVM's ct.sym can actually serve.
                        // The provider is an @Input, so the configuration cache evaluates it while
                        // the project is still available.
                        task.getJdkRelease().convention(
                                root.getProviders().provider(() -> defaultJdkRelease(root)));
                    }
                    task.getInstallDir().convention(root.getLayout().getBuildDirectory().dir("uika/cli"));
                    task.getJfrWorkDir().convention(
                            root.getLayout().getBuildDirectory()
                                    .dir("uika/" + net.exoego.uika.plugin.core.JfrEvidence.WORK_DIR_NAME));
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
            var notation = UikaCli.GROUP + ":" + UikaCli.ARTIFACT + ":"
                    + task.getCliVersion().get() + ":" + classifier + "@zip";
            task.getCliZip().from(detachedFor(root, notation));
        }));

        // -PuikaJfr=<dir> makes every Test task record class loads into a JFR recording
        // under <dir> (JFR generates pid-unique file names for a directory-valued
        // filename, so parallel forks never clobber each other), which is exactly what
        // uikaUpgradeCheck converts and reads back. One property serves both phases: the
        // base branch's test run collects, the dependency PR's check consumes. Three
        // deliberate choices, each verified against Gradle 9.7.0:
        // - jvmArgumentProviders, not jvmArgs: a build script's `jvmArgs = listOf(...)`
        //   setter replaces the list and silently discarded the injected flag; provider
        //   args survive it and still show in getAllJvmArgs().
        // - upToDateWhen(false) + doNotCacheIf: provider args are not fingerprinted, and
        //   an UP-TO-DATE or FROM-CACHE Test task forks no JVM — the collect run would
        //   upload an empty artifact with no symptom (promote-only evidence). Collection
        //   is a side effect that needs a real run, so asking for it defeats both.
        // - the doFirst mkdirs is load-bearing: a missing PARENT aborts JVM startup, but
        //   a missing leaf directory under an existing parent makes JFR silently record
        //   to a single FILE at that path, every fork clobbering the last — verified
        //   against a real StartFlightRecording run.
        // Configuration only touches task properties and lambdas capturing a provider,
        // so the tasks stay configuration-cache compatible.
        // A .jfr value is consumption-only: it feeds the check after conversion, and test
        // JVMs cannot record into an existing recording, so injection is skipped for it.
        if (jfrDir != null && jfrValueIsRecording(root)) {
            // Said out loud because the skip is otherwise symptomless: a collect run
            // against a .jfr value records nothing and uploads an empty artifact.
            root.getLogger().lifecycle(
                    "uika: -PuikaJfr names a .jfr recording (consumption-only); test JVMs will not record");
        } else if (jfrDir != null) {
            org.gradle.api.provider.Provider<File> dir = jfrDir;
            root.allprojects(p -> p.getTasks()
                    .withType(org.gradle.api.tasks.testing.Test.class)
                    .configureEach(test -> {
                        // The argument is composed inside the provider, at execution
                        // time, so the bare default follows a relocated build directory.
                        test.getJvmArgumentProviders().add(() -> java.util.List.of(
                                UikaCli.jfrClassLoadJvmArg(dir.get().toPath())));
                        test.getOutputs().upToDateWhen(t -> false);
                        test.getOutputs().doNotCacheIf(
                                "uika JFR class-load collection needs a real JVM run",
                                t -> true);
                        test.doFirst("uika JFR recording directory", t -> dir.get().mkdirs());
                    }));
        }

        root.allprojects(p -> {
            var moduleTask = p.getTasks().register(
                    "uikaDumpModuleClasspath", DumpModuleClasspathTask.class, task -> {
                        task.setDescription("Write this module's resolved classpath as a uika JSON fragment");
                        // The declared inputs cannot see the resolution result; an
                        // up-to-date hit would reuse a stale dump.
                        task.getOutputs().upToDateWhen(t -> false);
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
                var confName = task.getConfigurationName().get();
                var conf = p.getConfigurations().findByName(confName);
                var javaExt =
                        p.getExtensions().findByType(JavaPluginExtension.class);
                task.getUnresolvableConfiguration().set(
                        unresolvableConfiguration(p, confName, conf));
                task.getEmptyDump().set(javaExt == null && conf == null);
                // Here rather than at registration: compileJava's options.release and the
                // java extension's targetCompatibility are both build-script settable, so
                // reading them before the project evaluates would see the plugin defaults.
                task.getJdkRelease().set(dumpJdkRelease(root, p));
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
     * Why the named configuration cannot produce a classpath, or null when it can.
     *
     * <p>A missing or unresolvable configuration leaves {@code artifactEntries} unset, and
     * the {@code emptyDump} guard only covers projects with no Java at all, so the module
     * would land in the dump carrying its {@code classesDirs} and an empty classpath. The
     * check then reports nothing broken for it because it was handed nothing to check.
     *
     * <p>Returned rather than thrown. This runs while the per-module task is being
     * REALIZED, which happens on `gradle tasks` and on IDE sync, and the same file already
     * catches an unsupported platform there for exactly that reason. The task action
     * raises it instead, so only a run that actually asks for a dump fails.
     *
     * <p>Only for a project the java plugin touches, which is what creates
     * {@code runtimeClasspath}. {@code JavaPluginExtension} would be the wrong test: it
     * comes from {@code java-base}, so an Android or convention-plugin module that has the
     * extension without a source set would fail a build that never asked for a different
     * configuration. Only for a non-default name too, since the default cannot go missing
     * where the java plugin is applied.
     */
    private static String unresolvableConfiguration(
            Project p, String name, Configuration conf) {
        if (!p.getPlugins().hasPlugin(JavaPlugin.class)
                || DEFAULT_CONFIGURATION.equals(name)) {
            return null;
        }
        if (conf == null) {
            return "uika: project " + p.getPath() + " has no configuration \"" + name
                    + "\". -PuikaConfiguration must name one every module resolves, or"
                    + " override configurationName on that module's"
                    + " uikaDumpModuleClasspath task.";
        }
        if (!conf.isCanBeResolved()) {
            return "uika: configuration \"" + name + "\" of project " + p.getPath()
                    + " cannot be resolved. -PuikaConfiguration wants a resolvable"
                    + " configuration such as " + DEFAULT_CONFIGURATION + ".";
        }
        return null;
    }

    /**
     * The directory (or recording) named by {@code -PuikaJfr}, or null when the property is
     * absent. An empty value (bare {@code -PuikaJfr}) means the default
     * {@code <root build dir>/uika/jfr}, kept as a lazy provider so a build script
     * relocating {@code layout.buildDirectory} after the plugins block still lands the
     * recordings under the final location; an explicit value is a fixed path. A value
     * naming an existing regular file is rejected unless it is a {@code .jfr} recording
     * (consumption-only; the check converts it): any other file would make every test JVM
     * abort at startup with a JFR error that never mentions uika.
     */
    private static org.gradle.api.provider.Provider<File> jfrDir(Project root) {
        var value = root.findProperty("uikaJfr");
        if (value == null) {
            return null;
        }
        var path = value.toString();
        if (path.isEmpty()) {
            return root.getLayout().getBuildDirectory().dir("uika/jfr")
                    .map(org.gradle.api.file.Directory::getAsFile);
        }
        var dir = root.file(path);
        if (dir.isFile() && !jfrValueIsRecording(root)) {
            throw new org.gradle.api.GradleException(
                    "-PuikaJfr must name a directory (test JVMs record into it) or a .jfr"
                            + " recording, but " + dir + " is neither");
        }
        return root.getProviders().provider(() -> dir);
    }

    /**
     * Whether -PuikaJfr names a recording rather than a recording directory. The truth
     * table (a directory named {@code logs.jfr} is still a directory and keeps Test-JVM
     * injection; a path that does not exist yet keeps the suffix's meaning) lives in
     * {@code JfrEvidence.valueNamesRecording}, shared with the sbt plugin so the two
     * cannot drift. Reads the raw property string, never the {@code jfrDir} provider:
     * forcing the lazy bare-value default at apply time would capture a pre-relocation
     * build directory.
     */
    private static boolean jfrValueIsRecording(Project root) {
        var value = root.findProperty("uikaJfr");
        return value != null && !value.toString().isEmpty()
                && net.exoego.uika.plugin.core.JfrEvidence.valueNamesRecording(
                        root.file(value.toString()).toPath());
    }
}
