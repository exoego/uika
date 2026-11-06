package net.exoego.uika.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinTask;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Validates one flat runtime classpath, independently of any previous version. */
final class Snapshot {
    private static final int EXIT_CLEAN = 0;
    private static final int EXIT_VIOLATIONS = 1;
    private static final int EXIT_INCOMPLETE = 2;
    private static final int SCHEMA_VERSION = 1;
    private static final int RECORD_SOURCE = 0;
    private static final int RECORD_CLASS = 1;
    private static final int RECORD_COUNT = 2;
    private static final int RECORD_HEADER_WORDS = 3;
    private static final String JAR_INDEX_ENTRY = "META-INF/INDEX.LIST";

    private Snapshot() {}

    private enum Kind {
        CLASS_NOT_FOUND("class_not_found"),
        METHOD_NOT_FOUND("method_not_found"),
        FIELD_NOT_FOUND("field_not_found"),
        CLASS_NOT_ACCESSIBLE("class_not_accessible"),
        METHOD_NOT_ACCESSIBLE("method_not_accessible"),
        FIELD_NOT_ACCESSIBLE("field_not_accessible"),
        CLASS_NOT_INSTANTIABLE("class_not_instantiable"),
        EXTENDS_FINAL_CLASS("extends_final_class"),
        NOT_PERMITTED_SUBCLASS("not_permitted_subclass"),
        EXPECTED_CLASS("expected_class"),
        EXPECTED_INTERFACE("expected_interface"),
        ABSTRACT_METHOD_UNIMPLEMENTED("abstract_method_unimplemented"),
        ABSTRACT_METHOD_CALLED("abstract_method_called"),
        CONFLICTING_DEFAULT_METHODS("conflicting_default_methods"),
        OVERRIDES_FINAL_METHOD("overrides_final_method"),
        EXPECTED_INSTANCE_METHOD("expected_instance_method"),
        EXPECTED_STATIC_METHOD("expected_static_method"),
        EXPECTED_INSTANCE_FIELD("expected_instance_field"),
        EXPECTED_STATIC_FIELD("expected_static_field"),
        WRITE_TO_FINAL_FIELD("write_to_final_field"),
        SERVICE_PROVIDER_NOT_FOUND("service_provider_not_found"),
        SERVICE_PROVIDER_NOT_INSTANTIABLE("service_provider_not_instantiable");

        final String text;

        Kind(String text) {
            this.text = text;
        }
    }

    record Issue(String id, int source, int sourceClass, SymbolRef reference, Kind kind, int provider) {}

    private record IssueKey(int source, String id) {}

    static final class Result {
        final List<Issue> violations = new ArrayList<>();
        private final Set<IssueKey> seen = new HashSet<>();
        final Set<String> diagnostics = new TreeSet<>(Text::compareUtf8);
        int scannedClasses;
        int unknownRefs;

        int exitCode() {
            return !diagnostics.isEmpty() || unknownRefs != 0 ? EXIT_INCOMPLETE : violations.isEmpty() ? EXIT_CLEAN : EXIT_VIOLATIONS;
        }

        void issue(int source, int sourceClass, SymbolRef reference, Kind kind, int provider) {
            String id = requirementId(sourceClass, reference, kind);
            if (seen.add(new IssueKey(source, id))) {
                violations.add(new Issue(id, source, sourceClass, reference, kind, provider));
            }
        }
    }

    // Schema 1 identity encoding is fixed independently of report formatting.
    private static String requirementId(int sourceClass, SymbolRef reference, Kind kind) {
        StringBuilder key = new StringBuilder();
        Json.Writer json = new Json.Writer(key, false);
        json.beginArray().sym(sourceClass).value(kind.text);
        json.beginObject();
        json.key("kind").value(reference.kind().json);
        json.key("owner").sym(reference.owner());
        if (reference.hasMember()) {
            json.key("member").beginObject();
            json.key("name").sym(MemberKey.name(reference.member()));
            json.key("descriptor").sym(MemberKey.descriptor(reference.member()));
            json.endObject();
        }
        if (reference.expectedStatic() != null) {
            json.key("expected_static").value(reference.expectedStatic());
        }
        if (reference.fieldWrite() != null) {
            json.key("field_write").value(reference.fieldWrite());
        }
        if (reference.instantiated() != null) {
            json.key("instantiated").value(reference.instantiated());
        }
        json.endObject();
        json.endArray();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static int run(Cli.Validate args) {
        Result result = validate(args.classpath(), args.jdkRelease());
        if (args.json()) {
            Out.out.println(json(result));
        } else {
            Out.out.println("validated " + result.scannedClasses + " classes on JDK " + Runtime.version().feature());
            for (Issue issue : result.violations) {
                Out.out.println(Intern.str(issue.sourceClass()) + ": " + issue.kind().text + " (" + Intern.str(issue.source()) + ")");
                Out.out.println("    " + Intern.str(issue.reference().owner())
                        + (issue.reference().hasMember() ? "." + Intern.str(MemberKey.name(issue.reference().member()))
                                + Intern.str(MemberKey.descriptor(issue.reference().member())) : ""));
            }
            for (String diagnostic : result.diagnostics) {
                Out.err.println("error: " + diagnostic);
            }
            Out.out.println(result.exitCode() == EXIT_INCOMPLETE ? "validation incomplete" : result.violations.size() + " violation(s)");
        }
        return result.exitCode();
    }

    private static final class Leaf {
        final ApiIndex index = new ApiIndex();
        final IntBuf records = new IntBuf();
        final List<String> diagnostics = new ArrayList<>();
    }

    private static final Input.Sink<Leaf> SINK = new Input.Sink<>() {
        @Override
        public boolean strict() {
            return true;
        }

        @Override
        public Leaf newLeaf() {
            return new Leaf();
        }

        @Override
        public void accept(Leaf leaf, Scratch scratch, int source, int entry, ClassSource bytes) {
            int mark = leaf.records.n;
            try {
                bytes.readAll();
                ClassParser parser = scratch.parser;
                parser.collectMethodContext = true;
                parser.parse(bytes.bytes, bytes.available);
                ClassApi api = Extract.extractApi(parser, scratch);
                if (!Intern.str(api.name).equals(Intern.str(entry))) {
                    leaf.diagnostics.add("class entry does not match its binary name: " + Intern.str(source) + "!" + Intern.str(entry));
                    return;
                }
                if (api.sealingUnknown) {
                    leaf.diagnostics.add("unreadable sealing metadata: " + Intern.str(source) + "!" + Intern.str(entry));
                }
                api.source = source;
                leaf.index.insertIfAbsent(api);
                leaf.records.add(source);
                leaf.records.add(api.name);
                Extract.snapshotRefs(leaf.records, parser, scratch);
            } catch (ClassParser.FormatException e) {
                leaf.records.n = mark;
                leaf.diagnostics.add(Extract.context(source, entry, e));
            } finally {
                scratch.parser.collectMethodContext = false;
            }
        }
    };

    static Result validate(List<String> paths, Integer release) {
        Result result = new Result();
        if (paths.isEmpty()) {
            result.diagnostics.add("snapshot classpath is empty");
            return result;
        }
        if (release != null && release != Runtime.version().feature()) {
            result.diagnostics.add("validate must run on JDK " + release + "; the running JDK is " + Runtime.version().feature());
            return result;
        }
        try {
            for (String path : paths) {
                validatePath(path);
            }
            ApiIndex classes = new ApiIndex();
            IntArena references = new IntArena();
            load(paths, classes, references, result);
            result.scannedClasses = classes.classCount();
            if (!result.diagnostics.isEmpty()) {
                return result;
            }
            ApiIndex platform = platform(classes, references, result);
            Scope runtime = new Scope(platform, classes);
            ClassGraph graph = new ClassGraph();
            Check.NestHosts hosts = new Check.NestHosts(graph, Map.of());
            try (URLClassLoader loader = runtimeLoader(paths)) {
                checkReferences(references, platform, runtime, graph, hosts, loader.getUnnamedModule(), result);
                checkStructure(classes, platform, runtime, result);
                checkServices(paths, runtime, graph, result);
                verifyClasses(loader, classes, platform, runtime, result);
            }
        } catch (UikaException | IOException | SecurityException | InvalidPathException e) {
            result.diagnostics.add(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        result.violations.sort(Comparator.comparing(Issue::id).thenComparing((a, b) -> Intern.compare(a.source(), b.source())));
        return result;
    }

    private static void validatePath(String name) throws IOException {
        Path path = Path.of(name);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            throw new UikaException("snapshot input is missing or unreadable: " + name);
        }
        if (Files.isDirectory(path)) {
            return;
        }
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.getJarEntry(JAR_INDEX_ENTRY) != null) {
                throw new UikaException("remove " + JAR_INDEX_ENTRY + " from normalized snapshot inputs: " + name);
            }
            if (jar.isMultiRelease()) {
                throw new UikaException("normalize multi-release JARs for the target JDK before validation: " + name);
            }
            var manifest = jar.getManifest();
            String manifestClasspath = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (manifestClasspath != null && !manifestClasspath.isBlank()) {
                throw new UikaException("expand the manifest Class-Path and remove it from the normalized input: " + name);
            }
            IntSet entries = new IntSet();
            var all = jar.entries();
            while (all.hasMoreElements()) {
                String entry = all.nextElement().getName();
                if (entry.endsWith(".class") && !entries.add(Intern.intern(entry))) {
                    throw new UikaException("duplicate class entry in " + name + ": " + entry);
                }
            }
        }
    }

    /** A bounded window of paths, with span tasks within each path and first-wins merges. */
    private static void load(List<String> paths, ApiIndex classes, IntArena references, Result result) {
        Input.onPool(() -> {
            int window = Scan.window();
            for (int start = 0; start < paths.size(); start += window) {
                List<ForkJoinTask<List<Leaf>>> tasks = new ArrayList<>();
                for (int i = start; i < Math.min(start + window, paths.size()); i++) {
                    String path = paths.get(i);
                    tasks.add(ForkJoinTask.adapt(() -> {
                        List<Leaf> leaves = new ArrayList<>();
                        Input.forEachClass(path, SINK, leaves);
                        return leaves;
                    }).fork());
                }
                RuntimeException failure = null;
                for (int i = 0; i < tasks.size(); i++) {
                    var task = tasks.set(i, null);
                    try {
                        for (Leaf leaf : task.join()) {
                            result.diagnostics.addAll(leaf.diagnostics);
                            int at = 0;
                            while (at < leaf.records.n) {
                                int name = leaf.records.a[at + RECORD_CLASS];
                                int count = leaf.records.a[at + RECORD_COUNT];
                                int end = at + RECORD_HEADER_WORDS + count * SymbolRef.WORDS;
                                if (!classes.containsClass(name)) {
                                    classes.appendRow(leaf.index, leaf.index.entry(name));
                                    for (int k = at; k < end; k++) {
                                        references.add(leaf.records.a[k]);
                                    }
                                }
                                at = end;
                            }
                        }
                    } catch (RuntimeException e) {
                        if (failure == null) {
                            failure = e;
                        }
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        });
    }

    /** Actual running-JDK class files include private APIs and sealing omitted by some ct.sym versions. */
    private static ApiIndex platform(ApiIndex classes, IntArena references, Result result) throws IOException {
        ApiIndex platform = new ApiIndex();
        IntQueue queue = new IntQueue();
        IntSet seen = new IntSet();
        queue.add(Scope.objectSym());
        for (int e = 0; e < classes.classCount(); e++) {
            queue.add(classes.nameOf(e));
            enqueueParents(classes, e, queue);
        }
        for (int at = 0; at < references.size();) {
            int count = references.get(at + RECORD_COUNT);
            for (int i = 0; i < count; i++) {
                queue.add(references.get(at + RECORD_HEADER_WORDS + i * SymbolRef.WORDS + SymbolRef.OWNER_WORD));
            }
            at += RECORD_HEADER_WORDS + count * SymbolRef.WORDS;
        }
        ClassLoader loader = ClassLoader.getPlatformClassLoader();
        Scratch scratch = Scratch.current();
        while (!queue.isEmpty()) {
            int name = queue.poll();
            if (name == Intern.NONE || !seen.add(name)) {
                continue;
            }
            URL resource = loader.getResource(Intern.str(name) + ".class");
            if (resource == null) {
                continue;
            }
            if (!resource.getProtocol().equals("jrt") && !resource.getProtocol().equals("file")) {
                result.diagnostics.add("unsupported platform class source: " + resource);
                continue;
            }
            try (InputStream in = resource.openStream()) {
                byte[] bytes = in.readAllBytes();
                try {
                    scratch.parser.parse(bytes, bytes.length);
                    ClassApi api = Extract.extractApi(scratch.parser, scratch);
                    platform.insertIfAbsent(api);
                    enqueueParents(platform, platform.entry(api.name), queue);
                } catch (ClassParser.FormatException e) {
                    result.diagnostics.add("cannot read platform class " + Intern.str(name) + ": " + e.getMessage());
                }
            }
        }
        return platform;
    }

    private static void enqueueParents(ApiIndex index, int entry, IntQueue queue) {
        if (index.superOf(entry) != Intern.NONE) {
            queue.add(index.superOf(entry));
        }
        if (index.nestHostOf(entry) != Intern.NONE) {
            queue.add(index.nestHostOf(entry));
        }
        for (int i = 0; i < index.interfaceCount(entry); i++) {
            queue.add(index.interfaceAt(entry, i));
        }
    }

    private static void checkReferences(IntArena records, ApiIndex platform, Scope runtime,
            ClassGraph graph, Check.NestHosts hosts, Module consumerModule, Result result) {
        SymMap exported = new SymMap();
        for (int at = 0; at < records.size();) {
            int source = records.get(at + RECORD_SOURCE);
            int consumer = records.get(at + RECORD_CLASS);
            int count = records.get(at + RECORD_COUNT);
            for (int i = 0; i < count && !platform.containsClass(consumer); i++) {
                int base = at + RECORD_HEADER_WORDS + i * SymbolRef.WORDS;
                int meta = records.get(base + SymbolRef.META_WORD);
                int owner = records.get(base + SymbolRef.OWNER_WORD);
                int name = records.get(base + SymbolRef.NAME_WORD);
                int descriptor = records.get(base + SymbolRef.DESCRIPTOR_WORD);
                long key = name == Intern.NONE ? MemberKey.NONE : MemberKey.of(name, descriptor);
                int verdict = Check.verdict(meta, owner, key, consumer, null, runtime, graph, hosts);
                if (verdict == Check.OK && key == MemberKey.NONE && (meta & SymbolRef.DESCRIPTOR_TYPE) == 0
                        && platform.containsClass(owner)) {
                    int accessible = exported.get(owner);
                    if (accessible == SymMap.ABSENT) {
                        try {
                            Class<?> type = Class.forName(Intern.str(owner).replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                            accessible = type.getModule().isExported(type.getPackageName(), consumerModule) ? Check.YES : Check.NO;
                            exported.put(owner, accessible);
                        } catch (ClassNotFoundException | LinkageError e) {
                            result.diagnostics.add("cannot determine platform access for " + Intern.str(owner) + ": " + e);
                            accessible = Check.MAYBE;
                        }
                    }
                    verdict = accessible == Check.YES ? Check.OK : accessible == Check.NO ? Reason.CLASS_ACCESS_NARROWED.ordinal() : Check.UNKNOWN;
                }
                if (verdict == Check.UNKNOWN) {
                    result.unknownRefs++;
                    result.diagnostics.add("unresolved hierarchy for " + Intern.str(consumer) + " -> " + Intern.str(owner));
                } else if (verdict != Check.OK) {
                    SymbolRef reference = SymbolRef.unpack(meta, owner, name, descriptor);
                    Kind kind = verdict == Reason.METHOD_BECAME_ABSTRACT.ordinal() && (meta & SymbolRef.SPECIAL_CALL) != 0
                            ? Kind.ABSTRACT_METHOD_CALLED : kind(Reason.ALL[verdict]);
                    result.issue(source, consumer, reference, kind, provider(runtime, reference));
                }
            }
            at += RECORD_HEADER_WORDS + count * SymbolRef.WORDS;
        }
    }

    private static int provider(Scope runtime, SymbolRef reference) {
        int owner = reference.owner();
        if (reference.hasMember()) {
            Scope.MemberKind kind = reference.kind() == RefKind.FIELD ? Scope.MemberKind.FIELD : Scope.MemberKind.METHOD;
            long resolved = Intern.startsWith(owner, "[") ? runtime.resolveArrayMember(owner, reference.member(), kind)
                    : runtime.resolveMember(owner, reference.member(), kind);
            if (Scope.isFound(resolved)) {
                owner = Scope.foundOwner(resolved);
            }
        }
        ApiIndex layer = runtime.layerOf(owner);
        return layer == null ? Intern.NONE : layer.sourceOf(layer.entry(owner));
    }

    private static void checkStructure(ApiIndex classes, ApiIndex platform, Scope runtime, Result result) {
        for (int e = 0; e < classes.classCount(); e++) {
            int name = classes.nameOf(e);
            if (platform.containsClass(name)) {
                continue;
            }
            int source = classes.sourceOf(e);
            int parent = classes.superOf(e);
            checkParent(name, source, parent, false, runtime, result);
            for (int i = 0; i < classes.interfaceCount(e); i++) {
                checkParent(name, source, classes.interfaceAt(e, i), true, runtime, result);
            }
            for (int i = 0; i < classes.methodCount(e); i++) {
                if ((classes.methodAccessAt(e, i) & Acc.NON_VIRTUAL) != 0) {
                    continue;
                }
                long key = classes.methodKeyAt(e, i);
                int blocker = Check.finalOverrideOwner(name, key, runtime);
                if (blocker >= 0) {
                    SymbolRef reference = new SymbolRef(RefKind.METHOD, blocker, key, false, null, null);
                    result.issue(source, name, reference, Kind.OVERRIDES_FINAL_METHOD, provider(runtime, reference));
                }
            }
            if ((classes.accessOf(e) & (Acc.ABSTRACT | Acc.INTERFACE)) == 0) {
                checkSelection(name, source, runtime, result);
            }
        }
    }

    private static void checkParent(int name, int source, int parent, boolean iface, Scope runtime, Result result) {
        if (parent == Intern.NONE) {
            return;
        }
        ApiIndex layer = runtime.layerOf(parent);
        if (layer == null) {
            result.issue(source, name, SymbolRef.ofClass(parent), Kind.CLASS_NOT_FOUND, Intern.NONE);
            return;
        }
        int entry = layer.entry(parent);
        int access = layer.accessOf(entry);
        Kind violation = ((access & Acc.INTERFACE) != 0) != iface ? (iface ? Kind.EXPECTED_INTERFACE : Kind.EXPECTED_CLASS)
                : !iface && (access & Acc.FINAL) != 0 ? Kind.EXTENDS_FINAL_CLASS
                : layer.permittedCount(entry) >= 0 && !layer.permits(entry, name) ? Kind.NOT_PERMITTED_SUBCLASS : null;
        if (violation != null) {
            result.issue(source, name, SymbolRef.ofClass(parent), violation, layer.sourceOf(entry));
        }
    }

    private static void checkSelection(int name, int source, Scope runtime, Result result) {
        Map<Long, Integer> candidates = new HashMap<>();
        IntQueue queue = new IntQueue();
        IntSet seen = new IntSet();
        queue.add(name);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            ApiIndex layer = runtime.layerOf(current);
            if (layer == null) {
                continue;
            }
            int entry = layer.entry(current);
            for (int i = 0; i < layer.methodCount(entry); i++) {
                int access = layer.methodAccessAt(entry, i);
                if ((access & Acc.NON_VIRTUAL) == 0 && ((access & Acc.ABSTRACT) != 0 || (layer.accessOf(entry) & Acc.INTERFACE) != 0)) {
                    long key = layer.methodKeyAt(entry, i);
                    candidates.merge(key, current, (a, b) -> Intern.compare(a, b) <= 0 ? a : b);
                    if ((layer.accessOf(entry) & Acc.INTERFACE) != 0) {
                        long implementation = runtime.resolveVirtualClassMethod(name, key);
                        if (Scope.isFound(implementation) && (Scope.foundAccess(implementation) & Acc.PUBLIC) == 0) {
                            SymbolRef reference = new SymbolRef(RefKind.INTERFACE_METHOD, current, key, false, null, null);
                            result.issue(source, name, reference, Kind.METHOD_NOT_ACCESSIBLE, provider(runtime, reference));
                        }
                    } else if ((access & Acc.ABSTRACT) != 0 && (access & (Acc.PUBLIC | Acc.PROTECTED)) == 0) {
                        Check.ImplStatus status = Check.packageImplementationStatus(name, current, key, runtime);
                        if (status == Check.ImplStatus.ABSTRACT_ONLY) {
                            SymbolRef reference = new SymbolRef(RefKind.METHOD, current, key, false, null, null);
                            result.issue(source, name, reference, Kind.ABSTRACT_METHOD_UNIMPLEMENTED, provider(runtime, reference));
                        } else if (status == Check.ImplStatus.UNKNOWN) {
                            result.diagnostics.add("inconclusive package-private method selection for " + Intern.str(name));
                        }
                    }
                }
            }
            if (layer.superOf(entry) != Intern.NONE) {
                queue.add(layer.superOf(entry));
            }
            for (int i = 0; i < layer.interfaceCount(entry); i++) {
                queue.add(layer.interfaceAt(entry, i));
            }
        }
        for (var candidate : candidates.entrySet()) {
            Check.ImplStatus status = Check.snapshotImplementationStatus(name, candidate.getKey(), runtime);
            if (status == Check.ImplStatus.ABSTRACT_ONLY || status == Check.ImplStatus.CONFLICT) {
                SymbolRef reference = new SymbolRef(RefKind.METHOD, candidate.getValue(), candidate.getKey(), false, null, null);
                result.issue(source, name, reference, status == Check.ImplStatus.CONFLICT ? Kind.CONFLICTING_DEFAULT_METHODS : Kind.ABSTRACT_METHOD_UNIMPLEMENTED, provider(runtime, reference));
            } else if (status == Check.ImplStatus.UNKNOWN) {
                result.diagnostics.add("inconclusive method selection for " + Intern.str(name) + "." + Intern.str(MemberKey.name(candidate.getKey())));
            }
        }
    }

    private static void checkServices(List<String> paths, Scope runtime, ClassGraph graph, Result result) {
        List<String> warnings = new ArrayList<>();
        for (Reach.ServiceFile file : Reach.collectServices(paths, warnings, /* strict */ true)) {
            for (int provider : file.impls()) {
                int status = Check.spiInstantiable(graph, runtime, provider, file.iface(), true);
                if (status == Check.MAYBE) {
                    result.diagnostics.add("inconclusive service provider: " + Intern.str(provider));
                } else if (status == Check.NO) {
                    result.issue(file.source(), provider, SymbolRef.ofClass(file.iface()), runtime.containsClass(provider)
                            ? Kind.SERVICE_PROVIDER_NOT_INSTANTIABLE : Kind.SERVICE_PROVIDER_NOT_FOUND, Intern.NONE);
                }
            }
        }
        result.diagnostics.addAll(warnings);
    }

    /** Platform delegation matches the scope order used for static resolution. */
    private static URLClassLoader runtimeLoader(List<String> paths) throws IOException {
        URL[] urls = new URL[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            urls[i] = Path.of(paths.get(i)).toUri().toURL();
        }
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    /** Reflection forces verification and descriptor loading, without running class initializers. */
    private static void verifyClasses(URLClassLoader loader, ApiIndex classes, ApiIndex platform, Scope runtime, Result result) {
        SymMap issueHeads = new SymMap();
        int[] nextIssue = new int[result.violations.size()];
        for (int i = 0; i < nextIssue.length; i++) {
            nextIssue[i] = issueHeads.put(result.violations.get(i).sourceClass(), i);
        }
        for (int e = 0; e < classes.classCount(); e++) {
            int name = classes.nameOf(e);
            if (platform.containsClass(name)) {
                continue;
            }
            try {
                Class<?> type = Class.forName(Intern.str(name).replace('/', '.'), false, loader);
                int expectedHost = classes.nestHostOf(e);
                if (expectedHost != Intern.NONE && !type.getNestHost().getName().replace('.', '/').equals(Intern.str(expectedHost))) {
                    result.diagnostics.add("invalid nest membership for " + Intern.str(name));
                }
                type.getDeclaredMethods();
                type.getDeclaredConstructors();
                type.getDeclaredFields();
            } catch (VerifyError | ClassFormatError e1) {
                result.diagnostics.add("JVM verification failed for " + Intern.str(name) + ": " + e1);
            } catch (LinkageError | ClassNotFoundException e1) {
                // Structural/reference checks identify the actual requirement; a dependent
                // class failing to load must not create a second, message-derived identity.
                if (!diagnosedLoadingFailure(name, runtime, e1, result, issueHeads, nextIssue)) {
                    result.diagnostics.add("unclassified JVM linkage failure for " + Intern.str(name) + ": " + e1);
                }
            }
        }
    }

    private static boolean diagnosedLoadingFailure(int name, Scope runtime, Throwable failure, Result result, SymMap issueHeads, int[] nextIssue) {
        if (!(failure instanceof NoClassDefFoundError) && !(failure instanceof IncompatibleClassChangeError)) {
            return false;
        }
        IntQueue queue = new IntQueue();
        IntSet seen = new IntSet();
        queue.add(name);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            for (int i = issueHeads.get(current); i != SymMap.ABSENT; i = nextIssue[i]) {
                Issue issue = result.violations.get(i);
                boolean diagnosed = failure instanceof NoClassDefFoundError
                        ? issue.kind() == Kind.CLASS_NOT_FOUND && Intern.str(issue.reference().owner()).equals(failure.getMessage())
                        : failure instanceof IllegalAccessError ? issue.kind() == Kind.CLASS_NOT_ACCESSIBLE
                        : failure.getClass() == IncompatibleClassChangeError.class && switch (issue.kind()) {
                            case EXTENDS_FINAL_CLASS, OVERRIDES_FINAL_METHOD, NOT_PERMITTED_SUBCLASS, EXPECTED_CLASS, EXPECTED_INTERFACE -> true;
                            default -> false;
                        };
                if (diagnosed) {
                    return true;
                }
            }
            ApiIndex layer = runtime.layerOf(current);
            if (layer != null) {
                int entry = layer.entry(current);
                if (layer.superOf(entry) != Intern.NONE) {
                    queue.add(layer.superOf(entry));
                }
                for (int i = 0; i < layer.interfaceCount(entry); i++) {
                    queue.add(layer.interfaceAt(entry, i));
                }
            }
        }
        return false;
    }

    private static Kind kind(Reason reason) {
        return switch (reason) {
            case CLASS_REMOVED -> Kind.CLASS_NOT_FOUND;
            case METHOD_REMOVED -> Kind.METHOD_NOT_FOUND;
            case FIELD_REMOVED -> Kind.FIELD_NOT_FOUND;
            case CLASS_ACCESS_NARROWED -> Kind.CLASS_NOT_ACCESSIBLE;
            case METHOD_ACCESS_NARROWED -> Kind.METHOD_NOT_ACCESSIBLE;
            case FIELD_ACCESS_NARROWED -> Kind.FIELD_NOT_ACCESSIBLE;
            case CLASS_BECAME_ABSTRACT -> Kind.CLASS_NOT_INSTANTIABLE;
            case CLASS_BECAME_FINAL, EXTENDS_FINAL_CLASS -> Kind.EXTENDS_FINAL_CLASS;
            case CLASS_BECAME_SEALED -> Kind.NOT_PERMITTED_SUBCLASS;
            case CLASS_BECAME_INTERFACE -> Kind.EXPECTED_CLASS;
            case INTERFACE_BECAME_CLASS -> Kind.EXPECTED_INTERFACE;
            case METHOD_BECAME_ABSTRACT -> Kind.ABSTRACT_METHOD_UNIMPLEMENTED;
            case CONFLICTING_DEFAULT_METHODS -> Kind.CONFLICTING_DEFAULT_METHODS;
            case METHOD_BECAME_FINAL -> Kind.OVERRIDES_FINAL_METHOD;
            case METHOD_BECAME_STATIC -> Kind.EXPECTED_INSTANCE_METHOD;
            case METHOD_BECAME_INSTANCE -> Kind.EXPECTED_STATIC_METHOD;
            case FIELD_BECAME_STATIC -> Kind.EXPECTED_INSTANCE_FIELD;
            case FIELD_BECAME_INSTANCE -> Kind.EXPECTED_STATIC_FIELD;
            case FIELD_BECAME_FINAL -> Kind.WRITE_TO_FINAL_FIELD;
            case SERVICE_PROVIDER_REMOVED -> Kind.SERVICE_PROVIDER_NOT_FOUND;
            case SERVICE_PROVIDER_NOT_INSTANTIABLE -> Kind.SERVICE_PROVIDER_NOT_INSTANTIABLE;
        };
    }

    static String json(Result result) {
        StringBuilder out = new StringBuilder();
        Json.Writer json = new Json.Writer(out, true);
        json.beginObject().key("schema_version").value(SCHEMA_VERSION).key("complete").value(result.exitCode() != EXIT_INCOMPLETE)
                .key("jdk_release").value(Runtime.version().feature()).key("scanned_classes").value(result.scannedClasses)
                .key("unknown_refs").value(result.unknownRefs).key("violations").beginArray();
        for (Issue issue : result.violations) {
            json.beginObject().key("id").value(issue.id()).key("kind").value(issue.kind().text)
                    .key("source").sym(issue.source()).key("source_class").sym(issue.sourceClass()).key("reference");
            Verdicts.writeReference(json, issue.reference());
            json.key("provider").value(issue.provider() == Intern.NONE ? null : Intern.str(issue.provider())).endObject();
        }
        json.endArray().key("diagnostics").beginArray();
        for (String diagnostic : result.diagnostics) {
            json.value(diagnostic);
        }
        json.endArray().endObject();
        return out.toString();
    }
}
