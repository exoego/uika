package net.exoego.uika.maven;

import net.exoego.uika.plugin.core.ClasspathDump;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.UikaCli;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mojo(
        name = "dump-classpath",
        defaultPhase = LifecyclePhase.NONE,
        aggregator = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        threadSafe = true)
public final class DumpClasspathMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "uika.output", defaultValue = "${session.executionRootDirectory}/target/uika/classpath.json")
    private File outputFile;

    /**
     * The release the application runs on, recorded on every module instead of what each one
     * compiles for. The same parameter the upgrade-check goal takes, because a build has one
     * answer to give. Set it for the case the derivation cannot see: a reactor compiling
     * {@code maven.compiler.release} 11 that ships on a 21 runtime.
     */
    @Parameter(property = "uika.jdkRelease")
    private Integer jdkRelease;

    @Override
    public void execute() throws MojoExecutionException {
        // Attribution maps over the WHOLE reactor: a selected module may depend on an
        // unselected sibling, and that edge keeps its "project" key either way.
        var reactorByGav = new HashMap<String, MavenProject>();
        for (MavenProject reactorProject : session.getAllProjects()) {
            reactorByGav.put(gav(reactorProject), reactorProject);
        }
        var moduleNames = moduleNames(session.getAllProjects());
        // Modules over the SELECTED set only. Maven resolves dependencies for
        // session.getProjects(), so a -pl-excluded project has an empty getArtifacts()
        // and would land in the dump as a module with zero artifacts, silently
        // under-reporting. Without -pl the two sets are the same reactor.
        var modules = new ArrayList<ClasspathDump.Module>();
        for (MavenProject reactorProject : session.getProjects()) {
            if (compilesNothing(reactorProject)) {
                continue;
            }
            modules.add(moduleOf(reactorProject, reactorByGav, moduleNames));
        }

        var root = session.getExecutionRootDirectory();
        String json = DumpFormat.writeV2(modules, List.of(root), DumpFormat.dumpRelease(modules));
        var parent = outputFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            Files.writeString(outputFile.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("failed to write uika classpath dump: " + outputFile, e);
        }
        getLog().info("uika classpath dump: " + outputFile);
    }

    /**
     * Whether this project ships no bytecode of its own, in which case it is not a module.
     *
     * <p>An aggregator or BOM with its own {@code <dependencies>} used to land in the dump as a
     * module with no classesDirs and a full artifact list, and upgrade-check turns that into a
     * check RUN of its own. That run has no application roots, so reachability never runs for
     * it and {@code --fail-on reachable} degrades to {@code any} for every violation it finds —
     * the same breaks the real modules proved unreachable then fail the gate. It also rescans
     * every jar a second time and doubles the reported scanned and unverified counts.
     *
     * <p>Packaging, not an empty output directory: this goal declares no lifecycle phase, so a
     * run on an unbuilt tree has empty classesDirs everywhere and that test would empty the
     * whole dump. Dropping pom-packaged projects cannot move {@code DumpFormat.dumpRelease}
     * either, because {@link JdkReleases#declaredRelease} already answers null for them and an
     * explicit {@code jdkRelease} override replaces every module's value with the same number.
     */
    private static boolean compilesNothing(MavenProject project) {
        return "pom".equals(project.getPackaging());
    }

    /**
     * Module identity: ":" + artifactId, disambiguated with the groupId when two reactor
     * projects share an artifactId. Per-module checking pairs and attributes modules by this
     * name, so a collision would silently drop the second module from the check.
     */
    private static Map<MavenProject, String> moduleNames(List<MavenProject> reactorProjects) {
        var artifactIdCounts = new HashMap<String, Integer>();
        for (MavenProject project : reactorProjects) {
            artifactIdCounts.merge(project.getArtifactId(), 1, Integer::sum);
        }
        var names = new HashMap<MavenProject, String>();
        for (MavenProject project : reactorProjects) {
            names.put(project, artifactIdCounts.get(project.getArtifactId()) > 1
                    ? ":" + project.getGroupId() + ":" + project.getArtifactId()
                    : ":" + project.getArtifactId());
        }
        return names;
    }

    private ClasspathDump.Module moduleOf(
            MavenProject reactorProject,
            Map<String, MavenProject> reactorByGav,
            Map<MavenProject, String> moduleNames) {
        var classesDirs = new ArrayList<String>();
        var outputDirectory = new File(reactorProject.getBuild().getOutputDirectory());
        if (outputDirectory.exists()) {
            classesDirs.add(outputDirectory.getAbsolutePath());
        }

        // getArtifacts() iterates in the resolver's dependency order — the order Maven
        // builds the real runtime classpath in. Keep it: per-module checking applies JVM
        // first-wins duplicate-class semantics to this list, and sorting could crown a
        // different duplicate than the real classpath does.
        var artifacts = new ArrayList<ClasspathDump.Artifact>();
        Set<Artifact> projectArtifacts = reactorProject.getArtifacts();
        for (Artifact artifact : projectArtifacts) {
            if (!isRuntimeVisible(artifact)) {
                continue;
            }
            var sibling = reactorByGav.get(
                    gav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
            if (sibling != null) {
                // Reactor dependency: attribute it to its producing module and never drop it.
                // An unpackaged sibling (no jar yet) is dumped as its output directory, so the
                // classpath stays complete without requiring a package phase first.
                var file = artifact.getFile();
                String path = file != null && file.exists()
                        ? file.getAbsolutePath()
                        : new File(sibling.getBuild().getOutputDirectory()).getAbsolutePath();
                artifacts.add(new ClasspathDump.Artifact(
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion(),
                        path,
                        moduleNames.get(sibling)));
            } else if (artifact.getFile() != null && artifact.getFile().exists()) {
                artifacts.add(new ClasspathDump.Artifact(
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion(),
                        artifact.getFile().getAbsolutePath()));
            }
        }

        Integer override = UikaCli.overrideRelease(jdkRelease);
        return new ClasspathDump.Module(moduleNames.get(reactorProject), classesDirs, artifacts,
                override != null ? override : JdkReleases.declaredRelease(reactorProject));
    }

    private static String gav(MavenProject project) {
        return gav(project.getGroupId(), project.getArtifactId(), project.getVersion());
    }

    private static String gav(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }

    private boolean isRuntimeVisible(Artifact artifact) {
        var scope = artifact.getScope();
        return scope == null
                || Artifact.SCOPE_COMPILE.equals(scope)
                || Artifact.SCOPE_RUNTIME.equals(scope);
    }
}
