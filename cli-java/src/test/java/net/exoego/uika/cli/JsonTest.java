package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every expected string here is what serde_json 1.0.151 prints for the same input. */
class JsonTest {
    private static String error(String text) {
        return assertThrows(Json.ParseException.class, () -> Json.parse(text)).getMessage();
    }

    private static Json.Writer sample(Json.Writer json) {
        return json.beginObject()
                .key("a")
                .beginArray()
                .value(1)
                .beginObject()
                .key("b")
                .value((String) null)
                .key("c")
                .beginArray()
                .endArray()
                .endObject()
                .beginObject()
                .endObject()
                .endArray()
                .key("d")
                .beginObject()
                .endObject()
                .key("e")
                .value(true)
                .endObject();
    }

    @Test
    void prettyLayoutIndentsTwoSpacesAndKeepsEmptyContainersOnOneLine() {
        StringBuilder out = new StringBuilder();
        sample(new Json.Writer(out, true));
        assertEquals(
                """
                {
                  "a": [
                    1,
                    {
                      "b": null,
                      "c": []
                    },
                    {}
                  ],
                  "d": {},
                  "e": true
                }""",
                out.toString());
    }

    @Test
    void compactLayoutHasNoWhitespace() {
        StringBuilder out = new StringBuilder();
        sample(new Json.Writer(out, false));
        assertEquals("{\"a\":[1,{\"b\":null,\"c\":[]},{}],\"d\":{},\"e\":true}", out.toString());
    }

    /** The per-level table starts at 16 levels, so a deeper document has to grow it without losing a comma. */
    @Test
    void nestingPastSixteenLevelsKeepsEveryComma() {
        StringBuilder out = new StringBuilder();
        Json.Writer json = new Json.Writer(out, false);
        int depth = 40;
        for (int i = 0; i < depth; i++) {
            json.beginArray();
        }
        json.value(1).value(2);
        for (int i = 1; i < depth; i++) {
            json.endArray().value(3);
        }
        json.endArray();
        assertEquals("[".repeat(depth) + "1,2" + "],3".repeat(depth - 1) + "]", out.toString());
    }

    /** Short escapes where JSON has one, lowercase hex escapes for the other controls, and '/' and DEL left raw. */
    @Test
    void quoteEscapesOnlyWhatJsonRequires() {
        StringBuilder raw = new StringBuilder();
        for (char c = 0; c < 0x20; c++) {
            raw.append(c);
        }
        raw.append("\"\\/\u007fé");
        assertEquals(
                "\"\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\b\\t\\n\\u000b\\f\\r\\u000e\\u000f"
                        + "\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f"
                        + "\\\"\\\\/\u007fé\"",
                Json.quote(raw.toString()));
    }

    @Test
    void parsesEveryValueType() throws Json.ParseException {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", Arrays.asList(1L, -2L, 1.5, 1000.0, 100.0, 0.02, true, false, null, "s"));
        expected.put("b", Map.of());
        assertEquals(
                expected, Json.parse("{\"a\": [1, -2, 1.5, 1e3, 1E+2, 2e-2, true, false, null, \"s\"], \"b\": {}}"));
        assertEquals(42L, Json.parse("42"));
        assertEquals(Map.of(), Json.parse(" \t\r\n{ }\n"));
        assertEquals(List.of(), Json.parse("[ ]"));
    }

    @Test
    void objectsKeepTheirKeysInDocumentOrder() throws Json.ParseException {
        Map<?, ?> object = (Map<?, ?>) Json.parse("{\"b\":1,\"a\":2}");
        assertEquals(List.of("b", "a"), new ArrayList<>(object.keySet()));
    }

    @Test
    void decodesEveryEscape() throws Json.ParseException {
        assertEquals("plain", Json.parse("\"plain\""));
        assertEquals("\"\\/\b\f\n\r\t", Json.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\""));
        assertEquals("x\nyAz", Json.parse("\"x\\ny\\u0041z\""));
        assertEquals("é😀", Json.parse("\"\\u00E9\\ud83d\\ude00\""));
    }

    /** A dump cut off mid-write lands here, so the position is where the input ran out. */
    @Test
    void truncatedInputReportsWhereItEnded() {
        assertEquals("EOF while parsing a value at line 1 column 0", error(""));
        assertEquals("EOF while parsing a value at line 1 column 3", error("   "));
        assertEquals("EOF while parsing a value at line 3 column 0", error("\n\n"));
        assertEquals("EOF while parsing a value at line 1 column 3", error("[1,"));
        assertEquals("EOF while parsing a list at line 1 column 1", error("["));
        assertEquals("EOF while parsing a list at line 1 column 2", error("[1"));
        assertEquals("EOF while parsing an object at line 1 column 1", error("{"));
        assertEquals("EOF while parsing an object at line 1 column 4", error("{\"a\""));
        assertEquals("EOF while parsing an object at line 1 column 6", error("{\"a\":1"));
        assertEquals("EOF while parsing a string at line 1 column 4", error("\"abc"));
        assertEquals("EOF while parsing a string at line 1 column 3", error("\"a\\"));
        assertEquals("EOF while parsing a string at line 1 column 3", error("\"\\u"));
    }

    @Test
    void textAfterTheDocumentIsRejected() {
        assertEquals("trailing characters at line 1 column 4", error("{} x"));
        assertEquals("trailing characters at line 4 column 1", error("{\n\"a\":1}\n\nx"));
    }

    @Test
    void malformedValuesNameTheOffendingColumn() {
        assertEquals("expected value at line 1 column 1", error("x"));
        assertEquals("expected value at line 1 column 2", error("[,1]"));
        assertEquals("expected ident at line 1 column 8", error("{\"a\": nope}"));
        assertEquals("invalid number at line 1 column 3", error("[-]"));
        assertEquals("invalid number at line 1 column 4", error("[1e]"));
        assertEquals("control character (\\u0000-\\u001F) found while parsing a string at line 1 column 3", error("\"a\u0001\""));
        assertEquals("control character (\\u0000-\\u001F) found while parsing a string at line 1 column 5", error("\"tab\there\""));
    }

    @Test
    void malformedContainersNameTheOffendingColumn() {
        assertEquals("trailing comma at line 1 column 4", error("[1,]"));
        assertEquals("trailing comma at line 3 column 1", error("[\n1,\n]"));
        assertEquals("expected `,` or `]` at line 1 column 4", error("[1 2]"));
        assertEquals("trailing comma at line 1 column 8", error("{\"a\":1,}"));
        assertEquals("key must be a string at line 1 column 2", error("{1:2}"));
        assertEquals("key must be a string at line 3 column 3", error("{\n  \"a\": 1,\n  x\n}"));
        assertEquals("expected `:` at line 1 column 6", error("{\"a\" 1}"));
        assertEquals("expected `,` or `}` at line 1 column 8", error("{\"a\":1 \"b\":2}"));
    }

    /** After a comma serde expects a value, whatever container it is in. */
    @Test
    void inputEndingAfterAnObjectCommaIsAMissingValue() {
        assertEquals("EOF while parsing a value at line 1 column 7", error("{\"a\":1,"));
        assertEquals("EOF while parsing a value at line 1 column 8", error("{\"a\":1, "));
    }

    /** serde reads a literal one byte at a time and stops at the first byte that does not fit. */
    @Test
    void truncatedOrMisspelledLiteralsPointWhereTheyStop() {
        assertEquals("EOF while parsing a value at line 1 column 1", error("t"));
        assertEquals("EOF while parsing a value at line 1 column 3", error("tru"));
        assertEquals("expected ident at line 1 column 4", error("trux"));
        assertEquals("expected ident at line 1 column 5", error("[nul,1]"));
        assertEquals("expected ident at line 2 column 0", error("t\nrue"));
    }

    /** serde reads all four hex digits of a unicode escape before it checks them, so the position is past the fourth byte. */
    @Test
    void escapeErrorsPointPastTheBytesRead() {
        assertEquals("invalid escape at line 1 column 3", error("\"\\q\""));
        assertEquals("invalid escape at line 1 column 3", error("\"\\é\""));
        assertEquals("invalid escape at line 1 column 7", error("\"\\uZZZZ\""));
        assertEquals("invalid escape at line 1 column 7", error("\"\\u12G4\""));
        assertEquals("invalid escape at line 1 column 7", error("\"\\u12é\""));
        assertEquals("invalid escape at line 1 column 7", error("\"\\u+123\""));
        assertEquals("invalid escape at line 1 column 7", error("\"\\u-123\""));
        assertEquals("EOF while parsing a string at line 1 column 5", error("\"\\u12"));
        assertEquals("EOF while parsing a string at line 1 column 6", error("\"\\u12\""));
    }

    @Test
    void surrogateEscapesMustComeInPairs() {
        assertEquals("unexpected end of hex escape at line 1 column 8", error("\"\\ud83d\""));
        assertEquals("unexpected end of hex escape at line 1 column 9", error("\"\\ud83d\\n\""));
        assertEquals("lone leading surrogate in hex escape at line 1 column 13", error("\"\\ud83d\\u0041\""));
        assertEquals("lone leading surrogate in hex escape at line 1 column 7", error("\"\\udc00\""));
        assertEquals("EOF while parsing a string at line 1 column 7", error("\"\\ud83d"));
    }

    @Test
    void aRawNewlineInAStringIsReportedOnTheNextLine() {
        String control = "control character (\\u0000-\\u001F) found while parsing a string";
        assertEquals(control + " at line 2 column 0", error("\"a\nb\""));
        assertEquals(control + " at line 2 column 0", error("{\"a\nb\":1}"));
    }

    @Test
    void malformedNumbersAreWordedLikeSerde() {
        assertEquals("EOF while parsing a value at line 1 column 1", error("-"));
        assertEquals("EOF while parsing a value at line 1 column 2", error("1."));
        assertEquals("EOF while parsing a value at line 1 column 2", error("1e"));
        assertEquals("EOF while parsing a value at line 1 column 3", error("1e+"));
        assertEquals("invalid number at line 2 column 0", error("1.\n"));
        assertEquals("invalid number at line 1 column 4", error("[1.]"));
        assertEquals("invalid number at line 1 column 2", error("01"));
        assertEquals("invalid number at line 1 column 3", error("-01"));
        assertEquals("invalid number at line 1 column 2", error("--1"));
        assertEquals("invalid number at line 1 column 2", error("-x"));
        assertEquals("trailing characters at line 1 column 2", error("1-2"));
        assertEquals("trailing characters at line 1 column 4", error("1.2.3"));
    }

    @Test
    void numbersBeyondTheDoubleRangeAreRejected() {
        assertEquals("number out of range at line 1 column 5", error("1e400"));
        assertEquals("number out of range at line 1 column 6", error("-1e400"));
        assertEquals("number out of range at line 1 column 6", error("[1e400]"));
        assertEquals("number out of range at line 1 column 311", error("1" + "0".repeat(310)));
        assertEquals("number out of range at line 1 column 12", error("1e2147483648"));
    }

    /** serde keeps a u64 exact, turns -0 and anything below i64 into a float, and scales floats its own way. */
    @Test
    void numbersTakeSerdesKindAndValue() throws Json.ParseException {
        assertEquals(new BigInteger("9223372036854775808"), Json.parse("9223372036854775808"));
        assertEquals(new BigInteger("18446744073709551615"), Json.parse("18446744073709551615"));
        assertEquals(1.8446744073709552e19, Json.parse("18446744073709551616"));
        assertEquals(Long.MIN_VALUE, Json.parse("-9223372036854775808"));
        assertEquals(-9.223372036854775808e18, Json.parse("-9223372036854775809"));
        assertEquals(-0.0, Json.parse("-0"));
        assertEquals(0.0, Json.parse("1e-400"));
        assertEquals(0.0, Json.parse("0e2147483648"));
        // Double.parseDouble gives 3e23. serde multiplies 3 by the double nearest 1e23.
        assertEquals(2.9999999999999997e23, Json.parse("3e23"));
    }

    @Test
    void columnsCountUtf8Bytes() {
        assertEquals("expected value at line 1 column 8", error("[\"é\", x]"));
        assertEquals("expected value at line 1 column 10", error("[\"😀\", x]"));
        assertEquals("expected `:` at line 2 column 6", error("{\"é\":1,\n\"è\" x}"));
        assertEquals("expected value at line 1 column 1", error("\ufeff{}"));
    }

    @Test
    void nestingStopsAtSerdesRecursionLimit() throws Json.ParseException {
        Object nested = Json.parse("[".repeat(127) + "]".repeat(127));
        for (int depth = 1; depth < 127; depth++) {
            nested = ((List<?>) nested).get(0);
        }
        assertEquals(List.of(), nested);
        assertEquals("recursion limit exceeded at line 1 column 128", error("[".repeat(128) + "]".repeat(128)));
        assertEquals("recursion limit exceeded at line 1 column 636", error("{\"a\":".repeat(128) + "1" + "}".repeat(128)));
    }
}
