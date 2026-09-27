package net.exoego.uika.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code uika {}} block of the root build script, read by every uika task. Each setting
 * has a {@code -Puika*} twin (the name with the prefix, e.g. {@code -PuikaFailOn}) that wins
 * over the value here for one invocation.
 */
public abstract class UikaExtension {

    /** uika-cli version; defaults to the plugin's own version. */
    public abstract Property<String> getCliVersion();

    /** When to fail the build: {@code never}, {@code reachable}, or {@code any} (default). */
    public abstract Property<String> getFailOn();

    /** TOML files of known false positives to suppress, passed as repeated {@code --exclude-file}. */
    public abstract ConfigurableFileCollection getExcludeFiles();

    /**
     * JDK API release the application runs on. Recorded in the dump as every module's
     * release and passed to the check as {@code --jdk-release}. Unset, each module records
     * what it compiles for and the check takes the lowest of them. 0 disables the check's
     * API layer and leaves the dump derived.
     */
    public abstract Property<Integer> getJdkRelease();

    /** Check the union of every module's classpath once, passed as {@code --merged-classpath}. */
    public abstract Property<Boolean> getMergedClasspath();

    /** Text class-load evidence produced some other way, passed as repeated {@code --class-load-log}. */
    public abstract ConfigurableFileCollection getClassLoadLogs();

    /** Where the CLI writes draft exclude rules, passed as {@code --draft-exclude-file}. */
    public abstract RegularFileProperty getDraftExcludeFile();

    /** The configuration each module's dump resolves; defaults to {@code runtimeClasspath}. */
    public abstract Property<String> getConfiguration();

    /** Whether the dump builds the module outputs it names first; defaults to true. */
    public abstract Property<Boolean> getBuildOutputs();
}
