package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
        assertEquals("EOF while parsing an array at line 1 column 1", error("["));
        assertEquals("EOF while parsing an array at line 1 column 2", error("[1"));
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
        assertEquals("expected null at line 1 column 8", error("{\"a\": nope}"));
        assertEquals("invalid number at line 1 column 3", error("[-]"));
        assertEquals("invalid number at line 1 column 4", error("[1e]"));
        assertEquals("control character (\\u0000-\\u001F) found while parsing a string at line 1 column 3", error("\"a\u0001\""));
        assertEquals("control character (\\u0000-\\u001F) found while parsing a string at line 1 column 5", error("\"tab\there\""));
    }

    @Test
    void malformedContainersNameTheOffendingColumn() {
        assertEquals("trailing comma at line 1 column 4", error("[1,]"));
        assertEquals("trailing comma at line 3 column 1", error("[\n1,\n]"));
        assertEquals("expected \",\" or \"]\" at line 1 column 4", error("[1 2]"));
        assertEquals("trailing comma at line 1 column 8", error("{\"a\":1,}"));
        assertEquals("key must be a string at line 1 column 2", error("{1:2}"));
        assertEquals("key must be a string at line 3 column 3", error("{\n  \"a\": 1,\n  x\n}"));
        assertEquals("expected \":\" at line 1 column 6", error("{\"a\" 1}"));
        assertEquals("expected \",\" or \"}\" at line 1 column 8", error("{\"a\":1 \"b\":2}"));
    }
}
