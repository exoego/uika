package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ports the `model.rs` test, plus the policy functions that file holds and other tests lean on. */
class ModelTest {
    /**
     * Note this cannot check that `ALL` lists every variant. In Java `ALL` is `values()`, so
     * that gap is closed by construction.
     */
    @Test
    void reasonStringsAreDistinctAndRoundTrip() {
        for (Reason r : Reason.ALL) {
            assertEquals(r, Reason.parse(r.text), "wire form " + r);
            assertEquals(r, Reason.parse(r.configText()), "config form " + r);
        }
        Set<String> seen = new HashSet<>();
        for (Reason r : Reason.ALL) {
            seen.add(r.text);
        }
        assertEquals(Reason.ALL.length, seen.size(), "duplicate reason strings");
        assertNull(Reason.parse("not a reason"));
    }

    /** The Rust list is hand-kept at 22 entries. A count that drifts means one side gained a reason. */
    @Test
    void reasonSetMatchesTheRustList() {
        assertEquals(22, Reason.ALL.length);
        assertEquals("method_became_abstract", Reason.METHOD_BECAME_ABSTRACT.configText());
        assertEquals("service provider not instantiable", Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE.text);
    }

    /** Every legacy spelling below was a released value. Dropping one fails a build on a uika upgrade. */
    @Test
    void legacyKindSpellingsStillNameTheirSplitReasons() {
        assertEquals(List.of(Reason.CLASS_REMOVED), Reason.parseKinds("class_removed"));
        assertEquals(List.of(Reason.CLASS_REMOVED), Reason.parseKinds("class removed"));
        assertEquals(List.of(Reason.CLASS_BECAME_INTERFACE, Reason.INTERFACE_BECAME_CLASS), Reason.parseKinds("class_kind_changed"));
        assertEquals(
                List.of(Reason.METHOD_BECAME_INSTANCE, Reason.FIELD_BECAME_INSTANCE),
                Reason.parseKinds("member changed from static to instance"));
        assertEquals(
                List.of(Reason.METHOD_BECAME_STATIC, Reason.FIELD_BECAME_STATIC),
                Reason.parseKinds("member_changed_from_instance_to_static"));
        assertNull(Reason.parseKinds("not a kind"));
    }

    /** Declaration order IS the lattice, so reordering the constants inverts every narrowing test. */
    @Test
    void visibilityOrderIsTheAccessLattice() {
        assertEquals(List.of(Visibility.PRIVATE, Visibility.PACKAGE_PRIVATE, Visibility.PROTECTED, Visibility.PUBLIC), List.of(Visibility.values()));
        assertEquals(Visibility.PUBLIC, Visibility.of(Acc.PUBLIC | Acc.STATIC));
        assertEquals(Visibility.PROTECTED, Visibility.of(Acc.PROTECTED | Acc.FINAL));
        assertEquals(Visibility.PRIVATE, Visibility.of(Acc.PRIVATE));
        assertEquals(Visibility.PACKAGE_PRIVATE, Visibility.of(Acc.STATIC));
        // Mutually exclusive per JVMS 4.1. The order only matters for a malformed class file.
        assertEquals(Visibility.PUBLIC, Visibility.of(Acc.PUBLIC | Acc.PRIVATE));
        assertEquals("package-private", Visibility.PACKAGE_PRIVATE.text);
        assertTrue(Visibility.of(Acc.PROTECTED).compareTo(Visibility.of(Acc.PUBLIC)) < 0);
    }

    private static Violation violation(Boolean reachable, Boolean invocationFound, boolean observedLoading) {
        Violation v = new Violation(
                Intern.intern("app.jar"), Intern.intern("app/Caller"), SymbolRef.ofClass(Intern.intern("lib/Owner")), Reason.CLASS_REMOVED);
        v.reachable = reachable;
        v.invocationFound = invocationFound;
        v.observedLoading = observedLoading;
        return v;
    }

    @Test
    void tierIsTheOnePolicySite() {
        assertEquals(Tier.BREAKS, Tier.of(violation(null, null, false), true));
        assertEquals(Tier.BREAKS, Tier.of(violation(true, true, false), true));
        assertEquals(Tier.UNPROVEN, Tier.of(violation(false, null, false), true));
        assertEquals(Tier.LATENT, Tier.of(violation(true, false, false), true));
        // Proven-unreachable wins over latent, since an unreachable class cannot even load.
        assertEquals(Tier.UNPROVEN, Tier.of(violation(false, false, false), true));
        // Roots matched nothing, so the reachable axis means nothing while the latent axis survives.
        assertEquals(Tier.BREAKS, Tier.of(violation(false, null, false), false));
        assertEquals(Tier.LATENT, Tier.of(violation(false, false, false), false));
        // An observed load defeats only the unproven arm.
        assertEquals(Tier.BREAKS, Tier.of(violation(false, null, true), true));
        assertEquals(Tier.LATENT, Tier.of(violation(false, false, true), true));

        assertTrue(Tier.countsAsReachable(null));
        assertTrue(Tier.countsAsReachable(true));
        assertFalse(Tier.countsAsReachable(false));
        assertTrue(Tier.reachableAxisValid(null));
        assertTrue(Tier.reachableAxisValid(true));
        assertFalse(Tier.reachableAxisValid(false));
    }

    @Test
    void symbolRefsSurviveThePackedForm() {
        int owner = Intern.intern("lib/Owner");
        int name = Intern.intern("call");
        int descriptor = Intern.intern("()V");
        for (RefKind kind : RefKind.values()) {
            for (int expectedStatic : new int[] {SymbolRef.TRI_NONE, SymbolRef.TRI_FALSE, SymbolRef.TRI_TRUE}) {
                for (int fieldWrite : new int[] {SymbolRef.TRI_NONE, SymbolRef.TRI_FALSE, SymbolRef.TRI_TRUE}) {
                    for (boolean instantiated : new boolean[] {false, true}) {
                        int meta = SymbolRef.pack(kind, expectedStatic, fieldWrite, instantiated);
                        SymbolRef r = SymbolRef.unpack(meta, owner, name, descriptor);
                        assertEquals(kind, r.kind());
                        assertEquals(owner, r.owner());
                        assertEquals(MemberKey.of(name, descriptor), r.member());
                        assertEquals(tri(expectedStatic), r.expectedStatic());
                        assertEquals(tri(fieldWrite), r.fieldWrite());
                        assertEquals(instantiated ? Boolean.TRUE : null, r.instantiated());
                    }
                }
            }
        }
        SymbolRef classRef = SymbolRef.unpack(SymbolRef.pack(RefKind.CLASS, 0, 0, false), owner, Intern.NONE, Intern.NONE);
        assertEquals(SymbolRef.ofClass(owner), classRef);
        assertFalse(classRef.hasMember());
        assertEquals("interface_method", RefKind.INTERFACE_METHOD.json);
    }

    private static Boolean tri(int value) {
        return value == SymbolRef.TRI_NONE ? null : value == SymbolRef.TRI_TRUE;
    }

    /** A member key must hold any two symbols, and order by string value, never by id. */
    @Test
    void memberKeysPackTwoSymbolsAndOrderByText() {
        long key = MemberKey.of(Integer.MAX_VALUE, Integer.MAX_VALUE - 1);
        assertEquals(Integer.MAX_VALUE, MemberKey.name(key));
        assertEquals(Integer.MAX_VALUE - 1, MemberKey.descriptor(key));
        assertTrue(key != MemberKey.NONE);

        long later = MemberKey.of("zz-member", "()V");
        long earlier = MemberKey.of("aa-member", "()V");
        assertTrue(MemberKey.compare(earlier, later) < 0);
        assertTrue(MemberKey.compare(MemberKey.of("same", "(I)V"), MemberKey.of("same", "(J)V")) < 0);
        assertEquals(0, MemberKey.compare(later, MemberKey.of("zz-member", "()V")));
    }

    @Test
    void violationsOrderByTextWithClassLevelReferencesFirst() {
        int source = Intern.intern("order.jar");
        int caller = Intern.intern("order/Caller");
        int owner = Intern.intern("order/Owner");
        SymbolRef member = new SymbolRef(RefKind.METHOD, owner, MemberKey.of("call", "()V"), null, null, null);
        Violation classLevel = new Violation(source, caller, SymbolRef.ofClass(owner), Reason.CLASS_REMOVED);
        Violation memberLevel = new Violation(source, caller, member, Reason.METHOD_REMOVED);
        assertTrue(Violation.compare(classLevel, memberLevel) < 0);
        assertTrue(Violation.compare(memberLevel, classLevel) > 0);
        // With the same reference the reason text decides.
        Violation narrowed = new Violation(source, caller, member, Reason.METHOD_ACCESS_NARROWED);
        assertTrue(Violation.compare(narrowed, memberLevel) < 0);
        assertEquals(0, Violation.compare(memberLevel, new Violation(source, caller, member, Reason.METHOD_REMOVED)));
        Violation otherSource = new Violation(Intern.intern("a-first.jar"), caller, member, Reason.METHOD_REMOVED);
        assertTrue(Violation.compare(otherSource, classLevel) < 0);
    }

    /** Merged per-module runs can report one caller and owner under two reasons. */
    @Test
    void classLevelViolationsOnOneOwnerOrderByReasonText() {
        int source = Intern.intern("order.jar");
        int caller = Intern.intern("order/Caller");
        SymbolRef owner = SymbolRef.ofClass(Intern.intern("order/Owner"));
        Violation removed = new Violation(source, caller, owner, Reason.CLASS_REMOVED);
        Violation narrowed = new Violation(source, caller, owner, Reason.CLASS_ACCESS_NARROWED);
        assertTrue(Violation.compare(narrowed, removed) < 0);
        assertTrue(Violation.compare(removed, narrowed) > 0);
    }

    /** Java strings order by UTF-16 unit. Rust orders by UTF-8 byte, which is code point order. */
    @Test
    void textOrderIsCodePointOrder() {
        String supplementary = new String(Character.toChars(0x1F600));
        assertTrue(Text.compareUtf8("\ufffd", supplementary) < 0);
        assertTrue("\ufffd".compareTo(supplementary) > 0);
        assertTrue(Text.compareUtf8("a", "ab") < 0);
        assertTrue(Text.compareUtf8("ab", "a") > 0);
        assertEquals(0, Text.compareUtf8("same", "same"));
        assertTrue(Text.compareUtf8("A", "a") < 0);
        assertTrue(Text.compareUtf8("", "a") < 0);
    }
}
