package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Converts REAL recordings made in this JVM, never synthetic fixtures: the converter
 * reads live RecordedEvent structures, and real event shapes are where format assumptions
 * die (the -Xlog parser learned that twice). See {@link JfrTestRecordings} for why the
 * probe class is compiled at runtime.
 */
final class JfrEvidenceTest {
    @TempDir
    Path dir;

    @Test
    void convertsClassLoadEventsIntoTheCliTextShapes() throws Exception {
        Path jfr = dir.resolve("rec.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, jfr, "UikaJfrProbeCore");
        Path out = dir.resolve("rec.log");
        long events = JfrEvidence.convert(jfr, out);
        assertTrue(events > 0, "no jdk.ClassLoad events were converted");

        String text = Files.readString(out);
        assertTrue(text.contains("Java stack when loading UikaJfrProbeCore:"),
                () -> "probe load missing or stackless:\n" + head(text));
        // The frames feed the CLI's trigger composition: the reflective API and the real
        // caller must both be present, in the CLI's `at ` frame shape.
        assertTrue(text.lines().anyMatch(l -> l.startsWith("\tat java.lang.Class.forName")),
                () -> "reflective frame missing:\n" + head(text));
        assertTrue(text.lines().anyMatch(l -> l.contains("JfrTestRecordings.recordFreshClassLoad")),
                () -> "caller frame missing:\n" + head(text));
    }

    @Test
    void rewriteConvertsRecordingsGivenDirectlyOrInsideADirectory() throws Exception {
        Path direct = dir.resolve("direct.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, direct, "UikaJfrProbeRewrite");
        Path logsDir = Files.createDirectories(dir.resolve("load-logs"));
        Files.copy(direct, logsDir.resolve("nested.jfr"));
        Files.writeString(logsDir.resolve("plain.log"), "com.example.A\n");

        Path work = dir.resolve("work");
        List<Path> rewritten = JfrEvidence.rewrite(List.of(direct, logsDir), work, line -> {});

        assertFalse(rewritten.contains(direct), "the raw recording must not reach the CLI");
        assertTrue(rewritten.contains(logsDir), "the directory (its plain logs) must stay");
        List<Path> converted = rewritten.stream().filter(p -> p.startsWith(work)).toList();
        assertEquals(2, converted.size(),
                () -> "expected the direct and the nested recording converted: " + rewritten);
        // The nested recording is a byte-copy of the direct one, so batch dedup must
        // emit the probe's block exactly once: the CLI keeps only the first framed
        // block per class, and re-emitting it per fork recording is what made
        // conversions of large suites hundreds of MB of parsed-and-dropped text.
        long withBlock = converted.stream().filter(p -> {
            try {
                return Files.readString(p).contains("Java stack when loading UikaJfrProbeRewrite:");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).count();
        assertEquals(1, withBlock,
                () -> "expected the probe block once across the batch (first wins, "
                        + "duplicates deduped): " + rewritten);
    }

    /// jcmd JFR.dump and a file-valued filename= write whatever name they were given —
    /// no .jfr appended — and forwarding such a recording as "text" would lose all its
    /// evidence without a symptom (the CLI skips binary silently, promote-only hides the
    /// absence). Detection therefore sniffs the FLR\0 magic, not the name alone.
    @Test
    void convertsASuffixlessRecordingByItsMagicBytes() throws Exception {
        Path logsDir = Files.createDirectories(dir.resolve("load-logs"));
        Path suffixless = logsDir.resolve("prod-dump");
        JfrTestRecordings.recordFreshClassLoad(dir, suffixless, "UikaJfrProbeMagic");

        Path work = dir.resolve("work");
        List<Path> rewritten = JfrEvidence.rewrite(List.of(logsDir), work, line -> {});

        List<Path> converted = rewritten.stream().filter(p -> p.startsWith(work)).toList();
        assertEquals(1, converted.size(),
                () -> "expected the suffixless recording converted: " + rewritten);
        assertTrue(Files.readString(converted.get(0))
                        .contains("Java stack when loading UikaJfrProbeMagic:"),
                "conversion lost the probe load");
    }

    /// A fork killed during its exit dump leaves a truncated recording; an artifact
    /// download can truncate one too. That single damaged file must cost only its own
    /// evidence, never abort the check or the conversion of the intact recordings —
    /// the same leniency the CLI applies to damaged text logs.
    @Test
    void aTruncatedRecordingIsSkippedNotFatal() throws Exception {
        Path logsDir = Files.createDirectories(dir.resolve("load-logs"));
        Path good = logsDir.resolve("good.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, good, "UikaJfrProbeIntact");
        byte[] whole = Files.readAllBytes(good);
        // Truncated mid-chunk: valid magic, unreadable body — the SIGKILL shape.
        Files.write(logsDir.resolve("truncated.jfr"),
                java.util.Arrays.copyOf(whole, whole.length / 2));

        Path work = dir.resolve("work");
        List<String> logged = new java.util.ArrayList<>();
        List<Path> rewritten = JfrEvidence.rewrite(List.of(logsDir), work, logged::add);

        List<Path> converted = rewritten.stream()
                .filter(p -> p.startsWith(work))
                .filter(p -> {
                    try {
                        return Files.readString(p)
                                .contains("Java stack when loading UikaJfrProbeIntact:");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .toList();
        assertEquals(1, converted.size(),
                () -> "the intact recording must still convert: " + rewritten);
        // Both the name and the failure text: the success note also contains the path, so
        // the name alone would pass even if the damaged file converted without an error.
        assertTrue(logged.stream().anyMatch(l -> l.contains("truncated.jfr")
                        && l.contains("not a readable JFR recording")),
                () -> "the damaged recording must be reported by name: " + logged);
    }

    /// Recording names are pid-unique, so every collection run orphans the previous
    /// run's conversions; if the knob directory ever contains the workdir, the CLI
    /// would re-read those orphans as fresh evidence. rewrite deletes its own
    /// jfr-*.log files up front — and only those, so recordings sitting in the
    /// workdir itself survive.
    @Test
    void staleConversionsAreDeletedFromTheWorkDir() throws Exception {
        Path work = Files.createDirectories(dir.resolve("work"));
        Path stale = Files.writeString(work.resolve("jfr-9-gone.log"), "com.example.Stale\n");
        Path foreign = Files.writeString(work.resolve("notes.txt"), "keep me\n");

        List<Path> rewritten = JfrEvidence.rewrite(List.of(), work, line -> {});

        assertTrue(rewritten.isEmpty(), () -> "nothing to rewrite, got: " + rewritten);
        assertFalse(Files.exists(stale), "the stale conversion must be deleted");
        assertTrue(Files.exists(foreign), "files rewrite did not write must survive");
    }

    /// The CLI follows symlinks when it reads the kept directory, so the conversion
    /// walk must too: a linked artifact whose text logs are read but whose recordings
    /// are silently unconverted would lose evidence with no symptom.
    @Test
    void recordingsBehindASymlinkedSubdirectoryAreConverted() throws Exception {
        Path real = Files.createDirectories(dir.resolve("real"));
        Path rec = real.resolve("rec.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, rec, "UikaJfrProbeLinked");
        Path logsDir = Files.createDirectories(dir.resolve("load-logs"));
        try {
            Files.createSymbolicLink(logsDir.resolve("run"), real);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // No symlink support (Windows without privilege); nothing to pin here.
            org.junit.jupiter.api.Assumptions.abort("symlinks unsupported: " + e);
        }

        Path work = dir.resolve("work");
        List<Path> rewritten = JfrEvidence.rewrite(List.of(logsDir), work, line -> {});

        List<Path> converted = rewritten.stream().filter(p -> p.startsWith(work)).toList();
        assertEquals(1, converted.size(),
                () -> "expected the linked recording converted: " + rewritten);
        assertTrue(Files.readString(converted.get(0))
                        .contains("Java stack when loading UikaJfrProbeLinked:"),
                "conversion lost the probe load behind the symlink");
    }

    /// JFR's stack depth cap (default 64) truncates the OUTER end of the stack: with 100
    /// caller frames under the load, the frames the trigger composition reads — the
    /// reflective API and its immediate caller — survive at the inner end, and what falls
    /// off is the harness side this feature never reads. If truncation ever flipped to
    /// the inner end, the trigger would silently vanish on every deep-stack app, which is
    /// exactly the kind of drift promote-only evidence cannot surface on its own.
    @Test
    void deepCallerStacksKeepTheTriggerFrames() throws Exception {
        Path jfr = dir.resolve("deep.jfr");
        JfrTestRecordings.recordFreshClassLoadAtDepth(dir, jfr, "UikaJfrProbeDeep", 100);
        Path out = dir.resolve("deep.log");
        JfrEvidence.convert(jfr, out);

        String block = probeBlock(Files.readString(out), "UikaJfrProbeDeep");
        assertTrue(block.lines().anyMatch(l -> l.startsWith("\tat java.lang.Class.forName")),
                () -> "reflective frame missing from the deep stack:\n" + block);
        assertTrue(block.contains("JfrTestRecordings.descend"),
                () -> "immediate caller missing from the deep stack:\n" + block);
        // The 100 recursion frames push everything below them past the cap, so the outer
        // frames (this test, the recording helper's entry) must be the truncated side.
        assertFalse(block.contains("recordFreshClassLoadAtDepth"),
                () -> "expected outer frames truncated, not inner:\n" + block);
    }

    /// The probe's stack block: from its header to the first non-frame line. Split on
    /// any line terminator: the converter writes via newLine(), which is \r\n on
    /// Windows, and a plain "\n" split would leave a trailing \r that fails both
    /// guards on the very first line.
    private static String probeBlock(String text, String probe) {
        String header = "Java stack when loading " + probe + ":";
        int start = text.indexOf(header);
        assertTrue(start >= 0, () -> "no stack block for " + probe + ":\n" + head(text));
        StringBuilder block = new StringBuilder();
        for (String line : text.substring(start).split("\\R")) {
            if (!line.equals(header) && !line.startsWith("\tat ")) {
                break;
            }
            block.append(line).append('\n');
        }
        return block.toString();
    }

    private static String head(String text) {
        return text.lines().limit(15).reduce("", (a, b) -> a + b + "\n");
    }
}
