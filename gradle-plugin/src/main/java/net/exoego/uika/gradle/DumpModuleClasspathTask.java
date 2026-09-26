package net.exoego.uika.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import static net.exoego.uika.plugin.core.DumpFormat.quote;

/**
 * Writes this module's resolved classpath as one module JSON object.
 * All project state (module path, classes dirs, the resolved artifact view) is wired in as
 * task properties by {@link UikaPlugin} after the module evaluates, so the action never
 * touches {@code getProject()} and the task is configuration-cache compatible. The artifact
 * list comes from {@code ArtifactCollection.getResolvedArtifacts()}, the resolution-result
 * provider Gradle can serialize into the configuration cache; it still resolves lazily when
 * this task runs. Coordinates come from ResolvedArtifactResult's ModuleComponentIdentifier
 * (more robust than parsing cache paths). A project dependency is dumped as its classes
 * and resources directories, the secondary variants its runtimeElements publishes next to
 * the jar, so the dump never waits for a jar to be zipped; file dependencies become file
 * entries without coordinates. Both are used by uika only as scan targets.
 * Modules without Java-family plugins write an empty file (the merge side skips it).
 */
@DisableCachingByDefault(because = "Classpath resolution is environment-dependent and cheap to rerun")
public abstract class DumpModuleClasspathTask extends DefaultTask {

    /** One dumped artifact row, extracted from ResolvedArtifactResult so the configuration
     * cache can serialize it. */
    record Entry(String group, String name, String version, String projectPath, String file)
            implements Serializable {}

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Configuration name to resolve, from the uika extension's {@code configuration}. Public
     * because the extension is build-wide, and a build where the name exists on some modules
     * only needs a per-module override. */
    @Input
    public abstract Property<String> getConfigurationName();

    /** This module's project path, e.g. ":app". */
    @Input
    public abstract Property<String> getModulePath();

    // The release this module records: the uika extension's jdkRelease (or -PuikaJdkRelease)
    // when set, else what the module compiles for. Private so the extension stays the one
    // place a build script sets it, and still an input, through the initializer below, because it
    // changes what the task writes.
    private final Property<Integer> jdkRelease = getProject().getObjects().property(Integer.class);

    // Whether the outputs the entries name were built before this task ran. Then a project
    // directory that does not exist has no sources behind it (a module without resources
    // never creates build/resources/main) and is dropped. In a resolution-only dump the
    // unbuilt directories stay listed, so the CLI can fall back to the producing module.
    private final Property<Boolean> builtOutputs = getProject().getObjects().property(Boolean.class);

    // An initializer, not a constructor: Gradle instantiates tasks through a public or
    // @Inject constructor, and the static-analysis recipe narrows a public one to protected.
    {
        getInputs().property("jdkRelease", jdkRelease).optional(true);
    }

    Property<Integer> jdkRelease() {
        return jdkRelease;
    }

    Property<Boolean> builtOutputs() {
        return builtOutputs;
    }

    /** True when the module has neither a Java-family plugin nor the configuration; the dump
     * is an empty file the merge side skips. */
    @Internal
    public abstract Property<Boolean> getEmptyDump();

    /**
     * Why this module's named configuration cannot produce a classpath, or absent when it
     * can. Carried as a property rather than thrown while the task is wired, so `gradle
     * tasks` and IDE sync still work and only a real dump fails.
     */
    @Input
    @Optional
    public abstract Property<String> getUnresolvableConfiguration();

    /** The main source set's classes dirs. Declared-but-unbuilt dirs are filtered at
     * execution time (java/main in Kotlin-only modules, etc.). */
    @Internal
    public abstract ConfigurableFileCollection getClassesDirs();

    /** The main source set's compilable sources (allSource minus resources), for the
     * missing-build-output warning. Resources are not compilable, so a resources-only module
     * must not warn; allJava alone would miss Kotlin-only modules. */
    @Internal
    public abstract ConfigurableFileCollection getCompilableSources();

    /** The resolved lenient artifact views, mapped to serializable entries. */
    @Internal
    public abstract ListProperty<Entry> getArtifactEntries();

    /** The main source set, or null without a Java-family plugin (one spelling for the
     * dump wiring and the uikaBuildOutputs dependsOn wiring). */
    static SourceSet mainSourceSet(Project p) {
        var javaExt = p.getExtensions().findByType(JavaPluginExtension.class);
        return javaExt == null ? null : javaExt.getSourceSets().findByName("main");
    }

    /**
     * Static (captures nothing) so the configuration cache can serialize the mapped provider.
     *
     * <p>{@code classes} is the classpath with classes asked for: a jar per external
     * dependency and per file dependency, one or more classes directories per project
     * dependency. {@code resources} is the same classpath with resources asked for, and only
     * its project directories are taken, right after that project's classes, so the entry
     * order is still the classpath order. The external jars it lists again are the same files.
     */
    static List<Entry> toEntries(Collection<ResolvedArtifactResult> classes, Collection<ResolvedArtifactResult> resources) {
        var byComponent = new LinkedHashMap<ComponentIdentifier, List<ResolvedArtifactResult>>();
        for (ResolvedArtifactResult artifact : classes) {
            byComponent.computeIfAbsent(artifact.getId().getComponentIdentifier(), k -> new ArrayList<>()).add(artifact);
        }
        for (ResolvedArtifactResult artifact : resources) {
            var id = artifact.getId().getComponentIdentifier();
            if (id instanceof ProjectComponentIdentifier && byComponent.containsKey(id)) {
                byComponent.get(id).add(artifact);
            }
        }
        var entries = new ArrayList<Entry>();
        for (var component : byComponent.entrySet()) {
            var id = component.getKey();
            String group = null;
            String name = null;
            String version = null;
            String projectPath = null;
            if (id instanceof ModuleComponentIdentifier m) {
                group = m.getGroup();
                name = m.getModule();
                version = m.getVersion();
            } else if (id instanceof ProjectComponentIdentifier project
                    && ":".equals(project.getBuild().getBuildPath())) {
                // Attribute a project dependency's directories to their producing module so
                // uika can fall back to that module's classesDirs when they were never
                // built. Only for this build's own projects: an included build's project
                // path (":lib") can collide with a module of this build, and the fallback
                // would then scan the wrong module's classes.
                projectPath = project.getProjectPath();
            }
            for (ResolvedArtifactResult artifact : component.getValue()) {
                entries.add(new Entry(group, name, version, projectPath, artifact.getFile().getAbsolutePath()));
            }
        }
        return entries;
    }

    @TaskAction
    public void dump() throws IOException {
        var out = getOutputFile().get().getAsFile();
        var parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        if (getUnresolvableConfiguration().isPresent()) {
            throw new org.gradle.api.GradleException(getUnresolvableConfiguration().get());
        }
        if (getEmptyDump().getOrElse(false)) {
            Files.write(out.toPath(), new byte[0]);
            return;
        }

        var json = new StringBuilder();
        json.append("{\"module\":").append(quote(getModulePath().get()));
        var release = jdkRelease.getOrNull();
        if (release != null) {
            json.append(",\"jdkRelease\":").append(release.intValue());
        }

        json.append(",\"classesDirs\":[");
        var first = true;
        var any = false;
        for (File dir : getClassesDirs().getFiles()) {
            // Do not include declared but unbuilt outputs (java/main in Kotlin-only modules, etc.).
            if (!dir.exists()) {
                continue;
            }
            any = true;
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(dir.getAbsolutePath()));
        }
        // A module with sources but no built output would silently drop out of the
        // scan (and out of the reachability roots); say so instead.
        if (!any && !getCompilableSources().isEmpty()) {
            getLogger().warn(
                    "uika: {} has no built classes; its own classes will not be checked"
                            + " (build first, or keep uikaBuildOutputs enabled)",
                    getModulePath().get());
        }
        json.append("]");

        json.append(",\"artifacts\":[");
        first = true;
        var built = builtOutputs.getOrElse(false);
        for (Entry entry : getArtifactEntries().get()) {
            if (built && entry.projectPath() != null && !new File(entry.file()).exists()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            if (entry.group() != null) {
                json.append("\"group\":").append(quote(entry.group()))
                        .append(",\"name\":").append(quote(entry.name()))
                        .append(",\"version\":").append(quote(entry.version()))
                        .append(',');
            } else if (entry.projectPath() != null) {
                json.append("\"project\":").append(quote(entry.projectPath())).append(',');
            }
            json.append("\"file\":").append(quote(entry.file()));
            json.append('}');
        }
        json.append("]}");

        Files.write(out.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
    }

}
