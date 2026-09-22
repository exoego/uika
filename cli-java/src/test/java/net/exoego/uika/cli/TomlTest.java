package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every expected message here was printed by the Rust binary for the same input (toml 1.1.5),
 * so a change that alters one is a change in what users see, not a refactor.
 */
class TomlTest {
    private static String error(String input) {
        return assertThrows(Toml.Error.class, () -> Toml.parse(input)).getMessage();
    }

    private static String description(String input) {
        return assertThrows(Toml.Error.class, () -> Toml.parse(input)).description;
    }

    private static String string(Toml.Table table, String key) {
        return table.get(key).value().asString();
    }

    private static List<String> keys(Toml.Table table) {
        List<String> keys = new ArrayList<>();
        for (Toml.Entry entry : table.entries()) {
            keys.add(entry.key());
        }
        return keys;
    }

    /** A number or a boolean as a type error names it, the one place a user sees it. */
    private static String shown(Toml.Value value) {
        String description = assertThrows(Toml.Error.class, value::asString).description;
        return description.substring("invalid type: ".length(), description.indexOf(", expected"));
    }

    @Test
    void emptyAndCommentOnlyDocumentsAreEmptyTables() {
        assertTrue(Toml.parse("").isEmpty());
        assertTrue(Toml.parse("\n\n").isEmpty());
        assertTrue(Toml.parse("# only a comment").isEmpty());
        assertTrue(Toml.parse("  # indented\r\n# tab\there, non-ASCII é\n").isEmpty());
    }

    @Test
    void bareAndQuotedKeys() {
        Toml.Table t = Toml.parse("""
                bare-key_1 = "a"
                "quoted key" = "b"
                'literal "key"' = "c"
                "esc\\u0061ped" = "d"
                1234 = "e"
                """);
        assertEquals("a", string(t, "bare-key_1"));
        assertEquals("b", string(t, "quoted key"));
        assertEquals("c", string(t, "literal \"key\""));
        assertEquals("d", string(t, "escaped"));
        assertEquals("e", string(t, "1234"));
        assertNull(t.get("missing"));
    }

    /** The crate's map is a BTreeMap, and that order decides which schema error comes first. */
    @Test
    void tablesIterateInKeyByteOrder() {
        Toml.Table t = Toml.parse("b = 1\n\"é\" = 1\nB = 1\na = 1\n\"𝄞\" = 1\n\"\uFFFF\" = 1\n");
        assertEquals(List.of("B", "a", "b", "é", "\uFFFF", "𝄞"), keys(t));
    }

    @Test
    void basicStringEscapes() {
        Toml.Table t = Toml.parse("""
                s = "tab\\t nl\\n cr\\r bs\\b ff\\f esc\\e quote\\" slash\\\\ x\\x41 u\\u00e9 U\\U0001F600"
                raw = "as is: é 日本語 \t tab"
                """);
        assertEquals("tab\t nl\n cr\r bs\b ff\f esc\u001b quote\" slash\\ xA u\u00e9 U\uD83D\uDE00", string(t, "s"));
        assertEquals("as is: é 日本語 \t tab", string(t, "raw"));
    }

    @Test
    void literalStringsKeepBackslashes() {
        Toml.Table t = Toml.parse("s = 'C:\\path\\n \"quoted\"'\n");
        assertEquals("C:\\path\\n \"quoted\"", string(t, "s"));
    }

    @Test
    void multiLineBasicStrings() {
        Toml.Table t = Toml.parse("a = \"\"\"\nfirst newline is trimmed\nsecond stays\"\"\"\n"
                + "b = \"\"\"line \\\n      continued \\  \n\n   here\"\"\"\n"
                + "c = \"\"\"two \"\" quotes and an escaped \\\"\"\" triple\"\"\"\n"
                + "d = \"\"\"ends with quotes\"\"\"\"\"\n"
                + "e = \"\"\"crlf\r\nkept\"\"\"\n");
        assertEquals("first newline is trimmed\nsecond stays", string(t, "a"));
        assertEquals("line continued here", string(t, "b"));
        assertEquals("two \"\" quotes and an escaped \"\"\" triple", string(t, "c"));
        assertEquals("ends with quotes\"\"", string(t, "d"));
        assertEquals("crlf\r\nkept", string(t, "e"));
    }

    @Test
    void multiLineLiteralStrings() {
        Toml.Table t = Toml.parse("a = '''\nno \\escapes\n  here'''\nb = '''it''s'''\nc = '''quoted''''\n");
        assertEquals("no \\escapes\n  here", string(t, "a"));
        assertEquals("it''s", string(t, "b"));
        assertEquals("quoted'", string(t, "c"));
    }

    @Test
    void integersAndBooleans() {
        Toml.Table t = Toml.parse("""
                dec = 1_000
                neg = -17
                plus = +5
                zero = 0
                hex = 0xDEAD_beef
                oct = 0o17
                bin = 0b1010
                max = 9223372036854775807
                min = -9223372036854775808
                yes = true
                no = false
                """);
        assertEquals("integer `1000`", shown(t.get("dec").value()));
        assertEquals("integer `-17`", shown(t.get("neg").value()));
        assertEquals("integer `5`", shown(t.get("plus").value()));
        assertEquals("integer `0`", shown(t.get("zero").value()));
        assertEquals("integer `3735928559`", shown(t.get("hex").value()));
        assertEquals("integer `15`", shown(t.get("oct").value()));
        assertEquals("integer `10`", shown(t.get("bin").value()));
        assertEquals("integer `9223372036854775807`", shown(t.get("max").value()));
        assertEquals("integer `-9223372036854775808`", shown(t.get("min").value()));
        assertEquals("boolean `true`", shown(t.get("yes").value()));
        assertEquals("boolean `false`", shown(t.get("no").value()));
        assertEquals(Toml.Kind.INTEGER, t.get("dec").value().kind());
        assertEquals(Toml.Kind.BOOLEAN, t.get("yes").value().kind());
    }

    @Test
    void floatsAndDateTimesAreRecognised() {
        Toml.Table t = Toml.parse("""
                f = 6.02e23
                inf = -inf
                date = 1979-05-27
                spaced = 1979-05-27 07:32:00Z
                time = 07:32
                """);
        assertEquals(Toml.Kind.FLOAT, t.get("f").value().kind());
        assertEquals(Toml.Kind.FLOAT, t.get("inf").value().kind());
        assertEquals(Toml.Kind.DATETIME, t.get("date").value().kind());
        assertEquals(Toml.Kind.DATETIME, t.get("spaced").value().kind());
        assertEquals(Toml.Kind.DATETIME, t.get("time").value().kind());
    }

    @Test
    void arraysOfStrings() {
        Toml.Table t = Toml.parse("""
                one = ["a", 'b', \"\"\"c\"\"\"]
                multi = [
                  "x", # comment inside
                  "y",
                ]
                empty = []
                nested = [["a"], []]
                """);
        List<String> one = new ArrayList<>();
        for (Toml.Value v : t.get("one").value().asArray()) {
            one.add(v.asString());
        }
        assertEquals(List.of("a", "b", "c"), one);
        assertEquals(2, t.get("multi").value().asArray().size());
        assertTrue(t.get("empty").value().asArray().isEmpty());
        assertEquals("a", t.get("nested").value().asArray().get(0).asArray().get(0).asString());
    }

    @Test
    void tablesAndDottedKeys() {
        Toml.Table t = Toml.parse("""
                top = "root"
                a.b.c = "dotted"
                a . b . d = "spaced dots"

                [server]
                host = "h"
                opts.retry = true

                [server.tls]
                cert = "c"

                [ "quoted".'table' ]
                k = "v"
                """);
        assertEquals("root", string(t, "top"));
        Toml.Table ab = t.get("a").value().asTable("a map").get("b").value().asTable("a map");
        assertEquals("dotted", string(ab, "c"));
        assertEquals("spaced dots", string(ab, "d"));
        Toml.Table server = t.get("server").value().asTable("a map");
        assertEquals("h", string(server, "host"));
        assertEquals("boolean `true`", shown(server.get("opts").value().asTable("a map").get("retry").value()));
        assertEquals("c", string(server.get("tls").value().asTable("a map"), "cert"));
        assertEquals("v", string(t.get("quoted").value().asTable("a map").get("table").value().asTable("a map"), "k"));
    }

    @Test
    void arraysOfTables() {
        Toml.Table t = Toml.parse("""
                [[exclude]]
                owner = "first"

                [[exclude]]
                owner = "second"
                [exclude.sub]
                x = "under the second"

                [[ exclude ]]
                """);
        List<Toml.Value> items = t.get("exclude").value().asArray();
        assertEquals(3, items.size());
        assertEquals("first", string(items.get(0).asTable("t"), "owner"));
        Toml.Table second = items.get(1).asTable("t");
        assertEquals("under the second", string(second.get("sub").value().asTable("t"), "x"));
        assertTrue(items.get(2).asTable("t").isEmpty());
    }

    /** TOML 1.1 lets an inline table span lines and end with a comma, and the crate follows it. */
    @Test
    void inlineTables() {
        Toml.Table t = Toml.parse("""
                exclude = [{ owner = "a", reason = "r" }, {kind="k",reason="x",}]
                point = { x.y = "dotted inside", z = { deep = "er" } }
                wrapped = {
                  a = "1", # comment
                  b = "2",
                }
                none = {}
                """);
        List<Toml.Value> items = t.get("exclude").value().asArray();
        assertEquals("a", string(items.get(0).asTable("t"), "owner"));
        assertEquals("x", string(items.get(1).asTable("t"), "reason"));
        Toml.Table point = t.get("point").value().asTable("t");
        assertEquals("dotted inside", string(point.get("x").value().asTable("t"), "y"));
        assertEquals("er", string(point.get("z").value().asTable("t"), "deep"));
        assertEquals(List.of("a", "b"), keys(t.get("wrapped").value().asTable("t")));
        assertTrue(t.get("none").value().asTable("t").isEmpty());
    }

    @Test
    void aByteOrderMarkAndCrlfAreAccepted() {
        Toml.Table t = Toml.parse("\uFEFF[[exclude]]\r\nowner = \"a\"\r\n");
        assertEquals("a", string(t.get("exclude").value().asArray().get(0).asTable("t"), "owner"));
    }

    @Test
    void theRenderingQuotesTheLineAndPointsAtTheSpan() {
        assertEquals(
                """
                TOML parse error at line 3, column 1
                  |
                3 | owner = "b"
                  | ^^^^^
                duplicate key
                """,
                error("[[exclude]]\nowner = \"a\"\nowner = \"b\"\nreason = \"x\"\n"));
        assertEquals(
                """
                TOML parse error at line 2, column 11
                  |
                2 | owner = "a
                  |           ^
                invalid basic string, expected `"`
                """,
                error("[[exclude]]\nowner = \"a\nreason = \"x\"\n"));
        // The gutter grows with the line number.
        assertEquals(
                """
                TOML parse error at line 11, column 5
                   |
                11 | b = = 2
                   |     ^
                extra `=`, expected nothing
                """,
                error("a = 1\n\n\n\n\n\n\n\n\n\nb = = 2"));
    }

    /** The column counts characters and the caret run counts bytes, as the crate does. */
    @Test
    void nonAsciiLinesKeepTheCratesColumnArithmetic() {
        assertEquals(
                """
                TOML parse error at line 2, column 15
                  |
                2 | owner = "日本語" junk
                  |               ^
                unexpected key or value, expected newline, `#`
                """,
                error("[[exclude]]\nowner = \"日本語\" junk\nreason = \"r\""));
        assertEquals(
                """
                TOML parse error at line 2, column 1
                  |
                2 | 日本語 = "a"
                  | ^^^^^^^^^
                invalid unquoted key, expected letters, numbers, `-`, `_`
                """,
                error("[[exclude]]\n日本語 = \"a\"\nreason = \"r\""));
    }

    /** A syntax error anywhere wins over an earlier key, string or number error. */
    @Test
    void syntaxErrorsAreReportedBeforeContentErrors() {
        assertEquals("extra `=`, expected nothing", description("a = \"\\q\"\nb = = 1"));
        assertEquals("extra `=`, expected nothing", description("a = 1\na = 2\nb = = 1"));
        assertEquals("missing table open, expected `[`", description("a = 08\nb = 1\n]"));
        // Without one, the earliest content error is the one shown.
        assertTrue(description("a = \"\\q\"\na = 2").startsWith("missing escaped value"));
        // A value is decoded before its key is checked against the table.
        assertTrue(description("a = 1\na = \"\\q\"").startsWith("missing escaped value"));
        assertEquals("duplicate key", description("a = 1\na = 2\nb = \"\\q\""));
    }

    @Test
    void unterminatedAndMalformedStrings() {
        assertEquals("invalid basic string, expected `\"`", description("a = \"abc"));
        assertEquals("invalid literal string, expected `'`", description("a = 'abc"));
        assertEquals("invalid multi-line basic string, expected `\"`", description("a = \"\"\"abc"));
        assertEquals("invalid multi-line literal string, expected `'`", description("a = '''abc"));
        assertEquals(
                "missing escaped value, expected `b`, `e`, `f`, `n`, `r`, `\\`, `\"`, `x`, `u`, `U`",
                description("a = \"\\q\""));
        assertEquals("too few unicode value digits, expected unicode hexadecimal value", description("a = \"\\u12\""));
        assertEquals("invalid value, expected unicode hexadecimal value", description("a = \"\\uD800\""));
        assertEquals("invalid value, expected unicode hexadecimal value", description("a = \"\\U00110000\""));
        assertEquals(
                "invalid basic string, expected non-double-quote visible characters, `\\`",
                description("a = \"ctl\u0001\""));
        assertEquals(
                "invalid literal string, expected non-single-quote visible characters", description("a = 'ctl\u0001'"));
        assertEquals("invalid multi-line basic string, expected newline", description("a = \"\"\"a\\ b\"\"\""));
        assertEquals(
                "carriage return must be followed by newline, expected newline", description("a = \"\"\"cr\rx\"\"\""));
    }

    @Test
    void malformedKeysAndHeaders() {
        assertEquals("unquoted keys cannot be empty, expected letters, numbers, `-`, `_`", description("= \"x\""));
        assertEquals("unquoted keys cannot be empty, expected letters, numbers, `-`, `_`", description("a. = 1"));
        assertEquals("invalid unquoted key, expected letters, numbers, `-`, `_`", description("own!er = 1"));
        assertEquals("key with no value, expected `=`", description("owner"));
        assertEquals("key with no value, expected `=`", description("own er = 1"));
        assertEquals(
                "keys cannot be multi-line basic strings, expected basic string, literal string",
                description("\"\"\"owner\"\"\" = 1"));
        assertEquals("invalid key-value pair, expected key", description(", = 1"));
        assertEquals("unclosed array table, expected `]`", description("[[exclude]\nowner = \"a\""));
        assertEquals("unclosed array table, expected `]]`", description("[[exclude\nowner = \"a\""));
        assertEquals("unclosed table, expected `]`", description("[exclude\nowner = \"a\""));
        assertEquals("unexpected key or value, expected newline, `#`", description("[[exclude]] junk"));
        assertEquals("unexpected key or value, expected newline, `#`", description("a = \"x\" junk"));
    }

    @Test
    void duplicateAndConflictingDefinitions() {
        assertEquals("duplicate key", description("a = 1\na = 2"));
        assertEquals("duplicate key", description("a = 1\n\"a\" = 2"));
        assertEquals("duplicate key", description("[a]\n[a]"));
        assertEquals("duplicate key", description("[a]\n[[a]]"));
        assertEquals("duplicate key", description("[[a]]\n[a]"));
        assertEquals("duplicate key", description("a = 1\n[a]"));
        assertEquals("duplicate key", description("[a]\nb.c = 1\n[a.b]"));
        assertEquals("duplicate key", description("x = {a = 1, a = 2}"));
        assertEquals("duplicate key", description("x = {a = {b = 1}, a.c = 2}"));
        assertEquals("cannot extend value of type integer with a dotted key", description("a = 1\na.b = 2"));
        assertEquals("cannot extend value of type inline table with a dotted key", description("a = {}\na.b = 1"));
        assertEquals("cannot extend value of type array with a dotted key", description("a = []\na.b = 1"));
        // Reopening a table that a header only implied is fine.
        assertEquals(List.of("a"), keys(Toml.parse("[a.b]\n[a]\nx = 1")));
    }

    @Test
    void malformedValues() {
        assertEquals("string values must be quoted, expected literal string", description("a = "));
        assertEquals("string values must be quoted, expected literal string", description("a = bare"));
        assertEquals("missing opening quote, expected `\"`", description("a = bare\""));
        assertEquals("invalid boolean, expected `true`", description("a = True"));
        assertEquals("invalid float, expected `inf`", description("a = Inf"));
        assertEquals("unexpected leading zero, expected nothing", description("a = 01"));
        assertEquals("`_` may only go between digits, expected nothing", description("a = 1__0"));
        assertEquals("radix must be lowercase, expected `0x`", description("a = 0X1F"));
        assertEquals("invalid hexadecimal number", description("a = 0xg"));
        assertEquals("integers with a radix cannot be signed, expected nothing", description("a = +0x1"));
        assertEquals("redundant numeric sign, expected nothing", description("a = +-1"));
        assertEquals("invalid fraction, expected digits", description("a = 1."));
        assertEquals("invalid mantissa, expected digits", description("a = .5"));
        assertEquals("invalid exponent, expected digits", description("a = 1e"));
        assertEquals("invalid date, expected month between 01 and 12", description("a = 1979-13-27"));
        assertEquals("invalid date, expected day between 01 and 28", description("a = 1979-02-30"));
        assertEquals("invalid time, expected hour between 00 and 23", description("a = 1979-05-27T25:00:00"));
        assertEquals("invalid datetime", description("a = 1979-05-27T07:32:00Zjunk"));
    }

    @Test
    void malformedArraysAndInlineTables() {
        assertEquals("unclosed array, expected `]`", description("a = [\"x\""));
        assertEquals("extra comma in array, expected value", description("a = [,]"));
        assertEquals("missing comma between array elements, expected `,`", description("a = [\"x\" \"y\"]"));
        assertEquals("unexpected `=` in array, expected value, `]`", description("a = [=]"));
        assertEquals("missing array opening, expected `[`", description("a = ]"));
        assertEquals("unclosed inline table, expected `}`", description("a = {b = 1"));
        assertEquals("extra comma in inline table, expected key", description("a = {,}"));
        assertEquals("missing comma between key-value pairs, expected `,`", description("a = {b = \"x\" c = 2}"));
        // A bare value swallows the next bare word, the way a date-time holds a space.
        assertEquals("extra assignment between key-value pairs, expected `,`", description("a = {b = 1 c = 2}"));
        assertEquals("missing inline table opening, expected `{`", description("a = }"));
        assertEquals("invalid literal string, expected `'`", description("a = {b}"));
        assertEquals("cannot recurse further; max recursion depth met", description("a = " + "[".repeat(81) + "]".repeat(81)));
        assertEquals(1, Toml.parse("a = " + "[".repeat(80) + "]".repeat(80)).size());
    }

    @Test
    void controlCharactersInCommentsAndBareCarriageReturns() {
        assertEquals("invalid comment character, expected printable characters", description("# bad \u0001 comment\n"));
        assertEquals("carriage return must be followed by newline, expected newline", description("a = 1\rb = 2"));
        // The one error with no position, so it is rendered without the excerpt.
        assertEquals("recursion limit\n", error(".".repeat(0) + "k" + ".k".repeat(80) + " = 1"));
    }

    /** The serde side. What a wrongly typed value is called, and where the caret goes. */
    @Test
    void typeErrorsNameTheValueTheWaySerdeDoes() {
        assertEquals(
                """
                TOML parse error at line 2, column 9
                  |
                2 | owner = 5
                  |         ^
                invalid type: integer `5`, expected a string
                """,
                assertThrows(Toml.Error.class, () -> Toml.parse("[[exclude]]\nowner = 5\n")
                                .get("exclude")
                                .value()
                                .asArray()
                                .get(0)
                                .asTable("struct RawEntry")
                                .get("owner")
                                .value()
                                .asString())
                        .getMessage());
        assertEquals("invalid type: integer `31`, expected a string", stringError("0x1F"));
        assertEquals("invalid type: integer `18446744073709551615`, expected a string", stringError("18446744073709551615"));
        assertEquals(
                "invalid type: integer `18446744073709551616` as i128, expected a string",
                stringError("18446744073709551616"));
        assertEquals(
                "invalid type: integer `340282366920938463463374607431768211455` as u128, expected a string",
                stringError("340282366920938463463374607431768211455"));
        assertEquals("integer number overflowed", stringError("340282366920938463463374607431768211456"));
        assertEquals("invalid type: floating point `1.5`, expected a string", stringError("1.5"));
        assertEquals("invalid type: floating point `10000000000.0`, expected a string", stringError("1e10"));
        assertEquals("invalid type: floating point `0.0000001`, expected a string", stringError("1e-7"));
        assertEquals("invalid type: floating point `-0.0`, expected a string", stringError("-0.0"));
        assertEquals("invalid type: floating point `inf`, expected a string", stringError("+inf"));
        assertEquals("invalid type: floating point `NaN`, expected a string", stringError("-nan"));
        assertEquals("floating-point number overflowed", stringError("1e400"));
        assertEquals("invalid type: boolean `true`, expected a string", stringError("true"));
        assertEquals("invalid type: sequence, expected a string", stringError("[\"a\"]"));
        assertEquals("invalid type: map, expected a string", stringError("{a = 1}"));
        assertEquals("invalid type: map, expected a string", stringError("1979-05-27"));

        Toml.Value text = Toml.parse("v = \"quo\\\"te \\\\ \\n \\u0001 é\"").get("v").value();
        assertEquals(
                "invalid type: string \"quo\\\"te \\\\ \\n \\u{1} é\", expected a sequence",
                assertThrows(Toml.Error.class, text::asArray).description);
        assertEquals(
                "invalid type: string \"quo\\\"te \\\\ \\n \\u{1} é\", expected struct RawEntry",
                assertThrows(Toml.Error.class, () -> text.asTable("struct RawEntry")).description);
    }

    private static String stringError(String value) {
        Toml.Value v = Toml.parse("v = " + value).get("v").value();
        return assertThrows(Toml.Error.class, v::asString).description;
    }

    @Test
    void schemaErrorsPointAtTheKeyOrTheTable() {
        Toml.Table t = Toml.parse("[[exclude]]\nonwer = \"x\"\n");
        Toml.Value item = t.get("exclude").value().asArray().get(0);
        Toml.Entry field = item.asTable("struct RawEntry").get("onwer");
        assertEquals(
                """
                TOML parse error at line 2, column 1
                  |
                2 | onwer = "x"
                  | ^^^^^
                unknown field `onwer`, expected one of `owner`, `member`, `descriptor`, `kind`, `reason`
                """,
                field.unknownField("owner", "member", "descriptor", "kind", "reason").getMessage());
        assertEquals("unknown field `onwer`, expected `exclude`", field.unknownField("exclude").description);
        assertEquals("unknown field `onwer`, expected `a` or `b`", field.unknownField("a", "b").description);
        assertEquals("unknown field `onwer`, there are no fields", field.unknownField().description);
        assertEquals(
                """
                TOML parse error at line 1, column 1
                  |
                1 | [[exclude]]
                  | ^^^^^^^^^^^
                missing field `reason`
                """,
                item.missingField("reason").getMessage());
        assertEquals(
                "invalid length 2, expected struct RawEntry with 5 elements",
                item.invalidLength(2, "struct RawEntry with 5 elements").description);
    }

    /** Through the real schema, so the key order rule and the sequence form are pinned too. */
    @Test
    void excludeFilesAreRejectedLikeTheRustDeserializer() {
        assertEquals("unknown field `other`, expected `exclude`", excludeError("other = 1\n"));
        assertEquals("unknown field `other`, expected `exclude`", excludeError("[other]\nx = 1\n"));
        assertEquals("invalid type: map, expected a sequence", excludeError("[exclude]\nowner = \"a\"\n"));
        assertEquals("invalid type: string \"x\", expected struct RawEntry", excludeError("exclude = [\"x\"]"));
        assertEquals("missing field `reason`", excludeError("[[exclude]]\nowner = \"a\"\n"));
        // "owner" sorts before "reason" and after "onwer", whatever the file order is.
        assertEquals(
                "invalid type: integer `2`, expected a string", excludeError("[[exclude]]\nreason = 2\nzzz = 1\n"));
        assertTrue(excludeError("[[exclude]]\nowner = 1\nonwer = \"x\"\n").startsWith("unknown field `onwer`"));
        assertTrue(excludeError("[[exclude]]\nreason = 2\naaa = 1\n").startsWith("unknown field `aaa`"));
        // A serde struct also reads from a sequence, and leftovers go unchecked.
        assertEquals("invalid length 2, expected struct RawEntry with 5 elements", excludeError("exclude = [[\"a\", \"m\"]]"));
        assertEquals(
                1,
                Exclude.parse("exclude = [[\"a\", \"m\", \"()V\", \"method_removed\", \"r\", \"ignored\"]]")
                        .size());
    }

    private static String excludeError(String toml) {
        UikaException e = assertThrows(UikaException.class, () -> Exclude.parse(toml));
        assertTrue(e.getMessage().startsWith("invalid TOML: TOML parse error at line "), e.getMessage());
        String[] lines = e.getMessage().split("\n");
        return lines[lines.length - 1];
    }
}
