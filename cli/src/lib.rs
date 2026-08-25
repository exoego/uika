pub mod check;
pub mod classfile;
pub mod cli;
pub mod diff;
pub mod evidence;
pub mod exclude;
pub mod extract;
pub mod gradle;
pub mod index;
pub mod input;
pub mod intern;
pub mod jdk;
pub mod memstats;
pub mod model;
pub mod pom;
pub mod reach;
pub mod report;
pub mod suggest;
pub mod verdicts;
pub mod window;

use anyhow::{Result, bail};
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
            jdk_release_old,
            jdk_release_new,
            verdicts_json,
            class_load_log,
            draft_exclude_file,
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
                jdk_release_old.zip(jdk_release_new),
                verdicts_json.as_deref(),
                &class_load_log,
                draft_exclude_file.as_deref(),
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
            class_load_log,
            draft_exclude_file,
        } => cmd_upgrade_check(
            &before,
            &after,
            &exclude_file,
            json,
            fail_on,
            jdk_release,
            verdicts_json.as_deref(),
            merged,
            &class_load_log,
            draft_exclude_file.as_deref(),
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
    jdk_pair: Option<(u32, u32)>,
    verdicts_json: Option<&Path>,
    class_load_log: &[PathBuf],
    draft_exclude_file: Option<&Path>,
) -> Result<i32> {
    let exclude_rules = exclude::load(exclude_file)?;
    let load_evidence = load_evidence(class_load_log, draft_exclude_file, exclude_file)?;
    let mut jdk_indexer = jdk::indexer_for(jdk_release)?;
    let mut verdict_writer = verdicts_json
        .map(verdicts::VerdictWriter::create)
        .transpose()?;
    let jdk_pair_indexes = jdk_pair.map(jdk_release_pair).transpose()?;
    let result = match &jdk_pair_indexes {
        // The JDK upgrade IS the pair, and clap rejects --old/--new alongside it, so there
        // is no jar to exclude as stale and none to sweep for invocation evidence. Checking
        // a library pair and a JDK pair at once would be two runs, which is what
        // upgrade-check does from the dumps.
        Some((old_index, new_index)) => run_check_with_indexes(
            old_index,
            new_index,
            &[],
            &[],
            targets,
            app_roots,
            &exclude_rules,
            jdk_indexer.as_mut(),
            verdict_writer.as_mut(),
        ),
        None => run_check(
            old,
            new,
            targets,
            app_roots,
            &exclude_rules,
            jdk_indexer.as_mut(),
            verdict_writer.as_mut(),
        ),
    };
    let mut result = finish_verdicts(verdict_writer, result)?;
    apply_evidence_and_draft(
        &mut result.violations,
        result.app_roots_matched,
        load_evidence.as_ref(),
        draft_exclude_file,
    )?;
    if json {
        println!("{}", report::check_json(&result)?);
    } else {
        // scan_targets, not targets.len(): the header must agree with what was actually
        // scanned after missing-path skips, stale-old-version exclusion, and dedup.
        match jdk_pair {
            Some((o, n)) => print!("{}", report::check_header_jdk(o, n, result.scan_targets)),
            None => print!("{}", report::check_header(old, new, result.scan_targets)),
        }
        print!("{}", report::check_text(&result));
    }
    Ok(exit_code(&result, fail_on))
}

/// Build both sides of a JDK-pair check. Warns when the old side comes from jmods while
/// the new side comes from ct.sym: jmods also holds unexported internals, so as the older
/// side it would report every one of them as removed.
fn jdk_release_pair((old, new): (u32, u32)) -> Result<(ApiIndex, ApiIndex)> {
    if jdk::is_installed_release(old) && !jdk::is_installed_release(new) {
        eprintln!(
            "warning: --jdk-release-old {old} is this JDK's own release, read from jmods, \
             which also holds unexported internals; against a ct.sym new side those look removed"
        );
    }
    let (old_index, old_warnings) = jdk::release_index(old)?;
    let (new_index, new_warnings) = jdk::release_index(new)?;
    for w in old_warnings.iter().chain(&new_warnings) {
        eprintln!("warning: {w}");
    }
    Ok((old_index, new_index))
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

/// Load runtime class-load logs (--class-load-log) and note the ingest, so a user can see
/// their artifact was actually read. None when the flag was not given. A requested draft
/// file is truncated to a placeholder FIRST, mirroring --verdicts-json's create-upfront
/// contract: a run that errors anywhere after this point can never leave a stale draft
/// from an earlier run behind for a human to review.
fn load_evidence(
    paths: &[PathBuf],
    draft: Option<&Path>,
    exclude_file: &[PathBuf],
) -> Result<Option<evidence::LoadEvidence>> {
    if let Some(path) = draft {
        // Before the placeholder truncates it. "Regenerate it in place" is the natural
        // thing to try, and it drops every rule that was still suppressing something.
        if let Some(clash) = aliases_exclude_file(path, exclude_file) {
            bail!(
                "--draft-exclude-file {} is also an --exclude-file; drafting would \
                 overwrite it with only the drafted rules. Draft to a new path and merge.",
                clash.display()
            );
        }
        evidence::create_draft_placeholder(path)?;
    }
    if paths.is_empty() {
        return Ok(None);
    }
    let ev = evidence::load(paths)?;
    eprintln!(
        "note: runtime load evidence: {} distinct classes from {}",
        ev.distinct_classes(),
        ev.sources()
    );
    Ok(Some(ev))
}

/// The --exclude-file entry the draft path resolves to, if any. Canonicalized for
/// spelling and symlinks; hard links are caught by content in create_draft_placeholder.
fn aliases_exclude_file(draft: &Path, exclude_file: &[PathBuf]) -> Option<PathBuf> {
    let draft = draft.canonicalize().ok()?;
    exclude_file
        .iter()
        .find(|path| path.canonicalize().is_ok_and(|resolved| resolved == draft))
        .cloned()
}

/// The one evidence site per command: promote observed violations on the FINAL set (after
/// per-module merging and exclusion, before printing and the exit decision, so promotion
/// reaches both the report and the gate), then write the requested draft exclude file.
/// run_check stays untouched, which keeps the goldens and the verdicts stream stable.
/// Quiet paths (no changes, empty module plan) call this with an empty slice so the
/// requested draft file is written on every completed run, never silently absent.
///
/// `app_roots_matched` gates DRAFTING only (promotion needs no axis): Some(false) means
/// some run's roots matched nothing, so its `reachable = Some(false)` values carry no
/// evidence and nothing may be drafted from them.
fn apply_evidence_and_draft(
    violations: &mut [model::Violation],
    app_roots_matched: Option<bool>,
    evidence: Option<&evidence::LoadEvidence>,
    draft: Option<&Path>,
) -> Result<()> {
    let Some(ev) = evidence else { return Ok(()) };
    evidence::apply(violations, ev);
    if let Some(path) = draft {
        // Zero observed classes is a broken pipeline (artifact never downloaded, fork
        // never happened, directory holds only the recordings this binary skips), not
        // proof that nothing loaded. Drafting from it reads like a well-evidenced run.
        if ev.distinct_classes() == 0 {
            bail!(
                "refusing to draft from {}: no class loads were observed at all, so every \
                 violation would be drafted as never-loaded. Check that the evidence was \
                 produced and, for the Clojure frontends, that it is text and not JFR.",
                ev.sources()
            );
        }
        let drafted = evidence::draft_excludes(violations, app_roots_matched, ev, path)?;
        eprintln!(
            "note: drafted {drafted} exclude rule(s) to {}",
            path.display()
        );
    }
    Ok(())
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
    if should_fail(result.violations.iter(), result.app_roots_matched, fail_on) {
        1
    } else {
        0
    }
}

/// Exit policy, purely a threshold on `model::tier` — the same call the report's section
/// split makes, with the same `app_roots_matched`, so the gate always agrees with what the
/// report shows. Keep it that way: reasoning about violation fields here instead of about
/// the tier is how the two drift apart. Both degraded cases are handled inside `tier`.
fn should_fail<'a>(
    mut violations: impl Iterator<Item = &'a model::Violation>,
    app_roots_matched: Option<bool>,
    fail_on: FailOn,
) -> bool {
    match fail_on {
        FailOn::Never => false,
        FailOn::Reachable => {
            let axis = model::reachable_axis_valid(app_roots_matched);
            violations.any(|v| model::tier(v, axis) == model::Tier::Breaks)
        }
        FailOn::Any => violations.next().is_some(),
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
    let mut scanned = check::scan_target_paths(&paths, old_index, new_index, reachability)?;
    // The new library's own bytecode counts as evidence too, scan target or not.
    scanned.extend_invocations(check::library_invocation_evidence(
        new, old_index, new_index,
    ));
    memstats::report("after scan target indexing");
    // The compared libraries' own META-INF/services files, for the SPI provider check.
    // Always read, unlike the reachability-gated call above: old/new are the small library
    // JAR set, not the classpath.
    let (old_services, old_service_warnings) = reach::collect_services(old);
    let (new_services, new_service_warnings) = reach::collect_services(new);
    warn_all(&old_service_warnings);
    warn_all(&new_service_warnings);
    let mut result = check::check_scanned(
        scanned,
        old_index,
        new_index,
        &upgraded_sources,
        jdk,
        reach,
        &check::SpiServices {
            old: &old_services,
            new: &new_services,
        },
        verdicts,
    );
    result.scan_targets = paths.len();
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
    class_load_log: &[PathBuf],
    draft_exclude_file: Option<&Path>,
) -> Result<i32> {
    let exclude_rules = exclude::load(exclude_file)?;
    // Loaded before the no-changes early return for the same reason as jdk_indexer below:
    // a bad log path must fail on every run, not only when jars changed.
    let load_evidence = load_evidence(class_load_log, draft_exclude_file, exclude_file)?;
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
            load_evidence.as_ref(),
            draft_exclude_file,
        );
    }
    if !merged {
        eprintln!(
            "warning: the dump carries no per-module classpaths; checking the merged universe \
             (regenerate the dumps with a current uika plugin for per-module checking)"
        );
    }

    let jdk_pair = jdk_change(&before_universe, &after_universe);
    if changes.old_jars.is_empty() && jdk_pair.is_none() {
        // The empty slice still writes the requested draft file, like --verdicts-json,
        // so a script reading it never breaks on a quiet run.
        apply_evidence_and_draft(&mut [], None, load_evidence.as_ref(), draft_exclude_file)?;
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
    // The JDK moved too (or only the JDK did), so its removals are checked over the same
    // universe and folded in. Excludes were already applied to the dependency half, so this
    // half gets them here rather than a second time over the whole set.
    if let Some(pair) = jdk_pair {
        let (old_index, new_index) = jdk_release_pair(pair)?;
        let mut jdk_result = run_check_with_indexes(
            &old_index,
            &new_index,
            &[],
            &[],
            &after_universe.scan_targets,
            &after_universe.app_roots,
            &exclude_rules,
            None,
            None,
        )?;
        result.violations.append(&mut jdk_result.violations);
        result.warnings.append(&mut jdk_result.warnings);
        result.suppressed += jdk_result.suppressed;
        result.unknown_refs += jdk_result.unknown_refs;
    }
    // Attribute each break to the artifacts involved and propose a fix (coordinates only exist
    // for upgrade-check, so this lives here rather than in the shared run_check).
    suggest::annotate(
        &mut result.violations,
        &before_universe,
        &after_universe,
        &changes.changes,
    );
    apply_evidence_and_draft(
        &mut result.violations,
        result.app_roots_matched,
        load_evidence.as_ref(),
        draft_exclude_file,
    )?;
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
    /// This run compares JDK releases rather than JARs, so its indexes come from
    /// `jdk::release_index` and its jar lists stay empty. One run per distinct move: the
    /// modules of a build can name different releases, and the modules that share a move
    /// share its run over the union of their targets.
    jdk_pair: Option<(u32, u32)>,
    /// For a JDK run, the modules that made the move. `names` cannot hold them (it is the
    /// attribution key), so the report takes the real names from here.
    jdk_modules: Vec<String>,
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
/// proportional to the change, not the repository. A module whose JDK release moved is
/// checked for that too, on the same principle: only the modules that moved, and only
/// against the move they made.
fn plan_module_runs(before: &gradle::Universe, after: &gradle::Universe) -> ModulePlan {
    let mut notes = TargetNotes::default();
    let mut plan = plan_dependency_runs(before, after, &mut notes);
    plan_jdk_runs(before, after, &mut plan, &mut notes);
    notes.report();
    plan
}

/// Add one run per distinct JDK move, over the union of the targets of the modules that made
/// it. Per module rather than once for the whole universe: the dump records the release each
/// module compiles for, and a build is free to mix them, so a module still on 11 must not be
/// checked against the 17 -> 21 move its sibling made. A build where every module moved
/// together — the common case — still plans exactly one run, just with the same targets the
/// universe-wide run used.
fn plan_jdk_runs(
    before: &gradle::Universe,
    after: &gradle::Universe,
    plan: &mut ModulePlan,
    notes: &mut TargetNotes,
) {
    // Grouped before any run is built so each pair's targets are deduplicated across its
    // modules in one pass; sorted, so the run order does not depend on dump order.
    let mut moves: std::collections::BTreeMap<(u32, u32), Vec<&gradle::ModuleUniverse>> =
        std::collections::BTreeMap::new();
    let mut seen_names = std::collections::BTreeSet::new();
    for module in &after.modules {
        // Duplicate names are warned about (and skipped) by the dependency planner already.
        if !seen_names.insert(module.name.clone()) {
            continue;
        }
        // A module missing from the before dump has no release to compare with, the same
        // both-sides rule that keeps a dump predating the field from inventing a move.
        let Some(before_module) = before.module(&module.name) else {
            continue;
        };
        if let Some(pair) = release_change(before_module.jdk_release, module.jdk_release) {
            moves.entry(pair).or_default().push(module);
        }
    }

    for (pair, modules) in moves {
        let mut seen = std::collections::BTreeSet::new();
        let mut targets = Vec::new();
        let mut app_roots = Vec::new();
        let names: Vec<String> = modules.iter().map(|m| m.name.clone()).collect();
        for module in modules {
            // First-seen order, as in a single module's list: the JVM resolves a duplicated
            // class from the first classpath entry that carries it, and a scan that listed
            // the same jar twice would also inflate the run's scanned-class count.
            for target in module_targets(module, after, notes) {
                if seen.insert(target.clone()) {
                    targets.push(target);
                }
            }
            for dir in &module.classes_dirs {
                if !app_roots.contains(dir) {
                    app_roots.push(dir.clone());
                }
            }
        }
        plan.runs.push(ModuleRunPlan {
            // The pair, not the module names: this pseudo-name is also the key each
            // violation is attributed by, and reusing the real names would fold the
            // modules' dependency runs into this run's broken count.
            names: vec![format!("JDK {} -> {}", pair.0, pair.1)],
            changes: Vec::new(),
            old_jars: Vec::new(),
            new_jars: Vec::new(),
            targets,
            app_roots,
            jdk_pair: Some(pair),
            jdk_modules: names,
        });
    }
}

/// The JDK releases to compare, when both dumps recorded one and they differ. A dump
/// written before the plugins recorded it reads `None`, which is why an old before-dump
/// never manufactures a JDK change against a fresh after-dump.
fn jdk_change(before: &gradle::Universe, after: &gradle::Universe) -> Option<(u32, u32)> {
    release_change(before.jdk_release, after.jdk_release)
}

/// Same rule for a dump-level pair (merged mode) and a per-module pair.
fn release_change(before: Option<u32>, after: Option<u32>) -> Option<(u32, u32)> {
    let (b, a) = (before?, after?);
    (b != a).then_some((b, a))
}

/// Scan-target bookkeeping shared by both planners, reported once after planning rather
/// than once per module that hit the same file.
#[derive(Default)]
struct TargetNotes {
    /// file -> modules that needed it.
    missing: std::collections::BTreeMap<PathBuf, std::collections::BTreeSet<String>>,
    substituted: std::collections::BTreeMap<PathBuf, String>,
}

impl TargetNotes {
    fn report(&self) {
        for (file, project) in &self.substituted {
            eprintln!(
                "note: {} is not built; scanning module {}'s classesDirs instead",
                file.display(),
                project
            );
        }
        for (file, modules) in &self.missing {
            eprintln!(
                "warning: scan target not found, skipping: {} (needed by {})",
                file.display(),
                modules.iter().cloned().collect::<Vec<_>>().join(", ")
            );
        }
    }
}

/// One module's scan targets: its own outputs first (JVM order: application classes precede
/// dependencies), then the resolved classpath in resolution order. A project-dependency
/// artifact that was never built falls back to the producing module's classesDirs from the
/// same dump.
fn module_targets(
    module: &gradle::ModuleUniverse,
    after: &gradle::Universe,
    notes: &mut TargetNotes,
) -> Vec<PathBuf> {
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
                notes
                    .substituted
                    .insert(artifact.file.clone(), producer.name.clone());
                targets.extend(producer.classes_dirs.iter().cloned());
            }
            None => {
                notes
                    .missing
                    .entry(artifact.file.clone())
                    .or_default()
                    .insert(module.name.clone());
            }
        }
    }
    targets
}

fn plan_dependency_runs(
    before: &gradle::Universe,
    after: &gradle::Universe,
    notes: &mut TargetNotes,
) -> ModulePlan {
    let project_coords = gradle::project_coords_union(before, after);
    let mut runs: Vec<ModuleRunPlan> = Vec::new();
    let mut seen_names = std::collections::BTreeSet::new();
    let mut unchanged = 0usize;
    let mut new_modules = 0usize;
    let mut incomplete_modules = 0usize;

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
                // failed resolution), not an upgrade that removed every dependency; diffing
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

        let targets = module_targets(module, after, notes);

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
                jdk_pair: None,
                jdk_modules: Vec::new(),
            }),
        }
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
    jdk: bool,
    jdk_modules: Vec<String>,
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
    load_evidence: Option<&evidence::LoadEvidence>,
    draft_exclude_file: Option<&Path>,
) -> Result<i32> {
    let plan = plan_module_runs(before_universe, after_universe);
    // Created before the empty-plan return so a requested --verdicts-json file always
    // exists (empty when nothing was checked), never silently absent.
    let mut verdict_writer = verdicts_json
        .map(verdicts::VerdictWriter::create)
        .transpose()?;

    if plan.runs.is_empty() {
        finish_verdicts(verdict_writer, Ok(()))?;
        apply_evidence_and_draft(&mut [], None, load_evidence, draft_exclude_file)?;
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
            scan_targets: 0,
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
                model::Reason,
                Option<String>,
            ),
            usize,
        > = rustc_hash::FxHashMap::default();

        for run in &plan.runs {
            if let Some(w) = verdict_writer.as_mut() {
                w.set_module(Some(run.names.join(",")));
            }
            // A JDK run's pair comes from ct.sym/jmods, not from jars, so it bypasses the
            // per-side jar-index caches. One build per run, which is also one per distinct
            // move: two runs sharing a pair are merged at planning time.
            let jdk_indexes = match run.jdk_pair {
                Some(pair) => Some(jdk_release_pair(pair)?),
                None => None,
            };
            let (old_index, new_index) = match &jdk_indexes {
                Some((o, n)) => (o, n),
                None => (
                    cached_index(&mut old_indexes, &run.old_jars)?,
                    cached_index(&mut new_indexes, &run.new_jars)?,
                ),
            };
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
                jdk: run.jdk_pair.is_some(),
                jdk_modules: run.jdk_modules.clone(),
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
                    v.reason,
                    v.suggestion.as_ref().map(|s| s.advice.clone()),
                );
                match merged_index.get(&key) {
                    Some(&i) => {
                        let existing = &mut merged.violations[i];
                        existing.modules.extend(run.names.iter().cloned());
                        if reachable_rank(v.reachable) > reachable_rank(existing.reachable) {
                            existing.reachable = v.reachable;
                        }
                        // Most-dangerous-wins, like reachable: runs scan different
                        // classpaths, and one invocation is enough.
                        if v.invocation_found == Some(true) {
                            existing.invocation_found = Some(true);
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
    // After merging and exclusion, before the per-run exit decisions below, so an
    // observed load promotes a violation for the gate too. Drafting gets the
    // Some(false)-dominating fold of the per-run roots states, NOT merged.app_roots_matched
    // (which stays None): one module whose roots matched nothing stamps meaningless
    // reachable = Some(false) on its violations, and the per-run gate below treats those
    // as Breaks — drafting them as provably-unreachable would propose waiving the very
    // violations the gate fails on.
    let draft_axis = run_outcomes
        .iter()
        .fold(None, |acc, o| match (acc, o.app_roots_matched) {
            (Some(false), _) | (_, Some(false)) => Some(false),
            (Some(true), _) | (_, Some(true)) => Some(true),
            _ => None,
        });
    apply_evidence_and_draft(
        &mut merged.violations,
        draft_axis,
        load_evidence,
        draft_exclude_file,
    )?;

    // Fail per run with its own roots state: a module whose classesDirs matched nothing
    // degrades --fail-on reachable to any for ITS violations only (module attribution
    // reconstructs each run's post-exclusion set exactly).
    let failed = run_outcomes.iter().any(|outcome| {
        should_fail(
            run_violations(&merged, &outcome.names),
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
            jdk: outcome.jdk,
            jdk_modules: outcome.jdk_modules.clone(),
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
    use super::{FailOn, jdk_change, plan_module_runs, should_fail};
    use crate::intern::intern;
    use crate::model::{Reason, RefKind, SymbolRef, Violation};

    fn universe(jdk_release: Option<u32>) -> crate::gradle::Universe {
        crate::gradle::Universe {
            scan_targets: Vec::new(),
            app_roots: Vec::new(),
            versions: Default::default(),
            project_coords: Default::default(),
            modules: Vec::new(),
            jdk_release,
        }
    }

    /// A dump written before the plugins recorded the release reads None, and one missing
    /// side must never look like a JDK move: an old before-dump would then check every
    /// upgrade against a JDK pair the user never changed.
    #[test]
    fn a_jdk_pair_needs_both_dumps_to_name_a_different_release() {
        assert_eq!(
            jdk_change(&universe(Some(17)), &universe(Some(21))),
            Some((17, 21))
        );
        assert_eq!(jdk_change(&universe(Some(17)), &universe(Some(17))), None);
        assert_eq!(jdk_change(&universe(None), &universe(Some(21))), None);
        assert_eq!(jdk_change(&universe(Some(17)), &universe(None)), None);
        assert_eq!(jdk_change(&universe(None), &universe(None)), None);
    }

    fn module(name: &str, jdk_release: Option<u32>) -> crate::gradle::ModuleUniverse {
        module_with_dirs(name, jdk_release, &[&format!("/build{name}/classes")])
    }

    fn module_with_dirs(
        name: &str,
        jdk_release: Option<u32>,
        dirs: &[&str],
    ) -> crate::gradle::ModuleUniverse {
        crate::gradle::ModuleUniverse {
            name: name.to_string(),
            classes_dirs: dirs.iter().map(std::path::PathBuf::from).collect(),
            artifacts: Vec::new(),
            jdk_release,
        }
    }

    fn modular(modules: Vec<crate::gradle::ModuleUniverse>) -> crate::gradle::Universe {
        crate::gradle::Universe {
            modules,
            ..universe(None)
        }
    }

    /// The pair and the targets of every planned JDK run, which is what decides both what is
    /// compared and which code it is compared over.
    fn jdk_runs(
        before: &crate::gradle::Universe,
        after: &crate::gradle::Universe,
    ) -> Vec<((u32, u32), Vec<String>)> {
        plan_module_runs(before, after)
            .runs
            .iter()
            .filter_map(|run| {
                Some((
                    run.jdk_pair?,
                    run.targets
                        .iter()
                        .map(|t| t.display().to_string())
                        .collect(),
                ))
            })
            .collect()
    }

    /// A module that stayed on its release must not be scanned against a move its sibling
    /// made: the JDK 17 removals a `:app` upgrade has to answer for are not breakage in a
    /// `:legacy` still compiled for 8.
    #[test]
    fn a_jdk_move_is_scoped_to_the_modules_that_made_it() {
        let before = modular(vec![module(":app", Some(11)), module(":legacy", Some(8))]);
        let after = modular(vec![module(":app", Some(17)), module(":legacy", Some(8))]);
        assert_eq!(
            jdk_runs(&before, &after),
            vec![((11, 17), vec!["/build:app/classes".to_string()])]
        );
    }

    /// Modules moving together share one run over the union of their targets, so the common
    /// build — every module on one release — still plans exactly one JDK run.
    #[test]
    fn modules_sharing_a_move_share_one_run() {
        let before = modular(vec![module(":app", Some(11)), module(":web", Some(11))]);
        let after = modular(vec![module(":app", Some(17)), module(":web", Some(17))]);
        assert_eq!(
            jdk_runs(&before, &after),
            vec![(
                (11, 17),
                vec![
                    "/build:app/classes".to_string(),
                    "/build:web/classes".to_string()
                ]
            )]
        );
    }

    /// Merging two modules into one run must not list a shared target twice: the JVM
    /// resolves a duplicated class from the first entry that carries it, and the second copy
    /// would only inflate the run's scanned-class count.
    #[test]
    fn a_target_two_modules_share_is_scanned_once() {
        let dirs = ["/build/shared", "/build/own"];
        let before = vec![
            module_with_dirs(":app", Some(11), &dirs),
            module_with_dirs(":web", Some(11), &dirs),
        ];
        let after = vec![
            module_with_dirs(":app", Some(17), &dirs),
            module_with_dirs(":web", Some(17), &dirs),
        ];
        assert_eq!(
            jdk_runs(&modular(before), &modular(after)),
            vec![(
                (11, 17),
                vec!["/build/shared".to_string(), "/build/own".to_string()]
            )]
        );
    }

    /// Different moves are different comparisons and cannot share an index, so they get a
    /// run each.
    #[test]
    fn distinct_moves_get_a_run_each() {
        let before = modular(vec![module(":app", Some(11)), module(":web", Some(17))]);
        let after = modular(vec![module(":app", Some(17)), module(":web", Some(21))]);
        assert_eq!(
            jdk_runs(&before, &after)
                .into_iter()
                .map(|(pair, _)| pair)
                .collect::<Vec<_>>(),
            vec![(11, 17), (17, 21)]
        );
    }

    /// The both-sides rule, per module: a module the before dump does not have (renamed or
    /// new) has no release to compare with, and one that predates the field reads None.
    #[test]
    fn a_module_missing_a_release_on_either_side_plans_no_jdk_run() {
        let before = modular(vec![module(":app", Some(11)), module(":gone", Some(11))]);
        let after = modular(vec![module(":new", Some(17)), module(":app", None)]);
        assert_eq!(jdk_runs(&before, &after), Vec::new());
    }

    fn violation(reachable: Option<bool>, invocation_found: Option<bool>) -> Violation {
        Violation {
            source: intern("consumer.jar"),
            source_class: intern("app/Use"),
            reference: SymbolRef {
                kind: RefKind::Class,
                owner: intern("lib/C"),
                member: None,
                expected_static: None,
                field_write: None,
                instantiated: None,
            },
            // The invocation axis only exists on `method became abstract` violations.
            reason: if invocation_found.is_some() {
                Reason::MethodBecameAbstract
            } else {
                Reason::ClassRemoved
            },
            reachable,
            invocation_found,
            observed_loading: false,
            load_trigger: None,
            suggestion: None,
            modules: Vec::new(),
        }
    }

    /// `matched` is the app-root match state: None = reachability off, Some(true)/Some(false)
    /// = on and whether any app root matched a scanned class.
    fn fail(reachables: &[Option<bool>], matched: Option<bool>, fail_on: FailOn) -> bool {
        let violations: Vec<Violation> = reachables.iter().map(|&r| violation(r, None)).collect();
        should_fail(violations.iter(), matched, fail_on)
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

    #[test]
    fn reachable_does_not_fail_on_latent_violations() {
        // Reachable class, but no scanned invocation of the newly-abstract member: the
        // break cannot throw yet, so `reachable` treats it as a warning tier.
        let latent = violation(Some(true), Some(false));
        assert!(!should_fail(
            [&latent].into_iter(),
            Some(true),
            FailOn::Reachable
        ));
        // An invocation in scanned bytecode keeps it failing.
        let invoked = violation(Some(true), Some(true));
        assert!(should_fail(
            [&invoked].into_iter(),
            Some(true),
            FailOn::Reachable
        ));
        // `any` stays the strict escape hatch: latent still fails there.
        assert!(should_fail([&latent].into_iter(), Some(true), FailOn::Any));
    }

    #[test]
    fn latent_gating_holds_without_reachability_basis() {
        // Scan-derived, so it does not degrade with the reachable axis.
        let latent = violation(None, Some(false));
        assert!(!should_fail([&latent].into_iter(), None, FailOn::Reachable));
        let latent_unmatched = violation(Some(false), Some(false));
        assert!(!should_fail(
            [&latent_unmatched].into_iter(),
            Some(false),
            FailOn::Reachable
        ));
        // A non-latent violation alongside it still fails under the degraded mode.
        let plain = violation(Some(false), None);
        assert!(should_fail(
            [&latent_unmatched, &plain].into_iter(),
            Some(false),
            FailOn::Reachable
        ));
    }

    /// Runtime load evidence promotes only the reachable axis: an observed load lifts a
    /// proven-unreachable violation into the failing tier, while a latent one stays
    /// latent (loading proves nothing about invocation).
    #[test]
    fn observed_loading_promotes_only_the_reachable_axis() {
        let mut observed = violation(Some(false), None);
        observed.observed_loading = true;
        assert_eq!(
            crate::model::tier(&observed, true),
            crate::model::Tier::Breaks
        );
        assert!(should_fail(
            [&observed].into_iter(),
            Some(true),
            FailOn::Reachable
        ));

        let mut observed_latent = violation(Some(false), Some(false));
        observed_latent.observed_loading = true;
        assert_eq!(
            crate::model::tier(&observed_latent, true),
            crate::model::Tier::Latent
        );
        assert!(!should_fail(
            [&observed_latent].into_iter(),
            Some(true),
            FailOn::Reachable
        ));
    }

    /// A reader must be able to predict the exit code from the report, including when
    /// roots matched nothing and the reachable axis is dropped on both sides.
    #[test]
    fn gate_threshold_matches_the_reported_tier() {
        let observed = |reachable, invocation_found| {
            let mut v = violation(reachable, invocation_found);
            v.observed_loading = true;
            v
        };
        let cases = [
            (violation(Some(true), None), Some(true)),
            (violation(Some(false), None), Some(true)),
            (violation(Some(true), Some(false)), Some(true)),
            (violation(Some(false), Some(false)), Some(true)),
            // Roots supplied but unmatched: reachable is Some(false) with nothing behind it.
            (violation(Some(false), None), Some(false)),
            (violation(Some(false), Some(false)), Some(false)),
            // Reachability off entirely.
            (violation(None, None), None),
            (violation(None, Some(false)), None),
            // Runtime load evidence in every degraded state.
            (observed(Some(false), None), Some(true)),
            (observed(Some(false), Some(false)), Some(true)),
            (observed(Some(false), None), Some(false)),
            (observed(None, None), None),
        ];
        for (v, matched) in cases {
            let axis = crate::model::reachable_axis_valid(matched);
            let shown_as_breaks = crate::model::tier(&v, axis) == crate::model::Tier::Breaks;
            let fails = should_fail([&v].into_iter(), matched, FailOn::Reachable);
            assert_eq!(
                shown_as_breaks, fails,
                "tier and gate disagree for reachable={:?} invocation_found={:?} matched={matched:?}",
                v.reachable, v.invocation_found
            );
        }
    }

    /// `./x` and `x` are the same file, so the comparison canonicalizes.
    #[test]
    fn draft_path_aliasing_an_exclude_file_is_detected() {
        let dir = std::env::temp_dir().join(format!("uika-alias-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let rules = dir.join("keep.toml");
        std::fs::write(&rules, "").unwrap();
        let other = dir.join("draft.toml");
        std::fs::write(&other, "").unwrap();

        let excludes = vec![rules.clone()];
        assert_eq!(
            super::aliases_exclude_file(&rules, &excludes).map(|p| p.canonicalize().unwrap()),
            Some(rules.canonicalize().unwrap())
        );
        let spelled_differently = dir.join(".").join("keep.toml");
        assert!(super::aliases_exclude_file(&spelled_differently, &excludes).is_some());
        assert!(super::aliases_exclude_file(&other, &excludes).is_none());
        // A draft path that does not exist yet cannot be a file the loader just read.
        assert!(super::aliases_exclude_file(&dir.join("new.toml"), &excludes).is_none());

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The guard is on the evidence, not on the violation slice being empty.
    #[test]
    fn drafting_from_evidence_with_no_classes_is_refused() {
        let dir = std::env::temp_dir().join(format!("uika-noev-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let log = dir.join("empty.log");
        std::fs::write(&log, "nothing that parses as a class load\n").unwrap();
        let evidence = crate::evidence::load(std::slice::from_ref(&log)).unwrap();
        assert_eq!(evidence.distinct_classes(), 0);

        let draft = dir.join("draft.toml");
        let refused = super::apply_evidence_and_draft(&mut [], None, Some(&evidence), Some(&draft));
        let message = format!("{:#}", refused.unwrap_err());
        assert!(
            message.contains("no class loads were observed"),
            "{message}"
        );

        std::fs::write(&log, "[class,load] com.example.Loaded\n").unwrap();
        let evidence = crate::evidence::load(std::slice::from_ref(&log)).unwrap();
        assert_eq!(evidence.distinct_classes(), 1);
        super::apply_evidence_and_draft(&mut [], None, Some(&evidence), Some(&draft)).unwrap();

        let _ = std::fs::remove_dir_all(&dir);
    }
}
