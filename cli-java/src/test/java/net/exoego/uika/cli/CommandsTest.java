package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
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

        // A raw JFR recording, read as text, named java.lang.Thread.State and slipped past the guard.
        Path recording = Files.writeString(dir.resolve("rec.jfr"), "FLR\0\n   java.lang.Thread.State: RUNNABLE\n");
        Evidence.LoadEvidence raw = Evidence.load(List.of(recording.toString()));
        UikaException rawRefused =
                assertThrows(UikaException.class, () -> Commands.applyEvidenceAndDraft(new ArrayList<>(), null, raw, draft));
        assertTrue(rawRefused.getMessage().contains("no class loads were observed"), rawRefused.getMessage());

        Files.writeString(log, "[class,load] com.example.Loaded\n");
        Evidence.LoadEvidence loaded = Evidence.load(List.of(log.toString()));
        assertEquals(1, loaded.distinctClasses());
        Commands.applyEvidenceAndDraft(new ArrayList<>(), null, loaded, draft);
    }

    /** An exclude file that cannot be resolved is not the draft, and must not stop the others being compared. */
    @Test
    void anUnresolvableExcludeFileIsNotTheDraft(@TempDir Path dir) throws Exception {
        Path rules = Files.writeString(dir.resolve("keep.toml"), "");
        String gone = dir.resolve("gone.toml").toString();
        assertEquals(rules.toString(), Commands.aliasesExcludeFile(rules.toString(), List.of(gone, rules.toString())));
    }

    /** Evidence promotes without a draft being asked for, and then nothing is written. */
    @Test
    void evidenceAppliesWithoutADraft(@TempDir Path dir) throws Exception {
        Path log = Files.writeString(dir.resolve("loads.log"), "[class,load] app.Use\n");
        Violation v = violation(false, null);
        String err = stderrOf(() -> Commands.applyEvidenceAndDraft(List.of(v), true, Evidence.load(List.of(log.toString())), null));
        assertTrue(v.observedLoading);
        assertEquals("", err);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(log), files.toList());
        }
    }

    /** Runs {@code body} and returns what it printed to stderr. */
    static String stderrOf(Runnable body) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = Out.err;
        Out.err = new PrintStream(err, true, StandardCharsets.UTF_8);
        try {
            body.run();
        } finally {
            Out.err = previous;
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    /** A verdicts stream whose first record already failed, as on a full disk. */
    private static Verdicts.Writer truncatedStream() {
        Verdicts.Writer writer = Verdicts.Writer.to(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("No space left on device");
            }
        });
        writer.record(Intern.intern("a.jar"), Intern.intern("app/Use"), SymbolRef.ofClass(Intern.intern("lib/C")), "ok", null);
        return writer;
    }

    private static final String TRUNCATED = "verdicts output failed, stream truncated: No space left on device";

    /** A truncated stream would let an answer-check pass on a prefix, so a finished check fails over it. */
    @Test
    void aTruncatedVerdictsStreamFailsACheckThatFinished() {
        UikaException e = assertThrows(UikaException.class, () -> Commands.finishVerdicts(truncatedStream(), () -> "report"));
        assertEquals(TRUNCATED, e.getMessage());
    }

    /** The check's own failure stays the error, and the stream failure is still said. */
    @Test
    void aFailedCheckKeepsItsErrorOverTheStreamFailure() {
        UikaException[] thrown = new UikaException[1];
        String err = stderrOf(() -> thrown[0] = assertThrows(
                UikaException.class,
                () -> Commands.finishVerdicts(truncatedStream(), () -> {
                    throw new UikaException("cannot open app.jar");
                })));
        assertEquals("cannot open app.jar", thrown[0].getMessage());
        assertEquals("warning: " + TRUNCATED + "\n", err);

        // Without a stream, or with a healthy one, the failure passes through alone.
        err = stderrOf(() -> assertThrows(UikaException.class, () -> Commands.finishVerdicts(null, () -> {
            throw new UikaException("cannot open app.jar");
        })));
        assertEquals("", err);
        Verdicts.Writer healthy = Verdicts.Writer.to(new ByteArrayOutputStream());
        err = stderrOf(() -> assertThrows(UikaException.class, () -> Commands.finishVerdicts(healthy, () -> {
            throw new UikaException("cannot open app.jar");
        })));
        assertEquals("", err);
    }

    @AfterEach
    void clearEnvironment() {
        Env.clearOverrides();
    }

    private static final String OLD = GoldenTest.fixture("guava-22.0.jar");
    private static final String NEW = GoldenTest.fixture("guava-23.0-rc1.jar");
    private static final String CONSUMER = GoldenTest.fixture("selenium-remote-driver-3.4.0.jar");

    /**
     * A hand-assembled classpath may name a jar twice, still hold the old version, or name a
     * jar that is gone. None of that may change the verdicts, and the header counts only what
     * was scanned.
     */
    @Test
    void theScanTargetsAreWhatIsThereOnceWithoutTheOldVersion(@TempDir Path dir) throws Exception {
        String missing = dir.resolve("missing.jar").toString();
        UpgradeCheckIntegrationTest.Run text = UpgradeCheckIntegrationTest.runUika(
                "check", "--old", OLD, "--new", NEW, "--classpath", CONSUMER + ":" + missing + ":" + OLD + ":" + CONSUMER);
        assertEquals(1, text.code(), text.stderr());
        assertEquals("warning: scan target not found, skipping: " + missing + "\n", text.stderr());
        assertTrue(text.stdout().startsWith("checked guava-22.0.jar -> guava-23.0-rc1.jar against 1 scan target\n\n"), text.stdout());

        UpgradeCheckIntegrationTest.Run json = UpgradeCheckIntegrationTest.runUika(
                "check", "--json", "--old", OLD, "--new", NEW, "--classpath", CONSUMER + ":" + missing + ":" + OLD + ":" + CONSUMER);
        assertEquals(GoldenTest.scenarioJson("guava-selenium") + "\n", json.stdout());
    }

    /** A library path that is gone excludes and marks nothing. The index build names it. */
    @Test
    void aLibraryPathThatIsGoneMatchesNoScanTarget(@TempDir Path dir) {
        String missing = dir.resolve("missing.jar").toString();
        Commands.ScanTargets scan = Commands.scanTargets(List.of(missing), List.of(missing), List.of(CONSUMER));
        assertEquals(List.of(CONSUMER), scan.paths());
        assertTrue(scan.upgradedSources().isEmpty());
    }

    /** One release on both sides reads jmods twice, so neither side has internals the other lacks. */
    @Test
    void theRunningReleaseOnBothSidesIsNotWarnedAbout(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21\"\n", StandardCharsets.UTF_8);
        Files.createDirectories(home.resolve("jmods"));
        Env.override("UIKA_JDK", home.toString());
        ApiIndex[][] pair = new ApiIndex[1][];
        assertEquals("", stderrOf(() -> pair[0] = Commands.jdkReleasePair(new int[] {21, 21})));
        assertEquals(0, pair[0][0].classCount());
        assertEquals(0, pair[0][1].classCount());
    }

    /**
     * The JDK layer can only conclude references that escaped into the JDK. It never invents a
     * violation, since the same index sits under both sides.
     */
    @Test
    void theJdkLayerConcludesEscapesWithoutChangingTheViolations() throws Exception {
        Path home = Path.of(System.getProperty("java.home"));
        assumeTrue(Files.isRegularFile(home.resolve("lib/ct.sym")) && Runtime.version().feature() >= 18, "needs a ct.sym that serves 17");
        Env.override("UIKA_JDK", home.toString());

        UpgradeCheckIntegrationTest.Run run = UpgradeCheckIntegrationTest.runUika(
                "check", "--json", "--jdk-release", "17", "--old", OLD, "--new", NEW, "--classpath", CONSUMER);
        assertEquals(1, run.code(), run.stderr());
        Map<?, ?> layered = (Map<?, ?>) Json.parse(run.stdout());
        Map<?, ?> plain = (Map<?, ?>) Json.parse(GoldenTest.scenarioJson("guava-selenium"));
        assertEquals(plain.get("violations"), layered.get("violations"));
        assertTrue((Long) plain.get("unknown_refs") > 0, plain.toString());
        assertEquals(0L, layered.get("unknown_refs"));
    }

    private static Dump.Artifact artifact(String group, String name, String version, String file) {
        return new Dump.Artifact(group, name, version, file, null);
    }

    private static Dump.Universe dump(Dump.Module... modules) {
        Dump.Universe u = modular(modules);
        for (Dump.Module m : modules) {
            for (Dump.Artifact a : m.artifacts) {
                if (a.hasCoordinate()) {
                    u.versions.add(a.group(), a.name(), a.version(), a.file());
                }
            }
        }
        return u;
    }

    /** The build-tool plugins keep names unique, so a repeated one is a broken dump, checked once and said so. */
    @Test
    void aRepeatedModuleNameIsCheckedOnce() {
        Dump.Universe before = dump(new Dump.Module(":app", List.of(), List.of(artifact("g", "guava", "22.0", OLD)), 11));
        Dump.Universe after = dump(
                new Dump.Module(":app", List.of(), List.of(artifact("g", "guava", "23.0", NEW)), 17),
                new Dump.Module(":app", List.of(), List.of(artifact("g", "guava", "23.0", NEW)), 21));
        Commands.ModulePlan[] plan = new Commands.ModulePlan[1];
        String err = stderrOf(() -> plan[0] = Commands.planModuleRuns(before, after));
        assertEquals("warning: duplicate module name :app in dump; only the first is checked\n", err);
        assertEquals(1, plan[0].totalModules);
        List<String> names = new ArrayList<>();
        for (Commands.ModuleRunPlan run : plan[0].runs) {
            names.add(String.join(", ", run.names));
        }
        // The JDK move is the first module's too.
        assertEquals(List.of(":app", ":app (JDK 11 -> 17)"), names);
    }
}
