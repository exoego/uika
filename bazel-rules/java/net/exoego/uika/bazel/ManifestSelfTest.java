package net.exoego.uika.bazel;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import net.exoego.uika.plugin.core.JfrEvidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Unit tests for the ruleset's Java side, run as a plain {@code main}: the manifest parser,
 * the two path resolvers, the argument guards and the materializer.
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
        emptyManifestIsNoModules();
        rejectsMalformedLines();
        runfilesResolveThroughEitherConvention();
        execrootEntriesFallBackToTheOutputBase();
        relativePathsResolveAgainstTheWorkspace();
        flagValueRejectsMissingAndEmptyValues();
        flagOptionalDropsABlankButNotATrailingValue();
        flagReleaseNamesTheFlagOnGarbage();
        excludeFilesAreSplitTrimmedAndBlankDropped();
        conversionsLandInsideTheEvidenceLocation();
        aNonNegativeReleaseSkipsTheDerivation();
        materializeCopiesEveryJarOnce();
        materializeRefusesWhatItCannotCopy();
        materializeReplacesAStaleCopy();

        // A floor, not a total: `failures` counts only what FAILED, so a deleted call in
        // main or an early return inside a method would otherwise be a silent pass. The
        // class-file floor guard next door fails on an empty sweep for the same reason.
        var expected = 74;
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

    /**
     * A {@code uika_dump} with no targets writes one empty line, which has to read as no
     * modules rather than as a record before any module.
     */
    private static void emptyManifestIsNoModules() throws IOException {
        check(parse("\n", null).isEmpty(), "an empty manifest should be no modules");

        List<Module> modules = parse("module\t//app:app\n\ntoolchain\t21\n", null);
        check(modules.size() == 1 && Integer.valueOf(21).equals(modules.get(0).jdkRelease()),
                "a blank line should not cut the module before it short");
    }

    private static void rejectsMalformedLines() throws IOException {
        // An unknown line kind means the writer and this parser disagree, and a record before
        // any module means the manifest is truncated. Both would otherwise be absorbed into a
        // dump that looks fine and names the wrong things.
        expectFailure("unknown line kind", () -> parse("module\t//a:a\nnonsense\tvalue\n", null));
        expectFailure("record before any module", () -> parse("toolchain\t21\n", null));
    }

    /**
     * The test's own source rides in its runfiles, named by the BUILD file through
     * {@code $(rlocationpath)}, which is the form a {@code jvm_flags} entry carries. The
     * other form, a Starlark {@code short_path}, is derived from it the way Bazel spells the
     * two, so the check holds whether this suite runs with the ruleset as the main
     * repository or as the external module the integration workspace loads it as.
     */
    private static void runfilesResolveThroughEitherConvention() {
        String rlocation = System.getProperty("uika.selftest.runfile");
        check(rlocation != null && !rlocation.isEmpty(),
                "the BUILD file should pass -Duika.selftest.runfile");

        Path resolved = Manifest.resolveRunfile(rlocation);
        check(resolved.isAbsolute() && Files.isRegularFile(resolved),
                "the rlocationpath should resolve to a file, got " + resolved);
        // The runfiles entry is a symlink that the next build is free to replant. Only its
        // target is worth writing into a dump.
        check(!resolved.toString().contains(".runfiles"),
                "the runfiles symlink should be followed to its target, got " + resolved);

        String shortPath = rlocation.startsWith("_main/")
                ? rlocation.substring("_main/".length())
                : "../" + rlocation;
        check(resolved.equals(Manifest.resolveRunfile(shortPath)),
                "short_path and rlocationpath should name the same file");

        Exception missing = expectFailure("an entry outside the runfiles",
                IllegalStateException.class, () -> Manifest.resolveRunfile("no/such/entry.jar"));
        check(missing != null && missing.getMessage().contains("bazel run"),
                "the error should say how the binary has to be started");
    }

    /**
     * A sweep fragment names execution-root-relative paths, and the execution root's
     * external/ forest is replanted on every invocation, so a jar that is a source file of
     * an external module may only exist in the output base two levels up.
     */
    private static void execrootEntriesFallBackToTheOutputBase() throws IOException {
        Path outputBase = Files.createTempDirectory("uika-output-base");
        try {
            Path execroot = Files.createDirectories(outputBase.resolve("execroot/_main"));
            Path built = write(execroot.resolve("bazel-out/bin/app/app.jar"), "built");
            Path pruned = write(outputBase.resolve("external/vendored+/dep.jar"), "vendored");

            check(built.toRealPath().equals(Manifest.resolveExecroot(execroot, "bazel-out/bin/app/app.jar")),
                    "a build output should resolve under the execution root");
            check(pruned.toRealPath().equals(Manifest.resolveExecroot(execroot, "external/vendored+/dep.jar")),
                    "an external source file should be found in the output base");

            Exception missing = expectFailure("a jar under neither base",
                    IllegalStateException.class,
                    () -> Manifest.resolveExecroot(execroot, "external/gone+/dep.jar"));
            check(missing != null && missing.getMessage().contains(execroot.toString()),
                    "the error should name the execution root it looked under");
        } finally {
            deleteTree(outputBase);
        }

        // An execution root without two parents has no output base above it, and the
        // lookup must fall through to the error rather than walk off the top of the tree.
        Path root = Path.of("/");
        expectFailure("a root execution root", IllegalStateException.class,
                () -> Manifest.resolveExecroot(root, "dep.jar"));
        expectFailure("an execution root directly under the root", IllegalStateException.class,
                () -> Manifest.resolveExecroot(root.resolve("uika-no-such-execroot"), "dep.jar"));
    }

    /**
     * {@code bazel run} starts a binary inside its runfiles tree with the workspace root in
     * BUILD_WORKSPACE_DIRECTORY, and a relative {@code --output} has to land in the
     * workspace rather than in a tree the next build is free to delete. {@code bazel test}
     * exports no such variable, so the value goes in by hand.
     */
    private static void relativePathsResolveAgainstTheWorkspace() {
        check(Path.of("/ws/uika/out.json").equals(Manifest.workspacePath("uika/out.json", "/ws")),
                "a relative path should resolve against the workspace");
        check(Path.of("/elsewhere/out.json").equals(Manifest.workspacePath("/elsewhere/out.json", "/ws")),
                "an absolute path should be left alone");
        check(Path.of("uika/out.json").toAbsolutePath().equals(Manifest.workspacePath("uika/out.json", null)),
                "outside bazel run, a relative path resolves against the working directory");
        check(Path.of("/elsewhere/out.json").equals(Manifest.workspacePath("/elsewhere/out.json")),
                "the environment-reading form should agree on an absolute path");
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

    /**
     * {@code --failOn} is the one flag whose empty spelling means "unset", the way every
     * other integration reads it, so an unset CI variable must not fail the build. A trailing
     * flag is still a mistake.
     */
    private static void flagOptionalDropsABlankButNotATrailingValue() {
        String[] trailing = {"--failOn"};
        expectFailure("a trailing --failOn", () -> Manifest.flagOptional(trailing, 1));

        String[] blank = {"--failOn", ""};
        check(Manifest.flagOptional(blank, 1) == null, "a blank --failOn should read as unset");

        String[] set = {"--failOn", "never"};
        check("never".equals(Manifest.flagOptional(set, 1)), "flagOptional dropped a real value");
    }

    private static void flagReleaseNamesTheFlagOnGarbage() {
        String[] garbage = {"--jdkRelease", "seventeen"};
        Exception rejected = expectFailure("a non-numeric --jdkRelease",
                IllegalArgumentException.class, () -> Manifest.flagRelease(garbage, 1));
        check(rejected != null && rejected.getMessage().contains("--jdkRelease"),
                "the error does not name the flag: " + rejected);

        String[] good = {"--jdkRelease", "17"};
        check(Integer.valueOf(17).equals(Manifest.flagRelease(good, 1)), "flagRelease misparsed");
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

    /**
     * Two classpath entries can share a file name (one jar at two versions, or a build
     * output named like a dependency) and must not overwrite each other, while one entry
     * reached from two modules is copied once and both modules point at that copy.
     */
    private static void materializeCopiesEveryJarOnce() throws IOException {
        Path tmp = Files.createTempDirectory("uika-materialize");
        try {
            Path lib = write(tmp.resolve("bin/lib/lib.jar"), "lib");
            Path app = write(tmp.resolve("bin/app/app.jar"), "app");
            Path guava22 = write(tmp.resolve("22/guava.jar"), "22");
            Path guava23 = write(tmp.resolve("23/guava.jar"), "23");
            var v22 = new Artifact("com.google.guava", "guava", "22.0", guava22.toString());
            var v23 = new Artifact("com.google.guava", "guava", "23.0", guava23.toString());
            var libDep = new Artifact(null, null, null, lib.toString(), "//lib:lib");
            List<Module> modules = List.of(
                    new Module("//lib:lib", List.of(lib.toString()), List.of(v22, v23), 11),
                    new Module("//app:app", List.of(app.toString()), List.of(v22, libDep), 17));
            Path out = tmp.resolve("baseline-jars");

            List<Module> moved = Materialize.into(modules, out);

            Module movedLib = moved.get(0);
            check("//lib:lib".equals(movedLib.path()) && Integer.valueOf(11).equals(movedLib.jdkRelease()),
                    "module name and release should survive: " + movedLib.path());
            check(movedLib.classesDirs().equals(List.of(out.resolve("lib.jar").toString())),
                    "the module's own jar should move too: " + movedLib.classesDirs());
            var first = movedLib.artifacts().get(0);
            var second = movedLib.artifacts().get(1);
            check(out.resolve("guava.jar").toString().equals(first.file()),
                    "the first guava should keep its name: " + first.file());
            check(out.resolve("2-guava.jar").toString().equals(second.file()),
                    "the second guava must not overwrite the first: " + second.file());
            check("22".equals(Files.readString(out.resolve("guava.jar")))
                            && "23".equals(Files.readString(out.resolve("2-guava.jar"))),
                    "each copy should carry its own bytes");
            check("com.google.guava".equals(second.group()) && "23.0".equals(second.version()),
                    "coordinates should survive the move");

            Module movedApp = moved.get(1);
            check(first.file().equals(movedApp.artifacts().get(0).file()),
                    "one source reached from two modules should point at the one copy");
            check("//lib:lib".equals(movedApp.artifacts().get(1).project())
                            && movedLib.classesDirs().get(0).equals(movedApp.artifacts().get(1).file()),
                    "a project dependency keeps its attribution and shares its module's copy");
            try (var copies = Files.list(out)) {
                check(copies.count() == 4, "four distinct files, copied once each");
            }
        } finally {
            deleteTree(tmp);
        }
    }

    /**
     * {@code Files.copy} on a directory produces an EMPTY one and reports success, and
     * copying over a jar the dump itself names destroys the bytes about to be copied. The
     * second is easy to reach with {@code --materialize} pointed at a vendor directory: a
     * same-named jar from elsewhere on the classpath claims the name first, so comparing
     * the destination against the entry being copied is not enough.
     */
    private static void materializeRefusesWhatItCannotCopy() throws IOException {
        Path tmp = Files.createTempDirectory("uika-materialize");
        try {
            Path classes = Files.createDirectories(tmp.resolve("classes"));
            var directory = List.of(new Module("//app:app", List.of(classes.toString()), List.of()));
            Exception refused = expectFailure("a directory entry", IOException.class,
                    () -> Materialize.into(directory, tmp.resolve("out")));
            check(refused != null && refused.getMessage().contains(classes.toString()),
                    "the refusal should name the directory: " + refused);

            Path vendored = write(tmp.resolve("vendor/dep.jar"), "vendored");
            Path cached = write(tmp.resolve("cache/dep.jar"), "cached");
            var deps = List.of(new Module("//app:app", List.of(), List.of(
                    new Artifact("g", "dep", "2", cached.toString()),
                    new Artifact("g", "dep", "1", vendored.toString()))));
            refused = expectFailure("a destination the dump names", IOException.class,
                    () -> Materialize.into(deps, tmp.resolve("vendor")));
            check(refused != null && refused.getMessage().contains("itself on the classpath"),
                    "the refusal should say why the directory is unusable: " + refused);
            check("vendored".equals(Files.readString(vendored)),
                    "the refusal has to come before anything is deleted");
        } finally {
            deleteTree(tmp);
        }
    }

    /** A copy a previous run left in the destination is replaced, since the jar may have changed. */
    private static void materializeReplacesAStaleCopy() throws IOException {
        Path tmp = Files.createTempDirectory("uika-materialize");
        try {
            Path fresh = write(tmp.resolve("bazel-out/dep.jar"), "fresh");
            Path out = tmp.resolve("out");
            write(out.resolve("dep.jar"), "stale");
            var modules = List.of(new Module("//app:app", List.of(),
                    List.of(new Artifact("g", "dep", "1", fresh.toString()))));

            Materialize.into(modules, out);

            check("fresh".equals(Files.readString(out.resolve("dep.jar"))),
                    "the stale copy should be replaced");
        } finally {
            deleteTree(tmp);
        }
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

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private interface Thrower {
        void run() throws Exception;
    }

    private static void expectFailure(String what, Thrower body) {
        expectFailure(what, IllegalArgumentException.class, body);
    }

    /** The exception {@code body} threw, for a check on its message, or null when it did not. */
    private static Exception expectFailure(String what, Class<? extends Exception> expected,
            Thrower body) {
        try {
            body.run();
            check(false, what + " was accepted");
        } catch (Exception thrown) {
            if (expected.isInstance(thrown)) {
                // Counted, not just tolerated: the floor in main is only a floor if the
                // passing path of every check registers.
                check(true, what);
                return thrown;
            }
            check(false, what + " threw " + thrown);
        }
        return null;
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
