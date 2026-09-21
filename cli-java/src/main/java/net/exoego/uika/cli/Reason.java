package net.exoego.uika.cli;

import java.util.List;

/**
 * Why a reference or scanned class breaks. Everything user-visible (JSON, the verdicts
 * stream, the text report, sort order) speaks {@link #text}; never order by ordinal.
 */
enum Reason {
    CLASS_REMOVED("class removed"),
    CLASS_ACCESS_NARROWED("class access narrowed"),
    CLASS_BECAME_ABSTRACT("class became abstract"),
    CLASS_BECAME_FINAL("class became final"),
    CLASS_BECAME_SEALED("class became sealed"),
    CLASS_BECAME_INTERFACE("class became interface"),
    INTERFACE_BECAME_CLASS("interface became class"),
    EXTENDS_FINAL_CLASS("extends final class"),
    METHOD_REMOVED("method removed"),
    METHOD_ACCESS_NARROWED("method access narrowed"),
    METHOD_BECAME_ABSTRACT("method became abstract"),
    CONFLICTING_DEFAULT_METHODS("conflicting default methods"),
    METHOD_BECAME_FINAL("method became final"),
    METHOD_BECAME_STATIC("method became static"),
    METHOD_BECAME_INSTANCE("method became instance"),
    FIELD_REMOVED("field removed"),
    FIELD_ACCESS_NARROWED("field access narrowed"),
    FIELD_BECAME_FINAL("field became final"),
    FIELD_BECAME_STATIC("field became static"),
    FIELD_BECAME_INSTANCE("field became instance"),
    /** A META-INF/services provider ServiceLoader could construct under old is gone under new. */
    SERVICE_PROVIDER_REMOVED("service provider removed"),
    /** The provider exists but is no longer a public concrete subtype with a public no-arg constructor. */
    SERVICE_PROVIDER_NOT_INSTANTIABLE("service provider not instantiable");

    static final Reason[] ALL = values();

    /** The stable wire/report string. */
    final String text;

    Reason(String text) {
        this.text = text;
    }

    /** The exclude-rule {@code kind} spelling: {@link #text} with underscores. */
    String configText() {
        return text.replace(' ', '_');
    }

    /** Accepts both spellings, so a reason pasted straight out of a report works. */
    static Reason parse(String s) {
        String spaced = s.replace('_', ' ');
        for (Reason r : ALL) {
            if (r.text.equals(spaced)) {
                return r;
            }
        }
        return null;
    }

    /**
     * The reasons an exclude rule's {@code kind} names. Several for a spelling that named a
     * category before it was split by direction and member kind, so a rule file written
     * against an older uika keeps waiving what it used to. Every legacy string below was a
     * released value; dropping one fails a build on nothing but a uika upgrade.
     */
    static List<Reason> parseKinds(String s) {
        Reason one = parse(s);
        if (one != null) {
            return List.of(one);
        }
        return switch (s.replace('_', ' ')) {
            case "class kind changed" -> List.of(CLASS_BECAME_INTERFACE, INTERFACE_BECAME_CLASS);
            case "member changed from static to instance" -> List.of(METHOD_BECAME_INSTANCE, FIELD_BECAME_INSTANCE);
            case "member changed from instance to static" -> List.of(METHOD_BECAME_STATIC, FIELD_BECAME_STATIC);
            default -> null;
        };
    }
}
