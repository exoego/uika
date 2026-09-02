package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `UIKA_CLI_PATH`, the one knob every integration honours, checked once for all of them.
///
/// Its whole job is to fail EARLY and by name. Handed straight to ProcessBuilder, a value
/// that is not a file or has lost its executable bit dies deep inside process start-up with
/// a message that names neither uika nor the variable — and losing the bit is the everyday
/// case, because actions/upload-artifact does not preserve it, which is exactly how a
/// binary reaches a PR gate through the artifact flow the docs recommend.
///
/// In the core test source set because the call sites cannot all reach it: `System.getenv`
/// is unstubbable, sbt's scripted framework has no per-test environment hook, and the
/// Maven invoker's is per project. Those call sites are covered where each build can reach
/// them; the contract they all rely on is covered here, once.
final class BinaryOverrideTest {

    /// A CI `env:` block interpolating an unset input exports the empty string rather than
    /// nothing, so blank has to mean unset. Read through the environment too, since
    /// `binaryOverride()` is what Maven, sbt and the Bazel check binary call.
    @Test
    void anUnsetOrBlankValueIsNoOverride() {
        assertNull(UikaCli.overrideFrom(null));
        assertNull(UikaCli.overrideFrom(""));
        assertNull(UikaCli.overrideFrom("   "));
        if (System.getenv(UikaCli.CLI_PATH_ENV) == null) {
            assertNull(UikaCli.binaryOverride(),
                    "binaryOverride must read " + UikaCli.CLI_PATH_ENV + " through overrideFrom");
        }
    }

    @Test
    void aValueThatIsNotAFileFailsNamingTheVariable(@TempDir Path dir) {
        var missing = dir.resolve("nowhere/uika");
        var boom = assertThrows(IllegalStateException.class,
                () -> UikaCli.overrideFrom(missing.toString()));
        assertTrue(boom.getMessage().contains(UikaCli.CLI_PATH_ENV), boom.getMessage());
        assertTrue(boom.getMessage().contains(missing.toString()), boom.getMessage());

        // A directory is a path that exists and still cannot be run. Pointing the variable
        // at the install directory instead of the binary inside it is the likely slip.
        var onADirectory = assertThrows(IllegalStateException.class,
                () -> UikaCli.overrideFrom(dir.toString()));
        assertTrue(onADirectory.getMessage().contains("does not name a file"),
                onADirectory.getMessage());
    }

    @Test
    void aFileWithoutTheExecutableBitFailsNamingTheVariable(@TempDir Path dir) throws IOException {
        var binary = Files.writeString(dir.resolve("uika"), "#!/bin/sh\nexit 0\n");
        if (binary.toFile().canExecute() && !binary.toFile().setExecutable(false, false)) {
            return; // A filesystem that cannot clear the bit (Windows, some mounts).
        }
        var boom = assertThrows(IllegalStateException.class,
                () -> UikaCli.overrideFrom(binary.toString()));
        assertTrue(boom.getMessage().contains(UikaCli.CLI_PATH_ENV), boom.getMessage());
        assertTrue(boom.getMessage().contains("is not executable"), boom.getMessage());
    }

    @Test
    void anExecutableFileIsReturnedUnchanged(@TempDir Path dir) throws IOException {
        var binary = Files.writeString(dir.resolve("uika"), "#!/bin/sh\nexit 0\n");
        assertTrue(binary.toFile().setExecutable(true, false) || binary.toFile().canExecute());
        assertEquals(binary, UikaCli.overrideFrom(binary.toString()));
    }
}
