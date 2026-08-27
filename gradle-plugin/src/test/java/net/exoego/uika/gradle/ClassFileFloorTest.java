package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.UikaCli;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Guards build.gradle.kts's `options.release = 17`. Without it javac targets whatever
/// JDK runs the build and the jar dies with UnsupportedClassVersionError on any older
/// Gradle daemon — exactly how the released sbt 0.8.0 jar shipped major 65. Nothing in
/// a new build catches this by itself; Mill and sbt carry the same guard.
final class ClassFileFloorTest {
    @Test
    void compiledToTheJdk17Floor() throws Exception {
        for (Class<?> cls : new Class<?>[] {UikaCli.class, UikaPlugin.class}) {
            try (InputStream in = cls.getResourceAsStream(cls.getSimpleName() + ".class")) {
                byte[] header = in.readNBytes(8);
                int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                assertTrue(major <= 61,
                        cls + " has class-file major " + major + " (61 = JDK 17)");
            }
        }
    }
}
