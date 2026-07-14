package net.exoego.uika.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static net.exoego.uika.plugin.core.DumpFormat.quote;

/**
 * Writes this module's resolved classpath as one module JSON object.
 * Resolving this module's own configuration at execution time is safe with Gradle's project locks.
 * Coordinates come from ResolvedArtifactResult's ModuleComponentIdentifier (more robust than
 * parsing cache paths). Project dependencies and file dependencies become file entries without
 * coordinates and are used by uika only as scan targets.
 * Modules without Java-family plugins write an empty file (the merge side skips it).
 */
@DisableCachingByDefault(because = "Classpath resolution is environment-dependent and cheap to rerun")
public abstract class DumpModuleClasspathTask extends DefaultTask {

    public DumpModuleClasspathTask() {
        // The declared inputs cannot see the resolution result; an up-to-date hit would reuse a stale dump.
        getOutputs().upToDateWhen(task -> false);
    }

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Configuration name to resolve (default runtimeClasspath). */
    @Input
    public abstract Property<String> getConfigurationName();

    @TaskAction
    public void dump() throws IOException {
        Project p = getProject();
        JavaPluginExtension javaExt = p.getExtensions().findByType(JavaPluginExtension.class);
        Configuration conf = p.getConfigurations().findByName(getConfigurationName().get());

        File out = getOutputFile().get().getAsFile();
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        if (javaExt == null && conf == null) {
            Files.write(out.toPath(), new byte[0]);
            return;
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"module\":").append(quote(p.getPath()));

        json.append(",\"classesDirs\":[");
        boolean first = true;
        if (javaExt != null) {
            SourceSet main = javaExt.getSourceSets().findByName("main");
            if (main != null) {
                for (File dir : main.getOutput().getClassesDirs().getFiles()) {
                    // Do not include declared but unbuilt outputs (java/main in Kotlin-only modules, etc.).
                    if (!dir.exists()) {
                        continue;
                    }
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    json.append(quote(dir.getAbsolutePath()));
                }
            }
        }
        json.append("]");

        json.append(",\"artifacts\":[");
        first = true;
        if (conf != null && conf.isCanBeResolved()) {
            // lenient: skip project dependency JARs and similar artifacts that have not been built yet
            // (the module's own classes are covered by classesDirs).
            Iterable<ResolvedArtifactResult> artifacts = conf.getIncoming()
                    .artifactView(view -> view.lenient(true))
                    .getArtifacts()
                    .getArtifacts();
            for (ResolvedArtifactResult artifact : artifacts) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('{');
                ComponentIdentifier id = artifact.getId().getComponentIdentifier();
                if (id instanceof ModuleComponentIdentifier m) {
                    json.append("\"group\":").append(quote(m.getGroup()))
                            .append(",\"name\":").append(quote(m.getModule()))
                            .append(",\"version\":").append(quote(m.getVersion()))
                            .append(',');
                }
                json.append("\"file\":").append(quote(artifact.getFile().getAbsolutePath()));
                json.append('}');
            }
        }
        json.append("]}");

        Files.write(out.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
    }

}
