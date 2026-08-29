package net.exoego.uika.plugin.core;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes v1/v2 uika classpath dumps on read and writes v2.
 *
 * <p>v2 is "artifact deduplication + root table for path prefixes":
 *
 * <pre>
 * {"version": 2,
 *  "jdkRelease": 17,
 *  "roots": ["/abs/prefix/", ...],
 *  "artifacts": [{"group":..,"name":..,"version":..,"root":0,"path":"suffix"}, ...],
 *  "modules": [{"module":":path","jdkRelease":17,
 *               "classesDirs":[{"root":1,"path":"suffix"}],"artifactRefs":[0,...]}, ...]}
 * </pre>
 *
 * <p>This collapses duplication that used to scale with module count into one entity table.
 * Entries without coordinates (project/file dependencies) omit group/name/version.
 *
 * <p>Both {@code jdkRelease} fields are additive and optional. A module carries one when it
 * declares an API target; the dump-level one ({@link #dumpRelease}) stands in for the modules
 * that do not.
 */
public final class DumpFormat {
    private DumpFormat() {}

    /** Normalize v1 / v2 / module fragments (one v1 module) into a common model. */
    @SuppressWarnings("unchecked")
    public static List<Module> normalize(Map<String, Object> doc) {
        var version = doc.get("version");
        if (version instanceof Number n && n.intValue() == 2) {
            return fromV2(doc);
        }
        var modules = (List<Map<String, Object>>) doc.get("modules");
        if (modules == null) {
            throw new IllegalArgumentException("not a uika classpath dump");
        }
        var result = new ArrayList<Module>();
        for (Map<String, Object> module : modules) {
            result.add(fromV1Module(module));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Module fromV1Module(Map<String, Object> module) {
        var classesDirs =
                new ArrayList<String>((List<String>) module.getOrDefault("classesDirs", List.of()));
        var artifacts = new ArrayList<Artifact>();
        for (Map<String, Object> a :
                (List<Map<String, Object>>) module.getOrDefault("artifacts", List.of())) {
            artifacts.add(new Artifact(
                    (String) a.get("group"),
                    (String) a.get("name"),
                    (String) a.get("version"),
                    (String) a.get("file"),
                    (String) a.get("project")));
        }
        return new Module((String) module.get("module"), classesDirs, artifacts,
                releaseOf(module));
    }

    /** The {@code jdkRelease} of a dump or of one module object, null when it carries none. */
    private static Integer releaseOf(Map<String, Object> object) {
        return object.get("jdkRelease") instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Module> fromV2(Map<String, Object> doc) {
        var roots = (List<String>) doc.get("roots");
        var artifacts = new ArrayList<Artifact>();
        for (Map<String, Object> a : (List<Map<String, Object>>) doc.get("artifacts")) {
            artifacts.add(new Artifact(
                    (String) a.get("group"),
                    (String) a.get("name"),
                    (String) a.get("version"),
                    roots.get(((Number) a.get("root")).intValue()) + a.get("path"),
                    (String) a.get("project")));
        }
        var result = new ArrayList<Module>();
        for (Map<String, Object> m : (List<Map<String, Object>>) doc.get("modules")) {
            var classesDirs = new ArrayList<String>();
            for (Map<String, Object> dir :
                    (List<Map<String, Object>>) m.getOrDefault("classesDirs", List.of())) {
                classesDirs.add(roots.get(((Number) dir.get("root")).intValue()) + dir.get("path"));
            }
            var refs = new ArrayList<Artifact>();
            for (Object idx : (List<Object>) m.getOrDefault("artifactRefs", List.of())) {
                refs.add(artifacts.get(((Number) idx).intValue()));
            }
            result.add(new Module((String) m.get("module"), classesDirs, refs, releaseOf(m)));
        }
        return result;
    }

    /**
     * The dump-level {@code jdkRelease}: the API release the checked application runs on,
     * the LOWEST any module declares, else {@link #buildJvmRelease()}.
     *
     * <p>It is what upgrade-check compares between two dumps for modules that name no
     * release of their own, and what the merged (non per-module) mode compares outright, so
     * it has to describe the application rather than whoever wrote the file. The lowest for
     * the same reason {@code --jdk-release} takes the lowest: one value stands in for every
     * module that declared nothing, and under-claiming only costs Unknowns while
     * over-claiming loses findings silently.
     *
     * <p>Falling back to the build JVM is not a compromise here. A module that declares no
     * target compiles against whatever JDK runs the build, so for that module the build JVM
     * IS the release the application runs on, and a build-image bump genuinely moves it.
     */
    public static int dumpRelease(List<Module> modules) {
        Integer lowest = null;
        for (Module module : modules) {
            var release = module.jdkRelease();
            if (release != null) {
                lowest = lowest == null ? release : Math.min(lowest, release);
            }
        }
        return lowest == null ? buildJvmRelease() : lowest;
    }

    /**
     * The feature version of the JVM writing this dump. Only the fallback for a module that
     * declares no target of its own; see {@link #dumpRelease}.
     */
    public static int buildJvmRelease() {
        return Runtime.version().feature();
    }

    /**
     * The {@code jdkRelease} a dump was written with, or null when it predates the field.
     * Rehydration must carry the original value forward rather than stamping its own JVM,
     * or a before dump rehydrated elsewhere would claim the rehydrating JVM's release.
     */
    public static Integer jdkReleaseOf(Map<String, Object> doc) {
        return releaseOf(doc);
    }

    /** Write as v2. roots are built dynamically from known prefixes plus generic markers. */
    public static String writeV2(List<Module> modules, List<String> preferredRoots, Integer jdkRelease) {
        var roots = new RootTable(preferredRoots);

        var artifactIndex = new LinkedHashMap<String, Integer>();
        var table = new ArrayList<Artifact>();
        for (Module module : modules) {
            for (Artifact a : module.artifacts()) {
                if (artifactIndex.putIfAbsent(keyOf(a), table.size()) == null) {
                    table.add(a);
                }
            }
        }

        var artifactsJson = new StringBuilder();
        for (var i = 0; i < table.size(); i++) {
            var a = table.get(i);
            if (i > 0) {
                artifactsJson.append(',');
            }
            artifactsJson.append('{');
            if (a.group() != null) {
                artifactsJson.append("\"group\":").append(quote(a.group()))
                        .append(",\"name\":").append(quote(a.name()))
                        .append(",\"version\":").append(quote(a.version()))
                        .append(',');
            }
            if (a.project() != null) {
                artifactsJson.append("\"project\":").append(quote(a.project())).append(',');
            }
            var root = roots.indexOf(a.file());
            artifactsJson.append("\"root\":").append(root)
                    .append(",\"path\":").append(quote(roots.suffixOf(a.file(), root)))
                    .append('}');
        }

        var modulesJson = new StringBuilder();
        var firstModule = true;
        for (Module module : modules) {
            if (!firstModule) {
                modulesJson.append(',');
            }
            firstModule = false;
            modulesJson.append("{\"module\":").append(quote(module.path()));
            if (module.jdkRelease() != null) {
                modulesJson.append(",\"jdkRelease\":").append(module.jdkRelease().intValue());
            }
            modulesJson.append(",\"classesDirs\":[");
            var first = true;
            for (String dir : module.classesDirs()) {
                if (!first) {
                    modulesJson.append(',');
                }
                first = false;
                var root = roots.indexOf(dir);
                modulesJson.append("{\"root\":").append(root)
                        .append(",\"path\":").append(quote(roots.suffixOf(dir, root)))
                        .append('}');
            }
            modulesJson.append("],\"artifactRefs\":[");
            first = true;
            for (Artifact a : module.artifacts()) {
                if (!first) {
                    modulesJson.append(',');
                }
                first = false;
                modulesJson.append(artifactIndex.get(keyOf(a)));
            }
            modulesJson.append("]}");
        }

        var json = new StringBuilder();
        json.append("{\"version\":2");
        if (jdkRelease != null) {
            json.append(",\"jdkRelease\":").append(jdkRelease.intValue());
        }
        json.append(",\"roots\":[");
        List<String> built = roots.all();
        for (var i = 0; i < built.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(built.get(i)));
        }
        json.append("],\"artifacts\":[").append(artifactsJson);
        json.append("],\"modules\":[").append(modulesJson);
        json.append("]}");
        return json.toString();
    }

    public static String quote(String s) {
        var sb = new StringBuilder(s.length() + 2).append('"');
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String keyOf(Artifact a) {
        return a.group() + " " + a.name() + " " + a.version() + " " + a.file() + " " + a.project();
    }

    private static final class RootTable {
        private final List<String> roots = new ArrayList<>();

        RootTable(List<String> preferred) {
            for (String p : preferred) {
                if (!p.endsWith("/")) {
                    p = p + "/";
                }
                if (!roots.contains(p)) {
                    roots.add(p);
                }
            }
            if (!roots.contains("")) {
                roots.add("");
            }
        }

        int indexOf(String path) {
            var best = roots.indexOf("");
            var bestLen = 0;
            for (var i = 0; i < roots.size(); i++) {
                var root = roots.get(i);
                if (!root.isEmpty() && path.startsWith(root) && root.length() > bestLen) {
                    best = i;
                    bestLen = root.length();
                }
            }
            if (bestLen > 0) {
                return best;
            }
            String derived = derive(path);
            if (derived != null) {
                roots.add(derived);
                return roots.size() - 1;
            }
            return best;
        }

        String suffixOf(String path, int root) {
            return path.substring(roots.get(root).length());
        }

        List<String> all() {
            return roots;
        }

        private static String derive(String path) {
            for (String marker : new String[] {"/modules-2/files-2.1/", "/.m2/repository/"}) {
                var i = path.indexOf(marker);
                if (i >= 0) {
                    return path.substring(0, i + marker.length());
                }
            }
            return null;
        }
    }
}
