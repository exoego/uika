package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ports the `input.rs` tests. Rust picks the entries to inflate with `representative_offsets`
 * and reads them in batches. The Java port reads a directory once into {@link Jar.Entries},
 * drops duplicates with {@link Dedup} and streams classes to a sink, so the two Rust tests
 * are ported against that shape and the entry reading they rely on is pinned beside them.
 */
class InputTest {
    @TempDir
    Path dir;

    private static byte[] classLike(String text) {
        byte[] tail = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + tail.length];
        out[0] = (byte) 0xCA;
        out[1] = (byte) 0xFE;
        out[2] = (byte) 0xBA;
        out[3] = (byte) 0xBE;
        System.arraycopy(tail, 0, out, 4, tail.length);
        return out;
    }

    /** Deflated entries in the given order, like the Rust `write_jar`. Alternating (name, bytes). */
    private static String writeJar(Path path, Object... entries) throws IOException {
        try (OutputStream file = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        return path.toString();
    }

    private static void writeStored(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static Jar.Entries readEntries(String path) throws IOException {
        try (FileChannel channel = FileChannel.open(Path.of(path), StandardOpenOption.READ)) {
            return Jar.readEntries(channel, Scratch.current());
        }
    }

    private static List<String> names(Jar.Entries entries) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < entries.count; i++) {
            names.add(Intern.str(entries.name[i]));
        }
        return names;
    }

    record Seen(String source, String entry, byte[] bytes) {}

    /** Collects what a sink is handed. A leaf belongs to one thread at a time, so a plain list does. */
    private static final Input.Sink<List<Seen>> COLLECT = new Input.Sink<>() {
        @Override
        public List<Seen> newLeaf() {
            return new ArrayList<>();
        }

        @Override
        public void accept(List<Seen> leaf, Scratch scratch, int source, int entry, ClassSource bytes) {
            bytes.readAll();
            leaf.add(new Seen(Intern.str(source), Intern.str(entry), Arrays.copyOf(bytes.bytes, bytes.available)));
        }
    };

    private static List<Seen> stream(String path, Input.Prepared prepared) {
        List<List<Seen>> leaves = new ArrayList<>();
        Input.onPool(() -> Input.forEachClass(path, prepared, COLLECT, leaves));
        return Input.concat(leaves);
    }

    /** Entry problems are printed as they are met, so the test swaps the stream they go to. */
    private static List<Seen> streamCapturingWarnings(String path, StringBuilder captured) {
        java.io.PrintStream stderr = Out.err;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try {
            Out.err = new java.io.PrintStream(bytes, true, StandardCharsets.UTF_8);
            return stream(path, null);
        } finally {
            Out.err.flush();
            Out.err = stderr;
            captured.append(bytes.toString(StandardCharsets.UTF_8));
        }
    }

    private static List<String> entriesOf(List<Seen> seen) {
        List<String> out = new ArrayList<>();
        for (Seen s : seen) {
            out.add(s.entry());
        }
        return out;
    }

    /**
     * Byte-identical duplicate classes are dropped, but a same-named class with different
     * bytes (different CRC) is kept, and origin order is respected.
     */
    @Test
    void representativeOffsetsDropsByteIdenticalDuplicates() throws Exception {
        byte[] fooV1 = classLike("foo contents v1");
        byte[] fooV2 = classLike("foo contents v2 differs");
        String a = writeJar(dir.resolve("a.jar"), "Foo.class", fooV1, "Bar.class", classLike("bar"));
        // b repeats Foo byte-for-byte (skipped) and adds a new Baz (kept).
        String b = writeJar(dir.resolve("b.jar"), "Foo.class", fooV1, "Baz.class", classLike("baz"));
        // c has Foo with different bytes, so a different CRC. It is kept, not a duplicate.
        String c = writeJar(dir.resolve("c.jar"), "Foo.class", fooV2);

        Dedup dedup = new Dedup();
        List<Input.Prepared> prepared = new ArrayList<>();
        for (String path : List.of(a, b, c)) {
            Input.Prepared p = Input.prepare(path);
            assertNotNull(p.entries, path);
            dedup.apply(p.entries);
            prepared.add(p);
        }

        assertEquals(List.of("Foo", "Bar"), names(prepared.get(0).entries), "a.jar keeps Foo and Bar");
        assertEquals(List.of("Baz"), names(prepared.get(1).entries), "b.jar keeps only Baz; its byte-identical Foo is skipped");
        assertEquals(List.of("Foo"), names(prepared.get(2).entries), "c.jar keeps its differently-compiled Foo");
        assertEquals(1, dedup.skipped, "exactly one entry (b.jar's Foo) is skipped");

        // What survives is what gets inflated, from the right origin.
        List<Seen> fromB = stream(b, prepared.get(1));
        assertEquals(List.of("Baz"), entriesOf(fromB));
        assertEquals(b, fromB.get(0).source());
        assertArrayEquals(classLike("baz"), fromB.get(0).bytes());
        List<Seen> fromC = stream(c, prepared.get(2));
        assertEquals(List.of("Foo"), entriesOf(fromC));
        assertArrayEquals(fooV2, fromC.get(0).bytes());
        assertEquals(List.of("Foo", "Bar"), entriesOf(stream(a, prepared.get(0))));
    }

    /** A directory target is never deduplicated (reading, not inflating, is the cost there). */
    @Test
    void representativeOffsetsSkipsDirectories() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.write(classes.resolve("Foo.class"), classLike("foo"));
        assertNull(Input.prepare(classes.toString()).entries);
        // Read twice, the same class comes back twice. Nothing remembers a directory's entries.
        assertEquals(List.of("Foo"), entriesOf(stream(classes.toString(), Input.prepare(classes.toString()))));
        assertEquals(List.of("Foo"), entriesOf(stream(classes.toString(), null)));
        // A path that cannot be read at all answers the same way and fails when it is scanned.
        assertNull(Input.prepare(dir.resolve("missing.jar").toString()).entries);
    }

    /** The third CRC of one name goes to the overflow set, and is still exact. */
    @Test
    void dedupKeepsEveryDistinctCrcOfOneName() throws Exception {
        Dedup dedup = new Dedup();
        List<List<String>> kept = new ArrayList<>();
        String[] contents = {"v1", "v2", "v3", "v2", "v3", "v1"};
        for (int i = 0; i < contents.length; i++) {
            Jar.Entries entries = readEntries(writeJar(dir.resolve("crc" + i + ".jar"), "pkg/Same.class", classLike(contents[i])));
            dedup.apply(entries);
            kept.add(names(entries));
        }
        assertEquals(
                List.of(List.of("pkg/Same"), List.of("pkg/Same"), List.of("pkg/Same"), List.of(), List.of(), List.of()), kept);
        assertEquals(3, dedup.skipped);
    }

    @Test
    void dedupDropsARepeatInsideOneJarToo() throws Exception {
        Path path = dir.resolve("twice.jar");
        try (OutputStream file = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            writeStored(zip, "a/One.class", classLike("same"));
            writeStored(zip, "b/Other.class", classLike("same"));
        }
        Jar.Entries entries = readEntries(path.toString());
        Dedup dedup = new Dedup();
        dedup.apply(entries);
        // Same CRC under another name is another class.
        assertEquals(List.of("a/One", "b/Other"), names(entries));
        assertEquals(0, dedup.skipped);
    }

    @Test
    void streamsOnlyScannableClassEntriesInOffsetOrder() throws Exception {
        byte[] big = new byte[300_000];
        new Random(42).nextBytes(big);
        System.arraycopy(classLike(""), 0, big, 0, 4);
        Path path = dir.resolve("mixed.jar");
        try (OutputStream file = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("z/Last.class"));
            zip.write(classLike("last in name order, first in the file"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("module-info.class"));
            zip.write(classLike("module"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("a/module-info.class"));
            zip.write(classLike("nested module"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/versions/11/a/First.class"));
            zip.write(classLike("multi-release"));
            zip.closeEntry();
            writeStored(zip, "a/Stored.class", classLike("stored"));
            zip.putNextEntry(new ZipEntry("a/NotAClass.class"));
            zip.write("no magic".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("a/Tiny.class"));
            zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("a/Big.class"));
            zip.write(big);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("a/readme.txt"));
            zip.write(classLike("not a class name"));
            zip.closeEntry();
        }

        Jar.Entries entries = readEntries(path.toString());
        assertEquals(List.of("z/Last", "a/Stored", "a/NotAClass", "a/Tiny", "a/Big"), names(entries));
        assertTrue(entries.stored[1]);
        assertFalse(entries.stored[0]);

        List<Seen> seen = stream(path.toString(), null);
        assertEquals(List.of("z/Last", "a/Stored", "a/Big"), entriesOf(seen));
        assertArrayEquals(classLike("stored"), seen.get(1).bytes());
        assertArrayEquals(big, seen.get(2).bytes());
        assertEquals(path.toString(), seen.get(0).source());
    }

    /** More entries than one leaf holds come back in entry order, whatever the workers did. */
    @Test
    void leavesComeBackInEntryOrder() throws Exception {
        List<Object> entries = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            entries.add("p/C" + i + ".class");
            entries.add(classLike("class number " + i));
            expected.add("p/C" + i);
        }
        String path = writeJar(dir.resolve("many.jar"), entries.toArray());
        List<Seen> seen = stream(path, null);
        assertEquals(expected, entriesOf(seen));
        for (int i = 0; i < seen.size(); i++) {
            assertArrayEquals(classLike("class number " + i), seen.get(i).bytes());
        }
    }

    /** A corrupt entry is a warning about that entry. The rest of the JAR is still read. */
    @Test
    void aCorruptDeflateStreamSkipsThatEntryOnly() throws Exception {
        byte[] text = classLike("compressible ".repeat(400));
        Path path = dir.resolve("corrupt.jar");
        writeJar(path, "p/Good1.class", text, "p/Bad.class", text, "p/Good2.class", text);
        Jar.Entries entries = readEntries(path.toString());
        byte[] zip = Files.readAllBytes(path);
        int header = (int) entries.offset(1);
        int nameLength = (zip[header + 26] & 0xff) | (zip[header + 27] & 0xff) << 8;
        int extraLength = (zip[header + 28] & 0xff) | (zip[header + 29] & 0xff) << 8;
        // A final block of the reserved type 3, which no decoder can read past.
        zip[header + 30 + nameLength + extraLength] = 0x07;
        Files.write(path, zip);

        StringBuilder captured = new StringBuilder();
        List<Seen> seen = streamCapturingWarnings(path.toString(), captured);
        assertEquals(List.of("p/Good1", "p/Good2"), entriesOf(seen));
        assertArrayEquals(text, seen.get(1).bytes());
        assertEquals("warning: " + path + "!p/Bad.class: deflate error\n", captured.toString());
    }

    /** The central directory is trusted for where an entry starts, and the local header for how long its name is. */
    @Test
    void anEntryWhoseLocalHeaderIsDamagedIsSkippedWithAWarning() throws Exception {
        Path path = dir.resolve("badheader.jar");
        writeJar(path, "p/Good1.class", classLike("one"), "p/Bad.class", classLike("two"), "p/Good2.class", classLike("three"));
        Jar.Entries entries = readEntries(path.toString());
        byte[] zip = Files.readAllBytes(path);
        zip[(int) entries.offset(1)] = 0x51;
        Files.write(path, zip);

        StringBuilder captured = new StringBuilder();
        List<Seen> seen = streamCapturingWarnings(path.toString(), captured);
        assertEquals(List.of("p/Good1", "p/Good2"), entriesOf(seen));
        assertEquals("warning: " + path + "!p/Bad.class: bad local header signature\n", captured.toString());
    }

    @Test
    void streamsADirectoryInNameOrder() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("out"));
        Files.createDirectories(classes.resolve("b/inner"));
        Files.createDirectories(classes.resolve("META-INF/versions/9"));
        Files.write(classes.resolve("b/inner/Deep.class"), classLike("deep"));
        Files.write(classes.resolve("b/Zed.class"), classLike("zed"));
        Files.write(classes.resolve("Top.class"), classLike("top"));
        Files.write(classes.resolve("b/NotAClass.class"), "text".getBytes(StandardCharsets.UTF_8));
        Files.write(classes.resolve("b/notes.txt"), classLike("wrong suffix"));
        Files.write(classes.resolve("module-info.class"), classLike("module"));
        Files.write(classes.resolve("META-INF/versions/9/Top.class"), classLike("multi-release"));

        List<Seen> seen = stream(classes.toString(), null);
        assertEquals(List.of("Top", "b/Zed", "b/inner/Deep"), entriesOf(seen));
        assertEquals(classes.toString(), seen.get(0).source());
        assertArrayEquals(classLike("deep"), seen.get(2).bytes());
    }

    @Test
    void listsClassEntryNamesWithoutInflating() throws Exception {
        String jar = writeJar(
                dir.resolve("names.jar"),
                "x/B.class",
                classLike("b"),
                "module-info.class",
                classLike("m"),
                "META-INF/versions/9/x/B.class",
                classLike("mr"),
                "x/A.class",
                "not even a class".getBytes(StandardCharsets.UTF_8),
                "x/readme.txt",
                classLike("t"));
        assertEquals(List.of("x/B", "x/A"), Input.classEntryNames(jar));

        Path classes = Files.createDirectories(dir.resolve("names/x"));
        Files.write(classes.resolve("B.class"), classLike("b"));
        Files.write(classes.resolve("A.class"), classLike("a"));
        Files.write(classes.resolve("readme.txt"), classLike("t"));
        assertEquals(List.of("x/A", "x/B"), Input.classEntryNames(dir.resolve("names").toString()));

        // Best effort, so an unreadable input lists nothing.
        assertEquals(List.of(), Input.classEntryNames(dir.resolve("absent.jar").toString()));
        Files.write(dir.resolve("garbage.jar"), "not a zip".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), Input.classEntryNames(dir.resolve("garbage.jar").toString()));
    }

    @Test
    void fetchesNamedEntriesAndWarnsAboutTheRest() throws Exception {
        String jar = writeJar(dir.resolve("fetch.jar"), "x/A.class", classLike("a"), "x/B.class", classLike("b"));
        int a = Intern.intern("x/A");
        int b = Intern.intern("x/B");
        int gone = Intern.intern("x/Gone");
        List<Input.Wanted> wanted =
                List.of(new Input.Wanted(b, "x/B.class"), new Input.Wanted(gone, "x/Gone.class"), new Input.Wanted(a, "x/A.class"));

        List<String> got = new ArrayList<>();
        List<String> warnings = Input.fetchEntries(
                jar, wanted, (name, bytes, length) -> got.add(Intern.str(name) + "=" + new String(bytes, 4, length - 4, StandardCharsets.UTF_8)));
        assertEquals(List.of("x/B=b", "x/A=a"), got);
        assertEquals(List.of(jar + "!x/Gone.class: specified file not found in archive"), warnings);

        Path classes = Files.createDirectories(dir.resolve("fetch/x"));
        Files.write(classes.resolve("A.class"), classLike("dir a"));
        String root = dir.resolve("fetch").toString();
        got.clear();
        warnings = Input.fetchEntries(
                root, wanted, (name, bytes, length) -> got.add(Intern.str(name) + "=" + new String(bytes, 4, length - 4, StandardCharsets.UTF_8)));
        assertEquals(List.of("x/A=dir a"), got);
        assertEquals(
                List.of(
                        root + "!x/B.class: No such file or directory (os error 2)",
                        root + "!x/Gone.class: No such file or directory (os error 2)"),
                warnings);
    }

    @Test
    void anUnreadableJarEndsTheCommand() throws Exception {
        String missing = dir.resolve("missing.jar").toString();
        UikaException open = assertThrows(UikaException.class, () -> stream(missing, null));
        assertEquals("cannot open " + missing + ": No such file or directory (os error 2)", open.getMessage());
        UikaException fetch = assertThrows(UikaException.class, () -> Input.fetchEntries(missing, List.of(), (n, b, l) -> {}));
        assertEquals("cannot open " + missing + ": No such file or directory (os error 2)", fetch.getMessage());

        Path garbage = dir.resolve("garbage.jar");
        Files.write(garbage, "this is not a zip file at all".getBytes(StandardCharsets.UTF_8));
        UikaException notZip = assertThrows(UikaException.class, () -> stream(garbage.toString(), null));
        assertTrue(notZip.getMessage().startsWith("not a zip/jar: " + garbage), notZip.getMessage());
    }

    @Test
    void scannableNamesAreJudgedTheSameFromBytesAndStrings() {
        for (String name : List.of(
                "a/B.class",
                "B.class",
                ".class",
                "module-info.class",
                "a/module-info.class",
                "a/xmodule-info.class",
                "META-INF/versions/9/a/B.class",
                "META-INF/versionsX/a/B.class",
                "a/B.clazz",
                "a/B.CLASS",
                "class",
                "")) {
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            assertEquals(Input.isScannable(name), Input.isScannable(bytes, bytes.length), name);
        }
        assertTrue(Input.isScannable("a/B.class"));
        assertFalse(Input.isScannable("module-info.class"));
        assertFalse(Input.isScannable("a/xmodule-info.class")); // Rust tests the suffix only, so does this.
        assertFalse(Input.isScannable("META-INF/versions/9/a/B.class"));
        assertTrue(Input.isScannable("META-INF/versionsX/a/B.class"));
    }
}
