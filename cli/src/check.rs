use crate::extract::{class_name_of, extract_hierarchy, extract_refs};
use crate::index::{
    ApiIndex, ClassGraph, MemberKind, MemberResolution, Resolution, Scope, object_sym,
};
use crate::input::LoadedClass;
use crate::intern::Sym;
use crate::model::{
    ACC_FINAL, ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC, ACC_STATIC, MemberKey, RefKind, SymbolRef,
    Violation,
};
use anyhow::Result;
use rayon::prelude::*;
use rustc_hash::{FxHashMap, FxHashSet};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};

pub struct CheckReport {
    pub violations: Vec<Violation>,
    pub warnings: Vec<String>,
    pub scanned_classes: usize,
    /// Number of references that reached a type outside the index and could not be proven unbroken.
    pub unknown_refs: usize,
    /// True when class-load reachability was computed, so each violation carries a reachable flag.
    pub reachability_computed: bool,
    /// Whether any application root matched a scanned class, when reachability was computed.
    /// None when reachability was not computed; Some(false) means app roots were supplied but
    /// none matched (e.g. build outputs not compiled), so the not-proven-reachable labels are
    /// untrustworthy.
    pub app_roots_matched: Option<bool>,
}

/// Pass-1 result for one class. Does not carry member tables
/// (classes whose members are needed are reread precisely in pass 2).
pub struct ParsedTarget {
    pub source: Sym,
    pub class_name: Sym,
    /// None for same-name classes already in the graph (duplicate from another version).
    pub hierarchy: Option<(Option<Sym>, Vec<Sym>)>,
    /// Only set when the entry name differs from "{class_name}.class" (directory outputs, etc.). Used for pass-2 rereads.
    pub entry_override: Option<String>,
    /// Only references whose owner exists in the old index (= automatically scoped to the checked library).
    pub refs: Vec<SymbolRef>,
    /// Class-load edges for reachability (constant-pool classes + forName-shaped strings).
    /// Empty unless edge collection is enabled.
    pub edges: Vec<Sym>,
}

pub struct ParsedTargets {
    pub targets: Vec<ParsedTarget>,
    pub warnings: Vec<String>,
    pub scanned_classes: usize,
}

/// Pass 1: parse loaded classes in parallel and extract hierarchy plus references to old.
/// Hierarchy extraction is also skipped for class names already present in `known`.
/// Class-load edges are collected only when `collect_edges` is set (reachability mode),
/// since they cost extra memory proportional to the whole scanned classpath.
pub fn parse_targets(
    classes: &[LoadedClass],
    old: &ApiIndex,
    known: &ClassGraph,
    collect_edges: bool,
) -> ParsedTargets {
    let results: Vec<Result<ParsedTarget, String>> = classes
        .par_iter()
        .map(|lc| {
            let with_ctx = |e: anyhow::Error| format!("{}!{}: {e}", lc.source, lc.entry_name);
            let rc = crate::classfile::RawClass::parse(&lc.bytes).map_err(with_ctx)?;
            let class_name = class_name_of(&rc).map_err(with_ctx)?;
            let hierarchy = if known.contains(class_name) {
                None
            } else {
                let (_, super_name, interfaces) = extract_hierarchy(&rc).map_err(with_ctx)?;
                Some((super_name, interfaces))
            };
            let entry_override =
                if lc.entry_name.strip_suffix(".class") == Some(class_name.as_str()) {
                    None
                } else {
                    Some(lc.entry_name.clone())
                };
            let refs = extract_refs(&rc, |owner| old.contains_class(owner)).map_err(with_ctx)?;
            let edges = if collect_edges {
                crate::extract::extract_edges(&rc, class_name)
            } else {
                Vec::new()
            };
            Ok(ParsedTarget {
                source: lc.source,
                class_name,
                hierarchy,
                entry_override,
                refs,
                edges,
            })
        })
        .collect();

    let scanned_classes = classes.len();
    let mut targets = Vec::with_capacity(results.len());
    let mut warnings = Vec::new();
    for r in results {
        match r {
            Ok(t) => targets.push(t),
            Err(w) => warnings.push(w),
        }
    }
    ParsedTargets {
        targets,
        warnings,
        scanned_classes,
    }
}

/// Aggregated pass-1 result. Holds only the class hierarchy graph and reference records.
pub struct ScanResult {
    pub graph: ClassGraph,
    /// (source, class_name, refs) only for classes with references.
    records: Vec<(Sym, Sym, Vec<SymbolRef>)>,
    /// Reread locations for classes whose entry name is not "{name}.class" (directory outputs, etc.).
    entry_overrides: FxHashMap<Sym, String>,
    pub warnings: Vec<String>,
    pub scanned_classes: usize,
}

impl ScanResult {
    fn new() -> Self {
        Self {
            graph: ClassGraph::new(),
            records: Vec::new(),
            entry_overrides: FxHashMap::default(),
            warnings: Vec::new(),
            scanned_classes: 0,
        }
    }

    /// Fold parsed results into the graph (duplicate class names are first-wins = JVM classpath resolution order).
    fn merge(&mut self, parsed: ParsedTargets) {
        for t in parsed.targets {
            if let Some((super_name, interfaces)) = t.hierarchy
                && self.graph.insert_if_absent(
                    t.class_name,
                    super_name,
                    &interfaces,
                    &t.edges,
                    t.source,
                )
                && let Some(entry) = t.entry_override
            {
                self.entry_overrides.insert(t.class_name, entry);
            }
            if !t.refs.is_empty() {
                self.records.push((t.source, t.class_name, t.refs));
            }
        }
        self.warnings.extend(parsed.warnings);
        self.scanned_classes += parsed.scanned_classes;
    }
}

/// Pass 1: stream scan units (JAR / class directory) in parallel by chunk and merge
/// them into the hierarchy graph. Since no member tables are kept, peak memory is
/// bounded by the graph plus one chunk of temporaries.
/// Chunks are processed in path order, so duplicate-class winners are deterministic.
pub fn scan_target_paths(
    paths: &[PathBuf],
    old: &ApiIndex,
    collect_edges: bool,
) -> Result<ScanResult> {
    let chunk_size = std::env::var("UIKA_CHUNK")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(rayon::current_num_threads().max(1));
    let mut scanned = ScanResult::new();
    for chunk in paths.chunks(chunk_size) {
        // The graph is immutable while parsing a chunk, so it can skip duplicates without locking.
        let known = &scanned.graph;
        let parsed_chunk: Vec<ParsedTargets> = chunk
            .par_iter()
            .map(|p| {
                // Read and parse by batch to cap concurrently held inflated bytes.
                let mut acc = ParsedTargets {
                    targets: Vec::new(),
                    warnings: Vec::new(),
                    scanned_classes: 0,
                };
                crate::input::for_each_batch(p, 512, |batch| {
                    let parsed = parse_targets(&batch, old, known, collect_edges);
                    acc.targets.extend(parsed.targets);
                    acc.warnings.extend(parsed.warnings);
                    acc.scanned_classes += parsed.scanned_classes;
                    Ok(())
                })?;
                Ok(acc)
            })
            .collect::<Result<_>>()?;
        for parsed in parsed_chunk {
            scanned.merge(parsed);
        }
    }
    scanned.graph.shrink_to_fit();
    Ok(scanned)
}

/// Enumerate classes that reference resolution may visit on the hierarchy graph.
/// Before member tables are fetched, Found cannot terminate traversal early, so take the
/// full reachable closure (a conservative upper bound).
fn collect_wanted(scan: &ScanResult, old: &ApiIndex, new: &ApiIndex) -> FxHashSet<Sym> {
    let mut wanted = FxHashSet::default();
    let mut memo: FxHashSet<(Sym, bool)> = FxHashSet::default();
    for (_, _, refs) in &scan.records {
        for r in refs {
            if r.member.is_none() {
                continue; // Class references only need existence checks, not members.
            }
            for (lib, is_old_side) in [(new, false), (old, true)] {
                if !memo.insert((r.owner, is_old_side)) {
                    continue;
                }
                let mut queue = VecDeque::from([r.owner]);
                let mut seen = FxHashSet::default();
                while let Some(class) = queue.pop_front() {
                    if !seen.insert(class) || class == object_sym() {
                        continue;
                    }
                    if let Some(entry) = lib.classes.get(&class) {
                        if let Some(s) = entry.super_name {
                            queue.push_back(s);
                        }
                        queue.extend(lib.interfaces_of(entry).iter().copied());
                    } else if let Some(node) = scan.graph.get(class) {
                        wanted.insert(class);
                        if let Some(s) = node.super_name {
                            queue.push_back(s);
                        }
                        queue.extend(scan.graph.interfaces_of(node).iter().copied());
                    }
                }
            }
        }
    }
    wanted
}

fn collect_final_wanted(
    old: &ApiIndex,
    new: &ApiIndex,
    graph: &ClassGraph,
    wanted: &mut FxHashSet<Sym>,
) {
    let final_methods = newly_final_methods(old, new);
    if final_methods.is_empty() {
        return;
    }
    for (class_name, _) in graph.iter() {
        if first_ancestor_with_final_methods(class_name, new, graph, &final_methods).is_some() {
            wanted.insert(class_name);
        }
    }
}

/// Pass 2: reread only classes needed for resolution from their origin JAR/directory and
/// build an index with member tables.
fn fetch_members(scan: &ScanResult, wanted: &FxHashSet<Sym>) -> (ApiIndex, Vec<String>) {
    let mut by_source: FxHashMap<Sym, Vec<(Sym, String)>> = FxHashMap::default();
    for &name in wanted {
        let node = scan.graph.get(name).expect("wanted class must be in graph");
        let entry = scan
            .entry_overrides
            .get(&name)
            .cloned()
            .unwrap_or_else(|| format!("{}.class", name.as_str()));
        by_source
            .entry(node.source)
            .or_default()
            .push((name, entry));
    }

    let per_source: Vec<(Vec<crate::model::ClassApi>, Vec<String>)> = by_source
        .par_iter()
        .map(|(source, entries)| {
            let mut apis = Vec::with_capacity(entries.len());
            let mut warnings = Vec::new();
            let fetched =
                crate::input::fetch_entries(Path::new(source.as_str()), entries, |name, bytes| {
                    match crate::classfile::RawClass::parse(bytes)
                        .and_then(|rc| crate::extract::extract_api(&rc))
                    {
                        Ok(api) => apis.push(api),
                        Err(e) => warnings.push(format!("{source}!{name}: {e}")),
                    }
                });
            match fetched {
                Ok(w) => warnings.extend(w),
                Err(e) => warnings.push(format!("{source}: {e}")),
            }
            (apis, warnings)
        })
        .collect();

    let mut index = ApiIndex::new();
    let mut warnings = Vec::new();
    for (apis, w) in per_source {
        for api in apis {
            index.insert_if_absent(api);
        }
        warnings.extend(w);
    }
    (index, warnings)
}

/// Evaluate references and collect violations.
/// Resolution uses the composite scope "new + full scanned classpath" instead of new alone
/// because real JVM linking runs against the full runtime classpath. This avoids false
/// positives for moves to another artifact or copies bundled into fat JARs. The old side
/// is composed the same way to reduce Unknown results when a library hierarchy escapes to
/// a classpath-side parent.
pub fn check_scanned(
    scan: ScanResult,
    old: &ApiIndex,
    new: &ApiIndex,
    reach: Option<crate::reach::ReachInputs>,
) -> CheckReport {
    crate::memstats::report("after pass 1 (graph + reference records)");
    // Compute reachability marks before the graph is consumed below. Cheap relative to
    // the scan (one BFS over the class-load edge arena already built in pass 1).
    let reach_result = reach
        .as_ref()
        .map(|r| crate::reach::reachable_classes(&scan.graph, r));
    let mut wanted = collect_wanted(&scan, old, new);
    collect_final_wanted(old, new, &scan.graph, &mut wanted);
    let (fetched, fetch_warnings) = fetch_members(&scan, &wanted);
    #[cfg(feature = "memstats")]
    {
        let ref_count: usize = scan.records.iter().map(|(_, _, refs)| refs.len()).sum();
        let (syms, sym_bytes) = crate::intern::stats();
        eprintln!(
            "[mem] scale: graph={} wanted={} fetched={} ref_records={} refs={} \
             intern: {} syms / {:.0}MB",
            scan.graph.len(),
            wanted.len(),
            fetched.classes.len(),
            scan.records.len(),
            ref_count,
            syms,
            sym_bytes as f64 / 1024.0 / 1024.0,
        );
    }
    crate::memstats::report("after pass 2 (needed class members fetched)");

    let ScanResult {
        graph,
        records,
        warnings: mut all_warnings,
        scanned_classes,
        ..
    } = scan;
    all_warnings.extend(fetch_warnings);

    let old_scope = Scope::new(vec![old, &fetched]);
    let runtime_scope = Scope::new(vec![new, &fetched]);

    let mut violations = Vec::new();
    let mut unknown_refs = 0usize;
    let mut seen: FxHashSet<(Sym, Sym, SymbolRef)> = FxHashSet::default();
    for (source, class_name, refs) in records {
        for r in refs {
            match verdict(r, class_name, &old_scope, &runtime_scope, &graph) {
                RefVerdict::Ok => {}
                RefVerdict::Unknown => unknown_refs += 1,
                RefVerdict::Broken(reference, reason) => {
                    if seen.insert((source, class_name, reference)) {
                        violations.push(Violation {
                            source,
                            source_class: class_name,
                            reference,
                            reason: reason.to_string(),
                            reachable: None,
                            suggestion: None,
                        });
                    }
                }
            }
        }
    }
    add_final_violations(old, new, &fetched, &graph, &mut violations, &mut seen);

    if let Some(result) = &reach_result {
        for v in &mut violations {
            v.reachable = Some(crate::reach::is_reachable(&result.marks, v.source_class));
        }
        // App roots were supplied but none matched a scanned class (e.g. build outputs were
        // not compiled): every violation then falls into "not proven reachable", which would
        // read as "0 reachable" and be misleading. Say so explicitly.
        if !result.app_root_matched {
            all_warnings.push(
                "reachability: no application root matched a scanned class \
                 (were the project's build outputs compiled?); \
                 all violations are reported as not proven reachable"
                    .to_string(),
            );
        }
    }

    crate::memstats::report("after verdict");
    CheckReport {
        violations,
        warnings: all_warnings,
        scanned_classes,
        unknown_refs,
        reachability_computed: reach_result.is_some(),
        app_roots_matched: reach_result.as_ref().map(|r| r.app_root_matched),
    }
}

/// Check consumer-side classes (pass 1 + pass 2 + verdict). Reachability is not computed here.
pub fn check(targets: &[LoadedClass], old: &ApiIndex, new: &ApiIndex) -> CheckReport {
    let mut scan = ScanResult::new();
    let parsed = parse_targets(targets, old, &scan.graph, false);
    scan.merge(parsed);
    check_scanned(scan, old, new, None)
}

enum RefVerdict {
    Ok,
    /// Reached a type outside the index and cannot be proven.
    Unknown,
    Broken(SymbolRef, &'static str),
}

/// If the entire owner class disappeared, collapse member references into one Class reference
/// (prevents duplicate reports from a Class reference and multiple Methodref entries to the same class).
/// Class existence is checked against the graph (all scan targets), while member resolution
/// uses a scope layered with fetched (only classes that resolution may visit, already reread).
fn verdict(
    r: SymbolRef,
    source_class: Sym,
    old: &Scope,
    runtime: &Scope,
    graph: &ClassGraph,
) -> RefVerdict {
    if !runtime.contains_class(r.owner) && !graph.contains(r.owner) {
        return RefVerdict::Broken(
            SymbolRef {
                kind: RefKind::Class,
                owner: r.owner,
                member: None,
                expected_static: None,
                field_write: None,
            },
            "class removed",
        );
    }
    if r.member.is_none()
        && let Some(access) = runtime.class_access(r.owner)
        && !is_accessible(access, r.owner, source_class, graph)
    {
        return RefVerdict::Broken(r, "class access narrowed");
    }
    let Some(member) = r.member else {
        return RefVerdict::Ok; // Class references are OK if the owner remains.
    };
    let kind = match r.kind {
        RefKind::Field => MemberKind::Field,
        RefKind::Method | RefKind::InterfaceMethod => MemberKind::Method,
        RefKind::Class => return RefVerdict::Ok,
    };
    match runtime.resolve_member(r.owner, member, kind) {
        MemberResolution::Found(found) => {
            if let Some(expected_static) = r.expected_static
                && (found.access & ACC_STATIC != 0) != expected_static
            {
                match old.resolve_member(r.owner, member, kind) {
                    MemberResolution::Found(old_found)
                        if (old_found.access & ACC_STATIC != 0) == expected_static =>
                    {
                        return RefVerdict::Broken(
                            r,
                            if expected_static {
                                "member changed from static to instance"
                            } else {
                                "member changed from instance to static"
                            },
                        );
                    }
                    MemberResolution::Unknown => return RefVerdict::Unknown,
                    _ => return RefVerdict::Ok,
                }
            }
            if !is_accessible(found.access, found.owner, source_class, graph) {
                return RefVerdict::Broken(
                    r,
                    if kind == MemberKind::Field {
                        "field access narrowed"
                    } else {
                        "method access narrowed"
                    },
                );
            }
            if kind == MemberKind::Field
                && r.field_write == Some(true)
                && found.access & ACC_FINAL != 0
                && source_class != found.owner
            {
                match old.resolve_member(r.owner, member, kind) {
                    MemberResolution::Found(old_found) if old_found.access & ACC_FINAL == 0 => {
                        return RefVerdict::Broken(r, "field became final");
                    }
                    MemberResolution::Unknown => return RefVerdict::Unknown,
                    _ => return RefVerdict::Ok,
                }
            }
            RefVerdict::Ok
        }
        MemberResolution::Unknown => RefVerdict::Unknown,
        MemberResolution::NotFound => {
            // References that cannot resolve against old were already inconsistent, not breakage from this update.
            match old.resolve(r.owner, member, kind) {
                Resolution::Found => {
                    let what = if kind == MemberKind::Field {
                        "field removed"
                    } else {
                        "method removed"
                    };
                    RefVerdict::Broken(r, what)
                }
                Resolution::Unknown => RefVerdict::Unknown,
                Resolution::NotFound => RefVerdict::Ok,
            }
        }
    }
}

fn add_final_violations(
    old: &ApiIndex,
    new: &ApiIndex,
    fetched: &ApiIndex,
    graph: &ClassGraph,
    violations: &mut Vec<Violation>,
    seen: &mut FxHashSet<(Sym, Sym, SymbolRef)>,
) {
    let final_classes = newly_final_classes(old, new);
    for (class_name, node) in graph.iter() {
        if let Some(super_name) = node.super_name
            && final_classes.contains(&super_name)
        {
            let reference = SymbolRef {
                kind: RefKind::Class,
                owner: super_name,
                member: None,
                expected_static: None,
                field_write: None,
            };
            if seen.insert((node.source, class_name, reference)) {
                violations.push(Violation {
                    source: node.source,
                    source_class: class_name,
                    reference,
                    reason: "class became final".to_string(),
                    reachable: None,
                    suggestion: None,
                });
            }
        }
    }

    let final_methods = newly_final_methods(old, new);
    if final_methods.is_empty() {
        return;
    }
    for (class_name, node) in graph.iter() {
        let Some(owner) = first_ancestor_with_final_methods(class_name, new, graph, &final_methods)
        else {
            continue;
        };
        let Some(entry) = fetched.classes.get(&class_name) else {
            continue;
        };
        for (key, _) in fetched.methods_of(entry) {
            if final_methods
                .get(&owner)
                .is_some_and(|methods| methods.contains(key))
            {
                let reference = SymbolRef {
                    kind: RefKind::Method,
                    owner,
                    member: Some(*key),
                    expected_static: Some(false),
                    field_write: None,
                };
                if seen.insert((node.source, class_name, reference)) {
                    violations.push(Violation {
                        source: node.source,
                        source_class: class_name,
                        reference,
                        reason: "method became final".to_string(),
                        reachable: None,
                        suggestion: None,
                    });
                }
            }
        }
    }
}

fn newly_final_classes(old: &ApiIndex, new: &ApiIndex) -> FxHashSet<Sym> {
    old.classes
        .iter()
        .filter_map(|(&name, old_entry)| {
            let new_entry = new.classes.get(&name)?;
            (old_entry.access & ACC_FINAL == 0 && new_entry.access & ACC_FINAL != 0).then_some(name)
        })
        .collect()
}

fn newly_final_methods(old: &ApiIndex, new: &ApiIndex) -> FxHashMap<Sym, FxHashSet<MemberKey>> {
    let mut out = FxHashMap::default();
    for (&class, old_entry) in &old.classes {
        if !new.classes.contains_key(&class) {
            continue;
        }
        for (key, old_access) in old.methods_of(old_entry) {
            if old_access & ACC_FINAL != 0 {
                continue;
            }
            if let Some(new_access) = new.direct_method_access(class, *key)
                && new_access & ACC_FINAL != 0
            {
                out.entry(class)
                    .or_insert_with(FxHashSet::default)
                    .insert(*key);
            }
        }
    }
    out
}

fn first_ancestor_with_final_methods(
    class_name: Sym,
    new: &ApiIndex,
    graph: &ClassGraph,
    final_methods: &FxHashMap<Sym, FxHashSet<MemberKey>>,
) -> Option<Sym> {
    let mut next = graph.get(class_name).and_then(|node| node.super_name);
    let mut seen = FxHashSet::default();
    while let Some(class) = next {
        if !seen.insert(class) {
            return None;
        }
        if final_methods.contains_key(&class) {
            return Some(class);
        }
        next = graph
            .get(class)
            .and_then(|node| node.super_name)
            .or_else(|| new.classes.get(&class).and_then(|entry| entry.super_name));
    }
    None
}

fn is_accessible(access: u16, owner: Sym, source_class: Sym, graph: &ClassGraph) -> bool {
    if access & ACC_PUBLIC != 0 {
        return true;
    }
    if access & ACC_PRIVATE != 0 {
        return owner == source_class;
    }
    if same_package(owner, source_class) {
        return true;
    }
    access & ACC_PROTECTED != 0 && is_subclass(source_class, owner, graph)
}

fn same_package(a: Sym, b: Sym) -> bool {
    package_name(a.as_str()) == package_name(b.as_str())
}

fn package_name(name: &str) -> &str {
    name.rsplit_once('/').map_or("", |(pkg, _)| pkg)
}

fn is_subclass(class_name: Sym, target: Sym, graph: &ClassGraph) -> bool {
    let mut next = graph.get(class_name).and_then(|node| node.super_name);
    let mut seen = FxHashSet::default();
    while let Some(class) = next {
        if class == target {
            return true;
        }
        if !seen.insert(class) {
            return false;
        }
        next = graph.get(class).and_then(|node| node.super_name);
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::index::JAVA_LANG_OBJECT;
    use crate::intern::intern;
    use crate::model::{ACC_FINAL, ACC_PUBLIC, ACC_STATIC, ClassApi, MemberKey, build_members};

    fn class(name: &str, methods: &[(&str, &str)]) -> ClassApi {
        ClassApi {
            name: intern(name),
            access: ACC_PUBLIC,
            super_name: Some(intern(JAVA_LANG_OBJECT)),
            interfaces: vec![],
            methods: build_members(
                methods
                    .iter()
                    .map(|(n, d)| (MemberKey::new(n, d), ACC_PUBLIC)),
            ),
            fields: build_members([]),
        }
    }

    fn class_with_method_access(name: &str, methods: &[(&str, &str, u16)]) -> ClassApi {
        ClassApi {
            name: intern(name),
            access: ACC_PUBLIC,
            super_name: Some(intern(JAVA_LANG_OBJECT)),
            interfaces: vec![],
            methods: build_members(
                methods
                    .iter()
                    .map(|(n, d, acc)| (MemberKey::new(n, d), *acc)),
            ),
            fields: build_members([]),
        }
    }

    fn class_with_fields(name: &str, fields: &[(&str, &str, u16)]) -> ClassApi {
        ClassApi {
            name: intern(name),
            access: ACC_PUBLIC,
            super_name: Some(intern(JAVA_LANG_OBJECT)),
            interfaces: vec![],
            methods: build_members([]),
            fields: build_members(
                fields
                    .iter()
                    .map(|(n, d, acc)| (MemberKey::new(n, d), *acc)),
            ),
        }
    }

    fn method_ref(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::Method,
            owner: intern(owner),
            member: Some(MemberKey::new(name, desc)),
            expected_static: None,
            field_write: None,
        }
    }

    fn static_method_ref(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::Method,
            owner: intern(owner),
            member: Some(MemberKey::new(name, desc)),
            expected_static: Some(true),
            field_write: None,
        }
    }

    fn field_write_ref(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::Field,
            owner: intern(owner),
            member: Some(MemberKey::new(name, desc)),
            expected_static: Some(false),
            field_write: Some(true),
        }
    }

    fn broken(v: RefVerdict) -> Option<(SymbolRef, &'static str)> {
        match v {
            RefVerdict::Broken(r, reason) => Some((r, reason)),
            _ => None,
        }
    }

    #[test]
    fn broken_when_removed_in_new_but_resolvable_in_old() {
        let old = ApiIndex::build([class("lib/C", &[("m", "()J")])]);
        let new = ApiIndex::build([class("lib/C", &[])]);
        let v = verdict(
            method_ref("lib/C", "m", "()J"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, "method removed");
    }

    #[test]
    fn ok_when_method_moved_to_superclass() {
        let old = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
        let mut c = class("lib/C", &[]);
        c.super_name = Some(intern("lib/Base"));
        let new = ApiIndex::build([c, class("lib/Base", &[("m", "()V")])]);
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn unresolved_in_both_is_not_reported() {
        let old = ApiIndex::build([class("lib/C", &[])]);
        let new = ApiIndex::build([class("lib/C", &[])]);
        let v = verdict(
            method_ref("lib/C", "phantom", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn class_removal_collapses_member_refs() {
        let old = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
        let new = ApiIndex::build([]);
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        let (r, reason) = broken(v).unwrap();
        assert_eq!(reason, "class removed");
        assert_eq!(r.kind, RefKind::Class);
        assert!(r.member.is_none());
    }

    #[test]
    fn class_provided_by_scanned_classpath_is_not_reported() {
        // Copies bundled into fat JARs or moves to another artifact: not a violation if the
        // runtime classpath provides the class. The graph handles existence; fetched handles members.
        let old = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
        let new = ApiIndex::build([]);
        let fetched = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("lib/C"),
            Some(object_sym()),
            &[],
            &[],
            intern("fat.jar"),
        );
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old, &fetched]),
            &Scope::new(vec![&new, &fetched]),
            &graph,
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn unknown_when_runtime_hierarchy_escapes_scope() {
        let old = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
        let mut c = class("lib/C", &[]);
        c.super_name = Some(intern("ext/Base"));
        let new = ApiIndex::build([c]);
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Unknown));
    }

    #[test]
    fn graph_only_class_without_fetch_is_unknown() {
        // Resolution through a class present in the graph but not fetched is conservatively Unknown.
        let old = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
        let mut c = class("lib/C", &[]);
        c.super_name = Some(intern("cp/Base"));
        let new = ApiIndex::build([c]);
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("cp/Base"),
            Some(object_sym()),
            &[],
            &[],
            intern("cp.jar"),
        );
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &graph,
        );
        assert!(matches!(v, RefVerdict::Unknown));
    }

    #[test]
    fn static_mismatch_is_broken_only_when_old_matched_bytecode() {
        let old = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PUBLIC | ACC_STATIC)],
        )]);
        let new = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let v = verdict(
            static_method_ref("lib/C", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(
            broken(v).unwrap().1,
            "member changed from static to instance"
        );

        let old_already_mismatched = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new_still_mismatched = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let v = verdict(
            static_method_ref("lib/C", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old_already_mismatched]),
            &Scope::new(vec![&new_still_mismatched]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn external_write_to_new_final_field_is_broken() {
        let old = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PUBLIC)])]);
        let new = ApiIndex::build([class_with_fields(
            "lib/C",
            &[("x", "I", ACC_PUBLIC | ACC_FINAL)],
        )]);
        let v = verdict(
            field_write_ref("lib/C", "x", "I"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, "field became final");
    }

    #[test]
    fn external_write_to_already_final_field_is_not_reported() {
        let old = ApiIndex::build([class_with_fields(
            "lib/C",
            &[("x", "I", ACC_PUBLIC | ACC_FINAL)],
        )]);
        let new = ApiIndex::build([class_with_fields(
            "lib/C",
            &[("x", "I", ACC_PUBLIC | ACC_FINAL)],
        )]);
        let v = verdict(
            field_write_ref("lib/C", "x", "I"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn owner_write_to_final_field_is_not_reported() {
        let old = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PUBLIC)])]);
        let new = ApiIndex::build([class_with_fields(
            "lib/C",
            &[("x", "I", ACC_PUBLIC | ACC_FINAL)],
        )]);
        let v = verdict(
            field_write_ref("lib/C", "x", "I"),
            intern("lib/C"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
    }
}
