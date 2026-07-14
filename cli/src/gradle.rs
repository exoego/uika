//! Loading resolved classpath JSON emitted by the build-tool plugins
//! (gradle-plugin/, sbt-plugin/, maven-plugin/) and computing dependency diffs
//! between before and after states.
//!
//! The JSON is emitted by the uikaDumpClasspath tasks (Gradle/sbt) or the
//! uika:dump-classpath goal (Maven). Each module contains resolved artifacts
//! (coordinates + files) and build output directories. Coordinates come from
//! the build tool's resolution result instead of path parsing, which makes
//! them robust.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

#[derive(Deserialize)]
struct ClasspathDump {
    modules: Vec<ModuleDump>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModuleDump {
    #[allow(dead_code)]
    module: String,
    #[serde(default)]
    classes_dirs: Vec<PathBuf>,
    #[serde(default)]
    artifacts: Vec<ArtifactDump>,
}

#[derive(Deserialize)]
struct ArtifactDump {
    group: Option<String>,
    name: Option<String>,
    version: Option<String>,
    file: PathBuf,
}

/// Everything present at runtime, aggregated from one dump.
pub struct Universe {
    /// Scan targets: artifact files + build outputs (deduplicated, in first-seen order).
    pub scan_targets: Vec<PathBuf>,
    /// Application build outputs (module classesDirs). Reachability roots.
    pub app_roots: Vec<PathBuf>,
    /// Artifacts with coordinates: (group, name) -> version -> file.
    /// If modules resolve differently, multiple versions can appear for the same coordinate.
    pub versions: BTreeMap<(String, String), BTreeMap<String, PathBuf>>,
}

/// v2: deduplication + root table for path prefixes (paired with DumpFormat in jvm-plugin-core).
#[derive(Deserialize)]
struct DumpV2 {
    roots: Vec<String>,
    artifacts: Vec<ArtifactV2>,
    #[serde(default)]
    modules: Vec<ModuleV2>,
}

#[derive(Deserialize)]
struct ArtifactV2 {
    group: Option<String>,
    name: Option<String>,
    version: Option<String>,
    root: usize,
    path: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModuleV2 {
    #[serde(default)]
    classes_dirs: Vec<RootedPath>,
}

#[derive(Deserialize)]
struct RootedPath {
    root: usize,
    path: String,
}

pub fn load_dump(path: &Path) -> Result<Universe> {
    let text = std::fs::read_to_string(path)
        .with_context(|| format!("cannot read classpath dump {}", path.display()))?;
    let value: serde_json::Value = serde_json::from_str(&text)
        .with_context(|| format!("invalid classpath dump {}", path.display()))?;
    if value.get("version").and_then(|v| v.as_u64()) == Some(2) {
        from_v2(
            serde_json::from_value(value)
                .with_context(|| format!("invalid v2 classpath dump {}", path.display()))?,
        )
    } else {
        Ok(from_v1(serde_json::from_value(value).with_context(
            || format!("invalid v1 classpath dump {}", path.display()),
        )?))
    }
}

fn from_v1(dump: ClasspathDump) -> Universe {
    let mut scan_targets = Vec::new();
    let mut app_roots = Vec::new();
    let mut seen = BTreeSet::new();
    let mut versions: BTreeMap<(String, String), BTreeMap<String, PathBuf>> = BTreeMap::new();
    for module in dump.modules {
        for artifact in module.artifacts {
            if seen.insert(artifact.file.clone()) {
                scan_targets.push(artifact.file.clone());
            }
            if let (Some(group), Some(name), Some(version)) =
                (artifact.group, artifact.name, artifact.version)
            {
                versions
                    .entry((group, name))
                    .or_default()
                    .insert(version, artifact.file);
            }
        }
        for dir in module.classes_dirs {
            app_roots.push(dir.clone());
            if seen.insert(dir.clone()) {
                scan_targets.push(dir);
            }
        }
    }
    Universe {
        scan_targets,
        app_roots,
        versions,
    }
}

fn from_v2(dump: DumpV2) -> Result<Universe> {
    let rooted = |root: usize, suffix: &str| -> Result<PathBuf> {
        let prefix = dump
            .roots
            .get(root)
            .with_context(|| format!("root index {root} out of range"))?;
        Ok(PathBuf::from(format!("{prefix}{suffix}")))
    };

    let mut scan_targets = Vec::new();
    let mut app_roots = Vec::new();
    let mut seen = BTreeSet::new();
    let mut versions: BTreeMap<(String, String), BTreeMap<String, PathBuf>> = BTreeMap::new();
    // The entity table is deduplicated, so first-seen order is table order.
    for artifact in &dump.artifacts {
        let file = rooted(artifact.root, &artifact.path)?;
        if seen.insert(file.clone()) {
            scan_targets.push(file.clone());
        }
        if let (Some(group), Some(name), Some(version)) =
            (&artifact.group, &artifact.name, &artifact.version)
        {
            versions
                .entry((group.clone(), name.clone()))
                .or_default()
                .insert(version.clone(), file);
        }
    }
    for module in &dump.modules {
        for dir in &module.classes_dirs {
            let dir = rooted(dir.root, &dir.path)?;
            app_roots.push(dir.clone());
            if seen.insert(dir.clone()) {
                scan_targets.push(dir);
            }
        }
    }
    Ok(Universe {
        scan_targets,
        app_roots,
        versions,
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ChangeKind {
    Changed,
    Removed,
    Added,
}

#[derive(Debug, Clone, Serialize)]
pub struct DependencyChange {
    pub coordinate: String,
    pub kind: ChangeKind,
    pub before: Vec<String>,
    pub after: Vec<String>,
}

/// Dependency diff between before and after. old_jars / new_jars map to check's --old / --new.
pub struct DependencyChanges {
    pub changes: Vec<DependencyChange>,
    /// JARs for versions that exist only before (including removed artifacts).
    pub old_jars: Vec<PathBuf>,
    /// JARs for versions that exist only after.
    pub new_jars: Vec<PathBuf>,
}

pub fn diff_dumps(before: &Universe, after: &Universe) -> DependencyChanges {
    let mut changes = Vec::new();
    let mut old_jars = Vec::new();
    let mut new_jars = Vec::new();

    for (coord, before_versions) in &before.versions {
        let after_versions = after.versions.get(coord);
        let before_set: BTreeSet<&String> = before_versions.keys().collect();
        let after_set: BTreeSet<&String> = after_versions
            .map(|v| v.keys().collect())
            .unwrap_or_default();
        if before_set == after_set {
            continue;
        }
        for (version, file) in before_versions {
            if !after_set.contains(version) {
                old_jars.push(file.clone());
            }
        }
        if let Some(after_versions) = after_versions {
            for (version, file) in after_versions {
                if !before_set.contains(version) {
                    new_jars.push(file.clone());
                }
            }
        }
        changes.push(DependencyChange {
            coordinate: format!("{}:{}", coord.0, coord.1),
            kind: if after_versions.is_some() {
                ChangeKind::Changed
            } else {
                ChangeKind::Removed
            },
            before: before_versions.keys().cloned().collect(),
            after: after_versions
                .map(|v| v.keys().cloned().collect())
                .unwrap_or_default(),
        });
    }
    // Newly added artifacts naturally enter the scan targets, so they are not checked as pairs,
    // but they are still reported.
    for (coord, after_versions) in &after.versions {
        if !before.versions.contains_key(coord) {
            changes.push(DependencyChange {
                coordinate: format!("{}:{}", coord.0, coord.1),
                kind: ChangeKind::Added,
                before: vec![],
                after: after_versions.keys().cloned().collect(),
            });
        }
    }

    DependencyChanges {
        changes,
        old_jars,
        new_jars,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_v1_and_v2_to_same_universe() {
        let dir = std::env::temp_dir().join(format!("uika-dump-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let v1 = r#"{"modules":[
            {"module":":app","classesDirs":["/repo/app/build/classes/kotlin/main"],"artifacts":[
                {"group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13","file":"/cache/modules-2/files-2.1/io.ktor/ktor-io-jvm/2.3.13/ab/ktor-io-jvm-2.3.13.jar"},
                {"file":"/repo/libs/local.jar"}
            ]}]}"#;
        let v2 = r#"{"version":2,
            "roots":["/cache/modules-2/files-2.1/","/repo/"],
            "artifacts":[
                {"group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13","root":0,"path":"io.ktor/ktor-io-jvm/2.3.13/ab/ktor-io-jvm-2.3.13.jar"},
                {"root":1,"path":"libs/local.jar"}
            ],
            "modules":[
                {"module":":app","classesDirs":[{"root":1,"path":"app/build/classes/kotlin/main"}],"artifactRefs":[0,1]}
            ]}"#;
        let v1_path = dir.join("v1.json");
        let v2_path = dir.join("v2.json");
        std::fs::write(&v1_path, v1).unwrap();
        std::fs::write(&v2_path, v2).unwrap();
        let u1 = load_dump(&v1_path).unwrap();
        let u2 = load_dump(&v2_path).unwrap();
        assert_eq!(u1.scan_targets, u2.scan_targets);
        assert_eq!(u1.versions, u2.versions);
        assert_eq!(
            u2.versions[&("io.ktor".to_string(), "ktor-io-jvm".to_string())]["2.3.13"],
            PathBuf::from(
                "/cache/modules-2/files-2.1/io.ktor/ktor-io-jvm/2.3.13/ab/ktor-io-jvm-2.3.13.jar"
            )
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn universe(entries: &[(&str, &str, &str, &str)]) -> Universe {
        let mut versions: BTreeMap<(String, String), BTreeMap<String, PathBuf>> = BTreeMap::new();
        let mut scan_targets = Vec::new();
        for (group, name, version, file) in entries {
            versions
                .entry((group.to_string(), name.to_string()))
                .or_default()
                .insert(version.to_string(), PathBuf::from(file));
            scan_targets.push(PathBuf::from(file));
        }
        Universe {
            scan_targets,
            app_roots: Vec::new(),
            versions,
        }
    }

    #[test]
    fn detects_version_change_removal_and_addition() {
        let before = universe(&[
            ("io.otel", "sdk-common", "1.42.1", "/old/sdk-common.jar"),
            ("io.otel", "sender", "1.42.1", "/old/sender.jar"),
            ("a", "gone", "1.0", "/old/gone.jar"),
        ]);
        let after = universe(&[
            ("io.otel", "sdk-common", "1.60.1", "/new/sdk-common.jar"),
            ("io.otel", "sender", "1.42.1", "/old/sender.jar"),
            ("b", "fresh", "2.0", "/new/fresh.jar"),
        ]);
        let diff = diff_dumps(&before, &after);
        assert_eq!(
            diff.old_jars,
            vec![
                PathBuf::from("/old/gone.jar"),
                PathBuf::from("/old/sdk-common.jar")
            ]
        );
        assert_eq!(diff.new_jars, vec![PathBuf::from("/new/sdk-common.jar")]);
        let kinds: Vec<_> = diff
            .changes
            .iter()
            .map(|c| (c.coordinate.as_str(), c.kind))
            .collect();
        assert!(kinds.contains(&("io.otel:sdk-common", ChangeKind::Changed)));
        assert!(kinds.contains(&("a:gone", ChangeKind::Removed)));
        assert!(kinds.contains(&("b:fresh", ChangeKind::Added)));
        // The unchanged sender is not included in changes.
        assert!(!kinds.iter().any(|(c, _)| *c == "io.otel:sender"));
    }
}
