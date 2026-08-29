package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Runs {@code uika upgrade-check} between two classpath dumps. The CLI binary is resolved as
 * {@code net.exoego.uika:uika-cli:<version>:<platform>@zip} through this build's repositories
 * (same philosophy as {@link ResolveClasspathTask}: uika needs no repository knowledge of its
 * own), so downloads land in the Gradle cache and the version lives in the build, where bots
 * bump it. {@link UikaPlugin} wires the detached configuration for the ZIP into
 * {@link #getCliZip()} lazily from {@link #getCliVersion()}, so the action never touches
 * {@code getProject()} and the task is configuration-cache compatible.
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
    @Optional
    public abstract Property<String> getCliVersion();

    /** The resolved CLI distribution ZIP (one file), wired from {@link #getCliVersion()}.
     * Internal, not InputFiles: with no CLI version the friendly error below must win, not
     * a fingerprinting failure on an absent provider. */
    @Internal
    public abstract ConfigurableFileCollection getCliZip();

    /** When to fail the build: {@code never}, {@code reachable}, or {@code any} (default). */
    @Input
    public abstract Property<String> getFailOn();

    /** TOML files of known false positives to suppress, passed as repeated {@code --exclude-file}. */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getExcludeFiles();

    /**
     * JDK API release for the CLI's {@code --jdk-release} (resolves JDK hierarchy escapes
     * instead of counting them unverified). Defaults to the lowest release any project in the
     * build compiles for, from {@code compileJava}'s {@code options.release} else its target
     * compatibility, and to the build JVM when no project declares one. Clamped to what the
     * build JVM's ct.sym can serve. Set 0 to disable the layer.
     */
    @Input
    @Optional
    public abstract Property<Integer> getJdkRelease();

    /**
     * Runtime class-load evidence (JFR recordings, text logs, or directories of both) from a
     * test run of the current, not yet upgraded build, passed as repeated
     * {@code --class-load-log} after recordings are converted into {@link #getJfrWorkDir()}.
     * Wired from {@code -PuikaJfr} — the same property that makes {@code Test} tasks record,
     * so one value serves the collect run and the check run.
     */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getClassLoadLogs();

    /**
     * Where the CLI writes draft exclude rules for symbols never observed loading, passed as
     * {@code --draft-exclude-file}. The CLI rejects it without class-load logs, so the flag
     * is only useful together with {@link #getClassLoadLogs()}. Internal, not OutputFile:
     * this task declares no outputs on purpose, because declaring one makes a second
     * invocation UP-TO-DATE, and a check must always run and print its report (the draft is
     * consumed by a human, never by another task).
     */
    @Internal
    public abstract RegularFileProperty getDraftExcludeFile();

    /** Where JFR recordings on the class-load knob are converted to text for the CLI. */
    @Internal
    public abstract DirectoryProperty getJfrWorkDir();

    /**
     * A binary to run instead of resolving one, from {@code UIKA_CLI_PATH}.
     *
     * <p>Wired from a {@code providers.environmentVariable} rather than read with
     * {@code System.getenv}, which the configuration cache would record as a
     * configuration input and invalidate the whole entry on. Through a provider the value
     * is re-read at execution instead, so the entry stays reusable.
     */
    @Input
    @Optional
    public abstract Property<String> getCliPath();

    /** Where the binary is extracted, scoped by version and classifier below this directory. */
    @Internal
    public abstract DirectoryProperty getInstallDir();

    @TaskAction
    public void run() throws Exception {
        var binary = resolveBinary();

        List<Path> excludeFiles = getExcludeFiles().getFiles().stream()
                .map(File::toPath)
                .toList();
        // The build JVM supplies ct.sym: Gradle can name a module's toolchain but resolving it
        // to an installation would provision a JDK the build never asked for, so a release
        // above what this JVM serves is clamped down rather than chased.
        UikaCli.JdkSource jdk = UikaCli.JdkSource.current();
        Integer jdkRelease = UikaCli.effectiveJdkRelease(
                getJdkRelease().getOrNull(), jdk, getLogger()::lifecycle);
        // JFR recordings on the knob (a .jfr value, or recordings inside a directory) are
        // converted to the CLI's text format here: the CLI is JVM-free and never reads
        // binary JFR, while this task always runs on a full JDK.
        var classLoadLogs = net.exoego.uika.plugin.core.JfrEvidence.rewrite(
                getClassLoadLogs().getFiles().stream().map(File::toPath).toList(),
                getJfrWorkDir().get().getAsFile().toPath(),
                getLogger()::lifecycle);
        Path draftExcludeFile = getDraftExcludeFile().isPresent()
                ? getDraftExcludeFile().get().getAsFile().toPath()
                : null;
        var exit = UikaCli.runUpgradeCheck(binary,
                getBeforeFile().get().getAsFile().toPath(),
                getAfterFile().get().getAsFile().toPath(),
                getFailOn().getOrElse("any"),
                excludeFiles,
                jdkRelease,
                jdk,
                classLoadLogs,
                draftExcludeFile,
                getLogger()::lifecycle);
        if (exit == 1) {
            throw new GradleException("uika upgrade-check found broken references (see output above)");
        }
        if (exit != 0) {
            throw new GradleException("uika upgrade-check failed with exit code " + exit);
        }
    }

    /// UIKA_CLI_PATH wins outright, so a build can point at a binary it already has without
    /// the repositories, the version, or the platform classifier mattering at all. The
    /// resolver path below is unchanged.
    private Path resolveBinary() throws IOException {
        // The shared check, not a local copy: it also rejects a path that exists and is not
        // executable, which is how an artifact round trip usually breaks a hand-supplied
        // binary. Through the property rather than System.getenv, so the configuration
        // cache sees the variable as a declared input.
        Path override = UikaCli.overrideFrom(getCliPath().getOrNull());
        if (override != null) {
            return override;
        }
        if (!getCliVersion().isPresent()) {
            throw new GradleException(
                    "uika-cli version is unknown; pass -PuikaCliVersion=<version>");
        }
        var version = getCliVersion().get();
        String classifier = UikaCli.platformClassifier();

        Set<File> files = getCliZip().getFiles();
        if (files.isEmpty()) {
            throw new GradleException("uika-cli " + version + " (" + classifier
                    + ") did not resolve to a distribution ZIP");
        }
        var zip = files.iterator().next();

        var installDir = getInstallDir().get().getAsFile().toPath()
                .resolve(version + "-" + classifier);
        return UikaCli.extractBinary(zip.toPath(), installDir);
    }
}
