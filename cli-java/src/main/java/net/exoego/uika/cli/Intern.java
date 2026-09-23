package net.exoego.uika.cli;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * <p>A string is stored as its prefix through the last '/' and its tail after it, the prefix
 * being another symbol: the classes of a package share one copy of the package. On the
 * 2,800-jar stress workload the 488K class names were 30MB stored whole and are 13MB this
 * way, tails plus a few thousand package strings. A string ending in '/' (a prefix itself)
 * or holding none is stored whole, with no prefix, so the same bytes always intern to the
 * same symbol whichever way they arrive. Equality and hashing are over the whole string.
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
    /**
     * Per id, two longs: the location, then the hash in the high half and the prefix symbol + 1
     * (0 for a string stored whole) in the low half. One record, so a lookup reads one cache
     * line for all three; as three parallel columns it read three.
     */
    private static volatile LongBuffer[] metaChunks = new LongBuffer[8];
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
        return internHashed(cache, buf, off, len, hash, false);
    }

    /** @param whole store the string whole rather than split at its last '/': how a prefix is stored */
    private static int internHashed(Scratch cache, byte[] buf, int off, int len, int hash, boolean whole) {
        if (cache != null) {
            int slot = hash & Scratch.INTERN_CACHE_MASK;
            int cached = cache.internCacheSym[slot];
            if (cached != 0 && cache.internCacheHash[slot] == hash && equalsBytes(cached - 1, buf, off, len)) {
                return cached - 1;
            }
            int sym = internShared(cache, buf, off, len, hash, whole);
            cache.internCacheSym[slot] = sym + 1;
            cache.internCacheHash[slot] = hash;
            return sym;
        }
        return internShared(null, buf, off, len, hash, whole);
    }

    private static int internShared(Scratch cache, byte[] buf, int off, int len, int hash, boolean whole) {
        Shard shard = SHARD[(hash >>> 26) & (SHARDS - 1)];
        int found = probe(shard.table, buf, off, len, hash);
        if (found != NONE) {
            return found;
        }
        // A new string: its prefix is interned first, outside this shard's lock. The prefix
        // lives in another shard, and two threads taking two shard locks in opposite orders
        // would deadlock.
        int split = whole ? 0 : prefixLength(buf, off, len);
        int prefix = split == 0 ? NONE : internHashed(cache, buf, off, split, hash(buf, off, split), true);
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
            int sym = store(cache, buf, off + split, len - split, hash, prefix);
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

    /**
     * Bytes through the last '/', or 0 when there is none or it is the last byte. A string
     * ending in '/' is stored whole however it arrives, so a prefix never has a prefix of its
     * own and the accessors read one level, never a chain.
     */
    private static int prefixLength(byte[] buf, int off, int len) {
        for (int i = off + len - 2; i >= off; i--) {
            if (buf[i] == '/') {
                return buf[off + len - 1] == '/' ? 0 : i + 1 - off;
            }
        }
        return 0;
    }

    /** Stores the tail bytes of a new string; the caller has already interned the prefix. */
    private static int store(Scratch cache, byte[] buf, int off, int len, int hash, int prefix) {
        if (len > MAX_LEN) {
            throw new IllegalArgumentException("string too long to intern: " + len + " bytes");
        }
        long loc;
        if (len > STR_CHUNK) {
            loc = storeBig(buf, off, len);
        } else {
            long pos;
            if (cache != null && len <= SLAB) {
                // The null check covers the empty string, which fits any slab, even none.
                if (cache.internSlabChunk == null || cache.internSlabEnd - cache.internSlabPos < len) {
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
        LongBuffer[] metas = metaChunks;
        if (idChunk >= metas.length || metas[idChunk] == null) {
            growIds(idChunk);
            metas = metaChunks;
        }
        LongBuffer meta = metas[idChunk];
        int at = (id & ID_MASK) * 2;
        meta.put(at, loc);
        meta.put(at + 1, (long) hash << 32 | ((prefix + 1) & 0xffffffffL));
        return id;
    }

    /** A range of {@code len} arena bytes inside one chunk; the skipped tail of a chunk is never used. */
    private static long claim(int len) {
        return STR_TOP.accumulateAndGet(len, Intern::advance) - len;
    }

    /** The arena top after a claim of {@code len} bytes at {@code top}, skipping a chunk's tail the claim would straddle. */
    private static long advance(long top, long len) {
        long pos = top;
        if ((pos & STR_MASK) + len > STR_CHUNK) {
            pos = (pos + STR_CHUNK) & ~(long) STR_MASK;
        }
        return pos + len;
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
            LongBuffer[] metas = metaChunks;
            if (idChunk < metas.length && metas[idChunk] != null) {
                return;
            }
            int n = Math.max(metas.length, idChunk + 1);
            LongBuffer[] bigger = java.util.Arrays.copyOf(metas, n);
            bigger[idChunk] = ByteBuffer.allocateDirect(ID_CHUNK * 2 * Long.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asLongBuffer();
            metaChunks = bigger;
        }
    }

    private static long locOf(int sym) {
        return metaChunks[sym >>> ID_BITS].get((sym & ID_MASK) * 2);
    }

    static int hashOf(int sym) {
        return (int) (metaChunks[sym >>> ID_BITS].get((sym & ID_MASK) * 2 + 1) >>> 32);
    }

    /** The symbol of the string's prefix through its last '/', or {@link #NONE} when stored whole. */
    private static int prefixOf(int sym) {
        return (int) metaChunks[sym >>> ID_BITS].get((sym & ID_MASK) * 2 + 1) - 1;
    }

    /** Length of the whole string. A prefix is stored whole, so its stored length is its length. */
    static int length(int sym) {
        int prefix = prefixOf(sym);
        return (int) (locOf(sym) & MAX_LEN) + (prefix == NONE ? 0 : (int) (locOf(prefix) & MAX_LEN));
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

    /**
     * Whether the symbol's whole string equals the bytes. The tail is compared first: it is
     * what tells the classes of one package apart, so a mismatch is found before the shared
     * prefix is looked at.
     */
    static boolean equalsBytes(int sym, byte[] buf, int off, int len) {
        long loc = locOf(sym);
        int tailLen = (int) (loc & MAX_LEN);
        int prefix = prefixOf(sym);
        if (prefix == NONE) {
            return tailLen == len && storedEquals(loc, buf, off, len);
        }
        long prefixLoc = locOf(prefix);
        int prefixLen = (int) (prefixLoc & MAX_LEN);
        return prefixLen + tailLen == len
                && storedEquals(loc, buf, off + prefixLen, tailLen)
                && storedEquals(prefixLoc, buf, off, prefixLen);
    }

    /** Whether the {@code len} bytes stored at {@code loc} equal the bytes; the lengths must already agree. */
    private static boolean storedEquals(long loc, byte[] buf, int off, int len) {
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
        int prefix = prefixOf(sym);
        int at = dstOff;
        if (prefix != NONE) {
            long prefixLoc = locOf(prefix);
            int prefixLen = (int) (prefixLoc & MAX_LEN);
            bufferOf(prefixLoc).get(offsetOf(prefixLoc), dst, at, prefixLen);
            at += prefixLen;
        }
        long loc = locOf(sym);
        int len = (int) (loc & MAX_LEN);
        bufferOf(loc).get(offsetOf(loc), dst, at, len);
        return at + len - dstOff;
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
     * order. This is the only ordering output may use. Off the scan path, so the two strings
     * are simply assembled; a prefix-then-tail comparison would misorder a name whose
     * package extends the other's.
     */
    static int compare(int a, int b) {
        if (a == b) {
            return 0;
        }
        return java.util.Arrays.compareUnsigned(bytes(a), bytes(b));
    }

    /** Whether the symbol starts with the given ASCII prefix. */
    static boolean startsWith(int sym, String asciiPrefix) {
        int n = asciiPrefix.length();
        if (length(sym) < n) {
            return false;
        }
        byte[] bytes = bytes(sym);
        for (int i = 0; i < n; i++) {
            if (bytes[i] != (byte) asciiPrefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether two internal class names share a package (the text before the last '/'). The
     * package is the prefix symbol, so this is one compare; two default-package names share
     * the absent one.
     */
    static boolean samePackage(int a, int b) {
        return prefixOf(a) == prefixOf(b);
    }

    /** Unique string count and total string bytes as stored: tails plus prefixes once. */
    static long[] stats() {
        int n = tableLen();
        long bytes = 0;
        for (int i = 0; i < n; i++) {
            bytes += locOf(i) & MAX_LEN;
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
