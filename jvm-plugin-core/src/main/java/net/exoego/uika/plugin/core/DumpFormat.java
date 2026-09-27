package net.exoego.uika.plugin.core;

import net.exoego.uika.plugin.core.ClasspathDump.Artifact;
import net.exoego.uika.plugin.core.ClasspathDump.Module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

        var artifactIndex = new HashMap<Key, Integer>();
        var table = new ArrayList<Artifact>();
        var refs = new int[modules.size()][];
        for (var m = 0; m < modules.size(); m++) {
            var artifacts = modules.get(m).artifacts();
            var moduleRefs = new int[artifacts.size()];
            var i = 0;
            for (Artifact a : artifacts) {
                var index = artifactIndex.putIfAbsent(new Key(a), table.size());
                if (index == null) {
                    index = table.size();
                    table.add(a);
                }
                moduleRefs[i++] = index;
            }
            refs[m] = moduleRefs;
        }
        // Roots are derived while paths are matched, and the roots array precedes both
        // tables in the output, so every root is resolved before anything is written.
        var artifactRoots = new int[table.size()];
        for (var i = 0; i < table.size(); i++) {
            artifactRoots[i] = roots.indexOf(table.get(i).file());
        }
        var dirRoots = new int[modules.size()][];
        for (var m = 0; m < modules.size(); m++) {
            var dirs = modules.get(m).classesDirs();
            var moduleDirRoots = new int[dirs.size()];
            var i = 0;
            for (String dir : dirs) {
                moduleDirRoots[i++] = roots.indexOf(dir);
            }
            dirRoots[m] = moduleDirRoots;
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
            quote(json, built.get(i));
        }
        json.append("],\"artifacts\":[");
        for (var i = 0; i < table.size(); i++) {
            var a = table.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append('{');
            if (a.group() != null) {
                json.append("\"group\":");
                quote(json, a.group());
                json.append(",\"name\":");
                quote(json, a.name());
                json.append(",\"version\":");
                quote(json, a.version());
                json.append(',');
            }
            if (a.project() != null) {
                json.append("\"project\":");
                quote(json, a.project());
                json.append(',');
            }
            var root = artifactRoots[i];
            json.append("\"root\":").append(root).append(",\"path\":");
            quote(json, roots.suffixOf(a.file(), root));
            json.append('}');
        }
        json.append("],\"modules\":[");
        for (var m = 0; m < modules.size(); m++) {
            var module = modules.get(m);
            if (m > 0) {
                json.append(',');
            }
            json.append("{\"module\":");
            quote(json, module.path());
            if (module.jdkRelease() != null) {
                json.append(",\"jdkRelease\":").append(module.jdkRelease().intValue());
            }
            json.append(",\"classesDirs\":[");
            var i = 0;
            for (String dir : module.classesDirs()) {
                if (i > 0) {
                    json.append(',');
                }
                var root = dirRoots[m][i++];
                json.append("{\"root\":").append(root).append(",\"path\":");
                quote(json, roots.suffixOf(dir, root));
                json.append('}');
            }
            json.append("],\"artifactRefs\":[");
            var moduleRefs = refs[m];
            for (var r = 0; r < moduleRefs.length; r++) {
                if (r > 0) {
                    json.append(',');
                }
                json.append(moduleRefs[r]);
            }
            json.append("]}");
        }
        json.append("]}");
        return json.toString();
    }

    public static String quote(String s) {
        var sb = new StringBuilder(s.length() + 2);
        quote(sb, s);
        return sb.toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
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
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Artifact identity for deduplication: every field, compared by value. */
    private static final class Key {
        private final Artifact a;
        private final int hash;

        Key(Artifact a) {
            this.a = a;
            this.hash = Objects.hash(a.group(), a.name(), a.version(), a.file(), a.project());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(a.group(), k.a.group())
                    && Objects.equals(a.name(), k.a.name())
                    && Objects.equals(a.version(), k.a.version())
                    && Objects.equals(a.file(), k.a.file())
                    && Objects.equals(a.project(), k.a.project());
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class RootTable {
        private final List<String> roots = new ArrayList<>();

        RootTable(List<String> preferred) {
            for (String p : preferred) {
                roots.add(p.endsWith("/") ? p : p + "/");
            }
            roots.add("");
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
