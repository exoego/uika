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
 *  "roots": ["/abs/prefix/", ...],
 *  "artifacts": [{"group":..,"name":..,"version":..,"root":0,"path":"suffix"}, ...],
 *  "modules": [{"module":":path","classesDirs":[{"root":1,"path":"suffix"}],"artifactRefs":[0,...]}, ...]}
 * </pre>
 *
 * <p>This collapses duplication that used to scale with module count into one entity table.
 * Entries without coordinates (project/file dependencies) omit group/name/version.
 */
public final class DumpFormat {
    private DumpFormat() {}

    /** Normalize v1 / v2 / module fragments (one v1 module) into a common model. */
    @SuppressWarnings("unchecked")
    public static List<Module> normalize(Map<String, Object> doc) {
        Object version = doc.get("version");
        if (version instanceof Number n && n.intValue() == 2) {
            return fromV2(doc);
        }
        List<Map<String, Object>> modules = (List<Map<String, Object>>) doc.get("modules");
        if (modules == null) {
            throw new IllegalArgumentException("not a uika classpath dump");
        }
        List<Module> result = new ArrayList<>();
        for (Map<String, Object> module : modules) {
            result.add(fromV1Module(module));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Module fromV1Module(Map<String, Object> module) {
        List<String> classesDirs =
                new ArrayList<>((List<String>) module.getOrDefault("classesDirs", List.of()));
        List<Artifact> artifacts = new ArrayList<>();
        for (Map<String, Object> a :
                (List<Map<String, Object>>) module.getOrDefault("artifacts", List.of())) {
            artifacts.add(new Artifact(
                    (String) a.get("group"),
                    (String) a.get("name"),
                    (String) a.get("version"),
                    (String) a.get("file")));
        }
        return new Module((String) module.get("module"), classesDirs, artifacts);
    }

    @SuppressWarnings("unchecked")
    private static List<Module> fromV2(Map<String, Object> doc) {
        List<String> roots = (List<String>) doc.get("roots");
        List<Artifact> artifacts = new ArrayList<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) doc.get("artifacts")) {
            artifacts.add(new Artifact(
                    (String) a.get("group"),
                    (String) a.get("name"),
                    (String) a.get("version"),
                    roots.get(((Number) a.get("root")).intValue()) + a.get("path")));
        }
        List<Module> result = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) doc.get("modules")) {
            List<String> classesDirs = new ArrayList<>();
            for (Map<String, Object> dir :
                    (List<Map<String, Object>>) m.getOrDefault("classesDirs", List.of())) {
                classesDirs.add(roots.get(((Number) dir.get("root")).intValue()) + dir.get("path"));
            }
            List<Artifact> refs = new ArrayList<>();
            for (Object idx : (List<Object>) m.getOrDefault("artifactRefs", List.of())) {
                refs.add(artifacts.get(((Number) idx).intValue()));
            }
            result.add(new Module((String) m.get("module"), classesDirs, refs));
        }
        return result;
    }

    /** Write as v2. roots are built dynamically from known prefixes plus generic markers. */
    public static String writeV2(List<Module> modules, List<String> preferredRoots) {
        RootTable roots = new RootTable(preferredRoots);

        Map<String, Integer> artifactIndex = new LinkedHashMap<>();
        List<Artifact> table = new ArrayList<>();
        for (Module module : modules) {
            for (Artifact a : module.artifacts()) {
                if (artifactIndex.putIfAbsent(keyOf(a), table.size()) == null) {
                    table.add(a);
                }
            }
        }

        StringBuilder artifactsJson = new StringBuilder();
        for (int i = 0; i < table.size(); i++) {
            Artifact a = table.get(i);
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
            int root = roots.indexOf(a.file());
            artifactsJson.append("\"root\":").append(root)
                    .append(",\"path\":").append(quote(roots.suffixOf(a.file(), root)))
                    .append('}');
        }

        StringBuilder modulesJson = new StringBuilder();
        boolean firstModule = true;
        for (Module module : modules) {
            if (!firstModule) {
                modulesJson.append(',');
            }
            firstModule = false;
            modulesJson.append("{\"module\":").append(quote(module.path()));
            modulesJson.append(",\"classesDirs\":[");
            boolean first = true;
            for (String dir : module.classesDirs()) {
                if (!first) {
                    modulesJson.append(',');
                }
                first = false;
                int root = roots.indexOf(dir);
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

        StringBuilder json = new StringBuilder();
        json.append("{\"version\":2,\"roots\":[");
        List<String> built = roots.all();
        for (int i = 0; i < built.size(); i++) {
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
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
        return a.group() + " " + a.name() + " " + a.version() + " " + a.file();
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
            int best = roots.indexOf("");
            int bestLen = 0;
            for (int i = 0; i < roots.size(); i++) {
                String root = roots.get(i);
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
                int i = path.indexOf(marker);
                if (i >= 0) {
                    return path.substring(0, i + marker.length());
                }
            }
            return null;
        }
    }
}
