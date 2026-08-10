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
#[serde(rename_all = "camelCase")]
struct ClasspathDump {
    modules: Vec<ModuleDump>,
    /// Feature version of the JVM that wrote the dump; absent in dumps predating the field.
    #[serde(default)]
    jdk_release: Option<u32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModuleDump {
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
    #[serde(default)]
    project: Option<String>,
}

/// Artifacts with coordinates: (group, name) -> version -> file.
/// If modules resolve differently, multiple versions can appear for the same coordinate.
pub type VersionMap = BTreeMap<(String, String), BTreeMap<String, PathBuf>>;

/// Everything present at runtime, aggregated from one dump.
pub struct Universe {
    /// Scan targets: artifact files + build outputs (deduplicated, in first-seen order).
    pub scan_targets: Vec<PathBuf>,
    /// Application build outputs (module classesDirs). Reachability roots.
    pub app_roots: Vec<PathBuf>,
    /// See [`VersionMap`]. Includes project-attributed artifacts (they identify referencing
    /// jars for suggestions); the version DIFF excludes them via `project_coords`.
    pub versions: VersionMap,
    /// Coordinates carried by project/reactor artifacts anywhere in this dump. The version
    /// diff drops these on BOTH sides (a project's own version bump is not a dependency
    /// upgrade), and taking the union with the other dump's set keeps the exclusion symmetric
    /// when only one dump was written by an attribution-aware plugin.
    pub project_coords: BTreeSet<(String, String)>,
    /// Per-module resolved classpaths, in dump order. Empty when the dump carries no
    /// per-module artifact data (upgrade-check then falls back to the merged universe).
    /// Also emptied when any v2 module lacks a name: pairing unnamed modules positionally
    /// would diff unrelated classpaths against each other.
    pub modules: Vec<ModuleUniverse>,
    /// Feature version of the JVM the dump was taken on. `None` for dumps written before
    /// the plugins recorded it, which is what keeps an old before-dump from claiming a
    /// JDK change against a fresh after-dump.
    pub jdk_release: Option<u32>,
}

/// One build module's resolved runtime classpath as dumped by the build-tool plugin.
pub struct ModuleUniverse {
    /// Build-tool module path (":app", ":emr:encounter").
    pub name: String,
    /// This module's own build outputs: per-module scan targets and reachability roots.
    pub classes_dirs: Vec<PathBuf>,
    /// Resolved classpath entries in resolution order.
    pub artifacts: Vec<ModuleArtifact>,
}

/// One classpath entry of a module.
#[derive(Clone)]
pub struct ModuleArtifact {
    /// (group, name, version); None for project/file dependencies.
    pub coordinate: Option<(String, String, String)>,
    pub file: PathBuf,
    /// Producing module path when the entry is a project dependency the build tool
    /// attributed ("project" key, additive in v2). Lets the check substitute that
    /// module's classesDirs when the artifact file has not been built.
    pub project: Option<String>,
}

impl Universe {
    pub fn module(&self, name: &str) -> Option<&ModuleUniverse> {
        self.modules.iter().find(|m| m.name == name)
    }
}

impl ModuleUniverse {
    /// This module's coordinate -> version -> file map (single-version per coordinate in a
    /// consistent resolution, but kept map-shaped to share diffing with the merged universe).
    /// Complete, project-attributed artifacts included; the diff excludes those.
    pub fn versions(&self) -> VersionMap {
        let mut versions: VersionMap = BTreeMap::new();
        for artifact in &self.artifacts {
            if let Some((group, name, version)) = &artifact.coordinate {
                versions
                    .entry((group.clone(), name.clone()))
                    .or_default()
                    .insert(version.clone(), artifact.file.clone());
            }
        }
        versions
    }
}

/// Shared per-artifact bookkeeping for both loaders: version map, project-coordinate set,
/// and the module-view entry. One place so v1 and v2 semantics cannot drift.
fn artifact_entry(
    group: Option<String>,
    name: Option<String>,
    version: Option<String>,
    file: PathBuf,
    project: Option<String>,
    versions: &mut VersionMap,
    project_coords: &mut BTreeSet<(String, String)>,
) -> ModuleArtifact {
    let coordinate = match (group, name, version) {
        (Some(group), Some(name), Some(version)) => {
            versions
                .entry((group.clone(), name.clone()))
                .or_default()
                .insert(version.clone(), file.clone());
            if project.is_some() {
                project_coords.insert((group.clone(), name.clone()));
            }
            Some((group, name, version))
        }
        _ => None,
    };
    ModuleArtifact {
        coordinate,
        file,
        project,
    }
}

/// v2: deduplication + root table for path prefixes (paired with DumpFormat in jvm-plugin-core).
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DumpV2 {
    roots: Vec<String>,
    #[serde(default)]
    jdk_release: Option<u32>,
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
    #[serde(default)]
    project: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModuleV2 {
    #[serde(default)]
    module: Option<String>,
    #[serde(default)]
    classes_dirs: Vec<RootedPath>,
    #[serde(default)]
    artifact_refs: Vec<usize>,
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
    let mut versions: VersionMap = BTreeMap::new();
    let mut project_coords = BTreeSet::new();
    let mut modules = Vec::new();
    for module in dump.modules {
        let mut module_artifacts = Vec::new();
        for artifact in module.artifacts {
            if seen.insert(artifact.file.clone()) {
                scan_targets.push(artifact.file.clone());
            }
            module_artifacts.push(artifact_entry(
                artifact.group,
                artifact.name,
                artifact.version,
                artifact.file,
                artifact.project,
                &mut versions,
                &mut project_coords,
            ));
        }
        for dir in &module.classes_dirs {
            app_roots.push(dir.clone());
            if seen.insert(dir.clone()) {
                scan_targets.push(dir.clone());
            }
        }
        modules.push(ModuleUniverse {
            name: module.module,
            classes_dirs: module.classes_dirs,
            artifacts: module_artifacts,
        });
    }
    Universe {
        jdk_release: dump.jdk_release,
        scan_targets,
        app_roots,
        versions,
        project_coords,
        modules,
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
    let mut versions: VersionMap = BTreeMap::new();
    let mut project_coords = BTreeSet::new();
    // The entity table is deduplicated, so first-seen order is table order.
    let mut table = Vec::with_capacity(dump.artifacts.len());
    for artifact in &dump.artifacts {
        let file = rooted(artifact.root, &artifact.path)?;
        if seen.insert(file.clone()) {
            scan_targets.push(file.clone());
        }
        table.push(artifact_entry(
            artifact.group.clone(),
            artifact.name.clone(),
            artifact.version.clone(),
            file,
            artifact.project.clone(),
            &mut versions,
            &mut project_coords,
        ));
    }
    let mut modules = Vec::new();
    let mut unnamed_module = false;
    for module in &dump.modules {
        let mut classes_dirs = Vec::new();
        for dir in &module.classes_dirs {
            let dir = rooted(dir.root, &dir.path)?;
            app_roots.push(dir.clone());
            if seen.insert(dir.clone()) {
                scan_targets.push(dir.clone());
            }
            classes_dirs.push(dir);
        }
        let mut artifacts = Vec::with_capacity(module.artifact_refs.len());
        for &idx in &module.artifact_refs {
            let a = table
                .get(idx)
                .with_context(|| format!("artifact ref {idx} out of range"))?;
            artifacts.push(a.clone());
        }
        // Every shipped plugin writes the module name; a dump without one cannot be paired
        // by name and positional pairing would diff unrelated modules. Note it and let the
        // caller fall back to the merged universe.
        let Some(name) = module.module.clone() else {
            unnamed_module = true;
            continue;
        };
        modules.push(ModuleUniverse {
            name,
            classes_dirs,
            artifacts,
        });
    }
    if unnamed_module {
        modules.clear();
    }
    Ok(Universe {
        jdk_release: dump.jdk_release,
        scan_targets,
        app_roots,
        versions,
        project_coords,
        modules,
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

/// Coordinates the version diff must ignore: project-attributed on EITHER side. Taking the
/// union keeps the diff symmetric when one dump comes from an older plugin that did not
/// attribute yet — otherwise the attributed side's exclusion makes an unchanged reactor
/// dependency read as Removed, and its jar would be excluded from the scan as a stale old
/// version, fabricating "class removed" violations for the whole sibling module.
pub fn project_coords_union(before: &Universe, after: &Universe) -> BTreeSet<(String, String)> {
    before
        .project_coords
        .union(&after.project_coords)
        .cloned()
        .collect()
}

pub fn diff_dumps(before: &Universe, after: &Universe) -> DependencyChanges {
    diff_version_maps(
        &before.versions,
        &after.versions,
        &project_coords_union(before, after),
    )
}

/// Diff of a before/after version map pair, ignoring the coordinates in `exclude`.
pub fn diff_version_maps(
    before: &VersionMap,
    after: &VersionMap,
    exclude: &BTreeSet<(String, String)>,
) -> DependencyChanges {
    let mut changes = Vec::new();
    let mut old_jars = Vec::new();
    let mut new_jars = Vec::new();

    for (coord, before_versions) in before {
        if exclude.contains(coord) {
            continue;
        }
        let after_versions = after.get(coord);
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
    for (coord, after_versions) in after {
        if !exclude.contains(coord) && !before.contains_key(coord) {
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
        // Both carry the per-module view: name, classesDirs, and the module's own artifacts.
        for u in [&u1, &u2] {
            assert_eq!(u.modules.len(), 1);
            let m = &u.modules[0];
            assert_eq!(m.name, ":app");
            assert_eq!(
                m.classes_dirs,
                vec![PathBuf::from("/repo/app/build/classes/kotlin/main")]
            );
            assert_eq!(m.artifacts.len(), 2);
            assert_eq!(
                m.artifacts[0].coordinate,
                Some((
                    "io.ktor".to_string(),
                    "ktor-io-jvm".to_string(),
                    "2.3.13".to_string()
                ))
            );
            assert_eq!(m.artifacts[1].coordinate, None);
            assert_eq!(m.artifacts[1].file, PathBuf::from("/repo/libs/local.jar"));
        }
        assert_eq!(
            u2.versions[&("io.ktor".to_string(), "ktor-io-jvm".to_string())]["2.3.13"],
            PathBuf::from(
                "/cache/modules-2/files-2.1/io.ktor/ktor-io-jvm/2.3.13/ab/ktor-io-jvm-2.3.13.jar"
            )
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// v2 "project" attribution parses through to ModuleArtifact.project, and per-module
    /// diffing gates on the module's own resolution: a module that keeps its version has no
    /// old jars even when a sibling module upgrades the same coordinate.
    #[test]
    fn per_module_view_attributes_projects_and_diffs_per_module() {
        let dir = std::env::temp_dir().join(format!("uika-permod-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let dump = |lib_version: &str| {
            format!(
                r#"{{"version":2,
                "roots":["/repo/"],
                "artifacts":[
                    {{"group":"g","name":"lib","version":"{lib_version}","root":0,"path":"cache/lib-{lib_version}.jar"}},
                    {{"group":"g","name":"lib","version":"1.0","root":0,"path":"cache/lib-1.0.jar"}},
                    {{"root":0,"path":"shared/build/libs/shared.jar","project":":shared"}}
                ],
                "modules":[
                    {{"module":":app","classesDirs":[{{"root":0,"path":"app/build/classes"}}],"artifactRefs":[0,2]}},
                    {{"module":":pinned","classesDirs":[],"artifactRefs":[1]}},
                    {{"module":":shared","classesDirs":[{{"root":0,"path":"shared/build/classes"}}],"artifactRefs":[]}}
                ]}}"#
            )
        };
        let before_path = dir.join("before.json");
        let after_path = dir.join("after.json");
        std::fs::write(&before_path, dump("1.0")).unwrap();
        std::fs::write(&after_path, dump("2.0")).unwrap();
        let before = load_dump(&before_path).unwrap();
        let after = load_dump(&after_path).unwrap();

        assert_eq!(after.modules.len(), 3);
        let app = after.module(":app").unwrap();
        assert_eq!(app.artifacts[1].project.as_deref(), Some(":shared"));

        // :app moved g:lib 1.0 -> 2.0.
        let exclude = project_coords_union(&before, &after);
        let module_diff = |b: &ModuleUniverse, a: &ModuleUniverse| {
            diff_version_maps(&b.versions(), &a.versions(), &exclude)
        };
        let app_diff = module_diff(before.module(":app").unwrap(), app);
        assert_eq!(
            app_diff.old_jars,
            vec![PathBuf::from("/repo/cache/lib-1.0.jar")]
        );
        assert_eq!(
            app_diff.new_jars,
            vec![PathBuf::from("/repo/cache/lib-2.0.jar")]
        );

        // :pinned still resolves 1.0: nothing to check even though the universe-wide
        // version set for g:lib changed.
        let pinned_diff = module_diff(
            before.module(":pinned").unwrap(),
            after.module(":pinned").unwrap(),
        );
        assert!(pinned_diff.old_jars.is_empty());

        // The universe-wide diff misses :app's upgrade entirely (1.0 is still resolved by
        // :pinned on the after side), which is exactly why upgrade-check works per module.
        let global = diff_dumps(&before, &after);
        assert!(global.old_jars.is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A reactor/project dependency carries coordinates in Maven dumps. With project
    /// attribution it must stay out of the version DIFF (bumping the project's own version
    /// is not a dependency upgrade to check) while remaining in the version maps so
    /// suggestions can still attribute a referencing reactor jar. The exclusion is the
    /// UNION of both dumps' attributed coordinates, so a before dump from an older,
    /// non-attributing plugin does not diff as a removal.
    #[test]
    fn project_attributed_artifacts_are_not_version_diffed() {
        let dir = std::env::temp_dir().join(format!("uika-reactor-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let dump = |v: &str, attributed: bool| {
            let project = if attributed {
                r#""project":":lib","#
            } else {
                ""
            };
            format!(
                r#"{{"version":2,
                "roots":["/repo/"],
                "artifacts":[
                    {{"group":"com.example","name":"lib","version":"{v}",{project}"root":0,"path":"lib/target/lib-{v}.jar"}}
                ],
                "modules":[
                    {{"module":":app","classesDirs":[],"artifactRefs":[0]}},
                    {{"module":":lib","classesDirs":[{{"root":0,"path":"lib/target/classes"}}],"artifactRefs":[]}}
                ]}}"#
            )
        };
        let before_path = dir.join("before.json");
        let after_path = dir.join("after.json");
        std::fs::write(&before_path, dump("1.0.0", true)).unwrap();
        std::fs::write(&after_path, dump("1.1.0", true)).unwrap();
        let before = load_dump(&before_path).unwrap();
        let after = load_dump(&after_path).unwrap();

        // In the maps (for suggestion attribution), out of the diff (not an upgrade).
        assert!(!before.versions.is_empty());
        let exclude = project_coords_union(&before, &after);
        let app_diff = diff_version_maps(
            &before.module(":app").unwrap().versions(),
            &after.module(":app").unwrap().versions(),
            &exclude,
        );
        assert!(app_diff.old_jars.is_empty());
        assert!(app_diff.changes.is_empty());
        assert!(diff_dumps(&before, &after).old_jars.is_empty());
        // The artifact itself still reaches the scan through the module's classpath.
        assert_eq!(after.module(":app").unwrap().artifacts.len(), 1);

        // Asymmetric plugins (before dump written pre-attribution): the union exclusion
        // keeps the identical coordinate from diffing as Removed.
        std::fs::write(&before_path, dump("1.0.0", false)).unwrap();
        let before_old_plugin = load_dump(&before_path).unwrap();
        std::fs::write(&after_path, dump("1.0.0", true)).unwrap();
        let after_same = load_dump(&after_path).unwrap();
        let global = diff_dumps(&before_old_plugin, &after_same);
        assert!(global.changes.is_empty(), "{:?}", global.changes);
        assert!(global.old_jars.is_empty());
        let exclude = project_coords_union(&before_old_plugin, &after_same);
        let app_diff = diff_version_maps(
            &before_old_plugin.module(":app").unwrap().versions(),
            &after_same.module(":app").unwrap().versions(),
            &exclude,
        );
        assert!(app_diff.old_jars.is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A v2 module without a name cannot be paired with its before-side counterpart, so the
    /// whole dump degrades to the merged universe instead of pairing modules positionally.
    #[test]
    fn unnamed_v2_module_disables_the_per_module_view() {
        let dir = std::env::temp_dir().join(format!("uika-unnamed-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("dump.json");
        std::fs::write(
            &path,
            r#"{"version":2,
            "roots":["/repo/"],
            "artifacts":[{"group":"g","name":"lib","version":"1.0","root":0,"path":"lib-1.0.jar"}],
            "modules":[
                {"module":":app","classesDirs":[],"artifactRefs":[0]},
                {"classesDirs":[{"root":0,"path":"x/classes"}],"artifactRefs":[0]}
            ]}"#,
        )
        .unwrap();
        let universe = load_dump(&path).unwrap();
        assert!(universe.modules.is_empty());
        // The merged view still carries everything.
        assert_eq!(universe.scan_targets.len(), 2);
        assert!(!universe.versions.is_empty());
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
            jdk_release: None,
            scan_targets,
            app_roots: Vec::new(),
            versions,
            project_coords: BTreeSet::new(),
            modules: Vec::new(),
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
