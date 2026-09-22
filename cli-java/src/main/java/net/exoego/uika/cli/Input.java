package net.exoego.uika.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Streams the classes of a JAR or class directory.
 *
 * <p>Inflate and parse are fused: a worker inflates one entry into its own scratch buffer
 * and hands it straight to the sink, so inflated bytes are never held beyond the class being
 * looked at. Entries are grouped into spans read by one positioned read each.
 */
final class Input {
    /** Entries per span at most. */
    static final int BATCH = 512;

    /**
     * One positioned read per span. 2 MiB rather than more: a span is held for as long as its
     * entries are being inflated, one per active path, so this bounds the read memory, and the
     * read count it costs is a rounding error next to inflate.
     */
    private static final long SPAN_MAX = 2L * 1024 * 1024;
    private static final long GAP_MAX = 1024 * 1024;
    /** Entries handled by one task. Small enough to balance, large enough to amortize the fork. */
    private static final int LEAF = 16;
    private static final byte[] DOT_CLASS = ".class".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MODULE_INFO = "module-info.class".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VERSIONS = "META-INF/versions/".getBytes(StandardCharsets.US_ASCII);

    private static final Set<java.nio.file.OpenOption> NO_FOLLOW_READ =
            Set.of(StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS);

    private Input() {}

    /**
     * Receives classes on worker threads. One leaf accumulator is handed to exactly one
     * thread at a time, and leaves come back in entry order, so output stays deterministic.
     */
    interface Sink<L> {
        L newLeaf();

        /**
         * @param entry interned entry name (relative path for a directory) without ".class"
         * @param bytes the class, starting with the class magic and at least partly inflated;
         *     valid only during the call
         */
        void accept(L leaf, Scratch scratch, int source, int entry, ClassSource bytes);
    }

    /** Multi-release variants and module-info are out of scope. */
    static boolean isScannable(byte[] name, int length) {
        return endsWith(name, length, DOT_CLASS)
                && !endsWith(name, length, MODULE_INFO)
                && !startsWith(name, length, VERSIONS);
    }

    static boolean isScannable(String name) {
        return name.endsWith(".class") && !name.endsWith("module-info.class") && !name.startsWith("META-INF/versions/");
    }

    private static boolean endsWith(byte[] name, int length, byte[] suffix) {
        if (length < suffix.length) {
            return false;
        }
        return Arrays.equals(name, length - suffix.length, length, suffix, 0, suffix.length);
    }

    private static boolean startsWith(byte[] name, int length, byte[] prefix) {
        if (length < prefix.length) {
            return false;
        }
        return Arrays.equals(name, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** The symbol a scannable entry name is handed to a sink as: the name without ".class". */
    static int entrySym(String entryName) {
        return Intern.intern(entryName.substring(0, entryName.length() - 6));
    }

    static boolean hasClassMagic(byte[] bytes, int length) {
        return length >= 4
                && bytes[0] == (byte) 0xCA
                && bytes[1] == (byte) 0xFE
                && bytes[2] == (byte) 0xBA
                && bytes[3] == (byte) 0xBE;
    }

    /** What reading a path's directory ahead of time found. */
    static final class Prepared {
        /** Scannable entries for the fast path; null for a directory or a JAR that needs the fallback. */
        final Jar.Entries entries;
        final boolean directory;
        /** META-INF/services provider files, when asked for. */
        List<Reach.ServiceFile> services = List.of();
        /** Why the provider files could not be read, or null. */
        String serviceWarning;

        Prepared(Jar.Entries entries, boolean directory) {
            this.entries = entries;
            this.directory = directory;
        }
    }

    /**
     * Reads a JAR's central directory without inflating anything, so first-wins dedup can
     * run before the scan. Never throws: an unreadable path surfaces when it is scanned.
     */
    static Prepared prepare(String path) {
        return prepare(path, false);
    }

    /**
     * @param services also read the META-INF/services provider files. For a JAR they come out
     *     of the directory that is being streamed anyway, which saves reopening every archive.
     */
    @SuppressWarnings("unchecked")
    static Prepared prepare(String path, boolean services) {
        Path file = Path.of(path);
        Prepared prepared = null;
        if (Files.isDirectory(file)) {
            prepared = new Prepared(null, true);
        } else {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                Scratch scratch = Scratch.current();
                prepared = new Prepared(Jar.readEntries(channel, scratch), false);
                if (services && prepared.entries != null) {
                    prepared.services = Reach.servicesOf(channel, prepared.entries, Intern.intern(path), scratch);
                    return prepared;
                }
            } catch (IOException e) {
                prepared = new Prepared(null, false);
            }
        }
        if (services) {
            // A directory, or a JAR only the fallback reader understands.
            Object[] result = Reach.servicesOrWarning(path);
            prepared.services = (List<Reach.ServiceFile>) result[0];
            prepared.serviceWarning = (String) result[1];
        }
        return prepared;
    }

    /** Streams every class under {@code path} into {@code sink}, appending the filled leaves in entry order. */
    static <L> void forEachClass(String path, Sink<L> sink, List<L> out) {
        forEachClass(path, null, sink, out);
    }

    /** @param prepared the path's directory read ahead (and possibly deduplicated), or null */
    static <L> void forEachClass(String path, Prepared prepared, Sink<L> sink, List<L> out) {
        Path file = Path.of(path);
        int source = Intern.intern(path);
        if (Files.isDirectory(file)) {
            scanDirectory(path, sink, out);
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            Jar.Entries entries = prepared != null ? prepared.entries : Jar.readEntries(channel, Scratch.current());
            if (entries != null) {
                try {
                    scanJarFast(channel, entries, source, sink, out);
                } finally {
                    entries.release();
                }
                return;
            }
        } catch (IOException e) {
            throw new UikaException("cannot open " + path, e);
        }
        scanJarFallback(path, source, sink, out);
    }

    // ---- fast path ----

    /**
     * Spans are independent, so they are tasks of their own rather than a loop with a barrier
     * after each. A classpath is usually dominated by a few large JARs, and with one span at a
     * time such a JAR was the critical path of the whole scan: its workers idled at every
     * barrier while the next span was still being read.
     */
    private static <L> void scanJarFast(FileChannel channel, Jar.Entries entries, int source, Sink<L> sink, List<L> out) {
        int n = entries.count;
        IntBuf bounds = new IntBuf(8);
        int i = 0;
        while (i < n) {
            long start = entries.offset(i);
            int j = i + 1;
            while (j < n
                    && j - i < BATCH
                    && entries.end(j) - start <= SPAN_MAX
                    && entries.offset(j) - entries.end(j - 1) <= GAP_MAX) {
                j++;
            }
            bounds.add(i);
            bounds.add(j);
            i = j;
        }
        int spans = bounds.n / 2;
        if (spans == 1) {
            readSpan(channel, entries, 0, n, source, sink, out);
            return;
        }
        List<List<L>> perSpan = new ArrayList<>(spans);
        List<RecursiveAction> tasks = new ArrayList<>(spans);
        for (int k = 0; k < spans; k++) {
            List<L> leaves = new ArrayList<>();
            perSpan.add(leaves);
            int from = bounds.a[k * 2];
            int to = bounds.a[k * 2 + 1];
            tasks.add(new RecursiveAction() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void compute() {
                    readSpan(channel, entries, from, to, source, sink, leaves);
                }
            });
        }
        ForkJoinTask.invokeAll(tasks);
        for (List<L> leaves : perSpan) {
            out.addAll(leaves);
        }
    }

    private static <L> void readSpan(FileChannel channel, Jar.Entries entries, int from, int to, int source, Sink<L> sink, List<L> out) {
        long start = entries.offset(from);
        long end = start;
        for (int k = from; k < to; k++) {
            end = Math.max(end, entries.end(k));
        }
        long spanLength = end - start;
        if (spanLength > Integer.MAX_VALUE - 64) {
            throw new UikaException(
                    "cannot read " + Intern.str(source) + ": the entry at offset " + start + " is larger than 2 GB");
        }
        // The decoder refills its bit buffer a word at a time, so it may read (never consume)
        // a few bytes past the last entry.
        ByteBuffer span = BufPool.acquire((int) spanLength + Inflate.SLACK);
        try {
            if (!Jar.readFully(channel, span, start, (int) spanLength)) {
                throw new UikaException(
                        "cannot read " + Intern.str(source) + ": the entries from offset " + start + " could not be read in full");
            }
            span.limit((int) spanLength + Inflate.SLACK);
            runLeaves(from, to, sink, out, (leaf, a, b) -> decodeEntries(entries, span, start, a, b, source, sink, leaf));
        } finally {
            BufPool.release(span);
        }
    }

    private interface RangeBody<L> {
        void run(L leaf, int from, int to);
    }

    /** Splits [from, to) into leaves, runs them in parallel, and appends them in order. */
    private static <L> void runLeaves(int from, int to, Sink<L> sink, List<L> out, RangeBody<L> body) {
        int count = to - from;
        if (count <= LEAF) {
            L leaf = sink.newLeaf();
            body.run(leaf, from, to);
            out.add(leaf);
            return;
        }
        int leaves = (count + LEAF - 1) / LEAF;
        List<L> results = new ArrayList<>(leaves);
        List<RecursiveAction> tasks = new ArrayList<>(leaves);
        for (int k = 0; k < leaves; k++) {
            L leaf = sink.newLeaf();
            results.add(leaf);
            int leafFrom = from + k * LEAF;
            int leafTo = Math.min(to, leafFrom + LEAF);
            tasks.add(new RecursiveAction() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void compute() {
                    body.run(leaf, leafFrom, leafTo);
                }
            });
        }
        ForkJoinTask.invokeAll(tasks);
        out.addAll(results);
    }

    private static <L> void decodeEntries(
            Jar.Entries entries, ByteBuffer span, long spanStart, int from, int to, int source, Sink<L> sink, L leaf) {
        Scratch scratch = Scratch.current();
        // Only absolute reads from here on, so every leaf shares the one buffer.
        int spanLength = span.limit() - Inflate.SLACK;
        for (int i = from; i < to; i++) {
            String problem;
            try {
                problem = decodeEntry(entries, i, span, spanLength, spanStart, scratch, source, sink, leaf);
            } catch (ClassSource.DeflateError e) {
                problem = "deflate error";
            }
            if (problem != null) {
                Out.err.println("warning: " + Intern.str(source) + "!" + Intern.str(entries.name[i]) + ".class: " + problem);
            }
        }
    }

    /** Returns the problem text, or null when the entry was handled (or skipped as not a class). */
    private static <L> String decodeEntry(
            Jar.Entries entries, int i, ByteBuffer view, int spanLength, long spanStart, Scratch scratch, int source, Sink<L> sink, L leaf) {
        long base = entries.offset(i) - spanStart;
        if (base < 0 || base + 30 > spanLength) {
            return "local header runs into the next entry or the central directory";
        }
        int at = (int) base;
        if (view.getInt(at) != Jar.LOCAL_SIGNATURE) {
            return "bad local header signature";
        }
        // Lengths are re-read: the local header may differ from the central directory.
        int nameLength = view.getShort(at + 26) & 0xffff;
        int extraLength = view.getShort(at + 28) & 0xffff;
        long dataStart = base + 30 + nameLength + extraLength;
        long compressed = entries.compressed(i);
        if (dataStart + compressed > spanLength) {
            return "entry data runs into the next entry or the central directory";
        }
        ClassSource cs = scratch.classSource;
        if (entries.stored[i]) {
            int length = (int) compressed;
            view.get((int) dataStart, cs.reserve(length), 0, length);
            cs.ofBytes(cs.bytes, length);
        } else {
            cs.ofDeflate(view, (int) dataStart, (int) compressed, entries.inflated[i] & 0xffffffffL);
            // Most of a class file is its constant pool, so the first slice aims past the
            // typical header; the sink asks for more when the header runs longer.
            cs.ensure(Math.max(1024, (int) (cs.expected * 3L / 4)));
        }
        if (hasClassMagic(cs.bytes, cs.available)) {
            sink.accept(leaf, scratch, source, entries.name[i], cs);
        }
        return null;
    }

    /** The scratch class buffer, grown to {@code size} with its contents kept. */
    static byte[] classBytes(Scratch scratch, int size) {
        if (scratch.classBytes.length < size) {
            scratch.classBytes = Arrays.copyOf(scratch.classBytes, Math.max(size, scratch.classBytes.length * 2));
        }
        return scratch.classBytes;
    }

    // ---- fallback: zip64, uncommon compression methods, non-UTF-8 names ----

    private static <L> void scanJarFallback(String path, int source, Sink<L> sink, List<L> out) {
        Scratch scratch = Scratch.current();
        try (ZipFile zip = openZip(path)) {
            L leaf = sink.newLeaf();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!isScannable(entry.getName())) {
                    continue;
                }
                int length = readEntry(zip, entry, scratch);
                if (!hasClassMagic(scratch.classBytes, length)) {
                    continue;
                }
                scratch.classSource.ofBytes(scratch.classBytes, length);
                sink.accept(leaf, scratch, source, entrySym(entry.getName()), scratch.classSource);
            }
            out.add(leaf);
        } catch (IOException e) {
            throw new UikaException(path, e);
        }
    }

    static ZipFile openZip(String path) {
        try {
            return new ZipFile(path);
        } catch (ZipException e) {
            // Names that are not UTF-8 fail the default open; every byte maps in Latin-1.
            try {
                return new ZipFile(new java.io.File(path), StandardCharsets.ISO_8859_1);
            } catch (IOException again) {
                throw new UikaException("not a zip/jar: " + path, e);
            }
        } catch (IOException e) {
            throw new UikaException("cannot open " + path, e);
        }
    }

    /** Reads one entry into the scratch class buffer and returns its length. */
    static int readEntry(ZipFile zip, ZipEntry entry, Scratch scratch) throws IOException {
        long size = entry.getSize();
        byte[] buf = classBytes(scratch, (int) Math.max(1024, Math.min(size, 64L * 1024 * 1024)));
        int n = 0;
        try (InputStream in = zip.getInputStream(entry)) {
            while (true) {
                if (n == buf.length) {
                    buf = scratch.classBytes = Arrays.copyOf(buf, buf.length * 2);
                }
                int read = in.read(buf, n, buf.length - n);
                if (read < 0) {
                    return n;
                }
                n += read;
            }
        }
    }

    // ---- directories ----

    /**
     * Scans class directories through a fixed number of lanes.
     *
     * <p>A lane walks a directory or reads one batch of its files, start to finish, on one
     * thread. The lane count bounds how many loose files are open at once, which matters
     * because opening small files from many threads collapses under kernel lock contention on
     * macOS: 124K files took 3.2s and 26s of system time on 12 threads, against 1.7s and 4.5s
     * on 4. Other systems scale, so there every worker may hold a lane.
     *
     * <p>A lane is a task of the same parallel region as the JARs, forked when an item is
     * queued and fewer than the lane count are running, and it ends when the queue is empty.
     * So a worker never waits on a walk: it is a lane only while there is a batch to read,
     * and takes other work otherwise. One task joins the whole scan through the item count.
     *
     * <p>Each directory's listing is sorted, because the file system returns entries in its
     * own order and first-wins among same-named classes under one root must be reproducible.
     * A name ending in ".class" is opened without asking the file system what it is: that
     * question is a system call per file, and those are what the scan is bound by. Symbolic
     * links are not followed, neither into directories nor to class files.
     */
    static final class DirectoryScan<L> {
        private final Sink<L> sink;
        private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
        /** Lanes draining the queue right now, never more than {@link #lanes}. */
        private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        /** Queued plus running items. The last one to end completes {@link #completion}. */
        private final java.util.concurrent.atomic.AtomicInteger outstanding = new java.util.concurrent.atomic.AtomicInteger();
        /** Never run: completed by the last item, or exceptionally by the first failing one. */
        private final RecursiveAction completion = new RecursiveAction() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void compute() {
                // Completed by the items, never by running it.
            }
        };
        /** Set by the region's task. Lanes fork only inside the pool, since a fork elsewhere lands in the common pool. */
        private volatile boolean started;
        private final List<Root<L>> roots = new ArrayList<>();
        private final int lanes;

        private static final class Root<L> {
            final List<L> out;
            /** Filled leaves by batch sequence, so they come back in walk order. */
            final java.util.TreeMap<Integer, L> leaves = new java.util.TreeMap<>();
            int nextSequence;

            Root(List<L> out) {
                this.out = out;
            }
        }

        DirectoryScan(Sink<L> sink) {
            this.sink = sink;
            this.lanes = fileLanes();
        }

        boolean isEmpty() {
            return roots.isEmpty();
        }

        /** Registers a directory. Its leaves are appended to {@code out} by {@link #finish}. */
        void add(String path, List<L> out) {
            Root<L> root = new Root<>(out);
            roots.add(root);
            int source = Intern.intern(path);
            Path dir = Path.of(path);
            submit(() -> walkRoot(root, dir, source));
        }

        /** The task that starts the lanes and ends when every queued item has run. Runs in the region. */
        RecursiveAction laneTask() {
            return new RecursiveAction() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void compute() {
                    started = true;
                    spawnLanes();
                    completion.join();
                }
            };
        }

        /** Hands every directory's leaves over, in walk order. Call after {@link #laneTask} has joined. */
        void finish() {
            for (Root<L> root : roots) {
                root.out.addAll(root.leaves.values());
            }
        }

        private void submit(Runnable item) {
            outstanding.incrementAndGet();
            queued.incrementAndGet();
            queue.add(item);
            if (started) {
                spawnLanes();
            }
        }

        /** Forks a lane per queued item while fewer than {@link #lanes} run. Never waits. */
        private void spawnLanes() {
            while (!queue.isEmpty() && !completion.isCompletedAbnormally()) {
                int running = active.get();
                if (running >= lanes) {
                    return;
                }
                if (active.compareAndSet(running, running + 1)) {
                    new Lane().fork();
                }
            }
        }

        private final class Lane extends RecursiveAction {
            private static final long serialVersionUID = 1L;

            @Override
            protected void compute() {
                try {
                    Runnable item;
                    while (!completion.isCompletedAbnormally() && (item = queue.poll()) != null) {
                        queued.decrementAndGet();
                        try {
                            item.run();
                        } catch (Throwable t) {
                            // Surfaces through the region task's join; the other lanes stop.
                            completion.completeExceptionally(t);
                            return;
                        }
                        if (outstanding.decrementAndGet() == 0) {
                            completion.quietlyComplete();
                        }
                    }
                } finally {
                    active.decrementAndGet();
                }
                // An item queued between the last poll and that decrement saw a full lane
                // count and forked nothing, so it is this lane's to hand on.
                spawnLanes();
            }
        }

        private void walkRoot(Root<L> root, Path dir, int source) {
            List<Path> files = new ArrayList<>(BATCH);
            List<String> names = new ArrayList<>(BATCH);
            try {
                walk(root, dir, "", files, names, source);
            } catch (IOException e) {
                throw new UikaException(UikaException.describe(e));
            }
            flush(root, files, names, source);
        }

        private void walk(Root<L> root, Path dir, String prefix, List<Path> files, List<String> names, int source) throws IOException {
            List<String> entries = new ArrayList<>();
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    entries.add(entry.getFileName().toString());
                }
            }
            Collections.sort(entries);
            List<String> others = new ArrayList<>();
            for (String entry : entries) {
                if (!entry.endsWith(".class")) {
                    others.add(entry);
                    continue;
                }
                String name = prefix + entry;
                if (isScannable(name)) {
                    files.add(dir.resolve(entry));
                    names.add(name);
                    if (files.size() >= BATCH) {
                        flush(root, files, names, source);
                    }
                }
            }
            for (String entry : others) {
                Path child = dir.resolve(entry);
                if (Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    walk(root, child, prefix + entry + "/", files, names, source);
                }
            }
        }

        private void flush(Root<L> root, List<Path> files, List<String> names, int source) {
            if (files.isEmpty()) {
                return;
            }
            Path[] batchFiles = files.toArray(new Path[0]);
            String[] batchNames = names.toArray(new String[0]);
            files.clear();
            names.clear();
            int sequence = root.nextSequence++;
            Runnable batch = () -> readBatch(root, sequence, batchFiles, batchNames, source);
            // A walk outruns the readers easily. Past a few batches per lane it reads its own,
            // so a module with tens of thousands of classes never has its whole file list queued.
            if (queued.get() > lanes * 4) {
                batch.run();
            } else {
                submit(batch);
            }
        }

        private void readBatch(Root<L> root, int sequence, Path[] files, String[] names, int source) {
            Scratch scratch = Scratch.current();
            L leaf = sink.newLeaf();
            for (int k = 0; k < files.length; k++) {
                int length;
                try {
                    length = readFile(files[k], scratch);
                } catch (IOException e) {
                    if (Files.isSymbolicLink(files[k]) || Files.isDirectory(files[k])) {
                        continue;
                    }
                    throw new UikaException(UikaException.describe(e));
                }
                if (hasClassMagic(scratch.classBytes, length)) {
                    String name = names[k];
                    scratch.classSource.ofBytes(scratch.classBytes, length);
                    sink.accept(leaf, scratch, source, Intern.intern(name.substring(0, name.length() - 6)), scratch.classSource);
                }
            }
            synchronized (root) {
                root.leaves.put(sequence, leaf);
            }
        }
    }

    /** Lanes for loose class files: 4 on macOS, one per worker elsewhere. {@code UIKA_FILE_LANES} overrides. */
    static int fileLanes() {
        String env = Env.get("UIKA_FILE_LANES");
        if (env != null) {
            try {
                int n = Integer.parseInt(env.trim());
                if (n > 0) {
                    return n;
                }
            } catch (NumberFormatException e) {
                // fall through to the default
            }
        }
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        return mac ? Math.min(4, Scratch.threads()) : Scratch.threads();
    }

    private static <L> void scanDirectory(String path, Sink<L> sink, List<L> out) {
        DirectoryScan<L> scan = new DirectoryScan<>(sink);
        scan.add(path, out);
        // Through the pool, so the lanes fork into it whether or not the caller is a worker.
        Scratch.pool().invoke(scan.laneTask());
        scan.finish();
    }

    static byte[] relativeName(Path root, Path file) {
        String relative = root.relativize(file).toString();
        return relative.replace('\\', '/').getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a whole file into the scratch class buffer and returns its length. No size query,
     * which saves a system call per file. Reads go through the thread's own direct buffer,
     * because a read into a heap array is staged through a JDK-internal one sized to the
     * largest request.
     */
    static int readFile(Path file, Scratch scratch) throws IOException {
        try (FileChannel channel = FileChannel.open(file, NO_FOLLOW_READ)) {
            return readChannel(channel, scratch, file);
        }
    }

    /**
     * Reads until the channel reports its end. A read that comes back short of the buffer
     * is not that end: FUSE and network file systems answer short before it, and a regular
     * file's end costs one more read that returns nothing.
     */
    static int readChannel(ReadableByteChannel channel, Scratch scratch, Path file) throws IOException {
        ByteBuffer staging = scratch.fileBuffer;
        int n = 0;
        while (true) {
            staging.clear();
            int read = channel.read(staging);
            if (read <= 0) {
                return n;
            }
            byte[] buf = classBytes(scratch, n + read);
            if (buf.length < n + read) {
                throw new IOException("file too large: " + file);
            }
            staging.get(0, buf, n, read);
            n += read;
        }
    }

    // ---- pass 2: read only the named entries ----

    /** A class pass 2 re-reads: its name symbol and the entry (or relative file) holding it. */
    record Wanted(int name, String entry) {}

    interface EntryConsumer {
        void accept(int name, byte[] bytes, int length);
    }

    /** Individual read failures come back as warnings; only entries read in full reach the consumer. */
    static List<String> fetchEntries(String path, List<Wanted> entries, EntryConsumer consumer) {
        List<String> warnings = new ArrayList<>();
        Scratch scratch = Scratch.current();
        Path root = Path.of(path);
        if (Files.isDirectory(root)) {
            for (Wanted wanted : entries) {
                try {
                    int length = readFile(root.resolve(wanted.entry()), scratch);
                    consumer.accept(wanted.name(), scratch.classBytes, length);
                } catch (IOException | RuntimeException e) {
                    warnings.add(path + "!" + wanted.entry() + ": " + UikaException.describe(e));
                }
            }
            return warnings;
        }
        if (!Files.exists(root)) {
            throw new UikaException("cannot open " + path, new java.nio.file.NoSuchFileException(path));
        }
        try (ZipFile zip = openZip(path)) {
            for (Wanted wanted : entries) {
                ZipEntry entry = zip.getEntry(wanted.entry());
                if (entry == null) {
                    warnings.add(path + "!" + wanted.entry() + ": not found in the archive");
                    continue;
                }
                try {
                    int length = readEntry(zip, entry, scratch);
                    consumer.accept(wanted.name(), scratch.classBytes, length);
                } catch (IOException e) {
                    warnings.add(path + "!" + wanted.entry() + ": " + UikaException.describe(e));
                }
            }
        } catch (IOException e) {
            throw new UikaException(path, e);
        }
        return warnings;
    }

    /**
     * Internal class names (no ".class" suffix) in a JAR or directory, without inflating
     * anything. Best effort: an unreadable input lists nothing.
     */
    static List<String> classEntryNames(String path) {
        List<String> names = new ArrayList<>();
        Path root = Path.of(path);
        if (Files.isDirectory(root)) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) {
                            String name = new String(relativeName(root, file), StandardCharsets.UTF_8);
                            if (isScannable(name)) {
                                names.add(name.substring(0, name.length() - 6));
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // best effort
            }
            Collections.sort(names);
            return names;
        }
        try (ZipFile zip = openZip(path)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (isScannable(name)) {
                    names.add(name.substring(0, name.length() - 6));
                }
            }
        } catch (IOException | UikaException e) {
            // best effort
        }
        return names;
    }

    /** Runs {@code body} on the shared pool, or inline when already on it. */
    static void onPool(Runnable body) {
        if (ForkJoinTask.inForkJoinPool() && ForkJoinTask.getPool() == Scratch.pool()) {
            body.run();
            return;
        }
        Scratch.pool().invoke(new RecursiveAction() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void compute() {
                body.run();
            }
        });
    }

    static <T> List<T> concat(List<List<T>> lists) {
        List<T> all = new ArrayList<>();
        for (List<T> list : lists) {
            all.addAll(list);
        }
        return Collections.unmodifiableList(all);
    }
}
