package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** The decoder is hand-written, so it is pinned against the JDK's zlib on everything within reach. */
class InflateTest {
    private static byte[] deflate(byte[] data, int level, int strategy) {
        Deflater deflater = new Deflater(level, true);
        deflater.setStrategy(strategy);
        deflater.setInput(data);
        deflater.finish();
        byte[] out = new byte[data.length * 2 + 64];
        int n = 0;
        while (!deflater.finished()) {
            if (n == out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            n += deflater.deflate(out, n, out.length - n);
        }
        deflater.end();
        return Arrays.copyOf(out, n);
    }

    /** A direct little-endian buffer holding the stream plus the slack the decoder may read into. */
    private static ByteBuffer input(byte[] stream) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(stream.length + Inflate.SLACK).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(0, stream);
        return buffer;
    }

    private static byte[] inflate(byte[] stream) throws Inflate.FormatException {
        Inflate inflate = new Inflate();
        inflate.reset(input(stream), 0, stream.length, new byte[64]);
        inflate.inflate(Integer.MAX_VALUE);
        assertTrue(inflate.finished());
        return Arrays.copyOf(inflate.out, inflate.outPos);
    }

    private static byte[] sample(Random random, int size, int kind) {
        byte[] data = new byte[size];
        switch (kind) {
            case 0 -> random.nextBytes(data);
            case 1 -> Arrays.fill(data, (byte) 'a');
            case 2 -> {
                // Text-like: a small alphabet with repeats, which exercises long matches.
                String[] words = {"java/lang/Object", "()V", "<init>", "Ljava/lang/String;", "Code", "kotlin/Metadata", "x"};
                int at = 0;
                while (at < size) {
                    byte[] w = words[random.nextInt(words.length)].getBytes();
                    int n = Math.min(w.length, size - at);
                    System.arraycopy(w, 0, data, at, n);
                    at += n;
                }
            }
            default -> {
                // Short periods: distances below eight take their own copy path.
                int period = 2 + random.nextInt(6);
                for (int i = 0; i < size; i++) {
                    data[i] = (byte) (i % period == 0 ? random.nextInt(4) : i % period);
                }
            }
        }
        return data;
    }

    @Test
    void roundTripsEveryLevelStrategyAndShape() throws Exception {
        Random random = new Random(42);
        int[] sizes = {0, 1, 2, 7, 8, 9, 63, 64, 65, 257, 258, 259, 1000, 4096, 70_000, 300_000};
        int[] strategies = {Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY};
        for (int size : sizes) {
            for (int kind = 0; kind < 4; kind++) {
                byte[] data = sample(random, size, kind);
                for (int level : new int[] {0, 1, 6, 9}) {
                    for (int strategy : strategies) {
                        byte[] stream = deflate(data, level, strategy);
                        assertArrayEquals(data, inflate(stream), "size " + size + " kind " + kind + " level " + level + " strategy " + strategy);
                    }
                }
            }
        }
    }

    @Test
    void resumesAtEveryTarget() throws Exception {
        byte[] data = sample(new Random(7), 20_000, 2);
        byte[] stream = deflate(data, 6, Deflater.DEFAULT_STRATEGY);
        for (int step : new int[] {1, 3, 100, 1000, 7777}) {
            Inflate inflate = new Inflate();
            inflate.reset(input(stream), 0, stream.length, new byte[16]);
            int target = 0;
            while (!inflate.finished()) {
                target += step;
                inflate.inflate(target);
                // A call may overshoot by one match, never fall short unless the stream ended.
                assertTrue(inflate.finished() || inflate.outPos >= target);
            }
            assertArrayEquals(data, Arrays.copyOf(inflate.out, inflate.outPos), "step " + step);
        }
    }

    @Test
    void aStreamNotAtTheStartOfItsBufferDecodes() throws Exception {
        byte[] data = sample(new Random(3), 5000, 2);
        byte[] stream = deflate(data, 9, Deflater.DEFAULT_STRATEGY);
        ByteBuffer buffer = ByteBuffer.allocateDirect(1000 + stream.length + Inflate.SLACK).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(1000, stream);
        Inflate inflate = new Inflate();
        inflate.reset(buffer, 1000, 1000 + stream.length, new byte[64]);
        inflate.inflate(Integer.MAX_VALUE);
        assertArrayEquals(data, Arrays.copyOf(inflate.out, inflate.outPos));
    }

    @Test
    void oneDecoderIsReusableAcrossStreams() throws Exception {
        Inflate inflate = new Inflate();
        Random random = new Random(11);
        for (int i = 0; i < 200; i++) {
            byte[] data = sample(random, random.nextInt(9000), random.nextInt(4));
            byte[] stream = deflate(data, 1 + random.nextInt(9), Deflater.DEFAULT_STRATEGY);
            inflate.reset(input(stream), 0, stream.length, new byte[32]);
            inflate.inflate(Integer.MAX_VALUE);
            assertArrayEquals(data, Arrays.copyOf(inflate.out, inflate.outPos));
        }
    }

    @Test
    void aTruncatedStreamIsAnErrorNotAHang() {
        byte[] data = sample(new Random(5), 30_000, 2);
        byte[] stream = deflate(data, 6, Deflater.DEFAULT_STRATEGY);
        for (int cut : new int[] {0, 1, 2, 5, stream.length / 3, stream.length / 2, stream.length - 1}) {
            byte[] truncated = Arrays.copyOf(stream, cut);
            assertThrows(Inflate.FormatException.class, () -> inflate(truncated), "cut at " + cut);
        }
    }

    /** Corrupt input may decode to garbage or fail, but it must never hang or escape as another exception. */
    @Test
    void corruptStreamsNeverEscapeAsAnythingButFormatException() {
        Random random = new Random(99);
        byte[] data = sample(random, 8000, 2);
        byte[] stream = deflate(data, 6, Deflater.DEFAULT_STRATEGY);
        for (int i = 0; i < 3000; i++) {
            byte[] corrupt = stream.clone();
            int flips = 1 + random.nextInt(4);
            for (int f = 0; f < flips; f++) {
                corrupt[random.nextInt(corrupt.length)] ^= (byte) (1 << random.nextInt(8));
            }
            try {
                inflate(corrupt);
            } catch (Inflate.FormatException expected) {
                // fine
            } catch (AssertionError notFinished) {
                // decoded without reaching the end marker: also fine
            }
        }
        for (int i = 0; i < 2000; i++) {
            byte[] noise = new byte[1 + random.nextInt(400)];
            random.nextBytes(noise);
            try {
                inflate(noise);
            } catch (Inflate.FormatException | AssertionError expected) {
                // fine
            }
        }
    }

    @Test
    void reservedBlockTypeIsRejected() {
        assertThrows(Inflate.FormatException.class, () -> inflate(new byte[] {0x07, 0, 0, 0}));
    }

    @Test
    void storedBlockWithBadComplementIsRejected() {
        assertThrows(Inflate.FormatException.class, () -> inflate(new byte[] {0x01, 0x05, 0x00, 0x00, 0x00, 1, 2, 3, 4, 5}));
    }

    @Test
    void aDistancePastTheStartOfOutputIsRejected() {
        // Fixed block: literal 'a' would be needed first. Here a match comes first, reaching before the output.
        // 1 (final) 01 (fixed), then length code 257 (0000001) and distance code 0 (00000).
        assertThrows(Inflate.FormatException.class, () -> inflate(new byte[] {0x03, 0x02, 0x00}));
    }

    /** Every class entry of every fixture jar, against java.util.zip. */
    @Test
    void everyFixtureEntryInflatesLikeTheJdk() throws Exception {
        Path fixtures = Path.of("tests/fixtures");
        try (Stream<Path> jars = Files.list(fixtures)) {
            for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                compareWithJdk(jar);
            }
        }
    }

    private static void compareWithJdk(Path jar) throws Exception {
        byte[] file = Files.readAllBytes(jar);
        ByteBuffer raw = ByteBuffer.allocateDirect(file.length + Inflate.SLACK).order(ByteOrder.LITTLE_ENDIAN);
        raw.put(0, file);
        Inflate inflate = new Inflate();
        int compared = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // Local header offsets come from walking the central directory by hand.
            int eocd = file.length - 22;
            while (eocd >= 0 && raw.getInt(eocd) != 0x06054b50) {
                eocd--;
            }
            int total = raw.getShort(eocd + 10) & 0xffff;
            int p = raw.getInt(eocd + 16);
            for (int i = 0; i < total; i++) {
                int method = raw.getShort(p + 10) & 0xffff;
                int compressed = raw.getInt(p + 20);
                int nameLength = raw.getShort(p + 28) & 0xffff;
                int extraLength = raw.getShort(p + 30) & 0xffff;
                int commentLength = raw.getShort(p + 32) & 0xffff;
                int header = raw.getInt(p + 42);
                String name = new String(file, p + 46, nameLength);
                p += 46 + nameLength + extraLength + commentLength;
                if (method != 8) {
                    continue;
                }
                int dataStart = header + 30 + (raw.getShort(header + 26) & 0xffff) + (raw.getShort(header + 28) & 0xffff);
                inflate.reset(raw, dataStart, dataStart + compressed, new byte[64]);
                inflate.inflate(Integer.MAX_VALUE);
                assertArrayEquals(readAll(zip, name), Arrays.copyOf(inflate.out, inflate.outPos), jar + "!" + name);
                compared++;
            }
        }
        assertTrue(compared > 0, "no deflated entry in " + jar);
    }

    private static byte[] readAll(ZipFile zip, String name) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().equals(name)) {
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            }
        }
        throw new IOException("no entry " + name);
    }

    @Test
    void emptyInputIsTruncated() {
        assertEquals("unexpected end of deflate stream", assertThrows(Inflate.FormatException.class, () -> inflate(new byte[0])).getMessage());
    }

    // ---- hand-written streams, for the shapes zlib never emits ----

    /** Writes fields least significant bit first and Huffman codes most significant bit first, as RFC 1951 packs them. */
    private static final class Bits {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private int pending;
        private int count;

        Bits put(int value, int n) {
            for (int i = 0; i < n; i++) {
                pending |= ((value >>> i) & 1) << count;
                if (++count == 8) {
                    out.write(pending);
                    pending = 0;
                    count = 0;
                }
            }
            return this;
        }

        Bits code(int code, int length) {
            for (int i = length - 1; i >= 0; i--) {
                put(code >>> i, 1);
            }
            return this;
        }

        byte[] toByteArray() {
            byte[] bytes = out.toByteArray();
            if (count == 0) {
                return bytes;
            }
            byte[] withLast = Arrays.copyOf(bytes, bytes.length + 1);
            withLast[bytes.length] = (byte) pending;
            return withLast;
        }
    }

    private static final int[] PRECODE_ORDER = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};

    /** Canonical codes for the given lengths (RFC 1951 3.2.2). */
    private static int[] canonical(int[] lengths) {
        int[] count = new int[16];
        for (int length : lengths) {
            if (length > 0) {
                count[length]++;
            }
        }
        int[] next = new int[16];
        int code = 0;
        for (int bits = 1; bits < 16; bits++) {
            code = (code + count[bits - 1]) << 1;
            next[bits] = code;
        }
        int[] codes = new int[lengths.length];
        for (int s = 0; s < lengths.length; s++) {
            if (lengths[s] > 0) {
                codes[s] = next[lengths[s]]++;
            }
        }
        return codes;
    }

    /**
     * A dynamic block header. The precode gives every length 0-15 a four-bit code and
     * no repeat code, so all 19 precode lengths are sent. Returns the literal/length codes.
     */
    private static int[] dynamicHeader(Bits b, boolean last, int[] litlen, int[] dist) {
        b.put(last ? 1 : 0, 1).put(2, 2);
        b.put(litlen.length - 257, 5).put(dist.length - 1, 5).put(19 - 4, 4);
        for (int symbol : PRECODE_ORDER) {
            b.put(symbol < 16 ? 4 : 0, 3);
        }
        for (int length : litlen) {
            b.code(length, 4);
        }
        for (int length : dist) {
            b.code(length, 4);
        }
        return canonical(litlen);
    }

    private static byte[] inflateWithSlack(byte[] stream, int slackByte) throws Inflate.FormatException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(stream.length + Inflate.SLACK).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(0, stream);
        for (int i = 0; i < Inflate.SLACK; i++) {
            buffer.put(stream.length + i, (byte) slackByte);
        }
        Inflate inflate = new Inflate();
        inflate.reset(buffer, 0, stream.length, new byte[64]);
        inflate.inflate(Integer.MAX_VALUE);
        return Arrays.copyOf(inflate.out, inflate.outPos);
    }

    /**
     * A header that sends all 19 precode lengths runs out of buffered bits partway when it
     * starts at one particular bit offset, so every offset is tried.
     */
    @Test
    void aDynamicHeaderDecodesAtEveryBitAlignment() throws Exception {
        for (int shift = 0; shift < 8; shift++) {
            Bits b = new Bits();
            // A fixed block of nine-bit literals moves the next header one bit per literal.
            b.put(0, 1).put(1, 2);
            for (int k = 0; k < shift; k++) {
                b.code(0x190 + (200 - 144), 9);
            }
            b.code(0, 7);
            int[] litlen = new int[257];
            litlen['x'] = 2;
            litlen['y'] = 2;
            litlen[256] = 1;
            int[] codes = dynamicHeader(b, true, litlen, new int[] {0});
            for (char c : "xyyx".toCharArray()) {
                b.code(codes[c], litlen[c]);
            }
            b.code(codes[256], litlen[256]);

            byte[] expected = new byte[shift + 4];
            Arrays.fill(expected, 0, shift, (byte) 200);
            System.arraycopy("xyyx".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, expected, shift, 4);
            assertArrayEquals(expected, inflate(b.toByteArray()), "shift " + shift);
        }
    }

    @Test
    void aTruncatedStoredBlockIsAnError() {
        byte[] stream = {0x01, 100, 0, (byte) ~100, (byte) 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(
                "unexpected end of deflate stream",
                assertThrows(Inflate.FormatException.class, () -> inflate(stream)).getMessage());
    }

    /** A precode with no codes at all builds an empty table, and the first length it decodes hits it. */
    @Test
    void aPrecodeWithoutCodesIsRejected() {
        Bits b = new Bits().put(1, 1).put(2, 2).put(0, 5).put(0, 5).put(0, 4).put(0, 12).put(0, 32);
        assertEquals(
                "invalid code lengths set",
                assertThrows(Inflate.FormatException.class, () -> inflate(b.toByteArray())).getMessage());
    }

    @Test
    void aRepeatAsTheFirstCodeLengthIsRejected() {
        // Precode: lengths for 16 and 0 are one bit each, so 0 is code 0 and 16 is code 1.
        Bits b = new Bits().put(1, 1).put(2, 2).put(0, 5).put(0, 5).put(0, 4);
        b.put(1, 3).put(0, 3).put(0, 3).put(1, 3);
        b.code(1, 1).put(0, 2).put(0, 32);
        assertEquals(
                "invalid bit length repeat",
                assertThrows(Inflate.FormatException.class, () -> inflate(b.toByteArray())).getMessage());
    }

    /**
     * A code of a single one-bit symbol is the one incomplete code the format allows. It
     * decodes through the bit it assigns and fails on the other.
     */
    @Test
    void aSingleOneBitDistanceCodeDecodesAndRejectsTheUnusedBit() throws Exception {
        int[] litlen = new int[258];
        litlen['a'] = 2;
        litlen[256] = 2;
        litlen[257] = 1; // match length 3
        for (int distanceBit = 0; distanceBit < 2; distanceBit++) {
            Bits b = new Bits();
            int[] codes = dynamicHeader(b, true, litlen, new int[] {1});
            b.code(codes['a'], 2);
            b.code(codes[257], 1).code(distanceBit, 1);
            b.code(codes[256], 2);
            byte[] stream = b.toByteArray();
            if (distanceBit == 0) {
                assertArrayEquals("aaaa".getBytes(java.nio.charset.StandardCharsets.US_ASCII), inflate(stream));
            } else {
                assertEquals(
                        "invalid distance code",
                        assertThrows(Inflate.FormatException.class, () -> inflate(stream)).getMessage());
            }
        }
    }

    @Test
    void aSingleOneBitLiteralCodeDecodesAndRejectsTheUnusedBit() throws Exception {
        int[] litlen = new int[257];
        litlen[256] = 1;
        Bits empty = new Bits();
        dynamicHeader(empty, true, litlen, new int[] {0});
        empty.code(0, 1);
        assertArrayEquals(new byte[0], inflate(empty.toByteArray()));

        Bits invalid = new Bits();
        dynamicHeader(invalid, true, litlen, new int[] {0});
        invalid.code(1, 1).put(0, 16);
        assertEquals(
                "invalid literal/length code",
                assertThrows(Inflate.FormatException.class, () -> inflate(invalid.toByteArray())).getMessage());
    }

    /**
     * The decoder reads ahead into the bytes after the entry, which in a span are the next
     * entry's. A cut-off stream must end there as an error, whatever those bytes decode to.
     */
    @Test
    void aStreamCutInALiteralRunEndsAtTheBufferNotPastIt() {
        Bits b = new Bits().put(1, 1).put(1, 2);
        // Five nine-bit literals bring the cut to a byte boundary, so no stray zero bits sit before the garbage.
        for (int k = 0; k < 5; k++) {
            b.code(0x190 + (200 - 144), 9);
        }
        for (int k = 0; k < 15; k++) {
            b.code(0x30 + 'a', 8);
        }
        byte[] cut = b.toByteArray();
        assertEquals(21, cut.length);
        // All ones decode as the nine-bit literal 255 under the fixed code, so no end-of-block stops the run.
        assertEquals(
                "unexpected end of deflate stream",
                assertThrows(Inflate.FormatException.class, () -> inflateWithSlack(cut, 0xff)).getMessage());
    }
}
