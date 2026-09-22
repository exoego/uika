package net.exoego.uika.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Actionable upgrade-check suggestions.
 *
 * <p>A violation says "class X removed" but not which dependency to touch. Each violation is
 * attributed to the two artifacts involved, the coordinate whose class holds the broken
 * reference (referencedBy) and the coordinate whose version bump removed the symbol
 * (removedBy), and gets a concrete fix. This needs coordinates, so it only applies to
 * upgrade-check (dumps carry them). A plain {@code check} classpath has bare file paths and
 * gets nothing.
 */
final class Suggest {
    private Suggest() {}

    /**
     * Attaches a Suggestion to each violation whose removed owner maps to a changed dependency.
     * Left null when the owner cannot be attributed (it came from an unchanged artifact).
     */
    static void annotate(
            List<Violation> violations, Dump.Universe before, Dump.Universe after, List<Dump.DependencyChange> changes) {
        new Annotator(before, after).annotate(violations, changes);
    }

    /**
     * Reusable annotation state for callers that annotate several violation sets against the
     * same dump pair (the per-module upgrade-check annotates once per run). The file to
     * coordinate map and each before-side jar's class-name listing depend only on the
     * universes, so they are built or read once instead of once per run.
     */
    static final class Annotator {
        private final Dump.Universe before;
        private final Map<String, String> fileCoord;
        private final Map<String, int[]> classNamesByFile = new HashMap<>();
        /**
         * Referencing JAR path to the "group:name" that JAR's POM declares optional (empty when
         * there is no readable POM). Keyed by the jar alone, so the directory listing, the read
         * and the scan happen once however many removed coordinates that jar references, and
         * once however many per-module runs it appears on.
         */
        private final Map<String, Set<String>> optionalDeps = new HashMap<>();

        Annotator(Dump.Universe before, Dump.Universe after) {
            this.before = before;
            this.fileCoord = fileCoordinates(before, after);
        }

        /** {@code changes} may be the universe-wide list or one module's own. */
        void annotate(List<Violation> violations, List<Dump.DependencyChange> changes) {
            if (violations.isEmpty()) {
                return;
            }
            SymMap ownerChange = ownerChanges(changes);

            for (Violation v : violations) {
                int ci = ownerChange.get(v.reference.owner());
                if (ci == SymMap.ABSENT) {
                    continue;
                }
                Dump.DependencyChange change = changes.get(ci);
                String source = Intern.str(v.source);
                String referencedBy = fileCoord.get(source);
                // An SPI violation's source is the upgraded library's own jar. When the service
                // interface is that same coordinate, the advice below would tell the user to
                // align the coordinate with itself. No annotation beats a self-referential one.
                // referencedBy is "g:n:v", change.coordinate "g:n".
                if ((v.reason == Reason.SERVICE_PROVIDER_REMOVED || v.reason == Reason.SERVICE_PROVIDER_NOT_INSTANTIABLE)
                        && referencedBy != null) {
                    if (referencedBy.substring(0, referencedBy.lastIndexOf(':')).equals(change.coordinate())) {
                        continue;
                    }
                }
                // Only asked for a coordinate the upgrade dropped entirely. That is the one
                // advice branch whose claim ("still needs it") an optional declaration
                // contradicts.
                boolean ownerOptional =
                        change.after().isEmpty() && declaresOptional(source, referencedBy, change.coordinate());
                v.suggestion = build(change, referencedBy, ownerOptional);
            }
        }

        /** Whether the referencing artifact's own POM declares {@code owner} optional. */
        private boolean declaresOptional(String source, String referencedBy, String owner) {
            if (referencedBy == null) {
                return false;
            }
            Set<String> declared = optionalDeps.get(source);
            if (declared == null) {
                declared = readOptionalDeps(source, referencedBy);
                if (declared == null) {
                    declared = Set.of();
                }
                optionalDeps.put(source, declared);
            }
            return declared.contains(owner);
        }

        /**
         * Owner class to index into {@code changes}, by reading the before-side JARs of each
         * changed coordinate (removed classes live there, and so do classes losing only a
         * member). The first change shipping a class keeps it.
         */
        private SymMap ownerChanges(List<Dump.DependencyChange> changes) {
            SymMap map = new SymMap();
            for (int i = 0; i < changes.size(); i++) {
                Dump.DependencyChange change = changes.get(i);
                if (change.kind() == Dump.ChangeKind.ADDED) {
                    continue;
                }
                String coordinate = change.coordinate();
                int colon = coordinate.indexOf(':');
                TreeMap<String, String> versions = before.versions.get(
                        new Dump.Coord(coordinate.substring(0, colon), coordinate.substring(colon + 1)));
                if (versions == null) {
                    continue;
                }
                for (String file : versions.values()) {
                    for (int owner : classNamesByFile.computeIfAbsent(file, Suggest::classNames)) {
                        if (!map.containsKey(owner)) {
                            map.put(owner, i);
                        }
                    }
                }
            }
            return map;
        }
    }

    /**
     * Class-origin JAR path to "group:name:version". Both sides are indexed so a referencing
     * artifact is found whether it changed or not.
     */
    private static Map<String, String> fileCoordinates(Dump.Universe before, Dump.Universe after) {
        Map<String, String> map = new HashMap<>();
        for (Dump.Universe universe : List.of(before, after)) {
            for (Map.Entry<Dump.Coord, TreeMap<String, String>> e : universe.versions.entrySet()) {
                Dump.Coord coord = e.getKey();
                for (Map.Entry<String, String> version : e.getValue().entrySet()) {
                    map.putIfAbsent(version.getValue(), coord.group() + ":" + coord.name() + ":" + version.getKey());
                }
            }
        }
        return map;
    }

    /**
     * Interned internal names of the classes in a JAR/dir. Names only (no inflate). Empty on
     * read failure, since suggestions are best effort and never block the report.
     */
    private static int[] classNames(String path) {
        List<String> names;
        try {
            names = Input.classEntryNames(path);
        } catch (RuntimeException e) {
            return new int[0];
        }
        int[] syms = new int[names.size()];
        for (int i = 0; i < syms.length; i++) {
            syms[i] = Intern.intern(names.get(i));
        }
        return syms;
    }

    /**
     * Reads {@code referencer}'s POM, if it sits beside the JAR the broken class came from, and
     * reports the "group:name" it declares optional. Null when anything is missing. Decoded
     * lossily because a POM may declare a non-UTF-8 encoding, and refusing to read it would
     * silently produce the wording this whole path exists to avoid.
     */
    private static Set<String> readOptionalDeps(String source, String referencer) {
        // The limit keeps trailing empty segments, like Rust's split.
        String[] parts = referencer.split(":", -1);
        String path = Pom.locate(source, parts[1], parts[2]);
        if (path == null) {
            return null;
        }
        try {
            return Pom.optionalDependencies(new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Suggestion build(Dump.DependencyChange change, String referencedBy, boolean ownerOptional) {
        String owner = change.coordinate();
        String referencer = referencedBy != null ? referencedBy : "the referencing artifact";

        String advice;
        if (change.after().isEmpty()) {
            // Coordinate dropped entirely by the upgrade, so pinning a version back is meaningless.
            if (ownerOptional) {
                // An optional dependency was never required transitively, so "still needs it"
                // is false and "upgrade to a release that no longer requires it" points at a
                // release that will not exist. Optional integrations are permanent design
                // choices. https://github.com/exoego/uika/issues/96
                //
                // The claim stops at what the POM states. Saying it "arrived through some other
                // dependency" would assert a graph the dump does not model (no requested-by
                // edges), and would be wrong outright when the build declared the coordinate
                // directly and this upgrade dropped that declaration. That is the same
                // assert-an-unverified-cause mistake #96 exists to remove, pointed the other way.
                advice = owner + " was removed by the upgrade and " + referencer + " declares it optional, so "
                        + referencer + " never required it transitively -- it was on the classpath for some "
                        + "other reason that no longer holds. These references break only where "
                        + referencer + "'s optional feature is used; restore " + owner + " to keep that feature "
                        + "working";
            } else {
                advice = owner + " was removed by the upgrade, but " + referencer + " still needs it; "
                        + "upgrade " + referencer + " to a release that no longer requires " + owner + ", or restore "
                        + owner;
            }
        } else {
            // Advise on the versions that actually moved (before minus after, after minus
            // before), not the full resolved lists, so a multi-version coordinate does not read
            // as "pin to 1.62,1.63".
            List<String> gone = diffVersions(change.before(), change.after());
            List<String> added = diffVersions(change.after(), change.before());
            List<String> pin = gone.isEmpty() ? change.before() : gone;
            List<String> target = added.isEmpty() ? change.after() : added;
            String base = "upgrade " + referencer + " to a release built against " + owner + " "
                    + joinVersions(target) + ", or pin " + owner + " to " + joinVersions(pin);
            // Same-group skew (otel core vs its incubator). The real fix is aligning the whole
            // group, so lead with that.
            if (referencedBy != null && groupOf(referencedBy).equals(groupOf(owner))) {
                advice = "align all " + groupOf(owner)
                        + " artifacts to one version (e.g. via the matching BOM); otherwise " + base;
            } else {
                advice = base;
            }
        }

        return new Suggestion(
                referencedBy, owner, joinVersions(change.before()), joinVersions(change.after()), advice);
    }

    /** Versions present in {@code a} but not {@code b}, preserving {@code a}'s order. */
    private static List<String> diffVersions(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        for (String version : a) {
            if (!b.contains(version)) {
                out.add(version);
            }
        }
        return out;
    }

    private static String joinVersions(List<String> versions) {
        return versions.isEmpty() ? "-" : String.join(",", versions);
    }

    static String groupOf(String coordinate) {
        int colon = coordinate.indexOf(':');
        return colon < 0 ? coordinate : coordinate.substring(0, colon);
    }
}
