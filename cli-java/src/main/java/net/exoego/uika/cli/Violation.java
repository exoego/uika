package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.List;

final class Violation {
    /** Origin (JAR path or directory). */
    final int source;
    final int sourceClass;
    final SymbolRef reference;
    final Reason reason;
    /** Whether the referencing class is class-load reachable; null when not computed. */
    Boolean reachable;
    /**
     * Only for the invocation-triggered reasons: whether any scanned class holds a method
     * reference that could invoke the affected member. FALSE means latent.
     */
    Boolean invocationFound;
    /** Promote-only runtime load evidence. */
    boolean observedLoading;
    /** The frame that pulled the class in, when the log carried a cause stack. */
    String loadTrigger;
    /** Only populated by upgrade-check. */
    Suggestion suggestion;
    /** Build modules whose runtime classpath exhibits this violation (per-module upgrade-check). */
    List<String> modules = new ArrayList<>();

    Violation(int source, int sourceClass, SymbolRef reference, Reason reason) {
        this.source = source;
        this.sourceClass = sourceClass;
        this.reference = reference;
        this.reason = reason;
    }

    /**
     * Canonical report order, by string value only: source, class, owner, member, reason. A
     * class-level reference sorts before any member of the same owner.
     */
    static int compare(Violation a, Violation b) {
        int c = Intern.compare(a.source, b.source);
        if (c != 0) {
            return c;
        }
        c = Intern.compare(a.sourceClass, b.sourceClass);
        if (c != 0) {
            return c;
        }
        c = Intern.compare(a.reference.owner(), b.reference.owner());
        if (c != 0) {
            return c;
        }
        boolean memberA = a.reference.hasMember();
        boolean memberB = b.reference.hasMember();
        if (memberA != memberB) {
            return memberA ? 1 : -1;
        }
        if (memberA) {
            c = MemberKey.compare(a.reference.member(), b.reference.member());
            if (c != 0) {
                return c;
            }
        }
        return a.reason.text.compareTo(b.reason.text);
    }
}
