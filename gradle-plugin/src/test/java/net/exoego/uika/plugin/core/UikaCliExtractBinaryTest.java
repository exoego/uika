package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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
        Path zip = dir.resolve("uika-cli.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("uika-1.0.0/" + BINARY_NAME));
            out.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    @Test
    void extractsTheBinaryWithNoTempLeftover() throws Exception {
        Path installDir = dir.resolve("install");
        Path binary = UikaCli.extractBinary(stubZip(), installDir);
        assertTrue(Files.isRegularFile(binary), "no binary at " + binary);
        assertEquals(List.of(BINARY_NAME), fileNames(installDir));
    }

    @Test
    void failedExtractionLeavesNoTempFile() throws Exception {
        Path installDir = dir.resolve("install");
        // The binary's own path occupied by a non-empty DIRECTORY makes the atomic move
        // fail after the temp copy succeeded, which is exactly the orphaning window.
        Files.createDirectories(installDir.resolve(BINARY_NAME).resolve("occupied"));

        Path zip = stubZip();
        assertThrows(IOException.class, () -> UikaCli.extractBinary(zip, installDir));

        // Exact contents, not a suffix filter: a leftover by ANY name must fail, and the
        // planted directory is the only entry a clean failure leaves behind.
        assertEquals(List.of(BINARY_NAME), fileNames(installDir));
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
