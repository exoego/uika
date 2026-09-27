package net.exoego.uika.plugin.core;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The v2 writer's root table and string escaping. Reading a written dump back is tested
/// next to the one reader, Gradle's rehydration, because only that build has a JSON parser.
final class DumpFormatTest {

    @Test
    void dependencyCacheRootsAreDerivedFromThePaths() {
        var gradleCache = "/home/dev/.gradle/caches/modules-2/files-2.1/";
        var mavenLocal = "/home/dev/.m2/repository/";
        var module = new Module(":app", List.of("/work/app/build/classes/java/main"), List.of(
                new Artifact("com.google.guava", "guava", "33.0.0-jre",
                        gradleCache + "com.google.guava/guava/33.0.0-jre/abc/guava-33.0.0-jre.jar"),
                new Artifact("org.slf4j", "slf4j-api", "2.0.13",
                        mavenLocal + "org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"),
                new Artifact(null, null, null, "/opt/vendor/legacy.jar")));

        var json = DumpFormat.writeV2(List.of(module), List.of("/work/app"), 17);

        assertTrue(json.contains("\"roots\":[\"/work/app/\",\"\",\"" + gradleCache + "\",\""
                + mavenLocal + "\"]"), json);
        assertTrue(json.contains(
                "\"root\":2,\"path\":\"com.google.guava/guava/33.0.0-jre/abc/guava-33.0.0-jre.jar\""),
                json);
        assertTrue(json.contains(
                "\"root\":3,\"path\":\"org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar\""), json);
        assertTrue(json.contains("{\"root\":1,\"path\":\"/opt/vendor/legacy.jar\"}"), json);
        assertTrue(json.contains("\"classesDirs\":[{\"root\":0,\"path\":\"build/classes/java/main\"}]"),
                json);
    }

    /// Plugins build a fresh Artifact per module, so equal fields must share one table entry.
    /// The old space-joined string key also merged artifacts whose fields only differed in
    /// where a space fell.
    @Test
    void artifactsAreSharedByValueAcrossModules() {
        var jar = "/opt/lib/a.jar";
        var app = new Module(":app", List.of(), List.of(
                new Artifact("g", "a b", "1", jar),
                new Artifact("g", "a", "b 1", jar)));
        var lib = new Module(":lib", List.of(), List.of(
                new Artifact("g", "a", "b 1", jar),
                new Artifact("g", "a", "b 1", jar, ":shared")));

        var json = DumpFormat.writeV2(List.of(app, lib), List.of(), null);

        assertTrue(json.contains("\"artifactRefs\":[0,1]}"), json);
        assertTrue(json.contains("\"artifactRefs\":[1,2]}"), json);
        assertTrue(json.contains("\"project\":\":shared\""), json);
    }

    @Test
    void quoteEscapesWhatJsonRequires() {
        assertEquals("\"C:\\\\Users\\\\dev\\\\lib.jar\"", DumpFormat.quote("C:\\Users\\dev\\lib.jar"));
        assertEquals("\"a\\\"b\\n\\r\\t\\u0001\"", DumpFormat.quote("a\"b\n\r\t" + (char) 1));
    }

    @Test
    void aDocumentWithoutModulesIsNotADump() {
        var boom = assertThrows(IllegalArgumentException.class, () -> DumpFormat.normalize(Map.of()));
        assertEquals("not a uika classpath dump", boom.getMessage());
    }
}
