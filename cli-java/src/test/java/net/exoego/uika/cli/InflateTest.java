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
}
