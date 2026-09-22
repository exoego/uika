package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IntPoolTest {
    @Test
    void sizesRoundUpToAPowerOfTwoWithAFloor() {
        assertEquals(64, IntPool.acquire(1).length);
        assertEquals(64, IntPool.acquire(64).length);
        assertEquals(128, IntPool.acquire(65).length);
        assertEquals(1 << 20, IntPool.acquire(1 << 20).length);
    }

    /** Past the largest size class an array is allocated exactly and never pooled. */
    @Test
    void aHugeArrayIsExactAndStaysOutOfThePool() {
        int[] huge = IntPool.acquire((1 << 20) + 1);
        assertEquals((1 << 20) + 1, huge.length);
        IntPool.release(huge);
        assertNotSame(huge, IntPool.acquire((1 << 20) + 1));

        int[] twiceTheLargest = new int[1 << 21];
        IntPool.release(twiceTheLargest);
        int[] largest = IntPool.acquire(1 << 20);
        assertEquals(1 << 20, largest.length);
    }

    @Test
    void onlyPowerOfTwoArraysInsideTheClassesAreKept() {
        int[] odd = new int[100];
        IntPool.release(odd);
        assertEquals(128, IntPool.acquire(100).length);

        int[] tiny = new int[32];
        IntPool.release(tiny);
        assertEquals(64, IntPool.acquire(1).length);

        int[] kept = new int[256];
        IntPool.release(kept);
        assertSame(kept, IntPool.acquire(200));
    }

    @Test
    void growKeepsTheUsedPrefix() {
        int[] small = IntPool.acquire(64);
        for (int i = 0; i < 64; i++) {
            small[i] = i * 3;
        }
        int[] bigger = IntPool.grow(small, 64, 65);
        assertEquals(128, bigger.length);
        int[] expected = new int[64];
        Arrays.setAll(expected, i -> i * 3);
        assertArrayEquals(expected, Arrays.copyOf(bigger, 64));
    }
}
