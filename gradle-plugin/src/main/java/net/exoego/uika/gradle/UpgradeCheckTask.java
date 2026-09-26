package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Runs {@code uika upgrade-check} between two classpath dumps. The CLI is the pure-Java jar,
 * resolved as {@code net.exoego.uika:uika-cli:<version>:jvm@jar} through this build's repositories
 * (same philosophy as {@link ResolveClasspathTask}: uika needs no repository knowledge of its
 * own), so downloads land in the Gradle cache and the version lives in the build, where bots
 * bump it. {@link UikaPlugin} wires the detached configuration for the jar into
 * {@link #cliJar()} lazily from {@link #cliVersion()}, so the action never touches
 * {@code getProject()} and the task is configuration-cache compatible.
 */
@DisableCachingByDefault(because = "Resolves the CLI through environment-specific Gradle repositories")
public abstract class UpgradeCheckTask extends DefaultTask {
    // Every setting is a private carrier wired by UikaPlugin from the uika extension or a
    // -Puika* property, so the extension is the one place a build script configures. None
    // is declared as an input or output: the task has no outputs on purpose (see
    // draftExcludeFile), so it always runs and fingerprinting inputs would buy nothing.
    private final RegularFileProperty beforeFile = getProject().getObjects().fileProperty();
    private final RegularFileProperty afterFile = getProject().getObjects().fileProperty();
    private final Property<String> cliVersion = getProject().getObjects().property(String.class);
    // With no CLI version the friendly error in resolveBinary must win, not a failure to
    // resolve an absent notation.
    private final ConfigurableFileCollection cliJar = getProject().getObjects().fileCollection();
    private final Property<String> failOn = getProject().getObjects().property(String.class);
    private final ConfigurableFileCollection excludeFiles = getProject().getObjects().fileCollection();
    private final Property<Integer> jdkRelease = getProject().getObjects().property(Integer.class);
    private final ConfigurableFileCollection classLoadLogs = getProject().getObjects().fileCollection();
    // Never an output: declaring one made a second invocation UP-TO-DATE and silently
    // skipped the check. The draft is read by a human, never by another task.
    private final RegularFileProperty draftExcludeFile = getProject().getObjects().fileProperty();
    // Where JFR recordings on the class-load knob are converted to text for the CLI.
    private final DirectoryProperty jfrWorkDir = getProject().getObjects().directoryProperty();
    private final Property<Boolean> mergedClasspath = getProject().getObjects().property(Boolean.class);
    // From providers.environmentVariable rather than System.getenv, which the configuration
    // cache would record as a configuration input and invalidate the whole entry on.
    private final Property<String> cliPath = getProject().getObjects().property(String.class);

    RegularFileProperty beforeFile() {
        return beforeFile;
    }

    RegularFileProperty afterFile() {
        return afterFile;
    }

    Property<String> cliVersion() {
        return cliVersion;
    }

    ConfigurableFileCollection cliJar() {
        return cliJar;
    }

    Property<String> failOn() {
        return failOn;
    }

    ConfigurableFileCollection excludeFiles() {
        return excludeFiles;
    }

    Property<Integer> jdkRelease() {
        return jdkRelease;
    }

    ConfigurableFileCollection classLoadLogs() {
        return classLoadLogs;
    }

    RegularFileProperty draftExcludeFile() {
        return draftExcludeFile;
    }

    DirectoryProperty jfrWorkDir() {
        return jfrWorkDir;
    }

    Property<Boolean> mergedClasspath() {
        return mergedClasspath;
    }

    Property<String> cliPath() {
        return cliPath;
    }

    @TaskAction
    public void run() throws Exception {
        // Undeclared as inputs, so Gradle no longer names a missing dump; say it here.
        if (!beforeFile.isPresent() || !afterFile.isPresent()) {
            throw new GradleException(
                    "uika upgrade-check needs both dumps; pass -PuikaBefore=<file> -PuikaAfter=<file>");
        }
        var binary = resolveBinary();

        List<Path> excludeFiles = excludeFiles().getFiles().stream()
                .map(File::toPath)
                .toList();
        // The build JVM supplies ct.sym: Gradle can name a module's toolchain but resolving it
        // to an installation would provision a JDK the build never asked for, so a release
        // above what this JVM serves is clamped down rather than chased.
        UikaCli.JdkSource jdk = UikaCli.JdkSource.current();
        Integer jdkRelease = UikaCli.effectiveJdkRelease(
                jdkRelease().getOrNull(), jdk, getLogger()::lifecycle);
        // JFR recordings on the knob (a .jfr value, or recordings inside a directory) are
        // converted to the CLI's text format here: the CLI never reads
        // binary JFR, while this task always runs on a full JDK.
        var classLoadLogs = net.exoego.uika.plugin.core.JfrEvidence.rewrite(
                classLoadLogs().getFiles().stream().map(File::toPath).toList(),
                jfrWorkDir().get().getAsFile().toPath(),
                getLogger()::lifecycle);
        Path draftExcludeFile = draftExcludeFile().isPresent()
                ? draftExcludeFile().get().getAsFile().toPath()
                : null;
        var exit = UikaCli.runUpgradeCheck(binary,
                beforeFile().get().getAsFile().toPath(),
                afterFile().get().getAsFile().toPath(),
                failOn().getOrElse("any"),
                excludeFiles,
                jdkRelease,
                jdk,
                classLoadLogs,
                draftExcludeFile,
                mergedClasspath().getOrElse(false),
                getLogger()::lifecycle);
        if (exit == 1) {
            throw new GradleException("uika upgrade-check found broken references (see output above)");
        }
        if (exit != 0) {
            throw new GradleException("uika upgrade-check failed with exit code " + exit);
        }
    }

    /// UIKA_CLI_PATH wins outright, so a build can point at a jar or an executable it
    /// already has without the repositories or the version mattering at all.
    private Path resolveBinary() {
        // The shared check, not a local copy: it also rejects a path that exists and is not
        // executable, which is how an artifact round trip usually breaks a hand-supplied
        // binary. Through the property rather than System.getenv, so the configuration
        // cache sees the variable as a declared input.
        Path override = UikaCli.overrideFrom(cliPath().getOrNull());
        if (override != null) {
            return override;
        }
        if (!cliVersion().isPresent()) {
            throw new GradleException(
                    "uika-cli version is unknown; pass -PuikaCliVersion=<version>");
        }
        Set<File> files = cliJar().getFiles();
        if (files.isEmpty()) {
            throw new GradleException("uika-cli " + cliVersion().get() + " did not resolve to a jar");
        }
        // Run from where Gradle cached it: a jar needs no extraction and no executable bit.
        return files.iterator().next().toPath();
    }
}
