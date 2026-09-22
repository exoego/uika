package net.exoego.uika.cli;

/** Open-addressing map from a symbol (or any non-negative int) to an int. */
final class SymMap {
    static final int ABSENT = -1;

    private int[] keys;
    private int[] values;
    private int size;

    SymMap() {
        this(16);
    }

    SymMap(int expected) {
        int capacity = Integer.highestOneBit(Math.max(8, expected * 2 - 1)) * 2;
        keys = new int[capacity];
        values = new int[capacity];
    }

    boolean isEmpty() {
        return size == 0;
    }

    private static int slot(int key, int mask) {
        int h = key * 0x9E3779B9;
        return (h ^ (h >>> 15)) & mask;
    }

    /** The value for {@code key}, or {@link #ABSENT}. Values must therefore be non-negative. */
    int get(int key) {
        int[] k = keys;
        int mask = k.length - 1;
        int slot = slot(key, mask);
        while (true) {
            int stored = k[slot];
            if (stored == 0) {
                return ABSENT;
            }
            if (stored == key + 1) {
                return values[slot];
            }
            slot = (slot + 1) & mask;
        }
    }

    boolean containsKey(int key) {
        return get(key) != ABSENT;
    }

    /** Inserts or overwrites. Returns the previous value, or {@link #ABSENT}. */
    int put(int key, int value) {
        int[] k = keys;
        int mask = k.length - 1;
        int slot = slot(key, mask);
        while (true) {
            int stored = k[slot];
            if (stored == 0) {
                k[slot] = key + 1;
                values[slot] = value;
                if (++size * 2 > k.length) {
                    grow();
                }
                return ABSENT;
            }
            if (stored == key + 1) {
                int previous = values[slot];
                values[slot] = value;
                return previous;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void grow() {
        int[] oldKeys = keys;
        int[] oldValues = values;
        keys = new int[oldKeys.length * 2];
        values = new int[oldKeys.length * 2];
        int mask = keys.length - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            int stored = oldKeys[i];
            if (stored != 0) {
                int slot = slot(stored - 1, mask);
                while (keys[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = stored;
                values[slot] = oldValues[i];
            }
        }
    }
}
