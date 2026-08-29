package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A failed extraction must not orphan its temp file. The copy or the move can fail (a
/// truncated entry, a full disk), a retry creates a fresh temp file each time, and
/// nothing ever reaps the leftovers from the per-project install directory. Every JVM
/// plugin compiles this code (the directories are per tool, only the code is shared),
/// and the Clojure port has carried the cleanup from the start.
final class UikaCliExtractBinaryTest {
    @TempDir
    Path dir;

    private static final String BINARY_NAME =
            UikaCli.platformClassifier().startsWith("windows") ? "uika.exe" : "uika";

    private Path stubZip() throws IOException {
        var zip = dir.resolve("uika-cli.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("uika-1.0.0/" + BINARY_NAME));
            out.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    @Test
    void extractsTheBinaryWithNoTempLeftover() throws Exception {
        var installDir = dir.resolve("install");
        Path binary = UikaCli.extractBinary(stubZip(), installDir);
        assertTrue(Files.isRegularFile(binary), "no binary at " + binary);
        assertEquals(List.of(BINARY_NAME), fileNames(installDir));
    }

    @Test
    void failedExtractionLeavesNoTempFile() throws Exception {
        var installDir = dir.resolve("install");
        // The binary's own path occupied by a non-empty DIRECTORY makes the atomic move
        // fail after the temp copy succeeded, which is exactly the orphaning window.
        Files.createDirectories(installDir.resolve(BINARY_NAME).resolve("occupied"));

        var zip = stubZip();
        assertThrows(IOException.class, () -> UikaCli.extractBinary(zip, installDir));

        // Exact contents, not a suffix filter: a leftover by ANY name must fail, and the
        // planted directory is the only entry a clean failure leaves behind.
        assertEquals(List.of(BINARY_NAME), fileNames(installDir));
    }

    @Test
    void lostMoveRaceReusesTheInstalledBinaryAndDropsTheTemp() throws Exception {
        Path installDir = Files.createDirectories(dir.resolve("install"));
        Path binary = Files.writeString(installDir.resolve(BINARY_NAME), "winner");
        // Whether ATOMIC_MOVE replaces an existing target is implementation-specific, so
        // the loser of a concurrent extraction can see its move fail with the winner's
        // binary already installed. A directory as the temp file forces that combination
        // on every platform (renaming a directory over a regular file always fails); the
        // loser must fall back to the winner's binary and still clean up after itself.
        Path tmp = Files.createDirectory(installDir.resolve("uika-race.tmp"));

        UikaCli.moveIntoPlace(tmp, binary);

        assertEquals("winner", Files.readString(binary));
        assertEquals(List.of(BINARY_NAME), fileNames(installDir));
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
