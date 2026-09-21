package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * API index with class hierarchy. Member tables of all classes share one concatenated arena;
 * an entry is a row of ranges into it, so there is no per-class object.
 *
 * <p>On the heap on purpose: these hold the compared libraries and the few classes pass 2
 * fetches, which is small next to the scan.
 */
final class ApiIndex {
    private static final int STRIDE = 12;
    private static final int NAME = 0;
    private static final int ACCESS = 1;
    private static final int SUPER = 2;
    private static final int NEST_HOST = 3;
    private static final int IFACE_START = 4;
    private static final int IFACE_LEN = 5;
    /** -1 when unsealed. */
    private static final int PERMIT_START = 6;
    private static final int PERMIT_LEN = 7;
    private static final int METHOD_START = 8;
    private static final int METHOD_LEN = 9;
    private static final int FIELD_START = 10;
    private static final int FIELD_LEN = 11;
    private static final int SEALING_UNKNOWN_BIT = 1 << 16;

    private final SymMap classes = new SymMap();
    private int[] rows = new int[STRIDE * 64];
    private int classCount;
    private long[] memberKeys = new long[256];
    private char[] memberAccess = new char[256];
    private int memberCount;
    private int[] symArena = new int[64];
    private int symCount;

    static ApiIndex build(Iterable<ClassApi> apis) {
        ApiIndex index = new ApiIndex();
        for (ClassApi api : apis) {
            index.insertIfAbsent(api);
        }
        return index;
    }

    /** Duplicate class names are first-wins (JVM classpath order); a loser appends nothing. */
    void insertIfAbsent(ClassApi api) {
        if (classes.containsKey(api.name)) {
            return;
        }
        if ((classCount + 1) * STRIDE > rows.length) {
            rows = Arrays.copyOf(rows, rows.length * 2);
        }
        int row = classCount * STRIDE;
        rows[row + NAME] = api.name;
        rows[row + ACCESS] = api.access | (api.sealingUnknown ? SEALING_UNKNOWN_BIT : 0);
        rows[row + SUPER] = api.superName;
        rows[row + NEST_HOST] = api.nestHost;
        rows[row + IFACE_START] = appendSyms(api.interfaces);
        rows[row + IFACE_LEN] = api.interfaces.length;
        rows[row + PERMIT_START] = api.permitted == null ? -1 : appendSyms(api.permitted);
        rows[row + PERMIT_LEN] = api.permitted == null ? 0 : api.permitted.length;
        rows[row + METHOD_START] = appendMembers(api.methodKeys, api.methodAccess);
        rows[row + METHOD_LEN] = api.methodKeys.length;
        rows[row + FIELD_START] = appendMembers(api.fieldKeys, api.fieldAccess);
        rows[row + FIELD_LEN] = api.fieldKeys.length;
        classes.put(api.name, classCount);
        classCount++;
    }

    private int appendSyms(int[] syms) {
        int start = symCount;
        if (symCount + syms.length > symArena.length) {
            symArena = Arrays.copyOf(symArena, Math.max(symArena.length * 2, symCount + syms.length));
        }
        System.arraycopy(syms, 0, symArena, symCount, syms.length);
        symCount += syms.length;
        return start;
    }

    private int appendMembers(long[] keys, char[] access) {
        int start = memberCount;
        if (memberCount + keys.length > memberKeys.length) {
            int capacity = Math.max(memberKeys.length * 2, memberCount + keys.length);
            memberKeys = Arrays.copyOf(memberKeys, capacity);
            memberAccess = Arrays.copyOf(memberAccess, capacity);
        }
        System.arraycopy(keys, 0, memberKeys, memberCount, keys.length);
        System.arraycopy(access, 0, memberAccess, memberCount, keys.length);
        memberCount += keys.length;
        return start;
    }

    // ---- entries: an entry is an int handle, -1 when the class is absent ----

    int classCount() {
        return classCount;
    }

    /** Entries are numbered 0..classCount in insertion order. */
    int entry(int className) {
        return classes.get(className);
    }

    boolean containsClass(int className) {
        return classes.get(className) != SymMap.ABSENT;
    }

    int nameOf(int entry) {
        return rows[entry * STRIDE + NAME];
    }

    int accessOf(int entry) {
        return rows[entry * STRIDE + ACCESS] & 0xffff;
    }

    boolean sealingUnknown(int entry) {
        return (rows[entry * STRIDE + ACCESS] & SEALING_UNKNOWN_BIT) != 0;
    }

    /** {@link Intern#NONE} when the class has no superclass. */
    int superOf(int entry) {
        return rows[entry * STRIDE + SUPER];
    }

    int nestHostOf(int entry) {
        return rows[entry * STRIDE + NEST_HOST];
    }

    int interfaceCount(int entry) {
        return rows[entry * STRIDE + IFACE_LEN];
    }

    int interfaceAt(int entry, int k) {
        return symArena[rows[entry * STRIDE + IFACE_START] + k];
    }

    /** -1 when the class is not sealed; 0 for a sealed class permitting nothing. */
    int permittedCount(int entry) {
        return rows[entry * STRIDE + PERMIT_START] < 0 ? -1 : rows[entry * STRIDE + PERMIT_LEN];
    }

    int permittedAt(int entry, int k) {
        return symArena[rows[entry * STRIDE + PERMIT_START] + k];
    }

    boolean permits(int entry, int className) {
        int n = permittedCount(entry);
        for (int k = 0; k < n; k++) {
            if (permittedAt(entry, k) == className) {
                return true;
            }
        }
        return false;
    }

    int methodCount(int entry) {
        return rows[entry * STRIDE + METHOD_LEN];
    }

    long methodKeyAt(int entry, int k) {
        return memberKeys[rows[entry * STRIDE + METHOD_START] + k];
    }

    int methodAccessAt(int entry, int k) {
        return memberAccess[rows[entry * STRIDE + METHOD_START] + k];
    }

    int fieldCount(int entry) {
        return rows[entry * STRIDE + FIELD_LEN];
    }

    long fieldKeyAt(int entry, int k) {
        return memberKeys[rows[entry * STRIDE + FIELD_START] + k];
    }

    int fieldAccessAt(int entry, int k) {
        return memberAccess[rows[entry * STRIDE + FIELD_START] + k];
    }

    /** Access flags of a method declared on the entry itself, or -1. */
    int findMethod(int entry, long key) {
        return find(rows[entry * STRIDE + METHOD_START], rows[entry * STRIDE + METHOD_LEN], key);
    }

    int findField(int entry, long key) {
        return find(rows[entry * STRIDE + FIELD_START], rows[entry * STRIDE + FIELD_LEN], key);
    }

    private int find(int start, int length, long key) {
        int at = Arrays.binarySearch(memberKeys, start, start + length, key);
        return at < 0 ? -1 : memberAccess[at];
    }

    /** Total member-table entries (methods + fields). */
    int memberCount() {
        return memberCount;
    }

    // ---- by-name conveniences ----

    /** Class access flags, or -1 when absent. */
    int classAccess(int className) {
        int entry = entry(className);
        return entry < 0 ? -1 : accessOf(entry);
    }

    int directMethodAccess(int className, long key) {
        int entry = entry(className);
        return entry < 0 ? -1 : findMethod(entry, key);
    }

    int directFieldAccess(int className, long key) {
        int entry = entry(className);
        return entry < 0 ? -1 : findField(entry, key);
    }

    /**
     * Class names for pass-1 reference filtering by raw bytes, so the tens of millions of
     * constant-pool owners outside the checked library are rejected without interning.
     */
    NameSet classNameSet() {
        NameSet set = new NameSet(classCount);
        for (int e = 0; e < classCount; e++) {
            set.add(nameOf(e));
        }
        return set;
    }

    /** Resolve against this index alone. Use {@link Scope} across several. */
    Scope.Resolution resolve(int owner, long key, Scope.MemberKind kind) {
        return new Scope(this).resolve(owner, key, kind);
    }

    /**
     * Builds an index from one or more JARs/directories, first-wins in argument order.
     * Per-class parse failures come back as warnings instead of failing the build.
     */
    static ApiIndex fromPaths(List<String> paths, List<String> warnings) {
        ApiIndex index = new ApiIndex();
        for (ClassApi api : Extract.loadApis(paths, warnings)) {
            index.insertIfAbsent(api);
        }
        return index;
    }

    /** All entries' names, in insertion order. */
    List<Integer> classNames() {
        List<Integer> names = new ArrayList<>(classCount);
        for (int e = 0; e < classCount; e++) {
            names.add(nameOf(e));
        }
        return names;
    }
}
