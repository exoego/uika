package net.exoego.uika.maven;

import net.exoego.uika.plugin.core.ClasspathDump;
import net.exoego.uika.plugin.core.DumpFormat;
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
import java.util.Comparator;
import java.util.List;
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

    @Override
    public void execute() throws MojoExecutionException {
        List<ClasspathDump.Module> modules = new ArrayList<>();
        for (MavenProject reactorProject : session.getAllProjects()) {
            modules.add(moduleOf(reactorProject));
        }

        String root = session.getExecutionRootDirectory();
        String json = DumpFormat.writeV2(modules, List.of(root));
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

    private ClasspathDump.Module moduleOf(MavenProject reactorProject) {
        List<String> classesDirs = new ArrayList<>();
        File outputDirectory = new File(reactorProject.getBuild().getOutputDirectory());
        if (outputDirectory.exists()) {
            classesDirs.add(outputDirectory.getAbsolutePath());
        }

        List<ClasspathDump.Artifact> artifacts = new ArrayList<>();
        Set<Artifact> projectArtifacts = reactorProject.getArtifacts();
        projectArtifacts.stream()
                .filter(this::isRuntimeVisible)
                .filter(artifact -> artifact.getFile() != null && artifact.getFile().exists())
                .sorted(Comparator.comparing(Artifact::getGroupId)
                        .thenComparing(Artifact::getArtifactId)
                        .thenComparing(Artifact::getVersion)
                        .thenComparing(artifact -> artifact.getFile().getAbsolutePath()))
                .forEach(artifact -> artifacts.add(new ClasspathDump.Artifact(
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion(),
                        artifact.getFile().getAbsolutePath())));

        return new ClasspathDump.Module(":" + reactorProject.getArtifactId(), classesDirs, artifacts);
    }

    private boolean isRuntimeVisible(Artifact artifact) {
        String scope = artifact.getScope();
        return scope == null
                || Artifact.SCOPE_COMPILE.equals(scope)
                || Artifact.SCOPE_RUNTIME.equals(scope);
    }
}
