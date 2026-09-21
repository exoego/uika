package net.exoego.uika.cli;

import java.util.BitSet;

/**
 * Decides, from central directories alone, which JAR entries pass 1 inflates.
 *
 * <p>A byte-identical duplicate class (same entry name and CRC-32 as one already seen at an
 * earlier path) is dropped, because first-wins would discard the class it decodes to anyway.
 * On a real classpath that is most of the scan. This is exact, not a heuristic: a mis-named
 * entry has a different CRC, and even a CRC collision only ever drops a first-wins loser.
 *
 * <p>Paths must be applied in classpath order, so the surviving copy is always the earliest.
 */
final class Dedup {
    /**
     * Most class names have exactly one CRC across the classpath, so the first one lives in a
     * table indexed by symbol and only the second and later distinct CRCs go to a hash set.
     * That is a fraction of the memory of hashing every (name, CRC) pair.
     */
    private final IntArena firstCrc = new IntArena();
    private final BitSet hasFirst = new BitSet();
    private final LongSet more = new LongSet();
    /** Entries dropped, so the scanned-class total still counts the whole classpath. */
    int skipped;

    /** Drops entries already seen at an earlier path (or earlier in this one). */
    void apply(Jar.Entries entries) {
        boolean[] keep = new boolean[entries.count];
        boolean dropped = false;
        for (int i = 0; i < entries.count; i++) {
            keep[i] = firstSeen(entries.name[i], entries.crc[i]);
            dropped |= !keep[i];
            if (!keep[i]) {
                skipped++;
            }
        }
        if (dropped) {
            entries.retain(keep);
        }
    }

    private boolean firstSeen(int name, int crc) {
        if (!hasFirst.get(name)) {
            hasFirst.set(name);
            firstCrc.ensureSize(name + 1);
            firstCrc.set(name, crc);
            return true;
        }
        if (firstCrc.get(name) == crc) {
            return false;
        }
        return more.add((long) name << 32 | (crc & 0xffffffffL));
    }
}
