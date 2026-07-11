package net.exoego.uika.maven;

import net.exoego.uika.plugin.core.UikaCli;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs {@code uika upgrade-check} between two classpath dumps. The CLI binary is resolved as
 * {@code net.exoego.uika:uika-cli:<version>:<platform>@zip} through this build's repositories, so
 * downloads land in the local repository and the version is bumped together with the plugin.
 */
@Mojo(name = "upgrade-check", aggregator = true, threadSafe = true)
public final class UpgradeCheckMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "uika.before", required = true)
    private File before;

    @Parameter(property = "uika.after", required = true)
    private File after;

    /** uika-cli version; defaults to this plugin's own version. */
    @Parameter(property = "uika.cliVersion", defaultValue = "${plugin.version}")
    private String cliVersion;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repositorySession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepositories;

    @Component
    private RepositorySystem repositorySystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String classifier = UikaCli.platformClassifier();
        ArtifactRequest request = new ArtifactRequest(
                new DefaultArtifact(UikaCli.GROUP, UikaCli.ARTIFACT, classifier, "zip", cliVersion),
                remoteRepositories,
                "uika");
        File zip;
        try {
            zip = repositorySystem.resolveArtifact(repositorySession, request).getArtifact().getFile();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("failed to resolve " + request.getArtifact(), e);
        }

        Path installDir = Path.of(session.getExecutionRootDirectory(),
                "target", "uika", "cli-" + cliVersion + "-" + classifier);
        int exit;
        try {
            Path binary = UikaCli.extractBinary(zip.toPath(), installDir);
            exit = UikaCli.runUpgradeCheck(binary, before.toPath(), after.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("failed to run uika upgrade-check", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("interrupted while running uika upgrade-check", e);
        }
        if (exit == 1) {
            throw new MojoFailureException("uika upgrade-check found broken references (see output above)");
        }
        if (exit != 0) {
            throw new MojoExecutionException("uika upgrade-check failed with exit code " + exit);
        }
    }
}
