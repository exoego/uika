package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuggestTest {
    @Test
    void groupOfTakesFirstSegment() {
        assertEquals("io.opentelemetry", Suggest.groupOf("io.opentelemetry:opentelemetry-sdk-logs:1.62.0"));
        assertEquals("io.opentelemetry", Suggest.groupOf("io.opentelemetry:opentelemetry-api-incubator"));
    }

    private static Dump.DependencyChange change(
            String coord, Dump.ChangeKind kind, List<String> before, List<String> after) {
        return new Dump.DependencyChange(coord, kind, before, after);
    }

    // The tests below document one advice message per pattern. Read the one whose name matches
    // your situation to see the exact suggestion it produces.

    /** Cross-group version change, referencing artifact known. Upgrade it or pin the owner. */
    @Test
    void adviceCrossGroupVersionChange() {
        Suggestion s = Suggest.build(
                change(
                        "io.opentelemetry.instrumentation:opentelemetry-grpc-1.6",
                        Dump.ChangeKind.CHANGED,
                        List.of("2.24.0-alpha"),
                        List.of("2.29.0-alpha")),
                "com.google.cloud:google-cloud-firestore:3.42.0",
                false);
        assertEquals(
                "upgrade com.google.cloud:google-cloud-firestore:3.42.0 to a release built against "
                        + "io.opentelemetry.instrumentation:opentelemetry-grpc-1.6 2.29.0-alpha, or pin "
                        + "io.opentelemetry.instrumentation:opentelemetry-grpc-1.6 to 2.24.0-alpha",
                s.advice());
        assertEquals("2.24.0-alpha", s.before());
        assertEquals("2.29.0-alpha", s.after());
    }

    /**
     * Owner and referencer share a group (a version skew inside one family). Lead with BOM
     * alignment, then fall back to the upgrade-or-pin advice.
     */
    @Test
    void adviceSameGroupSkewLeadsWithBomAlignment() {
        Suggestion s = Suggest.build(
                change(
                        "io.opentelemetry:opentelemetry-api-incubator",
                        Dump.ChangeKind.CHANGED,
                        List.of("1.58.0-alpha"),
                        List.of("1.63.0-alpha")),
                "io.opentelemetry:opentelemetry-sdk-common:1.60.1",
                false);
        assertEquals(
                "align all io.opentelemetry artifacts to one version (e.g. via the matching BOM); "
                        + "otherwise upgrade io.opentelemetry:opentelemetry-sdk-common:1.60.1 to a release built "
                        + "against io.opentelemetry:opentelemetry-api-incubator 1.63.0-alpha, or pin "
                        + "io.opentelemetry:opentelemetry-api-incubator to 1.58.0-alpha",
                s.advice());
    }

    /**
     * Referencing artifact unknown (the break is in an application build output). The
     * referencer is left generic and the same-group alignment shortcut does not apply.
     */
    @Test
    void adviceReferencerUnknown() {
        Suggestion s = Suggest.build(
                change(
                        "io.opentelemetry.instrumentation:opentelemetry-grpc-1.6",
                        Dump.ChangeKind.CHANGED,
                        List.of("2.24.0-alpha"),
                        List.of("2.29.0-alpha")),
                null,
                false);
        assertEquals(
                "upgrade the referencing artifact to a release built against "
                        + "io.opentelemetry.instrumentation:opentelemetry-grpc-1.6 2.29.0-alpha, or pin "
                        + "io.opentelemetry.instrumentation:opentelemetry-grpc-1.6 to 2.24.0-alpha",
                s.advice());
    }

    /**
     * Coordinate removed entirely. There is no version to pin back, so advise dropping the
     * need or restoring the artifact.
     */
    @Test
    void adviceRemovedCoordinateReferencerKnown() {
        Suggestion s = Suggest.build(
                change(
                        "io.opentelemetry.instrumentation:opentelemetry-ktor-common",
                        Dump.ChangeKind.REMOVED,
                        List.of("2.24.0-alpha"),
                        List.of()),
                "com.example:app:1.0",
                false);
        assertEquals(
                "io.opentelemetry.instrumentation:opentelemetry-ktor-common was removed by the upgrade, "
                        + "but com.example:app:1.0 still needs it; upgrade com.example:app:1.0 to a release that "
                        + "no longer requires io.opentelemetry.instrumentation:opentelemetry-ktor-common, or "
                        + "restore io.opentelemetry.instrumentation:opentelemetry-ktor-common",
                s.advice());
        assertEquals("2.24.0-alpha", s.before());
        assertEquals("-", s.after());
    }

    /**
     * Coordinate removed and the referencing artifact declares it optional. It was never a
     * transitive requirement, so neither "still needs it" nor "upgrade to a release that no
     * longer requires it" applies. https://github.com/exoego/uika/issues/96
     */
    @Test
    void adviceRemovedOptionalCoordinate() {
        Suggestion s = Suggest.build(
                change("org.slf4j:slf4j-api", Dump.ChangeKind.REMOVED, List.of("2.0.13"), List.of()),
                "com.google.auth:google-auth-library-oauth2-http:1.50.0",
                true);
        assertEquals(
                "org.slf4j:slf4j-api was removed by the upgrade and "
                        + "com.google.auth:google-auth-library-oauth2-http:1.50.0 declares it optional, so "
                        + "com.google.auth:google-auth-library-oauth2-http:1.50.0 never required it "
                        + "transitively -- it was on the classpath for some other reason that no longer holds. "
                        + "These references break only where "
                        + "com.google.auth:google-auth-library-oauth2-http:1.50.0's optional feature is used; "
                        + "restore org.slf4j:slf4j-api to keep that feature working",
                s.advice());
    }

    /**
     * The optional wording is only for a coordinate that vanished. A version CHANGE leaves the
     * artifact on the classpath, so optional-ness says nothing about the break.
     */
    @Test
    void optionalDoesNotRewordAVersionChange() {
        Suggestion s = Suggest.build(
                change("g:n", Dump.ChangeKind.CHANGED, List.of("1.0"), List.of("2.0")), "h:m:1", true);
        assertEquals("upgrade h:m:1 to a release built against g:n 2.0, or pin g:n to 1.0", s.advice());
    }

    /** Coordinate removed, referencing artifact unknown. */
    @Test
    void adviceRemovedCoordinateReferencerUnknown() {
        Suggestion s = Suggest.build(
                change(
                        "io.opentelemetry.instrumentation:opentelemetry-ktor-common",
                        Dump.ChangeKind.REMOVED,
                        List.of("2.24.0-alpha"),
                        List.of()),
                null,
                false);
        assertEquals(
                "io.opentelemetry.instrumentation:opentelemetry-ktor-common was removed by the upgrade, "
                        + "but the referencing artifact still needs it; upgrade the referencing artifact to a "
                        + "release that no longer requires io.opentelemetry.instrumentation:opentelemetry-ktor-common, "
                        + "or restore io.opentelemetry.instrumentation:opentelemetry-ktor-common",
                s.advice());
    }

    /**
     * Multi-version coordinate (resolves to several versions at once). Advise only the
     * versions that actually moved (2.0 replaced by 3.0), not the full resolved lists.
     */
    @Test
    void adviceMultiVersionUsesOnlyChangedVersions() {
        Suggestion s = Suggest.build(
                change("g:n", Dump.ChangeKind.CHANGED, List.of("1.0", "2.0"), List.of("1.0", "3.0")),
                "h:m:1",
                false);
        assertEquals("upgrade h:m:1 to a release built against g:n 3.0, or pin g:n to 2.0", s.advice());
        // The removedBy line still shows the full resolved lists for context.
        assertEquals("1.0,2.0", s.before());
        assertEquals("1.0,3.0", s.after());
    }

    // The three tests below stand in for the Rust integration tests of the same names, which
    // reach annotate through run_check. The violation is built by hand here so this file does
    // not depend on the check pipeline. No Rust unit test covers annotate itself.

    private static final String SDK_COMMON_OLD = "tests/fixtures/opentelemetry-sdk-common-1.42.1.jar";
    private static final String SDK_COMMON_NEW = "tests/fixtures/opentelemetry-sdk-common-1.60.1.jar";
    private static final String SENDER = "tests/fixtures/opentelemetry-exporter-sender-okhttp-1.42.1.jar";

    /** The DaemonThreadFactory break sdk-common 1.60.1 causes in the okhttp sender. */
    private static Violation daemonThreadFactoryRemoved(String source) {
        return new Violation(
                Intern.intern(source),
                Intern.intern("io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil"),
                SymbolRef.ofClass(Intern.intern("io/opentelemetry/sdk/internal/DaemonThreadFactory")),
                Reason.CLASS_REMOVED);
    }

    private static Dump.Universe universe(String... groupNameVersionFile) {
        Dump.Universe universe = new Dump.Universe();
        for (int i = 0; i < groupNameVersionFile.length; i += 4) {
            universe.versions.add(
                    groupNameVersionFile[i],
                    groupNameVersionFile[i + 1],
                    groupNameVersionFile[i + 2],
                    groupNameVersionFile[i + 3]);
        }
        return universe;
    }

    @Test
    void upgradeCheckSuggestionAttributesTheBreak() {
        Dump.Universe before = universe(
                "io.opentelemetry", "opentelemetry-sdk-common", "1.42.1", SDK_COMMON_OLD,
                "io.opentelemetry", "opentelemetry-exporter-sender-okhttp", "1.42.1", SENDER);
        Dump.Universe after = universe(
                "io.opentelemetry", "opentelemetry-sdk-common", "1.60.1", SDK_COMMON_NEW,
                "io.opentelemetry", "opentelemetry-exporter-sender-okhttp", "1.42.1", SENDER);
        Violation attributed = daemonThreadFactoryRemoved(SENDER);
        // An owner no changed coordinate ships stays without a suggestion.
        Violation unattributed = new Violation(
                Intern.intern(SENDER),
                Intern.intern("io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil"),
                SymbolRef.ofClass(Intern.intern("okhttp3/OkHttpClient")),
                Reason.CLASS_REMOVED);

        Suggest.annotate(List.of(attributed, unattributed), before, after, Dump.diffDumps(before, after).changes());

        Suggestion s = attributed.suggestion;
        assertNotNull(s, "violation should carry a suggestion");
        assertEquals("io.opentelemetry:opentelemetry-exporter-sender-okhttp:1.42.1", s.referencedBy());
        assertEquals("io.opentelemetry:opentelemetry-sdk-common", s.removedBy());
        assertEquals("1.42.1", s.before());
        assertEquals("1.60.1", s.after());
        // Same group, so the advice leads with alignment.
        assertTrue(s.advice().startsWith("align all io.opentelemetry artifacts"), s.advice());
        assertNull(unattributed.suggestion);
    }

    /**
     * The optional rewording end to end, which is the only thing that exercises
     * {@code Annotator.declaresOptional}, {@code readOptionalDeps} and {@code Pom.locate}
     * together. The sender JAR is copied into a Maven-shaped layout so a POM can sit beside it
     * without polluting tests/fixtures.
     */
    @Test
    void upgradeCheckSuggestionReadsOptionalFromTheReferencersPom(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("io/opentelemetry/opentelemetry-exporter-sender-okhttp/1.42.1");
        Files.createDirectories(cache);
        String sender = cache.resolve("opentelemetry-exporter-sender-okhttp-1.42.1.jar").toString();
        Files.copy(Path.of(SENDER), Path.of(sender));
        Path pomPath = cache.resolve("opentelemetry-exporter-sender-okhttp-1.42.1.pom");

        // The upgrade drops sdk-common entirely, so the removed-coordinate advice applies.
        Dump.Universe before = universe(
                "io.opentelemetry", "opentelemetry-sdk-common", "1.42.1", SDK_COMMON_OLD,
                "io.opentelemetry", "opentelemetry-exporter-sender-okhttp", "1.42.1", sender);
        Dump.Universe after =
                universe("io.opentelemetry", "opentelemetry-exporter-sender-okhttp", "1.42.1", sender);
        List<Dump.DependencyChange> changes = Dump.diffDumps(before, after).changes();

        String optionalPom = """
                <project><dependencies><dependency>
                  <groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk-common</artifactId>
                  <version>1.42.1</version><optional>true</optional>
                </dependency></dependencies></project>""";
        Files.writeString(pomPath, optionalPom, StandardCharsets.UTF_8);
        Violation v = daemonThreadFactoryRemoved(sender);
        Suggest.annotate(List.of(v), before, after, changes);
        assertTrue(v.suggestion.advice().contains("declares it optional"), v.suggestion.advice());

        // Same dumps, POM gone. Every failure path falls back to the original wording.
        Files.delete(pomPath);
        v = daemonThreadFactoryRemoved(sender);
        Suggest.annotate(List.of(v), before, after, changes);
        assertTrue(v.suggestion.advice().contains("still needs it"), v.suggestion.advice());

        Files.writeString(pomPath, optionalPom.replace("<optional>true</optional>", ""), StandardCharsets.UTF_8);
        v = daemonThreadFactoryRemoved(sender);
        Suggest.annotate(List.of(v), before, after, changes);
        assertTrue(v.suggestion.advice().contains("still needs it"), v.suggestion.advice());
    }

    /**
     * An SPI violation's source is the upgraded library's own jar, so mapping it through the
     * coordinate table would advise aligning the coordinate with itself. It must stay
     * unannotated instead.
     */
    @Test
    void spiViolationGetsNoSelfReferentialSuggestion() {
        String oldJar = "tests/fixtures/synthetic-spi-1.0.jar";
        String newJar = "tests/fixtures/synthetic-spi-2.0.jar";
        String consumer = "tests/fixtures/synthetic-spi-consumer.jar";
        Dump.Universe before = universe("fixture", "spi", "1.0", oldJar, "fixture", "consumer", "1.0", consumer);
        Dump.Universe after = universe("fixture", "spi", "2.0", newJar, "fixture", "consumer", "1.0", consumer);
        List<Dump.DependencyChange> changes = Dump.diffDumps(before, after).changes();

        Violation spi = new Violation(
                Intern.intern(newJar),
                Intern.intern("fixture/lib/Impl"),
                SymbolRef.ofClass(Intern.intern("fixture/lib/Spi")),
                Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE);
        // The same owner referenced from another coordinate is still annotated.
        Violation fromConsumer = new Violation(
                Intern.intern(consumer),
                Intern.intern("fixture/app/Main"),
                SymbolRef.ofClass(Intern.intern("fixture/lib/Spi")),
                Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE);
        Suggest.annotate(List.of(spi, fromConsumer), before, after, changes);

        assertNull(spi.suggestion);
        assertNotNull(fromConsumer.suggestion);
        assertEquals("fixture:consumer:1.0", fromConsumer.suggestion.referencedBy());
    }
}
