package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * The text, Markdown and JSON renderers for every command.
 *
 * <p>Every sorted collection here orders by UTF-8 string value, never by symbol id, so the
 * output is the same on every run.
 */
final class Report {
    private Report() {}

    /**
     * Width of the tag column every {@code diff} line opens with. Padding to one width here is
     * what keeps the names starting at the same column. The tags used to be padded by hand and
     * had drifted into three columns (17, 23 and 24) inside a single listing. Five kinds tie at
     * this width, so it is not one tag's length to chase. {@code tagColumnFitsEveryKind} pins
     * the width against every kind. A longer kind does not truncate, it just pushes its own
     * line's name out of the column.
     */
    static final int TAG_WIDTH = 22;

    private static final Comparator<String> UTF8 = Text::compareUtf8;
    /** Rust orders {@code None} before every {@code Some}. */
    private static final Comparator<String> UTF8_NULL_FIRST = Comparator.nullsFirst(UTF8);

    // ---- diff ----

    /**
     * The tag a change opens its line with. Derived from the JSON {@code kind} rather than
     * spelled out per kind, so the listing and {@code --json} cannot come to disagree.
     */
    static String tag(BreakingChange change) {
        return change.kind().tag.replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    /**
     * The {@code (descriptor changed? now: ...)} hint, as a continuation line indented to the
     * name column. The indent is derived from TAG_WIDTH, so it cannot drift.
     */
    private static String replacementHint(List<Integer> replacements) {
        if (replacements.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n");
        out.append(" ".repeat(TAG_WIDTH)).append(" (descriptor changed? now: ");
        for (int i = 0; i < replacements.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(Intern.str(replacements.get(i)));
        }
        return out.append(')').toString();
    }

    private static String memberSubject(BreakingChange c) {
        return Intern.str(c.className()) + "." + Intern.str(c.name()) + " " + Intern.str(c.descriptor());
    }

    private static String narrowing(BreakingChange c) {
        return " (" + c.from().text + " -> " + c.to().text + ")";
    }

    static String diffText(List<BreakingChange> changes) {
        StringBuilder out = new StringBuilder();
        int classes = 0;
        int methods = 0;
        int fields = 0;
        for (BreakingChange c : changes) {
            // Only the subject varies per arm. The tag comes from the kind, and the one append
            // below owns the column, so no arm can invent its own.
            String subject =
                    switch (c.kind()) {
                        case CLASS_REMOVED,
                                CLASS_BECAME_FINAL,
                                CLASS_BECAME_SEALED,
                                CLASS_BECAME_ABSTRACT,
                                CLASS_BECAME_INTERFACE,
                                INTERFACE_BECAME_CLASS -> {
                            classes++;
                            yield Intern.str(c.className());
                        }
                        case CLASS_ACCESS_NARROWED -> {
                            classes++;
                            yield Intern.str(c.className()) + narrowing(c);
                        }
                        case METHOD_REMOVED -> {
                            methods++;
                            yield memberSubject(c) + replacementHint(c.replacementDescriptors());
                        }
                        case FIELD_REMOVED -> {
                            fields++;
                            yield memberSubject(c) + replacementHint(c.replacementDescriptors());
                        }
                        case METHOD_ACCESS_NARROWED -> {
                            methods++;
                            yield memberSubject(c) + narrowing(c);
                        }
                        case FIELD_ACCESS_NARROWED -> {
                            fields++;
                            yield memberSubject(c) + narrowing(c);
                        }
                        case METHOD_BECAME_ABSTRACT, METHOD_BECAME_STATIC, METHOD_BECAME_INSTANCE, METHOD_BECAME_FINAL -> {
                            methods++;
                            yield memberSubject(c);
                        }
                        case FIELD_BECAME_STATIC, FIELD_BECAME_INSTANCE, FIELD_BECAME_FINAL -> {
                            fields++;
                            yield memberSubject(c);
                        }
                    };
            String tag = tag(c);
            out.append(tag);
            for (int i = tag.length(); i < TAG_WIDTH; i++) {
                out.append(' ');
            }
            out.append(' ').append(subject).append('\n');
        }
        out.append("\nbreaking changes: ")
                .append(changes.size())
                .append(" (classes: ")
                .append(classes)
                .append(", methods: ")
                .append(methods)
                .append(", fields: ")
                .append(fields)
                .append(")\n");
        return out.toString();
    }

    static String diffJson(List<BreakingChange> changes) {
        StringBuilder out = new StringBuilder();
        Json.Writer json = new Json.Writer(out, true);
        json.beginObject();
        json.key("breaking_changes").beginArray();
        for (BreakingChange c : changes) {
            writeChange(json, c);
        }
        json.endArray();
        json.key("total").value(changes.size());
        json.endObject();
        return out.toString();
    }

    /** Internally tagged. {@code kind} comes first, then the fields the kind declares, in order. */
    private static void writeChange(Json.Writer json, BreakingChange c) {
        BreakingChange.Kind kind = c.kind();
        json.beginObject();
        json.key("kind").value(kind.tag);
        json.key("class").sym(c.className());
        if (kind.hasMember()) {
            json.key("name").sym(c.name());
            json.key("descriptor").sym(c.descriptor());
        }
        if (kind.isRemoval()) {
            json.key("replacement_descriptors").beginArray();
            for (int descriptor : c.replacementDescriptors()) {
                json.sym(descriptor);
            }
            json.endArray();
        }
        if (kind.isNarrowing()) {
            json.key("from").value(c.from().text);
            json.key("to").value(c.to().text);
        }
        json.endObject();
    }

    // ---- naming ----

    /**
     * "com/google/Foo$Bar" to "com.google.Foo$Bar". {@code $} is kept. It is part of the
     * class's binary name and dotting it would fabricate a nesting that does not exist for
     * Foo$1. Evidence names classes in drafted reasons and the two must agree.
     */
    static String dotted(String name) {
        return name.replace('/', '.');
    }

    /** Last segment of an internal class name. */
    private static String simple(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }

    /**
     * @param name Java-ish simple name
     * @param used chars consumed
     */
    record ParsedType(String name, int used) {}

    /**
     * One JVM type descriptor, or null on malformed input. Array dimensions are counted in a
     * loop with the JVMS 4.3.2 cap of 255, never by recursion. A corrupt descriptor can carry
     * a 64KB run of '[' and a recursive parse would overflow the stack mid-report.
     */
    static ParsedType parseType(String s) {
        int dims = 0;
        while (dims < s.length() && s.charAt(dims) == '[') {
            dims++;
        }
        if (dims > 255 || dims == s.length()) {
            return null;
        }
        String base;
        int used = 1;
        switch (s.charAt(dims)) {
            case 'B' -> base = "byte";
            case 'C' -> base = "char";
            case 'D' -> base = "double";
            case 'F' -> base = "float";
            case 'I' -> base = "int";
            case 'J' -> base = "long";
            case 'S' -> base = "short";
            case 'Z' -> base = "boolean";
            case 'V' -> base = "void";
            case 'L' -> {
                int end = s.indexOf(';', dims);
                if (end < 0) {
                    return null;
                }
                base = simple(s.substring(dims + 1, end));
                used = end - dims + 1;
            }
            default -> {
                return null;
            }
        }
        return new ParsedType(base + "[]".repeat(dims), dims + used);
    }

    /**
     * Parameter list of a method descriptor as Java-ish simple names, for example
     * "Callable, long, TimeUnit, boolean". Null if the descriptor does not parse.
     */
    private static String prettyParams(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0) {
            return null;
        }
        String rest = descriptor.substring(1, close);
        StringBuilder params = new StringBuilder();
        while (!rest.isEmpty()) {
            ParsedType type = parseType(rest);
            if (type == null) {
                return null;
            }
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append(type.name());
            rest = rest.substring(type.used());
        }
        return params.toString();
    }

    /**
     * A member as a Java-ish signature. Methods read "owner.name(params)", {@code <init>} reads
     * "owner constructor (params)" and fields read "owner.name: type". A descriptor that does
     * not parse falls back to the raw form.
     */
    private static String prettyMember(String owner, String name, String descriptor) {
        String dottedOwner = dotted(owner);
        String raw = dottedOwner + "." + name + " " + descriptor;
        if (descriptor.startsWith("(")) {
            String params = prettyParams(descriptor);
            if (params == null) {
                return raw;
            }
            if (name.equals("<init>")) {
                return dottedOwner + " constructor (" + params + ")";
            }
            return dottedOwner + "." + name + "(" + params + ")";
        }
        ParsedType type = parseType(descriptor);
        return type == null ? raw : dottedOwner + "." + name + ": " + type.name();
    }

    private static String memberName(Violation v) {
        return Intern.str(MemberKey.name(v.reference.member()));
    }

    private static String memberDescriptor(Violation v) {
        return Intern.str(MemberKey.descriptor(v.reference.member()));
    }

    /** The referenced symbol as shown on a violation line. */
    private static String prettyTarget(Violation v) {
        String owner = Intern.str(v.reference.owner());
        if (!v.reference.hasMember()) {
            return dotted(owner);
        }
        return prettyMember(owner, memberName(v), memberDescriptor(v));
    }

    /**
     * The referenced symbol with the raw member name and descriptor. The pretty form is lossy,
     * parameter packages and the return type are erased. So this is the identity distinct
     * symbols are grouped by, and the display fallback when two of them would otherwise
     * pretty-print alike.
     */
    private static String rawTarget(Violation v) {
        String owner = dotted(Intern.str(v.reference.owner()));
        if (!v.reference.hasMember()) {
            return owner;
        }
        return owner + "." + memberName(v) + " " + memberDescriptor(v);
    }

    private static boolean isConstructor(Violation v) {
        return v.reference.hasMember() && memberName(v).equals("<init>");
    }

    /** The reason as printed. "method ..." reads as "constructor ..." for {@code <init>}. */
    private static String displayReason(Violation v) {
        if (isConstructor(v)) {
            if (v.reason == Reason.METHOD_REMOVED) {
                return "constructor removed";
            }
            if (v.reason == Reason.METHOD_ACCESS_NARROWED) {
                return "constructor access narrowed";
            }
        }
        return v.reason.text;
    }

    /**
     * The error the JVM raises for a reference-style violation and when. It is the string a
     * reader greps production logs for. The switch has no default so a new reason fails here
     * at compile time. The structural reasons return null because they render through
     * structuralLines, which carries its own error line.
     */
    private static String runtimeError(Violation v) {
        boolean ctor = isConstructor(v);
        return switch (v.reason) {
            case CLASS_REMOVED -> "NoClassDefFoundError at first use";
            case CLASS_ACCESS_NARROWED -> "IllegalAccessError at first use";
            case CLASS_BECAME_ABSTRACT -> "InstantiationError at first `new`";
            case CLASS_BECAME_INTERFACE, INTERFACE_BECAME_CLASS -> "IncompatibleClassChangeError at first call";
            case METHOD_REMOVED -> ctor ? "NoSuchMethodError at first `new`" : "NoSuchMethodError at first call";
            case FIELD_REMOVED -> "NoSuchFieldError at first access";
            case METHOD_ACCESS_NARROWED -> ctor ? "IllegalAccessError at first `new`" : "IllegalAccessError at first call";
            case FIELD_ACCESS_NARROWED -> "IllegalAccessError at first access";
            case FIELD_BECAME_FINAL -> "IllegalAccessError at first write";
            case METHOD_BECAME_STATIC, METHOD_BECAME_INSTANCE -> "IncompatibleClassChangeError at first call";
            case FIELD_BECAME_STATIC, FIELD_BECAME_INSTANCE -> "IncompatibleClassChangeError at first access";
            case CLASS_BECAME_FINAL,
                    CLASS_BECAME_SEALED,
                    METHOD_BECAME_FINAL,
                    EXTENDS_FINAL_CLASS,
                    METHOD_BECAME_ABSTRACT,
                    CONFLICTING_DEFAULT_METHODS,
                    SERVICE_PROVIDER_REMOVED,
                    SERVICE_PROVIDER_NOT_INSTANTIABLE -> null;
        };
    }

    /**
     * JAR paths shrink to the file name (the full path stays in the JSON output). Directories
     * keep the full display string, whose basename alone (e.g. "main") says nothing.
     */
    private static String sourceDisplay(String source) {
        if (!source.endsWith(".jar")) {
            return source;
        }
        return source.substring(Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\')) + 1);
    }

    /**
     * Graph-walk violations. The broken thing is the scanned class itself (its hierarchy no
     * longer loads or selects), so they read consumer-first, unlike reference violations which
     * group by the symbol that changed. The two kind flips exist in both worlds, and the
     * graph-walk variant is the member-less Class edge.
     */
    private static boolean isStructural(Violation v) {
        return switch (v.reason) {
            case CLASS_BECAME_FINAL,
                    CLASS_BECAME_SEALED,
                    METHOD_BECAME_FINAL,
                    EXTENDS_FINAL_CLASS,
                    METHOD_BECAME_ABSTRACT,
                    CONFLICTING_DEFAULT_METHODS,
                    SERVICE_PROVIDER_REMOVED,
                    SERVICE_PROVIDER_NOT_INSTANTIABLE -> true;
            case CLASS_BECAME_INTERFACE, INTERFACE_BECAME_CLASS -> !v.reference.hasMember();
            case CLASS_REMOVED,
                    CLASS_ACCESS_NARROWED,
                    CLASS_BECAME_ABSTRACT,
                    METHOD_REMOVED,
                    METHOD_ACCESS_NARROWED,
                    FIELD_REMOVED,
                    FIELD_ACCESS_NARROWED,
                    FIELD_BECAME_FINAL,
                    METHOD_BECAME_STATIC,
                    METHOD_BECAME_INSTANCE,
                    FIELD_BECAME_STATIC,
                    FIELD_BECAME_INSTANCE -> false;
        };
    }

    /**
     * @param what what happened
     * @param error what the JVM does about it
     */
    record StructuralLines(String what, String error) {}

    static StructuralLines structuralLines(Violation v) {
        return structuralLines(v, UnaryOperator.identity());
    }

    /** @param code how the renderer sets the class and member names apart from the prose */
    private static StructuralLines structuralLines(Violation v, UnaryOperator<String> code) {
        String owner = code.apply(dotted(Intern.str(v.reference.owner())));
        String loading = code.apply(simple(Intern.str(v.sourceClass)));
        String target = code.apply(prettyTarget(v));
        String member = v.reference.hasMember() ? code.apply(memberName(v)) : null;
        // Which error fires depends on the JVM rather than on the violation, and the report
        // cannot know which JVM will run the code, so it names both. IncompatibleClassChangeError
        // from JDK 16 and VerifyError up to 15, confirmed on real JVMs from 11 to 25 for all
        // three reasons that share this string (class became final, method became final, extends
        // final class). The kind flips do not split this way and keep their own icceOnLoad.
        String loads = "throws IncompatibleClassChangeError (VerifyError up to JDK 15) when " + loading + " loads";
        String icceOnLoad = "throws IncompatibleClassChangeError when " + loading + " loads";
        // The graph walks always attach the member to the three member reasons. A member-less
        // one would be malformed, so it degrades readably. The non-structural reasons render
        // through referenceBlocks and land here only by mistake. They are listed rather than
        // defaulted so a new reason must choose its structural rendering at compile time.
        StructuralLines degraded = new StructuralLines(v.reason.text + ": " + owner, loads);
        boolean hasMember = v.reference.hasMember();
        boolean noInvocation = Boolean.FALSE.equals(v.invocationFound);
        return switch (v.reason) {
            // Which error the JVM picks depends on the call site, so both are named.
            // invokevirtual on the class throws IncompatibleClassChangeError ("Conflicting
            // default methods"), invokeinterface on either interface throws AbstractMethodError.
            case CONFLICTING_DEFAULT_METHODS -> !hasMember
                    ? degraded
                    : new StructuralLines(
                            "inherits " + target + " as a default from two unrelated interfaces",
                            noInvocation
                                    ? "throws IncompatibleClassChangeError or AbstractMethodError only when "
                                            + member
                                            + " is first called (no invocation found in scanned bytecode)"
                                    : "throws IncompatibleClassChangeError or AbstractMethodError when "
                                            + member
                                            + " is called");
            case METHOD_BECAME_ABSTRACT -> !hasMember
                    ? degraded
                    : new StructuralLines(
                            "inherits abstract " + target + " without implementing it",
                            noInvocation
                                    ? "throws AbstractMethodError only when "
                                            + member
                                            + " is first called (no invocation found in scanned bytecode)"
                                    : "throws AbstractMethodError when " + member + " is called");
            case METHOD_BECAME_FINAL -> !hasMember
                    ? degraded
                    : new StructuralLines("overrides " + target + ", which became final", loads);
            case CLASS_BECAME_FINAL -> new StructuralLines("extends " + owner + ", which became final", loads);
            // Cannot name the edge, for the same reason INTERFACE_BECAME_CLASS cannot.
            case CLASS_BECAME_SEALED -> new StructuralLines(
                    "extends or implements " + owner + ", which is now sealed without permitting it", icceOnLoad);
            case EXTENDS_FINAL_CLASS -> new StructuralLines(
                    "extends " + owner + ", which is final on the runtime classpath", loads);
            // A class that became an interface can only have been reached over the superclass
            // edge, so that one names the edge. The other side cannot. An interface's
            // superinterfaces sit in the same interfaces array a class's implements does, and
            // the graph keeps no access flags to tell an extends from an implements.
            case CLASS_BECAME_INTERFACE -> new StructuralLines("extends " + owner + ", which became an interface", icceOnLoad);
            case INTERFACE_BECAME_CLASS -> new StructuralLines(
                    "extends or implements " + owner + ", which became a class", icceOnLoad);
            // Not class-load breaks. ServiceLoader wraps the reflective failure itself, so
            // neither is a LinkageError. Rendered here rather than in referenceBlocks because
            // no constant-pool reference exists behind them.
            case SERVICE_PROVIDER_REMOVED -> new StructuralLines(
                    "is still registered in this jar's META-INF/services as a provider for "
                            + owner
                            + ", but the class is gone",
                    "throws ServiceConfigurationError (provider not found) when ServiceLoader loads it");
            case SERVICE_PROVIDER_NOT_INSTANTIABLE -> new StructuralLines(
                    "is registered in META-INF/services as a provider for "
                            + owner
                            + ", but is no longer a public concrete subtype of it with a public no-arg constructor",
                    "throws ServiceConfigurationError (not a subtype, or provider could not be instantiated) when ServiceLoader loads it");
            case CLASS_REMOVED,
                    CLASS_ACCESS_NARROWED,
                    CLASS_BECAME_ABSTRACT,
                    METHOD_REMOVED,
                    METHOD_ACCESS_NARROWED,
                    FIELD_REMOVED,
                    FIELD_ACCESS_NARROWED,
                    FIELD_BECAME_FINAL,
                    METHOD_BECAME_STATIC,
                    METHOD_BECAME_INSTANCE,
                    FIELD_BECAME_STATIC,
                    FIELD_BECAME_INSTANCE -> degraded;
        };
    }

    /** Per-module upgrade-check attribution, appended to the line a violation prints on. */
    private static String modulesSuffix(Violation v) {
        return v.modules.isEmpty() ? "" : " [" + String.join(", ", v.modules) + "]";
    }

    /**
     * Runtime load evidence (--class-load-log), appended to the line naming the referencing
     * class. When the log carried a cause stack it names the frame that pulled the class in,
     * typically the reflective path the static walk could not see.
     */
    private static String observedSuffix(Violation v, UnaryOperator<String> code) {
        if (!v.observedLoading) {
            return "";
        }
        if (v.loadTrigger == null) {
            return "  ⚡ observed loading at runtime";
        }
        return "  ⚡ observed loading at runtime (via " + code.apply(v.loadTrigger) + ")";
    }

    // ---- blocks ----

    /**
     * How a renderer sets a name (class, member, coordinate, module, jar) apart from the prose
     * around it. Text leaves it alone. Markdown wraps it as code, which also keeps a {@code
     * <init>}, a {@code $} or a {@code [} from being read as markup.
     */
    enum Style {
        TEXT,
        MARKDOWN;

        /** A name in a block body. */
        String code(String s) {
            return this == TEXT ? s : codeSpan(s);
        }

        /**
         * A name in a block title. Markdown puts the title inside a {@code <summary>} tag, an
         * HTML block where a backtick span would not render, so only an HTML tag works there.
         */
        String titleCode(String s) {
            return this == TEXT ? s : "<code>" + escapeHtml(s) + "</code>";
        }

        /** Prose in a block title that is not a name, which an HTML block still needs escaped. */
        String titleText(String s) {
            return this == TEXT ? s : escapeHtml(s);
        }
    }

    /** A CommonMark code span: the fence is one backtick longer than any run inside. */
    static String codeSpan(String s) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            run = s.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        // A span may not start or end with its own fence character, so one space each side,
        // which the renderer strips again.
        boolean pad = s.startsWith("`") || s.endsWith("`");
        return pad ? fence + " " + s + " " + fence : fence + s + fence;
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * One group of violations as both renderers lay it out: a title line, then detail lines
     * about the group as a whole and entries under them.
     *
     * @param depth 1 for a line about the whole block, 2 for one entry under it
     */
    record Line(int depth, String text) {}

    record Block(String title, List<Line> lines) {}

    /** The text layout: the title, then every line indented four spaces per depth. */
    private static String textBlock(Block block) {
        StringBuilder out = new StringBuilder(block.title()).append('\n');
        for (Line line : block.lines()) {
            out.append("    ".repeat(line.depth())).append(line.text()).append('\n');
        }
        return trimEnd(out);
    }

    /**
     * The Markdown layout: a {@code <details>} with the title as its summary, depth-1 lines as
     * paragraphs and depth-2 lines as list items. The blank line after the summary is what
     * makes GitHub render the body as Markdown rather than as the rest of the HTML block.
     */
    private static String markdownBlock(Block block, boolean open) {
        StringBuilder out = new StringBuilder(open ? "<details open>\n" : "<details>\n");
        out.append("<summary>").append(block.title()).append("</summary>\n\n");
        List<Line> lines = block.lines();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.depth() == 1) {
                out.append(line.text()).append("\n\n");
                continue;
            }
            out.append("- ").append(line.text()).append('\n');
            // A list ends where its entries do, so the next paragraph or the closing tag does
            // not join it.
            boolean last = i == lines.size() - 1;
            if (last || lines.get(i + 1).depth() == 1) {
                out.append('\n');
            }
        }
        return out.append("</details>").toString();
    }

    private static List<String> textBlocks(List<Violation> violations) {
        List<String> out = new ArrayList<>();
        for (Block block : violationBlocks(violations, Style.TEXT)) {
            out.add(textBlock(block));
        }
        return out;
    }

    private static List<String> markdownBlocks(List<Violation> violations, boolean open) {
        List<String> out = new ArrayList<>();
        for (Block block : violationBlocks(violations, Style.MARKDOWN)) {
            out.add(markdownBlock(block, open));
        }
        return out;
    }

    private static String joinCode(Iterable<String> names, UnaryOperator<String> code) {
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(code.apply(name));
        }
        return out.toString();
    }

    /**
     * Rust's {@code trim_end} on a finished block. Every block opens with its marker, so
     * trimming both ends removes the same characters.
     */
    private static String trimEnd(StringBuilder block) {
        return Reach.trim(block.toString());
    }

    /**
     * Groups a set of violations into blocks. upgrade-check violations carry a suggestion and
     * group by the fix. The rest split by shape. Reference violations group by the broken
     * symbol (the unit a fix targets), structural graph-walk violations by the scanned class
     * that no longer loads.
     */
    private static List<Block> violationBlocks(List<Violation> violations, Style style) {
        List<Violation> withSuggestion = new ArrayList<>();
        List<Violation> structural = new ArrayList<>();
        List<Violation> reference = new ArrayList<>();
        for (Violation v : violations) {
            if (v.suggestion != null) {
                withSuggestion.add(v);
            } else if (isStructural(v)) {
                structural.add(v);
            } else {
                reference.add(v);
            }
        }
        List<Block> blocks = suggestionBlocks(withSuggestion, style);
        blocks.addAll(referenceBlocks(reference, style));
        blocks.addAll(structuralBlocks(structural, style));
        return blocks;
    }

    private record WhyKey(String advice, String removedBy, String before, String after, String referencedBy) {
        static final Comparator<WhyKey> ORDER = Comparator.comparing(WhyKey::advice, UTF8_NULL_FIRST)
                .thenComparing(WhyKey::removedBy, UTF8_NULL_FIRST)
                .thenComparing(WhyKey::before, UTF8_NULL_FIRST)
                .thenComparing(WhyKey::after, UTF8_NULL_FIRST)
                .thenComparing(WhyKey::referencedBy, UTF8_NULL_FIRST);
    }

    /**
     * One 💡 block per distinct fix. Groups key on the advice TOGETHER WITH every field the
     * "why:" line quotes. Advice alone does not pin them. The removed-coordinate advice embeds
     * no versions and the changed-coordinate advice embeds only the moved delta, so per-module
     * runs over different resolved version lists can produce byte-identical advice. Keying on
     * all quoted fields keeps every why-line true for each reference under it. The block reads
     * as prose, so the advice never looks like the thing the labels below it accuse.
     * References are deduplicated by (sentence, raw symbol) and ordered by sentence text.
     */
    private static List<Block> suggestionBlocks(List<Violation> violations, Style style) {
        UnaryOperator<String> code = style::code;
        TreeMap<WhyKey, List<Violation>> grouped = new TreeMap<>(WhyKey.ORDER);
        for (Violation v : violations) {
            Suggestion s = v.suggestion;
            grouped.computeIfAbsent(
                            new WhyKey(s.advice(), s.removedBy(), s.before(), s.after(), s.referencedBy()),
                            k -> new ArrayList<>())
                    .add(v);
        }
        List<Block> blocks = new ArrayList<>();
        for (List<Violation> vs : grouped.values()) {
            List<Line> lines = new ArrayList<>();
            Suggestion s = vs.get(0).suggestion;
            // The modules whose classpaths exhibit this group, from per-module upgrade-check.
            TreeSet<String> modules = new TreeSet<>(UTF8);
            for (Violation v : vs) {
                modules.addAll(v.modules);
            }
            if (!modules.isEmpty()) {
                lines.add(new Line(1, "affected modules: " + joinCode(modules, code)));
            }
            StringBuilder why = new StringBuilder("why: ")
                    .append(code.apply(s.removedBy()))
                    .append(" changed ")
                    .append(s.before())
                    .append(" -> ")
                    .append(s.after());
            if (s.referencedBy() != null) {
                why.append(", which breaks ").append(code.apply(s.referencedBy()));
            }
            lines.add(new Line(1, why.append(':').toString()));
            // A sentence names the pretty target, which can collide for distinct symbols. A
            // sentence spanning several raw targets is re-rendered with the raw form so every
            // break stays visible. Identical (sentence, symbol) pairs still print once. That
            // is the same break surfaced by several module runs.
            TreeMap<String, TreeMap<String, Violation>> bySentence = new TreeMap<>(UTF8);
            for (Violation v : vs) {
                bySentence
                        .computeIfAbsent(suggestionLine(v, code.apply(prettyTarget(v)), code), k -> new TreeMap<>(UTF8))
                        .putIfAbsent(rawTarget(v), v);
            }
            TreeSet<String> sentences = new TreeSet<>(UTF8);
            for (Map.Entry<String, TreeMap<String, Violation>> e : bySentence.entrySet()) {
                if (e.getValue().size() == 1) {
                    sentences.add(e.getKey());
                } else {
                    for (Map.Entry<String, Violation> raw : e.getValue().entrySet()) {
                        sentences.add(suggestionLine(raw.getValue(), code.apply(raw.getKey()), code));
                    }
                }
            }
            for (String sentence : sentences) {
                lines.add(new Line(2, sentence));
            }
            blocks.add(new Block("💡 suggestion: " + style.titleText(s.advice()), lines));
        }
        return blocks;
    }

    /**
     * One reference in a 💡 block as an English sentence. It says what the library change did,
     * and what the consumer still does that no longer works. {@code target} is the rendered
     * symbol (pretty normally, raw when the caller detected a pretty collision), already set
     * apart the way {@code code} does. The switch has no default, so a new reason must pick
     * its phrasing here at compile time.
     */
    private static String suggestionLine(Violation v, String target, UnaryOperator<String> code) {
        String cls = code.apply(dotted(Intern.str(v.sourceClass)));
        String owner = code.apply(dotted(Intern.str(v.reference.owner())));
        boolean ctor = isConstructor(v);
        boolean hasMember = v.reference.hasMember();
        String accessVerb = v.reference.kind() == RefKind.FIELD ? "accesses" : "calls";
        String sentence =
                switch (v.reason) {
                    case CLASS_REMOVED -> target + " was removed, but " + cls + " still uses it";
                    case METHOD_REMOVED -> target + " was removed, but " + cls + (ctor ? " still instantiates it" : " still calls it");
                    case FIELD_REMOVED -> target + " was removed, but " + cls + " still accesses it";
                    case CLASS_ACCESS_NARROWED -> target + " is no longer accessible, but " + cls + " still uses it";
                    case METHOD_ACCESS_NARROWED -> target
                            + " is no longer accessible, but "
                            + cls
                            + (ctor ? " still instantiates it" : " still calls it");
                    case FIELD_ACCESS_NARROWED -> target + " is no longer accessible, but " + cls + " still accesses it";
                    case FIELD_BECAME_FINAL -> target + " became final, but " + cls + " still writes to it";
                    case CLASS_BECAME_ABSTRACT -> target + " became abstract, but " + cls + " still instantiates it";
                    // With a member the break is at a call site compiled against the old kind.
                    // Without one it is the hierarchy edge itself, which the reason names.
                    case CLASS_BECAME_INTERFACE -> hasMember
                            ? owner + " became an interface, but " + cls + " was compiled against it as a class"
                            : cls + " extends " + owner + ", which became an interface";
                    case INTERFACE_BECAME_CLASS -> hasMember
                            ? owner + " became a class, but " + cls + " was compiled against it as an interface"
                            : cls + " extends or implements " + owner + ", which became a class";
                    case METHOD_BECAME_INSTANCE, FIELD_BECAME_INSTANCE -> target
                            + " became an instance member, but "
                            + cls
                            + " still "
                            + accessVerb
                            + " it as a static member";
                    case METHOD_BECAME_STATIC, FIELD_BECAME_STATIC -> target
                            + " became static, but "
                            + cls
                            + " still "
                            + accessVerb
                            + " it as an instance member";
                    case CLASS_BECAME_FINAL -> owner + " became final, but " + cls + " still extends it";
                    case CLASS_BECAME_SEALED -> owner
                            + " is now sealed and does not permit "
                            + cls
                            + ", which extends or implements it";
                    case METHOD_BECAME_FINAL -> target + " became final, but " + cls + " still overrides it";
                    case EXTENDS_FINAL_CLASS -> cls + " extends " + owner + ", which is final on the runtime classpath";
                    case METHOD_BECAME_ABSTRACT -> target + " became abstract, but " + cls + " does not implement it";
                    case CONFLICTING_DEFAULT_METHODS -> target
                            + " is now a default that "
                            + cls
                            + " inherits from two unrelated interfaces";
                    case SERVICE_PROVIDER_REMOVED -> cls
                            + " was removed, but is still registered as a META-INF/services provider for "
                            + owner;
                    case SERVICE_PROVIDER_NOT_INSTANTIABLE -> cls
                            + " can no longer be instantiated by ServiceLoader, but is still registered as a provider for "
                            + owner;
                };
        return sentence + observedSuffix(v, code);
    }

    private record SymbolKey(String pretty, String reason, String raw) {
        static final Comparator<SymbolKey> ORDER = Comparator.comparing(SymbolKey::pretty, UTF8)
                .thenComparing(SymbolKey::reason, UTF8)
                .thenComparing(SymbolKey::raw, UTF8);
    }

    private record User(String className, String source, String modules, String observed) {
        static final Comparator<User> ORDER = Comparator.comparing(User::className, UTF8)
                .thenComparing(User::source, UTF8)
                .thenComparing(User::modules, UTF8)
                .thenComparing(User::observed, UTF8);
    }

    /**
     * One ❌ block per broken symbol. The symbol is the heading, then the reason with the
     * runtime error it causes, then every referencing class. Grouping is by the RAW symbol
     * identity, because two distinct removed overloads can pretty-print alike. The heading
     * stays pretty unless it would collide with another block's. Then it degrades to the raw
     * form so both symbols stay distinguishable and the block count matches the summary's
     * broken count.
     */
    private static List<Block> referenceBlocks(List<Violation> violations, Style style) {
        UnaryOperator<String> code = style::code;
        TreeMap<SymbolKey, List<Violation>> grouped = new TreeMap<>(SymbolKey.ORDER);
        for (Violation v : violations) {
            grouped.computeIfAbsent(new SymbolKey(prettyTarget(v), v.reason.text, rawTarget(v)), k -> new ArrayList<>())
                    .add(v);
        }
        Map<List<String>, Integer> prettyCounts = new HashMap<>();
        for (SymbolKey key : grouped.keySet()) {
            prettyCounts.merge(List.of(key.pretty(), key.reason()), 1, Integer::sum);
        }
        List<Block> blocks = new ArrayList<>();
        for (Map.Entry<SymbolKey, List<Violation>> e : grouped.entrySet()) {
            SymbolKey key = e.getKey();
            List<Violation> vs = e.getValue();
            boolean ambiguous = prettyCounts.get(List.of(key.pretty(), key.reason())) > 1;
            List<Line> lines = new ArrayList<>();
            Violation first = vs.get(0);
            // Never null here: the reasons without a runtime error are the structural ones,
            // which violationBlocks routes to structuralBlocks.
            lines.add(new Line(1, displayReason(first) + ", throws " + runtimeError(first)));
            TreeSet<User> users = new TreeSet<>(User.ORDER);
            for (Violation v : vs) {
                users.add(new User(
                        dotted(Intern.str(v.sourceClass)),
                        sourceDisplay(Intern.str(v.source)),
                        modulesSuffix(v),
                        observedSuffix(v, code)));
            }
            lines.add(new Line(1, "used by " + users.size() + " class" + (users.size() == 1 ? "" : "es") + ":"));
            for (User user : users) {
                lines.add(new Line(2, code.apply(user.className()) + "  (" + user.source() + ')' + user.modules() + user.observed()));
            }
            blocks.add(new Block("❌ " + style.titleCode(ambiguous ? key.raw() : key.pretty()), lines));
        }
        return blocks;
    }

    private record ConsumerKey(String className, String source) {
        static final Comparator<ConsumerKey> ORDER =
                Comparator.comparing(ConsumerKey::className, UTF8).thenComparing(ConsumerKey::source, UTF8);
    }

    private record Broken(String pretty, Violation violation) {
        static final Comparator<Broken> ORDER = Comparator.comparing(Broken::pretty, UTF8)
                .thenComparing(broken -> broken.violation().reason.text, UTF8);
    }

    /**
     * One ❌ block per scanned class whose own shape broke. Each entry pairs what happened
     * with the error the JVM raises for it. Grouped by the raw source path, because two jars
     * sharing a basename must not merge. Only the printed form is shortened.
     */
    private static List<Block> structuralBlocks(List<Violation> violations, Style style) {
        TreeMap<ConsumerKey, List<Violation>> grouped = new TreeMap<>(ConsumerKey.ORDER);
        for (Violation v : violations) {
            grouped.computeIfAbsent(
                            new ConsumerKey(dotted(Intern.str(v.sourceClass)), Intern.str(v.source)), k -> new ArrayList<>())
                    .add(v);
        }
        List<Block> blocks = new ArrayList<>();
        for (Map.Entry<ConsumerKey, List<Violation>> e : grouped.entrySet()) {
            List<Violation> vs = e.getValue();
            // One class per block, so the observed marker is a block-level fact. Any violation
            // of the group carries the same evidence.
            String title = "❌ " + style.titleCode(e.getKey().className()) + "  (" + style.titleText(sourceDisplay(e.getKey().source()))
                    + ')' + observedSuffix(vs.get(0), style::titleCode);
            List<Broken> entries = new ArrayList<>(vs.size());
            for (Violation v : vs) {
                entries.add(new Broken(prettyTarget(v), v));
            }
            // List.sort is stable, like the Rust sort it replaces.
            entries.sort(Broken.ORDER);
            List<Line> lines = new ArrayList<>();
            for (Broken entry : entries) {
                Violation v = entry.violation();
                StructuralLines what = structuralLines(v, style::code);
                lines.add(new Line(1, what.what() + modulesSuffix(v)));
                lines.add(new Line(2, what.error()));
            }
            blocks.add(new Block(title, lines));
        }
        return blocks;
    }

    // ---- check ----

    /** The bottom line, totals plus per-tier counts. ✅ marks a clean run. */
    private static String summaryLine(Check.Report report, int reachable, int latent, int unproven) {
        int broken = report.violations.size();
        StringBuilder line = new StringBuilder();
        if (broken == 0) {
            line.append("✅ scanned ").append(report.scannedClasses).append(" classes: 0 broken");
        } else {
            line.append("scanned ")
                    .append(report.scannedClasses)
                    .append(" classes: ❌ ")
                    .append(broken)
                    .append(" broken");
        }
        if (report.reachabilityComputed && broken > 0) {
            line.append(" (of which 💥 ").append(reachable).append(" reachable");
            if (latent > 0) {
                line.append(", 💤 ").append(latent).append(" latent");
            }
            line.append(", ⚠️ ").append(unproven).append(" not proven reachable)");
        } else if (latent > 0) {
            line.append(" (of which 💤 ").append(latent).append(" latent)");
        }
        // Runtime load evidence (--class-load-log). The count stays out of evidence-less runs.
        int observed = 0;
        for (Violation v : report.violations) {
            if (v.observedLoading) {
                observed++;
            }
        }
        if (observed > 0) {
            line.append(", ⚡ ").append(observed).append(" observed loading at runtime");
        }
        if (report.unknownRefs > 0) {
            line.append(", ❓ ")
                    .append(report.unknownRefs)
                    .append(" unverified reference")
                    .append(report.unknownRefs == 1 ? "" : "s")
                    .append(" (hierarchy escapes the analyzed scope)");
        }
        if (report.suppressed > 0) {
            line.append(", ").append(report.suppressed).append(" suppressed by --exclude-file");
        }
        return line.toString();
    }

    private static String names(List<String> paths, UnaryOperator<String> code) {
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(code.apply(sourceDisplay(path)));
        }
        return out.toString();
    }

    private static String targetsSuffix(int targets) {
        return " against " + targets + " scan target" + (targets == 1 ? "" : "s");
    }

    /**
     * One-line context header for plain {@code check}. It names the library pair compared and
     * how many scan targets it was checked against. The count is the admitted one ({@code
     * Check.Report.scanTargets}), so it agrees with any skip warnings above it. Paths are named
     * by the same rule as the violation lines. upgrade-check prints its dependency-change
     * header instead.
     */
    static String checkHeader(List<String> oldPaths, List<String> newPaths, int targets) {
        return checkHeaderLine(oldPaths, newPaths, targets, UnaryOperator.identity()) + "\n\n";
    }

    private static String checkHeaderLine(List<String> oldPaths, List<String> newPaths, int targets, UnaryOperator<String> code) {
        return "checked " + names(oldPaths, code) + " -> " + names(newPaths, code) + targetsSuffix(targets);
    }

    /** Header for a JDK-pair check, where the compared sides are releases rather than JARs. */
    static String checkHeaderJdk(int oldRelease, int newRelease, int targets) {
        return checkHeaderJdkLine(oldRelease, newRelease, targets) + "\n\n";
    }

    private static String checkHeaderJdkLine(int oldRelease, int newRelease, int targets) {
        return "checked JDK " + oldRelease + " -> JDK " + newRelease + targetsSuffix(targets);
    }

    private static final String RULE = "--------------------------------------------------------------------------------";

    /**
     * A reachability-tier title sandwiched in 80-column rules, so the section boundary stands
     * out when scrolling a long report.
     */
    private static String section(String title, List<Violation> violations) {
        return RULE + "\n" + title + "\n" + RULE + "\n\n" + String.join("\n\n", textBlocks(violations));
    }

    /** A reachability-tier heading with one collapsible block per finding under it. */
    private static String markdownSection(String title, List<Violation> violations, boolean open) {
        return "#### " + title + "\n\n" + String.join("\n\n", markdownBlocks(violations, open));
    }

    private static final String BREAKS_HEADING = "💥 reachable from the application (likely to break)";
    private static final String UNPROVEN_HEADING =
            "⚠️  not proven reachable (no static path found; may still load via reflection)";
    // The 💤 headings for the two report shapes, kept side by side so their wording cannot
    // drift apart. The only difference is that the ranked report can also say the class is
    // reachable, which the flat report has no basis to claim.
    private static final String LATENT_HEADING = "💤 latent (no scanned code invokes the affected member)";
    private static final String LATENT_HEADING_RANKED =
            "💤 latent (class reachable, but no scanned code invokes the affected member)";

    /** The violations split by tier, most severe first. */
    private record Tiers(List<Violation> breaks, List<Violation> latent, List<Violation> unproven) {
        /** Same judgement the exit gate makes, so sections and --fail-on cannot drift. */
        static Tiers of(Check.Report report) {
            Tiers tiers = new Tiers(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            boolean axis = Tier.reachableAxisValid(report.appRootsMatched);
            for (Violation v : report.violations) {
                List<Violation> tier =
                        switch (Tier.of(v, axis)) {
                            case BREAKS -> tiers.breaks();
                            case LATENT -> tiers.latent();
                            case UNPROVEN -> tiers.unproven();
                        };
                tier.add(v);
            }
            return tiers;
        }

        /**
         * Without a reachability axis the report is one flat list, but latency is scan-derived
         * and keeps its section. {@code unproven} is always empty here. Chaining it survives
         * that changing.
         */
        List<Violation> flat() {
            List<Violation> flat = new ArrayList<>(breaks);
            flat.addAll(unproven);
            return flat;
        }
    }

    static String checkText(Check.Report report) {
        Tiers tiers = Tiers.of(report);
        List<String> sections = new ArrayList<>();
        if (report.reachabilityComputed) {
            if (!tiers.breaks().isEmpty()) {
                sections.add(section(BREAKS_HEADING, tiers.breaks()));
            }
            if (!tiers.latent().isEmpty()) {
                sections.add(section(LATENT_HEADING_RANKED, tiers.latent()));
            }
            if (!tiers.unproven().isEmpty()) {
                sections.add(section(UNPROVEN_HEADING, tiers.unproven()));
            }
        } else if (!report.violations.isEmpty()) {
            List<Violation> flat = tiers.flat();
            if (!flat.isEmpty()) {
                sections.add(String.join("\n\n", textBlocks(flat)));
            }
            if (!tiers.latent().isEmpty()) {
                sections.add(section(LATENT_HEADING, tiers.latent()));
            }
        }
        sections.add(summaryLine(report, tiers.breaks().size(), tiers.latent().size(), tiers.unproven().size()));
        return String.join("\n\n", sections) + "\n";
    }

    // ---- markdown ----

    /**
     * Opens every Markdown report, so a workflow that posts the file as a pull request comment
     * can find the comment it posted last time and replace it.
     */
    static final String MARKDOWN_MARKER = "<!-- uika -->";

    /**
     * The verdict, as the first line a reader of a comment sees. Reachability and latency
     * counts follow the summary line's rules; the rest of its numbers stay down there.
     *
     * @param report null when no check ran
     */
    private static String headline(Check.Report report, Tiers tiers) {
        if (report == null) {
            return "### ✅ uika: nothing to check";
        }
        int broken = report.violations.size();
        if (broken == 0) {
            return "### ✅ uika: no broken references";
        }
        StringBuilder line = new StringBuilder("### ❌ uika: ")
                .append(broken)
                .append(" broken reference")
                .append(broken == 1 ? "" : "s");
        // Only the tiers that hold something. Without a reachability axis the breaks tier is
        // everything not latent, which is no claim about reachability.
        List<String> counts = new ArrayList<>();
        if (report.reachabilityComputed && !tiers.breaks().isEmpty()) {
            counts.add("💥 " + tiers.breaks().size() + " reachable");
        }
        if (!tiers.latent().isEmpty()) {
            counts.add("💤 " + tiers.latent().size() + " latent");
        }
        if (!tiers.unproven().isEmpty()) {
            counts.add("⚠️ " + tiers.unproven().size() + " not proven reachable");
        }
        if (!counts.isEmpty()) {
            line.append(" (").append(String.join(", ", counts)).append(')');
        }
        return line.toString();
    }

    /**
     * The check result as Markdown: the same tier sections as the text, each finding folded
     * into its own {@code <details>}. The 💥 tier opens unfolded, since it is what a reader of
     * the comment must act on; the deprioritized tiers open folded. The summary line closes it.
     */
    private static List<String> markdownSections(Check.Report report, Tiers tiers) {
        List<String> sections = new ArrayList<>();
        if (report.reachabilityComputed) {
            if (!tiers.breaks().isEmpty()) {
                sections.add(markdownSection(BREAKS_HEADING, tiers.breaks(), true));
            }
            if (!tiers.latent().isEmpty()) {
                sections.add(markdownSection(LATENT_HEADING_RANKED, tiers.latent(), false));
            }
            if (!tiers.unproven().isEmpty()) {
                sections.add(markdownSection(UNPROVEN_HEADING, tiers.unproven(), false));
            }
        } else if (!report.violations.isEmpty()) {
            List<Violation> flat = tiers.flat();
            if (!flat.isEmpty()) {
                sections.add(String.join("\n\n", markdownBlocks(flat, true)));
            }
            if (!tiers.latent().isEmpty()) {
                sections.add(markdownSection(LATENT_HEADING, tiers.latent(), false));
            }
        }
        sections.add(summaryLine(report, tiers.breaks().size(), tiers.latent().size(), tiers.unproven().size()));
        return sections;
    }

    /**
     * The plain {@code check} report as Markdown, for a pull request comment or a CI job
     * summary.
     *
     * @param jdkPair the compared releases when the JDK upgrade is the pair, else null
     */
    static String checkMarkdown(List<String> oldPaths, List<String> newPaths, int[] jdkPair, Check.Report report) {
        Tiers tiers = Tiers.of(report);
        List<String> parts = new ArrayList<>();
        parts.add(MARKDOWN_MARKER);
        parts.add(headline(report, tiers));
        parts.add(
                jdkPair != null
                        ? checkHeaderJdkLine(jdkPair[0], jdkPair[1], report.scanTargets)
                        : checkHeaderLine(oldPaths, newPaths, report.scanTargets, Style.MARKDOWN::code));
        parts.addAll(markdownSections(report, tiers));
        return String.join("\n\n", parts) + "\n";
    }

    static String checkJson(Check.Report report) {
        StringBuilder out = new StringBuilder();
        Json.Writer json = new Json.Writer(out, true);
        json.beginObject();
        writeViolations(json, report.violations);
        json.key("scanned_classes").value(report.scannedClasses);
        json.key("total").value(report.violations.size());
        json.key("unknown_refs").value(report.unknownRefs);
        json.key("suppressed").value(report.suppressed);
        json.endObject();
        return out.toString();
    }

    private static void writeViolations(Json.Writer json, List<Violation> violations) {
        json.key("violations").beginArray();
        for (Violation v : violations) {
            writeViolation(json, v);
        }
        json.endArray();
    }

    /**
     * One violation as every JSON surface spells it. The optional keys are left out at their
     * defaults, which is what keeps plain check output and the goldens byte-identical.
     */
    static void writeViolation(Json.Writer json, Violation v) {
        json.beginObject();
        json.key("source").sym(v.source);
        json.key("source_class").sym(v.sourceClass);
        json.key("reference");
        Verdicts.writeReference(json, v.reference);
        json.key("reason").value(v.reason.text);
        if (v.reachable != null) {
            json.key("reachable").value(v.reachable);
        }
        if (v.invocationFound != null) {
            json.key("invocation_found").value(v.invocationFound);
        }
        if (v.observedLoading) {
            json.key("observed_loading").value(true);
        }
        if (v.loadTrigger != null) {
            json.key("load_trigger").value(v.loadTrigger);
        }
        if (v.suggestion != null) {
            Suggestion s = v.suggestion;
            json.key("suggestion").beginObject();
            if (s.referencedBy() != null) {
                json.key("referenced_by").value(s.referencedBy());
            }
            json.key("removed_by").value(s.removedBy());
            json.key("before").value(s.before());
            json.key("after").value(s.after());
            json.key("advice").value(s.advice());
            json.endObject();
        }
        if (!v.modules.isEmpty()) {
            json.key("modules");
            writeStrings(json, v.modules);
        }
        json.endObject();
    }

    private static void writeStrings(Json.Writer json, List<String> values) {
        json.beginArray();
        for (String value : values) {
            json.value(value);
        }
        json.endArray();
    }

    // ---- upgrade-check ----

    /**
     * One deduplicated per-module check run (modules sharing identical inputs share one run).
     *
     * @param jdk the run compared JDK releases, not this module's jars, so it is not one of
     *     the dump's modules and must not be counted as a changed one
     * @param jdkModules for a JDK run, the dump module whose release moved. Empty for a
     *     dependency run, whose modules are {@code modules}. A list because {@code modules} is
     *     one, and because a future grouping of runs would put several here rather than reshape
     *     the field.
     * @param jdkPair for a JDK run, the releases compared, as an {@code int[]} of length 2
     *     (old, new). Null is Rust's {@code None}. The label needs both halves and reading
     *     them back out of {@code modules} would be parsing our own formatting.
     * @param broken post-exclusion violations attributed to any module of this run
     */
    record ModuleOutcome(
            List<String> modules,
            boolean jdk,
            List<String> jdkModules,
            int[] jdkPair,
            int scannedClasses,
            int broken,
            int unknownRefs) {}

    /**
     * How the per-module upgrade-check partitioned the dump's modules.
     *
     * @param unchangedModules modules whose own resolution lost no version. Skipped, they
     *     cannot break.
     * @param newModules modules present only in the after dump with nothing checkable. Skipped.
     * @param incompleteModules after-side modules whose artifact list vanished (partial dump).
     *     Skipped, warned.
     */
    record ModuleRunSummary(
            List<ModuleOutcome> outcomes, int totalModules, int unchangedModules, int newModules, int incompleteModules) {}

    /**
     * The shared tail of a run's summary line, so the per-module rows and the JDK rows cannot
     * drift into reporting the same three numbers differently.
     */
    private static String runCounts(ModuleOutcome o) {
        String broken = o.broken() == 0 ? "✅ 0 broken" : "❌ " + o.broken() + " broken";
        String unverified = o.unknownRefs() == 0 ? "0 unverified" : "❓ " + o.unknownRefs() + " unverified";
        return "scanned " + o.scannedClasses() + " classes, " + broken + ", " + unverified;
    }

    /**
     * The upgrade-check report. It is the dependency-diff header, the per-module run summary,
     * and the check result if a check ran. The header never swallows the rest. Per-module runs
     * gate on each module's OWN diff, which can find violations while the universe-wide change
     * list is empty (a version swap between modules), so the summary and result print
     * regardless of {@code changes}.
     *
     * @param result null when no check ran
     * @param modules null outside per-module mode
     */
    static String upgradeText(List<Dump.DependencyChange> changes, Check.Report result, ModuleRunSummary modules) {
        StringBuilder out = new StringBuilder();
        if (changes.isEmpty()) {
            out.append("dependency changes: none\n");
        } else {
            out.append("dependency changes: ").append(changes.size()).append('\n');
        }
        for (Dump.DependencyChange c : changes) {
            String label =
                    switch (c.kind()) {
                        case CHANGED -> "CHANGED";
                        case REMOVED -> "REMOVED";
                        case ADDED -> "ADDED  ";
                    };
            out.append("    ")
                    .append(label)
                    .append(' ')
                    .append(c.coordinate())
                    .append(' ')
                    .append(c.before().isEmpty() ? "-" : String.join(",", c.before()))
                    .append(" -> ")
                    .append(c.after().isEmpty() ? "-" : String.join(",", c.after()))
                    .append('\n');
        }
        if (modules != null) {
            out.append('\n').append(perModuleLine(modules)).append('\n');
            List<ModuleOutcome> jdkRuns = new ArrayList<>();
            for (ModuleOutcome o : modules.outcomes()) {
                if (o.jdk()) {
                    jdkRuns.add(o);
                } else {
                    out.append("    ")
                            .append(String.join(", ", o.modules()))
                            .append("  ")
                            .append(runCounts(o))
                            .append('\n');
                }
            }
            // Its own section, not another row above. A JDK run compares two releases of the
            // JDK while the rows above compare two versions of a jar, so their broken counts
            // are answers to different questions and side by side they read as one total that
            // does not add up.
            if (!jdkRuns.isEmpty()) {
                out.append('\n').append(jdkCheckLine(modules, jdkRuns)).append('\n');
                for (ModuleOutcome o : jdkRuns) {
                    out.append("    ")
                            .append(String.join(", ", o.jdkModules()))
                            .append("  JDK ")
                            .append(o.jdkPair()[0])
                            .append(" -> ")
                            .append(o.jdkPair()[1])
                            .append("  ")
                            .append(runCounts(o))
                            .append('\n');
                }
            }
        }
        if (result != null) {
            out.append('\n');
            out.append(checkText(result));
        }
        return out.toString();
    }

    private static String perModuleLine(ModuleRunSummary modules) {
        int checked = 0;
        for (ModuleOutcome o : modules.outcomes()) {
            if (!o.jdk()) {
                checked += o.modules().size();
            }
        }
        List<String> notes = new ArrayList<>();
        notes.add(modules.unchangedModules() + " unchanged");
        if (modules.newModules() > 0) {
            notes.add(modules.newModules() + " new");
        }
        if (modules.incompleteModules() > 0) {
            notes.add(modules.incompleteModules() + " incomplete");
        }
        return "per-module check: " + checked + " of " + modules.totalModules() + " modules changed their resolved versions ("
                + String.join(", ", notes) + ")";
    }

    private static String jdkCheckLine(ModuleRunSummary modules, List<ModuleOutcome> jdkRuns) {
        Set<String> moved = new HashSet<>();
        for (ModuleOutcome o : jdkRuns) {
            moved.addAll(o.jdkModules());
        }
        return "JDK check: " + moved.size() + " of " + modules.totalModules() + " modules moved to another release";
    }

    private static String changeLabel(Dump.ChangeKind kind) {
        return switch (kind) {
            case CHANGED -> "CHANGED";
            case REMOVED -> "REMOVED";
            case ADDED -> "ADDED";
        };
    }

    private static String versions(List<String> versions) {
        return versions.isEmpty() ? "-" : String.join(",", versions);
    }

    /** The dependency-change header as a table. */
    private static String changesMarkdown(List<Dump.DependencyChange> changes) {
        if (changes.isEmpty()) {
            return "dependency changes: none";
        }
        StringBuilder out = new StringBuilder("dependency changes: ").append(changes.size()).append("\n\n");
        out.append("| change | coordinate | before | after |\n| --- | --- | --- | --- |");
        for (Dump.DependencyChange c : changes) {
            out.append("\n| ")
                    .append(changeLabel(c.kind()))
                    .append(" | ")
                    .append(codeSpan(c.coordinate()))
                    .append(" | ")
                    .append(versions(c.before()))
                    .append(" | ")
                    .append(versions(c.after()))
                    .append(" |");
        }
        return out.toString();
    }

    /** The three numbers of {@link #runCounts} as table cells. */
    private static String countCells(ModuleOutcome o) {
        String broken = o.broken() == 0 ? "✅ 0" : "❌ " + o.broken();
        String unverified = o.unknownRefs() == 0 ? "0" : "❓ " + o.unknownRefs();
        return o.scannedClasses() + " | " + broken + " | " + unverified;
    }

    /**
     * The per-module run summary as a table, and the JDK runs as their own table after it, for
     * the reason {@link #upgradeText} keeps them apart.
     */
    private static List<String> modulesMarkdown(ModuleRunSummary modules) {
        List<String> parts = new ArrayList<>();
        StringBuilder rows = new StringBuilder();
        List<ModuleOutcome> jdkRuns = new ArrayList<>();
        for (ModuleOutcome o : modules.outcomes()) {
            if (o.jdk()) {
                jdkRuns.add(o);
            } else {
                rows.append("\n| ").append(joinCode(o.modules(), Report::codeSpan)).append(" | ").append(countCells(o)).append(" |");
            }
        }
        String perModule = perModuleLine(modules);
        if (rows.length() > 0) {
            perModule += "\n\n| module | scanned | broken | unverified |\n| --- | ---: | ---: | ---: |" + rows;
        }
        parts.add(perModule);
        if (!jdkRuns.isEmpty()) {
            StringBuilder jdk = new StringBuilder(jdkCheckLine(modules, jdkRuns));
            jdk.append("\n\n| module | JDK | scanned | broken | unverified |\n| --- | --- | ---: | ---: | ---: |");
            for (ModuleOutcome o : jdkRuns) {
                jdk.append("\n| ")
                        .append(joinCode(o.jdkModules(), Report::codeSpan))
                        .append(" | ")
                        .append(o.jdkPair()[0])
                        .append(" -> ")
                        .append(o.jdkPair()[1])
                        .append(" | ")
                        .append(countCells(o))
                        .append(" |");
            }
            parts.add(jdk.toString());
        }
        return parts;
    }

    /**
     * The upgrade-check report as Markdown, for a pull request comment or a CI job summary.
     * Same parts as {@link #upgradeText}, in the same order, with the listings as tables and
     * each finding folded into a {@code <details>}.
     */
    static String upgradeMarkdown(List<Dump.DependencyChange> changes, Check.Report result, ModuleRunSummary modules) {
        Tiers tiers = result == null ? null : Tiers.of(result);
        List<String> parts = new ArrayList<>();
        parts.add(MARKDOWN_MARKER);
        parts.add(headline(result, tiers));
        parts.add(changesMarkdown(changes));
        if (modules != null) {
            parts.addAll(modulesMarkdown(modules));
        }
        if (result != null) {
            parts.addAll(markdownSections(result, tiers));
        }
        return String.join("\n\n", parts) + "\n";
    }

    /**
     * @param result null when no check ran, which leaves out the four result keys
     * @param modules null outside per-module mode, which leaves out {@code module_runs}
     */
    static String upgradeJson(List<Dump.DependencyChange> changes, Check.Report result, ModuleRunSummary modules) {
        StringBuilder out = new StringBuilder();
        Json.Writer json = new Json.Writer(out, true);
        json.beginObject();
        json.key("changes").beginArray();
        for (Dump.DependencyChange c : changes) {
            json.beginObject();
            json.key("coordinate").value(c.coordinate());
            json.key("kind").value(c.kind().json);
            json.key("before");
            writeStrings(json, c.before());
            json.key("after");
            writeStrings(json, c.after());
            json.endObject();
        }
        json.endArray();
        if (modules != null) {
            json.key("module_runs").beginObject();
            json.key("outcomes").beginArray();
            for (ModuleOutcome o : modules.outcomes()) {
                writeOutcome(json, o);
            }
            json.endArray();
            json.key("total_modules").value(modules.totalModules());
            json.key("unchanged_modules").value(modules.unchangedModules());
            json.key("new_modules").value(modules.newModules());
            json.key("incomplete_modules").value(modules.incompleteModules());
            json.endObject();
        }
        if (result != null) {
            writeViolations(json, result.violations);
            json.key("scanned_classes").value(result.scannedClasses);
            json.key("unknown_refs").value(result.unknownRefs);
            json.key("suppressed").value(result.suppressed);
        }
        json.endObject();
        return out.toString();
    }

    private static void writeOutcome(Json.Writer json, ModuleOutcome o) {
        json.beginObject();
        json.key("modules");
        writeStrings(json, o.modules());
        if (o.jdk()) {
            json.key("jdk").value(true);
        }
        if (!o.jdkModules().isEmpty()) {
            json.key("jdk_modules");
            writeStrings(json, o.jdkModules());
        }
        if (o.jdkPair() != null) {
            json.key("jdk_pair").beginArray();
            json.value(o.jdkPair()[0]);
            json.value(o.jdkPair()[1]);
            json.endArray();
        }
        json.key("scanned_classes").value(o.scannedClasses());
        json.key("broken").value(o.broken());
        json.key("unknown_refs").value(o.unknownRefs());
        json.endObject();
    }
}
