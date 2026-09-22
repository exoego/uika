package net.exoego.uika.cli;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * JVMS 4.4.7 Modified UTF-8 to UTF-8.
 *
 * <p>ASCII (nearly every real name) has the same bytes in both, so it is interned in place.
 * Anything else is accepted as-is when it is already valid UTF-8, and otherwise decoded
 * (the 0xC0 0x80 NUL and surrogate pairs written as two 3-byte sequences).
 *
 * <p>An unpaired surrogate is legal Modified UTF-8, and HotSpot loads and links names holding
 * one, so it keeps its three bytes (generalized UTF-8). {@link Intern#str} shows it as U+FFFD.
 */
final class ModifiedUtf8 {
    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final String INVALID = "could not convert CESU-8 data to UTF-8";

    private ModifiedUtf8() {}

    static boolean isAscii(byte[] b, int off, int len) {
        int i = 0;
        for (; i + 8 <= len; i += 8) {
            if (((long) LONG_VIEW.get(b, off + i) & 0x8080808080808080L) != 0) {
                return false;
            }
        }
        for (; i < len; i++) {
            if (b[off + i] < 0) {
                return false;
            }
        }
        return true;
    }

    static int intern(Scratch scratch, byte[] b, int off, int len) throws ClassParser.FormatException {
        if (isAscii(b, off, len) || isValidUtf8(b, off, len)) {
            return Intern.intern(scratch, b, off, len);
        }
        // Decoding never grows the data: a 6-byte surrogate pair becomes 4 bytes.
        byte[] out = new byte[len];
        int n = decode(b, off, len, out);
        return Intern.intern(scratch, out, 0, n);
    }

    /** The UTF-8 bytes of a Modified UTF-8 constant, for callers that need the text itself. */
    static byte[] toUtf8(byte[] b, int off, int len) throws ClassParser.FormatException {
        if (isAscii(b, off, len) || isValidUtf8(b, off, len)) {
            return java.util.Arrays.copyOfRange(b, off, off + len);
        }
        byte[] out = new byte[len];
        int n = decode(b, off, len, out);
        return java.util.Arrays.copyOf(out, n);
    }

    private static boolean isCont(int x) {
        return (x & 0xC0) == 0x80;
    }

    /** Strict UTF-8: no overlong forms, no surrogates, nothing above U+10FFFF. */
    static boolean isValidUtf8(byte[] b, int off, int len) {
        int i = off;
        int end = off + len;
        while (i < end) {
            int first = b[i] & 0xff;
            if (first < 0x80) {
                i++;
            } else if (first >= 0xC2 && first <= 0xDF) {
                if (i + 1 >= end || !isCont(b[i + 1] & 0xff)) {
                    return false;
                }
                i += 2;
            } else if (first >= 0xE0 && first <= 0xEF) {
                if (i + 2 >= end) {
                    return false;
                }
                int second = b[i + 1] & 0xff;
                boolean ok = switch (first) {
                    case 0xE0 -> second >= 0xA0 && second <= 0xBF;
                    case 0xED -> second >= 0x80 && second <= 0x9F;
                    default -> second >= 0x80 && second <= 0xBF;
                };
                if (!ok || !isCont(b[i + 2] & 0xff)) {
                    return false;
                }
                i += 3;
            } else if (first >= 0xF0 && first <= 0xF4) {
                if (i + 3 >= end) {
                    return false;
                }
                int second = b[i + 1] & 0xff;
                boolean ok = switch (first) {
                    case 0xF0 -> second >= 0x90 && second <= 0xBF;
                    case 0xF4 -> second >= 0x80 && second <= 0x8F;
                    default -> second >= 0x80 && second <= 0xBF;
                };
                if (!ok || !isCont(b[i + 2] & 0xff) || !isCont(b[i + 3] & 0xff)) {
                    return false;
                }
                i += 4;
            } else {
                return false;
            }
        }
        return true;
    }

    private static int decode(byte[] b, int off, int len, byte[] out) throws ClassParser.FormatException {
        int i = off;
        int end = off + len;
        int n = 0;
        while (i < end) {
            int first = b[i++] & 0xff;
            if (first == 0) {
                throw new ClassParser.FormatException(INVALID);
            } else if (first < 0x80) {
                out[n++] = (byte) first;
            } else if (first == 0xC0) {
                if (i >= end || (b[i++] & 0xff) != 0x80) {
                    throw new ClassParser.FormatException(INVALID);
                }
                out[n++] = 0;
            } else {
                int width = first >= 0xC2 && first <= 0xDF ? 2 : first >= 0xE0 && first <= 0xEF ? 3 : 0;
                int second = cont(b, i++, end);
                if (width == 2) {
                    out[n++] = (byte) first;
                    out[n++] = (byte) second;
                } else if (width == 3) {
                    int third = cont(b, i++, end);
                    if (first == 0xE0 && second < 0xA0) {
                        throw new ClassParser.FormatException(INVALID);
                    }
                    if (first == 0xED && (second & 0xF0) == 0xA0 && startsLowSurrogate(b, i, end)) {
                        int fifth = b[i + 1] & 0xff;
                        int sixth = b[i + 2] & 0xff;
                        i += 3;
                        int high = 0xD000 | (second & 0x3F) << 6 | (third & 0x3F);
                        int low = 0xD000 | (fifth & 0x3F) << 6 | (sixth & 0x3F);
                        int c = 0x10000 + (((high - 0xD800) << 10) | (low - 0xDC00));
                        out[n++] = (byte) (0xF0 | (c >> 18));
                        out[n++] = (byte) (0x80 | ((c >> 12) & 0x3F));
                        out[n++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                        out[n++] = (byte) (0x80 | (c & 0x3F));
                    } else {
                        out[n++] = (byte) first;
                        out[n++] = (byte) second;
                        out[n++] = (byte) third;
                    }
                } else {
                    throw new ClassParser.FormatException(INVALID);
                }
            }
        }
        return n;
    }

    private static boolean startsLowSurrogate(byte[] b, int i, int end) {
        return i + 2 < end && (b[i] & 0xff) == 0xED && (b[i + 1] & 0xF0) == 0xB0 && isCont(b[i + 2] & 0xff);
    }

    private static int cont(byte[] b, int i, int end) throws ClassParser.FormatException {
        if (i >= end || !isCont(b[i] & 0xff)) {
            throw new ClassParser.FormatException(INVALID);
        }
        return b[i] & 0xff;
    }
}
