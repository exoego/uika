package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ports the `intern.rs` tests, plus the byte-level API only the Java pool has. */
class InternTest {
    /** Same value as the Rust CHUNK_SIZE and the private Intern.STR_CHUNK. */
    private static final int CHUNK_SIZE = 256 * 1024;

    @Test
    void interningDedupesAndRoundtrips() {
        int a = Intern.intern("hello/World");
        int b = Intern.intern("hello/World");
        assertEquals(a, b);
        assertEquals("hello/World", Intern.str(a));
        assertNotEquals(a, Intern.intern("other"));
    }

    @Test
    void survivesChunkRollover() {
        // Existing references must survive across chunk boundaries.
        int first = Intern.intern("rollover-first");
        String big = "x".repeat(CHUNK_SIZE + 1);
        int huge = Intern.intern(big);
        assertEquals("rollover-first", Intern.str(first));
        assertEquals(CHUNK_SIZE + 1, Intern.str(huge).length());
        assertEquals(huge, Intern.intern(big));

        // A string longer than a chunk is stored apart, so the rollover itself needs strings
        // that fit in one chunk but not in what is left of the current one.
        List<String> texts = new ArrayList<>();
        List<Integer> syms = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String text = ("rollover-" + i + "-").repeat(10_000);
            texts.add(text);
            syms.add(Intern.intern(text));
        }
        assertEquals("rollover-first", Intern.str(first));
        for (int i = 0; i < texts.size(); i++) {
            assertEquals(texts.get(i), Intern.str(syms.get(i)));
            assertEquals(syms.get(i), Intern.intern(texts.get(i)));
        }
    }

    @Test
    void internsRawBytesToTheSameSymbolAsTheString() {
        byte[] buf = "..net/exoego/Raw..".getBytes(StandardCharsets.UTF_8);
        int fromBytes = Intern.intern(null, buf, 2, buf.length - 4);
        assertEquals(Intern.intern("net/exoego/Raw"), fromBytes);
        assertEquals(fromBytes, Intern.find("net/exoego/Raw"));
        assertEquals(Intern.NONE, Intern.find("net/exoego/NeverInterned-" + System.nanoTime()));
        assertEquals("net/exoego/Raw".length(), Intern.length(fromBytes));
        assertTrue(Intern.equalsBytes(fromBytes, buf, 2, buf.length - 4));
        assertFalse(Intern.equalsBytes(fromBytes, buf, 1, buf.length - 4));
        assertFalse(Intern.equalsBytes(fromBytes, buf, 2, buf.length - 5));
    }

    @Test
    void aThreadCacheAnswersTheSameSymbols() {
        Scratch scratch = new Scratch();
        byte[] name = "net/exoego/Cached".getBytes(StandardCharsets.UTF_8);
        int first = Intern.intern(scratch, name, 0, name.length);
        assertEquals(first, Intern.intern(scratch, name, 0, name.length));
        assertEquals(first, Intern.intern("net/exoego/Cached"));
        // Two names forced into one cache slot must not answer each other's symbol.
        byte[] other = "net/exoego/Other".getBytes(StandardCharsets.UTF_8);
        int hash = Intern.hash(name, 0, name.length);
        int otherSym = Intern.internHashed(scratch, other, 0, other.length, hash);
        assertNotEquals(first, otherSym);
        assertEquals("net/exoego/Other", Intern.str(otherSym));
        assertEquals(first, Intern.internHashed(scratch, name, 0, name.length, hash));
    }

    /** Output order is Rust `&str` order, which is UTF-8 byte order, never symbol id order. */
    @Test
    void comparesByUtf8BytesNotBySymbolId() {
        int later = Intern.intern("order/zzz");
        int earlier = Intern.intern("order/aaa");
        assertTrue(Intern.compare(earlier, later) < 0);
        assertTrue(Intern.compare(later, earlier) > 0);
        assertEquals(0, Intern.compare(later, later));
        assertTrue(Intern.compare(Intern.intern("order/a"), Intern.intern("order/aa")) < 0);
        // '$' (0x24) sorts before '/' (0x2F), and both before letters.
        assertTrue(Intern.compare(Intern.intern("order/A$B"), Intern.intern("order/A/B")) < 0);
        // U+FFFD is three bytes starting 0xEF, U+1F600 four bytes starting 0xF0. UTF-16 order
        // would put the surrogate pair (0xD83D) first.
        String supplementary = "order/" + new String(Character.toChars(0x1F600));
        assertTrue(Intern.compare(Intern.intern("order/\ufffd"), Intern.intern(supplementary)) < 0);
        assertTrue("order/\ufffd".compareTo(supplementary) > 0);
    }

    @Test
    void prefixAndPackageQueries() {
        int name = Intern.intern("java/util/List");
        assertTrue(Intern.startsWith(name, "java/"));
        assertTrue(Intern.startsWith(name, "java/util/List"));
        assertFalse(Intern.startsWith(name, "java/util/List2"));
        assertFalse(Intern.startsWith(name, "javax/"));
        assertTrue(Intern.samePackage(name, Intern.intern("java/util/Map")));
        assertFalse(Intern.samePackage(name, Intern.intern("java/util/concurrent/Future")));
        assertFalse(Intern.samePackage(name, Intern.intern("java/lang/List")));
        assertTrue(Intern.samePackage(Intern.intern("TopLevelA"), Intern.intern("TopLevelB")));
        assertFalse(Intern.samePackage(Intern.intern("TopLevelA"), name));
    }

    /** The scan interns from every worker at once, so one string must get one id whoever wins. */
    @Test
    void concurrentInterningAgreesOnOneSymbolPerString() throws Exception {
        int threads = 8;
        int strings = 20_000;
        int[][] seen = new int[threads][strings];
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int worker = t;
            Thread thread = new Thread(() -> {
                Scratch scratch = new Scratch();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < strings; i++) {
                    // Each worker walks the same strings from its own starting point.
                    int n = (i + worker * 2_500) % strings;
                    byte[] name = ("concurrent/pkg" + n % 97 + "/Class" + n).getBytes(StandardCharsets.UTF_8);
                    seen[worker][n] = Intern.intern(scratch, name, 0, name.length);
                }
            });
            thread.start();
            workers.add(thread);
        }
        start.countDown();
        for (Thread thread : workers) {
            thread.join();
        }
        java.util.Set<Integer> distinct = new java.util.HashSet<>();
        for (int n = 0; n < strings; n++) {
            for (int t = 1; t < threads; t++) {
                assertEquals(seen[0][n], seen[t][n], "string " + n);
            }
            assertEquals("concurrent/pkg" + n % 97 + "/Class" + n, Intern.str(seen[0][n]));
            distinct.add(seen[0][n]);
        }
        assertEquals(strings, distinct.size());
    }

    @Test
    void equalsBytesSeesADifferenceAtEveryPosition() {
        // 15 bytes: one whole 8-byte word and a shorter tail.
        byte[] name = "net/exoego/Tail".getBytes(StandardCharsets.UTF_8);
        int sym = Intern.intern(null, name, 0, name.length);
        for (int i = 0; i < name.length; i++) {
            byte[] other = name.clone();
            other[i] ^= 1;
            assertFalse(Intern.equalsBytes(sym, other, 0, other.length), "differs at " + i);
        }
        assertTrue(Intern.equalsBytes(sym, name.clone(), 0, name.length));
    }

    @Test
    void findDoesNotAnswerAStringThatOnlySharesTheHash() {
        java.util.Map<Integer, String> seen = new java.util.HashMap<>();
        String first = null;
        String second = null;
        for (int i = 0; second == null; i++) {
            String s = "find/collision/" + i;
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            String earlier = seen.putIfAbsent(Intern.hash(utf8, 0, utf8.length), s);
            if (earlier != null) {
                first = earlier;
                second = s;
            }
        }
        int firstSym = Intern.intern(first);
        assertEquals(Intern.NONE, Intern.find(second));
        int secondSym = Intern.intern(second);
        assertNotEquals(firstSym, secondSym);
        assertEquals(firstSym, Intern.find(first));
        assertEquals(secondSym, Intern.find(second));
        assertEquals(second, Intern.str(secondSym));
    }

    /** A symbol keeps its length in 24 bits. */
    @Test
    void refusesAStringTooLongForItsLengthField() {
        byte[] huge = new byte[1 << 24];
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Intern.intern(null, huge, 0, huge.length));
        assertEquals("string too long to intern: 16777216 bytes", e.getMessage());
    }

    @Test
    void tableLengthBoundsEverySymbol() {
        int sym = Intern.intern("table/len/probe-" + System.nanoTime());
        assertTrue(sym < Intern.tableLen());
        long[] stats = Intern.stats();
        assertEquals(Intern.tableLen(), stats[0]);
        assertTrue(stats[1] > 0);
    }
}
