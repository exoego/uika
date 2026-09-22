package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer, so the jar ships no dependency.
 *
 * <p>The writer reproduces serde_json byte for byte (escaping, and the two-space pretty
 * layout), because the goldens and the build-tool plugins' parsers were pinned against it.
 */
final class Json {
    private Json() {}

    // ---- writer ----

    /** Streaming writer. Pretty mode matches serde_json's PrettyFormatter. */
    static final class Writer {
        private final StringBuilder out;
        private final boolean pretty;
        private int depth;
        /** Per nesting level: whether the container already holds an element. */
        private boolean[] hasElement = new boolean[16];
        private boolean afterKey;

        Writer(StringBuilder out, boolean pretty) {
            this.out = out;
            this.pretty = pretty;
        }

        private void beforeValue() {
            if (afterKey) {
                afterKey = false;
                return;
            }
            if (depth > 0) {
                if (hasElement[depth]) {
                    out.append(',');
                }
                hasElement[depth] = true;
                newline();
            }
        }

        private void newline() {
            if (pretty) {
                out.append('\n');
                for (int i = 0; i < depth; i++) {
                    out.append("  ");
                }
            }
        }

        private void open(char c) {
            beforeValue();
            out.append(c);
            depth++;
            if (depth == hasElement.length) {
                hasElement = java.util.Arrays.copyOf(hasElement, depth * 2);
            }
            hasElement[depth] = false;
        }

        private void close(char c) {
            boolean any = hasElement[depth];
            depth--;
            if (any) {
                newline();
            }
            out.append(c);
        }

        Writer beginObject() {
            open('{');
            return this;
        }

        Writer endObject() {
            close('}');
            return this;
        }

        Writer beginArray() {
            open('[');
            return this;
        }

        Writer endArray() {
            close(']');
            return this;
        }

        Writer key(String name) {
            beforeValue();
            string(out, name);
            out.append(pretty ? ": " : ":");
            afterKey = true;
            return this;
        }

        Writer value(String s) {
            beforeValue();
            if (s == null) {
                out.append("null");
            } else {
                string(out, s);
            }
            return this;
        }

        Writer value(long n) {
            beforeValue();
            out.append(n);
            return this;
        }

        Writer value(boolean b) {
            beforeValue();
            out.append(b);
            return this;
        }

        /** The symbol's text. */
        Writer sym(int sym) {
            return value(Intern.str(sym));
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xf]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        string(out, s);
        return out.toString();
    }

    // ---- reader ----

    /** A syntax error, positioned the way serde_json words it. */
    static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Parses a document into {@code Map<String,Object>} (insertion-ordered), {@code List<Object>},
     * String, Long, Double, Boolean or null.
     */
    static Object parse(String text) throws ParseException {
        Reader reader = new Reader(text);
        reader.skipWhitespace();
        Object value = reader.value();
        reader.skipWhitespace();
        if (reader.pos < text.length()) {
            throw reader.error("trailing characters");
        }
        return value;
    }

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        ParseException error(String what) {
            int line = 1;
            int column = 0;
            int end = Math.min(pos, text.length());
            for (int i = 0; i < end; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    column = 0;
                } else {
                    column++;
                }
            }
            if (pos < text.length()) {
                column++;
            }
            return new ParseException(what + " at line " + line + " column " + column);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() throws ParseException {
            if (pos >= text.length()) {
                throw error("EOF while parsing a value");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("expected value");
            }
        }

        private Object literal(String word, Object value) throws ParseException {
            if (!text.startsWith(word, pos)) {
                pos = Math.min(text.length(), pos + 1);
                throw error("expected ident");
            }
            pos += word.length();
            return value;
        }

        private Object number() throws ParseException {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            boolean integral = true;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, pos);
            try {
                if (integral) {
                    return Long.parseLong(literal);
                }
                return Double.parseDouble(literal);
            } catch (NumberFormatException e) {
                throw error("invalid number");
            }
        }

        private String string() throws ParseException {
            pos++;
            StringBuilder sb = null;
            int start = pos;
            while (true) {
                if (pos >= text.length()) {
                    throw error("EOF while parsing a string");
                }
                char c = text.charAt(pos);
                if (c == '"') {
                    String tail = text.substring(start, pos);
                    pos++;
                    return sb == null ? tail : sb.append(tail).toString();
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(text, start, pos);
                    pos++;
                    if (pos >= text.length()) {
                        throw error("EOF while parsing a string");
                    }
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("EOF while parsing a string");
                            }
                            try {
                                sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("invalid escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("invalid escape");
                    }
                    start = pos;
                    continue;
                }
                if (c < 0x20) {
                    throw error("control character (\\u0000-\\u001F) found while parsing a string");
                }
                pos++;
            }
        }

        private List<Object> array() throws ParseException {
            pos++;
            List<Object> out = new ArrayList<>();
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("EOF while parsing a list");
            }
            if (text.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                // After a comma, running out of input is a missing value, not an open list.
                if (pos < text.length() && text.charAt(pos) == ']') {
                    throw error("trailing comma");
                }
                out.add(value());
                skipWhitespace();
                if (pos >= text.length()) {
                    throw error("EOF while parsing a list");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return out;
                } else {
                    throw error("expected `,` or `]`");
                }
            }
        }

        private Map<String, Object> object() throws ParseException {
            pos++;
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (pos >= text.length()) {
                    throw error("EOF while parsing an object");
                }
                if (text.charAt(pos) == '}') {
                    throw error("trailing comma");
                }
                if (text.charAt(pos) != '"') {
                    throw error("key must be a string");
                }
                String key = string();
                skipWhitespace();
                if (pos >= text.length()) {
                    throw error("EOF while parsing an object");
                }
                if (text.charAt(pos) != ':') {
                    throw error("expected `:`");
                }
                pos++;
                skipWhitespace();
                out.put(key, value());
                skipWhitespace();
                if (pos >= text.length()) {
                    throw error("EOF while parsing an object");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return out;
                } else {
                    throw error("expected `,` or `}`");
                }
            }
        }
    }
}
