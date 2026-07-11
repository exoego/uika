package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rehydrates a classpath dump (v1/v2) produced in another environment into real file paths
 * in this environment, then writes v2. Missing artifact files are resolved by Gradle from
 * their coordinates through detached configurations, so repositories, mirrors, credentials,
 * and proxies configured for this build are reused as-is and uika does not need repository
 * knowledge.
 *
 * <p>For JARs with classifiers (netty natives, etc.), the classifier is recovered from the
 * original file name "name-version-classifier.jar" so the exact artifact is requested.
 * Entries that cannot be resolved are warned and left unchanged (uika will skip them).
 */
@DisableCachingByDefault(because = "Resolves artifacts through environment-specific Gradle repositories")
public abstract class ResolveClasspathTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputFile();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Preferred prefix for the root table (usually the repository root). */
    @Input
    public abstract Property<String> getRootDirPath();

    @TaskAction
    @SuppressWarnings("unchecked")
    public void resolve() throws IOException {
        File input = getInputFile().get().getAsFile();
        Map<String, Object> doc = (Map<String, Object>) new JsonSlurper().parse(input);
        List<Module> modules = DumpFormat.normalize(doc);

        // Collect missing artifacts with coordinates (notation = g:n:v[:classifier]).
        Set<String> wanted = new LinkedHashSet<>();
        for (Module module : modules) {
            for (Artifact artifact : module.artifacts()) {
                String notation = notationOf(artifact);
                if (notation != null && !new File(artifact.file()).exists()) {
                    wanted.add(notation);
                }
            }
        }

        // Let Gradle resolve them (non-transitive = only the entity listed in the dump).
        // Keep each notation in a separate configuration because putting multiple versions
        // of the same module into one configuration lets conflict resolution retain only
        // the highest version.
        Map<String, File> resolvedByKey = new HashMap<>();
        if (!wanted.isEmpty()) {
            getLogger().lifecycle("uika: resolving {} missing artifact(s) via Gradle", wanted.size());
        }
        for (String notation : wanted) {
            ModuleDependency dependency =
                    (ModuleDependency) getProject().getDependencies().create(notation);
            dependency.setTransitive(false);
            Configuration configuration =
                    getProject().getConfigurations().detachedConfiguration(dependency);
            configuration.setTransitive(false);
            Iterable<ResolvedArtifactResult> results = configuration.getIncoming()
                    .artifactView(view -> view.lenient(true))
                    .getArtifacts()
                    .getArtifacts();
            for (ResolvedArtifactResult result : results) {
                ComponentIdentifier id = result.getId().getComponentIdentifier();
                if (id instanceof ModuleComponentIdentifier m) {
                    // Include the file name in the key to distinguish classifier variants.
                    resolvedByKey.put(
                            m.getGroup() + ":" + m.getModule() + ":" + m.getVersion()
                                    + ":" + result.getFile().getName(),
                            result.getFile());
                }
            }
        }

        // Rebuild the common model with real paths.
        int rewritten = 0;
        int unresolved = 0;
        List<Module> rewrittenModules = new ArrayList<>();
        for (Module module : modules) {
            List<Artifact> artifacts = new ArrayList<>();
            for (Artifact artifact : module.artifacts()) {
                if (!new File(artifact.file()).exists() && artifact.group() != null) {
                    File local = resolvedByKey.get(artifact.group() + ":" + artifact.name() + ":"
                            + artifact.version() + ":" + new File(artifact.file()).getName());
                    if (local != null) {
                        artifact = new Artifact(artifact.group(), artifact.name(),
                                artifact.version(), local.getAbsolutePath());
                        rewritten++;
                    } else {
                        unresolved++;
                        getLogger().warn("uika: could not resolve {}:{}:{} ({})",
                                artifact.group(), artifact.name(), artifact.version(),
                                new File(artifact.file()).getName());
                    }
                }
                artifacts.add(artifact);
            }
            rewrittenModules.add(new Module(module.path(), module.classesDirs(), artifacts));
        }

        String json = DumpFormat.writeV2(rewrittenModules, List.of(getRootDirPath().get()));
        File out = getOutputFile().get().getAsFile();
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
        getLogger().lifecycle("uika: rehydrated dump -> {} ({} rewritten, {} unresolved)",
                out, rewritten, unresolved);
    }

    /** g:n:v[:classifier]. The classifier is recovered from the original file name "name-version-classifier.jar". */
    private static String notationOf(Artifact artifact) {
        if (artifact.group() == null || artifact.name() == null || artifact.version() == null) {
            return null;
        }
        String base = artifact.name() + "-" + artifact.version();
        String fileName = new File(artifact.file()).getName();
        String classifier = null;
        if (fileName.startsWith(base + "-") && fileName.endsWith(".jar")) {
            classifier = fileName.substring(base.length() + 1, fileName.length() - ".jar".length());
        }
        String notation = artifact.group() + ":" + artifact.name() + ":" + artifact.version();
        return classifier == null ? notation : notation + ":" + classifier;
    }
}
