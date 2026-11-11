package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MemberProbeTest {
    /** The name and descriptor side by side, as they sit in a constant pool buffer. */
    private static byte[] joined(String name, String descriptor) {
        return (name + descriptor).getBytes(StandardCharsets.UTF_8);
    }

    /** The evidence member a raw pair matches, looked up the way the pass-1 sweep does. */
    private static long find(MemberProbe probe, String name, String descriptor) {
        int nameLength = name.getBytes(StandardCharsets.UTF_8).length;
        int descriptorLength = descriptor.getBytes(StandardCharsets.UTF_8).length;
        int slot = probe.slot(joined(name, descriptor), 0, nameLength, nameLength, descriptorLength);
        return slot >= 0 && probe.isEvidence(slot) ? probe.keyAt(slot) : MemberKey.NONE;
    }

    /** JVMS 4.2.2 forbids an empty method name, so a zero-length pool name can match nothing. */
    @Test
    void aZeroLengthNameNeverPassesTheFilter() {
        MemberProbe probe = new MemberProbe(new long[] {MemberKey.of("", "()V"), MemberKey.of("close", "()V")});
        assertFalse(probe.mayContainName(new byte[0], 0, 0));
        byte[] close = "close".getBytes(StandardCharsets.UTF_8);
        assertTrue(probe.mayContainName(close, 0, close.length));
    }

    @Test
    void everyKeyIsFoundThroughCollisionsAndRepeats() {
        long[] keys = new long[200];
        for (int i = 0; i < 100; i++) {
            keys[i] = MemberKey.of("m" + i, "()V");
            keys[100 + i] = keys[i];
        }
        MemberProbe probe = new MemberProbe(keys);
        assertFalse(probe.isEmpty());
        for (int i = 0; i < 100; i++) {
            assertEquals(keys[i], find(probe, "m" + i, "()V"), "m" + i);
        }
        assertEquals(MemberKey.NONE, find(probe, "m100", "()V"));
        assertEquals(MemberKey.NONE, find(probe, "m1", "(I)V"));
    }

    /** Both strings hash alike, so only the byte comparison tells them apart. */
    @Test
    void aHashCollisionIsSettledByTheBytes() {
        String a = "maadnyu";
        String b = "maaspay";
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        assertEquals(Intern.hash(aBytes, 0, aBytes.length), Intern.hash(bBytes, 0, bBytes.length), "the pair must still collide");

        MemberProbe byName = new MemberProbe(new long[] {MemberKey.of(a, "()V")});
        assertEquals(MemberKey.NONE, find(byName, b, "()V"));
        assertEquals(MemberKey.of(a, "()V"), find(byName, a, "()V"));

        MemberProbe byDescriptor = new MemberProbe(new long[] {MemberKey.of("call", a)});
        assertEquals(MemberKey.NONE, find(byDescriptor, "call", b));
        assertEquals(MemberKey.of("call", a), find(byDescriptor, "call", a));
    }

    /** One table holds both kinds, and a key answers only for the kinds it was given. */
    @Test
    void evidenceAndChangedKeysShareTheTableWithoutLeaking() {
        long both = MemberKey.of("both", "()V");
        long evidenceOnly = MemberKey.of("evidence", "()V");
        long changedOnly = MemberKey.of("changed", "()V");
        MemberProbe probe = new MemberProbe(new long[] {both, evidenceOnly}, new long[] {both, changedOnly});
        assertFalse(probe.isEmpty());
        assertTrue(probe.hasChanged());

        int changedSlot = slotOf(probe, "changed");
        assertTrue(probe.isChanged(changedSlot));
        assertFalse(probe.isEvidence(changedSlot));
        assertEquals(changedOnly, probe.keyAt(changedSlot));

        int evidenceSlot = slotOf(probe, "evidence");
        assertTrue(probe.isEvidence(evidenceSlot));
        assertFalse(probe.isChanged(evidenceSlot));
        assertEquals(evidenceOnly, probe.keyAt(evidenceSlot));

        int bothSlot = slotOf(probe, "both");
        assertTrue(probe.isEvidence(bothSlot) && probe.isChanged(bothSlot));
        assertEquals(-1, slotOf(probe, "absent"));

        MemberProbe changedAlone = new MemberProbe(new long[0], new long[] {changedOnly});
        assertTrue(changedAlone.isEmpty());
        assertTrue(changedAlone.hasChanged());
        assertFalse(new MemberProbe(new long[] {evidenceOnly}).hasChanged());
    }

    private static int slotOf(MemberProbe probe, String name) {
        byte[] bytes = (name + "()V").getBytes(StandardCharsets.UTF_8);
        return probe.slot(bytes, 0, name.length(), name.length(), 3);
    }
}
