package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcludeTest {
    private static Violation classViolation(String owner) {
        return kindViolation(owner, Reason.CLASS_REMOVED);
    }

    private static Violation kindViolation(String owner, Reason reason) {
        return new Violation(
                Intern.intern("consumer.jar"),
                Intern.intern("app/Use"),
                SymbolRef.ofClass(Intern.intern(owner)),
                reason);
    }

    private static Violation memberViolation(String owner, String name, String descriptor) {
        return memberViolation(owner, name, descriptor, Reason.METHOD_REMOVED);
    }

    // Violation.reason is final here, so the reason a Rust test assigns afterwards is passed in.
    private static Violation memberViolation(String owner, String name, String descriptor, Reason reason) {
        SymbolRef reference =
                new SymbolRef(RefKind.FIELD, Intern.intern(owner), MemberKey.of(name, descriptor), null, null, null);
        return new Violation(Intern.intern("consumer.jar"), Intern.intern("app/Use"), reference, reason);
    }

    private static List<Violation> violations(Violation... all) {
        return new ArrayList<>(List.of(all));
    }

    private static String memberName(Violation v) {
        return Intern.str(MemberKey.name(v.reference.member()));
    }

    private static String parseError(String toml) {
        return assertThrows(UikaException.class, () -> Exclude.parse(toml)).getMessage();
    }

    private static String schemaError(String toml) {
        return assertThrows(Toml.Error.class, () -> Exclude.parse(toml)).getMessage();
    }

    @Test
    void exactOwnerAndMemberSuppressesMatchingViolationOnly() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "org/apache/commons/logging/impl/LogFactoryImpl"
                member = "classesToDiscover"
                reason = "reflectively scanned at init"
                """);
        List<Violation> violations = violations(
                memberViolation(
                        "org/apache/commons/logging/impl/LogFactoryImpl", "classesToDiscover", "[Ljava/lang/String;"),
                memberViolation("org/apache/commons/logging/impl/LogFactoryImpl", "otherField", "I"));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(1, stats.suppressed());
        assertTrue(stats.unused().isEmpty());
        assertEquals(1, violations.size());
        assertEquals("otherField", memberName(violations.get(0)));
    }

    @Test
    void memberRuleMatchesAnyOverloadByNameOnly() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "lib/C"
                member = "m"
                reason = "known reflection use regardless of overload"
                """);
        List<Violation> violations =
                violations(memberViolation("lib/C", "m", "()V"), memberViolation("lib/C", "m", "(I)V"));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(2, stats.suppressed());
        assertTrue(violations.isEmpty());
    }

    @Test
    void descriptorPinsOneOverloadAndKeepsSiblingsReported() {
        // Only the no-arg overload is a known false positive. The (I)V overload is a real
        // break and must survive.
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "lib/C"
                member = "m"
                descriptor = "()V"
                reason = "no-arg overload is invoked reflectively"
                """);
        List<Violation> violations =
                violations(memberViolation("lib/C", "m", "()V"), memberViolation("lib/C", "m", "(I)V"));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(1, stats.suppressed());
        assertTrue(stats.unused().isEmpty());
        assertEquals(1, violations.size());
        assertEquals("(I)V", Intern.str(MemberKey.descriptor(violations.get(0).reference.member())));
    }

    @Test
    void descriptorWithoutMemberIsRejected() {
        String err = parseError("""
                [[exclude]]
                owner = "lib/C"
                descriptor = "()V"
                reason = "bogus"
                """);
        assertTrue(err.contains("descriptor requires a member"), err);
    }

    @Test
    void unusedDescriptorRuleReportsThePinnedOverload() {
        // A descriptor-pinned rule that matches nothing must be reported unused, with the
        // descriptor shown so the operator sees which overload was pinned.
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "lib/C"
                member = "m"
                descriptor = "()V"
                reason = "no-arg overload is invoked reflectively"
                """);
        // Only the (I)V overload breaks. The pinned ()V overload never appears.
        List<Violation> violations = violations(memberViolation("lib/C", "m", "(I)V"));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(0, stats.suppressed());
        assertEquals(1, violations.size());
        assertEquals(1, stats.unused().size());
        String msg = stats.unused().get(0);
        assertTrue(msg.contains("lib/C#m ()V"), msg);
        assertTrue(msg.contains("no-arg overload is invoked reflectively"), msg);
    }

    @Test
    void ownerOnlyRuleSuppressesClassLevelAndMemberViolations() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "lib/Gone"
                reason = "whole class is a known reflection-only shim"
                """);
        List<Violation> violations = violations(classViolation("lib/Gone"), memberViolation("lib/Gone", "m", "()V"));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(2, stats.suppressed());
        assertTrue(violations.isEmpty());
    }

    @Test
    void trailingWildcardMatchesByPrefix() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "org/apache/commons/logging/*"
                reason = "entire package uses reflection-based class discovery"
                """);
        List<Violation> violations = violations(
                classViolation("org/apache/commons/logging/impl/LogFactoryImpl"),
                classViolation("org/apache/commons/logging/LogFactory"),
                classViolation("org/other/Unrelated"));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(2, stats.suppressed());
        assertEquals(1, violations.size());
        assertEquals("org/other/Unrelated", Intern.str(violations.get(0).reference.owner()));
    }

    @Test
    void nonMatchingRuleIsReportedUnused() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "lib/NeverReferenced"
                reason = "just in case"
                """);
        List<Violation> violations = violations(classViolation("lib/Other"));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(0, stats.suppressed());
        assertEquals(1, violations.size());
        assertEquals(1, stats.unused().size());
        assertTrue(stats.unused().get(0).contains("lib/NeverReferenced"));
        assertTrue(stats.unused().get(0).contains("just in case"));
    }

    @Test
    void emptyReasonIsRejected() {
        String err = parseError("""
                [[exclude]]
                owner = "lib/C"
                reason = "   "
                """);
        assertTrue(err.contains("missing a reason"), err);
    }

    @Test
    void wildcardNotAtEndIsRejected() {
        String err = parseError("""
                [[exclude]]
                owner = "lib/*/Inner"
                reason = "bogus"
                """);
        assertTrue(err.contains("trailing wildcard"), err);
    }

    @Test
    void multipleWildcardsAreRejected() {
        String err = parseError("""
                [[exclude]]
                owner = "lib/**"
                reason = "bogus"
                """);
        assertTrue(err.contains("trailing wildcard"), err);
    }

    @Test
    void kindOnlyRuleSuppressesThatKindAcrossAllOwners() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                kind = "method_became_abstract"
                reason = "we accept latent AbstractMethodError risk and track it separately"
                """);
        List<Violation> violations = violations(
                kindViolation("lib/A", Reason.METHOD_BECAME_ABSTRACT),
                kindViolation("other/B", Reason.METHOD_BECAME_ABSTRACT),
                kindViolation("lib/A", Reason.CLASS_REMOVED));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(2, stats.suppressed());
        assertTrue(stats.unused().isEmpty());
        assertEquals(1, violations.size());
        assertEquals(Reason.CLASS_REMOVED, violations.get(0).reason);
    }

    @Test
    void ownerAndKindCombine() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "org/conscrypt/*"
                kind = "method_became_abstract"
                reason = "conscrypt adds abstract methods nothing calls yet"
                """);
        List<Violation> violations = violations(
                kindViolation("org/conscrypt/BufferAllocator", Reason.METHOD_BECAME_ABSTRACT),
                // Same owner, different kind, still reported.
                kindViolation("org/conscrypt/BufferAllocator", Reason.METHOD_REMOVED),
                // Same kind, different owner, still reported.
                kindViolation("other/Lib", Reason.METHOD_BECAME_ABSTRACT));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(1, stats.suppressed());
        assertEquals(2, violations.size());
    }

    @Test
    void memberAndKindNarrowTogether() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "lib/C"
                member = "m"
                kind = "method_became_abstract"
                reason = "only this member's abstractness is known-safe"
                """);
        List<Violation> violations = violations(
                memberViolation("lib/C", "m", "()V", Reason.METHOD_BECAME_ABSTRACT),
                // Same member, different kind.
                memberViolation("lib/C", "m", "()V"));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(1, stats.suppressed());
        assertEquals(1, violations.size());
        assertEquals(Reason.METHOD_REMOVED, violations.get(0).reason);
    }

    @Test
    void unknownKindIsRejectedWithTheValidList() {
        String msg = parseError("""
                [[exclude]]
                kind = "method went weird"
                reason = "typo"
                """);
        assertTrue(msg.contains("unknown kind"), msg);
        assertTrue(msg.contains("method_became_abstract"), msg);
        assertFalse(msg.contains("method became abstract"), msg);
    }

    @Test
    void misspelledKeyIsRejectedInsteadOfWideningTheRule() {
        String err = schemaError("""
                [[exclude]]
                onwer = "org/conscrypt/*"
                kind = "method_became_abstract"
                reason = "conscrypt only"
                """);
        assertEquals("exclude[0]: unknown key \"onwer\", expected one of owner, member, descriptor, kind, reason", err);
        err = schemaError("""
                [[exclude]]
                owner = "org/conscrypt/*"
                kimd = "method became abstract"
                reason = "conscrypt only"
                """);
        assertTrue(err.startsWith("exclude[0]: unknown key \"kimd\""), err);
    }

    @Test
    void ruleWithoutOwnerOrKindIsRejected() {
        String err = parseError("""
                [[exclude]]
                reason = "suppress everything"
                """);
        assertTrue(err.contains("needs an owner, a kind, or both"), err);
    }

    @Test
    void memberWithoutOwnerIsRejected() {
        String err = parseError("""
                [[exclude]]
                member = "m"
                kind = "method_removed"
                reason = "bogus"
                """);
        assertTrue(err.contains("member requires an owner"), err);
    }

    @Test
    void unusedKindRuleNamesTheKind() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                kind = "field_became_final"
                reason = "not expected to occur"
                """);
        List<Violation> violations = violations(kindViolation("lib/A", Reason.CLASS_REMOVED));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(0, stats.suppressed());
        assertEquals(1, stats.unused().size());
        assertTrue(stats.unused().get(0).contains("kind \"field_became_final\""), stats.unused().get(0));
    }

    /** The spaced form is what a report prints under {@code reason}, so pasting one in works. */
    @Test
    void kindAcceptsBothSnakeCaseAndTheSpacedReportForm() {
        for (String spelling : List.of("method_became_abstract", "method became abstract")) {
            List<Exclude.Rule> rules = Exclude.parse("""
                    [[exclude]]
                    kind = "%s"
                    reason = "handled outside the gate"
                    """.formatted(spelling));
            List<Violation> violations = violations(
                    kindViolation("lib/A", Reason.METHOD_BECAME_ABSTRACT), kindViolation("lib/A", Reason.CLASS_REMOVED));
            Exclude.Stats stats = Exclude.filter(violations, rules);
            assertEquals(1, stats.suppressed(), "spelling " + spelling);
            assertEquals(1, violations.size());
        }
        // A half-converted spelling still resolves to the same reason.
        assertEquals(Reason.METHOD_BECAME_ABSTRACT, Reason.parse("method_became abstract"));
    }

    /**
     * A rule file written before a category was split by direction and member kind keeps
     * waiving what it used to, so an upgrade of uika does not fail the build on a
     * {@code kind} that no longer exists.
     */
    @Test
    void aKindThatHasSinceBeenSplitStillMatchesItsParts() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                kind = "member_changed_from_static_to_instance"
                reason = "the call sites are all reflective"

                [[exclude]]
                kind = "class_kind_changed"
                reason = "the flip is intentional and handled"
                """);
        List<Violation> violations = violations(
                kindViolation("lib/A", Reason.METHOD_BECAME_INSTANCE),
                kindViolation("lib/B", Reason.FIELD_BECAME_INSTANCE),
                kindViolation("lib/C", Reason.CLASS_BECAME_INTERFACE),
                kindViolation("lib/D", Reason.INTERFACE_BECAME_CLASS),
                // The other direction was a different kind before the split too.
                kindViolation("lib/E", Reason.METHOD_BECAME_STATIC));
        Exclude.Stats stats = Exclude.filter(violations, rules);
        assertEquals(4, stats.suppressed());
        assertEquals(1, violations.size());
        assertEquals(Reason.METHOD_BECAME_STATIC, violations.get(0).reason);
        assertTrue(stats.unused().isEmpty());

        // Unused, it reports the kinds it stands for now rather than the spelling it was
        // written in. Each is quoted on its own, since narrowing the rule means writing one
        // of those values, and a joined list would not load.
        List<Violation> nothingToMatch = violations(kindViolation("lib/A", Reason.CLASS_REMOVED));
        stats = Exclude.filter(nothingToMatch, rules);
        assertEquals(2, stats.unused().size());
        assertTrue(
                stats.unused().stream()
                        .anyMatch(u -> u.contains("kind \"class_became_interface\" or \"interface_became_class\"")),
                stats.unused().toString());
    }

    /** An unused owner rule is echoed as written, wildcard included, so it can be found in the file. */
    @Test
    void unusedRulesEchoTheirOwnerPatternAndMember() {
        List<Exclude.Rule> rules = Exclude.parse("""
                [[exclude]]
                owner = "org/apache/commons/logging/*"
                reason = "reflection-based discovery"

                [[exclude]]
                owner = "lib/C"
                member = "m"
                reason = "every overload is reflective"
                """);
        Exclude.Stats stats = Exclude.filter(violations(classViolation("lib/Other")), rules);
        assertEquals(
                List.of(
                        "org/apache/commons/logging/* (reflection-based discovery)",
                        "lib/C#m (every overload is reflective)"),
                stats.unused());
    }

    @Test
    void anExcludeFileThatCannotBeReadIsNamed(@TempDir Path dir) throws IOException {
        String missing = dir.resolve("missing.toml").toString();
        assertEquals(
                "cannot read exclude file " + missing + ": No such file or directory (os error 2)",
                assertThrows(UikaException.class, () -> Exclude.load(List.of(missing))).getMessage());

        String directory = dir.toString();
        assertEquals(
                "cannot read exclude file " + directory + ": Is a directory (os error 21)",
                assertThrows(UikaException.class, () -> Exclude.load(List.of(directory))).getMessage());

        Path latin1 = dir.resolve("latin1.toml");
        Files.write(latin1, "# café\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(
                "cannot read exclude file " + latin1 + ": stream did not contain valid UTF-8",
                assertThrows(UikaException.class, () -> Exclude.load(List.of(latin1.toString()))).getMessage());
    }

    @Test
    void anInvalidRuleNamesTheFileItCameFrom(@TempDir Path dir) throws IOException {
        Path good = dir.resolve("good.toml");
        Files.writeString(good, "[[exclude]]\nowner = \"lib/A\"\nreason = \"fine\"\n");
        Path bad = dir.resolve("bad.toml");
        Files.writeString(bad, "[[exclude]]\nowner = \"lib/C\"\nreason = \"\"\n");
        assertEquals(
                "invalid exclude file " + bad + ": exclude rule \"lib/C\" is missing a reason"
                        + " (reason must explain why the violation is a known false positive)",
                assertThrows(UikaException.class, () -> Exclude.load(List.of(good.toString(), bad.toString())))
                        .getMessage());
        assertEquals(1, Exclude.load(List.of(good.toString())).size());
    }

    /** Only a syntax error calls the file invalid TOML. A wrong shape is valid TOML. */
    @Test
    void aShapeErrorIsNotCalledATomlParseError(@TempDir Path dir) throws IOException {
        Path shape = dir.resolve("shape.toml");
        Files.writeString(shape, "exclude = [1]\n");
        assertEquals(
                "invalid exclude file " + shape + " at line 1, column 12\n"
                        + "  |\n"
                        + "1 | exclude = [1]\n"
                        + "  |            ^\n"
                        + "exclude[0]: expected a table with owner, member, descriptor, kind or reason,"
                        + " found the integer 1",
                assertThrows(UikaException.class, () -> Exclude.load(List.of(shape.toString())))
                        .getMessage());

        Path syntax = dir.resolve("syntax.toml");
        Files.writeString(syntax, "exclude = [1\n");
        assertEquals(
                "invalid exclude file " + syntax + ": TOML parse error at line 1, column 13\n"
                        + "  |\n"
                        + "1 | exclude = [1\n"
                        + "  |             ^\n"
                        + "unclosed array, expected `]`",
                assertThrows(UikaException.class, () -> Exclude.load(List.of(syntax.toString())))
                        .getMessage());
    }

    @Test
    void emptyRuleListIsANoOp() {
        List<Violation> violations = violations(classViolation("lib/C"));
        Exclude.Stats stats = Exclude.filter(violations, List.of());
        assertEquals(0, stats.suppressed());
        assertTrue(stats.unused().isEmpty());
        assertEquals(1, violations.size());
    }
}
