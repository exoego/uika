package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportTest {
    private static Violation classViolation(String sourceClass, String owner, Reason reason, Boolean reachable, String advice) {
        Violation v = new Violation(
                Intern.intern("consumer.jar"), Intern.intern(sourceClass), SymbolRef.ofClass(Intern.intern(owner)), reason);
        v.reachable = reachable;
        if (advice != null) {
            v.suggestion = new Suggestion("g:referencer:1", "g:owner", "1", "2", advice);
        }
        return v;
    }

    private static Violation memberViolation(
            String sourceClass, String owner, String name, String descriptor, RefKind kind, Reason reason) {
        SymbolRef reference = new SymbolRef(kind, Intern.intern(owner), MemberKey.of(name, descriptor), null, null, null);
        return new Violation(Intern.intern("consumer.jar"), Intern.intern(sourceClass), reference, reason);
    }

    /** Rust's {@code clone()} followed by an assignment to {@code source_class}. */
    private static Violation withSourceClass(Violation v, String sourceClass) {
        Violation copy = new Violation(v.source, Intern.intern(sourceClass), v.reference, v.reason);
        copy.reachable = v.reachable;
        copy.invocationFound = v.invocationFound;
        copy.observedLoading = v.observedLoading;
        copy.loadTrigger = v.loadTrigger;
        copy.suggestion = v.suggestion;
        copy.modules = new ArrayList<>(v.modules);
        return copy;
    }

    private static Check.Report report(Violation... violations) {
        Check.Report r = new Check.Report();
        r.violations = new ArrayList<>(Arrays.asList(violations));
        r.scannedClasses = 100;
        r.unknownRefs = 0;
        r.suppressed = 0;
        r.reachabilityComputed = true;
        r.appRootsMatched = Boolean.TRUE;
        r.scanTargets = 1;
        return r;
    }

    private static Report.ModuleOutcome outcome(List<String> modules, List<String> jdkModules, int broken) {
        boolean jdk = !jdkModules.isEmpty();
        return new Report.ModuleOutcome(modules, jdk, jdkModules, jdk ? new int[] {11, 17} : null, 10, broken, 0);
    }

    /** Rust's {@code str::matches(..).count()}, which counts non-overlapping occurrences. */
    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    /**
     * A JDK run compares two releases of the JDK while a module row compares two versions of a
     * jar. Listed as one more row, their broken counts read as parts of a total that does not
     * add up, so the JDK runs get their own section, one row per module, with the same three
     * numbers the rows above carry.
     */
    @Test
    void jdkRunsAreReportedApartFromTheModuleRows() {
        Report.ModuleRunSummary summary = new Report.ModuleRunSummary(
                List.of(
                        outcome(List.of(":app"), List.of(), 1),
                        outcome(List.of(":app (JDK 11 -> 17)"), List.of(":app"), 2),
                        outcome(List.of(":web (JDK 11 -> 17)"), List.of(":web"), 0)),
                3,
                1,
                0,
                0);
        String text = Report.upgradeText(List.of(), null, summary);
        assertTrue(text.contains("per-module check: 1 of 3 modules changed their resolved versions"), text);
        // The module table holds only the dependency row. The JDK runs are below it, one per
        // module, so each module's JDK breakage is its own number.
        String table = text.substring(0, text.indexOf("JDK check:"));
        assertTrue(table.contains(":app  scanned 10 classes, ❌ 1 broken"), text);
        assertFalse(table.contains("JDK 11 -> 17"), text);
        assertTrue(text.contains("JDK check: 2 of 3 modules moved to another release"), text);
        assertTrue(text.contains(":app  JDK 11 -> 17  scanned 10 classes, ❌ 2 broken"), text);
        assertTrue(text.contains(":web  JDK 11 -> 17  scanned 10 classes, ✅ 0 broken"), text);
    }

    /**
     * Several references sharing one piece of advice collapse into a single 💡 block, and the
     * block is ordered before a distinct one. A violation without a suggestion falls back to
     * the symbol listing.
     */
    @Test
    void suggestionsGroupByAdvice() {
        Check.Report r = report(
                classViolation("a/Foo", "x/GoneA", Reason.CLASS_REMOVED, true, "ADVICE_A"),
                classViolation("a/Bar", "x/GoneB", Reason.CLASS_REMOVED, true, "ADVICE_A"),
                classViolation("a/Baz", "x/GoneC", Reason.CLASS_REMOVED, true, "ADVICE_B"));
        String out = Report.checkText(r);
        // Shared advice printed once, both references listed under it.
        assertEquals(1, count(out, "💡 suggestion: ADVICE_A"), out);
        assertEquals(1, count(out, "💡 suggestion: ADVICE_B"), out);
        assertTrue(out.contains("    why: g:owner changed 1 -> 2, which breaks g:referencer:1:"), out);
        assertTrue(out.contains("        x.GoneA was removed, but a.Foo still uses it"), out);
        assertTrue(out.contains("        x.GoneB was removed, but a.Bar still uses it"), out);
        // Deterministic order. The ADVICE_A group comes before the ADVICE_B group.
        assertTrue(out.indexOf("ADVICE_A") < out.indexOf("ADVICE_B"), out);
    }

    /**
     * A {@code method became abstract} violation with no invocation in scanned bytecode gets
     * its own section between 💥 and ⚠️, and the summary counts it separately. The class is
     * reachable, but AbstractMethodError throws at invocation, not at class load.
     */
    @Test
    void latentViolationsGetTheirOwnSection() {
        Violation latent =
                memberViolation("app/Adapter", "lib/Base", "allocateHeapBuffer", "(I)V", RefKind.METHOD, Reason.METHOD_BECAME_ABSTRACT);
        latent.reachable = true;
        latent.invocationFound = false;
        Violation invoked =
                memberViolation("app/Other", "lib/Base", "allocateDirectBuffer", "(I)V", RefKind.METHOD, Reason.METHOD_BECAME_ABSTRACT);
        invoked.reachable = true;
        invoked.invocationFound = true;
        Violation unproven = classViolation("app/Dead", "lib/Gone", Reason.CLASS_REMOVED, false, null);
        unproven.reachable = false;

        String out = Report.checkText(report(latent, invoked, unproven));
        int breaksAt = out.indexOf("reachable from the application");
        int latentAt = out.indexOf("💤 latent");
        int unprovenAt = out.indexOf("not proven reachable (no static path");
        assertTrue(breaksAt >= 0 && latentAt >= 0 && unprovenAt >= 0, out);
        assertTrue(breaksAt < latentAt && latentAt < unprovenAt, out);
        // Each violation sits in its own tier.
        String latentBody = out.substring(latentAt, unprovenAt);
        assertTrue(latentBody.contains("app/Adapter".replace('/', '.')) || latentBody.contains("Adapter"), out);
        assertFalse(latentBody.contains("allocateDirectBuffer"), out);
        // The latent block explains that the error waits for a first call.
        assertTrue(latentBody.contains("no invocation found in scanned bytecode"), out);
        assertTrue(out.contains("💥 1 reachable, 💤 1 latent, ⚠️ 1 not proven reachable"), out);
    }

    /**
     * With no latent violations the summary keeps its original two-tier wording, so the common
     * report is untouched by the new tier.
     */
    @Test
    void summaryOmitsLatentSegmentWhenEmpty() {
        String out = Report.checkText(report(classViolation("app/Foo", "lib/Gone", Reason.CLASS_REMOVED, true, null)));
        assertTrue(out.contains("💥 1 reachable, ⚠️ 0 not proven reachable"), out);
        assertFalse(out.contains("latent"), out);
    }

    /**
     * The same advice covering both a reachable and an unproven reference appears once per
     * section, since the report splits into 💥 / ⚠️ before grouping.
     */
    @Test
    void sharedAdviceRepeatsOncePerReachabilitySection() {
        Check.Report r = report(
                classViolation("a/Foo", "x/Gone", Reason.CLASS_REMOVED, true, "ADVICE_A"),
                classViolation("a/Bar", "x/Gone", Reason.CLASS_REMOVED, false, "ADVICE_A"));
        String out = Report.checkText(r);
        assertEquals(2, count(out, "💡 suggestion: ADVICE_A"), out);
        int reachable = out.indexOf("reachable from the application");
        int unproven = out.indexOf("not proven reachable");
        assertTrue(reachable >= 0 && unproven >= 0, out);
        assertTrue(reachable < unproven);
        // Foo (reachable) sits in the 💥 section, Bar (unproven) in the ⚠️ section.
        assertTrue(out.indexOf("a.Foo") >= 0 && out.indexOf("a.Foo") < unproven, out);
        assertTrue(out.indexOf("a.Bar") > unproven, out);
    }

    /**
     * Violations without a suggestion group by the broken symbol, with the runtime error the
     * reason maps to and the referencing classes listed under it.
     */
    @Test
    void unattributedViolationGroupsBySymbol() {
        Check.Report r = report(
                classViolation("a/Foo", "x/Gone", Reason.CLASS_REMOVED, null, null),
                classViolation("a/Bar", "x/Gone", Reason.CLASS_REMOVED, null, null));
        r.reachabilityComputed = false;
        String out = Report.checkText(r);
        // One block for the one removed symbol, both users under it.
        assertEquals(1, count(out, "❌ x.Gone"), out);
        assertTrue(out.contains("    class removed, throws NoClassDefFoundError at first use"), out);
        assertTrue(out.contains("    used by 2 classes:"), out);
        assertTrue(out.contains("        a.Bar  (consumer.jar)"), out);
        assertTrue(out.contains("        a.Foo  (consumer.jar)"), out);
        assertFalse(out.contains("💡"), out);
    }

    /**
     * Method and field references render as Java-ish signatures. Descriptors are decoded,
     * {@code <init>} reads as a constructor, and the reason wording follows.
     */
    @Test
    void memberReferencesRenderAsSignatures() {
        Check.Report r = report(
                memberViolation(
                        "a/Foo",
                        "x/TimeLimiter",
                        "callWithTimeout",
                        "(Ljava/util/concurrent/Callable;JLjava/util/concurrent/TimeUnit;Z)Ljava/lang/Object;",
                        RefKind.METHOD,
                        Reason.METHOD_REMOVED),
                memberViolation(
                        "a/Foo",
                        "x/SimpleTimeLimiter",
                        "<init>",
                        "(Ljava/util/concurrent/ExecutorService;)V",
                        RefKind.METHOD,
                        Reason.METHOD_ACCESS_NARROWED),
                memberViolation("a/Foo", "x/Fields", "COUNTS", "[I", RefKind.FIELD, Reason.FIELD_REMOVED));
        r.reachabilityComputed = false;
        String out = Report.checkText(r);
        assertTrue(out.contains("❌ x.TimeLimiter.callWithTimeout(Callable, long, TimeUnit, boolean)"), out);
        assertTrue(out.contains("    method removed, throws NoSuchMethodError at first call"), out);
        assertTrue(out.contains("❌ x.SimpleTimeLimiter constructor (ExecutorService)"), out);
        assertTrue(out.contains("    constructor access narrowed, throws IllegalAccessError at first `new`"), out);
        assertTrue(out.contains("❌ x.Fields.COUNTS: int[]"), out);
        assertTrue(out.contains("    field removed, throws NoSuchFieldError at first access"), out);
    }

    /**
     * Graph-walk violations stay consumer-first. There is one block per broken scanned class,
     * each entry phrased as what the class does and the error the JVM raises.
     */
    @Test
    void structuralViolationsGroupByConsumerClass() {
        Check.Report r = report(
                memberViolation(
                        "org/koin/logger/SLF4JLogger",
                        "org/koin/core/logger/Logger",
                        "display",
                        "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V",
                        RefKind.METHOD,
                        Reason.METHOD_BECAME_ABSTRACT),
                memberViolation(
                        "org/koin/logger/SLF4JLogger",
                        "org/koin/core/logger/Logger",
                        "log",
                        "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V",
                        RefKind.METHOD,
                        Reason.METHOD_BECAME_FINAL));
        r.reachabilityComputed = false;
        String out = Report.checkText(r);
        // One consumer-class block holding both structural breaks.
        assertEquals(1, count(out, "❌ org.koin.logger.SLF4JLogger  (consumer.jar)"), out);
        assertTrue(
                out.contains(
                        "    inherits abstract org.koin.core.logger.Logger.display(Level, String) without implementing it"),
                out);
        assertTrue(out.contains("        throws AbstractMethodError when display is called"), out);
        assertTrue(out.contains("    overrides org.koin.core.logger.Logger.log(Level, String), which became final"), out);
        assertTrue(
                out.contains(
                        "        throws IncompatibleClassChangeError (VerifyError up to JDK 15) when SLF4JLogger loads"),
                out);
    }

    /**
     * A kind flip on a hierarchy edge names the edge it breaks. The walk only reaches it
     * through that edge, so the reason is enough to say extends or implements.
     */
    @Test
    void kindFlipBlocksNameTheBrokenEdge() {
        Check.Report r = report(
                classViolation("app/Sub", "lib/Base", Reason.CLASS_BECAME_INTERFACE, null, null),
                classViolation("app/Impl", "lib/Iface", Reason.INTERFACE_BECAME_CLASS, null, null));
        r.reachabilityComputed = false;
        String out = Report.checkText(r);
        assertTrue(out.contains("    extends lib.Base, which became an interface"), out);
        assertTrue(out.contains("        throws IncompatibleClassChangeError when Sub loads"), out);
        assertTrue(out.contains("    extends or implements lib.Iface, which became a class"), out);
    }

    /** A clean run collapses to the ✅ summary line, still carrying the unverified count. */
    @Test
    void cleanRunSummaryLeadsWithCheckMark() {
        Check.Report r = report();
        r.unknownRefs = 16;
        String out = Report.checkText(r);
        assertEquals(
                "✅ scanned 100 classes: 0 broken, ❓ 16 unverified references (hierarchy escapes the analyzed scope)\n",
                out);
    }

    /** The plain-check header names the compared pair and the scan target count. */
    @Test
    void checkHeaderNamesPairAndTargets() {
        List<String> oldPaths = List.of("/x/guava-22.0.jar");
        List<String> newPaths = List.of("/y/guava-23.0-rc1.jar");
        assertEquals(
                "checked guava-22.0.jar -> guava-23.0-rc1.jar against 1 scan target\n\n",
                Report.checkHeader(oldPaths, newPaths, 1));
        assertEquals(
                "checked guava-22.0.jar -> guava-23.0-rc1.jar against 3 scan targets\n\n",
                Report.checkHeader(oldPaths, newPaths, 3));
    }

    /**
     * Directory inputs keep their full path in the header (the basename alone, e.g. "main",
     * says nothing), matching the sourceDisplay rule the body uses.
     */
    @Test
    void checkHeaderKeepsDirectoryPathsWhole() {
        List<String> oldPaths = List.of("a/build/classes/java/main");
        List<String> newPaths = List.of("b/build/classes/java/main");
        assertEquals(
                "checked a/build/classes/java/main -> b/build/classes/java/main against 1 scan target\n\n",
                Report.checkHeader(oldPaths, newPaths, 1));
    }

    /**
     * Two distinct removed overloads that pretty-print alike (param packages and return types
     * are erased) must stay two ❌ blocks, distinguished by the raw descriptor, so the block
     * count matches the summary's broken count.
     */
    @Test
    void collidingOverloadsStayDistinctBlocks() {
        Check.Report r = report(
                memberViolation("a/Foo", "x/C", "m", "(Ljava/util/Date;)V", RefKind.METHOD, Reason.METHOD_REMOVED),
                memberViolation("a/Foo", "x/C", "m", "(Ljava/sql/Date;)V", RefKind.METHOD, Reason.METHOD_REMOVED));
        r.reachabilityComputed = false;
        String out = Report.checkText(r);
        assertEquals(2, count(out, "❌ x.C.m "), out);
        assertTrue(out.contains("❌ x.C.m (Ljava/util/Date;)V"), out);
        assertTrue(out.contains("❌ x.C.m (Ljava/sql/Date;)V"), out);
        assertTrue(out.contains("❌ 2 broken"), out);
    }

    /**
     * The same collision inside a 💡 block. The two breaks stay two sentences (raw form), never
     * silently deduplicated into one.
     */
    @Test
    void collidingOverloadsStayDistinctSuggestionLines() {
        Violation a = memberViolation("a/Foo", "x/C", "m", "(Ljava/util/Date;)V", RefKind.METHOD, Reason.METHOD_REMOVED);
        Violation b = memberViolation("a/Foo", "x/C", "m", "(Ljava/sql/Date;)V", RefKind.METHOD, Reason.METHOD_REMOVED);
        Suggestion suggestion = new Suggestion("g:referencer:1", "g:owner", "1", "2", "ADVICE_A");
        a.suggestion = suggestion;
        b.suggestion = suggestion;
        a.reachable = true;
        b.reachable = true;
        String out = Report.checkText(report(a, b));
        assertEquals(1, count(out, "💡 suggestion: ADVICE_A"), out);
        assertTrue(out.contains("x.C.m (Ljava/util/Date;)V was removed, but a.Foo still calls it"), out);
        assertTrue(out.contains("x.C.m (Ljava/sql/Date;)V was removed, but a.Foo still calls it"), out);
    }

    /**
     * Identical advice does not imply identical versions (per-module runs can resolve
     * different lists). The why-line must only ever cover references it is true for.
     */
    @Test
    void sameAdviceWithDifferentVersionsSplitsBlocks() {
        Violation a = classViolation("a/Foo", "x/GoneA", Reason.CLASS_REMOVED, true, "ADVICE_A");
        Violation b = classViolation("a/Bar", "x/GoneB", Reason.CLASS_REMOVED, true, "ADVICE_A");
        Suggestion s = b.suggestion;
        b.suggestion = new Suggestion(s.referencedBy(), s.removedBy(), "1.5", s.after(), s.advice());
        String out = Report.checkText(report(a, b));
        assertEquals(2, count(out, "💡 suggestion: ADVICE_A"), out);
        assertTrue(out.contains("why: g:owner changed 1 -> 2, which breaks g:referencer:1:"), out);
        assertTrue(out.contains("why: g:owner changed 1.5 -> 2, which breaks g:referencer:1:"), out);
        // Same versions collapse back into one block.
        Violation bSame = withSourceClass(a, "a/Baz");
        out = Report.checkText(report(a, bSame));
        assertEquals(1, count(out, "💡 suggestion: ADVICE_A"), out);
    }

    /**
     * A corrupt descriptor with a huge array-dimension run must fall back to the raw form,
     * never recurse per dimension (a 64KB '[' run would overflow the stack).
     */
    @Test
    void absurdArrayDepthDegradesToRawDescriptor() {
        String deep = "(" + "[".repeat(60_000) + "I)V";
        Check.Report r = report(memberViolation("a/Foo", "x/C", "m", deep, RefKind.METHOD, Reason.METHOD_REMOVED));
        r.reachabilityComputed = false;
        String out = Report.checkText(r);
        // Raw fallback. The undecoded descriptor appears after the member name.
        assertTrue(out.contains("❌ x.C.m ("));
        // Legal depths still decode.
        assertEquals(new Report.ParsedType("int[][]", 3), Report.parseType("[[I"));
        assertNull(Report.parseType("[".repeat(300)));
    }

    /**
     * One change of every kind, so the listing below is the whole vocabulary of {@code diff}.
     * EVERY kind has to be here, not just an interesting sample. Three tests pin TAG_WIDTH, the
     * tag-to-{@code kind} mapping and the column against this list alone, so a kind left out is
     * one whose tag could outgrow the column with all three still green.
     * {@code everyBreakingChangeKindIsCovered} fails when one goes missing.
     */
    private static List<BreakingChange> oneOfEveryChange() {
        int cls = Intern.intern("x/C");
        long method = MemberKey.of("m", "()V");
        long field = MemberKey.of("F", "I");
        return List.of(
                BreakingChange.ofClass(BreakingChange.Kind.CLASS_REMOVED, cls),
                BreakingChange.ofClass(BreakingChange.Kind.CLASS_BECAME_SEALED, cls),
                BreakingChange.removed(BreakingChange.Kind.METHOD_REMOVED, cls, method, List.of(Intern.intern("(I)V"))),
                BreakingChange.removed(BreakingChange.Kind.FIELD_REMOVED, cls, field, List.of()),
                BreakingChange.classNarrowed(cls, Visibility.PUBLIC, Visibility.PACKAGE_PRIVATE),
                BreakingChange.ofClass(BreakingChange.Kind.CLASS_BECAME_FINAL, cls),
                BreakingChange.ofClass(BreakingChange.Kind.CLASS_BECAME_ABSTRACT, cls),
                BreakingChange.ofClass(BreakingChange.Kind.INTERFACE_BECAME_CLASS, cls),
                BreakingChange.ofClass(BreakingChange.Kind.CLASS_BECAME_INTERFACE, cls),
                BreakingChange.ofMember(BreakingChange.Kind.METHOD_BECAME_ABSTRACT, cls, method),
                BreakingChange.memberNarrowed(
                        BreakingChange.Kind.METHOD_ACCESS_NARROWED, cls, method, Visibility.PUBLIC, Visibility.PROTECTED),
                BreakingChange.memberNarrowed(
                        BreakingChange.Kind.FIELD_ACCESS_NARROWED, cls, field, Visibility.PROTECTED, Visibility.PRIVATE),
                BreakingChange.ofMember(BreakingChange.Kind.METHOD_BECAME_INSTANCE, cls, method),
                BreakingChange.ofMember(BreakingChange.Kind.METHOD_BECAME_STATIC, cls, method),
                BreakingChange.ofMember(BreakingChange.Kind.FIELD_BECAME_STATIC, cls, field),
                BreakingChange.ofMember(BreakingChange.Kind.FIELD_BECAME_INSTANCE, cls, field),
                BreakingChange.ofMember(BreakingChange.Kind.FIELD_BECAME_FINAL, cls, field),
                BreakingChange.ofMember(BreakingChange.Kind.METHOD_BECAME_FINAL, cls, method));
    }

    /**
     * {@code oneOfEveryChange} really is one of EVERY change. Three tests below read nothing
     * but that helper, so a kind missing from it is one whose tag never meets TAG_WIDTH, with
     * all three still passing. The switch has no default, so a new kind cannot compile without
     * an arm here. The arm then needs a slot, and an unlisted slot fails this assertion.
     */
    @Test
    void everyBreakingChangeKindIsCovered() {
        final int variants = 18;
        List<BreakingChange> changes = oneOfEveryChange();
        boolean[] seen = new boolean[variants];
        for (BreakingChange c : changes) {
            int s =
                    switch (c.kind()) {
                        case CLASS_REMOVED -> 0;
                        case METHOD_REMOVED -> 1;
                        case FIELD_REMOVED -> 2;
                        case CLASS_ACCESS_NARROWED -> 3;
                        case CLASS_BECAME_FINAL -> 4;
                        case CLASS_BECAME_ABSTRACT -> 5;
                        case CLASS_BECAME_INTERFACE -> 6;
                        case INTERFACE_BECAME_CLASS -> 7;
                        case METHOD_BECAME_ABSTRACT -> 8;
                        case METHOD_ACCESS_NARROWED -> 9;
                        case FIELD_ACCESS_NARROWED -> 10;
                        case METHOD_BECAME_STATIC -> 11;
                        case METHOD_BECAME_INSTANCE -> 12;
                        case FIELD_BECAME_STATIC -> 13;
                        case FIELD_BECAME_INSTANCE -> 14;
                        case FIELD_BECAME_FINAL -> 15;
                        case METHOD_BECAME_FINAL -> 16;
                        case CLASS_BECAME_SEALED -> 17;
                    };
            assertTrue(s < variants, "slot " + s + " is past variants; bump it when adding a kind");
            seen[s] = true;
        }
        for (boolean s : seen) {
            assertTrue(s, "oneOfEveryChange is missing a kind (slots covered: " + Arrays.toString(seen) + ")");
        }
        assertEquals(variants, changes.size(), "one change per kind, no duplicates");
    }

    /**
     * Every {@code diff} line starts the name at the same column, continuation lines included.
     * The tags used to be padded by hand and had drifted into three columns at once, so this
     * asserts the property rather than the individual spellings.
     */
    @Test
    void diffTextStartsEveryNameAtOneColumn() {
        String out = Report.diffText(oneOfEveryChange());
        int checked = 0;
        for (String line : out.split("\n", -1)) {
            if (line.isEmpty()) {
                break;
            }
            int nameAt = line.indexOf("x/C");
            if (nameAt < 0) {
                nameAt = line.indexOf('(');
            }
            assertEquals(Report.TAG_WIDTH + 1, nameAt, "misaligned: " + Json.quote(line) + "\n" + out);
            assertEquals(
                    ' ', line.charAt(Report.TAG_WIDTH), "tag " + Json.quote(line) + " fills the separator column\n" + out);
            checked++;
        }
        // 18 changes plus the one replacement hint line.
        assertEquals(19, checked, out);
    }

    /**
     * The parentheticals carry what the short tags leave out, and a newly-final method counts
     * as a method. Under a bucket for "other" it read as miscellaneous, which put every method
     * of a Kotlin library (final by default) in the wrong column.
     */
    @Test
    void diffTextNamesBothSidesOfAChange() {
        String out = Report.diffText(oneOfEveryChange());
        for (String expected : List.of(
                "CLASS REMOVED          x/C",
                "METHOD REMOVED         x/C.m ()V",
                "                       (descriptor changed? now: (I)V)",
                "CLASS ACCESS NARROWED  x/C (public -> package-private)",
                "CLASS BECAME FINAL     x/C",
                "CLASS BECAME ABSTRACT  x/C",
                "INTERFACE BECAME CLASS x/C",
                "CLASS BECAME INTERFACE x/C",
                "METHOD BECAME ABSTRACT x/C.m ()V",
                "METHOD ACCESS NARROWED x/C.m ()V (public -> protected)",
                "FIELD ACCESS NARROWED  x/C.F I (protected -> private)",
                "METHOD BECAME INSTANCE x/C.m ()V",
                "METHOD BECAME STATIC   x/C.m ()V",
                "FIELD BECAME STATIC    x/C.F I",
                "FIELD BECAME INSTANCE  x/C.F I",
                "METHOD BECAME FINAL    x/C.m ()V",
                "breaking changes: 18 (classes: 7, methods: 6, fields: 5)")) {
            assertTrue(out.contains(expected), "missing " + Json.quote(expected) + "\n" + out);
        }
    }

    /**
     * {@code --json} says what the text says. It used to hand out the raw JVM data the text
     * was built from, so a reader had to know that access flag 1 means public.
     */
    @Test
    void diffJsonSpeaksTheSameWordsAsTheText() {
        String out = Report.diffJson(oneOfEveryChange());
        for (String expected : List.of(
                "\"kind\": \"class_access_narrowed\"",
                "\"from\": \"public\"",
                "\"to\": \"package-private\"",
                "\"from\": \"protected\"",
                "\"to\": \"private\"",
                "\"kind\": \"method_became_instance\"",
                "\"kind\": \"interface_became_class\"")) {
            assertTrue(out.contains(expected), "missing " + expected + "\n" + out);
        }
        // No raw flag words or bools left to decode.
        for (String gone : List.of("old_access", "new_access", "old_static", "old_interface")) {
            assertFalse(out.contains(gone), gone + " still in\n" + out);
        }
    }

    /**
     * The text tag is the JSON {@code kind} uppercased, so a jq filter on {@code kind} and the
     * listing pick out the same changes. This checks that the kind's tag is what diffJson
     * actually writes.
     */
    @Test
    @SuppressWarnings("unchecked")
    void kindMatchesTheSerializedTag() throws Json.ParseException {
        for (BreakingChange change : oneOfEveryChange()) {
            Map<String, Object> json = (Map<String, Object>) Json.parse(Report.diffJson(List.of(change)));
            List<Object> changes = (List<Object>) json.get("breaking_changes");
            Map<String, Object> first = (Map<String, Object>) changes.get(0);
            assertEquals(change.kind().tag, first.get("kind"), change.toString());
            // Internally tagged, so the tag is the first key.
            assertEquals("kind", first.keySet().iterator().next(), change.toString());
        }
    }

    /** No kind outgrows the column it has to print in. */
    @Test
    void tagColumnFitsEveryKind() {
        int widest = 0;
        for (BreakingChange c : oneOfEveryChange()) {
            widest = Math.max(widest, Report.tag(c).length());
        }
        assertEquals(Report.TAG_WIDTH, widest, "TAG_WIDTH should be the longest tag");
    }

    /**
     * Runtime load evidence. The promoted violation sits in the 💥 section carrying the ⚡
     * marker with its trigger frame, and the summary counts it. {@code --json} says the same
     * (observed_loading / load_trigger), and both keys are absent without evidence.
     */
    @Test
    void observedLoadingMarksLinesSummaryAndJson() {
        Violation v = classViolation("a/Foo", "x/Gone", Reason.CLASS_REMOVED, false, null);
        v.observedLoading = true;
        v.loadTrigger = "java.lang.Class.forName(Class.java:100)";
        Violation plain = classViolation("a/Dead", "x/Gone2", Reason.CLASS_REMOVED, false, null);
        Check.Report r = report(v, plain);
        String out = Report.checkText(r);
        assertTrue(
                out.contains(
                        "        a.Foo  (consumer.jar)  ⚡ observed loading at runtime (via java.lang.Class.forName(Class.java:100))"),
                out);
        // Promoted out of ⚠️. The observed violation prints before the unproven section, and
        // the tier counts follow.
        int unprovenAt = out.indexOf("not proven reachable (no static path");
        assertTrue(unprovenAt >= 0, out);
        assertTrue(out.indexOf("a.Foo") >= 0 && out.indexOf("a.Foo") < unprovenAt, out);
        assertTrue(out.indexOf("a.Dead") > unprovenAt, out);
        assertTrue(out.contains("💥 1 reachable, ⚠️ 1 not proven reachable"), out);
        assertTrue(out.contains(", ⚡ 1 observed loading at runtime"), out);

        String json = Report.checkJson(r);
        assertTrue(json.contains("\"observed_loading\": true"), json);
        assertTrue(json.contains("\"load_trigger\": \"java.lang.Class.forName(Class.java:100)\""), json);
        String evidenceLess =
                Report.checkJson(report(classViolation("a/Foo", "x/Gone", Reason.CLASS_REMOVED, true, null)));
        assertFalse(evidenceLess.contains("observed_loading"), evidenceLess);
        assertFalse(evidenceLess.contains("load_trigger"), evidenceLess);
    }

    /**
     * The ⚡ marker also lands on structural blocks (on the class heading, because the evidence
     * is a fact about the class, shared by every entry) and on 💡 suggestion sentences.
     */
    @Test
    void observedMarkerReachesStructuralAndSuggestionShapes() {
        Violation structural = memberViolation(
                "org/koin/logger/SLF4JLogger",
                "org/koin/core/logger/Logger",
                "log",
                "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V",
                RefKind.METHOD,
                Reason.METHOD_BECAME_FINAL);
        structural.observedLoading = true;
        Check.Report r = report(structural);
        r.reachabilityComputed = false;
        String out = Report.checkText(r);
        assertTrue(out.contains("❌ org.koin.logger.SLF4JLogger  (consumer.jar)  ⚡ observed loading at runtime"), out);

        Violation suggested = classViolation("a/Foo", "x/Gone", Reason.CLASS_REMOVED, true, "ADVICE_A");
        suggested.observedLoading = true;
        out = Report.checkText(report(suggested));
        assertTrue(
                out.contains("        x.Gone was removed, but a.Foo still uses it  ⚡ observed loading at runtime"), out);
    }

    /** The summary line notes suppressed violations only when the count is nonzero. */
    @Test
    void suppressedNoteAppearsOnlyWhenNonzero() {
        Check.Report r = report(classViolation("a/Foo", "x/Gone", Reason.CLASS_REMOVED, true, null));
        r.suppressed = 3;
        String out = Report.checkText(r);
        assertTrue(out.contains("3 suppressed by --exclude-file"), out);

        r.suppressed = 0;
        out = Report.checkText(r);
        assertFalse(out.contains("suppressed"), out);
    }
}
