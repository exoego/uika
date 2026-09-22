package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Documents the syntax errors the reader raises. A change that alters an expected message is a
 * change in what users see.
 */
class TomlErrorTest {
    private static final String EMPTY_KEY = "unquoted keys cannot be empty, expected letters, numbers, `-`, `_`";
    private static final String INVALID_KEY = "invalid unquoted key, expected letters, numbers, `-`, `_`";
    private static final String UNQUOTED = "string values must be quoted, expected literal string";
    private static final String LONE_CR = "carriage return must be followed by newline, expected newline";
    private static final String UNDERSCORE = "`_` may only go between digits, expected nothing";

    private static String error(String input) {
        return assertThrows(Toml.Error.class, () -> Toml.parse(input), input).render("e.toml");
    }

    /** The rendering without its excerpt, as "line L, column C: description". */
    private static String at(String input) {
        String[] lines = error(input).split("\n");
        return lines[0].substring("e.toml: TOML parse error at ".length()) + ": " + lines[lines.length - 1];
    }

    /** The caret line, whose width is the span in bytes. */
    private static String caret(String input) {
        return error(input).split("\n")[3];
    }

    private static String valueError(String value) {
        return assertThrows(Toml.Error.class, () -> Toml.parse("a = " + value), value).getMessage();
    }

    @Test
    void aFirstByteThatOnlyStartsLikeAByteOrderMarkIsKept() {
        assertEquals(
                "e.toml: TOML parse error at line 1, column 1\n  |\n1 | \uFEC0 = 1\n  | ^^^\n" + INVALID_KEY,
                error("\uFEC0 = 1"));
        assertEquals("line 1, column 1: " + INVALID_KEY, at("\uF000 = 1"));
    }

    @Test
    void tokensCutShortByALineBreakOrTheEnd() {
        assertEquals(
                """
                e.toml: TOML parse error at line 1, column 9
                  |
                1 | a = 'abc
                  |         ^
                invalid literal string, expected `'`""",
                error("a = 'abc\nb = 1"));
        assertEquals(
                """
                e.toml: TOML parse error at line 1, column 10
                  |
                1 | a = "abc\\
                  |          ^
                invalid basic string, expected `"`""",
                error("a = \"abc\\"));
        assertEquals("line 1, column 12: invalid multi-line basic string, expected `\"`", at("a = \"\"\"abc\\"));
        assertEquals("line 1, column 6: invalid literal string, expected `'`", at("a = '"));
        assertEquals("line 1, column 6: invalid basic string, expected `\"`", at("a = \""));
        assertEquals("line 1, column 8: invalid multi-line literal string, expected `'`", at("a = '''"));
        assertEquals("line 1, column 8: invalid multi-line basic string, expected `\"`", at("a = \"\"\""));
        assertEquals("line 1, column 9: invalid multi-line literal string, expected `'`", at("a = '''x"));
        assertEquals("line 1, column 9: invalid multi-line basic string, expected `\"`", at("a = \"\"\"x"));
        assertEquals("line 1, column 7: " + LONE_CR, at("a = 1\r"));
        // Only two extra quotes join the string, so the third one is left over.
        assertEquals("line 1, column 14: unexpected key or value, expected newline, `#`", at("a = '''x''''''"));
    }

    @Test
    void anUnclosedValuePointsPastItsLastSignificantToken() {
        String array =
                """
                e.toml: TOML parse error at line 1, column 8
                  |
                1 | a = [1, # c
                  |        ^
                unclosed array, expected `]`""";
        assertEquals(array, error("a = [1, # c\n"));
        assertEquals(array, error("a = [1, # c\n  \n"));
        assertEquals("line 1, column 12: unclosed inline table, expected `}`", at("a = {b = 1, # c\n"));
    }

    /** A comment that runs to the end of the input used to hide the unclosed value behind a crash. */
    @Test
    void aCommentAtTheEndStillReportsTheUnclosedValue() {
        assertEquals(
                """
                e.toml: TOML parse error at line 1, column 6
                  |
                1 | a = [# c
                  |      ^
                unclosed array, expected `]`""",
                error("a = [# c"));
        assertEquals("line 1, column 7: unclosed array, expected `]`", at("a = [1 # c"));
        assertEquals("line 1, column 6: unclosed inline table, expected `}`", at("a = {# c"));
        assertEquals("line 1, column 11: unclosed inline table, expected `}`", at("a = {b = 1 # c"));
    }

    @Test
    void aHeaderWithoutAUsableKeyReportsTheEmptyKey() {
        assertEquals(
                """
                e.toml: TOML parse error at line 1, column 2
                  |
                1 | [=]
                  |  ^
                unquoted keys cannot be empty, expected letters, numbers, `-`, `_`""",
                error("[=]"));
        for (String header : List.of("[,]", "[", "[= ]\n", "[=\nb = 1", "[= # c\n", "[.a]", "[. a]")) {
            assertEquals("line 1, column 2: " + EMPTY_KEY, at(header), header);
        }
        assertEquals("line 1, column 3: " + EMPTY_KEY, at("[ "));
        assertEquals("line 1, column 4: " + EMPTY_KEY, at("[a.]"));
        assertEquals("line 1, column 4: " + EMPTY_KEY, at("[a.=]"));
        // The rest of a broken header line is skipped, but a comment on it is still checked.
        assertEquals(
                "line 1, column 6: invalid comment character, expected printable characters", at("[= # \u0001\n"));
    }

    @Test
    void anUnclosedHeaderPointsRightAfterItsKey() {
        assertEquals(
                """
                e.toml: TOML parse error at line 1, column 3
                  |
                1 | [a b]
                  |   ^
                unclosed table, expected `]`""",
                error("[a b]"));
        assertEquals("line 1, column 4: unclosed table, expected `]`", at("[ a b ]"));
        assertEquals("line 1, column 4: unclosed array table, expected `]]`", at("[[a b]]"));
    }

    @Test
    void aStrayDotOrEqualsSignLeavesAnEmptyKey() {
        assertEquals("line 1, column 1: " + EMPTY_KEY, at(".a = 1"));
        assertEquals("line 1, column 1: " + EMPTY_KEY, at(". = 1"));
        assertEquals("line 1, column 3: " + EMPTY_KEY, at("a..b = 1"));
        assertEquals("line 1, column 4: " + EMPTY_KEY, at("a. .b = 1"));
        assertEquals("line 1, column 6: " + EMPTY_KEY, at("a = {.b = 1}"));
        assertEquals("line 1, column 6: " + EMPTY_KEY, at("a = {= 1}"));
    }

    @Test
    void keysThatAreNeitherBareNorOnOneLine() {
        assertEquals(
                """
                e.toml: TOML parse error at line 1, column 1
                  |
                1 | '''a''' = 1
                  | ^^^^^^^
                keys cannot be multi-line literal strings, expected basic string, literal string""",
                error("'''a''' = 1"));
        for (String key : List.of("a~b", "a^b", "a:b", "a|b", "a@b")) {
            assertEquals("line 1, column 2: " + INVALID_KEY, at(key + " = 1"), key);
        }
    }

    @Test
    void inlineTableElementsOutOfPlace() {
        assertEquals("line 1, column 6: missing inline table opening, expected `{`", at("a = [}]"));
        assertEquals("line 1, column 10: missing array opening, expected `[`", at("a = {b = ]}"));
        assertEquals("line 1, column 6: invalid inline table element, expected key", at("a = {]}"));
        assertEquals("line 1, column 8: invalid inline table element, expected `=`", at("a = {b ]}"));
        assertEquals("line 1, column 12: invalid inline table element, expected `,`", at("a = {b = 1 ]}"));
        assertEquals(
                "line 1, column 10: extra assignment between key-value pairs, expected value", at("a = {b = = 1}"));
        assertEquals("line 1, column 6: missing key for inline table element, expected key", at("a = {{}}"));
        assertEquals("line 1, column 6: missing key for inline table element, expected key", at("a = {[]}"));
        assertEquals("line 1, column 12: missing key for inline table element, expected `,`", at("a = {b = 1 [}"));
        assertEquals("line 1, column 12: missing key for inline table element, expected `,`", at("a = {b = 1 {}"));
        for (String table : List.of("{b [}", "{b {}", "{b c = 1}")) {
            assertEquals(
                    "line 1, column 8: missing assignment between key-value pairs, expected `=`",
                    at("a = " + table),
                    table);
        }
        // A key cut off by the closing brace gets an empty stand-in value, rejected as a string.
        assertEquals("line 1, column 9: invalid literal string, expected `'`", at("a = {b =}"));
    }

    @Test
    void dottedKeysInsideAnInlineTable() {
        assertEquals(
                "line 1, column 13: cannot extend value of type integer with a dotted key", at("a = {b = 1, b.c = 2}"));
        assertEquals(
                "line 1, column 14: cannot extend value of type array with a dotted key", at("a = {b = [], b.c = 1}"));
        assertEquals(
                "line 1, column 17: cannot extend value of type integer with a dotted key",
                at("a = {b.c = 1, b.c.d = 2}"));
        assertEquals("line 1, column 14: duplicate key", at("a = {b = {}, b.c = 1}"));
        assertEquals("line 1, column 15: duplicate key", at("a = {b.c = 1, b = 2}"));
        assertEquals("line 1, column 17: duplicate key", at("a = {b.c = 1, b.c = 2}"));
    }

    @Test
    void dottedKeysAndHeadersAgainstTablesDefinedElsewhere() {
        assertEquals("line 3, column 1: duplicate key", at("[a.b]\n[a]\nb.c = 1"));
        assertEquals("line 3, column 3: duplicate key", at("[[t.a]]\n[t]\na.c = 1"));
        assertEquals("line 2, column 2: duplicate key", at("a.b = 1\n[a]"));
        assertEquals("line 2, column 4: duplicate key", at("a.b = 1\n[a.b]"));
        assertEquals("line 3, column 2: duplicate key", at("[a]\n[a.b]\n[a]"));
    }

    @Test
    void invalidCharactersInStrings() {
        assertEquals(
                "line 1, column 7: invalid basic string, expected non-double-quote visible characters, `\\`",
                at("a = \"a\u007fb\""));
        // A run of bad characters is one span, up to a good character, a backslash or the end.
        assertEquals("  |      ^^", caret("a = \"\u0001\u0002\\n\""));
        assertEquals("  |      ^^", caret("a = \"\u0001\u0002x\""));
        assertEquals("  |       ^^", caret("a = \"a\u0001\u0002\""));
        assertEquals(
                "line 1, column 9: invalid multi-line basic string, expected `\\`, characters",
                at("a = \"\"\"a\u0001\u0002x\"\"\""));
        assertEquals("  |         ^^", caret("a = \"\"\"a\u0001\u0002x\"\"\""));
        assertEquals("  |         ^^", caret("a = \"\"\"a\u0001\u0002\\n\"\"\""));
        assertEquals("  |         ^^", caret("a = \"\"\"a\u0001\u0002\rb\"\"\""));
        assertEquals("  |         ^^", caret("a = \"\"\"a\u0001\u0002\"\"\""));
        // Literal strings point at the first bad character only.
        assertEquals(
                "line 1, column 7: invalid literal string, expected non-single-quote visible characters",
                at("a = 'a\u007f\u007fb'"));
        assertEquals("  |       ^", caret("a = 'a\u007f\u007fb'"));
        assertEquals(
                "line 1, column 9: invalid multi-line literal string, expected non-single-quote characters",
                at("a = '''a\u0001b'''"));
    }

    @Test
    void aCarriageReturnOutsideALineBreak() {
        assertEquals("line 1, column 5: " + LONE_CR, at("# a\rb"));
        assertEquals("line 1, column 9: " + LONE_CR, at("a = '''\rx'''"));
        assertEquals("line 1, column 10: " + LONE_CR, at("a = '''a\rb'''"));
        assertEquals("line 1, column 10: " + LONE_CR, at("a = '''a\r'''"));
        assertEquals("line 1, column 10: " + LONE_CR, at("a = \"\"\"a\r\"\"\""));
        assertEquals("line 1, column 11: " + LONE_CR, at("a = \"\"\"a\\\rb\"\"\""));
        assertEquals("line 1, column 11: " + LONE_CR, at("a = \"\"\"a\\\r\"\"\""));
        assertEquals("line 2, column 3: " + LONE_CR, at("a = \"\"\"a\\\n \rb\"\"\""));
        assertEquals("line 2, column 2: " + LONE_CR, at("a = \"\"\"a\\\n\r\"\"\""));
    }

    @Test
    void malformedEscapes() {
        String missing = "missing escaped value, expected `b`, `e`, `f`, `n`, `r`, `\\`, `\"`, `x`, `u`, `U`";
        // At the end of the input the quote of a `\"` escape closes the string and strands the backslash.
        assertEquals("line 1, column 10: " + missing, at("a = \"abc\\\""));
        assertEquals("line 1, column 12: " + missing, at("a = \"\"\"abc\\\"\"\""));
        assertEquals("line 1, column 9: " + missing, at("a = \"\"\"\\\"\"\""));
        String digits = "too few unicode value digits, expected unicode hexadecimal value";
        assertEquals("line 1, column 10: " + digits, at("a = \"\\u12x4\""));
        assertEquals("line 1, column 9: " + digits, at("a = \"\\x4\""));
        assertEquals("line 1, column 15: " + digits, at("a = \"\\U0001F60\""));
        assertEquals(
                "line 1, column 13: invalid multi-line basic string, expected newline", at("a = \"\"\"a\\   \"\"\""));
    }

    @Test
    void malformedBareValues() {
        assertEquals("  |     ^^^^^", caret("a = tru e"));
        for (String value : List.of("tru e", "+", "-", "+a", "0x1 2", "1 2", "1x")) {
            assertEquals(UNQUOTED, valueError(value), value);
        }
        assertEquals("missing opening quote, expected `'''`", valueError("abc'''"));
        assertEquals("missing opening quote, expected `\"\"\"`", valueError("abc\"\"\""));
        assertEquals("missing opening quote, expected `'`", valueError("abc'"));
        assertEquals("line 1, column 5: invalid float, expected `nan`", at("a = NaN"));
        assertEquals("line 1, column 5: invalid float, expected `inf`", at("a = infinity"));
        assertEquals("line 1, column 6: invalid float, expected `inf`", at("a = +Inf"));
        assertEquals("line 1, column 6: invalid float, expected `inf`", at("a = -Inf"));
        assertEquals("line 1, column 6: invalid float, expected `nan`", at("a = +NaN"));
        assertEquals("line 1, column 6: redundant numeric sign, expected nothing", at("a = ++1"));
        assertEquals("line 1, column 6: redundant numeric sign, expected nothing", at("a = --1"));
        // The error points at the sign, not at the dot.
        assertEquals("line 1, column 5: invalid mantissa, expected digits", at("a = +.5"));
        assertEquals("line 1, column 5: invalid mantissa, expected digits", at("a = -.5"));
    }

    @Test
    void malformedNumbers() {
        assertEquals("line 1, column 5: redundant integer number prefix, expected nothing", at("a = 0d10"));
        assertEquals("  |     ^^", caret("a = 0D10"));
        assertEquals("line 1, column 5: radix must be lowercase, expected `0b`", at("a = 0B1"));
        assertEquals("line 1, column 5: radix must be lowercase, expected `0o`", at("a = 0O7"));
        assertEquals("line 1, column 5: integers with a radix cannot be signed, expected nothing", at("a = -0x1"));
        assertEquals("line 1, column 7: unexpected sign, expected nothing", at("a = 0x+1"));
        assertEquals("line 1, column 7: unexpected sign, expected nothing", at("a = 0x-1"));
        assertEquals("line 1, column 8: invalid hexadecimal number", at("a = 0x1-2"));
        assertEquals("line 1, column 8: invalid hexadecimal number", at("a = 0x1.5"));
        assertEquals("line 1, column 7: invalid hexadecimal number", at("a = 0xé"));
        assertEquals("line 1, column 7: invalid octal number", at("a = 0o8"));
        assertEquals("line 1, column 7: invalid binary number", at("a = 0b2"));
        assertEquals("line 1, column 5: unexpected leading zero, expected nothing", at("a = 00"));
        assertEquals("line 1, column 6: unexpected leading zero, expected nothing", at("a = -01"));
        assertEquals("line 1, column 5: unexpected leading zero, expected nothing", at("a = 0_0"));
        assertEquals("line 1, column 5: unexpected leading zero, expected nothing", at("a = 01.5"));
        assertEquals("line 1, column 8: invalid float, expected nothing", at("a = 1.5x"));
        assertEquals("line 1, column 8: invalid float, expected nothing", at("a = 1e1.5"));
        assertEquals("line 1, column 8: invalid float, expected nothing", at("a = 0.0.0"));
        assertEquals("line 1, column 7: invalid fraction, expected digits", at("a = 1.e5"));
        assertEquals("line 1, column 7: invalid exponent, expected digits", at("a = 1ee5"));
        assertEquals("line 1, column 8: invalid exponent, expected digits", at("a = 1e+"));
        assertEquals("line 1, column 5: " + UNDERSCORE, at("a = _1"));
        assertEquals("line 1, column 6: " + UNDERSCORE, at("a = 1_"));
        assertEquals("line 1, column 7: " + UNDERSCORE, at("a = 1._5"));
        assertEquals("line 1, column 6: " + UNDERSCORE, at("a = 1_.5"));
        assertEquals("line 1, column 7: " + UNDERSCORE, at("a = 1e_5"));
        assertEquals("line 1, column 7: " + UNDERSCORE, at("a = 0x_1"));
        assertEquals("line 1, column 8: " + UNDERSCORE, at("a = 0x1_"));
        // Beside an underscore any hex digit is taken, but nothing else.
        for (String number : List.of("1_z", "1_Z", "1_@")) {
            assertEquals("line 1, column 6: " + UNDERSCORE, at("a = " + number), number);
        }
    }

    @Test
    void malformedDates() {
        assertEquals(
                """
                e.toml: TOML parse error at line 1, column 5
                  |
                1 | a = 1979-02-30
                  |     ^^^^^^^^^^
                invalid date, expected day between 01 and 28""",
                error("a = 1979-02-30"));
        assertEquals("invalid datetime, expected year or hour", valueError("+1979-05-27"));
        assertEquals("invalid datetime, expected year or hour", valueError("-1979-05-27"));
        assertEquals("invalid date, expected month", valueError("1979-"));
        assertEquals("invalid date, expected `-` (MM-DD)", valueError("1979-05"));
        assertEquals("invalid date, expected day", valueError("1979-05-"));
        assertEquals("invalid date, expected a four-digit year (YYYY)", valueError("979-05-27"));
        assertEquals("invalid date, expected a four-digit year (YYYY)", valueError("19790-05-27"));
        assertEquals("invalid date, expected a two-digit month (MM)", valueError("1979-5-27"));
        assertEquals("invalid date, expected a two-digit day (DD)", valueError("1979-05-7"));
        assertEquals("invalid date, expected month between 01 and 12", valueError("1979-00-01"));
        assertEquals("invalid date, expected day between 01 and 31", valueError("1979-01-00"));
        assertEquals("invalid date, expected day between 01 and 31", valueError("1979-01-32"));
        // Century years are leap years only when divisible by 400.
        assertEquals("invalid date, expected day between 01 and 28", valueError("1900-02-29"));
        assertEquals("invalid date, expected day between 01 and 28", valueError("2023-02-29"));
        for (String date : List.of("1979-04-31", "1979-06-31", "1979-09-31", "1979-11-31")) {
            assertEquals("invalid date, expected day between 01 and 30", valueError(date), date);
        }
        for (String date : List.of("1979-05-27x", "1979-05-27x07:00", "1979-05-27_", "1979-05-27.5")) {
            assertEquals("invalid date-time, expected `T` between date and time", valueError(date), date);
        }
    }

    @Test
    void malformedTimesAndOffsets() {
        assertEquals("invalid datetime, expected year or hour", valueError("+07:32"));
        assertEquals("invalid time, expected hour", valueError("1979-05-27T"));
        assertEquals("invalid time, expected hour", valueError("1979-05-27 x"));
        assertEquals("invalid time, expected `:` (HH:MM)", valueError("1979-05-27T07"));
        assertEquals("invalid time, expected `:` (HH:MM)", valueError("1979-05-27T0a:00"));
        assertEquals("invalid time, expected minute", valueError("1979-05-27T07:"));
        assertEquals("invalid time, expected minute", valueError("07:"));
        assertEquals("invalid time, expected second", valueError("07:32:"));
        assertEquals("invalid time, expected nanosecond", valueError("07:32:00."));
        assertEquals("invalid time, expected nanosecond", valueError("1979-05-27T07:32:00.Z"));
        assertEquals("invalid time, expected a two-digit hour (HH)", valueError("7:32"));
        assertEquals("invalid time, expected a two-digit minute (MM)", valueError("07:3"));
        assertEquals("invalid time, expected a two-digit second (SS)", valueError("07:32:0"));
        assertEquals("invalid time, expected hour between 00 and 23", valueError("24:00"));
        assertEquals("invalid time, expected minute between 00 and 59", valueError("07:60"));
        assertEquals("invalid time, expected second between 00 and 60", valueError("07:32:61"));
        String time = "1979-05-27T07:32:00";
        assertEquals("invalid offset, expected hour", valueError(time + "+"));
        assertEquals("invalid offset, expected hour", valueError(time + "++09:00"));
        assertEquals("invalid offset, expected `:` (HH:MM)", valueError(time + "+09"));
        assertEquals("invalid offset, expected minute", valueError(time + "+09:"));
        assertEquals("invalid offset, expected a two-digit hour (HH)", valueError(time + "+9:00"));
        assertEquals("invalid offset, expected a two-digit minute (MM)", valueError(time + "+09:0"));
        assertEquals("invalid offset, expected hours between 00 and 23", valueError(time + "+24:00"));
        assertEquals("invalid offset, expected minutes between 00 and 59", valueError(time + "+09:60"));
        assertEquals("invalid offset, expected `Z`, +OFFSET, -OFFSET", valueError(time + "x"));
        // Leftovers after a complete value, including an offset on a time without a date.
        for (String value : List.of(time + "Zx", time + "+09:00:00", time + "-09:00Z", "07:32:00Z", "12:34:56-07:00",
                "07:32 x")) {
            assertEquals("invalid datetime", valueError(value), value);
        }
    }
}
