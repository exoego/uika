package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ports the `jdk.rs` tests. Those six need no JDK. The ones below them read a real ct.sym,
 * found the way the Rust integration tests find it, and are skipped when there is none.
 */
class JdkTest {
    @TempDir
    Path dir;

    @AfterEach
    void clearEnvironment() {
        Env.clearOverrides();
    }

    @Test
    void jmodsClassesAreLevelledToStubFidelity() {
        ClassApi api = new ClassApi();
        api.name = Intern.intern("java/awt/event/InputEvent");
        api.nestHost = Intern.intern("java/awt/event/Host");
        api.permitted = new int[] {Intern.intern("java/awt/event/MouseEvent")};
        Jdk.levelToCtSymFidelity(api);
        assertNull(api.permitted, "sealing must not survive into a pair");
        assertNotEquals(Intern.NONE, api.nestHost, "stubs keep NestHost, so it stays");
    }

    private int installedFeatureOf(String version) throws IOException {
        Path home = Files.createDirectories(dir.resolve("uika-jdk-release-test"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nOTHER=1\n", StandardCharsets.UTF_8);
        return Jdk.installedFeature(home);
    }

    @Test
    void featureVersionParsesBothNamingSchemes() throws Exception {
        assertEquals(21, installedFeatureOf("21.0.11"));
        assertEquals(25, installedFeatureOf("25"));
        assertEquals(8, installedFeatureOf("1.8.0_402"));
        Path home = dir.resolve("uika-jdk-release-test");
        Files.delete(home.resolve("release"));
        Files.delete(home);
        assertEquals(-1, Jdk.installedFeature(home));
    }

    @Test
    void releaseCodesFollowCtSymBase36Convention() {
        assertEquals('8', Jdk.releaseCode(8));
        assertEquals('9', Jdk.releaseCode(9));
        assertEquals('A', Jdk.releaseCode(10));
        assertEquals('H', Jdk.releaseCode(17));
        assertEquals('L', Jdk.releaseCode(21));
        assertEquals(0, Jdk.releaseCode(7));
        assertEquals(0, Jdk.releaseCode(36));
        assertEquals(17, Jdk.codeRelease('H'));
        assertEquals(-1, Jdk.codeRelease('7'));
    }

    @Test
    void parsesTheJdk12LayoutWithModuleDirs() {
        assertArrayEquals(new String[] {"GH", "java/lang/Object"}, Jdk.parseEntry("GH/java.base/java/lang/Object.sig"));
        assertArrayEquals(
                new String[] {"89ABCDEFGHIJK", "jdk/net/Sockets"}, Jdk.parseEntry("89ABCDEFGHIJK/jdk.net/jdk/net/Sockets.sig"));
    }

    @Test
    void parsesTheJdk9To11LayoutWithoutModuleDirs() {
        // Package segments never contain '.', so nothing is stripped.
        assertArrayEquals(new String[] {"9AB", "java/lang/String"}, Jdk.parseEntry("9AB/java/lang/String.sig"));
        // Joint dirs keep codes for releases below 8 (API unchanged since then).
        assertArrayEquals(new String[] {"678", "java/util/Map"}, Jdk.parseEntry("678/java/util/Map.sig"));
    }

    @Test
    void skipsNonClassEntries() {
        // JDK 9-11 module descriptors live under "<code>-modules".
        assertNull(Jdk.parseEntry("A-modules/java.base/module-info.sig"));
        // JDK 12+ module descriptors sit inside regular codes dirs.
        assertNull(Jdk.parseEntry("GH/java.base/module-info.sig"));
        // JDK 8 layout and metadata files.
        assertNull(Jdk.parseEntry("META-INF/sym/rt.jar/java/lang/Object.class"));
        assertNull(Jdk.parseEntry("L/system-modules"));
        // Directory entries.
        assertNull(Jdk.parseEntry("GH/java.base/java/lang/"));
    }

    // ---- beyond the Rust unit tests ----

    @Test
    void featureVersionRejectsWhatIsNotAVersion() throws Exception {
        assertEquals(-1, installedFeatureOf("one.two"));
        assertEquals(-1, installedFeatureOf(""));
        assertEquals(-1, installedFeatureOf("1"));
        assertEquals(17, installedFeatureOf("17-ea"));
        assertEquals(9, installedFeatureOf("9.0.4"));
        // Rust parses a u32, which takes no sign.
        assertEquals(-1, installedFeatureOf("-17"));
        Path home = dir.resolve("uika-jdk-release-test");
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"x\"\n", StandardCharsets.UTF_8);
        assertEquals(-1, Jdk.installedFeature(home));
    }

    @Test
    void uikaJdkIsAuthoritativeOverJavaHome() throws Exception {
        Path pinned = Files.createDirectories(dir.resolve("pinned/lib"));
        Files.writeString(pinned.resolve("ct.sym"), "not read here");
        Path fallback = Files.createDirectories(dir.resolve("fallback/lib"));
        Files.writeString(fallback.resolve("ct.sym"), "not read here");

        Env.override("UIKA_JDK", null);
        Env.override("JAVA_HOME", dir.resolve("fallback").toString());
        assertEquals(fallback.resolve("ct.sym"), Jdk.findCtSym());

        Env.override("UIKA_JDK", dir.resolve("pinned").toString());
        assertEquals(pinned.resolve("ct.sym"), Jdk.findCtSym());

        // UIKA_JDK may point straight at a ct.sym file.
        Env.override("UIKA_JDK", pinned.resolve("ct.sym").toString());
        assertEquals(pinned.resolve("ct.sym"), Jdk.findCtSym());

        // An empty value reads as unset, and a bad explicit pin does not fall through to JAVA_HOME.
        Env.override("UIKA_JDK", "");
        assertEquals(fallback.resolve("ct.sym"), Jdk.findCtSym());
        Env.override("UIKA_JDK", dir.resolve("nowhere").toString());
        assertNull(Jdk.findCtSym());
    }

    @Test
    void aMissingJdkIsExplainedByWhichVariableWasSet() {
        assertNull(Jdk.indexerFor(null));

        Env.override("UIKA_JDK", null);
        Env.override("JAVA_HOME", null);
        UikaException unset = assertThrows(UikaException.class, () -> Jdk.indexerFor(17));
        assertEquals(
                "--jdk-release 17 needs a JDK: set UIKA_JDK to a JDK home or a ct.sym file (checked first), "
                        + "or JAVA_HOME to a JDK home",
                unset.getMessage());
        UikaException pair = assertThrows(UikaException.class, () -> Jdk.releaseIndex(17, new ArrayList<>()));
        assertEquals(
                "--jdk-release-old/--jdk-release-new need a JDK: set UIKA_JDK to a JDK home (checked first) or JAVA_HOME",
                pair.getMessage());
        assertFalse(Jdk.isInstalledRelease(17));

        String nowhere = dir.resolve("nowhere").toString();
        Env.override("UIKA_JDK", nowhere);
        UikaException pinned = assertThrows(UikaException.class, () -> Jdk.indexerFor(17));
        assertEquals(
                "--jdk-release 17 needs a JDK: UIKA_JDK is set to " + nowhere + " but it is not a ct.sym file and has no lib/ct.sym",
                pinned.getMessage());
        UikaException noCtSym = assertThrows(UikaException.class, () -> Jdk.releaseIndex(17, new ArrayList<>()));
        assertEquals("no lib/ct.sym under " + nowhere + " and its release is not 17", noCtSym.getMessage());
    }

    /**
     * A JDK home with lib/ct.sym. Tried in order are the environment lookup the CLI uses, the
     * JDK running the tests, and the mise installs. The Rust integration tests ask `mise` for
     * the last step. A JVM test reads the install directory instead of starting a process.
     */
    private static Path findJdkHome() {
        List<Path> candidates = new ArrayList<>();
        Path fromEnv = Jdk.findHome();
        if (fromEnv != null) {
            candidates.add(fromEnv);
        }
        String property = System.getProperty("uika.javaHome");
        if (property != null && !property.isEmpty()) {
            candidates.add(Path.of(property));
        }
        candidates.add(Path.of(System.getProperty("java.home")));
        Path installs = Path.of(System.getProperty("user.home"), ".local/share/mise/installs/java");
        if (Files.isDirectory(installs)) {
            List<Path> installed = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(installs)) {
                for (Path p : stream) {
                    installed.add(p);
                }
            } catch (IOException e) {
                // no mise installs to offer
            }
            installed.sort(null);
            candidates.addAll(installed);
        }
        for (Path home : candidates) {
            if (Files.isDirectory(home) && Jdk.ctSymIn(home) != null && Jdk.installedFeature(home) > 11) {
                return home;
            }
        }
        return null;
    }

    /** A release the JDK at {@code home} serves from ct.sym, walking down a ladder like the Rust test. */
    private static int stubbedRelease(Path home) {
        int own = Jdk.installedFeature(home);
        for (int release : new int[] {17, 11, 8}) {
            if (release < own) {
                return release;
            }
        }
        return -1;
    }

    @Test
    void fetchesTheSupertypeClosureOfARootFromCtSym() {
        Path home = findJdkHome();
        assumeTrue(home != null, "no JDK with ct.sym found (UIKA_JDK/JAVA_HOME/mise)");
        int release = stubbedRelease(home);
        assumeTrue(release > 0, "no usable release in " + home);
        Env.override("UIKA_JDK", home.toString());

        List<String> warnings = new ArrayList<>();
        try (Jdk.Indexer indexer = Jdk.indexerFor(release)) {
            IntSet roots = new IntSet();
            roots.add(Intern.intern("java/util/ArrayList"));
            roots.add(Intern.intern("not/in/TheJdk"));
            ApiIndex index = indexer.fetchClosure(roots, warnings);

            assertEquals(List.of(), warnings);
            // The closure and nothing else, so ArrayList's supertypes but no unrelated class.
            for (String name : List.of(
                    "java/util/ArrayList", "java/util/AbstractList", "java/util/AbstractCollection", "java/util/List",
                    "java/util/Collection", "java/lang/Iterable", "java/util/RandomAccess", "java/lang/Cloneable",
                    "java/io/Serializable")) {
                assertTrue(index.containsClass(Intern.intern(name)), name);
            }
            assertFalse(index.containsClass(Intern.intern("java/util/HashMap")));
            assertFalse(index.containsClass(Intern.intern("not/in/TheJdk")));
            // java/lang/Object is never fetched, because its methods are built into resolution.
            assertFalse(index.containsClass(Scope.objectSym()));

            // With the layer an escape into the JDK concludes both ways.
            int arrayList = Intern.intern("java/util/ArrayList");
            assertEquals(Scope.Resolution.FOUND, index.resolve(arrayList, MemberKey.of("isEmpty", "()Z"), Scope.MemberKind.METHOD));
            assertEquals(
                    Scope.Resolution.FOUND,
                    index.resolve(arrayList, MemberKey.of("containsAll", "(Ljava/util/Collection;)Z"), Scope.MemberKind.METHOD));
            assertEquals(Scope.Resolution.FOUND, index.resolve(arrayList, MemberKey.of("hashCode", "()I"), Scope.MemberKind.METHOD));
            assertEquals(
                    Scope.Resolution.NOT_FOUND, index.resolve(arrayList, MemberKey.of("noSuchMethod", "()V"), Scope.MemberKind.METHOD));
        }
    }

    @Test
    void aReleaseCtSymDoesNotCarryIsExplained() {
        Path home = findJdkHome();
        assumeTrue(home != null, "no JDK with ct.sym found (UIKA_JDK/JAVA_HOME/mise)");
        Path ctSym = Jdk.ctSymIn(home);
        int own = Jdk.installedFeature(home);

        UikaException tooNew = assertThrows(UikaException.class, () -> Jdk.Indexer.open(ctSym, own));
        assertTrue(tooNew.getMessage().startsWith("release " + own + " not present in " + ctSym + " (available: "), tooNew.getMessage());
        assertTrue(
                tooNew.getMessage()
                        .endsWith("; the installed JDK's own release is served from its runtime image, not ct.sym, so pick an older one)"),
                tooNew.getMessage());
        // The available list is ascending and stops one short of the JDK's own release.
        String available = tooNew.getMessage().substring(tooNew.getMessage().indexOf("(available: ") + 12, tooNew.getMessage().indexOf(';'));
        List<String> releases = List.of(available.split(", "));
        assertEquals(String.valueOf(own - 1), releases.get(releases.size() - 1));
        for (int i = 1; i < releases.size(); i++) {
            assertTrue(Integer.parseInt(releases.get(i - 1)) < Integer.parseInt(releases.get(i)), available);
        }

        UikaException unsupported = assertThrows(UikaException.class, () -> Jdk.Indexer.open(ctSym, 7));
        assertEquals("unsupported --jdk-release 7 (not between 8 and 35)", unsupported.getMessage());
        assertEquals(
                "unsupported --jdk-release 36 (not between 8 and 35)",
                assertThrows(UikaException.class, () -> Jdk.Indexer.open(ctSym, 36)).getMessage());
    }

    @Test
    void theRunningReleaseComesFromJmodsAndOlderOnesFromCtSym() {
        Path home = findJdkHome();
        assumeTrue(home != null, "no JDK with ct.sym found (UIKA_JDK/JAVA_HOME/mise)");
        assumeTrue(Files.isDirectory(home.resolve("jmods")), "no jmods under " + home);
        int release = stubbedRelease(home);
        assumeTrue(release > 0, "no usable release in " + home);
        int own = Jdk.installedFeature(home);
        Env.override("UIKA_JDK", home.toString());
        assertTrue(Jdk.isInstalledRelease(own));
        assertFalse(Jdk.isInstalledRelease(release));

        List<String> warnings = new ArrayList<>();
        ApiIndex older = Jdk.releaseIndex(release, warnings);
        ApiIndex running = Jdk.releaseIndex(own, warnings);
        assertEquals(List.of(), warnings);
        int string = Intern.intern("java/lang/String");
        assertTrue(older.containsClass(string));
        assertTrue(running.containsClass(string));
        // jmods is a superset of ct.sym, so unexported internals are only there.
        int internal = Intern.intern("jdk/internal/misc/Unsafe");
        assertTrue(running.containsClass(internal));
        assertFalse(older.containsClass(internal));
        // Sealing is levelled away, or every sealed JDK class would read as newly sealed.
        int constantDesc = Intern.intern("java/lang/constant/ConstantDesc");
        assertTrue(running.containsClass(constantDesc));
        assertEquals(-1, running.permittedCount(running.entry(constantDesc)));
        // The whole release is in, not an escape closure.
        assertTrue(older.classCount() > 3000, "classes in release " + release + ": " + older.classCount());
    }

    // ---- stand-in JDK homes and archives, which need no ct.sym on this machine ----

    private static void zip(Path file, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    /** A real class file from the running JDK. */
    private static byte[] jdkClass(String name) throws IOException {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            return in.readAllBytes();
        }
    }

    private Path fakeHome(String version) throws IOException {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n", StandardCharsets.UTF_8);
        Env.override("UIKA_JDK", home.toString());
        return home;
    }

    /** Release 17 holds ArrayList without its supertypes plus a stub that does not parse. A joint dir covers 7 to 9. */
    private static Path fakeCtSym(Path file) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", new byte[0]);
        entries.put("789/java.base/java/lang/Old.sig", new byte[0]);
        entries.put("H/java.base/java/util/ArrayList.sig", jdkClass("java/util/ArrayList"));
        entries.put("H/java.base/x/Bad.sig", "not a class file at all".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(file.getParent());
        zip(file, entries);
        return file;
    }

    @Test
    void aStubThatDoesNotParseIsAWarningAndTheClosureStopsAtTheArchive() throws Exception {
        Path ctSym = fakeCtSym(dir.resolve("ct.sym"));
        List<String> warnings = new ArrayList<>();
        try (Jdk.Indexer indexer = Jdk.Indexer.open(ctSym, 17)) {
            IntSet roots = new IntSet();
            roots.add(Intern.intern("java/util/ArrayList"));
            roots.add(Intern.intern("x/Bad"));
            ApiIndex index = indexer.fetchClosure(roots, warnings);
            assertTrue(index.containsClass(Intern.intern("java/util/ArrayList")));
            // Its supertypes are not in this archive, so they stay out rather than fail.
            assertEquals(1, index.classCount());
        }
        assertEquals(List.of("ct.sym!x/Bad: not a class file (bad magic)"), warnings);

        // '7' is a real code in joint dirs but no selectable release, so it is not offered.
        assertEquals(
                "release 11 not present in " + ctSym + " (available: 8, 9, 17; the installed JDK's own release is served"
                        + " from its runtime image, not ct.sym, so pick an older one)",
                assertThrows(UikaException.class, () -> Jdk.Indexer.open(ctSym, 11)).getMessage());
    }

    @Test
    void anOlderReleaseComesWholeFromTheHomesCtSym() throws Exception {
        Path home = fakeHome("21.0.4");
        fakeCtSym(home.resolve("lib").resolve("ct.sym"));
        assertFalse(Jdk.isInstalledRelease(17));
        List<String> warnings = new ArrayList<>();
        ApiIndex index = Jdk.releaseIndex(17, warnings);
        assertTrue(index.containsClass(Intern.intern("java/util/ArrayList")));
        assertEquals(List.of("ct.sym!x/Bad: not a class file (bad magic)"), warnings);
    }

    @Test
    void theRunningReleaseNeedsJmods() throws Exception {
        Path home = fakeHome("21.0.4");
        assertTrue(Jdk.isInstalledRelease(21));
        assertEquals(
                "release 21 is this JDK's own, which ct.sym never carries, so it must come from " + home.resolve("jmods")
                        + " (absent in a JRE or a jlink'd runtime): No such file or directory (os error 2)",
                assertThrows(UikaException.class, () -> Jdk.releaseIndex(21, new ArrayList<>())).getMessage());
    }

    @Test
    void jmodsServeTheirClassesAndWarnOnOnesThatDoNotParse() throws Exception {
        Path home = fakeHome("21");
        Path jmods = Files.createDirectories(home.resolve("jmods"));
        Files.writeString(jmods.resolve("README"), "not a jmod, so never opened");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("classes/module-info.class", "never parsed".getBytes(StandardCharsets.UTF_8));
        entries.put("classes/java/lang/constant/ConstantDesc.class", jdkClass("java/lang/constant/ConstantDesc"));
        entries.put("classes/x/Bad.class", "not a class file at all".getBytes(StandardCharsets.UTF_8));
        entries.put("classes/META-INF/services/x.Y", "x.Z".getBytes(StandardCharsets.UTF_8));
        entries.put("lib/libx.so", new byte[] {1, 2, 3});
        Path jmod = jmods.resolve("java.base.jmod");
        zip(jmod, entries);

        List<String> warnings = new ArrayList<>();
        ApiIndex index = Jdk.releaseIndex(21, warnings);
        assertEquals(List.of(jmod + "!classes/x/Bad.class: not a class file (bad magic)"), warnings);
        assertEquals(1, index.classCount());
        int constantDesc = Intern.intern("java/lang/constant/ConstantDesc");
        assertTrue(index.containsClass(constantDesc));
        assertEquals(-1, index.permittedCount(index.entry(constantDesc)));
    }

    @Test
    void aJmodThatIsNotAZipIsRefused() throws Exception {
        Path home = fakeHome("21");
        Path jmod = Files.createDirectories(home.resolve("jmods")).resolve("broken.jmod");
        Files.writeString(jmod, "plain text");
        UikaException e = assertThrows(UikaException.class, () -> Jdk.releaseIndex(21, new ArrayList<>()));
        assertTrue(e.getMessage().startsWith("not a zip: " + jmod + ": "), e.getMessage());
    }

    @Test
    void anUnreadableCtSymIsNamed() throws Exception {
        Path ctSym = fakeCtSym(dir.resolve("ct.sym"));
        assumeTrue(
                ctSym.toFile().setReadable(false, false) && !Files.isReadable(ctSym),
                "cannot take read permission away here (root or not POSIX)");
        try {
            assertEquals(
                    "cannot open ct.sym: " + ctSym + ": Permission denied (os error 13)",
                    assertThrows(UikaException.class, () -> Jdk.Indexer.open(ctSym, 17)).getMessage());
        } finally {
            ctSym.toFile().setReadable(true, false);
        }
    }

    /** An empty UIKA_JDK is unset, so the hint is the general one rather than blaming the pin. */
    @Test
    void anEmptyPinGetsTheGeneralHint() {
        Env.override("UIKA_JDK", "");
        Env.override("JAVA_HOME", dir.resolve("nowhere").toString());
        assertEquals(
                "--jdk-release 17 needs a JDK: set UIKA_JDK to a JDK home or a ct.sym file (checked first), "
                        + "or JAVA_HOME to a JDK home",
                assertThrows(UikaException.class, () -> Jdk.indexerFor(17)).getMessage());
    }

    @Test
    void onlyUppercaseBase36DirsHoldStubs() {
        assertNull(Jdk.parseEntry("ct.properties"));
        assertNull(Jdk.parseEntry("/java.base/java/lang/Object.sig"));
        assertNull(Jdk.parseEntry("h/java.base/java/lang/Object.sig"));
        assertFalse(Jdk.isCodesDir(""));
    }

    @Test
    void aFileThatIsNotCtSymIsRefused() throws Exception {
        Path notZip = dir.resolve("ct.sym");
        Files.writeString(notZip, "plain text");
        UikaException e = assertThrows(UikaException.class, () -> Jdk.Indexer.open(notZip, 17));
        assertTrue(e.getMessage().startsWith("not a zip: " + notZip), e.getMessage());

        Path missing = dir.resolve("missing/ct.sym");
        UikaException gone = assertThrows(UikaException.class, () -> Jdk.Indexer.open(missing, 17));
        assertEquals("cannot open ct.sym: " + missing + ": No such file or directory (os error 2)", gone.getMessage());

        // A zip with no release-coded entries at all, like the JDK 8 layout.
        Path jdk8 = dir.resolve("jdk8-ct.sym");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jdk8))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/sym/rt.jar/java/lang/Object.class"));
            zip.closeEntry();
        }
        UikaException preNine = assertThrows(UikaException.class, () -> Jdk.Indexer.open(jdk8, 8));
        assertEquals(
                "no release-coded API stubs found in " + jdk8 + " (pre-JDK-9 ct.sym layout, or not a ct.sym)", preNine.getMessage());
    }
}
