package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherTest {
    private static String classpath(int jars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jars; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append("lib").append(i).append(".jar");
        }
        return sb.toString();
    }

    /** C1 alone wins until roughly a thousand jars, so a project-sized classpath must not get C2. */
    @Test
    void aProjectSizedClasspathRunsWithoutC2() {
        String[] args = {"check", "--old", "a.jar", "--new", "b.jar", "--classpath", classpath(300)};
        assertFalse(Launcher.isLarge(args));
        assertTrue(Launcher.flags(args).contains("-XX:TieredStopAtLevel=1"));
    }

    @Test
    void aCacheSizedClasspathGetsC2() {
        String[] args = {"check", "--old", "a.jar", "--new", "b.jar", "--classpath", classpath(2800)};
        assertTrue(Launcher.isLarge(args));
        assertFalse(Launcher.flags(args).contains("-XX:TieredStopAtLevel=1"));
    }

    @Test
    void classpathEntriesAddUpAcrossFlagsAndSpellings() {
        String[] args = {"check", "--classpath", classpath(500), "--classpath=" + classpath(300)};
        assertTrue(Launcher.isLarge(args));
    }

    /** A JDK-pair check records nearly every reference of every class, so it turns heavy far sooner. */
    @Test
    void aJdkPairIsLargeSooner() {
        String cp = classpath(200);
        assertFalse(Launcher.isLarge(new String[] {"check", "--old", "a.jar", "--new", "b.jar", "--classpath", cp}));
        assertTrue(Launcher.isLarge(new String[] {"check", "--jdk-release-old", "11", "--jdk-release-new", "17", "--classpath", cp}));
    }

    @Test
    void aDumpIsSizedByItsFile(@TempDir Path dir) throws Exception {
        Path small = dir.resolve("small.json");
        Files.writeString(small, "x".repeat(10_000));
        Path big = dir.resolve("big.json");
        Files.writeString(big, "x".repeat(400_000));
        assertFalse(Launcher.isLarge(new String[] {"upgrade-check", "--before", small.toString(), "--after", small.toString()}));
        assertTrue(Launcher.isLarge(new String[] {"upgrade-check", "--before", big.toString(), "--after", big.toString()}));
        // A missing dump is the command's error to report, not the launcher's.
        assertFalse(Launcher.isLarge(new String[] {"upgrade-check", "--before", dir.resolve("none").toString()}));
    }

    @Test
    void everyChildGetsTheSmallYoungGeneration() {
        List<String> flags = Launcher.flags(new String[] {"dump", "x.jar"});
        assertTrue(flags.contains("-XX:+UseSerialGC"));
        assertTrue(flags.contains("-Xmn32m"));
    }

    /** Help, version and usage errors do no work worth a second JVM. */
    @Test
    void helpAndVersionStayInProcess() {
        assertFalse(Launcher.shouldRelaunch(new String[] {}));
        assertFalse(Launcher.shouldRelaunch(new String[] {"--version"}));
        assertFalse(Launcher.shouldRelaunch(new String[] {"help", "check"}));
        assertFalse(Launcher.shouldRelaunch(new String[] {"check"}));
    }

    @Test
    void aChildNeverRelaunches() {
        String previous = System.getProperty("uika.child");
        System.setProperty("uika.child", "true");
        try {
            assertFalse(Launcher.shouldRelaunch(new String[] {"dump", "x.jar"}));
        } finally {
            if (previous == null) {
                System.clearProperty("uika.child");
            } else {
                System.setProperty("uika.child", previous);
            }
        }
        assertEquals(previous, System.getProperty("uika.child"));
    }

    /** A real child JVM on this test's classpath, which is what the launcher hands every command to. */
    @Test
    void aChildJvmRunsTheCommand() {
        assertEquals(0, Launcher.relaunch(new String[] {"--version"}));
        assertEquals(2, Launcher.relaunch(new String[] {"no-such-command"}));
    }
}
