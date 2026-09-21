package net.exoego.uika.cli;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Pool of direct read buffers (JAR spans, central directories).
 *
 * <p>Reads go into direct buffers because a read into a heap array is staged through a
 * JDK-internal per-thread direct buffer anyway, sized to the largest read that thread ever
 * made and never returned. Pooled because a direct buffer is only freed when the collector
 * gets around to it, which a near-garbage-free scan rarely triggers.
 */
final class BufPool {
    private static final int MIN_BITS = 16;
    private static final int MAX_BITS = 24;

    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedDeque<ByteBuffer>[] FREE =
            new ConcurrentLinkedDeque[MAX_BITS - MIN_BITS + 1];

    static {
        for (int i = 0; i < FREE.length; i++) {
            FREE[i] = new ConcurrentLinkedDeque<>();
        }
    }

    private BufPool() {}

    /** A little-endian buffer with capacity of at least {@code size}, cleared. */
    static ByteBuffer acquire(int size) {
        int bits = Math.max(MIN_BITS, 32 - Integer.numberOfLeadingZeros(Math.max(1, size - 1)));
        if (bits > MAX_BITS) {
            return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        }
        // A free buffer up to 4x larger beats allocating: what bounds the pool is how many
        // buffers are in use at once, and strict size classes would retain that many per class.
        ByteBuffer buf = null;
        for (int b = bits; b <= Math.min(MAX_BITS, bits + 2) && buf == null; b++) {
            buf = FREE[b - MIN_BITS].pollFirst();
        }
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(1 << bits).order(ByteOrder.LITTLE_ENDIAN);
        }
        buf.clear();
        return buf;
    }

    static void release(ByteBuffer buf) {
        int capacity = buf.capacity();
        if (Integer.bitCount(capacity) != 1) {
            return;
        }
        int bits = Integer.numberOfTrailingZeros(capacity);
        if (bits >= MIN_BITS && bits <= MAX_BITS) {
            FREE[bits - MIN_BITS].addFirst(buf);
        }
    }
}
