package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A change that alters an expected message here is a change in what users see, not a refactor. */
class TomlTest {
    private static String error(String input) {
        return assertThrows(Toml.Error.class, () -> Toml.parse(input)).render("e.toml");
    }

    private static String description(String input) {
        return assertThrows(Toml.Error.class, () -> Toml.parse(input)).getMessage();
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

    /** A number or a boolean as a schema error names it, the one place a user sees it. */
    private static String shown(Toml.Value value) {
        return value.describe();
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

    /** This order decides which of two schema errors comes first. */
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
        assertEquals("the integer 1_000", shown(t.get("dec").value()));
        assertEquals("the integer -17", shown(t.get("neg").value()));
        assertEquals("the integer +5", shown(t.get("plus").value()));
        assertEquals("the integer 0", shown(t.get("zero").value()));
        assertEquals("the integer 0xDEAD_beef", shown(t.get("hex").value()));
        assertEquals("the integer 0o17", shown(t.get("oct").value()));
        assertEquals("the integer 0b1010", shown(t.get("bin").value()));
        assertEquals("the integer 9223372036854775807", shown(t.get("max").value()));
        assertEquals("the integer -9223372036854775808", shown(t.get("min").value()));
        assertEquals("the boolean true", shown(t.get("yes").value()));
        assertEquals("the boolean false", shown(t.get("no").value()));
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
        Toml.Table ab = t.get("a").value().asTable().get("b").value().asTable();
        assertEquals("dotted", string(ab, "c"));
        assertEquals("spaced dots", string(ab, "d"));
        Toml.Table server = t.get("server").value().asTable();
        assertEquals("h", string(server, "host"));
        assertEquals("the boolean true", shown(server.get("opts").value().asTable().get("retry").value()));
        assertEquals("c", string(server.get("tls").value().asTable(), "cert"));
        assertEquals("v", string(t.get("quoted").value().asTable().get("table").value().asTable(), "k"));
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
        assertEquals("first", string(items.get(0).asTable(), "owner"));
        Toml.Table second = items.get(1).asTable();
        assertEquals("under the second", string(second.get("sub").value().asTable(), "x"));
        assertTrue(items.get(2).asTable().isEmpty());
    }

    /** TOML 1.1 lets an inline table span lines and end with a comma. */
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
        assertEquals("a", string(items.get(0).asTable(), "owner"));
        assertEquals("x", string(items.get(1).asTable(), "reason"));
        Toml.Table point = t.get("point").value().asTable();
        assertEquals("dotted inside", string(point.get("x").value().asTable(), "y"));
        assertEquals("er", string(point.get("z").value().asTable(), "deep"));
        assertEquals(List.of("a", "b"), keys(t.get("wrapped").value().asTable()));
        assertTrue(t.get("none").value().asTable().isEmpty());
    }

    @Test
    void aByteOrderMarkAndCrlfAreAccepted() {
        Toml.Table t = Toml.parse("\uFEFF[[exclude]]\r\nowner = \"a\"\r\n");
        assertEquals("a", string(t.get("exclude").value().asArray().get(0).asTable(), "owner"));
    }

    @Test
    void theRenderingQuotesTheLineAndPointsAtTheSpan() {
        assertEquals(
                """
                e.toml: TOML parse error at line 3, column 1
                  |
                3 | owner = "b"
                  | ^^^^^
                duplicate key""",
                error("[[exclude]]\nowner = \"a\"\nowner = \"b\"\nreason = \"x\"\n"));
        assertEquals(
                """
                e.toml: TOML parse error at line 2, column 11
                  |
                2 | owner = "a
                  |           ^
                invalid basic string, expected `"`""",
                error("[[exclude]]\nowner = \"a\nreason = \"x\"\n"));
        // The gutter grows with the line number.
        assertEquals(
                """
                e.toml: TOML parse error at line 11, column 5
                   |
                11 | b = = 2
                   |     ^
                extra `=`""",
                error("a = 1\n\n\n\n\n\n\n\n\n\nb = = 2"));
    }

    /** The column counts characters and the caret run counts bytes. */
    @Test
    void nonAsciiLinesCountTheColumnInCharactersAndTheCaretInBytes() {
        assertEquals(
                """
                e.toml: TOML parse error at line 2, column 15
                  |
                2 | owner = "日本語" junk
                  |               ^
                unexpected key or value, expected newline, `#`""",
                error("[[exclude]]\nowner = \"日本語\" junk\nreason = \"r\""));
        assertEquals(
                """
                e.toml: TOML parse error at line 2, column 1
                  |
                2 | 日本語 = "a"
                  | ^^^^^^^^^
                invalid unquoted key, expected letters, numbers, `-`, `_`""",
                error("[[exclude]]\n日本語 = \"a\"\nreason = \"r\""));
    }

    /** A syntax error anywhere wins over an earlier key, string or number error. */
    @Test
    void syntaxErrorsAreReportedBeforeContentErrors() {
        assertEquals("extra `=`", description("a = \"\\q\"\nb = = 1"));
        assertEquals("extra `=`", description("a = 1\na = 2\nb = = 1"));
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
        assertEquals("unexpected leading zero", description("a = 01"));
        assertEquals("`_` may only go between digits", description("a = 1__0"));
        assertEquals("radix must be lowercase, expected `0x`", description("a = 0X1F"));
        assertEquals("invalid hexadecimal number", description("a = 0xg"));
        assertEquals("integers with a radix cannot be signed", description("a = +0x1"));
        assertEquals("redundant numeric sign", description("a = +-1"));
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
        assertEquals("nested more than 80 levels deep", description("a = " + "[".repeat(81) + "]".repeat(81)));
        assertEquals(1, Toml.parse("a = " + "[".repeat(80) + "]".repeat(80)).size());
    }

    @Test
    void controlCharactersInCommentsAndBareCarriageReturns() {
        assertEquals("invalid comment character, expected printable characters", description("# bad \u0001 comment\n"));
        assertEquals("carriage return must be followed by newline, expected newline", description("a = 1\rb = 2"));
        String key = "k" + ".k".repeat(80);
        assertEquals(
                "e.toml: TOML parse error at line 1, column 1\n  |\n1 | " + key + " = 1\n  | " + "^".repeat(key.length())
                        + "\nkey has more than 80 dotted parts",
                error(key + " = 1"));
        assertEquals(1, Toml.parse("k" + ".k".repeat(79) + " = 1").size());
    }

    /** A value is named in TOML terms and, when it is short, as the file spells it. */
    @Test
    void schemaErrorsNameTheValueInTomlTerms() {
        assertEquals("a string", shown(value("\"quo\\\"te \\n\"")));
        assertEquals("the integer 0x1F", shown(value("0x1F")));
        assertEquals(
                "the integer 340282366920938463463374607431768211456",
                shown(value("340282366920938463463374607431768211456")));
        assertEquals("the float 1e400", shown(value("1e400")));
        assertEquals("the float -nan", shown(value("-nan")));
        assertEquals("the boolean true", shown(value("true")));
        assertEquals("the date-time 1979-05-27 07:32:00Z", shown(value("1979-05-27 07:32:00Z")));
        assertEquals("an array", shown(value("[\"a\"]")));
        assertEquals("a table", shown(value("{a = 1}")));
        assertEquals("an array of tables", shown(Toml.parse("[[v]]").get("v").value()));
    }

    /** The file is valid TOML, so the header does not call it a parse error. */
    @Test
    void schemaErrorsPointAtTheKeyOrTheValue() {
        Toml.Table t = Toml.parse("[[exclude]]\nonwer = 5\n");
        Toml.Value item = t.get("exclude").value().asArray().get(0);
        Toml.Entry field = item.asTable().get("onwer");
        assertEquals(
                """
                e.toml at line 2, column 1
                  |
                2 | onwer = 5
                  | ^^^^^
                a key problem""",
                field.error("a key problem").render("e.toml"));
        assertEquals(
                """
                e.toml at line 2, column 9
                  |
                2 | onwer = 5
                  |         ^
                a value problem""",
                field.value().error("a value problem").render("e.toml"));
        assertEquals(
                """
                e.toml at line 1, column 1
                  |
                1 | [[exclude]]
                  | ^^^^^^^^^^^
                a table problem""",
                item.error("a table problem").render("e.toml"));
    }

    /** Through the real schema, so the key paths and the key order rule are pinned too. */
    @Test
    void excludeFilesAreRejectedWithTheKeyPath() {
        assertEquals("unknown key \"other\", expected exclude", excludeError("other = 1\n"));
        assertEquals("unknown key \"other\", expected exclude", excludeError("[other]\nx = 1\n"));
        assertEquals(
                "exclude: expected an array of tables, found a table", excludeError("[exclude]\nowner = \"a\"\n"));
        assertEquals("exclude: expected an array of tables, found a string", excludeError("exclude = \"x\""));
        assertEquals(
                "exclude[0]: expected a table with owner, member, descriptor, kind or reason, found a string",
                excludeError("exclude = [\"x\"]"));
        assertEquals("exclude[0]: missing required key \"reason\"", excludeError("[[exclude]]\nowner = \"a\"\n"));
        assertEquals(
                "exclude[1].kind: expected a string, found the integer 5",
                excludeError("[[exclude]]\nowner = \"a\"\nreason = \"r\"\n[[exclude]]\nkind = 5\nreason = \"r\"\n"));
        // "owner" sorts before "reason" and after "onwer", whatever the file order is.
        assertEquals(
                "exclude[0].reason: expected a string, found the integer 2",
                excludeError("[[exclude]]\nreason = 2\nzzz = 1\n"));
        assertEquals(
                "exclude[0]: unknown key \"onwer\", expected one of owner, member, descriptor, kind, reason",
                excludeError("[[exclude]]\nowner = 1\nonwer = \"x\"\n"));
        assertTrue(excludeError("[[exclude]]\nreason = 2\naaa = 1\n").startsWith("exclude[0]: unknown key \"aaa\""));
        // A list in place of an entry is rejected like any other value that is not a table.
        assertEquals(
                "exclude[0]: expected a table with owner, member, descriptor, kind or reason, found an array",
                excludeError("exclude = [[\"a\", \"m\", \"()V\", \"method_removed\", \"r\"]]"));
        // A quoted key is escaped, so the message stays on one line.
        assertEquals(
                "exclude[0]: unknown key \"a\\nb\", expected one of owner, member, descriptor, kind, reason",
                excludeError("[[exclude]]\n\"a\\nb\" = \"x\"\nreason = \"r\"\n"));
    }

    private static String excludeError(String toml) {
        return assertThrows(Toml.Error.class, () -> Exclude.parse(toml)).getMessage();
    }

    private static Toml.Value value(String toml) {
        return Toml.parse("v = " + toml).get("v").value();
    }

    @Test
    void valuesRightBeforeTheEndOfTheInput() {
        assertEquals("the integer 1", shown(value("1 ")));
        assertEquals("the integer 1", shown(value("1\t")));
        assertEquals("the integer 1", shown(value("1 # c")));
        assertEquals("x", value("'''x'''").asString());
        // At most two extra quotes join a multi-line string.
        assertEquals("x''", value("'''x'''''").asString());
        assertEquals("x\\", value("\"\"\"x\\\\\"\"\"").asString());
        assertTrue(Toml.parse("\uFEFF").isEmpty());
        assertEquals("the integer 1", shown(Toml.parse("\uFEFFa = 1").get("a").value()));
        Toml.Table t = Toml.parse("a = \"x\" # c\n[b] # c\nc = 2");
        assertEquals("x", string(t, "a"));
        assertEquals("the integer 2", shown(t.get("b").value().asTable().get("c").value()));
    }

    @Test
    void keyValuePairsWithoutSpaces() {
        Toml.Table t = Toml.parse("a=1\nb =2\nc= 3\nd.e=4");
        assertEquals("the integer 1", shown(t.get("a").value()));
        assertEquals("the integer 2", shown(t.get("b").value()));
        assertEquals("the integer 3", shown(t.get("c").value()));
        assertEquals("the integer 4", shown(t.get("d").value().asTable().get("e").value()));
    }

    @Test
    void multiLineStringLineEndingsAndEscapedNewlines() {
        assertEquals("x", value("'''\r\nx'''").asString());
        assertEquals("x", value("\"\"\"\r\nx\"\"\"").asString());
        assertEquals("a\r\nb", value("'''a\r\nb'''").asString());
        assertEquals("a\tb", value("'a\tb'").asString());
        assertEquals("é!", value("\"é!\"").asString());
        assertEquals("aéA", value("\"\"\"a\\u00e9\\x41\"\"\"").asString());
        // A line-ending backslash swallows spaces, tabs and line breaks of either kind.
        assertEquals("ab", value("\"\"\"a\\\t\nb\"\"\"").asString());
        assertEquals("ab", value("\"\"\"a\\\r\nb\"\"\"").asString());
        assertEquals("ab", value("\"\"\"a\\ \r\nb\"\"\"").asString());
        assertEquals("ab", value("\"\"\"a\\\n \r\n b\"\"\"").asString());
        assertEquals("ab", value("\"\"\"a\\\n\t\n  b\"\"\"").asString());
        assertEquals("a", value("\"\"\"a\\\n\"\"\"").asString());
    }

    @Test
    void dottedKeysInsideInlineTablesAndArraysOfTables() {
        Toml.Table b = value("{b.c = 1, b.d = 2, e = [1]}").asTable();
        assertEquals(List.of("c", "d"), keys(b.get("b").value().asTable()));
        assertEquals("the integer 1", shown(b.get("e").value().asArray().get(0)));

        Toml.Table first = Toml.parse("[[a]]\na.b = 1").get("a").value().asArray().get(0).asTable();
        assertEquals("the integer 1", shown(first.get("a").value().asTable().get("b").value()));

        List<Toml.Value> items = Toml.parse("[[a]]\n[a.b]\nc = 1\n[[a]]\n[a.b]\nc = 2")
                .get("a")
                .value()
                .asArray();
        assertEquals(2, items.size());
        Toml.Table second = items.get(1).asTable().get("b").value().asTable();
        assertEquals("the integer 2", shown(second.get("c").value()));
    }

    @Test
    void bareNumbersInEveryAcceptedSpelling() {
        for (String number : List.of("0xa_b", "0xA_B", "0x1F_ffFF", "0o7_7", "0b1_0", "+0", "-0")) {
            assertEquals("the integer " + number, shown(value(number)), number);
        }
        for (String number : List.of("inf", "nan", "+nan", "-nan", "1E5", "1e+5", "1_000.0", "0.0e0", "0e0", "1.0_1",
                "1.00", "1e05", "1.5e-0_5")) {
            assertEquals(Toml.Kind.FLOAT, value(number).kind(), number);
        }
    }

    @Test
    void dateTimesInEveryAcceptedForm() {
        for (String dateTime : List.of(
                "1979-05-27T07:32:00+09:00",
                "1979-05-27T07:32:00-07:00",
                "1979-05-27T07:32:00.999999Z",
                "1979-05-27t07:32:00z",
                "1979-05-27T07:32:00",
                "1979-05-27T07:32",
                "1979-05-27T07:32:00.123456789123Z",
                "1979-05-27 07:32:00",
                "07:32:00.999",
                "07:32:00",
                "07:32:60",
                "00:00",
                "2024-02-29",
                "2000-02-29",
                "1979-12-31",
                "0979-05-27")) {
            assertEquals(Toml.Kind.DATETIME, value(dateTime).kind(), dateTime);
        }
        // The space before a comment or a line break is not a date-time separator.
        assertEquals(Toml.Kind.DATETIME, value("1979-05-27 # c").kind());
        assertEquals(Toml.Kind.DATETIME, value("1979-05-27 \n").kind());
    }

    /**
     * The crate took any hex digit beside `_` and no digit after a radix, and this reader still
     * does. No exclude key takes an integer, so such a value still fails, as the wrong type.
     */
    @Test
    void integersWithoutValidDigitsAreStillIntegers() {
        for (String number : List.of("1_a", "1_A", "0x", "0o", "0b")) {
            assertEquals("the integer " + number, shown(value(number)), number);
        }
    }
}
