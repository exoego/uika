package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real JVM with the exact argument the plugins inject into test JVMs, so drift
 * between the composed StartFlightRecording flag and what a JVM accepts fails here
 * instead of silently in a user's test run. Asserts the whole producer chain: the flag is
 * accepted, JFR generates a pid-unique recording in the directory on exit, and the
 * recording converts into class-load lines.
 */
final class UikaCliJfrArgTest {
    @TempDir
    Path dir;

    @Test
    void composedArgMakesARealJvmDumpAConvertibleRecording() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String arg = UikaCli.jfrClassLoadJvmArg(dir);

        Process process = new ProcessBuilder(java.toString(), arg, "-version")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "java rejected " + arg + ":\n" + output);

        Path recording;
        try (Stream<Path> files = Files.list(dir)) {
            recording = files
                    .filter(JfrEvidence::isRecording)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no recording was dumped into " + dir + ":\n" + output));
        }
        Path converted = dir.resolve("converted.log");
        long events = JfrEvidence.convert(recording, converted);
        assertTrue(events > 0, "the recording carried no jdk.ClassLoad events");
        // Not java.lang.Object: the earliest bootstrap classes load before the recorder
        // engine is up, so a recording only carries later loads (JFR internals at least).
        String text = Files.readString(converted);
        assertTrue(text.contains("Java stack when loading ") || text.contains("[class,load] "),
                () -> "converted recording carried no class-load lines:\n" + text);
    }

    /// Comma is the StartFlightRecording option delimiter, and an UNQUOTED comma in the
    /// path does not fail the JVM: it truncates filename= at the comma and records to
    /// the truncated path, so the evidence directory stays empty with exit 0 — the
    /// silent-empty-artifact failure mode. The composer quotes the value for exactly
    /// this case; a real JVM run pins that the quoted form still lands the recording in
    /// the intended directory.
    @Test
    void composedArgSurvivesACommaInTheDirectory() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path commaDir = Files.createDirectories(dir.resolve("comma,dir"));
        String arg = UikaCli.jfrClassLoadJvmArg(commaDir);
        assertTrue(arg.endsWith("\""), () -> "expected a quoted filename value in " + arg);

        Process process = new ProcessBuilder(java.toString(), arg, "-version")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "java rejected " + arg + ":\n" + output);

        try (Stream<Path> files = Files.list(commaDir)) {
            assertTrue(files.anyMatch(JfrEvidence::isRecording),
                    () -> "no recording landed in " + commaDir + ":\n" + output);
        }
    }
}
