package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandsTest {
    private static Dump.Universe universe(Integer jdkRelease) {
        Dump.Universe u = new Dump.Universe();
        u.jdkRelease = jdkRelease;
        return u;
    }

    /**
     * A dump written before the plugins recorded the release reads null, and one missing side
     * must never look like a JDK move: an old before-dump would then check every upgrade
     * against a JDK pair the user never changed.
     */
    @Test
    void aJdkPairNeedsBothDumpsToNameADifferentRelease() {
        assertArrayEquals(new int[] {17, 21}, Commands.jdkChange(universe(17), universe(21)));
        assertNull(Commands.jdkChange(universe(17), universe(17)));
        assertNull(Commands.jdkChange(universe(null), universe(21)));
        assertNull(Commands.jdkChange(universe(17), universe(null)));
        assertNull(Commands.jdkChange(universe(null), universe(null)));
    }

    private static Dump.Module module(String name, Integer jdkRelease) {
        return new Dump.Module(name, List.of("/build" + name + "/classes"), List.of(), jdkRelease);
    }

    private static Dump.Universe modular(Dump.Module... modules) {
        Dump.Universe u = universe(null);
        u.modules.addAll(Arrays.asList(modules));
        return u;
    }

    /** Every planned JDK run as "label|old->new|targets". */
    private static List<String> jdkRuns(Dump.Universe before, Dump.Universe after) {
        List<String> out = new ArrayList<>();
        for (Commands.ModuleRunPlan run : Commands.planModuleRuns(before, after).runs) {
            if (run.jdkPair != null) {
                out.add(String.join(", ", run.names) + "|" + run.jdkPair[0] + "->" + run.jdkPair[1] + "|" + String.join(",", run.targets));
            }
        }
        return out;
    }

    /** A module that stayed on its release must not be scanned against a move its sibling made. */
    @Test
    void aJdkMoveIsScopedToTheModulesThatMadeIt() {
        Dump.Universe before = modular(module(":app", 11), module(":legacy", 8));
        Dump.Universe after = modular(module(":app", 17), module(":legacy", 8));
        assertEquals(List.of(":app (JDK 11 -> 17)|11->17|/build:app/classes"), jdkRuns(before, after));
    }

    /** One run per module, not one per move, so each module gets its own numbers. */
    @Test
    void eachMovedModuleGetsItsOwnRun() {
        Dump.Universe before = modular(module(":app", 11), module(":web", 11));
        Dump.Universe after = modular(module(":app", 17), module(":web", 17));
        assertEquals(
                List.of(":app (JDK 11 -> 17)|11->17|/build:app/classes", ":web (JDK 11 -> 17)|11->17|/build:web/classes"),
                jdkRuns(before, after));
    }

    /**
     * The run name carries the pair as well as the module, because a bare module name is the
     * key that module's DEPENDENCY run is attributed by.
     */
    @Test
    void aJdkRunNeverSharesANameWithItsModulesDependencyRun() {
        Dump.Universe before = modular(module(":app", 11));
        Dump.Universe after = modular(module(":app", 17));
        List<String> names = new ArrayList<>();
        for (Commands.ModuleRunPlan run : Commands.planModuleRuns(before, after).runs) {
            names.addAll(run.names);
        }
        assertFalse(names.contains(":app"), names.toString());
    }

    /** Different moves are different comparisons and cannot share an index. */
    @Test
    void distinctMovesKeepTheirOwnPairs() {
        Dump.Universe before = modular(module(":app", 11), module(":web", 17));
        Dump.Universe after = modular(module(":app", 17), module(":web", 21));
        List<String> pairs = new ArrayList<>();
        for (String run : jdkRuns(before, after)) {
            pairs.add(run.split("\\|")[1]);
        }
        assertEquals(List.of("11->17", "17->21"), pairs);
    }

    /** The both-sides rule, per module. */
    @Test
    void aModuleMissingAReleaseOnEitherSidePlansNoJdkRun() {
        Dump.Universe before = modular(module(":app", 11), module(":gone", 11));
        Dump.Universe after = modular(module(":new", 17), module(":app", null));
        assertEquals(List.of(), jdkRuns(before, after));
    }

    private static Violation violation(Boolean reachable, Boolean invocationFound) {
        // The invocation axis only exists on `method became abstract` violations.
        Violation v = new Violation(
                Intern.intern("consumer.jar"),
                Intern.intern("app/Use"),
                SymbolRef.ofClass(Intern.intern("lib/C")),
                invocationFound != null ? Reason.METHOD_BECAME_ABSTRACT : Reason.CLASS_REMOVED);
        v.reachable = reachable;
        v.invocationFound = invocationFound;
        return v;
    }

    /** {@code matched}: null = reachability off, else whether any app root matched a scanned class. */
    private static boolean fail(List<Boolean> reachables, Boolean matched, Cli.FailOn failOn) {
        List<Violation> violations = new ArrayList<>();
        for (Boolean r : reachables) {
            violations.add(violation(r, null));
        }
        return Commands.shouldFail(violations, matched, failOn);
    }

    @Test
    void neverAlwaysPasses() {
        assertFalse(fail(Arrays.asList(true, false, null), null, Cli.FailOn.NEVER));
        assertFalse(fail(List.of(false), false, Cli.FailOn.NEVER));
        assertFalse(fail(List.of(), null, Cli.FailOn.NEVER));
    }

    @Test
    void anyFailsOnAnyViolation() {
        assertFalse(fail(List.of(), null, Cli.FailOn.ANY));
        assertTrue(fail(List.of(false), true, Cli.FailOn.ANY));
        assertTrue(fail(Arrays.asList((Boolean) null), null, Cli.FailOn.ANY));
        assertTrue(fail(List.of(true), true, Cli.FailOn.ANY));
    }

    @Test
    void reachableFailsOnlyOnReachableOrUnknown() {
        assertFalse(fail(List.of(), true, Cli.FailOn.REACHABLE));
        // Proven not reachable does not fail (app roots matched).
        assertFalse(fail(List.of(false, false), true, Cli.FailOn.REACHABLE));
        assertTrue(fail(List.of(false, true), true, Cli.FailOn.REACHABLE));
        // Reachability not computed (no app roots) degrades to any.
        assertTrue(fail(Arrays.asList((Boolean) null), null, Cli.FailOn.REACHABLE));
    }

    @Test
    void reachableFailsWhenAppRootsSuppliedButUnmatched() {
        // Every violation is reachable=false, but the labels have no basis.
        assertTrue(fail(List.of(false, false), false, Cli.FailOn.REACHABLE));
        assertFalse(fail(List.of(), false, Cli.FailOn.REACHABLE));
    }

    @Test
    void reachableDoesNotFailOnLatentViolations() {
        Violation latent = violation(true, false);
        assertFalse(Commands.shouldFail(List.of(latent), true, Cli.FailOn.REACHABLE));
        Violation invoked = violation(true, true);
        assertTrue(Commands.shouldFail(List.of(invoked), true, Cli.FailOn.REACHABLE));
        // `any` stays the strict escape hatch.
        assertTrue(Commands.shouldFail(List.of(latent), true, Cli.FailOn.ANY));
    }

    @Test
    void latentGatingHoldsWithoutReachabilityBasis() {
        // Scan-derived, so it does not degrade with the reachable axis.
        Violation latent = violation(null, false);
        assertFalse(Commands.shouldFail(List.of(latent), null, Cli.FailOn.REACHABLE));
        Violation latentUnmatched = violation(false, false);
        assertFalse(Commands.shouldFail(List.of(latentUnmatched), false, Cli.FailOn.REACHABLE));
        Violation plain = violation(false, null);
        assertTrue(Commands.shouldFail(List.of(latentUnmatched, plain), false, Cli.FailOn.REACHABLE));
    }

    /** An observed load lifts a proven-unreachable violation into the failing tier, while a latent one stays latent. */
    @Test
    void observedLoadingPromotesOnlyTheReachableAxis() {
        Violation observed = violation(false, null);
        observed.observedLoading = true;
        assertEquals(Tier.BREAKS, Tier.of(observed, true));
        assertTrue(Commands.shouldFail(List.of(observed), true, Cli.FailOn.REACHABLE));

        Violation observedLatent = violation(false, false);
        observedLatent.observedLoading = true;
        assertEquals(Tier.LATENT, Tier.of(observedLatent, true));
        assertFalse(Commands.shouldFail(List.of(observedLatent), true, Cli.FailOn.REACHABLE));
    }

    private static Violation observed(Boolean reachable, Boolean invocationFound) {
        Violation v = violation(reachable, invocationFound);
        v.observedLoading = true;
        return v;
    }

    /** A reader must be able to predict the exit code from the report, in every degraded state. */
    @Test
    void gateThresholdMatchesTheReportedTier() {
        Object[][] cases = {
            {violation(true, null), true},
            {violation(false, null), true},
            {violation(true, false), true},
            {violation(false, false), true},
            // Roots supplied but unmatched: reachable=false with nothing behind it.
            {violation(false, null), false},
            {violation(false, false), false},
            // Reachability off entirely.
            {violation(null, null), null},
            {violation(null, false), null},
            // Runtime load evidence in every degraded state.
            {observed(false, null), true},
            {observed(false, false), true},
            {observed(false, null), false},
            {observed(null, null), null},
        };
        for (Object[] c : cases) {
            Violation v = (Violation) c[0];
            Boolean matched = (Boolean) c[1];
            boolean shownAsBreaks = Tier.of(v, Tier.reachableAxisValid(matched)) == Tier.BREAKS;
            boolean fails = Commands.shouldFail(List.of(v), matched, Cli.FailOn.REACHABLE);
            assertEquals(
                    shownAsBreaks,
                    fails,
                    "tier and gate disagree for reachable=" + v.reachable + " invocationFound=" + v.invocationFound + " matched=" + matched);
        }
    }

    /** {@code ./x} and {@code x} are the same file, so the comparison canonicalizes. */
    @Test
    void draftPathAliasingAnExcludeFileIsDetected(@TempDir Path dir) throws Exception {
        Path rules = dir.resolve("keep.toml");
        Files.writeString(rules, "");
        Path other = dir.resolve("draft.toml");
        Files.writeString(other, "");

        List<String> excludes = List.of(rules.toString());
        assertEquals(rules.toString(), Commands.aliasesExcludeFile(rules.toString(), excludes));
        assertNotNull(Commands.aliasesExcludeFile(dir + "/./keep.toml", excludes));
        assertNull(Commands.aliasesExcludeFile(other.toString(), excludes));
        // A draft path that does not exist yet cannot be a file the loader just read.
        assertNull(Commands.aliasesExcludeFile(dir.resolve("new.toml").toString(), excludes));
    }

    /** The guard is on the evidence, not on the violation list being empty. */
    @Test
    void draftingFromEvidenceWithNoClassesIsRefused(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("empty.log");
        Files.writeString(log, "nothing that parses as a class load\n");
        Evidence.LoadEvidence evidence = Evidence.load(List.of(log.toString()));
        assertEquals(0, evidence.distinctClasses());

        String draft = dir.resolve("draft.toml").toString();
        UikaException refused =
                assertThrows(UikaException.class, () -> Commands.applyEvidenceAndDraft(new ArrayList<>(), null, evidence, draft));
        assertTrue(refused.getMessage().contains("no class loads were observed"), refused.getMessage());

        Files.writeString(log, "[class,load] com.example.Loaded\n");
        Evidence.LoadEvidence loaded = Evidence.load(List.of(log.toString()));
        assertEquals(1, loaded.distinctClasses());
        Commands.applyEvidenceAndDraft(new ArrayList<>(), null, loaded, draft);
    }
}
