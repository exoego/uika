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
            return v2 ? fromV2(object(value, "")) : fromV1(object(value, ""));
        } catch (Shape e) {
            throw new UikaException("invalid " + (v2 ? "v2" : "v1") + " classpath dump " + path + ": " + e.getMessage());
        }
    }

    /** The dump does not have the documented shape. */
    private static final class Shape extends Exception {
        private static final long serialVersionUID = 1L;

        /** @param at JSON path of the offending value, empty for the whole document */
        Shape(String at, String message) {
            super(at.isEmpty() ? message : at + ": " + message, null, false, false);
        }
    }

    private static Universe fromV1(Map<String, Object> dump) throws Shape {
        Universe universe = new Universe();
        universe.jdkRelease = release(dump, "");
        List<Object> modules = list(dump, "", "modules");
        for (int m = 0; m < modules.size(); m++) {
            String at = "modules[" + m + "]";
            Map<String, Object> module = object(modules.get(m), at);
            String name = string(module, at, "module");
            List<Artifact> artifacts = new ArrayList<>();
            List<Object> entries = optionalList(module, at, "artifacts");
            for (int i = 0; i < entries.size(); i++) {
                String entryAt = at + ".artifacts[" + i + "]";
                Map<String, Object> artifact = object(entries.get(i), entryAt);
                String file = string(artifact, entryAt, "file");
                universe.addTarget(file);
                artifacts.add(universe.artifactEntry(
                        optionalString(artifact, entryAt, "group"),
                        optionalString(artifact, entryAt, "name"),
                        optionalString(artifact, entryAt, "version"),
                        file,
                        optionalString(artifact, entryAt, "project")));
            }
            List<String> classesDirs = new ArrayList<>();
            List<Object> dirs = optionalList(module, at, "classesDirs");
            for (int i = 0; i < dirs.size(); i++) {
                String d = string(dirs.get(i), at + ".classesDirs[" + i + "]");
                classesDirs.add(d);
                universe.appRoots.add(d);
                universe.addTarget(d);
            }
            Integer release = release(module, at);
            universe.modules.add(new Module(name, classesDirs, artifacts, release != null ? release : universe.jdkRelease));
        }
        return universe;
    }

    /** v2: a deduplicated artifact table plus a root table for path prefixes (DumpFormat in jvm-plugin-core). */
    private static Universe fromV2(Map<String, Object> dump) throws Shape {
        List<String> roots = new ArrayList<>();
        List<Object> rootEntries = list(dump, "", "roots");
        for (int i = 0; i < rootEntries.size(); i++) {
            roots.add(string(rootEntries.get(i), "roots[" + i + "]"));
        }
        Universe universe = new Universe();
        universe.jdkRelease = release(dump, "");
        // The table is deduplicated, so first-seen order is table order.
        List<Artifact> table = new ArrayList<>();
        List<Object> entries = list(dump, "", "artifacts");
        for (int i = 0; i < entries.size(); i++) {
            String at = "artifacts[" + i + "]";
            Map<String, Object> artifact = object(entries.get(i), at);
            String file = rooted(roots, artifact, at);
            universe.addTarget(file);
            table.add(universe.artifactEntry(
                    optionalString(artifact, at, "group"),
                    optionalString(artifact, at, "name"),
                    optionalString(artifact, at, "version"),
                    file,
                    optionalString(artifact, at, "project")));
        }
        boolean unnamedModule = false;
        List<Object> modules = optionalList(dump, "", "modules");
        for (int m = 0; m < modules.size(); m++) {
            String at = "modules[" + m + "]";
            Map<String, Object> module = object(modules.get(m), at);
            List<String> classesDirs = new ArrayList<>();
            List<Object> dirs = optionalList(module, at, "classesDirs");
            for (int i = 0; i < dirs.size(); i++) {
                String dirAt = at + ".classesDirs[" + i + "]";
                String file = rooted(roots, object(dirs.get(i), dirAt), dirAt);
                universe.appRoots.add(file);
                universe.addTarget(file);
                classesDirs.add(file);
            }
            List<Artifact> artifacts = new ArrayList<>();
            List<Object> refs = optionalList(module, at, "artifactRefs");
            for (int i = 0; i < refs.size(); i++) {
                artifacts.add(table.get(index(refs.get(i), at + ".artifactRefs[" + i + "]", table.size(), "artifacts")));
            }
            // A dump without a module name cannot be paired by name, and positional pairing
            // would diff unrelated modules. The caller falls back to the merged universe.
            String name = optionalString(module, at, "module");
            if (name == null) {
                unnamedModule = true;
                continue;
            }
            Integer release = release(module, at);
            universe.modules.add(new Module(name, classesDirs, artifacts, release != null ? release : universe.jdkRelease));
        }
        if (unnamedModule) {
            universe.modules.clear();
        }
        return universe;
    }

    private static String rooted(List<String> roots, Map<String, Object> entry, String at) throws Shape {
        int root = index(required(entry, at, "root"), child(at, "root"), roots.size(), "roots");
        return roots.get(root) + string(entry, at, "path");
    }

    // ---- shape helpers ----
    // `at` is the JSON path of the value, or of the object that holds `field`.

    private static String child(String at, String field) {
        return at.isEmpty() ? field : at + "." + field;
    }

    private static Object required(Map<String, Object> object, String at, String field) throws Shape {
        if (!object.containsKey(field)) {
            throw new Shape(at, "missing field \"" + field + "\"");
        }
        return object.get(field);
    }

    private static String string(Map<String, Object> object, String at, String field) throws Shape {
        return string(required(object, at, field), child(at, field));
    }

    private static String optionalString(Map<String, Object> object, String at, String field) throws Shape {
        Object value = object.get(field);
        return value == null ? null : string(value, child(at, field));
    }

    private static List<Object> list(Map<String, Object> object, String at, String field) throws Shape {
        return list(required(object, at, field), child(at, field));
    }

    /** Absent reads as empty, but an explicit null is still not an array. */
    private static List<Object> optionalList(Map<String, Object> object, String at, String field) throws Shape {
        return object.containsKey(field) ? list(object.get(field), child(at, field)) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String at) throws Shape {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw mismatch(at, "an object", value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String at) throws Shape {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw mismatch(at, "an array", value);
    }

    private static String string(Object value, String at) throws Shape {
        if (value instanceof String s) {
            return s;
        }
        throw mismatch(at, "a string", value);
    }

    /** A position in {@code table}, which has {@code size} entries. */
    private static int index(Object value, String at, int size, String table) throws Shape {
        long n = wholeNumber(value, at);
        if (n < 0) {
            throw new Shape(at, "expected a whole number of 0 or more, found " + n);
        }
        if (n >= size) {
            String entries = size == 1 ? "1 entry" : size + " entries";
            throw new Shape(at, "index " + n + " is out of range because " + table + " has " + entries);
        }
        return (int) n;
    }

    private static Integer release(Map<String, Object> object, String at) throws Shape {
        Object value = object.get("jdkRelease");
        if (value == null) {
            return null;
        }
        String releaseAt = child(at, "jdkRelease");
        long n = wholeNumber(value, releaseAt);
        // Earlier releases accepted up to 2^32 - 1, so this still does.
        if (n < 0 || n > 0xffffffffL) {
            throw new Shape(releaseAt, "expected a Java release number such as 17, found " + n);
        }
        return (int) Math.min(n, Integer.MAX_VALUE);
    }

    private static long wholeNumber(Object value, String at) throws Shape {
        if (value instanceof Long n) {
            return n;
        }
        throw mismatch(at, "a whole number", value);
    }

    private static Shape mismatch(String at, String expected, Object found) {
        return new Shape(at, "expected " + expected + ", found " + describe(found));
    }

    private static final int SHOWN_CODE_POINTS = 40;

    private static String describe(Object value) {
        if (value instanceof String s) {
            boolean cut = s.codePointCount(0, s.length()) > SHOWN_CODE_POINTS;
            return "the string " + Json.quote(cut ? s.substring(0, s.offsetByCodePoints(0, SHOWN_CODE_POINTS)) + "..." : s);
        } else if (value instanceof List<?>) {
            return "an array";
        } else if (value instanceof Map<?, ?>) {
            return "an object";
        }
        return String.valueOf(value);
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
