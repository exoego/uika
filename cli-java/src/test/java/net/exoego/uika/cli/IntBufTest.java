package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntBufTest {
    @Test
    void aPooledBufferGrowsAndGoesBackOnce() {
        IntBuf buf = IntBuf.pooled(64);
        for (int i = 0; i < 1000; i++) {
            buf.add(i);
        }
        assertEquals(1000, buf.n);
        assertTrue(buf.a.length >= 1000);
        for (int i = 0; i < 1000; i++) {
            assertEquals(i, buf.a[i]);
        }
        buf.release();
        assertNull(buf.a);
        // A second release must not hand anything to the pool again.
        buf.release();
        assertNull(buf.a);
    }

    @Test
    void releasingAHeapBufferKeepsItsArray() {
        IntBuf buf = new IntBuf(2);
        buf.add(1);
        buf.add(2);
        buf.add(3);
        buf.release();
        assertNotNull(buf.a);
        assertEquals(3, buf.n);
        assertEquals(3, buf.a[2]);
    }
}
