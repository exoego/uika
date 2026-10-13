package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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
    // The JDKs of the modules whose release the build JVM cannot serve (UikaPlugin wires
    // only those). Each is a lazy toolchain lookup, resolved when the check runs.
    private final ListProperty<CompilerJdk> compilerJdks =
            getProject().getObjects().listProperty(CompilerJdk.class);

    /** A JDK a module compiles with, in a shape the configuration cache can store. */
    record CompilerJdk(String home, int feature) implements Serializable {}

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

    ListProperty<CompilerJdk> compilerJdks() {
        return compilerJdks;
    }

    /** Static so the mapped provider captures nothing the configuration cache cannot store. */
    static CompilerJdk compilerJdk(JavaCompiler compiler) {
        var metadata = compiler.getMetadata();
        return new CompilerJdk(metadata.getInstallationPath().getAsFile().getAbsolutePath(),
                metadata.getLanguageVersion().asInt());
    }

    /**
     * The JDK whose ct.sym the check reads. The build JVM, unless a module compiles with a
     * newer one.
     *
     * <p>A JDK's ct.sym stops at its own release (one below it on JDK 21 and earlier), so a
     * module compiling for a release above the build JVM cannot be served by the build JVM at
     * all. The layer was clamped down, and a JDK move to that release failed for want of its
     * stubs. That happens whenever the Gradle daemon is pinned below the toolchain, which
     * {@code gradle-daemon-jvm.properties} exists to do. The newest compiler JDK serves every
     * module, because a JDK compiles for nothing above itself. A tie goes to the build JVM,
     * so a build whose toolchain matches its daemon behaves as before.
     */
    static UikaCli.JdkSource ctSymSource(
            UikaCli.JdkSource build, List<CompilerJdk> compilers, Consumer<String> log) {
        CompilerJdk newest = null;
        for (CompilerJdk compiler : compilers) {
            if (compiler.feature() > build.feature()
                    && (newest == null || compiler.feature() > newest.feature())) {
                newest = compiler;
            }
        }
        if (newest == null) {
            return build;
        }
        log.accept("uika: reading ct.sym from the JDK " + newest.feature() + " at " + newest.home()
                + " that the modules compile with (the build runs on JDK " + build.feature() + ")");
        return new UikaCli.JdkSource(Path.of(newest.home()), newest.feature());
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
        // The toolchain lookups resolve here, inside the one task that needs them. With the
        // layer off (0) nothing reads ct.sym and no UIKA_JDK is exported, so a lookup there
        // would only cost a possible JDK provisioning and announce a JDK nothing uses.
        var target = jdkRelease().getOrNull();
        UikaCli.JdkSource jdk = target == null || target <= 0
                ? UikaCli.JdkSource.current()
                : ctSymSource(UikaCli.JdkSource.current(), compilerJdks().get(), getLogger()::lifecycle);
        Integer jdkRelease = UikaCli.effectiveJdkRelease(target, jdk, getLogger()::lifecycle);
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
