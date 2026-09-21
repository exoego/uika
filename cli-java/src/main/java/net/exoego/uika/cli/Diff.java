package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists APIs that existed in old but no longer resolve in new, plus incompatible changes on
 * surviving members.
 *
 * <p>A member that merely moved to a superclass or superinterface still links, so it is not
 * breaking. Resolution escaping to a parent outside the index cannot be proven either way and
 * is not reported. Private members cannot be linked from outside, so they are not reported
 * (they are still indexed and affect check resolution).
 */
final class Diff {
    private Diff() {}

    static List<BreakingChange> diff(ApiIndex oldIndex, ApiIndex newIndex) {
        List<BreakingChange> changes = new ArrayList<>();
        // Symbol ids follow nondeterministic intern order, so output is ordered by string value.
        List<Integer> oldClasses = oldIndex.classNames();
        oldClasses.sort(Intern::compare);

        for (int name : oldClasses) {
            int entry = oldIndex.entry(name);
            int newEntry = newIndex.entry(name);
            if (newEntry < 0) {
                // Member removals fold into the class removal.
                changes.add(BreakingChange.ofClass(BreakingChange.Kind.CLASS_REMOVED, name));
                continue;
            }
            int oldAccess = oldIndex.accessOf(entry);
            int newAccess = newIndex.accessOf(newEntry);
            if (accessNarrowed(oldAccess, newAccess)) {
                changes.add(BreakingChange.classNarrowed(name, Visibility.of(oldAccess), Visibility.of(newAccess)));
            }
            if ((oldAccess & Acc.FINAL) == 0 && (newAccess & Acc.FINAL) != 0) {
                changes.add(BreakingChange.ofClass(BreakingChange.Kind.CLASS_BECAME_FINAL, name));
            }
            if (extendable(oldAccess | newAccess)
                    && !oldIndex.sealingUnknown(entry)
                    && !newIndex.sealingUnknown(newEntry)
                    && sealingTightened(oldIndex, entry, newIndex, newEntry)) {
                changes.add(BreakingChange.ofClass(BreakingChange.Kind.CLASS_BECAME_SEALED, name));
            }
            boolean oldInterface = (oldAccess & Acc.INTERFACE) != 0;
            boolean newInterface = (newAccess & Acc.INTERFACE) != 0;
            if (oldInterface != newInterface) {
                // The kind flip subsumes becoming abstract (an interface is always abstract).
                changes.add(BreakingChange.ofClass(
                        newInterface ? BreakingChange.Kind.CLASS_BECAME_INTERFACE : BreakingChange.Kind.INTERFACE_BECAME_CLASS, name));
            } else if ((oldAccess & Acc.ABSTRACT) == 0 && (newAccess & Acc.ABSTRACT) != 0) {
                changes.add(BreakingChange.ofClass(BreakingChange.Kind.CLASS_BECAME_ABSTRACT, name));
            }

            for (long[] member : visibleSorted(oldIndex, entry, true)) {
                long key = member[0];
                int oldMember = (int) member[1];
                int newMember = newIndex.findMethod(newEntry, key);
                if (newMember >= 0) {
                    if (accessNarrowed(oldMember, newMember)) {
                        changes.add(BreakingChange.memberNarrowed(
                                BreakingChange.Kind.METHOD_ACCESS_NARROWED, name, key, Visibility.of(oldMember), Visibility.of(newMember)));
                    }
                    if ((oldMember & Acc.ABSTRACT) == 0 && (newMember & Acc.ABSTRACT) != 0) {
                        changes.add(BreakingChange.ofMember(BreakingChange.Kind.METHOD_BECAME_ABSTRACT, name, key));
                    }
                    if ((oldMember & Acc.STATIC) != (newMember & Acc.STATIC)) {
                        changes.add(BreakingChange.ofMember(
                                (newMember & Acc.STATIC) != 0
                                        ? BreakingChange.Kind.METHOD_BECAME_STATIC
                                        : BreakingChange.Kind.METHOD_BECAME_INSTANCE,
                                name,
                                key));
                    }
                    if ((oldMember & Acc.FINAL) == 0 && (newMember & Acc.FINAL) != 0) {
                        changes.add(BreakingChange.ofMember(BreakingChange.Kind.METHOD_BECAME_FINAL, name, key));
                    }
                } else if (newIndex.resolve(name, key, Scope.MemberKind.METHOD) == Scope.Resolution.NOT_FOUND) {
                    changes.add(BreakingChange.removed(
                            BreakingChange.Kind.METHOD_REMOVED, name, key, replacements(newIndex, newEntry, key, true)));
                }
            }
            for (long[] member : visibleSorted(oldIndex, entry, false)) {
                long key = member[0];
                int oldMember = (int) member[1];
                int newMember = newIndex.findField(newEntry, key);
                if (newMember >= 0) {
                    if (accessNarrowed(oldMember, newMember)) {
                        changes.add(BreakingChange.memberNarrowed(
                                BreakingChange.Kind.FIELD_ACCESS_NARROWED, name, key, Visibility.of(oldMember), Visibility.of(newMember)));
                    }
                    if ((oldMember & Acc.STATIC) != (newMember & Acc.STATIC)) {
                        changes.add(BreakingChange.ofMember(
                                (newMember & Acc.STATIC) != 0
                                        ? BreakingChange.Kind.FIELD_BECAME_STATIC
                                        : BreakingChange.Kind.FIELD_BECAME_INSTANCE,
                                name,
                                key));
                    }
                    if ((oldMember & Acc.FINAL) == 0 && (newMember & Acc.FINAL) != 0) {
                        changes.add(BreakingChange.ofMember(BreakingChange.Kind.FIELD_BECAME_FINAL, name, key));
                    }
                } else if (newIndex.resolve(name, key, Scope.MemberKind.FIELD) == Scope.Resolution.NOT_FOUND) {
                    changes.add(BreakingChange.removed(
                            BreakingChange.Kind.FIELD_REMOVED, name, key, replacements(newIndex, newEntry, key, false)));
                }
            }
        }
        return changes;
    }

    /**
     * javac seals enums with constant-specific bodies since JDK 17
     * (https://issues.apache.org/jira/browse/GROOVY-10194), so a bare recompile would report
     * every such enum. Callers pass the OR of both sides' flags: final -> sealed is compatible
     * per JLS 13.4.2, because the old class had no subclasses to strand.
     */
    private static boolean extendable(int access) {
        return (access & (Acc.ENUM | Acc.FINAL)) == 0;
    }

    /** Gained a PermittedSubclasses attribute, or dropped a name from one. Adding names only widens. */
    private static boolean sealingTightened(ApiIndex oldIndex, int oldEntry, ApiIndex newIndex, int newEntry) {
        if (newIndex.permittedCount(newEntry) < 0) {
            return false;
        }
        int oldCount = oldIndex.permittedCount(oldEntry);
        if (oldCount < 0) {
            return true;
        }
        for (int k = 0; k < oldCount; k++) {
            if (!newIndex.permits(newEntry, oldIndex.permittedAt(oldEntry, k))) {
                return true;
            }
        }
        return false;
    }

    private static boolean accessNarrowed(int oldAccess, int newAccess) {
        return Visibility.of(newAccess).compareTo(Visibility.of(oldAccess)) < 0;
    }

    /** Non-private members as {key, access}, ordered by name then descriptor string value. */
    private static List<long[]> visibleSorted(ApiIndex index, int entry, boolean methods) {
        int n = methods ? index.methodCount(entry) : index.fieldCount(entry);
        List<long[]> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            int access = methods ? index.methodAccessAt(entry, k) : index.fieldAccessAt(entry, k);
            if ((access & Acc.PRIVATE) == 0) {
                out.add(new long[] {methods ? index.methodKeyAt(entry, k) : index.fieldKeyAt(entry, k), access});
            }
        }
        out.sort((a, b) -> MemberKey.compare(a[0], b[0]));
        return out;
    }

    /** Same-name, different-descriptor members left in the new class: signature-change hints. */
    private static List<Integer> replacements(ApiIndex newIndex, int newEntry, long key, boolean methods) {
        List<Integer> descriptors = new ArrayList<>();
        int n = methods ? newIndex.methodCount(newEntry) : newIndex.fieldCount(newEntry);
        for (int k = 0; k < n; k++) {
            long candidate = methods ? newIndex.methodKeyAt(newEntry, k) : newIndex.fieldKeyAt(newEntry, k);
            if (MemberKey.name(candidate) == MemberKey.name(key) && MemberKey.descriptor(candidate) != MemberKey.descriptor(key)) {
                descriptors.add(MemberKey.descriptor(candidate));
            }
        }
        descriptors.sort(Intern::compare);
        return descriptors;
    }
}
