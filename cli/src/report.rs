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
            BreakingChange::ClassBecameAbstract { class } => {
                classes += 1;
                writeln!(out, "CLASS BECAME ABSTRACT {class}").unwrap();
            }
            BreakingChange::ClassKindChanged {
                class,
                old_interface,
            } => {
                classes += 1;
                let flip = if *old_interface {
                    "INTERFACE BECAME CLASS"
                } else {
                    "CLASS BECAME INTERFACE"
                };
                writeln!(out, "{flip} {class}").unwrap();
            }
            BreakingChange::MethodBecameAbstract {
                class,
                name,
                descriptor,
            } => {
                methods += 1;
                writeln!(out, "METHOD BECAME ABSTRACT {class}.{name} {descriptor}").unwrap();
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

/// The referenced symbol as shown on a violation line: bare owner for a class reference,
/// otherwise "owner.member descriptor".
fn ref_target(v: &Violation) -> String {
    match (&v.reference.kind, &v.reference.member) {
        (RefKind::Class, _) | (_, None) => v.reference.owner.to_string(),
        (_, Some(m)) => format!("{}.{} {}", v.reference.owner, m.name, m.descriptor),
    }
}

/// Write a set of violations. upgrade-check violations carry a suggestion, so they are grouped by
/// the fix (one 💡 block lists every reference a single piece of advice covers) instead of
/// repeating the advice per reference. Plain `check` has no suggestions and keeps the
/// source -> class listing. In a mixed run the attributed groups come first, then the rest.
fn write_violation_groups(out: &mut String, violations: &[&Violation]) {
    let (with_sugg, without): (Vec<&Violation>, Vec<&Violation>) = violations
        .iter()
        .copied()
        .partition(|v| v.suggestion.is_some());
    write_suggestion_groups(out, &with_sugg);
    if !with_sugg.is_empty() && !without.is_empty() {
        writeln!(out).unwrap();
    }
    write_source_groups(out, &without);
}

/// One 💡 block per distinct fix. Identical advice implies the same removed_by / referenced_by /
/// before / after (the advice string embeds the coordinates and changed versions), so the header
/// is built from any member of the group. Groups are ordered by advice and references within a
/// group by class/target/reason, keeping the output deterministic.
fn write_suggestion_groups(out: &mut String, violations: &[&Violation]) {
    let mut grouped: BTreeMap<&str, Vec<&Violation>> = BTreeMap::new();
    for &v in violations {
        let advice = v.suggestion.as_ref().unwrap().advice.as_str();
        grouped.entry(advice).or_default().push(v);
    }
    for vs in grouped.values() {
        let s = vs[0].suggestion.as_ref().unwrap();
        writeln!(out, "💡 {}", s.advice).unwrap();
        writeln!(
            out,
            "   removed by: {} {} -> {}",
            s.removed_by, s.before, s.after
        )
        .unwrap();
        if let Some(rb) = &s.referenced_by {
            writeln!(out, "   referenced by: {rb}").unwrap();
        }
        // Per-module upgrade-check: the modules whose classpaths exhibit this group.
        let mut modules: Vec<&str> = vs
            .iter()
            .flat_map(|v| v.modules.iter().map(String::as_str))
            .collect();
        modules.sort_unstable();
        modules.dedup();
        if !modules.is_empty() {
            writeln!(out, "   modules: {}", modules.join(", ")).unwrap();
        }
        let mut refs = vs.clone();
        refs.sort_by_cached_key(|v| (v.source_class.as_str(), ref_target(v), v.reason.as_str()));
        for v in refs {
            writeln!(
                out,
                "   -> {}  {}: {}",
                v.source_class,
                v.reason,
                ref_target(v)
            )
            .unwrap();
        }
    }
}

/// Stable source -> source_class -> reference listing for violations without a suggestion.
fn write_source_groups(out: &mut String, violations: &[&Violation]) {
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
                let modules = if v.modules.is_empty() {
                    String::new()
                } else {
                    format!(" [{}]", v.modules.join(", "))
                };
                writeln!(out, "    -> {}: {}{modules}", v.reason, ref_target(v)).unwrap();
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
    let suppressed_note = if report.suppressed > 0 {
        format!(", {} suppressed by --exclude-file", report.suppressed)
    } else {
        String::new()
    };
    writeln!(
        out,
        "{}scanned {} classes, {} broken reference(s){reach_note}{unknown_note}{suppressed_note}",
        if has_body { "\n" } else { "" },
        report.scanned_classes,
        report.violations.len()
    )
    .unwrap();
    out
}

/// One deduplicated per-module check run (modules sharing identical inputs share one run).
#[derive(Serialize)]
pub struct ModuleOutcome {
    pub modules: Vec<String>,
    pub scanned_classes: usize,
    /// Post-exclusion violations attributed to any module of this run.
    pub broken: usize,
    pub unknown_refs: usize,
}

/// How the per-module upgrade-check partitioned the dump's modules.
#[derive(Serialize)]
pub struct ModuleRunSummary {
    pub outcomes: Vec<ModuleOutcome>,
    pub total_modules: usize,
    /// Modules whose own resolution lost no version: skipped, they cannot break.
    pub unchanged_modules: usize,
    /// Modules present only in the after dump with nothing checkable: skipped.
    pub new_modules: usize,
    /// After-side modules whose artifact list vanished (partial dump): skipped, warned.
    pub incomplete_modules: usize,
}

/// upgrade-check: dependency-diff header + per-module run summary + check result if a check ran.
/// The header never swallows the rest: per-module runs gate on each module's OWN diff, which
/// can find violations while the universe-wide change list is empty (a version swap between
/// modules), so the summary and result print regardless of `changes`.
pub fn upgrade_text(
    changes: &[crate::gradle::DependencyChange],
    result: Option<&CheckReport>,
    modules: Option<&ModuleRunSummary>,
) -> String {
    use crate::gradle::ChangeKind;
    let mut out = String::new();
    if changes.is_empty() {
        writeln!(out, "dependency changes: none").unwrap();
    } else {
        writeln!(out, "dependency changes: {}", changes.len()).unwrap();
    }
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
    if let Some(m) = modules {
        writeln!(out).unwrap();
        let checked: usize = m.outcomes.iter().map(|o| o.modules.len()).sum();
        let mut notes = vec![format!("{} unchanged", m.unchanged_modules)];
        if m.new_modules > 0 {
            notes.push(format!("{} new", m.new_modules));
        }
        if m.incomplete_modules > 0 {
            notes.push(format!("{} incomplete", m.incomplete_modules));
        }
        writeln!(
            out,
            "per-module check: {checked} of {} modules changed resolution ({})",
            m.total_modules,
            notes.join(", ")
        )
        .unwrap();
        for o in &m.outcomes {
            writeln!(
                out,
                "  {}  scanned {} classes, {} broken, {} unverified",
                o.modules.join(", "),
                o.scanned_classes,
                o.broken,
                o.unknown_refs
            )
            .unwrap();
        }
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
    module_runs: Option<&'a ModuleRunSummary>,
    #[serde(skip_serializing_if = "Option::is_none")]
    violations: Option<&'a [Violation]>,
    #[serde(skip_serializing_if = "Option::is_none")]
    scanned_classes: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    unknown_refs: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    suppressed: Option<usize>,
}

pub fn upgrade_json(
    changes: &[crate::gradle::DependencyChange],
    result: Option<&CheckReport>,
    modules: Option<&ModuleRunSummary>,
) -> Result<String> {
    Ok(serde_json::to_string_pretty(&UpgradeJson {
        changes,
        module_runs: modules,
        violations: result.map(|r| r.violations.as_slice()),
        scanned_classes: result.map(|r| r.scanned_classes),
        unknown_refs: result.map(|r| r.unknown_refs),
        suppressed: result.map(|r| r.suppressed),
    })?)
}

#[derive(Serialize)]
struct CheckJson<'a> {
    violations: &'a [Violation],
    scanned_classes: usize,
    total: usize,
    unknown_refs: usize,
    suppressed: usize,
}

pub fn check_json(report: &CheckReport) -> Result<String> {
    Ok(serde_json::to_string_pretty(&CheckJson {
        violations: &report.violations,
        scanned_classes: report.scanned_classes,
        total: report.violations.len(),
        unknown_refs: report.unknown_refs,
        suppressed: report.suppressed,
    })?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::intern::intern;
    use crate::model::{Suggestion, SymbolRef};

    fn class_violation(
        source_class: &str,
        owner: &str,
        reason: &str,
        reachable: Option<bool>,
        advice: Option<&str>,
    ) -> Violation {
        Violation {
            source: intern("consumer.jar"),
            source_class: intern(source_class),
            reference: SymbolRef {
                kind: RefKind::Class,
                owner: intern(owner),
                member: None,
                expected_static: None,
                field_write: None,
                instantiated: None,
            },
            reason: reason.to_string(),
            reachable,
            suggestion: advice.map(|a| Suggestion {
                referenced_by: Some("g:referencer:1".to_string()),
                removed_by: "g:owner".to_string(),
                before: "1".to_string(),
                after: "2".to_string(),
                advice: a.to_string(),
            }),
            modules: Vec::new(),
        }
    }

    fn report(violations: Vec<Violation>) -> CheckReport {
        CheckReport {
            violations,
            warnings: Vec::new(),
            scanned_classes: 100,
            unknown_refs: 0,
            suppressed: 0,
            reachability_computed: true,
            app_roots_matched: Some(true),
        }
    }

    /// Several references sharing one piece of advice collapse into a single 💡 block, and the
    /// block is ordered before a distinct one; a violation without a suggestion falls back to the
    /// source listing.
    #[test]
    fn suggestions_group_by_advice() {
        let r = report(vec![
            class_violation(
                "a/Foo",
                "x/GoneA",
                "class removed",
                Some(true),
                Some("ADVICE_A"),
            ),
            class_violation(
                "a/Bar",
                "x/GoneB",
                "class removed",
                Some(true),
                Some("ADVICE_A"),
            ),
            class_violation(
                "a/Baz",
                "x/GoneC",
                "class removed",
                Some(true),
                Some("ADVICE_B"),
            ),
        ]);
        let out = check_text(&r);
        // Shared advice printed once, both references listed under it.
        assert_eq!(out.matches("💡 ADVICE_A").count(), 1, "\n{out}");
        assert_eq!(out.matches("💡 ADVICE_B").count(), 1, "\n{out}");
        assert!(
            out.contains("   -> a/Foo  class removed: x/GoneA"),
            "\n{out}"
        );
        assert!(
            out.contains("   -> a/Bar  class removed: x/GoneB"),
            "\n{out}"
        );
        // Deterministic order: ADVICE_A group before ADVICE_B group.
        assert!(
            out.find("ADVICE_A").unwrap() < out.find("ADVICE_B").unwrap(),
            "\n{out}"
        );
    }

    /// The same advice covering both a reachable and an unproven reference appears once per
    /// section, since the report splits into 💥 / ⚠️ before grouping.
    #[test]
    fn shared_advice_repeats_once_per_reachability_section() {
        let r = report(vec![
            class_violation(
                "a/Foo",
                "x/Gone",
                "class removed",
                Some(true),
                Some("ADVICE_A"),
            ),
            class_violation(
                "a/Bar",
                "x/Gone",
                "class removed",
                Some(false),
                Some("ADVICE_A"),
            ),
        ]);
        let out = check_text(&r);
        assert_eq!(out.matches("💡 ADVICE_A").count(), 2, "\n{out}");
        let reachable = out.find("reachable from the application").unwrap();
        let unproven = out.find("not proven reachable").unwrap();
        assert!(reachable < unproven);
        // Foo (reachable) sits in the 💥 section, Bar (unproven) in the ⚠️ section.
        assert!(out.find("a/Foo").unwrap() < unproven, "\n{out}");
        assert!(out.find("a/Bar").unwrap() > unproven, "\n{out}");
    }

    /// Violations without a suggestion keep the source -> class listing.
    #[test]
    fn unattributed_violation_uses_source_listing() {
        let mut r = report(vec![class_violation(
            "a/Foo",
            "x/Gone",
            "class removed",
            None,
            None,
        )]);
        r.reachability_computed = false;
        let out = check_text(&r);
        assert!(out.contains("VIOLATION in consumer.jar"), "\n{out}");
        assert!(out.contains("  a/Foo"), "\n{out}");
        assert!(out.contains("    -> class removed: x/Gone"), "\n{out}");
        assert!(!out.contains("💡"), "\n{out}");
    }

    /// The summary line notes suppressed violations only when the count is nonzero.
    #[test]
    fn suppressed_note_appears_only_when_nonzero() {
        let mut r = report(vec![class_violation(
            "a/Foo",
            "x/Gone",
            "class removed",
            Some(true),
            None,
        )]);
        r.suppressed = 3;
        let out = check_text(&r);
        assert!(out.contains("3 suppressed by --exclude-file"), "\n{out}");

        r.suppressed = 0;
        let out = check_text(&r);
        assert!(!out.contains("suppressed"), "\n{out}");
    }
}
