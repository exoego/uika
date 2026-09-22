package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LongSetTest {
    @Test
    void keepsEveryKeyAcrossGrowth() {
        LongSet set = new LongSet();
        long[] added = new long[3000];
        for (int i = 0; i < added.length; i++) {
            // Packed (name, crc) pairs, the shape Dedup stores, with negative words too.
            long packed = (long) i << 32 | ((i * 0x9E3779B1L) & 0xffffffffL);
            added[i] = i % 2 == 0 ? packed : -packed;
            assertTrue(set.add(added[i]));
        }
        for (int i = 0; i < added.length; i++) {
            assertFalse(set.add(added[i]), "a second add of " + added[i]);
            assertTrue(set.contains(added[i]));
            long absent = (long) (i + 1_000_000) << 32 | i;
            assertFalse(set.contains(absent), "never added " + absent);
        }

        long[] expected = added.clone();
        Arrays.sort(expected);
        long[] actual = set.toArray();
        Arrays.sort(actual);
        assertArrayEquals(expected, actual);
    }
}
