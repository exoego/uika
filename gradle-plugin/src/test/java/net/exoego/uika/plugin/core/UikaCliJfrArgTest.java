package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        var windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String arg = UikaCli.jfrClassLoadJvmArg(dir);

        var process = new ProcessBuilder(java.toString(), arg, "-version")
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "java rejected " + arg + ":\n" + output);

        Path recording;
        try (var files = Files.list(dir)) {
            recording = files
                    .filter(JfrEvidence::isRecording)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no recording was dumped into " + dir + ":\n" + output));
        }
        var converted = dir.resolve("converted.log");
        var events = JfrEvidence.convert(recording, converted);
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
        var windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path commaDir = Files.createDirectories(dir.resolve("comma,dir"));
        String arg = UikaCli.jfrClassLoadJvmArg(commaDir);
        assertTrue(arg.endsWith("\""), () -> "expected a quoted filename value in " + arg);

        var process = new ProcessBuilder(java.toString(), arg, "-version")
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "java rejected " + arg + ":\n" + output);

        try (var files = Files.list(commaDir)) {
            assertTrue(files.anyMatch(JfrEvidence::isRecording),
                    () -> "no recording landed in " + commaDir + ":\n" + output);
        }
    }

    /// The hand-written copies of the flag (Maven cannot inject into surefire, the two
    /// Clojure frontends have nothing to inject into, and the README shows the canonical
    /// form) are kept in sync with the composer by hand. This pins them: each file must
    /// carry the composed flag verbatim up to the filename value, so a change to the
    /// event settings or their order fails here instead of drifting silently.
    @Test
    void handWrittenRecipesCarryTheComposedFlag() throws Exception {
        var composed = UikaCli.jfrClassLoadJvmArg(Path.of("VALUE"));
        var prefix = composed.substring(0, composed.indexOf("VALUE"));
        for (var relative : java.util.List.of(
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
