package net.exoego.uika.maven;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The release derivation that is Maven's alone.
 *
 * <p>Every other integration reads a flat option list through the shared
 * {@code UikaCli.declaredRelease}, which {@code ReleaseDerivationTest} covers. Maven instead
 * walks maven-compiler-plugin's configuration, per EXECUTION, and skips pom packaging, and
 * none of that is reachable from a flat list. Until this file the plugin had no test source
 * directory at all, so the only thing exercising it was one integration test built around a
 * whole reactor.
 */
final class JdkReleasesTest {

    @Test
    void aPomPackagedProjectDeclaresNothing() {
        var bom = project("pom");
        bom.getProperties().setProperty("maven.compiler.release", "8");
        // A BOM or aggregator compiles nothing, so the property it happens to inherit is not
        // a target anyone ships. Letting it into the minimum would gut the layer for the
        // modules that do compile.
        assertNull(JdkReleases.declaredRelease(bom));
    }

    @Test
    void thePropertiesAreReadInOrder() {
        var byRelease = project("jar");
        byRelease.getProperties().setProperty("maven.compiler.release", "17");
        byRelease.getProperties().setProperty("maven.compiler.target", "11");
        assertEquals(17, JdkReleases.declaredRelease(byRelease),
                "maven.compiler.release pins the API; target only names the bytecode level");

        var byTarget = project("jar");
        byTarget.getProperties().setProperty("maven.compiler.target", "1.8");
        assertEquals(8, JdkReleases.declaredRelease(byTarget),
                "the legacy 1.8 spelling names release 8, which the layer can serve");

        var belowTheFloor = project("jar");
        belowTheFloor.getProperties().setProperty("maven.compiler.release", "7");
        assertNull(JdkReleases.declaredRelease(belowTheFloor),
                "below the floor is no declaration at all, so it cannot drag the minimum under it");

        assertNull(JdkReleases.declaredRelease(project("jar")));
    }

    @Test
    void thePluginConfigurationBeatsTheProperties() {
        var project = project("jar");
        project.getProperties().setProperty("maven.compiler.release", "8");
        project.getBuild().addPlugin(compilerPlugin(release("17")));
        // A module that configures the plugin directly overrides whatever property it
        // inherits from a parent it does not control.
        assertEquals(17, JdkReleases.declaredRelease(project));
    }

    @Test
    void theLowestExecutionWins() {
        var project = project("jar");
        // One source root at 8 and another at 17 means the module runs on 8.
        project.getBuild().addPlugin(
                compilerPlugin(null, execution("legacy", release("8")), execution("main", release("17"))));
        assertEquals(8, JdkReleases.declaredRelease(project));
    }

    @Test
    void aPluginLevelValueEveryExecutionOverridesIsNeverCompiledWith() {
        var project = project("jar");
        // Maven merges the plugin-level configuration into each execution with the execution
        // winning, so it is a DEFAULT rather than one more candidate to minimize over.
        // Counting it made <release>8</release> at plugin level win over two executions that
        // both say 17, reporting 8 for a module that only ever compiles at 17.
        project.getBuild().addPlugin(compilerPlugin(
                release("8"),
                execution("default-compile", release("17")),
                execution("default-testCompile", release("17"))));
        assertEquals(17, JdkReleases.declaredRelease(project));
    }

    @Test
    void aPluginLevelValueStandsAloneWhenNoExecutionDeclaresOne() {
        var project = project("jar");
        // Nothing overrides it, so it is what the default lifecycle executions inherit.
        project.getBuild().addPlugin(compilerPlugin(release("11"), execution("main", null)));
        assertEquals(11, JdkReleases.declaredRelease(project));
    }

    @Test
    void theReactorMinimumIgnoresWhatDeclaresNothing() {
        var eleven = project("jar");
        eleven.getProperties().setProperty("maven.compiler.release", "11");
        var seventeen = project("jar");
        seventeen.getProperties().setProperty("maven.compiler.release", "17");
        // The aggregator declares 8 and compiles nothing, so it must not drag the flag down.
        var aggregator = project("pom");
        aggregator.getProperties().setProperty("maven.compiler.release", "8");

        assertEquals(11, JdkReleases.lowest(List.of(seventeen, eleven, aggregator)));
        // Nothing declares a servable target, so the build JVM is the only evidence left.
        assertEquals(Runtime.version().feature(),
                JdkReleases.lowest(List.of(project("jar"), aggregator)));
        assertEquals(Runtime.version().feature(), JdkReleases.lowest(List.of()));
    }

    private static MavenProject project(String packaging) {
        var model = new Model();
        model.setPackaging(packaging);
        model.setBuild(new Build());
        return new MavenProject(model);
    }

    private static Plugin compilerPlugin(Xpp3Dom configuration, PluginExecution... executions) {
        var plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        plugin.setConfiguration(configuration);
        for (PluginExecution execution : executions) {
            plugin.addExecution(execution);
        }
        return plugin;
    }

    private static PluginExecution execution(String id, Xpp3Dom configuration) {
        var execution = new PluginExecution();
        execution.setId(id);
        execution.setConfiguration(configuration);
        return execution;
    }

    private static Xpp3Dom release(String value) {
        var configuration = new Xpp3Dom("configuration");
        var release = new Xpp3Dom("release");
        release.setValue(value);
        configuration.addChild(release);
        return configuration;
    }
}
