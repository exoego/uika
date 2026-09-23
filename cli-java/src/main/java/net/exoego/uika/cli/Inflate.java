package net.exoego.uika.cli;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Raw DEFLATE (RFC 1951) decoder for JAR entries.
 *
 * <p>Inflate is the wall of the scan. The JDK's Inflater is a JNI wrapper around its bundled
 * zlib, which is plain C with no SIMD and is not the system zlib even where one exists. This
 * decoder keeps a 64-bit bit buffer refilled eight bytes at a time, resolves a symbol with one
 * table lookup, and copies matches a word at a time. Measured on 60,000 real class entries
 * (M4 Pro, one thread): 450 MB/s against the JDK's 350, byte-identical output. It also makes
 * scan speed independent of whichever zlib a JDK vendor bundled.
 *
 * <p>Resumable: {@link #inflate} stops once the output reaches a target, because pass 1
 * usually needs only the front of a class file.
 *
 * <p>The input buffer must be little-endian and readable {@link #SLACK} bytes past
 * {@code inEnd}: the bit buffer is refilled a word at a time without an end check, and may
 * run that far ahead of what a valid stream consumes.
 */
final class Inflate {
    /** The stream is corrupt or truncated. Stackless: a per-entry warning, never a crash. */
    static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message, null, false, false);
        }
    }

    /** Word access to the OUTPUT array, for match copies. The input is read through the buffer. */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Readable bytes required after the end of the input. */
    static final int SLACK = 16;

    private static final int LITLEN_BITS = 10;
    private static final int DIST_BITS = 8;
    private static final int PRECODE_BITS = 7;
    // Worst-case table sizes (primary + all subtables) for 15-bit codes, as zlib's "enough" computes them.
    private static final int LITLEN_ENOUGH = 2600;
    private static final int DIST_ENOUGH = 512;

    // Entry layout. Low byte: bits to consume. Bits 8-12: extra bit count. Bits 16-31: payload.
    private static final int LITERAL = 1 << 15;
    private static final int END_OF_BLOCK = 1 << 14;
    private static final int SUBTABLE = 1 << 13;
    private static final int INVALID = 0;

    private static final int[] LENGTH_BASE = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
    };
    private static final int[] LENGTH_EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0};
    private static final int[] DIST_BASE = {
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289,
        16385, 24577
    };
    private static final int[] DIST_EXTRA = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13};
    private static final int[] PRECODE_ORDER = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};

    private static final int[] FIXED_LITLEN = new int[LITLEN_ENOUGH];
    private static final int[] FIXED_DIST = new int[DIST_ENOUGH];

    static {
        byte[] lens = new byte[288 + 32];
        Arrays.fill(lens, 0, 144, (byte) 8);
        Arrays.fill(lens, 144, 256, (byte) 9);
        Arrays.fill(lens, 256, 280, (byte) 7);
        Arrays.fill(lens, 280, 288, (byte) 8);
        Arrays.fill(lens, 288, 320, (byte) 5);
        Inflate builder = new Inflate();
        try {
            builder.build(lens, 0, 288, FIXED_LITLEN, LITLEN_BITS, true);
            builder.build(lens, 288, 32, FIXED_DIST, DIST_BITS, false);
        } catch (FormatException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int[] litlenTable = new int[LITLEN_ENOUGH];
    private final int[] distTable = new int[DIST_ENOUGH];
    private final int[] precodeTable = new int[1 << PRECODE_BITS];
    private final byte[] lens = new byte[288 + 32 + 138];
    private final int[] count = new int[16];
    private final int[] offset = new int[16];
    private final short[] sorted = new short[288];

    // Stream state, so a call can stop mid-block and the next one resume.
    private ByteBuffer in;
    private int inPos;
    private int inEnd;
    private long bitbuf;
    private int bitcnt;
    private boolean lastBlock;
    private boolean finished;
    /** 0 = between blocks, 1 = inside a Huffman block, 2 = inside a stored block. */
    private int blockState;
    private int storedLeft;
    private int[] litlen;
    private int[] dist;

    /** Output so far. The array may be replaced when it grows, so re-read {@link #out} after a call. */
    byte[] out;
    int outPos;

    /** Starts a stream over {@code input[start, end)}, inflating into {@code output} from index 0. */
    void reset(ByteBuffer input, int start, int end, byte[] output) {
        in = input;
        inPos = start;
        inEnd = end;
        bitbuf = 0;
        bitcnt = 0;
        lastBlock = false;
        finished = false;
        blockState = 0;
        out = output;
        outPos = 0;
    }

    boolean finished() {
        return finished;
    }

    /** Inflates until at least {@code target} bytes are out or the stream ends. May overshoot by one match. */
    void inflate(int target) throws FormatException {
        try {
            while (!finished && outPos < target) {
                if (blockState == 0) {
                    beginBlock();
                } else if (blockState == 2) {
                    copyStored(target);
                } else {
                    decodeHuffman(target);
                }
            }
        } catch (IndexOutOfBoundsException e) {
            // A refill ran past the buffer: only a stream that lost its end gets there.
            throw new FormatException("unexpected end of deflate stream");
        }
    }

    // ---- bit reader ----

    /** Ensures at least 56 valid bits, assuming 8 readable bytes at inPos. */
    private void refill() {
        bitbuf |= in.getLong(inPos) << bitcnt;
        inPos += (63 - bitcnt) >>> 3;
        bitcnt |= 56;
    }

    private int bits(int n) {
        int value = (int) (bitbuf & ((1L << n) - 1));
        bitbuf >>>= n;
        bitcnt -= n;
        return value;
    }

    /** Bytes consumed beyond the real input mean the stream ran off its end. */
    private void checkOverrun() throws FormatException {
        if (inPos - (bitcnt >>> 3) > inEnd) {
            throw new FormatException("unexpected end of deflate stream");
        }
    }

    // ---- blocks ----

    private void beginBlock() throws FormatException {
        if (lastBlock) {
            checkOverrun();
            finished = true;
            return;
        }
        refill();
        lastBlock = bits(1) == 1;
        int type = bits(2);
        checkOverrun();
        switch (type) {
            case 0 -> {
                // Stored: skip to the byte boundary, then LEN and NLEN.
                bits(bitcnt & 7);
                int len = bits(16);
                int nlen = bits(16);
                if ((len ^ 0xffff) != nlen) {
                    throw new FormatException("invalid stored block lengths");
                }
                // Return the whole bytes still buffered to the input.
                inPos -= bitcnt >>> 3;
                bitbuf = 0;
                bitcnt = 0;
                storedLeft = len;
                blockState = 2;
            }
            case 1 -> {
                litlen = FIXED_LITLEN;
                dist = FIXED_DIST;
                blockState = 1;
            }
            case 2 -> {
                readDynamicTables();
                litlen = litlenTable;
                dist = distTable;
                blockState = 1;
            }
            default -> throw new FormatException("invalid block type");
        }
    }

    private void copyStored(int target) throws FormatException {
        int n = (int) Math.min(storedLeft, (long) target - outPos + 64);
        if (inPos + n > inEnd) {
            throw new FormatException("unexpected end of deflate stream");
        }
        ensureOut(n);
        in.get(inPos, out, outPos, n);
        inPos += n;
        outPos += n;
        storedLeft -= n;
        if (storedLeft == 0) {
            blockState = 0;
        }
    }

    private void readDynamicTables() throws FormatException {
        int hlit = bits(5) + 257;
        int hdist = bits(5) + 1;
        int hclen = bits(4) + 4;
        if (hlit > 286 || hdist > 30) {
            throw new FormatException("too many length or distance symbols");
        }
        byte[] l = lens;
        int precodeAt = 320;
        Arrays.fill(l, precodeAt, precodeAt + 19, (byte) 0);
        refill();
        for (int i = 0; i < hclen; i++) {
            if (bitcnt < 3) {
                refill();
            }
            l[precodeAt + PRECODE_ORDER[i]] = (byte) bits(3);
        }
        build(l, precodeAt, 19, precodeTable, PRECODE_BITS, false);

        int total = hlit + hdist;
        int i = 0;
        // A code-length symbol needs at most 7 + 7 bits, so the refill is conditional, and the
        // overrun check waits for the end of this bounded loop: reading past the entry only
        // reaches the span's slack or the next entry, and the buffer bounds catch the rest.
        while (i < total) {
            if (bitcnt < 14) {
                refill();
            }
            int entry = precodeTable[(int) (bitbuf & ((1 << PRECODE_BITS) - 1))];
            if (entry == INVALID) {
                throw new FormatException("invalid code lengths set");
            }
            bits(entry & 0xff);
            int symbol = entry >>> 16;
            if (symbol < 16) {
                l[i++] = (byte) symbol;
                continue;
            }
            int repeat;
            byte value = 0;
            if (symbol == 16) {
                if (i == 0) {
                    throw new FormatException("invalid bit length repeat");
                }
                value = l[i - 1];
                repeat = 3 + bits(2);
            } else if (symbol == 17) {
                repeat = 3 + bits(3);
            } else {
                repeat = 11 + bits(7);
            }
            if (i + repeat > total) {
                throw new FormatException("invalid bit length repeat");
            }
            Arrays.fill(l, i, i + repeat, value);
            i += repeat;
        }
        checkOverrun();
        if (l[256] == 0) {
            throw new FormatException("invalid code -- missing end-of-block");
        }
        // The distance lengths follow the literal/length ones in the same run.
        System.arraycopy(l, hlit, l, 288, hdist);
        Arrays.fill(l, 288 + hdist, 320, (byte) 0);
        Arrays.fill(l, hlit, 288, (byte) 0);
        build(l, 0, 288, litlenTable, LITLEN_BITS, true);
        build(l, 288, 32, distTable, DIST_BITS, false);
    }

    /**
     * Builds a decode table from canonical code lengths. A code no longer than
     * {@code primaryBits} is replicated across the primary table, so a lookup is one index. A
     * longer one goes through a subtable reached from its primary prefix.
     *
     * <p>The primary table is filled by doubling, not by one strided write per slot: after the
     * codes of length {@code len} are written once each, the first {@code 2^len} slots are the
     * period of everything so far, so the next half is a block copy. Nearly every class file is
     * one dynamic block, so this build runs once per class and used to cost as much as the
     * decode that followed it.
     */
    private void build(byte[] lengths, int at, int symbols, int[] table, int primaryBits, boolean litlenKind) throws FormatException {
        int[] cnt = count;
        Arrays.fill(cnt, 0);
        for (int s = 0; s < symbols; s++) {
            cnt[lengths[at + s]]++;
        }
        int used = symbols - cnt[0];
        int maxLen = 15;
        while (maxLen > 0 && cnt[maxLen] == 0) {
            maxLen--;
        }
        int primarySize = 1 << primaryBits;
        if (used == 0) {
            // No codes at all. Legal for distances (a block of literals only); any use is an error.
            Arrays.fill(table, 0, primarySize, INVALID);
            return;
        }
        // Kraft sum: an over-subscribed set is corrupt, an incomplete one is only legal with a single code.
        int left = 1;
        for (int len = 1; len <= 15; len++) {
            left = (left << 1) - cnt[len];
            if (left < 0) {
                throw new FormatException("over-subscribed code");
            }
        }
        // zlib's rule: an incomplete set is legal only as a single one-bit code, and never for the precode.
        if (left > 0 && (table == precodeTable || maxLen != 1)) {
            throw new FormatException("incomplete code");
        }
        int[] offs = offset;
        offs[1] = 0;
        for (int len = 1; len < 15; len++) {
            offs[len + 1] = offs[len] + cnt[len];
        }
        short[] order = sorted;
        for (int s = 0; s < symbols; s++) {
            int len = lengths[at + s];
            if (len != 0) {
                order[offs[len]++] = (short) s;
            }
        }
        if (left > 0) {
            // Only an incomplete code leaves slots unassigned.
            Arrays.fill(table, 0, primarySize, INVALID);
        }

        int code = 0;
        int index = 0;
        int next = primarySize;
        int subPrefix = -1;
        int subStart = 0;
        int subBits = 0;
        // Slots [0, region) hold every code written so far, replicated to that length.
        int region = 1;
        boolean precode = table == precodeTable;
        for (int len = 1; len <= maxLen; len++) {
            int n = cnt[len];
            if (len <= primaryBits) {
                System.arraycopy(table, 0, table, region, region);
                region <<= 1;
                for (int k = 0; k < n; k++, index++) {
                    int symbol = order[index];
                    table[Integer.reverse(code) >>> (32 - len)] = entryFor(symbol, litlenKind, precode) | len;
                    code++;
                }
                code <<= 1;
                continue;
            }
            for (int k = 0; k < n; k++, index++) {
                int symbol = order[index];
                int payload = entryFor(symbol, litlenKind, precode);
                int reversed = Integer.reverse(code) >>> (32 - len);
                int prefix = reversed & (primarySize - 1);
                if (prefix != subPrefix) {
                    // Codes arrive in canonical order, so every code sharing this prefix
                    // follows contiguously. Size the subtable for the longest of them.
                    subPrefix = prefix;
                    subStart = next;
                    subBits = len - primaryBits;
                    int remaining = (1 << subBits) - (n - k);
                    int l2 = len;
                    while (remaining > 0 && l2 < maxLen) {
                        l2++;
                        subBits++;
                        remaining = (remaining << 1) - cnt[l2];
                    }
                    next += 1 << subBits;
                    if (next > table.length) {
                        throw new FormatException("code table overflow");
                    }
                    table[prefix] = SUBTABLE | subStart << 16 | subBits << 8 | primaryBits;
                }
                int entry = payload | (len - primaryBits);
                int step = 1 << (len - primaryBits);
                for (int slot = reversed >>> primaryBits; slot < (1 << subBits); slot += step) {
                    table[subStart + slot] = entry;
                }
                code++;
            }
            code <<= 1;
        }
        // Codes shorter than the primary width leave the doubling unfinished.
        while (region < primarySize) {
            System.arraycopy(table, 0, table, region, region);
            region <<= 1;
        }
    }

    private static int entryFor(int symbol, boolean litlenKind, boolean precode) {
        if (precode) {
            return symbol << 16;
        }
        if (!litlenKind) {
            return symbol < 30 ? DIST_BASE[symbol] << 16 | DIST_EXTRA[symbol] << 8 : INVALID_PAYLOAD;
        }
        if (symbol < 256) {
            return LITERAL | symbol << 16;
        } else if (symbol == 256) {
            return END_OF_BLOCK;
        } else if (symbol < 286) {
            return LENGTH_BASE[symbol - 257] << 16 | LENGTH_EXTRA[symbol - 257] << 8;
        }
        return INVALID_PAYLOAD;
    }

    /** A symbol the format reserves (litlen 286-287, dist 30-31): decodes, then fails on use. */
    private static final int INVALID_PAYLOAD = 0x1f << 8;

    // ---- the hot loop ----

    private void ensureOut(int extra) {
        if (outPos + extra + 16 > out.length) {
            out = Arrays.copyOf(out, ClassSource.grownLength(out.length, outPos + extra + 16));
        }
    }

    private void decodeHuffman(int target) throws FormatException {
        final int[] lit = litlen;
        final int[] dst = dist;
        final ByteBuffer input = in;
        long bb = bitbuf;
        int bc = bitcnt;
        int ip = inPos;
        int op = outPos;
        byte[] o = out;
        // A refill reads 8 bytes, so the loop never starts one closer than that to the end.
        final int inLimit = inEnd;
        try {
            while (op < target) {
                if (op + 274 > o.length) {
                    outPos = op;
                    ensureOut(274);
                    o = out;
                }
                bb |= input.getLong(ip) << bc;
                ip += (63 - bc) >>> 3;
                bc |= 56;

                int e = lit[(int) bb & ((1 << LITLEN_BITS) - 1)];
                if ((e & LITERAL) != 0) {
                    bb >>>= e & 0xff;
                    bc -= e & 0xff;
                    o[op++] = (byte) (e >>> 16);
                    e = lit[(int) bb & ((1 << LITLEN_BITS) - 1)];
                    if ((e & LITERAL) != 0) {
                        bb >>>= e & 0xff;
                        bc -= e & 0xff;
                        o[op++] = (byte) (e >>> 16);
                        e = lit[(int) bb & ((1 << LITLEN_BITS) - 1)];
                        if ((e & LITERAL) != 0) {
                            bb >>>= e & 0xff;
                            bc -= e & 0xff;
                            o[op++] = (byte) (e >>> 16);
                            continue;
                        }
                    }
                    bb |= input.getLong(ip) << bc;
                    ip += (63 - bc) >>> 3;
                    bc |= 56;
                }
                if ((e & SUBTABLE) != 0) {
                    bb >>>= e & 0xff;
                    bc -= e & 0xff;
                    e = lit[(e >>> 16) + ((int) bb & ((1 << ((e >>> 8) & 0xf)) - 1))];
                    if ((e & LITERAL) != 0) {
                        bb >>>= e & 0xff;
                        bc -= e & 0xff;
                        o[op++] = (byte) (e >>> 16);
                        continue;
                    }
                }
                if ((e & END_OF_BLOCK) != 0) {
                    bb >>>= e & 0xff;
                    bc -= e & 0xff;
                    blockState = 0;
                    break;
                }
                if (e == INVALID) {
                    throw new FormatException("invalid literal/length code");
                }
                int codeBits = e & 0xff;
                int extra = (e >>> 8) & 0x1f;
                if (extra == 0x1f) {
                    throw new FormatException("invalid literal/length code");
                }
                bb >>>= codeBits;
                int length = (e >>> 16) + ((int) bb & ((1 << extra) - 1));
                bb >>>= extra;
                bc -= codeBits + extra;

                int d = dst[(int) bb & ((1 << DIST_BITS) - 1)];
                if ((d & SUBTABLE) != 0) {
                    bb >>>= d & 0xff;
                    bc -= d & 0xff;
                    d = dst[(d >>> 16) + ((int) bb & ((1 << ((d >>> 8) & 0xf)) - 1))];
                }
                if (d == INVALID) {
                    throw new FormatException("invalid distance code");
                }
                codeBits = d & 0xff;
                extra = (d >>> 8) & 0x1f;
                if (extra == 0x1f) {
                    throw new FormatException("invalid distance code");
                }
                bb >>>= codeBits;
                int distance = (d >>> 16) + ((int) bb & ((1 << extra) - 1));
                bb >>>= extra;
                bc -= codeBits + extra;

                int from = op - distance;
                if (from < 0) {
                    throw new FormatException("invalid distance too far back");
                }
                int end = op + length;
                if (distance >= 8) {
                    // Whole words, possibly past `end`: the slack reserved above absorbs it
                    // and the next symbol overwrites it. Two words up front, since most
                    // matches fit and a loop branch per word mispredicts.
                    LONG_LE.set(o, op, (long) LONG_LE.get(o, from));
                    LONG_LE.set(o, op + 8, (long) LONG_LE.get(o, from + 8));
                    if (length > 16) {
                        op += 16;
                        from += 16;
                        do {
                            LONG_LE.set(o, op, (long) LONG_LE.get(o, from));
                            op += 8;
                            from += 8;
                        } while (op < end);
                    }
                } else if (distance == 1) {
                    long pattern = (o[from] & 0xffL) * 0x0101010101010101L;
                    do {
                        LONG_LE.set(o, op, pattern);
                        op += 8;
                    } while (op < end);
                } else {
                    do {
                        o[op++] = o[from++];
                    } while (op < end);
                }
                op = end;
                if (ip > inLimit + 8) {
                    throw new FormatException("unexpected end of deflate stream");
                }
            }
        } catch (IndexOutOfBoundsException e) {
            throw new FormatException("unexpected end of deflate stream");
        } finally {
            bitbuf = bb;
            bitcnt = bc;
            inPos = ip;
            outPos = op;
        }
        if (inPos - (bitcnt >>> 3) > inEnd) {
            throw new FormatException("unexpected end of deflate stream");
        }
    }
}
