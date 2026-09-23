package net.exoego.uika.maven;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DumpClasspathMojoTest {

    @Test
    void aSharedArtifactIdIsDisambiguatedByItsGroup() {
        var core = project("com.example.server", "core");
        var otherCore = project("com.example.client", "core");
        var app = project("com.example.server", "app");

        var names = DumpClasspathMojo.moduleNames(List.of(core, otherCore, app));

        // Per-module checking pairs and attributes modules by this name, so two reactor
        // projects sharing an artifactId would otherwise collapse into one and the second
        // would silently drop out of the check.
        assertEquals(":com.example.server:core", names.get(core));
        assertEquals(":com.example.client:core", names.get(otherCore));
        assertEquals(":app", names.get(app), "the group is spelled out only where it disambiguates");
    }

    private static MavenProject project(String groupId, String artifactId) {
        var model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        return new MavenProject(model);
    }
}
