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
import java.util.ArrayList;
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

    /** When to fail the build: {@code never}, {@code reachable}, or {@code any} (default). */
    @Parameter(property = "uika.failOn", defaultValue = "any")
    private String failOn;

    /**
     * TOML files of known false positives to suppress, passed as repeated {@code --exclude-file}.
     * Configured as a nested list ({@code <excludeFiles><excludeFile>...</excludeFile></excludeFiles>})
     * since Maven properties do not support lists.
     */
    @Parameter
    private List<File> excludeFiles = new ArrayList<>();

    /**
     * JDK API release for the CLI's {@code --jdk-release} (resolves JDK hierarchy escapes
     * instead of counting them unverified). Defaults to {@code maven.compiler.release}, then
     * {@code maven.compiler.target}, then the build JVM; clamped to what the build JVM's
     * ct.sym can serve. Set 0 to disable the layer.
     */
    @Parameter(property = "uika.jdkRelease")
    private Integer jdkRelease;

    /**
     * Runtime class-load log (file or directory) from a test run of the current, not yet
     * upgraded build, passed as {@code --class-load-log}. Collect it with the test JVM flag
     * into a dedicated directory, e.g.
     * {@code mvn test -DargLine="-Xlog:class+load=info:file=/tmp/uika-load/load-%p.log"}
     * ({@code %p} keeps parallel forks from truncating each other's file; create the
     * directory first, since {@code -Xlog} aborts JVM startup when it cannot open the file),
     * then check with {@code -Duika.classLoadLog=/tmp/uika-load}. Use an absolute path in a
     * multi-module build: surefire forks resolve a relative path against each module's
     * basedir, while this aggregator mojo resolves it against the execution root, so a
     * relative directory collects logs the check never reads. A command-line
     * {@code -DargLine} also replaces any POM-configured argLine (jacoco's agent included);
     * append to the POM's argLine instead when one exists.
     */
    @Parameter(property = "uika.classLoadLog")
    private File classLoadLog;

    /**
     * Where the CLI writes draft exclude rules for symbols never observed loading, passed as
     * {@code --draft-exclude-file}. The CLI rejects it without {@code classLoadLog}.
     */
    @Parameter(property = "uika.draftExcludeFile")
    private File draftExcludeFile;

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
        List<Path> excludeFilePaths = excludeFiles.stream().map(File::toPath).toList();
        int exit;
        try {
            Path binary = UikaCli.extractBinary(zip.toPath(), installDir);
            Integer effectiveJdkRelease = UikaCli.effectiveJdkRelease(
                    jdkRelease != null ? jdkRelease : defaultJdkRelease(),
                    line -> getLog().info(line));
            exit = UikaCli.runUpgradeCheck(binary, before.toPath(), after.toPath(), failOn,
                    excludeFilePaths, effectiveJdkRelease,
                    classLoadLog != null ? List.of(classLoadLog.toPath()) : List.of(),
                    draftExcludeFile != null ? draftExcludeFile.toPath() : null,
                    line -> getLog().info(line));
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

    /**
     * The JDK API release the checked application most plausibly runs on:
     * {@code maven.compiler.release}, else {@code maven.compiler.target} (skipping "1.x"
     * pre-9 values, which are below the layer's floor anyway), else the JVM running the
     * build. {@link UikaCli#effectiveJdkRelease} clamps the result at execution time.
     */
    private int defaultJdkRelease() {
        var properties = session.getTopLevelProject().getProperties();
        for (String name : List.of("maven.compiler.release", "maven.compiler.target")) {
            String value = properties.getProperty(name);
            if (value != null && !value.isBlank() && !value.startsWith("1.")) {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    // Fall through to the next source.
                }
            }
        }
        return Runtime.version().feature();
    }
}
