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
fn finish_verdicts(
    writer: Option<verdicts::VerdictWriter>,
    result: Result<check::CheckReport>,
) -> Result<check::CheckReport> {
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
/// `exclude_rules` is applied last, after verdicts and reachability, so it is the single place
/// that decides which known false positives to drop (mirrors reachability's "one policy site").
pub fn run_check(
    old: &[PathBuf],
    new: &[PathBuf],
    targets: &[PathBuf],
    app_roots: &[PathBuf],
    exclude_rules: &[exclude::ExcludeRule],
    jdk: Option<&mut jdk::JdkIndexer>,
    verdicts: Option<&mut verdicts::VerdictWriter>,
) -> Result<check::CheckReport> {
    let reachability = !app_roots.is_empty();
    memstats::report("start");
    let old_index = build_index_multi(old)?;
    let new_index = build_index_multi(new)?;
    memstats::report("after old/new index build");

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
    let scanned = check::scan_target_paths(&paths, &old_index, reachability)?;
    memstats::report("after scan target indexing");
    let mut result = check::check_scanned(
        scanned,
        &old_index,
        &new_index,
        &upgraded_sources,
        jdk,
        reach,
        verdicts,
    );
    let stats = exclude::filter(&mut result.violations, exclude_rules);
    result.suppressed = stats.suppressed;
    result.warnings.extend(
        stats
            .unused
            .into_iter()
            .map(|u| format!("exclude rule matched nothing: {u}")),
    );
    warn_all(&result.warnings);
    Ok(result)
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

    let per_module = !merged && has_module_data(&before_universe) && has_module_data(&after_universe);
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
        if json {
            println!("{}", report::upgrade_json(&changes.changes, None, None)?);
        } else {
            print!("{}", report::upgrade_text(&changes.changes, None, None));
        }
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
    if json {
        println!(
            "{}",
            report::upgrade_json(&changes.changes, Some(&result), None)?
        );
    } else {
        print!(
            "{}",
            report::upgrade_text(&changes.changes, Some(&result), None)
        );
    }
    Ok(exit_code(&result, fail_on))
}

/// Whether a dump can drive per-module checking: at least one module lists its own artifacts.
/// (v2 dumps written by current plugins always do; hand-written or pre-artifactRefs dumps may not.)
fn has_module_data(universe: &gradle::Universe) -> bool {
    universe.modules.iter().any(|m| !m.artifacts.is_empty())
}

/// One deduplicated per-module check run: modules whose (old, new, targets, roots) are
/// identical share a single run and its results.
struct ModuleRunPlan {
    names: Vec<String>,
    old_jars: Vec<PathBuf>,
    new_jars: Vec<PathBuf>,
    targets: Vec<PathBuf>,
    app_roots: Vec<PathBuf>,
}

struct ModulePlan {
    runs: Vec<ModuleRunPlan>,
    total_modules: usize,
    unchanged_modules: usize,
    /// Modules present only in the after dump: nothing to diff against, skipped.
    new_modules: usize,
}

/// Decide which modules need a check and with what inputs. A module is checked only when its
/// own resolution lost a version (same gate as the merged mode's old_jars, per module) — an
/// unchanged module cannot break from the upgrade and is skipped, which also keeps the cost
/// proportional to the change, not the repository.
fn plan_module_runs(before: &gradle::Universe, after: &gradle::Universe) -> ModulePlan {
    let mut runs: Vec<ModuleRunPlan> = Vec::new();
    let mut by_signature: std::collections::BTreeMap<
        (Vec<PathBuf>, Vec<PathBuf>, Vec<PathBuf>, Vec<PathBuf>),
        usize,
    > = std::collections::BTreeMap::new();
    let mut seen_names = std::collections::BTreeSet::new();
    let mut unchanged = 0usize;
    let mut new_modules = 0usize;
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
        let Some(before_module) = before.module(&module.name) else {
            new_modules += 1;
            continue;
        };
        let module_changes = gradle::diff_modules(before_module, module);
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
            let producer_dirs = artifact
                .project
                .as_deref()
                .and_then(|p| after.module(p))
                .map(|m| m.classes_dirs.clone())
                .unwrap_or_default();
            if producer_dirs.is_empty() {
                missing
                    .entry(artifact.file.clone())
                    .or_default()
                    .insert(module.name.clone());
            } else {
                substituted.insert(
                    artifact.file.clone(),
                    artifact.project.clone().unwrap_or_default(),
                );
                targets.extend(producer_dirs);
            }
        }

        let signature = (
            module_changes.old_jars.clone(),
            module_changes.new_jars.clone(),
            targets.clone(),
            module.classes_dirs.clone(),
        );
        match by_signature.get(&signature) {
            Some(&i) => runs[i].names.push(module.name.clone()),
            None => {
                by_signature.insert(signature, runs.len());
                runs.push(ModuleRunPlan {
                    names: vec![module.name.clone()],
                    old_jars: module_changes.old_jars,
                    new_jars: module_changes.new_jars,
                    targets,
                    app_roots: module.classes_dirs.clone(),
                });
            }
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
    }
}

/// Run one check per changed module (deduplicated), merge the results into one report with
/// per-violation module attribution, and apply exclude rules once to the merged set (the
/// single policy site, same as run_check's own application in the merged mode).
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

    if plan.runs.is_empty() {
        let summary = report::ModuleRunSummary {
            outcomes: Vec::new(),
            total_modules: plan.total_modules,
            unchanged_modules: plan.unchanged_modules,
            new_modules: plan.new_modules,
        };
        if json {
            println!(
                "{}",
                report::upgrade_json(&changes.changes, None, Some(&summary))?
            );
        } else {
            print!(
                "{}",
                report::upgrade_text(&changes.changes, None, Some(&summary))
            );
        }
        return Ok(0);
    }

    let mut verdict_writer = verdicts_json
        .map(verdicts::VerdictWriter::create)
        .transpose()?;

    let mut merged = check::CheckReport {
        violations: Vec::new(),
        warnings: Vec::new(),
        scanned_classes: 0,
        unknown_refs: 0,
        suppressed: 0,
        reachability_computed: false,
        app_roots_matched: None,
    };
    let mut run_outcomes: Vec<(Vec<String>, usize, usize)> = Vec::new();
    // Violation identity for cross-module merging: everything the report prints except the
    // module list (the reference is compared by its serialized form; SymbolRef has no Ord).
    let mut merged_index: std::collections::BTreeMap<
        (String, String, String, String, Option<bool>),
        usize,
    > = std::collections::BTreeMap::new();

    let mut loop_result: Result<()> = Ok(());
    for run in &plan.runs {
        if let Some(w) = verdict_writer.as_mut() {
            w.set_module(Some(run.names.join(",")));
        }
        // Exclude rules are deliberately NOT applied per run: they filter the merged,
        // deduplicated set below, so counts and unused-rule warnings appear once.
        let result = run_check(
            &run.old_jars,
            &run.new_jars,
            &run.targets,
            &run.app_roots,
            &[],
            jdk_indexer.as_deref_mut(),
            verdict_writer.as_mut(),
        );
        let result = match result {
            Ok(r) => r,
            Err(e) => {
                loop_result = Err(e);
                break;
            }
        };
        merged.scanned_classes += result.scanned_classes;
        merged.unknown_refs += result.unknown_refs;
        merged.reachability_computed |= result.reachability_computed;
        // Some(false) (roots supplied, none matched) wins: it degrades --fail-on
        // reachable to any, the conservative direction for a partially built repo.
        merged.app_roots_matched = match (merged.app_roots_matched, result.app_roots_matched) {
            (Some(false), _) | (_, Some(false)) => Some(false),
            (Some(true), _) | (_, Some(true)) => Some(true),
            (None, None) => None,
        };
        run_outcomes.push((
            run.names.clone(),
            result.scanned_classes,
            result.unknown_refs,
        ));
        for mut v in result.violations {
            let key = (
                v.source.as_str().to_string(),
                v.source_class.as_str().to_string(),
                serde_json::to_string(&v.reference)?,
                v.reason.clone(),
                v.reachable,
            );
            match merged_index.get(&key) {
                Some(&i) => merged.violations[i].modules.extend(run.names.iter().cloned()),
                None => {
                    merged_index.insert(key, merged.violations.len());
                    v.modules = run.names.clone();
                    merged.violations.push(v);
                }
            }
        }
    }
    let mut merged = finish_verdicts(verdict_writer, loop_result.map(|()| merged))?;

    // Canonical cross-run order: the same string-value sort check_scanned applies per run,
    // with the module list as the tiebreak between entries merged from different runs.
    for v in &mut merged.violations {
        v.modules.sort();
        v.modules.dedup();
    }
    merged.violations.sort_by_cached_key(|v| {
        (
            v.source.as_str(),
            v.source_class.as_str(),
            v.reference.owner.as_str(),
            v.reference
                .member
                .map(|m| (m.name.as_str(), m.descriptor.as_str())),
            v.reason.clone(),
            v.modules.clone(),
        )
    });

    let stats = exclude::filter(&mut merged.violations, exclude_rules);
    merged.suppressed = stats.suppressed;
    for unused in stats.unused {
        eprintln!("warning: exclude rule matched nothing: {unused}");
    }

    suggest::annotate(
        &mut merged.violations,
        before_universe,
        after_universe,
        &changes.changes,
    );

    // Per-run outcome lines, with broken counts taken from the merged post-exclusion set so
    // the numbers agree with the violation listing.
    let outcomes: Vec<report::ModuleOutcome> = run_outcomes
        .into_iter()
        .map(|(names, scanned, unknown)| {
            let broken = merged
                .violations
                .iter()
                .filter(|v| v.modules.iter().any(|m| names.contains(m)))
                .count();
            report::ModuleOutcome {
                modules: names,
                scanned_classes: scanned,
                broken,
                unknown_refs: unknown,
            }
        })
        .collect();
    let summary = report::ModuleRunSummary {
        outcomes,
        total_modules: plan.total_modules,
        unchanged_modules: plan.unchanged_modules,
        new_modules: plan.new_modules,
    };

    if json {
        println!(
            "{}",
            report::upgrade_json(&changes.changes, Some(&merged), Some(&summary))?
        );
    } else {
        print!(
            "{}",
            report::upgrade_text(&changes.changes, Some(&merged), Some(&summary))
        );
    }
    Ok(exit_code(&merged, fail_on))
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
