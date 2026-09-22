package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ports the `reach.rs` tests. */
class ReachTest {
    private static final int[] NONE = new int[0];

    private static int[] syms(String... names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = Intern.intern(names[i]);
        }
        return out;
    }

    /** {name, source, refs...} rows, every class extending java/lang/Object. */
    private static ClassGraph graphWith(String[]... edges) {
        ClassGraph graph = new ClassGraph();
        for (String[] edge : edges) {
            String[] refs = java.util.Arrays.copyOfRange(edge, 2, edge.length);
            graph.insertIfAbsent(Intern.intern(edge[0]), Scope.objectSym(), NONE, syms(refs), Intern.NONE, Intern.intern(edge[1]));
        }
        return graph;
    }

    private static String[] node(String name, String source, String... refs) {
        String[] row = new String[2 + refs.length];
        row[0] = name;
        row[1] = source;
        System.arraycopy(refs, 0, row, 2, refs.length);
        return row;
    }

    private static Reach.Inputs inputs(List<Reach.ServiceFile> services, String... appSources) {
        IntSet sources = new IntSet();
        for (String s : appSources) {
            sources.add(Intern.intern(s));
        }
        return new Reach.Inputs(sources, services);
    }

    private static boolean isReachable(Reach.Result result, String name) {
        return result.isReachable(Intern.intern(name));
    }

    @Test
    void marksTransitiveRefsFromAppRootsOnly() {
        ClassGraph graph = graphWith(
                node("app/Main", "app-dir", "lib/Used"),
                node("lib/Used", "lib.jar", "lib/Indirect"),
                node("lib/Indirect", "lib.jar"),
                node("lib/Orphan", "lib.jar", "lib/OrphanDep"),
                node("lib/OrphanDep", "lib.jar"));
        Reach.Result result = Reach.reachableClasses(graph, inputs(List.of(), "app-dir"));
        assertTrue(result.appRootMatched());
        assertTrue(isReachable(result, "app/Main"));
        assertTrue(isReachable(result, "lib/Used"));
        assertTrue(isReachable(result, "lib/Indirect"));
        assertFalse(isReachable(result, "lib/Orphan"));
        assertFalse(isReachable(result, "lib/OrphanDep"));
    }

    @Test
    void appRootNotMatchedWhenSourceAbsent() {
        // App root points at a source that no scanned class carries (e.g. an unbuilt dir):
        // nothing is seeded, and the caller can warn instead of showing a silent "0 reachable".
        ClassGraph graph = graphWith(node("lib/Used", "lib.jar", "lib/Indirect"));
        Reach.Result result = Reach.reachableClasses(graph, inputs(List.of(), "app-dir"));
        assertFalse(result.appRootMatched());
        assertFalse(isReachable(result, "lib/Used"));
    }

    @Test
    void hierarchyEdgesMarkSupertypes() {
        ClassGraph graph = new ClassGraph();
        graph.insertIfAbsent(
                Intern.intern("app/Sub"), Intern.intern("lib/Base"), syms("lib/Iface"), NONE, Intern.NONE, Intern.intern("app-dir"));
        graph.insertIfAbsent(Intern.intern("lib/Base"), Scope.objectSym(), NONE, NONE, Intern.NONE, Intern.intern("lib.jar"));
        graph.insertIfAbsent(Intern.intern("lib/Iface"), Intern.NONE, NONE, NONE, Intern.NONE, Intern.intern("lib.jar"));
        Reach.Result result = Reach.reachableClasses(graph, inputs(List.of(), "app-dir"));
        assertTrue(isReachable(result, "lib/Base"));
        assertTrue(isReachable(result, "lib/Iface"));
    }

    @Test
    void serviceImplsFollowIfaceReachability() {
        ClassGraph graph = graphWith(
                node("app/Main", "app-dir", "lib/Spi"),
                node("lib/Spi", "lib.jar"),
                node("lib/SpiImpl", "lib.jar", "lib/SpiImplDep"),
                node("lib/SpiImplDep", "lib.jar"),
                node("lib/OtherSpi", "lib.jar"),
                node("lib/OtherImpl", "lib.jar"));
        List<Reach.ServiceFile> services = List.of(
                new Reach.ServiceFile(Intern.intern("lib/Spi"), syms("lib/SpiImpl"), Intern.intern("lib.jar")),
                new Reach.ServiceFile(Intern.intern("lib/OtherSpi"), syms("lib/OtherImpl"), Intern.intern("lib.jar")));
        Reach.Result result = Reach.reachableClasses(graph, inputs(services, "app-dir"));
        assertTrue(isReachable(result, "lib/SpiImpl"));
        assertTrue(isReachable(result, "lib/SpiImplDep"));
        // lib/OtherSpi is in the graph but nothing references it -> impls stay unreachable.
        assertFalse(isReachable(result, "lib/OtherImpl"));
    }

    @Test
    void serviceIfaceOutsideScopeMakesImplsRoots() {
        ClassGraph graph = graphWith(node("lib/JdbcDriver", "lib.jar"));
        List<Reach.ServiceFile> services =
                List.of(new Reach.ServiceFile(Intern.intern("java/sql/Driver"), syms("lib/JdbcDriver"), Intern.intern("lib.jar")));
        Reach.Result result = Reach.reachableClasses(graph, inputs(services, "app-dir"));
        assertTrue(isReachable(result, "lib/JdbcDriver"));
    }

    @Test
    void parsesServiceFileContents() {
        List<Reach.ServiceFile> out = new ArrayList<>();
        byte[] bytes = "# comment\ncom.example.ImplA\n\n  com.example.ImplB  # trailing\n".getBytes(StandardCharsets.UTF_8);
        Reach.pushService(out, "com.example.Spi", bytes, bytes.length, Intern.intern("test.jar"));
        assertEquals(1, out.size());
        assertEquals(Intern.intern("com/example/Spi"), out.get(0).iface());
        assertArrayEquals(syms("com/example/ImplA", "com/example/ImplB"), out.get(0).impls());
        assertEquals(Intern.intern("test.jar"), out.get(0).source());
    }

    // ---- beyond the Rust tests, the parts of the port that differ in shape ----

    /** ServiceLoader reads with BufferedReader.readLine, which also accepts a lone '\r'. */
    @Test
    void serviceFilesSplitOnEveryLineTerminator() {
        List<Reach.ServiceFile> out = new ArrayList<>();
        byte[] bytes = "a.One\rb.Two\r\nc.Three\u00a0\n\u3000d.Four".getBytes(StandardCharsets.UTF_8);
        Reach.pushService(out, "x.Spi", bytes, bytes.length, Intern.intern("test.jar"));
        assertArrayEquals(syms("a/One", "b/Two", "c/Three", "d/Four"), out.get(0).impls());

        // Only the first `length` bytes belong to the file, because the buffer is a reused scratch.
        out.clear();
        byte[] reused = "a.One\nstale.Leftover\n".getBytes(StandardCharsets.UTF_8);
        Reach.pushService(out, "x.Spi", reused, 6, Intern.intern("test.jar"));
        assertArrayEquals(syms("a/One"), out.get(0).impls());

        // Nothing but comments, or bytes that are not UTF-8, register no service at all.
        out.clear();
        byte[] comments = "# nothing\n\n   \n".getBytes(StandardCharsets.UTF_8);
        Reach.pushService(out, "x.Spi", comments, comments.length, Intern.intern("test.jar"));
        byte[] invalid = {'a', '.', 'B', (byte) 0xff, '\n'};
        Reach.pushService(out, "x.Spi", invalid, invalid.length, Intern.intern("test.jar"));
        assertTrue(out.isEmpty());
    }

    /** Rust `str::trim` trims Unicode White_Space, which is not what `String.trim` or `strip` do. */
    @Test
    void trimFollowsUnicodeWhiteSpace() {
        assertEquals("x", Reach.trim(" \t\u000b\u000c\u0085\u00a0\u1680\u2000\u200a\u2028\u2029\u202f\u205f\u3000x\u3000 "));
        // U+200B and U+FEFF are not White_Space, and U+001C..U+001F are not either although Java calls them whitespace.
        assertEquals("\u200bx\ufeff", Reach.trim("\u200bx\ufeff"));
        assertEquals("\u001cx\u001f", Reach.trim("\u001cx\u001f"));
        assertEquals("", Reach.trim(" \n "));
        assertEquals("a b", Reach.trim("a b"));
    }

    @TempDir
    Path dir;

    @Test
    void collectsServicesFromJarsAndDirectoriesInPathOrder() throws Exception {
        Path jar = dir.resolve("providers.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("META-INF/services/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/services/com.example.Spi"));
            zip.write("com.example.JarImpl\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/services/nested/com.example.Ignored"));
            zip.write("com.example.Nested\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/services/com.example.Empty"));
            zip.write("# no providers\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("other/META-INF/services/com.example.Elsewhere"));
            zip.write("com.example.Elsewhere\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path classes = Files.createDirectories(dir.resolve("classes/META-INF/services"));
        Files.write(classes.resolve("com.example.Spi"), "com.example.DirImpl # from the build output\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(classes.resolve("a.directory.Named.LikeAService"));
        Path plain = Files.createDirectories(dir.resolve("no-services"));
        String missing = dir.resolve("missing.jar").toString();

        List<String> warnings = new ArrayList<>();
        List<Reach.ServiceFile> services = Reach.collectServices(
                List.of(dir.resolve("classes").toString(), missing, jar.toString(), plain.toString()), warnings);

        assertEquals(2, services.size());
        assertEquals("com/example/Spi", Intern.str(services.get(0).iface()));
        assertArrayEquals(syms("com/example/DirImpl"), services.get(0).impls());
        assertEquals(dir.resolve("classes").toString(), Intern.str(services.get(0).source()));
        assertEquals("com/example/Spi", Intern.str(services.get(1).iface()));
        assertArrayEquals(syms("com/example/JarImpl"), services.get(1).impls());
        assertEquals(jar.toString(), Intern.str(services.get(1).source()));
        // Rust prints this warning with `{e}`, which is the outermost context only, not the chain.
        assertEquals(List.of(missing + ": cannot open " + missing), warnings);
    }

    /** A symbol interned after the pass is simply unmarked, never out of bounds. */
    @Test
    void symbolsInternedAfterThePassAreUnreachable() {
        ClassGraph graph = graphWith(node("late/Main", "late-dir"));
        Reach.Result result = Reach.reachableClasses(graph, inputs(List.of(), "late-dir"));
        assertTrue(isReachable(result, "late/Main"));
        assertFalse(result.isReachable(Intern.intern("late/InternedAfterwards-" + System.nanoTime())));
    }

    @Test
    void controlCharactersBelowTabAreNotWhiteSpace() {
        assertEquals("\u0000x\u0008", Reach.trim("\u0000x\u0008"));
    }

    private static void storedEntry(ZipOutputStream zip, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    /**
     * The fast path reads provider files through the channel the scan already opened. Each
     * record it cannot use is skipped alone, and the readable files around it still count.
     */
    @Test
    void providerFilesReadThroughAnOpenJarSkipOnlyTheUnreadableOnes() throws Exception {
        Path jar = dir.resolve("providers-fast.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            storedEntry(zip, "META-INF/services/com.example.Stored", "com.example.StoredImpl\n".getBytes(StandardCharsets.UTF_8));
            // 0xff opens a deflate block of the reserved type 3.
            storedEntry(zip, "META-INF/services/com.example.Garbage", new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});
            zip.putNextEntry(new ZipEntry("META-INF/services/com.example.Deflated"));
            zip.write("com.example.DeflatedImpl\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        long size = Files.size(jar);
        try (FileChannel channel = FileChannel.open(jar, StandardOpenOption.READ)) {
            Scratch scratch = Scratch.current();
            Jar.Entries entries = Jar.readEntries(channel, scratch);
            Map<String, Jar.ServiceEntry> byName = new HashMap<>();
            for (Jar.ServiceEntry entry : entries.services) {
                byName.put(entry.service(), entry);
            }
            Jar.ServiceEntry stored = byName.get("com.example.Stored");
            Jar.ServiceEntry deflated = byName.get("com.example.Deflated");
            long garbage = byName.get("com.example.Garbage").offset();
            entries.services = List.of(
                    stored,
                    new Jar.ServiceEntry("com.example.Bzip2", deflated.offset(), deflated.compressed(), deflated.inflated(), 12),
                    new Jar.ServiceEntry("nested/com.example.Spi", stored.offset(), stored.compressed(), stored.inflated(), 0),
                    new Jar.ServiceEntry("com.example.Huge", stored.offset(), 65L * 1024 * 1024, 65L * 1024 * 1024, 0),
                    new Jar.ServiceEntry("com.example.PastTheEnd", size + 16, 4, 4, 0),
                    new Jar.ServiceEntry("com.example.NotAHeader", stored.offset() + 1, 4, 4, 0),
                    new Jar.ServiceEntry("com.example.Truncated", stored.offset(), size, size, 0),
                    new Jar.ServiceEntry("com.example.Corrupt", garbage, 4, 64, 8),
                    deflated);
            List<Reach.ServiceFile> services = Reach.servicesOf(channel, entries, Intern.intern(jar.toString()), scratch);
            entries.release();

            assertEquals(2, services.size());
            assertEquals("com/example/Stored", Intern.str(services.get(0).iface()));
            assertArrayEquals(syms("com/example/StoredImpl"), services.get(0).impls());
            assertEquals("com/example/Deflated", Intern.str(services.get(1).iface()));
            assertArrayEquals(syms("com/example/DeflatedImpl"), services.get(1).impls());
        }
    }

    /** A deflate error in one provider file of the fallback reader costs that file only. */
    @Test
    void aCorruptProviderFileInTheFallbackReaderIsSkipped() throws Exception {
        Path jar = dir.resolve("providers-corrupt.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("META-INF/services/com.example.Broken"));
            zip.write("com.example.BrokenImpl\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/services/com.example.Spi"));
            zip.write("com.example.Impl\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(jar);
        // The first local header sits at offset 0, and its data follows the name and extra field.
        int dataStart = 30 + (bytes[26] & 0xff | (bytes[27] & 0xff) << 8) + (bytes[28] & 0xff | (bytes[29] & 0xff) << 8);
        bytes[dataStart] = (byte) 0xff;
        Files.write(jar, bytes);

        List<String> warnings = new ArrayList<>();
        List<Reach.ServiceFile> services = Reach.collectServices(List.of(jar.toString()), warnings);
        assertEquals(1, services.size());
        assertEquals("com/example/Spi", Intern.str(services.get(0).iface()));
        assertArrayEquals(syms("com/example/Impl"), services.get(0).impls());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aMissingTargetYieldsItsWarningInsteadOfProviders() {
        String missing = dir.resolve("gone.jar").toString();
        Object[] result = Reach.servicesOrWarning(missing);
        assertEquals(List.of(), result[0]);
        assertEquals(missing + ": cannot open " + missing, result[1]);
    }

    @Test
    void anUnreadableProviderFileInADirectoryWarnsForTheTarget() throws Exception {
        Path root = dir.resolve("locked");
        Path file = Files.createDirectories(root.resolve("META-INF/services")).resolve("com.example.Spi");
        Files.write(file, "com.example.Impl\n".getBytes(StandardCharsets.UTF_8));
        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX permissions here");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
        Files.setPosixFilePermissions(file, Set.of());
        try {
            assumeFalse(Files.isReadable(file), "this user reads files without permission bits");
            List<String> warnings = new ArrayList<>();
            assertEquals(List.of(), Reach.collectServices(List.of(root.toString()), warnings));
            assertEquals(List.of(root + ": Permission denied"), warnings);

            Object[] result = Reach.servicesOrWarning(root.toString());
            assertEquals(List.of(), result[0]);
            assertEquals(root + ": Permission denied", result[1]);
        } finally {
            Files.setPosixFilePermissions(file, original);
        }
    }

    @Test
    void cyclesTerminate() {
        ClassGraph graph = graphWith(node("cyc/A", "cyc-dir", "cyc/B"), node("cyc/B", "cyc.jar", "cyc/A", "cyc/B", "cyc/Unscanned"));
        Reach.Result result = Reach.reachableClasses(graph, inputs(List.of(), "cyc-dir"));
        assertTrue(isReachable(result, "cyc/B"));
        // A referenced class outside the scan is still marked, because the mark is per symbol.
        assertTrue(isReachable(result, "cyc/Unscanned"));
    }
}
