package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModifiedUtf8Test {
    private static final String INVALID = "could not convert CESU-8 data to UTF-8";

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertDecodes(String expected, byte[] raw) throws ClassParser.FormatException {
        assertArrayEquals(utf8(expected), ModifiedUtf8.toUtf8(raw, 0, raw.length), Arrays.toString(raw));
        assertEquals(Intern.intern(expected), ModifiedUtf8.intern(new Scratch(), raw, 0, raw.length), Arrays.toString(raw));
    }

    private static void assertRejected(int... raw) {
        byte[] b = bytes(raw);
        ClassParser.FormatException e = assertThrows(ClassParser.FormatException.class, () -> ModifiedUtf8.toUtf8(b, 0, b.length));
        assertEquals(INVALID, e.getMessage(), Arrays.toString(raw));
        assertThrows(ClassParser.FormatException.class, () -> ModifiedUtf8.intern(null, b, 0, b.length), Arrays.toString(raw));
    }

    @Test
    void asciiAndValidUtf8AreCopiedAsTheyAre() throws Exception {
        byte[] padded = utf8("..a/B..caf\u00e9/\u65e5\u672c..");
        assertArrayEquals(utf8("a/B"), ModifiedUtf8.toUtf8(padded, 2, 3));
        assertArrayEquals(utf8("caf\u00e9/\u65e5\u672c"), ModifiedUtf8.toUtf8(padded, 7, padded.length - 9));
        assertArrayEquals(new byte[0], ModifiedUtf8.toUtf8(padded, 0, 0));
        assertEquals(Intern.intern("caf\u00e9/\u65e5\u672c"), ModifiedUtf8.intern(null, padded, 7, padded.length - 9));
    }

    /** JVMS 4.4.7: U+0000 is written as C0 80, and a supplementary character as its surrogate pair. */
    @Test
    void decodesTheTwoByteNulAndSurrogatePairs() throws Exception {
        assertDecodes("a\u0000b", bytes('a', 0xC0, 0x80, 'b'));
        assertDecodes(new String(Character.toChars(0x1F600)), bytes(0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80));
        assertDecodes(new String(Character.toChars(0x10000)), bytes(0xED, 0xA0, 0x80, 0xED, 0xB0, 0x80));
        assertDecodes(new String(Character.toChars(0x10FFFF)), bytes(0xED, 0xAF, 0xBF, 0xED, 0xBF, 0xBF));
    }

    /** Once one sequence needs decoding, every other character goes through the decoder too. */
    @Test
    void decodesOrdinaryCharactersNextToOnesThatNeedDecoding() throws Exception {
        byte[] raw = bytes(
                'x',
                0xC3, 0xA9, // U+00E9
                0xC0, 0x80, // U+0000
                0xE6, 0x97, 0xA5, // U+65E5
                0xE0, 0xA0, 0x80, // U+0800, the lowest three-byte character
                0xED, 0x9F, 0xBF, // U+D7FF, just below the surrogates
                0xEF, 0xBF, 0xBF, // U+FFFF
                0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80); // U+1F600
        assertDecodes("x\u00e9\u0000\u65e5\u0800\ud7ff\uffff" + new String(Character.toChars(0x1F600)), raw);
    }

    /** Each input also holds something that is not UTF-8, so that it reaches the decoder. */
    @Test
    void rejectsWhatModifiedUtf8DoesNotAllow() {
        assertRejected(0x00, 0xC0, 0x80); // no byte may be zero
        assertRejected(0xC0); // a cut NUL
        assertRejected(0xC0, 0x41); // C0 only starts the NUL
        assertRejected(0xC1, 0x81); // overlong forms other than the NUL
        assertRejected(0xE0, 0x80, 0x80);
        assertRejected(0x80); // a continuation byte with no lead
        assertRejected(0xC3); // cut sequences
        assertRejected(0xE6, 0x97);
        assertRejected(0xC3, 0x41); // a lead byte without its continuation
        assertRejected(0xE6, 0xC3, 0xA9);
        // The four-byte form is UTF-8 but not Modified UTF-8.
        assertRejected(0xC0, 0x80, 0xF0, 0x9F, 0x98, 0x80);
    }

    private static void assertDecodesTo(int[] expected, int... raw) throws ClassParser.FormatException {
        byte[] b = bytes(raw);
        byte[] want = bytes(expected);
        assertArrayEquals(want, ModifiedUtf8.toUtf8(b, 0, b.length), Arrays.toString(raw));
        assertEquals(Intern.intern(null, want, 0, want.length), ModifiedUtf8.intern(new Scratch(), b, 0, b.length), Arrays.toString(raw));
    }

    private static void assertKept(int... raw) throws ClassParser.FormatException {
        assertDecodesTo(raw, raw);
    }

    /** A lone surrogate is legal Modified UTF-8 (JVMS 4.4.7), and HotSpot loads names holding one. */
    @Test
    void keepsSurrogatesThatDoNotPair() throws Exception {
        assertKept(0xED, 0xA0, 0x80); // U+D800
        assertKept(0xED, 0xB0, 0x80); // U+DC00, a low surrogate first
        assertKept('m', 0xED, 0xAF, 0xBF, 'x'); // U+DBFF followed by a character
        assertKept(0xED, 0xB0, 0x80, 0xED, 0xA0, 0x80); // low then high is no pair
        assertKept(0xED, 0xA0, 0xBD, 0xED, 0xA0, 0x80); // two high surrogates
        assertDecodesTo(
                new int[] {0xED, 0xA0, 0xBD, 0xF0, 0x9F, 0x98, 0x80},
                0xED, 0xA0, 0xBD, 0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80); // a high surrogate, then U+1F600
        assertDecodesTo(
                new int[] {0xF0, 0x9F, 0x98, 0x80, 0xED, 0xB8, 0x80},
                0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80, 0xED, 0xB8, 0x80); // U+1F600, then a low surrogate
        assertDecodesTo(new int[] {'a', 0x00, 0xED, 0xA0, 0x80}, 'a', 0xC0, 0x80, 0xED, 0xA0, 0x80);
    }

    @Test
    void rejectsMalformedBytesNextToALoneSurrogate() {
        assertRejected(0xED, 0xA0); // a surrogate cut short
        assertRejected(0xED, 0xA0, 0xBD, 0xED, 0xB8); // a low surrogate cut short
        assertRejected(0xED, 0xA0, 0xBD, 0xED, 0x41, 0x80);
        assertRejected(0xED, 0xA0, 0xBD, 'x', 0x80, 0x80); // continuation bytes with no lead
    }

    /** RFC 3629 and Unicode Table 3-7: no overlong forms, no surrogates, nothing above U+10FFFF. */
    @Test
    void isValidUtf8AcceptsOnlyWellFormedUtf8() {
        int[][] valid = {
            {}, {'a'}, {0xC2, 0x80}, {0xDF, 0xBF}, {0xE0, 0xA0, 0x80}, {0xE1, 0x80, 0x80}, {0xED, 0x9F, 0xBF}, {0xEF, 0xBF, 0xBF},
            {0xF0, 0x90, 0x80, 0x80}, {0xF3, 0xBF, 0xBF, 0xBF}, {0xF4, 0x8F, 0xBF, 0xBF},
        };
        for (int[] b : valid) {
            assertTrue(ModifiedUtf8.isValidUtf8(bytes(b), 0, b.length), Arrays.toString(b));
        }
        int[][] invalid = {
            {0x80}, {0xC0, 0x80}, {0xC1, 0xBF}, {0xC2}, {0xC2, 0x41}, {0xE0, 0x9F, 0xBF}, {0xE0, 0xC0, 0x80}, {0xE1, 0x80},
            {0xE1, 0x41, 0x80}, {0xE1, 0x80, 0x41}, {0xED, 0xA0, 0x80}, {0xED, 0x41, 0x80}, {0xF0, 0x8F, 0xBF, 0xBF},
            {0xF0, 0xC0, 0x80, 0x80}, {0xF1, 0x80, 0x80}, {0xF1, 0x41, 0x80, 0x80}, {0xF1, 0x80, 0x41, 0x80},
            {0xF1, 0x80, 0x80, 0x41}, {0xF4, 0x90, 0x80, 0x80}, {0xF4, 0x41, 0x80, 0x80}, {0xF5, 0x80, 0x80, 0x80}, {0xFF},
        };
        for (int[] b : invalid) {
            assertFalse(ModifiedUtf8.isValidUtf8(bytes(b), 0, b.length), Arrays.toString(b));
        }
    }

    /**
     * Every one- and two-byte sequence, and each of those followed by bytes on both sides of
     * the continuation range, judged like the JDK's strict decoder.
     */
    @Test
    void isValidUtf8AgreesWithTheJdkDecoder() {
        CharsetDecoder jdk = StandardCharsets.UTF_8.newDecoder();
        CharBuffer out = CharBuffer.allocate(8);
        int[] any = new int[256];
        for (int i = 0; i < 256; i++) {
            any[i] = i;
        }
        int[] tail = {0x41, 0x80, 0xBF, 0xC0};
        int[][] choices = {any, any, tail, tail};
        byte[] b = new byte[4];
        List<String> disagreements = new ArrayList<>();
        for (int len = 1; len <= 4; len++) {
            int[] at = new int[len];
            int k;
            do {
                for (int i = 0; i < len; i++) {
                    b[i] = (byte) choices[i][at[i]];
                }
                jdk.reset();
                out.clear();
                CoderResult result = jdk.decode(ByteBuffer.wrap(b, 0, len), out, true);
                boolean expected = !result.isError() && !jdk.flush(out).isError();
                if (ModifiedUtf8.isValidUtf8(b, 0, len) != expected) {
                    disagreements.add(Arrays.toString(Arrays.copyOf(b, len)));
                }
                k = len - 1;
                while (k >= 0 && ++at[k] == choices[k].length) {
                    at[k--] = 0;
                }
            } while (k >= 0);
        }
        assertEquals(List.of(), disagreements);
    }
}
