package net.exoego.uika.cli;

/**
 * Set of symbols that can be probed with raw, un-interned bytes. Immutable once built, so
 * workers share it without locking.
 */
final class NameSet {
    private final int[] slots;
    private final int mask;
    private int size;
    /**
     * One bit per hash of a name's first eight bytes. A checked library's classes share a
     * package prefix, so nearly every foreign constant-pool owner is rejected here on one
     * word load, before its whole name would be hashed.
     */
    private final long[] prefixFilter = new long[FILTER_WORDS];

    private static final int FILTER_WORDS = 1 << 10;
    private static final java.lang.invoke.VarHandle LONG_VIEW = java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
            long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

    private static int filterBit(long prefix) {
        long h = prefix * 0x9E3779B97F4A7C15L;
        return (int) (h >>> (64 - 16));
    }

    /** The first eight bytes as a little-endian word, zero-padded for shorter names. */
    static long prefixOf(byte[] buf, int off, int len) {
        if (len >= 8) {
            return (long) LONG_VIEW.get(buf, off);
        }
        long prefix = 0;
        for (int i = 0; i < len; i++) {
            prefix |= (buf[off + i] & 0xffL) << (i * 8);
        }
        return prefix;
    }

    /** False means no member starts with these bytes; true means {@link #find} must decide. */
    boolean mayContain(byte[] buf, int off, int len) {
        int bit = filterBit(prefixOf(buf, off, len));
        return (prefixFilter[bit >>> 6] & (1L << bit)) != 0;
    }

    NameSet(int expected) {
        slots = new int[Integer.highestOneBit(Math.max(8, expected * 2 - 1)) * 2];
        mask = slots.length - 1;
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void add(int sym) {
        int slot = Intern.hashOf(sym) & mask;
        while (true) {
            int stored = slots[slot];
            if (stored == 0) {
                slots[slot] = sym + 1;
                size++;
                byte[] name = Intern.bytes(sym);
                int bit = filterBit(prefixOf(name, 0, name.length));
                prefixFilter[bit >>> 6] |= 1L << bit;
                return;
            }
            if (stored == sym + 1) {
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** The matching symbol, or {@link Intern#NONE}. {@code hash} must be {@link Intern#hash} of the bytes. */
    int find(byte[] buf, int off, int len, int hash) {
        int slot = hash & mask;
        while (true) {
            int stored = slots[slot];
            if (stored == 0) {
                return Intern.NONE;
            }
            int sym = stored - 1;
            if (Intern.hashOf(sym) == hash && Intern.equalsBytes(sym, buf, off, len)) {
                return sym;
            }
            slot = (slot + 1) & mask;
        }
    }

    boolean contains(String name) {
        byte[] utf8 = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return find(utf8, 0, utf8.length, Intern.hash(utf8, 0, utf8.length)) != Intern.NONE;
    }
}
