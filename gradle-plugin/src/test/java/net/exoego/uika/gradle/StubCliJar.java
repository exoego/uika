package net.exoego.uika.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/// A stand-in for the uika-cli jar, compiled once per test JVM.
///
/// Every stub records its invocation next to the `--before` file (argv[2]): `.marker`
/// proves it ran, `.args` holds the argv, `.env` the UIKA_JDK it saw, and `.child` the
/// `uika.child` property, which is what tells the real jar not to start a second JVM. The
/// printed line and the exit code come from the jar's manifest, so one class serves the
/// clean, the violating and the failing CLI.
final class StubCliJar {
    private static final String SOURCE = """
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.jar.Manifest;

            public class Stub {
                public static void main(String[] args) throws Exception {
                    String before = args[2];
                    Files.writeString(Path.of(before + ".marker"), "ran\\n");
                    Files.writeString(Path.of(before + ".args"), String.join(" ", args) + "\\n");
                    String jdk = System.getenv("UIKA_JDK");
                    Files.writeString(Path.of(before + ".env"), (jdk == null ? "" : jdk) + "\\n");
                    Files.writeString(Path.of(before + ".child"), System.getProperty("uika.child") + "\\n");
                    Manifest manifest;
                    try (var in = Stub.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
                        manifest = new Manifest(in);
                    }
                    System.out.println(manifest.getMainAttributes().getValue("Stub-Line"));
                    System.exit(Integer.parseInt(manifest.getMainAttributes().getValue("Stub-Exit")));
                }
            }
            """;

    private static byte[] stubClass;

    private StubCliJar() {}

    static void write(Path jar, String line, int exit) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Stub");
        manifest.getMainAttributes().putValue("Stub-Line", line);
        manifest.getMainAttributes().putValue("Stub-Exit", Integer.toString(exit));
        Files.createDirectories(jar.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("Stub.class"));
            out.write(compiled());
            out.closeEntry();
        }
    }

    private static synchronized byte[] compiled() throws IOException {
        if (stubClass == null) {
            var dir = Files.createTempDirectory("uika-stub-cli");
            var source = Files.writeString(dir.resolve("Stub.java"), SOURCE, StandardCharsets.UTF_8);
            var rc = javax.tools.ToolProvider.getSystemJavaCompiler()
                    .run(null, null, null, "-d", dir.toString(), source.toString());
            if (rc != 0) {
                throw new IOException("could not compile the stub CLI");
            }
            stubClass = Files.readAllBytes(dir.resolve("Stub.class"));
        }
        return stubClass;
    }
}
