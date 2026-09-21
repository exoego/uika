package net.exoego.uika.cli;

import static net.exoego.uika.cli.GoldenTest.fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests using real JARs in tests/fixtures/ (vendored from Maven Central, see
 * tests/fixtures/README.md). Two real incidents are used as ground truth.
 *
 * <p>Ground truth 1. BlockingAdapter in ktor-io 2.3.13 binds to
 * EventLoopKt.processNextEventInCurrentThread ()J from kotlinx-coroutines 1.7.1, and that
 * method disappeared in 1.11.0 (causing NoSuchMethodError).
 */
class IntegrationTest {
    private static ApiIndex index(String jar) {
        return ApiIndex.fromPaths(List.of(jar), new ArrayList<>());
    }

    private static String sourceClass(Violation v) {
        return Intern.str(v.sourceClass);
    }

    private static String owner(Violation v) {
        return Intern.str(v.reference.owner());
    }

    /** Null for a class-level reference. */
    private static String memberName(Violation v) {
        return v.reference.hasMember() ? Intern.str(MemberKey.name(v.reference.member())) : null;
    }

    private static boolean memberIs(Violation v, String name, String descriptor) {
        return v.reference.hasMember()
                && Intern.str(MemberKey.name(v.reference.member())).equals(name)
                && Intern.str(MemberKey.descriptor(v.reference.member())).equals(descriptor);
    }

    private static boolean hasClassChange(List<BreakingChange> changes, BreakingChange.Kind kind, String className) {
        for (BreakingChange c : changes) {
            if (c.kind() == kind && Intern.str(c.className()).equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMemberChange(
            List<BreakingChange> changes, BreakingChange.Kind kind, String className, String name, String descriptor) {
        for (BreakingChange c : changes) {
            if (c.kind() == kind
                    && Intern.str(c.className()).equals(className)
                    && Intern.str(c.name()).equals(name)
                    && Intern.str(c.descriptor()).equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    /** Failure-message rendering, standing in for the Rust tests' {@code {:?}}. */
    static String describe(List<Violation> violations) {
        StringBuilder out = new StringBuilder("[");
        for (Violation v : violations) {
            out.append("\n  ").append(Intern.str(v.source)).append(' ').append(sourceClass(v)).append(" -> ").append(owner(v));
            if (v.reference.hasMember()) {
                out.append('.')
                        .append(Intern.str(MemberKey.name(v.reference.member())))
                        .append(' ')
                        .append(Intern.str(MemberKey.descriptor(v.reference.member())));
            }
            out.append(" (").append(v.reason.text).append(')');
            out.append(" reachable=").append(v.reachable).append(" invocationFound=").append(v.invocationFound);
        }
        return out.append("\n]").toString();
    }

    private static List<Violation> withReason(List<Violation> violations, Reason reason) {
        List<Violation> out = new ArrayList<>();
        for (Violation v : violations) {
            if (v.reason == reason) {
                out.add(v);
            }
        }
        return out;
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Test
    void detectsKtorIoBreakAgainstCoroutines111() {
        String oldJar = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
        String newJar = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
        String ktorIo = fixture("ktor-io-jvm-2.3.13.jar");

        List<String> warnings = new ArrayList<>();
        ApiIndex oldIndex = ApiIndex.fromPaths(List.of(oldJar), warnings);
        assertTrue(warnings.isEmpty(), "old jar parse warnings: " + warnings);
        ApiIndex newIndex = ApiIndex.fromPaths(List.of(newJar), warnings);
        assertTrue(warnings.isEmpty(), "new jar parse warnings: " + warnings);

        // The diff detects the original method removal.
        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertTrue(
                hasMemberChange(
                        changes,
                        BreakingChange.Kind.METHOD_REMOVED,
                        "kotlinx/coroutines/EventLoopKt",
                        "processNextEventInCurrentThread",
                        "()J"),
                "EventLoopKt.processNextEventInCurrentThread ()J removal is missing from diff");

        // The check reports the reference from BlockingAdapter as the only violation.
        Check.Report report = Check.check(List.of(ktorIo), oldIndex, newIndex, List.of(newJar));
        assertEquals(1, report.violations.size(), "violations: " + describe(report.violations));
        Violation v = report.violations.get(0);
        assertEquals("io/ktor/utils/io/jvm/javaio/BlockingAdapter", sourceClass(v));
        assertEquals(RefKind.METHOD, v.reference.kind());
        assertEquals("kotlinx/coroutines/EventLoopKt", owner(v));
        assertEquals(Reason.METHOD_REMOVED, v.reason);
    }

    /**
     * Ground truth 2. OTel 1.42 -> 1.60 moved DaemonThreadFactory from
     * io.opentelemetry.sdk.internal to io.opentelemetry.sdk.common.internal. OkHttpUtil in the
     * okhttp sender built against 1.42.1 references the old package, causing
     * NoClassDefFoundError (a real case where Sentry 8.43.2 lifted sdk-common).
     */
    @Test
    void detectsOtelDaemonThreadFactoryPackageMove() {
        String oldJar = fixture("opentelemetry-sdk-common-1.42.1.jar");
        String newJar = fixture("opentelemetry-sdk-common-1.60.1.jar");
        String sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertTrue(
                hasClassChange(changes, BreakingChange.Kind.CLASS_REMOVED, "io/opentelemetry/sdk/internal/DaemonThreadFactory"),
                "DaemonThreadFactory removal is missing from diff");

        Check.Report report = Check.check(List.of(sender), oldIndex, newIndex, List.of(newJar));
        assertEquals(1, report.violations.size(), "violations: " + describe(report.violations));
        Violation v = report.violations.get(0);
        // Matches the top of the real NoClassDefFoundError stack trace.
        assertEquals("io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil", sourceClass(v));
        assertEquals(RefKind.CLASS, v.reference.kind());
        assertEquals("io/opentelemetry/sdk/internal/DaemonThreadFactory", owner(v));
        assertEquals(Reason.CLASS_REMOVED, v.reason);
    }

    /**
     * https://github.com/SeleniumHQ/selenium/issues/4381: Selenium 3.4.0's UrlChecker calls
     * Guava SimpleTimeLimiter's public constructor. Guava 23.0-rc1 made that constructor
     * private, producing IllegalAccessError at runtime.
     */
    @Test
    void detectsSeleniumGuavaConstructorAccessNarrowing() {
        String oldJar = fixture("guava-22.0.jar");
        String newJar = fixture("guava-23.0-rc1.jar");
        String selenium = fixture("selenium-remote-driver-3.4.0.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertTrue(
                hasMemberChange(
                        changes,
                        BreakingChange.Kind.METHOD_ACCESS_NARROWED,
                        "com/google/common/util/concurrent/SimpleTimeLimiter",
                        "<init>",
                        "(Ljava/util/concurrent/ExecutorService;)V"),
                "SimpleTimeLimiter constructor access narrowing is missing from diff");

        Check.Report report = Check.check(List.of(selenium), oldIndex, newIndex, List.of(newJar));
        assertTrue(
                report.violations.stream()
                        .anyMatch(v -> sourceClass(v).equals("org/openqa/selenium/net/UrlChecker")
                                && owner(v).equals("com/google/common/util/concurrent/SimpleTimeLimiter")
                                && memberIs(v, "<init>", "(Ljava/util/concurrent/ExecutorService;)V")
                                && v.reason == Reason.METHOD_ACCESS_NARROWED),
                "violations: " + describe(report.violations));
    }

    /**
     * https://github.com/InsertKoinIO/koin/issues/1489: koin-core 3.3.0 made
     * Logger.log(Level, String) final while koin-logger-slf4j 3.2.2 still overrides it,
     * producing IncompatibleClassChangeError.
     */
    @Test
    void detectsKoinLoggerFinalMethodOverride() {
        String oldJar = fixture("koin-core-jvm-3.2.2.jar");
        String newJar = fixture("koin-core-jvm-3.3.0.jar");
        String logger = fixture("koin-logger-slf4j-3.2.2.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertTrue(
                hasMemberChange(
                        changes,
                        BreakingChange.Kind.METHOD_BECAME_FINAL,
                        "org/koin/core/logger/Logger",
                        "log",
                        "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V"),
                "Logger.log final addition is missing from diff");

        Check.Report report = Check.check(List.of(logger), oldIndex, newIndex, List.of(newJar));
        assertTrue(
                report.violations.stream()
                        .anyMatch(v -> sourceClass(v).equals("org/koin/logger/SLF4JLogger")
                                && owner(v).equals("org/koin/core/logger/Logger")
                                && memberIs(v, "log", "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V")
                                && v.reason == Reason.METHOD_BECAME_FINAL),
                "violations: " + describe(report.violations));
    }

    /**
     * The invoked side on real jars. koin-core 3.3.0 renamed abstract {@code Logger.log} to
     * {@code display}, and koin-core's own code calls it, so the caller lives in the library
     * rather than in the consumer jar. Dropping the library paths makes this a false latent.
     */
    @Test
    void koinAbstractBreakIsInvocableViaTheLibrarysOwnCall() {
        String oldJar = fixture("koin-core-jvm-3.2.2.jar");
        String newJar = fixture("koin-core-jvm-3.3.0.jar");
        String logger = fixture("koin-logger-slf4j-3.2.2.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        Function<List<String>, Boolean> abstractBreak = library -> {
            Check.Report report = Check.check(List.of(logger), oldIndex, newIndex, library);
            for (Violation v : report.violations) {
                if (v.reason == Reason.METHOD_BECAME_ABSTRACT && "display".equals(memberName(v))) {
                    return v.invocationFound;
                }
            }
            throw new AssertionError("the display() AbstractMethodError break must be reported");
        };

        assertEquals(Boolean.TRUE, abstractBreak.apply(List.of(newJar)));
        assertEquals(Boolean.FALSE, abstractBreak.apply(List.of()));
    }

    /**
     * {@code listener::end} compiles to invokedynamic plus a MethodHandle constant, so no
     * invoke opcode names the member. Narrowing evidence collection to code references would
     * file this invocable break as latent and let {@code --fail-on reachable} pass.
     */
    @Test
    void methodReferenceOnlyCallSiteCountsAsInvocation() {
        String oldJar = fixture("synthetic-abstract-added-1.0.jar");
        String newJar = fixture("synthetic-abstract-added-2.0.jar");
        String consumer = fixture("synthetic-abstract-added-consumer.jar");
        String caller = fixture("synthetic-abstract-added-methodref-caller.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        Function<List<String>, Boolean> found = targets -> {
            Check.Report report = Check.check(targets, oldIndex, newIndex, List.of(newJar));
            for (Violation v : report.violations) {
                if (v.reason == Reason.METHOD_BECAME_ABSTRACT) {
                    return v.invocationFound;
                }
            }
            throw new AssertionError("BrokenTranslator must inherit the unimplemented end()");
        };

        assertEquals(Boolean.FALSE, found.apply(List.of(consumer)));
        assertEquals(Boolean.TRUE, found.apply(List.of(consumer, caller)));
    }

    /**
     * A real JVM confirms the fixture. Loading Square against 2.0 throws
     * {@code IncompatibleClassChangeError: class fixture.app.Square cannot implement sealed
     * interface fixture.lib.Shape}, while Marker (implementing the untouched Tagged) loads.
     * Not probeable, since {@code MethodHandles.Lookup} models member resolution and this
     * break happens at class load.
     */
    @Test
    void detectsAConsumerSubclassOfANewlySealedInterface() {
        String oldJar = fixture("synthetic-sealed-1.0.jar");
        String newJar = fixture("synthetic-sealed-2.0.jar");
        String consumer = fixture("synthetic-sealed-consumer.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        Check.Report report = Check.check(List.of(consumer), oldIndex, newIndex, List.of(newJar));
        List<Violation> sealed = withReason(report.violations, Reason.CLASS_BECAME_SEALED);
        assertEquals(1, sealed.size(), describe(report.violations));
        assertEquals("fixture/app/Square", sourceClass(sealed.get(0)));
        assertEquals("fixture/lib/Shape", owner(sealed.get(0)));

        assertTrue(hasClassChange(Diff.diff(oldIndex, newIndex), BreakingChange.Kind.CLASS_BECAME_SEALED, "fixture/lib/Shape"));
    }

    /**
     * A real JVM confirms which error the fixture throws, and that it depends on the call
     * site. {@code invokevirtual Conflicted.n()} throws {@code IncompatibleClassChangeError:
     * Conflicting default methods: fixture/lib/A.n fixture/lib/B.n}, while
     * {@code invokeinterface A.n()} on the same receiver throws {@code AbstractMethodError}.
     * Both are LinkageErrors and the report names both. Overriding declares its own n() and
     * stays unreported under either form.
     */
    @Test
    void detectsADefaultMethodConflictFromANewlyAddedDefault() {
        String oldJar = fixture("synthetic-default-conflict-1.0.jar");
        String newJar = fixture("synthetic-default-conflict-2.0.jar");
        String consumer = fixture("synthetic-default-conflict-consumer.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        Check.Report report = Check.check(List.of(consumer), oldIndex, newIndex, List.of(newJar));
        assertEquals(1, report.violations.size(), describe(report.violations));
        Violation v = report.violations.get(0);
        assertEquals(Reason.CONFLICTING_DEFAULT_METHODS, v.reason);
        assertEquals("fixture/app/Conflicted", sourceClass(v));
        assertEquals("n", memberName(v));
        // fixture.app.Caller calls A.n(), which dispatches onto Conflicted.
        assertEquals(Boolean.TRUE, v.invocationFound);

        // Adding a default method is not itself an API break, so the diff stays quiet.
        assertTrue(Diff.diff(oldIndex, newIndex).isEmpty());
    }

    /**
     * Synthetic triple minimizing a realistic-but-unpublished move. jackson-module-kotlin
     * registers {@code KotlinModule} in
     * {@code META-INF/services/com.fasterxml.jackson.databind.Module}, and the SAME vendored
     * pair (2.18.2 -> 2.20.1) really did make a class abstract ({@code ValueClassBoxConverter},
     * the {@code new}-on-abstract fixture). This triple is that move landing on the registered
     * provider itself. No published pair does it yet, so per the fixture policy it is authored
     * and JVM-confirmed (fixtures/README.md). The consumer's
     * {@code ServiceLoader.load(Spi.class)} loop throws {@code ... Provider fixture.lib.Impl
     * could not be instantiated} under 2.0. No other check can see it, because the consumer's
     * bytecode never names Impl. Uses {@code Commands.runCheck} because only the path-based
     * entry points read META-INF/services (so no golden covers this, see AGENTS.md "SPI
     * provider breaks"). The new JAR is a scan target, which gives the reachability walk the
     * Spi -> Impl provider edge and proves the violation reachable.
     */
    @Test
    void detectsAProviderThatBecameAbstract() {
        String oldJar = fixture("synthetic-spi-1.0.jar");
        String newJar = fixture("synthetic-spi-2.0.jar");
        String consumer = fixture("synthetic-spi-consumer.jar");

        Check.Report report = Commands.runCheck(
                List.of(oldJar), List.of(newJar), List.of(newJar, consumer), List.of(consumer), List.of(), null, null);

        assertEquals(1, report.violations.size(), describe(report.violations));
        Violation v = report.violations.get(0);
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, v.reason);
        assertEquals("fixture/lib/Impl", sourceClass(v));
        assertEquals("fixture/lib/Spi", owner(v));
        assertFalse(v.reference.hasMember());
        assertEquals(
                Boolean.TRUE,
                v.reachable,
                "the consumer's own ServiceLoader.load(Spi.class) call makes Spi (and its registered provider) reachable");
    }

    /**
     * When the new jar is NOT a scan target the provider is invisible to the reachability
     * BFS, so its violation must stay unranked (null), never "proven unreachable". A FALSE
     * would let {@code --fail-on reachable} pass on a JVM-confirmed break.
     */
    @Test
    void anUnscannedProviderIsNotProvenUnreachable() {
        String oldJar = fixture("synthetic-spi-1.0.jar");
        String newJar = fixture("synthetic-spi-2.0.jar");
        String consumer = fixture("synthetic-spi-consumer.jar");

        Check.Report report =
                Commands.runCheck(List.of(oldJar), List.of(newJar), List.of(consumer), List.of(consumer), List.of(), null, null);

        assertEquals(1, report.violations.size(), describe(report.violations));
        Violation v = report.violations.get(0);
        assertEquals(Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE, v.reason);
        assertNull(v.reachable, String.valueOf(v.reachable));
    }

    /**
     * An SPI violation's source is the upgraded library's own jar, so mapping it through the
     * coordinate table would advise aligning the coordinate with itself. It must stay
     * unannotated instead.
     */
    @Test
    void spiViolationGetsNoSelfReferentialSuggestion(@TempDir Path dir) throws Exception {
        String oldJar = fixture("synthetic-spi-1.0.jar");
        String newJar = fixture("synthetic-spi-2.0.jar");
        String consumer = fixture("synthetic-spi-consumer.jar");

        BiFunction<String, String, String> dump = (version, file) -> """
                {"modules":[{"module":":app","classesDirs":[],"artifacts":[
                    {"group":"fixture","name":"spi","version":"%s","file":"%s"},
                    {"group":"fixture","name":"consumer","version":"1.0","file":"%s"}
                ]}]}"""
                .formatted(version, file, consumer);
        Path beforePath = dir.resolve("before.json");
        Path afterPath = dir.resolve("after.json");
        write(beforePath, dump.apply("1.0", oldJar));
        write(afterPath, dump.apply("2.0", newJar));

        Dump.Universe before = Dump.loadDump(beforePath.toString());
        Dump.Universe after = Dump.loadDump(afterPath.toString());
        Dump.DependencyChanges changes = Dump.diffDumps(before, after);
        Check.Report report =
                Commands.runCheck(changes.oldJars(), changes.newJars(), after.scanTargets, after.appRoots, List.of(), null, null);
        Suggest.annotate(report.violations, before, after, changes.changes());

        Violation v = report.violations.stream()
                .filter(x -> x.reason == Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("SPI violation expected"));
        assertNull(v.suggestion, String.valueOf(v.suggestion));
    }

    /**
     * Kotest 6 turned kotest-runner-junit5-jvm into a relocation shim whose only content is
     * {@code META-INF/services/org.junit.platform.engine.TestEngine}, still naming
     * {@code KotestJunitPlatformTestEngine}. The class moved to
     * kotest-runner-junit-platform-jvm. On a classpath without that sibling, JUnit engine
     * discovery throws {@code ServiceConfigurationError: Provider ... not found}
     * (JVM-confirmed both ways, fixtures/README.md). This is the real-pair cover for the
     * removed arm, next to the synthetic not-instantiable one above.
     */
    @Test
    void detectsKotestStaleEngineRegistrationInTheRelocationShim() {
        String oldJar = fixture("kotest-runner-junit5-jvm-5.9.1.jar");
        String newJar = fixture("kotest-runner-junit5-jvm-6.2.3.jar");
        String platform = fixture("junit-platform-engine-1.9.3.jar");

        Check.Report report =
                Commands.runCheck(List.of(oldJar), List.of(newJar), List.of(newJar, platform), List.of(), List.of(), null, null);

        assertEquals(1, report.violations.size(), describe(report.violations));
        Violation v = report.violations.get(0);
        assertEquals(Reason.SERVICE_PROVIDER_REMOVED, v.reason);
        assertEquals("io/kotest/runner/junit/platform/KotestJunitPlatformTestEngine", sourceClass(v));
        assertEquals("org/junit/platform/engine/TestEngine", owner(v));
        assertFalse(v.reference.hasMember());
    }

    /**
     * sshd-core 2.2.0's module split moved {@code RootedFileSystemProvider} to the new
     * sshd-common artifact but left
     * {@code META-INF/services/java.nio.file.spi.FileSystemProvider} behind, stale until 2.7.0
     * moved the file too (https://github.com/apache/mina-sshd/commit/23773e383221, with the
     * fallout at https://issues.apache.org/jira/browse/MCOMPILER-436). Without sshd-common on
     * the classpath, touching {@code FileSystems}/{@code Paths} throws
     * {@code ServiceConfigurationError} (JVM-confirmed, fixtures/README.md). The service is a
     * JDK class outside every scope, but the provider names it as its DIRECT superclass, so
     * reaching the target name on the walk proves the old side without resolving the JDK
     * class, and no {@code --jdk-release} is needed. The three {@code class removed}
     * violations are the same split seen by the ordinary reference check. On this curated
     * classpath both faces of the incident surface together.
     */
    @Test
    void detectsSshdsStaleFileSystemProviderRegistration() {
        String oldJar = fixture("sshd-core-2.1.0.jar");
        String newJar = fixture("sshd-core-2.2.0.jar");

        Check.Report report = Commands.runCheck(List.of(oldJar), List.of(newJar), List.of(newJar), List.of(), List.of(), null, null);

        assertEquals(4, report.violations.size(), describe(report.violations));
        List<Violation> spi = withReason(report.violations, Reason.SERVICE_PROVIDER_REMOVED);
        assertEquals(1, spi.size(), describe(report.violations));
        assertEquals("org/apache/sshd/common/file/root/RootedFileSystemProvider", sourceClass(spi.get(0)));
        assertEquals("java/nio/file/spi/FileSystemProvider", owner(spi.get(0)));
        assertEquals(3, withReason(report.violations, Reason.CLASS_REMOVED).size(), describe(report.violations));
    }

    /**
     * https://github.com/rburgst/okhttp-digest/issues/57: okhttp-digest 1.x calls
     * RequestLine.requestPath as a static OkHttp 3 method. OkHttp 4.0.x changed RequestLine
     * into a Kotlin object, making requestPath an instance method and producing
     * IncompatibleClassChangeError.
     */
    @Test
    void detectsOkhttpDigestStaticToInstanceChange() {
        String oldJar = fixture("okhttp-3.14.1.jar");
        String newJar = fixture("okhttp-4.0.1.jar");
        String digest = fixture("okhttp-digest-1.21.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertTrue(
                hasMemberChange(
                        changes,
                        BreakingChange.Kind.METHOD_BECAME_INSTANCE,
                        "okhttp3/internal/http/RequestLine",
                        "requestPath",
                        "(Lokhttp3/HttpUrl;)Ljava/lang/String;"),
                "RequestLine.requestPath static-to-instance change is missing from diff");

        Check.Report report = Check.check(List.of(digest), oldIndex, newIndex, List.of(newJar));
        assertTrue(
                report.violations.stream()
                        .anyMatch(v -> sourceClass(v).equals("com/burgstaller/okhttp/digest/DigestAuthenticator")
                                && owner(v).equals("okhttp3/internal/http/RequestLine")
                                && memberIs(v, "requestPath", "(Lokhttp3/HttpUrl;)Ljava/lang/String;")
                                && v.reason == Reason.METHOD_BECAME_INSTANCE),
                "violations: " + describe(report.violations));
    }

    private static String otelDump(String version, String sdkCommon, String sender) {
        return """
                {"modules":[{"module":":app","classesDirs":[],"artifacts":[
                    {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"%s","file":"%s"},
                    {"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"%s"}
                ]}]}"""
                .formatted(version, sdkCommon, sender);
    }

    /**
     * Gradle integration. Reproduces the OTel incident (only sdk-common lifted) from
     * before/after resolved classpath dumps.
     */
    @Test
    void upgradeCheckReproducesOtelIncidentFromDumps(@TempDir Path dir) throws Exception {
        String oldSc = fixture("opentelemetry-sdk-common-1.42.1.jar");
        String newSc = fixture("opentelemetry-sdk-common-1.60.1.jar");
        String sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

        Path beforePath = dir.resolve("before.json");
        Path afterPath = dir.resolve("after.json");
        write(beforePath, otelDump("1.42.1", oldSc, sender));
        write(afterPath, otelDump("1.60.1", newSc, sender));

        Dump.Universe before = Dump.loadDump(beforePath.toString());
        Dump.Universe after = Dump.loadDump(afterPath.toString());
        Dump.DependencyChanges changes = Dump.diffDumps(before, after);
        assertEquals(1, changes.changes().size());
        assertEquals("io.opentelemetry:opentelemetry-sdk-common", changes.changes().get(0).coordinate());
        assertEquals(List.of(oldSc), changes.oldJars());
        assertEquals(List.of(newSc), changes.newJars());

        Check.Report report =
                Commands.runCheck(changes.oldJars(), changes.newJars(), after.scanTargets, after.appRoots, List.of(), null, null);
        assertEquals(1, report.violations.size(), "violations: " + describe(report.violations));
        Violation v = report.violations.get(0);
        assertEquals("io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil", sourceClass(v));
        assertEquals("io/opentelemetry/sdk/internal/DaemonThreadFactory", owner(v));
    }

    /**
     * upgrade-check attributes the DaemonThreadFactory break to the two artifacts involved.
     * They are the referencing sender JAR and the sdk-common coordinate whose bump removed the
     * class.
     */
    @Test
    void upgradeCheckSuggestionAttributesTheBreak(@TempDir Path dir) throws Exception {
        String oldSc = fixture("opentelemetry-sdk-common-1.42.1.jar");
        String newSc = fixture("opentelemetry-sdk-common-1.60.1.jar");
        String sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

        Path beforePath = dir.resolve("before.json");
        Path afterPath = dir.resolve("after.json");
        write(beforePath, otelDump("1.42.1", oldSc, sender));
        write(afterPath, otelDump("1.60.1", newSc, sender));

        Dump.Universe before = Dump.loadDump(beforePath.toString());
        Dump.Universe after = Dump.loadDump(afterPath.toString());
        Dump.DependencyChanges changes = Dump.diffDumps(before, after);
        Check.Report report =
                Commands.runCheck(changes.oldJars(), changes.newJars(), after.scanTargets, after.appRoots, List.of(), null, null);
        Suggest.annotate(report.violations, before, after, changes.changes());

        Suggestion s = report.violations.get(0).suggestion;
        assertNotNull(s, "violation should carry a suggestion");
        assertEquals("io.opentelemetry:opentelemetry-exporter-sender-okhttp:1.42.1", s.referencedBy());
        assertEquals("io.opentelemetry:opentelemetry-sdk-common", s.removedBy());
        assertEquals("1.42.1", s.before());
        assertEquals("1.60.1", s.after());
        // Same group -> advice leads with alignment.
        assertTrue(s.advice().startsWith("align all io.opentelemetry artifacts"), "advice: " + s.advice());
    }

    /**
     * The optional rewording end to end, which is the only thing that exercises the
     * annotator's POM lookup from a violation. The unit tests either feed {@code build()} a
     * literal flag or call {@code Pom} directly. The sender JAR is copied into a Maven-shaped
     * layout so a POM can sit beside it without polluting tests/fixtures.
     */
    @Test
    void upgradeCheckSuggestionReadsOptionalFromTheReferencersPom(@TempDir Path dir) throws Exception {
        String sdkCommon = fixture("opentelemetry-sdk-common-1.42.1.jar");
        Path cache = dir.resolve("io/opentelemetry/opentelemetry-exporter-sender-okhttp/1.42.1");
        Files.createDirectories(cache);
        Path sender = cache.resolve("opentelemetry-exporter-sender-okhttp-1.42.1.jar");
        Files.copy(Path.of(fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar")), sender);

        // The upgrade drops sdk-common entirely, so the removed-coordinate advice applies.
        Function<Boolean, String> dump = isBefore -> {
            String sdk = isBefore
                    ? """
                    {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"1.42.1","file":"%s"},"""
                            .formatted(sdkCommon)
                    : "";
            return """
                    {"modules":[{"module":":app","classesDirs":[],"artifacts":[%s
                        {"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"%s"}
                    ]}]}"""
                    .formatted(sdk, sender);
        };
        write(dir.resolve("before.json"), dump.apply(true));
        write(dir.resolve("after.json"), dump.apply(false));

        Function<String, String> advice = pom -> {
            Path pomPath = cache.resolve("opentelemetry-exporter-sender-okhttp-1.42.1.pom");
            try {
                if (pom != null) {
                    write(pomPath, pom);
                } else {
                    Files.deleteIfExists(pomPath);
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            Dump.Universe before = Dump.loadDump(dir.resolve("before.json").toString());
            Dump.Universe after = Dump.loadDump(dir.resolve("after.json").toString());
            Dump.DependencyChanges changes = Dump.diffDumps(before, after);
            Check.Report report = Commands.runCheck(
                    changes.oldJars(), changes.newJars(), after.scanTargets, after.appRoots, List.of(), null, null);
            Suggest.annotate(report.violations, before, after, changes.changes());
            Suggestion s = report.violations.get(0).suggestion;
            assertNotNull(s, "violation should carry a suggestion");
            return s.advice();
        };

        String optionalPom = """
                <project><dependencies><dependency>
                    <groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk-common</artifactId>
                    <version>1.42.1</version><optional>true</optional>
                  </dependency></dependencies></project>""";
        assertTrue(
                advice.apply(optionalPom).contains("declares it optional"),
                "POM declaring it optional should reword the advice");
        // Same dumps with the POM gone. Every failure path falls back to the original wording.
        assertTrue(advice.apply(null).contains("still needs it"), "a missing POM must not reword the advice");
        String requiredPom = optionalPom.replace("<optional>true</optional>", "");
        assertTrue(advice.apply(requiredPom).contains("still needs it"), "a required declaration must not reword the advice");
    }

    /**
     * A coordinate rename (publishing the same library under a dev coordinate). The old
     * coordinate is REMOVED with no new-side pair, and the identical JAR re-enters as a plain
     * scan target. Its nest-internal private references (Java 11+ nestmates, e.g. anonymous
     * enum bodies calling the private enum constructor) resolve as private against both sides.
     * That is pre-existing, not access narrowing. Before the old-relative gate this reported
     * 13 false "access narrowed" violations from caffeine alone.
     */
    @Test
    void coordinateRenameOfIdenticalJarReportsNothing(@TempDir Path dir) throws Exception {
        String caffeine = fixture("caffeine-3.2.3.jar");

        Path copy = dir.resolve("caffeine-dev-abc123.jar");
        Files.copy(Path.of(caffeine), copy);
        Function<String[], String> dump = a -> """
                {"modules":[{"module":":app","classesDirs":[],"artifacts":[
                    {"group":"com.github.ben-manes.caffeine","name":"%s","version":"%s","file":"%s"}
                ]}]}"""
                .formatted(a[0], a[1], a[2]);
        Path beforePath = dir.resolve("before.json");
        Path afterPath = dir.resolve("after.json");
        write(beforePath, dump.apply(new String[] {"caffeine", "3.2.3", caffeine}));
        write(afterPath, dump.apply(new String[] {"caffeine-dev", "abc123", copy.toString()}));

        Dump.Universe before = Dump.loadDump(beforePath.toString());
        Dump.Universe after = Dump.loadDump(afterPath.toString());
        Dump.DependencyChanges changes = Dump.diffDumps(before, after);
        assertEquals(List.of(caffeine), changes.oldJars());
        assertTrue(changes.newJars().isEmpty());

        Check.Report report =
                Commands.runCheck(changes.oldJars(), changes.newJars(), after.scanTargets, after.appRoots, List.of(), null, null);
        assertTrue(report.violations.isEmpty(), "violations: " + describe(report.violations));
    }

    /**
     * The ktor-io / coroutines break (see detectsKtorIoBreakAgainstCoroutines111). The same
     * violation is reachable when the referencing JAR is an application root, and not proven
     * reachable when the only root is an unrelated JAR that never references it. ktor-io has
     * no service providers, so BlockingAdapter is only reachable through an explicit root.
     */
    @Test
    void reachabilityTiersViolationByAppRoots() {
        String oldJar = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
        String newJar = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
        String ktorIo = fixture("ktor-io-jvm-2.3.13.jar");
        // A real, scanned root unrelated to ktor/coroutines, so it never reaches BlockingAdapter.
        String unrelated = fixture("koin-logger-slf4j-3.2.2.jar");

        Check.Report reachable =
                Commands.runCheck(List.of(oldJar), List.of(newJar), List.of(ktorIo), List.of(ktorIo), List.of(), null, null);
        assertEquals(1, reachable.violations.size());
        assertEquals(
                Boolean.TRUE,
                reachable.violations.get(0).reachable,
                "referencing JAR as an app root should make the violation reachable");

        Check.Report unreachable = Commands.runCheck(
                List.of(oldJar), List.of(newJar), List.of(ktorIo, unrelated), List.of(unrelated), List.of(), null, null);
        assertEquals(1, unreachable.violations.size());
        assertEquals(
                Boolean.FALSE,
                unreachable.violations.get(0).reachable,
                "a root that never references BlockingAdapter should leave it not proven reachable");
    }

    // ---- real JVM helpers ----

    private record Exec(int code, String output) {}

    /**
     * Runs a program to completion with stdin closed. The output is stdout alone, or stdout
     * and stderr interleaved when {@code mergeStderr} is set. Null when it cannot be started.
     */
    private static Exec exec(boolean mergeStderr, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (mergeStderr) {
            builder.redirectErrorStream(true);
        } else {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Exec(process.waitFor(), output);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void run(String program, String... args) {
        List<String> command = new ArrayList<>();
        command.add(program);
        command.addAll(List.of(args));
        Exec out = exec(true, command);
        assertNotNull(out, "cannot start " + program);
        assertEquals(0, out.code(), program + " " + List.of(args) + " failed:\n" + out.output());
    }

    private record JavaTool(String java, int feature) {}

    /**
     * Locates a JVM and its feature release. JAVA_HOME first, then PATH, then the repo's
     * pinned toolchain via {@code mise where java}, the same convention findCtSymForTest falls
     * back to, so the guard tests do not silently skip on a machine where mise alone provides
     * java. They guard parser-vs-real-format drift, and promote-only means its loss has no
     * other symptom. The JVM running the tests comes last, which the Rust tests do not have.
     * Null when absent or the {@code --version} line does not parse.
     */
    private static JavaTool findJava() {
        List<String> candidates = new ArrayList<>();
        String home = System.getenv("JAVA_HOME");
        if (home != null) {
            candidates.add(Path.of(home, "bin", "java").toString());
        }
        candidates.add("java");
        Exec mise = exec(false, List.of("mise", "where", "java"));
        if (mise != null && mise.code() == 0) {
            candidates.add(Path.of(mise.output().trim(), "bin", "java").toString());
        }
        candidates.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String java : candidates) {
            Exec version = exec(false, List.of(java, "--version"));
            if (version == null || version.code() != 0) {
                continue;
            }
            // First line reads like "openjdk 21.0.11 2026-04-21 LTS".
            String[] words = version.output().trim().split("\\s+");
            if (words.length < 2) {
                continue;
            }
            try {
                return new JavaTool(java, Integer.parseInt(words[1].split("\\.", -1)[0]));
            } catch (NumberFormatException e) {
                // not a version line, try the next candidate
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    /**
     * The CURRENT (pre-upgrade) classpath, exactly the collection workflow. coroutines 1.7.1
     * still has the method, so BlockingAdapter loads cleanly while the evidence is written.
     * kotlin-stdlib is vendored in fixtures for the probe already.
     */
    private static String preUpgradeClasspath() {
        return String.join(
                ":",
                fixture("ktor-io-jvm-2.3.13.jar"),
                fixture("kotlin-stdlib-2.2.20.jar"),
                fixture("kotlinx-coroutines-core-jvm-1.7.1.jar"));
    }

    /**
     * The coroutines-pair check whose one violation starts unproven (the only app root never
     * references BlockingAdapter), shared by the class-load-log tests. Same shape as
     * reachabilityTiersViolationByAppRoots.
     */
    private static Check.Report oneUnprovenBlockingAdapterReport() {
        String oldJar = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
        String newJar = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
        String unrelated = fixture("koin-logger-slf4j-3.2.2.jar");
        List<String> targets = List.of(fixture("ktor-io-jvm-2.3.13.jar"), unrelated);
        Check.Report report =
                Commands.runCheck(List.of(oldJar), List.of(newJar), targets, List.of(unrelated), List.of(), null, null);
        assertEquals(1, report.violations.size());
        assertEquals(Boolean.FALSE, report.violations.get(0).reachable);
        return report;
    }

    /**
     * True end to end over a real JVM. The class-load log is EMITTED by {@code java -Xlog},
     * never hand-written, so drift between real unified-logging output and the evidence parser
     * fails here. Promote-only means a parser that matches nothing has no symptom beyond
     * silently promoting nothing. On JDK 22+ the {@code class+load+cause} variant is exercised
     * too (https://bugs.openjdk.org/browse/JDK-8193513). Real output from it found two bugs
     * the synthetic tests missed (monitor annotations read as the end of a stack, and a custom
     * loader's frame chosen as the trigger). Skipped without a usable JVM, and on Windows
     * (classpath separators and -Xlog file quoting differ there).
     */
    @Test
    void aRealJvmEmittedClassLoadLogPromotesTheViolation(@TempDir Path dir) throws Exception {
        assumeFalse(isWindows(), "classpath separators and -Xlog file quoting differ on Windows");
        JavaTool tool = findJava();
        assumeTrue(tool != null, "no usable java on JAVA_HOME, PATH, or mise");
        int feature = tool.feature();
        assumeTrue(feature >= 11, "the source-file launcher needs JDK 11+ (found " + feature + ")");

        Path runner = dir.resolve("LoadIt.java");
        write(runner, """
                public class LoadIt {
                    public static void main(String[] args) throws Exception {
                        Class.forName(args[0], false, LoadIt.class.getClassLoader());
                    }
                }
                """);
        String classpath = preUpgradeClasspath();
        Function<List<String>, Void> emit = xlogArgs -> {
            List<String> args = new ArrayList<>(xlogArgs);
            args.addAll(List.of("-cp", classpath, runner.toString(), "io.ktor.utils.io.jvm.javaio.BlockingAdapter"));
            run(tool.java(), args.toArray(new String[0]));
            return null;
        };
        Path log = dir.resolve("class-load.log");
        emit.apply(List.of("-Xlog:class+load=info:file=" + log));

        Check.Report report = oneUnprovenBlockingAdapterReport();

        Evidence.LoadEvidence evidence = Evidence.load(List.of(log.toString()));
        Evidence.apply(report.violations, evidence);
        assertTrue(
                report.violations.get(0).observedLoading,
                "real -Xlog output did not register BlockingAdapter as loaded (" + evidence.distinctClasses()
                        + " distinct classes parsed from it)");
        String text = Report.checkText(report);
        assertTrue(text.contains("💥 1 reachable"), "not promoted:\n" + text);
        assertTrue(
                text.contains(
                        "io.ktor.utils.io.jvm.javaio.BlockingAdapter  (ktor-io-jvm-2.3.13.jar)  ⚡ observed loading at runtime"),
                "missing the observed marker:\n" + text);

        if (feature >= 22) {
            Path causeLog = dir.resolve("cause.log");
            emit.apply(List.of(
                    "-Xlog:class+load+cause=info:file=" + causeLog,
                    "-XX:LogClassLoadingCauseFor=io.ktor.utils.io.jvm.javaio.BlockingAdapter"));
            Evidence.LoadEvidence causeEvidence = Evidence.load(List.of(causeLog.toString()));
            Evidence.apply(report.violations, causeEvidence);
            String trigger = report.violations.get(0).loadTrigger;
            trigger = trigger == null ? "" : trigger;
            assertTrue(
                    trigger.startsWith("java.lang.Class.forName") && trigger.contains(" from LoadIt.main"),
                    "unexpected trigger from a real cause stack: " + trigger);
            text = Report.checkText(report);
            assertTrue(
                    text.contains("⚡ observed loading at runtime (via java.lang.Class.forName"),
                    "missing the trigger in the report:\n" + text);
        } else {
            System.err.println("note: class+load+cause half skipped (JDK " + feature + " < 22)");
        }
    }

    /**
     * The JFR cross-language contract, closed end to end. A REAL recording made by this test,
     * converted by the REAL plugin-side converter (jvm-plugin-core's JfrEvidence, compiled
     * here from its source), parsed by this CLI's evidence reader, must promote the unproven
     * violation with a trigger composed from the JFR stack. The plugin suites pin what the
     * converter emits and the evidence unit tests pin what the parser accepts, but only a
     * test running both halves fails when the two drift apart. Promote-only evidence would
     * otherwise hide that drift as silently promoting nothing. Skipped without a JDK (javac
     * is needed, and the converter targets release 17) and on Windows like its -Xlog sibling.
     */
    @Test
    void aJfrRecordingConvertedByThePluginConverterPromotesTheViolation(@TempDir Path dir) throws Exception {
        assumeFalse(isWindows(), "classpath separators differ on Windows");
        JavaTool tool = findJava();
        assumeTrue(tool != null, "no usable java on JAVA_HOME, PATH, or mise");
        int feature = tool.feature();
        assumeTrue(feature >= 17, "the converter is compiled for release 17 (found " + feature + ")");
        // javac sits next to java (a bare "java" from PATH turns into a bare "javac").
        String javac = Path.of(tool.java()).resolveSibling("javac").toString();
        Exec javacVersion = exec(false, List.of(javac, "--version"));
        assumeTrue(javacVersion != null && javacVersion.code() == 0, "no javac next to " + tool.java());

        // A real recording of the CURRENT (pre-upgrade) classpath loading BlockingAdapter.
        Path recorder = dir.resolve("RecordIt.java");
        write(recorder, """
                import jdk.jfr.Recording;
                public class RecordIt {
                    public static void main(String[] args) throws Exception {
                        try (Recording r = new Recording()) {
                            r.enable("jdk.ClassLoad").withStackTrace().withoutThreshold();
                            r.start();
                            Class.forName(args[1], false, RecordIt.class.getClassLoader());
                            r.stop();
                            r.dump(java.nio.file.Path.of(args[0]));
                        }
                    }
                }
                """);
        Path jfr = dir.resolve("rec.jfr");
        run(
                tool.java(),
                "-cp",
                preUpgradeClasspath(),
                recorder.toString(),
                jfr.toString(),
                "io.ktor.utils.io.jvm.javaio.BlockingAdapter");

        // The REAL converter, compiled from the plugin-core source it ships as. Tests run
        // from cli/, so the repository root is one level up.
        Path converterSource = Path.of("../jvm-plugin-core/src/main/java/net/exoego/uika/plugin/core/JfrEvidence.java");
        Path classes = dir.resolve("classes");
        run(javac, "-d", classes.toString(), converterSource.toString());
        Path convert = dir.resolve("Convert.java");
        write(convert, """
                public class Convert {
                    public static void main(String[] args) throws Exception {
                        long events = net.exoego.uika.plugin.core.JfrEvidence.convert(
                                java.nio.file.Path.of(args[0]), java.nio.file.Path.of(args[1]));
                        if (events == 0) {
                            throw new IllegalStateException("no jdk.ClassLoad events converted");
                        }
                    }
                }
                """);
        Path converted = dir.resolve("rec-converted.log");
        run(tool.java(), "-cp", classes.toString(), convert.toString(), jfr.toString(), converted.toString());

        Check.Report report = oneUnprovenBlockingAdapterReport();
        Evidence.LoadEvidence evidence = Evidence.load(List.of(converted.toString()));
        Evidence.apply(report.violations, evidence);
        assertTrue(
                report.violations.get(0).observedLoading,
                "the converted recording did not register BlockingAdapter (" + evidence.distinctClasses()
                        + " distinct classes)");
        String trigger = report.violations.get(0).loadTrigger;
        trigger = trigger == null ? "" : trigger;
        assertTrue(
                trigger.startsWith("java.lang.Class.forName") && trigger.contains(" from RecordIt.main("),
                "unexpected trigger from the JFR stack: " + trigger);
        String text = Report.checkText(report);
        assertTrue(text.contains("💥 1 reachable"), "not promoted:\n" + text);
        assertTrue(
                text.contains("⚡ observed loading at runtime (via java.lang.Class.forName"),
                "missing the trigger in the report:\n" + text);
    }

    /** Stands in for the Rust tests' {@code violations.clone()}. */
    private static List<Violation> copyOf(List<Violation> violations) {
        List<Violation> out = new ArrayList<>();
        for (Violation v : violations) {
            Violation copy = new Violation(v.source, v.sourceClass, v.reference, v.reason);
            copy.reachable = v.reachable;
            copy.invocationFound = v.invocationFound;
            copy.observedLoading = v.observedLoading;
            copy.loadTrigger = v.loadTrigger;
            copy.suggestion = v.suggestion;
            copy.modules = new ArrayList<>(v.modules);
            out.add(copy);
        }
        return out;
    }

    /**
     * The base-branch-artifact workflow end to end. A class-load log from a test run of the
     * current build names BlockingAdapter, so the unproven violation is promoted (observed
     * loading, Breaks tier for the gate) and nothing is drafted for exclusion. Without the log
     * entry the violation stays unproven and --draft-exclude-file writes a REVIEW entry that
     * the real exclude parser accepts.
     */
    @Test
    void classLoadLogPromotesTheUnprovenViolationAndGatesDrafts(@TempDir Path dir) throws Exception {
        Check.Report report = oneUnprovenBlockingAdapterReport();

        // Unobserved, so the violation stays unproven and the draft names its symbol.
        Path emptyLog = dir.resolve("empty.log");
        write(emptyLog, "[0.1s][info][class,load] com.example.Other source: x\n");
        List<Violation> unobserved = copyOf(report.violations);
        Evidence.LoadEvidence evidence = Evidence.load(List.of(emptyLog.toString()));
        Evidence.apply(unobserved, evidence);
        assertFalse(unobserved.get(0).observedLoading);
        Path draft = dir.resolve("draft.toml");
        int drafted = Evidence.draftExcludes(unobserved, report.appRootsMatched, evidence, draft.toString());
        assertEquals(1, drafted);
        String content = Files.readString(draft, StandardCharsets.UTF_8);
        assertTrue(content.contains("owner = \"kotlinx/coroutines/EventLoopKt\""), content);
        assertTrue(content.contains("member = \"processNextEventInCurrentThread\""), content);
        assertTrue(content.contains("REVIEW:"), content);
        assertEquals(1, Exclude.load(List.of(draft.toString())).size(), "draft must load as a real exclude file");

        // Observed. A test-run log naming the referencing class promotes it and empties the draft.
        Path log = dir.resolve("test-run.log");
        write(log, "[0.2s][info][class,load] io.ktor.utils.io.jvm.javaio.BlockingAdapter source: file:/ktor-io.jar\n");
        List<Violation> observed = copyOf(report.violations);
        evidence = Evidence.load(List.of(log.toString()));
        Evidence.apply(observed, evidence);
        assertTrue(observed.get(0).observedLoading);
        assertEquals(
                Tier.BREAKS,
                Tier.of(observed.get(0), Tier.reachableAxisValid(report.appRootsMatched)),
                "an observed load must reach the failing tier");
        drafted = Evidence.draftExcludes(observed, report.appRootsMatched, evidence, draft.toString());
        assertEquals(0, drafted, "an observed symbol must not be drafted");
    }

    /**
     * https://github.com/pact-foundation/pact-jvm/issues/1338: junit5spring 4.2.3 subclasses
     * PactVerificationExtension, which junit5 4.2.3 opened up but 4.2.2 still declares final
     * (Kotlin classes start final). When the runtime classpath lags at junit5 4.2.2 the
     * subclass cannot load (IncompatibleClassChangeError). old = the compile-time binding
     * (4.2.3), new = the lagging runtime resolution (4.2.2).
     */
    @Test
    void detectsPactClassBecameFinalUnderVersionLag() {
        String oldJar = fixture("junit5-4.2.3.jar");
        String newJar = fixture("junit5-4.2.2.jar");
        String spring = fixture("junit5spring-4.2.3.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        assertTrue(
                hasClassChange(
                        changes,
                        BreakingChange.Kind.CLASS_BECAME_FINAL,
                        "au/com/dius/pact/provider/junit5/PactVerificationExtension"),
                "PactVerificationExtension final change is missing from diff");

        Check.Report report = Check.check(List.of(spring), oldIndex, newIndex, List.of(newJar));
        assertTrue(
                report.violations.stream()
                        .anyMatch(v -> sourceClass(v)
                                        .equals("au/com/dius/pact/provider/spring/junit5/PactVerificationSpringExtension")
                                && owner(v).equals("au/com/dius/pact/provider/junit5/PactVerificationExtension")
                                && v.reason == Reason.CLASS_BECAME_FINAL),
                "violations: " + describe(report.violations));
    }

    /**
     * Jetty module version skew. jetty-util 10 made ArrayTrie/ArrayTernaryTrie package-private
     * (and removed the Trie interface) while jetty-http 9.4 still references them, producing
     * IllegalAccessError/NoClassDefFoundError when the modules mix on one classpath.
     */
    @Test
    void detectsJettyUtilClassAccessNarrowing() {
        String oldJar = fixture("jetty-util-9.3.26.v20190403.jar");
        String newJar = fixture("jetty-util-10.0.26.jar");
        String http = fixture("jetty-http-9.4.49.v20220914.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        Check.Report report = Check.check(List.of(http), oldIndex, newIndex, List.of(newJar));
        assertTrue(
                report.violations.stream()
                        .anyMatch(v -> sourceClass(v).equals("org/eclipse/jetty/http/MimeTypes")
                                && owner(v).equals("org/eclipse/jetty/util/ArrayTrie")
                                && v.reason == Reason.CLASS_ACCESS_NARROWED),
                "violations: " + describe(report.violations));
        assertTrue(
                report.violations.stream()
                        .anyMatch(v -> owner(v).equals("org/eclipse/jetty/util/Trie") && v.reason == Reason.CLASS_REMOVED),
                "violations: " + describe(report.violations));
    }

    @Test
    void unrelatedJarReportsNoViolations() {
        String oldJar = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
        String newJar = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
        // A JAR that does not depend on coroutines produces no violations.
        String unrelated = fixture("opentelemetry-sdk-common-1.60.1.jar");

        Check.Report report = Check.check(List.of(unrelated), index(oldJar), index(newJar), List.of(newJar));
        assertTrue(report.violations.isEmpty(), "violations: " + describe(report.violations));
    }

    /**
     * Two versions of the same library on one classpath. Duplicate class names are
     * first-wins, and the JVM never loads the shadowed copies. sisu 0.3.4's SpaceScanner$1
     * extends its shaded asm ClassVisitor, while sisu 1.0.0's extends the real
     * org.objectweb.asm.ClassVisitor and calls its protected super(int) (public in asm 8,
     * protected in asm 9). With 0.3.4 winning, judging the shadowed 1.0.0 copy's super() call
     * against the winner's non-subclass hierarchy reported a false "method access narrowed".
     * Neither classpath order breaks at runtime.
     */
    @Test
    void refsFromShadowedDuplicateJarCopiesAreNotReported() {
        String oldJar = fixture("asm-8.0.1.jar");
        String newJar = fixture("asm-9.10.1.jar");
        String sisu034 = fixture("org.eclipse.sisu.inject-0.3.4.jar");
        String sisu100 = fixture("org.eclipse.sisu.inject-1.0.0.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);

        for (List<String> order : List.of(List.of(sisu034, sisu100), List.of(sisu100, sisu034))) {
            Check.Report report = Check.check(order, oldIndex, newIndex, List.of(newJar));
            assertTrue(report.violations.isEmpty(), "order " + order + ": violations: " + describe(report.violations));
        }
    }

    /**
     * Version lag in the upgrade direction
     * (https://github.com/pact-foundation/pact-jvm/issues/1338). Upgrading junit5spring
     * 4.2.2 -> 4.2.3 while junit5 stays at 4.2.2 introduces PactVerificationSpringExtension, a
     * new subclass of PactVerificationExtension, which the lagging junit5 still declares final
     * (opened only in 4.2.3). The final class lives in an artifact the upgrade did not change,
     * so the old/new pair diff cannot see it. The upgraded artifact's own new classes must be
     * checked against the resolved classpath.
     */
    @Test
    void detectsUpgradedArtifactSubclassingFinalClassOfLaggingSibling() {
        String oldSpring = fixture("junit5spring-4.2.2.jar");
        String newSpring = fixture("junit5spring-4.2.3.jar");
        String laggingJunit5 = fixture("junit5-4.2.2.jar");

        Check.Report report = Commands.runCheck(
                List.of(oldSpring), List.of(newSpring), List.of(newSpring, laggingJunit5), List.of(), List.of(), null, null);
        assertEquals(1, report.violations.size(), "violations: " + describe(report.violations));
        Violation v = report.violations.get(0);
        assertEquals("au/com/dius/pact/provider/spring/junit5/PactVerificationSpringExtension", sourceClass(v));
        assertEquals("au/com/dius/pact/provider/junit5/PactVerificationExtension", owner(v));
        assertEquals(Reason.EXTENDS_FINAL_CLASS, v.reason);
    }

    /**
     * The old-relative gate for the version-lag check. When the changed artifact's old version
     * already had the same super edge, the breakage predates the upgrade and must not be
     * reported (same stance as every other pre-existing inconsistency).
     */
    @Test
    void preexistingFinalSuperEdgeIsNotReportedOnUpgrade(@TempDir Path dir) throws Exception {
        String spring = fixture("junit5spring-4.2.3.jar");
        String laggingJunit5 = fixture("junit5-4.2.2.jar");

        Path oldCopy = dir.resolve("junit5spring-old.jar");
        Files.copy(Path.of(spring), oldCopy);

        Check.Report report = Commands.runCheck(
                List.of(oldCopy.toString()), List.of(spring), List.of(spring, laggingJunit5), List.of(), List.of(), null, null);
        assertTrue(report.violations.isEmpty(), "violations: " + describe(report.violations));
    }

    /**
     * Locates ct.sym for the JDK-layer test. The same environment lookup the CLI uses, then
     * the mise-pinned JDK as a fallback (CI and this repo's dev setup), then the JDK running
     * the tests, which the Rust test does not have.
     */
    private static Path findCtSymForTest() {
        Path found = Jdk.findCtSym();
        if (found != null) {
            return found;
        }
        Exec out = exec(true, List.of("mise", "exec", "--", "java", "-XshowSettings:properties", "-version"));
        if (out != null) {
            for (String line : out.output().split("\n", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("java.home = ")) {
                    Path ctSym = Jdk.ctSymIn(Path.of(trimmed.substring("java.home = ".length()).trim()));
                    if (ctSym != null) {
                        return ctSym;
                    }
                    break;
                }
            }
        }
        return Jdk.ctSymIn(Path.of(System.getProperty("java.home")));
    }

    private static String verdictKey(Violation v) {
        boolean hasMember = v.reference.hasMember();
        return String.join(
                " | ",
                sourceClass(v),
                owner(v),
                hasMember ? Intern.str(MemberKey.name(v.reference.member())) : "",
                hasMember ? Intern.str(MemberKey.descriptor(v.reference.member())) : "",
                v.reason.text);
    }

    private static List<String> sortedVerdictKeys(List<Violation> violations) {
        List<String> keys = new ArrayList<>();
        for (Violation v : violations) {
            keys.add(verdictKey(v));
        }
        keys.sort(null);
        return keys;
    }

    /**
     * The opt-in JDK API layer (--jdk-release). guava's collections extend java.util types, so
     * the selenium scenario leaves hierarchy-escape references unverified. With the layer,
     * every escape concludes and the broken verdicts stay identical. No detection is lost and
     * no new violation appears from ct.sym data.
     */
    @Test
    void jdkLayerResolvesHierarchyEscapesWithoutChangingVerdicts() {
        Path ctSym = findCtSymForTest();
        assumeTrue(ctSym != null, "no JDK with ct.sym found (JAVA_HOME/UIKA_JDK/mise)");
        String oldJar = fixture("guava-22.0.jar");
        String newJar = fixture("guava-23.0-rc1.jar");
        String selenium = fixture("selenium-remote-driver-3.4.0.jar");

        ApiIndex oldIndex = index(oldJar);
        ApiIndex newIndex = index(newJar);
        MemberProbe probe = Check.selectionMemberProbe(oldIndex, newIndex);

        Check.Report baseline = Check.checkScanned(
                Scan.scanTargetPaths(List.of(selenium), oldIndex, probe, false),
                oldIndex,
                newIndex,
                new IntSet(),
                null,
                null,
                Check.SpiServices.NONE,
                null);
        assertTrue(baseline.unknownRefs > 0, "expected hierarchy escapes");

        // The found JDK may be older than 18 (its own release is not in its ct.sym), so walk
        // down a ladder instead of failing. The guava escapes are java.util/java.lang types
        // present since release 8, so the assertions hold on every rung (verified for 8, 11,
        // and 17).
        Jdk.Indexer indexer = null;
        for (int release : new int[] {17, 11, 8}) {
            try {
                indexer = Jdk.Indexer.open(ctSym, release);
                break;
            } catch (UikaException e) {
                // next rung
            }
        }
        assumeTrue(indexer != null, "no usable release in " + ctSym);
        try (Jdk.Indexer jdk = indexer) {
            Check.Report withJdk = Check.checkScanned(
                    Scan.scanTargetPaths(List.of(selenium), oldIndex, probe, false),
                    oldIndex,
                    newIndex,
                    new IntSet(),
                    jdk,
                    null,
                    Check.SpiServices.NONE,
                    null);
            assertEquals(0, withJdk.unknownRefs, "all escapes should conclude");
            assertEquals(
                    sortedVerdictKeys(baseline.violations),
                    sortedVerdictKeys(withJdk.violations),
                    "verdicts must not change, only Unknowns conclude");
        }
    }

    /**
     * When the sibling is upgraded in lockstep (junit5 4.2.3 opened the class), the same
     * junit5spring upgrade reports nothing.
     */
    @Test
    void lockstepSiblingUpgradeReportsNothing() {
        String oldSpring = fixture("junit5spring-4.2.2.jar");
        String newSpring = fixture("junit5spring-4.2.3.jar");
        String oldJunit5 = fixture("junit5-4.2.2.jar");
        String newJunit5 = fixture("junit5-4.2.3.jar");

        Check.Report report = Commands.runCheck(
                List.of(oldSpring, oldJunit5),
                List.of(newSpring, newJunit5),
                List.of(newSpring, newJunit5),
                List.of(),
                List.of(),
                null,
                null);
        assertTrue(report.violations.isEmpty(), "violations: " + describe(report.violations));
    }

    private static String describePairs(List<Violation> violations) {
        List<String> out = new ArrayList<>();
        for (Violation v : violations) {
            out.add("(" + sourceClass(v) + ", " + owner(v) + ")");
        }
        return out.toString();
    }

    /**
     * ktor module version skew (the same class family as the coroutines/ktor fixture).
     * io.ktor.utils.io.ByteChannel was an interface in ktor-io 2.3.13 and became a final class
     * in 3.1.0. ktor-network 2.3.13 calls it through an InterfaceMethodref (invokeinterface),
     * so mixing ktor-io 3.1.0 under ktor-network 2.3.13 makes method resolution throw
     * IncompatibleClassChangeError. This is the class/interface flip branch of the class-shape
     * checks, not visible to the constant-pool removal checks.
     */
    @Test
    void detectsKtorInterfaceBecameClassUnderModuleSkew() {
        String oldJar = fixture("ktor-io-jvm-2.3.13.jar");
        String newJar = fixture("ktor-io-jvm-3.1.0.jar");
        String network = fixture("ktor-network-jvm-2.3.13.jar");

        Check.Report report = Check.check(List.of(network), index(oldJar), index(newJar), List.of(newJar));
        assertTrue(
                report.violations.stream()
                        .anyMatch(v -> sourceClass(v).equals("io/ktor/network/sockets/CIOReaderKt$attachForReadingDirectImpl$1")
                                && owner(v).equals("io/ktor/utils/io/ByteChannel")
                                && v.reason == Reason.INTERFACE_BECAME_CLASS),
                "expected an interface-became-class break on ByteChannel: "
                        + describePairs(withReason(report.violations, Reason.INTERFACE_BECAME_CLASS)));
    }

    /**
     * InstantiationError from a {@code new} on a class that became abstract.
     * jackson-module-kotlin 2.20.1 made ValueClassBoxConverter abstract. The module's own
     * ReflectionCache (and two other classes) instantiate it directly with {@code new}.
     * Bytecode compiled against 2.18.2, where the class was a concrete final class, throws
     * InstantiationError once 2.20.1 is on the classpath. Only the {@code new} breaks. A plain
     * type reference to the class stays valid.
     */
    @Test
    void detectsNewOnClassThatBecameAbstract() {
        String oldJar = fixture("jackson-module-kotlin-2.18.2.jar");
        String newJar = fixture("jackson-module-kotlin-2.20.1.jar");

        // The old jar's own classes are the consumer, since they carry the `new` sites.
        Check.Report report = Check.check(List.of(oldJar), index(oldJar), index(newJar), List.of(newJar));
        assertTrue(
                report.violations.stream()
                        .anyMatch(v -> sourceClass(v).equals("com/fasterxml/jackson/module/kotlin/ReflectionCache")
                                && owner(v).equals("com/fasterxml/jackson/module/kotlin/ValueClassBoxConverter")
                                && Boolean.TRUE.equals(v.reference.instantiated())
                                && v.reason == Reason.CLASS_BECAME_ABSTRACT),
                "expected an InstantiationError break on ValueClassBoxConverter: "
                        + describePairs(withReason(report.violations, Reason.CLASS_BECAME_ABSTRACT)));
    }
}
