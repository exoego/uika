package net.exoego.uika.cli;

/**
 * Report/gating tier of one violation, most severe first. The single boundary shared by the
 * report's section split and {@code --fail-on reachable}, so the gate matches what is shown.
 */
enum Tier {
    /** Likely to break. */
    BREAKS,
    /** The class is reachable but no scanned bytecode invokes the affected member. */
    LATENT,
    /** No static class-load path from the application. */
    UNPROVEN;

    /** Proven-reachable and unproven both count; only proven-not-reachable does not. */
    static boolean countsAsReachable(Boolean reachable) {
        return !Boolean.FALSE.equals(reachable);
    }

    static boolean isLatent(Violation v) {
        return Boolean.FALSE.equals(v.invocationFound);
    }

    /**
     * The one policy site mapping evidence to a tier. Proven-unreachable wins over latent, an
     * unreachable class cannot even load. An observed load defeats only the unproven arm: it
     * says nothing about invocation.
     *
     * @param reachableAxisValid false when app roots were supplied but matched nothing, so
     *     every {@code reachable = false} has nothing behind it
     */
    static Tier of(Violation v, boolean reachableAxisValid) {
        if (reachableAxisValid && !countsAsReachable(v.reachable) && !v.observedLoading) {
            return UNPROVEN;
        } else if (isLatent(v)) {
            return LATENT;
        }
        return BREAKS;
    }

    static boolean reachableAxisValid(Boolean appRootsMatched) {
        return !Boolean.FALSE.equals(appRootsMatched);
    }
}
