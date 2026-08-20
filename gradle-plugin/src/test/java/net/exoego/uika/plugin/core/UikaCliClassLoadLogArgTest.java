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
 * between the composed {@code -Xlog} flag and what a JVM accepts fails here instead of
 * silently in a user's test run (an unknown -Xlog option aborts JVM startup, but a
 * mis-substituted file name would just scatter logs). The CLI side owns parsing the
 * produced format; this asserts the flag is accepted, {@code %p} is substituted into a
 * per-process file, and class+load lines land in it.
 */
final class UikaCliClassLoadLogArgTest {
    @TempDir
    Path dir;

    @Test
    void composedArgMakesARealJvmWriteAPerProcessLog() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String arg = UikaCli.classLoadLogJvmArg(dir, "probe");

        Process process = new ProcessBuilder(java.toString(), arg, "-version")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "java rejected " + arg + ":\n" + output);

        try (Stream<Path> files = Files.list(dir)) {
            Path log = files
                    .filter(f -> {
                        String name = f.getFileName().toString();
                        return name.startsWith("probe-") && name.endsWith(".log")
                                && !name.contains("%p");
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "%p was not substituted into a per-process log file in " + dir));
            String content = Files.readString(log);
            assertTrue(content.contains("[class,load"),
                    "no class+load lines in the emitted log:\n" + firstLines(content));
            assertTrue(content.contains("java.lang.Object"),
                    "expected a java.lang.Object load line in:\n" + firstLines(content));
        }
    }

    private static String firstLines(String content) {
        return content.lines().limit(10).reduce("", (a, b) -> a + b + "\n");
    }
}
