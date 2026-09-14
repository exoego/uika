package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The plugin test suites run on Linux x86_64 alone, so the other published platforms are
/// reached only here.
final class PlatformClassifierTest {
    @Test
    void mapsEveryPublishedPlatform() {
        assertEquals("linux-x86_64", UikaCli.platformClassifier("Linux", "amd64"));
        assertEquals("linux-x86_64", UikaCli.platformClassifier("Linux", "x86_64"));
        assertEquals("macos-aarch64", UikaCli.platformClassifier("Mac OS X", "aarch64"));
        assertEquals("macos-aarch64", UikaCli.platformClassifier("Mac OS X", "arm64"));
        assertEquals("macos-x86_64", UikaCli.platformClassifier("Mac OS X", "x86_64"));
        assertEquals("windows-x86_64", UikaCli.platformClassifier("Windows 11", "amd64"));
    }

    @Test
    void anUnpublishedPlatformFailsListingThePublishedOnes() {
        var boom = assertThrows(IllegalStateException.class,
                () -> UikaCli.platformClassifier("Linux", "aarch64"));
        assertTrue(boom.getMessage().contains("linux/aarch64"), boom.getMessage());
        assertTrue(boom.getMessage().contains(
                        "available: linux-x86_64, macos-aarch64, macos-x86_64, windows-x86_64"),
                boom.getMessage());
        assertThrows(IllegalStateException.class,
                () -> UikaCli.platformClassifier("Windows 11", "aarch64"));
    }
}
