package net.exoego.uika.plugin.core;

import jdk.jfr.Configuration;
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
        record(dir, jfr, true, depth, className);
    }

    /** Like {@link #recordFreshClassLoad}, loading every class in order in one recording. */
    public static void recordFreshClassLoads(Path dir, Path jfr, String... classNames)
            throws Exception {
        record(dir, jfr, true, 0, classNames);
    }

    /** Like {@link #recordFreshClassLoad}, with stack traces switched off. */
    public static void recordStacklessClassLoad(Path dir, Path jfr, String className)
            throws Exception {
        record(dir, jfr, false, 0, className);
    }

    /** A recording made with the JDK's default settings, which leave jdk.ClassLoad off. */
    public static void recordWithDefaultSettings(Path jfr) throws Exception {
        try (var recording = new Recording(Configuration.getConfiguration("default"))) {
            recording.start();
            recording.stop();
            recording.dump(jfr);
        }
    }

    private static void record(Path dir, Path jfr, boolean stackTrace, int depth,
            String... classNames) throws Exception {
        var javacArgs = new String[classNames.length + 2];
        javacArgs[0] = "-d";
        javacArgs[1] = dir.toString();
        for (var i = 0; i < classNames.length; i++) {
            var source = dir.resolve(classNames[i] + ".java");
            Files.writeString(source, "public class " + classNames[i] + " {}");
            javacArgs[i + 2] = source.toString();
        }
        var rc = javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, javacArgs);
        if (rc != 0) {
            throw new IllegalStateException("javac failed for " + String.join(", ", classNames));
        }
        try (var recording = new Recording()) {
            var settings = recording.enable("jdk.ClassLoad").withoutThreshold();
            if (stackTrace) {
                settings.withStackTrace();
            } else {
                settings.withoutStackTrace();
            }
            recording.start();
            descend(depth, dir, classNames);
            recording.stop();
            recording.dump(jfr);
        }
    }

    private static void descend(int n, Path dir, String... classNames) throws Exception {
        if (n > 0) {
            descend(n - 1, dir, classNames);
            return;
        }
        try (var loader =
                new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            for (String className : classNames) {
                Class.forName(className, false, loader);
            }
        }
    }
}
