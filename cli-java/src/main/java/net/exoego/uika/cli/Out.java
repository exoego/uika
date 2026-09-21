package net.exoego.uika.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The process's stdout and stderr, always UTF-8. The platform default follows the locale,
 * and a report is full of characters a legacy code page cannot encode. Swappable so tests
 * can run a command in-process and read what it printed.
 */
final class Out {
    static PrintStream out = utf8(FileDescriptor.out);
    static PrintStream err = utf8(FileDescriptor.err);

    private Out() {}

    private static PrintStream utf8(FileDescriptor fd) {
        return new PrintStream(new FileOutputStream(fd), false, StandardCharsets.UTF_8);
    }

    static void warn(String message) {
        err.println("warning: " + message);
    }

    static void warnAll(Iterable<String> messages) {
        for (String message : messages) {
            warn(message);
        }
    }

    static void note(String message) {
        err.println("note: " + message);
    }
}
