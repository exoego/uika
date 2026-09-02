package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.JfrEvidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Unit tests for the manifest parser and the argument guards, run as a plain {@code main}.
 *
 * <p>No JUnit, and so no {@code maven.install}: the ruleset's only dependency is
 * {@code rules_java}, and adding a test framework would put a network fetch in front of a
 * suite whose whole point is running without one. {@code use_testrunner = False} lets Bazel
 * treat a non-zero exit as the failure signal, which is all a check like this needs.
 *
 * <p>These classes had no test of any kind. The manifest parser decides what a module IS,
 * and the argument guards are what keeps an empty {@code --before} from resolving to the
 * workspace root; both were found by reading rather than by a failing test.
 */
public final class ManifestSelfTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws IOException {
        parsesModulesWithReleasesAndDeps();
        overrideReplacesEveryModulesRelease();
        rejectsMalformedLines();
        flagValueRejectsMissingAndEmptyValues();
        flagReleaseNamesTheFlagOnGarbage();
        flagValueIsUsedByTheBinariesThatHaveIt();
        excludeFilesAreSplitTrimmedAndBlankDropped();
        conversionsLandInsideTheEvidenceLocation();
        aNonNegativeReleaseSkipsTheDerivation();

        // A floor, not a total: `failures` counts only what FAILED, so a deleted call in
        // main or an early return inside a method would otherwise be a silent pass. The
        // class-file floor guard next door fails on an empty sweep for the same reason.
        var expected = 39;
        if (checks < expected) {
            System.err.println("only " + checks + " checks ran, expected at least " + expected);
            System.exit(1);
        }
        if (failures > 0) {
            System.err.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ManifestSelfTest: " + checks + " checks passed");
    }

    private static void parsesModulesWithReleasesAndDeps() throws IOException {
        // javacopts pin the API and must win over the toolchain, which names the COMPILER.
        // Bazel's ordinary shape runs a recent toolchain against an older target, so reading
        // the toolchain for //app would over-claim, and over-claiming loses findings silently.
        List<Module> modules = parse("""
                module\t//app:app
                toolchain\t21
                javacopt\t--release
                javacopt\t11
                classes\tapp.jar
                dep\tcom.google.guava\tguava\t22.0\t\tguava.jar
                dep\t\t\t\t//lib:lib\tlib.jar
                module\t//lib:lib
                toolchain\t21
                """, null);

        check(modules.size() == 2, "expected two modules, got " + modules.size());
        Module app = modules.get(0);
        check("//app:app".equals(app.path()), "wrong module label: " + app.path());
        check(Integer.valueOf(11).equals(app.jdkRelease()),
                "javacopts must beat the toolchain, got " + app.jdkRelease());
        check(app.classesDirs().equals(List.of("app.jar")), "wrong classes: " + app.classesDirs());
        check(app.artifacts().size() == 2, "wrong dep count: " + app.artifacts().size());
        var guava = app.artifacts().get(0);
        check("com.google.guava".equals(guava.group()), "wrong group: " + guava.group());
        check("guava".equals(guava.name()), "wrong name: " + guava.name());
        check("22.0".equals(guava.version()), "wrong version: " + guava.version());
        check("guava.jar".equals(guava.file()), "wrong file: " + guava.file());
        check(guava.project() == null, "an external dep gained a project");
        // A target of the build itself carries no coordinates and is attributed by label,
        // the way the other tools record a project dependency.
        check(app.artifacts().get(1).group() == null, "a project dep gained coordinates");
        check("//lib:lib".equals(app.artifacts().get(1).project()),
                "wrong project attribution: " + app.artifacts().get(1).project());

        Module lib = modules.get(1);
        check(Integer.valueOf(21).equals(lib.jdkRelease()),
                "a module declaring nothing falls back to the toolchain, got " + lib.jdkRelease());
        check(lib.artifacts().isEmpty(), "the second module absorbed the first module's deps");
    }

    private static void overrideReplacesEveryModulesRelease() throws IOException {
        List<Module> modules = parse("""
                module\t//app:app
                toolchain\t21
                javacopt\t--release
                javacopt\t11
                module\t//lib:lib
                toolchain\t21
                """, 17);

        // The override is a statement about the whole build, so it replaces what each module
        // declares rather than sitting beside it.
        check(modules.size() == 2, "expected two modules, got " + modules.size());
        check(modules.stream().allMatch(m -> Integer.valueOf(17).equals(m.jdkRelease())),
                "the override did not reach every module");
    }

    private static void rejectsMalformedLines() throws IOException {
        // An unknown line kind means the writer and this parser disagree, and a record before
        // any module means the manifest is truncated. Both would otherwise be absorbed into a
        // dump that looks fine and names the wrong things.
        expectFailure("unknown line kind", () -> parse("module\t//a:a\nnonsense\tvalue\n", null));
        expectFailure("record before any module", () -> parse("toolchain\t21\n", null));
    }

    private static void flagValueRejectsMissingAndEmptyValues() {
        String[] trailing = {"--before"};
        expectFailure("trailing flag", () -> Manifest.flagValue(trailing, 1));
        // The empty case is the quiet one: a CI variable that is not set arrives as "", and
        // Path.of("") is a perfectly good relative path.
        String[] empty = {"--before", ""};
        expectFailure("empty value", () -> Manifest.flagValue(empty, 1));

        String[] good = {"--before", "dump.json"};
        check("dump.json".equals(Manifest.flagValue(good, 1)), "flagValue dropped a real value");
    }

    private static void flagReleaseNamesTheFlagOnGarbage() {
        String[] garbage = {"--jdkRelease", "seventeen"};
        try {
            Manifest.flagRelease(garbage, 1);
            check(false, "flagRelease accepted a non-number");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("--jdkRelease"),
                    "the error does not name the flag: " + expected.getMessage());
        }

        String[] good = {"--jdkRelease", "17"};
        check(Integer.valueOf(17).equals(Manifest.flagRelease(good, 1)), "flagRelease misparsed");
    }

    /// Deliberately NOT a workspacePath test. Both of its branches return an absolute
    /// argument unchanged -- `Path.resolve` answers `other` verbatim when `other` is
    /// absolute -- so an absolute-path assertion holds even with the isAbsolute check
    /// deleted. The branch worth testing is a RELATIVE path resolving against
    /// BUILD_WORKSPACE_DIRECTORY, which `bazel test` does not set and which the method
    /// reads straight from System.getenv, so it needs a seam this class does not have.
    private static void flagValueIsUsedByTheBinariesThatHaveIt() {
        String[] args = {"--output", "dump.json", "--jdkRelease", "17"};
        check("dump.json".equals(Manifest.flagValue(args, 1)), "flagValue dropped a value");
        check(Integer.valueOf(17).equals(Manifest.flagRelease(args, 3)), "flagRelease misparsed");
    }

    /**
     * {@code exclude_files} rides ONE comma-joined property, so this ruleset is the only one
     * that has to take an exclude list apart -- everywhere else the build tool repeats a
     * flag or binds a list natively. Absolute values throughout, so the assertions can be
     * exact: {@code workspacePath} leaves those alone, while a relative one resolves against
     * a workspace root the test does not have.
     */
    private static void excludeFilesAreSplitTrimmedAndBlankDropped() {
        var two = UpgradeCheckMain.paths("/a/x.toml,/b/y.toml");
        check(two.size() == 2, "a two-entry list did not split into two");
        check(Path.of("/a/x.toml").equals(two.get(0)), "the first entry was mangled");
        check(Path.of("/b/y.toml").equals(two.get(1)), "the second entry was mangled");

        // A CI script assembling the value writes a space after the comma, and picks up a
        // doubled or trailing one. Untrimmed, " /b/y.toml" is a path that is not there;
        // undropped, "" is the workspace root handed to the CLI as an exclude file.
        var messy = UpgradeCheckMain.paths(" /a/x.toml , ,, /b/y.toml ,");
        check(messy.size() == 2, "trimming or blank-dropping changed the count: " + messy);
        check(Path.of("/a/x.toml").equals(messy.get(0)), "leading space survived trimming");
        check(Path.of("/b/y.toml").equals(messy.get(1)), "trailing space survived trimming");

        // The rule always sets the property, so the empty attribute arrives as "" rather
        // than absent, and property() maps that to null. Both mean no exclude file.
        check(UpgradeCheckMain.paths(null).isEmpty(), "null should name no exclude file");
        check(UpgradeCheckMain.paths("   ").isEmpty(), "a blank value should name none");
    }

    /**
     * One path is enough for the evidence knob because the conversions land beside whatever
     * it names -- inside a directory, next to a file. Getting the file case wrong writes the
     * converted logs into the recording's parent's parent, where the CLI never looks.
     */
    private static void conversionsLandInsideTheEvidenceLocation() throws IOException {
        Path dir = Files.createTempDirectory("uika-evidence");
        Path recording = Files.createTempFile(dir, "probe", ".jfr");
        try {
            check(dir.resolve(JfrEvidence.WORK_DIR_NAME).equals(UpgradeCheckMain.workDirFor(dir)),
                    "a directory entry should convert into itself");
            check(dir.resolve(JfrEvidence.WORK_DIR_NAME)
                            .equals(UpgradeCheckMain.workDirFor(recording)),
                    "a file entry should convert beside itself");
        } finally {
            Files.deleteIfExists(recording);
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Only a NEGATIVE jdkRelease means "derive". Zero is the off switch, and folding the two
     * together would make `jdk_release = 0` silently read the targets instead of switching
     * the layer off.
     */
    private static void aNonNegativeReleaseSkipsTheDerivation() throws IOException {
        check(Integer.valueOf(0).equals(UpgradeCheckMain.wantedRelease(0)),
                "0 must reach effectiveJdkRelease as the off switch, not the derivation");
        check(Integer.valueOf(11).equals(UpgradeCheckMain.wantedRelease(11)),
                "an explicit release must skip the derivation");
        // With no -Duika.releases there is no manifest to read, so it falls through to the
        // JVM running the tool, which is Bazel's own Java runtime.
        String releases = System.getProperty("uika.releases");
        check(releases == null || releases.isEmpty(),
                "this test needs uika.releases unset; the rule sets it, bazel test does not");
        check(Integer.valueOf(DumpFormat.buildJvmRelease()).equals(UpgradeCheckMain.wantedRelease(-1)),
                "with nothing to read from, the derivation is the build JVM");
    }

    private static List<Module> parse(String manifest, Integer override) throws IOException {
        Path file = Files.createTempFile("uika-manifest", ".tsv");
        Files.writeString(file, manifest, StandardCharsets.UTF_8);
        try {
            Function<String, Path> identity = Path::of;
            return Manifest.parse(file, override, identity);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private interface Thrower {
        void run() throws Exception;
    }

    private static void expectFailure(String what, Thrower body) {
        try {
            body.run();
            check(false, what + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Counted, not just tolerated: the floor in main is only a floor if the
            // passing path of every check registers.
            check(true, what);
        } catch (Exception unexpected) {
            check(false, what + " threw " + unexpected);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            System.err.println("FAIL: " + message);
            failures++;
        }
    }

    private ManifestSelfTest() {}
}
