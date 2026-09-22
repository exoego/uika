package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;

/**
 * Pass 1: stream scan units (JAR / class directory) in parallel by chunk and fold them into
 * the hierarchy graph plus reference records. No member tables are kept, so peak memory is
 * the graph plus one chunk of temporaries.
 */
final class Scan {
    /** Aggregated pass-1 result. */
    static final class Result {
        final ClassGraph graph = new ClassGraph();
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
     * @param hierarchy false for a class the graph already held before its chunk (a
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

    static int chunkSize() {
        String env = System.getenv("UIKA_CHUNK");
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
        // 16x the thread count, not 1x: every chunk boundary is a barrier for the next
        // chunk's `known` snapshot, so a 1x chunk parks workers whenever its paths finish
        // unevenly, which real classpaths do constantly.
        return Scratch.threads() * 16;
    }

    static Result scanTargetPaths(List<String> paths, ApiIndex oldIndex, MemberProbe probe, boolean collectEdges) {
        Result result = new Result();
        NameSet oldNames = oldIndex.classNameSet();
        Input.onPool(() -> {
            Dedup dedup = new Dedup();
            int chunkSize = chunkSize();
            int n = paths.size();
            Input.Prepared[] prepared = new Input.Prepared[n];
            // Each round scans one chunk and, in the same parallel region, reads the central
            // directories of the next, so dedup costs no barrier of its own.
            for (int base = -chunkSize; base < n; base += chunkSize) {
                int scanEnd = Math.min(n, base + chunkSize);
                int nextEnd = Math.min(n, scanEnd + chunkSize);
                // The graph is immutable while a chunk parses, so workers read it lock-free.
                Extract.ScanSink sink = new Extract.ScanSink(oldNames, result.graph, collectEdges, probe);
                List<List<Extract.ScanLeaf>> perPath = new ArrayList<>();
                List<RecursiveAction> tasks = new ArrayList<>();
                // Directories share a few lanes instead of getting a task each.
                Input.DirectoryScan<Extract.ScanLeaf> directories = new Input.DirectoryScan<>(sink);
                for (int i = Math.max(base, 0); i < scanEnd; i++) {
                    List<Extract.ScanLeaf> leaves = new ArrayList<>();
                    perPath.add(leaves);
                    String path = paths.get(i);
                    Input.Prepared ready = prepared[i];
                    prepared[i] = null;
                    if (ready.directory) {
                        directories.add(path, leaves);
                        continue;
                    }
                    tasks.add(new RecursiveAction() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        protected void compute() {
                            Input.forEachClass(path, ready, sink, leaves);
                        }
                    });
                }
                for (int i = Math.max(scanEnd, 0); i < nextEnd; i++) {
                    int index = i;
                    tasks.add(new RecursiveAction() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        protected void compute() {
                            prepared[index] = Input.prepare(paths.get(index), collectEdges);
                        }
                    });
                }
                if (!directories.isEmpty()) {
                    tasks.add(directories.laneTask());
                }
                ForkJoinTask.invokeAll(tasks);
                directories.finish();
                // Merged in path order, so duplicate-class winners are deterministic.
                for (List<Extract.ScanLeaf> leaves : perPath) {
                    for (Extract.ScanLeaf leaf : leaves) {
                        result.merge(leaf);
                    }
                }
                for (int i = Math.max(scanEnd, 0); i < nextEnd; i++) {
                    if (prepared[i].entries != null) {
                        dedup.apply(prepared[i].entries);
                    }
                    result.services.addAll(prepared[i].services);
                    if (prepared[i].serviceWarning != null) {
                        result.serviceWarnings.add(prepared[i].serviceWarning);
                    }
                }
            }
            // The skipped byte-identical duplicates still count as scanned.
            result.scannedClasses += dedup.skipped;
        });
        return result;
    }
}
