package net.exoego.uika.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit suppression of known false positives (e.g. reflection-only member access).
 *
 * <p>Reachability only ever deprioritizes a violation, never drops it, because it cannot see
 * reflection driven by external configuration. An exclude list is the opposite. An operator
 * explicitly asserts "I know about this reference and it is not a real break", so dropping
 * it here is fine as long as the assertion stays visible, which means a committed TOML file
 * with a required reason, a suppressed count in the report, and a warning for entries that
 * matched nothing (so the list does not silently rot as the checked libraries change).
 *
 * <p>Kind matching lives here rather than in {@code --fail-on} so the gate stays a pure tier
 * threshold that always matches the displayed grouping.
 *
 * <p>A kind rule inherits the waiver semantics of the rest of the file, so matches are
 * DROPPED, not merely un-gated. Right for "we never want to see this category", wrong for
 * "keep showing it, just do not fail the build", which has no mechanism here.
 */
final class Exclude {
    private Exclude() {}

    private static final String[] ENTRY_FIELDS = {"owner", "member", "descriptor", "kind", "reason"};
    private static final String ENTRY_SHAPE = "a table with owner, member, descriptor, kind or reason";

    /**
     * Unknown keys are rejected, not ignored. Every scoping field is optional, so a
     * misspelled {@code onwer} would read as absent and silently WIDEN the rule to every
     * owner. It would still match, so the unused-rule warning could not catch it either.
     */
    private record RawEntry(String owner, String member, String descriptor, String kind, String reason) {}

    /** {@code prefix} holds the original text stripped of its trailing '*'. */
    private record OwnerPattern(String text, boolean prefix) {
        boolean matches(String owner) {
            return prefix ? owner.startsWith(text) : text.equals(owner);
        }

        @Override
        public String toString() {
            return prefix ? text + "*" : text;
        }
    }

    static final class Rule {
        /** Null (only with a kind) matches every owner. */
        private final OwnerPattern owner;
        /** Null excludes the owner outright, matching class-level violations too. */
        private final String member;
        /**
         * Null (with a member name) covers every overload, which over-suppresses when only
         * one overload is a known false positive. Pinning the descriptor keeps a real break
         * on a sibling overload reported.
         */
        private final String descriptor;
        /**
         * Empty matches every kind. More than one only from a spelling that named a category
         * before it was split, e.g. "class_kind_changed".
         */
        private final List<Reason> kinds;

        private final String reason;

        private Rule(OwnerPattern owner, String member, String descriptor, List<Reason> kinds, String reason) {
            this.owner = owner;
            this.member = member;
            this.descriptor = descriptor;
            this.kinds = kinds;
            this.reason = reason;
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (owner != null) {
                if (member == null) {
                    parts.add(owner.toString());
                } else if (descriptor == null) {
                    parts.add(owner + "#" + member);
                } else {
                    parts.add(owner + "#" + member + " " + descriptor);
                }
            }
            if (!kinds.isEmpty()) {
                // Canonical spelling, so a rule written in the spaced form, or in a spelling
                // since split, is echoed in the form the docs use. Each kind is quoted on
                // its own, because a rule names one kind and a joined list would not load
                // if it were pasted back.
                List<String> shown = new ArrayList<>();
                for (Reason kind : kinds) {
                    shown.add("\"" + kind.configText() + "\"");
                }
                parts.add("kind " + String.join(" or ", shown));
            }
            return String.join(" ", parts) + " (" + reason + ")";
        }
    }

    record Stats(int suppressed, List<String> unused) {}

    /**
     * Parse one exclude file's TOML content into rules. Throws {@link Toml.Error} for a file
     * that is not TOML or not shaped like an exclude file, and {@link UikaException} for a
     * rule that does not make sense.
     */
    static List<Rule> parse(String content) {
        List<Rule> rules = new ArrayList<>();
        for (RawEntry entry : read(Toml.parse(content))) {
            rules.add(compile(entry));
        }
        return rules;
    }

    // Keys are visited in the table's own order, and the first problem ends the read.
    private static List<RawEntry> read(Toml.Table file) {
        List<RawEntry> entries = new ArrayList<>();
        for (Toml.Entry top : file.entries()) {
            if (!top.key().equals("exclude")) {
                throw top.error("unknown key " + Json.quote(top.key()) + ", expected exclude");
            }
            List<Toml.Value> items = expect(top.value(), Toml.Kind.ARRAY, "exclude", "an array of tables").asArray();
            for (int i = 0; i < items.size(); i++) {
                Toml.Value item = items.get(i);
                String path = "exclude[" + i + "]";
                Toml.Table table = expect(item, Toml.Kind.TABLE, path, ENTRY_SHAPE).asTable();
                String[] values = new String[ENTRY_FIELDS.length];
                for (Toml.Entry field : table.entries()) {
                    int index = List.of(ENTRY_FIELDS).indexOf(field.key());
                    if (index < 0) {
                        throw field.error(path + ": unknown key " + Json.quote(field.key()) + ", expected one of "
                                + String.join(", ", ENTRY_FIELDS));
                    }
                    values[index] = expect(field.value(), Toml.Kind.STRING, path + "." + field.key(), "a string")
                            .asString();
                }
                if (values[4] == null) {
                    throw item.error(path + ": missing required key \"reason\"");
                }
                entries.add(new RawEntry(values[0], values[1], values[2], values[3], values[4]));
            }
        }
        return entries;
    }

    private static Toml.Value expect(Toml.Value value, Toml.Kind kind, String path, String expected) {
        if (value.kind() != kind) {
            throw value.error(path + ": expected " + expected + ", found " + value.describe());
        }
        return value;
    }

    private static Rule compile(RawEntry entry) {
        // Best identifier for error messages, whichever scoping field the rule has.
        String label = entry.owner != null ? entry.owner : entry.kind != null ? entry.kind : "";
        if (Reach.trim(entry.reason).isEmpty()) {
            throw new UikaException("exclude rule \"" + label
                    + "\" is missing a reason (reason must explain why the violation is a known false positive)");
        }
        if (entry.owner == null && entry.kind == null) {
            throw new UikaException("exclude rule needs an owner, a kind, or both (owner scopes it to a class or"
                    + " package, kind to one violation kind such as \"method_became_abstract\")");
        }
        if (entry.member != null && entry.owner == null) {
            throw new UikaException("exclude rule \"" + label
                    + "\": member requires an owner (a member name only means something on a class)");
        }
        if (entry.descriptor != null && entry.member == null) {
            throw new UikaException("exclude rule \"" + label
                    + "\": descriptor requires a member (a descriptor pins one overload of a named member)");
        }
        List<Reason> kinds = List.of();
        if (entry.kind != null) {
            kinds = Reason.parseKinds(entry.kind);
            if (kinds == null) {
                List<String> valid = new ArrayList<>();
                for (Reason reason : Reason.ALL) {
                    valid.add(reason.configText());
                }
                throw new UikaException("exclude rule \"" + label + "\": unknown kind \"" + entry.kind
                        + "\", expected one of " + String.join(", ", valid));
            }
        }
        OwnerPattern owner = null;
        if (entry.owner != null) {
            String o = entry.owner;
            long stars = o.chars().filter(c -> c == '*').count();
            if (stars > 1 || (stars == 1 && !o.endsWith("*"))) {
                throw new UikaException("exclude rule owner \"" + o
                        + "\": '*' is only supported once, as a trailing wildcard (prefix match)");
            }
            owner = o.endsWith("*") ? new OwnerPattern(o.substring(0, o.length() - 1), true) : new OwnerPattern(o, false);
        }
        return new Rule(owner, entry.member, entry.descriptor, kinds, entry.reason);
    }

    /** Load and merge exclude rules from one or more TOML files (union of all rules). */
    static List<Rule> load(List<String> paths) {
        List<Rule> rules = new ArrayList<>();
        for (String path : paths) {
            String content;
            try {
                content = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Files.readAllBytes(Path.of(path))))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new UikaException("cannot read exclude file " + path + ": stream did not contain valid UTF-8");
            } catch (IOException e) {
                if (Files.isDirectory(Path.of(path))) {
                    throw new UikaException("cannot read exclude file " + path + ": Is a directory (os error 21)");
                }
                throw new UikaException("cannot read exclude file " + path, e);
            }
            String subject = "invalid exclude file " + path;
            try {
                rules.addAll(parse(content));
            } catch (Toml.Error e) {
                throw new UikaException(e.render(subject));
            } catch (UikaException e) {
                throw new UikaException(subject, e);
            }
        }
        return rules;
    }

    /**
     * Drop violations matched by any rule. A violation may match more than one rule. All
     * matching rules are marked used (not just the first), so a redundant-but-still-applicable
     * rule is never reported as stale.
     */
    static Stats filter(List<Violation> violations, List<Rule> rules) {
        if (rules.isEmpty()) {
            return new Stats(0, new ArrayList<>());
        }
        boolean[] hit = new boolean[rules.size()];
        List<Violation> kept = new ArrayList<>();
        for (Violation v : violations) {
            String owner = Intern.str(v.reference.owner());
            boolean hasMember = v.reference.hasMember();
            String memberName = hasMember ? Intern.str(MemberKey.name(v.reference.member())) : null;
            String memberDescriptor = hasMember ? Intern.str(MemberKey.descriptor(v.reference.member())) : null;
            boolean matched = false;
            for (int i = 0; i < hit.length; i++) {
                Rule rule = rules.get(i);
                if (rule.owner != null && !rule.owner.matches(owner)) {
                    continue;
                }
                if (!rule.kinds.isEmpty() && !rule.kinds.contains(v.reason)) {
                    continue;
                }
                // A descriptor, when present, pins one overload.
                boolean memberMatches = rule.member == null
                        || (rule.member.equals(memberName)
                                && (rule.descriptor == null || rule.descriptor.equals(memberDescriptor)));
                if (memberMatches) {
                    hit[i] = true;
                    matched = true;
                }
            }
            if (!matched) {
                kept.add(v);
            }
        }
        int suppressed = violations.size() - kept.size();
        violations.clear();
        violations.addAll(kept);
        List<String> unused = new ArrayList<>();
        for (int i = 0; i < hit.length; i++) {
            if (!hit[i]) {
                unused.add(rules.get(i).describe());
            }
        }
        return new Stats(suppressed, unused);
    }
}
