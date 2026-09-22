package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NameSetTest {
    private static int find(NameSet set, String name) {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        return set.find(utf8, 0, utf8.length, Intern.hash(utf8, 0, utf8.length));
    }

    @Test
    void findsWhatWasAddedByRawBytes() {
        NameSet set = new NameSet(4);
        int shortName = Intern.intern("a/B");
        int longName = Intern.intern("org/example/lib/Owner");
        set.add(shortName);
        set.add(longName);
        // A repeat is a no-op, not a second slot.
        set.add(longName);

        assertEquals(shortName, find(set, "a/B"));
        assertEquals(longName, find(set, "org/example/lib/Owner"));
        assertEquals(Intern.NONE, find(set, "org/example/lib/Other"));
        assertTrue(set.contains("a/B"));
        assertFalse(set.contains("a/C"));

        byte[] padded = "xxorg/example/lib/Owner".getBytes(StandardCharsets.UTF_8);
        assertTrue(set.mayContain(padded, 2, padded.length - 2));
        byte[] shortBytes = "a/B".getBytes(StandardCharsets.UTF_8);
        assertTrue(set.mayContain(shortBytes, 0, shortBytes.length));
    }

    /** Two names can share the whole 32-bit hash; the bytes still tell them apart. */
    @Test
    void aFullHashCollisionIsNotAMatch() {
        Map<Integer, String> seen = new HashMap<>();
        String first = null;
        String second = null;
        for (int i = 0; second == null; i++) {
            String name = "collide/C" + i;
            byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
            String earlier = seen.putIfAbsent(Intern.hash(utf8, 0, utf8.length), name);
            if (earlier != null) {
                first = earlier;
                second = name;
            }
        }
        NameSet set = new NameSet(1);
        int sym = Intern.intern(first);
        set.add(sym);

        assertEquals(Intern.hashOf(sym), Intern.hash(second.getBytes(StandardCharsets.UTF_8), 0, second.length()));
        assertNotEquals(first, second);
        assertEquals(Intern.NONE, find(set, second));
        assertEquals(sym, find(set, first));
    }
}
