package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// What `runUpgradeCheck` hands the CLI, read back from a stub that records its argv and
/// its UIKA_JDK. The stub is a POSIX shell script.
@DisabledOnOs(OS.WINDOWS)
final class UpgradeCheckCommandTest {
    @TempDir
    Path dir;

    @Test
    void everyOptionIsPassedThrough() throws Exception {
        var jdk = new UikaCli.JdkSource(dir.resolve("jdk"), 21);
        var output = new ArrayList<String>();

        var exit = UikaCli.runUpgradeCheck(stub(), Path.of("before.json"), Path.of("after.json"),
                "reachable", List.of(Path.of("a.toml"), Path.of("b.toml")), 17, jdk,
                List.of(Path.of("load.log"), Path.of("jfr")), Path.of("draft.toml"), true,
                output::add);

        assertEquals(List.of("upgrade-check", "--before", "before.json", "--after", "after.json",
                "--fail-on", "reachable",
                "--exclude-file", "a.toml", "--exclude-file", "b.toml",
                "--jdk-release", "17",
                "--class-load-log", "load.log", "--class-load-log", "jfr",
                "--draft-exclude-file", "draft.toml",
                "--merged-classpath"), recordedArgs());
        // UIKA_JDK must name the JDK the release was clamped against, not the caller's JAVA_HOME.
        assertEquals(List.of("UIKA_JDK=" + jdk.home(), "second line"), output);
        assertEquals(3, exit, "the CLI exit code is what the plugins map to a build failure");
    }

    @Test
    void unsetOptionsAddNoFlagAndExportNoJdk() throws Exception {
        assumeTrue(System.getenv("UIKA_JDK") == null, "UIKA_JDK is inherited from this environment");
        // A build property set to nothing arrives as the empty string, not as null.
        for (String failOn : Arrays.asList(null, "")) {
            var output = new ArrayList<String>();

            UikaCli.runUpgradeCheck(stub(), Path.of("before.json"), Path.of("after.json"),
                    failOn, null, null, UikaCli.JdkSource.current(), null, null, false,
                    output::add);

            assertEquals(List.of("upgrade-check", "--before", "before.json", "--after", "after.json"),
                    recordedArgs(), "failOn=" + failOn);
            assertEquals("UIKA_JDK=unset", output.get(0), "failOn=" + failOn);
        }
    }

    private Path stub() throws IOException {
        var script = Files.writeString(dir.resolve("uika"), "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + dir.resolve("args.txt") + "'\n"
                + "echo \"UIKA_JDK=${UIKA_JDK-unset}\"\n"
                + "echo second line\n"
                + "exit 3\n");
        assertTrue(script.toFile().setExecutable(true, false), "could not mark the stub executable");
        return script;
    }

    private List<String> recordedArgs() throws IOException {
        return Files.readAllLines(dir.resolve("args.txt"));
    }
}
