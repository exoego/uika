package net.exoego.uika.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-runs the command in a child JVM started with the flags this workload wants.
 *
 * <p>A jar cannot carry JVM flags, and the defaults are tuned for a long-lived server. On a
 * command that runs for a second they cost real time and memory, measured on a 12-core M4 Pro:
 *
 * <ul>
 *   <li>G1 and the parallel collector size the young generation from the machine's RAM. The
 *       scan allocates almost nothing, so that space is only ever touched, never needed. The
 *       serial collector with a 32 MiB young generation cut the 2,800-jar run from about 380 MB
 *       resident to about 300 MB, with no loss of speed.
 *   <li>Tiered compilation profiles every method before C2 compiles it. For a few jars that
 *       profiling never pays back: C1 alone ran a 3-jar check in 0.08s against 0.17s, and a
 *       100-jar check in 0.28s against 0.45s. C2 wins from roughly a thousand jars up, 1.6s
 *       against 1.9s at 2,800.
 * </ul>
 *
 * <p>The child costs one more JVM boot, about 25ms. A caller that already passes the flags
 * sets {@code -Duika.child=true} (the build-tool plugins do), and {@code UIKA_NO_RELAUNCH}
 * turns this off for debugging.
 */
final class Launcher {
    /** Scan targets from which C2 starts to pay for its warm-up. */
    private static final int LARGE = 800;
    /** A JDK-pair check records nearly every reference of every class, so it is heavy far sooner. */
    private static final int LARGE_FOR_JDK_PAIR = 150;

    private Launcher() {}

    static boolean shouldRelaunch(String[] args) {
        if (System.getProperty("uika.child") != null) {
            return false;
        }
        // Help, version and usage errors do no work worth a second JVM.
        if (args.length < 2 || args[0].startsWith("-") || args[0].equals("help")) {
            return false;
        }
        String optOut = System.getenv("UIKA_NO_RELAUNCH");
        return optOut == null || optOut.isEmpty();
    }

    /** The exit code of the child, or -1 when no child could be started and the caller should run in-process. */
    static int relaunch(String[] args) {
        String javaHome = System.getProperty("java.home");
        String classPath = System.getProperty("java.class.path");
        if (javaHome == null || classPath == null || classPath.isEmpty()) {
            return -1;
        }
        Path java = Path.of(javaHome, "bin", File.separatorChar == '\\' ? "java.exe" : "java");
        if (!Files.isExecutable(java)) {
            return -1;
        }
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(flags(args));
        command.add("-Duika.child=true");
        command.add("-cp");
        command.add(classPath);
        command.add(Main.class.getName());
        command.addAll(List.of(args));
        try {
            Process child = new ProcessBuilder(command).inheritIO().start();
            // The terminal delivers an interrupt to both processes, so the child ends on its own.
            while (true) {
                try {
                    return child.waitFor();
                } catch (InterruptedException e) {
                    // keep waiting: the exit code is the command's result
                }
            }
        } catch (IOException e) {
            return -1;
        }
    }

    static List<String> flags(String[] args) {
        List<String> flags = new ArrayList<>();
        flags.add("-XX:+UseSerialGC");
        flags.add("-Xmn32m");
        flags.add("-XX:-UsePerfData");
        flags.add("-Xshare:auto");
        if (!isLarge(args)) {
            flags.add("-XX:TieredStopAtLevel=1");
        }
        return flags;
    }

    /** A guess from the arguments alone: nothing may be read that the command would not read anyway. */
    static boolean isLarge(String[] args) {
        long targets = 0;
        boolean jdkPair = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = null;
            int eq = arg.indexOf('=');
            String name = arg;
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            } else if (i + 1 < args.length) {
                value = args[i + 1];
            }
            // Plain loops only: this runs in the parent, where a lambda or a stream would cost
            // more start-up than the whole decision is worth.
            if (name.equals("--classpath") && value != null) {
                targets++;
                for (int k = 0; k < value.length(); k++) {
                    if (value.charAt(k) == ':') {
                        targets++;
                    }
                }
            } else if (name.equals("--app")) {
                targets++;
            } else if (name.equals("--classpath-file") || name.equals("--before") || name.equals("--after")) {
                targets += dumpArtifacts(value);
            } else if (name.equals("--jdk-release-old")) {
                jdkPair = true;
            }
        }
        return targets >= (jdkPair ? LARGE_FOR_JDK_PAIR : LARGE);
    }

    /** A dump spends roughly 150 bytes on an artifact. upgrade-check scans per module, so this overcounts on purpose. */
    private static long dumpArtifacts(String path) {
        if (path == null) {
            return 0;
        }
        try {
            return Files.size(Path.of(path)) / 150;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
