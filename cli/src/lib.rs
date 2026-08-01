pub mod check;
pub mod classfile;
pub mod cli;
pub mod diff;
pub mod exclude;
pub mod extract;
pub mod gradle;
pub mod index;
pub mod input;
pub mod intern;
pub mod jdk;
pub mod memstats;
pub mod model;
pub mod reach;
pub mod report;
pub mod suggest;
pub mod verdicts;
pub mod window;

use anyhow::Result;
use check::CheckReport;
use cli::{Cli, Command, FailOn};
use index::ApiIndex;
use model::{ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC, ACC_STATIC};
use std::path::{Path, PathBuf};

pub fn run(cli: Cli) -> Result<i32> {
    match cli.command {
        Command::Diff { old, new, json } => cmd_diff(&old, &new, json),
        Command::Check {
            old,
            new,
            classpath,
            app,
            classpath_file,
            exclude_file,
            json,
            fail_on,
            jdk_release,
            verdicts_json,
        } => {
            let mut targets: Vec<PathBuf> = classpath;
            let mut app_roots: Vec<PathBuf> = app.clone();
            targets.extend(app);
            for dump in &classpath_file {
                let universe = gradle::load_dump(dump)?;
                app_roots.extend(universe.app_roots);
                targets.extend(universe.scan_targets);
            }
            cmd_check(
                &old,
                &new,
                &targets,
                &app_roots,
                &exclude_file,
                json,
                fail_on,
                jdk_release,
                verdicts_json.as_deref(),
            )
        }
        Command::UpgradeCheck {
            before,
            after,
            exclude_file,
            json,
            fail_on,
            jdk_release,
            verdicts_json,
            merged,
        } => cmd_upgrade_check(
            &before,
            &after,
            &exclude_file,
            json,
            fail_on,
            jdk_release,
            verdicts_json.as_deref(),
            merged,
        ),
        Command::Dump { path } => cmd_dump(&path),
    }
}

fn build_index(path: &Path) -> Result<ApiIndex> {
    build_index_multi(std::slice::from_ref(&path.to_path_buf()))
}

/// Build one index from multiple JARs (duplicate class names are first-wins = argument order).
fn build_index_multi(paths: &[PathBuf]) -> Result<ApiIndex> {
    let mut classes = Vec::new();
    for path in paths {
        classes.extend(input::load(path)?);
    }
    let (index, warnings) = ApiIndex::from_classes(&classes);
    warn_all(&warnings);
    Ok(index)
}

fn warn_all(warnings: &[String]) {
    for w in warnings {
        eprintln!("warning: {w}");
    }
}

fn cmd_diff(old: &Path, new: &Path, json: bool) -> Result<i32> {
    let old_index = build_index(old)?;
    let new_index = build_index(new)?;
    let changes = diff::diff(&old_index, &new_index);
    if json {
        println!("{}", report::diff_json(&changes)?);
    } else {
        print!("{}", report::diff_text(&changes));
    }
    Ok(0)
}

#[allow(clippy::too_many_arguments)]
fn cmd_check(
    old: &[PathBuf],
    new: &[PathBuf],
    targets: &[PathBuf],
    app_roots: &[PathBuf],
    exclude_file: &[PathBuf],
    json: bool,
    fail_on: FailOn,
    jdk_release: Option<u32>,
    verdicts_json: Option<&Path>,
) -> Result<i32> {
    let exclude_rules = exclude::load(exclude_file)?;
    let mut jdk_indexer = jdk::indexer_for(jdk_release)?;
    let mut verdict_writer = verdicts_json
        .map(verdicts::VerdictWriter::create)
        .transpose()?;
    let result = run_check(
        old,
        new,
        targets,
        app_roots,
        &exclude_rules,
        jdk_indexer.as_mut(),
        verdict_writer.as_mut(),
    );
    let result = finish_verdicts(verdict_writer, result)?;
    if json {
        println!("{}", report::check_json(&result)?);
    } else {
        print!("{}", report::check_text(&result));
    }
    Ok(exit_code(&result, fail_on))
}

/// Close the verdict stream and surface a stream failure. Always runs `finish` (so the
/// buffered tail is flushed and the failure is never lost to an early return), then fails
/// the command when the check itself succeeded: the stream is an explicitly requested
/// output, and a silently truncated one would let an answer-check pass on a prefix of the
/// verdicts. When the check already failed, its error stays primary and the stream failure
/// degrades to a warning.
fn finish_verdicts<T>(writer: Option<verdicts::VerdictWriter>, result: Result<T>) -> Result<T> {
    let stream_error = writer.and_then(verdicts::VerdictWriter::finish);
    match (result, stream_error) {
        (Ok(_), Some(msg)) => Err(anyhow::anyhow!(msg)),
        (Err(e), Some(msg)) => {
            eprintln!("warning: {msg}");
            Err(e)
        }
        (result, None) => result,
    }
}

/// Filter known false positives and record the unused-rule warnings on the report (the one
/// exclusion site per report: run_check applies it to its own result, the per-module merger
/// to the merged set).
fn apply_excludes(report: &mut check::CheckReport, exclude_rules: &[exclude::ExcludeRule]) {
    let stats = exclude::filter(&mut report.violations, exclude_rules);
    report.suppressed = stats.suppressed;
    report.warnings.extend(
        stats
            .unused
            .into_iter()
            .map(|u| format!("exclude rule matched nothing: {u}")),
    );
}

/// Map a finished check to a process exit code per the selected policy. The report itself is
/// always printed in full; this only decides whether the run fails the caller (e.g. CI).
fn exit_code(result: &CheckReport, fail_on: FailOn) -> i32 {
    if should_fail(
        result.violations.iter().map(|v| v.reachable),
        result.app_roots_matched,
        fail_on,
    ) {
        1
    } else {
        0
    }
}

/// Exit policy over each violation's reachability flag. `Reachable` counts a violation unless it
/// is proven not reachable (`Some(false)`), so when reachability was not computed (all `None`) it
/// degrades to `Any`, matching the conservative stance that an unproven case is never dropped.
/// `app_roots_matched == Some(false)` means app roots were supplied but none matched a scanned
/// class, so the not-proven-reachable labels have no basis (nothing was walked from); `Reachable`
/// then also degrades to `Any` rather than passing every violation off as unreachable.
fn should_fail(
    mut reachables: impl Iterator<Item = Option<bool>>,
    app_roots_matched: Option<bool>,
    fail_on: FailOn,
) -> bool {
    match fail_on {
        FailOn::Never => false,
        FailOn::Reachable if app_roots_matched != Some(false) => {
            reachables.any(model::counts_as_reachable)
        }
        FailOn::Reachable | FailOn::Any => reachables.next().is_some(),
    }
}

/// Build old/new indexes, scan, then evaluate. Shared by upgrade-check and check.
/// Reachability ranking is only meaningful with application roots to walk from, so it turns
/// on exactly when they are present (--app or dump build outputs); when on, pass 1 also
/// collects class-load edges and each violation is tagged with whether its class is reachable.
/// `exclude_rules` is applied last, after verdicts and reachability; the per-module
/// upgrade-check passes no rules here and applies the same filter once to its merged set, so
/// exclusion still has one policy site per command.
pub fn run_check(
    old: &[PathBuf],
    new: &[PathBuf],
    targets: &[PathBuf],
    app_roots: &[PathBuf],
    exclude_rules: &[exclude::ExcludeRule],
    jdk: Option<&mut jdk::JdkIndexer>,
    verdicts: Option<&mut verdicts::VerdictWriter>,
) -> Result<check::CheckReport> {
    memstats::report("start");
    let old_index = build_index_multi(old)?;
    let new_index = build_index_multi(new)?;
    memstats::report("after old/new index build");
    run_check_with_indexes(
        &old_index,
        &new_index,
        old,
        new,
        targets,
        app_roots,
        exclude_rules,
        jdk,
        verdicts,
    )
}

/// run_check with prebuilt library indexes. The per-module upgrade-check builds each distinct
/// (old, new) jar pair once and reuses the indexes across module runs; `old`/`new` are still
/// needed as paths (old versions are excluded from scan targets, new versions feed the
/// version-lag check).
#[allow(clippy::too_many_arguments)]
pub fn run_check_with_indexes(
    old_index: &ApiIndex,
    new_index: &ApiIndex,
    old: &[PathBuf],
    new: &[PathBuf],
    targets: &[PathBuf],
    app_roots: &[PathBuf],
    exclude_rules: &[exclude::ExcludeRule],
    jdk: Option<&mut jdk::JdkIndexer>,
    verdicts: Option<&mut verdicts::VerdictWriter>,
) -> Result<check::CheckReport> {
    let reachability = !app_roots.is_empty();

    // Skip the old-version libraries if they are mixed into scan targets: after the
    // upgrade they are no longer on the runtime classpath. The new versions stay
    // scanned (they ARE runtime code), which lets check_scanned catch version-lag
    // breakage introduced by the upgraded artifacts' own new classes.
    // Missing paths (unbuilt output directories, etc.) are warned and skipped.
    let excluded: Vec<_> = old
        .iter()
        .filter_map(|p| std::fs::canonicalize(p).ok())
        .collect();
    let mut seen = std::collections::BTreeSet::new();
    let paths: Vec<PathBuf> = targets
        .iter()
        .filter(|path| {
            if !path.exists() {
                eprintln!(
                    "warning: scan target not found, skipping: {}",
                    path.display()
                );
                return false;
            }
            let canon = std::fs::canonicalize(path).ok();
            canon.as_ref().is_none_or(|c| !excluded.contains(c)) && seen.insert((*path).clone())
        })
        .cloned()
        .collect();

    // Scan targets that are new versions of the checked libraries. Their classes get
    // the extra version-lag check (a class newly extending something final on the
    // runtime classpath). Interned as the same display string input.rs uses for a
    // class's source, so membership can be tested per graph node.
    let new_canon: Vec<_> = new
        .iter()
        .filter_map(|p| std::fs::canonicalize(p).ok())
        .collect();
    let upgraded_sources: rustc_hash::FxHashSet<intern::Sym> = paths
        .iter()
        .filter(|p| {
            std::fs::canonicalize(p)
                .ok()
                .is_some_and(|c| new_canon.contains(&c))
        })
        .map(|p| intern::intern(&p.display().to_string()))
        .collect();

    // Build reachability inputs before the scan so pass 1 collects class-load edges only
    // when needed. Service files are read from the same scan targets.
    let reach = if reachability {
        let (services, warnings) = reach::collect_services(&paths);
        warn_all(&warnings);
        let app_sources = app_roots
            .iter()
            .map(|p| intern::intern(&p.display().to_string()))
            .collect();
        Some(reach::ReachInputs {
            app_sources,
            services,
        })
    } else {
        None
    };

    // Read and parse in parallel by chunk, then merge directly into the index.
    let scanned = check::scan_target_paths(&paths, old_index, reachability)?;
    memstats::report("after scan target indexing");
    let mut result = check::check_scanned(
        scanned,
        old_index,
        new_index,
        &upgraded_sources,
        jdk,
        reach,
        verdicts,
    );
    apply_excludes(&mut result, exclude_rules);
    warn_all(&result.warnings);
    Ok(result)
}

/// Print the upgrade-check report in the selected format (the one output site for all of
/// cmd_upgrade_check's exit paths).
fn print_upgrade(
    json: bool,
    changes: &[gradle::DependencyChange],
    result: Option<&check::CheckReport>,
    modules: Option<&report::ModuleRunSummary>,
) -> Result<()> {
    if json {
        println!("{}", report::upgrade_json(changes, result, modules)?);
    } else {
        print!("{}", report::upgrade_text(changes, result, modules));
    }
    Ok(())
}

/// Compare before/after dependency dumps and check the changed artifacts.
///
/// Default mode is per-module when both dumps carry per-module artifact data: each module whose
/// own resolution changed is checked against its own classpath (a real JVM classpath), never
/// against the union of all modules. The union mixes several resolved versions of one
/// coordinate, and judging one version's classes against another's produced false "broken"
/// verdicts for self-consistent jars (and masked upgrades whose old version another module
/// still resolves). `--merged` (or module-less dumps) keeps the old flat behavior.
#[allow(clippy::too_many_arguments)]
fn cmd_upgrade_check(
    before: &Path,
    after: &Path,
    exclude_file: &[PathBuf],
    json: bool,
    fail_on: FailOn,
    jdk_release: Option<u32>,
    verdicts_json: Option<&Path>,
    merged: bool,
) -> Result<i32> {
    let exclude_rules = exclude::load(exclude_file)?;
    // Opened before the no-changes early return: a bad --jdk-release value or
    // environment must fail on every run, not only on the first run that has
    // changed jars (a misconfigured PR gate would otherwise pass for weeks).
    let mut jdk_indexer = jdk::indexer_for(jdk_release)?;
    let before_universe = gradle::load_dump(before)?;
    let after_universe = gradle::load_dump(after)?;
    let changes = gradle::diff_dumps(&before_universe, &after_universe);

    let per_module =
        !merged && has_module_data(&before_universe) && has_module_data(&after_universe);
    if per_module {
        return upgrade_check_per_module(
            &before_universe,
            &after_universe,
            &changes,
            &exclude_rules,
            json,
            fail_on,
            jdk_indexer.as_mut(),
            verdicts_json,
        );
    }
    if !merged {
        eprintln!(
            "warning: dump carries no per-module classpaths; checking the merged universe \
             (regenerate the dumps with a current uika plugin for per-module checking)"
        );
    }

    if changes.old_jars.is_empty() {
        print_upgrade(json, &changes.changes, None, None)?;
        return Ok(0);
    }

    let mut verdict_writer = verdicts_json
        .map(verdicts::VerdictWriter::create)
        .transpose()?;
    // Scan target = the full after runtime classpath + build outputs.
    // Check removed/changed old versions as --old and new versions as --new in one batch.
    // Reachability ranks against the dump's own build outputs (run_check turns it on when present).
    let result = run_check(
        &changes.old_jars,
        &changes.new_jars,
        &after_universe.scan_targets,
        &after_universe.app_roots,
        &exclude_rules,
        jdk_indexer.as_mut(),
        verdict_writer.as_mut(),
    );
    let mut result = finish_verdicts(verdict_writer, result)?;
    // Attribute each break to the artifacts involved and propose a fix (coordinates only exist
    // for upgrade-check, so this lives here rather than in the shared run_check).
    suggest::annotate(
        &mut result.violations,
        &before_universe,
        &after_universe,
        &changes.changes,
    );
    print_upgrade(json, &changes.changes, Some(&result), None)?;
    Ok(exit_code(&result, fail_on))
}

/// Whether a dump can drive per-module checking: at least one module lists its own artifacts.
/// (v2 dumps written by current plugins always do; hand-written, pre-artifactRefs, or
/// unnamed-module dumps do not and fall back to the merged universe.)
fn has_module_data(universe: &gradle::Universe) -> bool {
    universe.modules.iter().any(|m| !m.artifacts.is_empty())
}

/// One deduplicated per-module check run: modules whose (old, new, targets, roots) are
/// identical share a single run and its results. The change lists behind equal jar sets can
/// differ only in Added entries, which never feed suggestions, so sharing the first module's
/// list is safe.
struct ModuleRunPlan {
    names: Vec<String>,
    /// This module's own dependency changes: feeds per-run suggestions with the exact
    /// versions the module moved (the universe-wide list can be empty or mix other modules').
    changes: Vec<gradle::DependencyChange>,
    old_jars: Vec<PathBuf>,
    new_jars: Vec<PathBuf>,
    targets: Vec<PathBuf>,
    app_roots: Vec<PathBuf>,
}

struct ModulePlan {
    runs: Vec<ModuleRunPlan>,
    total_modules: usize,
    unchanged_modules: usize,
    /// Modules present only in the after dump with nothing checkable against the union's
    /// before versions (genuinely new code).
    new_modules: usize,
    /// After-side modules that lost their entire artifact list (partial build or failed
    /// resolution): skipped with a warning instead of being read as total removal.
    incomplete_modules: usize,
}

/// Decide which modules need a check and with what inputs. A module is checked only when its
/// own resolution lost a version (same gate as the merged mode's old_jars, per module) — an
/// unchanged module cannot break from the upgrade and is skipped, which also keeps the cost
/// proportional to the change, not the repository.
fn plan_module_runs(before: &gradle::Universe, after: &gradle::Universe) -> ModulePlan {
    let project_coords = gradle::project_coords_union(before, after);
    let mut runs: Vec<ModuleRunPlan> = Vec::new();
    let mut seen_names = std::collections::BTreeSet::new();
    let mut unchanged = 0usize;
    let mut new_modules = 0usize;
    let mut incomplete_modules = 0usize;
    // file -> modules that needed it: warned once after planning, not once per module.
    let mut missing: std::collections::BTreeMap<PathBuf, std::collections::BTreeSet<String>> =
        std::collections::BTreeMap::new();
    let mut substituted: std::collections::BTreeMap<PathBuf, String> =
        std::collections::BTreeMap::new();

    for module in &after.modules {
        if !seen_names.insert(module.name.clone()) {
            eprintln!(
                "warning: duplicate module name {} in dump; only the first is checked",
                module.name
            );
            continue;
        }
        let module_versions = module.versions();
        let module_changes = match before.module(&module.name) {
            Some(before_module) => {
                let before_versions = before_module.versions();
                // A module that lost its ENTIRE resolution is missing data (partial build,
                // failed resolution), not a upgrade that removed every dependency; diffing
                // it would report each of its references as broken.
                if module_versions.is_empty() && !before_versions.is_empty() {
                    eprintln!(
                        "warning: module {} lists no resolved artifacts in the after dump \
                         (partial build or failed resolution?); skipping its check",
                        module.name
                    );
                    incomplete_modules += 1;
                    continue;
                }
                gradle::diff_version_maps(&before_versions, &module_versions, &project_coords)
            }
            None => {
                // Renamed or added module: nothing to pair with by name. Fall back to
                // merged-mode semantics scoped to this module: diff its own coordinates
                // against the union's before versions, so a rename+upgrade PR is still
                // checked rather than silently skipped. A genuinely new module finds no
                // before versions for its coordinates and has nothing to check.
                let scoped_before: gradle::VersionMap = module_versions
                    .keys()
                    .filter_map(|coord| before.versions.get_key_value(coord))
                    .map(|(coord, versions)| (coord.clone(), versions.clone()))
                    .collect();
                let fallback =
                    gradle::diff_version_maps(&scoped_before, &module_versions, &project_coords);
                if fallback.old_jars.is_empty() {
                    new_modules += 1;
                    continue;
                }
                eprintln!(
                    "warning: module {} is not in the before dump (renamed or new); \
                     checking it against the union's before versions",
                    module.name
                );
                fallback
            }
        };
        if module_changes.old_jars.is_empty() {
            unchanged += 1;
            continue;
        }

        // The module's own outputs first (JVM order: application classes precede dependencies),
        // then the resolved classpath in resolution order. A project-dependency artifact that
        // was never built falls back to the producing module's classesDirs from the same dump.
        let mut targets = module.classes_dirs.clone();
        for artifact in &module.artifacts {
            if artifact.file.exists() {
                targets.push(artifact.file.clone());
                continue;
            }
            let producer = artifact
                .project
                .as_deref()
                .and_then(|p| after.module(p))
                .filter(|m| !m.classes_dirs.is_empty());
            match producer {
                Some(producer) => {
                    substituted.insert(artifact.file.clone(), producer.name.clone());
                    targets.extend(producer.classes_dirs.iter().cloned());
                }
                None => {
                    missing
                        .entry(artifact.file.clone())
                        .or_default()
                        .insert(module.name.clone());
                }
            }
        }

        match runs.iter_mut().find(|r| {
            r.old_jars == module_changes.old_jars
                && r.new_jars == module_changes.new_jars
                && r.targets == targets
                && r.app_roots == module.classes_dirs
        }) {
            Some(run) => run.names.push(module.name.clone()),
            None => runs.push(ModuleRunPlan {
                names: vec![module.name.clone()],
                changes: module_changes.changes,
                old_jars: module_changes.old_jars,
                new_jars: module_changes.new_jars,
                targets,
                app_roots: module.classes_dirs.clone(),
            }),
        }
    }

    for (file, project) in &substituted {
        eprintln!(
            "note: {} is not built; scanning module {}'s classesDirs instead",
            file.display(),
            project
        );
    }
    for (file, modules) in &missing {
        eprintln!(
            "warning: scan target not found, skipping: {} (needed by {})",
            file.display(),
            modules.iter().cloned().collect::<Vec<_>>().join(", ")
        );
    }

    ModulePlan {
        runs,
        total_modules: seen_names.len(),
        unchanged_modules: unchanged,
        new_modules,
        incomplete_modules,
    }
}

/// Some(true) (proven reachable) > None (not computed) > Some(false) (proven not).
/// Merging a violation found by several runs keeps the most-reachable value, so one break
/// counts once and stays in the tier the strictest run put it in.
fn reachable_rank(reachable: Option<bool>) -> u8 {
    match reachable {
        Some(true) => 2,
        None => 1,
        Some(false) => 0,
    }
}

/// Per-run bookkeeping the aggregate report and the exit decision need after merging.
struct RunOutcome {
    names: Vec<String>,
    scanned_classes: usize,
    unknown_refs: usize,
    app_roots_matched: Option<bool>,
}

/// The merged violations attributed to one run's modules — the same predicate must decide
/// both the per-run broken counts and the per-run exit decision, or the two would disagree.
fn run_violations<'a>(
    merged: &'a check::CheckReport,
    names: &'a [String],
) -> impl Iterator<Item = &'a model::Violation> {
    merged
        .violations
        .iter()
        .filter(|v| v.modules.iter().any(|m| names.contains(m)))
}

/// One library index per distinct jar list, one cache per side: modules pinned to different
/// old versions that upgrade to one shared new version still build the new index once.
fn cached_index<'a>(
    cache: &'a mut Vec<(Vec<PathBuf>, ApiIndex)>,
    jars: &[PathBuf],
) -> Result<&'a ApiIndex> {
    let position = match cache.iter().position(|(key, _)| key == jars) {
        Some(i) => i,
        None => {
            let index = build_index_multi(jars)?;
            cache.push((jars.to_vec(), index));
            cache.len() - 1
        }
    };
    Ok(&cache[position].1)
}

/// Run one check per changed module (deduplicated), merge the results into one report with
/// per-violation module attribution, and apply exclude rules once to the merged set (the
/// single policy site for this command; run_check is invoked with no rules).
#[allow(clippy::too_many_arguments)]
fn upgrade_check_per_module(
    before_universe: &gradle::Universe,
    after_universe: &gradle::Universe,
    changes: &gradle::DependencyChanges,
    exclude_rules: &[exclude::ExcludeRule],
    json: bool,
    fail_on: FailOn,
    mut jdk_indexer: Option<&mut jdk::JdkIndexer>,
    verdicts_json: Option<&Path>,
) -> Result<i32> {
    let plan = plan_module_runs(before_universe, after_universe);
    // Created before the empty-plan return so a requested --verdicts-json file always
    // exists (empty when nothing was checked), never silently absent.
    let mut verdict_writer = verdicts_json
        .map(verdicts::VerdictWriter::create)
        .transpose()?;

    if plan.runs.is_empty() {
        finish_verdicts(verdict_writer, Ok(()))?;
        let summary = module_summary(&plan, Vec::new());
        print_upgrade(json, &changes.changes, None, Some(&summary))?;
        return Ok(0);
    }

    let mut annotator = suggest::Annotator::new(before_universe, after_universe);
    let result = (|| {
        let mut merged = check::CheckReport {
            violations: Vec::new(),
            warnings: Vec::new(),
            scanned_classes: 0,
            unknown_refs: 0,
            suppressed: 0,
            reachability_computed: false,
            app_roots_matched: None,
        };
        let mut run_outcomes: Vec<RunOutcome> = Vec::new();
        // One library-index cache per side (see cached_index).
        let mut old_indexes: Vec<(Vec<PathBuf>, ApiIndex)> = Vec::new();
        let mut new_indexes: Vec<(Vec<PathBuf>, ApiIndex)> = Vec::new();
        // Cross-run violation identity: what the report prints, including the advice (per-run
        // suggestions may legitimately differ for one reference when modules moved different
        // versions), but NOT reachable — the same break found by runs that disagree on
        // reachability must count once, kept at the most-reachable value (reachable_rank).
        #[allow(clippy::type_complexity)]
        let mut merged_index: rustc_hash::FxHashMap<
            (
                intern::Sym,
                intern::Sym,
                model::SymbolRef,
                String,
                Option<String>,
            ),
            usize,
        > = rustc_hash::FxHashMap::default();

        for run in &plan.runs {
            if let Some(w) = verdict_writer.as_mut() {
                w.set_module(Some(run.names.join(",")));
            }
            let old_index = cached_index(&mut old_indexes, &run.old_jars)?;
            let new_index = cached_index(&mut new_indexes, &run.new_jars)?;
            // Exclude rules are deliberately NOT applied per run: they filter the merged,
            // deduplicated set below, so counts and unused-rule warnings appear once.
            let mut result = run_check_with_indexes(
                old_index,
                new_index,
                &run.old_jars,
                &run.new_jars,
                &run.targets,
                &run.app_roots,
                &[],
                jdk_indexer.as_deref_mut(),
                verdict_writer.as_mut(),
            )?;
            merged.scanned_classes += result.scanned_classes;
            merged.unknown_refs += result.unknown_refs;
            merged.reachability_computed |= result.reachability_computed;
            run_outcomes.push(RunOutcome {
                names: run.names.clone(),
                scanned_classes: result.scanned_classes,
                unknown_refs: result.unknown_refs,
                app_roots_matched: result.app_roots_matched,
            });
            // Per-run suggestions from this module's own change list: exact versions even
            // when the universe-wide diff is empty (a swap) or unions several modules' moves.
            annotator.annotate(&mut result.violations, &run.changes);
            for mut v in result.violations {
                let key = (
                    v.source,
                    v.source_class,
                    v.reference,
                    v.reason.clone(),
                    v.suggestion.as_ref().map(|s| s.advice.clone()),
                );
                match merged_index.get(&key) {
                    Some(&i) => {
                        let existing = &mut merged.violations[i];
                        existing.modules.extend(run.names.iter().cloned());
                        if reachable_rank(v.reachable) > reachable_rank(existing.reachable) {
                            existing.reachable = v.reachable;
                        }
                    }
                    None => {
                        merged_index.insert(key, merged.violations.len());
                        v.modules = run.names.clone();
                        merged.violations.push(v);
                    }
                }
            }
        }
        Ok((merged, run_outcomes))
    })();
    let (mut merged, run_outcomes) = finish_verdicts(verdict_writer, result)?;

    // Canonical cross-run order: the same string-value key check_scanned sorts by, with the
    // module list as the tiebreak between entries merged from different runs.
    for v in &mut merged.violations {
        v.modules.sort();
        v.modules.dedup();
    }
    merged
        .violations
        .sort_by_cached_key(|v| (check::violation_sort_key(v), v.modules.clone()));

    apply_excludes(&mut merged, exclude_rules);
    warn_all(&merged.warnings);

    // Fail per run with its own roots state: a module whose classesDirs matched nothing
    // degrades --fail-on reachable to any for ITS violations only (module attribution
    // reconstructs each run's post-exclusion set exactly).
    let failed = run_outcomes.iter().any(|outcome| {
        should_fail(
            run_violations(&merged, &outcome.names).map(|v| v.reachable),
            outcome.app_roots_matched,
            fail_on,
        )
    });

    let summary = module_summary(&plan, module_outcomes(&run_outcomes, &merged));
    print_upgrade(json, &changes.changes, Some(&merged), Some(&summary))?;
    Ok(if failed { 1 } else { 0 })
}

/// Per-run outcome lines, with broken counts taken from the merged post-exclusion set so
/// the numbers agree with the violation listing.
fn module_outcomes(
    run_outcomes: &[RunOutcome],
    merged: &check::CheckReport,
) -> Vec<report::ModuleOutcome> {
    run_outcomes
        .iter()
        .map(|outcome| report::ModuleOutcome {
            modules: outcome.names.clone(),
            scanned_classes: outcome.scanned_classes,
            broken: run_violations(merged, &outcome.names).count(),
            unknown_refs: outcome.unknown_refs,
        })
        .collect()
}

fn module_summary(
    plan: &ModulePlan,
    outcomes: Vec<report::ModuleOutcome>,
) -> report::ModuleRunSummary {
    report::ModuleRunSummary {
        outcomes,
        total_modules: plan.total_modules,
        unchanged_modules: plan.unchanged_modules,
        new_modules: plan.new_modules,
        incomplete_modules: plan.incomplete_modules,
    }
}

fn cmd_dump(path: &Path) -> Result<i32> {
    let classes = input::load(path)?;
    let mut parse_errors = 0usize;
    let mut name_mismatches = 0usize;
    for lc in &classes {
        let api =
            match classfile::RawClass::parse(&lc.bytes).and_then(|rc| extract::extract_api(&rc)) {
                Ok(api) => api,
                Err(e) => {
                    parse_errors += 1;
                    eprintln!("warning: {}!{}: {e}", lc.source, lc.entry_name);
                    continue;
                }
            };
        // Also verifies the constant pool index convention: this_class should match the entry name.
        if lc.entry_name.trim_end_matches(".class") != api.name.as_str() {
            name_mismatches += 1;
            eprintln!(
                "warning: entry {} but this_class {}",
                lc.entry_name, api.name
            );
        }
        println!("class {} [{}]", api.name, flags_str(api.access));
        if let Some(s) = &api.super_name {
            println!("  extends {s}");
        }
        for i in &api.interfaces {
            println!("  implements {i}");
        }
        let mut methods: Vec<_> = api.methods.to_vec();
        methods.sort_by_key(|(key, _)| (key.name.as_str(), key.descriptor.as_str()));
        for (key, acc) in methods {
            println!(
                "  method {}.{} {} [{}]",
                api.name,
                key.name,
                key.descriptor,
                flags_str(acc)
            );
        }
        let mut fields: Vec<_> = api.fields.to_vec();
        fields.sort_by_key(|(key, _)| (key.name.as_str(), key.descriptor.as_str()));
        for (key, acc) in fields {
            println!(
                "  field {}.{} {} [{}]",
                api.name,
                key.name,
                key.descriptor,
                flags_str(acc)
            );
        }
    }
    eprintln!(
        "dumped {} classes ({} parse errors, {} name mismatches)",
        classes.len() - parse_errors,
        parse_errors,
        name_mismatches
    );
    Ok(0)
}

fn flags_str(access: u16) -> String {
    let visibility = if access & ACC_PUBLIC != 0 {
        "public"
    } else if access & ACC_PROTECTED != 0 {
        "protected"
    } else if access & ACC_PRIVATE != 0 {
        "private"
    } else {
        "package"
    };
    if access & ACC_STATIC != 0 {
        format!("{visibility} static")
    } else {
        visibility.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::{FailOn, should_fail};

    /// `matched` is the app-root match state: None = reachability off, Some(true)/Some(false)
    /// = on and whether any app root matched a scanned class.
    fn fail(reachables: &[Option<bool>], matched: Option<bool>, fail_on: FailOn) -> bool {
        should_fail(reachables.iter().copied(), matched, fail_on)
    }

    #[test]
    fn never_always_passes() {
        assert!(!fail(&[Some(true), Some(false), None], None, FailOn::Never));
        assert!(!fail(&[Some(false)], Some(false), FailOn::Never));
        assert!(!fail(&[], None, FailOn::Never));
    }

    #[test]
    fn any_fails_on_any_violation() {
        assert!(!fail(&[], None, FailOn::Any));
        assert!(fail(&[Some(false)], Some(true), FailOn::Any));
        assert!(fail(&[None], None, FailOn::Any));
        assert!(fail(&[Some(true)], Some(true), FailOn::Any));
    }

    #[test]
    fn reachable_fails_only_on_reachable_or_unknown() {
        assert!(!fail(&[], Some(true), FailOn::Reachable));
        // Proven not reachable does not fail (app roots matched).
        assert!(!fail(
            &[Some(false), Some(false)],
            Some(true),
            FailOn::Reachable
        ));
        // Proven reachable fails.
        assert!(fail(
            &[Some(false), Some(true)],
            Some(true),
            FailOn::Reachable
        ));
        // Reachability not computed (no app roots) degrades to Any.
        assert!(fail(&[None], None, FailOn::Reachable));
    }

    #[test]
    fn reachable_fails_when_app_roots_supplied_but_unmatched() {
        // App roots given but none matched a scanned class: every violation is Some(false),
        // but the labels have no basis, so Reachable degrades to Any and fails.
        assert!(fail(
            &[Some(false), Some(false)],
            Some(false),
            FailOn::Reachable
        ));
        // Still nothing to fail on with zero violations.
        assert!(!fail(&[], Some(false), FailOn::Reachable));
    }
}
