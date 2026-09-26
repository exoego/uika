package net.exoego.uika.maven;

import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.UikaCli;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What the reactor's projects compile for, read from the spelling that pins the API rather
 * than the one that names the compiler JDK.
 *
 * <p>Shared by both mojos so the two uses cannot drift: {@code dump-classpath} records each
 * module's own release next to it, and {@code upgrade-check} passes the lowest of them as
 * {@code --jdk-release} (one process-global flag, so it has only one value to give).
 */
final class JdkReleases {
    private JdkReleases() {}

    /**
     * The JDK API release the checked application runs on, the LOWEST any project in the
     * reactor compiles for. {@link UikaCli#effectiveJdkRelease} clamps the result at
     * execution time.
     *
     * <p>The whole reactor, not just the top-level project. A module is free to override the
     * property, and reading only the aggregator reported a release no module compiles
     * against. The lowest of them, because one flag serves a run that checks every module and
     * under-claiming only costs Unknowns while over-claiming loses findings silently. The
     * dump keeps each module's own release next to it, which is what lets upgrade-check
     * scope a JDK move to the modules that made it; the flag stays one value because the
     * layer it switches on is process-wide.
     */
    static int lowest(List<MavenProject> reactorProjects, Properties userProperties) {
        Integer lowest = null;
        for (MavenProject project : reactorProjects) {
            Integer release = declaredRelease(project, userProperties);
            if (release != null) {
                lowest = lowest == null ? release : Math.min(lowest, release);
            }
        }
        return lowest == null ? Runtime.version().feature() : lowest;
    }

    /**
     * The release one module's dump entry records. What it declares, else the release of the
     * JDK that compiles it, which is the JVM running Maven unless javac runs on another JDK.
     * Null for a pom-packaged project, and null when that other JDK is in play, since its
     * release is not read here and the CLI then falls back to the dump-level value.
     *
     * <p>{@link #lowest} does not use it. The flag stays on declared releases, as before.
     */
    static Integer moduleRelease(MavenProject project, Properties userProperties) {
        Integer declared = declaredRelease(project, userProperties);
        if (declared != null || "pom".equals(project.getPackaging())
                || compilesOnAnotherJdk(project, userProperties)) {
            return declared;
        }
        return DumpFormat.buildJvmRelease();
    }

    /**
     * Whether javac may run on a JDK other than the one running Maven: a JDK toolchain, picked
     * by maven-toolchains-plugin or by the compiler's {@code <jdkToolchain>}, or a forked
     * {@code <executable>}.
     */
    static boolean compilesOnAnotherJdk(MavenProject project, Properties userProperties) {
        if (project.getPlugin("org.apache.maven.plugins:maven-toolchains-plugin") != null) {
            return true;
        }
        var fork = "true".equals(property(project, userProperties, "maven.compiler.fork"));
        var executable = !isBlank(property(project, userProperties, "maven.compiler.executable"));
        var compiler = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (compiler == null) {
            return fork && executable;
        }
        var pluginLevel = compiler.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        if (pluginLevel != null && pluginLevel.getChild("jdkToolchain") != null) {
            return true;
        }
        // Execution values override the plugin level, which overrides the properties, so a
        // plugin-level fork that every execution turns off never runs another javac.
        fork = flag(pluginLevel, "fork", fork);
        executable = flag(pluginLevel, "executable", executable);
        if (compiler.getExecutions().isEmpty()) {
            return fork && executable;
        }
        for (PluginExecution execution : compiler.getExecutions()) {
            var dom = execution.getConfiguration() instanceof Xpp3Dom d ? d : null;
            if (dom != null && dom.getChild("jdkToolchain") != null) {
                return true;
            }
            if (flag(dom, "fork", fork) && flag(dom, "executable", executable)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code name} is on in {@code dom}: true/false for fork, non-blank for executable. */
    private static boolean flag(Xpp3Dom dom, String name, boolean inherited) {
        var child = dom == null ? null : dom.getChild(name);
        if (child == null) {
            return inherited;
        }
        return "fork".equals(name) ? "true".equals(child.getValue()) : !isBlank(child.getValue());
    }

    /** A {@code -D} property wins over the POM's, as it does for the compiler's own parameters. */
    private static String property(MavenProject project, Properties userProperties, String name) {
        var value = userProperties.getProperty(name);
        return value != null ? value : project.getProperties().getProperty(name);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
    static Integer declaredRelease(MavenProject project, Properties userProperties) {
        if ("pom".equals(project.getPackaging())) {
            return null;
        }
        Integer configured = compilerPluginRelease(project);
        if (configured != null) {
            return configured;
        }
        for (String name : List.of("maven.compiler.release", "maven.compiler.target")) {
            Integer release = UikaCli.parseRelease(property(project, userProperties, name));
            if (release != null) {
                return release;
            }
        }
        return null;
    }

    /**
     * The lowest release maven-compiler-plugin actually compiles with, or null when it names
     * none. Executions count because a module that compiles one source root at 8 and another
     * at 17 runs on 8.
     *
     * <p>The plugin-level configuration is NOT one more candidate to minimize over. Maven
     * merges it into each execution with the execution winning, so it is a default, and a
     * value every execution overrides is never compiled with. Treating it as a candidate made
     * {@code <release>8</release>} at plugin level lose to nothing when both default-compile
     * and default-testCompile said 17, reporting 8 for a module that only ever compiles at 17.
     */
    private static Integer compilerPluginRelease(MavenProject project) {
        var compiler = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (compiler == null) {
            return null;
        }
        Integer pluginLevel = configuredRelease(compiler.getConfiguration());
        Integer lowest = null;
        var anyExecution = false;
        for (PluginExecution execution : compiler.getExecutions()) {
            Integer effective = configuredRelease(execution.getConfiguration());
            if (effective == null) {
                effective = pluginLevel;
            }
            if (effective == null) {
                continue;
            }
            anyExecution = true;
            lowest = lowest == null ? effective : Math.min(lowest, effective);
        }
        // No execution declares one: the plugin-level value is what the default lifecycle
        // executions would inherit, so it stands on its own.
        return anyExecution ? lowest : pluginLevel;
    }

    /** {@code <release>}, else {@code <target>}, of one configuration block. */
    private static Integer configuredRelease(Object configuration) {
        if (!(configuration instanceof Xpp3Dom dom)) {
            return null;
        }
        for (String name : List.of("release", "target")) {
            var child = dom.getChild(name);
            Integer release = child == null ? null : UikaCli.parseRelease(child.getValue());
            if (release != null) {
                return release;
            }
        }
        return null;
    }
}
