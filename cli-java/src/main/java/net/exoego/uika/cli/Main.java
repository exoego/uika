package net.exoego.uika.cli;

import java.io.PrintStream;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        // Not a plain return: worker threads and the JDK's zip cleaner must not keep a
        // finished command alive.
        System.exit(exitCode(args));
    }

    /** The command's exit code, from a child JVM with the launcher's flags when that pays, else from here. */
    static int exitCode(String[] args) {
        if (Launcher.shouldRelaunch(args)) {
            int child = Launcher.relaunch(args);
            if (child >= 0) {
                return child;
            }
        }
        int code = run(args);
        Out.out.flush();
        Out.err.flush();
        return code;
    }

    /** Runs one command and returns its exit code: 0 clean, 1 violations found, 2 error. */
    static int run(String[] args) {
        try {
            return Commands.run(Cli.parse(args));
        } catch (Cli.UsageException e) {
            Out.err.print(e.getMessage());
            return 2;
        } catch (UikaException e) {
            Out.err.println("error: " + e.getMessage());
            return 2;
        } finally {
            Out.out.flush();
            Out.err.flush();
        }
    }

    /** {@link #run} with stdout and stderr captured, for tests that drive the CLI in-process. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        PrintStream previousOut = Out.out;
        PrintStream previousErr = Out.err;
        Out.out = out;
        Out.err = err;
        try {
            return run(args);
        } finally {
            Out.out = previousOut;
            Out.err = previousErr;
        }
    }
}
