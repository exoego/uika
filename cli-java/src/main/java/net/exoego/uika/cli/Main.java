package net.exoego.uika.cli;

import java.io.PrintStream;
import java.util.function.IntSupplier;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        // Not a plain return: worker threads and the JDK's zip cleaner must not keep a
        // finished command alive.
        System.exit(exitCode(args));
    }

    /** The command's exit code, from a child JVM with the launcher's flags when that pays, else from here. */
    static int exitCode(String[] args) {
        return guarded(() -> launch(args));
    }

    private static int launch(String[] args) {
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

    /**
     * Exit 2 on anything the command let escape. Leaving main would exit 1, the code for
     * violations found, even under {@code --fail-on never}.
     */
    static int guarded(IntSupplier command) {
        try {
            return command.getAsInt();
        } catch (Throwable t) {
            try {
                Throwable shown = t;
                // ForkJoinTask rethrows a worker's OutOfMemoryError as a message-less copy caused by the original.
                while (shown.getMessage() == null && shown.getCause() != null) {
                    shown = shown.getCause();
                }
                Out.err.println("error: " + shown);
                if (!(t instanceof VirtualMachineError)) {
                    t.printStackTrace(Out.err);
                }
                Out.err.flush();
            } catch (Throwable ignored) {
                // A second OutOfMemoryError while reporting must not cost the exit code.
            }
            return 2;
        }
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
