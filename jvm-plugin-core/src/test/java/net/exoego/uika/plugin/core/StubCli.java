package net.exoego.uika.plugin.core;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/// A stand-in for the uika-cli jar, for the suites that resolve and run one.
///
/// [#writeJar] packs this very class into a runnable jar, so it must stay ONE class file
/// that needs nothing but the JDK: no lambda, no nested class, no record. The printed line,
/// the stream and the exit code come from the jar's manifest, so one class serves the
/// clean, the violating and the failing CLI.
///
/// Every run records itself next to the `--before` file (argv[2]): `.marker` proves it ran,
/// `.args` holds the argv, `.env` the UIKA_JDK it saw, `.jvm` the JVM's own arguments one
/// per line, `.home` its java.home, and `.child` the `uika.child` property, which is what
/// tells the real jar not to start a second JVM.
///
/// Public with a public `main`, because the Maven invoker's hook scripts reach it through
/// the test class path, from outside this package.
public final class StubCli {
    private static final String CLASS_FILE = "net/exoego/uika/plugin/core/StubCli.class";

    private StubCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length > 2) {
            var before = args[2];
            Files.writeString(Path.of(before + ".marker"), "ran\n");
            Files.writeString(Path.of(before + ".args"), String.join(" ", args) + "\n");
            var jdk = System.getenv("UIKA_JDK");
            Files.writeString(Path.of(before + ".env"), (jdk == null ? "" : jdk) + "\n");
            Files.writeString(Path.of(before + ".child"), System.getProperty("uika.child") + "\n");
            Files.write(Path.of(before + ".jvm"), ManagementFactory.getRuntimeMXBean().getInputArguments());
            Files.writeString(Path.of(before + ".home"),
                    Path.of(System.getProperty("java.home")).toRealPath() + "\n");
        }
        Manifest manifest;
        try (var in = StubCli.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            manifest = new Manifest(in);
        }
        var attributes = manifest.getMainAttributes();
        var line = attributes.getValue("Stub-Line");
        if ("true".equals(attributes.getValue("Stub-Stderr"))) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        System.exit(Integer.parseInt(attributes.getValue("Stub-Exit")));
    }

    /** Writes a CLI jar that prints {@code line} to stdout and exits with {@code exit}. */
    public static void writeJar(Path jar, String line, int exit) throws IOException {
        writeJar(jar, line, false, exit);
    }

    public static void writeJar(Path jar, String line, boolean toStderr, int exit) throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, StubCli.class.getName());
        attributes.putValue("Stub-Line", line);
        attributes.putValue("Stub-Stderr", Boolean.toString(toStderr));
        attributes.putValue("Stub-Exit", Integer.toString(exit));
        Files.createDirectories(jar.toAbsolutePath().getParent());
        try (var in = StubCli.class.getClassLoader().getResourceAsStream(CLASS_FILE);
                var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (in == null) {
                throw new IOException("the stub's own class file is not on the class path");
            }
            out.putNextEntry(new JarEntry(CLASS_FILE));
            in.transferTo(out);
            out.closeEntry();
        }
    }
}
