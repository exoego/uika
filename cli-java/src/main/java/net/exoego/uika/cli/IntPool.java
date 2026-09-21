package net.exoego.uika.cli;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Recycles the int arrays the scan hands from one chunk to the next (central-directory
 * columns, leaf output).
 *
 * <p>They live until their chunk is merged, which is long enough to survive a young
 * collection and be promoted. Nothing collects the old generation in a run this short, so
 * without recycling the heap grows by the whole scan's worth of dead arrays.
 */
final class IntPool {
    private static final int MIN_BITS = 6;
    private static final int MAX_BITS = 20;

    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedDeque<int[]>[] FREE = new ConcurrentLinkedDeque[MAX_BITS - MIN_BITS + 1];

    static {
        for (int i = 0; i < FREE.length; i++) {
            FREE[i] = new ConcurrentLinkedDeque<>();
        }
    }

    private IntPool() {}

    /** An array of at least {@code size} elements. Contents are unspecified. */
    static int[] acquire(int size) {
        int bits = Math.max(MIN_BITS, 32 - Integer.numberOfLeadingZeros(Math.max(1, size - 1)));
        if (bits > MAX_BITS) {
            return new int[size];
        }
        int[] array = FREE[bits - MIN_BITS].pollFirst();
        return array != null ? array : new int[1 << bits];
    }

    static void release(int[] array) {
        int length = array.length;
        if (Integer.bitCount(length) != 1) {
            return;
        }
        int bits = Integer.numberOfTrailingZeros(length);
        if (bits >= MIN_BITS && bits <= MAX_BITS) {
            FREE[bits - MIN_BITS].addFirst(array);
        }
    }

    /** A larger array holding the first {@code used} elements of {@code array}, which is released. */
    static int[] grow(int[] array, int used, int size) {
        int[] bigger = acquire(Math.max(size, array.length * 2));
        System.arraycopy(array, 0, bigger, 0, used);
        release(array);
        return bigger;
    }
}
