package net.exoego.uika.maven;

import net.exoego.uika.plugin.core.JfrEvidence;
import net.exoego.uika.plugin.core.UikaCli;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
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
     * The JDK API release the checked application runs on, the LOWEST any project in the
     * reactor compiles for. {@link UikaCli#effectiveJdkRelease} clamps the result at
     * execution time.
     *
     * <p>The whole reactor, not just the top-level project. A module is free to override the
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

    /**
     * What one project compiles for, or null when it declares nothing servable.
     *
     * <p>maven-compiler-plugin's own {@code <release>}/{@code <target>} before the properties,
     * since a module that configures the plugin directly overrides whatever property it
     * inherits. A pom-packaged project is skipped outright: it compiles nothing, so the
     * {@code maven.compiler.release} a BOM or sub-aggregator inherits is not a target anyone
     * ships, and letting it into the minimum would gut the layer for the modules that do.
     */
    private static Integer declaredRelease(MavenProject project) {
        if ("pom".equals(project.getPackaging())) {
            return null;
        }
        Integer configured = compilerPluginRelease(project);
        if (configured != null) {
            return configured;
        }
        var properties = project.getProperties();
        for (String name : List.of("maven.compiler.release", "maven.compiler.target")) {
            Integer release = UikaCli.parseRelease(properties.getProperty(name));
            if (release != null) {
                return release;
            }
        }
        return null;
    }

    /**
     * The lowest release maven-compiler-plugin is configured with, across the plugin-level
     * configuration and every execution, or null when it names none. Executions count because
     * a module that compiles one source root at 8 and another at 17 runs on 8.
     */
    private static Integer compilerPluginRelease(MavenProject project) {
        Plugin compiler = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (compiler == null) {
            return null;
        }
        List<Object> configurations = new ArrayList<>();
        configurations.add(compiler.getConfiguration());
        for (PluginExecution execution : compiler.getExecutions()) {
            configurations.add(execution.getConfiguration());
        }
        Integer lowest = null;
        for (Object configuration : configurations) {
            if (!(configuration instanceof Xpp3Dom dom)) {
                continue;
            }
            for (String name : List.of("release", "target")) {
                Xpp3Dom child = dom.getChild(name);
                Integer release = child == null ? null : UikaCli.parseRelease(child.getValue());
                if (release != null) {
                    lowest = lowest == null ? release : Math.min(lowest, release);
                    break;
                }
            }
        }
        return lowest;
    }
}
