package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Ports the `index.rs` tests. */
class IndexTest {
    private static final String JAVA_LANG_OBJECT = "java/lang/Object";

    /** {name, descriptor} pairs, all public. */
    private static ClassApi classOf(String name, String superName, String[]... methods) {
        ClassApi api = new ClassApi();
        api.name = Intern.intern(name);
        api.access = Acc.PUBLIC;
        api.superName = superName == null ? Intern.NONE : Intern.intern(superName);
        long[] keys = new long[methods.length];
        char[] access = new char[methods.length];
        for (int i = 0; i < methods.length; i++) {
            keys[i] = MemberKey.of(methods[i][0], methods[i][1]);
            access[i] = Acc.PUBLIC;
        }
        ClassApi.sortMembers(keys, access, methods.length, api, true);
        return api;
    }

    private static String[] m(String name, String descriptor) {
        return new String[] {name, descriptor};
    }

    private static int[] syms(String... names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = Intern.intern(names[i]);
        }
        return out;
    }

    private static Scope.Resolution resolveMethod(ApiIndex idx, String owner, String name, String descriptor) {
        return idx.resolve(Intern.intern(owner), MemberKey.of(name, descriptor), Scope.MemberKind.METHOD);
    }

    @Test
    void resolvesMethodOnClassItself() {
        ApiIndex idx = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT, m("m", "()V"))));
        assertEquals(Scope.Resolution.FOUND, resolveMethod(idx, "a/C", "m", "()V"));
    }

    @Test
    void resolvesMethodMovedToSuperclass() {
        // Old has C.m and new moved it to the parent D. Runtime resolution succeeds, so Found.
        ApiIndex idx = ApiIndex.build(List.of(classOf("a/C", "a/D"), classOf("a/D", JAVA_LANG_OBJECT, m("m", "()V"))));
        assertEquals(Scope.Resolution.FOUND, resolveMethod(idx, "a/C", "m", "()V"));
    }

    @Test
    void resolvesMethodThroughInterface() {
        ClassApi c = classOf("a/C", JAVA_LANG_OBJECT);
        c.interfaces = syms("a/I");
        ClassApi i = classOf("a/I", null, m("m", "()V"));
        ApiIndex idx = ApiIndex.build(List.of(c, i));
        assertEquals(Scope.Resolution.FOUND, resolveMethod(idx, "a/C", "m", "()V"));
    }

    @Test
    void missingMethodWithObjectSuperIsNotFound() {
        // The Kt facade case. With Object as the only parent this is conclusively NotFound, not Unknown.
        ApiIndex idx = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT)));
        assertEquals(Scope.Resolution.NOT_FOUND, resolveMethod(idx, "a/C", "gone", "()J"));
    }

    @Test
    void objectBuiltinMethodsResolve() {
        ApiIndex idx = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT)));
        assertEquals(Scope.Resolution.FOUND, resolveMethod(idx, "a/C", "toString", "()Ljava/lang/String;"));
    }

    @Test
    void externalSupertypeYieldsUnknown() {
        // Escaping to a parent outside the index (another library) cannot be proven.
        ApiIndex idx = ApiIndex.build(List.of(classOf("a/C", "ext/Base")));
        assertEquals(Scope.Resolution.UNKNOWN, resolveMethod(idx, "a/C", "m", "()V"));
    }

    @Test
    void unknownOwnerYieldsUnknown() {
        ApiIndex idx = ApiIndex.build(List.of());
        assertEquals(Scope.Resolution.UNKNOWN, resolveMethod(idx, "ext/C", "m", "()V"));
    }

    @Test
    void scopeResolvesAcrossLayeredIndexes() {
        // Resolution can still succeed if a library class hierarchy escapes to a parent from the scanned classpath.
        ApiIndex lib = ApiIndex.build(List.of(classOf("lib/C", "cp/Base")));
        ApiIndex cp = ApiIndex.build(List.of(classOf("cp/Base", JAVA_LANG_OBJECT, m("m", "()V"))));
        Scope scope = new Scope(lib, cp);
        assertEquals(
                Scope.Resolution.FOUND, scope.resolve(Intern.intern("lib/C"), MemberKey.of("m", "()V"), Scope.MemberKind.METHOD));
        assertTrue(scope.containsClass(Intern.intern("cp/Base")));
    }

    @Test
    void duplicateClassIsFirstWins() {
        ClassApi first = classOf("a/C", JAVA_LANG_OBJECT, m("m", "()V"));
        first.access = Acc.PUBLIC;
        ClassApi second = classOf("a/C", JAVA_LANG_OBJECT, m("other", "()V"));
        ApiIndex idx = ApiIndex.build(List.of(first, second));
        assertEquals(Scope.Resolution.FOUND, resolveMethod(idx, "a/C", "m", "()V"));
        assertEquals(Scope.Resolution.NOT_FOUND, resolveMethod(idx, "a/C", "other", "()V"));
        // Duplicates are not appended to the arenas.
        assertEquals(1, idx.classCount());
        assertEquals(1, idx.memberCount());
    }

    /** {name, descriptor, access} triples. */
    private static ClassApi fieldClass(String name, String superName, String[] interfaces, Object[]... fields) {
        ClassApi api = new ClassApi();
        api.name = Intern.intern(name);
        api.access = Acc.PUBLIC;
        api.superName = superName == null ? Intern.NONE : Intern.intern(superName);
        api.interfaces = syms(interfaces);
        long[] keys = new long[fields.length];
        char[] access = new char[fields.length];
        for (int i = 0; i < fields.length; i++) {
            keys[i] = MemberKey.of((String) fields[i][0], (String) fields[i][1]);
            access[i] = (char) (int) (Integer) fields[i][2];
        }
        ClassApi.sortMembers(keys, access, fields.length, api, false);
        return api;
    }

    private static Object[] f(String name, String descriptor, int access) {
        return new Object[] {name, descriptor, access};
    }

    private static String resolvedOwner(ApiIndex idx, String owner, long key, Scope.MemberKind kind) {
        long resolved = new Scope(idx).resolveMember(Intern.intern(owner), key, kind);
        assertTrue(Scope.isFound(resolved), "expected Found, got " + resolved);
        return Intern.str(Scope.foundOwner(resolved));
    }

    @Test
    void fieldResolutionPrefersSuperinterfaceOverSuperclass() {
        // Per JVMS 5.4.3.2 a field on both a superinterface and the superclass resolves
        // to the interface. Getting this wrong attributes the static/access verdict to
        // the wrong owner (interface fields are implicitly static final).
        ClassApi c = fieldClass("a/C", "a/Base", new String[] {"a/I"});
        ClassApi base = fieldClass("a/Base", JAVA_LANG_OBJECT, new String[0], f("x", "I", Acc.PUBLIC));
        ClassApi i = fieldClass("a/I", null, new String[0], f("x", "I", Acc.PUBLIC | Acc.STATIC | Acc.FINAL));
        ApiIndex idx = ApiIndex.build(List.of(c, base, i));
        assertEquals("a/I", resolvedOwner(idx, "a/C", MemberKey.of("x", "I"), Scope.MemberKind.FIELD));
        long resolved = new Scope(idx).resolveMember(Intern.intern("a/C"), MemberKey.of("x", "I"), Scope.MemberKind.FIELD);
        assertEquals(Acc.PUBLIC | Acc.STATIC | Acc.FINAL, Scope.foundAccess(resolved));
    }

    @Test
    void methodResolutionPrefersSuperclassChainOverInterface() {
        // Per JVMS 5.4.3.3 the full superclass chain is searched before any interface,
        // so a method on a grandparent class wins over one on a directly-implemented
        // interface.
        ClassApi c = classOf("a/C", "a/Mid");
        c.interfaces = syms("a/I");
        ClassApi mid = classOf("a/Mid", "a/Grand");
        ClassApi grand = classOf("a/Grand", JAVA_LANG_OBJECT, m("m", "()V"));
        ClassApi i = classOf("a/I", null, m("m", "()V"));
        ApiIndex idx = ApiIndex.build(List.of(c, mid, grand, i));
        assertEquals("a/Grand", resolvedOwner(idx, "a/C", MemberKey.of("m", "()V"), Scope.MemberKind.METHOD));
    }

    @Test
    void fieldResolutionIsUnknownWhenAnInterfaceBranchEscapes() {
        // The field is on the superclass, but an unscanned superinterface could also
        // declare it, so the interface-first search cannot conclude and yields Unknown.
        ClassApi c = fieldClass("a/C", "a/Base", new String[] {"ext/I"});
        ClassApi base = fieldClass("a/Base", JAVA_LANG_OBJECT, new String[0], f("x", "I", Acc.PUBLIC));
        ApiIndex idx = ApiIndex.build(List.of(c, base));
        assertEquals(
                Scope.UNKNOWN, new Scope(idx).resolveMember(Intern.intern("a/C"), MemberKey.of("x", "I"), Scope.MemberKind.FIELD));
    }

    // ---- the Java-only shape of packed results and int-handle entries ----

    @Test
    void aFoundMemberPacksOwnerAndAccessEvenForLargeSymbols() {
        // Owner symbols grow with the scan, so the packing must not lose high bits.
        ClassApi c = classOf("a/Packed", JAVA_LANG_OBJECT);
        long[] keys = {MemberKey.of("m", "()V")};
        char[] access = {(char) 0xffff};
        ClassApi.sortMembers(keys, access, 1, c, true);
        ApiIndex idx = ApiIndex.build(List.of(c));
        long resolved = new Scope(idx).resolveMember(c.name, keys[0], Scope.MemberKind.METHOD);
        assertTrue(Scope.isFound(resolved));
        assertEquals(c.name, Scope.foundOwner(resolved));
        assertEquals(0xffff, Scope.foundAccess(resolved));
        assertFalse(Scope.isFound(Scope.NOT_FOUND));
        assertFalse(Scope.isFound(Scope.UNKNOWN));
    }

    @Test
    void constructorsAreNotInherited() {
        ClassApi c = classOf("a/Sub", "a/Super");
        ClassApi s = classOf("a/Super", JAVA_LANG_OBJECT, m("<init>", "(Z)V"));
        ApiIndex idx = ApiIndex.build(List.of(c, s));
        assertEquals(Scope.Resolution.NOT_FOUND, resolveMethod(idx, "a/Sub", "<init>", "(Z)V"));
        assertEquals(Scope.Resolution.FOUND, resolveMethod(idx, "a/Super", "<init>", "(Z)V"));
        assertEquals(Scope.Resolution.UNKNOWN, resolveMethod(idx, "ext/Gone", "<init>", "(Z)V"));
    }

    @Test
    void sealingStaysThreeValuedInTheIndex() {
        ClassApi unsealed = classOf("a/Unsealed", JAVA_LANG_OBJECT);
        ClassApi permitsNothing = classOf("a/PermitsNothing", JAVA_LANG_OBJECT);
        permitsNothing.permitted = new int[0];
        ClassApi sealed = classOf("a/Sealed", JAVA_LANG_OBJECT);
        sealed.interfaces = syms("a/I");
        sealed.permitted = syms("a/P", "a/Q");
        sealed.nestHost = Intern.intern("a/Host");
        ClassApi unknown = classOf("a/Unknown", JAVA_LANG_OBJECT);
        unknown.sealingUnknown = true;
        ApiIndex idx = ApiIndex.build(List.of(unsealed, permitsNothing, sealed, unknown));

        assertEquals(-1, idx.permittedCount(idx.entry(unsealed.name)));
        assertEquals(0, idx.permittedCount(idx.entry(permitsNothing.name)));
        int entry = idx.entry(sealed.name);
        assertEquals(2, idx.permittedCount(entry));
        assertTrue(idx.permits(entry, Intern.intern("a/Q")));
        assertFalse(idx.permits(entry, Intern.intern("a/I")));
        assertEquals(1, idx.interfaceCount(entry));
        assertEquals(Intern.intern("a/I"), idx.interfaceAt(entry, 0));
        assertEquals(Intern.intern("a/Host"), idx.nestHostOf(entry));
        assertFalse(idx.sealingUnknown(entry));
        assertTrue(idx.sealingUnknown(idx.entry(unknown.name)));
        assertEquals(Acc.PUBLIC, idx.accessOf(idx.entry(unknown.name)));
        assertEquals(-1, idx.entry(Intern.intern("a/Absent")));
        assertEquals(-1, idx.classAccess(Intern.intern("a/Absent")));
    }

    @Test
    void classGraphIsFirstWinsAndKeepsItsRanges() {
        ClassGraph graph = new ClassGraph();
        int name = Intern.intern("g/C");
        assertTrue(graph.insertIfAbsent(
                name, Intern.intern("g/Base"), syms("g/I", "g/J"), syms("g/R"), Intern.intern("g/Host"), Intern.intern("first.jar")));
        assertFalse(graph.insertIfAbsent(name, Intern.NONE, syms(), syms(), Intern.NONE, Intern.intern("second.jar")));
        assertTrue(graph.insertIfAbsent(Intern.intern("g/D"), Intern.NONE, syms("g/K"), syms(), Intern.NONE, Intern.intern("first.jar")));

        assertEquals(2, graph.size());
        assertTrue(graph.contains(name));
        assertFalse(graph.contains(Intern.intern("g/Missing")));
        assertEquals(-1, graph.node(Intern.intern("g/Missing")));
        int node = graph.node(name);
        assertEquals(name, graph.nameOf(node));
        assertEquals("g/Base", Intern.str(graph.superOf(node)));
        assertEquals("g/Host", Intern.str(graph.nestHostOf(node)));
        assertEquals("first.jar", Intern.str(graph.sourceOf(node)));
        assertEquals(2, graph.interfaceCount(node));
        assertEquals("g/J", Intern.str(graph.interfaceAt(node, 1)));
        assertEquals(1, graph.refCount(node));
        assertEquals("g/R", Intern.str(graph.refAt(node, 0)));
        int other = graph.node(Intern.intern("g/D"));
        assertEquals(Intern.NONE, graph.superOf(other));
        assertEquals("g/K", Intern.str(graph.interfaceAt(other, 0)));
        assertEquals(0, graph.refCount(other));
    }

    /** A symbol interned after the graph was last grown is simply absent, not out of bounds. */
    @Test
    void classGraphAnswersForSymbolsInternedLater() {
        ClassGraph graph = new ClassGraph();
        graph.insertIfAbsent(Intern.intern("g/Early"), Intern.NONE, syms(), syms(), Intern.NONE, Intern.intern("x.jar"));
        int later = Intern.intern("g/Later-" + System.nanoTime());
        assertFalse(graph.contains(later));
        assertEquals(-1, graph.node(later));
    }
}
