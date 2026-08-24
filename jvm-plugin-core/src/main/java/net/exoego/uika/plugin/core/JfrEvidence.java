package net.exoego.uika.plugin.core;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Converts JFR recordings into the class-load log text the uika CLI already parses. The
 * conversion lives in the plugins, not the CLI, on purpose: the CLI is JVM-free and must
 * never read binary JFR, while every build tool runs on a full JDK whose
 * {@code jdk.jfr.consumer.RecordingFile} reads recordings event by event. Emitting the
 * CLI's own trusted text shapes (a {@code [class,load]} line per stackless event, a
 * {@code Java stack when loading X:} block per stacked one) reuses the CLI's whole
 * evidence pipeline unchanged, including the {@code via ... from ...} trigger
 * composition — which a {@code jdk.ClassLoad} stack feeds from JDK 11, eleven releases
 * before the {@code -Xlog:class+load+cause} flags exist.
 */
public final class JfrEvidence {
    private JfrEvidence() {}

    /**
     * The leaf name of the directory conversions are written into, below each build
     * tool's own build/target space. Shared so the four plugins cannot drift apart on
     * where converted evidence lands.
     */
    public static final String WORK_DIR_NAME = "jfr-class-load";

    /** The four bytes every JFR chunk (and therefore every recording) starts with. */
    private static final byte[] FLR_MAGIC = {'F', 'L', 'R', 0};

    /**
     * Whether an existing path is a JFR recording the class-load knob should convert.
     * The {@code .jfr} suffix decides without I/O, but it is not required: {@code jcmd
     * JFR.dump filename=...} and {@code -XX:StartFlightRecording:filename=<file>} write
     * whatever name they were given verbatim, so suffixless files are sniffed by the
     * {@code FLR\0} magic instead of being forwarded to the CLI as text it would
     * silently skip.
     */
    public static boolean isRecording(Path path) {
        // getFileName() is null for filesystem roots — a degenerate but reachable knob
        // value that must not turn into a bare NPE.
        Path name = path.getFileName();
        if (name == null || !Files.isRegularFile(path)) {
            return false;
        }
        return name.toString().endsWith(".jfr") || startsWithFlrMagic(path);
    }

    /**
     * Whether a knob VALUE names a recording (consumption-only) rather than a recording
     * directory. Shared by the Gradle, sbt and Mill plugins so their injection-skip
     * decisions cannot drift. Unlike {@link #isRecording} this classifies intent, not content: a
     * path that does not exist yet keeps the suffix's meaning (it cannot be sniffed, and
     * the check may create or download it later), while a DIRECTORY named {@code x.jfr}
     * is still a directory and keeps Test-JVM injection.
     */
    public static boolean valueNamesRecording(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().endsWith(".jfr") && !Files.isDirectory(path);
    }

    private static boolean startsWithFlrMagic(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return Arrays.equals(in.readNBytes(FLR_MAGIC.length), FLR_MAGIC);
        } catch (IOException e) {
            // Unreadable is not a recording; if it matters, the CLI reports the path.
            return false;
        }
    }

    /**
     * Convert one recording's {@code jdk.ClassLoad} events; returns how many the
     * recording carried. Zero is the number to watch: the event is disabled in the
     * default JFC profile, and promote-only evidence gives an empty conversion no
     * downstream symptom, so callers surface the count to the user. Duplicate loads of
     * one class collapse to what the CLI would keep anyway — see {@link #convert(Path,
     * Path, Map)}.
     */
    public static long convert(Path recording, Path output) throws IOException {
        return convert(recording, output, new HashMap<>());
    }

    /**
     * The CLI keeps one record per distinct class name — a bare line never replaces an
     * existing record, and the first stack block with at least one frame line wins — so
     * emitting more is pure waste: every test fork re-records the same JDK and framework
     * load set, and converting all of it verbatim writes hundreds of MB the CLI parses
     * and drops. {@code emitted} (class name → whether a framed block was written)
     * mirrors those semantics exactly: the first occurrence per class is kept, a framed
     * block is still written after a bare line (the CLI upgrades frameless records), and
     * everything else is skipped. Threading one map across a batch dedups across
     * recordings with byte-identical CLI effect, because a skipped record is always
     * preceded by an emitted record that already claimed the class.
     */
    private static long convert(Path recording, Path output, Map<String, Boolean> emitted)
            throws IOException {
        long events = 0;
        try (RecordingFile file = new RecordingFile(recording);
                BufferedWriter out = Files.newBufferedWriter(output)) {
            List<String> frames = new ArrayList<>();
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!"jdk.ClassLoad".equals(event.getEventType().getName())) {
                    continue;
                }
                RecordedClass loaded = event.getValue("loadedClass");
                if (loaded == null || loaded.getName().startsWith("[")) {
                    // An array class never matches a violation's referencing class.
                    continue;
                }
                events++;
                String name = loaded.getName();
                frames.clear();
                RecordedStackTrace stack = event.getStackTrace();
                if (stack != null) {
                    // Unlike -Xlog:class+load+cause stacks, jdk.ClassLoad stacks start
                    // at the loading call site (no ClassLoader.defineClass machinery on
                    // top), so the CLI's trigger heuristics have less to skip, not more.
                    for (RecordedFrame frame : stack.getFrames()) {
                        if (!frame.isJavaFrame()) {
                            continue;
                        }
                        RecordedMethod method = frame.getMethod();
                        frames.add("\tat " + method.getType().getName() + "."
                                + method.getName() + "(line " + frame.getLineNumber()
                                + ")");
                    }
                }
                if (frames.isEmpty()) {
                    // Also the shape for a stack whose frames were all non-Java: a
                    // header with no frames would not claim the CLI's stacked slot, so
                    // the bare line keeps a later framed block eligible. The tags
                    // decorator makes the line trusted, so a default-package class
                    // survives the CLI's single-segment rule.
                    if (!emitted.containsKey(name)) {
                        out.write("[class,load] " + name);
                        out.newLine();
                        emitted.put(name, Boolean.FALSE);
                    }
                    continue;
                }
                if (Boolean.TRUE.equals(emitted.get(name))) {
                    continue;
                }
                out.write("Java stack when loading " + name + ":");
                out.newLine();
                for (String frame : frames) {
                    out.write(frame);
                    out.newLine();
                }
                emitted.put(name, Boolean.TRUE);
            }
        }
        return events;
    }

    /**
     * Rewrite the class-load log entries a build tool passes to the CLI: a recording
     * entry is converted into {@code workDir} and replaced by the converted text, and a
     * directory entry is kept as-is (its plain logs still matter) with conversions of any
     * recording found under it appended. Directories are walked following symlinks, the
     * same way the CLI reads the kept directory, so a linked artifact contributes its
     * recordings and not only its text logs. The CLI skips a binary {@code .jfr} left
     * inside a kept directory by its name, so leaving it there is harmless. Stale
     * {@code jfr-*.log} conversions from earlier runs are deleted from {@code workDir}
     * first (recording names are pid-unique, so orphans would otherwise accumulate and
     * be re-read as evidence whenever the knob directory contains the workdir).
     * Conversion order is sorted for determinism; every conversion is reported through
     * {@code log} with its event count, because a recording made with the default JFC
     * profile contains no {@code jdk.ClassLoad} events and would otherwise be silent
     * emptiness. A truncated or unreadable recording is reported and skipped, never
     * fatal: a fork killed mid-dump must not cost the evidence of every intact
     * recording, matching the CLI's own leniency about damaged text logs.
     */
    public static List<Path> rewrite(List<Path> classLoadLogs, Path workDir, Consumer<String> log)
            throws IOException {
        List<Path> rewritten = new ArrayList<>();
        List<Path> recordings = new ArrayList<>();
        for (Path entry : classLoadLogs) {
            if (isRecording(entry)) {
                recordings.add(entry);
                continue;
            }
            rewritten.add(entry);
            if (Files.isDirectory(entry)) {
                try (Stream<Path> walk = Files.walk(entry, FileVisitOption.FOLLOW_LINKS)) {
                    walk.filter(JfrEvidence::isRecording).sorted().forEach(recordings::add);
                } catch (UncheckedIOException e) {
                    // Files.walk wraps iteration failures (a symlink loop, an unreadable
                    // subdirectory); unwrap so callers catching IOException see it.
                    throw e.getCause();
                }
            }
        }
        if (Files.isDirectory(workDir)) {
            try (Stream<Path> stale = Files.list(workDir)) {
                for (Path old : stale.filter(JfrEvidence::isConversion).toList()) {
                    Files.deleteIfExists(old);
                }
            }
        }
        if (!recordings.isEmpty()) {
            Files.createDirectories(workDir);
        }
        int n = 0;
        Map<String, Boolean> emitted = new HashMap<>();
        for (Path recording : recordings) {
            String name = recording.getFileName().toString();
            String base = name.endsWith(".jfr")
                    ? name.substring(0, name.length() - ".jfr".length())
                    : name;
            Path output = workDir.resolve("jfr-" + ++n + "-" + base + ".log");
            long events;
            try {
                events = convert(recording, output, emitted);
            } catch (IOException e) {
                boolean partial = Files.isRegularFile(output);
                if (partial) {
                    rewritten.add(output);
                }
                log.accept("uika: " + recording
                        + " is truncated or not a readable JFR recording (" + e + "); "
                        + (partial ? "keeping the events converted before the error"
                                : "skipping it"));
                continue;
            }
            rewritten.add(output);
            log.accept("uika: converted " + recording + " (" + events
                    + " jdk.ClassLoad events)"
                    + (events == 0
                            ? " — the event is disabled in the default JFC profile;"
                                    + " record with jdk.ClassLoad#enabled=true"
                            : ""));
        }
        return rewritten;
    }

    /** A file this class wrote into the workdir on some earlier run. */
    private static boolean isConversion(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("jfr-") && name.endsWith(".log") && Files.isRegularFile(path);
    }
}
