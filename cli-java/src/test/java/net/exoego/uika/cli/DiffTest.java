package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ports the `diff.rs` tests. */
class DiffTest {
    private static final String JAVA_LANG_OBJECT = "java/lang/Object";

    /** {name, descriptor, access} triples. */
    private static Object[] m(String name, String descriptor, int access) {
        return new Object[] {name, descriptor, access};
    }

    private static void members(ClassApi api, boolean methods, Object[]... members) {
        long[] keys = new long[members.length];
        char[] access = new char[members.length];
        for (int i = 0; i < members.length; i++) {
            keys[i] = MemberKey.of((String) members[i][0], (String) members[i][1]);
            access[i] = (char) (int) (Integer) members[i][2];
        }
        ClassApi.sortMembers(keys, access, members.length, api, methods);
    }

    private static ClassApi classOf(String name, String superName, Object[]... methods) {
        ClassApi api = new ClassApi();
        api.name = Intern.intern(name);
        api.access = Acc.PUBLIC;
        api.superName = superName == null ? Intern.NONE : Intern.intern(superName);
        members(api, true, methods);
        return api;
    }

    private static ClassApi classWithFields(String name, Object[]... fields) {
        ClassApi api = new ClassApi();
        api.name = Intern.intern(name);
        api.access = Acc.PUBLIC;
        api.superName = Intern.intern(JAVA_LANG_OBJECT);
        members(api, false, fields);
        return api;
    }

    @Test
    void detectsMethodRemoval() {
        ApiIndex oldIndex = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT, m("m", "()J", Acc.PUBLIC))));
        ApiIndex newIndex = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT)));
        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertEquals(1, changes.size());
        assertEquals(BreakingChange.Kind.METHOD_REMOVED, changes.get(0).kind());
        assertEquals("a/C", Intern.str(changes.get(0).className()));
        assertEquals("m", Intern.str(changes.get(0).name()));
    }

    @Test
    void methodMovedToSuperclassIsNotBreaking() {
        ApiIndex oldIndex = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT, m("m", "()V", Acc.PUBLIC))));
        ApiIndex newIndex =
                ApiIndex.build(List.of(classOf("a/C", "a/D"), classOf("a/D", JAVA_LANG_OBJECT, m("m", "()V", Acc.PUBLIC))));
        assertTrue(Diff.diff(oldIndex, newIndex).isEmpty());
    }

    @Test
    void descriptorChangeReportsReplacement() {
        ApiIndex oldIndex = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT, m("m", "()J", Acc.PUBLIC))));
        ApiIndex newIndex = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT, m("m", "()I", Acc.PUBLIC))));
        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertEquals(1, changes.size());
        assertEquals(BreakingChange.Kind.METHOD_REMOVED, changes.get(0).kind());
        assertEquals(List.of("()I"), texts(changes.get(0).replacementDescriptors()));
    }

    @Test
    void classRemovalFoldsMemberRemovals() {
        ApiIndex oldIndex =
                ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT, m("m", "()V", Acc.PUBLIC), m("n", "()V", Acc.PUBLIC))));
        ApiIndex newIndex = ApiIndex.build(List.of());
        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertEquals(1, changes.size());
        assertEquals(BreakingChange.Kind.CLASS_REMOVED, changes.get(0).kind());
        assertEquals("a/C", Intern.str(changes.get(0).className()));
    }

    @Test
    void privateMembersAreNotReported() {
        ApiIndex oldIndex = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT, m("m", "()V", Acc.PRIVATE))));
        ApiIndex newIndex = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT)));
        assertTrue(Diff.diff(oldIndex, newIndex).isEmpty());
    }

    @Test
    void detectsFieldBecameFinal() {
        ApiIndex oldIndex = ApiIndex.build(List.of(classWithFields("a/C", m("x", "I", Acc.PUBLIC))));
        ApiIndex newIndex = ApiIndex.build(List.of(classWithFields("a/C", m("x", "I", Acc.PUBLIC | Acc.FINAL))));
        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertEquals(BreakingChange.Kind.FIELD_BECAME_FINAL, changes.get(0).kind());
        assertEquals("a/C", Intern.str(changes.get(0).className()));
        assertEquals("x", Intern.str(changes.get(0).name()));
        assertEquals("I", Intern.str(changes.get(0).descriptor()));
    }

    @Test
    void externalSupertypeSuppressesRemoval() {
        ApiIndex oldIndex = ApiIndex.build(List.of(classOf("a/C", JAVA_LANG_OBJECT, m("m", "()V", Acc.PUBLIC))));
        // The parent changed to a class outside the index in the new version, so this cannot be proven and is not reported.
        ApiIndex newIndex = ApiIndex.build(List.of(classOf("a/C", "ext/Base")));
        assertTrue(Diff.diff(oldIndex, newIndex).isEmpty());
    }

    // ---- beyond the Rust unit tests, every kind once and the order of the listing ----

    private static List<String> texts(List<Integer> syms) {
        List<String> out = new ArrayList<>();
        for (int sym : syms) {
            out.add(Intern.str(sym));
        }
        return out;
    }

    private static String show(BreakingChange c) {
        StringBuilder sb = new StringBuilder(c.kind().tag).append(' ').append(Intern.str(c.className()));
        if (c.name() != Intern.NONE) {
            sb.append('.').append(Intern.str(c.name())).append(':').append(Intern.str(c.descriptor()));
        }
        if (c.from() != null) {
            sb.append(' ').append(c.from().text).append("->").append(c.to().text);
        }
        if (c.replacementDescriptors() != null) {
            sb.append(' ').append(texts(c.replacementDescriptors()));
        }
        return sb.toString();
    }

    private static List<String> show(List<BreakingChange> changes) {
        List<String> out = new ArrayList<>();
        for (BreakingChange c : changes) {
            out.add(show(c));
        }
        return out;
    }

    private static ClassApi withAccess(ClassApi api, int access) {
        api.access = access;
        return api;
    }

    @Test
    void listsEveryKindOfChangeOrderedByText() {
        // Interned in reverse so symbol id order and string order disagree.
        ClassApi zOld = classOf(
                "d/Zed",
                JAVA_LANG_OBJECT,
                m("narrow", "()V", Acc.PUBLIC),
                m("abstracted", "()V", Acc.PUBLIC),
                m("toStatic", "()V", Acc.PUBLIC),
                m("toInstance", "()V", Acc.PUBLIC | Acc.STATIC),
                m("sealedOff", "()V", Acc.PROTECTED),
                m("gone", "(I)V", Acc.PUBLIC),
                m("hidden", "()V", Acc.PRIVATE));
        members(
                zOld,
                false,
                m("fNarrow", "I", Acc.PROTECTED),
                m("fStatic", "I", Acc.PUBLIC),
                m("fInstance", "I", Acc.PUBLIC | Acc.STATIC),
                m("fGone", "I", Acc.PUBLIC));
        ClassApi zNew = classOf(
                "d/Zed",
                JAVA_LANG_OBJECT,
                m("narrow", "()V", Acc.PROTECTED),
                m("abstracted", "()V", Acc.PUBLIC | Acc.ABSTRACT),
                m("toStatic", "()V", Acc.PUBLIC | Acc.STATIC),
                m("toInstance", "()V", Acc.PUBLIC),
                m("sealedOff", "()V", Acc.PROTECTED | Acc.FINAL),
                m("gone", "(J)V", Acc.PUBLIC),
                m("gone", "()V", Acc.PUBLIC));
        members(
                zNew,
                false,
                m("fNarrow", "I", 0),
                m("fStatic", "I", Acc.PUBLIC | Acc.STATIC),
                m("fInstance", "I", Acc.PUBLIC),
                m("fGone", "J", Acc.PUBLIC));

        List<ClassApi> oldApis = List.of(
                zOld,
                withAccess(classOf("d/Narrowed", JAVA_LANG_OBJECT), Acc.PUBLIC),
                withAccess(classOf("d/Finalized", JAVA_LANG_OBJECT), Acc.PUBLIC),
                withAccess(classOf("d/Abstracted", JAVA_LANG_OBJECT), Acc.PUBLIC),
                withAccess(classOf("d/NowInterface", JAVA_LANG_OBJECT), Acc.PUBLIC),
                withAccess(classOf("d/NowClass", JAVA_LANG_OBJECT), Acc.PUBLIC | Acc.INTERFACE | Acc.ABSTRACT),
                classOf("d/Removed", JAVA_LANG_OBJECT, m("m", "()V", Acc.PUBLIC)));
        List<ClassApi> newApis = List.of(
                zNew,
                withAccess(classOf("d/Narrowed", JAVA_LANG_OBJECT), 0),
                withAccess(classOf("d/Finalized", JAVA_LANG_OBJECT), Acc.PUBLIC | Acc.FINAL),
                withAccess(classOf("d/Abstracted", JAVA_LANG_OBJECT), Acc.PUBLIC | Acc.ABSTRACT),
                withAccess(classOf("d/NowInterface", JAVA_LANG_OBJECT), Acc.PUBLIC | Acc.INTERFACE | Acc.ABSTRACT),
                withAccess(classOf("d/NowClass", JAVA_LANG_OBJECT), Acc.PUBLIC));

        assertEquals(
                List.of(
                        "class_became_abstract d/Abstracted",
                        "class_became_final d/Finalized",
                        "class_access_narrowed d/Narrowed public->package-private",
                        "interface_became_class d/NowClass",
                        // The kind flip subsumes becoming abstract.
                        "class_became_interface d/NowInterface",
                        "class_removed d/Removed",
                        "method_became_abstract d/Zed.abstracted:()V",
                        "method_removed d/Zed.gone:(I)V [()V, (J)V]",
                        "method_access_narrowed d/Zed.narrow:()V public->protected",
                        "method_became_final d/Zed.sealedOff:()V",
                        "method_became_instance d/Zed.toInstance:()V",
                        "method_became_static d/Zed.toStatic:()V",
                        "field_removed d/Zed.fGone:I [J]",
                        "field_became_instance d/Zed.fInstance:I",
                        "field_access_narrowed d/Zed.fNarrow:I protected->package-private",
                        "field_became_static d/Zed.fStatic:I"),
                show(Diff.diff(ApiIndex.build(oldApis), ApiIndex.build(newApis))));
    }

    /** One member can change in several ways at once, and each is listed in the Rust order. */
    @Test
    void oneMemberCanCarrySeveralChanges() {
        ApiIndex oldIndex = ApiIndex.build(List.of(classOf("e/C", JAVA_LANG_OBJECT, m("m", "()V", Acc.PUBLIC))));
        ApiIndex newIndex = ApiIndex.build(
                List.of(classOf("e/C", JAVA_LANG_OBJECT, m("m", "()V", Acc.PROTECTED | Acc.ABSTRACT | Acc.STATIC | Acc.FINAL))));
        assertEquals(
                List.of(
                        "method_access_narrowed e/C.m:()V public->protected",
                        "method_became_abstract e/C.m:()V",
                        "method_became_static e/C.m:()V",
                        "method_became_final e/C.m:()V"),
                show(Diff.diff(oldIndex, newIndex)));
    }

    private static ClassApi sealedTo(ClassApi api, String... permitted) {
        api.permitted = new int[permitted.length];
        for (int i = 0; i < permitted.length; i++) {
            api.permitted[i] = Intern.intern(permitted[i]);
        }
        return api;
    }

    private static List<String> sealingDiff(ClassApi oldApi, ClassApi newApi) {
        return show(Diff.diff(ApiIndex.build(List.of(oldApi)), ApiIndex.build(List.of(newApi))));
    }

    @Test
    void sealingIsReportedOnlyWhenItTightens() {
        String sealed = "class_became_sealed s/C";
        // Gained the attribute, even one permitting nothing.
        assertEquals(List.of(sealed), sealingDiff(classOf("s/C", JAVA_LANG_OBJECT), sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/P")));
        assertEquals(List.of(sealed), sealingDiff(classOf("s/C", JAVA_LANG_OBJECT), sealedTo(classOf("s/C", JAVA_LANG_OBJECT))));
        // Dropped a name.
        assertEquals(
                List.of(sealed),
                sealingDiff(sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/P", "s/Q"), sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/Q")));
        // Adding names only widens, and unsealing is no break.
        assertEquals(
                List.of(),
                sealingDiff(sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/P"), sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/Q", "s/P")));
        assertEquals(List.of(), sealingDiff(sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/P"), classOf("s/C", JAVA_LANG_OBJECT)));

        // Sealing that could not be read on either side is skipped, not read as unsealed.
        ClassApi unreadable = classOf("s/C", JAVA_LANG_OBJECT);
        unreadable.sealingUnknown = true;
        assertEquals(List.of(), sealingDiff(unreadable, sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/P")));
        ClassApi unreadableNew = sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/P");
        unreadableNew.sealingUnknown = true;
        assertEquals(List.of(), sealingDiff(classOf("s/C", JAVA_LANG_OBJECT), unreadableNew));

        // javac seals enums with constant-specific bodies since JDK 17, and a final class had
        // no subclass to strand. Either side's flag is enough.
        assertEquals(
                List.of(),
                sealingDiff(
                        withAccess(classOf("s/C", JAVA_LANG_OBJECT), Acc.PUBLIC | Acc.ENUM),
                        sealedTo(withAccess(classOf("s/C", JAVA_LANG_OBJECT), Acc.PUBLIC | Acc.ENUM), "s/P")));
        assertEquals(
                List.of(),
                sealingDiff(
                        withAccess(classOf("s/C", JAVA_LANG_OBJECT), Acc.PUBLIC | Acc.FINAL),
                        sealedTo(classOf("s/C", JAVA_LANG_OBJECT), "s/P")));
    }

    /** The JSON tag is also the listing label, so the two vocabularies must stay one. */
    @Test
    void kindTagsAreDistinctSnakeCaseNames() {
        Set<String> tags = new HashSet<>();
        for (BreakingChange.Kind kind : BreakingChange.Kind.values()) {
            assertEquals(kind.name().toLowerCase(java.util.Locale.ROOT), kind.tag);
            assertTrue(tags.add(kind.tag));
        }
        assertEquals(18, tags.size());
        BreakingChange classLevel = BreakingChange.ofClass(BreakingChange.Kind.CLASS_REMOVED, Intern.intern("k/C"));
        assertEquals(Intern.NONE, classLevel.name());
        assertNull(classLevel.replacementDescriptors());
    }
}
