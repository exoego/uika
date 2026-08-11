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
    Annotator::new(before, after).annotate(violations, changes);
}

/// Reusable annotation state for callers that annotate several violation sets against the
/// same dump pair (the per-module upgrade-check annotates once per run): the file->coordinate
/// map and each before-side jar's class-name listing depend only on the universes, so they
/// are built or read once instead of once per run.
pub struct Annotator<'a> {
    before: &'a Universe,
    file_coord: FxHashMap<String, String>,
    class_names_by_file: FxHashMap<std::path::PathBuf, Vec<Sym>>,
    /// referencing JAR path -> the "group:name" that JAR's POM declares optional (empty when
    /// there is no readable POM). Keyed by the jar alone, so the directory listing, the read
    /// and the scan happen once however many removed coordinates that jar references, and
    /// once however many per-module runs it appears on.
    optional_deps: FxHashMap<String, rustc_hash::FxHashSet<String>>,
}

impl<'a> Annotator<'a> {
    pub fn new(before: &'a Universe, after: &'a Universe) -> Self {
        Self {
            before,
            file_coord: file_coordinates(before, after),
            class_names_by_file: FxHashMap::default(),
            optional_deps: FxHashMap::default(),
        }
    }

    /// See [`annotate`]. `changes` may be the universe-wide list or one module's own.
    pub fn annotate(&mut self, violations: &mut [Violation], changes: &[DependencyChange]) {
        if violations.is_empty() {
            return;
        }
        let owner_change = self.owner_changes(changes);

        for v in violations.iter_mut() {
            let Some(&ci) = owner_change.get(&v.reference.owner) else {
                continue;
            };
            let change = &changes[ci];
            let referenced_by = self.file_coord.get(v.source.as_str()).cloned();
            // Only asked for a coordinate the upgrade dropped entirely; that is the one advice
            // branch whose claim ("still needs it") an optional declaration contradicts.
            let owner_optional = change.after.is_empty()
                && self.declares_optional(
                    v.source.as_str(),
                    referenced_by.as_deref(),
                    &change.coordinate,
                );
            v.suggestion = Some(build(change, referenced_by, owner_optional));
        }
    }

    /// Whether the referencing artifact's own POM declares `owner` optional. `referenced_by`
    /// must be `self.file_coord[source]`; it is passed in because the caller already cloned it.
    fn declares_optional(
        &mut self,
        source: &str,
        referenced_by: Option<&str>,
        owner: &str,
    ) -> bool {
        let Some(referencer) = referenced_by else {
            return false;
        };
        if !self.optional_deps.contains_key(source) {
            let declared = read_optional_deps(source, referencer).unwrap_or_default();
            self.optional_deps.insert(source.to_string(), declared);
        }
        self.optional_deps
            .get(source)
            .is_some_and(|declared| declared.contains(owner))
    }

    /// owner class -> index into `changes`, by reading the before-side JARs of each changed
    /// coordinate (removed classes live there; classes losing only a member are there too).
    fn owner_changes(&mut self, changes: &[DependencyChange]) -> FxHashMap<Sym, usize> {
        let mut map = FxHashMap::default();
        for (i, change) in changes.iter().enumerate() {
            if change.kind == ChangeKind::Added {
                continue;
            }
            let Some((group, name)) = change.coordinate.split_once(':') else {
                continue;
            };
            let Some(versions) = self
                .before
                .versions
                .get(&(group.to_string(), name.to_string()))
            else {
                continue;
            };
            for file in versions.values() {
                let owners = self
                    .class_names_by_file
                    .entry(file.clone())
                    .or_insert_with(|| class_names(file));
                for owner in owners {
                    map.entry(*owner).or_insert(i);
                }
            }
        }
        map
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

/// Interned internal names of the classes in a JAR/dir. Names only (no inflate); empty on read
/// failure, since suggestions are best-effort and never block the report.
fn class_names(path: &Path) -> Vec<Sym> {
    crate::input::class_entry_names(path)
        .iter()
        .map(|name| intern(name))
        .collect()
}

/// Read `{referencer}`'s POM, if it sits beside the JAR the broken class came from, and report
/// the "group:name" it declares optional. None when anything is missing. Decoded lossily
/// because a POM may declare a non-UTF-8 encoding, and refusing to read it would silently
/// produce the wording this whole path exists to avoid.
fn read_optional_deps(source: &str, referencer: &str) -> Option<rustc_hash::FxHashSet<String>> {
    let mut parts = referencer.split(':');
    let (_, name, version) = (parts.next()?, parts.next()?, parts.next()?);
    let path = crate::pom::locate(Path::new(source), name, version)?;
    let bytes = std::fs::read(path).ok()?;
    Some(crate::pom::optional_dependencies(&String::from_utf8_lossy(
        &bytes,
    )))
}

fn build(
    change: &DependencyChange,
    referenced_by: Option<String>,
    owner_optional: bool,
) -> Suggestion {
    let owner = &change.coordinate;
    let referencer = referenced_by
        .as_deref()
        .unwrap_or("the referencing artifact");

    let advice = if change.after.is_empty() {
        // Coordinate dropped entirely by the upgrade: pinning a version back is meaningless.
        if owner_optional {
            // An optional dependency was never required transitively, so "still needs it" is
            // false and "upgrade to a release that no longer requires it" points at a release
            // that will not exist -- optional integrations are permanent design choices.
            // https://github.com/exoego/uika/issues/96
            //
            // The claim stops at what the POM states. Saying it "arrived through some other
            // dependency" would assert a graph the dump does not model (no requested-by edges),
            // and would be wrong outright when the build declared the coordinate directly and
            // this upgrade dropped that declaration -- the same assert-an-unverified-cause
            // mistake #96 exists to remove, pointed the other way.
            format!(
                "{owner} was removed by the upgrade and {referencer} declares it optional, so \
                 {referencer} never required it transitively -- it was on the classpath for some \
                 other reason that no longer holds. These references break only where \
                 {referencer}'s optional feature is used; restore {owner} to keep that feature \
                 working"
            )
        } else {
            format!(
                "{owner} was removed by the upgrade, but {referencer} still needs it; \
                 upgrade {referencer} to a release that no longer requires {owner}, or restore \
                 {owner}"
            )
        }
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
            false,
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
            false,
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
            false,
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
            false,
        );
        assert_eq!(
            s.advice,
            "io.opentelemetry.instrumentation:opentelemetry-ktor-common was removed by the upgrade, \
             but com.example:app:1.0 still needs it; upgrade com.example:app:1.0 to a release that \
             no longer requires io.opentelemetry.instrumentation:opentelemetry-ktor-common, or \
             restore io.opentelemetry.instrumentation:opentelemetry-ktor-common"
        );
        assert_eq!((s.before.as_str(), s.after.as_str()), ("2.24.0-alpha", "-"));
    }

    /// Coordinate removed and the referencing artifact declares it optional: it was never a
    /// transitive requirement, so neither "still needs it" nor "upgrade to a release that no
    /// longer requires it" applies. https://github.com/exoego/uika/issues/96
    #[test]
    fn advice_removed_optional_coordinate() {
        let s = build(
            &change("org.slf4j:slf4j-api", ChangeKind::Removed, &["2.0.13"], &[]),
            Some("com.google.auth:google-auth-library-oauth2-http:1.50.0".to_string()),
            true,
        );
        assert_eq!(
            s.advice,
            "org.slf4j:slf4j-api was removed by the upgrade and \
             com.google.auth:google-auth-library-oauth2-http:1.50.0 declares it optional, so \
             com.google.auth:google-auth-library-oauth2-http:1.50.0 never required it \
             transitively -- it was on the classpath for some other reason that no longer holds. \
             These references break only where \
             com.google.auth:google-auth-library-oauth2-http:1.50.0's optional feature is used; \
             restore org.slf4j:slf4j-api to keep that feature working"
        );
    }

    /// The optional wording is only for a coordinate that vanished. A version CHANGE leaves the
    /// artifact on the classpath, so optional-ness says nothing about the break.
    #[test]
    fn optional_does_not_reword_a_version_change() {
        let s = build(
            &change("g:n", ChangeKind::Changed, &["1.0"], &["2.0"]),
            Some("h:m:1".to_string()),
            true,
        );
        assert_eq!(
            s.advice,
            "upgrade h:m:1 to a release built against g:n 2.0, or pin g:n to 1.0"
        );
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
            false,
        );
        assert_eq!(
            s.advice,
            "io.opentelemetry.instrumentation:opentelemetry-ktor-common was removed by the upgrade, \
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
            false,
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
