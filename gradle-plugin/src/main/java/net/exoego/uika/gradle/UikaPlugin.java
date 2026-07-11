package net.exoego.uika.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * uika integration plugin.
 * When applied to the root project, it creates:
 * - uikaDumpModuleClasspath in each module (writes that module's resolved classpath as a JSON fragment)
 * - uikaDumpClasspath in the root (merges fragments into one JSON file)
 * Gradle does not allow resolving other projects' configurations at execution time, so
 * resolution must happen in each module's own task.
 *
 * <p>Usage (CI for dependency update PRs):
 * <pre>
 *   git checkout base && ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json
 *   git checkout head && ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
 *   ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
 * </pre>
 */
public class UikaPlugin implements Plugin<Project> {
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
            task.notCompatibleWithConfigurationCache("resolves detached configurations at execution time (PoC)");
        });

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
            task.getInstallDir().convention(root.getLayout().getBuildDirectory().dir("uika/cli"));
            task.notCompatibleWithConfigurationCache("resolves detached configurations at execution time (PoC)");
        });

        root.allprojects(p -> {
            TaskProvider<DumpModuleClasspathTask> moduleTask = p.getTasks().register(
                    "uikaDumpModuleClasspath", DumpModuleClasspathTask.class, task -> {
                        task.setDescription("Write this module's resolved classpath as a uika JSON fragment");
                        task.getOutputFile().convention(
                                p.getLayout().getBuildDirectory().file("uika/module-classpath.json"));
                        task.getConfigurationName().convention(configurationName);
                        task.notCompatibleWithConfigurationCache(
                                "resolves configurations at execution time (PoC)");
                    });
            merge.configure(m -> m.getFragments().from(moduleTask));
        });
    }
}
