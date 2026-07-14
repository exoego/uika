pub mod check;
pub mod classfile;
pub mod cli;
pub mod diff;
pub mod extract;
pub mod gradle;
pub mod index;
pub mod input;
pub mod intern;
pub mod memstats;
pub mod model;
pub mod reach;
pub mod report;
pub mod suggest;
pub mod window;

use anyhow::Result;
use cli::{Cli, Command};
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
            json,
        } => {
            let mut targets: Vec<PathBuf> = classpath;
            let mut app_roots: Vec<PathBuf> = app.clone();
            targets.extend(app);
            for dump in &classpath_file {
                let universe = gradle::load_dump(dump)?;
                app_roots.extend(universe.app_roots);
                targets.extend(universe.scan_targets);
            }
            cmd_check(&old, &new, &targets, &app_roots, json)
        }
        Command::UpgradeCheck {
            before,
            after,
            json,
        } => cmd_upgrade_check(&before, &after, json),
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

fn cmd_check(
    old: &[PathBuf],
    new: &[PathBuf],
    targets: &[PathBuf],
    app_roots: &[PathBuf],
    json: bool,
) -> Result<i32> {
    let result = run_check(old, new, targets, app_roots)?;
    if json {
        println!("{}", report::check_json(&result)?);
    } else {
        print!("{}", report::check_text(&result));
    }
    Ok(if result.violations.is_empty() { 0 } else { 1 })
}

/// Build old/new indexes, scan, then evaluate. Shared by upgrade-check and check.
/// Reachability ranking is only meaningful with application roots to walk from, so it turns
/// on exactly when they are present (--app or dump build outputs); when on, pass 1 also
/// collects class-load edges and each violation is tagged with whether its class is reachable.
pub fn run_check(
    old: &[PathBuf],
    new: &[PathBuf],
    targets: &[PathBuf],
    app_roots: &[PathBuf],
) -> Result<check::CheckReport> {
    let reachability = !app_roots.is_empty();
    memstats::report("start");
    let old_index = build_index_multi(old)?;
    let new_index = build_index_multi(new)?;
    memstats::report("after old/new index build");

    // Skip target libraries themselves if they are mixed into scan targets.
    // Missing paths (unbuilt output directories, etc.) are warned and skipped.
    let excluded: Vec<_> = old
        .iter()
        .chain(new.iter())
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
    let result = check::check_scanned(scanned, &old_index, &new_index, reach);
    warn_all(&result.warnings);
    Ok(result)
}

/// Compare before/after dependency dumps and check all changed artifacts at once.
fn cmd_upgrade_check(before: &Path, after: &Path, json: bool) -> Result<i32> {
    let before_universe = gradle::load_dump(before)?;
    let after_universe = gradle::load_dump(after)?;
    let changes = gradle::diff_dumps(&before_universe, &after_universe);

    if changes.old_jars.is_empty() {
        if json {
            println!("{}", report::upgrade_json(&changes.changes, None)?);
        } else {
            print!("{}", report::upgrade_text(&changes.changes, None));
        }
        return Ok(0);
    }

    // Scan target = the full after runtime classpath + build outputs.
    // Check removed/changed old versions as --old and new versions as --new in one batch.
    // Reachability ranks against the dump's own build outputs (run_check turns it on when present).
    let mut result = run_check(
        &changes.old_jars,
        &changes.new_jars,
        &after_universe.scan_targets,
        &after_universe.app_roots,
    )?;
    // Attribute each break to the artifacts involved and propose a fix (coordinates only exist
    // for upgrade-check, so this lives here rather than in the shared run_check).
    suggest::annotate(
        &mut result.violations,
        &before_universe,
        &after_universe,
        &changes.changes,
    );
    if json {
        println!("{}", report::upgrade_json(&changes.changes, Some(&result))?);
    } else {
        print!("{}", report::upgrade_text(&changes.changes, Some(&result)));
    }
    Ok(if result.violations.is_empty() { 0 } else { 1 })
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
