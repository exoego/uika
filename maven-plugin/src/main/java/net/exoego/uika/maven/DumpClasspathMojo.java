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
        Map<String, MavenProject> reactorByGav = new HashMap<>();
        for (MavenProject reactorProject : session.getAllProjects()) {
            reactorByGav.put(gav(reactorProject), reactorProject);
        }
        Map<MavenProject, String> moduleNames = moduleNames(session.getAllProjects());
        List<ClasspathDump.Module> modules = new ArrayList<>();
        for (MavenProject reactorProject : session.getAllProjects()) {
            modules.add(moduleOf(reactorProject, reactorByGav, moduleNames));
        }

        String root = session.getExecutionRootDirectory();
        String json = DumpFormat.writeV2(modules, List.of(root), DumpFormat.dumpRelease(modules));
        File parent = outputFile.getParentFile();
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
     * Module identity: ":" + artifactId, disambiguated with the groupId when two reactor
     * projects share an artifactId. Per-module checking pairs and attributes modules by this
     * name, so a collision would silently drop the second module from the check.
     */
    private static Map<MavenProject, String> moduleNames(List<MavenProject> reactorProjects) {
        Map<String, Integer> artifactIdCounts = new HashMap<>();
        for (MavenProject project : reactorProjects) {
            artifactIdCounts.merge(project.getArtifactId(), 1, Integer::sum);
        }
        Map<MavenProject, String> names = new HashMap<>();
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
        List<String> classesDirs = new ArrayList<>();
        File outputDirectory = new File(reactorProject.getBuild().getOutputDirectory());
        if (outputDirectory.exists()) {
            classesDirs.add(outputDirectory.getAbsolutePath());
        }

        // getArtifacts() iterates in the resolver's dependency order — the order Maven
        // builds the real runtime classpath in. Keep it: per-module checking applies JVM
        // first-wins duplicate-class semantics to this list, and sorting could crown a
        // different duplicate than the real classpath does.
        List<ClasspathDump.Artifact> artifacts = new ArrayList<>();
        Set<Artifact> projectArtifacts = reactorProject.getArtifacts();
        for (Artifact artifact : projectArtifacts) {
            if (!isRuntimeVisible(artifact)) {
                continue;
            }
            MavenProject sibling = reactorByGav.get(
                    gav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
            if (sibling != null) {
                // Reactor dependency: attribute it to its producing module and never drop it.
                // An unpackaged sibling (no jar yet) is dumped as its output directory, so the
                // classpath stays complete without requiring a package phase first.
                File file = artifact.getFile();
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
        String scope = artifact.getScope();
        return scope == null
                || Artifact.SCOPE_COMPILE.equals(scope)
                || Artifact.SCOPE_RUNTIME.equals(scope);
    }
}
