package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceTest {
    /** Rust's {@code str::lines}, then the same per-line entry the file reader uses. */
    private static Evidence.LoadEvidence parse(String text) {
        Map<String, Evidence.LoadRecord> loaded = new HashMap<>();
        Evidence.Parser parser = new Evidence.Parser(loaded);
        String[] lines = text.split("\n", -1);
        int count = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        for (int i = 0; i < count; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            Evidence.parseLine(Evidence.trimEnd(line), parser);
        }
        parser.closeBlock();
        return new Evidence.LoadEvidence(loaded, "test.log");
    }

    private static Violation violation(String sourceClass, String owner, String name, String descriptor) {
        long member = name == null ? MemberKey.NONE : MemberKey.of(name, descriptor);
        SymbolRef reference = new SymbolRef(RefKind.CLASS, Intern.intern(owner), member, null, null, null);
        Violation v = new Violation(
                Intern.intern("consumer.jar"), Intern.intern(sourceClass), reference, Reason.CLASS_REMOVED);
        v.reachable = Boolean.FALSE;
        return v;
    }

    private static Violation violation(String sourceClass, String owner) {
        return violation(sourceClass, owner, null, null);
    }

    /**
     * Every accepted format lands in the same set. Numeric tokens and other -Xlog streams
     * sharing the file do not. A tag-confirmed line accepts a default-package or non-ASCII
     * name (both are legal JVM class names), a bare token still needs two segments.
     */
    @Test
    void parsesUnifiedLoggingClasslistsAndPlainLists() {
        Evidence.LoadEvidence e = parse("""
                [0.062s][info][class,load] java.lang.Object source: shared objects file
                [0.100s][info][gc,start     ] Pause Young (Normal)
                [0.101s][info][class,load  ] com.example.App$Inner source: file:/app.jar
                [0.102s][info][class,load] LoadIt source: file:/
                [0.103s][info][class,load] com.example.日本語テスト source: file:/app.jar
                io/ktor/Thing id: 12
                com.example.Plain
                127.0.0.1 connected
                1.2.3
                warning
                """);
        for (String present : List.of(
                "java/lang/Object",
                "com/example/App$Inner",
                "LoadIt",
                "com/example/日本語テスト",
                "io/ktor/Thing",
                "com/example/Plain")) {
            assertNotNull(e.observed(present), present + " missing");
        }
        assertEquals(6, e.distinctClasses(), "numeric or bare tokens leaked in");
    }

    /**
     * The tags decorator is matched by exact membership. The class,loader,* family shares the
     * "class,load" substring but belongs to other streams, and a decorator set without tags
     * at all (-Xlog:...:file=x:time) still parses via the token path.
     */
    @Test
    void tagMatchingIsExactAndSurvivesMissingTagsDecorator() {
        Evidence.LoadEvidence e = parse("""
                [0.1s][info][class,loader,data] create class loader data 0x0 for instance of jdk.internal.loader.ClassLoaders$AppClassLoader
                [0.2s][info][class,loader,constraints] adding new constraint for name: java/lang/String
                [2026-08-21T12:00:00.000+0900] java.lang.Object source: shared objects file
                """);
        assertNull(
                e.observed("jdk/internal/loader/ClassLoaders$AppClassLoader"),
                "a class,loader,data line leaked through the tag gate");
        assertNotNull(
                e.observed("java/lang/Object"), "a tags-less decorator set must fall through to the token parse");
        assertEquals(1, e.distinctClasses());
    }

    /**
     * A cause stack is reduced to its trigger, frame lines are never read as loaded classes,
     * and the first stack wins.
     */
    @Test
    void capturesCauseStacksAndPicksTheTriggerFrame() {
        Evidence.LoadEvidence e = parse(
                """
                [info][class,load,cause] Java stack when loading org.example.Plugin:
                [info][class,load,cause] \tat java.lang.ClassLoader.loadClass(ClassLoader.java:600)
                [info][class,load,cause] \tat java.lang.Class.forName(Class.java:100)
                [info][class,load,cause] \tat com.example.Registry.discover(Registry.java:42)
                [info][class,load,cause] Java stack when loading org.example.Plugin:
                [info][class,load,cause] \tat com.example.Other.later(Other.java:1)
                """);
        // ClassLoader machinery is skipped. The reflective frame is the answer, and the
        // second stack must not override the first.
        assertEquals(
                "java.lang.Class.forName from com.example.Registry.discover(Registry.java:42)",
                e.observed("org/example/Plugin").trigger());
        assertNull(e.observed("java/lang/ClassLoader"));
    }

    /**
     * The stack shape a real JDK 25 emits, verbatim (module-qualified frames, monitor
     * annotations on synchronized loader frames, a custom loader in the delegation chain).
     * The "- locked" lines must not end the block, the custom loader's loadClass counts as
     * machinery, and the trigger composes the reflective API with its first real caller.
     */
    @Test
    void realJdkStackShapeParsesThroughMonitorsAndCustomLoaders() {
        Evidence.LoadEvidence e = parse(
                """
                [0.295s][info][class,load,cause] Java stack when loading io.ktor.utils.io.jvm.javaio.BlockingAdapter:
                [0.295s][info][class,load,cause] \tat java.lang.ClassLoader.defineClass1(java.base@25.0.3/Native Method)
                [0.295s][info][class,load,cause] \tat java.lang.ClassLoader.defineClass(java.base@25.0.3/ClassLoader.java:962)
                [0.295s][info][class,load,cause] \tat java.security.SecureClassLoader.defineClass(java.base@25.0.3/SecureClassLoader.java:144)
                [0.295s][info][class,load,cause] \tat jdk.internal.loader.BuiltinClassLoader.defineClass(java.base@25.0.3/BuiltinClassLoader.java:776)
                [0.295s][info][class,load,cause] \tat jdk.internal.loader.BuiltinClassLoader.findClassOnClassPathOrNull(java.base@25.0.3/BuiltinClassLoader.java:691)
                [0.295s][info][class,load,cause] \tat jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(java.base@25.0.3/BuiltinClassLoader.java:620)
                [0.295s][info][class,load,cause] \t- locked <0x0000000c4f1f3d40> (a java.lang.Object)
                [0.295s][info][class,load,cause] \tat jdk.internal.loader.BuiltinClassLoader.loadClass(java.base@25.0.3/BuiltinClassLoader.java:578)
                [0.295s][info][class,load,cause] \tat java.lang.ClassLoader.loadClass(java.base@25.0.3/ClassLoader.java:490)
                [0.295s][info][class,load,cause] \tat com.sun.tools.javac.launcher.MemoryClassLoader.loadClass(jdk.compiler@25.0.3/MemoryClassLoader.java:129)
                [0.295s][info][class,load,cause] \t- locked <0x0000000c4f1c8ff8> (a com.sun.tools.javac.launcher.MemoryClassLoader)
                [0.295s][info][class,load,cause] \tat java.lang.ClassLoader.loadClass(java.base@25.0.3/ClassLoader.java:490)
                [0.295s][info][class,load,cause] \tat java.lang.Class.forName0(java.base@25.0.3/Native Method)
                [0.295s][info][class,load,cause] \tat java.lang.Class.forName(java.base@25.0.3/Class.java:547)
                [0.295s][info][class,load,cause] \tat LoadIt.main(LoadIt.java:3)
                [0.295s][info][class,load,cause] \tat java.lang.invoke.LambdaForm$DMH/0x000001c00106c000.invokeStatic(java.base@25.0.3/LambdaForm$DMH)
                """);
        // A monitor annotation ending the stack early would leave a machinery-only prefix
        // and a null trigger, so the composed value pins both rules at once.
        assertEquals(
                "java.lang.Class.forName0 from LoadIt.main(LoadIt.java:3)",
                e.observed("io/ktor/utils/io/jvm/javaio/BlockingAdapter").trigger());
    }

    /**
     * The native variant marks the class loaded without collecting VM frames. The frame lines
     * carry the same class,load,cause,native tags as their header, and their frame-kind
     * letters ("V", "j") must not register as trusted single-segment classes.
     */
    @Test
    void nativeStackMarksLoadedWithoutFrames() {
        Evidence.LoadEvidence e = parse("""
                [info][class,load,cause,native] Native stack when loading com.example.N:
                [info][class,load,cause,native] V  [libjvm.so+0x123abc]
                [info][class,load,cause,native] j  java.lang.Thread.run()V+8
                [info][class,load,cause,native] j  com.example.Caller.run()V+2
                """);
        Evidence.LoadRecord record = e.observed("com/example/N");
        assertNotNull(record);
        assertNull(record.trigger());
        assertEquals(1, e.distinctClasses(), "native frame lines leaked in as classes");
    }

    /**
     * apply is promote-only across evidence sets too. A stack-less observation applied after
     * a stacked one must not clear the established trigger.
     */
    @Test
    void applyNeverClearsAnEstablishedTrigger() {
        Evidence.LoadEvidence stacked = parse("Java stack when loading io.ktor.A:\n\tat com.example.Boot.init(Boot.java:5)\n");
        Evidence.LoadEvidence plain = parse("io.ktor.A\n");
        List<Violation> violations = List.of(violation("io/ktor/A", "x/Gone"));
        Evidence.apply(violations, stacked);
        Evidence.apply(violations, plain);
        assertTrue(violations.get(0).observedLoading);
        assertEquals(
                "com.example.Boot.init(Boot.java:5)",
                violations.get(0).loadTrigger,
                "a later stack-less observation cleared the trigger");
    }

    /**
     * Nothing is retained per frame, so a delegation chain of any depth still yields its
     * trigger (a fixed retention cap used to lose it past 32 machinery frames).
     */
    @Test
    void triggerSurvivesArbitrarilyDeepDelegationChains() {
        StringBuilder text = new StringBuilder("Java stack when loading a.b.C:\n");
        for (int i = 0; i < 100; i++) {
            text.append("\tat jdk.internal.loader.Deep.m").append(i).append("(Deep.java:").append(i).append(")\n");
        }
        text.append("\tat com.example.Boot.init(Boot.java:5)\n");
        Evidence.LoadEvidence e = parse(text.toString());
        assertEquals("com.example.Boot.init(Boot.java:5)", e.observed("a/b/C").trigger());
    }

    /**
     * apply is promote-only. Observed classes gain the flag and trigger, everything else is
     * untouched.
     */
    @Test
    void applyMarksOnlyObservedClasses() {
        Evidence.LoadEvidence e = parse("Java stack when loading io.ktor.A:\n\tat com.example.Boot.init(Boot.java:5)\n");
        List<Violation> violations = List.of(violation("io/ktor/A", "x/Gone"), violation("io/ktor/B", "x/Gone"));
        Evidence.apply(violations, e);
        assertTrue(violations.get(0).observedLoading);
        assertEquals("com.example.Boot.init(Boot.java:5)", violations.get(0).loadTrigger);
        assertFalse(violations.get(1).observedLoading);
        assertNull(violations.get(1).loadTrigger);
    }

    /**
     * Drafts cover only symbols whose every violation is ⚠️ and unobserved, and the file
     * round-trips through the real exclude parser. A member-less rule matches the owner
     * outright in Exclude.filter, so it is withheld whenever any symbol on the same owner is
     * not draftable. Otherwise the drafted class-level rule would waive a reachable member
     * break too.
     */
    @Test
    void draftsOnlyFullyUnprovenUnobservedSymbols(@TempDir Path dir) throws IOException {
        Violation unobservedA = violation("app/DeadA", "lib/Gone", "m", "()V");
        Violation unobservedB = violation("app/DeadB", "lib/Gone", "m", "()V");
        Violation observed = violation("app/Live", "lib/AlsoGone");
        observed.observedLoading = true;
        Violation reachable = violation("app/Hot", "lib/Hot");
        reachable.reachable = Boolean.TRUE;
        // The same symbol broken by both a dead and a live class must not be drafted.
        Violation mixedDead = violation("app/DeadC", "lib/Mixed");
        Violation mixedLive = violation("app/Hot2", "lib/Mixed");
        mixedLive.reachable = Boolean.TRUE;
        // A draftable class-level symbol whose owner also carries a reachable member break.
        // The member-less rule would waive that break, so it must not be drafted.
        Violation splitClass = violation("app/DeadD", "lib/Split");
        Violation splitMember = violation("app/Hot3", "lib/Split", "n", "()V");
        splitMember.reachable = Boolean.TRUE;

        Evidence.LoadEvidence evidence = parse("com.example.Whatever\n");
        String path = dir.resolve("draft.toml").toString();
        int drafted = Evidence.draftExcludes(
                List.of(unobservedA, unobservedB, observed, reachable, mixedDead, mixedLive, splitClass, splitMember),
                Boolean.TRUE,
                evidence,
                path);
        assertEquals(1, drafted);
        String content = Files.readString(Path.of(path));
        assertTrue(content.contains("owner = \"lib/Gone\""), content);
        assertTrue(content.contains("member = \"m\""), content);
        assertTrue(content.contains("descriptor = \"()V\""), content);
        assertTrue(content.contains("REVIEW: referenced only by app.DeadA, app.DeadB;"), content);
        assertFalse(content.contains("lib/AlsoGone"), content);
        assertFalse(content.contains("lib/Hot"), content);
        assertFalse(content.contains("lib/Mixed"), content);
        assertFalse(content.contains("lib/Split"), content);
        // The draft must load as a real exclude file.
        List<Exclude.Rule> rules = Exclude.load(List.of(path));
        assertEquals(1, rules.size());
    }

    /**
     * An invalid reachable axis (roots matched nothing, appRootsMatched = FALSE) drafts
     * nothing. Every reachable = FALSE then carries no evidence, and the per-module fold in
     * the command layer passes exactly this value when any run's roots matched nothing. The
     * gate fails those violations, so drafting them would propose waiving the very breaks
     * the command exits 1 on.
     */
    @Test
    void nothingIsDraftedWhenTheReachableAxisIsInvalid(@TempDir Path dir) {
        Violation v = violation("app/Dead", "lib/Gone");
        Evidence.LoadEvidence evidence = parse("");
        String path = dir.resolve("draft.toml").toString();
        int drafted = Evidence.draftExcludes(List.of(v), Boolean.FALSE, evidence, path);
        assertEquals(0, drafted);
    }

    /**
     * A directory of logs (per-process files from parallel test JVMs, or an unpacked CI
     * artifact) reads recursively as one source.
     */
    @Test
    void aDirectoryOfLogsReadsEveryFile(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("a.log"), "com.example.A\n");
        Files.writeString(dir.resolve("nested").resolve("b.log"), "com.example.B\n");
        Evidence.LoadEvidence e = Evidence.load(List.of(dir.toString()));
        assertNotNull(e.observed("com/example/A"));
        assertNotNull(e.observed("com/example/B"));
        assertEquals(dir.toString(), e.sources(), "the directory is one source");
    }

    /**
     * A newline-less binary dropped into the log directory is skipped in bounded memory and
     * does not derail the lines after it in other respects.
     */
    @Test
    void oversizedLinesAreSkippedInBoundedMemory(@TempDir Path dir) throws IOException {
        byte[] tail = "\ncom.example.After\n".getBytes();
        byte[] junk = new byte[3 * Evidence.MAX_LINE + tail.length];
        Arrays.fill(junk, 0, 3 * Evidence.MAX_LINE, (byte) 'x');
        System.arraycopy(tail, 0, junk, 3 * Evidence.MAX_LINE, tail.length);
        Files.write(dir.resolve("junk.bin"), junk);
        Evidence.LoadEvidence e = Evidence.load(List.of(dir.toString()));
        assertNotNull(e.observed("com/example/After"));
        assertEquals(1, e.distinctClasses());
    }

    /**
     * {@code .jfr} files in a directory are the binary recordings the plugins convert and
     * leave in place. The walk skips them by NAME (never byte-scanning megabytes for
     * newlines, never fluke-registering pool strings), while an explicitly passed
     * {@code .jfr} path is still read line by line.
     */
    @Test
    void jfrNamedFilesAreSkippedInTheDirectoryWalkOnly(@TempDir Path dir) throws IOException {
        Path jfr = dir.resolve("rec.jfr");
        // Readable text on purpose. If the walk opened the file at all, this would register
        // and the assertion below would catch it.
        Files.writeString(jfr, "com.example.FromRecording\n");
        Files.writeString(dir.resolve("plain.log"), "com.example.FromLog\n");
        Evidence.LoadEvidence e = Evidence.load(List.of(dir.toString()));
        assertNotNull(e.observed("com/example/FromLog"));
        assertNull(e.observed("com/example/FromRecording"), "a .jfr inside the directory must be skipped by name");
        Evidence.LoadEvidence direct = Evidence.load(List.of(jfr.toString()));
        assertNotNull(direct.observed("com/example/FromRecording"), "an explicitly passed .jfr path must still be read");
    }

    /**
     * With nothing to draft the file still exists and says why, so a requested draft is never
     * silently absent.
     */
    @Test
    void emptyDraftStillWritesTheFile(@TempDir Path dir) throws IOException {
        Violation v = violation("app/Hot", "lib/Hot");
        v.reachable = Boolean.TRUE;
        Evidence.LoadEvidence evidence = parse("");
        String path = dir.resolve("draft.toml").toString();
        int drafted = Evidence.draftExcludes(List.of(v), Boolean.TRUE, evidence, path);
        assertEquals(0, drafted);
        String content = Files.readString(Path.of(path));
        assertTrue(content.contains("Nothing to draft"), content);
        assertTrue(Exclude.load(List.of(path)).isEmpty());
    }

    /**
     * The placeholder written before the check runs is a valid, empty exclude file, so an
     * errored run leaves neither a stale draft nor an unparseable one. The fixture carries
     * the header because a real stale draft always does. The headerless case is refused, and
     * covered below.
     */
    @Test
    void draftPlaceholderIsAValidEmptyExcludeFile(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("draft.toml");
        Files.writeString(
                path,
                "# Draft exclude rules generated by uika --draft-exclude-file.\n[[exclude]]\nowner = \"stale/Rule\"\n");
        Evidence.createDraftPlaceholder(path.toString());
        String content = Files.readString(path);
        assertFalse(content.contains("stale/Rule"), content);
        assertTrue(Exclude.load(List.of(path.toString())).isEmpty());
    }

    /**
     * Reproduced before the guard existed. The run exited 0 and keep.toml came back holding
     * the draft header.
     */
    @Test
    void aFileUikaDidNotWriteIsNeverTruncated(@TempDir Path dir) throws IOException {
        Path rules = dir.resolve("keep.toml");
        String handWritten = "[[exclude]]\nowner = \"org/example/Keep\"\nreason = \"mine\"\n";
        Files.writeString(rules, handWritten);
        Path linked = dir.resolve("draft.toml");
        Files.createLink(linked, rules);

        String message = assertThrows(UikaException.class, () -> Evidence.createDraftPlaceholder(linked.toString()))
                .getMessage();
        assertTrue(message.contains("not written by --draft-exclude-file"), message);
        assertEquals(handWritten, Files.readString(rules));

        // Ours to replace. An earlier placeholder, and an empty file the user made.
        String ours = dir.resolve("ours.toml").toString();
        Evidence.createDraftPlaceholder(ours);
        Evidence.createDraftPlaceholder(ours);
        Path empty = dir.resolve("empty.toml");
        Files.writeString(empty, "");
        Evidence.createDraftPlaceholder(empty.toString());
    }

    /** A failed UTF-8 decode used to count as ours, so the file was replaced. */
    @Test
    void aFileThatIsNotUtf8IsNeverTruncated(@TempDir Path dir) throws IOException {
        byte[] binary = {(byte) 0xff};
        Path path = Files.write(dir.resolve("draft.toml"), binary);

        assertEquals(
                "refusing to overwrite " + path
                        + ": it was not written by --draft-exclude-file. Draft to a new path and merge.",
                assertThrows(UikaException.class, () -> Evidence.createDraftPlaceholder(path.toString()))
                        .getMessage());
        assertArrayEquals(binary, Files.readAllBytes(path));
    }

    /** A failed read used to count as ours, and a write-only file was replaced. */
    @Test
    void aFileThatCannotBeReadIsNeverTruncated(@TempDir Path dir) throws IOException {
        String handWritten = "[[exclude]]\nowner = \"org/example/Keep\"\nreason = \"mine\"\n";
        Path path = Files.writeString(dir.resolve("draft.toml"), handWritten);
        assumeTrue(
                path.toFile().setReadable(false, false) && !Files.isReadable(path),
                "cannot take read permission away here (root or not POSIX)");
        try {
            assertEquals(
                    "cannot read draft exclude file " + path + ": Permission denied (os error 13)",
                    assertThrows(UikaException.class, () -> Evidence.createDraftPlaceholder(path.toString()))
                            .getMessage());
        } finally {
            path.toFile().setReadable(true, false);
        }
        assertEquals(handWritten, Files.readString(path));
    }

    /**
     * Evidence is data another job produces, so an absent path is an operational state, not a
     * mistake, and an empty directory already degraded this way.
     */
    @Test
    void aMissingEvidencePathIsSkippedNotFatal(@TempDir Path dir) throws IOException {
        Path present = dir.resolve("there.log");
        Files.writeString(present, "[class,load] com.example.Loaded\n");

        Evidence.LoadEvidence evidence =
                Evidence.load(List.of(dir.resolve("gone.log").toString(), present.toString()));
        // The surviving path still contributes, so one bad entry does not cost the rest.
        assertEquals(1, evidence.distinctClasses());

        // A permission or read error is still fatal. Only absence is tolerated.
        Path unreadable = dir.resolve("adir");
        Files.createDirectories(unreadable);
        assertEquals(0, Evidence.load(List.of(unreadable.toString())).distinctClasses());
    }

    /**
     * Every frontend creates the dump's parents, so a matching target/uika/draft.toml is the
     * natural next thing to write.
     */
    @Test
    void theDraftPlaceholderCreatesItsParentDirectories(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("made").resolve("target").resolve("uika").resolve("draft.toml");

        Evidence.createDraftPlaceholder(nested.toString());

        String content = Files.readString(nested);
        assertTrue(content.contains("did not complete"), content);
    }

    /**
     * Linux's wording for open(O_CREAT) on such a path. Each of these used to write a regular
     * file with the slash dropped.
     */
    @Test
    void aDraftPathEndingInASlashIsRefused(@TempDir Path dir) throws IOException {
        String stale = "# Draft exclude rules generated by uika --draft-exclude-file.\n";
        Path draft = Files.writeString(dir.resolve("draft.toml"), stale);

        for (String path : List.of(dir + "/new/", dir + "/made/new/", draft + "/")) {
            assertEquals(
                    "cannot write draft exclude file " + path + ": Is a directory (os error 21)",
                    assertThrows(UikaException.class, () -> Evidence.createDraftPlaceholder(path)).getMessage());
        }
        assertFalse(Files.exists(dir.resolve("new")));
        assertFalse(Files.exists(dir.resolve("made")));
        assertEquals(stale, Files.readString(draft));
    }

    // Beyond the Rust unit tests. The messages below were read off the Rust binary.

    @Test
    void directoryWalkFailuresNameThePathTheWayWalkdirDoes(@TempDir Path dir) throws IOException {
        Path looped = dir.resolve("loop");
        Files.createDirectories(looped.resolve("sub"));
        Files.createSymbolicLink(looped.resolve("sub").resolve("back"), Path.of(".."));
        String message = assertThrows(UikaException.class, () -> Evidence.load(List.of(looped.toString())))
                .getMessage();
        assertEquals(
                "cannot read class-load log directory " + looped + ": File system loop found: " + looped
                        + "/sub/back points to an ancestor " + looped,
                message);

        Path dangling = dir.resolve("dangling");
        Files.createDirectories(dangling);
        Files.createSymbolicLink(dangling.resolve("zz.log"), Path.of("/nonexistent/uika/target"));
        message = assertThrows(UikaException.class, () -> Evidence.load(List.of(dangling.toString())))
                .getMessage();
        assertEquals(
                "cannot read class-load log directory " + dangling + ": IO error for operation on " + dangling
                        + "/zz.log: No such file or directory (os error 2): No such file or directory (os error 2)",
                message);
    }

    /** Files parse in name order with a directory's contents in its place, so the first stack is stable. */
    @Test
    void directoryEntriesAreReadInFileNameOrder(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("b").resolve("z.log"), "Java stack when loading a.b.C:\n\tat first.Caller.run(F.java:1)\n");
        Files.writeString(dir.resolve("c.log"), "Java stack when loading a.b.C:\n\tat second.Caller.run(S.java:1)\n");
        Files.writeString(dir.resolve("a.log"), "a.b.C\n");
        Evidence.LoadEvidence e = Evidence.load(List.of(dir.toString()));
        assertEquals("first.Caller.run(F.java:1)", e.observed("a/b/C").trigger());
    }

    @Test
    void aLineOfExactlyTheCapIsStillRead(@TempDir Path dir) throws IOException {
        String name = "com.example.Edge";
        StringBuilder line = new StringBuilder(name);
        while (line.length() < Evidence.MAX_LINE - 1) {
            line.append(' ');
        }
        Files.writeString(dir.resolve("edge.log"), line + "\ncom.example.Next\n");
        Evidence.LoadEvidence e = Evidence.load(List.of(dir.toString()));
        assertNotNull(e.observed("com/example/Edge"));
        assertNotNull(e.observed("com/example/Next"));
    }

    /** First stack WITH frames wins, so a header that got no frames leaves the slot open. */
    @Test
    void aStackWithoutFramesLeavesTheSlotToTheNextOne() {
        Evidence.LoadEvidence e = parse("""
                Java stack when loading a.b.C:
                Java stack when loading a.b.C:
                \tat com.example.Later.run(Later.java:9)
                """);
        assertEquals("com.example.Later.run(Later.java:9)", e.observed("a/b/C").trigger());
    }

    @Test
    void aStackOfOnlyLoaderFramesClaimsTheSlotWithoutATrigger() {
        Evidence.LoadEvidence e = parse("""
                Java stack when loading a.b.D:
                \tat java.lang.ClassLoader.loadClass(ClassLoader.java:1)
                Java stack when loading a.b.D:
                \tat com.example.Late.run(Late.java:1)
                """);
        assertNotNull(e.observed("a/b/D"));
        assertNull(e.observed("a/b/D").trigger());
    }

    @Test
    void aReflectiveTriggerWithoutACallerIsKeptWhole() {
        Evidence.LoadEvidence e = parse("""
                Java stack when loading a.b.E:
                \tat java.lang.Class.forName(Class.java:100)
                Java stack when loading a.b.F:
                \tat java.lang.Class.forName
                \tat com.example.Registry.discover(Registry.java:42)
                """);
        assertEquals("java.lang.Class.forName(Class.java:100)", e.observed("a/b/E").trigger());
        assertEquals(
                "java.lang.Class.forName from com.example.Registry.discover(Registry.java:42)",
                e.observed("a/b/F").trigger());
    }

    /** ServiceLoader's own frames sit between the reflective API and the code that iterated the loader. */
    @Test
    void aServiceLoaderLoadNamesTheCodeThatIteratedIt() {
        Evidence.LoadEvidence e = parse("""
                Java stack when loading com.example.spi.Impl:
                \tat java.lang.ClassLoader.loadClass(java.base@21.0.4/ClassLoader.java:526)
                \tat java.lang.Class.forName0(java.base@21.0.4/Native Method)
                \tat java.lang.Class.forName(java.base@21.0.4/Class.java:534)
                \tat java.util.ServiceLoader$LazyClassPathLookupIterator.nextProviderClass(java.base@21.0.4/ServiceLoader.java:1212)
                \tat java.util.ServiceLoader$3.hasNext(java.base@21.0.4/ServiceLoader.java:1393)
                \tat com.example.Boot.main(Boot.java:7)
                """);
        assertEquals(
                "java.lang.Class.forName0 from com.example.Boot.main(Boot.java:7)",
                e.observed("com/example/spi/Impl").trigger());
    }

    @Test
    void framesAreClassifiedByPackageAndLoaderMethodName() {
        for (String frame : List.of(
                "java.lang.reflect.Method.invoke(Method.java:580)",
                "jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)",
                "sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)",
                "java.lang.invoke.MethodHandleNatives.linkCallSite(MethodHandleNatives.java:1)",
                "java.util.ServiceLoader.load(ServiceLoader.java:1)")) {
            assertTrue(Evidence.isReflective(frame), frame);
        }
        assertFalse(Evidence.isReflective("com.example.Boot.main(Boot.java:7)"));
        for (String frame : List.of(
                "java.net.URLClassLoader.access$100(URLClassLoader.java:74)",
                "com.example.PluginLoader.findClass(PluginLoader.java:12)",
                "com.example.PluginLoader.defineClass(PluginLoader.java:30)",
                "com.example.PluginLoader.loadClass")) {
            assertTrue(Evidence.isMachinery(frame), frame);
        }
        assertFalse(Evidence.isMachinery("com.example.PluginLoader.lookup(PluginLoader.java:5)"));
    }

    /** A monitor annotation outside a block is not a class name either. */
    @Test
    void framesWithoutAnOpenBlockAreIgnored() {
        Evidence.LoadEvidence e = parse("""
                \tat com.example.Stray.run(Stray.java:1)
                Java stack when loading 1.2.3:
                \tat com.example.Orphan.run(Orphan.java:2)
                Native stack when loading 4.5:
                - locked <0x0000000c4f1f3d40> (a java.lang.Object)
                Java stack when loading :
                """);
        assertEquals(0, e.distinctClasses());
    }

    /** Decorators with nothing after them count as a blank line. */
    @Test
    void aBlankLineEndsTheStackBlock() {
        Evidence.LoadEvidence e = parse("""
                Java stack when loading a.b.G:

                \tat com.example.TooLate.run(TooLate.java:1)
                Java stack when loading a.b.H:
                [0.3s][info][class,load,cause]
                \tat com.example.TooLate.run(TooLate.java:1)
                """);
        assertNull(e.observed("a/b/G").trigger());
        assertNull(e.observed("a/b/H").trigger());
        assertEquals(2, e.distinctClasses());
    }

    /** A crash can cut a line inside its decorators, and what is left is not a class name. */
    @Test
    void aLineCutInsideItsDecoratorsRegistersNothing() {
        assertEquals(0, parse("[0.1s][info][class,load com.example.Cut\n").distinctClasses());
    }

    @Test
    void tokensMustBeWellFormedNames() {
        Evidence.LoadEvidence e = parse("""
                org.apache.logging.log4j.Log4j2
                com..example.Empty
                com.example.
                com.exa-mple.Dash
                com.example.At@1f
                :,;
                """);
        assertNotNull(e.observed("org/apache/logging/log4j/Log4j2"));
        assertEquals(1, e.distinctClasses());
    }

    /** A file named just ".jfr" has no extension, so it is not taken for a recording. */
    @Test
    void theWalkReadsEveryTextFileAndStopsAtEofInsideAnOversizedLine(@TempDir Path dir) throws IOException {
        byte[] head = "com.example.Before\n".getBytes(StandardCharsets.UTF_8);
        byte[] junk = new byte[head.length + 2 * Evidence.MAX_LINE];
        System.arraycopy(head, 0, junk, 0, head.length);
        Arrays.fill(junk, head.length, junk.length, (byte) 'x');
        Files.write(dir.resolve("core.bin"), junk);
        Files.writeString(dir.resolve("classlist"), "\ncom.example.Listed\n\n");
        Files.writeString(dir.resolve(".jfr"), "com.example.Hidden\n");
        Evidence.LoadEvidence e = Evidence.load(List.of(dir.toString()));
        assertNotNull(e.observed("com/example/Before"));
        assertNotNull(e.observed("com/example/Listed"));
        assertNotNull(e.observed("com/example/Hidden"));
        assertEquals(3, e.distinctClasses());
    }

    /** A JVM that is being attached to has a .java_pid socket in the temp directory, where logs may go too. */
    @Test
    void aSocketInTheDirectoryIsSkipped(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("run.log"), "com.example.A\n");
        try (ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.bind(UnixDomainSocketAddress.of(dir.resolve(".java_pid123")));
            Evidence.LoadEvidence e = Evidence.load(List.of(dir.toString()));
            assertEquals(1, e.distinctClasses());
        }
    }

    /** A directory given with a trailing slash does not grow a doubled one in messages. */
    @Test
    void aTrailingSlashIsNotDoubledInMessages(@TempDir Path dir) throws IOException {
        Files.createSymbolicLink(dir.resolve("zz.log"), Path.of("/nonexistent/uika/target"));
        String root = dir + "/";
        assertEquals(
                "cannot read class-load log directory " + root + ": IO error for operation on " + dir
                        + "/zz.log: No such file or directory (os error 2): No such file or directory (os error 2)",
                assertThrows(UikaException.class, () -> Evidence.load(List.of(root))).getMessage());
    }

    @Test
    void unreadableLogsEndTheCommand(@TempDir Path dir) throws IOException {
        Path locked = Files.createDirectories(dir.resolve("logs").resolve("locked"));
        Path file = Files.writeString(dir.resolve("run.log"), "com.example.A\n");
        assumeTrue(
                locked.toFile().setReadable(false, false) && file.toFile().setReadable(false, false)
                        && !Files.isReadable(locked) && !Files.isReadable(file),
                "cannot take read permission away here (root or not POSIX)");
        try {
            String logs = dir.resolve("logs").toString();
            assertEquals(
                    "cannot read class-load log directory " + logs + ": IO error for operation on " + locked
                            + ": Permission denied (os error 13): Permission denied (os error 13)",
                    assertThrows(UikaException.class, () -> Evidence.load(List.of(logs))).getMessage());
            assertEquals(
                    "cannot read class-load log " + file + ": Permission denied (os error 13)",
                    assertThrows(UikaException.class, () -> Evidence.load(List.of(file.toString()))).getMessage());
        } finally {
            locked.toFile().setReadable(true, false);
            file.toFile().setReadable(true, false);
        }
    }

    @Test
    void draftedRulesOrderClassLevelFirstThenByMember(@TempDir Path dir) throws IOException {
        List<Violation> violations = List.of(
                violation("app/A", "lib/X", "m", "(I)V"),
                violation("app/B", "lib/X"),
                violation("app/C", "lib/X", "n", "()V"),
                violation("app/D", "lib/X", "m", "()V"));
        String path = dir.resolve("draft.toml").toString();
        Evidence.LoadEvidence evidence = new Evidence.LoadEvidence(new HashMap<>(), "run.log");
        assertEquals(4, Evidence.draftExcludes(violations, null, evidence, path));
        List<String> symbols = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(path))) {
            if (line.startsWith("member = ") || line.startsWith("descriptor = ") || line.equals("[[exclude]]")) {
                symbols.add(line);
            }
        }
        assertEquals(
                List.of(
                        "[[exclude]]",
                        "[[exclude]]", "member = \"m\"", "descriptor = \"()V\"",
                        "[[exclude]]", "member = \"m\"", "descriptor = \"(I)V\"",
                        "[[exclude]]", "member = \"n\"", "descriptor = \"()V\""),
                symbols);
    }

    /** Drafting leaves directories to the placeholder, so a parent that is gone by then fails the write. */
    @Test
    void aDraftThatCannotBeWrittenIsNamed(@TempDir Path dir) {
        String path = dir.resolve("no-such-dir").resolve("draft.toml").toString();
        Evidence.LoadEvidence evidence = new Evidence.LoadEvidence(new HashMap<>(), "run.log");
        assertEquals(
                "cannot write draft exclude file " + path + ": No such file or directory (os error 2)",
                assertThrows(UikaException.class, () -> Evidence.draftExcludes(List.of(), null, evidence, path))
                        .getMessage());
    }

    /** Each path names an existing directory, so nothing is written wherever the test runs. */
    @Test
    void aDraftPathNamingADirectoryIsRefused(@TempDir Path dir) {
        for (String path : List.of("/", ".", dir + "/", dir.toString())) {
            assertEquals(
                    "cannot write draft exclude file " + path + ": Is a directory (os error 21)",
                    assertThrows(UikaException.class, () -> Evidence.createDraftPlaceholder(path)).getMessage());
        }
    }

    @Test
    void aDraftParentThatIsAFileFailsBeforeTheScan(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("file.txt"), "");
        // The doubled slash belongs to neither name, so the message names the file as it is.
        String path = file + "//draft.toml";
        assertEquals(
                "cannot create directory " + file + " for the draft exclude file: File exists (os error 17)",
                assertThrows(UikaException.class, () -> Evidence.createDraftPlaceholder(path)).getMessage());
        String deeper = file + "/sub/draft.toml";
        assertEquals(
                "cannot create directory " + file + "/sub for the draft exclude file: Not a directory (os error 20)",
                assertThrows(UikaException.class, () -> Evidence.createDraftPlaceholder(deeper)).getMessage());
    }

    @Test
    void draftTextIsPinned(@TempDir Path dir) throws IOException {
        List<Violation> violations = new ArrayList<>();
        for (String user : List.of("app/D", "app/B", "app/A", "app/C")) {
            violations.add(violation(user, "lib/Q\"uote", "m\\", "()V"));
        }
        violations.add(violation("app/Z", "lib/A"));
        String path = dir.resolve("draft.toml").toString();
        Evidence.LoadEvidence evidence = new Evidence.LoadEvidence(new HashMap<>(), "logs/a, logs/b");
        assertEquals(2, Evidence.draftExcludes(violations, null, evidence, path));
        assertEquals(
                """
                # Draft exclude rules generated by uika --draft-exclude-file.
                # Basis: no static path from the application reaches the referencing classes, and
                # none was observed loading in: logs/a, logs/b
                # Absence of a load entry proves nothing beyond the observed runs. Review each
                # entry and delete any you cannot justify before committing this file.

                [[exclude]]
                owner = "lib/A"
                reason = "REVIEW: referenced only by app.Z; no static path from the application reaches them and none was observed loading in logs/a, logs/b"

                [[exclude]]
                owner = "lib/Q\\"uote"
                member = "m\\\\"
                descriptor = "()V"
                reason = "REVIEW: referenced only by app.A, app.B, app.C and 1 more; no static path from the application reaches them and none was observed loading in logs/a, logs/b"
                """,
                Files.readString(Path.of(path)));
        assertEquals(2, Exclude.load(List.of(path)).size());
    }
}
