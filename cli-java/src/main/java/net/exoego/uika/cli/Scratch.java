package net.exoego.uika.cli;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Per-thread reusable state for the scan path, so parsing a class allocates nothing.
 *
 * <p>Garbage is what would otherwise set the resident size: the collector grows the young
 * generation to absorb the allocation rate, and every page it touches stays resident.
 */
final class Scratch {
    static final int INTERN_CACHE_SIZE = 4096;
    static final int INTERN_CACHE_MASK = INTERN_CACHE_SIZE - 1;

    final int[] internCacheSym = new int[INTERN_CACHE_SIZE];
    final int[] internCacheHash = new int[INTERN_CACHE_SIZE];
    /** This thread's unused range of the intern string arena, the chunk holding it, and its range of the id space. */
    long internSlabPos;
    long internSlabEnd;
    java.nio.ByteBuffer internSlabChunk;
    int internIdNext;
    int internIdEnd;
    final ClassParser parser = new ClassParser();
    /** The class being parsed, inflated on demand. */
    final ClassSource classSource = new ClassSource();
    /** Staging for loose-file reads. */
    final java.nio.ByteBuffer fileBuffer = java.nio.ByteBuffer.allocateDirect(64 * 1024);
    /** Whole-file reads (directories, fallback, pass 2). */
    byte[] classBytes = new byte[64 * 1024];
    /** Entry name or decoded constant, copied out of an off-heap buffer. */
    byte[] nameBytes = new byte[1024];
    /** One flag byte per constant-pool slot. */
    byte[] cpFlags = new byte[1024];
    /** Per constant-pool Class slot: the accepted owner symbol + 1, or -1 for rejected. */
    int[] cpOwner = new int[1024];

    final EntryColumns entryColumns = new EntryColumns();

    /** Columns a central-directory parse fills before the exact-size copy is made. */
    static final class EntryColumns {
        int[] name = new int[256];
        int[] crc = new int[256];
        int[] offset = new int[256];
        int[] end = new int[256];
        int[] compressed = new int[256];
        int[] inflated = new int[256];
        int[] ordinal = new int[256];
        boolean[] stored = new boolean[256];
        /** {@code offset << 31 | ordinal} of EVERY record; only filled for out-of-order directories. */
        long[] allKeys = new long[0];

        void reset(int allRecords) {
            if (allKeys.length < allRecords) {
                allKeys = new long[allRecords];
            }
        }

        void ensure(int n) {
            if (name.length < n) {
                int capacity = Math.max(n, name.length * 2);
                name = java.util.Arrays.copyOf(name, capacity);
                crc = java.util.Arrays.copyOf(crc, capacity);
                offset = java.util.Arrays.copyOf(offset, capacity);
                end = java.util.Arrays.copyOf(end, capacity);
                compressed = java.util.Arrays.copyOf(compressed, capacity);
                inflated = java.util.Arrays.copyOf(inflated, capacity);
                ordinal = java.util.Arrays.copyOf(ordinal, capacity);
                stored = java.util.Arrays.copyOf(stored, capacity);
            }
        }

        /**
         * A jar with tens of thousands of classes grows these columns to megabytes, and every
         * worker meets one sooner or later. They are dropped again afterwards, since keeping
         * them would cost that on every thread for the rest of the run.
         */
        private static final int KEEP = 4096;

        private void trim() {
            if (name.length > KEEP) {
                name = new int[256];
                crc = new int[256];
                offset = new int[256];
                end = new int[256];
                compressed = new int[256];
                inflated = new int[256];
                ordinal = new int[256];
                stored = new boolean[256];
            }
            if (allKeys.length > KEEP) {
                allKeys = new long[0];
            }
        }

        /** Copy of the first {@code n} rows, reordered by {@code permutation} when given. */
        Jar.Entries copy(int n, int[] permutation) {
            Jar.Entries out = copyRows(n, permutation);
            trim();
            return out;
        }

        private Jar.Entries copyRows(int n, int[] permutation) {
            Jar.Entries out = new Jar.Entries();
            out.count = n;
            out.name = pick(name, n, permutation);
            out.crc = pick(crc, n, permutation);
            out.offset = pick(offset, n, permutation);
            out.end = pick(end, n, permutation);
            out.compressed = pick(compressed, n, permutation);
            out.inflated = pick(inflated, n, permutation);
            out.stored = new boolean[n];
            for (int i = 0; i < n; i++) {
                out.stored[i] = stored[permutation == null ? i : permutation[i]];
            }
            return out;
        }

        /** The copy comes from {@link IntPool}, so it may be longer than {@code n}. */
        private static int[] pick(int[] column, int n, int[] permutation) {
            int[] out = IntPool.acquire(n);
            if (permutation == null) {
                System.arraycopy(column, 0, out, 0, n);
                return out;
            }
            for (int i = 0; i < n; i++) {
                out[i] = column[permutation[i]];
            }
            return out;
        }
    }

    private static final ThreadLocal<Scratch> FALLBACK = ThreadLocal.withInitial(Scratch::new);

    static Scratch current() {
        Thread t = Thread.currentThread();
        if (t instanceof Worker w) {
            return w.scratch;
        }
        return FALLBACK.get();
    }

    byte[] nameBytes(int len) {
        if (nameBytes.length < len) {
            nameBytes = new byte[Math.max(len, nameBytes.length * 2)];
        }
        return nameBytes;
    }

    /** A worker that carries its scratch as a field: cheaper than a ThreadLocal lookup. */
    static final class Worker extends ForkJoinWorkerThread {
        final Scratch scratch = new Scratch();

        Worker(ForkJoinPool pool) {
            super(pool);
            setDaemon(true);
        }
    }

    private static volatile ForkJoinPool pool;

    /** The shared pool: one worker per hardware thread, like rayon's default. */
    static ForkJoinPool pool() {
        ForkJoinPool p = pool;
        if (p == null) {
            synchronized (Scratch.class) {
                p = pool;
                if (p == null) {
                    p = new ForkJoinPool(threads(), Worker::new, null, false);
                    pool = p;
                }
            }
        }
        return p;
    }

    static int threads() {
        String env = Env.get("UIKA_THREADS");
        if (env != null) {
            try {
                int n = Integer.parseInt(env.trim());
                if (n > 0) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }
}
