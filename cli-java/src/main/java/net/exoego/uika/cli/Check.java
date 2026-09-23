package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;

/** Pass 2 and the verdicts: turns a scan into violations. */
final class Check {
    static final class Report {
        List<Violation> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int scannedClasses;
        /** References that reached a type outside the index and could not be proven unbroken. */
        int unknownRefs;
        /** Violations dropped by --exclude-file rules. The caller applies the rules and sets it. */
        int suppressed;
        /** Whether each violation carries a reachable flag. */
        boolean reachabilityComputed;
        /**
         * Whether any application root matched a scanned class. Null when reachability was not
         * computed; FALSE means roots were supplied but none matched, so the
         * not-proven-reachable labels are untrustworthy.
         */
        Boolean appRootsMatched;
        /** Scan target paths actually admitted. Feeds the plain-check header only, never JSON. */
        int scanTargets;
    }

    /** The compared libraries' own META-INF/services files, one side each. */
    record SpiServices(List<Reach.ServiceFile> oldFiles, List<Reach.ServiceFile> newFiles) {
        static final SpiServices NONE = new SpiServices(List.of(), List.of());
    }

    /** The dedup key every violation funnels through. */
    record ViolationKey(int source, int sourceClass, SymbolRef reference) {}

    private Check() {}

    // ---- wanted-class collection ----

    /**
     * Classes reference resolution may visit on the hierarchy graph. Before member tables are
     * fetched, Found cannot end a traversal early, so this takes the full closure.
     *
     * @param escapes receives classes in no analyzed scope (the JDK index roots), or null
     */
    static IntSet collectWanted(Scan.Result scan, ApiIndex oldIndex, ApiIndex newIndex, IntSet escapes) {
        IntSet wanted = new IntSet();
        LongSet memo = new LongSet();
        ApiIndex[] sides = {newIndex, oldIndex};
        IntQueue queue = new IntQueue();
        IntSet seen = new IntSet();
        IntArena records = scan.records;
        int at = 0;
        int end = records.size();
        while (at < end) {
            int refCount = records.get(at + 2);
            int refsAt = at + 3;
            for (int r = 0; r < refCount; r++) {
                int owner = records.get(refsAt + r * 4 + 1);
                boolean hasMember = records.get(refsAt + r * 4 + 2) != Intern.NONE;
                if (!hasMember) {
                    // Class references only need existence, not members. The owner must still
                    // seed the JDK escape roots, or a class verdict would depend on whether an
                    // unrelated member reference to the same owner exists elsewhere.
                    if (escapes != null && !newIndex.containsClass(owner) && !scan.graph.contains(owner)) {
                        escapes.add(owner);
                    }
                    continue;
                }
                for (int side = 0; side < 2; side++) {
                    if (!memo.add((long) owner << 1 | side)) {
                        continue;
                    }
                    ApiIndex lib = sides[side];
                    queue.clear();
                    seen.clear();
                    queue.add(owner);
                    while (!queue.isEmpty()) {
                        int c = queue.poll();
                        if (!seen.add(c) || c == Scope.objectSym()) {
                            continue;
                        }
                        int entry = lib.entry(c);
                        if (entry >= 0) {
                            pushSupertypes(queue, lib, entry);
                            continue;
                        }
                        int node = scan.graph.node(c);
                        if (node >= 0) {
                            wanted.add(c);
                            pushSupertypes(queue, scan.graph, node);
                        } else if (escapes != null) {
                            escapes.add(c);
                        }
                    }
                }
            }
            at = refsAt + refCount * 4;
        }
        return wanted;
    }

    private static void pushSupertypes(IntQueue queue, ApiIndex index, int entry) {
        int superName = index.superOf(entry);
        if (superName != Intern.NONE) {
            queue.add(superName);
        }
        int n = index.interfaceCount(entry);
        for (int k = 0; k < n; k++) {
            queue.add(index.interfaceAt(entry, k));
        }
    }

    private static void pushSupertypes(IntQueue queue, ClassGraph graph, int node) {
        int superName = graph.superOf(node);
        if (superName != Intern.NONE) {
            queue.add(superName);
        }
        int n = graph.interfaceCount(node);
        for (int k = 0; k < n; k++) {
            queue.add(graph.interfaceAt(node, k));
        }
    }

    private static void collectFinalWanted(ApiIndex oldIndex, ApiIndex newIndex, ClassGraph graph, IntSet wanted, IntSet escapes) {
        Map<Integer, LongSet> finalMethods = newlyFinalMethods(oldIndex, newIndex);
        // Pass 2 has not run yet. An added final method whose old chain leaves the library
        // stays a candidate, and the chain is fetched so the verdict can judge the old side.
        IntSet unresolved = new IntSet();
        mergeMethods(finalMethods, addedFinalOverrides(oldIndex, newIndex, new Scope(oldIndex), unresolved));
        if (finalMethods.isEmpty() && unresolved.isEmpty()) {
            return;
        }
        IntSet finalOwners = ownersOf(finalMethods);
        Walk walk = new Walk();
        for (int owner : unresolved.toArray()) {
            finalOwners.add(owner);
            forEachSupertype(owner, oldIndex, graph, walk, type -> {
                if (oldIndex.containsClass(type)) {
                    return;
                }
                if (graph.contains(type)) {
                    wanted.add(type);
                } else if (escapes != null) {
                    escapes.add(type);
                }
            });
        }
        SupertypeReach reach = SupertypeReach.bySuperclass(finalOwners, newIndex, graph);
        for (int node = 0; node < graph.size(); node++) {
            int className = graph.nameOf(node);
            int superName = graph.superOf(node);
            if (superName != Intern.NONE && reach.reaches(superName)) {
                wanted.add(className);
            }
        }
    }

    /**
     * Whether a type, or a supertype of it, is one of a set of owners, remembered per type:
     * the scanned classes are many and their supertypes few, so a walk per class visits the
     * same chains hundreds of thousands of times over. One instance answers for one owner set
     * and one kind of walk, since the answer depends on both. {@link #bySuperclass} follows
     * the superclass chain alone, the way a final method is inherited; {@link #byClosure}
     * follows interfaces too, by the steps of {@link #forEachSupertype}, the library index
     * first, then the scan graph.
     */
    static final class SupertypeReach {
        private static final byte NO = 1;
        private static final byte YES = 2;
        /** Marks a type being answered, so a cyclic hierarchy in corrupt input ends. */
        private static final byte OPEN = 3;

        private final IntSet owners;
        private final ApiIndex lib;
        private final ClassGraph graph;
        private final boolean closure;
        /** Sized once: a walk interns nothing, so every type it meets already has an id. */
        private final byte[] state = new byte[Intern.tableLen()];

        private SupertypeReach(IntSet owners, ApiIndex lib, ClassGraph graph, boolean closure) {
            this.owners = owners;
            this.lib = lib;
            this.graph = graph;
            this.closure = closure;
        }

        /** Reaches through superclasses only: the scan graph's edge first, else the library's. */
        static SupertypeReach bySuperclass(IntSet owners, ApiIndex lib, ClassGraph graph) {
            return new SupertypeReach(owners, lib, graph, false);
        }

        /** Reaches through superclasses and interfaces, as {@link #forEachSupertype} walks them. */
        static SupertypeReach byClosure(IntSet owners, ApiIndex lib, ClassGraph graph) {
            return new SupertypeReach(owners, lib, graph, true);
        }

        /** Whether {@code type} or a supertype of it is an owner. */
        boolean reaches(int type) {
            if (closure && type == Scope.objectSym()) {
                return false;
            }
            byte known = state[type];
            if (known != 0) {
                return known == YES;
            }
            boolean result;
            if (owners.contains(type)) {
                result = true;
            } else {
                state[type] = OPEN;
                result = closure ? closureReaches(type) : superclassReaches(type);
            }
            state[type] = result ? YES : NO;
            return result;
        }

        private boolean superclassReaches(int type) {
            int superName = Intern.NONE;
            int node = graph.node(type);
            if (node >= 0) {
                superName = graph.superOf(node);
            }
            if (superName == Intern.NONE) {
                int entry = lib.entry(type);
                superName = entry < 0 ? Intern.NONE : lib.superOf(entry);
            }
            return superName != Intern.NONE && reaches(superName);
        }

        private boolean closureReaches(int type) {
            int entry = lib.entry(type);
            if (entry >= 0) {
                int superName = lib.superOf(entry);
                boolean result = superName != Intern.NONE && reaches(superName);
                for (int k = 0, n = lib.interfaceCount(entry); !result && k < n; k++) {
                    result = reaches(lib.interfaceAt(entry, k));
                }
                return result;
            }
            int node = graph.node(type);
            if (node < 0) {
                return false;
            }
            int superName = graph.superOf(node);
            boolean result = superName != Intern.NONE && reaches(superName);
            for (int k = 0, n = graph.interfaceCount(node); !result && k < n; k++) {
                result = reaches(graph.interfaceAt(node, k));
            }
            return result;
        }
    }

    private interface TypeVisitor {
        void visit(int type);
    }

    /**
     * Visits {@code start} and its transitive supertypes. Library classes take their hierarchy
     * from {@code lib}, scanned classes from the graph. A type visible in neither is still
     * visited but contributes no further edges. java/lang/Object ends the walk.
     */
    private static void forEachSupertype(int start, ApiIndex lib, ClassGraph graph, Walk walk, TypeVisitor visitor) {
        IntQueue queue = walk.queue;
        IntSet seen = walk.seen;
        queue.clear();
        seen.clear();
        queue.add(start);
        while (!queue.isEmpty()) {
            int c = queue.poll();
            if (!seen.add(c) || c == Scope.objectSym()) {
                continue;
            }
            visitor.visit(c);
            int entry = lib.entry(c);
            if (entry >= 0) {
                pushSupertypes(queue, lib, entry);
            } else {
                int node = graph.node(c);
                if (node >= 0) {
                    pushSupertypes(queue, graph, node);
                }
            }
        }
    }

    /** Reusable traversal state, so a walk per graph node allocates nothing. */
    private static final class Walk {
        final IntQueue queue = new IntQueue();
        final IntSet seen = new IntSet();
    }

    /**
     * Classes the selection walk may need to resolve through: a scanned class inheriting a
     * newly-abstract or newly-default method, plus every scanned class on its supertype
     * closure. Without the intermediate chain, resolution escapes to a graph-only class and
     * answers Unknown, hiding a real break.
     */
    private static void collectAbstractWanted(ApiIndex oldIndex, ApiIndex newIndex, ClassGraph graph, IntSet wanted) {
        Map<Integer, LongSet> abstractMethods = methodsNewlyAbstract(oldIndex, newIndex);
        Map<Integer, LongSet> defaultMethods = methodsNewlyDefault(oldIndex, newIndex);
        if (abstractMethods.isEmpty() && defaultMethods.isEmpty()) {
            return;
        }
        Walk walk = new Walk();
        IntBuf scannedChain = new IntBuf();
        boolean[] inherits = new boolean[1];
        // Probed once per supertype of every scanned class, so the owners are an int set and
        // the visitor is one object rather than a boxed key and a lambda per class.
        IntSet owners = new IntSet();
        for (int owner : abstractMethods.keySet()) {
            owners.add(owner);
        }
        for (int owner : defaultMethods.keySet()) {
            owners.add(owner);
        }
        TypeVisitor visitor = anc -> {
            inherits[0] |= owners.contains(anc);
            if (graph.contains(anc)) {
                scannedChain.add(anc);
            }
        };
        // The remembered answer decides which classes need the chain collected; the full walk
        // then runs only for those, a small fraction of the graph.
        SupertypeReach reach = SupertypeReach.byClosure(owners, newIndex, graph);
        for (int node = 0; node < graph.size(); node++) {
            if (!reach.reaches(graph.nameOf(node))) {
                continue;
            }
            inherits[0] = false;
            scannedChain.n = 0;
            forEachSupertype(graph.nameOf(node), newIndex, graph, walk, visitor);
            if (inherits[0]) {
                for (int i = 0; i < scannedChain.n; i++) {
                    wanted.add(scannedChain.a[i]);
                }
            }
        }
    }

    /** (service, provider, registering jar). */
    private record Provider(int iface, int provider, int source) {}

    /**
     * Providers the new service files still list that the old files also listed for the same
     * service, deduplicated the way ServiceLoader dedups names across configuration files.
     */
    private static List<Provider> stillListedProviders(SpiServices services) {
        Map<Integer, IntSet> oldImpls = new HashMap<>();
        for (Reach.ServiceFile file : services.oldFiles()) {
            IntSet set = oldImpls.computeIfAbsent(file.iface(), k -> new IntSet());
            for (int impl : file.impls()) {
                set.add(impl);
            }
        }
        LongSet seen = new LongSet();
        List<Provider> out = new ArrayList<>();
        for (Reach.ServiceFile file : services.newFiles()) {
            IntSet oldProviders = oldImpls.get(file.iface());
            if (oldProviders == null) {
                continue;
            }
            for (int provider : file.impls()) {
                if (oldProviders.contains(provider) && seen.add((long) file.iface() << 32 | provider)) {
                    out.add(new Provider(file.iface(), provider, file.source()));
                }
            }
        }
        return out;
    }

    static void collectSpiWanted(SpiServices services, ApiIndex oldIndex, ApiIndex newIndex, ClassGraph graph, IntSet wanted) {
        for (Provider p : stillListedProviders(services)) {
            if (graph.contains(p.provider()) && (!oldIndex.containsClass(p.provider()) || !newIndex.containsClass(p.provider()))) {
                wanted.add(p.provider());
            }
        }
    }

    /** (class, direct super, source). */
    record LagEdge(int className, int superName, int source) {}

    /**
     * Direct-super edges of classes scanned from upgraded (new-version) scan targets, the
     * inputs to the version-lag check. Only the direct superclass matters: a final class has
     * no subclasses, so a deeper ancestor can never be the broken edge.
     */
    static List<LagEdge> collectUpgradedSuperEdges(
            ClassGraph graph, IntSet upgradedSources, ApiIndex newIndex, IntSet wanted, IntSet escapes) {
        List<LagEdge> edges = new ArrayList<>();
        if (upgradedSources.isEmpty()) {
            return edges;
        }
        for (int node = 0; node < graph.size(); node++) {
            if (!upgradedSources.contains(graph.sourceOf(node))) {
                continue;
            }
            int superName = graph.superOf(node);
            if (superName == Intern.NONE || superName == Scope.objectSym()) {
                continue;
            }
            if (newIndex.containsClass(superName)) {
                // Access flags come from the new index directly.
            } else if (graph.contains(superName)) {
                wanted.add(superName);
            } else if (escapes != null) {
                escapes.add(superName);
            }
            edges.add(new LagEdge(graph.nameOf(node), superName, graph.sourceOf(node)));
        }
        return edges;
    }

    // ---- pass 2 ----

    /** Re-reads only the classes resolution needs from their origin and indexes their members. */
    private static ApiIndex fetchMembers(Scan.Result scan, IntSet wanted, List<String> warnings) {
        Map<Integer, List<Input.Wanted>> bySource = new LinkedHashMap<>();
        int[] names = wanted.toArray();
        // Grouped in string order, so warnings come out the same on every run.
        Integer[] sorted = new Integer[names.length];
        for (int i = 0; i < names.length; i++) {
            sorted[i] = names[i];
        }
        java.util.Arrays.sort(sorted, Intern::compare);
        for (int name : sorted) {
            int node = scan.graph.node(name);
            String entry = entryName(scan.entryOverrides, name);
            bySource.computeIfAbsent(scan.graph.sourceOf(node), k -> new ArrayList<>()).add(new Input.Wanted(name, entry));
        }

        List<Integer> sources = new ArrayList<>(bySource.keySet());
        @SuppressWarnings("unchecked")
        List<ClassApi>[] apis = new List[sources.size()];
        @SuppressWarnings("unchecked")
        List<String>[] sourceWarnings = new List[sources.size()];
        List<RecursiveAction> tasks = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            int index = i;
            tasks.add(new RecursiveAction() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void compute() {
                    int source = sources.get(index);
                    List<ClassApi> out = new ArrayList<>();
                    List<String> w = new ArrayList<>();
                    String path = Intern.str(source);
                    try {
                        w.addAll(Input.fetchEntries(path, bySource.get(source), (name, bytes, length) -> {
                            Scratch scratch = Scratch.current();
                            try {
                                scratch.parser.parse(bytes, length);
                                out.add(Extract.extractApi(scratch.parser, scratch));
                            } catch (ClassParser.FormatException e) {
                                w.add(path + "!" + Intern.str(name) + ": " + e.getMessage());
                            }
                        }));
                    } catch (UikaException e) {
                        w.add(path + ": " + e.getMessage());
                    }
                    apis[index] = out;
                    sourceWarnings[index] = w;
                }
            });
        }
        Input.onPool(() -> ForkJoinTask.invokeAll(tasks));

        ApiIndex index = new ApiIndex();
        for (int i = 0; i < sources.size(); i++) {
            for (ClassApi api : apis[i]) {
                index.insertIfAbsent(api);
            }
            warnings.addAll(sourceWarnings[i]);
        }
        return index;
    }

    /** The entry pass 1 found a class under, which differs from its name inside BOOT-INF/classes and the like. */
    private static String entryName(Map<Integer, String> entryOverrides, int className) {
        String entry = entryOverrides.get(className);
        return entry != null ? entry : Intern.str(className) + ".class";
    }

    // ---- the check ----

    /**
     * Evaluates references and collects violations. Resolution uses "new + the scanned
     * classpath" rather than new alone, because real linking runs against the whole runtime
     * classpath. The old side is composed the same way.
     *
     * @param jdk opt-in JDK API layer, or null
     * @param reach reachability inputs, or null when there are no application roots
     * @param verdicts evaluation stream, or null
     */
    static Report checkScanned(
            Scan.Result scan,
            ApiIndex oldIndex,
            ApiIndex newIndex,
            IntSet upgradedSources,
            Jdk.Indexer jdk,
            Reach.Inputs reach,
            SpiServices services,
            Verdicts.Writer verdicts) {
        ClassGraph graph = scan.graph;
        Reach.Result reachResult = reach == null ? null : Reach.reachableClasses(graph, reach);
        IntSet escapes = jdk == null ? null : new IntSet();
        IntSet wanted = collectWanted(scan, oldIndex, newIndex, escapes);
        collectFinalWanted(oldIndex, newIndex, graph, wanted, escapes);
        collectAbstractWanted(oldIndex, newIndex, graph, wanted);
        collectSpiWanted(services, oldIndex, newIndex, graph, wanted);
        List<LagEdge> lagEdges = collectUpgradedSuperEdges(graph, upgradedSources, newIndex, wanted, escapes);

        List<String> warnings = new ArrayList<>(scan.warnings);
        ApiIndex fetched = fetchMembers(scan, wanted, warnings);
        // The JDK index sits in BOTH scopes, so ct.sym incompleteness resolves NotFound on
        // both sides and the old-relative gate keeps it unreported.
        ApiIndex jdkIndex = jdk == null ? null : jdk.fetchClosure(escapes, warnings);

        Scope oldScope = jdk == null ? new Scope(oldIndex, fetched) : new Scope(oldIndex, fetched, jdkIndex);
        Scope runtimeScope = jdk == null ? new Scope(newIndex, fetched) : new Scope(newIndex, fetched, jdkIndex);

        NestHosts hosts = new NestHosts(graph, scan.entryOverrides);
        List<Violation> violations = new ArrayList<>();
        Set<ViolationKey> seen = new HashSet<>();
        int unknownRefs = 0;
        IntArena records = scan.records;
        int at = 0;
        int end = records.size();
        while (at < end) {
            int source = records.get(at);
            int className = records.get(at + 1);
            int refCount = records.get(at + 2);
            int refsAt = at + 3;
            for (int r = 0; r < refCount; r++) {
                int base = refsAt + r * 4;
                int meta = records.get(base);
                int owner = records.get(base + 1);
                int name = records.get(base + 2);
                int descriptor = records.get(base + 3);
                long member = name == Intern.NONE ? MemberKey.NONE : MemberKey.of(name, descriptor);
                int verdict = verdict(meta, owner, member, className, oldScope, runtimeScope, graph, hosts);
                if (verdicts != null) {
                    // The raw reference, not the collapsed one a broken verdict may carry.
                    String verdictName = verdict == OK ? "ok" : verdict == UNKNOWN ? "unknown" : "broken";
                    verdicts.record(
                            source,
                            className,
                            SymbolRef.unpack(meta, owner, name, descriptor),
                            verdictName,
                            verdict < 0 ? null : Reason.ALL[verdict].text);
                }
                if (verdict == OK) {
                    continue;
                }
                if (verdict == UNKNOWN) {
                    unknownRefs++;
                    continue;
                }
                Reason reason = Reason.ALL[verdict];
                // If the entire owner class disappeared, member references collapse into one
                // Class reference, so a class and its members do not each report.
                SymbolRef reference =
                        reason == Reason.CLASS_REMOVED ? SymbolRef.ofClass(owner) : SymbolRef.unpack(meta, owner, name, descriptor);
                pushViolation(violations, seen, source, className, reference, reason);
            }
            at = refsAt + refCount * 4;
        }
        addFinalViolations(oldIndex, newIndex, oldScope, fetched, graph, violations, seen);
        addExtendsFinalViolations(lagEdges, oldIndex, runtimeScope, violations, seen);
        addKindFlipViolations(oldIndex, newIndex, graph, violations, seen);
        addSealedViolations(oldIndex, newIndex, graph, violations, seen);
        addSelectionViolations(oldScope, runtimeScope, oldIndex, newIndex, fetched, graph, scan.invocations, violations, seen);
        addSpiViolations(graph, oldScope, runtimeScope, services, violations, seen);

        // Canonical order by string value (never symbol ids), so the JSON report and the
        // goldens pinning it are reproducible.
        violations.sort(Violation::compare);

        if (reachResult != null) {
            // Registration edges the BFS could actually see. An SPI provider outside the scan
            // targets has no graph node and no such edge, so an unset mark means "unobserved",
            // not "proven unreachable": it stays null.
            LongSet spiEdges = new LongSet();
            for (Reach.ServiceFile file : reach.services()) {
                for (int impl : file.impls()) {
                    spiEdges.add((long) file.iface() << 32 | impl);
                }
            }
            for (Violation v : violations) {
                boolean marked = reachResult.isReachable(v.sourceClass);
                boolean spi = v.reason == Reason.SERVICE_PROVIDER_REMOVED || v.reason == Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE;
                if (spi && !marked && !spiEdges.contains((long) v.reference.owner() << 32 | v.sourceClass)) {
                    v.reachable = null;
                } else {
                    v.reachable = marked;
                }
            }
            if (!reachResult.appRootMatched()) {
                warnings.add("reachability: no application root matched a scanned class "
                        + "(were the project's build outputs compiled?); "
                        + "violations are not ranked by reachability in this run");
            }
        }

        Report report = new Report();
        report.violations = violations;
        report.warnings = warnings;
        report.scannedClasses = scan.scannedClasses;
        report.unknownRefs = unknownRefs;
        report.reachabilityComputed = reachResult != null;
        report.appRootsMatched = reachResult == null ? null : reachResult.appRootMatched();
        return report;
    }


    private static Violation pushViolation(
            List<Violation> violations, Set<ViolationKey> seen, int source, int sourceClass, SymbolRef reference, Reason reason) {
        if (!seen.add(new ViolationKey(source, sourceClass, reference))) {
            return null;
        }
        Violation v = new Violation(source, sourceClass, reference, reason);
        violations.add(v);
        return v;
    }

    /**
     * Checks consumer paths against a library pair: pass 1, pass 2 and the verdicts, with no
     * reachability, JDK layer or SPI files. The goldens' entry point.
     *
     * @param libraryPaths the new-version library's own jars, swept for invocation evidence
     *     exactly as the CLI does with --new. Not optional in the "nice to have" sense: when the
     *     library itself is the only caller of a newly-abstract member, leaving it out reports
     *     that real break as latent.
     */
    static Report check(List<String> targetPaths, ApiIndex oldIndex, ApiIndex newIndex, List<String> libraryPaths) {
        MemberProbe probe = selectionMemberProbe(oldIndex, newIndex);
        Scan.Result scan = Scan.scanTargetPaths(targetPaths, oldIndex, probe, false);
        libraryInvocationEvidence(libraryPaths, probe, scan.invocations);
        return checkScanned(scan, oldIndex, newIndex, new IntSet(), null, null, SpiServices.NONE, null);
    }

    // ---- reference verdicts ----

    /** A reference verdict in object form, for callers outside the scan loop. */
    record Verdict(Kind kind, SymbolRef reference, Reason reason) {
        enum Kind {
            OK,
            /** Reached a type outside the index and cannot be proven. */
            UNKNOWN,
            BROKEN
        }
    }

    /** {@link #verdict} for one reference given as an object rather than a packed record. */
    static Verdict verdictOf(SymbolRef r, int sourceClass, Scope oldScope, Scope runtime, ClassGraph graph) {
        int meta = SymbolRef.pack(
                r.kind(),
                r.expectedStatic() == null ? SymbolRef.TRI_NONE : r.expectedStatic() ? SymbolRef.TRI_TRUE : SymbolRef.TRI_FALSE,
                r.fieldWrite() == null ? SymbolRef.TRI_NONE : r.fieldWrite() ? SymbolRef.TRI_TRUE : SymbolRef.TRI_FALSE,
                Boolean.TRUE.equals(r.instantiated()));
        int code = verdict(meta, r.owner(), r.member(), sourceClass, oldScope, runtime, graph, new NestHosts(graph, Map.of()));
        if (code == OK) {
            return new Verdict(Verdict.Kind.OK, r, null);
        } else if (code == UNKNOWN) {
            return new Verdict(Verdict.Kind.UNKNOWN, r, null);
        }
        Reason reason = Reason.ALL[code];
        return new Verdict(Verdict.Kind.BROKEN, reason == Reason.CLASS_REMOVED ? SymbolRef.ofClass(r.owner()) : r, reason);
    }

    private static final int OK = -1;
    /** Reached a type outside the index and cannot be proven. */
    private static final int UNKNOWN = -2;

    /**
     * {@link #OK}, {@link #UNKNOWN}, or the ordinal of the breaking {@link Reason}. Class
     * existence is checked against the graph (all scan targets); member resolution uses the
     * scope layered with the fetched classes.
     */
    private static int verdict(
            int meta, int owner, long member, int sourceClass, Scope oldScope, Scope runtime, ClassGraph graph, NestHosts hosts) {
        if (!runtime.containsClass(owner) && !graph.contains(owner)) {
            return Reason.CLASS_REMOVED.ordinal();
        }
        int ownerAccess = runtime.classAccess(owner);
        // `new X` where X is now abstract or an interface throws InstantiationError.
        // Old-relative: only when X was concrete before.
        if (SymbolRef.instantiatedOf(meta) && ownerAccess >= 0 && (ownerAccess & (Acc.ABSTRACT | Acc.INTERFACE)) != 0) {
            int oldAccess = oldScope.classAccess(owner);
            if (oldAccess < 0) {
                return UNKNOWN;
            }
            return (oldAccess & (Acc.ABSTRACT | Acc.INTERFACE)) == 0 ? Reason.CLASS_BECAME_ABSTRACT.ordinal() : OK;
        }
        if (member == MemberKey.NONE) {
            // Only public or the same run-time package (JVMS 5.4.4). No subclass or nestmate rule.
            if (ownerAccess < 0 || Visibility.ofClass(ownerAccess) == Visibility.PUBLIC || Intern.samePackage(owner, sourceClass)) {
                return OK;
            }
            // Narrowing is relative to old: a reference equally inaccessible before the
            // change is pre-existing.
            int oldAccess = oldScope.classAccess(owner);
            if (oldAccess < 0) {
                return UNKNOWN;
            }
            return Visibility.ofClass(oldAccess) == Visibility.PUBLIC ? Reason.CLASS_ACCESS_NARROWED.ordinal() : OK;
        }
        RefKind refKind = SymbolRef.kindOf(meta);
        Scope.MemberKind kind = refKind == RefKind.FIELD ? Scope.MemberKind.FIELD : Scope.MemberKind.METHOD;
        // Methodref vs InterfaceMethodref encodes the owner kind the compiler saw; a class <->
        // interface flip makes resolution throw IncompatibleClassChangeError. Old-relative:
        // only when the ref kind matched the old owner kind.
        if (kind == Scope.MemberKind.METHOD && ownerAccess >= 0) {
            boolean expectsInterface = refKind == RefKind.INTERFACE_METHOD;
            if (((ownerAccess & Acc.INTERFACE) != 0) != expectsInterface) {
                int oldAccess = oldScope.classAccess(owner);
                if (oldAccess < 0) {
                    return UNKNOWN;
                }
                if (((oldAccess & Acc.INTERFACE) != 0) == expectsInterface) {
                    return (expectsInterface ? Reason.INTERFACE_BECAME_CLASS : Reason.CLASS_BECAME_INTERFACE).ordinal();
                }
            }
        }
        long found = runtime.resolveMember(owner, member, kind);
        if (found == Scope.UNKNOWN) {
            return UNKNOWN;
        }
        if (found == Scope.NOT_FOUND) {
            // A reference that cannot resolve against old was already inconsistent.
            return switch (oldScope.resolve(owner, member, kind)) {
                case FOUND -> (kind == Scope.MemberKind.FIELD ? Reason.FIELD_REMOVED : Reason.METHOD_REMOVED).ordinal();
                case UNKNOWN -> UNKNOWN;
                case NOT_FOUND -> OK;
            };
        }
        int foundAccess = Scope.foundAccess(found);
        int foundOwner = Scope.foundOwner(found);
        int expectedStatic = SymbolRef.expectedStaticOf(meta);
        if (expectedStatic != SymbolRef.TRI_NONE) {
            boolean expected = expectedStatic == SymbolRef.TRI_TRUE;
            if (((foundAccess & Acc.STATIC) != 0) != expected) {
                long oldFound = oldScope.resolveMember(owner, member, kind);
                if (oldFound == Scope.UNKNOWN) {
                    return UNKNOWN;
                }
                if (Scope.isFound(oldFound) && ((Scope.foundAccess(oldFound) & Acc.STATIC) != 0) == expected) {
                    if (kind == Scope.MemberKind.METHOD) {
                        return (expected ? Reason.METHOD_BECAME_INSTANCE : Reason.METHOD_BECAME_STATIC).ordinal();
                    }
                    return (expected ? Reason.FIELD_BECAME_INSTANCE : Reason.FIELD_BECAME_STATIC).ordinal();
                }
                return OK;
            }
        }
        int accessible = isAccessible(foundAccess, foundOwner, sourceClass, runtime, graph, hosts);
        if (accessible == MAYBE) {
            return UNKNOWN;
        }
        if (accessible == NO) {
            // Levels are compared instead of re-running isAccessible against old, because the
            // subclass walk only sees scanned classes and would demote real narrowing.
            long oldFound = oldScope.resolveMember(owner, member, kind);
            if (oldFound == Scope.UNKNOWN) {
                return UNKNOWN;
            }
            if (Scope.isFound(oldFound)
                    && Visibility.of(foundAccess).compareTo(Visibility.of(Scope.foundAccess(oldFound))) < 0) {
                return (kind == Scope.MemberKind.FIELD ? Reason.FIELD_ACCESS_NARROWED : Reason.METHOD_ACCESS_NARROWED).ordinal();
            }
            return OK;
        }
        if (kind == Scope.MemberKind.FIELD
                && SymbolRef.fieldWriteOf(meta) == SymbolRef.TRI_TRUE
                && (foundAccess & Acc.FINAL) != 0
                && sourceClass != foundOwner) {
            long oldFound = oldScope.resolveMember(owner, member, kind);
            if (oldFound == Scope.UNKNOWN) {
                return UNKNOWN;
            }
            if (Scope.isFound(oldFound) && (Scope.foundAccess(oldFound) & Acc.FINAL) == 0) {
                return Reason.FIELD_BECAME_FINAL.ordinal();
            }
            return OK;
        }
        return OK;
    }

    // ---- graph walks: breaks that need no constant-pool reference ----

    static void addFinalViolations(
            ApiIndex oldIndex,
            ApiIndex newIndex,
            Scope oldScope,
            ApiIndex fetched,
            ClassGraph graph,
            List<Violation> violations,
            Set<ViolationKey> seen) {
        IntSet finalClasses = newlyFinalClasses(oldIndex, newIndex);
        for (int node = 0; node < graph.size(); node++) {
            int superName = graph.superOf(node);
            if (superName != Intern.NONE && finalClasses.contains(superName)) {
                pushViolation(violations, seen, graph.sourceOf(node), graph.nameOf(node), SymbolRef.ofClass(superName), Reason.CLASS_BECAME_FINAL);
            }
        }

        Map<Integer, LongSet> finalMethods = newlyFinalMethods(oldIndex, newIndex);
        mergeMethods(finalMethods, addedFinalOverrides(oldIndex, newIndex, oldScope, null));
        if (finalMethods.isEmpty()) {
            return;
        }
        IntSet finalOwners = ownersOf(finalMethods);
        IntSet seenAncestors = new IntSet();
        IntBuf owners = new IntBuf(8);
        for (int node = 0; node < graph.size(); node++) {
            int className = graph.nameOf(node);
            int entry = fetched.entry(className);
            if (entry < 0) {
                continue;
            }
            finalOwnersOnChain(className, newIndex, graph, finalOwners, seenAncestors, owners);
            int n = owners.isEmpty() ? 0 : fetched.methodCount(entry);
            for (int k = 0; k < n; k++) {
                long key = fetched.methodKeyAt(entry, k);
                // The nearest final declaration is the one the JVM names.
                for (int i = 0; i < owners.n; i++) {
                    int owner = owners.a[i];
                    if (finalMethods.get(owner).contains(key)) {
                        SymbolRef reference = new SymbolRef(RefKind.METHOD, owner, key, Boolean.FALSE, null, null);
                        pushViolation(violations, seen, graph.sourceOf(node), className, reference, Reason.METHOD_BECAME_FINAL);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Version lag from the upgraded artifacts' own new classes: a scanned class from a
     * new-version JAR extends a class that is final on the runtime classpath. Invisible to
     * the pair diff because the final class lives in an artifact the upgrade did not change
     * (https://github.com/pact-foundation/pact-jvm/issues/1338). Old-relative: the same super
     * edge in the changed artifact's old version is pre-existing.
     */
    static void addExtendsFinalViolations(
            List<LagEdge> lagEdges, ApiIndex oldIndex, Scope runtime, List<Violation> violations, Set<ViolationKey> seen) {
        for (LagEdge edge : lagEdges) {
            int access = runtime.classAccess(edge.superName());
            if (access < 0 || (access & Acc.FINAL) == 0) {
                continue;
            }
            int oldEntry = oldIndex.entry(edge.className());
            if (oldEntry >= 0 && oldIndex.superOf(oldEntry) == edge.superName()) {
                continue;
            }
            pushViolation(violations, seen, edge.source(), edge.className(), SymbolRef.ofClass(edge.superName()), Reason.EXTENDS_FINAL_CLASS);
        }
    }

    /**
     * A scanned class extends a class that became an interface, or implements an interface
     * that became a class: it fails to load. Judged old-vs-new library kind, so an edge that
     * was already cross-kind is pre-existing.
     */
    static void addKindFlipViolations(
            ApiIndex oldIndex, ApiIndex newIndex, ClassGraph graph, List<Violation> violations, Set<ViolationKey> seen) {
        // owner -> 1 when the new kind is interface (an extends edge breaks), 0 when it is
        // class (an implements edge breaks).
        SymMap flipped = new SymMap();
        for (int e = 0; e < oldIndex.classCount(); e++) {
            int name = oldIndex.nameOf(e);
            int newEntry = newIndex.entry(name);
            if (newEntry < 0) {
                continue;
            }
            boolean oldInterface = (oldIndex.accessOf(e) & Acc.INTERFACE) != 0;
            boolean newInterface = (newIndex.accessOf(newEntry) & Acc.INTERFACE) != 0;
            if (oldInterface != newInterface) {
                flipped.put(name, newInterface ? 1 : 0);
            }
        }
        if (flipped.isEmpty()) {
            return;
        }
        for (int node = 0; node < graph.size(); node++) {
            int superName = graph.superOf(node);
            if (superName != Intern.NONE && flipped.get(superName) == 1) {
                pushViolation(violations, seen, graph.sourceOf(node), graph.nameOf(node), SymbolRef.ofClass(superName), Reason.CLASS_BECAME_INTERFACE);
            }
            int n = graph.interfaceCount(node);
            for (int k = 0; k < n; k++) {
                int iface = graph.interfaceAt(node, k);
                if (flipped.get(iface) == 0) {
                    pushViolation(violations, seen, graph.sourceOf(node), graph.nameOf(node), SymbolRef.ofClass(iface), Reason.INTERFACE_BECAME_CLASS);
                }
            }
        }
    }

    /**
     * A scanned class extends or implements a library type that is now sealed without naming
     * it, so it fails to load (JVMS 5.3.5). Only DIRECT supertypes, because that is the edge
     * the JVM checks. Old-relative over the permits lists.
     */
    static void addSealedViolations(
            ApiIndex oldIndex, ApiIndex newIndex, ClassGraph graph, List<Violation> violations, Set<ViolationKey> seen) {
        // owner -> new entry, for owners whose sealing is comparable on both sides.
        SymMap sealed = new SymMap();
        for (int e = 0; e < newIndex.classCount(); e++) {
            if (newIndex.permittedCount(e) < 0) {
                continue;
            }
            int name = newIndex.nameOf(e);
            int oldEntry = oldIndex.entry(name);
            if (oldEntry < 0) {
                continue;
            }
            // javac seals enums with constant-specific bodies since JDK 17
            // (https://issues.apache.org/jira/browse/GROOVY-10194), so a bare recompile gains
            // the attribute. A final super is `extends final class` on the new side, and on
            // the old side it had no subclasses to strand.
            if (((newIndex.accessOf(e) | oldIndex.accessOf(oldEntry)) & (Acc.ENUM | Acc.FINAL)) != 0) {
                continue;
            }
            // Reading unreadable sealing as unsealed is what would invent a violation out of
            // a corrupt old class file.
            if (oldIndex.sealingUnknown(oldEntry) || newIndex.sealingUnknown(e)) {
                continue;
            }
            sealed.put(name, e);
        }
        if (sealed.isEmpty()) {
            return;
        }
        for (int node = 0; node < graph.size(); node++) {
            int superName = graph.superOf(node);
            if (superName != Intern.NONE) {
                reportSealed(sealed, superName, node, oldIndex, newIndex, graph, violations, seen);
            }
            int n = graph.interfaceCount(node);
            for (int k = 0; k < n; k++) {
                reportSealed(sealed, graph.interfaceAt(node, k), node, oldIndex, newIndex, graph, violations, seen);
            }
        }
    }

    private static void reportSealed(
            SymMap sealed, int owner, int node, ApiIndex oldIndex, ApiIndex newIndex, ClassGraph graph, List<Violation> violations, Set<ViolationKey> seen) {
        int newEntry = sealed.get(owner);
        if (newEntry < 0) {
            return;
        }
        int className = graph.nameOf(node);
        if (newIndex.permits(newEntry, className)) {
            return;
        }
        int oldEntry = oldIndex.entry(owner);
        if (oldIndex.permittedCount(oldEntry) >= 0 && !oldIndex.permits(oldEntry, className)) {
            return;
        }
        pushViolation(violations, seen, graph.sourceOf(node), className, SymbolRef.ofClass(owner), Reason.CLASS_BECAME_SEALED);
    }

    /**
     * A META-INF/services provider ServiceLoader could construct under old but not under new:
     * ServiceConfigurationError, not a LinkageError, so no reference or class-load edge carries
     * it. Only a PROVEN old-side yes gates the check and only a PROVEN new-side no is reported.
     */
    static void addSpiViolations(
            ClassGraph graph, Scope oldScope, Scope runtimeScope, SpiServices services, List<Violation> violations, Set<ViolationKey> seen) {
        for (Provider p : stillListedProviders(services)) {
            if (spiInstantiable(graph, oldScope, p.provider(), p.iface(), true) != YES) {
                continue;
            }
            if (spiInstantiable(graph, runtimeScope, p.provider(), p.iface(), false) != NO) {
                continue;
            }
            Reason reason = runtimeScope.containsClass(p.provider())
                    ? Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE
                    : Reason.SERVICE_PROVIDER_REMOVED;
            pushViolation(violations, seen, p.source(), p.provider(), SymbolRef.ofClass(p.iface()), reason);
        }
    }

    private static final long NO_ARG_CONSTRUCTOR = MemberKey.of("<init>", "()V");

    /**
     * The class-path rule: public, concrete, assignable to the service, public no-arg
     * constructor. A static {@code provider()} factory is deliberately ignored, the JDK honors
     * it only for providers in explicit modules.
     */
    private static int spiInstantiable(ClassGraph graph, Scope scope, int provider, int iface, boolean scopeFirst) {
        int access = scope.classAccess(provider);
        if (access < 0) {
            // A graph node means the class is still on the runtime classpath and only its
            // member table is missing: not proof of removal.
            return graph.contains(provider) ? MAYBE : NO;
        }
        if ((access & Acc.PUBLIC) == 0 || (access & (Acc.INTERFACE | Acc.ABSTRACT)) != 0) {
            return NO;
        }
        int constructor = scope.directMethodAccess(provider, NO_ARG_CONSTRUCTOR);
        if (constructor < 0 || (constructor & Acc.PUBLIC) == 0) {
            return NO;
        }
        return isAssignable(graph, scope, provider, iface, scopeFirst);
    }

    /**
     * Whether {@code from} is assignable to {@code to}, following interface edges too. An edge
     * escaping both graph and scope answers MAYBE, never NO. The old side passes
     * {@code scopeFirst}: the scan graph carries the NEW hierarchy whenever the upgraded jar
     * is a scan target, and judging the old side by it hid every dropped-interface break.
     */
    private static int isAssignable(ClassGraph graph, Scope scope, int from, int to, boolean scopeFirst) {
        IntQueue queue = new IntQueue();
        IntSet seen = new IntSet();
        boolean escaped = false;
        queue.add(from);
        while (!queue.isEmpty()) {
            int c = queue.poll();
            if (c == to) {
                return YES;
            }
            if (c == Scope.objectSym() || !seen.add(c)) {
                continue;
            }
            ApiIndex layer = scope.layerOf(c);
            int node = graph.node(c);
            if (layer != null && (scopeFirst || node < 0)) {
                pushSupertypes(queue, layer, scope.entryOf(c));
            } else if (node >= 0) {
                pushSupertypes(queue, graph, node);
            } else {
                escaped = true;
            }
        }
        return escaped ? MAYBE : NO;
    }

    // ---- method selection: AbstractMethodError and conflicting defaults ----

    private enum ImplStatus {
        /** A concrete declaration wins selection. */
        CONCRETE,
        /** Declared only abstractly, and the whole closure was in scope. */
        ABSTRACT_ONLY,
        /** Two or more unrelated superinterfaces supply a default: selection has no winner. */
        CONFLICT,
        /** No declaration at all, and the whole closure was in scope. */
        ABSENT,
        /** The closure escaped scope before a concrete declaration could be ruled out. */
        UNKNOWN
    }

    /**
     * Whether {@code key} has a concrete implementation available to {@code start}, in JVMS
     * 5.4.6 selection order. Phase 1 walks the superclass chain, which wins over interfaces:
     * the first class declaring the method decides it. Phase 2 consults the superinterfaces;
     * specificity is not modeled, so an abstract/concrete mix is UNKNOWN rather than guessed.
     * A static or private declaration never overrides and is ignored.
     */
    private static ImplStatus implementationStatus(int start, long key, Scope scope) {
        IntQueue queue = new IntQueue();
        IntSet seen = new IntSet();
        int current = start;
        while (current != Intern.NONE) {
            if (!seen.add(current)) {
                break;
            }
            if (current == Scope.objectSym()) {
                if (Scope.isObjectMethod(key)) {
                    return ImplStatus.CONCRETE;
                }
                break;
            }
            ApiIndex layer = scope.layerOf(current);
            if (layer == null) {
                // A nearer class could declare the method, so the chain is inconclusive.
                return ImplStatus.UNKNOWN;
            }
            int entry = scope.entryOf(current);
            int access = layer.findMethod(entry, key);
            if (access >= 0 && (access & (Acc.STATIC | Acc.PRIVATE)) == 0) {
                return (access & Acc.ABSTRACT) == 0 ? ImplStatus.CONCRETE : ImplStatus.ABSTRACT_ONLY;
            }
            int n = layer.interfaceCount(entry);
            for (int k = 0; k < n; k++) {
                queue.add(layer.interfaceAt(entry, k));
            }
            current = layer.superOf(entry);
        }
        IntSet interfaceSeen = new IntSet();
        boolean sawAbstract = false;
        boolean escaped = false;
        IntBuf concrete = new IntBuf(4);
        while (!queue.isEmpty()) {
            int iface = queue.poll();
            if (iface == Scope.objectSym() || !interfaceSeen.add(iface)) {
                continue;
            }
            ApiIndex layer = scope.layerOf(iface);
            if (layer == null) {
                escaped = true;
                continue;
            }
            int entry = scope.entryOf(iface);
            int access = layer.findMethod(entry, key);
            if (access >= 0 && (access & (Acc.STATIC | Acc.PRIVATE)) == 0) {
                if ((access & Acc.ABSTRACT) == 0) {
                    concrete.add(iface);
                } else {
                    sawAbstract = true;
                }
            }
            int n = layer.interfaceCount(entry);
            for (int k = 0; k < n; k++) {
                queue.add(layer.interfaceAt(entry, k));
            }
        }
        if (escaped || (sawAbstract && !concrete.isEmpty())) {
            return ImplStatus.UNKNOWN;
        } else if (sawAbstract) {
            return ImplStatus.ABSTRACT_ONLY;
        } else if (concrete.isEmpty()) {
            return ImplStatus.ABSENT;
        } else if (maximallySpecificCount(concrete, scope) > 1) {
            return ImplStatus.CONFLICT;
        }
        return ImplStatus.CONCRETE;
    }

    /**
     * How many declarations are maximally specific (JVMS 5.4.3.3): an interface that another
     * declaring interface inherits from is overridden by it and does not compete.
     */
    private static int maximallySpecificCount(IntBuf declarations, Scope scope) {
        IntSet shadowed = new IntSet();
        IntQueue queue = new IntQueue();
        IntSet seen = new IntSet();
        for (int d = 0; d < declarations.n; d++) {
            queue.clear();
            seen.clear();
            pushInterfaces(queue, scope, declarations.a[d]);
            while (!queue.isEmpty()) {
                int iface = queue.poll();
                if (!seen.add(iface)) {
                    continue;
                }
                for (int i = 0; i < declarations.n; i++) {
                    if (declarations.a[i] == iface) {
                        shadowed.add(iface);
                    }
                }
                pushInterfaces(queue, scope, iface);
            }
        }
        int count = 0;
        for (int d = 0; d < declarations.n; d++) {
            if (!shadowed.contains(declarations.a[d])) {
                count++;
            }
        }
        return count;
    }

    private static void pushInterfaces(IntQueue queue, Scope scope, int className) {
        ApiIndex layer = scope.layerOf(className);
        if (layer == null) {
            return;
        }
        int entry = scope.entryOf(className);
        int n = layer.interfaceCount(entry);
        for (int k = 0; k < n; k++) {
            queue.add(layer.interfaceAt(entry, k));
        }
    }

    /**
     * A concrete scanned class that inherits an abstract method with no implementation throws
     * AbstractMethodError when it is invoked, and one inheriting two unrelated defaults has no
     * selection winner. Structural like the newly-final walk. Old-relative: report only when
     * old had an implementation or no such method at all.
     */
    static void addSelectionViolations(
            Scope oldScope,
            Scope runtime,
            ApiIndex oldIndex,
            ApiIndex newIndex,
            ApiIndex fetched,
            ClassGraph graph,
            Scan.Invocations invocations,
            List<Violation> violations,
            Set<ViolationKey> seen) {
        Map<Integer, LongSet> abstractMethods = methodsNewlyAbstract(oldIndex, newIndex);
        Map<Integer, LongSet> defaultMethods = methodsNewlyDefault(oldIndex, newIndex);
        if (abstractMethods.isEmpty() && defaultMethods.isEmpty()) {
            return;
        }
        Walk walk = new Walk();
        Walk downWalk = new Walk();
        IntSet supertypes = new IntSet();
        for (int node = 0; node < graph.size(); node++) {
            int className = graph.nameOf(node);
            // Only a concrete, instantiable class triggers the error. Access flags come from
            // the fetched table; an unfetched candidate is skipped, like Unknown.
            int entry = fetched.entry(className);
            if (entry < 0 || (fetched.accessOf(entry) & (Acc.ABSTRACT | Acc.INTERFACE)) != 0) {
                continue;
            }
            // Probed methods declared by any supertype, mapped to the declaring owner
            // (smallest by name, for a deterministic report).
            Map<Long, Integer> candidates = new HashMap<>();
            supertypes.clear();
            Escapes escapes = new Escapes();
            forEachSupertype(className, newIndex, graph, walk, anc -> {
                supertypes.add(anc);
                if (!newIndex.containsClass(anc) && !graph.contains(anc)) {
                    escapes.add(anc);
                }
                for (int side = 0; side < 2; side++) {
                    LongSet keys = (side == 0 ? abstractMethods : defaultMethods).get(anc);
                    if (keys == null) {
                        continue;
                    }
                    for (long key : keys.toArray()) {
                        candidates.merge(key, anc, (owner, other) -> Intern.compare(other, owner) < 0 ? other : owner);
                    }
                }
            });
            for (Map.Entry<Long, Integer> candidate : candidates.entrySet()) {
                long key = candidate.getKey();
                Reason reason;
                switch (implementationStatus(className, key, runtime)) {
                    case ABSTRACT_ONLY -> reason = Reason.METHOD_BECAME_ABSTRACT;
                    case CONFLICT -> reason = Reason.CONFLICTING_DEFAULT_METHODS;
                    default -> {
                        continue;
                    }
                }
                ImplStatus before = implementationStatus(className, key, oldScope);
                if (before != ImplStatus.CONCRETE && before != ImplStatus.ABSENT) {
                    // Broken the same way in old is pre-existing; Unknown is conservative.
                    continue;
                }
                SymbolRef reference = new SymbolRef(RefKind.METHOD, candidate.getValue(), key, Boolean.FALSE, null, null);
                Violation v = pushViolation(violations, seen, graph.sourceOf(node), className, reference, reason);
                if (v != null) {
                    // Both errors throw at invocation, not at class load, so a violation
                    // nothing can call is latent rather than dropped.
                    boolean invoked = false;
                    IntSet owners = invocations.ownersOf(key);
                    if (owners != null) {
                        for (int evidenceOwner : owners.toArray()) {
                            if (onDispatchChain(evidenceOwner, className, supertypes, escapes, newIndex, graph, downWalk)) {
                                invoked = true;
                                break;
                            }
                        }
                    }
                    v.invocationFound = invoked;
                }
            }
        }
    }

    /**
     * Types a hierarchy walk left analyzed scope through. An escape normally forces "related",
     * with one provable exception: the JVM reserves java.*, so an escape into it cannot hide a
     * library or application class. Without the carve-out the filter is nearly inert, because
     * any class implementing Serializable escapes. javax/, sun/ and com/sun/ do not qualify.
     */
    private static final class Escapes {
        boolean platform;
        boolean other;

        void add(int className) {
            if (Intern.startsWith(className, "java/")) {
                platform = true;
            } else {
                other = true;
            }
        }

        boolean couldHide(int owner) {
            return other || (platform && Intern.startsWith(owner, "java/"));
        }
    }

    /**
     * Whether a call on {@code evidenceOwner} can dispatch onto an instance of
     * {@code className}: the owner is the class, a supertype, or a subtype. A sibling subtype
     * is provably unrelated.
     */
    private static boolean onDispatchChain(
            int evidenceOwner, int className, IntSet supertypes, Escapes supertypesEscaped, ApiIndex newIndex, ClassGraph graph, Walk walk) {
        if (evidenceOwner == className || supertypesEscaped.couldHide(evidenceOwner) || supertypes.contains(evidenceOwner)) {
            return true;
        }
        boolean[] found = new boolean[1];
        Escapes down = new Escapes();
        forEachSupertype(evidenceOwner, newIndex, graph, walk, anc -> {
            found[0] |= anc == className;
            if (!newIndex.containsClass(anc) && !graph.contains(anc)) {
                down.add(anc);
            }
        });
        return found[0] || down.couldHide(className);
    }

    // ---- library-side inferences ----

    private static IntSet newlyFinalClasses(ApiIndex oldIndex, ApiIndex newIndex) {
        IntSet out = new IntSet();
        for (int e = 0; e < oldIndex.classCount(); e++) {
            int newEntry = newIndex.entry(oldIndex.nameOf(e));
            if (newEntry >= 0 && (oldIndex.accessOf(e) & Acc.FINAL) == 0 && (newIndex.accessOf(newEntry) & Acc.FINAL) != 0) {
                out.add(oldIndex.nameOf(e));
            }
        }
        return out;
    }

    static Map<Integer, LongSet> newlyFinalMethods(ApiIndex oldIndex, ApiIndex newIndex) {
        Map<Integer, LongSet> out = new HashMap<>();
        for (int e = 0; e < oldIndex.classCount(); e++) {
            int className = oldIndex.nameOf(e);
            int newEntry = newIndex.entry(className);
            if (newEntry < 0) {
                continue;
            }
            int n = oldIndex.methodCount(e);
            for (int k = 0; k < n; k++) {
                int oldAccess = oldIndex.methodAccessAt(e, k);
                if ((oldAccess & Acc.FINAL) != 0) {
                    continue;
                }
                long key = oldIndex.methodKeyAt(e, k);
                int newAccess = newIndex.findMethod(newEntry, key);
                if (newAccess >= 0 && (newAccess & Acc.FINAL) != 0 && !compilerGeneratedOnly(oldAccess, newAccess)) {
                    out.computeIfAbsent(className, c -> new LongSet()).add(key);
                }
            }
        }
        return out;
    }

    /**
     * Final methods a class declares in new but only inherited in old (owner -> keys), when the
     * inherited version was one a subclass could override. The inherited declaration is the old
     * side of the bridge guard. A method whose old chain leaves {@code oldScope} is left out like
     * Unknown, and its class goes to {@code unresolved} when that is non-null.
     */
    static Map<Integer, LongSet> addedFinalOverrides(ApiIndex oldIndex, ApiIndex newIndex, Scope oldScope, IntSet unresolved) {
        Map<Integer, LongSet> out = new HashMap<>();
        for (int e = 0; e < newIndex.classCount(); e++) {
            int className = newIndex.nameOf(e);
            int oldEntry = oldIndex.entry(className);
            // A subclass of a class that was already final never loaded.
            if (oldEntry < 0 || (oldIndex.accessOf(oldEntry) & Acc.FINAL) != 0) {
                continue;
            }
            int n = newIndex.methodCount(e);
            for (int k = 0; k < n; k++) {
                int newAccess = newIndex.methodAccessAt(e, k);
                long key = newIndex.methodKeyAt(e, k);
                boolean blocksOverride = (newAccess & (Acc.FINAL | Acc.STATIC | Acc.PRIVATE)) == Acc.FINAL;
                if (!blocksOverride || oldIndex.findMethod(oldEntry, key) >= 0) {
                    continue;
                }
                long inherited = oldScope.resolveMember(className, key, Scope.MemberKind.METHOD);
                if (inherited == Scope.UNKNOWN) {
                    if (unresolved != null) {
                        unresolved.add(className);
                    }
                    continue;
                }
                if (!Scope.isFound(inherited)) {
                    continue;
                }
                int oldAccess = Scope.foundAccess(inherited);
                if (isOverridable(oldAccess) && !compilerGeneratedOnly(oldAccess, newAccess)) {
                    out.computeIfAbsent(className, c -> new LongSet()).add(key);
                }
            }
        }
        return out;
    }

    /** Static and private methods never override, and a final one cannot be overridden. */
    private static boolean isOverridable(int access) {
        return (access & (Acc.FINAL | Acc.STATIC | Acc.PRIVATE)) == 0;
    }

    private static void mergeMethods(Map<Integer, LongSet> into, Map<Integer, LongSet> from) {
        for (Map.Entry<Integer, LongSet> e : from.entrySet()) {
            LongSet keys = into.computeIfAbsent(e.getKey(), c -> new LongSet());
            for (long key : e.getValue().toArray()) {
                keys.add(key);
            }
        }
    }

    /**
     * A generic-signature edit can reshape a bridge without a source-visible API change, so a
     * method that is synthetic or a bridge on every side where it exists is not the subject of
     * a became-abstract or became-final inference. A real method on either side still is. A
     * concrete bridge turned real abstract, a bridge turned real final, and a real method turned
     * kotlinc's final collection bridge all break subclasses (JVM-confirmed).
     */
    private static boolean compilerGeneratedOnly(int oldAccess, int newAccess) {
        return isSyntheticOrBridge(newAccess) && (oldAccess < 0 || isSyntheticOrBridge(oldAccess));
    }

    private static boolean isSyntheticOrBridge(int access) {
        return (access & (Acc.SYNTHETIC | Acc.BRIDGE)) != 0;
    }

    /**
     * Methods abstract in new but not in old (owner -> keys). Covers both shapes that throw
     * AbstractMethodError: a concrete method turned abstract, and a brand-new abstract method.
     * The owner must exist in old, so a wholly-new type is not a break for existing code.
     */
    static Map<Integer, LongSet> methodsNewlyAbstract(ApiIndex oldIndex, ApiIndex newIndex) {
        Map<Integer, LongSet> out = new HashMap<>();
        for (int e = 0; e < newIndex.classCount(); e++) {
            int className = newIndex.nameOf(e);
            int oldEntry = oldIndex.entry(className);
            if (oldEntry < 0) {
                continue;
            }
            int n = newIndex.methodCount(e);
            for (int k = 0; k < n; k++) {
                int newAccess = newIndex.methodAccessAt(e, k);
                if ((newAccess & Acc.ABSTRACT) == 0) {
                    continue;
                }
                long key = newIndex.methodKeyAt(e, k);
                int oldAccess = oldIndex.findMethod(oldEntry, key);
                if ((oldAccess < 0 || (oldAccess & Acc.ABSTRACT) == 0) && !compilerGeneratedOnly(oldAccess, newAccess)) {
                    out.computeIfAbsent(className, c -> new LongSet()).add(key);
                }
            }
        }
        return out;
    }

    /** Interface methods that are a default in new but were not in old (owner -> keys). */
    static Map<Integer, LongSet> methodsNewlyDefault(ApiIndex oldIndex, ApiIndex newIndex) {
        Map<Integer, LongSet> out = new HashMap<>();
        for (int e = 0; e < newIndex.classCount(); e++) {
            int className = newIndex.nameOf(e);
            if ((newIndex.accessOf(e) & Acc.INTERFACE) == 0) {
                continue;
            }
            int oldEntry = oldIndex.entry(className);
            if (oldEntry < 0) {
                continue;
            }
            int n = newIndex.methodCount(e);
            for (int k = 0; k < n; k++) {
                int newAccess = newIndex.methodAccessAt(e, k);
                if ((newAccess & (Acc.ABSTRACT | Acc.STATIC | Acc.PRIVATE)) != 0 || isSyntheticOrBridge(newAccess)) {
                    continue;
                }
                long key = newIndex.methodKeyAt(e, k);
                int oldAccess = oldIndex.findMethod(oldEntry, key);
                if (oldAccess < 0 || (oldAccess & Acc.ABSTRACT) != 0) {
                    out.computeIfAbsent(className, c -> new LongSet()).add(key);
                }
            }
        }
        return out;
    }

    /** Every method the selection walk may report, for matching raw references in pass 1. */
    static MemberProbe selectionMemberProbe(ApiIndex oldIndex, ApiIndex newIndex) {
        LongSet keys = new LongSet();
        for (LongSet set : methodsNewlyAbstract(oldIndex, newIndex).values()) {
            for (long key : set.toArray()) {
                keys.add(key);
            }
        }
        for (LongSet set : methodsNewlyDefault(oldIndex, newIndex).values()) {
            for (long key : set.toArray()) {
                keys.add(key);
            }
        }
        return new MemberProbe(keys.toArray());
    }

    /**
     * Invocation evidence from the checked library's own new-version jars, which need not be
     * scan targets: the library is often the only caller of a newly-abstract member. A read
     * failure yields no evidence, tolerated only because such a jar already failed at index build.
     */
    static void libraryInvocationEvidence(List<String> newPaths, MemberProbe probe, Scan.Invocations into) {
        if (probe.isEmpty()) {
            return;
        }
        Input.Sink<IntBuf> sink = new Input.Sink<>() {
            @Override
            public IntBuf newLeaf() {
                return new IntBuf(8);
            }

            @Override
            public void accept(IntBuf leaf, Scratch scratch, int source, int entry, ClassSource bytes) {
                try {
                    Extract.parseHeader(scratch.parser, bytes);
                } catch (ClassParser.FormatException e) {
                    return;
                }
                Extract.invocationEvidence(leaf, scratch.parser, scratch, probe);
            }
        };
        Input.onPool(() -> {
            for (String path : newPaths) {
                List<IntBuf> leaves = new ArrayList<>();
                try {
                    Input.forEachClass(path, sink, leaves);
                } catch (UikaException e) {
                    // No evidence from an unreadable jar; whatever was read before the failure counts.
                }
                for (IntBuf leaf : leaves) {
                    into.addAll(leaf);
                }
            }
        });
    }

    /** The owners of a per-owner map as an int set, so a per-class walk probes without boxing. */
    private static IntSet ownersOf(Map<Integer, LongSet> byOwner) {
        IntSet owners = new IntSet(byOwner.size());
        for (int owner : byOwner.keySet()) {
            owners.add(owner);
        }
        return owners;
    }

    /**
     * Fills {@code out} with the superclasses of {@code className} that own a newly final
     * method, nearest first.
     *
     * @param seen scratch, cleared here: this runs once per scanned class
     */
    private static void finalOwnersOnChain(int className, ApiIndex newIndex, ClassGraph graph, IntSet finalOwners, IntSet seen, IntBuf out) {
        out.n = 0;
        int next = graph.superOf(graph.node(className));
        seen.clear();
        while (next != Intern.NONE && seen.add(next)) {
            if (finalOwners.contains(next)) {
                out.add(next);
            }
            int n = graph.node(next);
            int superName = n < 0 ? Intern.NONE : graph.superOf(n);
            if (superName == Intern.NONE) {
                int entry = newIndex.entry(next);
                superName = entry < 0 ? Intern.NONE : newIndex.superOf(entry);
            }
            next = superName;
        }
    }

    // ---- access ----

    private static final int YES = 1;
    private static final int NO = 0;
    /** The relationship cannot be proven either way; callers treat the reference as unverified. */
    private static final int MAYBE = 2;

    private static int isAccessible(int access, int owner, int sourceClass, Scope runtime, ClassGraph graph, NestHosts hosts) {
        if ((access & Acc.PUBLIC) != 0) {
            return YES;
        }
        if ((access & Acc.PRIVATE) != 0) {
            // Nestmates share private access (JVMS 5.4.4, Java 11+).
            return owner == sourceClass || nestHostOf(owner, runtime, hosts) == nestHostOf(sourceClass, runtime, hosts) ? YES : NO;
        }
        if (Intern.samePackage(owner, sourceClass)) {
            return YES;
        }
        if ((access & Acc.PROTECTED) == 0) {
            return NO;
        }
        return isSubclass(sourceClass, owner, runtime, graph);
    }

    /**
     * A class without a NestHost attribute hosts itself. A class outside both the graph and
     * the scope also defaults to hosting itself, which forbids private access.
     */
    private static int nestHostOf(int className, Scope runtime, NestHosts hosts) {
        int node = hosts.graph.node(className);
        int host = node >= 0 ? hosts.of(node) : runtime.classNestHost(className);
        return host == Intern.NONE ? className : host;
    }

    /**
     * Nest hosts of scanned classes. Pass 1 stops inflating a class once its constant pool
     * shows no reference into the checked library, and NestHost sits at the end of the file,
     * so for such a class it is read here on demand. Only a private-access verdict asks, and
     * only about the class DECLARING the member, so this runs for a handful of classes.
     */
    static final class NestHosts {
        final ClassGraph graph;
        private final Map<Integer, String> entryOverrides;
        private final SymMap read = new SymMap();

        NestHosts(ClassGraph graph, Map<Integer, String> entryOverrides) {
            this.graph = graph;
            this.entryOverrides = entryOverrides;
        }

        int of(int node) {
            int host = graph.nestHostOf(node);
            if (host != ClassGraph.NEST_HOST_UNREAD) {
                return host;
            }
            int className = graph.nameOf(node);
            int cached = read.get(className);
            if (cached != SymMap.ABSENT) {
                return cached - 1;
            }
            int[] found = {Intern.NONE};
            String entry = entryName(entryOverrides, className);
            try {
                Input.fetchEntries(Intern.str(graph.sourceOf(node)), List.of(new Input.Wanted(className, entry)), (name, bytes, length) -> {
                    Scratch scratch = Scratch.current();
                    try {
                        scratch.parser.parse(bytes, length);
                        if (scratch.parser.nestHost != 0) {
                            found[0] = scratch.parser.internClassName(scratch, scratch.parser.nestHost);
                        }
                    } catch (ClassParser.FormatException e) {
                        // Unreadable now means "hosts itself", which denies private access.
                    }
                });
            } catch (UikaException e) {
                // Same conservative default.
            }
            read.put(className, found[0] + 1);
            return found[0];
        }
    }

    /**
     * Three-valued subclass walk for protected access. Graph edges first, then the runtime
     * scope's super chain. MAYBE when the chain reaches a class visible in no scope: the
     * reference may well be legal. NO only when the chain was walked to Object.
     */
    private static int isSubclass(int className, int target, Scope runtime, ClassGraph graph) {
        int current = className;
        IntSet seen = new IntSet();
        while (true) {
            if (current == target) {
                return YES;
            }
            if (current == Scope.objectSym() || !seen.add(current)) {
                return NO;
            }
            int node = graph.node(current);
            int superName;
            if (node >= 0) {
                superName = graph.superOf(node);
            } else {
                ApiIndex layer = runtime.layerOf(current);
                if (layer == null) {
                    return MAYBE;
                }
                superName = layer.superOf(runtime.entryOf(current));
            }
            if (superName == Intern.NONE) {
                return NO;
            }
            current = superName;
        }
    }
}
