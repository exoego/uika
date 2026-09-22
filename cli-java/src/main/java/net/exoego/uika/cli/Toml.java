package net.exoego.uika.cli;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;

/**
 * TOML reader for {@code --exclude-file}. The jar ships no dependency, so this stands in for
 * the toml crate (1.1.5, spec 1.1.0) and keeps its observable behaviour, because a rejected
 * file prints the crate's message verbatim.
 *
 * <p>Three things are load-bearing for that. Errors come from two passes, syntax first and
 * then keys, strings, numbers and duplicate keys, and only the first error is shown, so a
 * syntax error on a later line wins over a duplicate key on an earlier one. Spans are UTF-8
 * byte offsets, since the crate counts the column in chars but the caret width in bytes.
 * Tables iterate in key byte order (the crate's BTreeMap), which decides which of two schema
 * errors a reader sees.
 */
final class Toml {
    private Toml() {}

    static Table parse(String input) {
        Source source = new Source(input.getBytes(StandardCharsets.UTF_8));
        Parser parser = new Parser(source);
        parser.lex();
        parser.document();
        return parser.build();
    }

    enum Kind {
        STRING("string"),
        INTEGER("integer"),
        FLOAT("float"),
        BOOLEAN("boolean"),
        DATETIME("datetime"),
        ARRAY("array"),
        TABLE("table");

        final String typeStr;

        Kind(String typeStr) {
            this.typeStr = typeStr;
        }
    }

    /** Message is the crate's whole Display output, trailing newline included. */
    static final class Error extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** The last line of the rendering, without the source excerpt. */
        final String description;

        Error(String rendered, String description) {
            super(rendered);
            this.description = description;
        }
    }

    static final class Table {
        private final TreeMap<String, Entry> map = new TreeMap<>(Text::compareUtf8);
        private boolean implicit;
        private boolean dotted;
        private boolean inline;

        Collection<Entry> entries() {
            return map.values();
        }

        Entry get(String key) {
            return map.get(key);
        }

        boolean isEmpty() {
            return map.isEmpty();
        }

        int size() {
            return map.size();
        }
    }

    static final class Entry {
        private final Source source;
        private final String key;
        private final int keyStart;
        private final int keyEnd;
        private final Value value;

        private Entry(Source source, Key key, Value value) {
            this.source = source;
            this.key = key.text;
            this.keyStart = key.start;
            this.keyEnd = key.end;
            this.value = value;
        }

        String key() {
            return key;
        }

        Value value() {
            return value;
        }

        /** serde's error for a struct that denies unknown fields. */
        Error unknownField(String... fields) {
            StringBuilder message = new StringBuilder("unknown field `").append(key).append("`, ");
            if (fields.length == 0) {
                message.append("there are no fields");
            } else {
                message.append("expected ");
                if (fields.length == 1) {
                    message.append('`').append(fields[0]).append('`');
                } else if (fields.length == 2) {
                    message.append('`').append(fields[0]).append("` or `").append(fields[1]).append('`');
                } else {
                    message.append("one of ");
                    for (int i = 0; i < fields.length; i++) {
                        message.append(i == 0 ? "`" : ", `").append(fields[i]).append('`');
                    }
                }
            }
            return source.error(message.toString(), keyStart, keyEnd);
        }
    }

    static final class Value {
        private final Source source;
        private final Kind kind;
        private final int start;
        private final int end;
        private String text;
        private int radix;
        private boolean bool;
        private List<Value> items;
        private boolean arrayOfTables;
        private Table table;

        private Value(Source source, Kind kind, int start, int end) {
            this.source = source;
            this.kind = kind;
            this.start = start;
            this.end = end;
        }

        Kind kind() {
            return kind;
        }

        String asString() {
            if (kind != Kind.STRING) {
                throw invalidType("a string");
            }
            return text;
        }

        List<Value> asArray() {
            if (kind != Kind.ARRAY) {
                throw invalidType("a sequence");
            }
            return items;
        }

        /** {@code expecting} is serde's name for the target, such as "struct RawEntry". */
        Table asTable(String expecting) {
            if (kind != Kind.TABLE) {
                throw invalidType(expecting);
            }
            return table;
        }

        /** {@code expecting} as serde words it, such as "struct RawEntry with 5 elements". */
        Error invalidLength(int length, String expecting) {
            return source.error("invalid length " + length + ", expected " + expecting, start, end);
        }

        Error missingField(String field) {
            return source.error("missing field `" + field + "`", start, end);
        }

        Error invalidType(String expecting) {
            String unexpected =
                    switch (kind) {
                        case STRING -> "string " + debugQuote(text);
                        case INTEGER -> unexpectedInteger();
                        case FLOAT -> "floating point `" + floatText() + "`";
                        case BOOLEAN -> "boolean `" + bool + "`";
                        case DATETIME, TABLE -> "map";
                        case ARRAY -> "sequence";
                    };
            return source.error("invalid type: " + unexpected + ", expected " + expecting, start, end);
        }

        private Error error(String message) {
            return source.error(message, start, end);
        }

        private BigInteger integer() {
            BigInteger value;
            try {
                value = new BigInteger(text, radix);
            } catch (NumberFormatException e) {
                throw error("integer number overflowed");
            }
            if (value.bitLength() > 128 || (value.signum() < 0 && value.bitLength() > 127)) {
                throw error("integer number overflowed");
            }
            return value;
        }

        private String unexpectedInteger() {
            BigInteger value = integer();
            if (value.bitLength() <= 63 || (value.signum() > 0 && value.bitLength() <= 64)) {
                return "integer `" + value + "`";
            }
            return "integer `" + value + "` as " + (value.bitLength() <= 127 ? "i128" : "u128");
        }

        private String floatText() {
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith("nan")) {
                return "NaN";
            }
            if (lower.endsWith("inf")) {
                return text.startsWith("-") ? "-inf" : "inf";
            }
            double value = Double.parseDouble(text);
            if (Double.isInfinite(value)) {
                throw error("floating-point number overflowed");
            }
            if (value == 0) {
                return 1 / value < 0 ? "-0.0" : "0.0";
            }
            // Shortest digits that read back as the same double, which is what Rust prints.
            // Double.toString is not that before JDK 19.
            BigDecimal exact = new BigDecimal(value);
            BigDecimal shortest;
            for (int precision = 1; ; precision++) {
                shortest = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
                if (shortest.doubleValue() == value) {
                    break;
                }
            }
            String plain = shortest.stripTrailingZeros().toPlainString();
            return plain.indexOf('.') >= 0 ? plain : plain + ".0";
        }
    }

    /**
     * Rust's {@code {:?}} for a str. The printable test is by general category, which is how
     * the Rust table is generated. Grapheme_Extend is approximated by the two mark categories,
     * so the few spacing marks it also holds (U+09BE is one) print unescaped here.
     */
    static String debugQuote(String s) {
        StringBuilder out = new StringBuilder("\"");
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case 0 -> out.append("\\0");
                default -> {
                    if (needsEscape(cp)) {
                        out.append("\\u{").append(Integer.toHexString(cp)).append('}');
                    } else {
                        out.appendCodePoint(cp);
                    }
                }
            }
        });
        return out.append('"').toString();
    }

    private static boolean needsEscape(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.SURROGATE,
                    Character.PRIVATE_USE,
                    Character.UNASSIGNED,
                    Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR,
                    Character.NON_SPACING_MARK,
                    Character.ENCLOSING_MARK -> true;
            case Character.SPACE_SEPARATOR -> cp != ' ';
            default -> false;
        };
    }

    private record Key(String text, int start, int end) {}

    private record Header(List<Key> path, Key key, int start, int end, boolean array) {}

    private static final class Source {
        final byte[] bytes;

        Source(byte[] bytes) {
            this.bytes = bytes;
        }

        String text(int start, int end) {
            return new String(bytes, start, end - start, StandardCharsets.UTF_8);
        }

        Error error(String message) {
            return new Error(message + "\n", message);
        }

        Error error(String message, int start, int end) {
            int index = start;
            int line = 0;
            int lineStart = 0;
            int column = index;
            if (bytes.length > 0) {
                int safe = Math.min(index, bytes.length - 1);
                int columnOffset = index - safe;
                index = safe;
                for (int i = index - 1; i >= 0; i--) {
                    if (bytes[i] == '\n') {
                        lineStart = i + 1;
                        break;
                    }
                }
                for (int i = 0; i < lineStart; i++) {
                    if (bytes[i] == '\n') {
                        line++;
                    }
                }
                // A slice that ends inside a character is not UTF-8, and the crate then
                // falls back to the byte distance.
                boolean wholeChars = index + 1 >= bytes.length || (bytes[index + 1] & 0xC0) != 0x80;
                if (wholeChars) {
                    int chars = 0;
                    for (int i = lineStart; i <= index; i++) {
                        if ((bytes[i] & 0xC0) != 0x80) {
                            chars++;
                        }
                    }
                    column = chars - 1;
                } else {
                    column = index - lineStart;
                }
                column += columnOffset;
            }
            int lineEnd = lineStart;
            while (lineEnd < bytes.length && bytes[lineEnd] != '\n') {
                lineEnd++;
            }
            int highlight = Math.min(end - start, Math.max(0, lineEnd - lineStart - column));
            String lineNumber = Integer.toString(line + 1);
            String gutter = " ".repeat(lineNumber.length() + 1);
            StringBuilder out = new StringBuilder();
            out.append("TOML parse error at line ").append(lineNumber).append(", column ").append(column + 1).append('\n');
            out.append(gutter).append("|\n");
            out.append(lineNumber).append(" | ").append(text(lineStart, lineEnd)).append('\n');
            out.append(gutter).append('|').append(" ".repeat(column + 1)).append('^');
            out.append("^".repeat(Math.max(0, highlight - 1))).append('\n');
            out.append(message).append('\n');
            return new Error(out.toString(), message);
        }
    }

    private static String lit(String literal) {
        return literal.equals("\n") ? "newline" : "`" + literal + "`";
    }

    private static final int T_DOT = 0;
    private static final int T_EQUALS = 1;
    private static final int T_COMMA = 2;
    private static final int T_LBRACKET = 3;
    private static final int T_RBRACKET = 4;
    private static final int T_LBRACE = 5;
    private static final int T_RBRACE = 6;
    private static final int T_WS = 7;
    private static final int T_COMMENT = 8;
    private static final int T_NEWLINE = 9;
    private static final int T_LITERAL = 10;
    private static final int T_BASIC = 11;
    private static final int T_ML_LITERAL = 12;
    private static final int T_ML_BASIC = 13;
    private static final int T_ATOM = 14;
    private static final int T_EOF = 15;

    private static final int ENC_NONE = 0;
    private static final int ENC_LITERAL = 1;
    private static final int ENC_BASIC = 2;
    private static final int ENC_ML_LITERAL = 3;
    private static final int ENC_ML_BASIC = 4;

    private static final int E_STD_TABLE_OPEN = 0;
    private static final int E_STD_TABLE_CLOSE = 1;
    private static final int E_ARRAY_TABLE_OPEN = 2;
    private static final int E_ARRAY_TABLE_CLOSE = 3;
    private static final int E_INLINE_TABLE_OPEN = 4;
    private static final int E_INLINE_TABLE_CLOSE = 5;
    private static final int E_ARRAY_OPEN = 6;
    private static final int E_ARRAY_CLOSE = 7;
    private static final int E_KEY = 8;
    private static final int E_KEY_SEP = 9;
    private static final int E_KEY_VAL_SEP = 10;
    private static final int E_SCALAR = 11;
    private static final int E_VALUE_SEP = 12;
    private static final int E_WS = 13;
    private static final int E_COMMENT = 14;
    private static final int E_NEWLINE = 15;
    private static final int E_ERROR = 16;

    /** The crate's nesting limit for arrays, inline tables and dotted keys. */
    private static final int LIMIT = 80;

    private static int encodingOf(int tokenKind) {
        return switch (tokenKind) {
            case T_LITERAL -> ENC_LITERAL;
            case T_BASIC -> ENC_BASIC;
            case T_ML_LITERAL -> ENC_ML_LITERAL;
            case T_ML_BASIC -> ENC_ML_BASIC;
            default -> ENC_NONE;
        };
    }

    /**
     * Every error is thrown where the crate reports it. The crate keeps parsing and keeps
     * only the first report, so nothing after a report is ported. The recoveries that report
     * nothing (an empty stand-in key or value) are ported, because the second pass is what
     * rejects them, in its own order.
     */
    private static final class Parser {
        private final Source source;
        private final byte[] s;
        private final int n;
        private final IntBuf tk = new IntBuf();
        private final IntBuf ts = new IntBuf();
        private final IntBuf te = new IntBuf();
        private final IntBuf ek = new IntBuf();
        private final IntBuf ec = new IntBuf();
        private final IntBuf es = new IntBuf();
        private final IntBuf ee = new IntBuf();
        private int pos;
        private int depth;
        private int ep;

        Parser(Source source) {
            this.source = source;
            this.s = source.bytes;
            this.n = s.length;
        }

        private Error fail(String description, int start, int end, String... expected) {
            String list = expected.length == 0 ? "nothing" : String.join(", ", expected);
            return source.error(description + ", expected " + list, start, end);
        }

        // Lexer.

        void lex() {
            int i = 0;
            if (n >= 3 && s[0] == (byte) 0xEF && s[1] == (byte) 0xBB && s[2] == (byte) 0xBF) {
                i = 3;
            }
            while (i < n) {
                int start = i;
                int kind;
                switch (s[i]) {
                    case '.' -> {
                        kind = T_DOT;
                        i++;
                    }
                    case '=' -> {
                        kind = T_EQUALS;
                        i++;
                    }
                    case ',' -> {
                        kind = T_COMMA;
                        i++;
                    }
                    case '[' -> {
                        kind = T_LBRACKET;
                        i++;
                    }
                    case ']' -> {
                        kind = T_RBRACKET;
                        i++;
                    }
                    case '{' -> {
                        kind = T_LBRACE;
                        i++;
                    }
                    case '}' -> {
                        kind = T_RBRACE;
                        i++;
                    }
                    case ' ', '\t' -> {
                        kind = T_WS;
                        while (i < n && (s[i] == ' ' || s[i] == '\t')) {
                            i++;
                        }
                    }
                    case '#' -> {
                        kind = T_COMMENT;
                        while (i < n && s[i] != '\r' && s[i] != '\n') {
                            i++;
                        }
                    }
                    case '\r' -> {
                        kind = T_NEWLINE;
                        i++;
                        if (i < n && s[i] == '\n') {
                            i++;
                        }
                    }
                    case '\n' -> {
                        kind = T_NEWLINE;
                        i++;
                    }
                    case '\'' -> {
                        if (startsWith(i, '\'', 3)) {
                            kind = T_ML_LITERAL;
                            i = lexMlLiteral(i + 3);
                        } else {
                            kind = T_LITERAL;
                            i = lexLiteral(i + 1);
                        }
                    }
                    case '"' -> {
                        if (startsWith(i, '"', 3)) {
                            kind = T_ML_BASIC;
                            i = lexMlBasic(i + 3);
                        } else {
                            kind = T_BASIC;
                            i = lexBasic(i + 1);
                        }
                    }
                    default -> {
                        kind = T_ATOM;
                        while (i < n && !isTokenStart(s[i])) {
                            i++;
                        }
                    }
                }
                token(kind, start, i);
            }
            token(T_EOF, n, n);
        }

        private void token(int kind, int start, int end) {
            tk.add(kind);
            ts.add(start);
            te.add(end);
        }

        private boolean startsWith(int at, char c, int count) {
            if (at + count > n) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (s[at + i] != c) {
                    return false;
                }
            }
            return true;
        }

        // Quotes are left out on purpose, so a value missing its opening quote stays one atom.
        private static boolean isTokenStart(byte b) {
            return switch (b) {
                case '.', '=', ',', '[', ']', '{', '}', ' ', '\t', '#', '\r', '\n' -> true;
                default -> false;
            };
        }

        private int lexLiteral(int i) {
            for (; i < n; i++) {
                if (s[i] == '\'') {
                    return i + 1;
                }
                if (s[i] == '\n') {
                    return i;
                }
            }
            return n;
        }

        private int lexMlLiteral(int i) {
            for (; i < n; i++) {
                if (startsWith(i, '\'', 3)) {
                    i += 3;
                    for (int extra = 0; extra < 2 && i < n && s[i] == '\''; extra++) {
                        i++;
                    }
                    return i;
                }
            }
            return n;
        }

        private int lexBasic(int i) {
            while (i < n) {
                byte b = s[i];
                if (b == '"') {
                    return i + 1;
                }
                if (b == '\n') {
                    return i;
                }
                i++;
                if (b == '\\' && i < n && (s[i] == '\\' || s[i] == '"')) {
                    i++;
                }
            }
            return n;
        }

        private int lexMlBasic(int i) {
            while (i < n) {
                if (startsWith(i, '"', 3)) {
                    i += 3;
                    for (int extra = 0; extra < 2 && i < n && s[i] == '"'; extra++) {
                        i++;
                    }
                    return i;
                }
                byte b = s[i];
                i++;
                if (b == '\\' && i < n && (s[i] == '\\' || s[i] == '"')) {
                    i++;
                }
            }
            return n;
        }

        // First pass, tokens to events.

        private void event(int kind, int encoding, int start, int end) {
            ek.add(kind);
            ec.add(encoding);
            es.add(start);
            ee.add(end);
        }

        private void event(int kind, int token) {
            event(kind, ENC_NONE, ts.a[token], te.a[token]);
        }

        private void emptyKey(int at) {
            event(E_KEY, ENC_NONE, at, at);
        }

        private void newline(int token) {
            int start = ts.a[token];
            int end = te.a[token];
            if (end - start == 1 && s[start] == '\r') {
                throw fail("carriage return must be followed by newline", end, end, lit("\n"));
            }
            event(E_NEWLINE, token);
        }

        private void open(int kind, int token) {
            event(kind, token);
            depth++;
            if (depth > LIMIT) {
                throw source.error("cannot recurse further; max recursion depth met", ts.a[token], te.a[token]);
            }
        }

        private void close(int kind, int token) {
            depth--;
            event(kind, token);
        }

        private int peek() {
            return pos < tk.n ? tk.a[pos] : -1;
        }

        private void optWhitespace() {
            if (peek() == T_WS) {
                event(E_WS, pos++);
            }
        }

        private int afterLastSignificantToken() {
            for (int i = pos - 1; ; i--) {
                int kind = tk.a[i];
                if (kind != T_WS && kind != T_COMMENT && kind != T_NEWLINE && kind != T_EOF) {
                    return te.a[i];
                }
            }
        }

        void document() {
            while (pos < tk.n) {
                int t = pos++;
                int at = ts.a[t];
                switch (tk.a[t]) {
                    case T_LBRACKET -> onTable(t);
                    case T_RBRACKET -> throw fail("missing table open", at, at, lit("["));
                    case T_LITERAL, T_BASIC, T_ML_LITERAL, T_ML_BASIC, T_ATOM -> {
                        event(E_KEY, encodingOf(tk.a[t]), at, te.a[t]);
                        optDotKeys();
                        keyValue("key with no value");
                    }
                    case T_EQUALS -> {
                        emptyKey(at);
                        onKeyValSep(t);
                    }
                    case T_DOT -> {
                        emptyKey(at);
                        pos--;
                        optDotKeys();
                        keyValue("missing value for key");
                    }
                    case T_COMMA, T_RBRACE, T_LBRACE -> throw fail("invalid key-value pair", at, at, "key");
                    case T_WS -> event(E_WS, t);
                    case T_NEWLINE -> newline(t);
                    case T_COMMENT -> onComment(t);
                    default -> {
                        return;
                    }
                }
            }
        }

        private void keyValue(String missing) {
            optWhitespace();
            if (peek() == T_EQUALS) {
                onKeyValSep(pos++);
            } else {
                int at = ts.a[pos];
                throw fail(missing, at, at, lit("="));
            }
        }

        private void onKeyValSep(int equals) {
            event(E_KEY_VAL_SEP, equals);
            optWhitespace();
            onValue();
            wsCommentNewline();
        }

        private void onTable(int open) {
            boolean array = peek() == T_LBRACKET;
            if (array) {
                event(E_ARRAY_TABLE_OPEN, ENC_NONE, ts.a[open], te.a[pos++]);
            } else {
                event(E_STD_TABLE_OPEN, open);
            }
            optWhitespace();
            boolean validKey = key();
            optWhitespace();

            boolean success = false;
            if (peek() == T_RBRACKET) {
                int close = pos++;
                if (!array) {
                    event(E_STD_TABLE_CLOSE, close);
                    success = true;
                } else if (peek() == T_RBRACKET) {
                    event(E_ARRAY_TABLE_CLOSE, ENC_NONE, ts.a[close], te.a[pos++]);
                    success = true;
                } else {
                    throw fail("unclosed array table", te.a[close], te.a[close], lit("]"));
                }
            } else if (validKey) {
                int last = pos - 1;
                while (tk.a[last] == T_WS) {
                    last--;
                }
                int at = te.a[last];
                throw fail(array ? "unclosed array table" : "unclosed table", at, at, lit(array ? "]]" : "]"));
            }
            if (success) {
                wsCommentNewline();
            } else {
                ignoreToNewline();
            }
        }

        private boolean key() {
            while (true) {
                int t = pos++;
                int at = ts.a[t];
                switch (tk.a[t]) {
                    case T_WS -> event(E_WS, t);
                    case T_DOT -> {
                        emptyKey(at);
                        event(E_KEY_SEP, t);
                    }
                    case T_LITERAL, T_BASIC, T_ML_LITERAL, T_ML_BASIC, T_ATOM -> {
                        event(E_KEY, encodingOf(tk.a[t]), at, te.a[t]);
                        return optDotKeys();
                    }
                    default -> {
                        emptyKey(at);
                        pos--;
                        return false;
                    }
                }
            }
        }

        private boolean optDotKeys() {
            optWhitespace();
            dots:
            while (peek() == T_DOT) {
                event(E_KEY_SEP, pos++);
                while (true) {
                    int t = pos++;
                    int at = ts.a[t];
                    switch (tk.a[t]) {
                        case T_WS -> event(E_WS, t);
                        case T_DOT -> {
                            emptyKey(at);
                            event(E_KEY_SEP, t);
                        }
                        case T_LITERAL, T_BASIC, T_ML_LITERAL, T_ML_BASIC, T_ATOM -> {
                            event(E_KEY, encodingOf(tk.a[t]), at, te.a[t]);
                            optWhitespace();
                            continue dots;
                        }
                        default -> {
                            emptyKey(at);
                            pos--;
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private void onValue() {
            int t = pos++;
            int at = ts.a[t];
            if (tk.a[t] == T_EQUALS) {
                throw fail("extra `=`", at, te.a[t]);
            }
            switch (tk.a[t]) {
                case T_COMMENT, T_COMMA, T_NEWLINE, T_EOF, T_WS -> {
                    event(E_SCALAR, ENC_NONE, at, at);
                    pos--;
                }
                case T_LBRACE -> onInlineTable(t);
                case T_RBRACE -> throw fail("missing inline table opening", at, at, lit("{"));
                case T_LBRACKET -> onArray(t);
                case T_RBRACKET -> throw fail("missing array opening", at, at, lit("["));
                default -> onScalar(t);
            }
        }

        private void onScalar(int t) {
            int end = te.a[t];
            if (tk.a[t] == T_DOT || tk.a[t] == T_ATOM) {
                scan:
                while (true) {
                    switch (tk.a[pos]) {
                        case T_WS -> {
                            // Only a date-time may hold a space. Whether this one may is the
                            // second pass's call.
                            if (tk.a[pos + 1] == T_ATOM) {
                                end = te.a[pos + 1];
                                pos += 2;
                            } else {
                                break scan;
                            }
                        }
                        case T_DOT, T_ATOM -> end = te.a[pos++];
                        default -> {
                            break scan;
                        }
                    }
                }
            }
            event(E_SCALAR, encodingOf(tk.a[t]), ts.a[t], end);
        }

        private void onArray(int open) {
            open(E_ARRAY_OPEN, open);
            boolean needsValue = true;
            while (true) {
                int t = pos++;
                int at = ts.a[t];
                int kind = tk.a[t];
                switch (kind) {
                    case T_COMMENT -> onComment(t);
                    case T_WS -> event(E_WS, t);
                    case T_NEWLINE -> newline(t);
                    case T_EOF -> {
                        int after = afterLastSignificantToken();
                        throw fail("unclosed array", after, after, lit("]"));
                    }
                    case T_COMMA -> {
                        if (needsValue) {
                            throw fail("extra comma in array", at, te.a[t], "value");
                        }
                        event(E_VALUE_SEP, t);
                        needsValue = true;
                    }
                    case T_EQUALS -> throw fail("unexpected `=` in array", at, te.a[t], "value", lit("]"));
                    case T_RBRACKET -> {
                        close(E_ARRAY_CLOSE, t);
                        return;
                    }
                    default -> {
                        if (!needsValue) {
                            throw fail("missing comma between array elements", at, at, lit(","));
                        }
                        switch (kind) {
                            case T_LBRACE -> onInlineTable(t);
                            case T_RBRACE -> throw fail("missing inline table opening", at, at, lit("{"));
                            case T_LBRACKET -> onArray(t);
                            default -> onScalar(t);
                        }
                        needsValue = false;
                    }
                }
            }
        }

        private static final int NEEDS_KEY = 0;
        private static final int NEEDS_EQUALS = 1;
        private static final int NEEDS_VALUE = 2;
        private static final int NEEDS_COMMA = 3;

        private static String expectedIn(int state) {
            return switch (state) {
                case NEEDS_KEY -> "key";
                case NEEDS_EQUALS -> lit("=");
                case NEEDS_VALUE -> "value";
                default -> lit(",");
            };
        }

        private void onInlineTable(int open) {
            open(E_INLINE_TABLE_OPEN, open);
            int state = NEEDS_KEY;
            while (true) {
                int t = pos++;
                int at = ts.a[t];
                int kind = tk.a[t];
                String expected = expectedIn(state);
                switch (kind) {
                    case T_COMMENT -> onComment(t);
                    case T_WS -> event(E_WS, t);
                    case T_NEWLINE -> newline(t);
                    case T_EOF -> {
                        int after = afterLastSignificantToken();
                        throw fail("unclosed inline table", after, after, lit("}"));
                    }
                    case T_COMMA -> {
                        if (state != NEEDS_COMMA) {
                            throw fail("extra comma in inline table", at, at, expected);
                        }
                        event(E_VALUE_SEP, t);
                        state = NEEDS_KEY;
                    }
                    case T_EQUALS -> {
                        if (state == NEEDS_VALUE || state == NEEDS_COMMA) {
                            throw fail("extra assignment between key-value pairs", at, at, expected);
                        }
                        if (state == NEEDS_KEY) {
                            emptyKey(at);
                        }
                        event(E_KEY_VAL_SEP, t);
                        state = NEEDS_VALUE;
                    }
                    case T_LBRACE, T_LBRACKET -> {
                        if (state == NEEDS_KEY || state == NEEDS_COMMA) {
                            throw fail("missing key for inline table element", at, at, expected);
                        }
                        if (state == NEEDS_EQUALS) {
                            throw fail("missing assignment between key-value pairs", at, at, expected);
                        }
                        if (kind == T_LBRACE) {
                            onInlineTable(t);
                        } else {
                            onArray(t);
                        }
                        state = NEEDS_COMMA;
                    }
                    case T_RBRACE -> {
                        if (state == NEEDS_EQUALS) {
                            event(E_KEY_VAL_SEP, ENC_NONE, at, at);
                        }
                        if (state == NEEDS_EQUALS || state == NEEDS_VALUE) {
                            event(E_SCALAR, ENC_LITERAL, at, at);
                        }
                        close(E_INLINE_TABLE_CLOSE, t);
                        return;
                    }
                    case T_RBRACKET -> {
                        if (state == NEEDS_VALUE) {
                            throw fail("missing array opening", at, at, lit("["));
                        }
                        throw fail("invalid inline table element", at, at, expected);
                    }
                    default -> {
                        switch (state) {
                            case NEEDS_KEY -> {
                                if (kind == T_DOT) {
                                    emptyKey(at);
                                    pos--;
                                } else {
                                    event(E_KEY, encodingOf(kind), at, te.a[t]);
                                }
                                optDotKeys();
                                state = NEEDS_EQUALS;
                            }
                            case NEEDS_EQUALS ->
                                throw fail("missing assignment between key-value pairs", at, at, expected);
                            case NEEDS_VALUE -> {
                                onScalar(t);
                                state = NEEDS_COMMA;
                            }
                            default -> throw fail("missing comma between key-value pairs", at, at, expected);
                        }
                    }
                }
            }
        }

        private void wsCommentNewline() {
            while (true) {
                int t = pos++;
                switch (tk.a[t]) {
                    case T_WS -> event(E_WS, t);
                    case T_COMMENT -> {
                        onComment(t);
                        return;
                    }
                    case T_NEWLINE -> {
                        newline(t);
                        return;
                    }
                    case T_EOF -> {
                        return;
                    }
                    default -> throw fail("unexpected key or value", ts.a[t], ts.a[t], lit("\n"), lit("#"));
                }
            }
        }

        private void onComment(int comment) {
            for (int i = ts.a[comment]; i < te.a[comment]; i++) {
                int b = s[i] & 0xFF;
                boolean nonEol = b == 0x09 || (b >= 0x20 && b <= 0x7E) || b >= 0x80;
                if (!nonEol) {
                    throw fail("invalid comment character", i, i, "printable characters");
                }
            }
            event(E_COMMENT, comment);
            // The lexer ends a comment at a line break or at the end. The end stays for the caller,
            // which may still have an array or inline table to report as unclosed.
            if (tk.a[pos] == T_NEWLINE) {
                newline(pos++);
            }
        }

        // Reached only after a table header whose key was a silent stand-in.
        private void ignoreToNewline() {
            while (true) {
                int t = pos++;
                switch (tk.a[t]) {
                    case T_WS -> event(E_WS, t);
                    case T_COMMENT -> {
                        onComment(t);
                        return;
                    }
                    case T_NEWLINE -> {
                        newline(t);
                        return;
                    }
                    case T_EOF -> {
                        return;
                    }
                    default -> event(E_ERROR, t);
                }
            }
        }

        // Second pass, events to tables.

        private Table root = new Table();
        private Table current = new Table();
        private Header header;

        Table build() {
            while (ep < ek.n) {
                int e = ep++;
                switch (ek.a[e]) {
                    case E_STD_TABLE_OPEN, E_ARRAY_TABLE_OPEN -> {
                        finishTable();
                        startTable(onHeader(e));
                    }
                    case E_KEY -> {
                        List<Key> path = new ArrayList<>();
                        Key key = onKey(e, path);
                        if (ek.a[ep] == E_WS) {
                            ep++;
                        }
                        // The first pass always put the `=` here.
                        ep++;
                        if (ek.a[ep] == E_WS) {
                            ep++;
                        }
                        captureKeyValue(path, key, buildValue());
                    }
                    default -> {}
                }
            }
            finishTable();
            return root;
        }

        private Header onHeader(int open) {
            boolean array = ek.a[open] == E_ARRAY_TABLE_OPEN;
            List<Key> path = new ArrayList<>();
            Key key = null;
            int end = ee.a[open];
            while (ep < ek.n) {
                int e = ep++;
                int kind = ek.a[e];
                if (kind == E_ARRAY_TABLE_CLOSE || kind == E_STD_TABLE_CLOSE) {
                    end = ee.a[e];
                    break;
                }
                if (kind == E_KEY) {
                    path = new ArrayList<>();
                    key = onKey(e, path);
                }
            }
            return new Header(path, key, es.a[open], end, array);
        }

        private boolean moreKey() {
            int first = ep < ek.n ? ek.a[ep] : -1;
            int second = ep + 1 < ek.n ? ek.a[ep + 1] : -1;
            return first == E_KEY_SEP || (first == E_WS && second == E_KEY_SEP);
        }

        private Key onKey(int keyEvent, List<Key> path) {
            Key key = null;
            int pending = keyEvent;
            if (moreKey()) {
                while (ep < ek.n) {
                    int e = ep++;
                    if (ek.a[e] == E_KEY) {
                        pending = e;
                        if (!moreKey()) {
                            break;
                        }
                    } else if (ek.a[e] == E_KEY_SEP && pending >= 0) {
                        if (key != null) {
                            path.add(key);
                        }
                        key = decodeKey(pending);
                        pending = -1;
                    }
                }
            }
            if (pending >= 0) {
                if (key != null) {
                    path.add(key);
                }
                key = decodeKey(pending);
            }
            if (LIMIT <= path.size()) {
                throw source.error("recursion limit");
            }
            return key;
        }

        private Value buildValue() {
            int e = ep++;
            return switch (ek.a[e]) {
                case E_INLINE_TABLE_OPEN -> inlineTable(e);
                case E_ARRAY_OPEN -> array(e);
                default -> decodeScalar(e);
            };
        }

        private Value array(int open) {
            List<Value> items = new ArrayList<>();
            Value pending = null;
            int end = ee.a[open];
            scan:
            while (true) {
                int e = ep++;
                end = ee.a[e];
                switch (ek.a[e]) {
                    case E_INLINE_TABLE_OPEN -> pending = inlineTable(e);
                    case E_ARRAY_OPEN -> pending = array(e);
                    case E_SCALAR -> pending = decodeScalar(e);
                    case E_VALUE_SEP, E_ARRAY_CLOSE -> {
                        if (pending != null) {
                            items.add(pending);
                            pending = null;
                        }
                        if (ek.a[e] == E_ARRAY_CLOSE) {
                            break scan;
                        }
                    }
                    default -> {}
                }
            }
            Value value = new Value(source, Kind.ARRAY, es.a[open], end);
            value.items = items;
            return value;
        }

        private Value inlineTable(int open) {
            Table result = new Table();
            result.inline = true;
            List<Key> path = null;
            Key key = null;
            Value pending = null;
            int end = ee.a[open];
            scan:
            while (true) {
                int e = ep++;
                end = ee.a[e];
                switch (ek.a[e]) {
                    case E_KEY -> {
                        path = new ArrayList<>();
                        key = onKey(e, path);
                    }
                    case E_INLINE_TABLE_OPEN -> pending = inlineTable(e);
                    case E_ARRAY_OPEN -> pending = array(e);
                    case E_SCALAR -> pending = decodeScalar(e);
                    case E_VALUE_SEP, E_INLINE_TABLE_CLOSE -> {
                        if (key != null && pending != null) {
                            insertInline(result, path, key, pending);
                        }
                        key = null;
                        pending = null;
                        if (ek.a[e] == E_INLINE_TABLE_CLOSE) {
                            break scan;
                        }
                    }
                    default -> {}
                }
            }
            Value value = new Value(source, Kind.TABLE, es.a[open], end);
            value.table = result;
            return value;
        }

        private void insertInline(Table result, List<Key> path, Key key, Value value) {
            Table table = result;
            for (Key part : path) {
                Entry existing = table.map.get(part.text);
                if (existing == null) {
                    Table created = new Table();
                    created.implicit = true;
                    created.dotted = true;
                    created.inline = true;
                    table.map.put(part.text, new Entry(source, part, tableValue(created, part.start, part.end)));
                    table = created;
                } else if (existing.value.kind == Kind.TABLE) {
                    if (!existing.value.table.implicit) {
                        throw duplicateKey(part);
                    }
                    table = existing.value.table;
                } else {
                    throw cannotExtend(existing.value.kind.typeStr, part);
                }
            }
            if (table.dotted == path.isEmpty() || table.map.containsKey(key.text)) {
                throw duplicateKey(key);
            }
            table.map.put(key.text, new Entry(source, key, value));
        }

        private Value tableValue(Table table, int start, int end) {
            Value value = new Value(source, Kind.TABLE, start, end);
            value.table = table;
            return value;
        }

        private Error duplicateKey(Key key) {
            return source.error("duplicate key", key.start, key.end);
        }

        private Error cannotExtend(String type, Key key) {
            return source.error("cannot extend value of type " + type + " with a dotted key", key.start, key.end);
        }

        private Table descend(Table table, List<Key> path, boolean dotted) {
            for (Key part : path) {
                Entry existing = table.map.get(part.text);
                if (existing == null) {
                    Table created = new Table();
                    created.implicit = true;
                    created.dotted = dotted;
                    table.map.put(part.text, new Entry(source, part, tableValue(created, part.start, part.end)));
                    table = created;
                    continue;
                }
                Value value = existing.value;
                if (value.kind == Kind.ARRAY) {
                    if (!value.arrayOfTables) {
                        throw cannotExtend("array", part);
                    }
                    table = value.items.get(value.items.size() - 1).table;
                } else if (value.kind == Kind.TABLE) {
                    Table child = value.table;
                    if (child.inline) {
                        throw cannotExtend("inline table", part);
                    }
                    if (dotted && child.implicit) {
                        child.dotted = true;
                    }
                    if (dotted && !child.implicit) {
                        throw duplicateKey(part);
                    }
                    table = child;
                } else {
                    throw cannotExtend(value.kind.typeStr, part);
                }
            }
            return table;
        }

        private void captureKeyValue(List<Key> path, Key key, Value value) {
            boolean dotted = !path.isEmpty();
            Table parent = descend(current, path, dotted);
            if ((dotted && !parent.implicit) || parent.map.containsKey(key.text)) {
                throw duplicateKey(key);
            }
            parent.map.put(key.text, new Entry(source, key, value));
        }

        private void finishTable() {
            Table finished = current;
            current = new Table();
            Header h = header;
            header = null;
            if (h == null) {
                root = finished;
                return;
            }
            Table parent = descend(root, h.path, false);
            Value value = tableValue(finished, h.start, h.end);
            if (!h.array) {
                parent.map.put(h.key.text, new Entry(source, h.key, value));
                return;
            }
            Entry existing = parent.map.get(h.key.text);
            if (existing == null) {
                Value array = new Value(source, Kind.ARRAY, h.start, h.end);
                array.items = new ArrayList<>();
                array.arrayOfTables = true;
                existing = new Entry(source, h.key, array);
                parent.map.put(h.key.text, existing);
            } else if (!existing.value.arrayOfTables) {
                throw duplicateKey(h.key);
            }
            existing.value.items.add(value);
        }

        private void startTable(Header h) {
            if (!h.array) {
                // Looked up at the header, not when the table ends, so the duplicate is
                // reported on the redefining line.
                Table parent = descend(root, h.path, false);
                Entry old = parent.map.remove(h.key.text);
                if (old != null) {
                    boolean reopenable =
                            old.value.kind == Kind.TABLE && old.value.table.implicit && !old.value.table.dotted;
                    if (!reopenable) {
                        throw duplicateKey(h.key);
                    }
                    current = old.value.table;
                }
            }
            current.implicit = false;
            current.dotted = false;
            header = h;
        }

        // Decoding. Offsets here are absolute, where the crate rebases raw-relative ones.

        private Key decodeKey(int e) {
            int start = es.a[e];
            int end = ee.a[e];
            String text =
                    switch (ec.a[e]) {
                        case ENC_LITERAL -> literalString(start, end);
                        case ENC_BASIC -> basicString(start, end);
                        case ENC_ML_LITERAL ->
                            throw fail(
                                    "keys cannot be multi-line literal strings",
                                    start,
                                    end,
                                    "basic string",
                                    "literal string");
                        case ENC_ML_BASIC ->
                            throw fail(
                                    "keys cannot be multi-line basic strings",
                                    start,
                                    end,
                                    "basic string",
                                    "literal string");
                        default -> unquotedKey(start, end);
                    };
            return new Key(text, start, end);
        }

        private String unquotedKey(int start, int end) {
            String[] expected = {"letters", "numbers", lit("-"), lit("_")};
            if (start == end) {
                throw fail("unquoted keys cannot be empty", start, end, expected);
            }
            for (int i = start; i < end; i++) {
                if (!isUnquotedKeyChar(s[i])) {
                    int j = i + 1;
                    while (j < end && !isUnquotedKeyChar(s[j])) {
                        j++;
                    }
                    throw fail("invalid unquoted key", i, j, expected);
                }
            }
            return source.text(start, end);
        }

        private static boolean isUnquotedKeyChar(byte b) {
            return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '-' || b == '_';
        }

        private static boolean isLiteralChar(int b) {
            return b == 0x09 || (b >= 0x20 && b <= 0x26) || (b >= 0x28 && b <= 0x7E) || b >= 0x80;
        }

        private static boolean isBasicUnescaped(int b) {
            return b == ' ' || b == '\t' || b == 0x21 || (b >= 0x23 && b <= 0x5B) || (b >= 0x5D && b <= 0x7E) || b >= 0x80;
        }

        private String literalString(int start, int end) {
            String invalid = "invalid literal string";
            int a = start;
            if (a < end && s[a] == '\'') {
                a++;
            } else {
                throw fail(invalid, start, start, lit("'"));
            }
            int b = end;
            if (b > a && s[b - 1] == '\'') {
                b--;
            } else {
                throw fail(invalid, end, end, lit("'"));
            }
            for (int i = a; i < b; i++) {
                if (!isLiteralChar(s[i] & 0xFF)) {
                    throw fail(invalid, i, i, "non-single-quote visible characters");
                }
            }
            return source.text(a, b);
        }

        private int skipStartNewline(int a, int end) {
            if (a < end && s[a] == '\n') {
                return a + 1;
            }
            if (a + 1 < end && s[a] == '\r' && s[a + 1] == '\n') {
                return a + 2;
            }
            return a;
        }

        private String mlLiteralString(int start, int end) {
            String invalid = "invalid multi-line literal string";
            int a = skipStartNewline(start + 3, end);
            int b = end;
            if (b - a >= 3 && startsWith(b - 3, '\'', 3)) {
                b -= 3;
            } else {
                throw fail(invalid, end, end, lit("'"));
            }
            for (int i = a; i < b; i++) {
                int c = s[i] & 0xFF;
                if (c == '\'' || c == '\n') {
                    continue;
                }
                if (c == '\r') {
                    if (i + 1 >= b || s[i + 1] != '\n') {
                        throw fail("carriage return must be followed by newline", i + 1, i + 1, lit("\n"));
                    }
                } else if (!isLiteralChar(c)) {
                    throw fail(invalid, i, i, "non-single-quote characters");
                }
            }
            return source.text(a, b);
        }

        private String basicString(int start, int end) {
            String invalid = "invalid basic string";
            int a = start + 1;
            int b = end;
            if (b > a && s[b - 1] == '"') {
                b--;
            } else {
                throw fail(invalid, end, end, lit("\""));
            }
            StringBuilder out = new StringBuilder();
            int[] at = {a};
            while (true) {
                int run = at[0];
                while (at[0] < b && isBasicUnescaped(s[at[0]] & 0xFF)) {
                    at[0]++;
                }
                out.append(source.text(run, at[0]));
                if (at[0] >= b) {
                    return out.toString();
                }
                if (s[at[0]] == '\\') {
                    at[0]++;
                    out.appendCodePoint(escape(at, b));
                } else {
                    int from = at[0];
                    int to = from;
                    while (to < b && !isBasicUnescaped(s[to] & 0xFF) && s[to] != '\\') {
                        to++;
                    }
                    throw fail(invalid, from, to, "non-double-quote visible characters", lit("\\"));
                }
            }
        }

        private int escape(int[] at, int end) {
            String[] expected = {
                lit("b"), lit("e"), lit("f"), lit("n"), lit("r"), lit("\\"), lit("\""), lit("x"), lit("u"), lit("U")
            };
            int i = at[0];
            if (i >= end) {
                throw fail("missing escaped value", i, i, expected);
            }
            at[0] = i + 1;
            return switch (s[i]) {
                case 'b' -> 0x08;
                case 'e' -> 0x1B;
                case 'f' -> 0x0C;
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'x' -> hexEscape(at, end, 2);
                case 'u' -> hexEscape(at, end, 4);
                case 'U' -> hexEscape(at, end, 8);
                case '\\' -> '\\';
                case '"' -> '"';
                default -> throw fail("missing escaped value", i, i, expected);
            };
        }

        private int hexEscape(int[] at, int end, int digits) {
            int from = at[0];
            int to = from;
            while (to < end && to - from < digits && Character.digit(s[to], 16) >= 0) {
                to++;
            }
            at[0] = to;
            if (to - from != digits) {
                throw fail("too few unicode value digits", to, to, "unicode hexadecimal value");
            }
            long value = Long.parseLong(source.text(from, to), 16);
            if (value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) {
                throw fail("invalid value", from, from, "unicode hexadecimal value");
            }
            return (int) value;
        }

        private String mlBasicString(int start, int end) {
            String invalid = "invalid multi-line basic string";
            int a = skipStartNewline(start + 3, end);
            int b = end;
            if (b - a >= 3 && startsWith(b - 3, '"', 3)) {
                b -= 3;
            } else {
                throw fail(invalid, end, end, lit("\""));
            }
            StringBuilder out = new StringBuilder();
            int[] at = {a};
            while (true) {
                int run = at[0];
                while (at[0] < b && isMlbUnescaped(s[at[0]] & 0xFF)) {
                    at[0]++;
                }
                out.append(source.text(run, at[0]));
                if (at[0] >= b) {
                    return out.toString();
                }
                int i = at[0];
                if (s[i] == '\\') {
                    at[0]++;
                    if (at[0] < b && (s[at[0]] == ' ' || s[at[0]] == '\t' || s[at[0]] == '\r' || s[at[0]] == '\n')) {
                        escapedNewline(at, b);
                    } else {
                        out.appendCodePoint(escape(at, b));
                    }
                } else if (s[i] == '\r') {
                    if (i + 1 >= b || s[i + 1] != '\n') {
                        throw fail("carriage return must be followed by newline", i + 1, i + 1, lit("\n"));
                    }
                    out.append("\r\n");
                    at[0] += 2;
                } else {
                    int to = i;
                    while (to < b && !isMlbUnescaped(s[to] & 0xFF) && s[to] != '\\' && s[to] != '\r') {
                        to++;
                    }
                    throw fail(invalid, i, to, lit("\\"), "characters");
                }
            }
        }

        private static boolean isMlbUnescaped(int b) {
            return isBasicUnescaped(b) || b == '"' || b == '\n';
        }

        private void escapedNewline(int[] at, int end) {
            int i = at[0];
            while (i < end && (s[i] == ' ' || s[i] == '\t')) {
                i++;
            }
            if (i < end && s[i] == '\n') {
                i++;
            } else if (i < end && s[i] == '\r') {
                i++;
                if (i < end && s[i] == '\n') {
                    i++;
                } else {
                    throw fail("carriage return must be followed by newline", i, i, lit("\n"));
                }
            } else {
                throw fail("invalid multi-line basic string", i, i, lit("\n"));
            }
            while (true) {
                int before = i;
                while (i < end && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n')) {
                    i++;
                }
                if (i < end && s[i] == '\r') {
                    if (i + 1 < end && s[i + 1] == '\n') {
                        i += 2;
                    } else {
                        throw fail("carriage return must be followed by newline", i + 1, i + 1, lit("\n"));
                    }
                }
                if (i == before) {
                    break;
                }
            }
            at[0] = i;
        }

        private Value decodeScalar(int e) {
            int start = es.a[e];
            int end = ee.a[e];
            String text;
            switch (ec.a[e]) {
                case ENC_LITERAL -> text = literalString(start, end);
                case ENC_BASIC -> text = basicString(start, end);
                case ENC_ML_LITERAL -> text = mlLiteralString(start, end);
                case ENC_ML_BASIC -> text = mlBasicString(start, end);
                default -> {
                    return new Scalar(this, start, end).decode();
                }
            }
            Value value = new Value(source, Kind.STRING, start, end);
            value.text = text;
            return value;
        }
    }

    /** One unquoted value. Positions are chars of {@code raw} and turn into byte offsets in {@code fail}. */
    private static final class Scalar {
        private final Parser parser;
        private final Source source;
        private final int start;
        private final int end;
        private final String raw;

        Scalar(Parser parser, int start, int end) {
            this.parser = parser;
            this.source = parser.source;
            this.start = start;
            this.end = end;
            this.raw = source.text(start, end);
        }

        private Error fail(String description, int from, int to, String... expected) {
            return parser.fail(description, start + utf8Length(from), start + utf8Length(to), expected);
        }

        private int utf8Length(int chars) {
            return raw.substring(0, chars).getBytes(StandardCharsets.UTF_8).length;
        }

        private Value of(Kind kind) {
            return new Value(source, kind, start, end);
        }

        Value decode() {
            if (raw.isEmpty()) {
                throw invalid();
            }
            char first = raw.charAt(0);
            if (!isDigit(first) && raw.contains(" ")) {
                throw invalid();
            }
            return switch (first) {
                case '+', '-' -> signed();
                // A leading underscore or dot reads as a mistyped number, not as a bare string.
                case '_', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> dateOrNumber(0);
                case '0' -> zeroPrefixed(0, false);
                case '.' -> throw fail("invalid mantissa", 0, 0, "digits");
                case 't', 'T' -> symbol("true", Kind.BOOLEAN);
                case 'f', 'F' -> symbol("false", Kind.BOOLEAN);
                case 'i', 'I' -> symbol("inf", Kind.FLOAT);
                case 'n', 'N' -> symbol("nan", Kind.FLOAT);
                default -> throw invalid();
            };
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private Error invalid() {
            int length = raw.length();
            if (raw.endsWith("'''")) {
                return fail("missing opening quote", 0, 0, lit("'''"));
            }
            if (raw.endsWith("\"\"\"")) {
                return fail("missing opening quote", 0, 0, lit("\"\"\""));
            }
            if (raw.endsWith("'")) {
                return fail("missing opening quote", 0, 0, lit("'"));
            }
            if (raw.endsWith("\"")) {
                return fail("missing opening quote", 0, 0, lit("\""));
            }
            return fail("string values must be quoted", 0, length, "literal string");
        }

        private Value symbol(String symbol, Kind kind) {
            if (!raw.equals(symbol)) {
                String description = kind == Kind.BOOLEAN ? "invalid boolean" : "invalid float";
                throw fail(description, 0, raw.length(), lit(symbol));
            }
            Value value = of(kind);
            value.text = symbol;
            value.bool = symbol.equals("true");
            return value;
        }

        private Value signed() {
            int at = 1;
            if (at >= raw.length()) {
                throw invalid();
            }
            char first = raw.charAt(at);
            if (first == '+' || first == '-') {
                throw fail("redundant numeric sign", at, at + 1);
            }
            switch (first) {
                case '_', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                    return dateOrNumber(at);
                }
                case '0' -> {
                    return zeroPrefixed(at, true);
                }
                case '.' -> {
                    // The crate checks the signed text here, so the sign is what it rejects.
                    throw fail("invalid mantissa", 0, 0, "digits");
                }
                case 'i', 'I', 'n', 'N' -> {
                    String symbol = first == 'i' || first == 'I' ? "inf" : "nan";
                    if (!raw.substring(at).equals(symbol)) {
                        throw fail("invalid float", at, raw.length(), lit(symbol));
                    }
                    Value value = of(Kind.FLOAT);
                    value.text = raw;
                    return value;
                }
                default -> throw invalid();
            }
        }

        private Value zeroPrefixed(int at, boolean signed) {
            if (raw.length() - at == 1) {
                return number(0, Kind.INTEGER, 10);
            }
            char marker = raw.charAt(at + 1);
            int radix =
                    switch (marker) {
                        case 'x', 'X' -> 16;
                        case 'o', 'O' -> 8;
                        case 'b', 'B' -> 2;
                        case 'd', 'D' -> 10;
                        default -> 0;
                    };
            if (radix == 0) {
                return dateOrNumber(at);
            }
            if (raw.indexOf(' ', at) >= 0) {
                throw invalid();
            }
            if (signed) {
                throw fail("integers with a radix cannot be signed", 0, 1);
            }
            if (radix == 10) {
                throw fail("redundant integer number prefix", 0, 2);
            }
            if (Character.isUpperCase(marker)) {
                throw fail("radix must be lowercase", at, at + 2, lit("0" + Character.toLowerCase(marker)));
            }
            int digits = at + 2;
            if (digits < raw.length() && (raw.charAt(digits) == '+' || raw.charAt(digits) == '-')) {
                int sign = Math.min(indexOrMax(raw.indexOf('+')), indexOrMax(raw.indexOf('-')));
                throw fail("unexpected sign", sign, sign + 1);
            }
            for (int i = digits; i < raw.length(); ) {
                int c = raw.codePointAt(i);
                if (c != '_' && (c > 0x7F || Character.digit(c, radix) < 0)) {
                    String description =
                            radix == 16
                                    ? "invalid hexadecimal number"
                                    : radix == 8 ? "invalid octal number" : "invalid binary number";
                    int at2 = start + utf8Length(i);
                    throw source.error(description, at2, at2);
                }
                i += Character.charCount(c);
            }
            return number(digits, Kind.INTEGER, radix);
        }

        private static int indexOrMax(int index) {
            return index < 0 ? Integer.MAX_VALUE : index;
        }

        private Value dateOrNumber(int at) {
            int digitEnd = at;
            while (digitEnd < raw.length() && isDigit(raw.charAt(digitEnd))) {
                digitEnd++;
            }
            if (digitEnd == raw.length()) {
                ensureNoLeadingZero(at);
                return number(0, Kind.INTEGER, 10);
            }
            String rest = raw.substring(digitEnd);
            if (rest.startsWith("-") || rest.startsWith(":")) {
                String problem = datetimeProblem(raw);
                if (problem != null) {
                    throw source.error(problem, start, end);
                }
                Value value = of(Kind.DATETIME);
                value.text = raw;
                return value;
            }
            if (rest.contains(" ")) {
                throw invalid();
            }
            if (rest.indexOf('.') >= 0 || rest.indexOf('e') >= 0 || rest.indexOf('E') >= 0) {
                ensureFloat(at);
                return number(0, Kind.FLOAT, 10);
            }
            if (rest.startsWith("_")) {
                ensureNoLeadingZero(at);
                return number(0, Kind.INTEGER, 10);
            }
            throw invalid();
        }

        private void ensureNoLeadingZero(int at) {
            if (raw.startsWith("0", at)) {
                throw fail("unexpected leading zero", at, at + 1);
            }
        }

        private void ensureFloat(int at) {
            int i = ensureDecUint(at, false, "invalid mantissa");
            if (raw.startsWith(".", i)) {
                i = ensureDecUint(i + 1, true, "invalid fraction");
            }
            if (raw.startsWith("e", i) || raw.startsWith("E", i)) {
                i++;
                if (raw.startsWith("+", i) || raw.startsWith("-", i)) {
                    i++;
                }
                i = ensureDecUint(i, true, "invalid exponent");
            }
            if (i < raw.length()) {
                throw fail("invalid float", i, raw.length());
            }
        }

        private int ensureDecUint(int at, boolean zeroPrefix, String description) {
            int i = at;
            int digits = 0;
            while (i < raw.length() && (isDigit(raw.charAt(i)) || raw.charAt(i) == '_')) {
                if (raw.charAt(i) != '_') {
                    digits++;
                }
                i++;
            }
            if (digits == 0) {
                throw fail(description, at, at, "digits");
            }
            if (digits > 1 && raw.startsWith("0", at) && !zeroPrefix) {
                throw fail("unexpected leading zero", at, at + 1);
            }
            return i;
        }

        private Value number(int from, Kind kind, int radix) {
            StringBuilder out = new StringBuilder();
            for (int i = from; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c != '_') {
                    out.append(c);
                    continue;
                }
                boolean before = i > from && isSeparable(raw.charAt(i - 1), kind);
                boolean after = i + 1 < raw.length() && isSeparable(raw.charAt(i + 1), kind);
                if (!before || !after) {
                    throw fail("`_` may only go between digits", i, i + 1);
                }
            }
            Value value = of(kind);
            value.text = out.toString();
            value.radix = radix;
            return value;
        }

        // The crate accepts any hex digit beside an underscore whatever the radix.
        private static boolean isSeparable(char c, Kind kind) {
            return isDigit(c) || (kind != Kind.FLOAT && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')));
        }
    }

    // toml_datetime's validator. Returns the message, or null for a valid date-time.

    private static final int D_DIGITS = 0;
    private static final int D_DASH = 1;
    private static final int D_COLON = 2;
    private static final int D_DOT = 3;
    private static final int D_T = 4;
    private static final int D_SPACE = 5;
    private static final int D_Z = 6;
    private static final int D_PLUS = 7;
    private static final int D_UNKNOWN = 8;
    private static final int D_NONE = -1;

    private static final class DateLexer {
        final String text;
        int at;
        String raw;

        DateLexer(String text) {
            this.text = text;
        }

        int next() {
            if (at >= text.length()) {
                raw = "";
                return D_NONE;
            }
            int from = at;
            char c = text.charAt(at);
            int kind;
            if (c >= '0' && c <= '9') {
                while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                    at++;
                }
                kind = D_DIGITS;
            } else {
                kind =
                        switch (c) {
                            case '-' -> D_DASH;
                            case ':' -> D_COLON;
                            case 'T', 't' -> D_T;
                            case ' ' -> D_SPACE;
                            case 'Z', 'z' -> D_Z;
                            case '+' -> D_PLUS;
                            case '.' -> D_DOT;
                            default -> D_UNKNOWN;
                        };
                at = kind == D_UNKNOWN ? text.length() : at + 1;
            }
            raw = text.substring(from, at);
            return kind;
        }

        int peek() {
            int saved = at;
            int kind = next();
            at = saved;
            return kind;
        }

        String expect(int kind, String what, String expected) {
            return next() == kind ? null : problem(what, expected);
        }
    }

    private static String problem(String what, String expected) {
        return "invalid " + (what == null ? "datetime" : what) + (expected == null ? "" : ", expected " + expected);
    }

    static String datetimeProblem(String text) {
        DateLexer lexer = new DateLexer(text);
        String p = lexer.expect(D_DIGITS, null, "year or hour");
        if (p != null) {
            return p;
        }
        String year = lexer.raw;
        boolean hasDate = false;
        switch (lexer.next()) {
            case D_DASH -> {
                if ((p = lexer.expect(D_DIGITS, "date", "month")) != null) {
                    return p;
                }
                String month = lexer.raw;
                if ((p = lexer.expect(D_DASH, "date", "`-` (MM-DD)")) != null) {
                    return p;
                }
                if ((p = lexer.expect(D_DIGITS, "date", "day")) != null) {
                    return p;
                }
                String day = lexer.raw;
                if (year.length() != 4) {
                    return problem("date", "a four-digit year (YYYY)");
                }
                if (month.length() != 2) {
                    return problem("date", "a two-digit month (MM)");
                }
                if (day.length() != 2) {
                    return problem("date", "a two-digit day (DD)");
                }
                int y = Integer.parseInt(year);
                int m = Integer.parseInt(month);
                int d = Integer.parseInt(day);
                if (m < 1 || m > 12) {
                    return problem("date", "month between 01 and 12");
                }
                boolean leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
                int max = m == 2 ? (leap ? 29 : 28) : (m == 4 || m == 6 || m == 9 || m == 11) ? 30 : 31;
                if (d < 1 || d > max) {
                    return problem("date", "day between 01 and " + max);
                }
                hasDate = true;
            }
            // The caller only passes text whose leading digits are followed by `-` or `:`.
            default -> lexer.at = 0;
        }

        boolean hasTime = true;
        if (hasDate) {
            int separator = lexer.next();
            if (separator == D_NONE) {
                hasTime = false;
            } else if (separator != D_T && separator != D_SPACE) {
                return problem("date-time", "`T` between date and time");
            }
        }
        if (hasTime) {
            if ((p = lexer.expect(D_DIGITS, "time", "hour")) != null) {
                return p;
            }
            String hour = lexer.raw;
            if ((p = lexer.expect(D_COLON, "time", "`:` (HH:MM)")) != null) {
                return p;
            }
            if ((p = lexer.expect(D_DIGITS, "time", "minute")) != null) {
                return p;
            }
            String minute = lexer.raw;
            String second = null;
            if (lexer.peek() == D_COLON) {
                lexer.next();
                if ((p = lexer.expect(D_DIGITS, "time", "second")) != null) {
                    return p;
                }
                second = lexer.raw;
                if (lexer.peek() == D_DOT) {
                    lexer.next();
                    if ((p = lexer.expect(D_DIGITS, "time", "nanosecond")) != null) {
                        return p;
                    }
                }
            }
            if (hour.length() != 2) {
                return problem("time", "a two-digit hour (HH)");
            }
            if (minute.length() != 2) {
                return problem("time", "a two-digit minute (MM)");
            }
            if (second != null && second.length() != 2) {
                return problem("time", "a two-digit second (SS)");
            }
            if (Integer.parseInt(hour) > 23) {
                return problem("time", "hour between 00 and 23");
            }
            if (Integer.parseInt(minute) > 59) {
                return problem("time", "minute between 00 and 59");
            }
            if (second != null && Integer.parseInt(second) > 60) {
                return problem("time", "second between 00 and 60");
            }
        }

        if (hasDate && hasTime) {
            int kind = lexer.next();
            if (kind == D_PLUS || kind == D_DASH) {
                if ((p = lexer.expect(D_DIGITS, "offset", "hour")) != null) {
                    return p;
                }
                String hours = lexer.raw;
                if ((p = lexer.expect(D_COLON, "offset", "`:` (HH:MM)")) != null) {
                    return p;
                }
                if ((p = lexer.expect(D_DIGITS, "offset", "minute")) != null) {
                    return p;
                }
                String minutes = lexer.raw;
                if (hours.length() != 2) {
                    return problem("offset", "a two-digit hour (HH)");
                }
                if (minutes.length() != 2) {
                    return problem("offset", "a two-digit minute (MM)");
                }
                if (Integer.parseInt(hours) > 23) {
                    return problem("offset", "hours between 00 and 23");
                }
                if (Integer.parseInt(minutes) > 59) {
                    return problem("offset", "minutes between 00 and 59");
                }
            } else if (kind != D_Z && kind != D_NONE) {
                return problem("offset", "`Z`, +OFFSET, -OFFSET");
            }
        }
        return lexer.at < text.length() ? problem(null, null) : null;
    }
}
