package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden regression: the full check JSON for fixed fixture scenarios is compared byte for
 * byte against tests/golden/{scenario}.json, so any detection shift (count, order, reason,
 * field) fails here first. {@code make java-cli-bless} rewrites them once a shift is verified
 * as intended.
 *
 * <p>Inputs are loaded via crate-relative paths, because the path string is interned as the
 * violation source and the golden JSON pins it.
 */
class GoldenTest {
    static String fixture(String jarName) {
        Path path = Path.of("tests/fixtures").resolve(jarName);
        assertTrue(Files.exists(path), "fixture not found: " + path + " (tests run with cli-java/ as the working directory)");
        return "tests/fixtures/" + jarName;
    }

    /** (old, new, consumer) jar names for a scenario in tests/scenarios.tsv. */
    private static String[] scenario(String name) throws Exception {
        for (String line : Files.readAllLines(Path.of("tests/scenarios.tsv"), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            assertEquals(5, fields.length, "malformed scenarios.tsv line: " + line);
            if (fields[0].equals(name)) {
                return new String[] {fields[1], fields[2], fields[3]};
            }
        }
        throw new AssertionError("scenario not found in tests/scenarios.tsv: " + name);
    }

    /** Parse warnings are intentionally not part of the golden surface. */
    static String scenarioJson(String name) throws Exception {
        String[] jars = scenario(name);
        List<String> ignored = new ArrayList<>();
        ApiIndex oldIndex = ApiIndex.fromPaths(List.of(fixture(jars[0])), ignored);
        ApiIndex newIndex = ApiIndex.fromPaths(List.of(fixture(jars[1])), ignored);
        // The new library's own bytecode is swept for invocation evidence, matching what the
        // CLI does with --new, so the goldens pin the latent classification users see.
        Check.Report report = Check.check(List.of(fixture(jars[2])), oldIndex, newIndex, List.of(fixture(jars[1])));
        return Report.checkJson(report);
    }

    private static void assertGolden(String name) throws Exception {
        String actual = scenarioJson(name) + "\n";
        Path golden = Path.of("tests/golden").resolve(name + ".json");
        if (Boolean.getBoolean("uika.bless")) {
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
            return;
        }
        String expected = Files.readString(golden, StandardCharsets.UTF_8);
        assertEquals(expected, actual, "golden mismatch for " + name + ": detection output changed");
    }

    /** ktor-io binds EventLoopKt.processNextEventInCurrentThread ()J, removed in coroutines 1.11.0. */
    @Test
    void goldenCoroutinesKtorIo() throws Exception {
        assertGolden("coroutines-ktor-io");
    }

    /** OTel moved DaemonThreadFactory out of sdk.internal between 1.42 and 1.60. */
    @Test
    void goldenOtelSdkCommonSenderOkhttp() throws Exception {
        assertGolden("otel-sdk-common-sender-okhttp");
    }

    /** Guava 23.0-rc1 made the SimpleTimeLimiter constructor private while Selenium 3.4.0 still calls it. */
    @Test
    void goldenGuavaSelenium() throws Exception {
        assertGolden("guava-selenium");
    }

    /** koin-core 3.3.0 made Logger.log final while koin-logger-slf4j 3.2.2 overrides it. */
    @Test
    void goldenKoinCoreLoggerSlf4j() throws Exception {
        assertGolden("koin-core-logger-slf4j");
    }

    /** OkHttp 4.0 turned RequestLine into a Kotlin object, making requestPath an instance method. */
    @Test
    void goldenOkhttpDigest() throws Exception {
        assertGolden("okhttp-digest");
    }

    /** https://github.com/pact-foundation/pact-jvm/issues/1338: a runtime lagging at 4.2.2 breaks the 4.2.3 subclass. */
    @Test
    void goldenPactJunit5VersionLag() throws Exception {
        assertGolden("pact-junit5-version-lag");
    }

    /** jetty-util 10 made the Trie classes package-private and removed the Trie interface. */
    @Test
    void goldenJettyUtilHttpSkew() throws Exception {
        assertGolden("jetty-util-http-skew");
    }

    /** Shape-2 AbstractMethodError, minimizing https://github.com/jOOQ/jOOQ/issues/14430. */
    @Test
    void goldenSyntheticAbstractAdded() throws Exception {
        assertGolden("synthetic-abstract-added");
    }

    /** Shape 2.0 is sealed and permits only Circle, so Square no longer loads. */
    @Test
    void goldenSyntheticSealed() throws Exception {
        assertGolden("synthetic-sealed");
    }

    /** lib/B 2.0 adds a default n() that lib/A already declares, so Conflicted cannot select one. */
    @Test
    void goldenSyntheticDefaultConflict() throws Exception {
        assertGolden("synthetic-default-conflict");
    }
}
