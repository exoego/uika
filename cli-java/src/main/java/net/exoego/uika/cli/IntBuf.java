package net.exoego.uika.cli;

import java.util.Arrays;

/** Growable int buffer. */
final class IntBuf {
    int[] a;
    int n;

    IntBuf() {
        this(64);
    }

    IntBuf(int capacity) {
        a = new int[capacity];
    }

    private boolean pooled;

    /** A buffer whose array comes from {@link IntPool} and goes back with {@link #release}. */
    static IntBuf pooled(int capacity) {
        IntBuf buf = new IntBuf(IntPool.acquire(capacity));
        buf.pooled = true;
        return buf;
    }

    private IntBuf(int[] array) {
        a = array;
    }

    void add(int value) {
        if (n == a.length) {
            a = pooled ? IntPool.grow(a, n, n + 1) : Arrays.copyOf(a, a.length * 2);
        }
        a[n++] = value;
    }

    /** Returns a pooled array. The buffer must not be used afterwards. */
    void release() {
        if (pooled && a != null) {
            IntPool.release(a);
            a = null;
        }
    }

    boolean isEmpty() {
        return n == 0;
    }
}
