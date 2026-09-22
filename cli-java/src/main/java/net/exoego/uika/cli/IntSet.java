package net.exoego.uika.cli;

import java.util.Arrays;

/** Open-addressing set of non-negative ints (symbols). */
final class IntSet {
    private int[] slots;
    private int size;

    IntSet() {
        this(8);
    }

    IntSet(int expected) {
        slots = new int[Integer.highestOneBit(Math.max(8, expected * 2 - 1)) * 2];
    }

    boolean isEmpty() {
        return size == 0;
    }

    private static int slot(int key, int mask) {
        int h = key * 0x9E3779B9;
        return (h ^ (h >>> 15)) & mask;
    }

    boolean contains(int key) {
        int[] s = slots;
        int mask = s.length - 1;
        int slot = slot(key, mask);
        while (true) {
            int stored = s[slot];
            if (stored == 0) {
                return false;
            }
            if (stored == key + 1) {
                return true;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** True when the key was not present before. */
    boolean add(int key) {
        int[] s = slots;
        int mask = s.length - 1;
        int slot = slot(key, mask);
        while (true) {
            int stored = s[slot];
            if (stored == 0) {
                s[slot] = key + 1;
                if (++size * 2 > s.length) {
                    grow();
                }
                return true;
            }
            if (stored == key + 1) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void grow() {
        int[] old = slots;
        slots = new int[old.length * 2];
        int mask = slots.length - 1;
        for (int stored : old) {
            if (stored != 0) {
                int slot = slot(stored - 1, mask);
                while (slots[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                slots[slot] = stored;
            }
        }
    }

    void clear() {
        if (size != 0) {
            Arrays.fill(slots, 0);
            size = 0;
        }
    }

    /** Elements in table order, which is arbitrary: sort by string value before output. */
    int[] toArray() {
        int[] out = new int[size];
        int n = 0;
        for (int stored : slots) {
            if (stored != 0) {
                out[n++] = stored - 1;
            }
        }
        return out;
    }
}
