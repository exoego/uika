package net.exoego.uika.cli;

import static net.exoego.uika.cli.CheckTest.JAVA_LANG_OBJECT;
import static net.exoego.uika.cli.CheckTest.intern;
import static net.exoego.uika.cli.CheckTest.m;
import static net.exoego.uika.cli.CheckTest.syms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The selection-walk half of the cli/src/check.rs unit tests. It covers AbstractMethodError,
 * conflicting default methods and the invocation evidence behind the latent tier.
 */
final class CheckSelectionTest {
    private static final int IFACE = Acc.PUBLIC | Acc.INTERFACE | Acc.ABSTRACT;
    private static final CheckTest.Member DEFAULT_N = m("n", "()V", Acc.PUBLIC);
    private static final CheckTest.Member ABSTRACT_N = m("n", "()V", Acc.PUBLIC | Acc.ABSTRACT);
    private static final String[] NO_INTERFACES = {};

    /** An interface-free class (a superclass edge only), the shape-1 special case of {@link #amvFull}. */
    private static ClassApi amvClass(String name, String superName, int access, CheckTest.Member... methods) {
        return amvFull(name, superName, access, NO_INTERFACES, methods);
    }

    /** ClassApi with explicit superclass, access flags, implemented interfaces, and methods. */
    private static ClassApi amvFull(String name, String superName, int access, String[] interfaces, CheckTest.Member... methods) {
        ClassApi api = new ClassApi();
        api.name = intern(name);
        api.access = access;
        api.superName = intern(superName);
        api.interfaces = syms(interfaces);
        CheckTest.setMembers(api, true, methods);
        return api;
    }

    private record Node(String name, String superName, String... interfaces) {}

    private static Node node(String name, String superName, String... interfaces) {
        return new Node(name, superName, interfaces);
    }

    /** A ClassGraph of scanned (name, superclass) edges with no interfaces. Arguments alternate name and superclass. */
    private static ClassGraph scannedGraph(String... edges) {
        Node[] nodes = new Node[edges.length / 2];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = node(edges[i * 2], edges[i * 2 + 1]);
        }
        return scannedGraphFull(nodes);
    }

    /** A ClassGraph of (name, superclass, interfaces) edges, all from one origin. */
    private static ClassGraph scannedGraphFull(Node... nodes) {
        ClassGraph graph = new ClassGraph();
        for (Node n : nodes) {
            graph.insertIfAbsent(intern(n.name()), intern(n.superName()), syms(n.interfaces()), new int[0], Intern.NONE, intern("consumer.jar"));
        }
        return graph;
    }

    private static ApiIndex index(ClassApi... apis) {
        return ApiIndex.build(List.of(apis));
    }

    /** Runs the walk over a two-layer scope (library + fetched scanned classes). */
    private static List<Violation> abstractViolations(ApiIndex oldLib, ApiIndex newLib, ApiIndex fetched, ClassGraph graph) {
        return abstractViolationsWith(oldLib, newLib, fetched, graph, new Scan.Invocations());
    }

    private static List<Violation> abstractViolationsWith(
            ApiIndex oldLib, ApiIndex newLib, ApiIndex fetched, ClassGraph graph, Scan.Invocations invocations) {
        Scope oldScope = new Scope(oldLib, fetched);
        Scope runtime = new Scope(newLib, fetched);
        List<Violation> violations = new ArrayList<>();
        Set<Check.ViolationKey> seen = new HashSet<>();
        Check.addSelectionViolations(oldScope, runtime, oldLib, newLib, fetched, graph, invocations, violations, seen);
        return violations;
    }

    /** One (owner, name, descriptor) evidence triple. */
    private static Scan.Invocations invoked(String owner, String name, String desc) {
        Scan.Invocations invocations = new Scan.Invocations();
        invocations.add(intern(owner), MemberKey.of(name, desc));
        return invocations;
    }

    private static ApiIndex concreteA() {
        return index(amvClass("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()V", Acc.PUBLIC)));
    }

    private static ApiIndex abstractA() {
        return index(amvClass("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC | Acc.ABSTRACT, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT)));
    }

    /**
     * Reachable and instantiated, but nothing invokes the member, so the JVM cannot throw yet
     * (https://github.com/exoego/uika/issues/81).
     */
    @Test
    void uninvokedAbstractMethodIsReportedAsLatent() {
        ApiIndex oldLib = concreteA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC));
        ClassGraph graph = scannedGraph("app/C", "lib/A");
        List<Violation> v = abstractViolations(oldLib, newLib, fetched, graph);
        assertEquals(1, v.size());
        assertEquals(Boolean.FALSE, v.get(0).invocationFound);
    }

    /** The conscrypt/netty shape. The library invokes the member on the abstract base. */
    @Test
    void invocationThroughSupertypeOwnerIsEvidence() {
        ApiIndex oldLib = concreteA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC));
        ClassGraph graph = scannedGraph("app/C", "lib/A");
        List<Violation> v = abstractViolationsWith(oldLib, newLib, fetched, graph, invoked("lib/A", "m", "()V"));
        assertEquals(1, v.size());
        assertEquals(Boolean.TRUE, v.get(0).invocationFound);
    }

    private static ApiIndex withOther(boolean abstractA) {
        ClassApi a = abstractA
                ? amvClass("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC | Acc.ABSTRACT, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT))
                : amvClass("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()V", Acc.PUBLIC));
        return index(a, amvClass("lib/Other", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()V", Acc.PUBLIC)));
    }

    /**
     * The pass-1 sweep matches on name+descriptor alone, so without this filter one
     * unrelated {@code close ()V} call would suppress every latent classification.
     */
    @Test
    void invocationOnUnrelatedOwnerIsNotEvidence() {
        ApiIndex oldLib = withOther(false);
        ApiIndex newLib = withOther(true);
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC));
        ClassGraph graph = scannedGraph("app/C", "lib/A");
        List<Violation> v = abstractViolationsWith(oldLib, newLib, fetched, graph, invoked("lib/Other", "m", "()V"));
        assertEquals(1, v.size());
        assertEquals(Boolean.FALSE, v.get(0).invocationFound);
    }

    /** A subtype-typed receiver IS an instance of the broken class. */
    @Test
    void invocationOnSubtypeOwnerIsEvidence() {
        ApiIndex oldLib = concreteA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC), amvClass("app/Sub", "app/C", Acc.PUBLIC));
        ClassGraph graph = scannedGraph("app/C", "lib/A", "app/Sub", "app/C");
        List<Violation> v = abstractViolationsWith(oldLib, newLib, fetched, graph, invoked("app/Sub", "m", "()V"));
        // Both classes inherit the unimplemented method, and the call reaches both.
        assertEquals(2, v.size());
        for (Violation violation : v) {
            assertEquals(Boolean.TRUE, violation.invocationFound, Intern.str(violation.sourceClass));
        }
    }

    /** An escape must not license the downgrade, because unseen types could relate the two. */
    @Test
    void unrelatedOwnerWithEscapingHierarchyStaysEvidence() {
        ApiIndex oldLib = concreteA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC));
        // app/Mystery's superclass is in no scope, so the walk cannot prove it unrelated.
        ClassGraph graph = scannedGraph("app/C", "lib/A", "app/Mystery", "off/Scope");
        List<Violation> v = abstractViolationsWith(oldLib, newLib, fetched, graph, invoked("app/Mystery", "m", "()V"));
        assertEquals(1, v.size());
        assertEquals(Boolean.TRUE, v.get(0).invocationFound);
    }

    private static int[] sortedOwners(Scan.Invocations invocations, long member) {
        IntSet owners = invocations.ownersOf(member);
        int[] out = owners == null ? new int[0] : owners.toArray();
        Arrays.sort(out);
        return out;
    }

    /**
     * {@code known} is the graph as of the chunk start and chunk size scales with the thread
     * count, so evidence skipped on that path would vary by core count.
     */
    @Test
    void invocationEvidenceIsChunkBoundaryIndependent() {
        int className = intern("dup/C");
        long member = MemberKey.of("m", "()V");
        // Only the LOSING copy of dup/C invokes the newly-abstract member.
        Scan.Target winner = new Scan.Target(
                intern("first.jar"), className, true, intern("lib/Base"), new int[0], Intern.NONE, null, List.of(), new int[0]);
        // Skipped at parse time, because a later chunk finds it already in the graph.
        Scan.Target loser =
                new Scan.Target(intern("second.jar"), className, false, Intern.NONE, new int[0], Intern.NONE, null, List.of(), new int[0]);

        // Same chunk. Both copies are parsed together and the evidence rides on the batch.
        Scan.Result sameChunk = new Scan.Result();
        Extract.ScanLeaf together = Scan.leafOf(List.of(winner, loser));
        addEvidence(together, "lib/Base", member);
        sameChunk.merge(together);
        // Separate chunks. The loser hits the known-class fast path, which must still
        // contribute its evidence.
        Scan.Result split = new Scan.Result();
        split.merge(Scan.leafOf(List.of(winner)));
        Extract.ScanLeaf late = Scan.leafOf(List.of(loser));
        addEvidence(late, "lib/Base", member);
        split.merge(late);

        assertEquals(sameChunk.invocations.size(), split.invocations.size());
        assertEquals(1, split.invocations.size());
        assertTrue(Arrays.equals(sortedOwners(sameChunk.invocations, member), sortedOwners(split.invocations, member)));
        assertTrue(split.invocations.ownersOf(member).contains(intern("lib/Base")));
        assertEquals(2, split.scannedClasses);
    }

    private static void addEvidence(Extract.ScanLeaf leaf, String owner, long member) {
        leaf.invocations.add(intern(owner));
        leaf.invocations.add(MemberKey.name(member));
        leaf.invocations.add(MemberKey.descriptor(member));
    }

    /**
     * The JVM reserves {@code java.*}, so the types above such an escape are all platform
     * classes and none can be lib/Other.
     */
    @Test
    void escapeIntoTheJdkDoesNotBlockTheLatentDowngrade() {
        ApiIndex oldLib = withOther(false);
        ApiIndex newLib = withOther(true);
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC));
        // app/C implements a JDK interface that is in neither scope.
        ClassGraph graph = scannedGraphFull(node("app/C", "lib/A", "java/io/Serializable"), node("lib/Other", JAVA_LANG_OBJECT));
        List<Violation> v = abstractViolationsWith(oldLib, newLib, fetched, graph, invoked("lib/Other", "m", "()V"));
        assertEquals(1, v.size());
        assertEquals(Boolean.FALSE, v.get(0).invocationFound);

        // A JDK-owned evidence reference is still blocked by the same escape. The unseen
        // platform types above Serializable could well include it.
        List<Violation> jdkOwned = abstractViolationsWith(oldLib, newLib, fetched, graph, invoked("java/util/List", "m", "()V"));
        assertEquals(Boolean.TRUE, jdkOwned.get(0).invocationFound);

        // A non-JDK escape keeps blocking, since an unseen library type could relate them.
        ClassGraph libraryEscape = scannedGraphFull(node("app/C", "lib/A", "off/Scope"), node("lib/Other", JAVA_LANG_OBJECT));
        v = abstractViolationsWith(oldLib, newLib, fetched, libraryEscape, invoked("lib/Other", "m", "()V"));
        assertEquals(Boolean.TRUE, v.get(0).invocationFound);
    }

    @Test
    void abstractMethodWithoutOverrideIsReported() {
        // lib A.m concrete -> abstract. Consumer C extends A without overriding, so invoking
        // m on a C throws AbstractMethodError.
        ApiIndex oldLib = concreteA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC));
        ClassGraph graph = scannedGraph("app/C", "lib/A");
        List<Violation> v = abstractViolations(oldLib, newLib, fetched, graph);
        assertEquals(1, v.size());
        assertEquals(Reason.METHOD_BECAME_ABSTRACT, v.get(0).reason);
        assertEquals("app/C", Intern.str(v.get(0).sourceClass));
        assertEquals("lib/A", Intern.str(v.get(0).reference.owner()));
        long member = v.get(0).reference.member();
        assertEquals("m", Intern.str(MemberKey.name(member)));
        assertEquals("()V", Intern.str(MemberKey.descriptor(member)));
    }

    @Test
    void intermediateConcreteOverrideSuppressesReport() {
        // C extends B extends A. B provides a concrete override, so method selection stops at B and C
        // is fine.
        ApiIndex oldLib = concreteA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "app/B", Acc.PUBLIC), amvClass("app/B", "lib/A", Acc.PUBLIC, m("m", "()V", Acc.PUBLIC)));
        ClassGraph graph = scannedGraph("app/C", "app/B", "app/B", "lib/A");
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void abstractSubclassIsNotReported() {
        // C is itself abstract, so it is never instantiated directly and cannot throw.
        ApiIndex oldLib = concreteA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC | Acc.ABSTRACT));
        ClassGraph graph = scannedGraph("app/C", "lib/A");
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void alreadyAbstractInOldIsPreExisting() {
        // m is abstract on both sides. C was already responsible for implementing it, so this is
        // pre-existing, not introduced by the upgrade.
        ApiIndex oldLib = abstractA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC));
        ClassGraph graph = scannedGraph("app/C", "lib/A");
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void covariantBridgeCountsAsConcreteOverride() {
        // A.m()Object concrete -> abstract. C overrides with a covariant return, so javac
        // emits a synthetic bridge m()Object (a real body). It must satisfy the abstract
        // method, so nothing is reported. This proves synthetic members are NOT filtered on
        // the resolution/override side.
        ApiIndex oldLib = index(amvClass("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()Ljava/lang/Object;", Acc.PUBLIC)));
        ApiIndex newLib = index(amvClass(
                "lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC | Acc.ABSTRACT, m("m", "()Ljava/lang/Object;", Acc.PUBLIC | Acc.ABSTRACT)));
        ApiIndex fetched = index(
                amvClass("app/C", "lib/A", Acc.PUBLIC, m("m", "()Ljava/lang/Object;", Acc.PUBLIC | Acc.BRIDGE | Acc.SYNTHETIC)));
        ClassGraph graph = scannedGraph("app/C", "lib/A");
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void syntheticMethodSubjectIsGuarded() {
        // A synthetic method that became abstract is a compiler artifact, not a source-visible
        // API change, so it is not the subject of a report.
        ApiIndex oldLib = index(amvClass("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()V", Acc.PUBLIC | Acc.SYNTHETIC)));
        ApiIndex newLib = index(amvClass(
                "lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC | Acc.ABSTRACT, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT | Acc.SYNTHETIC)));
        ApiIndex fetched = index(amvClass("app/C", "lib/A", Acc.PUBLIC));
        ClassGraph graph = scannedGraph("app/C", "lib/A");
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void unfetchedIntermediateClassStaysUnknown() {
        // C -> D -> A, but D has no fetched member table, so method selection escapes to a
        // graph-only class and answers Unknown, never a false break. (collectAbstractWanted
        // fetches D in a real run. Here it is omitted to exercise the conservative path.)
        ApiIndex oldLib = concreteA();
        ApiIndex newLib = abstractA();
        ApiIndex fetched = index(amvClass("app/C", "app/D", Acc.PUBLIC));
        ClassGraph graph = scannedGraph("app/C", "app/D", "app/D", "lib/A");
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void syntheticBecameFinalIsGuarded() {
        // The bridge/synthetic guard also covers the existing newly-final inference. A
        // synthetic bridge turning final is not reported, while a real method still is.
        ApiIndex oldLib = index(amvClass("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()V", Acc.PUBLIC | Acc.BRIDGE)));
        ApiIndex newLib = index(amvClass("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()V", Acc.PUBLIC | Acc.FINAL | Acc.BRIDGE)));
        assertTrue(Check.newlyFinalMethods(oldLib, newLib).isEmpty());

        ApiIndex oldReal = index(amvClass("lib/B", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()V", Acc.PUBLIC)));
        ApiIndex newReal = index(amvClass("lib/B", JAVA_LANG_OBJECT, Acc.PUBLIC, m("m", "()V", Acc.PUBLIC | Acc.FINAL)));
        assertFalse(Check.newlyFinalMethods(oldReal, newReal).isEmpty());
    }

    // ---- shape 2, an interface gains an abstract method ----

    @Test
    void addedAbstractInterfaceMethodWithoutImplIsReported() {
        // lib/I gains abstract b(). app/C implements I with only a() and never provides b(),
        // so calling b() on a C throws AbstractMethodError.
        ApiIndex oldLib = index(amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("a", "()V", Acc.PUBLIC | Acc.ABSTRACT)));
        ApiIndex newLib = index(amvFull(
                "lib/I",
                JAVA_LANG_OBJECT,
                IFACE,
                NO_INTERFACES,
                m("a", "()V", Acc.PUBLIC | Acc.ABSTRACT),
                m("b", "()V", Acc.PUBLIC | Acc.ABSTRACT)));
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/I"}, m("a", "()V", Acc.PUBLIC)));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/I"));
        List<Violation> v = abstractViolations(oldLib, newLib, fetched, graph);
        assertEquals(1, v.size());
        assertEquals(Reason.METHOD_BECAME_ABSTRACT, v.get(0).reason);
        assertEquals("app/C", Intern.str(v.get(0).sourceClass));
        assertEquals("lib/I", Intern.str(v.get(0).reference.owner()));
        long member = v.get(0).reference.member();
        assertEquals("b", Intern.str(MemberKey.name(member)));
        assertEquals("()V", Intern.str(MemberKey.descriptor(member)));
    }

    @Test
    void siblingInterfaceDefaultSuppressesReport() {
        // C implements both I (gains abstract b) and J (provides a default b). J's default
        // means there is no AbstractMethodError at runtime. The interface phase sees a mix of
        // an abstract (I) and a concrete (J) declaration and, without modeling specificity,
        // returns Unknown, so nothing is reported either way.
        ApiIndex oldLib = index(
                amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES),
                amvFull("lib/J", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("b", "()V", Acc.PUBLIC)));
        ApiIndex newLib = index(
                amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("b", "()V", Acc.PUBLIC | Acc.ABSTRACT)),
                amvFull("lib/J", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("b", "()V", Acc.PUBLIC)));
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/I", "lib/J"}));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/I", "lib/J"));
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void addedAbstractObjectMethodIsSuppressed() {
        // An interface that redeclares an Object method as abstract does not break a concrete
        // implementor, because java.lang.Object supplies the implementation.
        ApiIndex oldLib = index(amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES));
        ApiIndex newLib = index(amvFull(
                "lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("toString", "()Ljava/lang/String;", Acc.PUBLIC | Acc.ABSTRACT)));
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/I"}));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/I"));
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void classAbstractMethodBeatsInterfaceDefault() {
        // JVMS class-wins. A superclass's abstract method is selected over an interface
        // default, so a concrete subclass still throws AbstractMethodError. lib/A.m turns
        // abstract. C extends A and implements I whose default m must NOT rescue it.
        ApiIndex oldLib = index(
                amvFull("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC, NO_INTERFACES, m("m", "()V", Acc.PUBLIC)),
                amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("m", "()V", Acc.PUBLIC)));
        ApiIndex newLib = index(
                amvFull("lib/A", JAVA_LANG_OBJECT, Acc.PUBLIC | Acc.ABSTRACT, NO_INTERFACES, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT)),
                amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("m", "()V", Acc.PUBLIC)));
        ApiIndex fetched = index(amvFull("app/C", "lib/A", Acc.PUBLIC, new String[] {"lib/I"}));
        ClassGraph graph = scannedGraphFull(node("app/C", "lib/A", "lib/I"));
        List<Violation> v = abstractViolations(oldLib, newLib, fetched, graph);
        assertEquals(1, v.size());
        assertEquals("lib/A", Intern.str(v.get(0).reference.owner()));
    }

    @Test
    void reabstractingAShadowedDefaultIsPreExisting() {
        // I's default m becomes abstract, but sub-interface J already re-declares m abstract
        // in both versions. C implements J, so J.m (abstract, maximally specific) is selected
        // and C already threw AbstractMethodError against old. The interface phase returns
        // Unknown for the old side (mixed abstract J + concrete default I), so the pre-existing
        // break is not misreported as introduced by the upgrade.
        ApiIndex oldLib = index(
                amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("m", "()V", Acc.PUBLIC)),
                amvFull("lib/J", JAVA_LANG_OBJECT, IFACE, new String[] {"lib/I"}, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT)));
        ApiIndex newLib = index(
                amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT)),
                amvFull("lib/J", JAVA_LANG_OBJECT, IFACE, new String[] {"lib/I"}, m("m", "()V", Acc.PUBLIC | Acc.ABSTRACT)));
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/J"}));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/J"));
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void addedAbstractMethodWithEscapingSuperIsUnknown() {
        // C implements I (gains abstract b) but also extends an unscanned class that could
        // supply b, so the closure escapes scope and nothing is reported.
        ApiIndex oldLib = index(amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES));
        ApiIndex newLib = index(amvFull("lib/I", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, m("b", "()V", Acc.PUBLIC | Acc.ABSTRACT)));
        ApiIndex fetched = index(amvFull("app/C", "ext/Base", Acc.PUBLIC, new String[] {"lib/I"}));
        ClassGraph graph = scannedGraphFull(node("app/C", "ext/Base", "lib/I"));
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    // ---- conflicting default methods (same walk, Conflict status) ----

    /** lib/A always has {@code default n()} and lib/B gains one in new. {@code bOld} is B's old shape. Returns {old, new}. */
    private static ApiIndex[] conflictPair(CheckTest.Member... bOld) {
        return new ApiIndex[] {
            index(
                    amvFull("lib/A", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N),
                    amvFull("lib/B", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, bOld)),
            index(
                    amvFull("lib/A", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N),
                    amvFull("lib/B", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N)),
        };
    }

    @Test
    void aDefaultAddedBesideAnUnrelatedDefaultIsAConflict() {
        ApiIndex[] pair = conflictPair();
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/A", "lib/B"}));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/A", "lib/B"));
        List<Violation> v = abstractViolations(pair[0], pair[1], fetched, graph);
        assertEquals(1, v.size());
        assertEquals(Reason.CONFLICTING_DEFAULT_METHODS, v.get(0).reason);
        assertEquals("app/C", Intern.str(v.get(0).sourceClass));
    }

    @Test
    void aClassDeclarationWinsOverBothDefaults() {
        ApiIndex[] pair = conflictPair();
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/A", "lib/B"}, DEFAULT_N));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/A", "lib/B"));
        assertTrue(abstractViolations(pair[0], pair[1], fetched, graph).isEmpty());
    }

    @Test
    void aSubinterfaceDefaultShadowsBothAndIsNotAConflict() {
        // app/C implements lib/AB, which extends both and redeclares n(), so only AB's
        // declaration is maximally specific.
        ApiIndex oldLib = index(
                amvFull("lib/A", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N),
                amvFull("lib/B", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES),
                amvFull("lib/AB", JAVA_LANG_OBJECT, IFACE, new String[] {"lib/A", "lib/B"}, DEFAULT_N));
        ApiIndex newLib = index(
                amvFull("lib/A", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N),
                amvFull("lib/B", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N),
                amvFull("lib/AB", JAVA_LANG_OBJECT, IFACE, new String[] {"lib/A", "lib/B"}, DEFAULT_N));
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/AB"}));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/AB"));
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void aConflictAlreadyPresentInOldIsPreExisting() {
        ApiIndex[] pair = conflictPair(DEFAULT_N);
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/A", "lib/B"}));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/A", "lib/B"));
        assertTrue(abstractViolations(pair[0], pair[1], fetched, graph).isEmpty());
    }

    @Test
    void aDefaultAddedWhereNothingElseDeclaresItIsNotReported() {
        // The common safe evolution. Only lib/B declares n(), so selection has a winner.
        ApiIndex oldLib = index(amvFull("lib/B", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES));
        ApiIndex newLib = index(amvFull("lib/B", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N));
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/B"}));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/B"));
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }

    @Test
    void anAbstractSiblingKeepsTheConflictInconclusive() {
        // lib/D declares n() abstract, so phase 2 sees a mix and stays Unknown rather than
        // guessing which declaration is more specific.
        ApiIndex oldLib = index(
                amvFull("lib/A", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N),
                amvFull("lib/B", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES),
                amvFull("lib/D", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, ABSTRACT_N));
        ApiIndex newLib = index(
                amvFull("lib/A", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N),
                amvFull("lib/B", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, DEFAULT_N),
                amvFull("lib/D", JAVA_LANG_OBJECT, IFACE, NO_INTERFACES, ABSTRACT_N));
        ApiIndex fetched = index(amvFull("app/C", JAVA_LANG_OBJECT, Acc.PUBLIC, new String[] {"lib/A", "lib/B", "lib/D"}));
        ClassGraph graph = scannedGraphFull(node("app/C", JAVA_LANG_OBJECT, "lib/A", "lib/B", "lib/D"));
        assertTrue(abstractViolations(oldLib, newLib, fetched, graph).isEmpty());
    }
}
