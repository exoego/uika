package net.exoego.uika.cli;

import java.util.List;

/**
 * One incompatible change between two library versions, as {@code diff} lists it.
 *
 * <p>Every direction is its own kind, so the JSON tag and the text label name the change
 * unambiguously: {@code jq 'select(.kind == "method_became_final")'} selects exactly the
 * listing lines labelled METHOD BECAME FINAL.
 *
 * @param name member name, {@link Intern#NONE} for a class-level change
 * @param descriptor member descriptor, {@link Intern#NONE} for a class-level change
 * @param from old visibility, only for the access-narrowed kinds
 * @param to new visibility, only for the access-narrowed kinds
 * @param replacementDescriptors same-name members left in the new class, only for the
 *     member-removed kinds
 */
record BreakingChange(Kind kind, int className, int name, int descriptor, Visibility from, Visibility to, List<Integer> replacementDescriptors) {
    enum Kind {
        CLASS_REMOVED("class_removed"),
        METHOD_REMOVED("method_removed"),
        FIELD_REMOVED("field_removed"),
        CLASS_ACCESS_NARROWED("class_access_narrowed"),
        CLASS_BECAME_FINAL("class_became_final"),
        CLASS_BECAME_SEALED("class_became_sealed"),
        CLASS_BECAME_ABSTRACT("class_became_abstract"),
        CLASS_BECAME_INTERFACE("class_became_interface"),
        INTERFACE_BECAME_CLASS("interface_became_class"),
        METHOD_BECAME_ABSTRACT("method_became_abstract"),
        METHOD_ACCESS_NARROWED("method_access_narrowed"),
        FIELD_ACCESS_NARROWED("field_access_narrowed"),
        METHOD_BECAME_STATIC("method_became_static"),
        METHOD_BECAME_INSTANCE("method_became_instance"),
        FIELD_BECAME_STATIC("field_became_static"),
        FIELD_BECAME_INSTANCE("field_became_instance"),
        FIELD_BECAME_FINAL("field_became_final"),
        METHOD_BECAME_FINAL("method_became_final");

        /** The JSON tag, which is also the listing label (uppercased, underscores as spaces). */
        final String tag;

        Kind(String tag) {
            this.tag = tag;
        }

        boolean hasMember() {
            return switch (this) {
                case CLASS_REMOVED, CLASS_ACCESS_NARROWED, CLASS_BECAME_FINAL, CLASS_BECAME_SEALED, CLASS_BECAME_ABSTRACT,
                        CLASS_BECAME_INTERFACE, INTERFACE_BECAME_CLASS -> false;
                default -> true;
            };
        }

        boolean isRemoval() {
            return this == METHOD_REMOVED || this == FIELD_REMOVED;
        }

        boolean isNarrowing() {
            return this == CLASS_ACCESS_NARROWED || this == METHOD_ACCESS_NARROWED || this == FIELD_ACCESS_NARROWED;
        }
    }

    static BreakingChange ofClass(Kind kind, int className) {
        return new BreakingChange(kind, className, Intern.NONE, Intern.NONE, null, null, null);
    }

    static BreakingChange classNarrowed(int className, Visibility from, Visibility to) {
        return new BreakingChange(Kind.CLASS_ACCESS_NARROWED, className, Intern.NONE, Intern.NONE, from, to, null);
    }

    static BreakingChange ofMember(Kind kind, int className, long member) {
        return new BreakingChange(kind, className, MemberKey.name(member), MemberKey.descriptor(member), null, null, null);
    }

    static BreakingChange memberNarrowed(Kind kind, int className, long member, Visibility from, Visibility to) {
        return new BreakingChange(kind, className, MemberKey.name(member), MemberKey.descriptor(member), from, to, null);
    }

    static BreakingChange removed(Kind kind, int className, long member, List<Integer> replacementDescriptors) {
        return new BreakingChange(
                kind, className, MemberKey.name(member), MemberKey.descriptor(member), null, null, replacementDescriptors);
    }
}
