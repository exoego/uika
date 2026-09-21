package net.exoego.uika.cli;

/** A member's name and descriptor symbols packed into one long: {@code name << 32 | descriptor}. */
final class MemberKey {
    /** "No member": a class-level reference. */
    static final long NONE = -1L;

    private MemberKey() {}

    static long of(int name, int descriptor) {
        return (long) name << 32 | (descriptor & 0xffffffffL);
    }

    static long of(String name, String descriptor) {
        return of(Intern.intern(name), Intern.intern(descriptor));
    }

    static int name(long key) {
        return (int) (key >>> 32);
    }

    static int descriptor(long key) {
        return (int) key;
    }

    /** Orders by name then descriptor string value, the only order output may use. */
    static int compare(long a, long b) {
        int byName = Intern.compare(name(a), name(b));
        return byName != 0 ? byName : Intern.compare(descriptor(a), descriptor(b));
    }
}
