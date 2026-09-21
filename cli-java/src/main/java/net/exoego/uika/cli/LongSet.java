package net.exoego.uika.cli;

/** Open-addressing set of longs other than {@link Long#MIN_VALUE} (member keys, packed pairs). */
final class LongSet {
    private static final long EMPTY = Long.MIN_VALUE;

    private long[] slots;
    private int size;

    LongSet() {
        this(8);
    }

    LongSet(int expected) {
        slots = new long[Integer.highestOneBit(Math.max(8, expected * 2 - 1)) * 2];
        java.util.Arrays.fill(slots, EMPTY);
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    boolean contains(long key) {
        long[] s = slots;
        int mask = s.length - 1;
        int slot = slot(key, mask);
        while (true) {
            long stored = s[slot];
            if (stored == EMPTY) {
                return false;
            }
            if (stored == key) {
                return true;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** True when the key was not present before. */
    boolean add(long key) {
        long[] s = slots;
        int mask = s.length - 1;
        int slot = slot(key, mask);
        while (true) {
            long stored = s[slot];
            if (stored == EMPTY) {
                s[slot] = key;
                if (++size * 2 > s.length) {
                    grow();
                }
                return true;
            }
            if (stored == key) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void grow() {
        long[] old = slots;
        slots = new long[old.length * 2];
        java.util.Arrays.fill(slots, EMPTY);
        int mask = slots.length - 1;
        for (long stored : old) {
            if (stored != EMPTY) {
                int slot = slot(stored, mask);
                while (slots[slot] != EMPTY) {
                    slot = (slot + 1) & mask;
                }
                slots[slot] = stored;
            }
        }
    }

    /** Elements in table order, which is arbitrary: sort by string value before output. */
    long[] toArray() {
        long[] out = new long[size];
        int n = 0;
        for (long stored : slots) {
            if (stored != EMPTY) {
                out[n++] = stored;
            }
        }
        return out;
    }
}
