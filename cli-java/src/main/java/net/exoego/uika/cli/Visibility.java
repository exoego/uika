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

    /** Mutually exclusive per JVMS 4.1. The order only matters for a malformed class file. */
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
