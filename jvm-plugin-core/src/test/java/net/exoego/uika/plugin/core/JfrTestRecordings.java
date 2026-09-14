package net.exoego.uika.plugin.core;

import jdk.jfr.Recording;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records a REAL jdk.ClassLoad event for a class guaranteed to be freshly loaded. A member
 * class of the test cannot serve as the probe: JUnit discovery resolves nested classes via
 * {@code getDeclaredClasses()}, which loads them before any test body runs, so a recording
 * started inside the test never sees their load. The probe is therefore compiled at
 * runtime and loaded through a brand-new URLClassLoader, which defines its own copy no
 * matter what the test JVM loaded before.
 */
public final class JfrTestRecordings {
    private JfrTestRecordings() {}

    /** Compile {@code className} into {@code dir}, then record its load into {@code jfr}. */
    public static void recordFreshClassLoad(Path dir, Path jfr, String className)
            throws Exception {
        recordFreshClassLoadAtDepth(dir, jfr, className, 0);
    }

    /**
     * Like {@link #recordFreshClassLoad}, with the load performed under {@code depth}
     * extra stack frames, to exercise JFR's stack-depth truncation against a real
     * recording.
     */
    public static void recordFreshClassLoadAtDepth(Path dir, Path jfr, String className,
            int depth) throws Exception {
        var source = dir.resolve(className + ".java");
        Files.writeString(source, "public class " + className + " {}");
        var rc = javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", dir.toString(), source.toString());
        if (rc != 0) {
            throw new IllegalStateException("javac failed for " + source);
        }
        try (var recording = new Recording()) {
            recording.enable("jdk.ClassLoad").withStackTrace().withoutThreshold();
            recording.start();
            descend(depth, dir, className);
            recording.stop();
            recording.dump(jfr);
        }
    }

    private static void descend(int n, Path dir, String className) throws Exception {
        if (n > 0) {
            descend(n - 1, dir, className);
            return;
        }
        try (var loader =
                new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            Class.forName(className, false, loader);
        }
    }
}
