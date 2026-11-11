package net.exoego.uika.cli;

/**
 * Raw name+descriptor pairs mapped to their interned member key, for matching un-interned
 * constant-pool references during pass 1. A hit then interns only the owner. Immutable once
 * built, so workers share it without locking.
 */
final class MemberProbe {
    /** A member the selection walk may report. Any call to it is invocation evidence. */
    private static final byte EVIDENCE = 1;
    /** A member the library removed or reshaped. A reference inheriting it must be judged. */
    private static final byte CHANGED = 2;

    private final long[] slots;
    private final byte[] kinds;
    private final int mask;
    private final int evidenceSize;
    private final int changedSize;
    /**
     * One bit per (length, first, middle and last byte) of a probed member NAME. Nearly every
     * constant-pool method name fails it, so the sweep hashes almost nothing.
     */
    private final long[] nameFilter = new long[1 << 10];

    private static int filterBit(int length, byte first, byte middle, byte last) {
        return (((length * 31 + first) * 31 + middle) * 31 + last) & 0xffff;
    }

    /** False means no probed member has this name; true means {@link #slot} must decide. */
    boolean mayContainName(byte[] buf, int off, int len) {
        if (len == 0) {
            return false;
        }
        int bit = filterBit(len, buf[off], buf[off + (len >>> 1)], buf[off + len - 1]);
        return (nameFilter[bit >>> 6] & (1L << bit)) != 0;
    }

    MemberProbe(long[] keys) {
        this(keys, new long[0]);
    }

    MemberProbe(long[] evidence, long[] changed) {
        int total = evidence.length + changed.length;
        slots = new long[Integer.highestOneBit(Math.max(8, total * 2 - 1)) * 2];
        kinds = new byte[slots.length];
        java.util.Arrays.fill(slots, MemberKey.NONE);
        mask = slots.length - 1;
        evidenceSize = insert(evidence, EVIDENCE);
        changedSize = insert(changed, CHANGED);
    }

    /** Returns how many of {@code keys} were new to {@code kind}. */
    private int insert(long[] keys, byte kind) {
        int n = 0;
        for (long key : keys) {
            int slot = combine(Intern.hashOf(MemberKey.name(key)), Intern.hashOf(MemberKey.descriptor(key))) & mask;
            while (slots[slot] != MemberKey.NONE && slots[slot] != key) {
                slot = (slot + 1) & mask;
            }
            if (slots[slot] == MemberKey.NONE) {
                slots[slot] = key;
                byte[] name = Intern.bytes(MemberKey.name(key));
                if (name.length > 0) {
                    int bit = filterBit(name.length, name[0], name[name.length >>> 1], name[name.length - 1]);
                    nameFilter[bit >>> 6] |= 1L << bit;
                }
            }
            if ((kinds[slot] & kind) == 0) {
                kinds[slot] |= kind;
                n++;
            }
        }
        return n;
    }

    /** True when no member is probed for invocation evidence. */
    boolean isEmpty() {
        return evidenceSize == 0;
    }

    boolean hasChanged() {
        return changedSize != 0;
    }

    private static int combine(int nameHash, int descriptorHash) {
        return nameHash * 31 + descriptorHash;
    }

    long keyAt(int slot) {
        return slots[slot];
    }

    boolean isEvidence(int slot) {
        return (kinds[slot] & EVIDENCE) != 0;
    }

    boolean isChanged(int slot) {
        return (kinds[slot] & CHANGED) != 0;
    }

    /** The slot of the member whose name and descriptor equal the given UTF-8 bytes, of either kind, or -1. */
    int slot(byte[] buf, int nameOff, int nameLen, int descriptorOff, int descriptorLen) {
        int nameHash = Intern.hash(buf, nameOff, nameLen);
        int descriptorHash = Intern.hash(buf, descriptorOff, descriptorLen);
        int slot = combine(nameHash, descriptorHash) & mask;
        while (true) {
            long key = slots[slot];
            if (key == MemberKey.NONE) {
                return -1;
            }
            int name = MemberKey.name(key);
            int descriptor = MemberKey.descriptor(key);
            if (Intern.hashOf(name) == nameHash
                    && Intern.hashOf(descriptor) == descriptorHash
                    && Intern.equalsBytes(name, buf, nameOff, nameLen)
                    && Intern.equalsBytes(descriptor, buf, descriptorOff, descriptorLen)) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }
}
