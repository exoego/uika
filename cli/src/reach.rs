//! Class-load reachability over the scanned classpath.
//!
//! Answers "can this class ever be loaded when the application runs?" as an
//! over-approximation: application classes (--app / dump classesDirs) are roots, and
//! edges are constant-pool class references, superclass/interface links, class-name-shaped
//! string constants (Class.forName patterns), and META-INF/services provider files.
//! Reflection driven purely by external configuration stays invisible, so an unreachable
//! verdict is a prioritization hint, never a reason to drop a violation.

use crate::index::ClassGraph;
use crate::intern::{Sym, intern};
use anyhow::{Context, Result};
use rayon::prelude::*;
use rustc_hash::{FxHashMap, FxHashSet};
use std::collections::VecDeque;
use std::io::Read;
use std::path::{Path, PathBuf};

/// One META-INF/services provider file: service interface -> implementation classes.
pub struct ServiceFile {
    pub iface: Sym,
    pub impls: Vec<Sym>,
}

/// Inputs for the reachability pass.
pub struct ReachInputs {
    /// Origin syms (JAR/directory path as interned by input.rs) of application scan targets.
    pub app_sources: FxHashSet<Sym>,
    pub services: Vec<ServiceFile>,
}

const SERVICES_PREFIX: &str = "META-INF/services/";

/// Collect provider files from all scan targets (JARs and class directories).
/// Read failures per target are returned as warnings; the reachability pass stays
/// conservative without them, it just sees fewer dynamic edges.
pub fn collect_services(paths: &[PathBuf]) -> (Vec<ServiceFile>, Vec<String>) {
    let per_path: Vec<(Vec<ServiceFile>, Option<String>)> = paths
        .par_iter()
        .map(|p| match services_of(p) {
            Ok(files) => (files, None),
            Err(e) => (Vec::new(), Some(format!("{}: {e}", p.display()))),
        })
        .collect();
    let mut services = Vec::new();
    let mut warnings = Vec::new();
    for (files, warning) in per_path {
        services.extend(files);
        warnings.extend(warning);
    }
    (services, warnings)
}

fn services_of(path: &Path) -> Result<Vec<ServiceFile>> {
    if path.is_dir() {
        return services_of_dir(path);
    }
    let file =
        std::fs::File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
    let reader = crate::window::WindowedReader::new(file, 256 * 1024);
    let mut archive = zip::ZipArchive::new(reader)
        .with_context(|| format!("not a zip/jar: {}", path.display()))?;
    let names: Vec<String> = archive
        .file_names()
        .filter(|n| n.strip_prefix(SERVICES_PREFIX).is_some_and(is_service_name))
        .map(str::to_string)
        .collect();
    let mut out = Vec::new();
    let mut bytes = Vec::new();
    for name in names {
        // Skip a single unreadable provider file rather than dropping the whole JAR's
        // providers; fewer SPI edges only makes reachability more conservative.
        let read_ok = match archive.by_name(&name) {
            Ok(mut entry) => {
                bytes.clear();
                entry.read_to_end(&mut bytes).is_ok()
            }
            Err(_) => false,
        };
        if read_ok {
            push_service(&mut out, &name[SERVICES_PREFIX.len()..], &bytes);
        }
    }
    Ok(out)
}

fn services_of_dir(path: &Path) -> Result<Vec<ServiceFile>> {
    let dir = path.join("META-INF/services");
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return Ok(out); // No services directory: nothing to collect.
    };
    for entry in entries {
        let entry = entry?;
        let file_name = entry.file_name();
        let Some(name) = file_name.to_str().filter(|n| is_service_name(n)) else {
            continue;
        };
        if entry.file_type()?.is_file() {
            push_service(&mut out, name, &std::fs::read(entry.path())?);
        }
    }
    Ok(out)
}

/// Provider file names are dotted FQNs; skip stray non-class entries.
fn is_service_name(name: &str) -> bool {
    !name.is_empty() && !name.contains('/')
}

fn push_service(out: &mut Vec<ServiceFile>, dotted_iface: &str, bytes: &[u8]) {
    let Ok(text) = std::str::from_utf8(bytes) else {
        return;
    };
    let impls: Vec<Sym> = text
        .lines()
        .map(|line| line.split('#').next().unwrap_or("").trim())
        .filter(|line| !line.is_empty())
        .map(|line| intern(&line.replace('.', "/")))
        .collect();
    if !impls.is_empty() {
        out.push(ServiceFile {
            iface: intern(&dotted_iface.replace('.', "/")),
            impls,
        });
    }
}

/// Result of a reachability pass: per-Sym marks plus whether any application root actually
/// matched a scanned class (false means the given app roots contributed no roots, e.g. their
/// build outputs were not compiled or not scanned).
pub struct Reachability {
    pub marks: Vec<bool>,
    pub app_root_matched: bool,
}

/// BFS from application roots. Marks are indexed by Sym::index; syms interned after this call
/// are trivially unmarked (guarded by bounds checks in is_reachable).
pub fn reachable_classes(graph: &ClassGraph, inputs: &ReachInputs) -> Reachability {
    let mut marks = vec![false; crate::intern::table_len()];
    let mut queue: VecDeque<Sym> = VecDeque::new();

    let mut app_root_matched = false;
    for (name, node) in graph.iter() {
        if inputs.app_sources.contains(&node.source) {
            app_root_matched = true;
            queue.push_back(name);
        }
    }
    // Providers whose service interface is outside the scanned scope (JDK interfaces like
    // java.sql.Driver): the trigger cannot be observed, so treat them as roots.
    let mut providers: FxHashMap<Sym, Vec<Sym>> = FxHashMap::default();
    for service in &inputs.services {
        if graph.contains(service.iface) {
            providers
                .entry(service.iface)
                .or_default()
                .extend(&service.impls);
        } else {
            queue.extend(&service.impls);
        }
    }

    while let Some(sym) = queue.pop_front() {
        let Some(mark) = marks.get_mut(sym.index()) else {
            continue;
        };
        if std::mem::replace(mark, true) {
            continue;
        }
        if let Some(node) = graph.get(sym) {
            queue.extend(node.super_name);
            queue.extend(graph.interfaces_of(node));
            queue.extend(graph.refs_of(node));
        }
        if let Some(impls) = providers.get(&sym) {
            queue.extend(impls);
        }
    }
    Reachability {
        marks,
        app_root_matched,
    }
}

pub fn is_reachable(marks: &[bool], sym: Sym) -> bool {
    marks.get(sym.index()).copied().unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::index::object_sym;

    fn graph_with(edges: &[(&str, &[&str], &str)]) -> ClassGraph {
        let mut graph = ClassGraph::new();
        for (name, refs, source) in edges {
            let refs: Vec<Sym> = refs.iter().map(|r| intern(r)).collect();
            graph.insert_if_absent(intern(name), Some(object_sym()), &[], &refs, intern(source));
        }
        graph
    }

    fn inputs(app_sources: &[&str], services: Vec<ServiceFile>) -> ReachInputs {
        ReachInputs {
            app_sources: app_sources.iter().map(|s| intern(s)).collect(),
            services,
        }
    }

    #[test]
    fn marks_transitive_refs_from_app_roots_only() {
        let graph = graph_with(&[
            ("app/Main", &["lib/Used"], "app-dir"),
            ("lib/Used", &["lib/Indirect"], "lib.jar"),
            ("lib/Indirect", &[], "lib.jar"),
            ("lib/Orphan", &["lib/OrphanDep"], "lib.jar"),
            ("lib/OrphanDep", &[], "lib.jar"),
        ]);
        let result = reachable_classes(&graph, &inputs(&["app-dir"], vec![]));
        assert!(result.app_root_matched);
        let marks = result.marks;
        assert!(is_reachable(&marks, intern("app/Main")));
        assert!(is_reachable(&marks, intern("lib/Used")));
        assert!(is_reachable(&marks, intern("lib/Indirect")));
        assert!(!is_reachable(&marks, intern("lib/Orphan")));
        assert!(!is_reachable(&marks, intern("lib/OrphanDep")));
    }

    #[test]
    fn app_root_not_matched_when_source_absent() {
        // App root points at a source that no scanned class carries (e.g. an unbuilt dir):
        // nothing is seeded, and the caller can warn instead of showing a silent "0 reachable".
        let graph = graph_with(&[("lib/Used", &["lib/Indirect"], "lib.jar")]);
        let result = reachable_classes(&graph, &inputs(&["app-dir"], vec![]));
        assert!(!result.app_root_matched);
        assert!(!is_reachable(&result.marks, intern("lib/Used")));
    }

    #[test]
    fn hierarchy_edges_mark_supertypes() {
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("app/Sub"),
            Some(intern("lib/Base")),
            &[intern("lib/Iface")],
            &[],
            intern("app-dir"),
        );
        graph.insert_if_absent(
            intern("lib/Base"),
            Some(object_sym()),
            &[],
            &[],
            intern("lib.jar"),
        );
        graph.insert_if_absent(intern("lib/Iface"), None, &[], &[], intern("lib.jar"));
        let marks = reachable_classes(&graph, &inputs(&["app-dir"], vec![])).marks;
        assert!(is_reachable(&marks, intern("lib/Base")));
        assert!(is_reachable(&marks, intern("lib/Iface")));
    }

    #[test]
    fn service_impls_follow_iface_reachability() {
        let graph = graph_with(&[
            ("app/Main", &["lib/Spi"], "app-dir"),
            ("lib/Spi", &[], "lib.jar"),
            ("lib/SpiImpl", &["lib/SpiImplDep"], "lib.jar"),
            ("lib/SpiImplDep", &[], "lib.jar"),
            ("lib/OtherSpi", &[], "lib.jar"),
            ("lib/OtherImpl", &[], "lib.jar"),
        ]);
        let services = vec![
            ServiceFile {
                iface: intern("lib/Spi"),
                impls: vec![intern("lib/SpiImpl")],
            },
            ServiceFile {
                iface: intern("lib/OtherSpi"),
                impls: vec![intern("lib/OtherImpl")],
            },
        ];
        let marks = reachable_classes(&graph, &inputs(&["app-dir"], services)).marks;
        assert!(is_reachable(&marks, intern("lib/SpiImpl")));
        assert!(is_reachable(&marks, intern("lib/SpiImplDep")));
        // lib/OtherSpi is in the graph but nothing references it -> impls stay unreachable.
        assert!(!is_reachable(&marks, intern("lib/OtherImpl")));
    }

    #[test]
    fn service_iface_outside_scope_makes_impls_roots() {
        let graph = graph_with(&[("lib/JdbcDriver", &[], "lib.jar")]);
        let services = vec![ServiceFile {
            iface: intern("java/sql/Driver"),
            impls: vec![intern("lib/JdbcDriver")],
        }];
        let marks = reachable_classes(&graph, &inputs(&["app-dir"], services)).marks;
        assert!(is_reachable(&marks, intern("lib/JdbcDriver")));
    }

    #[test]
    fn parses_service_file_contents() {
        let mut out = Vec::new();
        push_service(
            &mut out,
            "com.example.Spi",
            b"# comment\ncom.example.ImplA\n\n  com.example.ImplB  # trailing\n",
        );
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].iface, intern("com/example/Spi"));
        assert_eq!(
            out[0].impls,
            vec![intern("com/example/ImplA"), intern("com/example/ImplB")]
        );
    }
}
