package net.exoego.uika.cli;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer, so the jar ships no dependency.
 *
 * <p>The writer reproduces serde_json byte for byte (escaping, and the two-space pretty
 * layout), because the goldens and the build-tool plugins' parsers were pinned against it.
 * The reader matches serde_json 1.0.151 on malformed input too, message and position, since a
 * broken classpath dump prints it.
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

    /**
     * A float the way serde_json's typed-read errors print it (zmij's format): the shortest
     * digits, plain from 1e-5 up to below 1e16, otherwise like {@code 1.5e+20}.
     */
    static String errorFloat(double value) {
        if (value == 0) {
            return 1 / value < 0 ? "-0.0" : "0.0";
        }
        BigDecimal shortest = Text.shortestDecimal(Math.abs(value)).stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        int exponent = digits.length() - 1 - shortest.scale();
        String sign = value < 0 ? "-" : "";
        if (exponent >= -5 && exponent <= 15) {
            String plain = shortest.toPlainString();
            return sign + (plain.indexOf('.') >= 0 ? plain : plain + ".0");
        }
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
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
     * String, Long, BigInteger, Double, Boolean or null. A number gets the kind serde_json gives
     * it: BigInteger stands for a u64 above Long.MAX_VALUE, and {@code -0} is a Double.
     */
    static Object parse(String text) throws ParseException {
        Reader reader = new Reader(text.getBytes(StandardCharsets.UTF_8));
        Object value = reader.value();
        if (reader.skipWhitespace()) {
            throw reader.peekError("trailing characters");
        }
        return value;
    }

    /** Follows serde_json's Deserializer step by step. It reads bytes because serde counts the column in bytes. */
    private static final class Reader {
        private static final long U64_MAX_DIV_10 = Long.divideUnsigned(-1L, 10);
        /** serde's POW10 table, correctly rounded like its literals. */
        private static final double[] POW10 = new double[309];

        static {
            for (int i = 0; i < POW10.length; i++) {
                POW10[i] = Double.parseDouble("1e" + i);
            }
        }

        private final byte[] input;
        private int index;
        /** serde_json's recursion limit: the 128th nested container is an error. */
        private int remainingDepth = 128;

        Reader(byte[] input) {
            this.input = input;
        }

        /** serde's position(): after the bytes consumed so far. */
        ParseException error(String what) {
            return errorAt(what, index);
        }

        /** serde's peek_position(): one byte further, so it names the byte peeked at. */
        ParseException peekError(String what) {
            return errorAt(what, Math.min(input.length, index + 1));
        }

        private ParseException errorAt(String what, int at) {
            int line = 1;
            int startOfLine = 0;
            for (int i = 0; i < at; i++) {
                if (input[i] == '\n') {
                    line++;
                    startOfLine = i + 1;
                }
            }
            return new ParseException(what + " at line " + line + " column " + (at - startOfLine));
        }

        /** The next byte, or -1 at the end. */
        private int peek() {
            return index < input.length ? input[index] & 0xff : -1;
        }

        private static boolean isDigit(int b) {
            return b >= '0' && b <= '9';
        }

        /** Whether a byte follows the whitespace. */
        boolean skipWhitespace() {
            while (index < input.length) {
                byte b = input[index];
                if (b != ' ' && b != '\n' && b != '\t' && b != '\r') {
                    return true;
                }
                index++;
            }
            return false;
        }

        Object value() throws ParseException {
            if (!skipWhitespace()) {
                throw peekError("EOF while parsing a value");
            }
            int b = peek();
            switch (b) {
                case 'n':
                    index++;
                    ident("ull");
                    return null;
                case 't':
                    index++;
                    ident("rue");
                    return Boolean.TRUE;
                case 'f':
                    index++;
                    ident("alse");
                    return Boolean.FALSE;
                case '-':
                    index++;
                    return parseInteger(false);
                case '"':
                    index++;
                    return string();
                case '[':
                    return array();
                case '{':
                    return object();
                default:
                    if (isDigit(b)) {
                        return parseInteger(true);
                    }
                    throw peekError("expected value");
            }
        }

        private void ident(String rest) throws ParseException {
            for (int i = 0; i < rest.length(); i++) {
                if (index >= input.length) {
                    throw error("EOF while parsing a value");
                }
                if (input[index++] != rest.charAt(i)) {
                    throw error("expected ident");
                }
            }
        }

        private void enter() throws ParseException {
            if (--remainingDepth == 0) {
                throw peekError("recursion limit exceeded");
            }
        }

        private List<Object> array() throws ParseException {
            enter();
            index++;
            List<Object> out = new ArrayList<>();
            boolean first = true;
            while (true) {
                if (!skipWhitespace()) {
                    throw peekError("EOF while parsing a list");
                }
                byte b = input[index];
                if (b == ']') {
                    break;
                }
                if (first) {
                    first = false;
                } else if (b == ',') {
                    index++;
                    if (!skipWhitespace()) {
                        throw peekError("EOF while parsing a value");
                    }
                    if (input[index] == ']') {
                        throw peekError("trailing comma");
                    }
                } else {
                    throw peekError("expected `,` or `]`");
                }
                out.add(value());
            }
            index++;
            remainingDepth++;
            return out;
        }

        private Map<String, Object> object() throws ParseException {
            enter();
            index++;
            Map<String, Object> out = new LinkedHashMap<>();
            boolean first = true;
            while (true) {
                if (!skipWhitespace()) {
                    throw peekError("EOF while parsing an object");
                }
                byte b = input[index];
                if (b == '}') {
                    break;
                }
                if (first) {
                    first = false;
                } else if (b == ',') {
                    index++;
                    if (!skipWhitespace()) {
                        throw peekError("EOF while parsing a value");
                    }
                    b = input[index];
                    if (b == '}') {
                        throw peekError("trailing comma");
                    }
                } else {
                    throw peekError("expected `,` or `}`");
                }
                if (b != '"') {
                    throw peekError("key must be a string");
                }
                index++;
                String key = string();
                if (!skipWhitespace()) {
                    throw peekError("EOF while parsing an object");
                }
                if (input[index] != ':') {
                    throw peekError("expected `:`");
                }
                index++;
                out.put(key, value());
            }
            index++;
            remainingDepth++;
            return out;
        }

        // ---- numbers: serde's parse_integer and its helpers, without float_roundtrip ----

        private static boolean overflowsU64(long significand, int digit) {
            int order = Long.compareUnsigned(significand, U64_MAX_DIV_10);
            return order > 0 || (order == 0 && digit > 5);
        }

        private Object parseInteger(boolean positive) throws ParseException {
            if (index >= input.length) {
                throw error("EOF while parsing a value");
            }
            int first = input[index++];
            if (first == '0') {
                if (isDigit(peek())) {
                    throw peekError("invalid number");
                }
                return parseNumber(positive, 0);
            }
            if (first < '1' || first > '9') {
                throw error("invalid number");
            }
            long significand = first - '0';
            while (isDigit(peek())) {
                int digit = input[index] - '0';
                if (overflowsU64(significand, digit)) {
                    return parseLongInteger(positive, significand);
                }
                index++;
                significand = significand * 10 + digit;
            }
            return parseNumber(positive, significand);
        }

        /** @param significand a u64 */
        private Object parseNumber(boolean positive, long significand) throws ParseException {
            int next = peek();
            if (next == '.') {
                return parseDecimal(positive, significand, 0);
            }
            if (next == 'e' || next == 'E') {
                return parseExponent(positive, significand, 0);
            }
            if (positive) {
                return significand >= 0 ? (Object) significand : new BigInteger(Long.toUnsignedString(significand));
            }
            long negated = -significand;
            return negated >= 0 ? (Object) (-toDouble(significand)) : (Object) negated;
        }

        private double parseLongInteger(boolean positive, long significand) throws ParseException {
            int exponent = 0;
            while (true) {
                int next = peek();
                if (isDigit(next)) {
                    index++;
                    exponent++;
                } else if (next == '.') {
                    return parseDecimal(positive, significand, exponent);
                } else if (next == 'e' || next == 'E') {
                    return parseExponent(positive, significand, exponent);
                } else {
                    return f64FromParts(positive, significand, exponent);
                }
            }
        }

        private double parseDecimal(boolean positive, long significand, int exponentBeforePoint) throws ParseException {
            index++;
            int exponentAfterPoint = 0;
            while (isDigit(peek())) {
                int digit = input[index] - '0';
                if (overflowsU64(significand, digit)) {
                    return parseDecimalOverflow(positive, significand, exponentBeforePoint + exponentAfterPoint);
                }
                index++;
                significand = significand * 10 + digit;
                exponentAfterPoint--;
            }
            if (exponentAfterPoint == 0) {
                throw peekError(index < input.length ? "invalid number" : "EOF while parsing a value");
            }
            int exponent = exponentBeforePoint + exponentAfterPoint;
            int next = peek();
            if (next == 'e' || next == 'E') {
                return parseExponent(positive, significand, exponent);
            }
            return f64FromParts(positive, significand, exponent);
        }

        /** Digits past what a u64 holds are dropped. */
        private double parseDecimalOverflow(boolean positive, long significand, int exponent) throws ParseException {
            while (isDigit(peek())) {
                index++;
            }
            int next = peek();
            if (next == 'e' || next == 'E') {
                return parseExponent(positive, significand, exponent);
            }
            return f64FromParts(positive, significand, exponent);
        }

        private double parseExponent(boolean positive, long significand, int startingExponent) throws ParseException {
            index++;
            boolean positiveExponent = true;
            int sign = peek();
            if (sign == '+') {
                index++;
            } else if (sign == '-') {
                index++;
                positiveExponent = false;
            }
            if (index >= input.length) {
                throw error("EOF while parsing a value");
            }
            int first = input[index++];
            if (!isDigit(first)) {
                throw error("invalid number");
            }
            int exponent = first - '0';
            while (isDigit(peek())) {
                int digit = input[index++] - '0';
                if (exponent >= Integer.MAX_VALUE / 10
                        && (exponent > Integer.MAX_VALUE / 10 || digit > Integer.MAX_VALUE % 10)) {
                    if (significand != 0 && positiveExponent) {
                        throw error("number out of range");
                    }
                    while (isDigit(peek())) {
                        index++;
                    }
                    return positive ? 0.0 : -0.0;
                }
                exponent = exponent * 10 + digit;
            }
            long sum = positiveExponent ? (long) startingExponent + exponent : (long) startingExponent - exponent;
            return f64FromParts(positive, significand, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum)));
        }

        /**
         * Scales by a power of ten like serde does, which is not always correctly rounded. A float
         * shows up only in an error, and Double.parseDouble could print a different one there.
         */
        private double f64FromParts(boolean positive, long significand, int exponent) throws ParseException {
            double f = toDouble(significand);
            while (true) {
                int abs = Math.abs(exponent);
                if (abs >= 0 && abs < POW10.length) {
                    if (exponent >= 0) {
                        f *= POW10[abs];
                        if (Double.isInfinite(f)) {
                            throw error("number out of range");
                        }
                    } else {
                        f /= POW10[abs];
                    }
                    break;
                }
                if (f == 0.0) {
                    break;
                }
                if (exponent >= 0) {
                    throw error("number out of range");
                }
                f /= 1e308;
                exponent += 308;
            }
            return positive ? f : -f;
        }

        /** A u64 to the nearest double. */
        private static double toDouble(long unsigned) {
            return unsigned >= 0 ? unsigned : ((double) ((unsigned >>> 1) | (unsigned & 1))) * 2.0;
        }

        // ---- strings ----

        private String string() throws ParseException {
            StringBuilder scratch = null;
            int start = index;
            while (true) {
                while (index < input.length) {
                    int b = input[index] & 0xff;
                    if (b == '"' || b == '\\' || b < 0x20) {
                        break;
                    }
                    index++;
                }
                if (index == input.length) {
                    throw error("EOF while parsing a string");
                }
                byte b = input[index];
                if (b == '"') {
                    String tail = new String(input, start, index - start, StandardCharsets.UTF_8);
                    index++;
                    return scratch == null ? tail : scratch.append(tail).toString();
                }
                if (b != '\\') {
                    index++;
                    throw error("control character (\\u0000-\\u001F) found while parsing a string");
                }
                if (scratch == null) {
                    scratch = new StringBuilder();
                }
                scratch.append(new String(input, start, index - start, StandardCharsets.UTF_8));
                index++;
                escape(scratch);
                start = index;
            }
        }

        private void escape(StringBuilder scratch) throws ParseException {
            if (index >= input.length) {
                throw error("EOF while parsing a string");
            }
            switch (input[index++]) {
                case '"' -> scratch.append('"');
                case '\\' -> scratch.append('\\');
                case '/' -> scratch.append('/');
                case 'b' -> scratch.append('\b');
                case 'f' -> scratch.append('\f');
                case 'n' -> scratch.append('\n');
                case 'r' -> scratch.append('\r');
                case 't' -> scratch.append('\t');
                case 'u' -> unicodeEscape(scratch);
                default -> throw error("invalid escape");
            }
        }

        /** A high surrogate must be followed right away by an escaped low one. */
        private void unicodeEscape(StringBuilder scratch) throws ParseException {
            int n = hexEscape();
            if (n >= 0xDC00 && n <= 0xDFFF) {
                throw error("lone leading surrogate in hex escape");
            }
            scratch.append((char) n);
            if (n < 0xD800 || n > 0xDBFF) {
                return;
            }
            for (char expected : new char[] {'\\', 'u'}) {
                if (index >= input.length) {
                    throw error("EOF while parsing a string");
                }
                if (input[index++] != expected) {
                    throw error("unexpected end of hex escape");
                }
            }
            int low = hexEscape();
            if (low < 0xDC00 || low > 0xDFFF) {
                throw error("lone leading surrogate in hex escape");
            }
            scratch.append((char) low);
        }

        /** serde takes the next four bytes whatever they are, and checks them after. */
        private int hexEscape() throws ParseException {
            if (input.length - index < 4) {
                index = input.length;
                throw error("EOF while parsing a string");
            }
            int value = 0;
            boolean valid = true;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(input[index + i], 16);
                valid &= digit >= 0;
                value = value << 4 | digit;
            }
            index += 4;
            if (!valid) {
                throw error("invalid escape");
            }
            return value;
        }
    }
}
