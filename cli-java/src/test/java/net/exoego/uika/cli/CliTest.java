package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CliTest {
    /**
     * A JDK pair supplies both compared sides itself. Accepting --old/--new next to it would
     * silently ignore them, since only one pair reaches the check.
     */
    @Test
    void aJdkPairAndAJarPairCannotBeAskedForAtOnce() throws Exception {
        Cli.Command parsed =
                Cli.parse(new String[] {"check", "--jdk-release-old", "11", "--jdk-release-new", "17", "--classpath", "app.jar"});
        Cli.Check check = assertInstanceOf(Cli.Check.class, parsed, "a JDK pair alone is valid");
        assertTrue(check.oldJars().isEmpty() && check.newJars().isEmpty());

        assertThrows(
                Cli.UsageException.class,
                () -> Cli.parse(new String[] {
                    "check", "--old", "a.jar", "--new", "b.jar", "--jdk-release-old", "11", "--jdk-release-new", "17"
                }),
                "--old/--new must be rejected alongside a JDK pair, not ignored");
        assertThrows(
                Cli.UsageException.class,
                () -> Cli.parse(new String[] {"check", "--classpath", "app.jar"}),
                "without a JDK pair, --old/--new stay required");
        assertThrows(
                Cli.UsageException.class,
                () -> Cli.parse(new String[] {"check", "--jdk-release-old", "11", "--classpath", "app.jar"}),
                "half a JDK pair is not a pair");
    }

    private static String usageError(String... args) {
        return assertThrows(Cli.UsageException.class, () -> Cli.parse(args)).getMessage();
    }

    /** clap never reads a dash-led token as an option's value, and never accepts an empty one. */
    @Test
    void valuesAreTakenTheWayClapTakesThem() {
        String tail = "\n\nFor more information, try '--help'.\n";
        assertEquals(
                "error: unexpected argument '-1' found\n\nUsage: uika check [OPTIONS]" + tail,
                usageError("check", "--old", "a.jar", "--new", "b.jar", "--jdk-release", "-1"));
        assertEquals(
                "error: unexpected argument '-x' found\n\nUsage: uika check [OPTIONS]" + tail,
                usageError("check", "--old", "-x", "--new", "b.jar"));
        assertEquals(
                "error: a value is required for '--old <OLD>' but none was supplied" + tail,
                usageError("check", "--old", "--new", "b.jar"));
        assertEquals(
                "error: a value is required for '--new <NEW>' but none was supplied" + tail,
                usageError("check", "--old", "a.jar", "--new"));
        assertEquals(
                "error: a value is required for '--old <OLD>' but none was supplied" + tail,
                usageError("check", "--old=", "--new", "b.jar"));
        assertEquals(
                "error: a value is required for '--classpath <CLASSPATH>' but none was supplied" + tail,
                usageError("check", "--old", "a.jar", "--new", "b.jar", "--classpath", "a.jar:"));
        assertEquals(
                "error: a value is required for '--fail-on <FAIL_ON>' but none was supplied\n"
                        + "  [possible values: never, reachable, any]" + tail,
                usageError("check", "--old", "a.jar", "--new", "b.jar", "--fail-on", ""));
        assertEquals(
                "error: invalid value '' for '--jdk-release <JDK_RELEASE>': cannot parse integer from empty string" + tail,
                usageError("check", "--old", "a.jar", "--new", "b.jar", "--jdk-release="));
        assertEquals(
                "error: a value is required for '<OLD>' but none was supplied" + tail,
                usageError("diff", "", "b.jar"));
        assertEquals(
                "error: unexpected argument '-x' found\n\n  tip: to pass '-x' as a value, use '-- -x'\n\n"
                        + "Usage: uika dump <PATH>" + tail,
                usageError("dump", "-x"));
        assertEquals(
                "error: unexpected argument 'c' found\n\nUsage: uika diff [OPTIONS] <OLD> <NEW>" + tail,
                usageError("diff", "a", "b", "c"));
        assertEquals("error: unexpected argument '-x' found\n\nUsage: uika <COMMAND>" + tail, usageError("-x"));
    }

    private static final String TAIL = "\n\nFor more information, try '--help'.\n";

    @Test
    void helpWinsOverTheArgumentsTheCommandWouldNeed() throws Exception {
        assertEquals(new Cli.Print(Cli.help("root", true)), Cli.parse(new String[] {"help"}));
        assertEquals(new Cli.Print(Cli.help("diff", false)), Cli.parse(new String[] {"diff", "-h"}));
        assertEquals(new Cli.Print(Cli.help("check", true)), Cli.parse(new String[] {"check", "--help"}));
        assertEquals(new Cli.Print(Cli.help("upgrade-check", false)), Cli.parse(new String[] {"upgrade-check", "-h"}));
        assertEquals(new Cli.Print(Cli.help("dump", true)), Cli.parse(new String[] {"dump", "--help"}));
    }

    /** A packaging mistake that drops a help page must still print something usable. */
    @Test
    void aMissingHelpPageFallsBackToTheUsageLine() {
        assertEquals("Usage: uika <COMMAND>\n", Cli.help("no-such-page", true));
    }

    @Test
    void aDoubleDashAndALoneDashAreValues() throws Exception {
        assertEquals(new Cli.DumpApi("-x"), Cli.parse(new String[] {"dump", "--", "-x"}));
        assertEquals(new Cli.Diff("-a", "--json", false), Cli.parse(new String[] {"diff", "--", "-a", "--json"}));
        assertEquals(new Cli.DumpApi("-"), Cli.parse(new String[] {"dump", "-"}));
        Cli.Check check = assertInstanceOf(Cli.Check.class, Cli.parse(new String[] {"check", "--old", "-", "--new", "b.jar"}));
        assertEquals(List.of("-"), check.oldJars());
    }

    @Test
    void everyOptionOfCheckAndUpgradeCheckIsRead() throws Exception {
        Cli.Check check = assertInstanceOf(Cli.Check.class, Cli.parse(new String[] {
            "check", "--old", "a.jar", "--old=b.jar", "--new", "c.jar", "--classpath", "x.jar:y.jar", "--app", "classes",
            "--classpath-file", "dump.json", "--exclude-file", "e.toml", "--json", "--fail-on", "reachable",
            "--jdk-release", "17", "--verdicts-json", "v.jsonl", "--class-load-log", "loads.log", "--draft-exclude-file", "d.toml"
        }));
        assertEquals(
                new Cli.Check(
                        List.of("a.jar", "b.jar"),
                        List.of("c.jar"),
                        List.of("x.jar", "y.jar"),
                        List.of("classes"),
                        List.of("dump.json"),
                        List.of("e.toml"),
                        true,
                        Cli.FailOn.REACHABLE,
                        17,
                        null,
                        null,
                        "v.jsonl",
                        List.of("loads.log"),
                        "d.toml"),
                check);

        Cli.UpgradeCheck upgrade = assertInstanceOf(Cli.UpgradeCheck.class, Cli.parse(new String[] {
            "upgrade-check", "--before", "b.json", "--after", "a.json", "--exclude-file", "e.toml", "--fail-on", "any",
            "--jdk-release", "21", "--verdicts-json", "v.jsonl", "--merged-classpath", "--class-load-log", "loads.log",
            "--draft-exclude-file", "d.toml"
        }));
        assertEquals(
                new Cli.UpgradeCheck(
                        "b.json", "a.json", List.of("e.toml"), false, Cli.FailOn.ANY, 21, "v.jsonl", true, List.of("loads.log"), "d.toml"),
                upgrade);
    }

    /** Each option belongs to the commands that read it. Elsewhere it is an unknown argument, like clap reports it. */
    @Test
    void anOptionOfAnotherCommandIsUnexpected() {
        String check = "\n\nUsage: uika check [OPTIONS]" + TAIL;
        String upgrade = "\n\nUsage: uika upgrade-check [OPTIONS]" + TAIL;
        assertEquals("error: unexpected argument '--merged-classpath' found" + check, usageError("check", "--merged-classpath"));
        assertEquals("error: unexpected argument '--before' found" + check, usageError("check", "--before", "b.json"));
        assertEquals("error: unexpected argument '--old' found" + upgrade, usageError("upgrade-check", "--old", "a.jar"));
        assertEquals(
                "error: unexpected argument '--jdk-release-old' found" + upgrade, usageError("upgrade-check", "--jdk-release-old", "11"));
        assertEquals("error: unexpected argument '--bogus' found" + check, usageError("check", "--bogus"));
        // diff and dump take positionals, so clap adds how to pass a dash-led one.
        String diffTip = "\n\nUsage: uika diff [OPTIONS] <OLD> <NEW>" + TAIL;
        assertEquals(
                "error: unexpected argument '--exclude-file' found\n\n  tip: to pass '--exclude-file' as a value, use '-- --exclude-file'"
                        + diffTip,
                usageError("diff", "--exclude-file", "e.toml", "a.jar", "b.jar"));
        assertEquals(
                "error: unexpected argument '--fail-on' found\n\n  tip: to pass '--fail-on' as a value, use '-- --fail-on'" + diffTip,
                usageError("diff", "--fail-on", "never", "a.jar", "b.jar"));
        assertEquals(
                "error: unexpected argument '-x' found\n\n  tip: to pass '-x' as a value, use '-- -x'" + diffTip,
                usageError("diff", "-x", "a.jar", "b.jar"));
        assertEquals(
                "error: unexpected argument '--json' found\n\n  tip: to pass '--json' as a value, use '-- --json'\n\nUsage: uika dump <PATH>"
                        + TAIL,
                usageError("dump", "--json", "a.jar"));
        assertEquals("error: unexpected argument 'extra' found" + check, usageError("check", "extra", "--old", "a.jar", "--new", "b.jar"));
    }

    @Test
    void anOptionIsGivenTheWayItsKindAllows() {
        assertEquals(
                "error: unexpected value 'yes' for '--json' found; no more were expected\n\nUsage: uika diff [OPTIONS] <OLD> <NEW>" + TAIL,
                usageError("diff", "--json=yes", "a.jar", "b.jar"));
        assertEquals(
                "error: the argument '--before <BEFORE>' cannot be used multiple times\n\nUsage: uika upgrade-check [OPTIONS]" + TAIL,
                usageError("upgrade-check", "--before", "a.json", "--before", "b.json", "--after", "c.json"));
        // A known flag after a value-taking option leaves that option without its value.
        String missingOld = "error: a value is required for '--old <OLD>' but none was supplied" + TAIL;
        assertEquals(missingOld, usageError("check", "--old", "--", "a.jar", "--new", "b.jar"));
        assertEquals(missingOld, usageError("check", "--old", "-h", "--new", "b.jar"));
        assertEquals(missingOld, usageError("check", "--old", "--help", "--new", "b.jar"));
        assertEquals(missingOld, usageError("check", "--old", "--new=b.jar"));
        // An unknown one is reported as itself.
        assertEquals(
                "error: unexpected argument '--bogus' found\n\nUsage: uika check [OPTIONS]" + TAIL,
                usageError("check", "--old", "--bogus", "--new", "b.jar"));
        assertEquals(
                "error: the following required arguments were not provided:\n  <OLD>\n  <NEW>\n\nUsage: uika diff <OLD> <NEW>" + TAIL,
                usageError("diff"));
    }

    @Test
    void aReleaseMustBeANumberCtSymCanServe() {
        assertEquals(
                "error: invalid value '7' for '--jdk-release <JDK_RELEASE>': 7 is not in 8..=35" + TAIL,
                usageError("check", "--old", "a.jar", "--new", "b.jar", "--jdk-release", "7"));
        assertEquals(
                "error: invalid value '36' for '--jdk-release <JDK_RELEASE>': 36 is not in 8..=35" + TAIL,
                usageError("upgrade-check", "--before", "a.json", "--after", "b.json", "--jdk-release", "36"));
        assertEquals(
                "error: invalid value 'seventeen' for '--jdk-release <JDK_RELEASE>': invalid digit found in string" + TAIL,
                usageError("check", "--old", "a.jar", "--new", "b.jar", "--jdk-release", "seventeen"));
        assertEquals(
                "error: invalid value '' for '--jdk-release-old <JDK_RELEASE_OLD>': cannot parse integer from empty string" + TAIL,
                usageError("check", "--jdk-release-old=", "--jdk-release-new", "17"));
        assertEquals(
                "error: invalid value '' for '--jdk-release-new <JDK_RELEASE_NEW>': cannot parse integer from empty string" + TAIL,
                usageError("check", "--jdk-release-old", "11", "--jdk-release-new="));
    }
}
