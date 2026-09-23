package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The unit tests of cli/src/check.rs. The selection walk (AbstractMethodError, conflicting
 * defaults, invocation evidence) lives in {@link CheckSelectionTest}.
 */
final class CheckTest {
    static final String JAVA_LANG_OBJECT = "java/lang/Object";

    record Member(String name, String descriptor, int access) {}

    static Member m(String name, String descriptor, int access) {
        return new Member(name, descriptor, access);
    }

    static int intern(String s) {
        return Intern.intern(s);
    }

    static int[] syms(String... names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = intern(names[i]);
        }
        return out;
    }

    /** Rust build_members. */
    static void setMembers(ClassApi api, boolean methods, Member... members) {
        long[] keys = new long[members.length];
        char[] access = new char[members.length];
        for (int i = 0; i < members.length; i++) {
            keys[i] = MemberKey.of(members[i].name(), members[i].descriptor());
            access[i] = (char) members[i].access();
        }
        ClassApi.sortMembers(keys, access, members.length, api, methods);
    }

    /** Public class extending Object. {@code methodPairs} alternates name and descriptor, all public. */
    static ClassApi classApi(String name, String... methodPairs) {
        Member[] methods = new Member[methodPairs.length / 2];
        for (int i = 0; i < methods.length; i++) {
            methods[i] = m(methodPairs[i * 2], methodPairs[i * 2 + 1], Acc.PUBLIC);
        }
        return classWithMethodAccess(name, methods);
    }

    static ClassApi classWithMethodAccess(String name, Member... methods) {
        ClassApi api = new ClassApi();
        api.name = intern(name);
        api.access = Acc.PUBLIC;
        api.superName = intern(JAVA_LANG_OBJECT);
        setMembers(api, true, methods);
        return api;
    }

    static ClassApi classWithFields(String name, Member... fields) {
        ClassApi api = new ClassApi();
        api.name = intern(name);
        api.access = Acc.PUBLIC;
        api.superName = intern(JAVA_LANG_OBJECT);
        setMembers(api, false, fields);
        return api;
    }

    private static SymbolRef methodRef(String owner, String name, String desc) {
        return new SymbolRef(RefKind.METHOD, intern(owner), MemberKey.of(name, desc), null, null, null);
    }

    private static SymbolRef staticMethodRef(String owner, String name, String desc) {
        return new SymbolRef(RefKind.METHOD, intern(owner), MemberKey.of(name, desc), Boolean.TRUE, null, null);
    }

    private static SymbolRef fieldWriteRef(String owner, String name, String desc) {
        return new SymbolRef(RefKind.FIELD, intern(owner), MemberKey.of(name, desc), Boolean.FALSE, Boolean.TRUE, null);
    }

    private static SymbolRef methodRefExpectingInstance(String owner, String name, String desc) {
        return new SymbolRef(RefKind.METHOD, intern(owner), MemberKey.of(name, desc), Boolean.FALSE, null, null);
    }

    private static SymbolRef staticFieldRef(String owner, String name, String desc) {
        return new SymbolRef(RefKind.FIELD, intern(owner), MemberKey.of(name, desc), Boolean.TRUE, Boolean.TRUE, null);
    }

    private static SymbolRef classRef(String owner) {
        return new SymbolRef(RefKind.CLASS, intern(owner), MemberKey.NONE, null, null, null);
    }

    private static SymbolRef newRef(String owner) {
        return new SymbolRef(RefKind.CLASS, intern(owner), MemberKey.NONE, null, null, Boolean.TRUE);
    }

    private static SymbolRef interfaceMethodRef(String owner, String name, String desc) {
        return new SymbolRef(RefKind.INTERFACE_METHOD, intern(owner), MemberKey.of(name, desc), Boolean.FALSE, null, null);
    }

    private static ClassApi abstractClass(String name) {
        ClassApi c = classApi(name);
        c.access = Acc.PUBLIC | Acc.ABSTRACT;
        return c;
    }

    private static ClassApi iface(String name) {
        ClassApi c = classApi(name);
        c.access = Acc.PUBLIC | Acc.INTERFACE | Acc.ABSTRACT;
        return c;
    }

    private static ClassApi finalClass(String name) {
        ClassApi c = classApi(name);
        c.access = Acc.PUBLIC | Acc.FINAL;
        return c;
    }

    private static ApiIndex index(ClassApi... apis) {
        return ApiIndex.build(List.of(apis));
    }

    private static Check.Verdict verdict(SymbolRef r, String sourceClass, Scope oldScope, Scope runtime, ClassGraph graph) {
        return Check.verdictOf(r, intern(sourceClass), oldScope, runtime, graph);
    }

    private static void assertOk(Check.Verdict v) {
        assertEquals(Check.Verdict.Kind.OK, v.kind());
    }

    private static void assertUnknown(Check.Verdict v) {
        assertEquals(Check.Verdict.Kind.UNKNOWN, v.kind());
    }

    /** Rust {@code broken(v).unwrap()}. */
    private static Check.Verdict broken(Check.Verdict v) {
        assertEquals(Check.Verdict.Kind.BROKEN, v.kind());
        return v;
    }

    private static void insert(ClassGraph graph, String name, int superName, int[] interfaces, int nestHost, String source) {
        graph.insertIfAbsent(intern(name), superName, interfaces, new int[0], nestHost, intern(source));
    }

    @Test
    void brokenWhenRemovedInNewButResolvableInOld() {
        ApiIndex oldLib = index(classApi("lib/C", "m", "()J"));
        ApiIndex newLib = index(classApi("lib/C"));
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()J"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.METHOD_REMOVED, broken(v).reason());
    }

    @Test
    void okWhenMethodMovedToSuperclass() {
        ApiIndex oldLib = index(classApi("lib/C", "m", "()V"));
        ClassApi c = classApi("lib/C");
        c.superName = intern("lib/Base");
        ApiIndex newLib = index(c, classApi("lib/Base", "m", "()V"));
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    @Test
    void unresolvedInBothIsNotReported() {
        ApiIndex oldLib = index(classApi("lib/C"));
        ApiIndex newLib = index(classApi("lib/C"));
        Check.Verdict v =
                verdict(methodRef("lib/C", "phantom", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    @Test
    void classRemovalCollapsesMemberRefs() {
        ApiIndex oldLib = index(classApi("lib/C", "m", "()V"));
        ApiIndex newLib = index();
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.CLASS_REMOVED, broken(v).reason());
        assertEquals(RefKind.CLASS, v.reference().kind());
        assertFalse(v.reference().hasMember());
    }

    @Test
    void classProvidedByScannedClasspathIsNotReported() {
        // Copies bundled into fat JARs or moves to another artifact are not a violation if the
        // runtime classpath provides the class. The graph handles existence, fetched handles members.
        ApiIndex oldLib = index(classApi("lib/C", "m", "()V"));
        ApiIndex newLib = index();
        ApiIndex fetched = index(classApi("lib/C", "m", "()V"));
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/C", Scope.objectSym(), new int[0], Intern.NONE, "fat.jar");
        Check.Verdict v =
                verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib, fetched), new Scope(newLib, fetched), graph);
        assertOk(v);
    }

    @Test
    void unknownWhenRuntimeHierarchyEscapesScope() {
        ApiIndex oldLib = index(classApi("lib/C", "m", "()V"));
        ClassApi c = classApi("lib/C");
        c.superName = intern("ext/Base");
        ApiIndex newLib = index(c);
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertUnknown(v);
    }

    @Test
    void graphOnlyClassWithoutFetchIsUnknown() {
        // Resolution through a class present in the graph but not fetched is conservatively Unknown.
        ApiIndex oldLib = index(classApi("lib/C", "m", "()V"));
        ClassApi c = classApi("lib/C");
        c.superName = intern("cp/Base");
        ApiIndex newLib = index(c);
        ClassGraph graph = new ClassGraph();
        insert(graph, "cp/Base", Scope.objectSym(), new int[0], Intern.NONE, "cp.jar");
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), graph);
        assertUnknown(v);
    }

    @Test
    void newOnClassThatBecameAbstractIsBroken() {
        ApiIndex oldLib = index(classApi("lib/C"));
        ApiIndex newLib = index(abstractClass("lib/C"));
        Check.Verdict v = verdict(newRef("lib/C"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.CLASS_BECAME_ABSTRACT, broken(v).reason());
    }

    @Test
    void newOnClassThatBecameInterfaceIsBroken() {
        ApiIndex oldLib = index(classApi("lib/C"));
        ApiIndex newLib = index(iface("lib/C"));
        Check.Verdict v = verdict(newRef("lib/C"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.CLASS_BECAME_ABSTRACT, broken(v).reason());
    }

    @Test
    void newOnAlreadyAbstractClassIsNotReported() {
        // A class abstract on both sides was never valid to instantiate, so the reference is
        // pre-existing inconsistency, not this upgrade's breakage.
        ApiIndex oldLib = index(abstractClass("lib/C"));
        ApiIndex newLib = index(abstractClass("lib/C"));
        Check.Verdict v = verdict(newRef("lib/C"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    @Test
    void plainClassRefToNewlyAbstractClassIsOk() {
        // Only `new` breaks. A type reference (field type, cast) to an abstract class stays valid.
        ApiIndex oldLib = index(classApi("lib/C"));
        ApiIndex newLib = index(abstractClass("lib/C"));
        Check.Verdict v = verdict(classRef("lib/C"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    /**
     * lib/C moved to another artifact that is still on the classpath. Pass 2 fetches no flags
     * for a class-only reference, so existence passes it like any other class reference.
     */
    @Test
    void newOnAClassMovedToTheScannedClasspathIsOk() {
        ApiIndex oldLib = index(classApi("lib/C"));
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/C", Scope.objectSym(), new int[0], Intern.NONE, "moved.jar");
        assertOk(verdict(newRef("lib/C"), "app/Use", new Scope(oldLib), new Scope(index()), graph));
    }

    @Test
    void methodrefOwnerThatBecameInterfaceIsBroken() {
        // A Methodref (compiled against a class) whose owner is now an interface makes
        // resolution throw IncompatibleClassChangeError.
        ApiIndex oldLib = index(classApi("lib/C", "m", "()V"));
        ClassApi c = iface("lib/C");
        setMembers(c, true, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT));
        ApiIndex newLib = index(c);
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.CLASS_BECAME_INTERFACE, broken(v).reason());
    }

    @Test
    void interfaceMethodrefOwnerThatBecameClassIsBroken() {
        ApiIndex oldLib = index(iface("lib/I"));
        ApiIndex newLib = index(classApi("lib/I", "m", "()V"));
        Check.Verdict v =
                verdict(interfaceMethodRef("lib/I", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.INTERFACE_BECAME_CLASS, broken(v).reason());
    }

    @Test
    void matchingOwnerKindIsNotReported() {
        // Owner stayed a class, so a Methodref resolves normally.
        ApiIndex oldLib = index(classApi("lib/C", "m", "()V"));
        ApiIndex newLib = index(classApi("lib/C", "m", "()V"));
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    @Test
    void extendsClassThatBecameInterfaceIsBroken() {
        ApiIndex oldLib = index(classApi("lib/Base"));
        ApiIndex newLib = index(iface("lib/Base"));
        ClassGraph graph = new ClassGraph();
        insert(graph, "app/Sub", intern("lib/Base"), new int[0], Intern.NONE, "app.jar");
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addKindFlipViolations(oldLib, newLib, graph, violations, seen);
        assertEquals(1, violations.size());
        assertEquals(Reason.CLASS_BECAME_INTERFACE, violations.get(0).reason);
        assertEquals("lib/Base", Intern.str(violations.get(0).reference.owner()));
        assertEquals("app/Sub", Intern.str(violations.get(0).sourceClass));
    }

    @Test
    void implementsInterfaceThatBecameClassIsBroken() {
        ApiIndex oldLib = index(iface("lib/I"));
        ApiIndex newLib = index(classApi("lib/I"));
        ClassGraph graph = new ClassGraph();
        insert(graph, "app/Impl", Scope.objectSym(), syms("lib/I"), Intern.NONE, "app.jar");
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addKindFlipViolations(oldLib, newLib, graph, violations, seen);
        assertEquals(1, violations.size());
        assertEquals(Reason.INTERFACE_BECAME_CLASS, violations.get(0).reason);
        assertEquals("lib/I", Intern.str(violations.get(0).reference.owner()));
    }

    @Test
    void stableKindHierarchyIsNotReported() {
        ApiIndex oldLib = index(classApi("lib/Base"), iface("lib/I"));
        ApiIndex newLib = index(classApi("lib/Base"), iface("lib/I"));
        ClassGraph graph = new ClassGraph();
        insert(graph, "app/Sub", intern("lib/Base"), syms("lib/I"), Intern.NONE, "app.jar");
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addKindFlipViolations(oldLib, newLib, graph, violations, seen);
        assertTrue(violations.isEmpty());
    }

    // ---- sealing walk (addSealedViolations) ----

    private static ClassApi sealedInterface(String name, String... permits) {
        ClassApi c = iface(name);
        c.permitted = syms(permits);
        return c;
    }

    /** One scanned class, reached over the interface edge unless {@code asSuper} names a superclass. */
    private static List<Violation> sealedViolations(ApiIndex oldLib, ApiIndex newLib, String sub, String owner, boolean asSuper) {
        ClassGraph graph = new ClassGraph();
        if (asSuper) {
            insert(graph, sub, intern(owner), new int[0], Intern.NONE, "app.jar");
        } else {
            insert(graph, sub, Scope.objectSym(), syms(owner), Intern.NONE, "app.jar");
        }
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addSealedViolations(oldLib, newLib, graph, violations, seen);
        return violations;
    }

    @Test
    void implementingANewlySealedInterfaceIsBroken() {
        ApiIndex oldLib = index(iface("lib/I"));
        ApiIndex newLib = index(sealedInterface("lib/I", "lib/Known"));
        List<Violation> violations = sealedViolations(oldLib, newLib, "app/Impl", "lib/I", false);
        assertEquals(1, violations.size());
        assertEquals(Reason.CLASS_BECAME_SEALED, violations.get(0).reason);
        assertEquals("lib/I", Intern.str(violations.get(0).reference.owner()));
        assertEquals("app/Impl", Intern.str(violations.get(0).sourceClass));
    }

    @Test
    void extendingANewlySealedClassIsBroken() {
        ApiIndex oldLib = index(classApi("lib/Base"));
        ClassApi c = classApi("lib/Base");
        c.permitted = syms("lib/Known");
        ApiIndex newLib = index(c);
        List<Violation> violations = sealedViolations(oldLib, newLib, "app/Sub", "lib/Base", true);
        assertEquals(1, violations.size());
        assertEquals(Reason.CLASS_BECAME_SEALED, violations.get(0).reason);
    }

    @Test
    void aPermittedSubclassIsNotReported() {
        ApiIndex oldLib = index(iface("lib/I"));
        ApiIndex newLib = index(sealedInterface("lib/I", "app/Impl", "lib/Known"));
        assertTrue(sealedViolations(oldLib, newLib, "app/Impl", "lib/I", false).isEmpty());
    }

    @Test
    void droppingANameFromPermitsIsBroken() {
        ApiIndex oldLib = index(sealedInterface("lib/I", "app/Impl"));
        ApiIndex newLib = index(sealedInterface("lib/I", "lib/Known"));
        List<Violation> violations = sealedViolations(oldLib, newLib, "app/Impl", "lib/I", false);
        assertEquals(1, violations.size());
        assertEquals(Reason.CLASS_BECAME_SEALED, violations.get(0).reason);
    }

    @Test
    void alreadyUnpermittedInOldIsPreExisting() {
        // Never loaded under old either, so the upgrade did not break it.
        ApiIndex oldLib = index(sealedInterface("lib/I", "lib/Known"));
        ApiIndex newLib = index(sealedInterface("lib/I", "lib/Known"));
        assertTrue(sealedViolations(oldLib, newLib, "app/Impl", "lib/I", false).isEmpty());
    }

    @Test
    void aNewlySealedEnumIsNotReported() {
        // javac seals enums with constant-specific bodies, so this fires on a bare recompile.
        ApiIndex oldLib = index(classApi("lib/E"));
        ClassApi c = classApi("lib/E");
        c.access = Acc.PUBLIC | Acc.ENUM;
        c.permitted = syms("lib/E$1");
        ApiIndex newLib = index(c);
        assertTrue(sealedViolations(oldLib, newLib, "lib/E$2", "lib/E", true).isEmpty());
    }

    @Test
    void anUnreadableOldSealingAttributeReportsNothing() {
        // A corrupt old class file must not read as "unsealed" and so manufacture a break.
        ClassApi c = iface("lib/I");
        c.sealingUnknown = true;
        ApiIndex oldLib = index(c);
        ApiIndex newLib = index(sealedInterface("lib/I", "lib/Known"));
        assertTrue(sealedViolations(oldLib, newLib, "app/Impl", "lib/I", false).isEmpty());
    }

    @Test
    void sealingAClassThatUsedToBeFinalIsPreExisting() {
        // final -> sealed is compatible (JLS 13.4.2). A final class had no subclasses, so a
        // scanned one was already broken before the upgrade and `extends final class` owns it.
        ApiIndex oldLib = index(finalClass("lib/C"));
        ClassApi c = classApi("lib/C");
        c.permitted = syms("lib/Known");
        ApiIndex newLib = index(c);
        assertTrue(sealedViolations(oldLib, newLib, "app/Sub", "lib/C", true).isEmpty());
    }

    @Test
    void aNewlySealedFinalClassIsNotReported() {
        // `extends final class` already reports this edge with the reason that explains it.
        ApiIndex oldLib = index(classApi("lib/C"));
        ClassApi c = finalClass("lib/C");
        c.permitted = syms("lib/Known");
        ApiIndex newLib = index(c);
        assertTrue(sealedViolations(oldLib, newLib, "app/Sub", "lib/C", true).isEmpty());
    }

    // ---- SPI provider walk (addSpiViolations) ----

    private static Reach.ServiceFile serviceFile(String iface, int[] impls, String source) {
        return new Reach.ServiceFile(intern(iface), impls, intern(source));
    }

    /**
     * The common case. lib/Impl is registered for lib/Spi on both sides, the provider is inside
     * the compared library, and there is no classpath data. Tests that vary the service files or the classpath
     * use the two helpers below directly.
     */
    private static List<Violation> spiCheck(ApiIndex oldLib, ApiIndex newLib) {
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "new.jar"));
        return spiViolations(oldLib, newLib, oldSvc, newSvc);
    }

    private static List<Violation> spiViolations(
            ApiIndex oldLib, ApiIndex newLib, List<Reach.ServiceFile> oldServices, List<Reach.ServiceFile> newServices) {
        return spiViolationsWithClasspath(oldLib, newLib, new ClassGraph(), new ApiIndex(), oldServices, newServices);
    }

    /**
     * {@code graph} and {@code fetched} stand in for what pass 1 (hierarchy) and pass 2
     * (member tables, via collectSpiWanted) would have produced for scanned-classpath providers.
     */
    private static List<Violation> spiViolationsWithClasspath(
            ApiIndex oldLib,
            ApiIndex newLib,
            ClassGraph graph,
            ApiIndex fetched,
            List<Reach.ServiceFile> oldServices,
            List<Reach.ServiceFile> newServices) {
        Scope oldScope = new Scope(oldLib, fetched);
        Scope runtimeScope = new Scope(newLib, fetched);
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addSpiViolations(graph, oldScope, runtimeScope, new Check.SpiServices(oldServices, newServices), violations, seen);
        return violations;
    }

    /** A provider with a public no-arg constructor that implements {@code iface}, the common shape. */
    private static ClassApi instantiableProvider(String name, String iface) {
        ClassApi c = classWithMethodAccess(name, m("<init>", "()V", Acc.PUBLIC));
        c.interfaces = syms(iface);
        return c;
    }

    @Test
    void providerRemovedFromNewIsBroken() {
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index();
        List<Violation> violations = spiCheck(oldLib, newLib);
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_REMOVED, violations.get(0).reason);
        assertEquals("lib/Impl", Intern.str(violations.get(0).sourceClass));
        assertEquals("lib/Spi", Intern.str(violations.get(0).reference.owner()));
        assertFalse(violations.get(0).reference.hasMember());
        // Attributed to the jar that still registers the now-missing provider.
        assertEquals("new.jar", Intern.str(violations.get(0).source));
    }

    @Test
    void providerBecameAbstractIsBroken() {
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(abstractClass("lib/Impl"));
        List<Violation> violations = spiCheck(oldLib, newLib);
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, violations.get(0).reason);
    }

    @Test
    void providerBecameAnInterfaceIsBroken() {
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(iface("lib/Impl"));
        List<Violation> violations = spiCheck(oldLib, newLib);
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, violations.get(0).reason);
    }

    @Test
    void providerLostItsPublicNoArgConstructorIsBroken() {
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(classWithMethodAccess("lib/Impl", m("<init>", "()V", Acc.PRIVATE)));
        List<Violation> violations = spiCheck(oldLib, newLib);
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, violations.get(0).reason);
    }

    @Test
    void providerNarrowedToPackagePrivateClassIsBroken() {
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ClassApi c = instantiableProvider("lib/Impl", "lib/Spi");
        c.access = 0; // package-private, no longer public
        ApiIndex newLib = index(c);
        List<Violation> violations = spiCheck(oldLib, newLib);
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, violations.get(0).reason);
    }

    @Test
    void providerNoLongerAssignableToTheServiceInterfaceIsBroken() {
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        // public, concrete, has a constructor, but no longer `implements Spi`
        ApiIndex newLib = index(classWithMethodAccess("lib/Impl", m("<init>", "()V", Acc.PUBLIC)));
        List<Violation> violations = spiCheck(oldLib, newLib);
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, violations.get(0).reason);
    }

    @Test
    void aDroppedInterfaceIsReportedWhenTheNewJarIsAScanTarget() {
        // Same break as above, but the provider also has a graph node carrying the NEW
        // hierarchy (the upgraded jar is a scan target, as in every real run). The old-side
        // gate must judge assignability by the old library's own edges, not the graph's.
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(classWithMethodAccess("lib/Impl", m("<init>", "()V", Acc.PUBLIC)));
        ClassGraph graph = new ClassGraph();
        // no longer implements Spi
        insert(graph, "lib/Impl", Scope.objectSym(), new int[0], Intern.NONE, "new.jar");
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "new.jar"));
        List<Violation> violations = spiViolationsWithClasspath(oldLib, newLib, graph, new ApiIndex(), oldSvc, newSvc);
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, violations.get(0).reason);
    }

    @Test
    void aProviderFactoryDoesNotRescueALostConstructor() {
        // ServiceLoader honors a public static provider() factory only for providers in
        // explicit modules resolved from `provides` directives. The class-path iterator
        // that reads META-INF/services requires the public no-arg constructor
        // unconditionally, so losing it is a break no factory can compensate.
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(classWithMethodAccess("lib/Impl", m("provider", "()Llib/Spi;", Acc.PUBLIC | Acc.STATIC)));
        List<Violation> violations = spiCheck(oldLib, newLib);
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, violations.get(0).reason);
    }

    @Test
    void aStrayProviderFactoryOnAValidProviderIsNotReported() {
        // The mirror direction. The factory is ignored, so a method that merely happens to
        // be named provider() must not break an otherwise valid provider.
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ClassApi c = instantiableProvider("lib/Impl", "lib/Spi");
        setMembers(c, true, m("<init>", "()V", Acc.PUBLIC), m("provider", "()V", Acc.PUBLIC | Acc.STATIC));
        ApiIndex newLib = index(c);
        assertTrue(spiCheck(oldLib, newLib).isEmpty());
    }

    @Test
    void providerBrokenOnBothSidesIsPreExisting() {
        // Never a usable provider under old either, so the upgrade did not break it.
        ApiIndex oldLib = index(abstractClass("lib/Impl"));
        ApiIndex newLib = index(abstractClass("lib/Impl"));
        assertTrue(spiCheck(oldLib, newLib).isEmpty());
    }

    @Test
    void aNewlyAddedProviderIsNotReported() {
        // Only in new's service file, so there is nothing to regress against.
        ApiIndex oldLib = index();
        ApiIndex newLib = index(abstractClass("lib/Impl"));
        List<Reach.ServiceFile> oldSvc = List.of();
        List<Reach.ServiceFile> newSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "new.jar"));
        assertTrue(spiViolations(oldLib, newLib, oldSvc, newSvc).isEmpty());
    }

    @Test
    void aProviderDroppedFromNewsServiceFileIsNotReported() {
        // Arm B (silent provider loss) is out of scope for now, see the addSpiViolations docs.
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(abstractClass("lib/Impl"));
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of();
        assertTrue(spiViolations(oldLib, newLib, oldSvc, newSvc).isEmpty());
    }

    @Test
    void aStillInstantiableProviderIsNotReported() {
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        assertTrue(spiCheck(oldLib, newLib).isEmpty());
    }

    @Test
    void duplicateRegistrationsReportOnce() {
        // ServiceLoader dedups provider names across configuration files (first
        // registration wins), so two new-side jars listing the same broken provider are
        // one runtime failure, attributed to the first registering jar.
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(abstractClass("lib/Impl"));
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of(
                serviceFile("lib/Spi", syms("lib/Impl"), "new.jar"), serviceFile("lib/Spi", syms("lib/Impl"), "other.jar"));
        List<Violation> violations = spiViolations(oldLib, newLib, oldSvc, newSvc);
        assertEquals(1, violations.size());
        assertEquals("new.jar", Intern.str(violations.get(0).source));
    }

    @Test
    void anUnfetchedClasspathProviderIsUnknownNotRemoved() {
        // The provider is gone from the library but a scan target still carries it. With
        // no fetched member table its shape is unprovable, which must suppress, never
        // read as "removed".
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index();
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/Impl", Scope.objectSym(), syms("lib/Spi"), Intern.NONE, "unrelated.jar");
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "new.jar"));
        assertTrue(spiViolationsWithClasspath(oldLib, newLib, graph, new ApiIndex(), oldSvc, newSvc).isEmpty());
    }

    @Test
    void aProviderMovedToAnUnchangedRuntimeArtifactIsNotReported() {
        // The upgrade extracted lib/Impl out of the checked library into a different,
        // unchanged JAR that stays on the runtime classpath (the same "moves to another
        // artifact are not violations" shape every other check in this file honors).
        // ServiceLoader still finds and constructs it fine, so "not in new" alone must not be
        // read as removed.
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(); // lib/Impl no longer ships with the checked library
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/Impl", Scope.objectSym(), syms("lib/Spi"), Intern.NONE, "unrelated.jar");
        // What collectSpiWanted + pass 2 would have fetched for this graph-only class.
        ApiIndex fetched = index(instantiableProvider("lib/Impl", "lib/Spi"));
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "new.jar"));
        assertTrue(spiViolationsWithClasspath(oldLib, newLib, graph, fetched, oldSvc, newSvc).isEmpty());
    }

    // ---- collectSpiWanted ----

    private static IntSet spiWanted(
            ApiIndex oldLib, ApiIndex newLib, ClassGraph graph, List<Reach.ServiceFile> oldSvc, List<Reach.ServiceFile> newSvc) {
        IntSet wanted = new IntSet();
        Check.collectSpiWanted(new Check.SpiServices(oldSvc, newSvc), oldLib, newLib, graph, wanted);
        return wanted;
    }

    @Test
    void collectSpiWantedAddsStillListedGraphOnlyProviders() {
        ApiIndex oldLib = index();
        ApiIndex newLib = index();
        ClassGraph graph = new ClassGraph();
        for (String name : new String[] {"lib/Impl", "lib/OnlyOldListed"}) {
            insert(graph, name, Scope.objectSym(), new int[0], Intern.NONE, "x");
        }
        // Only providers listed on BOTH sides are ever judged, so only those are fetched.
        // A name in no scan target has nothing to fetch from.
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl", "lib/OnlyOldListed"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl", "lib/Ghost"), "new.jar"));
        IntSet wanted = spiWanted(oldLib, newLib, graph, oldSvc, newSvc);
        assertTrue(wanted.contains(intern("lib/Impl")));
        assertFalse(wanted.contains(intern("lib/OnlyOldListed")));
        assertFalse(wanted.contains(intern("lib/Ghost")));
    }

    @Test
    void collectSpiWantedFetchesAShadowCopyStillOnTheClasspath() {
        // In the old index AND in an unchanged scan-target jar, dropped from new. The
        // runtime side can only resolve the surviving copy through fetched.
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index();
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/Impl", Scope.objectSym(), syms("lib/Spi"), Intern.NONE, "unrelated.jar");
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "new.jar"));
        IntSet wanted = spiWanted(oldLib, newLib, graph, oldSvc, newSvc);
        assertTrue(wanted.contains(intern("lib/Impl")));
    }

    @Test
    void collectSpiWantedSkipsProvidersShippedInBothIndexes() {
        ApiIndex oldLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ApiIndex newLib = index(instantiableProvider("lib/Impl", "lib/Spi"));
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/Impl", Scope.objectSym(), new int[0], Intern.NONE, "x");
        List<Reach.ServiceFile> oldSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "old.jar"));
        List<Reach.ServiceFile> newSvc = List.of(serviceFile("lib/Spi", syms("lib/Impl"), "new.jar"));
        assertTrue(spiWanted(oldLib, newLib, graph, oldSvc, newSvc).isEmpty());
    }

    // ---- access ----

    @Test
    void memberAccessNarrowedFromPublicIsReported() {
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithFields("lib/C", m("x", "I", Acc.PRIVATE)));
        Check.Verdict v = verdict(fieldWriteRef("lib/C", "x", "I"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.FIELD_ACCESS_NARROWED, broken(v).reason());
    }

    @Test
    void memberEquallyInaccessibleInOldIsNotReported() {
        // A renamed copy of the library on the scanned classpath. Its nest-internal
        // private references resolve as private against both sides. Pre-existing,
        // not narrowing.
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PRIVATE)));
        ApiIndex newLib = index(classWithFields("lib/C", m("x", "I", Acc.PRIVATE)));
        Check.Verdict v =
                verdict(fieldWriteRef("lib/C", "x", "I"), "lib/C$Builder", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    @Test
    void nestInternalPrivateAccessIsLegalEvenWhenNarrowed() {
        // public -> private is a real narrowing, but a nestmate (same nest host per
        // the NestHost attribute) keeps private access at runtime (JVMS 5.4.4).
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithFields("lib/C", m("x", "I", Acc.PRIVATE)));
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/C$Builder", Scope.objectSym(), new int[0], intern("lib/C"), "cp.jar");
        insert(graph, "lib/C", Scope.objectSym(), new int[0], Intern.NONE, "cp.jar");
        Check.Verdict v = verdict(fieldWriteRef("lib/C", "x", "I"), "lib/C$Builder", new Scope(oldLib), new Scope(newLib), graph);
        assertOk(v);
    }

    @Test
    void sharedNamePrefixIsNotANest() {
        // Classes without nest attributes are their own nest hosts. Neither a
        // name-prefix lookalike nor a same-simple-name class in another package
        // gains private access.
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithFields("lib/C", m("x", "I", Acc.PRIVATE)));
        for (String source : new String[] {"lib/CX", "other/C$Inner"}) {
            Check.Verdict v = verdict(fieldWriteRef("lib/C", "x", "I"), source, new Scope(oldLib), new Scope(newLib), new ClassGraph());
            assertEquals(Check.Verdict.Kind.BROKEN, v.kind(), source);
            assertEquals(Reason.FIELD_ACCESS_NARROWED, v.reason(), source);
        }
    }

    @Test
    void protectedToPrivateIsReportedWithoutHierarchyProof() {
        // The old side is compared by access level, not by re-running isAccessible.
        // The subclass walk only sees scanned classes, so a chain through the library
        // would wrongly demote real narrowing to pre-existing.
        ApiIndex oldLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PROTECTED)));
        ApiIndex newLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PRIVATE)));
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Sub", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.METHOD_ACCESS_NARROWED, broken(v).reason());
    }

    @Test
    void protectedNarrowingIsUnknownWhenSubclassChainEscapesScope() {
        // new C.m is protected and the caller is in a different package, so access
        // hinges on whether app/Sub is a subclass of lib/C. Its super chain leaves
        // analyzed scope, so the relationship is unprovable and the reference must be
        // Unknown, not a false "method access narrowed".
        ApiIndex oldLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PROTECTED)));
        // app/Sub extends an unscanned class, so the walk escapes.
        ClassGraph graph = new ClassGraph();
        insert(graph, "app/Sub", intern("ext/Hidden"), new int[0], Intern.NONE, "app.jar");
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Sub", new Scope(oldLib), new Scope(newLib), graph);
        assertUnknown(v);
    }

    @Test
    void protectedNarrowingIsReportedWhenProvablyNotASubclass() {
        // The caller's full chain is visible and reaches Object without passing
        // through the owner, so it is provably not a subclass and the protected
        // narrowing is a real break.
        ApiIndex oldLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PROTECTED)));
        ClassGraph graph = new ClassGraph();
        insert(graph, "app/Sub", Scope.objectSym(), new int[0], Intern.NONE, "app.jar");
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Sub", new Scope(oldLib), new Scope(newLib), graph);
        assertEquals(Reason.METHOD_ACCESS_NARROWED, broken(v).reason());
    }

    @Test
    void constructorIsNotInheritedFromSuperclass() {
        // Owner's (Z)V constructor is removed and a superclass still declares one. A
        // constructor is never inherited, so resolution must be owner-only and the
        // reference is a removal (NoSuchMethodError), not access-narrowed against the
        // superclass copy. This is the jetty ArrayTernaryTrie/AbstractTrie shape.
        ApiIndex oldLib = index(classWithMethodAccess("lib/Sub", m("<init>", "(Z)V", Acc.PUBLIC)));
        ClassApi subNew = classWithMethodAccess("lib/Sub");
        subNew.superName = intern("lib/Base");
        ApiIndex newLib = index(subNew, classWithMethodAccess("lib/Base", m("<init>", "(Z)V", Acc.PROTECTED)));
        Check.Verdict v =
                verdict(methodRef("lib/Sub", "<init>", "(Z)V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.METHOD_REMOVED, broken(v).reason());
    }

    @Test
    void narrowingIsUnknownWhenOldResolutionEscapesScope() {
        ClassApi c = classApi("lib/C");
        c.superName = intern("ext/Base");
        ApiIndex oldLib = index(c);
        ApiIndex newLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PRIVATE)));
        Check.Verdict v = verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertUnknown(v);
    }

    private static ClassApi packagePrivateClass() {
        ClassApi c = classApi("lib/C");
        c.access = 0;
        return c;
    }

    @Test
    void classAccessNarrowedOnlyWhenOldWasWider() {
        ApiIndex newLib = index(packagePrivateClass());

        ApiIndex oldPublic = index(classApi("lib/C"));
        Check.Verdict v = verdict(classRef("lib/C"), "app/Use", new Scope(oldPublic), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.CLASS_ACCESS_NARROWED, broken(v).reason());

        ApiIndex oldSame = index(packagePrivateClass());
        v = verdict(classRef("lib/C"), "app/Use", new Scope(oldSame), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    private static ClassApi classWithOwnFlags(int access) {
        ClassApi c = classApi("lib/C");
        c.access = access;
        return c;
    }

    @Test
    void protectedClassFlagDoesNotGrantSubclassAccess() {
        // JVMS 4.1 ignores ACC_PROTECTED on a class, so it is package-private (JVMS 5.4.4). HotSpot throws IllegalAccessError.
        ClassGraph graph = new ClassGraph();
        insert(graph, "app/Sub", intern("lib/C"), new int[0], Intern.NONE, "app.jar");
        Check.Verdict v = verdict(
                classRef("lib/C"), "app/Sub", new Scope(index(classApi("lib/C"))), new Scope(index(classWithOwnFlags(Acc.PROTECTED))), graph);
        assertEquals(Reason.CLASS_ACCESS_NARROWED, broken(v).reason());
    }

    @Test
    void privateClassFlagDoesNotDenySamePackageAccess() {
        // JVMS 4.1 ignores ACC_PRIVATE on a class, so it is package-private (JVMS 5.4.4). HotSpot links it.
        Check.Verdict v = verdict(
                classRef("lib/C"),
                "lib/Other",
                new Scope(index(classApi("lib/C"))),
                new Scope(index(classWithOwnFlags(Acc.PRIVATE))),
                new ClassGraph());
        assertOk(v);
    }

    @Test
    void protectedClassFlagInOldIsNotWiderThanPackagePrivate() {
        // JVMS 4.1 ignores ACC_PROTECTED on a class, so old was already package-private and the break is pre-existing.
        Check.Verdict v = verdict(
                classRef("lib/C"),
                "app/Use",
                new Scope(index(classWithOwnFlags(Acc.PROTECTED))),
                new Scope(index(packagePrivateClass())),
                new ClassGraph());
        assertOk(v);
    }

    @Test
    void staticMismatchIsBrokenOnlyWhenOldMatchedBytecode() {
        ApiIndex oldLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC | Acc.STATIC)));
        ApiIndex newLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC)));
        Check.Verdict v =
                verdict(staticMethodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.METHOD_BECAME_INSTANCE, broken(v).reason());

        ApiIndex oldAlreadyMismatched = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC)));
        ApiIndex newStillMismatched = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC)));
        v = verdict(
                staticMethodRef("lib/C", "m", "()V"),
                "app/Use",
                new Scope(oldAlreadyMismatched),
                new Scope(newStillMismatched),
                new ClassGraph());
        assertOk(v);
    }

    private static Reason flip(int from, int to, SymbolRef reference) {
        ApiIndex oldLib;
        ApiIndex newLib;
        if (reference.kind() == RefKind.FIELD) {
            oldLib = index(classWithFields("lib/C", m("x", "I", from)));
            newLib = index(classWithFields("lib/C", m("x", "I", to)));
        } else {
            oldLib = index(classWithMethodAccess("lib/C", m("m", "()V", from)));
            newLib = index(classWithMethodAccess("lib/C", m("m", "()V", to)));
        }
        Check.Verdict v = verdict(reference, "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        return broken(v).reason();
    }

    /**
     * The reason names the member kind and the direction the member moved, and all four
     * combinations come off one branch, so each one is pinned. Getting a direction backwards
     * would still report the break, with the wrong sentence and the wrong exclude kind.
     */
    @Test
    void staticFlipReasonNamesTheMemberKindAndDirection() {
        int stat = Acc.PUBLIC | Acc.STATIC;
        int inst = Acc.PUBLIC;
        assertEquals(Reason.METHOD_BECAME_INSTANCE, flip(stat, inst, staticMethodRef("lib/C", "m", "()V")));
        assertEquals(Reason.METHOD_BECAME_STATIC, flip(inst, stat, methodRefExpectingInstance("lib/C", "m", "()V")));
        assertEquals(Reason.FIELD_BECAME_INSTANCE, flip(stat, inst, staticFieldRef("lib/C", "x", "I")));
        assertEquals(Reason.FIELD_BECAME_STATIC, flip(inst, stat, fieldWriteRef("lib/C", "x", "I")));
    }

    @Test
    void externalWriteToNewFinalFieldIsBroken() {
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC | Acc.FINAL)));
        Check.Verdict v = verdict(fieldWriteRef("lib/C", "x", "I"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.FIELD_BECAME_FINAL, broken(v).reason());
    }

    @Test
    void externalWriteToAlreadyFinalFieldIsNotReported() {
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC | Acc.FINAL)));
        ApiIndex newLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC | Acc.FINAL)));
        Check.Verdict v = verdict(fieldWriteRef("lib/C", "x", "I"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    // ---- newly-final walk (addFinalViolations) ----

    private static final Member PUBLIC_M = m("m", "()V", Acc.PUBLIC);
    private static final Member FINAL_M = m("m", "()V", Acc.PUBLIC | Acc.FINAL);
    private static final String CONTAINS = "(Ljava/lang/Object;)Z";

    private static ClassApi extending(String name, String superName, Member... methods) {
        ClassApi c = classWithMethodAccess(name, methods);
        c.superName = intern(superName);
        return c;
    }

    /** lib/Base declares {@code baseM}, and lib/Mid extends it declaring {@code midMethods}. */
    private static ApiIndex baseAndMid(Member baseM, Member... midMethods) {
        return index(classWithMethodAccess("lib/Base", baseM), extending("lib/Mid", "lib/Base", midMethods));
    }

    /** Runs the walk over scanned subclasses fetched in pass 2, with {@code jdk} as the ct.sym layer when non-null. */
    private static List<Violation> finalViolations(ApiIndex oldLib, ApiIndex newLib, ApiIndex jdk, ClassApi... scanned) {
        ClassGraph graph = new ClassGraph();
        for (ClassApi c : scanned) {
            insert(graph, Intern.str(c.name), c.superName, new int[0], Intern.NONE, "app.jar");
        }
        ApiIndex fetched = index(scanned);
        Scope oldScope = jdk == null ? new Scope(oldLib, fetched) : new Scope(oldLib, fetched, jdk);
        List<Violation> violations = new ArrayList<>();
        Check.addFinalViolations(oldLib, newLib, oldScope, fetched, graph, violations, new HashSet<>());
        return violations;
    }

    private static SymbolRef overridden(String owner, String name, String descriptor) {
        return new SymbolRef(RefKind.METHOD, intern(owner), MemberKey.of(name, descriptor), Boolean.FALSE, null, null);
    }

    @Test
    void aFinalOverrideOfAnInheritedMethodIsReported() {
        // Mid adds `@Override public final void m()`. JVM: class app.Sub overrides final method lib.Mid.m()V.
        List<Violation> v = finalViolations(baseAndMid(PUBLIC_M), baseAndMid(PUBLIC_M, FINAL_M), null, extending("app/Sub", "lib/Mid", PUBLIC_M));
        assertEquals(1, v.size());
        assertEquals(Reason.METHOD_BECAME_FINAL, v.get(0).reason);
        assertEquals("app/Sub", Intern.str(v.get(0).sourceClass));
        assertEquals(overridden("lib/Mid", "m", "()V"), v.get(0).reference);
    }

    @Test
    void anInheritedMethodThatWasAlreadyFinalIsPreExisting() {
        // Base.m was final in old, so Sub failed to load before the upgrade too.
        List<Violation> v = finalViolations(baseAndMid(FINAL_M), baseAndMid(PUBLIC_M, FINAL_M), null, extending("app/Sub", "lib/Mid", PUBLIC_M));
        assertTrue(v.isEmpty());
    }

    @Test
    void aSubclassThatDoesNotOverrideTheAddedFinalMethodIsNotReported() {
        ClassApi sub = extending("app/Sub", "lib/Mid", m("n", "()V", Acc.PUBLIC));
        assertTrue(finalViolations(baseAndMid(PUBLIC_M), baseAndMid(PUBLIC_M, FINAL_M), null, sub).isEmpty());
    }

    @Test
    void anAddedStaticFinalMethodIsNotAnOverride() {
        // Mid adds `public static final String helper()` over Base's static one. On the JVM Sub's static helper() still loads.
        Member helper = m("helper", "()Ljava/lang/String;", Acc.PUBLIC | Acc.STATIC);
        Member finalHelper = m("helper", "()Ljava/lang/String;", Acc.PUBLIC | Acc.STATIC | Acc.FINAL);
        List<Violation> v = finalViolations(baseAndMid(helper), baseAndMid(helper, finalHelper), null, extending("app/Sub", "lib/Mid", helper));
        assertTrue(v.isEmpty());
    }

    @Test
    void anAddedFinalBridgeOverAnInheritedBridgeIsGuarded() {
        // Compiler-generated on both sides, the case the bridge guard skips for a declared method too.
        Member bridge = m("m", "()V", Acc.PUBLIC | Acc.BRIDGE | Acc.SYNTHETIC);
        Member finalBridge = m("m", "()V", Acc.PUBLIC | Acc.FINAL | Acc.BRIDGE);
        List<Violation> v = finalViolations(baseAndMid(bridge), baseAndMid(bridge, finalBridge), null, extending("app/Sub", "lib/Mid", PUBLIC_M));
        assertTrue(v.isEmpty());
    }

    @Test
    void anAddedFinalMethodDoesNotHideANewlyFinalOneFurtherUp() {
        // Mid, the nearer owner, adds a final m while Base makes n final. Sub overrides only n.
        Member n = m("n", "()V", Acc.PUBLIC);
        ApiIndex oldLib = index(classWithMethodAccess("lib/Base", PUBLIC_M, n), extending("lib/Mid", "lib/Base"));
        ApiIndex newLib = index(
                classWithMethodAccess("lib/Base", PUBLIC_M, m("n", "()V", Acc.PUBLIC | Acc.FINAL)), extending("lib/Mid", "lib/Base", FINAL_M));
        List<Violation> v = finalViolations(oldLib, newLib, null, extending("app/Sub", "lib/Mid", n));
        assertEquals(1, v.size());
        assertEquals(overridden("lib/Base", "n", "()V"), v.get(0).reference);
    }

    /** javac's `Items extends java.util.AbstractList<String>` with get and size. */
    private static ApiIndex javaItems() {
        return index(extending(
                "lib/Items",
                "java/util/AbstractList",
                m("<init>", "()V", Acc.PUBLIC),
                m("get", "(I)Ljava/lang/String;", Acc.PUBLIC),
                m("size", "()I", Acc.PUBLIC),
                m("get", "(I)Ljava/lang/Object;", Acc.PUBLIC | Acc.BRIDGE | Acc.SYNTHETIC)));
    }

    /** kotlinc 2.1.10's `open class Items : java.util.AbstractList<String>()` with the same get and size. */
    private static ApiIndex kotlinItems() {
        int bridge = Acc.PUBLIC | Acc.BRIDGE;
        int finalBridge = Acc.PUBLIC | Acc.FINAL | Acc.BRIDGE;
        return index(extending(
                "lib/Items",
                "java/util/AbstractList",
                m("<init>", "()V", Acc.PUBLIC),
                m("get", "(I)Ljava/lang/String;", Acc.PUBLIC),
                m("getSize", "()I", Acc.PUBLIC),
                m("get", "(I)Ljava/lang/Object;", Acc.PUBLIC | Acc.BRIDGE | Acc.SYNTHETIC),
                m("size", "()I", finalBridge),
                m("remove", "(Ljava/lang/String;)Z", bridge),
                m("remove", CONTAINS, finalBridge),
                m("indexOf", "(Ljava/lang/String;)I", bridge),
                m("indexOf", "(Ljava/lang/Object;)I", finalBridge),
                m("lastIndexOf", "(Ljava/lang/String;)I", bridge),
                m("lastIndexOf", "(Ljava/lang/Object;)I", finalBridge),
                m("contains", "(Ljava/lang/String;)Z", bridge),
                m("contains", CONTAINS, finalBridge),
                m("removeAt", "(I)Ljava/lang/String;", bridge),
                m("remove", "(I)Ljava/lang/String;", finalBridge)));
    }

    /** The ct.sym stubs Items inherits from, trimmed to the members this test reads. */
    private static ApiIndex jdkCollections() {
        ClassApi list = extending("java/util/AbstractList", "java/util/AbstractCollection", m("indexOf", "(Ljava/lang/Object;)I", Acc.PUBLIC));
        list.access = Acc.PUBLIC | Acc.ABSTRACT;
        ClassApi collection = classWithMethodAccess(
                "java/util/AbstractCollection", m("contains", CONTAINS, Acc.PUBLIC), m("size", "()I", Acc.PUBLIC | Acc.ABSTRACT));
        collection.access = Acc.PUBLIC | Acc.ABSTRACT;
        return index(list, collection);
    }

    @Test
    void aKotlinFinalBridgeOverAnInheritedMethodIsReported() {
        // Items ported from Java to Kotlin. JVM: class app.Containing overrides final method lib.Items.contains(Ljava/lang/Object;)Z.
        ClassApi containing = extending("app/Containing", "lib/Items", m("contains", CONTAINS, Acc.PUBLIC));
        List<Violation> v = finalViolations(javaItems(), kotlinItems(), jdkCollections(), containing);
        assertEquals(1, v.size());
        assertEquals("app/Containing", Intern.str(v.get(0).sourceClass));
        assertEquals(overridden("lib/Items", "contains", CONTAINS), v.get(0).reference);
    }

    @Test
    void anAddedFinalMethodWhoseOldChainEscapesIsNotReported() {
        // Without the JDK layer old resolution cannot see AbstractCollection, so the old side is unknown.
        ClassApi containing = extending("app/Containing", "lib/Items", m("contains", CONTAINS, Acc.PUBLIC));
        assertTrue(finalViolations(javaItems(), kotlinItems(), null, containing).isEmpty());
    }

    // ---- version lag (addExtendsFinalViolations) ----

    @Test
    void upgradedClassExtendingFinalClasspathClassIsBroken() {
        // C is new in the upgraded artifact (absent from old) and extends X, which
        // the scanned classpath (fetched side) declares final.
        ApiIndex oldLib = index();
        ApiIndex newLib = index();
        ApiIndex fetched = index(finalClass("cp/X"));
        Scope runtime = new Scope(newLib, fetched);
        List<Check.LagEdge> edges = List.of(new Check.LagEdge(intern("lib/C"), intern("cp/X"), intern("lib-new.jar")));
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addExtendsFinalViolations(edges, oldLib, runtime, violations, seen);
        assertEquals(1, violations.size());
        assertEquals(Reason.EXTENDS_FINAL_CLASS, violations.get(0).reason);
        assertEquals("cp/X", Intern.str(violations.get(0).reference.owner()));
        assertEquals("lib/C", Intern.str(violations.get(0).sourceClass));
    }

    @Test
    void preexistingFinalSuperEdgeIsNotReported() {
        // The changed artifact's old version already extended X, so it was equally broken
        // before the upgrade. Pre-existing, not introduced breakage.
        ClassApi cOld = classApi("lib/C");
        cOld.superName = intern("cp/X");
        ApiIndex oldLib = index(cOld);
        ApiIndex newLib = index();
        ApiIndex fetched = index(finalClass("cp/X"));
        Scope runtime = new Scope(newLib, fetched);
        List<Check.LagEdge> edges = List.of(new Check.LagEdge(intern("lib/C"), intern("cp/X"), intern("lib-new.jar")));
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addExtendsFinalViolations(edges, oldLib, runtime, violations, seen);
        assertTrue(violations.isEmpty());
    }

    @Test
    void nonFinalOrOutOfScopeSuperIsNotReported() {
        ApiIndex oldLib = index();
        ApiIndex newLib = index();
        // A non-final super is fine. An out-of-scope super has no access flags and is
        // skipped (same conservative direction as Unknown).
        ApiIndex fetched = index(classApi("cp/X"));
        Scope runtime = new Scope(newLib, fetched);
        List<Check.LagEdge> edges = List.of(
                new Check.LagEdge(intern("lib/C"), intern("cp/X"), intern("lib-new.jar")),
                new Check.LagEdge(intern("lib/D"), intern("ext/Gone"), intern("lib-new.jar")));
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addExtendsFinalViolations(edges, oldLib, runtime, violations, seen);
        assertTrue(violations.isEmpty());
    }

    @Test
    void upgradedSuperEdgesComeOnlyFromUpgradedSources() {
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/C", intern("cp/X"), new int[0], Intern.NONE, "lib-new.jar");
        insert(graph, "cp/X", Scope.objectSym(), new int[0], Intern.NONE, "cp.jar");
        insert(graph, "cp/D", intern("cp/X"), new int[0], Intern.NONE, "cp.jar");
        ApiIndex newLib = index();
        IntSet wanted = new IntSet();
        IntSet upgraded = new IntSet();
        upgraded.add(intern("lib-new.jar"));
        List<Check.LagEdge> edges = Check.collectUpgradedSuperEdges(graph, upgraded, newLib, wanted, null);
        assertEquals(List.of(new Check.LagEdge(intern("lib/C"), intern("cp/X"), intern("lib-new.jar"))), edges);
        // The super only exists on the scanned classpath, so pass 2 must fetch
        // its access flags.
        assertTrue(wanted.contains(intern("cp/X")));
    }

    @Test
    void protectedMemberOnScopeSideSuperIsAccessibleToRealSubclass() {
        // app/Sub extends lib/L, which is NOT a scan target (graph has no node
        // for it). Old L declared m public, new L dropped it and resolution
        // falls to protected m on L's super in the new index. isSubclass must
        // cross the scope-only edge or a genuine subclass caller would be
        // reported as "method access narrowed" (a false positive the JVM
        // links fine under JVMS 5.4.4).
        ApiIndex oldLib = index(classWithMethodAccess("lib/L", m("m", "()V", Acc.PUBLIC)));
        ClassApi lNew = classApi("lib/L");
        lNew.superName = intern("cp/Base");
        ApiIndex newLib = index(lNew, classWithMethodAccess("cp/Base", m("m", "()V", Acc.PROTECTED)));
        ClassGraph graph = new ClassGraph();
        insert(graph, "app/Sub", intern("lib/L"), new int[0], Intern.NONE, "app.jar");
        Check.Verdict v = verdict(methodRef("lib/L", "m", "()V"), "app/Sub", new Scope(oldLib), new Scope(newLib), graph);
        assertOk(v);
    }

    // ---- pass 1 merge ----

    private static Scan.Target target(String source, String className, String superName, List<SymbolRef> refs) {
        return new Scan.Target(intern(source), intern(className), true, intern(superName), new int[0], Intern.NONE, null, refs, new int[0]);
    }

    /** A class the graph already held at parse time, Rust {@code hierarchy: None}. */
    private static Scan.Target knownDuplicate(String source, String className, List<SymbolRef> refs) {
        return new Scan.Target(intern(source), intern(className), false, Intern.NONE, new int[0], Intern.NONE, null, refs, new int[0]);
    }

    @Test
    void classOnlyRefsSeedTheJdkEscapeRoots() {
        // A Class-constant reference (no member) to an owner outside the
        // new index and the graph must still become a JDK escape root, or the
        // existence verdict would depend on unrelated member refs.
        ApiIndex oldLib = index(classApi("javax/xml/Gone"));
        ApiIndex newLib = index();
        Scan.Result scan = new Scan.Result();
        scan.merge(Scan.leafOf(List.of(target("app.jar", "app/Use", JAVA_LANG_OBJECT, List.of(classRef("javax/xml/Gone"))))));
        IntSet escapes = new IntSet();
        Check.collectWanted(scan, oldLib, newLib, escapes);
        assertTrue(escapes.contains(intern("javax/xml/Gone")));
        // The Java port spells "escapes off" as a null set, so there is nothing to find
        // empty. The off path must still run and want nothing.
        IntSet off = Check.collectWanted(scan, oldLib, newLib, null);
        assertTrue(off.isEmpty());
    }

    @Test
    void refsFromFirstWinsLosingDuplicatesAreDropped() {
        List<SymbolRef> refs = List.of(methodRef("lib/A", "m", "()V"));

        // Same chunk. Both copies carry a hierarchy and merge order decides the winner.
        Scan.Result scan = new Scan.Result();
        scan.merge(Scan.leafOf(List.of(target("first.jar", "dup/C", "lib/Base", refs), target("second.jar", "dup/C", "other/Base", refs))));
        // Later chunk. The graph already had the class at parse time, so there is no hierarchy.
        scan.merge(Scan.leafOf(List.of(knownDuplicate("third.jar", "dup/C", refs))));

        // Only the winning copy defines the node and keeps its references. The
        // JVM never loads the shadowed copies, so their references must not be
        // judged (against the winner's hierarchy) at all.
        int node = scan.graph.node(intern("dup/C"));
        assertTrue(node >= 0);
        assertEquals("first.jar", Intern.str(scan.graph.sourceOf(node)));
        assertEquals("lib/Base", Intern.str(scan.graph.superOf(node)));
        List<String> recordSources = new ArrayList<>();
        int at = 0;
        while (at < scan.records.size()) {
            recordSources.add(Intern.str(scan.records.get(at)));
            at += 3 + scan.records.get(at + 2) * 4;
        }
        assertEquals(List.of("first.jar"), recordSources);
        assertEquals(1, scan.recordCount);
    }

    @Test
    void ownerWriteToFinalFieldIsNotReported() {
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC | Acc.FINAL)));
        Check.Verdict v = verdict(fieldWriteRef("lib/C", "x", "I"), "lib/C", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertOk(v);
    }

    @Test
    void readingAFieldThatBecameFinalIsNotReported() {
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC | Acc.FINAL)));
        SymbolRef read = new SymbolRef(RefKind.FIELD, intern("lib/C"), MemberKey.of("x", "I"), Boolean.FALSE, Boolean.FALSE, null);
        assertOk(verdict(read, "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph()));
    }

    @Test
    void aRemovedFieldIsReported() {
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithFields("lib/C"));
        Check.Verdict v = verdict(fieldWriteRef("lib/C", "x", "I"), "app/Use", new Scope(oldLib), new Scope(newLib), new ClassGraph());
        assertEquals(Reason.FIELD_REMOVED, broken(v).reason());
    }

    /**
     * A scan only records owners of the old index, so these gates always find old flags there.
     * Asked without them, each answers Unknown rather than a break.
     */
    @Test
    void classShapeGatesAreUnknownWithoutOldSideFlags() {
        Scope noOld = new Scope(index());
        ClassGraph graph = new ClassGraph();
        assertUnknown(verdict(newRef("lib/C"), "app/Use", noOld, new Scope(index(abstractClass("lib/C"))), graph));
        assertUnknown(verdict(classRef("lib/C"), "app/Use", noOld, new Scope(index(packagePrivateClass())), graph));
        ClassApi nowInterface = iface("lib/C");
        setMembers(nowInterface, true, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT));
        assertUnknown(verdict(methodRef("lib/C", "m", "()V"), "app/Use", noOld, new Scope(index(nowInterface)), graph));
    }

    /** The class moved to a scanned jar whose member table pass 2 could not read. */
    @Test
    void aMemberReferenceToAClassWithoutAFetchedTableIsUnknown() {
        ApiIndex oldLib = index(classApi("lib/C", "m", "()V"));
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/C", Scope.objectSym(), new int[0], Intern.NONE, "moved.jar");
        assertUnknown(verdict(methodRef("lib/C", "m", "()V"), "app/Use", new Scope(oldLib), new Scope(index()), graph));
    }

    /** A Methodref on an interface threw IncompatibleClassChangeError against old too. */
    @Test
    void aMethodrefToAnOwnerThatWasAlreadyAnInterfaceIsPreExisting() {
        ClassApi oldI = iface("lib/I");
        setMembers(oldI, true, m("m", "()V", Acc.PUBLIC));
        ClassApi newI = iface("lib/I");
        setMembers(newI, true, m("m", "()V", Acc.PUBLIC));
        assertOk(verdict(methodRef("lib/I", "m", "()V"), "app/Use", new Scope(index(oldI)), new Scope(index(newI)), new ClassGraph()));
    }

    /** An old class that does not declare the member and whose chain leaves every scope. */
    private static ClassApi escapingOld() {
        ClassApi c = classApi("lib/C");
        c.superName = intern("ext/Base");
        return c;
    }

    /** The unseen old super may have supplied the member, so no old-relative gate can decide. */
    @Test
    void oldRelativeMemberGatesAreUnknownWhenOldResolutionEscapes() {
        Scope old = new Scope(index(escapingOld()));
        ClassGraph graph = new ClassGraph();
        assertUnknown(verdict(methodRef("lib/C", "gone", "()V"), "app/Use", old, new Scope(index(classApi("lib/C"))), graph));
        Scope staticM = new Scope(index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC | Acc.STATIC))));
        assertUnknown(verdict(methodRefExpectingInstance("lib/C", "m", "()V"), "app/Use", old, staticM, graph));
        Scope finalX = new Scope(index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC | Acc.FINAL))));
        assertUnknown(verdict(fieldWriteRef("lib/C", "x", "I"), "app/Use", old, finalX, graph));
    }

    /** A member old never resolved was already broken, whatever new did to it. */
    @Test
    void oldRelativeMemberGatesIgnoreMembersOldNeverHad() {
        Scope old = new Scope(index(classApi("lib/C")));
        ClassGraph graph = new ClassGraph();
        Scope staticM = new Scope(index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC | Acc.STATIC))));
        assertOk(verdict(methodRefExpectingInstance("lib/C", "m", "()V"), "app/Use", old, staticM, graph));
        Scope privateM = new Scope(index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PRIVATE))));
        assertOk(verdict(methodRef("lib/C", "m", "()V"), "app/Use", old, privateM, graph));
        Scope finalX = new Scope(index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC | Acc.FINAL))));
        assertOk(verdict(fieldWriteRef("lib/C", "x", "I"), "app/Use", old, finalX, graph));
    }

    @Test
    void anUpgradedClassThatMovedOntoAFinalSuperIsReported() {
        // Old lib/C extended another class, so the edge to the final one came with the upgrade.
        ClassApi cOld = classApi("lib/C");
        cOld.superName = intern("cp/Other");
        Scope runtime = new Scope(index(), index(finalClass("cp/X")));
        List<Check.LagEdge> edges = List.of(new Check.LagEdge(intern("lib/C"), intern("cp/X"), intern("lib-new.jar")));
        List<Violation> violations = new ArrayList<>();
        Check.addExtendsFinalViolations(edges, index(cOld), runtime, violations, new HashSet<>());
        assertEquals(1, violations.size());
        assertEquals(Reason.EXTENDS_FINAL_CLASS, violations.get(0).reason);
        assertEquals("lib/C", Intern.str(violations.get(0).sourceClass));
    }

    /** android.jar and similar bundles put java/lang/Object itself, which has no superclass, into the scan. */
    @Test
    void hierarchyWalksSkipAScannedClassWithoutASuperclass() {
        ClassGraph graph = new ClassGraph();
        insert(graph, JAVA_LANG_OBJECT, Intern.NONE, new int[0], Intern.NONE, "android.jar");
        insert(graph, "app/Impl", Scope.objectSym(), syms("lib/Flip", "lib/Sealed"), Intern.NONE, "app.jar");
        ApiIndex oldLib = index(iface("lib/Flip"), iface("lib/Sealed"));
        ApiIndex newLib = index(classApi("lib/Flip"), sealedInterface("lib/Sealed", "lib/Known"));
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addKindFlipViolations(oldLib, newLib, graph, violations, seen);
        Check.addSealedViolations(oldLib, newLib, graph, violations, seen);
        assertEquals(2, violations.size());
        assertEquals(Reason.INTERFACE_BECAME_CLASS, violations.get(0).reason);
        assertEquals("lib/Flip", Intern.str(violations.get(0).reference.owner()));
        assertEquals(Reason.CLASS_BECAME_SEALED, violations.get(1).reason);
        assertEquals("lib/Sealed", Intern.str(violations.get(1).reference.owner()));
        for (Violation v : violations) {
            assertEquals("app/Impl", Intern.str(v.sourceClass));
        }
    }

    /** Under old the subclass could not load either, so there is nothing to regress against. */
    @Test
    void aSealedTypeTheOldLibraryLackedIsNotJudged() {
        ApiIndex newLib = index(sealedInterface("lib/New", "lib/Known"));
        assertTrue(sealedViolations(index(), newLib, "app/Impl", "lib/New", false).isEmpty());
    }

    /** A second, malformed PermittedSubclasses attribute keeps the first list but not trust in it. */
    @Test
    void anUnreadableNewSealingAttributeReportsNothing() {
        ClassApi c = sealedInterface("lib/I", "lib/Known");
        c.sealingUnknown = true;
        assertTrue(sealedViolations(index(iface("lib/I")), index(c), "app/Impl", "lib/I", false).isEmpty());
    }

    @Test
    void aProviderReachingTheServiceThroughADiamondIsJudged() {
        ClassApi a = iface("lib/A");
        a.interfaces = syms("lib/Base");
        ClassApi b = iface("lib/B");
        b.interfaces = syms("lib/Base");
        ClassApi base = iface("lib/Base");
        base.interfaces = syms("lib/Spi");
        ClassApi oldImpl = classWithMethodAccess("lib/Impl", m("<init>", "()V", Acc.PUBLIC));
        oldImpl.interfaces = syms("lib/A", "lib/B");
        ClassApi newImpl = classWithMethodAccess("lib/Impl", m("<init>", "()V", Acc.PRIVATE));
        newImpl.interfaces = syms("lib/A", "lib/B");
        List<Violation> violations = spiCheck(index(oldImpl, a, b, base), index(newImpl, a, b, base));
        assertEquals(1, violations.size());
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, violations.get(0).reason);
    }

    /** Only a proven old-side yes gates the check, and only a proven new-side no reports. */
    @Test
    void aProviderHierarchyLeavingEveryScopeProvesNothing() {
        // The only route from Impl to Spi would run through ext/Base, which no scope holds.
        ClassApi oldImpl = classWithMethodAccess("lib/Impl", m("<init>", "()V", Acc.PUBLIC));
        oldImpl.superName = intern("ext/Base");
        ApiIndex lostConstructor = index(classWithMethodAccess("lib/Impl", m("<init>", "()V", Acc.PRIVATE)));
        assertTrue(spiCheck(index(oldImpl), lostConstructor).isEmpty());

        ClassApi newImpl = classWithMethodAccess("lib/Impl", m("<init>", "()V", Acc.PUBLIC));
        newImpl.superName = intern("ext/Base");
        assertTrue(spiCheck(index(instantiableProvider("lib/Impl", "lib/Spi")), index(newImpl)).isEmpty());
    }

    @Test
    void aClassOnlyReferenceToAScannedClassIsNoJdkEscapeRoot() {
        ApiIndex oldLib = index(classApi("lib/Moved"));
        Scan.Result scan = new Scan.Result();
        scan.merge(Scan.leafOf(List.of(
                target("app.jar", "app/Use", JAVA_LANG_OBJECT, List.of(classRef("lib/Moved"))),
                target("other.jar", "lib/Moved", JAVA_LANG_OBJECT, List.of()))));
        IntSet escapes = new IntSet();
        Check.collectWanted(scan, oldLib, index(), escapes);
        assertTrue(escapes.isEmpty());
    }

    /** A malformed class file can omit its superclass. The walk ends there instead of seeding a bogus root. */
    @Test
    void theWantedWalkEndsAtAClassWithoutASuperclass() {
        ClassApi cOld = new ClassApi();
        cOld.name = intern("lib/C");
        cOld.access = Acc.PUBLIC;
        setMembers(cOld, true, m("m", "()V", Acc.PUBLIC));
        ClassApi cNew = classApi("lib/C");
        cNew.superName = intern("cp/Mid");
        Scan.Result scan = new Scan.Result();
        scan.merge(Scan.leafOf(List.of(
                target("app.jar", "app/Use", JAVA_LANG_OBJECT, List.of(methodRef("lib/C", "m", "()V"))),
                new Scan.Target(intern("cp.jar"), intern("cp/Mid"), true, Intern.NONE, new int[0], Intern.NONE, null, List.of(), new int[0]))));
        IntSet escapes = new IntSet();
        IntSet wanted = Check.collectWanted(scan, index(cOld), index(cNew), escapes);
        assertEquals(List.of(intern("cp/Mid")), java.util.Arrays.stream(wanted.toArray()).boxed().toList());
        assertTrue(escapes.isEmpty());
    }

    @Test
    void upgradedSuperEdgesHandAnOutOfScopeSuperToTheJdkLayer() {
        ClassGraph graph = new ClassGraph();
        insert(graph, "lib/C", intern("javax/swing/JPanel"), new int[0], Intern.NONE, "lib-new.jar");
        // An upgraded android.jar ships java/lang/Object, the one class with no superclass.
        insert(graph, JAVA_LANG_OBJECT, Intern.NONE, new int[0], Intern.NONE, "lib-new.jar");
        IntSet upgraded = new IntSet();
        upgraded.add(intern("lib-new.jar"));
        IntSet wanted = new IntSet();
        IntSet escapes = new IntSet();
        List<Check.LagEdge> edges = Check.collectUpgradedSuperEdges(graph, upgraded, index(), wanted, escapes);
        assertEquals(List.of(new Check.LagEdge(intern("lib/C"), intern("javax/swing/JPanel"), intern("lib-new.jar"))), edges);
        assertTrue(escapes.contains(intern("javax/swing/JPanel")));
        assertTrue(wanted.isEmpty());
    }
}
