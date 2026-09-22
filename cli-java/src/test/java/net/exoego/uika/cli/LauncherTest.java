package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    @Test
    void aLeadingFlagStaysInProcessWhateverFollows() {
        assertFalse(Launcher.shouldRelaunch(new String[] {"-h", "check"}));
    }

    @Test
    void everyAppIsOneScanTarget() {
        List<String> args = new ArrayList<>(List.of("check", "--old", "a.jar", "--new", "b.jar"));
        for (int i = 0; i < 799; i++) {
            args.add("--app");
            args.add("classes" + i);
        }
        assertFalse(Launcher.isLarge(args.toArray(new String[0])));
        args.add("--app");
        args.add("classes799");
        assertTrue(Launcher.isLarge(args.toArray(new String[0])));
    }

    @Test
    void aClasspathFileIsSizedLikeADump(@TempDir Path dir) throws Exception {
        Path big = Files.writeString(dir.resolve("big.json"), "x".repeat(400_000));
        assertTrue(Launcher.isLarge(new String[] {"check", "--old", "a.jar", "--new", "b.jar", "--classpath-file", big.toString()}));
        assertTrue(Launcher.isLarge(new String[] {"check", "--old", "a.jar", "--new", "b.jar", "--classpath-file=" + big}));
    }

    /** A value-taking option at the end is the parser's error to report, so it counts nothing here. */
    @Test
    void anOptionWithoutItsValueCountsNothing() {
        assertFalse(Launcher.isLarge(new String[] {"check", "--classpath"}));
        assertFalse(Launcher.isLarge(new String[] {"upgrade-check", "--after", "a.json", "--before"}));
    }

    /** Swaps a system property, or clears it for a null value, for the length of {@code body}. */
    private static void withProperty(String name, String value, Runnable body) {
        String previous = System.getProperty(name);
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
        try {
            body.run();
        } finally {
            System.setProperty(name, previous);
        }
    }

    /** -1 hands the command back to this JVM, which is better than failing a run that could still work. */
    @Test
    void noUsableJavaMeansRunInProcess(@TempDir Path dir) throws Exception {
        String[] args = {"--version"};
        withProperty("java.home", null, () -> assertEquals(-1, Launcher.relaunch(args)));
        withProperty("java.class.path", null, () -> assertEquals(-1, Launcher.relaunch(args)));
        withProperty("java.class.path", "", () -> assertEquals(-1, Launcher.relaunch(args)));
        // A runtime image without a java launcher.
        withProperty("java.home", dir.toString(), () -> assertEquals(-1, Launcher.relaunch(args)));

        // A java that is there but cannot be started.
        Path java = Files.createDirectories(dir.resolve("bin")).resolve("java");
        Files.writeString(java, "#!/no/such/interpreter\n");
        assumeTrue(java.toFile().setExecutable(true), "cannot mark a file executable here");
        withProperty("java.home", dir.toString(), () -> assertEquals(-1, Launcher.relaunch(args)));
    }

    /** The child's exit code is the command's result, so an interrupt must not lose it. */
    @Test
    void anInterruptDoesNotLoseTheChildsExitCode() {
        Thread.currentThread().interrupt();
        try {
            assertEquals(2, Launcher.relaunch(new String[] {"no-such-command"}));
        } finally {
            Thread.interrupted();
        }
    }
}
