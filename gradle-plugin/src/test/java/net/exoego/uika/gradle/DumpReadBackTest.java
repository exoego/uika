package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Rehydration reads the v2 dumps `uikaDumpClasspath` writes, while its integration test
/// feeds it v1 dumps. This reads a written dump back through rehydration's own parse, so
/// the writer and the reader cannot drift apart unnoticed.
final class DumpReadBackTest {

    @Test
    void aWrittenDumpReadsBackUnchanged() {
        var cache = "/home/dev/.gradle/caches/modules-2/files-2.1/";
        var guava = new Artifact("com.google.guava", "guava", "33.0.0-jre",
                cache + "com.google.guava/guava/33.0.0-jre/abc/guava-33.0.0-jre.jar");
        var lib = new Artifact("com.example", "lib", "1.0.0",
                "/work/app/lib/build/libs/lib-1.0.0.jar", ":lib");
        var vendored = new Artifact(null, null, null, "C:\\Users\\dev\\libs\\legacy.jar");
        var modules = List.of(
                new Module(":app",
                        List.of("/work/app/app/build/classes/java/main",
                                "/work/app/app/build/classes/kotlin/main"),
                        List.of(guava, lib, vendored), 17),
                new Module(":lib", List.of("/work/app/lib/build/classes/java/main"), List.of(guava)));

        var json = DumpFormat.writeV2(modules, List.of("/work/app"), 17);

        assertEquals(render(modules), render(ResolveClasspathTask.parseModules(json)), json);
        assertEquals(17, ResolveClasspathTask.parseJdkRelease(json));
    }

    private static String render(List<Module> modules) {
        var text = new StringBuilder();
        for (Module module : modules) {
            text.append(module.path()).append(" release=").append(module.jdkRelease())
                    .append(' ').append(module.classesDirs()).append('\n');
            for (Artifact a : module.artifacts()) {
                text.append("  ").append(a.group()).append(':').append(a.name()).append(':')
                        .append(a.version()).append(' ').append(a.file())
                        .append(" project=").append(a.project()).append('\n');
            }
        }
        return text.toString();
    }
}
