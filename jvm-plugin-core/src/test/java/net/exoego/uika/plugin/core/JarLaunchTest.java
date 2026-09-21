package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// What `runUpgradeCheck` does with the CLI jar, read back from a stub jar that reports the
/// JVM it was started in. A real child JVM, because the point is what reaches it: a flag
/// this JVM rejects, or a relaunch the property failed to switch off, shows only there.
final class JarLaunchTest {
    @TempDir
    Path dir;

    @Test
    void theJarRunsOnThisJvmWithTheLauncherFlags() throws Exception {
        var before = Files.writeString(dir.resolve("before.json"), "{}");
        var after = Files.writeString(dir.resolve("after.json"), "{}");
        var output = new ArrayList<String>();

        var exit = UikaCli.runUpgradeCheck(stubJar(), before, after, "any", null, null,
                UikaCli.JdkSource.current(), null, null, false, output::add);

        assertEquals(3, exit, String.join("\n", output));
        assertEquals(List.of("upgrade-check", "--before", before.toString(), "--after", after.toString(),
                "--fail-on", "any"), tagged(output, "arg:"));
        var jvm = tagged(output, "jvm:");
        assertTrue(jvm.containsAll(UikaCli.JVM_FLAGS), jvm.toString());
        assertTrue(jvm.contains(UikaCli.SMALL_RUN_FLAG), jvm.toString());
        // Without it the jar starts a second JVM and this one idles for the whole run.
        assertEquals(List.of("true"), tagged(output, "child:"));
        assertEquals(List.of(Path.of(System.getProperty("java.home")).toRealPath().toString()),
                tagged(output, "home:"));
    }

    /// C1 alone loses to C2 on a large scan, and the dumps' size is the only measure of the
    /// scan that costs nothing to read.
    @Test
    void largeDumpsKeepTheOptimizingCompiler() throws Exception {
        var half = new byte[UikaCli.LARGE * UikaCli.DUMP_BYTES_PER_ARTIFACT / 2];
        var before = Files.write(dir.resolve("before.json"), half);
        var after = Files.write(dir.resolve("after.json"), half);

        var command = UikaCli.launchCommand(Path.of("uika-cli.jar"), before, after);

        assertFalse(command.contains(UikaCli.SMALL_RUN_FLAG), command.toString());
        assertTrue(command.containsAll(UikaCli.JVM_FLAGS), command.toString());
    }

    @Test
    void aNativeBinaryStartsItself() {
        var binary = dir.resolve("uika");
        assertEquals(List.of(binary.toString()),
                UikaCli.launchCommand(binary, dir.resolve("before.json"), dir.resolve("after.json")));
    }

    private static List<String> tagged(List<String> output, String tag) {
        return output.stream().filter(line -> line.startsWith(tag))
                .map(line -> line.substring(tag.length())).toList();
    }

    private Path stubJar() throws IOException {
        var source = Files.writeString(dir.resolve("Stub.java"), """
                import java.lang.management.ManagementFactory;
                import java.nio.file.Path;

                public class Stub {
                    public static void main(String[] args) throws Exception {
                        for (String flag : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                            System.out.println("jvm:" + flag);
                        }
                        System.out.println("child:" + System.getProperty("uika.child"));
                        System.out.println("home:" + Path.of(System.getProperty("java.home")).toRealPath());
                        for (String arg : args) {
                            System.out.println("arg:" + arg);
                        }
                        System.exit(3);
                    }
                }
                """);
        var rc = javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", dir.toString(), source.toString());
        assertEquals(0, rc, "could not compile the stub");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Stub");
        var jar = dir.resolve("uika-cli-stub.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("Stub.class"));
            out.write(Files.readAllBytes(dir.resolve("Stub.class")));
            out.closeEntry();
        }
        return jar;
    }
}
