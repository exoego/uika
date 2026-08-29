package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
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
 * <p>The input dump is read once more at configuration time by {@link UikaPlugin} to set up
 * one detached configuration per missing notation (configuration-cache compatible: the file
 * becomes a tracked build input, and the resolution results reach the action through
 * {@link #getResolvedFiles()}). The input file must therefore exist before the build starts;
 * producing it and rehydrating it in one invocation is not supported.
 *
 * <p>For JARs with classifiers (netty natives, etc.), the classifier is recovered from the
 * original file name "name-version-classifier.jar" so the exact artifact is requested.
 * The task fails when any coordinate-carrying artifact stays unresolved: only a scan target
 * uika would skip with a warning, and an unresolved COMPARED-PAIR jar makes the check exit 2
 * on a path this machine does not have. A failed rehydration is what lets the workflow fall
 * back to dumping the baseline from a checkout. Project-attributed and coordinate-less
 * entries are left unchanged and are exempt from that failure, for the reason
 * {@link #wantedNotations} gives.
 */
@DisableCachingByDefault(because = "Resolves artifacts through environment-specific Gradle repositories")
public abstract class ResolveClasspathTask extends DefaultTask {

    /** One artifact Gradle resolved for a wanted notation. The key matches
     * {@link #keyOf(Artifact)} so classifier variants stay distinct. */
    record ResolvedFile(String key, String file) implements Serializable {}

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputFile();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Preferred prefix for the root table (usually the repository root). */
    @Input
    public abstract Property<String> getRootDirPath();

    /** Resolution results for the missing notations, wired by {@link UikaPlugin} from one
     * detached configuration per notation. */
    @Internal
    public abstract ListProperty<ResolvedFile> getResolvedFiles();

    /** True when the plugin saw the input file at configuration time and set up resolution.
     * False means the file appeared only after the build was configured, so nothing can be
     * fetched in this invocation. */
    @Internal
    public abstract Property<Boolean> getWiredAtConfiguration();

    /** Static (captures nothing) so the configuration cache can serialize the mapped provider. */
    static List<ResolvedFile> toResolvedFiles(Collection<ResolvedArtifactResult> artifacts) {
        List<ResolvedFile> files = new ArrayList<>();
        for (ResolvedArtifactResult result : artifacts) {
            ComponentIdentifier id = result.getId().getComponentIdentifier();
            if (id instanceof ModuleComponentIdentifier m) {
                // Include the file name in the key to distinguish classifier variants.
                files.add(new ResolvedFile(
                        m.getGroup() + ":" + m.getModule() + ":" + m.getVersion()
                                + ":" + result.getFile().getName(),
                        result.getFile().getAbsolutePath()));
            }
        }
        return files;
    }

    @SuppressWarnings("unchecked")
    static List<Module> parseModules(String json) {
        Map<String, Object> doc = (Map<String, Object>) new JsonSlurper().parseText(json);
        return DumpFormat.normalize(doc);
    }

    @SuppressWarnings("unchecked")
    static Integer parseJdkRelease(String json) {
        Map<String, Object> doc = (Map<String, Object>) new JsonSlurper().parseText(json);
        return DumpFormat.jdkReleaseOf(doc);
    }

    /**
     * Notations (g:n:v[:classifier]) of coordinate-carrying artifacts whose file is absent
     * here.
     *
     * <p>Project-attributed artifacts are never wanted, even when they carry coordinates
     * the way a Maven reactor dependency does. Their coordinates may also name a PUBLISHED
     * release, and fetching that would hand the CLI a stale jar while permanently
     * bypassing its own fallback, which substitutes the producing module's classesDirs
     * whenever the file is missing. Left alone, the entry keeps its attribution and the
     * CLI keeps that choice.
     */
    static Set<String> wantedNotations(List<Module> modules) {
        Set<String> wanted = new LinkedHashSet<>();
        for (Module module : modules) {
            for (Artifact artifact : module.artifacts()) {
                String notation = notationOf(artifact);
                if (notation != null && artifact.project() == null
                        && !new File(artifact.file()).exists()) {
                    wanted.add(notation);
                }
            }
        }
        return wanted;
    }

    @TaskAction
    public void resolve() throws IOException {
        File input = getInputFile().get().getAsFile();
        String inputJson = Files.readString(input.toPath(), StandardCharsets.UTF_8);
        List<Module> modules = parseModules(inputJson);
        // Rehydration only fetches missing files; the releases stay the ones that produced
        // the dump, not the JVM doing the fetching. Each module's own carries forward with
        // it below, and this is the dump-level one.
        Integer jdkRelease = parseJdkRelease(inputJson);

        Set<String> wanted = wantedNotations(modules);
        if (!wanted.isEmpty() && !getWiredAtConfiguration().getOrElse(false)) {
            throw new GradleException("uika: " + input + " did not exist when the build was"
                    + " configured, so Gradle could not set up resolution for " + wanted.size()
                    + " missing artifact(s). Create the dump before invoking"
                    + " uikaResolveClasspath (a separate invocation).");
        }
        if (!wanted.isEmpty()) {
            getLogger().lifecycle("uika: resolving {} missing artifact(s) via Gradle", wanted.size());
        }
        Map<String, File> resolvedByKey = new HashMap<>();
        for (ResolvedFile resolved : getResolvedFiles().get()) {
            resolvedByKey.put(resolved.key(), new File(resolved.file()));
        }

        // Rebuild the common model with real paths.
        int rewritten = 0;
        int unresolved = 0;
        List<Module> rewrittenModules = new ArrayList<>();
        for (Module module : modules) {
            List<Artifact> artifacts = new ArrayList<>();
            for (Artifact artifact : module.artifacts()) {
                // Project-attributed entries pass through untouched and unwarned, for the
                // reason wantedNotations gives. A rewrite that dropped the project key
                // would also put those coordinates back into the version diff as Removed.
                if (!new File(artifact.file()).exists() && artifact.group() != null
                        && artifact.project() == null) {
                    File local = resolvedByKey.get(keyOf(artifact));
                    if (local != null) {
                        // The attribution rides along even though the guard above makes it
                        // null today, so a change to that guard cannot silently strip it.
                        artifact = new Artifact(artifact.group(), artifact.name(),
                                artifact.version(), local.getAbsolutePath(), artifact.project());
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
            rewrittenModules.add(new Module(module.path(), module.classesDirs(), artifacts,
                    module.jdkRelease()));
        }

        if (unresolved > 0) {
            throw new GradleException("uika: " + unresolved + " artifact(s) could not be"
                    + " resolved (warnings above). The rehydrated dump would name files this"
                    + " machine does not have, and the check fails with \"cannot open\" on a"
                    + " compared-pair jar, so no output is written. Fall back to dumping the"
                    + " baseline from a checkout of the base commit.");
        }

        String json =
                DumpFormat.writeV2(
                        rewrittenModules, List.of(getRootDirPath().get()), jdkRelease);
        File out = getOutputFile().get().getAsFile();
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
        getLogger().lifecycle("uika: rehydrated dump -> {} ({} rewritten, {} unresolved)",
                out, rewritten, unresolved);
    }

    private static String keyOf(Artifact artifact) {
        return artifact.group() + ":" + artifact.name() + ":" + artifact.version()
                + ":" + new File(artifact.file()).getName();
    }

    /** g:n:v[:classifier]. The classifier is recovered from the original file name "name-version-classifier.jar". */
    static String notationOf(Artifact artifact) {
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
