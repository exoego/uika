package net.exoego.uika.cli;

/**
 * Lightweight hierarchy graph of the scanned classpath: class name to superclass, interfaces,
 * nest host and origin, with no member tables. Classes whose members resolution actually
 * needs are re-read precisely in pass 2.
 *
 * <p>Off-heap and columnar. A node is a row of ints, and the name lookup is a table indexed
 * by symbol id rather than a hash map, since ids are dense.
 */
final class ClassGraph {
    /**
     * Nest host of a class pass 1 did not read to the end. NestHost is a class attribute, so
     * it sits after the members; {@link Check} reads it on demand for the rare private-access
     * verdict that needs it.
     */
    static final int NEST_HOST_UNREAD = -2;

    private static final int NAME = 0;
    private static final int SUPER = 1;
    private static final int NEST_HOST = 2;
    private static final int SOURCE = 3;
    private static final int IFACE_START = 4;
    private static final int IFACE_LEN = 5;
    private static final int REFS_START = 6;
    private static final int REFS_LEN = 7;

    /** Indexed by symbol id: node + 1, 0 when the class is not in the graph. */
    private final IntArena nodeOf = new IntArena();
    private final IntArena rows = new IntArena();
    private final IntArena interfaces = new IntArena();
    /** Class-load edges for reachability. Empty unless edge collection is on. */
    private final IntArena refs = new IntArena();
    /** Whether rows carry the two edge columns; without them a row is six ints, not eight. */
    private final boolean edges;
    private final int stride;
    private int nodeCount;

    ClassGraph() {
        this(true);
    }

    /** @param edges whether class-load edges are stored; a graph without them saves two ints per node */
    ClassGraph(boolean edges) {
        this.edges = edges;
        this.stride = edges ? 8 : 6;
    }

    /**
     * First-wins insert from a packed record. Returns true if inserted.
     *
     * @param data holds {@code interfaceCount} interface symbols at {@code interfacesAt} and
     *     {@code refCount} edge symbols at {@code refsAt}
     */
    boolean insertIfAbsent(
            int name, int superName, int nestHost, int source, int[] data, int interfacesAt, int interfaceCount, int refsAt, int refCount) {
        if (contains(name)) {
            return false;
        }
        nodeOf.ensureSize(Math.max(name + 1, Intern.tableLen()));
        int interfaceStart = interfaces.addAll(data, interfacesAt, interfaceCount);
        rows.add(name);
        rows.add(superName);
        rows.add(nestHost);
        rows.add(source);
        rows.add(interfaceStart);
        rows.add(interfaceCount);
        if (edges) {
            int refStart = refs.addAll(data, refsAt, refCount);
            rows.add(refStart);
            rows.add(refCount);
        }
        nodeOf.set(name, ++nodeCount);
        return true;
    }

    /** First-wins insert from plain arrays. Returns true if inserted. */
    boolean insertIfAbsent(int name, int superName, int[] interfaceNames, int[] refNames, int nestHost, int source) {
        int[] data = new int[interfaceNames.length + refNames.length];
        System.arraycopy(interfaceNames, 0, data, 0, interfaceNames.length);
        System.arraycopy(refNames, 0, data, interfaceNames.length, refNames.length);
        return insertIfAbsent(name, superName, nestHost, source, data, 0, interfaceNames.length, interfaceNames.length, refNames.length);
    }

    /**
     * Safe to call from workers while the merge thread inserts: a class the merge has not
     * published yet reads as absent, which is the conservative answer for the scan.
     */
    boolean contains(int name) {
        return nodeOf.getOrZero(name) != 0;
    }

    /** Node handle, or -1. Nodes are numbered 0..size in insertion order. */
    int node(int name) {
        return name < nodeOf.size() ? nodeOf.get(name) - 1 : -1;
    }

    int size() {
        return nodeCount;
    }

    int nameOf(int node) {
        return rows.get(node * stride + NAME);
    }

    /** {@link Intern#NONE} when the class has no superclass. */
    int superOf(int node) {
        return rows.get(node * stride + SUPER);
    }

    int nestHostOf(int node) {
        return rows.get(node * stride + NEST_HOST);
    }

    /** Origin selected by first-wins: where pass 2 re-reads the class. */
    int sourceOf(int node) {
        return rows.get(node * stride + SOURCE);
    }

    int interfaceCount(int node) {
        return rows.get(node * stride + IFACE_LEN);
    }

    int interfaceAt(int node, int k) {
        return interfaces.get(rows.get(node * stride + IFACE_START) + k);
    }

    /** Zero for every node of a graph built without edges. */
    int refCount(int node) {
        return edges ? rows.get(node * stride + REFS_LEN) : 0;
    }

    int refAt(int node, int k) {
        return refs.get(rows.get(node * stride + REFS_START) + k);
    }
}
