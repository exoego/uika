package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins `UikaCli.launchCommand`'s JVM flags against the CLI jar's own launcher.
///
/// The plugins pass the flags themselves so the jar does not start a second JVM to get
/// them, which makes this a hand copy of a decision that is measured and owned in
/// cli-java. A flag retuned there must not leave every build tool on the old one. Read
/// from the source because the core shares no classpath with the CLI. Both builds that
/// mount this directory run one level below the repository root.
final class LauncherFlagsSyncTest {
    @Test
    void theFlagsAndTheThresholdMatchTheLauncher() throws Exception {
        var launcher = Files.readString(
                Path.of("../cli-java/src/main/java/net/exoego/uika/cli/Launcher.java"));

        var added = new ArrayList<String>();
        var add = Pattern.compile("flags\\.add\\(\"([^\"]+)\"\\)").matcher(launcher);
        while (add.find()) {
            added.add(add.group(1));
        }
        var expected = new ArrayList<>(UikaCli.JVM_FLAGS);
        expected.add(UikaCli.SMALL_RUN_FLAG);
        assertEquals(expected, added);

        assertTrue(launcher.contains("int LARGE = " + UikaCli.LARGE + ";"), "LARGE moved");
        assertTrue(launcher.contains("/ " + UikaCli.DUMP_BYTES_PER_ARTIFACT + ";"),
                "the bytes-per-artifact estimate moved");
        assertTrue(launcher.contains("command.add(\"-Duika.child=true\")"),
                "the property that switches the relaunch off was renamed");
    }
}
