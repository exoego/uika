package net.exoego.uika.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/** The commands: what each does with its parsed arguments, and the exit code it ends with. */
final class Commands {
    private Commands() {}

    /** Exit codes: 0 clean, 1 violations found per --fail-on. A thrown {@link UikaException} is 2. */
    static int run(Cli.Command command) {
        if (command instanceof Cli.Diff diff) {
            return cmdDiff(diff);
        } else if (command instanceof Cli.Check check) {
            return cmdCheck(check);
        } else if (command instanceof Cli.UpgradeCheck upgrade) {
            return cmdUpgradeCheck(upgrade);
        } else if (command instanceof Cli.DumpApi dump) {
            return cmdDump(dump.path());
        }
        Out.out.print(((Cli.Print) command).text());
        return 0;
    }

    /** One index from several JARs; duplicate class names are first-wins in argument order. */
    static ApiIndex buildIndex(List<String> paths) {
        List<String> warnings = new ArrayList<>();
        ApiIndex index = ApiIndex.fromPaths(paths, warnings);
        Out.warnAll(warnings);
        return index;
    }

    private static int cmdDiff(Cli.Diff args) {
        ApiIndex oldIndex = buildIndex(List.of(args.oldJar()));
        ApiIndex newIndex = buildIndex(List.of(args.newJar()));
        List<BreakingChange> changes = Diff.diff(oldIndex, newIndex);
        if (args.json()) {
            Out.out.println(Report.diffJson(changes));
        } else {
            Out.out.print(Report.diffText(changes));
        }
        return 0;
    }

    private static int cmdCheck(Cli.Check args) {
        List<String> targets = new ArrayList<>(args.classpath());
        List<String> appRoots = new ArrayList<>(args.app());
        targets.addAll(args.app());
        for (String dump : args.classpathFile()) {
            Dump.Universe universe = Dump.loadDump(dump);
            appRoots.addAll(universe.appRoots);
            targets.addAll(universe.scanTargets);
        }
        List<Exclude.Rule> excludeRules = Exclude.load(args.excludeFile());
        Evidence.LoadEvidence evidence = loadEvidence(args.classLoadLog(), args.draftExcludeFile(), args.excludeFile());
        int[] jdkPair = args.jdkReleaseOld() == null ? null : new int[] {args.jdkReleaseOld(), args.jdkReleaseNew()};
        Check.Report result;
        try (JdkLayer jdk = new JdkLayer(args.jdkRelease())) {
            Verdicts.Writer verdicts = args.verdictsJson() == null ? null : Verdicts.Writer.create(args.verdictsJson());
            result = finishVerdicts(verdicts, () -> {
                if (jdkPair != null) {
                    // The JDK upgrade IS the pair, so there is no jar to exclude as stale and
                    // none to sweep for invocation evidence.
                    ApiIndex[] pair = jdkReleasePair(jdkPair);
                    return runCheckWithIndexes(pair[0], pair[1], List.of(), List.of(), targets, appRoots, excludeRules, jdk.indexer, verdicts);
                }
                return runCheck(args.oldJars(), args.newJars(), targets, appRoots, excludeRules, jdk.indexer, verdicts);
            });
        }
        applyEvidenceAndDraft(result.violations, result.appRootsMatched, evidence, args.draftExcludeFile());
        if (args.json()) {
            Out.out.println(Report.checkJson(result));
        } else {
            // scanTargets, not targets.size(): the header must agree with what was scanned
            // after missing-path skips, stale-old-version exclusion, and dedup.
            if (jdkPair != null) {
                Out.out.print(Report.checkHeaderJdk(jdkPair[0], jdkPair[1], result.scanTargets));
            } else {
                Out.out.print(Report.checkHeader(args.oldJars(), args.newJars(), result.scanTargets));
            }
            Out.out.print(Report.checkText(result));
        }
        return exitCode(result, args.failOn());
    }

    /** Owns the optional ct.sym indexer for the length of a command. */
    private static final class JdkLayer implements AutoCloseable {
        final Jdk.Indexer indexer;

        JdkLayer(Integer release) {
            indexer = Jdk.indexerFor(release);
        }

        @Override
        public void close() {
            if (indexer != null) {
                indexer.close();
            }
        }
    }

    /**
     * Both sides of a JDK-pair check. Warns when the old side comes from jmods while the new
     * side comes from ct.sym: jmods also holds unexported internals, so as the older side it
     * would report every one of them as removed.
     */
    static ApiIndex[] jdkReleasePair(int[] pair) {
        int oldRelease = pair[0];
        int newRelease = pair[1];
        if (Jdk.isInstalledRelease(oldRelease) && !Jdk.isInstalledRelease(newRelease)) {
            Out.warn("--jdk-release-old " + oldRelease + " is this JDK's own release, read from jmods, "
                    + "which also holds unexported internals; against a ct.sym new side those look removed");
        }
        List<String> warnings = new ArrayList<>();
        ApiIndex oldIndex = Jdk.releaseIndex(oldRelease, warnings);
        ApiIndex newIndex = Jdk.releaseIndex(newRelease, warnings);
        Out.warnAll(warnings);
        return new ApiIndex[] {oldIndex, newIndex};
    }

    /**
     * Closes the verdict stream and surfaces a stream failure. The stream is an explicitly
     * requested output, and a silently truncated one would let an answer-check pass on a
     * prefix of the verdicts, so a failure fails the command. When the check itself already
     * failed, its error stays primary and the stream failure degrades to a warning.
     */
    static <T> T finishVerdicts(Verdicts.Writer writer, Supplier<T> body) {
        T result;
        try {
            result = body.get();
        } catch (RuntimeException e) {
            String streamError = writer == null ? null : writer.finish();
            if (streamError != null) {
                Out.warn(streamError);
            }
            throw e;
        }
        String streamError = writer == null ? null : writer.finish();
        if (streamError != null) {
            throw new UikaException(streamError);
        }
        return result;
    }

    /**
     * Loads --class-load-log and notes the ingest, so a user can see the artifact was read.
     * A requested draft file is truncated to a placeholder FIRST, mirroring --verdicts-json:
     * a run that errors later can never leave a stale draft from an earlier run behind.
     */
    static Evidence.LoadEvidence loadEvidence(List<String> paths, String draft, List<String> excludeFile) {
        if (draft != null) {
            // Before the placeholder truncates it. "Regenerate it in place" is the natural
            // thing to try, and it drops every rule that was still suppressing something.
            String clash = aliasesExcludeFile(draft, excludeFile);
            if (clash != null) {
                throw new UikaException("--draft-exclude-file " + clash + " is also an --exclude-file; drafting would "
                        + "overwrite it with only the drafted rules. Draft to a new path and merge.");
            }
            Evidence.createDraftPlaceholder(draft);
        }
        if (paths.isEmpty()) {
            return null;
        }
        Evidence.LoadEvidence evidence = Evidence.load(paths);
        Out.note("runtime load evidence: " + evidence.distinctClasses() + " distinct classes from " + evidence.sources());
        return evidence;
    }

    /** The --exclude-file entry the draft path resolves to, if any. Hard links are caught by content elsewhere. */
    static String aliasesExcludeFile(String draft, List<String> excludeFile) {
        Path resolved;
        try {
            resolved = Path.of(draft).toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        for (String path : excludeFile) {
            try {
                if (Path.of(path).toRealPath().equals(resolved)) {
                    return path;
                }
            } catch (IOException | RuntimeException e) {
                // not resolvable, so not the same file
            }
        }
        return null;
    }

    /**
     * The one evidence site per command, applied to the FINAL violation set: after per-module
     * merging and exclusion, before printing and the exit decision. Quiet paths call this with
     * an empty list so a requested draft file is written on every completed run.
     *
     * @param appRootsMatched gates DRAFTING only. FALSE means some run's roots matched
     *     nothing, so its reachable=false values carry no evidence.
     */
    static void applyEvidenceAndDraft(List<Violation> violations, Boolean appRootsMatched, Evidence.LoadEvidence evidence, String draft) {
        if (evidence == null) {
            return;
        }
        Evidence.apply(violations, evidence);
        if (draft != null) {
            // Zero observed classes is a broken pipeline, not proof that nothing loaded.
            // Drafting from it reads like a well-evidenced run.
            if (evidence.distinctClasses() == 0) {
                throw new UikaException("refusing to draft from " + evidence.sources() + ": no class loads were observed at all, so every "
                        + "violation would be drafted as never-loaded. Check that the evidence was "
                        + "produced and, for the Clojure frontends, that it is text and not JFR.");
            }
            int drafted = Evidence.draftExcludes(violations, appRootsMatched, evidence, draft);
            Out.note("drafted " + drafted + " exclude rule(s) to " + draft);
        }
    }

    /** The one exclusion site per report. */
    static void applyExcludes(Check.Report report, List<Exclude.Rule> excludeRules) {
        Exclude.Stats stats = Exclude.filter(report.violations, excludeRules);
        report.suppressed = stats.suppressed();
        for (String unused : stats.unused()) {
            Out.warn("exclude rule matched nothing: " + unused);
        }
    }

    private static int exitCode(Check.Report result, Cli.FailOn failOn) {
        return shouldFail(result.violations, result.appRootsMatched, failOn) ? 1 : 0;
    }

    /**
     * Exit policy, purely a threshold on {@link Tier#of}: the same call the report's section
     * split makes with the same roots state, so the gate always agrees with what is shown.
     */
    static boolean shouldFail(Iterable<Violation> violations, Boolean appRootsMatched, Cli.FailOn failOn) {
        switch (failOn) {
            case NEVER:
                return false;
            case REACHABLE:
                boolean axis = Tier.reachableAxisValid(appRootsMatched);
                for (Violation v : violations) {
                    if (Tier.of(v, axis) == Tier.BREAKS) {
                        return true;
                    }
                }
                return false;
            default:
                return violations.iterator().hasNext();
        }
    }

    /** Builds the old/new indexes, scans, then evaluates. */
    static Check.Report runCheck(
            List<String> oldJars,
            List<String> newJars,
            List<String> targets,
            List<String> appRoots,
            List<Exclude.Rule> excludeRules,
            Jdk.Indexer jdk,
            Verdicts.Writer verdicts) {
        ApiIndex oldIndex = buildIndex(oldJars);
        ApiIndex newIndex = buildIndex(newJars);
        return runCheckWithIndexes(oldIndex, newIndex, oldJars, newJars, targets, appRoots, excludeRules, jdk, verdicts);
    }

    /**
     * {@link #runCheck} with prebuilt library indexes. The paths are still needed: old
     * versions are excluded from the scan targets, new versions feed the version-lag check.
     * Reachability turns on exactly when there are application roots to walk from.
     */
    static Check.Report runCheckWithIndexes(
            ApiIndex oldIndex,
            ApiIndex newIndex,
            List<String> oldJars,
            List<String> newJars,
            List<String> targets,
            List<String> appRoots,
            List<Exclude.Rule> excludeRules,
            Jdk.Indexer jdk,
            Verdicts.Writer verdicts) {
        boolean reachability = !appRoots.isEmpty();

        // Old-version libraries mixed into the scan targets are skipped: after the upgrade
        // they are no longer on the runtime classpath. The new versions stay scanned.
        Set<Path> excluded = canonical(oldJars);
        Set<Path> seen = new HashSet<>();
        List<String> paths = new ArrayList<>();
        for (String target : targets) {
            Path path = Path.of(target);
            if (!Files.exists(path)) {
                Out.warn("scan target not found, skipping: " + target);
                continue;
            }
            Path real = realPath(path);
            if ((real == null || !excluded.contains(real)) && seen.add(path)) {
                paths.add(target);
            }
        }

        // Scan targets that are new versions of the checked libraries get the extra
        // version-lag check. Interned as the string a class's source is interned as.
        Set<Path> newCanonical = canonical(newJars);
        IntSet upgradedSources = new IntSet();
        for (String p : paths) {
            Path real = realPath(Path.of(p));
            if (real != null && newCanonical.contains(real)) {
                upgradedSources.add(Intern.intern(p));
            }
        }

        MemberProbe probe = Check.selectionMemberProbe(oldIndex, newIndex);
        // With reachability on, pass 1 also collects class-load edges and the scan targets'
        // provider files.
        Scan.Result scanned = Scan.scanTargetPaths(paths, oldIndex, probe, reachability);
        Reach.Inputs reach = null;
        if (reachability) {
            Out.warnAll(scanned.serviceWarnings);
            IntSet appSources = new IntSet();
            for (String root : appRoots) {
                appSources.add(Intern.intern(root));
            }
            reach = new Reach.Inputs(appSources, scanned.services);
        }
        // The new library's own bytecode counts as evidence too, scan target or not.
        Check.libraryInvocationEvidence(newJars, probe, scanned.invocations);
        // The compared libraries' own service files. Always read, unlike the
        // reachability-gated call above: old/new are the small library set.
        List<String> serviceWarnings = new ArrayList<>();
        List<Reach.ServiceFile> oldServices = Reach.collectServices(oldJars, serviceWarnings);
        List<Reach.ServiceFile> newServices = Reach.collectServices(newJars, serviceWarnings);
        Out.warnAll(serviceWarnings);

        Check.Report result = Check.checkScanned(
                scanned, oldIndex, newIndex, upgradedSources, jdk, reach, new Check.SpiServices(oldServices, newServices), verdicts);
        result.scanTargets = paths.size();
        Out.warnAll(result.warnings);
        applyExcludes(result, excludeRules);
        return result;
    }

    private static Set<Path> canonical(List<String> paths) {
        Set<Path> out = new HashSet<>();
        for (String p : paths) {
            Path real = realPath(Path.of(p));
            if (real != null) {
                out.add(real);
            }
        }
        return out;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- upgrade-check ----

    private static void printUpgrade(boolean json, List<Dump.DependencyChange> changes, Check.Report result, Report.ModuleRunSummary modules) {
        if (json) {
            Out.out.println(Report.upgradeJson(changes, result, modules));
        } else {
            Out.out.print(Report.upgradeText(changes, result, modules));
        }
    }

    /**
     * Compares before/after dumps and checks the changed artifacts. Per-module by default:
     * each module whose own resolution changed is checked against its own classpath, never
     * the union of all modules, which mixes several resolved versions of one coordinate.
     */
    private static int cmdUpgradeCheck(Cli.UpgradeCheck args) {
        List<Exclude.Rule> excludeRules = Exclude.load(args.excludeFile());
        // Loaded and opened before the no-changes early return: a bad log path or
        // --jdk-release must fail on every run, not only when jars changed.
        Evidence.LoadEvidence evidence = loadEvidence(args.classLoadLog(), args.draftExcludeFile(), args.excludeFile());
        try (JdkLayer jdk = new JdkLayer(args.jdkRelease())) {
            Dump.Universe before = Dump.loadDump(args.before());
            Dump.Universe after = Dump.loadDump(args.after());
            Dump.DependencyChanges changes = Dump.diffDumps(before, after);

            boolean perModule = !args.mergedClasspath() && hasModuleData(before) && hasModuleData(after);
            if (perModule) {
                return upgradeCheckPerModule(before, after, changes, excludeRules, args, jdk.indexer, evidence);
            }
            if (!args.mergedClasspath()) {
                Out.warn("the dump carries no per-module classpaths; checking the merged universe "
                        + "(regenerate the dumps with a current uika plugin for per-module checking)");
            }

            int[] jdkPair = jdkChange(before, after);
            if (changes.oldJars().isEmpty() && jdkPair == null) {
                // The empty list still writes the requested draft file, like --verdicts-json.
                applyEvidenceAndDraft(new ArrayList<>(), null, evidence, args.draftExcludeFile());
                printUpgrade(args.json(), changes.changes(), null, null);
                return 0;
            }

            Verdicts.Writer verdicts = args.verdictsJson() == null ? null : Verdicts.Writer.create(args.verdictsJson());
            // Scan target = the full after runtime classpath + build outputs. Exclude rules
            // filter the combined set once, as in per-module mode.
            Check.Report result = finishVerdicts(verdicts, () -> {
                Check.Report combined =
                        runCheck(changes.oldJars(), changes.newJars(), after.scanTargets, after.appRoots, List.of(), jdk.indexer, verdicts);
                // The JDK moved too (or only the JDK did), so its removals are checked over the
                // same universe and folded in.
                if (jdkPair != null) {
                    ApiIndex[] pair = jdkReleasePair(jdkPair);
                    Check.Report jdkResult = runCheckWithIndexes(
                            pair[0], pair[1], List.of(), List.of(), after.scanTargets, after.appRoots, List.of(), null, verdicts);
                    combined.violations.addAll(jdkResult.violations);
                    combined.violations.sort(Violation::compare);
                    combined.unknownRefs += jdkResult.unknownRefs;
                }
                return combined;
            });
            applyExcludes(result, excludeRules);
            Suggest.annotate(result.violations, before, after, changes.changes());
            applyEvidenceAndDraft(result.violations, result.appRootsMatched, evidence, args.draftExcludeFile());
            printUpgrade(args.json(), changes.changes(), result, null);
            return exitCode(result, args.failOn());
        }
    }

    /** Whether a dump can drive per-module checking: at least one module lists its own artifacts. */
    private static boolean hasModuleData(Dump.Universe universe) {
        for (Dump.Module m : universe.modules) {
            if (!m.artifacts.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One deduplicated per-module check run: modules whose (old, new, targets, roots) are
     * identical share a single run.
     */
    static final class ModuleRunPlan {
        final List<String> names = new ArrayList<>();
        /** This module's own dependency changes: exact versions for per-run suggestions. */
        List<Dump.DependencyChange> changes = List.of();
        List<String> oldJars = List.of();
        List<String> newJars = List.of();
        List<String> targets = List.of();
        List<String> appRoots = List.of();
        /** Set for a run that compares JDK releases rather than JARs; its jar lists stay empty. */
        int[] jdkPair;
        /** For a JDK run, the modules that made the move. {@link #names} is the attribution key. */
        List<String> jdkModules = List.of();
    }

    static final class ModulePlan {
        final List<ModuleRunPlan> runs = new ArrayList<>();
        int totalModules;
        int unchangedModules;
        /** Only in the after dump with nothing checkable against the union's before versions. */
        int newModules;
        /** After-side modules that lost their entire artifact list: skipped with a warning. */
        int incompleteModules;
    }

    /**
     * A module is checked only when its own resolution lost a version, which keeps the cost
     * proportional to the change. A module whose JDK release moved is checked for that too.
     */
    static ModulePlan planModuleRuns(Dump.Universe before, Dump.Universe after) {
        TargetNotes notes = new TargetNotes();
        ModulePlan plan = planDependencyRuns(before, after, notes);
        planJdkRuns(before, after, plan, notes);
        notes.report();
        return plan;
    }

    /**
     * One run per module whose release moved, over that module's own classpath. Per module
     * because a build may mix releases, and because a run is the unit the report counts and
     * the gate decides on. The cost, a jar on several modules' classpaths scanned once per
     * module, was measured and accepted.
     */
    private static void planJdkRuns(Dump.Universe before, Dump.Universe after, ModulePlan plan, TargetNotes notes) {
        Set<String> seenNames = new HashSet<>();
        for (Dump.Module module : after.modules) {
            if (!seenNames.add(module.name)) {
                continue;
            }
            // A module missing from the before dump has no release to compare with.
            Dump.Module beforeModule = before.module(module.name);
            if (beforeModule == null) {
                continue;
            }
            int[] pair = releaseChange(beforeModule.jdkRelease, module.jdkRelease);
            if (pair == null) {
                continue;
            }
            ModuleRunPlan run = new ModuleRunPlan();
            // The module name AND the pair. This string is the key each violation is
            // attributed by, and a bare module name would collide with that module's
            // dependency run.
            run.names.add(module.name + " (JDK " + pair[0] + " -> " + pair[1] + ")");
            run.targets = moduleTargets(module, after, notes);
            run.appRoots = module.classesDirs;
            run.jdkPair = pair;
            run.jdkModules = List.of(module.name);
            plan.runs.add(run);
        }
    }

    /**
     * The JDK releases to compare, when both sides recorded one and they differ. A dump
     * predating the field reads null, so it never manufactures a JDK change.
     */
    static int[] releaseChange(Integer before, Integer after) {
        if (before == null || after == null || before.equals(after)) {
            return null;
        }
        return new int[] {before, after};
    }

    static int[] jdkChange(Dump.Universe before, Dump.Universe after) {
        return releaseChange(before.jdkRelease, after.jdkRelease);
    }

    /** Scan-target bookkeeping, reported once after planning rather than once per module. */
    private static final class TargetNotes {
        /** file -> modules that needed it. */
        final TreeMap<String, TreeSet<String>> missing = new TreeMap<>(Text::compareUtf8);
        final TreeMap<String, String> substituted = new TreeMap<>(Text::compareUtf8);

        void report() {
            for (Map.Entry<String, String> e : substituted.entrySet()) {
                Out.note(e.getKey() + " is not built; scanning module " + e.getValue() + "'s classesDirs instead");
            }
            for (Map.Entry<String, TreeSet<String>> e : missing.entrySet()) {
                Out.warn("scan target not found, skipping: " + e.getKey() + " (needed by " + String.join(", ", e.getValue()) + ")");
            }
        }
    }

    /**
     * One module's scan targets: its own outputs first (application classes precede
     * dependencies), then the resolved classpath in resolution order. A project-dependency
     * artifact that was never built falls back to the producing module's classesDirs.
     */
    private static List<String> moduleTargets(Dump.Module module, Dump.Universe after, TargetNotes notes) {
        List<String> targets = new ArrayList<>(module.classesDirs);
        for (Dump.Artifact artifact : module.artifacts) {
            if (Files.exists(Path.of(artifact.file()))) {
                targets.add(artifact.file());
                continue;
            }
            Dump.Module producer = artifact.project() == null ? null : after.module(artifact.project());
            if (producer != null && !producer.classesDirs.isEmpty()) {
                notes.substituted.put(artifact.file(), producer.name);
                targets.addAll(producer.classesDirs);
            } else {
                notes.missing.computeIfAbsent(artifact.file(), k -> new TreeSet<>(Text::compareUtf8)).add(module.name);
            }
        }
        return targets;
    }

    private static ModulePlan planDependencyRuns(Dump.Universe before, Dump.Universe after, TargetNotes notes) {
        TreeSet<Dump.Coord> projectCoords = Dump.projectCoordsUnion(before, after);
        ModulePlan plan = new ModulePlan();
        Set<String> seenNames = new LinkedHashSet<>();
        for (Dump.Module module : after.modules) {
            if (!seenNames.add(module.name)) {
                Out.warn("duplicate module name " + module.name + " in dump; only the first is checked");
                continue;
            }
            Dump.VersionMap moduleVersions = module.versions();
            Dump.DependencyChanges moduleChanges;
            Dump.Module beforeModule = before.module(module.name);
            if (beforeModule != null) {
                Dump.VersionMap beforeVersions = beforeModule.versions();
                // A module that lost its ENTIRE resolution is missing data, not an upgrade
                // that removed every dependency.
                if (moduleVersions.isEmpty() && !beforeVersions.isEmpty()) {
                    Out.warn("module " + module.name + " lists no resolved artifacts in the after dump "
                            + "(partial build or failed resolution?); skipping its check");
                    plan.incompleteModules++;
                    continue;
                }
                moduleChanges = Dump.diffVersionMaps(beforeVersions, moduleVersions, projectCoords);
            } else {
                // Renamed or added module. Diff its own coordinates against the union's
                // before versions, so a rename+upgrade is still checked.
                Dump.VersionMap scopedBefore = new Dump.VersionMap();
                for (Dump.Coord coord : moduleVersions.keySet()) {
                    TreeMap<String, String> versions = before.versions.get(coord);
                    if (versions != null) {
                        scopedBefore.put(coord, versions);
                    }
                }
                Dump.DependencyChanges fallback = Dump.diffVersionMaps(scopedBefore, moduleVersions, projectCoords);
                if (fallback.oldJars().isEmpty()) {
                    plan.newModules++;
                    continue;
                }
                Out.warn("module " + module.name + " is not in the before dump (renamed or new); "
                        + "checking it against the union's before versions");
                moduleChanges = fallback;
            }
            if (moduleChanges.oldJars().isEmpty()) {
                plan.unchangedModules++;
                continue;
            }

            List<String> targets = moduleTargets(module, after, notes);
            ModuleRunPlan match = null;
            for (ModuleRunPlan run : plan.runs) {
                if (samePaths(run.oldJars, moduleChanges.oldJars())
                        && samePaths(run.newJars, moduleChanges.newJars())
                        && samePaths(run.targets, targets)
                        && samePaths(run.appRoots, module.classesDirs)) {
                    match = run;
                    break;
                }
            }
            if (match != null) {
                match.names.add(module.name);
            } else {
                ModuleRunPlan run = new ModuleRunPlan();
                run.names.add(module.name);
                run.changes = moduleChanges.changes();
                run.oldJars = moduleChanges.oldJars();
                run.newJars = moduleChanges.newJars();
                run.targets = targets;
                run.appRoots = module.classesDirs;
                plan.runs.add(run);
            }
        }
        plan.totalModules = seenNames.size();
        return plan;
    }

    private static boolean samePaths(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Path.of(a.get(i)).equals(Path.of(b.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** TRUE (proven reachable) > null (not computed) > FALSE (proven not). */
    private static int reachableRank(Boolean reachable) {
        return reachable == null ? 1 : reachable ? 2 : 0;
    }

    private record RunOutcome(List<String> names, int[] jdk, List<String> jdkModules, int scannedClasses, int unknownRefs, Boolean appRootsMatched) {}

    /**
     * The merged violations attributed to one run's modules. The same predicate decides both
     * the per-run broken counts and the per-run exit decision, or the two would disagree.
     */
    private static List<Violation> runViolations(Check.Report merged, List<String> names) {
        List<Violation> out = new ArrayList<>();
        for (Violation v : merged.violations) {
            for (String m : v.modules) {
                if (names.contains(m)) {
                    out.add(v);
                    break;
                }
            }
        }
        return out;
    }

    /** Cross-run violation identity: what the report prints, including the advice, but NOT reachable. */
    private record MergeKey(int source, int sourceClass, SymbolRef reference, Reason reason, String advice) {}

    private static int upgradeCheckPerModule(
            Dump.Universe before,
            Dump.Universe after,
            Dump.DependencyChanges changes,
            List<Exclude.Rule> excludeRules,
            Cli.UpgradeCheck args,
            Jdk.Indexer jdk,
            Evidence.LoadEvidence evidence) {
        ModulePlan plan = planModuleRuns(before, after);
        // Created before the empty-plan return so a requested --verdicts-json file always exists.
        Verdicts.Writer verdicts = args.verdictsJson() == null ? null : Verdicts.Writer.create(args.verdictsJson());

        if (plan.runs.isEmpty()) {
            finishVerdicts(verdicts, () -> null);
            applyEvidenceAndDraft(new ArrayList<>(), null, evidence, args.draftExcludeFile());
            printUpgrade(args.json(), changes.changes(), null, moduleSummary(plan, List.of()));
            return 0;
        }

        Suggest.Annotator annotator = new Suggest.Annotator(before, after);
        Check.Report merged = new Check.Report();
        List<RunOutcome> outcomes = new ArrayList<>();
        finishVerdicts(verdicts, () -> {
            // One library index per distinct jar list, one cache per side: modules pinned to
            // different old versions that upgrade to one shared new version build it once.
            Map<List<Path>, ApiIndex> oldIndexes = new HashMap<>();
            Map<List<Path>, ApiIndex> newIndexes = new HashMap<>();
            // Reading a release out of ct.sym is the expensive half of a JDK run, and the
            // modules of one build usually move together.
            Map<Long, ApiIndex[]> jdkIndexes = new HashMap<>();
            Map<MergeKey, Violation> mergedIndex = new HashMap<>();

            for (ModuleRunPlan run : plan.runs) {
                if (verdicts != null) {
                    verdicts.setModule(String.join(",", run.names));
                }
                ApiIndex oldIndex;
                ApiIndex newIndex;
                if (run.jdkPair != null) {
                    int[] pair = run.jdkPair;
                    ApiIndex[] indexes = jdkIndexes.computeIfAbsent((long) pair[0] << 32 | pair[1], k -> jdkReleasePair(pair));
                    oldIndex = indexes[0];
                    newIndex = indexes[1];
                } else {
                    oldIndex = oldIndexes.computeIfAbsent(pathKey(run.oldJars), k -> buildIndex(run.oldJars));
                    newIndex = newIndexes.computeIfAbsent(pathKey(run.newJars), k -> buildIndex(run.newJars));
                }
                // Exclude rules are NOT applied per run: they filter the merged set below,
                // so counts and unused-rule warnings appear once.
                Check.Report result =
                        runCheckWithIndexes(oldIndex, newIndex, run.oldJars, run.newJars, run.targets, run.appRoots, List.of(), jdk, verdicts);
                merged.scannedClasses += result.scannedClasses;
                merged.unknownRefs += result.unknownRefs;
                merged.reachabilityComputed |= result.reachabilityComputed;
                outcomes.add(new RunOutcome(
                        run.names, run.jdkPair, run.jdkModules, result.scannedClasses, result.unknownRefs, result.appRootsMatched));
                // Per-run suggestions from this module's own change list: exact versions even
                // when the universe-wide diff is empty (a swap) or unions other modules' moves.
                annotator.annotate(result.violations, run.changes);
                for (Violation v : result.violations) {
                    MergeKey key = new MergeKey(
                            v.source, v.sourceClass, v.reference, v.reason, v.suggestion == null ? null : v.suggestion.advice());
                    Violation existing = mergedIndex.get(key);
                    if (existing != null) {
                        existing.modules.addAll(run.names);
                        if (reachableRank(v.reachable) > reachableRank(existing.reachable)) {
                            existing.reachable = v.reachable;
                        }
                        // Most-dangerous-wins, like reachable: one invocation is enough.
                        if (Boolean.TRUE.equals(v.invocationFound)) {
                            existing.invocationFound = Boolean.TRUE;
                        }
                    } else {
                        mergedIndex.put(key, v);
                        v.modules = new ArrayList<>(run.names);
                        merged.violations.add(v);
                    }
                }
            }
            return null;
        });

        // Canonical cross-run order, with the module list as the tiebreak between entries
        // merged from different runs.
        for (Violation v : merged.violations) {
            TreeSet<String> distinct = new TreeSet<>(Text::compareUtf8);
            distinct.addAll(v.modules);
            v.modules = new ArrayList<>(distinct);
        }
        merged.violations.sort((a, b) -> {
            int c = Violation.compare(a, b);
            return c != 0 ? c : compareLists(a.modules, b.modules);
        });

        applyExcludes(merged, excludeRules);
        // Drafting gets the FALSE-dominating fold of the per-run roots states, never
        // merged.appRootsMatched (which stays null): a module whose roots matched nothing
        // stamps meaningless reachable=false on its violations, and drafting those would
        // propose waiving the very violations the per-run gate fails on.
        Boolean draftAxis = null;
        for (RunOutcome o : outcomes) {
            if (Boolean.FALSE.equals(o.appRootsMatched()) || Boolean.FALSE.equals(draftAxis)) {
                draftAxis = Boolean.FALSE;
            } else if (Boolean.TRUE.equals(o.appRootsMatched())) {
                draftAxis = Boolean.TRUE;
            }
        }
        applyEvidenceAndDraft(merged.violations, draftAxis, evidence, args.draftExcludeFile());

        // Fail per run with its own roots state: a module whose classesDirs matched nothing
        // degrades --fail-on reachable to any for ITS violations only.
        boolean failed = false;
        for (RunOutcome o : outcomes) {
            failed |= shouldFail(runViolations(merged, o.names()), o.appRootsMatched(), args.failOn());
        }

        List<Report.ModuleOutcome> moduleOutcomes = new ArrayList<>();
        for (RunOutcome o : outcomes) {
            // Broken counts come from the merged post-exclusion set, so they agree with the listing.
            moduleOutcomes.add(new Report.ModuleOutcome(
                    o.names(), o.jdk() != null, o.jdkModules(), o.jdk(), o.scannedClasses(), runViolations(merged, o.names()).size(), o.unknownRefs()));
        }
        printUpgrade(args.json(), changes.changes(), merged, moduleSummary(plan, moduleOutcomes));
        return failed ? 1 : 0;
    }

    private static List<Path> pathKey(List<String> paths) {
        List<Path> key = new ArrayList<>(paths.size());
        for (String p : paths) {
            key.add(Path.of(p));
        }
        return key;
    }

    private static int compareLists(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = Text.compareUtf8(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static Report.ModuleRunSummary moduleSummary(ModulePlan plan, List<Report.ModuleOutcome> outcomes) {
        return new Report.ModuleRunSummary(outcomes, plan.totalModules, plan.unchangedModules, plan.newModules, plan.incompleteModules);
    }

    // ---- dump ----

    private static int cmdDump(String path) {
        List<DumpedClass> classes = new ArrayList<>();
        Input.onPool(() -> {
            List<List<DumpedClass>> leaves = new ArrayList<>();
            Input.forEachClass(path, new Input.Sink<List<DumpedClass>>() {
                @Override
                public List<DumpedClass> newLeaf() {
                    return new ArrayList<>();
                }

                @Override
                public void accept(List<DumpedClass> leaf, Scratch scratch, int source, int entry, ClassSource bytes) {
                    try {
                        bytes.readAll();
                        scratch.parser.parse(bytes.bytes, bytes.available);
                        leaf.add(new DumpedClass(source, entry, Extract.extractApi(scratch.parser, scratch), null));
                    } catch (ClassParser.FormatException e) {
                        leaf.add(new DumpedClass(source, entry, null, e.getMessage()));
                    }
                }
            }, leaves);
            for (List<DumpedClass> leaf : leaves) {
                classes.addAll(leaf);
            }
        });
        int parseErrors = 0;
        int nameMismatches = 0;
        StringBuilder out = new StringBuilder();
        for (DumpedClass c : classes) {
            String entryName = Intern.str(c.entry()) + ".class";
            if (c.api() == null) {
                parseErrors++;
                Out.warn(Intern.str(c.source()) + "!" + entryName + ": " + c.error());
                continue;
            }
            ClassApi api = c.api();
            // Also verifies the constant pool index convention: this_class should match the entry name.
            if (c.entry() != api.name) {
                nameMismatches++;
                Out.warn("entry " + entryName + " but this_class " + Intern.str(api.name));
            }
            String name = Intern.str(api.name);
            out.append("class ").append(name).append(" [").append(flags(api.access)).append("]\n");
            if (api.superName != Intern.NONE) {
                out.append("  extends ").append(Intern.str(api.superName)).append('\n');
            }
            for (int iface : api.interfaces) {
                out.append("  implements ").append(Intern.str(iface)).append('\n');
            }
            appendMembers(out, "method", name, api.methodKeys, api.methodAccess);
            appendMembers(out, "field", name, api.fieldKeys, api.fieldAccess);
            if (out.length() > 1 << 16) {
                Out.out.print(out);
                out.setLength(0);
            }
        }
        Out.out.print(out);
        Out.err.println("dumped " + (classes.size() - parseErrors) + " classes (" + parseErrors + " parse errors, " + nameMismatches
                + " name mismatches)");
        return 0;
    }

    private record DumpedClass(int source, int entry, ClassApi api, String error) {}

    private static void appendMembers(StringBuilder out, String kind, String className, long[] keys, char[] access) {
        Integer[] order = new Integer[keys.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> MemberKey.compare(keys[a], keys[b]));
        for (int i : order) {
            out.append("  ").append(kind).append(' ').append(className).append('.').append(Intern.str(MemberKey.name(keys[i])))
                    .append(' ').append(Intern.str(MemberKey.descriptor(keys[i]))).append(" [").append(flags(access[i])).append("]\n");
        }
    }

    private static String flags(int access) {
        String visibility = (access & Acc.PUBLIC) != 0
                ? "public"
                : (access & Acc.PROTECTED) != 0 ? "protected" : (access & Acc.PRIVATE) != 0 ? "private" : "package";
        return (access & Acc.STATIC) != 0 ? visibility + " static" : visibility;
    }
}
