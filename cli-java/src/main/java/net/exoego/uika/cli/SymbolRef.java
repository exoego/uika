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
    static final int META_WORD = 0;
    static final int OWNER_WORD = 1;
    static final int NAME_WORD = 2;
    static final int DESCRIPTOR_WORD = 3;
    static final int WORDS = 4;

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
    /** Snapshot-only evidence; upgrade reports keep their historical metadata unchanged. */
    static final int FINAL_WRITE_OUTSIDE_INITIALIZER = 1 << 7;
    /** A descriptor names a required type without requiring public access to that type. */
    static final int DESCRIPTOR_TYPE = 1 << 8;
    /** A direct invocation selects from the caller's supertype, not the receiver's class. */
    static final int SPECIAL_CALL = 1 << 9;
    /** Constructor handles have stricter protected access than a super() call. */
    static final int CONSTRUCTOR_HANDLE = 1 << 10;
    /** The raw owner is an array descriptor, whose members are not in the class index. */
    static final int ARRAY_OWNER = 1 << 11;
    /** REF_invokeSpecial also constrains the caller relative to the selected declaration. */
    static final int SPECIAL_HANDLE = 1 << 12;

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
