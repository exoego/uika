package net.exoego.uika.maven;

import net.exoego.uika.plugin.core.UikaCli;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Guards the pom's `maven.compiler.release` 17. Without it javac targets whatever JDK
/// runs the build and the jar dies with UnsupportedClassVersionError on any older Maven
/// JVM — exactly how the released sbt 0.8.0 jar shipped major 65. Nothing in a new build
/// catches this by itself; Mill and sbt carry the same guard.
final class ClassFileFloorTest {
    @Test
    void compiledToTheJdk17Floor() throws Exception {
        for (Class<?> cls : new Class<?>[] {UikaCli.class, UpgradeCheckMojo.class}) {
            try (InputStream in = cls.getResourceAsStream(cls.getSimpleName() + ".class")) {
                byte[] header = in.readNBytes(8);
                int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                assertTrue(major <= 61,
                        cls + " has class-file major " + major + " (61 = JDK 17)");
            }
        }
    }
}
