package net.exoego.uika.cli;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Class-load reachability over the scanned classpath.
 *
 * <p>An over-approximation: application classes are roots, and edges are constant-pool class
 * references, hierarchy links, class-name-shaped strings (Class.forName patterns) and
 * META-INF/services providers. Reflection driven purely by external configuration stays
 * invisible, so an unreachable verdict is a prioritization hint, never grounds to drop a
 * violation.
 */
final class Reach {
    /**
     * One META-INF/services provider file.
     *
     * @param source origin (JAR path or directory), interned like a class's source
     */
    record ServiceFile(int iface, int[] impls, int source) {}

    /** @param appSources origin symbols of the application scan targets */
    record Inputs(IntSet appSources, List<ServiceFile> services) {}

    /** Marks indexed by symbol id; symbols interned later are trivially unmarked. */
    record Result(BitSet marks, boolean appRootMatched) {
        boolean isReachable(int sym) {
            return marks.get(sym);
        }
    }

    private static final String SERVICES_PREFIX = "META-INF/services/";

    private Reach() {}

    /** Read failures per target come back as warnings; reachability just sees fewer dynamic edges. */
    static List<ServiceFile> collectServices(List<String> paths, List<String> warnings) {
        @SuppressWarnings("unchecked")
        List<ServiceFile>[] perPath = new List[paths.size()];
        String[] failures = new String[paths.size()];
        List<RecursiveAction> tasks = new ArrayList<>(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            int index = i;
            tasks.add(new RecursiveAction() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void compute() {
                    String path = paths.get(index);
                    try {
                        perPath[index] = servicesOf(path);
                    } catch (UikaException e) {
                        perPath[index] = List.of();
                        failures[index] = path + ": " + outermost(e);
                    } catch (IOException e) {
                        perPath[index] = List.of();
                        failures[index] = path + ": " + UikaException.describe(e);
                    }
                }
            });
        }
        Input.onPool(() -> ForkJoinTask.invokeAll(tasks));
        List<ServiceFile> services = new ArrayList<>();
        for (int i = 0; i < perPath.length; i++) {
            services.addAll(perPath[i]);
            if (failures[i] != null) {
                warnings.add(failures[i]);
            }
        }
        return services;
    }

    /** Rust formats this warning with `{e}`, which prints the outermost context and not the chain. */
    private static String outermost(UikaException e) {
        String message = e.getMessage();
        if (e.getCause() != null) {
            String chained = ": " + UikaException.describe(e.getCause());
            if (message.endsWith(chained)) {
                return message.substring(0, message.length() - chained.length());
            }
        }
        return message;
    }

    /**
     * Provider files of a JAR whose central directory was already streamed, read through the
     * open channel. One unreadable provider file is skipped, not the whole JAR's providers.
     */
    static List<ServiceFile> servicesOf(java.nio.channels.FileChannel channel, Jar.Entries entries, int source, Scratch scratch) {
        List<ServiceFile> out = new ArrayList<>();
        if (entries.services == null) {
            return out;
        }
        // A name listed twice is read once, at its first position, from its last record.
        Map<String, Jar.ServiceEntry> byName = new java.util.LinkedHashMap<>();
        for (Jar.ServiceEntry entry : entries.services) {
            byName.put(entry.service(), entry);
        }
        for (Jar.ServiceEntry entry : byName.values()) {
            if (!isServiceName(entry.service()) || (entry.method() != 0 && entry.method() != 8)) {
                continue;
            }
            if (entry.compressed() > 64L * 1024 * 1024) {
                continue;
            }
            java.nio.ByteBuffer header = BufPool.acquire(30);
            java.nio.ByteBuffer data = null;
            try {
                if (!Jar.readFully(channel, header, entry.offset(), 30) || header.getInt(0) != Jar.LOCAL_SIGNATURE) {
                    continue;
                }
                long dataStart = entry.offset() + 30 + (header.getShort(26) & 0xffff) + (header.getShort(28) & 0xffff);
                int compressed = (int) entry.compressed();
                data = BufPool.acquire(compressed + Inflate.SLACK);
                if (!Jar.readFully(channel, data, dataStart, compressed)) {
                    continue;
                }
                data.limit(compressed + Inflate.SLACK);
                ClassSource bytes = scratch.classSource;
                if (entry.method() == 0) {
                    data.get(0, bytes.reserve(compressed), 0, compressed);
                    bytes.ofBytes(bytes.bytes, compressed);
                } else {
                    bytes.ofDeflate(data, 0, compressed, entry.inflated());
                    bytes.readAll();
                }
                pushService(out, entry.service(), bytes.bytes, bytes.available, source);
            } catch (ClassSource.DeflateError e) {
                // skipped
            } finally {
                BufPool.release(header);
                if (data != null) {
                    BufPool.release(data);
                }
            }
        }
        return out;
    }

    /**
     * Provider files of one scan target, with the warning text when they could not be read.
     * The second element is null on success.
     */
    static Object[] servicesOrWarning(String path) {
        try {
            return new Object[] {servicesOf(path), null};
        } catch (UikaException e) {
            return new Object[] {List.of(), path + ": " + outermost(e)};
        } catch (IOException e) {
            return new Object[] {List.of(), path + ": " + UikaException.describe(e)};
        }
    }

    private static List<ServiceFile> servicesOf(String path) throws IOException {
        Path file = Path.of(path);
        if (Files.isDirectory(file)) {
            return servicesOfDirectory(file, path);
        }
        int source = Intern.intern(path);
        if (!Files.exists(file)) {
            throw new UikaException("cannot open " + path, new java.nio.file.NoSuchFileException(path));
        }
        List<ServiceFile> out = new ArrayList<>();
        Scratch scratch = Scratch.current();
        try (ZipFile zip = Input.openZip(path)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(SERVICES_PREFIX) || !isServiceName(name.substring(SERVICES_PREFIX.length()))) {
                    continue;
                }
                // One unreadable provider file is skipped, not the whole JAR's providers.
                int length;
                try {
                    length = Input.readEntry(zip, entry, scratch);
                } catch (IOException e) {
                    continue;
                }
                pushService(out, name.substring(SERVICES_PREFIX.length()), scratch.classBytes, length, source);
            }
        }
        return out;
    }

    private static List<ServiceFile> servicesOfDirectory(Path root, String path) throws IOException {
        int source = Intern.intern(path);
        List<ServiceFile> out = new ArrayList<>();
        Path dir = root.resolve("META-INF/services");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                files.add(entry);
            }
        }
        files.sort(null);
        for (Path entry : files) {
            String name = entry.getFileName().toString();
            if (isServiceName(name) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = Files.readAllBytes(entry);
                pushService(out, name, bytes, bytes.length, source);
            }
        }
        return out;
    }

    /** Provider file names are dotted FQNs; stray non-class entries are skipped. */
    private static boolean isServiceName(String name) {
        return !name.isEmpty() && name.indexOf('/') < 0;
    }

    static void pushService(List<ServiceFile> out, String dottedIface, byte[] bytes, int length, int source) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (CharacterCodingException e) {
            return;
        }
        // Both terminators, not lines(): ServiceLoader reads these files with
        // BufferedReader.readLine, which also accepts a lone '\r'.
        IntBuf impls = new IntBuf(4);
        for (String line : text.split("[\r\n]", -1)) {
            int hash = line.indexOf('#');
            String provider = trim(hash < 0 ? line : line.substring(0, hash));
            if (!provider.isEmpty()) {
                impls.add(Intern.intern(provider.replace('.', '/')));
            }
        }
        if (!impls.isEmpty()) {
            out.add(new ServiceFile(Intern.intern(dottedIface.replace('.', '/')), java.util.Arrays.copyOf(impls.a, impls.n), source));
        }
    }

    /** Unicode White_Space trim, which is what the provider-line parsing was specified against. */
    static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isWhitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    static boolean isWhitespace(char c) {
        return (c >= 0x09 && c <= 0x0D)
                || c == ' '
                || c == 0x85
                || c == 0xA0
                || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028
                || c == 0x2029
                || c == 0x202F
                || c == 0x205F
                || c == 0x3000;
    }

    /** BFS from the application roots. */
    static Result reachableClasses(ClassGraph graph, Inputs inputs) {
        BitSet marks = new BitSet(Intern.tableLen());
        int limit = Intern.tableLen();
        IntQueue queue = new IntQueue();
        boolean appRootMatched = false;
        for (int node = 0; node < graph.size(); node++) {
            if (inputs.appSources().contains(graph.sourceOf(node))) {
                appRootMatched = true;
                queue.add(graph.nameOf(node));
            }
        }
        // A provider whose service interface is outside the scanned scope (a JDK SPI such as
        // java.sql.Driver) is a root: the runtime can instantiate it unobserved.
        Map<Integer, IntBuf> providers = new HashMap<>();
        for (ServiceFile service : inputs.services()) {
            if (graph.contains(service.iface())) {
                IntBuf impls = providers.computeIfAbsent(service.iface(), k -> new IntBuf(4));
                for (int impl : service.impls()) {
                    impls.add(impl);
                }
            } else {
                for (int impl : service.impls()) {
                    queue.add(impl);
                }
            }
        }
        while (!queue.isEmpty()) {
            int sym = queue.poll();
            if (sym >= limit || marks.get(sym)) {
                continue;
            }
            marks.set(sym);
            int node = graph.node(sym);
            if (node >= 0) {
                int superName = graph.superOf(node);
                if (superName != Intern.NONE) {
                    queue.add(superName);
                }
                int interfaces = graph.interfaceCount(node);
                for (int k = 0; k < interfaces; k++) {
                    queue.add(graph.interfaceAt(node, k));
                }
                int refs = graph.refCount(node);
                for (int k = 0; k < refs; k++) {
                    queue.add(graph.refAt(node, k));
                }
            }
            IntBuf impls = providers.get(sym);
            if (impls != null) {
                for (int i = 0; i < impls.n; i++) {
                    queue.add(impls.a[i]);
                }
            }
        }
        return new Result(marks, appRootMatched);
    }
}
