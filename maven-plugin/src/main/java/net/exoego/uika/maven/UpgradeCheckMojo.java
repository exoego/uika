package net.exoego.uika.maven;

import net.exoego.uika.plugin.core.JfrEvidence;
import net.exoego.uika.plugin.core.UikaCli;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
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
     * JFR class-load evidence from a test run of the current, not yet upgraded build: a
     * {@code .jfr} recording, or a directory of recordings, converted and passed as
     * {@code --class-load-log}. Collect it with the test JVM flag (JDK 17+), e.g.
     * {@code mvn test -DargLine="-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=/tmp/uika-jfr"}
     * (JFR generates pid-unique file names for a directory-valued filename; create the
     * directory first — given a missing parent JFR aborts JVM startup, but given an
     * existing parent it silently records to a single FILE at that path, every fork
     * clobbering the last), then
     * check with {@code -Duika.jfr=/tmp/uika-jfr}. Keep this recipe in sync with
     * {@code UikaCli.jfrClassLoadJvmArg} by hand: no mojo can inject into surefire. Use
     * an absolute path in a multi-module build: surefire forks resolve a relative path
     * against each module's basedir, while this aggregator mojo resolves it against the
     * execution root, so a relative directory collects recordings the check never reads.
     * A command-line {@code -DargLine} also replaces any POM-configured argLine (jacoco's
     * agent included); append to the POM's argLine instead when one exists.
     */
    @Parameter(property = "uika.jfr")
    private File jfr;

    /**
     * Where the CLI writes draft exclude rules for symbols never observed loading, passed as
     * {@code --draft-exclude-file}. The CLI rejects it without {@code jfr}.
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
            UikaCli.JdkSource jdk = UikaCli.JdkSource.current();
            Integer effectiveJdkRelease = UikaCli.effectiveJdkRelease(
                    jdkRelease != null ? jdkRelease : defaultJdkRelease(), jdk,
                    line -> getLog().info(line));
            // Recordings (a .jfr value, or recordings inside the directory) are converted
            // to the CLI's text format here: the CLI is JVM-free and never reads binary
            // JFR.
            List<Path> classLoadLogs = JfrEvidence.rewrite(
                    jfr != null ? List.of(jfr.toPath()) : List.of(),
                    Path.of(session.getExecutionRootDirectory(), "target", "uika",
                            JfrEvidence.WORK_DIR_NAME),
                    line -> getLog().info(line));
            exit = UikaCli.runUpgradeCheck(binary, before.toPath(), after.toPath(), failOn,
                    excludeFilePaths, effectiveJdkRelease, jdk,
                    classLoadLogs,
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
     * The JDK API release the checked application runs on: the LOWEST
     * {@code maven.compiler.release}, else {@code maven.compiler.target} (skipping "1.x"
     * pre-9 values, which are below the layer's floor anyway), across every project in the
     * reactor. {@link UikaCli#effectiveJdkRelease} clamps the result at execution time.
     *
     * <p>The whole reactor, not just the top-level project: a module is free to override the
     * property, and reading only the aggregator reported a release no module compiles
     * against. The lowest of them, because one flag serves a run that checks every module and
     * under-claiming only costs Unknowns while over-claiming loses findings silently. Issue
     * #128 tracks carrying a release per module in the dump instead.
     */
    private int defaultJdkRelease() {
        Integer lowest = null;
        for (MavenProject project : session.getAllProjects()) {
            Integer release = declaredRelease(project);
            if (release != null) {
                lowest = lowest == null ? release : Math.min(lowest, release);
            }
        }
        return lowest == null ? Runtime.version().feature() : lowest;
    }

    /** {@code maven.compiler.release}, else {@code .target}, of one project; null when unset. */
    private static Integer declaredRelease(MavenProject project) {
        var properties = project.getProperties();
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
        return null;
    }
}
