package net.exoego.uika.cli;

/** FIFO queue of ints over a growable ring. */
final class IntQueue {
    private int[] ring = new int[16];
    private int head;
    private int size;

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        head = 0;
        size = 0;
    }

    void add(int value) {
        if (size == ring.length) {
            int[] bigger = new int[ring.length * 2];
            for (int i = 0; i < size; i++) {
                bigger[i] = ring[(head + i) & (ring.length - 1)];
            }
            ring = bigger;
            head = 0;
        }
        ring[(head + size) & (ring.length - 1)] = value;
        size++;
    }

    int poll() {
        int value = ring[head];
        head = (head + 1) & (ring.length - 1);
        size--;
        return value;
    }
}
