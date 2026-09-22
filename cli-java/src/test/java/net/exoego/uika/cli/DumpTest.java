package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ports the `gradle.rs` tests. */
class DumpTest {
    @TempDir
    Path dir;

    private String write(String file, String json) throws Exception {
        Path path = dir.resolve(file);
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return path.toString();
    }

    @Test
    void parsesV1AndV2ToSameUniverse() throws Exception {
        String v1 = """
                {"modules":[
                    {"module":":app","classesDirs":["/repo/app/build/classes/kotlin/main"],"artifacts":[
                        {"group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13","file":"/cache/modules-2/files-2.1/io.ktor/ktor-io-jvm/2.3.13/ab/ktor-io-jvm-2.3.13.jar"},
                        {"file":"/repo/libs/local.jar"}
                    ]}]}""";
        String v2 = """
                {"version":2,
                    "roots":["/cache/modules-2/files-2.1/","/repo/"],
                    "artifacts":[
                        {"group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13","root":0,"path":"io.ktor/ktor-io-jvm/2.3.13/ab/ktor-io-jvm-2.3.13.jar"},
                        {"root":1,"path":"libs/local.jar"}
                    ],
                    "modules":[
                        {"module":":app","classesDirs":[{"root":1,"path":"app/build/classes/kotlin/main"}],"artifactRefs":[0,1]}
                    ]}""";
        Dump.Universe u1 = Dump.loadDump(write("v1.json", v1));
        Dump.Universe u2 = Dump.loadDump(write("v2.json", v2));
        assertEquals(u1.scanTargets, u2.scanTargets);
        assertEquals(u1.versions, u2.versions);
        // Both carry the per-module view of name, classesDirs, and the module's own artifacts.
        for (Dump.Universe u : List.of(u1, u2)) {
            assertEquals(1, u.modules.size());
            Dump.Module m = u.modules.get(0);
            assertEquals(":app", m.name);
            assertEquals(List.of("/repo/app/build/classes/kotlin/main"), m.classesDirs);
            assertEquals(2, m.artifacts.size());
            assertEquals("io.ktor", m.artifacts.get(0).group());
            assertEquals("ktor-io-jvm", m.artifacts.get(0).name());
            assertEquals("2.3.13", m.artifacts.get(0).version());
            assertFalse(m.artifacts.get(1).hasCoordinate());
            assertEquals("/repo/libs/local.jar", m.artifacts.get(1).file());
        }
        assertEquals(
                "/cache/modules-2/files-2.1/io.ktor/ktor-io-jvm/2.3.13/ab/ktor-io-jvm-2.3.13.jar",
                u2.versions.get(new Dump.Coord("io.ktor", "ktor-io-jvm")).get("2.3.13"));
        // First-seen order, which is artifacts as the dump lists them and then the build outputs.
        assertEquals(
                List.of(
                        "/cache/modules-2/files-2.1/io.ktor/ktor-io-jvm/2.3.13/ab/ktor-io-jvm-2.3.13.jar",
                        "/repo/libs/local.jar",
                        "/repo/app/build/classes/kotlin/main"),
                u1.scanTargets);
        assertEquals(List.of("/repo/app/build/classes/kotlin/main"), u2.appRoots);
    }

    private static String perModuleDump(String libVersion) {
        return """
                {"version":2,
                "roots":["/repo/"],
                "artifacts":[
                    {"group":"g","name":"lib","version":"%1$s","root":0,"path":"cache/lib-%1$s.jar"},
                    {"group":"g","name":"lib","version":"1.0","root":0,"path":"cache/lib-1.0.jar"},
                    {"root":0,"path":"shared/build/libs/shared.jar","project":":shared"}
                ],
                "modules":[
                    {"module":":app","classesDirs":[{"root":0,"path":"app/build/classes"}],"artifactRefs":[0,2]},
                    {"module":":pinned","classesDirs":[],"artifactRefs":[1]},
                    {"module":":shared","classesDirs":[{"root":0,"path":"shared/build/classes"}],"artifactRefs":[]}
                ]}"""
                .formatted(libVersion);
    }

    /**
     * v2 "project" attribution parses through to Artifact.project, and per-module diffing
     * gates on the module's own resolution. A module that keeps its version has no old jars
     * even when a sibling module upgrades the same coordinate.
     */
    @Test
    void perModuleViewAttributesProjectsAndDiffsPerModule() throws Exception {
        Dump.Universe before = Dump.loadDump(write("before.json", perModuleDump("1.0")));
        Dump.Universe after = Dump.loadDump(write("after.json", perModuleDump("2.0")));

        assertEquals(3, after.modules.size());
        Dump.Module app = after.module(":app");
        assertEquals(":shared", app.artifacts.get(1).project());

        // :app moved g:lib 1.0 -> 2.0.
        TreeSet<Dump.Coord> exclude = Dump.projectCoordsUnion(before, after);
        Dump.DependencyChanges appDiff = Dump.diffVersionMaps(before.module(":app").versions(), app.versions(), exclude);
        assertEquals(List.of("/repo/cache/lib-1.0.jar"), appDiff.oldJars());
        assertEquals(List.of("/repo/cache/lib-2.0.jar"), appDiff.newJars());

        // :pinned still resolves 1.0, so there is nothing to check even though the
        // universe-wide version set for g:lib changed.
        Dump.DependencyChanges pinnedDiff =
                Dump.diffVersionMaps(before.module(":pinned").versions(), after.module(":pinned").versions(), exclude);
        assertTrue(pinnedDiff.oldJars().isEmpty());

        // The universe-wide diff misses :app's upgrade entirely (1.0 is still resolved by
        // :pinned on the after side), which is exactly why upgrade-check works per module.
        Dump.DependencyChanges global = Dump.diffDumps(before, after);
        assertTrue(global.oldJars().isEmpty());
    }

    private static String reactorDump(String version, boolean attributed) {
        String project = attributed ? "\"project\":\":lib\"," : "";
        return """
                {"version":2,
                "roots":["/repo/"],
                "artifacts":[
                    {"group":"com.example","name":"lib","version":"%1$s",%2$s"root":0,"path":"lib/target/lib-%1$s.jar"}
                ],
                "modules":[
                    {"module":":app","classesDirs":[],"artifactRefs":[0]},
                    {"module":":lib","classesDirs":[{"root":0,"path":"lib/target/classes"}],"artifactRefs":[]}
                ]}"""
                .formatted(version, project);
    }

    /**
     * A reactor/project dependency carries coordinates in Maven dumps. With project
     * attribution it must stay out of the version DIFF (bumping the project's own version
     * is not a dependency upgrade to check) while remaining in the version maps so
     * suggestions can still attribute a referencing reactor jar. The exclusion is the
     * UNION of both dumps' attributed coordinates, so a before dump from an older,
     * non-attributing plugin does not diff as a removal.
     */
    @Test
    void projectAttributedArtifactsAreNotVersionDiffed() throws Exception {
        Dump.Universe before = Dump.loadDump(write("before.json", reactorDump("1.0.0", true)));
        Dump.Universe after = Dump.loadDump(write("after.json", reactorDump("1.1.0", true)));

        // In the maps (for suggestion attribution), out of the diff (not an upgrade).
        assertFalse(before.versions.isEmpty());
        TreeSet<Dump.Coord> exclude = Dump.projectCoordsUnion(before, after);
        Dump.DependencyChanges appDiff =
                Dump.diffVersionMaps(before.module(":app").versions(), after.module(":app").versions(), exclude);
        assertTrue(appDiff.oldJars().isEmpty());
        assertTrue(appDiff.changes().isEmpty());
        assertTrue(Dump.diffDumps(before, after).oldJars().isEmpty());
        // The artifact itself still reaches the scan through the module's classpath.
        assertEquals(1, after.module(":app").artifacts.size());

        // Asymmetric plugins, where the before dump was written pre-attribution. The union
        // exclusion keeps the identical coordinate from diffing as Removed.
        Dump.Universe beforeOldPlugin = Dump.loadDump(write("before.json", reactorDump("1.0.0", false)));
        Dump.Universe afterSame = Dump.loadDump(write("after.json", reactorDump("1.0.0", true)));
        Dump.DependencyChanges global = Dump.diffDumps(beforeOldPlugin, afterSame);
        assertTrue(global.changes().isEmpty(), global.changes().toString());
        assertTrue(global.oldJars().isEmpty());
        TreeSet<Dump.Coord> union = Dump.projectCoordsUnion(beforeOldPlugin, afterSame);
        Dump.DependencyChanges asymmetric =
                Dump.diffVersionMaps(beforeOldPlugin.module(":app").versions(), afterSame.module(":app").versions(), union);
        assertTrue(asymmetric.oldJars().isEmpty());
    }

    /**
     * A v2 module without a name cannot be paired with its before-side counterpart, so the
     * whole dump degrades to the merged universe instead of pairing modules positionally.
     */
    @Test
    void unnamedV2ModuleDisablesThePerModuleView() throws Exception {
        String path = write("dump.json", """
                {"version":2,
                "roots":["/repo/"],
                "artifacts":[{"group":"g","name":"lib","version":"1.0","root":0,"path":"lib-1.0.jar"}],
                "modules":[
                    {"module":":app","classesDirs":[],"artifactRefs":[0]},
                    {"classesDirs":[{"root":0,"path":"x/classes"}],"artifactRefs":[0]}
                ]}""");
        Dump.Universe universe = Dump.loadDump(path);
        assertTrue(universe.modules.isEmpty());
        // The merged view still carries everything.
        assertEquals(2, universe.scanTargets.size());
        assertFalse(universe.versions.isEmpty());
    }

    /** {group, name, version, file} rows. */
    private static Dump.Universe universe(String[]... entries) {
        Dump.Universe universe = new Dump.Universe();
        for (String[] e : entries) {
            universe.versions.add(e[0], e[1], e[2], e[3]);
            universe.scanTargets.add(e[3]);
        }
        return universe;
    }

    @Test
    void detectsVersionChangeRemovalAndAddition() {
        Dump.Universe before = universe(
                new String[] {"io.otel", "sdk-common", "1.42.1", "/old/sdk-common.jar"},
                new String[] {"io.otel", "sender", "1.42.1", "/old/sender.jar"},
                new String[] {"a", "gone", "1.0", "/old/gone.jar"});
        Dump.Universe after = universe(
                new String[] {"io.otel", "sdk-common", "1.60.1", "/new/sdk-common.jar"},
                new String[] {"io.otel", "sender", "1.42.1", "/old/sender.jar"},
                new String[] {"b", "fresh", "2.0", "/new/fresh.jar"});
        Dump.DependencyChanges diff = Dump.diffDumps(before, after);
        assertEquals(List.of("/old/gone.jar", "/old/sdk-common.jar"), diff.oldJars());
        assertEquals(List.of("/new/sdk-common.jar"), diff.newJars());
        List<String> kinds = new ArrayList<>();
        for (Dump.DependencyChange c : diff.changes()) {
            kinds.add(c.coordinate() + " " + c.kind());
        }
        assertTrue(kinds.contains("io.otel:sdk-common CHANGED"));
        assertTrue(kinds.contains("a:gone REMOVED"));
        assertTrue(kinds.contains("b:fresh ADDED"));
        // The unchanged sender is not included in changes.
        assertFalse(kinds.stream().anyMatch(k -> k.startsWith("io.otel:sender")));
        // Removals and changes in coordinate order first, then the additions.
        assertEquals(List.of("a:gone REMOVED", "io.otel:sdk-common CHANGED", "b:fresh ADDED"), kinds);
        assertEquals(List.of("1.42.1"), diff.changes().get(1).before());
        assertEquals(List.of("1.60.1"), diff.changes().get(1).after());
        assertEquals(List.of(), diff.changes().get(0).after());
        assertEquals("changed", Dump.ChangeKind.CHANGED.json);
    }

    // ---- beyond the Rust unit tests ----

    /**
     * The release recorded per module falls back to the dump's, and both stay absent for a
     * dump that predates the field, so an old before-dump never claims a JDK move.
     */
    @Test
    void jdkReleaseFallsBackFromModuleToDump() throws Exception {
        Dump.Universe v2 = Dump.loadDump(write("release-v2.json", """
                {"version":2,"roots":["/r/"],"jdkRelease":17,"artifacts":[],
                "modules":[
                    {"module":":declares","jdkRelease":11,"classesDirs":[],"artifactRefs":[]},
                    {"module":":inherits","classesDirs":[],"artifactRefs":[]},
                    {"module":":null","jdkRelease":null,"classesDirs":[],"artifactRefs":[]}
                ]}"""));
        assertEquals(17, v2.jdkRelease);
        assertEquals(11, v2.module(":declares").jdkRelease);
        assertEquals(17, v2.module(":inherits").jdkRelease);
        assertEquals(17, v2.module(":null").jdkRelease);

        Dump.Universe v1 = Dump.loadDump(write("release-v1.json", """
                {"jdkRelease":21,"modules":[{"module":":a","jdkRelease":8},{"module":":b"}]}"""));
        assertEquals(21, v1.jdkRelease);
        assertEquals(8, v1.module(":a").jdkRelease);
        assertEquals(21, v1.module(":b").jdkRelease);
        assertEquals(List.of(), v1.module(":b").artifacts);
        assertEquals(List.of(), v1.module(":b").classesDirs);

        Dump.Universe old = Dump.loadDump(write("release-old.json", "{\"modules\":[{\"module\":\":a\"}]}"));
        assertNull(old.jdkRelease);
        assertNull(old.module(":a").jdkRelease);
        assertNull(old.module(":missing"));
    }

    @Test
    void scanTargetsAreDeduplicatedInFirstSeenOrder() throws Exception {
        Dump.Universe u = Dump.loadDump(write("dedup.json", """
                {"modules":[
                    {"module":":a","classesDirs":["/r/a/classes"],"artifacts":[{"file":"/r/shared.jar"},{"file":"/r/a-only.jar"}]},
                    {"module":":b","classesDirs":["/r/b/classes","/r/a/classes"],"artifacts":[{"file":"/r/shared.jar"},{"file":"/r/a/classes"}]}
                ]}"""));
        assertEquals(List.of("/r/shared.jar", "/r/a-only.jar", "/r/a/classes", "/r/b/classes"), u.scanTargets);
        // Roots are not deduplicated, because they are matched and not scanned.
        assertEquals(List.of("/r/a/classes", "/r/b/classes", "/r/a/classes"), u.appRoots);
        assertEquals(2, u.module(":b").artifacts.size());
    }

    @Test
    void aPartialCoordinateIsNoCoordinate() throws Exception {
        Dump.Universe u = Dump.loadDump(write("partial.json", """
                {"modules":[{"module":":a","artifacts":[
                    {"group":"g","name":"n","file":"/r/no-version.jar","project":":p"},
                    {"group":"g","name":"n","version":"1","file":"/r/full.jar","project":":p"}
                ]}]}"""));
        assertFalse(u.module(":a").artifacts.get(0).hasCoordinate());
        assertEquals(":p", u.module(":a").artifacts.get(0).project());
        assertEquals(List.of(new Dump.Coord("g", "n")), new ArrayList<>(u.projectCoords));
        assertEquals(1, u.versions.size());
    }

    @Test
    void aDumpThatCannotBeUsedEndsTheCommandWithItsPath() throws Exception {
        String missing = dir.resolve("missing.json").toString();
        assertEquals(
                "cannot read classpath dump " + missing + ": No such file or directory",
                assertThrows(UikaException.class, () -> Dump.loadDump(missing)).getMessage());

        Path latin1 = dir.resolve("latin1.json");
        Files.write(latin1, "{\"modules\":[{\"module\":\":caf\u00e9\"}]}".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(
                "cannot read classpath dump " + latin1 + ": not valid UTF-8",
                assertThrows(UikaException.class, () -> Dump.loadDump(latin1.toString())).getMessage());

        String broken = write("broken.json", "{\"modules\":[1,");
        assertEquals(
                "invalid classpath dump " + broken + ": EOF while parsing a value at line 1 column 14",
                assertThrows(UikaException.class, () -> Dump.loadDump(broken)).getMessage());

        String noModules = write("no-modules.json", "{}");
        assertEquals(
                "invalid v1 classpath dump " + noModules + ": missing field \"modules\"",
                assertThrows(UikaException.class, () -> Dump.loadDump(noModules)).getMessage());

        String noRoots = write("no-roots.json", "{\"version\":2,\"artifacts\":[]}");
        assertEquals(
                "invalid v2 classpath dump " + noRoots + ": missing field \"roots\"",
                assertThrows(UikaException.class, () -> Dump.loadDump(noRoots)).getMessage());

        String badRoot = write("bad-root.json", "{\"version\":2,\"roots\":[\"/r/\"],\"artifacts\":[{\"root\":3,\"path\":\"x.jar\"}]}");
        assertEquals(
                "invalid v2 classpath dump " + badRoot + ": artifacts[0].root: index 3 is out of range because roots has 1 entry",
                assertThrows(UikaException.class, () -> Dump.loadDump(badRoot)).getMessage());

        String badRef = write(
                "bad-ref.json",
                "{\"version\":2,\"roots\":[\"/r/\"],\"artifacts\":[],\"modules\":[{\"module\":\":a\",\"artifactRefs\":[5]}]}");
        assertEquals(
                "invalid v2 classpath dump " + badRef + ": modules[0].artifactRefs[0]: index 5 is out of range because artifacts has 0 entries",
                assertThrows(UikaException.class, () -> Dump.loadDump(badRef)).getMessage());
    }

    /** Each case is {dump, message}, with %s standing for the dump's file path. */
    private void assertShapeErrors(String[][] cases) throws Exception {
        for (int i = 0; i < cases.length; i++) {
            String path = write("shape-" + i + ".json", cases[i][0]);
            assertEquals(
                    cases[i][1].formatted(path),
                    assertThrows(UikaException.class, () -> Dump.loadDump(path)).getMessage(),
                    cases[i][0]);
        }
    }

    /** A value of the wrong type is named by its JSON kind, and an explicit null is not an absent field. */
    @Test
    void shapeErrorsNameEveryJsonKind() throws Exception {
        assertShapeErrors(new String[][] {
            {"\"x\"", "invalid v1 classpath dump %s: expected an object, found the string \"x\""},
            {"{\"modules\":true}", "invalid v1 classpath dump %s: modules: expected an array, found true"},
            {"{\"modules\":[{\"module\":\":a\",\"artifacts\":null}]}",
                "invalid v1 classpath dump %s: modules[0].artifacts: expected an array, found null"},
            {"{\"modules\":[{\"module\":[]}]}", "invalid v1 classpath dump %s: modules[0].module: expected a string, found an array"},
            {"{\"modules\":[{\"module\":\":a\",\"artifacts\":[{\"file\":5}]}]}",
                "invalid v1 classpath dump %s: modules[0].artifacts[0].file: expected a string, found 5"},
            {"{\"version\":2,\"roots\":{},\"artifacts\":[]}", "invalid v2 classpath dump %s: roots: expected an array, found an object"},
            {"{\"version\":2,\"roots\":[\"/r/\"],\"artifacts\":[{\"root\":0.5,\"path\":\"x\"}]}",
                "invalid v2 classpath dump %s: artifacts[0].root: expected a whole number, found 0.5"},
        });
    }

    /** A whole number outside what the field takes names the accepted range. */
    @Test
    void numberErrorsNameTheAcceptedRange() throws Exception {
        assertShapeErrors(new String[][] {
            {"{\"version\":2,\"roots\":[\"/r/\"],\"artifacts\":[{\"root\":-1,\"path\":\"x.jar\"}]}",
                "invalid v2 classpath dump %s: artifacts[0].root: expected a whole number of 0 or more, found -1"},
            {"{\"version\":2,\"roots\":[\"/r/\"],\"artifacts\":[{\"root\":3000000000,\"path\":\"x.jar\"}]}",
                "invalid v2 classpath dump %s: artifacts[0].root: index 3000000000 is out of range because roots has 1 entry"},
            {"{\"version\":2,\"roots\":[],\"artifacts\":[{\"root\":0,\"path\":\"x.jar\"}]}",
                "invalid v2 classpath dump %s: artifacts[0].root: index 0 is out of range because roots has 0 entries"},
            {"{\"version\":2,\"roots\":[],\"artifacts\":[],\"jdkRelease\":-1}",
                "invalid v2 classpath dump %s: jdkRelease: expected a Java release number such as 17, found -1"},
            {"{\"version\":2,\"roots\":[],\"artifacts\":[],\"jdkRelease\":4294967296}",
                "invalid v2 classpath dump %s: jdkRelease: expected a Java release number such as 17, found 4294967296"},
            {"{\"version\":2,\"jdkRelease\":\"17\",\"roots\":[],\"artifacts\":[]}",
                "invalid v2 classpath dump %s: jdkRelease: expected a whole number, found the string \"17\""},
        });
    }

    /** The path runs from the top of the file down to the entry of a nested array. */
    @Test
    void shapeErrorsNameWhereTheValueIs() throws Exception {
        String v2 = "{\"version\":2,\"roots\":[\"/r/\"],\"artifacts\":[{\"root\":0,\"path\":\"a.jar\"}],\"modules\":[%s]}";
        assertShapeErrors(new String[][] {
            {"{\"modules\":[{\"module\":\":a\"},{\"artifacts\":[]}]}", "invalid v1 classpath dump %s: modules[1]: missing field \"module\""},
            {"{\"modules\":[{\"module\":\":a\",\"artifacts\":[{\"file\":\"/a.jar\"},{\"group\":\"g\"}]}]}",
                "invalid v1 classpath dump %s: modules[0].artifacts[1]: missing field \"file\""},
            {"{\"modules\":[{\"module\":\":a\",\"artifacts\":[{\"file\":\"/a.jar\",\"group\":false}]}]}",
                "invalid v1 classpath dump %s: modules[0].artifacts[0].group: expected a string, found false"},
            {"{\"modules\":[{\"module\":\":a\",\"classesDirs\":[\"/c\",null]}]}",
                "invalid v1 classpath dump %s: modules[0].classesDirs[1]: expected a string, found null"},
            {"{\"modules\":[{\"module\":\":a\"},{\"module\":\":b\",\"jdkRelease\":\"17\"}]}",
                "invalid v1 classpath dump %s: modules[1].jdkRelease: expected a whole number, found the string \"17\""},
            {"{\"version\":2,\"roots\":[\"/r/\",true],\"artifacts\":[]}", "invalid v2 classpath dump %s: roots[1]: expected a string, found true"},
            {"{\"version\":2,\"roots\":[\"/r/\"],\"artifacts\":[{\"root\":0,\"path\":\"a.jar\"},{\"root\":0}]}",
                "invalid v2 classpath dump %s: artifacts[1]: missing field \"path\""},
            {v2.formatted("{\"module\":\":a\"},[]"), "invalid v2 classpath dump %s: modules[1]: expected an object, found an array"},
            {v2.formatted("{\"module\":1}"), "invalid v2 classpath dump %s: modules[0].module: expected a string, found 1"},
            {v2.formatted("{\"module\":\":a\",\"classesDirs\":[\"/r/c\"]}"),
                "invalid v2 classpath dump %s: modules[0].classesDirs[0]: expected an object, found the string \"/r/c\""},
            {v2.formatted("{\"module\":\":a\",\"classesDirs\":[{\"root\":0}]}"),
                "invalid v2 classpath dump %s: modules[0].classesDirs[0]: missing field \"path\""},
            {v2.formatted("{\"module\":\":a\"},{\"module\":\":b\",\"classesDirs\":[{\"root\":\"0\",\"path\":\"c\"}]}"),
                "invalid v2 classpath dump %s: modules[1].classesDirs[0].root: expected a whole number, found the string \"0\""},
            {v2.formatted("{\"module\":\":a\",\"artifactRefs\":[0,\"0\"]}"),
                "invalid v2 classpath dump %s: modules[0].artifactRefs[1]: expected a whole number, found the string \"0\""},
            {v2.formatted("{\"module\":\":a\",\"jdkRelease\":4294967296}"),
                "invalid v2 classpath dump %s: modules[0].jdkRelease: expected a Java release number such as 17, found 4294967296"},
        });
    }

    /** A long string is cut short, and escaping keeps a line break in it from breaking the message. */
    @Test
    void aShownStringStaysShortAndOnOneLine() throws Exception {
        assertShapeErrors(new String[][] {
            {"{\"modules\":[{\"module\":\":a\",\"jdkRelease\":\"line one\\nline two, and then enough words to be long\"}]}",
                "invalid v1 classpath dump %s: modules[0].jdkRelease: expected a whole number, found the string \"line one\\nline two, and then enough words...\""},
            {"{\"modules\":[{\"module\":\":a\",\"jdkRelease\":\"" + "😀".repeat(41) + "\"}]}",
                "invalid v1 classpath dump %s: modules[0].jdkRelease: expected a whole number, found the string \""
                        + "😀".repeat(40) + "...\""},
        });
    }

    @Test
    void anArtifactWithAGroupButNoNameHasNoCoordinate() throws Exception {
        Dump.Universe u = Dump.loadDump(write("group-only.json", """
                {"modules":[{"module":":a","artifacts":[{"group":"g","version":"1","file":"/r/group-only.jar"}]}]}"""));
        Dump.Artifact artifact = u.module(":a").artifacts.get(0);
        assertFalse(artifact.hasCoordinate());
        assertNull(artifact.version());
        assertTrue(u.versions.isEmpty());
        assertEquals(List.of("/r/group-only.jar"), u.scanTargets);
    }

    /** Coordinates and versions order by string value, the way Rust BTreeMap keys do. */
    @Test
    void versionMapsOrderByText() {
        Dump.VersionMap versions = new Dump.VersionMap();
        versions.add("b", "z", "1", "/bz");
        versions.add("a", "z", "10", "/az10");
        versions.add("a", "z", "9", "/az9");
        versions.add("a", "y", "1", "/ay");
        List<String> order = new ArrayList<>();
        versions.forEach((coord, byVersion) -> order.add(coord + "=" + byVersion.keySet()));
        assertEquals(List.of("a:y=[1]", "a:z=[10, 9]", "b:z=[1]"), order);
    }
}
