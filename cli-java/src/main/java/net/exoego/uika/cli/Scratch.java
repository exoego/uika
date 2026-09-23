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

    private static final class PoolHolder {
        static final ForkJoinPool POOL = new ForkJoinPool(threads(), Worker::new, null, false);
    }

    /** The shared pool: one worker per hardware thread. */
    static ForkJoinPool pool() {
        return PoolHolder.POOL;
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
