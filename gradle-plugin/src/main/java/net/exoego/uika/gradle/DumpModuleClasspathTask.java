package net.exoego.uika.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
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
 * (more robust than parsing cache paths). Project dependencies and file dependencies become
 * file entries without coordinates and are used by uika only as scan targets.
 * Modules without Java-family plugins write an empty file (the merge side skips it).
 */
@DisableCachingByDefault(because = "Classpath resolution is environment-dependent and cheap to rerun")
public abstract class DumpModuleClasspathTask extends DefaultTask {

    public DumpModuleClasspathTask() {
        // The declared inputs cannot see the resolution result; an up-to-date hit would reuse a stale dump.
        getOutputs().upToDateWhen(task -> false);
    }

    /** One dumped artifact row, extracted from ResolvedArtifactResult so the configuration
     * cache can serialize it. */
    record Entry(String group, String name, String version, String projectPath, String file)
            implements Serializable {}

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Configuration name to resolve (default runtimeClasspath). */
    @Input
    public abstract Property<String> getConfigurationName();

    /** This module's project path, e.g. ":app". */
    @Input
    public abstract Property<String> getModulePath();

    /** The API release this module compiles for, unset when it declares none. Wired in by
     * {@link UikaPlugin} at configuration time, where the project is still available. */
    @Input
    @Optional
    public abstract Property<Integer> getJdkRelease();

    /** True when the module has neither a Java-family plugin nor the configuration; the dump
     * is an empty file the merge side skips. */
    @Internal
    public abstract Property<Boolean> getEmptyDump();

    /** The main source set's classes dirs. Declared-but-unbuilt dirs are filtered at
     * execution time (java/main in Kotlin-only modules, etc.). */
    @Internal
    public abstract ConfigurableFileCollection getClassesDirs();

    /** The main source set's compilable sources (allSource minus resources), for the
     * missing-build-output warning. Resources are not compilable, so a resources-only module
     * must not warn; allJava alone would miss Kotlin-only modules. */
    @Internal
    public abstract ConfigurableFileCollection getCompilableSources();

    /** The resolved lenient artifact view, mapped to serializable entries. */
    @Internal
    public abstract ListProperty<Entry> getArtifactEntries();

    /** The main source set, or null without a Java-family plugin (one spelling for the
     * dump wiring and the uikaBuildOutputs dependsOn wiring). */
    static SourceSet mainSourceSet(Project p) {
        var javaExt = p.getExtensions().findByType(JavaPluginExtension.class);
        return javaExt == null ? null : javaExt.getSourceSets().findByName("main");
    }

    /** Static (captures nothing) so the configuration cache can serialize the mapped provider. */
    static List<Entry> toEntries(Collection<ResolvedArtifactResult> artifacts) {
        var entries = new ArrayList<Entry>();
        for (ResolvedArtifactResult artifact : artifacts) {
            var id = artifact.getId().getComponentIdentifier();
            String group = null;
            String name = null;
            String version = null;
            String projectPath = null;
            if (id instanceof ModuleComponentIdentifier m) {
                group = m.getGroup();
                name = m.getModule();
                version = m.getVersion();
            } else if (id instanceof ProjectComponentIdentifier project
                    && project.getBuild().getBuildPath().equals(":")) {
                // Attribute project-dependency jars to their producing module so uika can
                // fall back to that module's classesDirs when the jar was never built.
                // Only for this build's own projects: an included build's project path
                // (":lib") can collide with a module of this build, and the fallback
                // would then scan the wrong module's classes.
                projectPath = project.getProjectPath();
            }
            entries.add(new Entry(group, name, version, projectPath,
                    artifact.getFile().getAbsolutePath()));
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
        if (getEmptyDump().getOrElse(false)) {
            Files.write(out.toPath(), new byte[0]);
            return;
        }

        var json = new StringBuilder();
        json.append("{\"module\":").append(quote(getModulePath().get()));
        var jdkRelease = getJdkRelease().getOrNull();
        if (jdkRelease != null) {
            json.append(",\"jdkRelease\":").append(jdkRelease.intValue());
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
        for (Entry entry : getArtifactEntries().get()) {
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
