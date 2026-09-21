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

    byte[] reserve(int size) {
        if (bytes.length < size) {
            bytes = new byte[Math.max(size, bytes.length * 2)];
        }
        return bytes;
    }

    /**
     * Starts a deflated entry occupying {@code compressed} bytes of {@code input} at
     * {@code dataStart}. Inflates nothing yet. {@code input} must be little-endian and
     * readable {@link Inflate#SLACK} bytes past the entry.
     */
    void ofDeflate(ByteBuffer input, int dataStart, int compressed, long expectedSize) {
        expected = (int) Math.min(expectedSize, 64L * 1024 * 1024);
        // Room for the decoder's overshoot, so the usual entry never grows the buffer.
        reserve(expected + 320);
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
