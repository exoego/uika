package net.exoego.uika.cli;

/**
 * API surface of one class. Bytecode visibility is kept as-is (Kotlin internal is ACC_PUBLIC);
 * private members are registered too and filtered at report time.
 */
final class ClassApi {
    int name;
    int access;
    /** {@link Intern#NONE} only for java/lang/Object. */
    int superName = Intern.NONE;
    int[] interfaces = EMPTY;
    /** Sorted by packed key and deduplicated; {@link #methodAccess} runs parallel. */
    long[] methodKeys = NO_KEYS;
    char[] methodAccess = NO_ACCESS;
    long[] fieldKeys = NO_KEYS;
    char[] fieldAccess = NO_ACCESS;
    /** NestHost target, {@link Intern#NONE} when absent. */
    int nestHost = Intern.NONE;
    /** PermittedSubclasses targets; null when unsealed, empty for a class permitting nothing. */
    int[] permitted;
    /** Sealing could not be read, so neither side may claim this class is unsealed. */
    boolean sealingUnknown;

    static final int[] EMPTY = new int[0];
    static final long[] NO_KEYS = new long[0];
    static final char[] NO_ACCESS = new char[0];

    boolean hasMethod(long key) {
        return java.util.Arrays.binarySearch(methodKeys, key) >= 0;
    }

    boolean hasField(long key) {
        return java.util.Arrays.binarySearch(fieldKeys, key) >= 0;
    }

    /**
     * Sorts members by packed key and drops repeats, keeping the first declared. The order is
     * by symbol id, which is fine: it only serves binary search and never reaches output.
     */
    static void sortMembers(long[] keys, char[] access, int count, ClassApi into, boolean methods) {
        long[] sorted = java.util.Arrays.copyOf(keys, count);
        java.util.Arrays.sort(sorted);
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (n == 0 || sorted[n - 1] != sorted[i]) {
                sorted[n++] = sorted[i];
            }
        }
        long[] outKeys = n == count ? sorted : java.util.Arrays.copyOf(sorted, n);
        char[] outAccess = new char[n];
        boolean[] filled = new boolean[n];
        for (int i = 0; i < count; i++) {
            int at = java.util.Arrays.binarySearch(outKeys, keys[i]);
            if (!filled[at]) {
                filled[at] = true;
                outAccess[at] = access[i];
            }
        }
        if (methods) {
            into.methodKeys = outKeys;
            into.methodAccess = outAccess;
        } else {
            into.fieldKeys = outKeys;
            into.fieldAccess = outAccess;
        }
    }
}
