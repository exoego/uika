package net.exoego.uika.cli;

/**
 * Symbol reference extracted from a consumer's constant pool.
 *
 * <p>The scan stores references packed (see {@link #pack}); this object form exists for the
 * few that reach a violation or the verdicts stream.
 *
 * @param member {@link MemberKey#NONE} for a class-level reference
 * @param expectedStatic null when no opcode named the reference
 * @param fieldWrite null for anything but an opcode-backed field reference
 * @param instantiated TRUE for a Class reference targeted by a {@code new} instruction, else null
 */
record SymbolRef(RefKind kind, int owner, long member, Boolean expectedStatic, Boolean fieldWrite, Boolean instantiated) {
    static SymbolRef ofClass(int owner) {
        return new SymbolRef(RefKind.CLASS, owner, MemberKey.NONE, null, null, null);
    }

    boolean hasMember() {
        return member != MemberKey.NONE;
    }

    // Packed meta word: kind in bits 0-1, then two bits each for expectedStatic and
    // fieldWrite (0 none, 1 false, 2 true), then one bit for instantiated.

    static int pack(RefKind kind, int expectedStatic, int fieldWrite, boolean instantiated) {
        return kind.ordinal() | expectedStatic << 2 | fieldWrite << 4 | (instantiated ? 1 << 6 : 0);
    }

    static final int TRI_NONE = 0;
    static final int TRI_FALSE = 1;
    static final int TRI_TRUE = 2;

    static RefKind kindOf(int meta) {
        return RefKind.VALUES[meta & 3];
    }

    static int expectedStaticOf(int meta) {
        return (meta >>> 2) & 3;
    }

    static int fieldWriteOf(int meta) {
        return (meta >>> 4) & 3;
    }

    static boolean instantiatedOf(int meta) {
        return (meta & (1 << 6)) != 0;
    }

    private static Boolean tri(int value) {
        return value == TRI_NONE ? null : value == TRI_TRUE;
    }

    static SymbolRef unpack(int meta, int owner, int name, int descriptor) {
        long member = name < 0 ? MemberKey.NONE : MemberKey.of(name, descriptor);
        return new SymbolRef(
                kindOf(meta),
                owner,
                member,
                tri(expectedStaticOf(meta)),
                tri(fieldWriteOf(meta)),
                instantiatedOf(meta) ? Boolean.TRUE : null);
    }
}
