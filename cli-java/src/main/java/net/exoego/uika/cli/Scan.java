package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;

/**
 * Pass 1: stream scan units (JAR / class directory) in parallel through a sliding window of
 * paths and fold them into the hierarchy graph plus reference records. No member tables are
 * kept, so peak memory is the graph plus one window of temporaries.
 */
final class Scan {
    /** Aggregated pass-1 result. */
    static final class Result {
        final ClassGraph graph;
        /** Records {@code source, className, refCount, (meta, owner, name, descriptor)...}, only for classes with references. */
        final IntArena records = new IntArena();
        int recordCount;
        /** Re-read locations for classes whose entry name is not "{name}.class". */
        final Map<Integer, String> entryOverrides = new HashMap<>();
        /** Distinct (owner, member) method references matching a probed member. NOT filtered by first-wins. */
        final Invocations invocations = new Invocations();
        final List<String> warnings = new ArrayList<>();
        int scannedClasses;
        /** META-INF/services provider files of the scan targets, in path order. Only with edges on. */
        final List<Reach.ServiceFile> services = new ArrayList<>();
        final List<String> serviceWarnings = new ArrayList<>();

        Result() {
            this(true);
        }

        /** @param edges whether the graph stores class-load edges, which only reachability reads */
        Result(boolean edges) {
            graph = new ClassGraph(edges);
        }

        /** Folds one leaf in; duplicate class names are first-wins, which is classpath order. */
        void merge(Extract.ScanLeaf leaf) {
            int[] a = leaf.records.a;
            int end = leaf.records.n;
            int at = 0;
            while (at < end) {
                int source = a[at];
                int className = a[at + 1];
                int superName = a[at + 2];
                int nestHost = a[at + 3];
                int override = a[at + 4];
                int interfaceCount = a[at + 5];
                int interfacesAt = at + 6;
                int edgeCount = a[interfacesAt + interfaceCount];
                int edgesAt = interfacesAt + interfaceCount + 1;
                int refCount = a[edgesAt + edgeCount];
                int refsAt = edgesAt + edgeCount + 1;
                // A definition that loses first-wins never loads at runtime, so its references
                // are dropped along with its hierarchy: judging another version's bytecode
                // against the winner's hierarchy produced false positives.
                boolean won = graph.insertIfAbsent(
                        className, superName, nestHost, source, a, interfacesAt, interfaceCount, edgesAt, edgeCount);
                if (won) {
                    if (override >= 0) {
                        entryOverrides.put(className, leaf.overrides.get(override));
                    }
                    if (refCount > 0) {
                        records.add(source);
                        records.add(className);
                        records.add(refCount);
                        records.addAll(a, refsAt, refCount * 4);
                        recordCount++;
                    }
                }
                at = refsAt + refCount * 4;
            }
            invocations.addAll(leaf.invocations);
            if (leaf.warnings != null) {
                warnings.addAll(leaf.warnings);
            }
            scannedClasses += leaf.scanned;
            leaf.records.release();
        }
    }

    /**
     * One parsed class in object form: what pass 1 produces for a class, for callers that
     * build a scan by hand instead of reading class files.
     *
     * @param superName {@link Intern#NONE} for none
     * @param nestHost {@link Intern#NONE} for none
     * @param entryOverride entry name when it is not "{className}.class", else null
     * @param hierarchy false for a class the graph already held when it was parsed (a
     *     guaranteed first-wins loser), which contributes nothing but evidence
     */
    record Target(
            int source,
            int className,
            boolean hierarchy,
            int superName,
            int[] interfaces,
            int nestHost,
            String entryOverride,
            List<SymbolRef> refs,
            int[] edges) {}

    /** Packs targets into a leaf, the form {@link Result#merge} consumes. */
    static Extract.ScanLeaf leafOf(List<Target> targets) {
        Extract.ScanLeaf leaf = new Extract.ScanLeaf();
        IntBuf out = leaf.records;
        for (Target t : targets) {
            leaf.scanned++;
            if (!t.hierarchy()) {
                continue;
            }
            out.add(t.source());
            out.add(t.className());
            out.add(t.superName());
            out.add(t.nestHost());
            if (t.entryOverride() == null) {
                out.add(-1);
            } else {
                if (leaf.overrides == null) {
                    leaf.overrides = new ArrayList<>();
                }
                out.add(leaf.overrides.size());
                leaf.overrides.add(t.entryOverride());
            }
            out.add(t.interfaces().length);
            for (int iface : t.interfaces()) {
                out.add(iface);
            }
            out.add(t.edges().length);
            for (int edge : t.edges()) {
                out.add(edge);
            }
            out.add(t.refs().size());
            for (SymbolRef r : t.refs()) {
                out.add(SymbolRef.pack(
                        r.kind(),
                        r.expectedStatic() == null ? SymbolRef.TRI_NONE : r.expectedStatic() ? SymbolRef.TRI_TRUE : SymbolRef.TRI_FALSE,
                        r.fieldWrite() == null ? SymbolRef.TRI_NONE : r.fieldWrite() ? SymbolRef.TRI_TRUE : SymbolRef.TRI_FALSE,
                        Boolean.TRUE.equals(r.instantiated())));
                out.add(r.owner());
                out.add(r.hasMember() ? MemberKey.name(r.member()) : Intern.NONE);
                out.add(r.hasMember() ? MemberKey.descriptor(r.member()) : Intern.NONE);
            }
        }
        return leaf;
    }

    /** Set of (owner, name, descriptor) triples. */
    static final class Invocations {
        private final Map<Long, IntSet> ownersByMember = new HashMap<>();
        private int size;

        void add(int owner, long member) {
            if (ownersByMember.computeIfAbsent(member, k -> new IntSet()).add(owner)) {
                size++;
            }
        }

        void addAll(IntBuf triples) {
            for (int i = 0; i < triples.n; i += 3) {
                add(triples.a[i], MemberKey.of(triples.a[i + 1], triples.a[i + 2]));
            }
        }

        /** Owners through which {@code member} is invoked somewhere in the scan, or null. */
        IntSet ownersOf(long member) {
            return ownersByMember.get(member);
        }

        int size() {
            return size;
        }
    }

    private Scan() {}

    /**
     * Paths in flight at once, from central-directory read to merge. Their directory columns
     * and their leaves are what the window holds in memory, and the reads and scans it keeps
     * forked ahead are what stops workers from parking while the path merged next is slow.
     */
    static int window() {
        String env = Env.get("UIKA_CHUNK");
        if (env != null) {
            try {
                int n = Integer.parseInt(env.trim());
                if (n > 0) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return Scratch.threads() * 8;
    }

    /** The first window's central-directory reads, running on the pool ahead of the scan. */
    static final class Ahead {
        private final List<String> paths;
        private final boolean collectEdges;
        private final Input.Prepared[] prepared;
        private final List<ForkJoinTask<?>> tasks = new ArrayList<>();

        private Ahead(List<String> paths, boolean collectEdges) {
            this.paths = paths;
            this.collectEdges = collectEdges;
            this.prepared = new Input.Prepared[paths.size()];
        }
    }

    /**
     * Starts the first window's central-directory reads now, for a {@link #scanTargetPaths}
     * over the same paths later. The reads need nothing the caller may still be building.
     */
    static Ahead prepareAhead(List<String> paths, boolean collectEdges) {
        Ahead ahead = new Ahead(paths, collectEdges);
        int n = Math.min(paths.size(), window());
        for (int i = 0; i < n; i++) {
            int index = i;
            ahead.tasks.add(Scratch.pool().submit(() -> ahead.prepared[index] = Input.prepare(paths.get(index), collectEdges)));
        }
        return ahead;
    }

    static Result scanTargetPaths(List<String> paths, ApiIndex oldIndex, MemberProbe probe, boolean collectEdges) {
        return scanTargetPaths(paths, oldIndex, probe, collectEdges, null);
    }

    /**
     * Three cursors move through the paths in order, on this thread: central-directory reads
     * are forked up to a window ahead of the merge; every read that is in is deduplicated and
     * its scan forked; the scan merged next is joined. Nothing waits for a whole batch of
     * paths, so a slow jar holds up its own merge and nothing else, and while a scan is
     * joined this thread executes other forked tasks. Dedup and merge run in path order, so
     * duplicate-class winners are the classpath's first, and a merged path's leaves go back
     * to the pool at once.
     *
     * @param ahead the first window's directory reads if they were started early, else null
     */
    static Result scanTargetPaths(List<String> paths, ApiIndex oldIndex, MemberProbe probe, boolean collectEdges, Ahead ahead) {
        Result result = new Result(collectEdges);
        NameSet oldNames = oldIndex.classNameSet();
        boolean useAhead = ahead != null && ahead.paths.equals(paths) && ahead.collectEdges == collectEdges;
        Input.onPool(() -> {
            Dedup dedup = new Dedup();
            int window = window();
            int n = paths.size();
            Input.Prepared[] prepared = useAhead ? ahead.prepared : new Input.Prepared[n];
            ForkJoinTask<?>[] prepares = new ForkJoinTask<?>[n];
            ForkJoinTask<?>[] scans = new ForkJoinTask<?>[n];
            @SuppressWarnings("unchecked")
            Input.DirectoryScan.Root<Extract.ScanLeaf>[] roots = new Input.DirectoryScan.Root[n];
            @SuppressWarnings("unchecked")
            List<Extract.ScanLeaf>[] perPath = new List[n];
            // Workers read the graph while this thread grows it: `contains` is the one read,
            // and a class not yet merged reads as absent.
            Extract.ScanSink sink = new Extract.ScanSink(oldNames, result.graph, collectEdges, probe);
            // Directories share a few lanes instead of getting a task each.
            Input.DirectoryScan<Extract.ScanLeaf> directories = new Input.DirectoryScan<>(sink);
            directories.start();
            int prepareNext = 0;
            if (useAhead) {
                for (ForkJoinTask<?> task : ahead.tasks) {
                    prepares[prepareNext++] = task;
                }
            }
            int dedupNext = 0;
            for (int mergeNext = 0; mergeNext < n; mergeNext++) {
                while (prepareNext < n && prepareNext < mergeNext + window) {
                    int index = prepareNext++;
                    RecursiveAction prepare = new RecursiveAction() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        protected void compute() {
                            prepared[index] = Input.prepare(paths.get(index), collectEdges);
                        }
                    };
                    prepare.fork();
                    prepares[index] = prepare;
                }
                while (dedupNext < prepareNext) {
                    int index = dedupNext++;
                    prepares[index].join();
                    prepares[index] = null;
                    Input.Prepared ready = prepared[index];
                    prepared[index] = null;
                    if (ready.entries != null) {
                        dedup.apply(ready.entries);
                    }
                    result.services.addAll(ready.services);
                    if (ready.serviceWarning != null) {
                        result.serviceWarnings.add(ready.serviceWarning);
                    }
                    List<Extract.ScanLeaf> leaves = new ArrayList<>();
                    perPath[index] = leaves;
                    String path = paths.get(index);
                    if (ready.directory) {
                        roots[index] = directories.add(path, leaves);
                        continue;
                    }
                    RecursiveAction scan = new RecursiveAction() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        protected void compute() {
                            Input.forEachClass(path, ready, sink, leaves);
                        }
                    };
                    scan.fork();
                    scans[index] = scan;
                }
                if (scans[mergeNext] != null) {
                    scans[mergeNext].join();
                    scans[mergeNext] = null;
                } else {
                    directories.finish(roots[mergeNext]);
                    roots[mergeNext] = null;
                }
                for (Extract.ScanLeaf leaf : perPath[mergeNext]) {
                    result.merge(leaf);
                }
                perPath[mergeNext] = null;
            }
            // The skipped byte-identical duplicates still count as scanned.
            result.scannedClasses += dedup.skipped;
        });
        return result;
    }
}
