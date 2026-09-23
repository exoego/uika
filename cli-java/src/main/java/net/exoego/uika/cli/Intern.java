package net.exoego.uika.cli;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * String interning. A symbol is an int id; equality and hashing are one integer compare.
 *
 * <p>Strings are stored as UTF-8 bytes in off-heap chunks and are never materialized as
 * {@link String} on the scan path. Class-file names arrive as Modified UTF-8, which is
 * byte-identical to UTF-8 for ASCII, so the common case interns straight from the class
 * bytes with no decode and no allocation.
 *
 * <p>Ids are assigned in arrival order, which is nondeterministic under parallel parsing.
 * Never sort or compare output by id; use {@link #compare}.
 */
final class Intern {
    static final int NONE = -1;

    private static final int SHARDS = 64;
    private static final int STR_BITS = 18;
    private static final int STR_CHUNK = 1 << STR_BITS;
    private static final int STR_MASK = STR_CHUNK - 1;
    private static final int ID_BITS = 15;
    private static final int ID_CHUNK = 1 << ID_BITS;
    private static final int ID_MASK = ID_CHUNK - 1;
    private static final int LEN_BITS = 24;
    private static final int MAX_LEN = (1 << LEN_BITS) - 1;
    private static final long BIG_FLAG = 1L << 63;

    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(int[].class);

    /** Bytes a thread claims from the string arena at a time; a longer string takes the shared path. */
    private static final int SLAB = 16 * 1024;
    /** Ids a thread claims at a time. Unused ones at the end of a run are gaps, never reused. */
    private static final int ID_BLOCK = 64;

    /**
     * Lookups take no lock: a slot is published with a release store after the symbol's bytes,
     * location and hash are written, and read with an acquire load. Inserts still serialize on
     * the shard, and a table replaced by {@link #rehash} is never written again, so a reader
     * holding the old one sees a consistent, if stale, view and falls through to the lock.
     */
    private static final class Shard {
        /** Open addressing, slot = id + 1, 0 = empty. */
        volatile int[] table = new int[512];
        int count;
    }

    private static final Shard[] SHARD = new Shard[SHARDS];
    private static final Object GROW_LOCK = new Object();
    private static final AtomicLong STR_TOP = new AtomicLong();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static volatile ByteBuffer[] strChunks = new ByteBuffer[8];
    private static volatile LongBuffer[] locChunks = new LongBuffer[8];
    private static volatile IntBuffer[] hashChunks = new IntBuffer[8];
    private static volatile ByteBuffer[] bigStrings = new ByteBuffer[0];

    static {
        for (int i = 0; i < SHARDS; i++) {
            SHARD[i] = new Shard();
        }
    }

    private Intern() {}

    /** Current table size, the exclusive upper bound of every id handed out so far. */
    static int tableLen() {
        return NEXT_ID.get();
    }

    static int intern(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        return intern(null, utf8, 0, utf8.length);
    }

    /**
     * Interns UTF-8 bytes. {@code cache} is the calling thread's private lookup cache and
     * arena slabs, or null. The cache matters on the scan path: "java/lang/Object" is the
     * superclass of most classes, so it answers without touching the shared table at all.
     */
    static int intern(Scratch cache, byte[] buf, int off, int len) {
        int hash = hash(buf, off, len);
        return internHashed(cache, buf, off, len, hash);
    }

    static int internHashed(Scratch cache, byte[] buf, int off, int len, int hash) {
        if (cache != null) {
            int slot = hash & Scratch.INTERN_CACHE_MASK;
            int cached = cache.internCacheSym[slot];
            if (cached != 0 && cache.internCacheHash[slot] == hash && equalsBytes(cached - 1, buf, off, len)) {
                return cached - 1;
            }
            int sym = internShared(cache, buf, off, len, hash);
            cache.internCacheSym[slot] = sym + 1;
            cache.internCacheHash[slot] = hash;
            return sym;
        }
        return internShared(null, buf, off, len, hash);
    }

    private static int internShared(Scratch cache, byte[] buf, int off, int len, int hash) {
        Shard shard = SHARD[(hash >>> 26) & (SHARDS - 1)];
        int found = probe(shard.table, buf, off, len, hash);
        if (found != NONE) {
            return found;
        }
        synchronized (shard) {
            int[] table = shard.table;
            int mask = table.length - 1;
            int slot = hash & mask;
            while (true) {
                int entry = table[slot];
                if (entry == 0) {
                    break;
                }
                int sym = entry - 1;
                if (hashOf(sym) == hash && equalsBytes(sym, buf, off, len)) {
                    return sym;
                }
                slot = (slot + 1) & mask;
            }
            int sym = store(cache, buf, off, len, hash);
            SLOT.setRelease(table, slot, sym + 1);
            if (++shard.count * 2 > table.length) {
                rehash(shard);
            }
            return sym;
        }
    }

    /** Lock-free lookup in one shard table. A miss is only final under the shard lock. */
    private static int probe(int[] table, byte[] buf, int off, int len, int hash) {
        int mask = table.length - 1;
        int slot = hash & mask;
        while (true) {
            int entry = (int) SLOT.getAcquire(table, slot);
            if (entry == 0) {
                return NONE;
            }
            int sym = entry - 1;
            if (hashOf(sym) == hash && equalsBytes(sym, buf, off, len)) {
                return sym;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** Looks a string up without inserting it. Returns {@link #NONE} when absent. */
    static int find(byte[] buf, int off, int len) {
        int hash = hash(buf, off, len);
        Shard shard = SHARD[(hash >>> 26) & (SHARDS - 1)];
        int found = probe(shard.table, buf, off, len, hash);
        if (found != NONE) {
            return found;
        }
        synchronized (shard) {
            return probe(shard.table, buf, off, len, hash);
        }
    }

    static int find(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        return find(utf8, 0, utf8.length);
    }

    private static void rehash(Shard shard) {
        int[] old = shard.table;
        int[] table = new int[old.length * 2];
        int mask = table.length - 1;
        for (int entry : old) {
            if (entry != 0) {
                int slot = hashOf(entry - 1) & mask;
                while (table[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                table[slot] = entry;
            }
        }
        shard.table = table;
    }

    private static int store(Scratch cache, byte[] buf, int off, int len, int hash) {
        if (len > MAX_LEN) {
            throw new IllegalArgumentException("string too long to intern: " + len + " bytes");
        }
        long loc;
        if (len > STR_CHUNK) {
            loc = storeBig(buf, off, len);
        } else {
            long pos;
            if (cache != null && len <= SLAB) {
                if (cache.internSlabEnd - cache.internSlabPos < len) {
                    cache.internSlabPos = claim(SLAB);
                    cache.internSlabEnd = cache.internSlabPos + SLAB;
                    cache.internSlabChunk = strChunk((int) (cache.internSlabPos >>> STR_BITS));
                }
                pos = cache.internSlabPos;
                cache.internSlabPos += len;
                // The slab's chunk is resolved once per slab: resolving it per string put the
                // rarely taken chunk-growth branch on the hot path, and the JIT deoptimized the
                // interner every time a chunk was added.
                cache.internSlabChunk.put((int) (pos & STR_MASK), buf, off, len);
            } else {
                pos = claim(len);
                strChunk((int) (pos >>> STR_BITS)).put((int) (pos & STR_MASK), buf, off, len);
            }
            loc = (pos << LEN_BITS) | len;
        }
        int id;
        if (cache != null) {
            if (cache.internIdNext == cache.internIdEnd) {
                cache.internIdNext = claimIds(ID_BLOCK);
                cache.internIdEnd = cache.internIdNext + ID_BLOCK;
            }
            id = cache.internIdNext++;
        } else {
            id = claimIds(1);
        }
        int idChunk = id >>> ID_BITS;
        LongBuffer[] locs = locChunks;
        if (idChunk >= locs.length || locs[idChunk] == null) {
            growIds(idChunk);
        }
        locChunks[idChunk].put(id & ID_MASK, loc);
        hashChunks[idChunk].put(id & ID_MASK, hash);
        return id;
    }

    /** A range of {@code len} arena bytes inside one chunk; the skipped tail of a chunk is never used. */
    private static long claim(int len) {
        while (true) {
            long top = STR_TOP.get();
            long pos = top;
            if ((pos & STR_MASK) + len > STR_CHUNK) {
                pos = (pos + STR_CHUNK) & ~(long) STR_MASK;
            }
            if (STR_TOP.compareAndSet(top, pos + len)) {
                return pos;
            }
        }
    }

    /** The first of {@code n} fresh consecutive ids. */
    private static int claimIds(int n) {
        int id = NEXT_ID.getAndAdd(n);
        if (id < 0 || id + n < 0) {
            throw new IllegalStateException("intern table overflow");
        }
        return id;
    }

    private static long storeBig(byte[] buf, int off, int len) {
        synchronized (GROW_LOCK) {
            ByteBuffer[] old = bigStrings;
            ByteBuffer[] bigger = new ByteBuffer[old.length + 1];
            System.arraycopy(old, 0, bigger, 0, old.length);
            ByteBuffer copy = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder());
            copy.put(0, buf, off, len);
            bigger[old.length] = copy;
            bigStrings = bigger;
            return BIG_FLAG | ((long) old.length << LEN_BITS) | len;
        }
    }

    private static ByteBuffer strChunk(int index) {
        ByteBuffer[] chunks = strChunks;
        if (index < chunks.length && chunks[index] != null) {
            return chunks[index];
        }
        synchronized (GROW_LOCK) {
            chunks = strChunks;
            if (index < chunks.length && chunks[index] != null) {
                return chunks[index];
            }
            // Copy-on-write: a reader only ever sees a chunk through the volatile publish
            // below, never through a racy write into an array it already holds.
            ByteBuffer[] bigger = java.util.Arrays.copyOf(chunks, Math.max(chunks.length, index + 1));
            bigger[index] = ByteBuffer.allocateDirect(STR_CHUNK).order(ByteOrder.nativeOrder());
            strChunks = bigger;
            return bigger[index];
        }
    }

    private static void growIds(int idChunk) {
        synchronized (GROW_LOCK) {
            LongBuffer[] locs = locChunks;
            if (idChunk < locs.length && locs[idChunk] != null) {
                return;
            }
            int n = Math.max(locs.length, idChunk + 1);
            LongBuffer[] biggerLocs = java.util.Arrays.copyOf(locs, n);
            IntBuffer[] biggerHashes = java.util.Arrays.copyOf(hashChunks, n);
            biggerLocs[idChunk] = ByteBuffer.allocateDirect(ID_CHUNK * Long.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asLongBuffer();
            biggerHashes[idChunk] = ByteBuffer.allocateDirect(ID_CHUNK * Integer.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            // Hashes first: a reader gates on locChunks, so by the time it sees the new
            // location chunk the hash chunk is already published.
            hashChunks = biggerHashes;
            locChunks = biggerLocs;
        }
    }

    private static long locOf(int sym) {
        return locChunks[sym >>> ID_BITS].get(sym & ID_MASK);
    }

    static int hashOf(int sym) {
        return hashChunks[sym >>> ID_BITS].get(sym & ID_MASK);
    }

    static int length(int sym) {
        return (int) (locOf(sym) & MAX_LEN);
    }

    /** The backing buffer of {@code loc}; its start offset is {@link #offsetOf}. */
    private static ByteBuffer bufferOf(long loc) {
        if (loc < 0) {
            return bigStrings[(int) ((loc & ~BIG_FLAG) >>> LEN_BITS)];
        }
        return strChunks[(int) (loc >>> (LEN_BITS + STR_BITS))];
    }

    private static int offsetOf(long loc) {
        return loc < 0 ? 0 : (int) ((loc >>> LEN_BITS) & STR_MASK);
    }

    static boolean equalsBytes(int sym, byte[] buf, int off, int len) {
        long loc = locOf(sym);
        if ((int) (loc & MAX_LEN) != len) {
            return false;
        }
        ByteBuffer chunk = bufferOf(loc);
        int pos = offsetOf(loc);
        int i = 0;
        for (; i + 8 <= len; i += 8) {
            if (chunk.getLong(pos + i) != (long) LONG_VIEW.get(buf, off + i)) {
                return false;
            }
        }
        for (; i < len; i++) {
            if (chunk.get(pos + i) != buf[off + i]) {
                return false;
            }
        }
        return true;
    }

    /** Copies the symbol's UTF-8 bytes into {@code dst} and returns the length. */
    static int copyBytes(int sym, byte[] dst, int dstOff) {
        long loc = locOf(sym);
        int len = (int) (loc & MAX_LEN);
        bufferOf(loc).get(offsetOf(loc), dst, dstOff, len);
        return len;
    }

    static byte[] bytes(int sym) {
        byte[] out = new byte[length(sym)];
        copyBytes(sym, out, 0);
        return out;
    }

    static String str(int sym) {
        return new String(bytes(sym), StandardCharsets.UTF_8);
    }

    /**
     * Orders two symbols by string value: unsigned bytewise over UTF-8, which is code point
     * order. This is the only ordering output may use.
     */
    static int compare(int a, int b) {
        if (a == b) {
            return 0;
        }
        long locA = locOf(a);
        long locB = locOf(b);
        ByteBuffer bufA = bufferOf(locA);
        ByteBuffer bufB = bufferOf(locB);
        int posA = offsetOf(locA);
        int posB = offsetOf(locB);
        int lenA = (int) (locA & MAX_LEN);
        int lenB = (int) (locB & MAX_LEN);
        int n = Math.min(lenA, lenB);
        for (int i = 0; i < n; i++) {
            int x = bufA.get(posA + i) & 0xff;
            int y = bufB.get(posB + i) & 0xff;
            if (x != y) {
                return x - y;
            }
        }
        return lenA - lenB;
    }

    /** Whether the symbol starts with the given ASCII prefix. */
    static boolean startsWith(int sym, String asciiPrefix) {
        long loc = locOf(sym);
        int len = (int) (loc & MAX_LEN);
        int n = asciiPrefix.length();
        if (len < n) {
            return false;
        }
        ByteBuffer buf = bufferOf(loc);
        int pos = offsetOf(loc);
        for (int i = 0; i < n; i++) {
            if (buf.get(pos + i) != (byte) asciiPrefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Whether two internal class names share a package (the text before the last '/'). */
    static boolean samePackage(int a, int b) {
        int endA = packageEnd(a);
        int endB = packageEnd(b);
        if (endA != endB) {
            return false;
        }
        long locA = locOf(a);
        long locB = locOf(b);
        ByteBuffer bufA = bufferOf(locA);
        ByteBuffer bufB = bufferOf(locB);
        int posA = offsetOf(locA);
        int posB = offsetOf(locB);
        for (int i = 0; i < endA; i++) {
            if (bufA.get(posA + i) != bufB.get(posB + i)) {
                return false;
            }
        }
        return true;
    }

    /** Index of the last '/', or 0 when the name has none (the default package). */
    private static int packageEnd(int sym) {
        long loc = locOf(sym);
        ByteBuffer buf = bufferOf(loc);
        int pos = offsetOf(loc);
        for (int i = (int) (loc & MAX_LEN) - 1; i >= 0; i--) {
            if (buf.get(pos + i) == '/') {
                return i;
            }
        }
        return 0;
    }

    /** Unique string count and total string bytes. */
    static long[] stats() {
        int n = tableLen();
        long bytes = 0;
        for (int i = 0; i < n; i++) {
            bytes += length(i);
        }
        return new long[] {n, bytes};
    }

    static int hash(byte[] buf, int off, int len) {
        long h = len * 0x9E3779B97F4A7C15L;
        int i = 0;
        for (; i + 8 <= len; i += 8) {
            long w = (long) LONG_VIEW.get(buf, off + i);
            h = (Long.rotateLeft(h, 5) ^ w) * 0x517CC1B727220A95L;
        }
        long tail = 0;
        for (int shift = 0; i < len; i++, shift += 8) {
            tail |= (buf[off + i] & 0xffL) << shift;
        }
        h = (Long.rotateLeft(h, 5) ^ tail) * 0x517CC1B727220A95L;
        return (int) (h ^ (h >>> 32));
    }
}
