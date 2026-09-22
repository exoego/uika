package net.exoego.uika.cli;

import static net.exoego.uika.cli.GoldenTest.fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-module upgrade-check half of the Rust crate's tests/integration.rs, from
 * {@code run_uika} downward. End-to-end coverage of flags, JSON shape and exit code.
 */
class UpgradeCheckIntegrationTest {
    @TempDir
    Path tempDir;

    record Run(int code, String stdout, String stderr) {}

    /** The Rust tests spawn the binary. The port runs the same entry point in-process. */
    static Run runUika(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Writes the before/after dump JSON into a scratch dir, runs {@code uika upgrade-check}
     * with any extra args, and returns (exit code, stdout, stderr). The six per-module e2e
     * tests differ only in their dump JSON and assertions.
     */
    private Run runUpgradeCheckWithDumps(String tag, String before, String after, String... extra) throws IOException {
        Path dir = tempDir.resolve("uika-" + tag);
        Files.createDirectories(dir);
        Path beforePath = dir.resolve("before.json");
        Path afterPath = dir.resolve("after.json");
        Files.writeString(beforePath, before, StandardCharsets.UTF_8);
        Files.writeString(afterPath, after, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("upgrade-check", "--before", beforePath.toString(), "--after", afterPath.toString()));
        args.addAll(List.of(extra));
        return runUika(args.toArray(new String[0]));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertNotNull(value, "expected a JSON object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        assertNotNull(value, "expected a JSON array");
        return (List<Object>) value;
    }

    private static Map<String, Object> parse(String stdout) throws Json.ParseException {
        return object(Json.parse(stdout));
    }

    /** Whether some violation has this source class and exactly this module attribution. */
    private static boolean hasViolation(List<Object> violations, String sourceClass, List<String> modules) {
        for (Object v : violations) {
            Map<String, Object> violation = object(v);
            if (sourceClass.equals(violation.get("source_class")) && modules.equals(violation.get("modules"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean noneAttributedTo(List<Object> violations, String module) {
        for (Object v : violations) {
            if (array(object(v).get("modules")).contains(module)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Per-module upgrade-check judges each module against its own resolution. Two properties
     * on one fixture layout (the netty shape from a real multi-module monorepo incident).
     *
     * <p>A module pinned to the old version is skipped, so its jar's classes are never judged
     * against the sibling module's newer version (the cross-version false-positive class).
     *
     * <p>The merged universe still resolves the old version somewhere (the pinned module), so
     * the flat diff has no old jars and reports NOTHING for the real break in the upgrading
     * module (a false negative per-module mode fixes).
     */
    @Test
    void perModuleUpgradeCheckGatesOnEachModulesOwnResolution() throws Exception {
        String oldSc = fixture("opentelemetry-sdk-common-1.42.1.jar");
        String newSc = fixture("opentelemetry-sdk-common-1.60.1.jar");
        String sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");
        BiFunction<String, String, String> dump = (appSc, appScVersion) -> """
                {"modules":[
                    {"module":":app","classesDirs":[],"artifacts":[
                        {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"%s","file":"%s"},
                        {"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"%s"}
                    ]},
                    {"module":":pinned","classesDirs":[],"artifacts":[
                        {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"1.42.1","file":"%s"},
                        {"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"%s"}
                    ]}
                ]}"""
                .formatted(appScVersion, appSc, sender, oldSc, sender);
        String before = dump.apply(oldSc, "1.42.1");
        String after = dump.apply(newSc, "1.60.1");

        // Per-module is the default. :app's upgrade is caught and attributed, and :pinned is
        // skipped.
        Run run = runUpgradeCheckWithDumps("permod-e2e", before, after, "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        List<Object> violations = array(json.get("violations"));
        assertTrue(
                hasViolation(violations, "io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil", List.of(":app")),
                "expected the :app-attributed DaemonThreadFactory break:\n" + run.stdout());
        assertTrue(
                noneAttributedTo(violations, ":pinned"),
                "the pinned module must never be judged against the sibling's upgrade:\n" + run.stdout());
        Map<String, Object> moduleRuns = object(json.get("module_runs"));
        List<Object> runs = array(moduleRuns.get("outcomes"));
        assertEquals(1, runs.size(), run.stdout());
        assertEquals(List.of(":app"), object(runs.get(0)).get("modules"));
        assertEquals(1L, moduleRuns.get("unchanged_modules"), run.stdout());

        // Text mode carries the same attribution for humans.
        run = runUpgradeCheckWithDumps("permod-e2e", before, after);
        assertEquals(1, run.code());
        assertTrue(run.stdout().contains("per-module check: 1 of 2 modules"), run.stdout());
        assertTrue(run.stdout().contains("affected modules: :app"), run.stdout());

        // --merged-classpath keeps the flat behavior. 1.42.1 is still resolved by :pinned, so
        // the flat diff has no removed version and the real break in :app goes unreported.
        run = runUpgradeCheckWithDumps("permod-e2e", before, after, "--merged-classpath", "--json");
        assertEquals(0, run.code(), run.stdout());
        json = parse(run.stdout());
        assertNull(json.get("violations"), run.stdout());
    }

    /**
     * A project-dependency artifact that was never built (jar path missing) falls back to the
     * producing module's classesDirs from the same dump, so the reference is still checked
     * instead of being silently skipped.
     */
    @Test
    void perModuleProjectDependencyFallsBackToClassesDirs() throws Exception {
        String oldSc = fixture("opentelemetry-sdk-common-1.42.1.jar");
        String newSc = fixture("opentelemetry-sdk-common-1.60.1.jar");
        String sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

        // :app depends on project :sender-lib whose jar is unbuilt. :sender-lib's classesDirs
        // stand in for its output. A jar path works there, since scan targets may be jars or
        // dirs.
        BiFunction<String, String, String> dump = (sc, version) -> """
                {"modules":[
                    {"module":":app","classesDirs":[],"artifacts":[
                        {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"%s","file":"%s"},
                        {"file":"/nonexistent/uika-test/sender-lib.jar","project":":sender-lib"}
                    ]},
                    {"module":":sender-lib","classesDirs":["%s"],"artifacts":[]}
                ]}"""
                .formatted(version, sc, sender);

        Run run = runUpgradeCheckWithDumps(
                "permod-fallback", dump.apply(oldSc, "1.42.1"), dump.apply(newSc, "1.60.1"), "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertTrue(
                hasViolation(
                        array(json.get("violations")),
                        "io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil",
                        List.of(":app")),
                "the fallback classesDirs must be scanned in :app's run:\n" + run.stdout());
        assertTrue(
                run.stderr().contains("is not built; scanning module :sender-lib's classesDirs"),
                "stderr:\n" + run.stderr());
    }

    /**
     * The stay-vs-upgrade shape. Module :pinned stays on a THIRD version of the coordinate
     * (not the upgrading module's old version), module :upgrader moves old -> new across a
     * breaking change. The pinned module's classpath must never be judged against the
     * upgrading module's new version, and the break must be attributed to :upgrader alone.
     */
    @Test
    void perModuleCheckDetectsUpgradeBesideModulePinnedToThirdVersion() throws Exception {
        String coroutinesOld = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
        String coroutinesNew = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
        String ktorIo = fixture("ktor-io-jvm-2.3.13.jar");

        // :pinned resolves a distinct third version. The version string is what the diff
        // sees, and the 1.7.1 jar stands in for its bytes. :upgrader moves 1.7.1 -> 1.11.0,
        // which removed EventLoopKt.processNextEventInCurrentThread that ktor-io references.
        BiFunction<String, String, String> dump = (upgraderVersion, upgraderJar) -> """
                {"modules":[
                    {"module":":pinned","classesDirs":[],"artifacts":[
                        {"group":"org.jetbrains.kotlinx","name":"kotlinx-coroutines-core-jvm","version":"1.5.0","file":"%s"},
                        {"group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13","file":"%s"}
                    ]},
                    {"module":":upgrader","classesDirs":[],"artifacts":[
                        {"group":"org.jetbrains.kotlinx","name":"kotlinx-coroutines-core-jvm","version":"%s","file":"%s"},
                        {"group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13","file":"%s"}
                    ]}
                ]}"""
                .formatted(coroutinesOld, ktorIo, upgraderVersion, upgraderJar, ktorIo);

        Run run = runUpgradeCheckWithDumps(
                "thirdver-e2e", dump.apply("1.7.1", coroutinesOld), dump.apply("1.11.0", coroutinesNew), "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        List<Object> violations = array(json.get("violations"));
        assertTrue(
                hasViolation(violations, "io/ktor/utils/io/jvm/javaio/BlockingAdapter", List.of(":upgrader")),
                "expected the BlockingAdapter break attributed to :upgrader only:\n" + run.stdout());
        assertTrue(
                noneAttributedTo(violations, ":pinned"),
                "the third-version pinned module must never be judged against 1.11.0:\n" + run.stdout());
        Map<String, Object> moduleRuns = object(json.get("module_runs"));
        assertEquals(1L, moduleRuns.get("unchanged_modules"), run.stdout());
        List<Object> runs = array(moduleRuns.get("outcomes"));
        assertEquals(1, runs.size(), run.stdout());
        assertEquals(List.of(":upgrader"), object(runs.get(0)).get("modules"));
    }

    /**
     * A version swap between modules leaves the universe-wide change list empty, but the
     * per-module diffs still find the break. The text report must show it (an empty global
     * header must not swallow the violations) and the per-run suggestion must carry the
     * module's own before -> after versions.
     */
    @Test
    void perModuleCheckReportsBreakWhenGlobalVersionSetIsUnchanged() throws Exception {
        String oldSc = fixture("opentelemetry-sdk-common-1.42.1.jar");
        String newSc = fixture("opentelemetry-sdk-common-1.60.1.jar");
        String sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

        BiFunction<String[], String[], String> dump = (a, b) -> """
                {"modules":[
                    {"module":":a","classesDirs":[],"artifacts":[
                        {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"%s","file":"%s"},
                        {"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"%s"}
                    ]},
                    {"module":":b","classesDirs":[],"artifacts":[
                        {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"%s","file":"%s"}
                    ]}
                ]}"""
                .formatted(a[0], a[1], sender, b[0], b[1]);

        // :a upgrades 1.42.1 -> 1.60.1 (break) and :b downgrades 1.60.1 -> 1.42.1, so the
        // union version set {1.42.1, 1.60.1} is identical on both sides.
        Run run = runUpgradeCheckWithDumps(
                "swap-e2e",
                dump.apply(new String[] {"1.42.1", oldSc}, new String[] {"1.60.1", newSc}),
                dump.apply(new String[] {"1.60.1", newSc}, new String[] {"1.42.1", oldSc}));
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertTrue(run.stdout().contains("dependency changes: none"), run.stdout());
        // Both sides of the swap changed their own resolution (a downgrade is a change too).
        assertTrue(run.stdout().contains("per-module check: 2 of 2 modules"), run.stdout());
        assertTrue(
                run.stdout().contains("io.opentelemetry.sdk.internal.DaemonThreadFactory"),
                "the swap-hidden break must still be reported:\n" + run.stdout());
        // The per-run suggestion quotes :a's own move, which the empty global list cannot.
        assertTrue(
                run.stdout().contains("why: io.opentelemetry:opentelemetry-sdk-common changed 1.42.1 -> 1.60.1"),
                run.stdout());
    }

    /**
     * A module renamed while upgrading (no same-name before module) is checked against the
     * union's before versions instead of being silently skipped.
     */
    @Test
    void perModuleCheckCoversRenamedModuleViaUnionFallback() throws Exception {
        String oldSc = fixture("opentelemetry-sdk-common-1.42.1.jar");
        String newSc = fixture("opentelemetry-sdk-common-1.60.1.jar");
        String sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

        BiFunction<String, String[], String> dump = (name, sc) -> """
                {"modules":[
                    {"module":"%s","classesDirs":[],"artifacts":[
                        {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"%s","file":"%s"},
                        {"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"%s"}
                    ]}
                ]}"""
                .formatted(name, sc[0], sc[1], sender);

        Run run = runUpgradeCheckWithDumps(
                "rename-e2e",
                dump.apply(":server", new String[] {"1.42.1", oldSc}),
                dump.apply(":backend", new String[] {"1.60.1", newSc}),
                "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertTrue(run.stderr().contains("module :backend is not in the before dump"), "stderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertTrue(
                hasViolation(
                        array(json.get("violations")),
                        "io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil",
                        List.of(":backend")),
                "the renamed module's upgrade must still be checked:\n" + run.stdout());
    }

    /**
     * An after-side module whose artifact list vanished (partial build, failed resolution) is
     * skipped as incomplete instead of being diffed as "every dependency removed".
     */
    @Test
    void perModuleCheckSkipsModuleWithVanishedArtifacts() throws Exception {
        String oldSc = fixture("opentelemetry-sdk-common-1.42.1.jar");
        String sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

        String before = """
                {"modules":[
                    {"module":":a","classesDirs":[],"artifacts":[
                        {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"1.42.1","file":"%s"},
                        {"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"%s"}
                    ]}
                ]}"""
                .formatted(oldSc, sender);
        // :a still exists but resolved nothing. A second module keeps per-module mode on.
        String after = """
                {"modules":[
                    {"module":":a","classesDirs":[],"artifacts":[]},
                    {"module":":b","classesDirs":[],"artifacts":[
                        {"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"1.42.1","file":"%s"}
                    ]}
                ]}"""
                .formatted(oldSc);

        Run run = runUpgradeCheckWithDumps("incomplete-e2e", before, after, "--json");
        assertEquals(0, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertTrue(
                run.stderr().contains("module :a lists no resolved artifacts in the after dump"),
                "stderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertEquals(1L, object(json.get("module_runs")).get("incomplete_modules"), run.stdout());
        assertNull(json.get("violations"), run.stdout());
    }

    // ---- dumps built from parts ----

    private static final String OLD_COROUTINES = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
    private static final String NEW_COROUTINES = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
    private static final String KTOR_IO = fixture("ktor-io-jvm-2.3.13.jar");
    /** A real jar unrelated to ktor and coroutines, so as a root it never reaches BlockingAdapter. */
    private static final String UNRELATED = fixture("koin-logger-slf4j-3.2.2.jar");
    private static final String BLOCKING_ADAPTER = "io/ktor/utils/io/jvm/javaio/BlockingAdapter";

    private static String artifact(String group, String name, String version, String file) {
        return "{\"group\":\"%s\",\"name\":\"%s\",\"version\":\"%s\",\"file\":\"%s\"}".formatted(group, name, version, file);
    }

    private static String coroutines(String version, String file) {
        return artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", version, file);
    }

    private static String ktorIo() {
        return artifact("io.ktor", "ktor-io-jvm", "2.3.13", KTOR_IO);
    }

    private static String unrelated() {
        return artifact("io.insert-koin", "koin-logger-slf4j", "3.2.2", UNRELATED);
    }

    private static String module(String name, List<String> classesDirs, String... artifacts) {
        List<String> dirs = new ArrayList<>();
        for (String dir : classesDirs) {
            dirs.add("\"" + dir + "\"");
        }
        return "{\"module\":\"%s\",\"classesDirs\":[%s],\"artifacts\":[%s]}".formatted(name, String.join(",", dirs), String.join(",", artifacts));
    }

    private static String dump(String... modules) {
        return "{\"modules\":[" + String.join(",", modules) + "]}";
    }

    private static List<Object> outcomeModules(Map<String, Object> json) {
        List<Object> modules = new ArrayList<>();
        for (Object o : array(object(json.get("module_runs")).get("outcomes"))) {
            modules.add(object(o).get("modules"));
        }
        return modules;
    }

    /**
     * Modules with identical inputs share one run, and a break two runs both find is one
     * violation attributed to all their modules. The per-run broken counts still give each
     * run its own number.
     */
    @Test
    void identicalModulesShareOneRunAndABreakCountsOnce() throws Exception {
        String before = dump(
                module(":a", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo()),
                module(":b", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo()),
                module(":c", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo(), unrelated()));
        String after = before.replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES));
        Path verdicts = tempDir.resolve("verdicts.jsonl");

        Run run = runUpgradeCheckWithDumps("shared-e2e", before, after, "--json", "--verdicts-json", verdicts.toString());
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertEquals(List.of(List.of(":a", ":b"), List.of(":c")), outcomeModules(json), run.stdout());
        for (Object o : array(object(json.get("module_runs")).get("outcomes"))) {
            assertEquals(1L, object(o).get("broken"), run.stdout());
        }
        List<Object> violations = array(json.get("violations"));
        assertEquals(1, violations.size(), run.stdout());
        assertTrue(hasViolation(violations, BLOCKING_ADAPTER, List.of(":a", ":b", ":c")), run.stdout());

        // Every verdict names the run that produced it, a shared run by all its modules.
        List<String> labels = new ArrayList<>();
        for (String line : Files.readAllLines(verdicts)) {
            String label = (String) object(Json.parse(line)).get("module");
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        assertEquals(List.of(":a,:b", ":c"), labels);

        run = runUpgradeCheckWithDumps("shared-e2e", before, after);
        assertTrue(run.stdout().contains("per-module check: 3 of 3 modules changed their resolved versions (0 unchanged)\n    :a, :b  scanned "),
                run.stdout());
    }

    /**
     * Runs that disagree on reachability keep the most reachable answer, so a break is never
     * ranked down by a run that could not see it.
     */
    @Test
    void aMergedBreakKeepsTheMostReachableAnswer() throws Exception {
        // :b has no roots (not computed), :a roots at the referencing jar (reachable), :c at
        // an unrelated one (proven not). The unreachable :c comes after :a, so it must not win.
        String before = dump(
                module(":b", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo()),
                module(":a", List.of(KTOR_IO), coroutines("1.7.1", OLD_COROUTINES), ktorIo()),
                module(":c", List.of(UNRELATED), coroutines("1.7.1", OLD_COROUTINES), ktorIo()));
        String after = before.replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES));

        Run run = runUpgradeCheckWithDumps("reach-merge-e2e", before, after, "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertEquals(3, outcomeModules(json).size(), run.stdout());
        List<Object> violations = array(json.get("violations"));
        assertEquals(1, violations.size(), run.stdout());
        Map<String, Object> violation = object(violations.get(0));
        assertEquals(List.of(":a", ":b", ":c"), violation.get("modules"), run.stdout());
        assertEquals(Boolean.TRUE, violation.get("reachable"), run.stdout());
    }

    /**
     * The jOOQ 3.17 ExecuteListener.end shape (synthetic-abstract-added). One module only
     * implements the listener, which is latent; another also calls end(), which breaks. The
     * merged break is the invoked one, so --fail-on reachable fails for it.
     */
    @Test
    void aBreakInvokedInOneModuleIsInvokedForAll() throws Exception {
        String lib1 = artifact("fixture", "lib", "1.0", fixture("synthetic-abstract-added-1.0.jar"));
        String lib2 = artifact("fixture", "lib", "2.0", fixture("synthetic-abstract-added-2.0.jar"));
        String consumer = artifact("fixture", "app", "1.0", fixture("synthetic-abstract-added-consumer.jar"));
        String caller = artifact("fixture", "caller", "1.0", fixture("synthetic-abstract-added-methodref-caller.jar"));
        String serviceOnly = dump(module(":service", List.of(), lib1, consumer));
        Run latent = runUpgradeCheckWithDumps(
                "latent-e2e", serviceOnly, serviceOnly.replace(lib1, lib2), "--json", "--fail-on", "reachable");
        assertEquals(0, latent.code(), "stdout:\n" + latent.stdout() + "\nstderr:\n" + latent.stderr());
        assertEquals(Boolean.FALSE, object(array(parse(latent.stdout()).get("violations")).get(0)).get("invocation_found"));

        String both = dump(module(":service", List.of(), lib1, consumer), module(":web", List.of(), lib1, consumer, caller));
        Run run = runUpgradeCheckWithDumps("invoked-e2e", both, both.replace(lib1, lib2), "--json", "--fail-on", "reachable");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        List<Object> violations = array(parse(run.stdout()).get("violations"));
        assertEquals(1, violations.size(), run.stdout());
        Map<String, Object> violation = object(violations.get(0));
        assertEquals(List.of(":service", ":web"), violation.get("modules"), run.stdout());
        assertEquals(Boolean.TRUE, violation.get("invocation_found"), run.stdout());
    }

    /**
     * Two modules upgrading from one version to different new ones get different advice, so
     * the same break is listed once per advice. The order must not depend on which module the
     * dump lists first.
     */
    @Test
    void oneBreakWithDifferentAdviceIsOrderedByModule() throws Exception {
        // The version string is what the diff sees, so a copy of the 1.11.0 jar stands in for 1.10.0.
        Path stand = Files.copy(Path.of(NEW_COROUTINES), tempDir.resolve("kotlinx-coroutines-core-jvm-1.10.0.jar"));
        String before = dump(
                module(":b", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo()),
                module(":a", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo()));
        String after = dump(
                module(":b", List.of(), coroutines("1.10.0", stand.toString()), ktorIo()),
                module(":a", List.of(), coroutines("1.11.0", NEW_COROUTINES), ktorIo()));

        Run run = runUpgradeCheckWithDumps("advice-order-e2e", before, after, "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        List<Object> violations = array(parse(run.stdout()).get("violations"));
        assertEquals(2, violations.size(), run.stdout());
        Map<String, Object> first = object(violations.get(0));
        Map<String, Object> second = object(violations.get(1));
        assertEquals(List.of(":a"), first.get("modules"), run.stdout());
        assertTrue(((String) object(first.get("suggestion")).get("advice")).contains("kotlinx-coroutines-core-jvm 1.11.0, or pin"), run.stdout());
        assertEquals(List.of(":b"), second.get("modules"), run.stdout());
        assertTrue(((String) object(second.get("suggestion")).get("advice")).contains("kotlinx-coroutines-core-jvm 1.10.0, or pin"), run.stdout());
    }

    /**
     * A module with no classes of its own, running on a sibling's unbuilt jar, scans exactly
     * what the sibling scans. Its roots differ, so it cannot share the sibling's run.
     */
    @Test
    void theSameTargetsWithDifferentRootsAreSeparateRuns() throws Exception {
        // ktor-io stands in for :core's own classes.
        String coreJar = "{\"file\":\"/nonexistent/uika-test/core.jar\",\"project\":\":core\"}";
        String before = dump(
                module(":core", List.of(KTOR_IO), coroutines("1.7.1", OLD_COROUTINES)),
                module(":app", List.of(), coreJar, coroutines("1.7.1", OLD_COROUTINES)));
        String after = before.replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES));

        Run run = runUpgradeCheckWithDumps("roots-e2e", before, after, "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertTrue(run.stderr().contains("/nonexistent/uika-test/core.jar is not built; scanning module :core's classesDirs instead"),
                run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertEquals(List.of(List.of(":core"), List.of(":app")), outcomeModules(json), run.stdout());
        List<Object> violations = array(json.get("violations"));
        assertEquals(1, violations.size(), run.stdout());
        assertEquals(List.of(":app", ":core"), object(violations.get(0)).get("modules"), run.stdout());
        // Reachable from :core's own classes, where :app has none to walk from.
        assertEquals(Boolean.TRUE, object(violations.get(0)).get("reachable"), run.stdout());
    }

    /** A missing jar is reported once, with every module that needed it, and the rest is still checked. */
    @Test
    void aMissingScanTargetIsReportedOnceWithTheModulesThatNeedIt() throws Exception {
        String gone = "{\"file\":\"/nonexistent/uika-test/gone.jar\"}";
        // A project whose module has no classesDirs to stand in, and one the dump does not have.
        String unbuilt = "{\"file\":\"/nonexistent/uika-test/lib.jar\",\"project\":\":lib\"}";
        String foreign = "{\"file\":\"/nonexistent/uika-test/other.jar\",\"project\":\":elsewhere\"}";
        String before = dump(
                module(":a", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo(), gone, unbuilt, foreign),
                module(":b", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo(), gone),
                module(":lib", List.of()));
        String after = before.replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES));

        Run run = runUpgradeCheckWithDumps("missing-e2e", before, after, "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertEquals(
                "warning: scan target not found, skipping: /nonexistent/uika-test/gone.jar (needed by :a, :b)\n"
                        + "warning: scan target not found, skipping: /nonexistent/uika-test/lib.jar (needed by :a)\n"
                        + "warning: scan target not found, skipping: /nonexistent/uika-test/other.jar (needed by :a)\n",
                run.stderr());
        assertTrue(hasViolation(array(parse(run.stdout()).get("violations")), BLOCKING_ADAPTER, List.of(":a", ":b")), run.stdout());
    }

    /** A renamed module that also gained a dependency is still checked for the upgrade it made. */
    @Test
    void aRenamedModuleWithANewDependencyIsStillChecked() throws Exception {
        String before = dump(module(":server", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo()));
        String after = dump(module(":backend", List.of(), coroutines("1.11.0", NEW_COROUTINES), ktorIo(), unrelated()));

        Run run = runUpgradeCheckWithDumps("rename-added-e2e", before, after, "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertTrue(hasViolation(array(json.get("violations")), BLOCKING_ADAPTER, List.of(":backend")), run.stdout());
        assertTrue(run.stderr().contains("module :backend is not in the before dump"), run.stderr());
        Set<Object> kinds = new HashSet<>();
        for (Object change : array(json.get("changes"))) {
            kinds.add(object(change).get("coordinate") + " " + object(change).get("kind"));
        }
        assertEquals(Set.of("io.insert-koin:koin-logger-slf4j added", "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm changed"), kinds);
    }

    /**
     * Exclude rules filter the merged set once. Applied per run, a rule that suppresses the
     * break in one run would be reported unused by every other run.
     */
    @Test
    void excludeRulesApplyOnceToTheMergedSet() throws Exception {
        String before = dump(
                module(":a", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo()),
                module(":b", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo(), unrelated()));
        String after = before.replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES));
        Path rules = Files.writeString(tempDir.resolve("exclude.toml"), """
                [[exclude]]
                owner = "kotlinx/coroutines/EventLoopKt"
                reason = "ktor-io is upgraded in the same release"

                [[exclude]]
                owner = "com/example/Gone"
                reason = "left over from an older upgrade"
                """);

        Run run = runUpgradeCheckWithDumps("exclude-e2e", before, after, "--json", "--exclude-file", rules.toString());
        assertEquals(0, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertEquals("warning: exclude rule matched nothing: com/example/Gone (left over from an older upgrade)\n", run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertEquals(1L, json.get("suppressed"), run.stdout());
        assertEquals(List.of(), json.get("violations"), run.stdout());
        for (Object o : array(object(json.get("module_runs")).get("outcomes"))) {
            assertEquals(0L, object(o).get("broken"), run.stdout());
        }
    }

    /**
     * A module whose roots matched nothing has reachable=false with nothing behind it.
     * Drafting judges all runs' roots together, so one such module stops the others' breaks
     * from being drafted as waivable. The per-run gate still fails on them.
     */
    @Test
    void oneModuleWithUnmatchedRootsStopsDrafting() throws Exception {
        Path log = Files.writeString(tempDir.resolve("loads.log"), "[class,load] com.example.Started\n");
        // Its jar is the same file on both sides, so the run scans nothing and has no roots.
        String noRoots = module(":n", List.of(), artifact("io.insert-koin", "koin-logger-slf4j", "3.2.1", UNRELATED));
        String rooted = module(":a", List.of(UNRELATED), coroutines("1.7.1", OLD_COROUTINES), ktorIo());
        String before = dump(noRoots, rooted);
        String after = before.replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES))
                .replace("\"3.2.1\"", "\"3.2.2\"");
        Path draft = tempDir.resolve("draft.toml");
        Run run = runUpgradeCheckWithDumps(
                "draft-e2e", before, after, "--class-load-log", log.toString(), "--draft-exclude-file", draft.toString());
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertTrue(run.stderr().contains("note: drafted 1 exclude rule(s) to " + draft + "\n"), run.stderr());
        assertTrue(Files.readString(draft).contains("owner = \"kotlinx/coroutines/EventLoopKt\"\n"), Files.readString(draft));

        Path unbuilt = Files.createDirectories(tempDir.resolve("unbuilt-classes"));
        String unmatched = module(":b", List.of(unbuilt.toString()), coroutines("1.7.1", OLD_COROUTINES), ktorIo());
        String healthy = module(":c", List.of(UNRELATED), coroutines("1.7.1", OLD_COROUTINES), ktorIo(), unrelated());
        before = dump(rooted, unmatched, healthy);
        after = before.replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES));
        run = runUpgradeCheckWithDumps(
                "draft-unmatched-e2e", before, after, "--class-load-log", log.toString(), "--draft-exclude-file", draft.toString(),
                "--fail-on", "reachable");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertTrue(run.stderr().contains("note: drafted 0 exclude rule(s) to " + draft + "\n"), run.stderr());
    }

    /** Without per-module data on both sides the check falls back to the merged universe, and says so. */
    @Test
    void anAfterDumpWithoutModuleNamesIsCheckedMerged() throws Exception {
        String before = dump(module(":app", List.of(), coroutines("1.7.1", OLD_COROUTINES), ktorIo()));
        // A v2 dump with an unnamed module cannot be paired by name.
        String after = """
                {"version":2,"roots":[""],"artifacts":[
                    {"root":0,"path":"%s","group":"org.jetbrains.kotlinx","name":"kotlinx-coroutines-core-jvm","version":"1.11.0"},
                    {"root":0,"path":"%s","group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13"}
                ],"modules":[{"classesDirs":[],"artifactRefs":[0,1]}]}"""
                .formatted(NEW_COROUTINES, KTOR_IO);
        Path verdicts = tempDir.resolve("verdicts.jsonl");

        Run run = runUpgradeCheckWithDumps("unnamed-e2e", before, after, "--json", "--verdicts-json", verdicts.toString());
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertTrue(run.stderr().contains("warning: the dump carries no per-module classpaths; checking the merged universe"), run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertNull(json.get("module_runs"), run.stdout());
        List<Object> violations = array(json.get("violations"));
        assertEquals(1, violations.size(), run.stdout());
        assertEquals(BLOCKING_ADAPTER, object(violations.get(0)).get("source_class"));
        // The merged stream has no runs to label its records with.
        List<String> lines = Files.readAllLines(verdicts);
        assertTrue(lines.size() > 1, lines.toString());
        for (String line : lines) {
            assertNull(object(Json.parse(line)).get("module"), line);
        }
    }

    // ---- JDK moves ----

    @AfterEach
    void clearEnvironment() {
        Env.clearOverrides();
    }

    /** Points the JDK lookup at the JDK running the tests, or skips when its ct.sym cannot serve 11 and 17. */
    private static void useRunningJdk() {
        Path home = Path.of(System.getProperty("java.home"));
        assumeTrue(
                Files.isRegularFile(home.resolve("lib/ct.sym")) && Runtime.version().feature() >= 18,
                "needs a JDK whose ct.sym serves releases 11 and 17");
        Env.override("UIKA_JDK", home.toString());
    }

    /**
     * A class directory holding the README's JDK-upgrade example: a class that references
     * java.rmi.activation.ActivationGroup, which JDK 17 removed. No vendored jar references an
     * API removed between 11 and 17.
     */
    private String activationGroupUser() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(55);
        // Constant pool: this class, its superclass, and the removed class.
        List<String> names = List.of("UsesRemoved", "java/lang/Object", "java/rmi/activation/ActivationGroup");
        out.writeShort(2 * names.size() + 1);
        for (int i = 0; i < names.size(); i++) {
            // CONSTANT_Utf8, then the CONSTANT_Class naming it.
            out.writeByte(1);
            out.writeUTF(names.get(i));
            out.writeByte(7);
            out.writeShort(2 * i + 1);
        }
        // public super, this_class #2, super_class #4.
        out.writeShort(0x0021);
        out.writeShort(2);
        out.writeShort(4);
        // No interfaces, fields, methods or attributes.
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        Path classes = Files.createDirectories(tempDir.resolve("app-classes"));
        Files.write(classes.resolve("UsesRemoved.class"), bytes.toByteArray());
        return classes.toString();
    }

    private static String withRelease(String module, int release) {
        return module.substring(0, module.length() - 1) + ",\"jdkRelease\":" + release + "}";
    }

    /**
     * Each module whose release moved gets its own JDK run over its own classpath, reported in
     * its own section. A module that also upgraded a dependency keeps the two breaks apart,
     * since the JDK run is named by the module AND the pair.
     */
    @Test
    void eachModuleThatMovedItsJdkReleaseIsCheckedForTheMove() throws Exception {
        useRunningJdk();
        String classes = activationGroupUser();
        String before = dump(
                withRelease(module(":app", List.of(classes), coroutines("1.7.1", OLD_COROUTINES), ktorIo()), 11),
                withRelease(module(":lib", List.of(), unrelated()), 11));
        String after = before.replace("\"jdkRelease\":11", "\"jdkRelease\":17")
                .replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES));

        Run run = runUpgradeCheckWithDumps("jdk-e2e", before, after, "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        Map<String, Object> json = parse(run.stdout());
        Map<String, Object> moduleRuns = object(json.get("module_runs"));
        assertEquals(1L, moduleRuns.get("unchanged_modules"), run.stdout());
        assertEquals(List.of(List.of(":app"), List.of(":app (JDK 11 -> 17)"), List.of(":lib (JDK 11 -> 17)")), outcomeModules(json));
        List<Object> outcomes = array(moduleRuns.get("outcomes"));
        assertNull(object(outcomes.get(0)).get("jdk"));
        assertEquals(1L, object(outcomes.get(0)).get("broken"));
        Map<String, Object> app = object(outcomes.get(1));
        assertEquals(Boolean.TRUE, app.get("jdk"));
        assertEquals(List.of(":app"), app.get("jdk_modules"));
        assertEquals(List.of(11L, 17L), app.get("jdk_pair"));
        assertEquals(1L, app.get("broken"));
        assertEquals(0L, object(outcomes.get(2)).get("broken"));

        List<Object> violations = array(json.get("violations"));
        assertEquals(2, violations.size(), run.stdout());
        assertTrue(hasViolation(violations, BLOCKING_ADAPTER, List.of(":app")), run.stdout());
        assertTrue(hasViolation(violations, "UsesRemoved", List.of(":app (JDK 11 -> 17)")), run.stdout());
        for (Object v : violations) {
            Map<String, Object> violation = object(v);
            if ("UsesRemoved".equals(violation.get("source_class"))) {
                assertEquals("java/rmi/activation/ActivationGroup", object(violation.get("reference")).get("owner"));
                assertEquals("class removed", violation.get("reason"));
                // The JDK is not a dependency, so there is no version to advise on.
                assertNull(violation.get("suggestion"));
            }
        }

        run = runUpgradeCheckWithDumps("jdk-e2e", before, after);
        assertTrue(run.stdout().contains("per-module check: 1 of 2 modules changed their resolved versions (1 unchanged)\n    :app  scanned "),
                run.stdout());
        assertTrue(run.stdout().contains("\n\nJDK check: 2 of 2 modules moved to another release\n    :app  JDK 11 -> 17  scanned "), run.stdout());
        assertTrue(run.stdout().contains("\n    :lib  JDK 11 -> 17  scanned 3 classes, ✅ 0 broken, 0 unverified\n"), run.stdout());
    }

    /** Merged mode has no per-module releases, so it checks the move the two dumps name. */
    @Test
    void theMergedUniverseIsCheckedForTheDumpsJdkMove() throws Exception {
        useRunningJdk();
        String classes = activationGroupUser();
        String before = "{\"jdkRelease\":11," + dump(module(":app", List.of(classes), unrelated())).substring(1);
        String after = before.replace("\"jdkRelease\":11", "\"jdkRelease\":17");

        Run run = runUpgradeCheckWithDumps("jdk-merged-e2e", before, after, "--merged-classpath");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertTrue(run.stdout().startsWith("dependency changes: none\n\n"), run.stdout());
        assertTrue(run.stdout().contains("❌ java.rmi.activation.ActivationGroup\n    class removed, throws NoClassDefFoundError at first use\n"),
                run.stdout());
    }

    /** A merged universe with one break from the JDK move and one from the coroutines upgrade. */
    private String[] mergedDumpsWithJdkAndDependencyBreaks() throws IOException {
        String before = "{\"jdkRelease\":11,"
                + dump(module(":app", List.of(activationGroupUser()), coroutines("1.7.1", OLD_COROUTINES), ktorIo())).substring(1);
        String after = before.replace("\"jdkRelease\":11", "\"jdkRelease\":17")
                .replace(coroutines("1.7.1", OLD_COROUTINES), coroutines("1.11.0", NEW_COROUTINES));
        return new String[] {before, after};
    }

    /**
     * The JDK run's breaks join the dependency run's before exclusion, so a rule that matches
     * only one run's break is not reported unused by the other.
     */
    @Test
    void mergedExcludeRulesApplyOnceAcrossTheJdkRun() throws Exception {
        useRunningJdk();
        String[] dumps = mergedDumpsWithJdkAndDependencyBreaks();
        Path rules = Files.writeString(tempDir.resolve("exclude.toml"), """
                [[exclude]]
                owner = "java/rmi/activation/ActivationGroup"
                reason = "the JDK move is tracked separately"

                [[exclude]]
                owner = "kotlinx/coroutines/EventLoopKt"
                reason = "ktor-io is upgraded in the same release"

                [[exclude]]
                owner = "com/example/Gone"
                reason = "left over from an older upgrade"
                """);

        Run run = runUpgradeCheckWithDumps(
                "jdk-merged-exclude-e2e", dumps[0], dumps[1], "--merged-classpath", "--json", "--exclude-file", rules.toString());
        assertEquals(0, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        assertEquals("warning: exclude rule matched nothing: com/example/Gone (left over from an older upgrade)\n", run.stderr());
        Map<String, Object> json = parse(run.stdout());
        assertEquals(2L, json.get("suppressed"), run.stdout());
        assertEquals(List.of(), json.get("violations"), run.stdout());
    }

    /** The JDK run's breaks are sorted in with the dependency run's, not appended after them. */
    @Test
    void mergedJdkBreaksAreSortedWithTheDependencyBreaks() throws Exception {
        useRunningJdk();
        String[] dumps = mergedDumpsWithJdkAndDependencyBreaks();

        Run run = runUpgradeCheckWithDumps("jdk-merged-order-e2e", dumps[0], dumps[1], "--merged-classpath", "--json");
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        List<Object> sourceClasses = new ArrayList<>();
        for (Object v : array(parse(run.stdout()).get("violations"))) {
            sourceClasses.add(object(v).get("source_class"));
        }
        // The class directory is an absolute path and the fixtures are relative, so the
        // JDK break sorts first although its run comes second.
        assertEquals(List.of("UsesRemoved", BLOCKING_ADAPTER), sourceClasses, run.stdout());
    }

    /** The JDK run streams its verdicts, unlabeled like the rest of the merged stream. */
    @Test
    void mergedJdkRunStreamsItsVerdicts() throws Exception {
        useRunningJdk();
        String[] dumps = mergedDumpsWithJdkAndDependencyBreaks();
        Path verdicts = tempDir.resolve("verdicts.jsonl");

        Run run = runUpgradeCheckWithDumps(
                "jdk-merged-verdicts-e2e", dumps[0], dumps[1], "--merged-classpath", "--json", "--verdicts-json", verdicts.toString());
        assertEquals(1, run.code(), "stdout:\n" + run.stdout() + "\nstderr:\n" + run.stderr());
        List<Object> broken = new ArrayList<>();
        for (String line : Files.readAllLines(verdicts)) {
            Map<String, Object> record = object(Json.parse(line));
            assertNull(record.get("module"), line);
            if ("broken".equals(record.get("verdict"))) {
                broken.add(object(record.get("reference")).get("owner"));
            }
        }
        assertTrue(broken.contains("java/rmi/activation/ActivationGroup"), broken.toString());
        assertTrue(broken.contains("kotlinx/coroutines/EventLoopKt"), broken.toString());
    }
}
