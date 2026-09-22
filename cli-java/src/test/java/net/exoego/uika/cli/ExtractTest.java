package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Ports the `extract.rs` tests. The Rust helpers take and return strings. The Java twins
 * judge raw constant-pool bytes and intern the result, so the tests go through the symbol.
 */
class ExtractTest {
    private final Scratch scratch = new Scratch();

    /** Rust `object_class_of`, with null standing for None. */
    private String objectClassOf(String raw) {
        byte[] bytes = ("<" + raw + ">").getBytes(StandardCharsets.UTF_8);
        int sym = Extract.internObjectClass(scratch, bytes, 1, bytes.length - 2);
        return sym == Intern.NONE ? null : Intern.str(sym);
    }

    /** Rust `slashed_class_name`, with null standing for None. */
    private String slashedClassName(String s) {
        byte[] bytes = ("<" + s + ">").getBytes(StandardCharsets.UTF_8);
        int sym = Extract.internSlashedClassName(scratch, bytes, 1, bytes.length - 2);
        return sym == Intern.NONE ? null : Intern.str(sym);
    }

    @Test
    void objectClassOfUnwrapsArrays() {
        assertEquals("foo/Bar", objectClassOf("foo/Bar"));
        assertEquals("foo/Bar", objectClassOf("[Lfoo/Bar;"));
        assertEquals("foo/Bar", objectClassOf("[[Lfoo/Bar;"));
        assertNull(objectClassOf("[I"));
        assertNull(objectClassOf("[[J"));
    }

    @Test
    void slashedClassNameAcceptsBinaryNamesOnly() {
        assertEquals("com/foo/Bar$Baz", slashedClassName("com.foo.Bar$Baz"));
        assertEquals("os/name", slashedClassName("os.name")); // Package-shaped: dead symbol, harmless.
        assertNull(slashedClassName("Bar")); // No dot.
        assertNull(slashedClassName("com..Bar")); // Empty segment.
        assertNull(slashedClassName("com.foo.Bar Baz")); // Space.
        assertNull(slashedClassName("com/foo/Bar")); // Already slashed: not a forName argument shape.
        assertNull(slashedClassName("1.2.3")); // Segments cannot start with digits.
        assertNull(slashedClassName("a.b().c"));
    }

    @Test
    void objectClassOfRejectsMalformedArraysAndUndecodableNames() {
        assertNull(objectClassOf("[[")); // dimensions without an element type
        assertNull(objectClassOf("[II"));
        assertNull(objectClassOf("[Lfoo/Bar")); // no closing ';'
        byte[] undecodable = {'a', (byte) 0xC0, 'b'}; // C0 stands only before 80, for U+0000
        assertEquals(Intern.NONE, Extract.internObjectClass(scratch, undecodable, 0, undecodable.length));
    }

    @Test
    void slashedClassNameEdgesOfTheShape() {
        assertEquals("a/b", slashedClassName("a.b"));
        assertEquals("_a/$b9", slashedClassName("_a.$b9"));
        assertNull(slashedClassName("ab")); // Shorter than three bytes.
        assertNull(slashedClassName(".ab"));
        assertNull(slashedClassName("ab."));
        assertNull(slashedClassName("caf\u00e9.Bar")); // Identifier segments are ASCII only.
        assertNull(slashedClassName("a." + "b".repeat(299))); // Longer than 300 bytes.
        assertEquals("a/" + "b".repeat(298), slashedClassName("a." + "b".repeat(298)));
    }

    @Test
    void slashedClassNameRejectsPunctuationAboveTheLetters() {
        assertNull(slashedClassName("a.b~c"));
        assertNull(slashedClassName("a.b{c"));
        assertNull(slashedClassName("a.b[c"));
    }

    // ---- extract_refs and extract_edges have no Rust unit test, so these pin the packed Java form ----

    /**
     * class app/Main with Class constants (plain, array, primitive array, foreign), member
     * references with and without an opcode behind them, and two string constants.
     */
    private static byte[] referencingClass() {
        ClassFileBytes b = ClassFileBytes.header(52, 29);
        b.utf8("app/Main"); // #1
        b.classRef(1); // #2
        b.utf8("java/lang/Object"); // #3
        b.classRef(3); // #4
        b.utf8("lib/Owner"); // #5
        b.classRef(5); // #6
        b.utf8("[[Llib/Elem;"); // #7
        b.classRef(7); // #8
        b.utf8("[I"); // #9
        b.classRef(9); // #10
        b.utf8("other/Thing"); // #11
        b.classRef(11); // #12
        b.utf8("call"); // #13
        b.utf8("()V"); // #14
        b.nameAndType(13, 14); // #15
        b.utf8("count"); // #16
        b.utf8("I"); // #17
        b.nameAndType(16, 17); // #18
        b.memberRef(10, 6, 15); // #19 Methodref lib/Owner.call()V
        b.memberRef(9, 6, 18); // #20 Fieldref lib/Owner.count:I
        b.memberRef(11, 6, 15); // #21 InterfaceMethodref lib/Owner.call()V, only a MethodHandle would name it
        b.memberRef(10, 12, 15); // #22 Methodref other/Thing.call()V
        b.memberRef(10, 8, 15); // #23 Methodref on an array owner
        b.utf8("Code"); // #24
        b.utf8("lib.Dynamic"); // #25
        b.stringRef(25); // #26
        b.utf8("not a class"); // #27
        b.stringRef(27); // #28
        b.u16(0x0021).u16(2).u16(4).u16(0);
        b.u16(0); // fields
        b.u16(1); // methods
        b.u16(0x0009).u16(13).u16(14);
        b.u16(1);
        int[] code = {
            0xbb, 0x00, 0x06, // new lib/Owner
            0xb8, 0x00, 0x13, // invokestatic #19
            0xb5, 0x00, 0x14, // putfield #20
            0xb6, 0x00, 0x16, // invokevirtual #22
            0xb2, 0x00, 0x14, // getstatic #20
            0xb1, // return
        };
        b.u16(24).u32(8 + code.length + 4).u16(2).u16(1).u32(code.length).raw(code).u16(0).u16(0);
        b.u16(0); // class attrs
        return b.toByteArray();
    }

    private static NameSet names(String... names) {
        NameSet set = new NameSet(names.length);
        for (String name : names) {
            set.add(Intern.intern(name));
        }
        return set;
    }

    private static String show(SymbolRef r) {
        String member = r.hasMember() ? "." + Intern.str(MemberKey.name(r.member())) + ":" + Intern.str(MemberKey.descriptor(r.member())) : "";
        return r.kind() + " " + Intern.str(r.owner()) + member + " static=" + r.expectedStatic() + " write=" + r.fieldWrite() + " new="
                + r.instantiated();
    }

    @Test
    void extractsOnlyReferencesIntoTheAcceptedOwners() throws Exception {
        byte[] bytes = referencingClass();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);

        List<String> refs = new ArrayList<>();
        for (SymbolRef r : Extract.extractRefs(p, scratch, names("lib/Owner", "lib/Elem"))) {
            refs.add(show(r));
        }
        // The pool walk first (Class constants and members no opcode names), then the opcodes in code order.
        assertEquals(
                List.of(
                        "CLASS lib/Owner static=null write=null new=true",
                        "CLASS lib/Elem static=null write=null new=null",
                        "INTERFACE_METHOD lib/Owner.call:()V static=null write=null new=null",
                        "METHOD lib/Owner.call:()V static=true write=null new=null",
                        "FIELD lib/Owner.count:I static=false write=true new=null",
                        "FIELD lib/Owner.count:I static=true write=false new=null"),
                refs);
    }

    @Test
    void aPoolNamingNoAcceptedOwnerHoldsNoReference() throws Exception {
        byte[] bytes = referencingClass();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);
        assertFalse(Extract.referencesLibrary(p, scratch, names("elsewhere/Owner")));
        assertTrue(Extract.extractRefs(p, scratch, names("elsewhere/Owner")).isEmpty());
        assertTrue(Extract.referencesLibrary(p, scratch, names("lib/Elem")));
    }

    /** The owner cache is per pool, so a reused scratch must not answer with the previous class's owners. */
    @Test
    void aReusedScratchDoesNotLeakOwnersBetweenClasses() throws Exception {
        byte[] bytes = referencingClass();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);
        assertEquals(6, Extract.extractRefs(p, scratch, names("lib/Owner", "lib/Elem")).size());
        List<SymbolRef> foreign = Extract.extractRefs(p, scratch, names("other/Thing"));
        List<String> shown = new ArrayList<>();
        for (SymbolRef r : foreign) {
            shown.add(show(r));
        }
        assertEquals(
                List.of("CLASS other/Thing static=null write=null new=null", "METHOD other/Thing.call:()V static=false write=null new=null"),
                shown);
    }

    @Test
    void extractsClassLoadEdgesSortedAndWithoutSelf() throws Exception {
        byte[] bytes = referencingClass();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);

        int[] edges = Extract.extractEdges(p, scratch, Intern.intern("app/Main"));
        TreeSet<String> names = new TreeSet<>();
        for (int i = 0; i < edges.length; i++) {
            names.add(Intern.str(edges[i]));
            assertTrue(i == 0 || edges[i - 1] < edges[i], "sorted and deduplicated");
        }
        assertEquals(new TreeSet<>(List.of("java/lang/Object", "lib/Owner", "lib/Elem", "other/Thing", "lib/Dynamic")), names);
    }

    /**
     * Evidence is matched by name and descriptor for ANY owner, over the whole pool. A method
     * reference reaches its member only through a MethodHandle constant, and the call may go
     * through a subclass outside the checked library.
     */
    @Test
    void invocationEvidenceIgnoresTheOwnerFilterAndTheOpcodes() throws Exception {
        byte[] bytes = referencingClass();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);

        MemberProbe probe = new MemberProbe(new long[] {MemberKey.of("call", "()V"), MemberKey.of("unrelated", "()V")});
        IntBuf out = new IntBuf();
        Extract.invocationEvidence(out, p, scratch, probe);
        List<String> evidence = new ArrayList<>();
        for (int i = 0; i < out.n; i += 3) {
            evidence.add(Intern.str(out.a[i]) + "." + Intern.str(out.a[i + 1]) + ":" + Intern.str(out.a[i + 2]));
        }
        // Pool order. The array owner is skipped and a Fieldref is no invocation.
        assertEquals(List.of("lib/Owner.call:()V", "lib/Owner.call:()V", "other/Thing.call:()V"), evidence);

        IntBuf none = new IntBuf();
        Extract.invocationEvidence(none, p, scratch, new MemberProbe(new long[] {MemberKey.of("call", "(I)V"), MemberKey.of("count", "I")}));
        assertTrue(none.isEmpty());
        assertTrue(new MemberProbe(new long[0]).isEmpty());
    }

    /** lib/Owner as #2, then one malformed entry as #3. */
    private String brokenPoolError(ClassFileBytes entry) throws Exception {
        ClassFileBytes b = ClassFileBytes.header(52, 4);
        b.utf8("lib/Owner"); // #1
        b.classRef(1); // #2
        b.raw(entry.toByteArray()); // #3
        b.u16(0x0021).u16(2).u16(0).u16(0).u16(0).u16(0).u16(0);
        byte[] bytes = b.toByteArray();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);
        return assertThrows(ClassParser.FormatException.class, () -> Extract.extractRefs(p, scratch, names("lib/Owner")))
                .getMessage();
    }

    @Test
    void aMemberReferenceWithABrokenPoolFailsTheClass() throws Exception {
        assertEquals("constant pool #1 is not NameAndType", brokenPoolError(new ClassFileBytes().memberRef(10, 2, 1)));
        assertEquals("constant pool #1 is not Class", brokenPoolError(new ClassFileBytes().memberRef(9, 1, 1)));
        assertEquals("constant pool #2 is not Utf8", brokenPoolError(new ClassFileBytes().classRef(2)));
        assertEquals("constant pool #9 is not Class", brokenPoolError(new ClassFileBytes().memberRef(11, 9, 1)));
    }

    @Test
    void extractsTheApiSurface() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(61, 14);
        b.utf8("a/Sealed"); // #1
        b.classRef(1); // #2
        b.utf8("a/Base"); // #3
        b.classRef(3); // #4
        b.utf8("a/Iface"); // #5
        b.classRef(5); // #6
        b.utf8("zeta"); // #7
        b.utf8("alpha"); // #8
        b.utf8("()V"); // #9
        b.utf8("I"); // #10
        b.utf8("NestHost"); // #11
        b.utf8("PermittedSubclasses"); // #12
        b.utf8("unused"); // #13
        b.u16(0x0421).u16(2).u16(4);
        b.u16(1).u16(6); // interfaces
        b.u16(1); // fields
        b.u16(0x0019).u16(8).u16(10).u16(0);
        b.u16(3); // methods
        b.u16(0x0001).u16(7).u16(9).u16(0);
        b.u16(0x0404).u16(8).u16(9).u16(0);
        b.u16(0x0002).u16(7).u16(9).u16(0); // declared twice: the first one is kept
        b.u16(2); // class attrs
        b.u16(11).u32(2).u16(4);
        b.u16(12).u32(4).u16(1).u16(6);
        byte[] bytes = b.toByteArray();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);

        ClassApi api = Extract.extractApi(p, scratch);
        assertEquals("a/Sealed", Intern.str(api.name));
        assertEquals(0x0421, api.access);
        assertEquals("a/Base", Intern.str(api.superName));
        assertEquals(1, api.interfaces.length);
        assertEquals("a/Iface", Intern.str(api.interfaces[0]));
        assertEquals("a/Base", Intern.str(api.nestHost));
        assertEquals(1, api.permitted.length);
        assertEquals("a/Iface", Intern.str(api.permitted[0]));
        assertFalse(api.sealingUnknown);
        assertEquals(2, api.methodKeys.length);
        assertTrue(api.methodKeys[0] < api.methodKeys[1], "sorted for binary search");
        assertTrue(api.hasMethod(MemberKey.of("zeta", "()V")));
        assertTrue(api.hasMethod(MemberKey.of("alpha", "()V")));
        assertFalse(api.hasMethod(MemberKey.of("alpha", "I")));
        assertTrue(api.hasField(MemberKey.of("alpha", "I")));
        ApiIndex idx = ApiIndex.build(List.of(api));
        assertEquals(0x0001, idx.directMethodAccess(api.name, MemberKey.of("zeta", "()V")));
        assertEquals(0x0404, idx.directMethodAccess(api.name, MemberKey.of("alpha", "()V")));
        assertEquals(0x0019, idx.directFieldAccess(api.name, MemberKey.of("alpha", "I")));
    }

    /** A public class named {@code name} extending java/lang/Object with one public method {@code method}()V. */
    private static byte[] classNamed(String name, String method) {
        ClassFileBytes b = ClassFileBytes.header(52, 7);
        b.utf8(name); // #1
        b.classRef(1); // #2
        b.utf8("java/lang/Object"); // #3
        b.classRef(3); // #4
        b.utf8(method); // #5
        b.utf8("()V"); // #6
        b.u16(0x0021).u16(2).u16(4).u16(0);
        b.u16(0); // fields
        b.u16(1); // methods
        b.u16(0x0001).u16(5).u16(6).u16(0);
        b.u16(0); // class attrs
        return b.toByteArray();
    }

    private static String writeJar(java.nio.file.Path path, Object... entries) throws java.io.IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new java.util.zip.ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        return path.toString();
    }

    /** Duplicate class names are first-wins in path order, and a class that does not parse is a warning. */
    @Test
    void indexesPathsFirstWinsAndWarnsAboutUnparsableClasses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        byte[] good = classNamed("p/Good", "fromFirst");
        byte[] truncated = java.util.Arrays.copyOf(good, 20);
        String first = writeJar(dir.resolve("first.jar"), "p/Good.class", good, "p/Broken.class", truncated);
        String second = writeJar(
                dir.resolve("second.jar"), "p/Good.class", classNamed("p/Good", "fromSecond"), "p/Extra.class", classNamed("p/Extra", "m"));
        java.nio.file.Path classes = java.nio.file.Files.createDirectories(dir.resolve("classes/p"));
        java.nio.file.Files.write(classes.resolve("Extra.class"), classNamed("p/Extra", "fromDirectory"));
        // The entry name is only where the class was found. The name inside the class decides.
        java.nio.file.Files.write(classes.resolve("Misnamed.class"), classNamed("p/Real", "m"));

        List<String> warnings = new ArrayList<>();
        ApiIndex index = ApiIndex.fromPaths(List.of(first, second, dir.resolve("classes").toString()), warnings);

        assertEquals(List.of(first + "!p/Broken.class: truncated class file at offset 20"), warnings);
        assertEquals(3, index.classCount());
        int goodName = Intern.intern("p/Good");
        assertEquals(Acc.PUBLIC, index.directMethodAccess(goodName, MemberKey.of("fromFirst", "()V")));
        assertEquals(-1, index.directMethodAccess(goodName, MemberKey.of("fromSecond", "()V")));
        assertEquals(Acc.PUBLIC, index.directMethodAccess(Intern.intern("p/Extra"), MemberKey.of("m", "()V")));
        assertTrue(index.containsClass(Intern.intern("p/Real")));
        assertFalse(index.containsClass(Intern.intern("p/Misnamed")));
        assertTrue(index.classNameSet().contains("p/Extra"));
        assertFalse(index.classNameSet().contains("p/Broken"));
    }

    /** The whole path from a real JAR to an index, against what the JDK's own zip reader lists. */
    @Test
    void indexesEveryClassOfARealJar() throws Exception {
        String jar = "tests/fixtures/guava-22.0.jar";
        int expected = 0;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (Input.isScannable(entries.nextElement().getName())) {
                    expected++;
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        ApiIndex index = ApiIndex.fromPaths(List.of(jar), warnings);
        assertEquals(List.of(), warnings);
        assertEquals(expected, index.classCount());
        int joiner = Intern.intern("com/google/common/base/Joiner");
        assertEquals(
                Scope.Resolution.FOUND,
                index.resolve(joiner, MemberKey.of("on", "(Ljava/lang/String;)Lcom/google/common/base/Joiner;"), Scope.MemberKind.METHOD));
        assertEquals(Scope.Resolution.NOT_FOUND, index.resolve(joiner, MemberKey.of("noSuchMethod", "()V"), Scope.MemberKind.METHOD));
        int entry = index.entry(Intern.intern("com/google/common/collect/ImmutableList"));
        assertEquals("com/google/common/collect/ImmutableCollection", Intern.str(index.superOf(entry)));
        assertTrue((index.accessOf(entry) & Acc.ABSTRACT) != 0);
    }

    // ---- the pass-1 sink, which is where the packed records are produced for real ----

    private static ApiIndex libraryOf(String... names) {
        List<ClassApi> apis = new ArrayList<>();
        for (String name : names) {
            ClassApi api = new ClassApi();
            api.name = Intern.intern(name);
            api.access = Acc.PUBLIC;
            api.superName = Scope.objectSym();
            apis.add(api);
        }
        return ApiIndex.build(apis);
    }

    /** Decodes {@code source, className, refCount, (meta, owner, name, descriptor)...} records. */
    private static List<String> recordsOf(Scan.Result result) {
        List<String> out = new ArrayList<>();
        int at = 0;
        for (int r = 0; r < result.recordCount; r++) {
            String head = Intern.str(result.records.get(at)) + "!" + Intern.str(result.records.get(at + 1));
            int refs = result.records.get(at + 2);
            at += 3;
            for (int i = 0; i < refs; i++, at += 4) {
                SymbolRef ref = SymbolRef.unpack(
                        result.records.get(at), result.records.get(at + 1), result.records.get(at + 2), result.records.get(at + 3));
                out.add(head + " -> " + show(ref));
            }
        }
        assertEquals(result.records.size(), at);
        return out;
    }

    private static TreeSet<String> edgesOf(ClassGraph graph, String name) {
        int node = graph.node(Intern.intern(name));
        TreeSet<String> edges = new TreeSet<>();
        for (int k = 0; k < graph.refCount(node); k++) {
            edges.add(Intern.str(graph.refAt(node, k)));
        }
        return edges;
    }

    @Test
    void theScanSinkRecordsHierarchyEdgesAndReferencesFirstWins(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String first = writeJar(
                dir.resolve("first.jar"), "app/Main.class", referencingClass(), "app/NoLibrary.class", classNamed("app/NoLibrary", "m"));
        // The same class name again, in a later path and with other bytes, loses whole.
        String second = writeJar(dir.resolve("second.jar"), "app/Main.class", classNamed("app/Main", "shadowed"));
        java.nio.file.Path classes = java.nio.file.Files.createDirectories(dir.resolve("classes/x"));
        java.nio.file.Files.write(classes.resolve("Misplaced.class"), classNamed("app/FromDirectory", "m"));

        ApiIndex library = libraryOf("lib/Owner", "lib/Elem");
        Scan.Result result = Scan.scanTargetPaths(
                List.of(first, second, dir.resolve("classes").toString()), library, new MemberProbe(new long[0]), true);

        assertEquals(List.of(), result.warnings);
        assertEquals(4, result.scannedClasses);
        assertEquals(3, result.graph.size());
        assertEquals(
                List.of(
                        first + "!app/Main -> CLASS lib/Owner static=null write=null new=true",
                        first + "!app/Main -> CLASS lib/Elem static=null write=null new=null",
                        first + "!app/Main -> INTERFACE_METHOD lib/Owner.call:()V static=null write=null new=null",
                        first + "!app/Main -> METHOD lib/Owner.call:()V static=true write=null new=null",
                        first + "!app/Main -> FIELD lib/Owner.count:I static=false write=true new=null",
                        first + "!app/Main -> FIELD lib/Owner.count:I static=true write=false new=null"),
                recordsOf(result));

        int main = result.graph.node(Intern.intern("app/Main"));
        assertEquals(first, Intern.str(result.graph.sourceOf(main)));
        assertEquals("java/lang/Object", Intern.str(result.graph.superOf(main)));
        assertEquals(Intern.NONE, result.graph.nestHostOf(main));
        assertEquals(
                new TreeSet<>(List.of("java/lang/Object", "lib/Owner", "lib/Elem", "other/Thing", "lib/Dynamic")),
                edgesOf(result.graph, "app/Main"));

        // A class naming nothing of the library is not read past its header, so its nest host is unknown.
        int noLibrary = result.graph.node(Intern.intern("app/NoLibrary"));
        assertEquals(ClassGraph.NEST_HOST_UNREAD, result.graph.nestHostOf(noLibrary));
        assertEquals(new TreeSet<>(List.of("java/lang/Object")), edgesOf(result.graph, "app/NoLibrary"));

        // Pass 2 re-reads a class by its name unless the entry was called something else.
        assertEquals(java.util.Map.of(Intern.intern("app/FromDirectory"), "x/Misplaced.class"), result.entryOverrides);
    }

    /**
     * A class is inflated only as far as its header needs. The header here ends well past the
     * first slice and short of the whole file, and the one library reference sits at the very
     * end of the pool, so a header parsed from too few bytes would lose it.
     */
    @Test
    void aHeaderLongerThanTheFirstInflatedSliceIsStillReadWhole(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        int filler = 20_000;
        ClassFileBytes b = ClassFileBytes.header(52, filler + 8);
        java.util.Random random = new java.util.Random(11);
        for (int i = 0; i < filler; i++) {
            // Entry sizes vary, so the size estimate the reader works from is never exact.
            byte[] text = new byte[1 + random.nextInt(40)];
            for (int k = 0; k < text.length; k++) {
                text[k] = (byte) ('a' + random.nextInt(26));
            }
            b.utf8(text);
        }
        b.utf8("app/Wide"); // filler + 1
        b.classRef(filler + 1); // filler + 2
        b.utf8("java/lang/Object"); // filler + 3
        b.classRef(filler + 3); // filler + 4
        b.utf8("Pad"); // filler + 5
        b.utf8("lib/Owner"); // filler + 6
        b.classRef(filler + 6); // filler + 7
        b.u16(0x0021).u16(filler + 2).u16(filler + 4).u16(0);
        int headerEnd = b.size();
        b.u16(0); // fields
        b.u16(1); // methods
        b.u16(0x0001).u16(filler + 5).u16(filler + 5);
        b.u16(1);
        byte[] pad = new byte[headerEnd / 6];
        random.nextBytes(pad);
        b.u16(filler + 5).u32(pad.length).raw(pad);
        b.u16(0); // class attrs
        byte[] bytes = b.toByteArray();
        assertTrue(headerEnd > bytes.length * 3 / 4 && headerEnd < bytes.length * 15 / 16, headerEnd + " of " + bytes.length);

        String jar = writeJar(dir.resolve("wide.jar"), "app/Wide.class", bytes, "app/After.class", referencingClass());
        Scan.Result result = Scan.scanTargetPaths(List.of(jar), libraryOf("lib/Owner"), new MemberProbe(new long[0]), false);
        assertEquals(List.of(), result.warnings);
        assertEquals(2, result.scannedClasses);
        List<String> records = recordsOf(result);
        assertEquals(jar + "!app/Wide -> CLASS lib/Owner static=null write=null new=null", records.get(0));
        // The reused parser and buffers serve the next class of the same leaf correctly.
        assertEquals(6, records.size());
        assertEquals(jar + "!app/Main -> CLASS lib/Owner static=null write=null new=true", records.get(1));
        assertEquals(java.util.Map.of(Intern.intern("app/Main"), "app/After.class"), result.entryOverrides);
        // Edges are only collected when reachability asked for them.
        assertTrue(edgesOf(result.graph, "app/Wide").isEmpty());
    }

    private static List<String> shownRefs(ClassParser p, Scratch scratch, NameSet oldNames) throws ClassParser.FormatException {
        List<String> shown = new ArrayList<>();
        for (SymbolRef r : Extract.extractRefs(p, scratch, oldNames)) {
            shown.add(show(r));
        }
        return shown;
    }

    /** Code operands that name no member reference, and Class constants that name no class, yield nothing. */
    @Test
    void referencesIgnoreOperandsAndClassNamesThatNameNoMember() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(52, 19);
        b.utf8("app/Odd"); // #1
        b.classRef(1); // #2
        b.utf8("lib/Owner"); // #3
        b.classRef(3); // #4
        b.utf8("call"); // #5
        b.utf8("()V"); // #6
        b.nameAndType(5, 6); // #7
        b.memberRef(10, 4, 7); // #8 Methodref lib/Owner.call()V
        b.utf8(""); // #9
        b.classRef(9); // #10
        b.memberRef(10, 10, 7); // #11 Methodref on a class with an empty name
        b.utf8("[["); // #12
        b.classRef(12); // #13
        b.utf8("[II"); // #14
        b.classRef(14); // #15
        b.utf8("[Llib/Owner"); // #16
        b.classRef(16); // #17
        b.utf8("Code"); // #18
        b.u16(0x0021).u16(2).u16(0).u16(0);
        b.u16(0); // fields
        b.u16(1); // methods
        b.u16(0x0009).u16(5).u16(6);
        b.u16(1);
        int[] code = {
            0xb8, 0x7f, 0xff, // invokestatic past the end of the pool
            0xbb, 0x7f, 0xff, // new past the end of the pool
            0xb2, 0x00, 0x04, // getstatic on a Class constant
            0xb6, 0x00, 0x07, // invokevirtual on a NameAndType
            0xb8, 0x00, 0x08, // invokestatic #8
            0xb1, // return
        };
        b.u16(18).u32(8 + code.length + 4).u16(2).u16(1).u32(code.length).raw(code).u16(0).u16(0);
        b.u16(0); // class attrs
        byte[] bytes = b.toByteArray();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);

        assertEquals(
                List.of("CLASS lib/Owner static=null write=null new=null", "METHOD lib/Owner.call:()V static=true write=null new=null"),
                shownRefs(p, scratch, names("lib/Owner")));
    }

    /**
     * Owners are matched by their UTF-8 text. A supplementary character is a surrogate pair in
     * the class file and four bytes in the name set, so the raw bytes differ.
     */
    @Test
    void ownersWithNonAsciiNamesAreMatchedByTheirDecodedText() throws Exception {
        byte[] pair = {(byte) 0xED, (byte) 0xA0, (byte) 0xBD, (byte) 0xED, (byte) 0xB8, (byte) 0x80}; // U+1F600
        ClassFileBytes b = ClassFileBytes.header(52, 13);
        b.utf8("app/Main"); // #1
        b.classRef(1); // #2
        b.utf8("lib/Caf\u00e9"); // #3
        b.classRef(3); // #4
        b.utf8(new ClassFileBytes().raw("lib/".getBytes(StandardCharsets.US_ASCII)).raw(pair).toByteArray()); // #5
        b.classRef(5); // #6
        b.utf8("lib/Caf\u00e8"); // #7
        b.classRef(7); // #8
        b.utf8("call"); // #9
        b.utf8("()V"); // #10
        b.nameAndType(9, 10); // #11
        b.memberRef(10, 6, 11); // #12
        b.u16(0x0021).u16(2).u16(0).u16(0).u16(0).u16(0).u16(0);
        byte[] bytes = b.toByteArray();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);

        String smiley = new String(Character.toChars(0x1F600));
        assertEquals(
                List.of(
                        "CLASS lib/Caf\u00e9 static=null write=null new=null",
                        "CLASS lib/" + smiley + " static=null write=null new=null",
                        "METHOD lib/" + smiley + ".call:()V static=null write=null new=null"),
                shownRefs(p, scratch, names("lib/Caf\u00e9", "lib/" + smiley)));
    }

    /** A scan meets the malformed String too. The malformed Class fails a scanned class before its edges are read. */
    @Test
    void edgesSkipConstantsThatNameNoUtf8() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(52, 8);
        b.utf8("app/E"); // #1
        b.classRef(1); // #2
        b.u8(3).u32(0); // #3 Integer
        b.classRef(3); // #4 a Class naming the Integer
        b.stringRef(2); // #5 a String naming the Class
        b.utf8("x.y.Z"); // #6
        b.stringRef(6); // #7
        b.u16(0x0021).u16(2).u16(0).u16(0).u16(0).u16(0).u16(0);
        byte[] bytes = b.toByteArray();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);

        int[] edges = Extract.extractEdges(p, scratch, Intern.intern("app/E"));
        assertEquals(1, edges.length);
        assertEquals("x/y/Z", Intern.str(edges[0]));
    }

    private static List<String> evidenceOf(IntBuf out) {
        List<String> evidence = new ArrayList<>();
        for (int i = 0; i < out.n; i += 3) {
            evidence.add(Intern.str(out.a[i]) + "." + Intern.str(out.a[i + 1]) + ":" + Intern.str(out.a[i + 2]));
        }
        return evidence;
    }

    @Test
    void invocationEvidenceSkipsMalformedReferencesAndDecodesNonAsciiNames() throws Exception {
        byte[] pair = {(byte) 0xED, (byte) 0xA0, (byte) 0xBD, (byte) 0xED, (byte) 0xB8, (byte) 0x80}; // U+1F600
        String smiley = new String(Character.toChars(0x1F600));
        ClassFileBytes b = ClassFileBytes.header(52, 39);
        b.utf8("app/Ev"); // #1
        b.classRef(1); // #2
        b.utf8("lib/Owner"); // #3
        b.classRef(3); // #4
        b.utf8("call"); // #5
        b.utf8("()V"); // #6
        b.nameAndType(5, 6); // #7
        b.memberRef(10, 4, 5); // #8 its NameAndType is a Utf8
        b.nameAndType(2, 6); // #9
        b.memberRef(10, 4, 9); // #10 its name is a Class
        b.nameAndType(5, 2); // #11
        b.memberRef(10, 4, 11); // #12 its descriptor is a Class
        b.memberRef(10, 3, 7); // #13 its class is a Utf8
        b.u8(3).u32(0); // #14 Integer
        b.classRef(14); // #15
        b.memberRef(10, 15, 7); // #16 its class names an Integer
        b.utf8(""); // #17
        b.classRef(17); // #18
        b.memberRef(10, 18, 7); // #19 its class has an empty name
        b.utf8(new byte[] {'l', 'i', 'b', '/', (byte) 0xC0, 'x'}); // #20
        b.classRef(20); // #21
        b.memberRef(10, 21, 7); // #22 its class name does not decode
        b.utf8("caf\u00e9"); // #23
        b.nameAndType(23, 6); // #24
        b.memberRef(11, 4, 24); // #25 a non-ASCII name that is UTF-8 already
        b.utf8("(Lcaf\u00e9;)V"); // #26
        b.nameAndType(5, 26); // #27
        b.memberRef(10, 4, 27); // #28 a non-ASCII descriptor that is UTF-8 already
        b.utf8(new ClassFileBytes().raw('g', 'o').raw(pair).toByteArray()); // #29
        b.nameAndType(29, 6); // #30
        b.memberRef(10, 4, 30); // #31 a name holding a surrogate pair
        b.utf8(new ClassFileBytes().raw('(', 'L').raw(pair).raw(';', ')', 'V').toByteArray()); // #32
        b.nameAndType(5, 32); // #33
        b.memberRef(10, 4, 33); // #34 a descriptor holding a surrogate pair
        b.utf8(new byte[] {'g', 'o', (byte) 0xED, (byte) 0xA0, (byte) 0xBD}); // #35
        b.nameAndType(35, 6); // #36
        b.memberRef(10, 4, 36); // #37 a name holding a lone surrogate
        b.memberRef(10, 4, 7); // #38
        b.u16(0x0021).u16(2).u16(0).u16(0).u16(0).u16(0).u16(0);
        byte[] bytes = b.toByteArray();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);

        MemberProbe probe = new MemberProbe(new long[] {
            MemberKey.of("call", "()V"),
            MemberKey.of("caf\u00e9", "()V"),
            MemberKey.of("call", "(Lcaf\u00e9;)V"),
            MemberKey.of("go" + smiley, "()V"),
            MemberKey.of("call", "(L" + smiley + ";)V"),
        });
        IntBuf out = new IntBuf();
        Extract.invocationEvidence(out, p, scratch, probe);
        assertEquals(
                List.of(
                        ".call:()V",
                        "lib/Owner.caf\u00e9:()V",
                        "lib/Owner.call:(Lcaf\u00e9;)V",
                        "lib/Owner.go" + smiley + ":()V",
                        "lib/Owner.call:(L" + smiley + ";)V",
                        "lib/Owner.call:()V"),
                evidenceOf(out));
    }

    /**
     * A raw deflate stream holding the first {@code length} bytes of {@code data} in one stored
     * block, then {@code tail}, with the slack the decoder may read past the end.
     */
    private static ByteBuffer storedBlock(byte[] data, int length, boolean last, int... tail) {
        ByteBuffer input = ByteBuffer.allocateDirect(5 + length + tail.length + Inflate.SLACK).order(ByteOrder.LITTLE_ENDIAN);
        input.put(0, (byte) (last ? 1 : 0));
        input.putShort(1, (short) length);
        input.putShort(3, (short) ~length);
        input.put(5, data, 0, length);
        for (int i = 0; i < tail.length; i++) {
            input.put(5 + length + i, (byte) tail[i]);
        }
        return input;
    }

    /** The header is inflated in steps from nothing, even when its first constant outgrows the first step. */
    @Test
    void theHeaderIsInflatedOnlyAsFarAsItReaches() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(52, 6);
        b.utf8("x".repeat(200)); // #1
        b.utf8("app/Wide"); // #2
        b.classRef(2); // #3
        b.utf8("java/lang/Object"); // #4
        b.classRef(4); // #5
        b.u16(0x0021).u16(3).u16(5).u16(0);
        b.u16(0); // fields
        b.u16(1); // methods
        b.u16(0x0001).u16(1).u16(1);
        b.u16(1);
        b.u16(1).u32(8000).raw(new byte[8000]);
        b.u16(0); // class attrs
        byte[] bytes = b.toByteArray();
        ClassParser whole = new ClassParser();
        whole.parse(bytes, bytes.length);

        ClassSource source = new ClassSource();
        source.ofDeflate(storedBlock(bytes, bytes.length, true), 0, 5 + bytes.length, bytes.length);
        ClassParser p = new ClassParser();
        Extract.parseHeader(p, source);

        assertEquals("app/Wide", Intern.str(p.internClassName(scratch, p.thisClass)));
        assertEquals("java/lang/Object", Intern.str(p.internClassName(scratch, p.superClass)));
        assertEquals(whole.headerEnd, p.headerEnd);
        assertFalse(source.complete);
        assertTrue(source.available < bytes.length / 2, source.available + " of " + bytes.length);
    }

    @Test
    void anEntryThatStopsInflatingIsNotCountedAsScanned() {
        byte[] bytes = referencingClass();
        int half = bytes.length / 2;
        ClassSource source = new ClassSource();
        // The stored half ends in a block header of the reserved type 3.
        source.ofDeflate(storedBlock(bytes, half, false, 0x07), 0, 5 + half + 1, bytes.length);
        Extract.ScanSink sink = new Extract.ScanSink(names("lib/Owner"), new ClassGraph(), true, new MemberProbe(new long[0]));
        Extract.ScanLeaf leaf = sink.newLeaf();

        assertThrows(
                ClassSource.DeflateError.class,
                () -> sink.accept(leaf, scratch, Intern.intern("broken.jar"), Intern.intern("app/Main"), source));
        assertEquals(0, leaf.scanned);
        assertEquals(0, leaf.records.n);
        assertNull(leaf.warnings);
    }

    /** A class an earlier chunk already put in the graph loses first-wins, yet its evidence still counts. */
    @Test
    void aClassTheGraphAlreadyHoldsContributesOnlyEvidence() {
        int main = Intern.intern("app/Main");
        ClassGraph known = new ClassGraph();
        known.insertIfAbsent(main, Scope.objectSym(), new int[0], new int[0], Intern.NONE, Intern.intern("earlier.jar"));
        MemberProbe probe = new MemberProbe(new long[] {MemberKey.of("call", "()V")});
        Extract.ScanSink sink = new Extract.ScanSink(names("lib/Owner", "lib/Elem"), known, true, probe);
        Extract.ScanLeaf leaf = sink.newLeaf();
        byte[] bytes = referencingClass();
        ClassSource source = new ClassSource();
        source.ofBytes(bytes, bytes.length);

        sink.accept(leaf, scratch, Intern.intern("later.jar"), main, source);

        assertEquals(1, leaf.scanned);
        assertEquals(0, leaf.records.n);
        assertNull(leaf.warnings);
        assertEquals(List.of("lib/Owner.call:()V", "lib/Owner.call:()V", "other/Thing.call:()V"), evidenceOf(leaf.invocations));
    }

    /** A class that fails partway leaves no partial record behind, and the rest of the leaf is still scanned. */
    @Test
    void theScanSinkWarnsAboutBrokenClassesAndKeepsTheRest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        ClassFileBytes badRef = ClassFileBytes.header(52, 7);
        badRef.utf8("p/BadRef"); // #1
        badRef.classRef(1); // #2
        badRef.utf8("lib/Owner"); // #3
        badRef.classRef(3); // #4
        badRef.utf8("call"); // #5
        badRef.memberRef(10, 4, 5); // #6: its NameAndType is a Utf8
        badRef.u16(0x0021).u16(2).u16(0).u16(0).u16(0).u16(0).u16(0);
        // No superclass, like java/lang/Object.
        ClassFileBytes root = ClassFileBytes.header(52, 5);
        root.utf8("p/Root"); // #1
        root.classRef(1); // #2
        root.utf8("lib/Owner"); // #3
        root.classRef(3); // #4
        root.u16(0x0021).u16(2).u16(0).u16(0).u16(0).u16(0).u16(0);
        String jar = writeJar(
                dir.resolve("odd.jar"),
                "p/Truncated.class", java.util.Arrays.copyOf(classNamed("p/Truncated", "m"), 20),
                "p/BadRef.class", badRef.toByteArray(),
                "p/Root.class", root.toByteArray(),
                "x/First.class", classNamed("p/One", "m"),
                "x/Second.class", classNamed("p/Two", "m"));

        Scan.Result result = Scan.scanTargetPaths(List.of(jar), libraryOf("lib/Owner"), new MemberProbe(new long[0]), false);

        assertEquals(
                List.of(
                        jar + "!p/Truncated.class: truncated class file at offset 13",
                        jar + "!p/BadRef.class: constant pool #5 is not NameAndType"),
                result.warnings);
        assertEquals(5, result.scannedClasses);
        assertEquals(3, result.graph.size());
        assertFalse(result.graph.contains(Intern.intern("p/BadRef")));
        assertEquals(Intern.NONE, result.graph.superOf(result.graph.node(Intern.intern("p/Root"))));
        assertEquals(List.of(jar + "!p/Root -> CLASS lib/Owner static=null write=null new=null"), recordsOf(result));
        assertEquals(
                java.util.Map.of(Intern.intern("p/One"), "x/First.class", Intern.intern("p/Two"), "x/Second.class"),
                result.entryOverrides);
    }

    /** java/lang/Object is the one class with super_class 0. */
    @Test
    void aClassWithoutSuperclassHasNoSuperName() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(52, 3);
        b.utf8("java/lang/Object"); // #1
        b.classRef(1); // #2
        b.u16(0x0021).u16(2).u16(0).u16(0).u16(0).u16(0).u16(0);
        byte[] bytes = b.toByteArray();
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);
        ClassApi api = Extract.extractApi(p, scratch);
        assertEquals(Intern.NONE, api.superName);
        assertEquals(Intern.NONE, api.nestHost);
        assertNull(api.permitted);
    }
}
