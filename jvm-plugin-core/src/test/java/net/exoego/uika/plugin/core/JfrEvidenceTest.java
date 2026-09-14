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
        var jfr = dir.resolve("rec.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, jfr, "UikaJfrProbeCore");
        var out = dir.resolve("rec.log");
        var events = JfrEvidence.convert(jfr, out);
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
        var direct = dir.resolve("direct.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, direct, "UikaJfrProbeRewrite");
        Path logsDir = Files.createDirectories(dir.resolve("load-logs"));
        Files.copy(direct, logsDir.resolve("nested.jfr"));
        Files.writeString(logsDir.resolve("plain.log"), "com.example.A\n");

        var work = dir.resolve("work");
        var rewritten = JfrEvidence.rewrite(List.of(direct, logsDir), work, line -> {});

        assertFalse(rewritten.contains(direct), "the raw recording must not reach the CLI");
        assertTrue(rewritten.contains(logsDir), "the directory (its plain logs) must stay");
        List<Path> converted = rewritten.stream().filter(p -> p.startsWith(work)).toList();
        assertEquals(2, converted.size(),
                () -> "expected the direct and the nested recording converted: " + rewritten);
        // The nested recording is a byte-copy of the direct one, so batch dedup must
        // emit the probe's block exactly once: the CLI keeps only the first framed
        // block per class, and re-emitting it per fork recording is what made
        // conversions of large suites hundreds of MB of parsed-and-dropped text.
        var withBlock = converted.stream().filter(p -> {
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
        var suffixless = logsDir.resolve("prod-dump");
        JfrTestRecordings.recordFreshClassLoad(dir, suffixless, "UikaJfrProbeMagic");

        var work = dir.resolve("work");
        var rewritten = JfrEvidence.rewrite(List.of(logsDir), work, line -> {});

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
        var good = logsDir.resolve("good.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, good, "UikaJfrProbeIntact");
        byte[] whole = Files.readAllBytes(good);
        // Truncated mid-chunk: valid magic, unreadable body — the SIGKILL shape.
        Files.write(logsDir.resolve("truncated.jfr"),
                java.util.Arrays.copyOf(whole, whole.length / 2));

        var work = dir.resolve("work");
        var logged = new java.util.ArrayList<String>();
        var rewritten = JfrEvidence.rewrite(List.of(logsDir), work, logged::add);

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
        Path prefixed = Files.writeString(work.resolve("jfr-notes.txt"), "keep me\n");

        var rewritten = JfrEvidence.rewrite(List.of(), work, line -> {});

        assertTrue(rewritten.isEmpty(), () -> "nothing to rewrite, got: " + rewritten);
        assertFalse(Files.exists(stale), "the stale conversion must be deleted");
        assertTrue(Files.exists(foreign), "files rewrite did not write must survive");
        assertTrue(Files.exists(prefixed), "the jfr- prefix alone must not mark a conversion");
    }

    /// A custom JFC can switch jdk.ClassLoad stack traces off. Such a recording still shows
    /// which classes loaded.
    @Test
    void aStacklessRecordingConvertsIntoBareLoadLines() throws Exception {
        var jfr = dir.resolve("stackless.jfr");
        JfrTestRecordings.recordStacklessClassLoad(dir, jfr, "UikaJfrProbeStackless");
        var out = dir.resolve("stackless.log");
        JfrEvidence.convert(jfr, out);

        String text = Files.readString(out);
        assertTrue(text.lines().anyMatch("[class,load] UikaJfrProbeStackless"::equals),
                () -> "bare load line missing:\n" + head(text));
        assertFalse(text.contains("Java stack when loading "),
                () -> "a stackless recording produced a stack block:\n" + head(text));
    }

    /// The CLI upgrades a bare record when a framed block for the same class comes later,
    /// and drops a bare line once a framed block exists. Batch dedup must keep exactly
    /// that, or a fork recorded without stacks would cost a later fork its trigger.
    @Test
    void aFramedBlockStillFollowsABareLineButNotTheReverse() throws Exception {
        var bare = dir.resolve("bare.jfr");
        JfrTestRecordings.recordStacklessClassLoad(dir, bare, "UikaJfrProbeUpgrade");
        var framed = dir.resolve("framed.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, framed, "UikaJfrProbeUpgrade");
        var bareAgain = Files.copy(bare, dir.resolve("bare-again.jfr"));

        var rewritten = JfrEvidence.rewrite(
                List.of(bare, framed, bareAgain), dir.resolve("work"), line -> {});

        assertEquals(3, rewritten.size(),
                () -> "expected one conversion per recording: " + rewritten);
        assertTrue(Files.readString(rewritten.get(0))
                        .contains("[class,load] UikaJfrProbeUpgrade"),
                "the first bare line must be written");
        assertTrue(Files.readString(rewritten.get(1))
                        .contains("Java stack when loading UikaJfrProbeUpgrade:"),
                "a framed block must still follow the bare line");
        assertFalse(Files.readString(rewritten.get(2)).contains("UikaJfrProbeUpgrade"),
                "a bare line after the framed block must be deduped");
    }

    /// The JDK's default profile leaves jdk.ClassLoad off, so such a recording converts to
    /// nothing, and the log line is the only symptom.
    @Test
    void aRecordingWithoutClassLoadEventsSaysHowToRecordThem() throws Exception {
        var jfr = dir.resolve("default.jfr");
        JfrTestRecordings.recordWithDefaultSettings(jfr);
        var logged = new java.util.ArrayList<String>();

        JfrEvidence.rewrite(List.of(jfr), dir.resolve("work"), logged::add);

        assertEquals(1, logged.size(), () -> "expected one line: " + logged);
        assertTrue(logged.get(0).contains("(0 jdk.ClassLoad events)")
                        && logged.get(0).contains("jdk.ClassLoad#enabled=true"),
                () -> "the empty conversion must say how to record the event: " + logged);
    }

    /// A long recording spans several chunks, so a dump or a download cut short loses only
    /// the last one. Recordings concatenate into one multi-chunk recording, which is how
    /// this builds that shape.
    @Test
    void aTruncatedLastChunkKeepsTheEventsOfTheIntactChunks() throws Exception {
        var intact = dir.resolve("intact.jfr");
        // RecordingFile reads one event ahead, so the event that ends the intact chunk is
        // lost with the failing chunk. The tail load keeps the asserted one from being it.
        JfrTestRecordings.recordFreshClassLoads(
                dir, intact, "UikaJfrProbeKept", "UikaJfrProbeTail");
        var last = dir.resolve("last.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, last, "UikaJfrProbeCutOff");
        byte[] lastChunk = Files.readAllBytes(last);
        Path logsDir = Files.createDirectories(dir.resolve("load-logs"));
        var rotated = Files.write(logsDir.resolve("rotated.jfr"), Files.readAllBytes(intact));
        Files.write(rotated, java.util.Arrays.copyOf(lastChunk, lastChunk.length / 2),
                java.nio.file.StandardOpenOption.APPEND);

        var logged = new java.util.ArrayList<String>();
        var rewritten = JfrEvidence.rewrite(List.of(rotated), dir.resolve("work"), logged::add);

        assertEquals(1, rewritten.size(),
                () -> "the partial conversion must reach the CLI: " + rewritten);
        String text = Files.readString(rewritten.get(0));
        assertTrue(text.contains("Java stack when loading UikaJfrProbeKept:"),
                () -> "the intact chunk's events were lost:\n" + head(text));
        assertFalse(text.contains("UikaJfrProbeCutOff"),
                () -> "the truncated chunk cannot have converted:\n" + head(text));
        assertTrue(logged.stream().anyMatch(l -> l.contains("rotated.jfr")
                        && l.contains("keeping the events converted before the error")),
                () -> "the partial conversion must be reported: " + logged);
    }

    /// Callers declare IOException, so a directory that cannot be walked to the end must
    /// fail as one, not as the UncheckedIOException Files.walk throws.
    @Test
    void aSymlinkLoopFailsAsAnIOException() throws Exception {
        Path logsDir = Files.createDirectories(dir.resolve("load-logs"));
        try {
            Files.createSymbolicLink(logsDir.resolve("self"), logsDir);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unsupported: " + e);
        }

        org.junit.jupiter.api.Assertions.assertThrows(java.nio.file.FileSystemLoopException.class,
                () -> JfrEvidence.rewrite(List.of(logsDir), dir.resolve("work"), line -> {}));
    }

    @Test
    void aKnobValueNamesARecordingByItsSuffixUnlessItIsADirectory() throws Exception {
        // The check may create or download the recording after the value is read.
        assertTrue(JfrEvidence.valueNamesRecording(dir.resolve("baseline.jfr")));
        assertFalse(JfrEvidence.valueNamesRecording(
                Files.createDirectories(dir.resolve("recordings.jfr"))));
        assertFalse(JfrEvidence.valueNamesRecording(dir.resolve("recordings")));
        // A filesystem root has no file name.
        assertFalse(JfrEvidence.valueNamesRecording(dir.getRoot()));
        assertFalse(JfrEvidence.isRecording(dir.getRoot()));
    }

    /// The CLI follows symlinks when it reads the kept directory, so the conversion
    /// walk must too: a linked artifact whose text logs are read but whose recordings
    /// are silently unconverted would lose evidence with no symptom.
    @Test
    void recordingsBehindASymlinkedSubdirectoryAreConverted() throws Exception {
        Path real = Files.createDirectories(dir.resolve("real"));
        var rec = real.resolve("rec.jfr");
        JfrTestRecordings.recordFreshClassLoad(dir, rec, "UikaJfrProbeLinked");
        Path logsDir = Files.createDirectories(dir.resolve("load-logs"));
        try {
            Files.createSymbolicLink(logsDir.resolve("run"), real);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // No symlink support (Windows without privilege); nothing to pin here.
            org.junit.jupiter.api.Assumptions.abort("symlinks unsupported: " + e);
        }

        var work = dir.resolve("work");
        var rewritten = JfrEvidence.rewrite(List.of(logsDir), work, line -> {});

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
        var jfr = dir.resolve("deep.jfr");
        JfrTestRecordings.recordFreshClassLoadAtDepth(dir, jfr, "UikaJfrProbeDeep", 100);
        var out = dir.resolve("deep.log");
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
        var header = "Java stack when loading " + probe + ":";
        var start = text.indexOf(header);
        assertTrue(start >= 0, () -> "no stack block for " + probe + ":\n" + head(text));
        var block = new StringBuilder();
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
