package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class ClassSourceTest {
    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(6, true);
        deflater.setInput(data);
        deflater.finish();
        byte[] out = new byte[data.length * 2 + 64];
        int n = deflater.deflate(out);
        deflater.end();
        return Arrays.copyOf(out, n);
    }

    /** The size the central directory claims is untrusted input; only the compressed bytes bound the buffer. */
    @Test
    void aBogusUncompressedSizeDoesNotReserveTheMaximum() {
        byte[] data = new byte[3000];
        new java.util.Random(4).nextBytes(data);
        byte[] stream = deflate(data);
        ByteBuffer input = ByteBuffer.allocateDirect(stream.length + Inflate.SLACK).order(ByteOrder.LITTLE_ENDIAN);
        input.put(0, stream);
        ClassSource source = new ClassSource();

        source.ofDeflate(input, 0, stream.length, 4L << 30);

        assertTrue(source.bytes.length <= stream.length * ClassSource.MAX_DEFLATE_RATIO + 320,
                "reserved " + source.bytes.length + " bytes for " + stream.length + " compressed");
        source.readAll();
        assertTrue(source.complete);
        assertArrayEquals(data, Arrays.copyOf(source.bytes, source.available));
    }

    /** The parser asks again for bytes it already has; that must not inflate further. */
    @Test
    void askingForLessThanIsInflatedKeepsThePartialRead() {
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 % 251);
        }
        byte[] stream = deflate(data);
        ByteBuffer input = ByteBuffer.allocateDirect(stream.length + Inflate.SLACK).order(ByteOrder.LITTLE_ENDIAN);
        input.put(0, stream);
        ClassSource source = new ClassSource();
        source.ofDeflate(input, 0, stream.length, data.length);

        source.ensure(1000);
        int available = source.available;
        assertTrue(available >= 1000 && available < data.length, "inflated " + available);
        source.ensure(500);
        assertEquals(available, source.available);
        assertFalse(source.complete);
        assertArrayEquals(Arrays.copyOf(data, available), Arrays.copyOf(source.bytes, available));
    }
}
