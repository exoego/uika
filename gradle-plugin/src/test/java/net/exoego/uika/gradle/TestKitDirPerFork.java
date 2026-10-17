package net.exoego.uika.gradle;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/// Gives each test fork a TestKit dir of its own. Forks sharing one dir spent most of their
/// time waiting on each other's Gradle user home locks: with three forks the largest class
/// took 21 seconds against 9 alone.
public final class TestKitDirPerFork implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        var base = System.getProperty("uika.testkit.base");
        if (base == null) {
            return;
        }
        var dir = Path.of(base, "fork-" + System.getProperty("org.gradle.test.worker"));
        try {
            Files.createDirectories(dir);
            Files.copy(Path.of(base, "gradle.properties"), dir.resolve("gradle.properties"), REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.setProperty("org.gradle.testkit.dir", dir.toString());
    }
}
