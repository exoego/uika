package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stands in for the `window.rs` tests. Those pin a sliding-window reader the zip crate reads
 * through, which has no Java twin. Here {@link Jar} streams the central directory through a
 * fixed window itself and everything it does not cover falls back to {@link ZipFile}. The
 * properties the Rust tests guard (reads that cross a window boundary, far-apart reads, the
 * end of the file) are therefore pinned on that reader instead.
 */
class JarTest {
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

    private static Jar.Entries readEntries(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
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

    /** What {@link ZipFile} lists, narrowed to the entries a scan reads, without the suffix. */
    private static List<String> zipFileClassNames(Path path) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (Input.isScannable(name)) {
                    names.add(name.substring(0, name.length() - 6));
                }
            }
        }
        return names;
    }

    record Seen(String entry, byte[] bytes) {}

    private static final Input.Sink<List<Seen>> COLLECT = new Input.Sink<>() {
        @Override
        public List<Seen> newLeaf() {
            return new ArrayList<>();
        }

        @Override
        public void accept(List<Seen> leaf, Scratch scratch, int source, int entry, ClassSource bytes) {
            bytes.readAll();
            leaf.add(new Seen(Intern.str(entry), Arrays.copyOf(bytes.bytes, bytes.available)));
        }
    };

    private static List<Seen> stream(Path path) {
        List<List<Seen>> leaves = new ArrayList<>();
        Input.onPool(() -> Input.forEachClass(path.toString(), COLLECT, leaves));
        return Input.concat(leaves);
    }

    // ---- a hand-assembled zip, for the layouts ZipOutputStream never writes ----

    /** One stored entry. The fields a test can bend are public on purpose. */
    static final class Rec {
        final byte[] name;
        final byte[] data;
        int flags;
        int method;
        /** Central-directory values, where -1 keeps the real one. */
        long cdOffset = -1;
        long cdCompressed = -1;
        long cdInflated = -1;
        /** Extra field in the LOCAL header only, so local and central lengths disagree. */
        int localExtra;
        /** Extra field and comment in the CENTRAL record. */
        int cdExtra;
        int cdComment;
        int localOffset;

        Rec(String name, byte[] data) {
            this(name.getBytes(StandardCharsets.UTF_8), data);
        }

        Rec(byte[] name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >>> 8);
    }

    private static void le32(ByteArrayOutputStream out, long v) {
        le16(out, (int) (v & 0xffff));
        le16(out, (int) ((v >>> 16) & 0xffff));
    }

    /**
     * Local entries in {@code physical} order, central records in {@code central} order.
     *
     * @param declaredTotal the entry count the end record claims, -1 for the real one
     */
    private static byte[] assemble(List<Rec> physical, List<Rec> central, int declaredTotal, int archiveComment) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Rec r : physical) {
            r.localOffset = out.size();
            CRC32 crc = new CRC32();
            crc.update(r.data);
            le32(out, 0x04034b50L);
            le16(out, 20);
            le16(out, r.flags);
            le16(out, r.method);
            le16(out, 0);
            le16(out, 0);
            le32(out, crc.getValue());
            le32(out, r.data.length);
            le32(out, r.data.length);
            le16(out, r.name.length);
            le16(out, r.localExtra);
            out.write(r.name, 0, r.name.length);
            out.write(new byte[r.localExtra], 0, r.localExtra);
            out.write(r.data, 0, r.data.length);
        }
        int cdStart = out.size();
        for (Rec r : central) {
            CRC32 crc = new CRC32();
            crc.update(r.data);
            le32(out, 0x02014b50L);
            le16(out, 20);
            le16(out, 20);
            le16(out, r.flags);
            le16(out, r.method);
            le16(out, 0);
            le16(out, 0);
            le32(out, crc.getValue());
            le32(out, r.cdCompressed >= 0 ? r.cdCompressed : r.data.length);
            le32(out, r.cdInflated >= 0 ? r.cdInflated : r.data.length);
            le16(out, r.name.length);
            le16(out, r.cdExtra);
            le16(out, r.cdComment);
            le16(out, 0);
            le16(out, 0);
            le32(out, 0);
            le32(out, r.cdOffset >= 0 ? r.cdOffset : r.localOffset);
            out.write(r.name, 0, r.name.length);
            out.write(new byte[r.cdExtra + r.cdComment], 0, r.cdExtra + r.cdComment);
        }
        int cdSize = out.size() - cdStart;
        le32(out, 0x06054b50L);
        le16(out, 0);
        le16(out, 0);
        le16(out, declaredTotal >= 0 ? declaredTotal : central.size());
        le16(out, declaredTotal >= 0 ? declaredTotal : central.size());
        le32(out, cdSize);
        le32(out, cdStart);
        le16(out, archiveComment);
        out.write(new byte[archiveComment], 0, archiveComment);
        return out.toByteArray();
    }

    private Path write(String file, byte[] bytes) throws IOException {
        Path path = dir.resolve(file);
        Files.write(path, bytes);
        return path;
    }

    // ---- (a) a directory larger than the streaming window ----

    /**
     * Stands in for `reads_across_window_boundaries`. A directory several windows long, whose
     * records straddle every window edge, reads to the same entries as the reference reader.
     */
    @Test
    void aCentralDirectoryLargerThanTheWindowReadsLikeZipFile() throws Exception {
        Path path = dir.resolve("wide.jar");
        String padding = "p".repeat(180);
        // The pool may hand the reader a window up to 4x the nominal 256 KiB, so go well past that.
        int count = 16_000;
        try (OutputStream file = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (int i = 0; i < count; i++) {
                // Name lengths vary, so record boundaries drift across the window edges.
                String name = "pkg/" + padding.substring(0, 100 + i % 80) + "/C" + i;
                zip.putNextEntry(new ZipEntry(i % 7 == 3 ? name + ".txt" : name + ".class"));
                zip.write(classLike("class " + i));
                zip.closeEntry();
            }
        }
        long cdSize;
        long cdOffset;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            java.nio.ByteBuffer eocd = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            channel.read(eocd, channel.size() - 22);
            cdSize = eocd.getInt(12) & 0xffffffffL;
            cdOffset = eocd.getInt(16) & 0xffffffffL;
        }
        assertTrue(cdSize > 3 * 1024 * 1024, "the directory must span several windows, was " + cdSize);

        Jar.Entries entries = readEntries(path);
        assertNotNull(entries);
        List<String> expected = zipFileClassNames(path);
        assertEquals(expected, names(entries));
        assertTrue(expected.size() > count / 2 && expected.size() < count, "classes and resources are mixed");

        // Offsets ascend, and each entry ends where the next record of any kind begins.
        for (int i = 0; i < entries.count; i++) {
            long dataEnd = entries.offset(i) + 30 + (Intern.length(entries.name[i]) + 6) + entries.compressed(i);
            assertTrue(entries.end(i) >= dataEnd, "entry " + i + " ends after its data");
            if (i + 1 < entries.count) {
                assertTrue(entries.end(i) <= entries.offset(i + 1), "entry " + i + " ends before the next one");
            }
        }
        assertEquals(cdOffset, entries.end(entries.count - 1));

        // And the bounds are good enough to read every class back.
        List<Seen> seen = stream(path);
        assertEquals(expected.size(), seen.size());
        for (int i = 0; i < seen.size(); i++) {
            assertEquals(expected.get(i), seen.get(i).entry());
            String number = expected.get(i).substring(expected.get(i).lastIndexOf('C') + 1);
            assertArrayEquals(classLike("class " + number), seen.get(i).bytes());
        }
    }

    // ---- (b) a directory that is not in offset order ----

    /**
     * Stands in for `alternating_far_reads_use_both_windows` and `seek_and_read_matches_slice`.
     * The directory order says nothing about where an entry sits, so entries come back by
     * offset and each one ends at the next local header, whatever kind of entry owns it.
     */
    @Test
    void aPermutedCentralDirectoryStillYieldsOffsetOrderAndBounds() throws Exception {
        Rec a = new Rec("p/A.class", classLike("a"));
        Rec resource = new Rec("p/notes.txt", "in between".getBytes(StandardCharsets.UTF_8));
        Rec b = new Rec("p/B.class", classLike("bb"));
        b.localExtra = 7;
        Rec c = new Rec("p/C.class", classLike("ccc"));
        Rec trailer = new Rec("zz/trailer.bin", new byte[40]);
        List<Rec> physical = List.of(a, resource, b, c, trailer);
        List<Rec> central = List.of(c, trailer, a, resource, b);
        byte[] zip = assemble(physical, central, -1, 0);
        Path path = write("permuted.jar", zip);

        Jar.Entries entries = readEntries(path);
        assertNotNull(entries);
        assertEquals(List.of("p/A", "p/B", "p/C"), names(entries));
        assertEquals(a.localOffset, entries.offset(0));
        assertEquals(b.localOffset, entries.offset(1));
        assertEquals(c.localOffset, entries.offset(2));
        assertEquals(resource.localOffset, entries.end(0), "A ends at the resource behind it");
        assertEquals(c.localOffset, entries.end(1));
        assertEquals(trailer.localOffset, entries.end(2), "C ends at the trailer, not at the directory");
        assertEquals(classLike("bb").length, entries.compressed(1));
        assertTrue(entries.stored[0]);

        List<Seen> seen = stream(path);
        assertEquals(List.of("p/A", "p/B", "p/C"), seen.stream().map(Seen::entry).toList());
        assertArrayEquals(classLike("a"), seen.get(0).bytes());
        assertArrayEquals(classLike("bb"), seen.get(1).bytes(), "the local header's own extra length is honoured");
        assertArrayEquals(classLike("ccc"), seen.get(2).bytes());

        // The same entries in directory order give the same answer through the ordered path.
        Jar.Entries ordered = readEntries(write("ordered.jar", assemble(physical, physical, -1, 0)));
        assertEquals(names(entries), names(ordered));
        assertArrayEquals(Arrays.copyOf(entries.offset, entries.count), Arrays.copyOf(ordered.offset, ordered.count));
        assertArrayEquals(Arrays.copyOf(entries.end, entries.count), Arrays.copyOf(ordered.end, ordered.count));
        assertArrayEquals(Arrays.copyOf(entries.crc, entries.count), Arrays.copyOf(ordered.crc, ordered.count));
    }

    @Test
    void theLastScannableEntryOfAnOrderedDirectoryEndsAtTheDirectory() throws Exception {
        Rec a = new Rec("p/A.class", classLike("a"));
        Rec b = new Rec("p/B.class", classLike("b"));
        byte[] zip = assemble(List.of(a, b), List.of(a, b), -1, 0);
        Jar.Entries entries = readEntries(write("two.jar", zip));
        assertEquals(b.localOffset, entries.end(0));
        assertEquals(b.localOffset + 30 + 9 + 5, entries.end(1));
    }

    @Test
    void aPermutedDirectoryLargerThanTheWindowIsReadTwiceCorrectly() throws Exception {
        List<Rec> physical = new ArrayList<>();
        String padding = "q".repeat(150);
        for (int i = 0; i < 10_000; i++) {
            physical.add(new Rec("pkg/" + padding + "/C" + i + (i % 5 == 0 ? ".txt" : ".class"), classLike("n" + i)));
        }
        List<Rec> central = new ArrayList<>(physical);
        java.util.Collections.reverse(central);
        Path path = write("reversed.jar", assemble(physical, central, -1, 0));

        Jar.Entries entries = readEntries(path);
        assertNotNull(entries);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < physical.size(); i++) {
            if (i % 5 != 0) {
                expected.add("pkg/" + padding + "/C" + i);
            }
        }
        assertEquals(expected, names(entries));
        int k = 0;
        for (int i = 0; i < physical.size(); i++) {
            if (i % 5 == 0) {
                continue;
            }
            assertEquals(physical.get(i).localOffset, entries.offset(k));
            Rec last = physical.get(physical.size() - 1);
            long directory = last.localOffset + 30 + last.name.length + last.data.length;
            long next = i + 1 < physical.size() ? physical.get(i + 1).localOffset : directory;
            assertEquals(next, entries.end(k), "entry " + i);
            k++;
        }
        assertEquals(expected.size(), stream(path).size());
    }

    // ---- (c) what the direct reader does not cover ----

    private Jar.Entries readAssembled(String file, List<Rec> records, int declaredTotal) throws IOException {
        return readEntries(write(file, assemble(records, records, declaredTotal, 0)));
    }

    @Test
    void zip64MarkersSendTheJarToTheFallback() throws Exception {
        Rec plain = new Rec("p/A.class", classLike("a"));
        assertNotNull(readAssembled("plain.jar", List.of(plain), -1));

        assertNull(readAssembled("total.jar", List.of(new Rec("p/A.class", classLike("a"))), 0xFFFF));

        Rec offset = new Rec("p/A.class", classLike("a"));
        offset.cdOffset = 0xFFFFFFFFL;
        assertNull(readAssembled("offset.jar", List.of(offset), -1));

        Rec compressed = new Rec("p/A.class", classLike("a"));
        compressed.cdCompressed = 0xFFFFFFFFL;
        assertNull(readAssembled("compressed.jar", List.of(compressed), -1));

        Rec inflated = new Rec("p/A.class", classLike("a"));
        inflated.cdInflated = 0xFFFFFFFFL;
        assertNull(readAssembled("inflated.jar", List.of(inflated), -1));

        // The marker counts on any entry, scanned or not, because the offsets after it cannot be trusted.
        Rec resource = new Rec("p/big.bin", new byte[8]);
        resource.cdInflated = 0xFFFFFFFFL;
        assertNull(readAssembled("resource.jar", List.of(new Rec("p/A.class", classLike("a")), resource), -1));
    }

    /** A real zip64 archive, with more entries than the 16-bit count holds. */
    @Test
    void aRealZip64JarIsReadThroughTheFallback() throws Exception {
        Path path = dir.resolve("zip64.jar");
        try (OutputStream file = new java.io.BufferedOutputStream(Files.newOutputStream(path), 1 << 16);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (int i = 0; i < 66_000; i++) {
                ZipEntry entry = new ZipEntry("r/" + i);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(0);
                entry.setCrc(0);
                zip.putNextEntry(entry);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("p/Only.class"));
            zip.write(classLike("only"));
            zip.closeEntry();
        }
        assertNull(readEntries(path));
        List<Seen> seen = stream(path);
        assertEquals(1, seen.size());
        assertEquals("p/Only", seen.get(0).entry());
        assertArrayEquals(classLike("only"), seen.get(0).bytes());
        assertEquals(List.of("p/Only"), Input.classEntryNames(path.toString()));
    }

    @Test
    void anEncryptedFlagSendsTheJarToTheFallback() throws Exception {
        Rec encrypted = new Rec("p/Secret.class", classLike("s"));
        encrypted.flags = 0x1;
        assertNull(readAssembled("encrypted.jar", List.of(new Rec("p/A.class", classLike("a")), encrypted), -1));

        // Any other flag (UTF-8 names, data descriptor) is fine.
        Rec flagged = new Rec("p/A.class", classLike("a"));
        flagged.flags = 0x0800;
        assertNotNull(readAssembled("flagged.jar", List.of(flagged), -1));
    }

    @Test
    void aNameThatIsNotUtf8SendsTheJarToTheFallback() throws Exception {
        byte[] latin1 = "p/Caf\u00e9.class".getBytes(StandardCharsets.ISO_8859_1);
        List<Rec> records = List.of(new Rec("p/A.class", classLike("a")), new Rec(latin1, classLike("latin")));
        Path path = write("latin1.jar", assemble(records, records, -1, 0));
        assertNull(readEntries(path));

        // Rust checks every name, so a resource with such a name is enough.
        byte[] resourceName = "p/caf\u00e9.txt".getBytes(StandardCharsets.ISO_8859_1);
        assertNull(readAssembled("latin1-resource.jar", List.of(new Rec("p/A.class", classLike("a")), new Rec(resourceName, new byte[3])), -1));

        // Proper UTF-8 beyond ASCII is covered, and keeps its bytes.
        Jar.Entries utf8 = readAssembled("utf8.jar", List.of(new Rec("p/Caf\u00e9\u65e5.class", classLike("u"))), -1);
        assertEquals(List.of("p/Caf\u00e9\u65e5"), names(utf8));

        // The fallback still reads the classes of the jar the direct reader refused.
        List<Seen> seen = stream(path);
        assertEquals(2, seen.size());
        assertEquals("p/A", seen.get(0).entry());
        assertArrayEquals(classLike("latin"), seen.get(1).bytes());
    }

    @Test
    void anUncommonCompressionMethodOnAClassSendsTheJarToTheFallback() throws Exception {
        Rec bzip2 = new Rec("p/B.class", classLike("b"));
        bzip2.method = 12;
        assertNull(readAssembled("bzip2.jar", List.of(new Rec("p/A.class", classLike("a")), bzip2), -1));

        // Rust judges the method after dropping what it does not scan.
        Rec resource = new Rec("p/data.bz2", new byte[5]);
        resource.method = 12;
        Jar.Entries entries = readAssembled("bzip2-resource.jar", List.of(new Rec("p/A.class", classLike("a")), resource), -1);
        assertEquals(List.of("p/A"), names(entries));
    }

    @Test
    void aBrokenDirectoryIsRefusedNotMisread() throws Exception {
        Rec a = new Rec("p/A.class", classLike("a"));
        byte[] zip = assemble(List.of(a), List.of(a), -1, 0);

        // The end record claims one more entry than the directory holds.
        assertNull(readAssembled("overcount.jar", List.of(new Rec("p/A.class", classLike("a"))), 2));

        // No end record at all, an empty file, and an end record cut short.
        assertNull(readEntries(write("noeocd.jar", Arrays.copyOf(zip, zip.length - 22))));
        assertNull(readEntries(write("empty.jar", new byte[0])));
        assertNull(readEntries(write("short.jar", Arrays.copyOfRange(zip, zip.length - 22, zip.length - 1))));

        // A directory offset that points past the end of the file.
        byte[] beyond = zip.clone();
        beyond[zip.length - 6] = (byte) 0x7f;
        beyond[zip.length - 5] = (byte) 0x7f;
        assertNull(readEntries(write("beyond.jar", beyond)));

        // A record signature that is not one.
        byte[] badRecord = zip.clone();
        badRecord[a.localOffset + 30 + a.name.length + a.data.length] = 0x51;
        assertNull(readEntries(write("badrecord.jar", badRecord)));

        // An empty archive is fine and holds nothing.
        Jar.Entries none = readEntries(write("none.jar", assemble(List.of(), List.of(), -1, 0)));
        assertNotNull(none);
        assertEquals(0, none.count);
    }

    // ---- (d) where the directory sits relative to the 66,000-byte tail read ----

    /**
     * Like `read_past_eof_returns_zero` guards the end of the file, this guards the boundary
     * between a directory served from the tail read and one that needs a read of its own.
     */
    @Test
    void theDirectoryIsFoundOnBothSidesOfTheTailRead() throws Exception {
        // Three records, padded through their comment and extra fields, and the 22-byte end record.
        int fixed = 46 + "filler.bin".length() + 46 + "p/A.class".length() + 46 + "p/B.class".length() + 22;
        for (int tail : new int[] {fixed, 1000, 65_999, 66_000, 66_001, 66_002, 70_000}) {
            Rec a = new Rec("p/A.class", classLike("first"));
            Rec b = new Rec("p/B.class", classLike("second"));
            int padding = tail - fixed;
            a.cdComment = Math.min(padding, 0xffff);
            b.cdExtra = padding - a.cdComment;
            // Enough in front of the directory that the tail read never reaches the start of the file.
            Rec filler = new Rec("filler.bin", new byte[100_000]);
            byte[] zip = assemble(List.of(filler, a, b), List.of(filler, a, b), -1, 0);
            Path path = write("tail-" + tail + ".jar", zip);

            Jar.Entries entries = readEntries(path);
            assertNotNull(entries, "directory plus end record of " + tail + " bytes");
            assertEquals(List.of("p/A", "p/B"), names(entries), "tail " + tail);
            assertEquals(a.localOffset, entries.offset(0), "tail " + tail);
            assertEquals(b.localOffset, entries.end(0), "tail " + tail);
            assertEquals(zip.length - tail, entries.end(1), "tail " + tail);
            List<Seen> seen = stream(path);
            assertArrayEquals(classLike("first"), seen.get(0).bytes(), "tail " + tail);
            assertArrayEquals(classLike("second"), seen.get(1).bytes(), "tail " + tail);
        }
    }

    @Test
    void aJarSmallerThanTheTailReadIsReadWhole() throws Exception {
        Rec a = new Rec("p/A.class", classLike("a"));
        byte[] zip = assemble(List.of(a), List.of(a), -1, 0);
        assertTrue(zip.length < 200);
        Jar.Entries entries = readEntries(write("tiny.jar", zip));
        assertEquals(List.of("p/A"), names(entries));
        assertEquals(0, entries.offset(0));
        assertEquals(30 + 9 + 5, entries.end(0));
    }

    /** The end record is searched backwards, so an archive comment of any legal length is fine. */
    @Test
    void anArchiveCommentDoesNotHideTheEndRecord() throws Exception {
        // The last case pushes the start of the directory out of the tail read while its end stays inside.
        int[][] cases = {{1, 0}, {300, 0}, {0xffff, 0}, {0xffff, 600}};
        for (int[] c : cases) {
            int comment = c[0];
            Rec a = new Rec("p/A.class", classLike("a"));
            Rec filler = new Rec("filler.bin", new byte[80_000]);
            filler.cdComment = c[1];
            Path path = write("comment-" + comment + "-" + c[1] + ".jar", assemble(List.of(filler, a), List.of(filler, a), -1, comment));
            Jar.Entries entries = readEntries(path);
            assertNotNull(entries, "comment of " + comment);
            assertEquals(List.of("p/A"), names(entries));
            assertArrayEquals(classLike("a"), stream(path).get(0).bytes());
        }
    }

    @Test
    void retainKeepsEveryColumnAligned() throws Exception {
        Rec a = new Rec("p/A.class", classLike("a"));
        Rec b = new Rec("p/B.class", classLike("bb"));
        Rec c = new Rec("p/C.class", classLike("ccc"));
        Jar.Entries entries = readAssembled("retain.jar", List.of(a, b, c), -1);
        long endOfC = entries.end(2);
        entries.retain(new boolean[] {true, false, true});
        assertEquals(List.of("p/A", "p/C"), names(entries));
        assertEquals(c.localOffset, entries.offset(1));
        assertEquals(endOfC, entries.end(1));
        assertEquals(classLike("ccc").length, entries.compressed(1));
        // The dropped entry leaves a gap the span read simply crosses.
        Path path = dir.resolve("retain.jar");
        List<List<Seen>> leaves = new ArrayList<>();
        Input.onPool(() -> Input.forEachClass(path.toString(), new Input.Prepared(entries, false), COLLECT, leaves));
        List<Seen> seen = Input.concat(leaves);
        assertEquals(List.of("p/A", "p/C"), seen.stream().map(Seen::entry).toList());
        assertArrayEquals(classLike("ccc"), seen.get(1).bytes());
    }
}
