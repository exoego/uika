package net.exoego.uika.cli;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Growable int array over fixed-size off-heap chunks.
 *
 * <p>Off-heap because everything proportional to the scan lives here. On the heap the same
 * bytes would raise the live set, and the collector sizes the committed heap as a multiple of
 * it. Chunked because growth then never copies, so there is no transient 2x peak.
 *
 * <p>Not thread-safe for writes. Reads are safe from any thread once the writer has handed
 * the arena over through a happens-before edge (a task fork, a join).
 */
final class IntArena {
    private static final int BITS = 16;
    private static final int CHUNK = 1 << BITS;
    private static final int MASK = CHUNK - 1;

    private static final java.lang.invoke.VarHandle ELEMENT =
            java.lang.invoke.MethodHandles.arrayElementVarHandle(IntBuffer[].class);

    /** Volatile so a reader that races the writer sees a grown array whole; the read is cheap next to the buffer access it guards. */
    private volatile IntBuffer[] chunks = new IntBuffer[4];
    private int chunkCount;
    private int size;

    int size() {
        return size;
    }

    int get(int index) {
        return chunks[index >>> BITS].get(index & MASK);
    }

    /**
     * A read that may race with the writer: a chunk is published with a release store once
     * allocated, so a slot the writer has not reached yet reads as zero rather than failing.
     * Zero is what an unwritten slot holds anyway, chunks being zero-filled.
     */
    int getOrZero(int index) {
        IntBuffer[] c = chunks;
        int chunk = index >>> BITS;
        if (chunk >= c.length) {
            return 0;
        }
        IntBuffer b = (IntBuffer) ELEMENT.getAcquire(c, chunk);
        return b == null ? 0 : b.get(index & MASK);
    }

    void set(int index, int value) {
        chunks[index >>> BITS].put(index & MASK, value);
    }

    /** Appends one value and returns the index it was written at. */
    int add(int value) {
        int index = size;
        if ((index >>> BITS) >= chunkCount) {
            grow();
        }
        chunks[index >>> BITS].put(index & MASK, value);
        size = index + 1;
        return index;
    }

    /** Appends {@code count} values from {@code src} and returns the start index. */
    int addAll(int[] src, int from, int count) {
        int start = size;
        for (int i = 0; i < count; i++) {
            add(src[from + i]);
        }
        return start;
    }

    /** Grows to at least {@code newSize} elements; new elements read as zero. */
    void ensureSize(int newSize) {
        while (((newSize + MASK) >>> BITS) > chunkCount) {
            grow();
        }
        if (newSize > size) {
            size = newSize;
        }
    }

    private void grow() {
        IntBuffer chunk = ByteBuffer.allocateDirect(CHUNK * Integer.BYTES)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        IntBuffer[] c = chunks;
        if (chunkCount == c.length) {
            IntBuffer[] bigger = new IntBuffer[c.length * 2];
            System.arraycopy(c, 0, bigger, 0, chunkCount);
            bigger[chunkCount] = chunk;
            chunks = bigger;
        } else {
            ELEMENT.setRelease(c, chunkCount, chunk);
        }
        chunkCount++;
    }
}
