package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Module;

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

    public static void main(String[] args) throws IOException {
        parsesModulesWithReleasesAndDeps();
        overrideReplacesEveryModulesRelease();
        rejectsMalformedLines();
        flagValueRejectsMissingAndEmptyValues();
        flagReleaseNamesTheFlagOnGarbage();
        workspacePathKeepsAnAbsolutePath();

        if (failures > 0) {
            System.err.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ManifestSelfTest: all checks passed");
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
        check("com.google.guava".equals(app.artifacts().get(0).group()), "coordinates lost");
        check(app.artifacts().get(0).project() == null, "an external dep gained a project");
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

    private static void workspacePathKeepsAnAbsolutePath() {
        Path absolute = Path.of("tmp", "before.json").toAbsolutePath();
        check(absolute.equals(Manifest.workspacePath(absolute.toString())),
                "an absolute path was rewritten");
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
            // what the guards throw
        } catch (Exception unexpected) {
            check(false, what + " threw " + unexpected);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.err.println("FAIL: " + message);
            failures++;
        }
    }

    private ManifestSelfTest() {}
}
