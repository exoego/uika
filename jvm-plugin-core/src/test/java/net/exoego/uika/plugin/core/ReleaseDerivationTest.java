package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The release derivation Gradle, sbt, Maven, Mill and the Bazel rules all share.
///
/// One copy in the core test source set, mounted into the Gradle and Maven builds the way
/// the main sources are, so the logic five integrations depend on is verified once rather
/// than per plugin.
///
/// The skip branches are the reason this exists. `effectiveJdkRelease` decides whether
/// `--jdk-release` is sent at all, and switching the layer off silently costs findings:
/// with it on, the guava-selenium fixture goes from 16 unverified to 0. Every integration
/// test in the repository runs on a JDK whose ct.sym is present and whose derived release
/// sits far below the ceiling, so neither skip branch could ever be reached from one, and
/// a fake JdkSource is the only way in.
final class ReleaseDerivationTest {
    @Test
    void parseReleaseTakesEverySpellingTheToolchainsUse() {
        assertEquals(17, UikaCli.parseRelease("17"));
        assertEquals(8, UikaCli.parseRelease("1.8"), "javac's legacy spelling");
        assertEquals(8, UikaCli.parseRelease("jvm-1.8"), "scalac's spelling");
        assertEquals(8, UikaCli.parseRelease("  8  "), "value is trimmed");

        // Below the floor is reported as no declaration at all, so one legacy module cannot
        // drag a build's minimum under what ct.sym can serve.
        assertNull(UikaCli.parseRelease("7"));
        assertNull(UikaCli.parseRelease("1.7"));
        assertNull(UikaCli.parseRelease("eleven"));
        assertNull(UikaCli.parseRelease(null));
    }

    @Test
    void declaredReleaseReadsTheFlagsThatPinTheApi() {
        assertEquals(17, UikaCli.declaredRelease(List.of("--release", "17")));
        assertEquals(17, UikaCli.declaredRelease(List.of("--release=17")), "javac takes either");
        assertEquals(11, UikaCli.declaredRelease(List.of("-release", "11")), "scalac");
        assertEquals(11, UikaCli.declaredRelease(List.of("-java-output-version:11")),
                "scalac's colon form");
        assertEquals(8, UikaCli.declaredRelease(List.of("-target", "1.8")));
        assertNull(UikaCli.declaredRelease(List.of("-Xlint:all", "-g")));
        assertNull(UikaCli.declaredRelease(List.of()));

        // --release pins the API; -target only names the class-file version, so it must
        // never win over one. Order must not decide it either.
        assertEquals(17, UikaCli.declaredRelease(List.of("-target", "11", "--release", "17")));
        assertEquals(17, UikaCli.declaredRelease(List.of("--release", "17", "-target", "11")));

        // A trailing flag with no value must not read past the end of the list.
        assertNull(UikaCli.declaredRelease(List.of("--release")));
    }

    @Test
    void overrideReleaseKeepsOnlyServableValues() {
        assertEquals(21, UikaCli.overrideRelease(21));
        assertEquals(UikaCli.MIN_RELEASE, UikaCli.overrideRelease(UikaCli.MIN_RELEASE));
        // Zero means "switch the API layer off", and the dump keeps its derived values
        // rather than going silent, so it must answer null here like any below-floor value.
        assertNull(UikaCli.overrideRelease(0));
        assertNull(UikaCli.overrideRelease(7));
        assertNull(UikaCli.overrideRelease(null));
    }

    @Test
    void aDisabledLayerSendsNoFlagAndSaysNothing(@TempDir Path home) {
        var log = new ArrayList<String>();

        assertNull(UikaCli.effectiveJdkRelease(null, jdk(home, 21), log::add));
        assertNull(UikaCli.effectiveJdkRelease(0, jdk(home, 21), log::add));

        // Zero is the documented opt-out, so explaining it would be noise on a run the user
        // asked for. The below-floor branch below is the one that owes an explanation.
        assertTrue(log.isEmpty(), () -> "disabling the layer logged: " + log);
    }

    @Test
    void aBelowFloorReleaseSkipsAndSaysWhy(@TempDir Path home) throws IOException {
        withCtSym(home);
        var log = new ArrayList<String>();

        // The ceiling is feature - 1, so a JDK 8 clamps every target to 7, under the floor.
        assertNull(UikaCli.effectiveJdkRelease(11, jdk(home, 8), log::add));

        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("below the lowest release ct.sym serves"),
                () -> "wrong reason for a below-floor release: " + log);
        // Folding this into the missing-ct.sym message sent users to inspect a JDK that was
        // fine, so the two reasons must stay distinguishable.
        assertFalse(log.get(0).contains("no usable ct.sym"),
                () -> "below-floor was reported as a missing ct.sym: " + log);
    }

    @Test
    void aJdkWithoutCtSymSkipsAndNamesTheJdk(@TempDir Path home) {
        var log = new ArrayList<String>();

        assertNull(UikaCli.effectiveJdkRelease(17, jdk(home, 21), log::add));

        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("no usable ct.sym"),
                () -> "wrong reason for a JDK with no ct.sym: " + log);
        // The message names the JDK because for Leiningen it is the project's JVM rather
        // than the one running the build, and "the build JVM" would send the user to the
        // wrong home.
        assertTrue(log.get(0).contains(home.toString()),
                () -> "message does not name the JDK it inspected: " + log);
    }

    @Test
    void aTargetAboveTheCeilingIsClampedDownAndAnnounced(@TempDir Path home) throws IOException {
        withCtSym(home);
        var log = new ArrayList<String>();

        // A JDK's own ct.sym never carries its own release, so the ceiling is feature - 1.
        assertEquals(20, UikaCli.effectiveJdkRelease(21, jdk(home, 21), log::add));

        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("clamped to release 20"),
                () -> "clamp was not announced: " + log);
    }

    @Test
    void aServableTargetPassesThroughSilently(@TempDir Path home) throws IOException {
        withCtSym(home);
        var log = new ArrayList<String>();

        assertEquals(11, UikaCli.effectiveJdkRelease(11, jdk(home, 21), log::add));

        assertTrue(log.isEmpty(), () -> "an unclamped release logged: " + log);
    }

    /// Clamping only ever goes DOWN. Rounding up would make a member the runtime lacks
    /// resolve cleanly, which loses the finding with nothing to show for it, while
    /// under-claiming only turns it into an Unknown.
    @Test
    void clampingNeverRoundsUp(@TempDir Path home) throws IOException {
        withCtSym(home);

        for (var target = UikaCli.MIN_RELEASE; target <= 30; target++) {
            Integer effective = UikaCli.effectiveJdkRelease(target, jdk(home, 21), line -> { });
            var wanted = target;
            assertTrue(effective == null || effective <= wanted,
                    () -> "release " + wanted + " was raised to " + effective);
        }
    }

    private static UikaCli.JdkSource jdk(Path home, int feature) {
        return new UikaCli.JdkSource(home, feature);
    }

    /// Only its presence is read, so an empty file is enough to stand in for a real one.
    private static void withCtSym(Path home) throws IOException {
        Files.createDirectories(home.resolve("lib"));
        Files.createFile(home.resolve("lib").resolve("ct.sym"));
    }
}
