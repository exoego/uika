package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Guards the JDK 17 class-file floor on EVERY class of the build's main output, the way
/// sbt's checkClassFileVersions sweeps its classDirectory. A named-class allowlist proves
/// the floor only for the one javac invocation those classes share, and goes quietly
/// stale when a second compile path (generated sources, another source set) appears.
/// Failing on an empty sweep is load-bearing too, so the guard cannot pass vacuously.
///
/// One copy, mounted into the Gradle and Maven test builds the same way the main sources
/// are, because a too-lax twin never fails and so its drift is invisible.
///
/// What an unguarded floor does differs per build: bare javac (Gradle's shape) targets
/// whatever JDK runs the build and ships a jar that dies with
/// UnsupportedClassVersionError on any older daemon, exactly how the released sbt 0.8.0
/// jar shipped major 65, while Maven's compiler default (1.8) fails the compile loudly,
/// so there the guard catches the floor being RAISED or overridden rather than removed.
final class ClassFileFloorTest {
    @Test
    void everyCompiledClassMeetsTheJdk17Floor() throws Exception {
        Path classesDir = Path.of(
                UikaCli.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(classesDir)) {
            classFiles = walk.filter(path -> path.toString().endsWith(".class")).toList();
        }
        assertFalse(classFiles.isEmpty(), "no compiled classes found under " + classesDir);

        List<String> offenders = new ArrayList<>();
        for (Path classFile : classFiles) {
            byte[] header;
            try (InputStream in = Files.newInputStream(classFile)) {
                header = in.readNBytes(8);
            }
            assertTrue(header.length == 8
                            && (header[0] & 0xff) == 0xca && (header[1] & 0xff) == 0xfe
                            && (header[2] & 0xff) == 0xba && (header[3] & 0xff) == 0xbe,
                    classFile + " is not a class file");
            int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
            if (major > 61) {
                offenders.add(classesDir.relativize(classFile) + "=" + major);
            }
        }
        assertTrue(offenders.isEmpty(),
                "classes above the JDK 17 floor (major 61): " + offenders);
    }
}
