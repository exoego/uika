package net.exoego.uika.cli;

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
}
