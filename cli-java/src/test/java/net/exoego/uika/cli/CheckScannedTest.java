package net.exoego.uika.cli;

import static net.exoego.uika.cli.CheckTest.JAVA_LANG_OBJECT;
import static net.exoego.uika.cli.CheckTest.classWithFields;
import static net.exoego.uika.cli.CheckTest.classWithMethodAccess;
import static net.exoego.uika.cli.CheckTest.intern;
import static net.exoego.uika.cli.CheckTest.m;
import static net.exoego.uika.cli.CheckTest.syms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Whole {@link Check#checkScanned} runs: pass 2, the verdicts, the graph walks and reachability together. */
final class CheckScannedTest {
    @TempDir
    Path dir;

    private static ApiIndex index(ClassApi... apis) {
        return ApiIndex.build(List.of(apis));
    }

    private static ClassApi classApi(String name, String superName, int access) {
        ClassApi api = new ClassApi();
        api.name = intern(name);
        api.access = access;
        api.superName = intern(superName);
        return api;
    }

    private static ClassApi provider(String name, String service) {
        ClassApi api = classWithMethodAccess(name, m("<init>", "()V", Acc.PUBLIC));
        api.interfaces = syms(service);
        return api;
    }

    private static Scan.Target scanned(String source, String name, String superName, String[] interfaces, List<SymbolRef> refs) {
        int superSym = superName == null ? Intern.NONE : intern(superName);
        return new Scan.Target(intern(source), intern(name), true, superSym, syms(interfaces), Intern.NONE, null, refs, new int[0]);
    }

    private static Scan.Target scanned(String source, String name, String superName) {
        return scanned(source, name, superName, new String[0], List.of());
    }

    private static Scan.Result scanOf(Scan.Target... targets) {
        Scan.Result scan = new Scan.Result();
        scan.merge(Scan.leafOf(List.of(targets)));
        return scan;
    }

    private static Reach.Inputs roots(List<Reach.ServiceFile> services, String... appSources) {
        IntSet sources = new IntSet();
        for (String source : appSources) {
            sources.add(intern(source));
        }
        return new Reach.Inputs(sources, services);
    }

    private static Check.Report check(Scan.Result scan, ApiIndex oldLib, ApiIndex newLib, Reach.Inputs reach, Check.SpiServices services) {
        return Check.checkScanned(scan, oldLib, newLib, new IntSet(), null, reach, services, null);
    }

    private static SymbolRef fieldRead(String owner, String name, String descriptor) {
        return new SymbolRef(RefKind.FIELD, intern(owner), MemberKey.of(name, descriptor), Boolean.FALSE, Boolean.FALSE, null);
    }

    // ---- in-memory scans ----

    @Test
    void serviceProviderBreaksAreRankedByTheRegistrationEdgesTheScanSaw() {
        ApiIndex oldLib = index(
                classApi("lib/Spi", JAVA_LANG_OBJECT, Acc.PUBLIC | Acc.INTERFACE | Acc.ABSTRACT),
                provider("lib/Impl", "lib/Spi"),
                provider("lib/Driver", "java/sql/Driver"));
        ClassApi abstractImpl = provider("lib/Impl", "lib/Spi");
        abstractImpl.access = Acc.PUBLIC | Acc.ABSTRACT;
        ApiIndex newLib = index(classApi("lib/Spi", JAVA_LANG_OBJECT, Acc.PUBLIC | Acc.INTERFACE | Acc.ABSTRACT), abstractImpl);
        List<Reach.ServiceFile> oldFiles = List.of(
                new Reach.ServiceFile(intern("lib/Spi"), syms("lib/Impl"), intern("lib-old.jar")),
                new Reach.ServiceFile(intern("java/sql/Driver"), syms("lib/Driver"), intern("lib-old.jar")));
        List<Reach.ServiceFile> newFiles = List.of(
                new Reach.ServiceFile(intern("lib/Spi"), syms("lib/Impl"), intern("lib-new.jar")),
                new Reach.ServiceFile(intern("java/sql/Driver"), syms("lib/Driver"), intern("lib-new.jar")));
        // The upgraded jar is a scan target, as in every real run, and nothing in the app uses lib/Spi.
        Scan.Result scan = scanOf(
                scanned("app-classes", "app/Main", JAVA_LANG_OBJECT),
                scanned("lib-new.jar", "lib/Spi", JAVA_LANG_OBJECT),
                scanned("lib-new.jar", "lib/Impl", JAVA_LANG_OBJECT, new String[] {"lib/Spi"}, List.of()));

        Check.Report report = check(scan, oldLib, newLib, roots(newFiles, "app-classes"), new Check.SpiServices(oldFiles, newFiles));

        assertEquals(2, report.violations.size());
        Violation driver = report.violations.get(0);
        assertEquals("lib/Driver", Intern.str(driver.sourceClass));
        assertEquals(Reason.SERVICE_PROVIDER_REMOVED, driver.reason);
        // DriverManager loads every java.sql.Driver provider unasked, so the registration is a root.
        assertEquals(Boolean.TRUE, driver.reachable);
        Violation impl = report.violations.get(1);
        assertEquals("lib/Impl", Intern.str(impl.sourceClass));
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, impl.reason);
        // The registration edge was in the scan and still never marked, so this is proven.
        assertEquals(Boolean.FALSE, impl.reachable);
        assertEquals(Boolean.TRUE, report.appRootsMatched);
        assertEquals(List.of(), report.warnings);
    }

    @Test
    void rootsThatMatchNoScannedClassWarnThatTheRankingIsVoid() {
        ApiIndex oldLib = index(classWithMethodAccess("lib/C", m("m", "()V", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithMethodAccess("lib/C"));
        SymbolRef call = new SymbolRef(RefKind.METHOD, intern("lib/C"), MemberKey.of("m", "()V"), Boolean.FALSE, null, null);
        Scan.Result scan = scanOf(scanned("consumer.jar", "app/Use", JAVA_LANG_OBJECT, new String[0], List.of(call)));

        Check.Report report = check(scan, oldLib, newLib, roots(List.of(), "build/classes/java/main"), Check.SpiServices.NONE);

        assertEquals(1, report.violations.size());
        assertEquals(Reason.METHOD_REMOVED, report.violations.get(0).reason);
        assertEquals(Boolean.FALSE, report.appRootsMatched);
        assertEquals(
                List.of("reachability: no application root matched a scanned class "
                        + "(were the project's build outputs compiled?); "
                        + "violations are not ranked by reachability in this run"),
                report.warnings);
    }

    @Test
    void finalWalksTolerateUnreadableSubclassesAndMalformedChains() {
        ApiIndex oldLib = index(classApi("lib/F", JAVA_LANG_OBJECT, Acc.PUBLIC), classWithMethodAccess("lib/M", m("m", "()V", Acc.PUBLIC)));
        ApiIndex newLib = index(
                classApi("lib/F", JAVA_LANG_OBJECT, Acc.PUBLIC | Acc.FINAL),
                classWithMethodAccess("lib/M", m("m", "()V", Acc.PUBLIC | Acc.FINAL)));
        String gone = dir.resolve("gone.jar").toString();
        Scan.Result scan = scanOf(
                // android.jar and similar bundles put java/lang/Object, which has no superclass, into the scan.
                scanned("android.jar", JAVA_LANG_OBJECT, null),
                scanned("app.jar", "app/Sub", "lib/F"),
                // Whether this one overrides the now-final m is unknowable once its jar is gone.
                scanned(gone, "app/Over", "lib/M"),
                // The JVM rejects a cycle with ClassCircularityError. The walk must still end.
                scanned("app.jar", "app/CycA", "app/CycB"),
                scanned("app.jar", "app/CycB", "app/CycA"));

        Check.Report report = check(scan, oldLib, newLib, null, Check.SpiServices.NONE);

        assertEquals(1, report.violations.size());
        Violation v = report.violations.get(0);
        assertEquals(Reason.CLASS_BECAME_FINAL, v.reason);
        assertEquals("app/Sub", Intern.str(v.sourceClass));
        assertEquals("lib/F", Intern.str(v.reference.owner()));
        assertEquals(List.of(gone + ": cannot open " + gone + ": No such file or directory"), report.warnings);
    }

    /**
     * The new library moved x into lib/Base, a class old did not have, so pass 1 never read
     * its NestHost. If the jar is gone when the verdict asks, lib/Base hosts itself, which is
     * its real nest host anyway, so its nest member keeps private access.
     */
    @Test
    void aNestMemberKeepsPrivateAccessWhenItsHostCannotBeReRead() {
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC)));
        ClassApi c = classApi("lib/C", "lib/Base", Acc.PUBLIC);
        ClassApi base = classWithFields("lib/Base", m("x", "I", Acc.PRIVATE));
        ApiIndex newLib = index(c, base);
        String gone = dir.resolve("lib-new.jar").toString();
        Scan.Target host = new Scan.Target(
                intern(gone), intern("lib/Base"), true, intern(JAVA_LANG_OBJECT), new int[0], ClassGraph.NEST_HOST_UNREAD, null,
                List.of(), new int[0]);
        Scan.Target member = new Scan.Target(
                intern(gone), intern("lib/Base$Inner"), true, intern(JAVA_LANG_OBJECT), new int[0], intern("lib/Base"), null,
                List.of(fieldRead("lib/C", "x", "I")), new int[0]);

        Check.Report report = check(scanOf(host, member), oldLib, newLib, null, Check.SpiServices.NONE);

        assertEquals(List.of(), report.violations);
        assertEquals(0, report.unknownRefs);
    }

    // ---- scans of real class files ----

    /** A minimal class-file writer: constant pool, header, members with code, and nest attributes. */
    private static final class ClassWriter {
        private final ByteArrayOutputStream pool = new ByteArrayOutputStream();
        private final Map<String, Integer> indexes = new HashMap<>();
        private int poolCount = 1;
        private final ByteArrayOutputStream members = new ByteArrayOutputStream();
        private int fieldCount;
        private int methodCount;
        private final ByteArrayOutputStream methods = new ByteArrayOutputStream();
        private final ByteArrayOutputStream attributes = new ByteArrayOutputStream();
        private int attributeCount;
        private final int thisClass;
        private final int superClass;

        ClassWriter(String name, String superName) {
            thisClass = classRef(name);
            superClass = classRef(superName);
        }

        private static void u16(ByteArrayOutputStream out, int value) {
            out.write(value >>> 8);
            out.write(value);
        }

        private static void u32(ByteArrayOutputStream out, int value) {
            u16(out, value >>> 16);
            u16(out, value & 0xffff);
        }

        private int entry(String key, int tag, int... operands) {
            Integer known = indexes.get(key);
            if (known != null) {
                return known;
            }
            pool.write(tag);
            for (int operand : operands) {
                u16(pool, operand);
            }
            indexes.put(key, poolCount);
            return poolCount++;
        }

        int utf8(String text) {
            Integer known = indexes.get("U" + text);
            if (known != null) {
                return known;
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            pool.write(1);
            u16(pool, bytes.length);
            pool.writeBytes(bytes);
            indexes.put("U" + text, poolCount);
            return poolCount++;
        }

        int classRef(String name) {
            int nameIndex = utf8(name);
            return entry("C" + name, 7, nameIndex);
        }

        int memberRef(int tag, String owner, String name, String descriptor) {
            int ownerIndex = classRef(owner);
            int nameIndex = utf8(name);
            int descriptorIndex = utf8(descriptor);
            int nameAndType = entry("N" + name + ":" + descriptor, 12, nameIndex, descriptorIndex);
            return entry(tag + owner + "." + name + ":" + descriptor, tag, ownerIndex, nameAndType);
        }

        ClassWriter field(int access, String name, String descriptor) {
            u16(members, access);
            u16(members, utf8(name));
            u16(members, utf8(descriptor));
            u16(members, 0);
            fieldCount++;
            return this;
        }

        /** A method whose Code attribute holds {@code code}, which may name pool entries built beforehand. */
        ClassWriter method(int access, String name, String descriptor, int... code) {
            u16(methods, access);
            u16(methods, utf8(name));
            u16(methods, utf8(descriptor));
            u16(methods, 1);
            u16(methods, utf8("Code"));
            u32(methods, 12 + code.length);
            u16(methods, 2);
            u16(methods, 2);
            u32(methods, code.length);
            for (int b : code) {
                methods.write(b);
            }
            u16(methods, 0);
            u16(methods, 0);
            methodCount++;
            return this;
        }

        ClassWriter nestHost(String host) {
            int hostIndex = classRef(host);
            u16(attributes, utf8("NestHost"));
            u32(attributes, 2);
            u16(attributes, hostIndex);
            attributeCount++;
            return this;
        }

        ClassWriter nestMembers(String... names) {
            int[] refs = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                refs[i] = classRef(names[i]);
            }
            u16(attributes, utf8("NestMembers"));
            u32(attributes, 2 + 2 * names.length);
            u16(attributes, names.length);
            for (int ref : refs) {
                u16(attributes, ref);
            }
            attributeCount++;
            return this;
        }

        byte[] bytes() {
            ByteArrayOutputStream out = header();
            u16(out, fieldCount);
            out.writeBytes(members.toByteArray());
            u16(out, methodCount);
            out.writeBytes(methods.toByteArray());
            u16(out, attributeCount);
            out.writeBytes(attributes.toByteArray());
            return out.toByteArray();
        }

        /** Cut off right after the interface table: pass 1 still reads it, a full parse cannot. */
        byte[] headerOnly() {
            return header().toByteArray();
        }

        private ByteArrayOutputStream header() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            u32(out, 0xCAFEBABE);
            u16(out, 0);
            u16(out, 55);
            u16(out, poolCount);
            out.writeBytes(pool.toByteArray());
            u16(out, Acc.PUBLIC | 0x0020);
            u16(out, thisClass);
            u16(out, superClass);
            u16(out, 0);
            return out;
        }
    }

    private static final int GETFIELD = 0xb4;
    private static final int INVOKEVIRTUAL = 0xb6;
    private static final int ACONST_NULL = 0x01;
    private static final int POP = 0x57;
    private static final int RETURN = 0xb1;

    private static ClassWriter callingMethod(String name, String owner, String method, String descriptor) {
        ClassWriter w = new ClassWriter(name, JAVA_LANG_OBJECT);
        int ref = w.memberRef(10, owner, method, descriptor);
        return w.method(Acc.PUBLIC, "call", "()V", ACONST_NULL, INVOKEVIRTUAL, ref >>> 8, ref & 0xff, RETURN);
    }

    private String jar(String name, Object... entries) throws Exception {
        Path path = dir.resolve(name);
        try (OutputStream file = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        return path.toString();
    }

    /**
     * {@code aconst_null; getfield owner.name; pop} for each int field, then {@code return}. The
     * scan does not verify, so the null receiver is fine.
     */
    private static ClassWriter readingFields(String name, String owner, String... fieldNames) {
        ClassWriter w = new ClassWriter(name, JAVA_LANG_OBJECT);
        int[] code = new int[fieldNames.length * 5 + 1];
        for (int i = 0; i < fieldNames.length; i++) {
            int ref = w.memberRef(9, owner, fieldNames[i], "I");
            System.arraycopy(new int[] {ACONST_NULL, GETFIELD, ref >>> 8, ref & 0xff, POP}, 0, code, i * 5, 5);
        }
        code[code.length - 1] = RETURN;
        return w.method(Acc.PUBLIC, "read", "()V", code);
    }

    /**
     * The upgrade moved x and y out of lib/C into private fields of two classes old never
     * had: lib/Outer and its nest member lib/Outer$Holder, which lib/C now extends. Pass 1
     * left both NestHosts unread. JVMS 5.4.4 lets another member of lib/Outer's nest keep
     * reading both, while an outside caller now fails with IllegalAccessError.
     */
    @Test
    void privateAccessFollowsTheNestHostReadInPassTwo() throws Exception {
        String libNew = jar(
                "lib-new.jar",
                "lib/C.class", new ClassWriter("lib/C", "lib/Outer$Holder").bytes(),
                "lib/Outer.class",
                new ClassWriter("lib/Outer", JAVA_LANG_OBJECT)
                        .field(Acc.PRIVATE, "y", "I")
                        .nestMembers("lib/Outer$Holder", "lib/Outer$Reader")
                        .bytes(),
                "lib/Outer$Holder.class",
                new ClassWriter("lib/Outer$Holder", "lib/Outer").field(Acc.PRIVATE, "x", "I").nestHost("lib/Outer").bytes(),
                "lib/Outer$Reader.class", readingFields("lib/Outer$Reader", "lib/C", "x", "y").nestHost("lib/Outer").bytes());
        String app = jar("app.jar", "app/Use.class", readingFields("app/Use", "lib/C", "x", "y").bytes());
        ApiIndex oldLib = index(classWithFields("lib/C", m("x", "I", Acc.PUBLIC), m("y", "I", Acc.PUBLIC)));
        List<String> warnings = new ArrayList<>();
        ApiIndex newLib = ApiIndex.fromPaths(List.of(libNew), warnings);
        assertEquals(List.of(), warnings);

        Check.Report report = Check.check(List.of(libNew, app), oldLib, newLib, List.of(libNew));

        assertEquals(List.of(), report.warnings);
        assertEquals(2, report.violations.size());
        for (Violation v : report.violations) {
            assertEquals(app, Intern.str(v.source));
            assertEquals("app/Use", Intern.str(v.sourceClass));
            assertEquals(Reason.FIELD_ACCESS_NARROWED, v.reason);
        }
        assertEquals(fieldRead("lib/C", "x", "I"), report.violations.get(0).reference);
        assertEquals(fieldRead("lib/C", "y", "I"), report.violations.get(1).reference);
        assertEquals(0, report.unknownRefs);
    }

    /**
     * A Spring Boot jar keeps application classes under BOOT-INF/classes, so pass 2 must
     * re-read app/Over from that entry to see that it overrides the now-final m.
     */
    @Test
    void passTwoRereadsAClassFromTheEntryItWasFoundUnder() throws Exception {
        String boot = jar(
                "app-boot.jar",
                "BOOT-INF/classes/app/Over.class", new ClassWriter("app/Over", "lib/M").method(Acc.PUBLIC, "m", "()V", RETURN).bytes());
        ApiIndex oldLib = index(classWithMethodAccess("lib/M", m("m", "()V", Acc.PUBLIC)));
        ApiIndex newLib = index(classWithMethodAccess("lib/M", m("m", "()V", Acc.PUBLIC | Acc.FINAL)));

        Check.Report report = Check.check(List.of(boot), oldLib, newLib, List.of());

        assertEquals(List.of(), report.warnings);
        assertEquals(1, report.violations.size());
        Violation v = report.violations.get(0);
        assertEquals("app/Over", Intern.str(v.sourceClass));
        assertEquals(Reason.METHOD_BECAME_FINAL, v.reason);
        assertEquals(new SymbolRef(RefKind.METHOD, intern("lib/M"), MemberKey.of("m", "()V"), Boolean.FALSE, null, null), v.reference);
    }

    /** The JVM would throw ClassFormatError loading lib/D's new superclass. Here it stays unverified. */
    @Test
    void aSuperclassThatFailsToParseInPassTwoLeavesTheReferenceUnverified() throws Exception {
        String cp = jar(
                "cp.jar",
                "cp/Mid.class", new ClassWriter("cp/Mid", JAVA_LANG_OBJECT).headerOnly(),
                "app/Call.class", callingMethod("app/Call", "lib/D", "m", "()V").bytes());
        ApiIndex oldLib = index(classWithMethodAccess("lib/D", m("m", "()V", Acc.PUBLIC)));
        ApiIndex newLib = index(classApi("lib/D", "cp/Mid", Acc.PUBLIC));

        Check.Report report = Check.check(List.of(cp), oldLib, newLib, List.of());

        assertEquals(List.of(), report.violations);
        assertEquals(1, report.unknownRefs);
        assertEquals(1, report.warnings.size());
        assertTrue(report.warnings.get(0).startsWith(cp + "!cp/Mid: truncated class file"), report.warnings.get(0));
    }

    /** An unreadable jar or class yields no evidence; whatever else was read still counts. */
    @Test
    void libraryEvidenceSurvivesAnUnreadableJarAndAMalformedClass() throws Exception {
        byte[] unknownPoolTag = {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 55, 0, 3, 2, 0, 0, 0, 0};
        String libNew = jar(
                "lib-new.jar",
                "lib/Bad.class", unknownPoolTag,
                "lib/Caller.class", callingMethod("lib/Caller", "lib/Logger", "display", "(Ljava/lang/String;)V").bytes());
        long display = MemberKey.of("display", "(Ljava/lang/String;)V");
        Scan.Invocations invocations = new Scan.Invocations();

        Check.libraryInvocationEvidence(
                List.of(dir.resolve("missing.jar").toString(), libNew), new MemberProbe(new long[] {display}), invocations);

        assertEquals(1, invocations.size());
        assertEquals(List.of(intern("lib/Logger")), Arrays.stream(invocations.ownersOf(display).toArray()).boxed().toList());
        assertNull(invocations.ownersOf(MemberKey.of("call", "()V")));
    }
}
