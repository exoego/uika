package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        assertEquals(11, UikaCli.declaredRelease(List.of("--target", "11")), "javac takes either");
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
    void installedReleaseReadsTheJdksReleaseFile(@TempDir Path home) throws IOException {
        assertNull(UikaCli.installedRelease(home), "no release file");

        var release = home.resolve("release");
        Files.writeString(release, "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"21.0.4\"\n");
        assertEquals(21, UikaCli.installedRelease(home));
        Files.writeString(release, "JAVA_VERSION=\"1.8.0_402\"\n");
        assertEquals(8, UikaCli.installedRelease(home), "8 still writes the legacy spelling");
        Files.writeString(release, "JAVA_VERSION=\"25-ea\"\n");
        assertEquals(25, UikaCli.installedRelease(home));
        Files.writeString(release, "JAVA_VERSION=\"1.7.0_80\"\n");
        assertNull(UikaCli.installedRelease(home), "below the floor");
        Files.writeString(release, "JAVA_VERSION=\"1\"\n");
        assertNull(UikaCli.installedRelease(home), "a bare 1 is not 1.x");
        Files.writeString(release, "IMPLEMENTOR=\"Eclipse Adoptium\"\n");
        assertNull(UikaCli.installedRelease(home), "no version line");
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

        // A JDK 8 ct.sym stops at 7, under the floor, so every target is clamped there.
        assertNull(UikaCli.effectiveJdkRelease(11, jdk(home, 8), log::add));

        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("below the lowest release ct.sym serves"),
                () -> "wrong reason for a below-floor release: " + log);
        // Folding this into the missing-ct.sym message sent users to inspect a JDK that was
        // fine, so the two reasons must stay distinguishable.
        assertFalse(log.get(0).contains("no usable ct.sym"),
                () -> "below-floor was reported as a missing ct.sym: " + log);
        // Reachable without a fake JVM too: Maven, sbt and Bazel all pass an explicit knob
        // value straight through, so `jdkRelease = 5` on a modern JDK lands here as well.
        var direct = new ArrayList<String>();
        assertNull(UikaCli.effectiveJdkRelease(5, jdk(home, 21), direct::add));
        assertEquals(1, direct.size(), () -> "expected exactly one line: " + direct);
        assertTrue(direct.get(0).contains("below the lowest release ct.sym serves"),
                () -> "wrong reason for an explicit below-floor knob: " + direct);
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

        // A JDK 21 ct.sym stops one release below its own, so the ceiling is 20.
        assertEquals(20, UikaCli.effectiveJdkRelease(21, jdk(home, 21), log::add));

        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("clamped to release 20"),
                () -> "clamp was not announced: " + log);
    }

    /// From JDK 22 on, ct.sym carries the JDK's own release (JDK-8318913), so a JDK 25
    /// serves 25. Clamping it to 24 announced a release the file does have as missing, and
    /// every member 25 added stayed Unknown on a JDK that could have resolved it.
    @Test
    void aJdk22OrLaterServesItsOwnRelease(@TempDir Path home) throws IOException {
        withCtSym(home, "P");
        var log = new ArrayList<String>();

        assertEquals(25, UikaCli.effectiveJdkRelease(25, jdk(home, 25), log::add));
        assertTrue(log.isEmpty(), () -> "the JDK's own release was announced as clamped: " + log);

        assertEquals(25, UikaCli.effectiveJdkRelease(26, jdk(home, 25), log::add));
        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("clamped to release 25"),
                () -> "a release above the JDK was not clamped to it: " + log);
    }

    /// The JDK 22 early-access builds before JDK-8318913 are 22 by version and 21 by
    /// content, with an M/system-modules entry and no M stubs. The version rule alone sent
    /// `--jdk-release 22` to a file without it, and the CLI exited 2 where the old clamp
    /// had passed. The module-info stub in the M dir must not count as carrying 22.
    @Test
    void aJdkWhoseCtSymLacksItsOwnReleaseIsClampedOneBelow(@TempDir Path home) throws IOException {
        withCtSym(home);
        var log = new ArrayList<String>();

        assertEquals(21, UikaCli.effectiveJdkRelease(22, jdk(home, 22), log::add));
        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("clamped to release 21") && log.get(0).contains("has no release 22"),
                () -> "the missing own release was not announced: " + log);

        // Below the own release the file is not consulted, so the same file serves 21.
        log.clear();
        assertEquals(21, UikaCli.effectiveJdkRelease(21, jdk(home, 22), log::add));
        assertTrue(log.isEmpty(), () -> "a release every ct.sym carries was announced: " + log);
    }

    /// A stub in a joint dir is shared by every release the dir names, so "MP" carries 22.
    @Test
    void aStubInAJointDirCountsForEveryReleaseItNames(@TempDir Path home) throws IOException {
        withCtSym(home, "MP");
        var log = new ArrayList<String>();

        assertEquals(22, UikaCli.effectiveJdkRelease(22, jdk(home, 22), log::add));
        assertTrue(log.isEmpty(), () -> "a carried release was announced as clamped: " + log);
    }

    /// A ct.sym that is not a zip is the CLI's to refuse, naming the file. Clamping it
    /// would blame a release instead and send the CLI to the same unreadable file.
    @Test
    void anUnreadableCtSymIsLeftToTheCli(@TempDir Path home) throws IOException {
        Files.createDirectories(home.resolve("lib"));
        Files.writeString(home.resolve("lib").resolve("ct.sym"), "not a zip");
        var log = new ArrayList<String>();

        assertEquals(25, UikaCli.effectiveJdkRelease(25, jdk(home, 25), log::add));
        assertTrue(log.isEmpty(), () -> "an unreadable ct.sym was reported as lacking a release: " + log);
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
        withCtSym(home, "MP");

        // Explicit ceilings, not ctSymCeiling. The sweep guards the direction of that rule,
        // so its expectation cannot come from it. 22 is the first JDK whose ct.sym carries
        // its own release.
        for (var pair : List.of(Map.entry(21, 20), Map.entry(22, 22), Map.entry(25, 25))) {
            var feature = pair.getKey();
            var ceiling = pair.getValue();
            for (var target = UikaCli.MIN_RELEASE; target <= 30; target++) {
                Integer effective = UikaCli.effectiveJdkRelease(target, jdk(home, feature), line -> { });
                var wanted = target;
                // The exact value, not `<= wanted`. With a ceiling above the floor of 8 this
                // can never legitimately answer null, so tolerating null would admit the
                // "silently switched the layer off" regression the test exists for.
                assertEquals(Math.min(wanted, ceiling), effective,
                        () -> "release " + wanted + " on JDK " + feature + " came back as " + effective);
            }
        }
    }

    private static UikaCli.JdkSource jdk(Path home, int feature) {
        return new UikaCli.JdkSource(home, feature);
    }

    /// A ct.sym holding one class stub per codes dir in `stubDirs`, over the shape of a JDK
    /// 22 early-access file. That is a 21 stub and the two M entries that are not stubs. The
    /// clamp reads the file at the JDK's own release, so an empty file no longer stands in.
    /// "MP" is a joint dir, a file shared by 22 and 25.
    private static void withCtSym(Path home, String... stubDirs) throws IOException {
        Files.createDirectories(home.resolve("lib"));
        try (var zip = new ZipOutputStream(Files.newOutputStream(home.resolve("lib").resolve("ct.sym")))) {
            for (var entry : List.of("L/java.base/java/lang/Object.sig",
                    "M/system-modules", "M/java.base/module-info.sig")) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.closeEntry();
            }
            for (var dir : stubDirs) {
                zip.putNextEntry(new ZipEntry(dir + "/java.base/java/lang/Object.sig"));
                zip.closeEntry();
            }
        }
    }
}
