package net.exoego.uika.cli;

/**
 * Raw name+descriptor pairs mapped to their interned member key, for matching un-interned
 * constant-pool references during pass 1. A hit then interns only the owner. Immutable once
 * built, so workers share it without locking.
 */
final class MemberProbe {
    private final long[] slots;
    private final int mask;
    private final int size;
    /**
     * One bit per (length, first byte, last byte) of a probed member NAME. Nearly every
     * constant-pool method name fails it, so the sweep hashes almost nothing.
     */
    private final long[] nameFilter = new long[1 << 6];

    private static int filterBit(int length, byte first, byte last) {
        return ((length * 31 + first) * 31 + last) & 0xfff;
    }

    /** False means no probed member has this name; true means {@link #find} must decide. */
    boolean mayContainName(byte[] buf, int off, int len) {
        if (len == 0) {
            return false;
        }
        int bit = filterBit(len, buf[off], buf[off + len - 1]);
        return (nameFilter[bit >>> 6] & (1L << bit)) != 0;
    }

    MemberProbe(long[] keys) {
        slots = new long[Integer.highestOneBit(Math.max(8, keys.length * 2 - 1)) * 2];
        java.util.Arrays.fill(slots, MemberKey.NONE);
        mask = slots.length - 1;
        int n = 0;
        for (long key : keys) {
            int slot = combine(Intern.hashOf(MemberKey.name(key)), Intern.hashOf(MemberKey.descriptor(key))) & mask;
            while (slots[slot] != MemberKey.NONE && slots[slot] != key) {
                slot = (slot + 1) & mask;
            }
            if (slots[slot] == MemberKey.NONE) {
                slots[slot] = key;
                n++;
                byte[] name = Intern.bytes(MemberKey.name(key));
                if (name.length > 0) {
                    int bit = filterBit(name.length, name[0], name[name.length - 1]);
                    nameFilter[bit >>> 6] |= 1L << bit;
                }
            }
        }
        size = n;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int size() {
        return size;
    }

    private static int combine(int nameHash, int descriptorHash) {
        return nameHash * 31 + descriptorHash;
    }

    /** The member key whose name and descriptor equal the given UTF-8 bytes, or {@link MemberKey#NONE}. */
    long find(byte[] buf, int nameOff, int nameLen, int descriptorOff, int descriptorLen) {
        int nameHash = Intern.hash(buf, nameOff, nameLen);
        int descriptorHash = Intern.hash(buf, descriptorOff, descriptorLen);
        int slot = combine(nameHash, descriptorHash) & mask;
        while (true) {
            long key = slots[slot];
            if (key == MemberKey.NONE) {
                return MemberKey.NONE;
            }
            int name = MemberKey.name(key);
            int descriptor = MemberKey.descriptor(key);
            if (Intern.hashOf(name) == nameHash
                    && Intern.hashOf(descriptor) == descriptorHash
                    && Intern.equalsBytes(name, buf, nameOff, nameLen)
                    && Intern.equalsBytes(descriptor, buf, descriptorOff, descriptorLen)) {
                return key;
            }
            slot = (slot + 1) & mask;
        }
    }
}
