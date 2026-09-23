package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanTest {
    @TempDir
    Path dir;

    @AfterEach
    void clearEnvironment() {
        Env.clearOverrides();
    }

    private static int intern(String s) {
        return Intern.intern(s);
    }

    /** A class with no members: its pool names only itself and its superclass. */
    private static byte[] classFile(String name, String superName) {
        ClassFileBytes b = ClassFileBytes.header(52, 5);
        b.utf8(name); // #1
        b.classRef(1); // #2
        b.utf8(superName); // #3
        b.classRef(3); // #4
        b.u16(0x0021).u16(2).u16(4).u16(0).u16(0).u16(0).u16(0);
        return b.toByteArray();
    }

    private static String writeJar(Path path, Object... entries) throws IOException {
        try (OutputStream file = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        return path.toString();
    }

    private static ApiIndex library(String... names) {
        List<ClassApi> apis = new ArrayList<>();
        for (String name : names) {
            ClassApi api = new ClassApi();
            api.name = intern(name);
            api.access = Acc.PUBLIC;
            api.superName = Scope.objectSym();
            apis.add(api);
        }
        return ApiIndex.build(apis);
    }

    private static Scan.Result scan(List<String> paths, boolean collectEdges) {
        return Scan.scanTargetPaths(paths, library("lib/Base", "lib/Other"), new MemberProbe(new long[0]), collectEdges);
    }

    /** Every class in the graph as {@code name <- super @ source}, in name order. */
    private static Map<String, String> graphOf(Scan.Result result) {
        Map<String, String> out = new TreeMap<>();
        for (int node = 0; node < result.graph.size(); node++) {
            out.put(
                    Intern.str(result.graph.nameOf(node)),
                    Intern.str(result.graph.superOf(node)) + " @ " + Intern.str(result.graph.sourceOf(node)));
        }
        return out;
    }

    private static List<Integer> recordsOf(Scan.Result result) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < result.records.size(); i++) {
            out.add(result.records.get(i));
        }
        return out;
    }

    @Test
    void windowComesFromTheEnvironmentWhenPositive() {
        Env.override("UIKA_CHUNK", null);
        int fallback = Scratch.threads() * 8;
        assertEquals(fallback, Scan.window());
        Env.override("UIKA_CHUNK", " 5 ");
        assertEquals(5, Scan.window());
        Env.override("UIKA_CHUNK", "0");
        assertEquals(fallback, Scan.window());
        Env.override("UIKA_CHUNK", "five");
        assertEquals(fallback, Scan.window());
    }

    /**
     * The scan takes an early directory read only for its own paths and edge setting. Any
     * other is left to finish on its own and the scan reads for itself.
     */
    @Test
    void anEarlyDirectoryReadIsUsedOnlyForTheSamePathsAndEdges() throws Exception {
        String first = writeJar(dir.resolve("first.jar"), "app/A.class", classFile("app/A", "lib/Base"));
        String second = writeJar(dir.resolve("second.jar"), "app/B.class", classFile("app/B", "lib/Other"));
        List<String> both = List.of(first, second);
        ApiIndex lib = library("lib/Base", "lib/Other");
        MemberProbe probe = new MemberProbe(new long[0]);
        Map<String, String> expected = graphOf(scan(both, false));

        assertEquals(expected, graphOf(Scan.scanTargetPaths(both, lib, probe, false, Scan.prepareAhead(both, false))));
        assertEquals(expected, graphOf(Scan.scanTargetPaths(both, lib, probe, true, Scan.prepareAhead(both, false))));
        assertEquals(expected, graphOf(Scan.scanTargetPaths(both, lib, probe, false, Scan.prepareAhead(List.of(first), false))));
    }

    /**
     * The window is a memory bound, not a semantic line: first-wins, duplicate skipping and
     * the scanned count must come out the same however many paths are in flight.
     */
    @Test
    void oneWindowPerPathScansLikeOneWindowForAll() throws Exception {
        byte[] b = classFile("app/B", "java/lang/Object");
        String first = writeJar(
                dir.resolve("first.jar"),
                "app/A.class", classFile("app/A", "lib/Base"),
                "app/B.class", b,
                "app/Broken.class", new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 0, 9, (byte) 0xEE});
        // Another A that loses first-wins, and a C.
        String second = writeJar(
                dir.resolve("second.jar"), "app/A.class", classFile("app/A", "lib/Other"), "app/C.class", classFile("app/C", "lib/Other"));
        // A byte-identical B, which is skipped without being read, and a D.
        String third = writeJar(dir.resolve("third.jar"), "app/B.class", b, "app/D.class", classFile("app/D", "app/C"));
        Path classes = Files.createDirectories(dir.resolve("classes/x"));
        Files.write(classes.resolve("Misplaced.class"), classFile("app/E", "lib/Base"));
        List<String> paths = List.of(first, second, third, dir.resolve("classes").toString());

        Scan.Result together = scan(paths, false);
        Env.override("UIKA_CHUNK", "1");
        Scan.Result apart = scan(paths, false);

        Map<String, String> expected = new TreeMap<>(Map.of(
                "app/A", "lib/Base @ " + first,
                "app/B", "java/lang/Object @ " + first,
                "app/C", "lib/Other @ " + second,
                "app/D", "app/C @ " + third,
                "app/E", "lib/Base @ " + dir.resolve("classes")));
        assertEquals(expected, graphOf(together));
        assertEquals(expected, graphOf(apart));
        assertEquals(recordsOf(together), recordsOf(apart));
        assertEquals(together.recordCount, apart.recordCount);
        assertEquals(8, together.scannedClasses);
        assertEquals(8, apart.scannedClasses);
        assertEquals(Map.of(intern("app/E"), "x/Misplaced.class"), together.entryOverrides);
        assertEquals(together.entryOverrides, apart.entryOverrides);

        // A class that does not parse is a warning, the same in either shape.
        assertEquals(1, together.warnings.size());
        assertTrue(together.warnings.get(0).startsWith(first + "!app/Broken.class: "), together.warnings.get(0));
        assertEquals(together.warnings, apart.warnings);
    }

    /** Callers that build a scan by hand pack it with leafOf; merge must read back what went in. */
    @Test
    void aHandBuiltLeafMergesBackToWhatWentIn() {
        int source = intern("hand.jar");
        int owner = intern("lib/Owner");
        List<SymbolRef> refs = List.of(
                new SymbolRef(RefKind.CLASS, owner, MemberKey.NONE, null, null, true),
                new SymbolRef(RefKind.METHOD, owner, MemberKey.of("run", "()V"), true, null, null),
                new SymbolRef(RefKind.INTERFACE_METHOD, owner, MemberKey.of("call", "()V"), false, null, null),
                new SymbolRef(RefKind.FIELD, owner, MemberKey.of("count", "I"), false, true, null),
                new SymbolRef(RefKind.FIELD, owner, MemberKey.of("count", "I"), true, false, null));
        Scan.Target impl = new Scan.Target(
                source,
                intern("app/Impl"),
                true,
                intern("app/Base"),
                new int[] {intern("app/Api"), intern("app/Other")},
                intern("app/Host"),
                "custom/Impl.class",
                refs,
                new int[] {intern("app/Used")});
        Scan.Target plain = new Scan.Target(
                source, intern("app/Plain"), true, Intern.NONE, new int[0], Intern.NONE, "custom/Plain.class", List.of(), new int[0]);
        Scan.Target loser = new Scan.Target(
                source, intern("app/Impl"), false, Intern.NONE, new int[0], Intern.NONE, null, List.of(), new int[0]);

        Scan.Result result = new Scan.Result();
        result.merge(Scan.leafOf(List.of(impl, plain, loser)));

        assertEquals(3, result.scannedClasses);
        assertEquals(2, result.graph.size());
        int node = result.graph.node(intern("app/Impl"));
        assertEquals("app/Base", Intern.str(result.graph.superOf(node)));
        assertEquals("app/Host", Intern.str(result.graph.nestHostOf(node)));
        assertEquals(source, result.graph.sourceOf(node));
        assertEquals(2, result.graph.interfaceCount(node));
        assertEquals("app/Api", Intern.str(result.graph.interfaceAt(node, 0)));
        assertEquals("app/Other", Intern.str(result.graph.interfaceAt(node, 1)));
        assertEquals(1, result.graph.refCount(node));
        assertEquals("app/Used", Intern.str(result.graph.refAt(node, 0)));
        assertEquals(
                Map.of(intern("app/Impl"), "custom/Impl.class", intern("app/Plain"), "custom/Plain.class"), result.entryOverrides);

        assertEquals(1, result.recordCount);
        assertEquals(source, result.records.get(0));
        assertEquals(intern("app/Impl"), result.records.get(1));
        assertEquals(refs.size(), result.records.get(2));
        List<SymbolRef> back = new ArrayList<>();
        for (int k = 0, at = 3; k < refs.size(); k++, at += 4) {
            back.add(SymbolRef.unpack(
                    result.records.get(at), result.records.get(at + 1), result.records.get(at + 2), result.records.get(at + 3)));
        }
        assertEquals(refs, back);
    }

    /** A provider file that cannot be read costs its edges and a warning, not the scan. */
    @Test
    void anUnreadableProviderFileIsAWarning() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("app"));
        Files.write(classes.resolve("Main.class"), classFile("Main", "lib/Base"));
        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Path provider = Files.write(services.resolve("com.example.Api"), "com.example.Impl\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!provider.toFile().setReadable(false, false) || provider.toFile().canRead()) {
            return; // A filesystem or user that cannot lock a file (root, Windows).
        }
        try {
            Scan.Result result = scan(List.of(classes.toString()), true);
            assertEquals(List.of(classes + ": Permission denied"), result.serviceWarnings);
            assertEquals(List.of(), result.services);
            assertTrue(result.graph.contains(intern("Main")));
        } finally {
            provider.toFile().setReadable(true, false);
        }
    }
}
