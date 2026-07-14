use crate::check::CheckReport;
use crate::model::{BreakingChange, RefKind, Violation, counts_as_reachable};
use anyhow::Result;
use serde::Serialize;
use std::collections::BTreeMap;
use std::fmt::Write;

pub fn diff_text(changes: &[BreakingChange]) -> String {
    let mut out = String::new();
    let (mut classes, mut methods, mut fields, mut other) = (0usize, 0usize, 0usize, 0usize);
    for c in changes {
        match c {
            BreakingChange::ClassRemoved { class } => {
                classes += 1;
                writeln!(out, "CLASS REMOVED   {class}").unwrap();
            }
            BreakingChange::MethodRemoved {
                class,
                name,
                descriptor,
                replacement_descriptors,
            } => {
                methods += 1;
                writeln!(out, "METHOD REMOVED  {class}.{name} {descriptor}").unwrap();
                if !replacement_descriptors.is_empty() {
                    writeln!(
                        out,
                        "                (descriptor changed? now: {})",
                        replacement_descriptors
                            .iter()
                            .map(|d| d.as_str())
                            .collect::<Vec<_>>()
                            .join(", ")
                    )
                    .unwrap();
                }
            }
            BreakingChange::FieldRemoved {
                class,
                name,
                descriptor,
                replacement_descriptors,
            } => {
                fields += 1;
                writeln!(out, "FIELD REMOVED   {class}.{name} {descriptor}").unwrap();
                if !replacement_descriptors.is_empty() {
                    writeln!(
                        out,
                        "                (descriptor changed? now: {})",
                        replacement_descriptors
                            .iter()
                            .map(|d| d.as_str())
                            .collect::<Vec<_>>()
                            .join(", ")
                    )
                    .unwrap();
                }
            }
            BreakingChange::ClassAccessNarrowed { class, .. } => {
                classes += 1;
                writeln!(out, "CLASS ACCESS NARROWED {class}").unwrap();
            }
            BreakingChange::ClassBecameFinal { class } => {
                classes += 1;
                writeln!(out, "CLASS BECAME FINAL    {class}").unwrap();
            }
            BreakingChange::MethodAccessNarrowed {
                class,
                name,
                descriptor,
                ..
            } => {
                methods += 1;
                writeln!(out, "METHOD ACCESS NARROWED {class}.{name} {descriptor}").unwrap();
            }
            BreakingChange::FieldAccessNarrowed {
                class,
                name,
                descriptor,
                ..
            } => {
                fields += 1;
                writeln!(out, "FIELD ACCESS NARROWED  {class}.{name} {descriptor}").unwrap();
            }
            BreakingChange::MethodStaticChanged {
                class,
                name,
                descriptor,
                old_static,
                new_static,
            } => {
                methods += 1;
                writeln!(
                    out,
                    "METHOD STATIC CHANGED  {class}.{name} {descriptor} ({old_static} -> {new_static})"
                )
                .unwrap();
            }
            BreakingChange::FieldStaticChanged {
                class,
                name,
                descriptor,
                old_static,
                new_static,
            } => {
                fields += 1;
                writeln!(
                    out,
                    "FIELD STATIC CHANGED   {class}.{name} {descriptor} ({old_static} -> {new_static})"
                )
                .unwrap();
            }
            BreakingChange::FieldBecameFinal {
                class,
                name,
                descriptor,
            } => {
                fields += 1;
                writeln!(out, "FIELD BECAME FINAL    {class}.{name} {descriptor}").unwrap();
            }
            BreakingChange::MethodBecameFinal {
                class,
                name,
                descriptor,
            } => {
                other += 1;
                writeln!(out, "METHOD BECAME FINAL   {class}.{name} {descriptor}").unwrap();
            }
        }
    }
    writeln!(
        out,
        "\nbreaking changes: {} (classes: {classes}, methods: {methods}, fields: {fields}, other: {other})",
        changes.len()
    )
    .unwrap();
    out
}

#[derive(Serialize)]
struct DiffJson<'a> {
    breaking_changes: &'a [BreakingChange],
    total: usize,
}

pub fn diff_json(changes: &[BreakingChange]) -> Result<String> {
    Ok(serde_json::to_string_pretty(&DiffJson {
        breaking_changes: changes,
        total: changes.len(),
    })?)
}

/// Write violations grouped in stable source -> source_class -> reference order.
fn write_violation_groups(out: &mut String, violations: &[&Violation]) {
    let mut grouped: BTreeMap<&str, BTreeMap<&str, Vec<&Violation>>> = BTreeMap::new();
    for &v in violations {
        grouped
            .entry(v.source.as_str())
            .or_default()
            .entry(v.source_class.as_str())
            .or_default()
            .push(v);
    }
    for (source, by_class) in &grouped {
        writeln!(out, "VIOLATION in {source}").unwrap();
        for (class, vs) in by_class {
            writeln!(out, "  {class}").unwrap();
            for v in vs {
                let target = match (&v.reference.kind, &v.reference.member) {
                    (RefKind::Class, _) | (_, None) => v.reference.owner.to_string(),
                    (_, Some(m)) => {
                        format!("{}.{} {}", v.reference.owner, m.name, m.descriptor)
                    }
                };
                writeln!(out, "    -> {}: {target}", v.reason).unwrap();
                if let Some(s) = &v.suggestion {
                    if let Some(rb) = &s.referenced_by {
                        writeln!(out, "       referenced by: {rb}").unwrap();
                    }
                    writeln!(
                        out,
                        "       removed by:    {} {} -> {}",
                        s.removed_by, s.before, s.after
                    )
                    .unwrap();
                    writeln!(out, "       suggestion:    {}", s.advice).unwrap();
                }
            }
        }
    }
}

pub fn check_text(report: &CheckReport) -> String {
    let mut out = String::new();
    let has_body = !report.violations.is_empty();
    // Partition once (reused for both the sections and the summary count).
    let reach_note = if report.reachability_computed {
        // Reachable first (likely to break), then the ones we could not prove reachable
        // (no static path found, but reflection may still load them).
        let (reachable, unproven): (Vec<&Violation>, Vec<&Violation>) = report
            .violations
            .iter()
            .partition(|v| counts_as_reachable(v.reachable));
        if !reachable.is_empty() {
            writeln!(out, "💥 reachable from the application (likely to break)").unwrap();
            write_violation_groups(&mut out, &reachable);
        }
        if !unproven.is_empty() {
            if !reachable.is_empty() {
                writeln!(out).unwrap();
            }
            writeln!(
                out,
                "⚠️  not proven reachable (no static path found; may still load via reflection)"
            )
            .unwrap();
            write_violation_groups(&mut out, &unproven);
        }
        // Only annotate the summary once there is something to rank.
        if has_body {
            format!(
                " (💥 {} reachable, ⚠️ {} not proven reachable)",
                reachable.len(),
                unproven.len()
            )
        } else {
            String::new()
        }
    } else {
        write_violation_groups(&mut out, &report.violations.iter().collect::<Vec<_>>());
        String::new()
    };
    let unknown_note = if report.unknown_refs > 0 {
        format!(
            ", {} unverified (hierarchy escapes scope)",
            report.unknown_refs
        )
    } else {
        String::new()
    };
    writeln!(
        out,
        "{}scanned {} classes, {} broken reference(s){reach_note}{unknown_note}",
        if has_body { "\n" } else { "" },
        report.scanned_classes,
        report.violations.len()
    )
    .unwrap();
    out
}

/// upgrade-check: dependency-diff header + check result if a check ran.
pub fn upgrade_text(
    changes: &[crate::gradle::DependencyChange],
    result: Option<&CheckReport>,
) -> String {
    use crate::gradle::ChangeKind;
    let mut out = String::new();
    if changes.is_empty() {
        writeln!(out, "dependency changes: none").unwrap();
        return out;
    }
    writeln!(out, "dependency changes: {}", changes.len()).unwrap();
    for c in changes {
        let label = match c.kind {
            ChangeKind::Changed => "CHANGED",
            ChangeKind::Removed => "REMOVED",
            ChangeKind::Added => "ADDED  ",
        };
        writeln!(
            out,
            "  {label} {} {} -> {}",
            c.coordinate,
            if c.before.is_empty() {
                "-".to_string()
            } else {
                c.before.join(",")
            },
            if c.after.is_empty() {
                "-".to_string()
            } else {
                c.after.join(",")
            },
        )
        .unwrap();
    }
    if let Some(result) = result {
        writeln!(out).unwrap();
        out.push_str(&check_text(result));
    }
    out
}

#[derive(Serialize)]
struct UpgradeJson<'a> {
    changes: &'a [crate::gradle::DependencyChange],
    #[serde(skip_serializing_if = "Option::is_none")]
    violations: Option<&'a [Violation]>,
    #[serde(skip_serializing_if = "Option::is_none")]
    scanned_classes: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    unknown_refs: Option<usize>,
}

pub fn upgrade_json(
    changes: &[crate::gradle::DependencyChange],
    result: Option<&CheckReport>,
) -> Result<String> {
    Ok(serde_json::to_string_pretty(&UpgradeJson {
        changes,
        violations: result.map(|r| r.violations.as_slice()),
        scanned_classes: result.map(|r| r.scanned_classes),
        unknown_refs: result.map(|r| r.unknown_refs),
    })?)
}

#[derive(Serialize)]
struct CheckJson<'a> {
    violations: &'a [Violation],
    scanned_classes: usize,
    total: usize,
    unknown_refs: usize,
}

pub fn check_json(report: &CheckReport) -> Result<String> {
    Ok(serde_json::to_string_pretty(&CheckJson {
        violations: &report.violations,
        scanned_classes: report.scanned_classes,
        total: report.violations.len(),
        unknown_refs: report.unknown_refs,
    })?)
}
