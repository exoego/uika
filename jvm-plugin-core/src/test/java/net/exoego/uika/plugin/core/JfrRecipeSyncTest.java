package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the hand-written copies of the JFR test-JVM flag against the composer.
///
/// Maven cannot inject into surefire and the two Clojure front ends have nothing to inject
/// into, so those three carry the flag as prose. The README shows the canonical form. Each
/// file must contain the composed flag verbatim up to the filename value, so a change to the
/// event settings or their order fails here instead of drifting silently into a recipe that
/// records nothing.
///
/// In the core test source set rather than the Gradle one: the Maven build mounts only this
/// directory, so a guard living next to the Gradle tests never ran in the build whose own
/// page and mojo javadoc it protects. Both builds run from a module directory one level
/// below the repository root, which is what makes the relative paths work in either.
final class JfrRecipeSyncTest {
    @Test
    void handWrittenRecipesCarryTheComposedFlag() throws Exception {
        var composed = UikaCli.jfrClassLoadJvmArg(Path.of("VALUE"));
        var prefix = composed.substring(0, composed.indexOf("VALUE"));
        for (var relative : List.of(
                "../README.md",
                "../docs/maven.md",
                "../docs/clojure.md",
                "../docs/leiningen.md",
                "../maven-plugin/src/main/java/net/exoego/uika/maven/UpgradeCheckMojo.java")) {
            var file = Path.of(relative);
            var text = Files.readString(file);
            assertTrue(text.contains(prefix),
                    () -> file + " does not carry the composed StartFlightRecording flag: "
                            + prefix);
        }
    }
}
