package net.exoego.uika.cli;

/**
 * The one access lattice. Declaration order is load-bearing: {@code of(new).compareTo(of(old)) < 0}
 * IS narrowing, and both the diff listing and check's old-relative gate compare through it.
 */
enum Visibility {
    PRIVATE("private"),
    PACKAGE_PRIVATE("package-private"),
    PROTECTED("protected"),
    PUBLIC("public");

    final String text;

    Visibility(String text) {
        this.text = text;
    }

    /**
     * A class's own access_flags. The JVM ignores ACC_PROTECTED and ACC_PRIVATE there (JVMS 4.1),
     * so a class is public or package-private (JVMS 5.4.4). javac writes a nested class's source
     * level to InnerClasses, which linkage never reads.
     */
    static Visibility ofClass(int access) {
        return (access & Acc.PUBLIC) != 0 ? PUBLIC : PACKAGE_PRIVATE;
    }

    /** A member's access_flags. Mutually exclusive per JVMS 4.1. The order only matters for a malformed class file. */
    static Visibility of(int access) {
        if ((access & Acc.PUBLIC) != 0) {
            return PUBLIC;
        } else if ((access & Acc.PROTECTED) != 0) {
            return PROTECTED;
        } else if ((access & Acc.PRIVATE) != 0) {
            return PRIVATE;
        }
        return PACKAGE_PRIVATE;
    }
}
