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
     * TOML files of known false positives to suppress, passed as repeated
     * {@code --exclude-file}. As a nested list in the POM
     * ({@code <excludeFiles><excludeFile>...</excludeFile></excludeFiles>}), or as a
     * comma-separated {@code -Duika.excludeFiles=a.toml,b.toml}.
     *
     * <p>One parameter, not two: plexus splits a property expression on commas for a
     * collection parameter ({@code AbstractCollectionConverter.csvToXml}) and aligns each
     * relative entry to the basedir through {@code FileConverter}, so the command-line form
     * needs no code. It follows Maven's ordinary precedence as a result -- a POM
     * {@code <excludeFiles>} shadows the property rather than being appended to, the same
     * as every other knob on this mojo. A path containing a comma has to go in the POM,
     * since the comma is the delimiter there.
     */
    @Parameter(property = "uika.excludeFiles")
    private List<File> excludeFiles = new ArrayList<>();

    /**
     * JDK API release for the CLI's {@code --jdk-release} (resolves JDK hierarchy escapes
     * instead of counting them unverified). Defaults to the lowest release any project in the
     * reactor compiles for, from maven-compiler-plugin's {@code <release>}/{@code <target>}
     * else {@code maven.compiler.release}/{@code maven.compiler.target}, and to the build JVM
     * when no project declares one. Clamped to what the build JVM's ct.sym can serve. Set 0 to
     * disable the layer.
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
     * clobbering the last — and quote the filename value when the path carries a comma,
     * the option delimiter: unquoted it silently truncates with exit 0), then
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

    /**
     * Check the union of every module's classpath once instead of each module against its
     * own resolution, passed as {@code --merged-classpath}.
     *
     * <p>Per-module checking scans once per module, so a large reactor can pay minutes for
     * it. The trade is real in the other direction too: a break that only one module's
     * resolution shows can hide behind another module's version of the same jar, which is
     * why this is off by default.
     */
    @Parameter(property = "uika.mergedClasspath", defaultValue = "false")
    private boolean mergedClasspath;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repositorySession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepositories;

    @Component
    private RepositorySystem repositorySystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // UIKA_CLI_PATH wins outright, so an air-gapped build or one pointed at a locally
        // built binary never reaches the resolver, the version, or the classifier.
        Path override = UikaCli.binaryOverride();
        File zip = null;
        String classifier = null;
        if (override == null) {
            classifier = UikaCli.platformClassifier();
            var request = new ArtifactRequest(
                    new DefaultArtifact(
                            UikaCli.GROUP, UikaCli.ARTIFACT, classifier, "zip", cliVersion),
                    remoteRepositories,
                    "uika");
            try {
                zip = repositorySystem.resolveArtifact(repositorySession, request)
                        .getArtifact().getFile();
            } catch (ArtifactResolutionException e) {
                throw new MojoExecutionException("failed to resolve " + request.getArtifact(), e);
            }
        }

        // Nulls, not blanks: plexus turns an empty CSV entry into a null element, and a
        // -D list assembled by a CI script picks up doubled or trailing commas.
        List<Path> excludeFilePaths = excludeFiles.stream()
                .filter(java.util.Objects::nonNull)
                .map(File::toPath)
                .toList();
        int exit;
        try {
            Path binary = override != null
                    ? override
                    : UikaCli.extractBinary(zip.toPath(), Path.of(session.getExecutionRootDirectory(),
                            "target", "uika", "cli-" + cliVersion + "-" + classifier));
            UikaCli.JdkSource jdk = UikaCli.JdkSource.current();
            Integer effectiveJdkRelease = UikaCli.effectiveJdkRelease(
                    jdkRelease != null ? jdkRelease : JdkReleases.lowest(session.getAllProjects()),
                    jdk,
                    line -> getLog().info(line));
            // Recordings (a .jfr value, or recordings inside the directory) are converted
            // to the CLI's text format here: the CLI is JVM-free and never reads binary
            // JFR.
            var classLoadLogs = JfrEvidence.rewrite(
                    jfr != null ? List.of(jfr.toPath()) : List.of(),
                    Path.of(session.getExecutionRootDirectory(), "target", "uika",
                            JfrEvidence.WORK_DIR_NAME),
                    line -> getLog().info(line));
            exit = UikaCli.runUpgradeCheck(binary, before.toPath(), after.toPath(), failOn,
                    excludeFilePaths, effectiveJdkRelease, jdk,
                    classLoadLogs,
                    draftExcludeFile != null ? draftExcludeFile.toPath() : null,
                    mergedClasspath,
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

}
