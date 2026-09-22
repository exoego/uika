package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class BufPoolTest {
    @Test
    void aBufferIsLittleEndianClearedAndLargeEnough() {
        ByteBuffer buf = BufPool.acquire(70_000);
        assertTrue(buf.isDirect());
        assertEquals(ByteOrder.LITTLE_ENDIAN, buf.order());
        assertTrue(buf.capacity() >= 70_000);
        buf.position(10).limit(20);
        BufPool.release(buf);
        ByteBuffer again = BufPool.acquire(70_000);
        assertSame(buf, again);
        assertEquals(0, again.position());
        assertEquals(again.capacity(), again.limit());
    }

    /** Past the largest size class a buffer is allocated exactly and never pooled. */
    @Test
    void aHugeBufferIsExactAndStaysOutOfThePool() {
        int size = (1 << 24) + 1;
        ByteBuffer huge = BufPool.acquire(size);
        assertEquals(size, huge.capacity());
        assertEquals(ByteOrder.LITTLE_ENDIAN, huge.order());
        BufPool.release(huge);
        assertNotSame(huge, BufPool.acquire(size));
    }

    @Test
    void onlyPowerOfTwoBuffersInsideTheClassesAreKept() {
        ByteBuffer small = ByteBuffer.allocateDirect(1024);
        BufPool.release(small);
        ByteBuffer smallest = BufPool.acquire(1024);
        assertNotSame(small, smallest);
        assertTrue(smallest.capacity() >= 1 << 16, "capacity " + smallest.capacity());

        // A heap buffer keeps the test cheap; release looks at the capacity only.
        ByteBuffer beyond = ByteBuffer.allocate(1 << 25);
        BufPool.release(beyond);
        ByteBuffer largest = BufPool.acquire(1 << 24);
        assertTrue(largest.isDirect());
        assertEquals(1 << 24, largest.capacity());
        BufPool.release(largest);
    }
}
