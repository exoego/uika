package net.exoego.uika.cli;

import static net.exoego.uika.cli.GoldenTest.fixture;
import static net.exoego.uika.cli.UpgradeCheckIntegrationTest.runUika;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.exoego.uika.cli.UpgradeCheckIntegrationTest.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line end to end: argv in, exit code and streams out. The pipeline tests drive
 * {@link Commands} and {@link Check} directly, so what only this layer does, parsing, dispatch,
 * the streams it opens and the notes it prints, is pinned here.
 */
class MainTest {
    private static final String OLD = "guava-22.0.jar";
    private static final String NEW = "guava-23.0-rc1.jar";
    private static final String CONSUMER = "selenium-remote-driver-3.4.0.jar";
    private static final String REFERENCER = "org/openqa/selenium/net/UrlChecker";

    @TempDir
    Path dir;

    @Test
    void helpAndVersionNeedNoInput() {
        Run longHelp = runUika("--help");
        assertEquals(0, longHelp.code());
        assertEquals(Cli.help("root", true), longHelp.stdout());
        assertEquals(Cli.help("root", false), runUika("-h").stdout());
        assertEquals(Cli.help("check", true), runUika("help", "check").stdout());
        assertEquals("uika " + Cli.version() + "\n", runUika("--version").stdout());

        Run none = runUika();
        assertEquals(2, none.code());
        assertEquals(Cli.help("root", false), none.stderr());
        assertTrue(runUika("frobnicate").stderr().startsWith("error: unrecognized subcommand 'frobnicate'"));
        assertTrue(runUika("--frobnicate").stderr().startsWith("error: unexpected argument '--frobnicate'"));
    }

    @Test
    void diffPrintsTextOrJson() {
        Run text = runUika("diff", fixture(OLD), fixture(NEW));
        assertEquals(0, text.code(), text.stderr());
        assertTrue(text.stdout().contains("\nbreaking changes: "), text.stdout());

        Run json = runUika("diff", "--json", fixture(OLD), fixture(NEW));
        assertEquals(0, json.code());
        assertTrue(json.stdout().startsWith("{"));
        assertTrue(json.stdout().contains("\"breaking_changes\""));

        assertTrue(runUika("diff", fixture(OLD)).stderr().contains("  <NEW>\n"));
        assertTrue(runUika("diff", fixture(OLD), fixture(NEW), "extra").stderr().contains("unexpected argument 'extra'"));
    }

    @Test
    void dumpPrintsEveryClassAndCountsWhatItCouldNotParse() throws Exception {
        Run jar = runUika("dump", fixture(OLD));
        assertEquals(0, jar.code());
        assertTrue(jar.stdout().contains("class com/google/common/base/Optional [public]\n"), jar.stdout().substring(0, 300));
        assertTrue(jar.stdout().contains("  method com/google/common/base/Optional.isPresent ()Z [public]\n"));
        assertTrue(jar.stderr().endsWith(" classes (0 parse errors, 0 name mismatches)\n"), jar.stderr());

        // A directory, with one class under a wrong name and one that is not a class file.
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve("net/exoego/uika/cli"));
        Files.write(classes.resolve("net/exoego/uika/cli/Main.class"), mainClass());
        Files.write(classes.resolve("Other.class"), mainClass());
        // The class magic and nothing after it. A file without the magic is not a class and is
        // skipped without a word, so it would not count.
        Files.write(classes.resolve("Bad.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61});
        Run directory = runUika("dump", classes.toString());
        assertEquals(0, directory.code());
        assertTrue(directory.stderr().contains("warning: entry Other.class but this_class net/exoego/uika/cli/Main\n"), directory.stderr());
        assertTrue(directory.stderr().endsWith("dumped 2 classes (1 parse errors, 1 name mismatches)\n"), directory.stderr());

        assertTrue(runUika("dump").stderr().contains("  <PATH>\n"));
        assertTrue(runUika("dump", "a", "b").stderr().contains("unexpected argument 'b'"));
    }

    /** The command line and the golden entry point must agree, or the goldens pin something users never see. */
    @Test
    void checkMatchesTheGoldenThroughTheCommandLine() throws Exception {
        Run run = runUika("check", "--json", "--old", fixture(OLD), "--new", fixture(NEW), "--classpath", fixture(CONSUMER));
        assertEquals(1, run.code(), run.stderr());
        assertEquals(GoldenTest.scenarioJson("guava-selenium") + "\n", run.stdout());

        assertEquals(0, runUika("check", "--fail-on", "never", "--old", fixture(OLD), "--new", fixture(NEW), "--classpath", fixture(CONSUMER)).code());
        Run bad = runUika("check", "--fail-on", "sometimes", "--old", fixture(OLD), "--new", fixture(NEW), "--classpath", fixture(CONSUMER));
        assertEquals(2, bad.code());
        assertTrue(bad.stderr().contains("[possible values: never, reachable, any]"), bad.stderr());
    }

    @Test
    void checkReadsItsTargetsFromADump() throws Exception {
        Path dump = dir.resolve("dump.json");
        Files.writeString(dump, """
                {"modules":[{"module":":app","classesDirs":[],"artifacts":[{"file":"%s"}]}]}
                """.formatted(fixture(CONSUMER)));

        Run fromDump = runUika("check", "--json", "--old", fixture(OLD), "--new", fixture(NEW), "--classpath-file", dump.toString());
        Run fromArgs = runUika("check", "--json", "--old", fixture(OLD), "--new", fixture(NEW), "--classpath", fixture(CONSUMER));

        assertEquals(fromArgs.code(), fromDump.code());
        assertEquals(fromArgs.stdout(), fromDump.stdout());
    }

    @Test
    void checkStreamsVerdictsAndFailsWhenItCannot() throws Exception {
        Path verdicts = dir.resolve("verdicts.jsonl");
        Run run = runUika("check", "--verdicts-json", verdicts.toString(), "--fail-on", "never",
                "--old", fixture(OLD), "--new", fixture(NEW), "--classpath", fixture(CONSUMER));
        assertEquals(0, run.code(), run.stderr());
        assertTrue(Files.readAllLines(verdicts).size() > 100, "the stream holds every reference verdict");

        Run unwritable = runUika("check", "--verdicts-json", dir.resolve("nowhere/verdicts.jsonl").toString(),
                "--old", fixture(OLD), "--new", fixture(NEW), "--classpath", fixture(CONSUMER));
        assertEquals(2, unwritable.code());
        assertTrue(unwritable.stderr().startsWith("error: cannot create verdicts output "), unwritable.stderr());
    }

    @Test
    void checkAppliesRuntimeEvidenceAndDraftsExcludes() throws Exception {
        Path log = Files.writeString(dir.resolve("loads.log"), "[class,load] " + REFERENCER.replace('/', '.') + "\n");
        Path draft = dir.resolve("draft.toml");

        Run run = runUika("check", "--class-load-log", log.toString(), "--draft-exclude-file", draft.toString(),
                "--old", fixture(OLD), "--new", fixture(NEW), "--classpath", fixture(CONSUMER));
        assertEquals(1, run.code(), run.stderr());
        assertTrue(run.stderr().contains("note: runtime load evidence: 1 distinct classes from " + log + "\n"), run.stderr());
        assertTrue(run.stdout().contains("observed loading at runtime"), run.stdout());
        assertTrue(Files.exists(draft), "the draft is written even when nothing is draftable");

        // Drafting over a live exclude file would drop every rule still suppressing something.
        Path exclude = Files.writeString(dir.resolve("exclude.toml"), "");
        Run clash = runUika("check", "--class-load-log", log.toString(), "--draft-exclude-file", exclude.toString(),
                "--exclude-file", exclude.toString(), "--old", fixture(OLD), "--new", fixture(NEW), "--classpath", fixture(CONSUMER));
        assertEquals(2, clash.code());
        assertTrue(clash.stderr().contains("is also an --exclude-file"), clash.stderr());
    }

    @Test
    void checkCanCompareTwoJdkReleases() {
        Path home = Path.of(System.getProperty("java.home"));
        assumeTrue(Files.isRegularFile(home.resolve("lib/ct.sym")) && Runtime.version().feature() >= 18,
                "needs a JDK whose ct.sym serves releases 11 and 17");

        Run run = runUika("check", "--jdk-release-old", "11", "--jdk-release-new", "17", "--classpath", fixture(CONSUMER));
        assertTrue(run.code() == 0 || run.code() == 1, run.stderr());
        assertTrue(run.stdout().startsWith("checked JDK 11 -> JDK 17 against 1 scan target\n"), run.stdout());

        // The running JDK's own release comes from jmods, a superset of ct.sym, which only
        // ever cancels removals on the new side. As the old side it would invent them.
        assumeTrue(Files.isDirectory(home.resolve("jmods")), "needs a JDK that ships jmods");
        Run inverted = runUika("check", "--jdk-release-old", Integer.toString(Runtime.version().feature()),
                "--jdk-release-new", "17", "--classpath", fixture(CONSUMER));
        assertTrue(inverted.stderr().contains("read from jmods"), inverted.stderr());
    }

    @Test
    void upgradeCheckFallsBackToTheMergedUniverse() throws Exception {
        String modules = """
                {"modules":[{"module":":app","classesDirs":[],"artifacts":[
                    {"group":"com.google.guava","name":"guava","version":"%s","file":"%s"},
                    {"group":"org.seleniumhq.selenium","name":"selenium-remote-driver","version":"3.4.0","file":"%s"}
                ]}]}
                """;
        Path before = Files.writeString(dir.resolve("before.json"), modules.formatted("22.0", fixture(OLD), fixture(CONSUMER)));
        Path after = Files.writeString(dir.resolve("after.json"), modules.formatted("23.0-rc1", fixture(NEW), fixture(CONSUMER)));

        Run merged = runUika("upgrade-check", "--merged-classpath", "--before", before.toString(), "--after", after.toString());
        assertEquals(1, merged.code(), merged.stderr());
        assertTrue(merged.stdout().contains("CHANGED com.google.guava:guava 22.0 -> 23.0-rc1"), merged.stdout());
        assertTrue(merged.stdout().contains("broken"), merged.stdout());
        assertFalse(merged.stderr().contains("carries no per-module classpaths"), merged.stderr());

        // A dump with no module classpaths at all is checked merged, with a warning, not skipped.
        String bare = "{\"modules\":[{\"module\":\":app\",\"classesDirs\":[],\"artifacts\":[]}]}\n";
        Path emptyBefore = Files.writeString(dir.resolve("empty-before.json"), bare);
        Path emptyAfter = Files.writeString(dir.resolve("empty-after.json"), bare);
        Run warned = runUika("upgrade-check", "--before", emptyBefore.toString(), "--after", emptyAfter.toString());
        assertEquals(0, warned.code(), warned.stderr());
        assertTrue(warned.stderr().contains("warning: the dump carries no per-module classpaths"), warned.stderr());
    }

    @Test
    void mainDecidesBetweenAChildJvmAndThisOne() throws Exception {
        // Help never pays for a second JVM.
        assertEquals(0, Main.exitCode(new String[] {"--version"}));

        // A command does, and its exit code comes back from the child.
        Path jar = dir.resolve("one.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("net/exoego/uika/cli/Main.class"));
            out.write(mainClass());
            out.closeEntry();
        }
        assertEquals(0, Main.exitCode(new String[] {"dump", jar.toString()}));
        assertEquals(2, Main.exitCode(new String[] {"dump", dir.resolve("missing.jar").toString()}));
    }

    private static byte[] mainClass() throws Exception {
        try (InputStream in = Main.class.getResourceAsStream("Main.class")) {
            return in.readAllBytes();
        }
    }
}
