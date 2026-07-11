package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

/**
 * Runs {@code uika upgrade-check} between two classpath dumps. The CLI binary is resolved as
 * {@code net.exoego.uika:uika-cli:<version>:<platform>@zip} through this build's repositories
 * (same philosophy as {@link ResolveClasspathTask}: uika needs no repository knowledge of its
 * own), so downloads land in the Gradle cache and the version lives in the build, where bots
 * bump it.
 */
@DisableCachingByDefault(because = "Resolves the CLI through environment-specific Gradle repositories")
public abstract class UpgradeCheckTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBeforeFile();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAfterFile();

    /** uika-cli version; defaults to the plugin's own version. */
    @Input
    public abstract Property<String> getCliVersion();

    /** Where the binary is extracted, scoped by version and classifier below this directory. */
    @Internal
    public abstract DirectoryProperty getInstallDir();

    @TaskAction
    public void run() throws Exception {
        if (!getCliVersion().isPresent()) {
            throw new GradleException(
                    "uika-cli version is unknown; pass -PuikaCliVersion=<version>");
        }
        String version = getCliVersion().get();
        String classifier = UikaCli.platformClassifier();

        String notation = UikaCli.GROUP + ":" + UikaCli.ARTIFACT + ":" + version
                + ":" + classifier + "@zip";
        ModuleDependency dependency =
                (ModuleDependency) getProject().getDependencies().create(notation);
        dependency.setTransitive(false);
        Configuration configuration =
                getProject().getConfigurations().detachedConfiguration(dependency);
        configuration.setTransitive(false);
        Set<File> files = configuration.resolve();
        File zip = files.iterator().next();

        Path installDir = getInstallDir().get().getAsFile().toPath()
                .resolve(version + "-" + classifier);
        Path binary = UikaCli.extractBinary(zip.toPath(), installDir);

        int exit = UikaCli.runUpgradeCheck(binary,
                getBeforeFile().get().getAsFile().toPath(),
                getAfterFile().get().getAsFile().toPath());
        if (exit == 1) {
            throw new GradleException("uika upgrade-check found broken references (see output above)");
        }
        if (exit != 0) {
            throw new GradleException("uika upgrade-check failed with exit code " + exit);
        }
    }
}
