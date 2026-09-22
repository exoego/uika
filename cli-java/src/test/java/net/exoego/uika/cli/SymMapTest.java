package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SymMapTest {
    @Test
    void keepsEveryEntryAcrossGrowth() {
        SymMap map = new SymMap();
        assertTrue(map.isEmpty());
        // Key 0 is stored as 1, so it must not read as an empty slot.
        for (int key = 0; key < 5000; key += 3) {
            assertEquals(SymMap.ABSENT, map.put(key, key * 2));
        }
        assertFalse(map.isEmpty());
        for (int key = 0; key < 5000; key++) {
            if (key % 3 == 0) {
                assertEquals(key * 2, map.get(key), "key " + key);
                assertTrue(map.containsKey(key));
            } else {
                assertEquals(SymMap.ABSENT, map.get(key), "key " + key);
                assertFalse(map.containsKey(key));
            }
        }
    }

    @Test
    void putOverwritesAndReturnsThePreviousValue() {
        SymMap map = new SymMap(2);
        assertEquals(SymMap.ABSENT, map.put(7, 1));
        assertEquals(1, map.put(7, 42));
        assertEquals(42, map.get(7));
        assertEquals(42, map.put(7, 0));
        assertEquals(0, map.get(7));
    }
}
