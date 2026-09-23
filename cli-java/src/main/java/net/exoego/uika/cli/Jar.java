package net.exoego.uika.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Direct central-directory reader.
 *
 * <p>Covers the plain case only: zip64, encrypted entries, entry names that are not UTF-8 and
 * compression methods other than stored/deflate make {@link #readEntries} answer null, and the
 * caller falls back to {@link java.util.zip.ZipFile}.
 *
 * <p>The directory is streamed through a fixed window rather than read whole, so a JAR with
 * tens of thousands of entries does not pin a multi-megabyte buffer.
 */
final class Jar {
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CD_SIGNATURE = 0x02014b50;
    static final int LOCAL_SIGNATURE = 0x04034b50;
    /** A record is at most 46 + 3 * 65535 bytes, so one always fits. */
    private static final int WINDOW = 256 * 1024;

    /**
     * The scannable entries of one JAR in local-header-offset order, which is the order they
     * are read and therefore the first-wins order inside the JAR. Offsets and sizes are unsigned 32-bit.
     * The columns are pooled: only the first {@link #count} elements of each are meaningful.
     */
    static final class Entries {
        int count;
        /** Interned entry name without the ".class" suffix. */
        int[] name;
        int[] crc;
        int[] offset;
        /** Up to the next entry's local header, or the directory for the last entry. */
        int[] end;
        int[] compressed;
        int[] inflated;
        /** Compression method 0 rather than 8. */
        boolean[] stored;
        /** META-INF/services provider files, in directory order. Null when the JAR has none. */
        List<ServiceEntry> services;

        long offset(int i) {
            return offset[i] & 0xffffffffL;
        }

        long end(int i) {
            return end[i] & 0xffffffffL;
        }

        long compressed(int i) {
            return compressed[i] & 0xffffffffL;
        }

        /** Pooled columns for {@code capacity} rows. */
        private void acquire(int capacity) {
            name = IntPool.acquire(capacity);
            crc = IntPool.acquire(capacity);
            offset = IntPool.acquire(capacity);
            end = IntPool.acquire(capacity);
            compressed = IntPool.acquire(capacity);
            inflated = IntPool.acquire(capacity);
            stored = new boolean[capacity];
        }

        /** Hands the columns back to {@link IntPool}. The entries must not be used afterwards. */
        void release() {
            for (int[] column : new int[][] {name, crc, offset, end, compressed, inflated}) {
                if (column != null) {
                    IntPool.release(column);
                }
            }
            name = crc = offset = end = compressed = inflated = null;
            count = 0;
        }

        /**
         * The same rows in pooled columns sized for {@code count}. Columns are sized by the
         * directory's record count, so a JAR that is mostly resources would otherwise park
         * columns several times its class count in the pool until the chunk is merged.
         */
        private void compact() {
            if (count == 0 || count > name.length / 2) {
                return;
            }
            Entries tight = permuted(null);
            release();
            name = tight.name;
            crc = tight.crc;
            offset = tight.offset;
            end = tight.end;
            compressed = tight.compressed;
            inflated = tight.inflated;
            stored = tight.stored;
            count = tight.count;
        }

        /** A copy of the first {@link #count} rows in new pooled columns, reordered by {@code permutation} when given. */
        private Entries permuted(int[] permutation) {
            Entries out = new Entries();
            out.count = count;
            out.name = pick(name, count, permutation);
            out.crc = pick(crc, count, permutation);
            out.offset = pick(offset, count, permutation);
            out.end = pick(end, count, permutation);
            out.compressed = pick(compressed, count, permutation);
            out.inflated = pick(inflated, count, permutation);
            out.stored = new boolean[count];
            for (int i = 0; i < count; i++) {
                out.stored[i] = stored[permutation == null ? i : permutation[i]];
            }
            out.services = services;
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

        /** Drops every entry whose {@code keep} flag is unset. */
        void retain(boolean[] keep) {
            int n = 0;
            for (int i = 0; i < count; i++) {
                if (keep[i]) {
                    name[n] = name[i];
                    crc[n] = crc[i];
                    offset[n] = offset[i];
                    end[n] = end[i];
                    compressed[n] = compressed[i];
                    inflated[n] = inflated[i];
                    stored[n] = stored[i];
                    n++;
                }
            }
            count = n;
        }
    }

    /**
     * A provider file seen while streaming the directory, so reachability needs no second
     * pass over the archive.
     *
     * @param service the dotted service name (the entry name after META-INF/services/)
     * @param method 0 stored, 8 deflated, anything else is unreadable here
     */
    record ServiceEntry(String service, long offset, long compressed, long inflated, int method) {}

    private static final byte[] SERVICES_PREFIX = "META-INF/services/".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private Jar() {}

    /** Reads exactly {@code length} bytes at {@code offset} into the start of {@code dst}. */
    static boolean readFully(FileChannel channel, ByteBuffer dst, long offset, int length) {
        dst.clear().limit(length);
        try {
            while (dst.hasRemaining()) {
                int n = channel.read(dst, offset + dst.position());
                if (n <= 0) {
                    return false;
                }
            }
        } catch (IOException e) {
            return false;
        }
        dst.clear();
        return true;
    }

    /** Null for zip64 or any structure this reader does not cover. */
    static Entries readEntries(FileChannel channel, Scratch scratch) {
        long size;
        try {
            size = channel.size();
        } catch (IOException e) {
            return null;
        }
        // The EOCD is searched from the end: max comment length plus the record itself.
        int tailLength = (int) Math.min(size, 66_000);
        ByteBuffer tail = BufPool.acquire(Math.max(tailLength, 1));
        ByteBuffer window = null;
        try {
            if (!readFully(channel, tail, size - tailLength, tailLength)) {
                return null;
            }
            int at = -1;
            for (int i = tailLength - 4; i >= 0; i--) {
                if (tail.getInt(i) == EOCD_SIGNATURE) {
                    at = i;
                    break;
                }
            }
            if (at < 0 || tailLength - at < 22) {
                return null;
            }
            int total = tail.getShort(at + 10) & 0xffff;
            long cdSize = tail.getInt(at + 12) & 0xffffffffL;
            long cdOffset = tail.getInt(at + 16) & 0xffffffffL;
            if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL || cdOffset + cdSize > size) {
                return null;
            }
            Cursor cursor = new Cursor(channel, cdOffset, cdOffset + cdSize);
            long tailStart = size - tailLength;
            if (cdOffset >= tailStart) {
                // Small JARs: the directory already sits in the tail, no second read.
                cursor.buffer = tail;
                cursor.bufferStart = tailStart;
                cursor.bufferLength = tailLength;
            } else {
                window = BufPool.acquire(WINDOW);
                cursor.buffer = window;
            }
            Entries entries = parse(cursor, total, cdOffset, scratch, false);
            if (entries == NOT_MONOTONIC) {
                cursor.position = cdOffset;
                entries = parse(cursor, total, cdOffset, scratch, true);
            }
            return entries;
        } finally {
            BufPool.release(tail);
            if (window != null) {
                BufPool.release(window);
            }
        }
    }

    /** Sentinel: records are not in offset order, so the parse must collect every offset. */
    private static final Entries NOT_MONOTONIC = new Entries();

    /** Sliding view over the directory bytes. */
    private static final class Cursor {
        final FileChannel channel;
        final long limit;
        ByteBuffer buffer;
        long bufferStart;
        int bufferLength;
        long position;

        Cursor(FileChannel channel, long start, long limit) {
            this.channel = channel;
            this.position = start;
            this.limit = limit;
        }

        /** Buffer index of {@link #position} with {@code need} bytes readable, or -1. */
        int ensure(int need) {
            if (position + need > limit) {
                return -1;
            }
            if (position < bufferStart || position + need > bufferStart + bufferLength) {
                int length = (int) Math.min(buffer.capacity(), limit - position);
                if (length < need || !readFully(channel, buffer, position, length)) {
                    return -1;
                }
                bufferStart = position;
                bufferLength = length;
            }
            return (int) (position - bufferStart);
        }
    }

    /**
     * The columns are filled in place, sized by the record count the end record declares: an
     * upper bound of the scannable entries, so the parse never grows or copies them. A jar
     * with tens of thousands of entries used to grow a worker's scratch columns by doubling and
     * drop them again after, on every worker, for every such jar.
     */
    private static Entries parse(Cursor cursor, int total, long cdOffset, Scratch scratch, boolean general) {
        Entries c = new Entries();
        c.acquire(total);
        int[] ordinal = general ? IntPool.acquire(total) : null;
        long[] allKeys = general ? new long[total] : null;
        Entries result = null;
        try {
            result = parse(cursor, total, cdOffset, scratch, c, ordinal, allKeys);
            return result;
        } finally {
            if (result != c) {
                c.release();
            }
            if (ordinal != null) {
                IntPool.release(ordinal);
            }
        }
    }

    private static Entries parse(Cursor cursor, int total, long cdOffset, Scratch scratch, Entries c, int[] ordinal, long[] allKeys) {
        boolean general = ordinal != null;
        int n = 0;
        long previousOffset = -1;
        List<ServiceEntry> services = null;
        // In offset order an entry ends where the next record of ANY kind begins.
        boolean pendingEnd = false;
        for (int i = 0; i < total; i++) {
            int p = cursor.ensure(46);
            if (p < 0 || cursor.buffer.getInt(p) != CD_SIGNATURE) {
                return null;
            }
            ByteBuffer b = cursor.buffer;
            int flags = b.getShort(p + 8) & 0xffff;
            int method = b.getShort(p + 10) & 0xffff;
            int crc = b.getInt(p + 16);
            int compressed = b.getInt(p + 20);
            int inflated = b.getInt(p + 24);
            int nameLength = b.getShort(p + 28) & 0xffff;
            int extraLength = b.getShort(p + 30) & 0xffff;
            int commentLength = b.getShort(p + 32) & 0xffff;
            int offset = b.getInt(p + 42);
            // zip64 markers and encrypted entries.
            if (offset == -1 || compressed == -1 || inflated == -1 || (flags & 1) != 0) {
                return null;
            }
            p = cursor.ensure(46 + nameLength);
            if (p < 0) {
                return null;
            }
            byte[] name = scratch.nameBytes(nameLength);
            cursor.buffer.get(p + 46, name, 0, nameLength);
            if (!ModifiedUtf8.isAscii(name, 0, nameLength) && !ModifiedUtf8.isValidUtf8(name, 0, nameLength)) {
                return null;
            }
            long unsignedOffset = offset & 0xffffffffL;
            // A local header at or past the directory cannot bound any span. The fallback
            // reader gets to say what is wrong with the archive.
            if (unsignedOffset >= cdOffset) {
                return null;
            }
            if (general) {
                allKeys[i] = unsignedOffset << 31 | i;
            } else {
                if (unsignedOffset < previousOffset) {
                    return NOT_MONOTONIC;
                }
                previousOffset = unsignedOffset;
                if (pendingEnd) {
                    c.end[n - 1] = offset;
                    pendingEnd = false;
                }
            }
            if (nameLength > SERVICES_PREFIX.length
                    && Arrays.equals(name, 0, SERVICES_PREFIX.length, SERVICES_PREFIX, 0, SERVICES_PREFIX.length)) {
                if (services == null) {
                    services = new ArrayList<>();
                }
                String service = new String(name, SERVICES_PREFIX.length, nameLength - SERVICES_PREFIX.length, java.nio.charset.StandardCharsets.UTF_8);
                services.add(new ServiceEntry(service, unsignedOffset, compressed & 0xffffffffL, inflated & 0xffffffffL, method));
            }
            if (Input.isScannable(name, nameLength)) {
                if (method != 0 && method != 8) {
                    return null;
                }
                c.name[n] = Intern.intern(scratch, name, 0, nameLength - 6);
                c.crc[n] = crc;
                c.offset[n] = offset;
                c.compressed[n] = compressed;
                c.inflated[n] = inflated;
                c.stored[n] = method == 0;
                if (general) {
                    ordinal[n] = i;
                }
                pendingEnd = true;
                n++;
            }
            cursor.position += 46L + nameLength + extraLength + commentLength;
        }
        if (pendingEnd && !general) {
            c.end[n - 1] = (int) cdOffset;
        }
        c.count = n;
        c.services = services;
        if (general) {
            return sortedByOffset(c, ordinal, allKeys, cdOffset);
        }
        c.compact();
        return c;
    }

    /** The rare JAR whose directory is not in offset order: a stable sort by offset. */
    private static Entries sortedByOffset(Entries c, int[] ordinal, long[] keys, long cdOffset) {
        int n = c.count;
        int total = keys.length;
        Arrays.sort(keys);
        // Position of each ordinal in offset order, to find its successor.
        int[] rank = new int[total];
        for (int k = 0; k < total; k++) {
            rank[(int) (keys[k] & 0x7fffffff)] = k;
        }
        long[] order = new long[n];
        for (int j = 0; j < n; j++) {
            int k = rank[ordinal[j]];
            c.end[j] = (int) (k + 1 < total ? keys[k + 1] >>> 31 : cdOffset);
            order[j] = (long) k << 32 | j;
        }
        Arrays.sort(order);
        int[] permutation = new int[n];
        for (int j = 0; j < n; j++) {
            permutation[j] = (int) order[j];
        }
        return c.permuted(permutation);
    }
}
