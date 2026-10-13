package net.exoego.uika.gradle;

import net.exoego.uika.gradle.UpgradeCheckTask.CompilerJdk;
import net.exoego.uika.plugin.core.UikaCli;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Which JDK's ct.sym the check reads, decided without a build: the integration tests
/// cover the toolchain lookup itself, this pins the choice among the answers.
final class CtSymSourceTest {
    private final UikaCli.JdkSource build = new UikaCli.JdkSource(Path.of("/jdk/build"), 21);

    @Test
    void theBuildJvmStaysWithoutANewerCompiler() {
        var log = new ArrayList<String>();

        assertSame(build, UpgradeCheckTask.ctSymSource(build, List.of(), log::add));
        // A tie goes to the build JVM, so a build whose toolchain matches its daemon behaves
        // as before, and an older toolchain cannot serve what the build JVM already does.
        assertSame(build, UpgradeCheckTask.ctSymSource(build,
                List.of(new CompilerJdk("/jdk/same", 21), new CompilerJdk("/jdk/older", 17)), log::add));

        assertTrue(log.isEmpty(), () -> "an unchanged choice was announced: " + log);
    }

    @Test
    void theNewestCompilerWinsAndIsAnnounced() {
        var log = new ArrayList<String>();

        var chosen = UpgradeCheckTask.ctSymSource(build, List.of(
                new CompilerJdk("/jdk/17", 17),
                new CompilerJdk("/jdk/25", 25),
                new CompilerJdk("/jdk/23", 23)), log::add);

        assertEquals(Path.of("/jdk/25"), chosen.home());
        assertEquals(25, chosen.feature());
        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("JDK 25 at /jdk/25") && log.get(0).contains("JDK 21"),
                () -> "the line must name both JDKs: " + log);
    }
}
