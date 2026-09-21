package net.exoego.uika.cli;

import static net.exoego.uika.cli.GoldenTest.fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
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
}
