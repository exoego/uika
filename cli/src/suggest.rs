//! Actionable upgrade-check suggestions.
//!
//! A violation says "class X removed" but not which dependency to touch. Here we attribute each
//! violation to the two artifacts involved -- the coordinate whose class holds the broken
//! reference (referenced_by) and the coordinate whose version bump removed the symbol
//! (removed_by) -- and propose a concrete fix. This needs coordinates, so it only applies to
//! upgrade-check (dumps carry them); a plain `check` classpath has bare file paths and gets
//! nothing.

use crate::gradle::{ChangeKind, DependencyChange, Universe};
use crate::intern::{Sym, intern};
use crate::model::{Suggestion, Violation};
use rustc_hash::FxHashMap;
use std::path::Path;

/// Attach a Suggestion to each violation whose removed owner maps to a changed dependency.
/// Left as None when the owner cannot be attributed (e.g. it came from an unchanged artifact).
pub fn annotate(
    violations: &mut [Violation],
    before: &Universe,
    after: &Universe,
    changes: &[DependencyChange],
) {
    if violations.is_empty() {
        return;
    }
    let file_coord = file_coordinates(before, after);
    let owner_change = owner_changes(before, changes);

    for v in violations.iter_mut() {
        let Some(&ci) = owner_change.get(&v.reference.owner) else {
            continue;
        };
        let change = &changes[ci];
        let referenced_by = file_coord.get(v.source.as_str()).cloned();
        v.suggestion = Some(build(change, referenced_by));
    }
}

/// class-origin JAR path -> "group:name:version". Both sides are indexed so a referencing
/// artifact is found whether it changed or not.
fn file_coordinates(before: &Universe, after: &Universe) -> FxHashMap<String, String> {
    let mut map = FxHashMap::default();
    for universe in [before, after] {
        for ((group, name), versions) in &universe.versions {
            for (version, file) in versions {
                map.entry(file.display().to_string())
                    .or_insert_with(|| format!("{group}:{name}:{version}"));
            }
        }
    }
    map
}

/// owner class -> index into `changes`, by reading the before-side JARs of each changed
/// coordinate (removed classes live there; classes losing only a member are there too).
fn owner_changes(before: &Universe, changes: &[DependencyChange]) -> FxHashMap<Sym, usize> {
    let mut map = FxHashMap::default();
    for (i, change) in changes.iter().enumerate() {
        if change.kind == ChangeKind::Added {
            continue;
        }
        let Some((group, name)) = change.coordinate.split_once(':') else {
            continue;
        };
        let Some(versions) = before.versions.get(&(group.to_string(), name.to_string())) else {
            continue;
        };
        for file in versions.values() {
            for owner in class_names(file) {
                map.entry(owner).or_insert(i);
            }
        }
    }
    map
}

/// Interned internal names of the classes in a JAR/dir. Names only (no inflate); empty on read
/// failure, since suggestions are best-effort and never block the report.
fn class_names(path: &Path) -> Vec<Sym> {
    crate::input::class_entry_names(path)
        .iter()
        .map(|name| intern(name))
        .collect()
}

fn build(change: &DependencyChange, referenced_by: Option<String>) -> Suggestion {
    let owner = &change.coordinate;
    let referencer = referenced_by
        .as_deref()
        .unwrap_or("the referencing artifact");

    let advice = if change.after.is_empty() {
        // Coordinate dropped entirely by the upgrade: pinning a version back is meaningless.
        format!(
            "{owner} was removed by the upgrade but {referencer} still needs it; \
             upgrade {referencer} to a release that no longer requires {owner}, or restore {owner}"
        )
    } else {
        // Advise on the versions that actually moved (before minus after / after minus before),
        // not the full resolved lists, so a multi-version coordinate does not read as
        // "pin to 1.62,1.63".
        let gone = diff_versions(&change.before, &change.after);
        let added = diff_versions(&change.after, &change.before);
        let pin = if gone.is_empty() {
            &change.before
        } else {
            &gone
        };
        let target = if added.is_empty() {
            &change.after
        } else {
            &added
        };
        let base = format!(
            "upgrade {referencer} to a release built against {owner} {}, or pin {owner} to {}",
            join_versions(target),
            join_versions(pin)
        );
        // Same-group skew (e.g. otel core vs its incubator): the real fix is aligning the whole
        // group, so lead with that.
        match &referenced_by {
            Some(rb) if group_of(rb) == group_of(owner) => format!(
                "align all {} artifacts to one version (e.g. via the matching BOM); otherwise {base}",
                group_of(owner)
            ),
            _ => base,
        }
    };

    Suggestion {
        referenced_by,
        removed_by: owner.clone(),
        before: join_versions(&change.before),
        after: join_versions(&change.after),
        advice,
    }
}

/// Versions present in `a` but not `b`, preserving `a`'s order.
fn diff_versions(a: &[String], b: &[String]) -> Vec<String> {
    a.iter().filter(|v| !b.contains(v)).cloned().collect()
}

fn join_versions(versions: &[String]) -> String {
    if versions.is_empty() {
        "-".to_string()
    } else {
        versions.join(",")
    }
}

fn group_of(coordinate: &str) -> &str {
    coordinate.split(':').next().unwrap_or(coordinate)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn group_of_takes_first_segment() {
        assert_eq!(
            group_of("io.opentelemetry:opentelemetry-sdk-logs:1.62.0"),
            "io.opentelemetry"
        );
        assert_eq!(
            group_of("io.opentelemetry:opentelemetry-api-incubator"),
            "io.opentelemetry"
        );
    }

    fn change(coord: &str, kind: ChangeKind, before: &[&str], after: &[&str]) -> DependencyChange {
        DependencyChange {
            coordinate: coord.to_string(),
            kind,
            before: before.iter().map(|s| s.to_string()).collect(),
            after: after.iter().map(|s| s.to_string()).collect(),
        }
    }

    // The tests below document one advice message per pattern: read the one whose name matches
    // your situation to see the exact suggestion it produces.

    /// Cross-group version change, referencing artifact known: upgrade it or pin the owner.
    #[test]
    fn advice_cross_group_version_change() {
        let s = build(
            &change(
                "io.opentelemetry.instrumentation:opentelemetry-grpc-1.6",
                ChangeKind::Changed,
                &["2.24.0-alpha"],
                &["2.29.0-alpha"],
            ),
            Some("com.google.cloud:google-cloud-firestore:3.42.0".to_string()),
        );
        assert_eq!(
            s.advice,
            "upgrade com.google.cloud:google-cloud-firestore:3.42.0 to a release built against \
             io.opentelemetry.instrumentation:opentelemetry-grpc-1.6 2.29.0-alpha, or pin \
             io.opentelemetry.instrumentation:opentelemetry-grpc-1.6 to 2.24.0-alpha"
        );
        assert_eq!(
            (s.before.as_str(), s.after.as_str()),
            ("2.24.0-alpha", "2.29.0-alpha")
        );
    }

    /// Owner and referencer share a group (a version skew inside one family): lead with BOM
    /// alignment, then fall back to the upgrade-or-pin advice.
    #[test]
    fn advice_same_group_skew_leads_with_bom_alignment() {
        let s = build(
            &change(
                "io.opentelemetry:opentelemetry-api-incubator",
                ChangeKind::Changed,
                &["1.58.0-alpha"],
                &["1.63.0-alpha"],
            ),
            Some("io.opentelemetry:opentelemetry-sdk-common:1.60.1".to_string()),
        );
        assert_eq!(
            s.advice,
            "align all io.opentelemetry artifacts to one version (e.g. via the matching BOM); \
             otherwise upgrade io.opentelemetry:opentelemetry-sdk-common:1.60.1 to a release built \
             against io.opentelemetry:opentelemetry-api-incubator 1.63.0-alpha, or pin \
             io.opentelemetry:opentelemetry-api-incubator to 1.58.0-alpha"
        );
    }

    /// Referencing artifact unknown (e.g. the break is in an application build output): the
    /// referencer is left generic and the same-group alignment shortcut does not apply.
    #[test]
    fn advice_referencer_unknown() {
        let s = build(
            &change(
                "io.opentelemetry.instrumentation:opentelemetry-grpc-1.6",
                ChangeKind::Changed,
                &["2.24.0-alpha"],
                &["2.29.0-alpha"],
            ),
            None,
        );
        assert_eq!(
            s.advice,
            "upgrade the referencing artifact to a release built against \
             io.opentelemetry.instrumentation:opentelemetry-grpc-1.6 2.29.0-alpha, or pin \
             io.opentelemetry.instrumentation:opentelemetry-grpc-1.6 to 2.24.0-alpha"
        );
    }

    /// Coordinate removed entirely: no version to pin back, so advise dropping the need or
    /// restoring the artifact.
    #[test]
    fn advice_removed_coordinate_referencer_known() {
        let s = build(
            &change(
                "io.opentelemetry.instrumentation:opentelemetry-ktor-common",
                ChangeKind::Removed,
                &["2.24.0-alpha"],
                &[],
            ),
            Some("com.example:app:1.0".to_string()),
        );
        assert_eq!(
            s.advice,
            "io.opentelemetry.instrumentation:opentelemetry-ktor-common was removed by the upgrade \
             but com.example:app:1.0 still needs it; upgrade com.example:app:1.0 to a release that \
             no longer requires io.opentelemetry.instrumentation:opentelemetry-ktor-common, or \
             restore io.opentelemetry.instrumentation:opentelemetry-ktor-common"
        );
        assert_eq!((s.before.as_str(), s.after.as_str()), ("2.24.0-alpha", "-"));
    }

    /// Coordinate removed, referencing artifact unknown.
    #[test]
    fn advice_removed_coordinate_referencer_unknown() {
        let s = build(
            &change(
                "io.opentelemetry.instrumentation:opentelemetry-ktor-common",
                ChangeKind::Removed,
                &["2.24.0-alpha"],
                &[],
            ),
            None,
        );
        assert_eq!(
            s.advice,
            "io.opentelemetry.instrumentation:opentelemetry-ktor-common was removed by the upgrade \
             but the referencing artifact still needs it; upgrade the referencing artifact to a \
             release that no longer requires io.opentelemetry.instrumentation:opentelemetry-ktor-common, \
             or restore io.opentelemetry.instrumentation:opentelemetry-ktor-common"
        );
    }

    /// Multi-version coordinate (resolves to several versions at once): advise only the versions
    /// that actually moved (2.0 replaced by 3.0), not the full resolved lists.
    #[test]
    fn advice_multi_version_uses_only_changed_versions() {
        let s = build(
            &change("g:n", ChangeKind::Changed, &["1.0", "2.0"], &["1.0", "3.0"]),
            Some("h:m:1".to_string()),
        );
        assert_eq!(
            s.advice,
            "upgrade h:m:1 to a release built against g:n 3.0, or pin g:n to 2.0"
        );
        // The removed_by line still shows the full resolved lists for context.
        assert_eq!(
            (s.before.as_str(), s.after.as_str()),
            ("1.0,2.0", "1.0,3.0")
        );
    }
}
