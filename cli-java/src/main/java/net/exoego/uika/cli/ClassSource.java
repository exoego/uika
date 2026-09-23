package net.exoego.uika.cli;

import java.nio.ByteBuffer;

/**
 * The bytes of one class, inflated on demand.
 *
 * <p>Pass 1 decides almost everything from the constant pool and the class header, which sit
 * at the front of the file. Inflate is the wall of the scan, so a class that turns out to
 * hold no reference into the checked library is only inflated that far. One instance is
 * reused per thread.
 */
final class ClassSource {
    /** The entry's deflate stream is corrupt or truncated. Stackless: it is a per-entry warning. */
    static final class DeflateError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DeflateError() {
            super("deflate error", null, false, false);
        }
    }

    /** Valid up to {@link #available}. Replaced when it grows, so re-read it after {@link #ensure}. */
    byte[] bytes = new byte[64 * 1024];
    int available;
    /** Everything the entry holds is in {@link #bytes}. */
    boolean complete;
    /** Uncompressed size the central directory claims; a hint, never trusted as a bound. */
    int expected;

    /** A deflate stream expands at most 1032:1 (a 258-byte match costs at least two bits). */
    static final int MAX_DEFLATE_RATIO = 1032;

    private final Inflate inflate = new Inflate();
    private boolean deflated;

    /** A class already wholly in memory; {@code buffer} becomes {@link #bytes}. */
    void ofBytes(byte[] buffer, int length) {
        bytes = buffer;
        available = length;
        expected = length;
        complete = true;
        deflated = false;
    }

    /** Above this the buffer grows to the request only: every thread keeps its largest, and doubling kept twice the largest class. */
    static final int DOUBLING_LIMIT = 256 * 1024;

    byte[] reserve(int size) {
        if (bytes.length < size) {
            bytes = new byte[grownLength(bytes.length, size)];
        }
        return bytes;
    }

    static int grownLength(int current, int needed) {
        return needed >= DOUBLING_LIMIT ? needed : Math.max(needed, current * 2);
    }

    /**
     * Starts a deflated entry occupying {@code compressed} bytes of {@code input} at
     * {@code dataStart}. Inflates nothing yet. {@code input} must be little-endian and
     * readable {@link Inflate#SLACK} bytes past the entry.
     */
    void ofDeflate(ByteBuffer input, int dataStart, int compressed, long expectedSize) {
        expected = (int) Math.min(expectedSize, 64L * 1024 * 1024);
        // Room for the decoder's overshoot, so the usual entry never grows the buffer. The
        // claim is capped by what the compressed bytes can possibly inflate to, so a corrupt
        // directory cannot make every thread reserve 64 MiB for a class of a few kilobytes.
        long bound = (long) compressed * MAX_DEFLATE_RATIO;
        reserve((int) Math.min(expected, bound) + 320);
        inflate.reset(input, dataStart, dataStart + compressed, bytes);
        available = 0;
        complete = false;
        deflated = true;
    }

    /** Inflates until at least {@code upTo} bytes are available or the stream ends. */
    void ensure(int upTo) {
        if (complete || available >= upTo) {
            return;
        }
        try {
            inflate.inflate(upTo);
        } catch (Inflate.FormatException e) {
            throw new DeflateError();
        }
        bytes = inflate.out;
        available = inflate.outPos;
        complete = inflate.finished();
    }

    void readAll() {
        if (deflated) {
            ensure(Integer.MAX_VALUE);
        }
    }
}
