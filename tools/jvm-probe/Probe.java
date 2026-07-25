import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answer-checks uika verdicts against a real JVM. Reads the JSON Lines stream
 * written by {@code uika check --verdicts-json}, resolves each reference with
 * {@link MethodHandles.Lookup} against the new-side classpath, and reports
 * disagreements:
 *
 * <ul>
 *   <li>false-positive candidate: uika said broken, the JVM links it;
 *   <li>false-negative candidate: uika said ok/unknown, the JVM fails it on the
 *       new side but links it on the old side (without {@code --old-classpath}
 *       every new-side failure is listed, including pre-existing ones uika
 *       deliberately does not report).
 * </ul>
 *
 * The probe is evidence, not ground truth. Known approximations: findVirtual
 * does not model invokespecial selection; a write to a final field is probed as
 * a read when the writer is the declaring class (the JVM allows it only inside
 * initializers, which the verdict stream cannot see); when the referencing
 * class itself cannot be loaded the probe falls back to a caller-context-free
 * lookup, losing private/protected access rights (two-sided runs cancel the
 * resulting noise out).
 *
 * Zero dependencies; run directly: {@code java Probe.java --verdicts v.jsonl
 * --classpath new.jar:consumer.jar [--old-classpath old.jar:consumer.jar]
 * [--fail-on-fp]}
 */
public final class Probe {

    record Ref(String kind, String owner, String memberName, String memberDesc,
               Boolean expectedStatic, Boolean fieldWrite) {}

    record Rec(String source, String sourceClass, Ref ref, String verdict, String reason) {}

    enum Outcome { LINKS, FAILS, SKIP, ERROR }

    record Result(Outcome outcome, String detail) {
        static final Result LINKS = new Result(Outcome.LINKS, "");
        static Result fails(Throwable t) {
            return new Result(Outcome.FAILS, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        Path verdicts = null;
        String classpath = null;
        String oldClasspath = null;
        boolean failOnFp = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--verdicts" -> verdicts = Path.of(args[++i]);
                case "--classpath" -> classpath = args[++i];
                case "--old-classpath" -> oldClasspath = args[++i];
                case "--fail-on-fp" -> failOnFp = true;
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (verdicts == null || classpath == null) {
            System.err.println("usage: java Probe.java --verdicts <file.jsonl> --classpath <cp> "
                    + "[--old-classpath <cp>] [--fail-on-fp]");
            System.exit(2);
        }

        try (URLClassLoader newLoader = loader(classpath);
             URLClassLoader oldLoader = oldClasspath == null ? null : loader(oldClasspath)) {
            int exit = run(verdicts, newLoader, oldLoader, failOnFp);
            System.exit(exit);
        }
    }

    static int run(Path verdicts, ClassLoader newLoader, ClassLoader oldLoader, boolean failOnFp)
            throws IOException {
        // verdict -> outcome -> count
        Map<String, Map<Outcome, Integer>> matrix = new LinkedHashMap<>();
        List<String> fpCandidates = new ArrayList<>();
        List<String> fnCandidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int preExisting = 0;
        int records = 0;

        for (String line : Files.readAllLines(verdicts)) {
            if (line.isBlank()) continue;
            records++;
            Rec rec = parseRec(line);
            Result result = probe(rec, newLoader);
            matrix.computeIfAbsent(rec.verdict(), k -> new LinkedHashMap<>())
                    .merge(result.outcome(), 1, Integer::sum);
            switch (result.outcome()) {
                case ERROR -> errors.add(describe(rec) + " -> " + result.detail());
                case LINKS -> {
                    if (rec.verdict().equals("broken")) {
                        fpCandidates.add(describe(rec) + " [" + rec.reason() + "] links fine");
                    }
                }
                case FAILS -> {
                    if (!rec.verdict().equals("broken")) {
                        // Only breakage the change introduced counts: a reference that
                        // also fails on the old side is pre-existing, which uika
                        // deliberately does not report.
                        if (oldLoader != null && probe(rec, oldLoader).outcome() != Outcome.LINKS) {
                            preExisting++;
                        } else {
                            fnCandidates.add(describe(rec) + " [" + rec.verdict() + "] "
                                    + result.detail());
                        }
                    }
                }
                case SKIP -> {}
            }
        }

        System.out.println("probed " + records + " verdict records from " + verdicts);
        matrix.forEach((verdict, outcomes) -> System.out.println("  " + verdict + ": " + outcomes));
        if (preExisting > 0) {
            System.out.println("  pre-existing failures (fail on both sides, not uika's scope): "
                    + preExisting);
        }
        printList("PROBE ERRORS (probe bug or unsupported shape)", errors);
        printList("FP candidates (uika broken, JVM links)", fpCandidates);
        printList("FN candidates (uika ok/unknown, JVM fails"
                + (oldLoader == null ? "; no --old-classpath, may include pre-existing" : "")
                + ")", fnCandidates);
        if (failOnFp && !fpCandidates.isEmpty()) {
            System.out.println("FAIL: " + fpCandidates.size() + " false-positive candidate(s)");
            return 1;
        }
        return 0;
    }

    static void printList(String header, List<String> items) {
        if (items.isEmpty()) return;
        System.out.println(header + ":");
        items.forEach(i -> System.out.println("  " + i));
    }

    static String describe(Rec rec) {
        Ref r = rec.ref();
        String member = r.memberName() == null ? "" : "." + r.memberName() + " " + r.memberDesc();
        return rec.sourceClass() + " -> " + r.kind() + " " + r.owner() + member;
    }

    static URLClassLoader loader(String classpath) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(":")) {
            if (!entry.isEmpty()) urls.add(Path.of(entry).toUri().toURL());
        }
        // Platform parent: JDK classes resolve, the probe's own classes do not leak in.
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    static Result probe(Rec rec, ClassLoader loader) {
        try {
            Class<?> owner = Class.forName(dots(rec.ref().owner()), false, loader);
            if (rec.ref().kind().equals("class")) return Result.LINKS;
            MethodHandles.Lookup lookup = lookupFor(rec, loader);
            return switch (rec.ref().kind()) {
                case "method", "interface_method" -> probeMethod(rec.ref(), owner, lookup, loader);
                case "field" -> probeField(rec, owner, lookup, loader);
                default -> new Result(Outcome.SKIP, "unknown kind " + rec.ref().kind());
            };
        } catch (ClassNotFoundException | TypeNotPresentException | LinkageError e) {
            return Result.fails(e);
        } catch (Throwable t) {
            return new Result(Outcome.ERROR, t.toString());
        }
    }

    /**
     * Full-power lookup in the referencing class when it loads; otherwise a
     * caller-context-free public lookup (noted in two-sided noise handling).
     */
    static MethodHandles.Lookup lookupFor(Rec rec, ClassLoader loader) throws IllegalAccessException {
        try {
            Class<?> src = Class.forName(dots(rec.sourceClass()), false, loader);
            return MethodHandles.privateLookupIn(src, MethodHandles.lookup());
        } catch (ClassNotFoundException | LinkageError e) {
            return MethodHandles.lookup();
        }
    }

    static Result probeMethod(Ref ref, Class<?> owner, MethodHandles.Lookup lookup,
                              ClassLoader loader) {
        if (ref.memberName().equals("<clinit>")) return new Result(Outcome.SKIP, "<clinit>");
        try {
            MethodType mt = MethodType.fromMethodDescriptorString(ref.memberDesc(), loader);
            if (ref.memberName().equals("<init>")) {
                lookup.findConstructor(owner, mt.changeReturnType(void.class));
            } else if (ref.expectedStatic() == null) {
                // Constant-pool-only reference with no opcode context: either linkage form counts.
                try {
                    lookup.findVirtual(owner, ref.memberName(), mt);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    lookup.findStatic(owner, ref.memberName(), mt);
                }
            } else if (ref.expectedStatic()) {
                lookup.findStatic(owner, ref.memberName(), mt);
            } else {
                lookup.findVirtual(owner, ref.memberName(), mt);
            }
            return Result.LINKS;
        } catch (NoSuchMethodException | IllegalAccessException | TypeNotPresentException
                 | LinkageError e) {
            return Result.fails(e);
        }
    }

    static Result probeField(Rec rec, Class<?> owner, MethodHandles.Lookup lookup,
                             ClassLoader loader) {
        Ref ref = rec.ref();
        try {
            Class<?> type = fieldType(ref.memberDesc(), loader);
            // The JVM allows a final-field write inside the declaring class's own
            // initializers, which the stream cannot distinguish; probe those as reads.
            boolean write = Boolean.TRUE.equals(ref.fieldWrite())
                    && !rec.sourceClass().equals(ref.owner());
            if (ref.expectedStatic() == null) {
                try {
                    findField(lookup, owner, ref.memberName(), type, false, write);
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    findField(lookup, owner, ref.memberName(), type, true, write);
                }
            } else {
                findField(lookup, owner, ref.memberName(), type, ref.expectedStatic(), write);
            }
            return Result.LINKS;
        } catch (NoSuchFieldException | IllegalAccessException | ClassNotFoundException
                 | TypeNotPresentException | LinkageError e) {
            return Result.fails(e);
        }
    }

    static void findField(MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?> type,
                          boolean isStatic, boolean write)
            throws NoSuchFieldException, IllegalAccessException {
        if (isStatic) {
            if (write) lookup.findStaticSetter(owner, name, type);
            else lookup.findStaticGetter(owner, name, type);
        } else {
            if (write) lookup.findSetter(owner, name, type);
            else lookup.findGetter(owner, name, type);
        }
    }

    static Class<?> fieldType(String descriptor, ClassLoader loader) throws ClassNotFoundException {
        return switch (descriptor.charAt(0)) {
            case 'B' -> byte.class;
            case 'C' -> char.class;
            case 'D' -> double.class;
            case 'F' -> float.class;
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'S' -> short.class;
            case 'Z' -> boolean.class;
            case 'L' -> Class.forName(
                    dots(descriptor.substring(1, descriptor.length() - 1)), false, loader);
            // Array binary names keep the descriptor form, with dots.
            case '[' -> Class.forName(dots(descriptor), false, loader);
            default -> throw new IllegalArgumentException("bad field descriptor: " + descriptor);
        };
    }

    static String dots(String internalName) {
        return internalName.replace('/', '.');
    }

    @SuppressWarnings("unchecked")
    static Rec parseRec(String line) {
        Map<String, Object> o = (Map<String, Object>) Json.parse(line);
        Map<String, Object> r = (Map<String, Object>) o.get("reference");
        Map<String, Object> m = (Map<String, Object>) r.get("member");
        Ref ref = new Ref(
                (String) r.get("kind"),
                (String) r.get("owner"),
                m == null ? null : (String) m.get("name"),
                m == null ? null : (String) m.get("descriptor"),
                (Boolean) r.get("expected_static"),
                (Boolean) r.get("field_write"));
        return new Rec(
                (String) o.get("source"),
                (String) o.get("source_class"),
                ref,
                (String) o.get("verdict"),
                (String) o.get("reason"));
    }

    /** Minimal JSON parser (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json p = new Json(s);
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) throw p.err("trailing data");
            return v;
        }

        private Object value() {
            ws();
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                map.put(key, value());
                ws();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw err("expected , or }");
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(value());
                ws();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw err("expected , or ]");
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("bad escape \\" + esc);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String text = s.substring(start, i);
            if (text.isEmpty()) throw err("unexpected character");
            return text.contains(".") || text.contains("e") || text.contains("E")
                    ? (Object) Double.parseDouble(text)
                    : (Object) Long.parseLong(text);
        }

        private Object literal(String text, Object value) {
            if (!s.startsWith(text, i)) throw err("expected " + text);
            i += text.length();
            return value;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            if (i >= s.length()) throw err("unexpected end");
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) throw err("expected " + c);
        }

        private IllegalArgumentException err(String message) {
            return new IllegalArgumentException("JSON error at " + i + ": " + message);
        }
    }
}
