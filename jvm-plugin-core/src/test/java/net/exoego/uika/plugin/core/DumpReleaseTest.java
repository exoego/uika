package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// What a dump records as the application's release, on the dump and on each module.
///
/// A module is allowed to declare nothing — the derivation reads the spelling that pins the
/// API, and plenty of modules pin none — and the two writers here have to agree about what
/// that means: `dumpRelease` skips it when taking the minimum, and `writeV2` omits the field
/// rather than writing a null. Both mattered to no test of their own until now. They were
/// covered incidentally, by the pom-packaged aggregator the Maven dump used to emit, which
/// was the one module in that reactor declaring no release; dropping it from the dump took
/// the only exerciser of either branch with it.
final class DumpReleaseTest {

    @Test
    void aModuleDeclaringNothingIsSkippedWhenTakingTheMinimum() {
        assertEquals(11, DumpFormat.dumpRelease(
                List.of(module(":a", null), module(":b", 17), module(":c", 11), module(":d", null))));
        // The one that declares nothing must not read as zero, or a single such module would
        // pin the dump below the floor and send upgrade-check to ct.sym for a release it has
        // never carried.
        assertEquals(17, DumpFormat.dumpRelease(List.of(module(":a", null), module(":b", 17))));
    }

    @Test
    void nothingDeclaredAtAllFallsBackToTheBuildJvm() {
        // Not a compromise: a module that declares no target compiles against whatever JDK
        // runs the build, so for it the build JVM IS the release the application runs on.
        assertEquals(DumpFormat.buildJvmRelease(), DumpFormat.dumpRelease(List.of()));
        assertEquals(DumpFormat.buildJvmRelease(),
                DumpFormat.dumpRelease(List.of(module(":a", null), module(":b", null))));
    }

    @Test
    void theFieldIsOmittedForAModuleThatDeclaresNothing() {
        String json = DumpFormat.writeV2(List.of(module(":a", null), module(":b", 17)), List.of("/repo"), 17);
        // Two modules and one dump-level value, so exactly two occurrences: the module that
        // declares nothing writes no field at all. Written as null it would parse as a
        // declaration the module never made, and the CLI applies the dump-level value as
        // that module's fallback precisely because the field is absent.
        assertEquals(2, json.split("\"jdkRelease\":", -1).length - 1,
                () -> "only the declaring module and the dump itself carry jdkRelease: " + json);
        assertFalse(json.contains("null"), () -> "no null reached the JSON: " + json);
        assertTrue(json.contains("\"jdkRelease\":17"), json);
    }

    /// The path is passed in rather than derived from the release: a derived name would
    /// spell the undeclared module ":mnull" and make the no-null assertion above trip on
    /// itself.
    private static ClasspathDump.Module module(String path, Integer release) {
        return new ClasspathDump.Module(path, List.of(), List.of(), release);
    }
}
