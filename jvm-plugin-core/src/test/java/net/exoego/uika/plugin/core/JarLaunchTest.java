package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// What `runUpgradeCheck` does with the CLI jar, read back from a stub jar that records the
/// JVM it was started in. A real child JVM, because the point is what reaches it: a flag
/// this JVM rejects, or a relaunch the property failed to switch off, shows only there.
final class JarLaunchTest {
    @TempDir
    Path dir;

    @Test
    void theJarRunsOnThisJvmWithTheLauncherFlags() throws Exception {
        var before = Files.writeString(dir.resolve("before.json"), "{}");
        var after = Files.writeString(dir.resolve("after.json"), "{}");
        var jar = dir.resolve("uika-cli-stub.jar");
        StubCli.writeJar(jar, "stub line", 3);
        var output = new ArrayList<String>();

        var exit = UikaCli.runUpgradeCheck(jar, before, after, "any", null, null,
                UikaCli.JdkSource.current(), null, null, false, output::add);

        assertEquals(3, exit, String.join("\n", output));
        assertEquals(List.of("stub line"), output);
        assertEquals(String.join(" ", "upgrade-check", "--before", before.toString(),
                "--after", after.toString(), "--fail-on", "any"), recorded(before, ".args"));
        var jvm = Files.readAllLines(Path.of(before + ".jvm"));
        assertTrue(jvm.containsAll(UikaCli.JVM_FLAGS), jvm.toString());
        assertTrue(jvm.contains(UikaCli.SMALL_RUN_FLAG), jvm.toString());
        // Without it the jar starts a second JVM and this one idles for the whole run.
        assertEquals("true", recorded(before, ".child"));
        assertEquals(Path.of(System.getProperty("java.home")).toRealPath().toString(),
                recorded(before, ".home"));
    }

    /// C1 alone loses to C2 on a large scan, and the dumps' size is the only measure of the
    /// scan that costs nothing to read.
    @Test
    void largeDumpsKeepTheOptimizingCompiler() throws Exception {
        var half = new byte[UikaCli.LARGE * UikaCli.DUMP_BYTES_PER_ARTIFACT / 2];
        var before = Files.write(dir.resolve("before.json"), half);
        var after = Files.write(dir.resolve("after.json"), half);

        var command = UikaCli.launchCommand(Path.of("uika-cli.jar"), before, after);

        assertFalse(command.contains(UikaCli.SMALL_RUN_FLAG), command.toString());
        assertTrue(command.containsAll(UikaCli.JVM_FLAGS), command.toString());
    }

    /// A dump that is not there is the CLI's error to report, with its own message and exit
    /// code. Sizing the run must not fail first and hide that.
    @Test
    void aMissingDumpCountsAsEmpty() {
        var command = UikaCli.launchCommand(Path.of("uika-cli.jar"),
                dir.resolve("no-before.json"), dir.resolve("no-after.json"));

        assertTrue(command.contains(UikaCli.SMALL_RUN_FLAG), command.toString());
    }

    @Test
    void aNativeBinaryStartsItself() {
        var binary = dir.resolve("uika");
        assertEquals(List.of(binary.toString()),
                UikaCli.launchCommand(binary, dir.resolve("before.json"), dir.resolve("after.json")));
    }

    private static String recorded(Path before, String suffix) throws Exception {
        return Files.readString(Path.of(before + suffix)).strip();
    }
}
