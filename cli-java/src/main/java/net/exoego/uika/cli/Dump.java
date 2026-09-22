package net.exoego.uika.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Loads the resolved classpath JSON the build-tool plugins write, and diffs two of them.
 *
 * <p>Coordinates come from the build tool's resolution result rather than from path parsing.
 * Paths stay the strings the dump holds: they are interned as a class's source and printed
 * in reports exactly as written.
 */
final class Dump {
    private Dump() {}

    /** (group, name), ordered by string value. */
    record Coord(String group, String name) implements Comparable<Coord> {
        @Override
        public int compareTo(Coord other) {
            int c = Text.compareUtf8(group, other.group);
            return c != 0 ? c : Text.compareUtf8(name, other.name);
        }

        @Override
        public String toString() {
            return group + ":" + name;
        }
    }

    /**
     * (group, name) to version to file. When modules resolve differently, several versions
     * appear for one coordinate.
     */
    static final class VersionMap extends TreeMap<Coord, TreeMap<String, String>> {
        private static final long serialVersionUID = 1L;

        void add(String group, String name, String version, String file) {
            computeIfAbsent(new Coord(group, name), k -> new TreeMap<>(Text::compareUtf8)).put(version, file);
        }
    }

    /**
     * One classpath entry of a module.
     *
     * @param group null together with name and version for a project/file dependency
     * @param project producing module path when the build tool attributed a project
     *     dependency; lets the check substitute that module's classesDirs when the artifact
     *     has not been built
     */
    record Artifact(String group, String name, String version, String file, String project) {
        boolean hasCoordinate() {
            return group != null;
        }
    }

    /** One build module's resolved runtime classpath. */
    static final class Module {
        /** Build-tool module path (":app"). */
        final String name;
        /** Own build outputs: per-module scan targets and reachability roots. */
        final List<String> classesDirs;
        /** Resolved classpath entries in resolution order. */
        final List<Artifact> artifacts;
        /**
         * The API release THIS module compiles for, falling back to the dump's. Null only for
         * a dump written before the plugins recorded any release, which is what keeps an old
         * before-dump from claiming a JDK change against a fresh after-dump.
         */
        final Integer jdkRelease;

        Module(String name, List<String> classesDirs, List<Artifact> artifacts, Integer jdkRelease) {
            this.name = name;
            this.classesDirs = classesDirs;
            this.artifacts = artifacts;
            this.jdkRelease = jdkRelease;
        }

        /** Complete, project-attributed artifacts included; the diff excludes those. */
        VersionMap versions() {
            VersionMap versions = new VersionMap();
            for (Artifact artifact : artifacts) {
                if (artifact.hasCoordinate()) {
                    versions.add(artifact.group(), artifact.name(), artifact.version(), artifact.file());
                }
            }
            return versions;
        }
    }

    /** Everything present at runtime, aggregated from one dump. */
    static final class Universe {
        /** Artifact files + build outputs, deduplicated, in first-seen order. */
        final List<String> scanTargets = new ArrayList<>();
        /** Module classesDirs: the reachability roots. */
        final List<String> appRoots = new ArrayList<>();
        /** Includes project-attributed artifacts; the version DIFF excludes them. */
        final VersionMap versions = new VersionMap();
        /** Coordinates carried by project/reactor artifacts anywhere in this dump. */
        final TreeSet<Coord> projectCoords = new TreeSet<>();
        /**
         * Per-module classpaths in dump order. Empty when the dump carries no per-module data,
         * and also when any v2 module lacks a name: pairing unnamed modules positionally would
         * diff unrelated classpaths against each other.
         */
        final List<Module> modules = new ArrayList<>();
        /** The lowest release any module declares, else the JVM that wrote the dump; null for old dumps. */
        Integer jdkRelease;

        private final Set<Path> seenTargets = new HashSet<>();

        Module module(String name) {
            for (Module m : modules) {
                if (m.name.equals(name)) {
                    return m;
                }
            }
            return null;
        }

        private void addTarget(String file) {
            if (seenTargets.add(Path.of(file))) {
                scanTargets.add(file);
            }
        }

        /** Shared per-artifact bookkeeping for both formats, so v1 and v2 semantics cannot drift. */
        private Artifact artifactEntry(String group, String name, String version, String file, String project) {
            if (group != null && name != null && version != null) {
                versions.add(group, name, version, file);
                if (project != null) {
                    projectCoords.add(new Coord(group, name));
                }
                return new Artifact(group, name, version, file, project);
            }
            return new Artifact(null, null, null, file, project);
        }
    }

    static Universe loadDump(String path) {
        String text;
        try {
            text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new UikaException("cannot read classpath dump " + path, e);
        }
        Object value;
        try {
            value = Json.parse(text);
        } catch (Json.ParseException e) {
            throw new UikaException("invalid classpath dump " + path + ": " + e.getMessage());
        }
        boolean v2 = value instanceof Map<?, ?> map && Long.valueOf(2).equals(map.get("version"));
        try {
            return v2 ? fromV2(object(value, "struct DumpV2")) : fromV1(object(value, "struct ClasspathDump"));
        } catch (Shape e) {
            throw new UikaException("invalid " + (v2 ? "v2" : "v1") + " classpath dump " + path + ": " + e.getMessage());
        }
    }

    /** The dump does not have the documented shape. */
    private static final class Shape extends Exception {
        private static final long serialVersionUID = 1L;

        Shape(String message) {
            super(message, null, false, false);
        }
    }

    private static Universe fromV1(Map<String, Object> dump) throws Shape {
        Universe universe = new Universe();
        universe.jdkRelease = release(dump);
        for (Object item : list(required(dump, "modules"), "modules")) {
            Map<String, Object> module = object(item, "struct ModuleDump");
            String name = string(required(module, "module"), "module");
            List<Artifact> artifacts = new ArrayList<>();
            for (Object a : optionalList(module, "artifacts")) {
                Map<String, Object> artifact = object(a, "struct ArtifactDump");
                String file = path(required(artifact, "file"));
                universe.addTarget(file);
                artifacts.add(universe.artifactEntry(
                        optionalString(artifact, "group"),
                        optionalString(artifact, "name"),
                        optionalString(artifact, "version"),
                        file,
                        optionalString(artifact, "project")));
            }
            List<String> classesDirs = new ArrayList<>();
            for (Object dir : optionalList(module, "classesDirs")) {
                String d = path(dir);
                classesDirs.add(d);
                universe.appRoots.add(d);
                universe.addTarget(d);
            }
            Integer release = release(module);
            universe.modules.add(new Module(name, classesDirs, artifacts, release != null ? release : universe.jdkRelease));
        }
        return universe;
    }

    /** v2: a deduplicated artifact table plus a root table for path prefixes (DumpFormat in jvm-plugin-core). */
    private static Universe fromV2(Map<String, Object> dump) throws Shape {
        List<String> roots = new ArrayList<>();
        for (Object root : list(required(dump, "roots"), "roots")) {
            roots.add(string(root, "roots"));
        }
        Universe universe = new Universe();
        universe.jdkRelease = release(dump);
        // The table is deduplicated, so first-seen order is table order.
        List<Artifact> table = new ArrayList<>();
        for (Object a : list(required(dump, "artifacts"), "artifacts")) {
            Map<String, Object> artifact = object(a, "struct ArtifactV2");
            String file = rooted(roots, index(required(artifact, "root"), "root"), string(required(artifact, "path"), "path"));
            universe.addTarget(file);
            table.add(universe.artifactEntry(
                    optionalString(artifact, "group"),
                    optionalString(artifact, "name"),
                    optionalString(artifact, "version"),
                    file,
                    optionalString(artifact, "project")));
        }
        boolean unnamedModule = false;
        for (Object item : optionalList(dump, "modules")) {
            Map<String, Object> module = object(item, "struct ModuleV2");
            List<String> classesDirs = new ArrayList<>();
            for (Object d : optionalList(module, "classesDirs")) {
                Map<String, Object> dir = object(d, "struct RootedPath");
                String file = rooted(roots, index(required(dir, "root"), "root"), string(required(dir, "path"), "path"));
                universe.appRoots.add(file);
                universe.addTarget(file);
                classesDirs.add(file);
            }
            List<Artifact> artifacts = new ArrayList<>();
            for (Object ref : optionalList(module, "artifactRefs")) {
                int idx = index(ref, "artifactRefs");
                if (idx >= table.size()) {
                    throw new UikaException("artifact ref " + idx + " out of range");
                }
                artifacts.add(table.get(idx));
            }
            // A dump without a module name cannot be paired by name, and positional pairing
            // would diff unrelated modules. The caller falls back to the merged universe.
            String name = optionalString(module, "module");
            if (name == null) {
                unnamedModule = true;
                continue;
            }
            Integer release = release(module);
            universe.modules.add(new Module(name, classesDirs, artifacts, release != null ? release : universe.jdkRelease));
        }
        if (unnamedModule) {
            universe.modules.clear();
        }
        return universe;
    }

    /** An index out of range is not a shape error. Rust reports it bare, without the dump's path. */
    private static String rooted(List<String> roots, int root, String suffix) {
        if (root >= roots.size()) {
            throw new UikaException("root index " + root + " out of range");
        }
        return roots.get(root) + suffix;
    }

    // ---- shape helpers ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String expected) throws Shape {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new Shape("invalid type: " + describe(value) + ", expected " + expected);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String field) throws Shape {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new Shape("invalid type: " + describe(value) + ", expected a sequence");
    }

    private static List<Object> optionalList(Map<String, Object> map, String field) throws Shape {
        Object value = map.get(field);
        return value == null && !map.containsKey(field) ? List.of() : list(value, field);
    }

    private static Object required(Map<String, Object> map, String field) throws Shape {
        if (!map.containsKey(field)) {
            throw new Shape("missing field `" + field + "`");
        }
        return map.get(field);
    }

    private static String string(Object value, String field) throws Shape {
        if (value instanceof String s) {
            return s;
        }
        throw new Shape("invalid type: " + describe(value) + ", expected a string");
    }

    /** A v1 file or directory, which serde reads as a PathBuf and names as such. */
    private static String path(Object value) throws Shape {
        if (value instanceof String s) {
            return s;
        }
        throw new Shape("invalid type: " + describe(value) + ", expected path string");
    }

    private static String optionalString(Map<String, Object> map, String field) throws Shape {
        Object value = map.get(field);
        return value == null ? null : string(value, field);
    }

    private static int index(Object value, String field) throws Shape {
        if (value instanceof Long n && n >= 0 && n <= Integer.MAX_VALUE) {
            return n.intValue();
        }
        throw outOfShape(value, "usize");
    }

    private static Integer release(Map<String, Object> map) throws Shape {
        Object value = map.get("jdkRelease");
        if (value == null) {
            return null;
        }
        if (value instanceof Long n && n >= 0 && n <= 0xffffffffL) {
            return (int) Math.min(n, Integer.MAX_VALUE);
        }
        throw outOfShape(value, "u32");
    }

    /** serde calls an integer outside the target's range an invalid value, and anything else an invalid type. */
    private static Shape outOfShape(Object value, String expected) {
        String kind = value instanceof Long ? "invalid value" : "invalid type";
        return new Shape(kind + ": " + describe(value) + ", expected " + expected);
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String s) {
            return "string " + Json.quote(s);
        } else if (value instanceof Long n) {
            return "integer `" + n + "`";
        } else if (value instanceof Double d) {
            return "floating point `" + d + "`";
        } else if (value instanceof Boolean b) {
            return "boolean `" + b + "`";
        } else if (value instanceof List<?>) {
            return "sequence";
        }
        return "map";
    }

    // ---- diff ----

    enum ChangeKind {
        CHANGED("changed"),
        REMOVED("removed"),
        ADDED("added");

        final String json;

        ChangeKind(String json) {
            this.json = json;
        }
    }

    /** @param coordinate "group:name" */
    record DependencyChange(String coordinate, ChangeKind kind, List<String> before, List<String> after) {}

    /**
     * Dependency diff between two dumps.
     *
     * @param oldJars JARs of versions that exist only before (removed artifacts included); check's --old
     * @param newJars JARs of versions that exist only after; check's --new
     */
    record DependencyChanges(List<DependencyChange> changes, List<String> oldJars, List<String> newJars) {}

    /**
     * Coordinates the version diff must ignore: project-attributed on EITHER side. The union
     * keeps the diff symmetric when one dump comes from an older plugin that did not attribute
     * yet. Otherwise an unchanged reactor dependency reads as Removed, and its jar would be
     * dropped from the scan as a stale old version, fabricating "class removed" violations.
     */
    static TreeSet<Coord> projectCoordsUnion(Universe before, Universe after) {
        TreeSet<Coord> union = new TreeSet<>(before.projectCoords);
        union.addAll(after.projectCoords);
        return union;
    }

    static DependencyChanges diffDumps(Universe before, Universe after) {
        return diffVersionMaps(before.versions, after.versions, projectCoordsUnion(before, after));
    }

    static DependencyChanges diffVersionMaps(VersionMap before, VersionMap after, Set<Coord> exclude) {
        List<DependencyChange> changes = new ArrayList<>();
        List<String> oldJars = new ArrayList<>();
        List<String> newJars = new ArrayList<>();
        for (Map.Entry<Coord, TreeMap<String, String>> e : before.entrySet()) {
            Coord coord = e.getKey();
            if (exclude.contains(coord)) {
                continue;
            }
            TreeMap<String, String> beforeVersions = e.getValue();
            TreeMap<String, String> afterVersions = after.get(coord);
            Set<String> afterSet = afterVersions == null ? Set.of() : afterVersions.keySet();
            if (beforeVersions.keySet().equals(afterSet)) {
                continue;
            }
            for (Map.Entry<String, String> v : beforeVersions.entrySet()) {
                if (!afterSet.contains(v.getKey())) {
                    oldJars.add(v.getValue());
                }
            }
            if (afterVersions != null) {
                for (Map.Entry<String, String> v : afterVersions.entrySet()) {
                    if (!beforeVersions.containsKey(v.getKey())) {
                        newJars.add(v.getValue());
                    }
                }
            }
            changes.add(new DependencyChange(
                    coord.toString(),
                    afterVersions != null ? ChangeKind.CHANGED : ChangeKind.REMOVED,
                    new ArrayList<>(beforeVersions.keySet()),
                    afterVersions == null ? List.of() : new ArrayList<>(afterVersions.keySet())));
        }
        // Newly added artifacts naturally enter the scan targets, so they are not checked as
        // pairs, but they are still reported.
        for (Map.Entry<Coord, TreeMap<String, String>> e : after.entrySet()) {
            if (!exclude.contains(e.getKey()) && !before.containsKey(e.getKey())) {
                changes.add(new DependencyChange(e.getKey().toString(), ChangeKind.ADDED, List.of(), new ArrayList<>(e.getValue().keySet())));
            }
        }
        return new DependencyChanges(changes, oldJars, newJars);
    }

}
