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
     * table indexed by symbol. The second and later distinct CRCs of a name form a short
     * linked list in two more arenas, which is a fraction of the memory of hashing every
     * (name, CRC) pair. All three grow by chunks off-heap: a heap hash set of the pairs cost
     * 8MB live plus as much again in promoted garbage from doubling on a large classpath.
     */
    private final IntArena firstCrc = new IntArena();
    private final BitSet hasFirst = new BitSet();
    /** Per name, the index + 1 of its list head in {@link #moreList}, 0 for none. */
    private final IntArena moreHead = new IntArena();
    /** (CRC, next index + 1) pairs. */
    private final IntArena moreList = new IntArena();
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
        moreHead.ensureSize(name + 1);
        int head = moreHead.get(name);
        for (int at = head; at != 0; at = moreList.get(at)) {
            if (moreList.get(at - 1) == crc) {
                return false;
            }
        }
        int index = moreList.add(crc);
        moreList.add(head);
        moreHead.set(name, index + 1);
        return true;
    }
}
