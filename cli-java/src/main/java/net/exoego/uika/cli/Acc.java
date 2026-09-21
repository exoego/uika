package net.exoego.uika.cli;

/** JVM access flags (JVMS 4.1, 4.5, 4.6). */
final class Acc {
    static final int PUBLIC = 0x0001;
    static final int PRIVATE = 0x0002;
    static final int PROTECTED = 0x0004;
    static final int STATIC = 0x0008;
    static final int FINAL = 0x0010;
    /** Compiler-generated bridge method (covariant returns, generic erasure). */
    static final int BRIDGE = 0x0040;
    static final int INTERFACE = 0x0200;
    static final int ABSTRACT = 0x0400;
    /** Compiler-generated member not present in the source. */
    static final int SYNTHETIC = 0x1000;
    static final int ENUM = 0x4000;

    private Acc() {}
}
