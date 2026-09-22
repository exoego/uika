package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
