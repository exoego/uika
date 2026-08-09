use crate::extract::{Hierarchy, class_name_of, extract_hierarchy, extract_refs};
use crate::index::{
    ApiIndex, ClassGraph, MemberKind, MemberResolution, Resolution, Scope, is_object_method,
    object_sym,
};
use crate::input::LoadedClass;
use crate::intern::Sym;
use crate::model::{
    ACC_ABSTRACT, ACC_BRIDGE, ACC_FINAL, ACC_INTERFACE, ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC,
    ACC_STATIC, ACC_SYNTHETIC, MemberKey, Reason, RefKind, SymbolRef, Violation, Visibility,
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
    /// Number of violations dropped by --exclude-file rules. Always 0 here; the caller
    /// (lib.rs::run_check) applies exclude rules after check_scanned returns and overwrites this.
    pub suppressed: usize,
    /// True when class-load reachability was computed, so each violation carries a reachable flag.
    pub reachability_computed: bool,
    /// Whether any application root matched a scanned class, when reachability was computed.
    /// None when reachability was not computed; Some(false) means app roots were supplied but
    /// none matched (e.g. build outputs not compiled), so the not-proven-reachable labels are
    /// untrustworthy.
    pub app_roots_matched: Option<bool>,
    /// Scan target paths actually admitted (after missing-path skips, stale-old-version
    /// exclusion, and duplicate dedup). 0 here; lib.rs::run_check_with_indexes, which owns
    /// the admission step, overwrites it. Feeds the plain-check header only, never JSON.
    pub scan_targets: usize,
}

/// Pass-1 result for one class. Does not carry member tables
/// (classes whose members are needed are reread precisely in pass 2).
pub struct ParsedTarget {
    pub source: Sym,
    pub class_name: Sym,
    /// None for same-name classes already in the graph (duplicate from another version).
    pub hierarchy: Option<Hierarchy>,
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
    /// (owner, member) of method references to a newly-abstract member, ANY owner —
    /// invocation evidence for the latent tier. Empty when nothing became abstract.
    /// Per batch rather than per class: a Vec on `ParsedTarget` cost ~35MB RSS on the
    /// stress workload for no gain.
    pub invocations: Vec<(Sym, MemberKey)>,
}

/// Pass 1: parse loaded classes in parallel and extract hierarchy plus references to old.
/// Hierarchy extraction is also skipped for class names already present in `known`.
/// Class-load edges are collected only when `collect_edges` is set (reachability mode),
/// since they cost extra memory proportional to the whole scanned classpath.
pub fn parse_targets(
    classes: &[LoadedClass],
    old_names: &FxHashSet<&str>,
    known: &ClassGraph,
    collect_edges: bool,
    abstract_probe: &FxHashMap<(&str, &str), MemberKey>,
) -> ParsedTargets {
    // Evidence rides alongside each result and is folded away below. Collecting it here
    // rather than in a second pass matters: reparsing the batch cost ~21% CPU on the
    // stress workload.
    type Parsed = (ParsedTarget, Vec<(Sym, MemberKey)>);
    let results: Vec<Result<Parsed, String>> = classes
        .par_iter()
        .map(|lc| {
            let with_ctx = |e: anyhow::Error| format!("{}!{}: {e}", lc.source, lc.entry_name);
            let rc = crate::classfile::RawClass::parse(&lc.bytes).map_err(with_ctx)?;
            let class_name = class_name_of(&rc).map_err(with_ctx)?;
            // A class already in the graph before this chunk is a guaranteed first-wins
            // loser: the graph only grows, so merge will drop this copy's hierarchy,
            // references, and edges. Skip extracting them (references dominate the
            // remaining per-class work and duplicates are a large share of the scan).
            //
            // Evidence is swept even here. `known` holds whatever the earlier chunks
            // merged, and chunk size defaults to the thread count, so skipping it would
            // make invocation_found vary by core count.
            if known.contains(class_name) {
                let invocations = if abstract_probe.is_empty() {
                    Vec::new()
                } else {
                    crate::extract::extract_invocation_evidence(&rc, abstract_probe)
                };
                return Ok((
                    ParsedTarget {
                        source: lc.source,
                        class_name,
                        hierarchy: None,
                        entry_override: None,
                        refs: Vec::new(),
                        edges: Vec::new(),
                    },
                    invocations,
                ));
            }
            let (_, hierarchy) = extract_hierarchy(&rc).map_err(with_ctx)?;
            let entry_override =
                if lc.entry_name.strip_suffix(".class") == Some(class_name.as_str()) {
                    None
                } else {
                    Some(lc.entry_name.clone())
                };
            let refs = extract_refs(&rc, |owner| old_names.contains(owner)).map_err(with_ctx)?;
            let edges = if collect_edges {
                crate::extract::extract_edges(&rc, class_name)
            } else {
                Vec::new()
            };
            // The probe is NOT empty on a typical upgrade — it covers newly ADDED
            // abstract methods, not just concrete->abstract ones — so this sweep is the
            // normal path, not a rare one. ~3% CPU on the stress workload.
            let invocations = if abstract_probe.is_empty() {
                Vec::new()
            } else {
                crate::extract::extract_invocation_evidence(&rc, abstract_probe)
            };
            Ok((
                ParsedTarget {
                    source: lc.source,
                    class_name,
                    hierarchy: Some(hierarchy),
                    entry_override,
                    refs,
                    edges,
                },
                invocations,
            ))
        })
        .collect();

    let scanned_classes = classes.len();
    let mut targets = Vec::with_capacity(results.len());
    let mut warnings = Vec::new();
    let mut invocations = Vec::new();
    for r in results {
        match r {
            Ok((t, mut inv)) => {
                targets.push(t);
                invocations.append(&mut inv);
            }
            Err(w) => warnings.push(w),
        }
    }
    ParsedTargets {
        targets,
        warnings,
        scanned_classes,
        invocations,
    }
}

/// Aggregated pass-1 result. Holds only the class hierarchy graph and reference records.
pub struct ScanResult {
    pub graph: ClassGraph,
    /// (source, class_name, refs) only for classes with references.
    records: Vec<(Sym, Sym, Vec<SymbolRef>)>,
    /// Reread locations for classes whose entry name is not "{name}.class" (directory outputs, etc.).
    entry_overrides: FxHashMap<Sym, String>,
    /// Distinct (owner, member) method references matching a newly-abstract member —
    /// invocation evidence for the latent classification, deduplicated across the scan.
    /// Unlike `records` this is NOT filtered by first-wins (see `ParsedTargets`).
    invocations: FxHashSet<(Sym, MemberKey)>,
    pub warnings: Vec<String>,
    pub scanned_classes: usize,
}

impl ScanResult {
    fn new() -> Self {
        Self {
            graph: ClassGraph::new(),
            records: Vec::new(),
            entry_overrides: FxHashMap::default(),
            invocations: FxHashSet::default(),
            warnings: Vec::new(),
            scanned_classes: 0,
        }
    }

    /// Merge invocation evidence found outside pass 1 (the new library's own jars).
    pub fn extend_invocations(&mut self, extra: Vec<(Sym, MemberKey)>) {
        self.invocations.extend(extra);
    }

    /// Fold parsed results into the graph (duplicate class names are first-wins = JVM classpath resolution order).
    fn merge(&mut self, parsed: ParsedTargets) {
        for t in parsed.targets {
            // A definition that loses first-wins never loads at runtime, so its
            // references are dropped along with its hierarchy. Keeping them would
            // judge another version's bytecode against the winner's hierarchy
            // (e.g. a copy whose same-named class has a different superclass),
            // which produced false positives.
            let won = match t.hierarchy {
                Some(h) => self.graph.insert_if_absent(
                    t.class_name,
                    h.super_name,
                    &h.interfaces,
                    &t.edges,
                    h.nest_host,
                    t.source,
                ),
                // Parse-time skip: the class name was already in the graph before this chunk.
                None => false,
            };
            if won {
                if let Some(entry) = t.entry_override {
                    self.entry_overrides.insert(t.class_name, entry);
                }
                if !t.refs.is_empty() {
                    self.records.push((t.source, t.class_name, t.refs));
                }
            }
        }
        self.invocations.extend(parsed.invocations);
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
    new: &ApiIndex,
    collect_edges: bool,
) -> Result<ScanResult> {
    let chunk_size = std::env::var("UIKA_CHUNK")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(rayon::current_num_threads().max(1));
    // Build the owner filter once: extract_refs tests candidate owners against these raw
    // names instead of interning every constant-pool owner just to reject it.
    let old_names = old.class_name_set();
    // Raw name+descriptor pairs of newly-abstract methods. Empty only when the upgrade
    // made nothing abstract, in which case pass 1 skips evidence collection entirely.
    let abstract_probe = abstract_member_probe(old, new);
    // Decide, from central directories alone (no inflate), which entries to inflate: a
    // byte-identical duplicate class bundled in a later JAR is skipped because first-wins
    // would discard it anyway. On large classpaths most scanned classes are such duplicates.
    let (keep_sets, skipped_dups) = crate::input::representative_offsets(paths);
    let mut scanned = ScanResult::new();
    for (chunk_index, chunk) in paths.chunks(chunk_size).enumerate() {
        let base = chunk_index * chunk_size;
        // The graph is immutable while parsing a chunk, so it can skip duplicates without locking.
        let known = &scanned.graph;
        let parsed_chunk: Vec<ParsedTargets> = chunk
            .par_iter()
            .enumerate()
            .map(|(i, p)| {
                let keep = keep_sets[base + i].as_ref();
                // Read and parse by batch to cap concurrently held inflated bytes.
                let mut acc = ParsedTargets {
                    targets: Vec::new(),
                    warnings: Vec::new(),
                    scanned_classes: 0,
                    invocations: Vec::new(),
                };
                crate::input::for_each_batch(p, 512, keep, |batch| {
                    let parsed =
                        parse_targets(&batch, &old_names, known, collect_edges, &abstract_probe);
                    acc.targets.extend(parsed.targets);
                    acc.warnings.extend(parsed.warnings);
                    acc.scanned_classes += parsed.scanned_classes;
                    acc.invocations.extend(parsed.invocations);
                    Ok(())
                })?;
                Ok(acc)
            })
            .collect::<Result<_>>()?;
        for parsed in parsed_chunk {
            scanned.merge(parsed);
        }
    }
    // Count the skipped byte-identical duplicates too, so the reported scanned-class total
    // stays the size of the whole classpath rather than only the entries actually inflated.
    scanned.scanned_classes += skipped_dups;
    scanned.graph.shrink_to_fit();
    Ok(scanned)
}

/// Enumerate classes that reference resolution may visit on the hierarchy graph.
/// Before member tables are fetched, Found cannot terminate traversal early, so take the
/// full reachable closure (a conservative upper bound).
/// The second returned set holds hierarchy escapes: classes traversal reached that are in
/// no analyzed scope (mostly JDK types). Only collected when `collect_escapes` is set
/// (they become the roots of the opt-in JDK index).
fn collect_wanted(
    scan: &ScanResult,
    old: &ApiIndex,
    new: &ApiIndex,
    collect_escapes: bool,
) -> (FxHashSet<Sym>, FxHashSet<Sym>) {
    let mut wanted = FxHashSet::default();
    let mut escapes = FxHashSet::default();
    let mut memo: FxHashSet<(Sym, bool)> = FxHashSet::default();
    for (_, _, refs) in &scan.records {
        for r in refs {
            if r.member.is_none() {
                // Class references only need existence checks, not members. The
                // owner must still seed the JDK escape roots: without it, an
                // owner the modeled JDK provides would verdict "class removed"
                // while a member ref to the same owner elsewhere would flip it
                // to Ok (the verdict must not depend on unrelated references).
                if collect_escapes
                    && !new.classes.contains_key(&r.owner)
                    && !scan.graph.contains(r.owner)
                {
                    escapes.insert(r.owner);
                }
                continue;
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
                    } else if collect_escapes {
                        escapes.insert(class);
                    }
                }
            }
        }
    }
    (wanted, escapes)
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

/// Visit `start` and its transitive supertypes (superclasses and interfaces). Scanned
/// classes take their hierarchy from the graph; library classes from `lib` (the new
/// index). A type visible in neither is still visited (so callers can test membership)
/// but contributes no further edges. java/lang/Object terminates the walk.
fn for_each_supertype(
    start: Sym,
    lib: &ApiIndex,
    graph: &ClassGraph,
    mut on_type: impl FnMut(Sym),
) {
    let mut queue = VecDeque::from([start]);
    let mut seen = FxHashSet::default();
    while let Some(class) = queue.pop_front() {
        if !seen.insert(class) || class == object_sym() {
            continue;
        }
        on_type(class);
        if let Some(entry) = lib.classes.get(&class) {
            if let Some(s) = entry.super_name {
                queue.push_back(s);
            }
            queue.extend(lib.interfaces_of(entry).iter().copied());
        } else if let Some(node) = graph.get(class) {
            if let Some(s) = node.super_name {
                queue.push_back(s);
            }
            queue.extend(graph.interfaces_of(node).iter().copied());
        }
    }
}

/// Classes that `add_abstract_method_violations` may need to resolve through: a scanned
/// class inheriting a newly-abstract method, plus every scanned class on its supertype
/// closure. They are added to `wanted` so pass 2 fetches their member tables; without the
/// intermediate chain, `resolve_member` would escape to a graph-only class and answer
/// Unknown, hiding a real break. Library-side ancestors already carry members in `new`,
/// so only scanned (graph) classes are collected. Empty and free when nothing became
/// abstract.
fn collect_abstract_wanted(
    old: &ApiIndex,
    new: &ApiIndex,
    graph: &ClassGraph,
    wanted: &mut FxHashSet<Sym>,
) {
    let abstract_methods = methods_newly_abstract(old, new);
    if abstract_methods.is_empty() {
        return;
    }
    // One reused buffer, not a fresh Vec per class (the graph spans the whole scan).
    let mut scanned_chain: Vec<Sym> = Vec::new();
    for (class_name, _) in graph.iter() {
        let mut inherits = false;
        scanned_chain.clear();
        for_each_supertype(class_name, new, graph, |anc| {
            inherits |= abstract_methods.contains_key(&anc);
            if graph.contains(anc) {
                scanned_chain.push(anc);
            }
        });
        if inherits {
            wanted.extend(scanned_chain.iter().copied());
        }
    }
}

/// Direct-super edges of classes scanned from upgraded (new-version) scan targets:
/// the inputs to the version-lag check in `add_extends_final_violations`. Supers that
/// only exist on the scanned classpath are added to `wanted` so pass 2 fetches their
/// access flags. Only the direct superclass matters: a final class has no subclasses,
/// so a deeper ancestor can never be the broken edge.
fn collect_upgraded_super_edges(
    graph: &ClassGraph,
    upgraded_sources: &FxHashSet<Sym>,
    new: &ApiIndex,
    wanted: &mut FxHashSet<Sym>,
    mut escapes: Option<&mut FxHashSet<Sym>>,
) -> Vec<(Sym, Sym, Sym)> {
    if upgraded_sources.is_empty() {
        return Vec::new();
    }
    let mut edges = Vec::new();
    for (class_name, node) in graph.iter() {
        if !upgraded_sources.contains(&node.source) {
            continue;
        }
        let Some(super_name) = node.super_name else {
            continue;
        };
        if super_name == object_sym() {
            continue;
        }
        // Each super is classified exactly once: resolved from the new index
        // (nothing to fetch), fetchable from its scanned origin (fetch_members
        // requires wanted classes to be in the graph), or outside every scope.
        // The last kind feeds the JDK escape roots when the layer is on;
        // without it the check skips them, the same conservative direction as
        // Unknown.
        if new.classes.contains_key(&super_name) {
            // Access flags come from the new index directly.
        } else if graph.contains(super_name) {
            wanted.insert(super_name);
        } else if let Some(escapes) = escapes.as_deref_mut() {
            escapes.insert(super_name);
        }
        edges.push((class_name, super_name, node.source));
    }
    edges
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
    upgraded_sources: &FxHashSet<Sym>,
    jdk: Option<&mut crate::jdk::JdkIndexer>,
    reach: Option<crate::reach::ReachInputs>,
    mut verdicts: Option<&mut crate::verdicts::VerdictWriter>,
) -> CheckReport {
    crate::memstats::report("after pass 1 (graph + reference records)");
    // Compute reachability marks before the graph is consumed below. Cheap relative to
    // the scan (one BFS over the class-load edge arena already built in pass 1).
    let reach_result = reach
        .as_ref()
        .map(|r| crate::reach::reachable_classes(&scan.graph, r));
    let jdk_on = jdk.is_some();
    let (mut wanted, mut escapes) = collect_wanted(&scan, old, new, jdk_on);
    collect_final_wanted(old, new, &scan.graph, &mut wanted);
    collect_abstract_wanted(old, new, &scan.graph, &mut wanted);
    let lag_edges = collect_upgraded_super_edges(
        &scan.graph,
        upgraded_sources,
        new,
        &mut wanted,
        jdk_on.then_some(&mut escapes),
    );
    let (fetched, fetch_warnings) = fetch_members(&scan, &wanted);
    // The JDK index is built from the escape roots' transitive closure inside ct.sym.
    // It is layered into BOTH scopes below, so ct.sym incompleteness resolves NotFound
    // on both sides and the old-relative gate keeps it unreported.
    let (jdk_index, jdk_warnings) = match jdk {
        Some(indexer) => indexer.fetch_closure(escapes),
        None => (ApiIndex::new(), Vec::new()),
    };
    crate::memstats::report("after jdk closure fetch (ct.sym)");
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
        invocations,
        warnings: mut all_warnings,
        scanned_classes,
        ..
    } = scan;
    all_warnings.extend(fetch_warnings);
    all_warnings.extend(jdk_warnings);

    let mut old_layers = vec![old, &fetched];
    let mut runtime_layers = vec![new, &fetched];
    if jdk_on {
        old_layers.push(&jdk_index);
        runtime_layers.push(&jdk_index);
    }
    let old_scope = Scope::new(old_layers);
    let runtime_scope = Scope::new(runtime_layers);

    let mut violations = Vec::new();
    let mut unknown_refs = 0usize;
    let mut seen: FxHashSet<(Sym, Sym, SymbolRef)> = FxHashSet::default();
    for (source, class_name, refs) in records {
        for r in refs {
            let v = verdict(r, class_name, &old_scope, &runtime_scope, &graph);
            if let Some(w) = verdicts.as_deref_mut() {
                let (name, reason) = match &v {
                    RefVerdict::Ok => ("ok", None),
                    RefVerdict::Unknown => ("unknown", None),
                    RefVerdict::Broken(_, reason) => ("broken", Some(*reason)),
                };
                // The raw reference, not the collapsed one a Broken verdict may carry:
                // the stream is an evaluation surface and keeps what the bytecode says.
                w.record(source, class_name, &r, name, reason.map(Reason::as_str));
            }
            match v {
                RefVerdict::Ok => {}
                RefVerdict::Unknown => unknown_refs += 1,
                RefVerdict::Broken(reference, reason) => {
                    push_violation(
                        &mut violations,
                        &mut seen,
                        source,
                        class_name,
                        reference,
                        reason,
                    );
                }
            }
        }
    }
    add_final_violations(old, new, &fetched, &graph, &mut violations, &mut seen);
    add_extends_final_violations(&lag_edges, old, &runtime_scope, &mut violations, &mut seen);
    add_kind_flip_violations(old, new, &graph, &mut violations, &mut seen);
    add_abstract_method_violations(
        &old_scope,
        &runtime_scope,
        old,
        new,
        &fetched,
        &graph,
        &invocations,
        &mut violations,
        &mut seen,
    );

    // Canonical order, sorted by string value (never Sym ids): the reference loop
    // is already deterministic, but add_final_violations discovers violations in
    // FxHashMap iteration order, which varies run to run. Sorting here makes the
    // JSON report (and the golden files pinning it) reproducible.
    violations.sort_by_cached_key(violation_sort_key);

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
                 violations are not ranked by reachability in this run"
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
        suppressed: 0,
        reachability_computed: reach_result.is_some(),
        app_roots_matched: reach_result.as_ref().map(|r| r.app_root_matched),
        scan_targets: 0,
    }
}

/// Canonical report order for violations, by string value (never Sym ids — interning order
/// is nondeterministic). Shared by check_scanned and the per-module upgrade-check merger so
/// the two orders cannot drift.
pub fn violation_sort_key(
    v: &Violation,
) -> (
    &'static str,
    &'static str,
    &'static str,
    Option<(&'static str, &'static str)>,
    &'static str,
) {
    (
        v.source.as_str(),
        v.source_class.as_str(),
        v.reference.owner.as_str(),
        v.reference
            .member
            .map(|m| (m.name.as_str(), m.descriptor.as_str())),
        v.reason.as_str(),
    )
}

/// Check consumer-side classes (pass 1 + pass 2 + verdict). Reachability is not computed here.
/// `library_classes` is the new-version library's own bytecode — the same classes `new` was
/// built from — swept for invocation evidence exactly as the CLI does with the `--new` jars.
/// It is not optional in the "nice to have" sense: passing `&[]` while the library itself is
/// the only caller of a newly-abstract member reports that real break as latent (the koin
/// `Logger.display` shape, pinned by `koin_abstract_break_is_invocable_via_the_librarys_own_call`),
/// which `--fail-on reachable` then lets through.
pub fn check(
    targets: &[LoadedClass],
    old: &ApiIndex,
    new: &ApiIndex,
    library_classes: &[LoadedClass],
) -> CheckReport {
    let probe = abstract_member_probe(old, new);
    let mut scan = ScanResult::new();
    let parsed = parse_targets(targets, &old.class_name_set(), &scan.graph, false, &probe);
    scan.merge(parsed);
    if !probe.is_empty() {
        scan.extend_invocations(class_invocation_evidence(library_classes, &probe));
    }
    check_scanned(scan, old, new, &FxHashSet::default(), None, None, None)
}

enum RefVerdict {
    Ok,
    /// Reached a type outside the index and cannot be proven.
    Unknown,
    Broken(SymbolRef, Reason),
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
                instantiated: None,
            },
            Reason::ClassRemoved,
        );
    }
    // `new X` where X is now abstract or an interface throws InstantiationError.
    // Old-relative: only report when X was concrete before (a class that was already
    // abstract and stayed instantiable via a subclass is not this reference's concern).
    if r.instantiated == Some(true)
        && let Some(access) = runtime.class_access(r.owner)
        && access & (ACC_ABSTRACT | ACC_INTERFACE) != 0
    {
        return match old.class_access(r.owner) {
            Some(old_access) if old_access & (ACC_ABSTRACT | ACC_INTERFACE) == 0 => {
                RefVerdict::Broken(r, Reason::ClassBecameAbstract)
            }
            Some(_) => RefVerdict::Ok,
            None => RefVerdict::Unknown,
        };
    }
    if r.member.is_none()
        && let Some(access) = runtime.class_access(r.owner)
    {
        match is_accessible(access, r.owner, source_class, runtime, graph) {
            // Cannot prove the protected subclass relationship (the referencing
            // class's super chain escaped analyzed scope): the reference may well
            // be legal, so do not report it.
            Accessible::Unknown => return RefVerdict::Unknown,
            Accessible::No => {
                // Narrowing is relative to old: a reference equally inaccessible before
                // the change is pre-existing inconsistency (e.g. nest-internal private
                // references in a renamed copy of the checked library), not breakage
                // introduced by it. Levels are compared instead of re-running
                // is_accessible against old because the subclass walk only sees scanned
                // classes and would demote real narrowing.
                return match old.class_access(r.owner) {
                    Some(old_access) if Visibility::of(access) < Visibility::of(old_access) => {
                        RefVerdict::Broken(r, Reason::ClassAccessNarrowed)
                    }
                    Some(_) => RefVerdict::Ok,
                    None => RefVerdict::Unknown,
                };
            }
            Accessible::Yes => {}
        }
    }
    let Some(member) = r.member else {
        return RefVerdict::Ok; // Class references are OK if the owner remains.
    };
    let kind = match r.kind {
        RefKind::Field => MemberKind::Field,
        RefKind::Method | RefKind::InterfaceMethod => MemberKind::Method,
        RefKind::Class => return RefVerdict::Ok,
    };
    // Methodref vs InterfaceMethodref encodes the owner kind the compiler saw; a
    // class <-> interface flip makes resolution throw IncompatibleClassChangeError.
    // Old-relative: only when the ref kind matched the old owner kind (it did, for
    // code that compiled). Owner outside old scope is Unknown, not a report.
    if kind == MemberKind::Method
        && let Some(new_access) = runtime.class_access(r.owner)
    {
        let ref_expects_interface = r.kind == RefKind::InterfaceMethod;
        if (new_access & ACC_INTERFACE != 0) != ref_expects_interface {
            match old.class_access(r.owner) {
                Some(old_access) if (old_access & ACC_INTERFACE != 0) == ref_expects_interface => {
                    // The ref matched the old owner kind, so the old kind is the ref's:
                    // an InterfaceMethodref means the owner used to be the interface.
                    return RefVerdict::Broken(
                        r,
                        if ref_expects_interface {
                            Reason::InterfaceBecameClass
                        } else {
                            Reason::ClassBecameInterface
                        },
                    );
                }
                None => return RefVerdict::Unknown,
                _ => {}
            }
        }
    }
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
                            match (kind, expected_static) {
                                (MemberKind::Method, true) => Reason::MethodBecameInstance,
                                (MemberKind::Method, false) => Reason::MethodBecameStatic,
                                (MemberKind::Field, true) => Reason::FieldBecameInstance,
                                (MemberKind::Field, false) => Reason::FieldBecameStatic,
                            },
                        );
                    }
                    MemberResolution::Unknown => return RefVerdict::Unknown,
                    _ => return RefVerdict::Ok,
                }
            }
            match is_accessible(found.access, found.owner, source_class, runtime, graph) {
                // Protected subclass relationship unprovable (the caller's super chain
                // escaped analyzed scope): the reference may be legal, so do not report.
                Accessible::Unknown => return RefVerdict::Unknown,
                Accessible::No => {
                    // Same old-relative gate as the class-access case above.
                    return match old.resolve_member(r.owner, member, kind) {
                        MemberResolution::Found(old_found)
                            if Visibility::of(found.access) < Visibility::of(old_found.access) =>
                        {
                            RefVerdict::Broken(
                                r,
                                if kind == MemberKind::Field {
                                    Reason::FieldAccessNarrowed
                                } else {
                                    Reason::MethodAccessNarrowed
                                },
                            )
                        }
                        MemberResolution::Unknown => RefVerdict::Unknown,
                        _ => RefVerdict::Ok,
                    };
                }
                Accessible::Yes => {}
            }
            if kind == MemberKind::Field
                && r.field_write == Some(true)
                && found.access & ACC_FINAL != 0
                && source_class != found.owner
            {
                match old.resolve_member(r.owner, member, kind) {
                    MemberResolution::Found(old_found) if old_found.access & ACC_FINAL == 0 => {
                        return RefVerdict::Broken(r, Reason::FieldBecameFinal);
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
                        Reason::FieldRemoved
                    } else {
                        Reason::MethodRemoved
                    };
                    RefVerdict::Broken(r, what)
                }
                Resolution::Unknown => RefVerdict::Unknown,
                Resolution::NotFound => RefVerdict::Ok,
            }
        }
    }
}

/// Record a violation, deduplicated on `(source, class, reference)`. Every graph walk and
/// the per-reference loop funnel through here so the `Violation` shape and the dedup key
/// live in one place.
///
/// Returns the pushed entry so a caller can refine fields on it (invocation evidence), and
/// `None` when the triple was already recorded. Handing back the reference rather than a
/// bool keeps the entry tied to the push: a caller cannot reach for `violations.last_mut()`
/// and silently patch a different violation if this ever stops appending.
fn push_violation<'v>(
    violations: &'v mut Vec<Violation>,
    seen: &mut FxHashSet<(Sym, Sym, SymbolRef)>,
    source: Sym,
    source_class: Sym,
    reference: SymbolRef,
    reason: Reason,
) -> Option<&'v mut Violation> {
    if !seen.insert((source, source_class, reference)) {
        return None;
    }
    violations.push(Violation {
        source,
        source_class,
        reference,
        reason,
        reachable: None,
        invocation_found: None,
        suggestion: None,
        modules: Vec::new(),
    });
    violations.last_mut()
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
                instantiated: None,
            };
            push_violation(
                violations,
                seen,
                node.source,
                class_name,
                reference,
                Reason::ClassBecameFinal,
            );
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
                    instantiated: None,
                };
                push_violation(
                    violations,
                    seen,
                    node.source,
                    class_name,
                    reference,
                    Reason::MethodBecameFinal,
                );
            }
        }
    }
}

/// Version-lag breakage from the upgraded artifacts' own new classes: a scanned class
/// from a new-version JAR extends a class that is final on the runtime classpath
/// (IncompatibleClassChangeError/VerifyError at class load). This is invisible to the
/// pair-diff checks because the final class lives in an artifact the upgrade did NOT
/// change (https://github.com/pact-foundation/pact-jvm/issues/1338: junit5spring 4.2.3 introduced a subclass of
/// PactVerificationExtension, which a lagging junit5 4.2.2 still declares final).
/// Old-relative gate: the same super edge already present in the changed artifact's
/// old version is pre-existing inconsistency, not breakage introduced by the upgrade.
/// A super outside the analyzed scope has no access flags and is skipped, the same
/// conservative direction as Unknown.
fn add_extends_final_violations(
    lag_edges: &[(Sym, Sym, Sym)],
    old: &ApiIndex,
    runtime: &Scope,
    violations: &mut Vec<Violation>,
    seen: &mut FxHashSet<(Sym, Sym, SymbolRef)>,
) {
    for &(class_name, super_name, source) in lag_edges {
        let Some(access) = runtime.class_access(super_name) else {
            continue;
        };
        if access & ACC_FINAL == 0 {
            continue;
        }
        if old
            .classes
            .get(&class_name)
            .is_some_and(|entry| entry.super_name == Some(super_name))
        {
            continue;
        }
        let reference = SymbolRef {
            kind: RefKind::Class,
            owner: super_name,
            member: None,
            expected_static: None,
            field_write: None,
            instantiated: None,
        };
        push_violation(
            violations,
            seen,
            source,
            class_name,
            reference,
            Reason::ExtendsFinalClass,
        );
    }
}

/// A scanned class extends a class that became an interface, or implements an
/// interface that became a class. Either flip makes the subclass fail to load
/// (VerifyError / IncompatibleClassChangeError), so it is found by a graph walk
/// like the newly-final walk, without needing a constant-pool reference. The flip
/// is judged old-vs-new library kind, so an edge that was already cross-kind (never
/// valid) is pre-existing, not this upgrade's breakage.
fn add_kind_flip_violations(
    old: &ApiIndex,
    new: &ApiIndex,
    graph: &ClassGraph,
    violations: &mut Vec<Violation>,
    seen: &mut FxHashSet<(Sym, Sym, SymbolRef)>,
) {
    // owner -> new kind is interface (true = class became interface, so an extends
    // edge breaks; false = interface became class, so an implements edge breaks).
    let flipped: FxHashMap<Sym, bool> = old
        .classes
        .iter()
        .filter_map(|(&name, old_entry)| {
            let new_entry = new.classes.get(&name)?;
            let old_iface = old_entry.access & ACC_INTERFACE != 0;
            let new_iface = new_entry.access & ACC_INTERFACE != 0;
            (old_iface != new_iface).then_some((name, new_iface))
        })
        .collect();
    if flipped.is_empty() {
        return;
    }
    let mut report = |owner: Sym, class_name: Sym, node: &crate::index::GraphNode, reason| {
        let reference = SymbolRef {
            kind: RefKind::Class,
            owner,
            member: None,
            expected_static: None,
            field_write: None,
            instantiated: None,
        };
        push_violation(violations, seen, node.source, class_name, reference, reason);
    };
    for (class_name, node) in graph.iter() {
        if let Some(super_name) = node.super_name
            && flipped.get(&super_name) == Some(&true)
        {
            report(super_name, class_name, node, Reason::ClassBecameInterface);
        }
        for &iface in graph.interfaces_of(node) {
            if flipped.get(&iface) == Some(&false) {
                report(iface, class_name, node, Reason::InterfaceBecameClass);
            }
        }
    }
}

/// Whether a class has a concrete implementation of a method available through its full
/// supertype closure. Deliberately conservative: any concrete declaration anywhere wins,
/// and an escape out of analyzed scope becomes Unknown rather than a claim of "no impl".
#[derive(Clone, Copy, PartialEq, Eq)]
enum ImplStatus {
    /// A concrete (non-abstract) declaration exists somewhere in the closure.
    Concrete,
    /// The method is declared, only abstractly, and the whole closure was in scope.
    AbstractOnly,
    /// No declaration at all, and the whole closure was in scope.
    Absent,
    /// The closure escaped scope before a concrete declaration could be ruled out.
    Unknown,
}

/// Whether `key` has a concrete implementation available to `start`, following JVMS 5.4.6
/// selection order rather than a flat closure scan. A `static` or `private` declaration is
/// never an instance-method override and is ignored.
///
/// Phase 1 walks the superclass chain, which wins over interfaces: the first class that
/// declares the method decides it (concrete -> `Concrete`, abstract -> `AbstractOnly`),
/// even when a sibling interface has a default. `java/lang/Object` supplies concrete
/// versions of its own methods, so reaching it resolves an Object-signature method.
///
/// Phase 2 (no class declared it) consults the superinterfaces. Interface method
/// specificity is not modeled, so a mix of abstract and concrete declarations is
/// inconclusive (`Unknown`) rather than guessed. That single rule avoids both a
/// first-match false positive (a sibling default read as unimplemented) and a
/// re-abstraction false positive (a shadowed default read as an implementation), at the
/// cost of a rare false negative. Escapes out of analyzed scope are also `Unknown`.
fn implementation_status(start: Sym, key: MemberKey, scope: &Scope) -> ImplStatus {
    // Phase 1: the superclass chain (class wins over interface).
    let mut interface_seed: Vec<Sym> = Vec::new();
    let mut class = Some(start);
    let mut seen = FxHashSet::default();
    while let Some(c) = class {
        if !seen.insert(c) {
            break;
        }
        if c == object_sym() {
            return if is_object_method(key) {
                ImplStatus::Concrete
            } else {
                // Object does not declare it; fall through to the interfaces below.
                break;
            };
        }
        let Some((super_name, interfaces)) = scope.super_and_interfaces(c) else {
            // A nearer class could declare the method, so the chain is inconclusive.
            return ImplStatus::Unknown;
        };
        if let Some(access) = scope.direct_method_access(c, key)
            && access & (ACC_STATIC | ACC_PRIVATE) == 0
        {
            return if access & ACC_ABSTRACT == 0 {
                ImplStatus::Concrete
            } else {
                ImplStatus::AbstractOnly
            };
        }
        interface_seed.extend(interfaces.iter().copied());
        class = super_name;
    }
    // Phase 2: the superinterface closure.
    let mut queue: VecDeque<Sym> = interface_seed.into();
    let mut iface_seen = FxHashSet::default();
    let (mut saw_abstract, mut saw_concrete, mut escaped) = (false, false, false);
    while let Some(iface) = queue.pop_front() {
        if iface == object_sym() || !iface_seen.insert(iface) {
            continue;
        }
        let Some((_, super_ifaces)) = scope.super_and_interfaces(iface) else {
            escaped = true;
            continue;
        };
        if let Some(access) = scope.direct_method_access(iface, key)
            && access & (ACC_STATIC | ACC_PRIVATE) == 0
        {
            if access & ACC_ABSTRACT == 0 {
                saw_concrete = true;
            } else {
                saw_abstract = true;
            }
        }
        queue.extend(super_ifaces.iter().copied());
    }
    if escaped || (saw_abstract && saw_concrete) {
        ImplStatus::Unknown
    } else if saw_abstract {
        ImplStatus::AbstractOnly
    } else if saw_concrete {
        ImplStatus::Concrete
    } else {
        ImplStatus::Absent
    }
}

/// A concrete scanned class that ends up inheriting an abstract method with no concrete
/// implementation throws AbstractMethodError when it is invoked. Two upgrade shapes cause
/// it: a concrete method turned abstract, and a new abstract method added to an interface
/// (or class) the class already extends/implements. Like the newly-final and kind-flip
/// walks this needs no constant-pool reference; the break is structural.
///
/// The decision compares the method's implementation status before and after the upgrade
/// over the class's full supertype closure (not the resolver's first-match selection, so a
/// sibling interface's default method is never mistaken for an unimplemented abstract one).
/// Report only when new is `AbstractOnly` and old had an implementation (`Concrete`) or no
/// such method at all (`Absent`); abstract-on-both-sides is pre-existing and any escape
/// stays Unknown.
#[allow(clippy::too_many_arguments)]
fn add_abstract_method_violations(
    old_scope: &Scope,
    runtime: &Scope,
    old: &ApiIndex,
    new: &ApiIndex,
    fetched: &ApiIndex,
    graph: &ClassGraph,
    invocations: &FxHashSet<(Sym, MemberKey)>,
    violations: &mut Vec<Violation>,
    seen: &mut FxHashSet<(Sym, Sym, SymbolRef)>,
) {
    let abstract_methods = methods_newly_abstract(old, new);
    if abstract_methods.is_empty() {
        return;
    }
    // Index the scan-wide evidence by member. The lookup key is the MemberKey alone, so the
    // (owner, member) set would have to be scanned end to end per violation — and the scan
    // cannot short-circuit in exactly the latent case, the one the tier exists to find.
    // Built after the early return above, so it stays free when nothing became abstract.
    let mut invoked_by: FxHashMap<MemberKey, Vec<Sym>> = FxHashMap::default();
    for &(owner, key) in invocations {
        invoked_by.entry(key).or_default().push(owner);
    }
    // Reused across classes: `for_each_supertype` allocates internally, so keeping the
    // result out of `on_dispatch_chain` also keeps it out of the per-evidence-owner loop.
    let mut supertypes: FxHashSet<Sym> = FxHashSet::default();
    for (class_name, node) in graph.iter() {
        // Only a concrete, instantiable class triggers AbstractMethodError. Access flags
        // come from the pass-2 fetched table (the graph carries none); an unfetched
        // candidate is skipped, the same conservative direction as Unknown.
        let Some(entry) = fetched.classes.get(&class_name) else {
            continue;
        };
        if entry.access & (ACC_ABSTRACT | ACC_INTERFACE) != 0 {
            continue;
        }
        // Newly-abstract methods declared by any supertype of this class, mapped to the
        // owner that declared them (smallest by name for a deterministic report). The same
        // walk records what `on_dispatch_chain` needs, which is loop-invariant below.
        let mut candidates: FxHashMap<MemberKey, Sym> = FxHashMap::default();
        supertypes.clear();
        let mut supertypes_escaped = Escapes::default();
        for_each_supertype(class_name, new, graph, |anc| {
            supertypes.insert(anc);
            if !new.classes.contains_key(&anc) && !graph.contains(anc) {
                supertypes_escaped.add(anc);
            }
            if let Some(keys) = abstract_methods.get(&anc) {
                for &key in keys {
                    candidates
                        .entry(key)
                        .and_modify(|owner| {
                            if anc.as_str() < owner.as_str() {
                                *owner = anc;
                            }
                        })
                        .or_insert(anc);
                }
            }
        });
        // Iterated in FxHashMap order; the final string-value sort in check_scanned makes
        // the report deterministic (never sort output by Sym id).
        for (key, owner) in candidates {
            if implementation_status(class_name, key, runtime) != ImplStatus::AbstractOnly {
                continue;
            }
            match implementation_status(class_name, key, old_scope) {
                ImplStatus::Concrete | ImplStatus::Absent => {}
                // Abstract on both sides is pre-existing; Unknown is conservative.
                ImplStatus::AbstractOnly | ImplStatus::Unknown => continue,
            }
            let reference = SymbolRef {
                kind: RefKind::Method,
                owner,
                member: Some(key),
                expected_static: Some(false),
                field_write: None,
                instantiated: None,
            };
            if let Some(v) = push_violation(
                violations,
                seen,
                node.source,
                class_name,
                reference,
                Reason::MethodBecameAbstract,
            ) {
                // AbstractMethodError throws at invocation, not at class load, so a
                // violation nothing can call is latent rather than dropped.
                v.invocation_found = Some(invoked_by.get(&key).is_some_and(|owners| {
                    owners.iter().any(|&e_owner| {
                        on_dispatch_chain(
                            e_owner,
                            class_name,
                            &supertypes,
                            &supertypes_escaped,
                            new,
                            graph,
                        )
                    })
                }));
            }
        }
    }
}

/// Types a hierarchy walk left analyzed scope through. An escape normally forces "related",
/// since the unseen hierarchy could hold the evidence owner, with one provable exception:
/// the JVM reserves `java.*`, so a `java/`-named type is a platform class and so is
/// everything the boot loader resolves above it. Such an escape cannot hide a library or
/// application class. Without the carve-out the filter is nearly inert, because any class
/// implementing Serializable or Comparable escapes. `javax/`, `sun/` and `com/sun/` do not
/// qualify — javax.servlet and friends ship as ordinary artifacts.
#[derive(Default)]
struct Escapes {
    platform: bool,
    other: bool,
}

impl Escapes {
    fn add(&mut self, class: Sym) {
        if class.as_str().starts_with("java/") {
            self.platform = true;
        } else {
            self.other = true;
        }
    }

    /// Whether the escaped-past types could hide `owner`.
    fn could_hide(&self, owner: Sym) -> bool {
        self.other || (self.platform && owner.as_str().starts_with("java/"))
    }
}

/// Whether a call on `evidence_owner` can dispatch onto an instance of `class_name`: the
/// owner is the class, a supertype, or a subtype. A sibling subtype is provably unrelated,
/// its receivers can never be this class. `supertypes` is the caller's precomputed up-walk.
fn on_dispatch_chain(
    evidence_owner: Sym,
    class_name: Sym,
    supertypes: &FxHashSet<Sym>,
    supertypes_escaped: &Escapes,
    new: &ApiIndex,
    graph: &ClassGraph,
) -> bool {
    if evidence_owner == class_name
        || supertypes_escaped.could_hide(evidence_owner)
        || supertypes.contains(&evidence_owner)
    {
        return true;
    }
    let mut down_found = false;
    let mut down_escaped = Escapes::default();
    for_each_supertype(evidence_owner, new, graph, |anc| {
        down_found |= anc == class_name;
        if !new.classes.contains_key(&anc) && !graph.contains(anc) {
            down_escaped.add(anc);
        }
    });
    down_found || down_escaped.could_hide(class_name)
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
            if old_access & ACC_FINAL != 0 || is_synthetic_or_bridge(*old_access) {
                continue;
            }
            if let Some(new_access) = new.direct_method_access(class, *key)
                && new_access & ACC_FINAL != 0
                && !is_synthetic_or_bridge(new_access)
            {
                out.entry(class)
                    .or_insert_with(FxHashSet::default)
                    .insert(*key);
            }
        }
    }
    out
}

/// Methods a compiler generated rather than the source author (bridges for covariant
/// returns and generic erasure, and other synthetic members). They must not be the
/// subject of a library-side "became abstract"/"became final" inference, since a
/// generic-signature edit can add or reshape them without a source-visible API change.
/// This guards the inference only; a consumer's explicit reference to such a member is
/// still resolved and checked normally, and such a method still counts as a concrete
/// override when selecting a method.
fn is_synthetic_or_bridge(access: u16) -> bool {
    access & (ACC_SYNTHETIC | ACC_BRIDGE) != 0
}

/// Methods abstract in new but not abstract in old (owner class -> keys), the input to the
/// AbstractMethodError walk. This covers both shapes that throw AbstractMethodError:
/// a concrete method turned abstract, and a brand-new abstract method added to an interface
/// (or class) that existing concrete implementors do not provide. The owner class must exist
/// in old, so a wholly-new type is not a break for pre-existing code. The synthetic/bridge
/// guard excludes compiler-generated subjects.
fn methods_newly_abstract(old: &ApiIndex, new: &ApiIndex) -> FxHashMap<Sym, FxHashSet<MemberKey>> {
    let mut out = FxHashMap::default();
    for (&class, new_entry) in &new.classes {
        if !old.classes.contains_key(&class) {
            continue;
        }
        for (key, new_access) in new.methods_of(new_entry) {
            if new_access & ACC_ABSTRACT == 0 || is_synthetic_or_bridge(*new_access) {
                continue;
            }
            // Newly abstract when old had a concrete non-synthetic declaration (shape 1) or
            // none at all (shape 2). Already abstract in old, or a guarded synthetic
            // concrete, is pre-existing.
            let newly_abstract = old
                .direct_method_access(class, *key)
                .is_none_or(|a| a & ACC_ABSTRACT == 0 && !is_synthetic_or_bridge(a));
            if newly_abstract {
                out.entry(class)
                    .or_insert_with(FxHashSet::default)
                    .insert(*key);
            }
        }
    }
    out
}

/// Raw name+descriptor pairs of every newly-abstract method mapped to their interned
/// `MemberKey`, for matching un-interned constant-pool references during pass 1 (invocation
/// evidence for the latent tier). Carrying the key means a hit interns only the owner.
fn abstract_member_probe(
    old: &ApiIndex,
    new: &ApiIndex,
) -> FxHashMap<(&'static str, &'static str), MemberKey> {
    methods_newly_abstract(old, new)
        .values()
        .flatten()
        .map(|k| ((k.name.as_str(), k.descriptor.as_str()), *k))
        .collect()
}

/// Invocation evidence from the checked library's own new-version jars. The library is
/// often the only caller of a newly-abstract member (koin-core invokes `Logger.display`),
/// and in plain `check` those jars need not be scan targets. Old jars are not swept, they
/// are not runtime code.
///
/// A read failure yields no evidence, which pushes a break INTO the latent tier and so
/// WEAKENS the gate. Tolerated only because an unreadable jar already failed at index
/// build. Batched rather than `input::load`, which holds a whole jar inflated at once.
pub fn library_invocation_evidence(
    new_paths: &[PathBuf],
    old: &ApiIndex,
    new: &ApiIndex,
) -> Vec<(Sym, MemberKey)> {
    let probe = abstract_member_probe(old, new);
    if probe.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::new();
    for path in new_paths {
        let _ = crate::input::for_each_batch(path, 512, None, |batch| {
            out.extend(class_invocation_evidence(&batch, &probe));
            Ok(())
        });
    }
    out
}

fn class_invocation_evidence(
    classes: &[LoadedClass],
    probe: &FxHashMap<(&str, &str), MemberKey>,
) -> Vec<(Sym, MemberKey)> {
    let mut out = Vec::new();
    for lc in classes {
        if let Ok(rc) = crate::classfile::RawClass::parse(&lc.bytes) {
            out.extend(crate::extract::extract_invocation_evidence(&rc, probe));
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

/// Three-valued accessibility. Unknown only arises from the protected-subclass
/// check when the referencing class's super chain escapes analyzed scope: the
/// reference may be legal, so callers treat Unknown as unverified (never broken).
#[derive(Clone, Copy, PartialEq, Eq)]
enum Accessible {
    Yes,
    No,
    Unknown,
}

fn is_accessible(
    access: u16,
    owner: Sym,
    source_class: Sym,
    runtime: &Scope,
    graph: &ClassGraph,
) -> Accessible {
    if access & ACC_PUBLIC != 0 {
        return Accessible::Yes;
    }
    if access & ACC_PRIVATE != 0 {
        // Nestmates share private access (JVMS 5.4.4, Java 11+): both classes must
        // have the same nest host, read from the NestHost attribute (self when absent).
        return if owner == source_class
            || nest_host_of(owner, runtime, graph) == nest_host_of(source_class, runtime, graph)
        {
            Accessible::Yes
        } else {
            Accessible::No
        };
    }
    if same_package(owner, source_class) {
        return Accessible::Yes;
    }
    if access & ACC_PROTECTED == 0 {
        // Package-private in a different package.
        return Accessible::No;
    }
    match is_subclass(source_class, owner, runtime, graph) {
        Subclass::Yes => Accessible::Yes,
        Subclass::No => Accessible::No,
        Subclass::Unknown => Accessible::Unknown,
    }
}

fn same_package(a: Sym, b: Sym) -> bool {
    package_name(a.as_str()) == package_name(b.as_str())
}

/// A class without a NestHost attribute is its own nest host. Scanned classes are
/// looked up in the graph, resolution-scope classes in the runtime scope; a class
/// outside both defaults to hosting itself (conservative: forbids private access).
fn nest_host_of(class: Sym, runtime: &Scope, graph: &ClassGraph) -> Sym {
    if let Some(node) = graph.get(class) {
        return node.nest_host.unwrap_or(class);
    }
    runtime.class_nest_host(class).flatten().unwrap_or(class)
}

fn package_name(name: &str) -> &str {
    name.rsplit_once('/').map_or("", |(pkg, _)| pkg)
}

/// Three-valued subclass walk for the protected-access check. Graph edges first
/// (scanned classes), then the runtime scope's super chain: resolve_member can
/// find a protected member on a new-index or JDK-layer owner, and judging the
/// caller against a hierarchy the walk cannot itself follow invented false
/// "access narrowed" reports (a genuine subclass through an unscanned library or
/// JDK edge looked unrelated). When the chain reaches a class visible in no scope,
/// the relationship is Unknown rather than No, so the caller does not report a
/// reference that may well be legal. No means the full chain was walked to Object
/// without finding the target (a provable non-subclass).
enum Subclass {
    Yes,
    No,
    Unknown,
}

fn is_subclass(class_name: Sym, target: Sym, runtime: &Scope, graph: &ClassGraph) -> Subclass {
    // Some(Some(s)) = class known, super s; Some(None) = class known, no super
    // (java/lang/Object); None = class visible in no scope.
    let super_of = |class: Sym| -> Option<Option<Sym>> {
        graph
            .get(class)
            .map(|node| node.super_name)
            .or_else(|| runtime.class_super(class))
    };
    let mut current = class_name;
    let mut seen = FxHashSet::default();
    loop {
        if current == target {
            return Subclass::Yes;
        }
        // Object has no superclass and is not indexed; reaching it means the full
        // chain was walked without finding the target.
        if current == object_sym() {
            return Subclass::No;
        }
        if !seen.insert(current) {
            return Subclass::No;
        }
        match super_of(current) {
            Some(Some(s)) => current = s,
            Some(None) => return Subclass::No,
            None => return Subclass::Unknown,
        }
    }
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
            nest_host: None,
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
            nest_host: None,
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
            nest_host: None,
        }
    }

    fn method_ref(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::Method,
            owner: intern(owner),
            member: Some(MemberKey::new(name, desc)),
            expected_static: None,
            field_write: None,
            instantiated: None,
        }
    }

    fn static_method_ref(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::Method,
            owner: intern(owner),
            member: Some(MemberKey::new(name, desc)),
            expected_static: Some(true),
            field_write: None,
            instantiated: None,
        }
    }

    fn field_write_ref(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::Field,
            owner: intern(owner),
            member: Some(MemberKey::new(name, desc)),
            expected_static: Some(false),
            field_write: Some(true),
            instantiated: None,
        }
    }

    fn broken(v: RefVerdict) -> Option<(SymbolRef, Reason)> {
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
        assert_eq!(broken(v).unwrap().1, Reason::MethodRemoved);
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
        assert_eq!(reason, Reason::ClassRemoved);
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
            None,
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
            None,
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

    fn class_ref(owner: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::Class,
            owner: intern(owner),
            member: None,
            expected_static: None,
            field_write: None,
            instantiated: None,
        }
    }

    fn new_ref(owner: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::Class,
            owner: intern(owner),
            member: None,
            expected_static: None,
            field_write: None,
            instantiated: Some(true),
        }
    }

    fn interface_method_ref(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            kind: RefKind::InterfaceMethod,
            owner: intern(owner),
            member: Some(MemberKey::new(name, desc)),
            expected_static: Some(false),
            field_write: None,
            instantiated: None,
        }
    }

    fn abstract_class(name: &str) -> ClassApi {
        let mut c = class(name, &[]);
        c.access = ACC_PUBLIC | ACC_ABSTRACT;
        c
    }

    fn interface(name: &str) -> ClassApi {
        let mut c = class(name, &[]);
        c.access = ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT;
        c
    }

    #[test]
    fn new_on_class_that_became_abstract_is_broken() {
        let old = ApiIndex::build([class("lib/C", &[])]);
        let new = ApiIndex::build([abstract_class("lib/C")]);
        let v = verdict(
            new_ref("lib/C"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, Reason::ClassBecameAbstract);
    }

    #[test]
    fn new_on_class_that_became_interface_is_broken() {
        let old = ApiIndex::build([class("lib/C", &[])]);
        let new = ApiIndex::build([interface("lib/C")]);
        let v = verdict(
            new_ref("lib/C"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, Reason::ClassBecameAbstract);
    }

    #[test]
    fn new_on_already_abstract_class_is_not_reported() {
        // A class abstract on both sides: instantiating it was never valid, so the
        // reference is pre-existing inconsistency, not this upgrade's breakage.
        let old = ApiIndex::build([abstract_class("lib/C")]);
        let new = ApiIndex::build([abstract_class("lib/C")]);
        let v = verdict(
            new_ref("lib/C"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn plain_class_ref_to_newly_abstract_class_is_ok() {
        // Only `new` breaks; a type reference (field type, cast) to an abstract
        // class stays valid.
        let old = ApiIndex::build([class("lib/C", &[])]);
        let new = ApiIndex::build([abstract_class("lib/C")]);
        let v = verdict(
            class_ref("lib/C"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn methodref_owner_that_became_interface_is_broken() {
        // A Methodref (compiled against a class) whose owner is now an interface:
        // resolution throws IncompatibleClassChangeError.
        let old = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
        let new = ApiIndex::build([{
            let mut c = interface("lib/C");
            c.methods = build_members([(MemberKey::new("m", "()V"), ACC_PUBLIC | ACC_ABSTRACT)]);
            c
        }]);
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, Reason::ClassBecameInterface);
    }

    #[test]
    fn interface_methodref_owner_that_became_class_is_broken() {
        let old = ApiIndex::build([interface("lib/I")]);
        let new = ApiIndex::build([class("lib/I", &[("m", "()V")])]);
        let v = verdict(
            interface_method_ref("lib/I", "m", "()V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, Reason::InterfaceBecameClass);
    }

    #[test]
    fn matching_owner_kind_is_not_reported() {
        // Owner stayed a class: a Methodref resolves normally.
        let old = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
        let new = ApiIndex::build([class("lib/C", &[("m", "()V")])]);
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
    fn extends_class_that_became_interface_is_broken() {
        let old = ApiIndex::build([class("lib/Base", &[])]);
        let new = ApiIndex::build([interface("lib/Base")]);
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("app/Sub"),
            Some(intern("lib/Base")),
            &[],
            &[],
            None,
            intern("app.jar"),
        );
        let mut violations = Vec::new();
        let mut seen = FxHashSet::default();
        add_kind_flip_violations(&old, &new, &graph, &mut violations, &mut seen);
        assert_eq!(violations.len(), 1);
        assert_eq!(violations[0].reason, Reason::ClassBecameInterface);
        assert_eq!(violations[0].reference.owner.as_str(), "lib/Base");
        assert_eq!(violations[0].source_class.as_str(), "app/Sub");
    }

    #[test]
    fn implements_interface_that_became_class_is_broken() {
        let old = ApiIndex::build([interface("lib/I")]);
        let new = ApiIndex::build([class("lib/I", &[])]);
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("app/Impl"),
            Some(object_sym()),
            &[intern("lib/I")],
            &[],
            None,
            intern("app.jar"),
        );
        let mut violations = Vec::new();
        let mut seen = FxHashSet::default();
        add_kind_flip_violations(&old, &new, &graph, &mut violations, &mut seen);
        assert_eq!(violations.len(), 1);
        assert_eq!(violations[0].reason, Reason::InterfaceBecameClass);
        assert_eq!(violations[0].reference.owner.as_str(), "lib/I");
    }

    #[test]
    fn stable_kind_hierarchy_is_not_reported() {
        let old = ApiIndex::build([class("lib/Base", &[]), interface("lib/I")]);
        let new = ApiIndex::build([class("lib/Base", &[]), interface("lib/I")]);
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("app/Sub"),
            Some(intern("lib/Base")),
            &[intern("lib/I")],
            &[],
            None,
            intern("app.jar"),
        );
        let mut violations = Vec::new();
        let mut seen = FxHashSet::default();
        add_kind_flip_violations(&old, &new, &graph, &mut violations, &mut seen);
        assert!(violations.is_empty());
    }

    #[test]
    fn member_access_narrowed_from_public_is_reported() {
        let old = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PUBLIC)])]);
        let new = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PRIVATE)])]);
        let v = verdict(
            field_write_ref("lib/C", "x", "I"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, Reason::FieldAccessNarrowed);
    }

    #[test]
    fn member_equally_inaccessible_in_old_is_not_reported() {
        // A renamed copy of the library on the scanned classpath: its nest-internal
        // private references resolve as private against both sides. Pre-existing,
        // not narrowing.
        let old = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PRIVATE)])]);
        let new = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PRIVATE)])]);
        let v = verdict(
            field_write_ref("lib/C", "x", "I"),
            intern("lib/C$Builder"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn nest_internal_private_access_is_legal_even_when_narrowed() {
        // public -> private is a real narrowing, but a nestmate (same nest host per
        // the NestHost attribute) keeps private access at runtime (JVMS 5.4.4).
        let old = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PUBLIC)])]);
        let new = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PRIVATE)])]);
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("lib/C$Builder"),
            Some(object_sym()),
            &[],
            &[],
            Some(intern("lib/C")),
            intern("cp.jar"),
        );
        graph.insert_if_absent(
            intern("lib/C"),
            Some(object_sym()),
            &[],
            &[],
            None,
            intern("cp.jar"),
        );
        let v = verdict(
            field_write_ref("lib/C", "x", "I"),
            intern("lib/C$Builder"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &graph,
        );
        assert!(matches!(v, RefVerdict::Ok));
    }

    #[test]
    fn shared_name_prefix_is_not_a_nest() {
        // Classes without nest attributes are their own nest hosts: neither a
        // name-prefix lookalike nor a same-simple-name class in another package
        // gains private access.
        let old = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PUBLIC)])]);
        let new = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", ACC_PRIVATE)])]);
        for source in ["lib/CX", "other/C$Inner"] {
            let v = verdict(
                field_write_ref("lib/C", "x", "I"),
                intern(source),
                &Scope::new(vec![&old]),
                &Scope::new(vec![&new]),
                &ClassGraph::new(),
            );
            assert_eq!(
                broken(v).unwrap().1,
                Reason::FieldAccessNarrowed,
                "{source}"
            );
        }
    }

    #[test]
    fn protected_to_private_is_reported_without_hierarchy_proof() {
        // The old side is compared by access level, not by re-running is_accessible:
        // the subclass walk only sees scanned classes, so a chain through the library
        // would wrongly demote real narrowing to pre-existing.
        let old = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PROTECTED)],
        )]);
        let new = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PRIVATE)],
        )]);
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Sub"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, Reason::MethodAccessNarrowed);
    }

    #[test]
    fn protected_narrowing_is_unknown_when_subclass_chain_escapes_scope() {
        // new C.m is protected and the caller is in a different package, so access
        // hinges on whether app/Sub is a subclass of lib/C. Its super chain leaves
        // analyzed scope, so the relationship is unprovable and the reference must be
        // Unknown, not a false "method access narrowed".
        let old = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PROTECTED)],
        )]);
        // app/Sub extends an unscanned class, so the walk escapes.
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("app/Sub"),
            Some(intern("ext/Hidden")),
            &[],
            &[],
            None,
            intern("app.jar"),
        );
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Sub"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &graph,
        );
        assert!(matches!(v, RefVerdict::Unknown));
    }

    #[test]
    fn protected_narrowing_is_reported_when_provably_not_a_subclass() {
        // The caller's full chain is visible and reaches Object without passing
        // through the owner, so it is provably not a subclass and the protected
        // narrowing is a real break.
        let old = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PROTECTED)],
        )]);
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("app/Sub"),
            Some(object_sym()),
            &[],
            &[],
            None,
            intern("app.jar"),
        );
        let v = verdict(
            method_ref("lib/C", "m", "()V"),
            intern("app/Sub"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &graph,
        );
        assert_eq!(broken(v).unwrap().1, Reason::MethodAccessNarrowed);
    }

    #[test]
    fn constructor_is_not_inherited_from_superclass() {
        // Owner's (Z)V constructor is removed; a superclass still declares one. A
        // constructor is never inherited, so resolution must be owner-only and the
        // reference is a removal (NoSuchMethodError), not access-narrowed against the
        // superclass copy. This is the jetty ArrayTernaryTrie/AbstractTrie shape.
        let old = ApiIndex::build([class_with_method_access(
            "lib/Sub",
            &[("<init>", "(Z)V", ACC_PUBLIC)],
        )]);
        let mut sub_new = class_with_method_access("lib/Sub", &[]);
        sub_new.super_name = Some(intern("lib/Base"));
        let new = ApiIndex::build([
            sub_new,
            class_with_method_access("lib/Base", &[("<init>", "(Z)V", ACC_PROTECTED)]),
        ]);
        let v = verdict(
            method_ref("lib/Sub", "<init>", "(Z)V"),
            intern("app/Use"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, Reason::MethodRemoved);
    }

    #[test]
    fn narrowing_is_unknown_when_old_resolution_escapes_scope() {
        let mut c = class("lib/C", &[]);
        c.super_name = Some(intern("ext/Base"));
        let old = ApiIndex::build([c]);
        let new = ApiIndex::build([class_with_method_access(
            "lib/C",
            &[("m", "()V", ACC_PRIVATE)],
        )]);
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
    fn class_access_narrowed_only_when_old_was_wider() {
        let package_private = || {
            let mut c = class("lib/C", &[]);
            c.access = 0;
            c
        };
        let new = ApiIndex::build([package_private()]);

        let old_public = ApiIndex::build([class("lib/C", &[])]);
        let v = verdict(
            class_ref("lib/C"),
            intern("app/Use"),
            &Scope::new(vec![&old_public]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert_eq!(broken(v).unwrap().1, Reason::ClassAccessNarrowed);

        let old_same = ApiIndex::build([package_private()]);
        let v = verdict(
            class_ref("lib/C"),
            intern("app/Use"),
            &Scope::new(vec![&old_same]),
            &Scope::new(vec![&new]),
            &ClassGraph::new(),
        );
        assert!(matches!(v, RefVerdict::Ok));
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
        assert_eq!(broken(v).unwrap().1, Reason::MethodBecameInstance);

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

    /// The reason names the member kind and the direction the member moved, and all four
    /// combinations come off one match, so each one is pinned. Getting a direction backwards
    /// would still report the break, with the wrong sentence and the wrong exclude kind.
    #[test]
    fn static_flip_reason_names_the_member_kind_and_direction() {
        fn flip(from: u16, to: u16, reference: SymbolRef) -> Reason {
            let old = ApiIndex::build([class_with_method_access("lib/C", &[("m", "()V", from)])]);
            let new = ApiIndex::build([class_with_method_access("lib/C", &[("m", "()V", to)])]);
            let old_f = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", from)])]);
            let new_f = ApiIndex::build([class_with_fields("lib/C", &[("x", "I", to)])]);
            let (old, new) = if reference.kind == RefKind::Field {
                (old_f, new_f)
            } else {
                (old, new)
            };
            let v = verdict(
                reference,
                intern("app/Use"),
                &Scope::new(vec![&old]),
                &Scope::new(vec![&new]),
                &ClassGraph::new(),
            );
            broken(v).unwrap().1
        }
        let (stat, inst) = (ACC_PUBLIC | ACC_STATIC, ACC_PUBLIC);
        assert_eq!(
            flip(stat, inst, static_method_ref("lib/C", "m", "()V")),
            Reason::MethodBecameInstance
        );
        assert_eq!(
            flip(
                inst,
                stat,
                method_ref_expecting_instance("lib/C", "m", "()V")
            ),
            Reason::MethodBecameStatic
        );
        assert_eq!(
            flip(stat, inst, static_field_ref("lib/C", "x", "I")),
            Reason::FieldBecameInstance
        );
        assert_eq!(
            flip(inst, stat, field_write_ref("lib/C", "x", "I")),
            Reason::FieldBecameStatic
        );
    }

    fn method_ref_expecting_instance(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            expected_static: Some(false),
            ..method_ref(owner, name, desc)
        }
    }

    fn static_field_ref(owner: &str, name: &str, desc: &str) -> SymbolRef {
        SymbolRef {
            expected_static: Some(true),
            ..field_write_ref(owner, name, desc)
        }
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
        assert_eq!(broken(v).unwrap().1, Reason::FieldBecameFinal);
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

    fn final_class(name: &str) -> ClassApi {
        let mut c = class(name, &[]);
        c.access = ACC_PUBLIC | ACC_FINAL;
        c
    }

    #[test]
    fn upgraded_class_extending_final_classpath_class_is_broken() {
        // C is new in the upgraded artifact (absent from old) and extends X, which
        // the scanned classpath (fetched side) declares final.
        let old = ApiIndex::build([]);
        let new = ApiIndex::build([]);
        let fetched = ApiIndex::build([final_class("cp/X")]);
        let runtime = Scope::new(vec![&new, &fetched]);
        let edges = vec![(intern("lib/C"), intern("cp/X"), intern("lib-new.jar"))];
        let mut violations = Vec::new();
        let mut seen = FxHashSet::default();
        add_extends_final_violations(&edges, &old, &runtime, &mut violations, &mut seen);
        assert_eq!(violations.len(), 1, "violations: {violations:?}");
        assert_eq!(violations[0].reason, Reason::ExtendsFinalClass);
        assert_eq!(violations[0].reference.owner.as_str(), "cp/X");
        assert_eq!(violations[0].source_class.as_str(), "lib/C");
    }

    #[test]
    fn preexisting_final_super_edge_is_not_reported() {
        // The changed artifact's old version already extended X: equally broken
        // before the upgrade, so it is pre-existing, not introduced breakage.
        let mut c_old = class("lib/C", &[]);
        c_old.super_name = Some(intern("cp/X"));
        let old = ApiIndex::build([c_old]);
        let new = ApiIndex::build([]);
        let fetched = ApiIndex::build([final_class("cp/X")]);
        let runtime = Scope::new(vec![&new, &fetched]);
        let edges = vec![(intern("lib/C"), intern("cp/X"), intern("lib-new.jar"))];
        let mut violations = Vec::new();
        let mut seen = FxHashSet::default();
        add_extends_final_violations(&edges, &old, &runtime, &mut violations, &mut seen);
        assert!(violations.is_empty(), "violations: {violations:?}");
    }

    #[test]
    fn non_final_or_out_of_scope_super_is_not_reported() {
        let old = ApiIndex::build([]);
        let new = ApiIndex::build([]);
        // Non-final super: fine. Out-of-scope super: no access flags, skipped
        // (same conservative direction as Unknown).
        let fetched = ApiIndex::build([class("cp/X", &[])]);
        let runtime = Scope::new(vec![&new, &fetched]);
        let edges = vec![
            (intern("lib/C"), intern("cp/X"), intern("lib-new.jar")),
            (intern("lib/D"), intern("ext/Gone"), intern("lib-new.jar")),
        ];
        let mut violations = Vec::new();
        let mut seen = FxHashSet::default();
        add_extends_final_violations(&edges, &old, &runtime, &mut violations, &mut seen);
        assert!(violations.is_empty(), "violations: {violations:?}");
    }

    #[test]
    fn upgraded_super_edges_come_only_from_upgraded_sources() {
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("lib/C"),
            Some(intern("cp/X")),
            &[],
            &[],
            None,
            intern("lib-new.jar"),
        );
        graph.insert_if_absent(
            intern("cp/X"),
            Some(object_sym()),
            &[],
            &[],
            None,
            intern("cp.jar"),
        );
        graph.insert_if_absent(
            intern("cp/D"),
            Some(intern("cp/X")),
            &[],
            &[],
            None,
            intern("cp.jar"),
        );
        let new = ApiIndex::build([]);
        let mut wanted = FxHashSet::default();
        let upgraded = FxHashSet::from_iter([intern("lib-new.jar")]);
        let edges = collect_upgraded_super_edges(&graph, &upgraded, &new, &mut wanted, None);
        assert_eq!(
            edges,
            vec![(intern("lib/C"), intern("cp/X"), intern("lib-new.jar"))]
        );
        // The super only exists on the scanned classpath, so pass 2 must fetch
        // its access flags.
        assert!(wanted.contains(&intern("cp/X")));
    }

    #[test]
    fn protected_member_on_scope_side_super_is_accessible_to_real_subclass() {
        // app/Sub extends lib/L, which is NOT a scan target (graph has no node
        // for it); old L declared m public, new L dropped it and resolution
        // falls to protected m on L's super in the new index. is_subclass must
        // cross the scope-only edge or a genuine subclass caller would be
        // reported as "method access narrowed" (a false positive the JVM
        // links fine under JVMS 5.4.4).
        let old = ApiIndex::build([class_with_method_access(
            "lib/L",
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let mut l_new = class("lib/L", &[]);
        l_new.super_name = Some(intern("cp/Base"));
        let new = ApiIndex::build([
            l_new,
            class_with_method_access("cp/Base", &[("m", "()V", ACC_PROTECTED)]),
        ]);
        let mut graph = ClassGraph::new();
        graph.insert_if_absent(
            intern("app/Sub"),
            Some(intern("lib/L")),
            &[],
            &[],
            None,
            intern("app.jar"),
        );
        let v = verdict(
            method_ref("lib/L", "m", "()V"),
            intern("app/Sub"),
            &Scope::new(vec![&old]),
            &Scope::new(vec![&new]),
            &graph,
        );
        assert!(matches!(v, RefVerdict::Ok), "expected Ok");
    }

    #[test]
    fn class_only_refs_seed_the_jdk_escape_roots() {
        // A Class-constant reference (member = None) to an owner outside the
        // new index and the graph must still become a JDK escape root, or the
        // existence verdict would depend on unrelated member refs.
        let old = ApiIndex::build([class("javax/xml/Gone", &[])]);
        let new = ApiIndex::build([]);
        let mut scan = ScanResult::new();
        scan.merge(ParsedTargets {
            targets: vec![ParsedTarget {
                source: intern("app.jar"),
                class_name: intern("app/Use"),
                hierarchy: Some(Hierarchy {
                    super_name: Some(object_sym()),
                    interfaces: vec![],
                    nest_host: None,
                }),
                entry_override: None,
                refs: vec![class_ref("javax/xml/Gone")],
                edges: vec![],
            }],
            warnings: vec![],
            scanned_classes: 1,
            invocations: vec![],
        });
        let (_, escapes) = collect_wanted(&scan, &old, &new, true);
        assert!(escapes.contains(&intern("javax/xml/Gone")));
        let (_, off) = collect_wanted(&scan, &old, &new, false);
        assert!(off.is_empty());
    }

    #[test]
    fn refs_from_first_wins_losing_duplicates_are_dropped() {
        let class_name = intern("dup/C");
        let target = |source: &str, super_name: &str| ParsedTarget {
            source: intern(source),
            class_name,
            hierarchy: Some(Hierarchy {
                super_name: Some(intern(super_name)),
                interfaces: vec![],
                nest_host: None,
            }),
            entry_override: None,
            refs: vec![method_ref("lib/A", "m", "()V")],
            edges: vec![],
        };

        // Same chunk: both copies carry a hierarchy; merge order decides the winner.
        let mut scan = ScanResult::new();
        scan.merge(ParsedTargets {
            targets: vec![
                target("first.jar", "lib/Base"),
                target("second.jar", "other/Base"),
            ],
            warnings: vec![],
            scanned_classes: 2,
            invocations: vec![],
        });
        // Later chunk: the graph already had the class at parse time, so hierarchy is None.
        let mut late = target("third.jar", "other/Base");
        late.hierarchy = None;
        scan.merge(ParsedTargets {
            targets: vec![late],
            warnings: vec![],
            scanned_classes: 1,
            invocations: vec![],
        });

        // Only the winning copy defines the node and keeps its references; the
        // JVM never loads the shadowed copies, so their references must not be
        // judged (against the winner's hierarchy) at all.
        let node = scan.graph.get(class_name).unwrap();
        assert_eq!(node.source.as_str(), "first.jar");
        let record_sources: Vec<&str> = scan.records.iter().map(|(s, _, _)| s.as_str()).collect();
        assert_eq!(record_sources, ["first.jar"]);
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

    // ---- AbstractMethodError walk (add_abstract_method_violations) ----

    /// ClassApi with an explicit superclass plus class- and method-level access flags.
    /// An interface-free class (a superclass edge only), the shape-1 special case of `amv_full`.
    fn amv_class(
        name: &str,
        super_name: &str,
        access: u16,
        methods: &[(&str, &str, u16)],
    ) -> ClassApi {
        amv_full(name, super_name, access, &[], methods)
    }

    /// A ClassGraph of scanned (name -> superclass) edges with no interfaces.
    fn scanned_graph(edges: &[(&str, &str)]) -> ClassGraph {
        let nodes: Vec<(&str, &str, &[&str])> =
            edges.iter().map(|&(n, s)| (n, s, &[] as &[&str])).collect();
        scanned_graph_full(&nodes)
    }

    /// Run the walk over a two-layer scope (library + fetched scanned classes).
    fn abstract_violations(
        old: &ApiIndex,
        new: &ApiIndex,
        fetched: &ApiIndex,
        graph: &ClassGraph,
    ) -> Vec<Violation> {
        abstract_violations_with(old, new, fetched, graph, &FxHashSet::default())
    }

    fn abstract_violations_with(
        old: &ApiIndex,
        new: &ApiIndex,
        fetched: &ApiIndex,
        graph: &ClassGraph,
        invocations: &FxHashSet<(Sym, MemberKey)>,
    ) -> Vec<Violation> {
        let old_scope = Scope::new(vec![old, fetched]);
        let runtime = Scope::new(vec![new, fetched]);
        let mut violations = Vec::new();
        let mut seen = FxHashSet::default();
        add_abstract_method_violations(
            &old_scope,
            &runtime,
            old,
            new,
            fetched,
            graph,
            invocations,
            &mut violations,
            &mut seen,
        );
        violations
    }

    fn invoked(pairs: &[(&str, &str, &str)]) -> FxHashSet<(Sym, MemberKey)> {
        pairs
            .iter()
            .map(|&(owner, name, desc)| (intern(owner), MemberKey::new(name, desc)))
            .collect()
    }

    /// https://github.com/exoego/uika/issues/81: reachable and instantiated, but nothing
    /// invokes the member, so the JVM cannot throw yet.
    #[test]
    fn uninvoked_abstract_method_is_reported_as_latent() {
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC, &[])]);
        let graph = scanned_graph(&[("app/C", "lib/A")]);
        let v = abstract_violations(&old, &new, &fetched, &graph);
        assert_eq!(v.len(), 1, "{v:?}");
        assert_eq!(v[0].invocation_found, Some(false));
    }

    /// The conscrypt/netty shape: the library invokes the member on the abstract base.
    #[test]
    fn invocation_through_supertype_owner_is_evidence() {
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC, &[])]);
        let graph = scanned_graph(&[("app/C", "lib/A")]);
        let v = abstract_violations_with(
            &old,
            &new,
            &fetched,
            &graph,
            &invoked(&[("lib/A", "m", "()V")]),
        );
        assert_eq!(v.len(), 1, "{v:?}");
        assert_eq!(v[0].invocation_found, Some(true));
    }

    /// The pass-1 sweep matches on name+descriptor alone, so without this filter one
    /// unrelated `close ()V` call would suppress every latent classification.
    #[test]
    fn invocation_on_unrelated_owner_is_not_evidence() {
        let old = ApiIndex::build([
            amv_class(
                "lib/A",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC,
                &[("m", "()V", ACC_PUBLIC)],
            ),
            amv_class(
                "lib/Other",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC,
                &[("m", "()V", ACC_PUBLIC)],
            ),
        ]);
        let new = ApiIndex::build([
            amv_class(
                "lib/A",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC | ACC_ABSTRACT,
                &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
            ),
            amv_class(
                "lib/Other",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC,
                &[("m", "()V", ACC_PUBLIC)],
            ),
        ]);
        let fetched = ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC, &[])]);
        let graph = scanned_graph(&[("app/C", "lib/A")]);
        let v = abstract_violations_with(
            &old,
            &new,
            &fetched,
            &graph,
            &invoked(&[("lib/Other", "m", "()V")]),
        );
        assert_eq!(v.len(), 1, "{v:?}");
        assert_eq!(v[0].invocation_found, Some(false));
    }

    /// A subtype-typed receiver IS an instance of the broken class.
    #[test]
    fn invocation_on_subtype_owner_is_evidence() {
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([
            amv_class("app/C", "lib/A", ACC_PUBLIC, &[]),
            amv_class("app/Sub", "app/C", ACC_PUBLIC, &[]),
        ]);
        let graph = scanned_graph(&[("app/C", "lib/A"), ("app/Sub", "app/C")]);
        let v = abstract_violations_with(
            &old,
            &new,
            &fetched,
            &graph,
            &invoked(&[("app/Sub", "m", "()V")]),
        );
        // Both classes inherit the unimplemented method, and the call reaches both.
        assert_eq!(v.len(), 2, "{v:?}");
        assert!(v.iter().all(|v| v.invocation_found == Some(true)), "{v:?}");
    }

    /// An escape must not license the downgrade: unseen types could relate the two.
    #[test]
    fn unrelated_owner_with_escaping_hierarchy_stays_evidence() {
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC, &[])]);
        // app/Mystery's superclass is in no scope, so the walk cannot prove it unrelated.
        let graph = scanned_graph(&[("app/C", "lib/A"), ("app/Mystery", "off/Scope")]);
        let v = abstract_violations_with(
            &old,
            &new,
            &fetched,
            &graph,
            &invoked(&[("app/Mystery", "m", "()V")]),
        );
        assert_eq!(v.len(), 1, "{v:?}");
        assert_eq!(v[0].invocation_found, Some(true));
    }

    /// `known` is the graph as of the chunk start and chunk size defaults to the thread
    /// count, so evidence skipped on that path would vary by core count.
    #[test]
    fn invocation_evidence_is_chunk_boundary_independent() {
        let class_name = intern("dup/C");
        // Only the LOSING copy of dup/C invokes the newly-abstract member.
        let winner = ParsedTarget {
            source: intern("first.jar"),
            class_name,
            hierarchy: Some(Hierarchy {
                super_name: Some(intern("lib/Base")),
                interfaces: vec![],
                nest_host: None,
            }),
            entry_override: None,
            refs: vec![],
            edges: vec![],
        };
        let loser = ParsedTarget {
            source: intern("second.jar"),
            class_name,
            hierarchy: None, // parse-time skip: already in the graph (a later chunk)
            entry_override: None,
            refs: vec![],
            edges: vec![],
        };
        let evidence = vec![(intern("lib/Base"), MemberKey::new("m", "()V"))];

        // Same chunk: both copies parsed together, evidence rides on the batch.
        let mut same_chunk = ScanResult::new();
        same_chunk.merge(ParsedTargets {
            targets: vec![winner, loser],
            warnings: vec![],
            scanned_classes: 2,
            invocations: evidence.clone(),
        });
        // Separate chunks: the loser hits the `known.contains` fast path, which must still
        // contribute its evidence.
        let mut split = ScanResult::new();
        split.merge(ParsedTargets {
            targets: vec![ParsedTarget {
                source: intern("first.jar"),
                class_name,
                hierarchy: Some(Hierarchy {
                    super_name: Some(intern("lib/Base")),
                    interfaces: vec![],
                    nest_host: None,
                }),
                entry_override: None,
                refs: vec![],
                edges: vec![],
            }],
            warnings: vec![],
            scanned_classes: 1,
            invocations: vec![],
        });
        split.merge(ParsedTargets {
            targets: vec![ParsedTarget {
                source: intern("second.jar"),
                class_name,
                hierarchy: None,
                entry_override: None,
                refs: vec![],
                edges: vec![],
            }],
            warnings: vec![],
            scanned_classes: 1,
            invocations: evidence,
        });
        assert_eq!(same_chunk.invocations, split.invocations);
        assert!(!split.invocations.is_empty());
    }

    /// The JVM reserves `java.*`, so the types above such an escape are all platform
    /// classes and none can be `lib/Other`.
    #[test]
    fn escape_into_the_jdk_does_not_block_the_latent_downgrade() {
        let old = ApiIndex::build([
            amv_class(
                "lib/A",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC,
                &[("m", "()V", ACC_PUBLIC)],
            ),
            amv_class(
                "lib/Other",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC,
                &[("m", "()V", ACC_PUBLIC)],
            ),
        ]);
        let new = ApiIndex::build([
            amv_class(
                "lib/A",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC | ACC_ABSTRACT,
                &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
            ),
            amv_class(
                "lib/Other",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC,
                &[("m", "()V", ACC_PUBLIC)],
            ),
        ]);
        let fetched = ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC, &[])]);
        // app/C implements a JDK interface that is in neither scope.
        let graph = scanned_graph_full(&[
            ("app/C", "lib/A", &["java/io/Serializable"]),
            ("lib/Other", JAVA_LANG_OBJECT, &[]),
        ]);
        let v = abstract_violations_with(
            &old,
            &new,
            &fetched,
            &graph,
            &invoked(&[("lib/Other", "m", "()V")]),
        );
        assert_eq!(v.len(), 1, "{v:?}");
        assert_eq!(v[0].invocation_found, Some(false));

        // A JDK-owned evidence reference is still blocked by the same escape: the unseen
        // platform types above Serializable could well include it.
        let jdk_owned = abstract_violations_with(
            &old,
            &new,
            &fetched,
            &graph,
            &invoked(&[("java/util/List", "m", "()V")]),
        );
        assert_eq!(jdk_owned[0].invocation_found, Some(true));

        // A non-JDK escape keeps blocking, since an unseen library type could relate them.
        let library_escape = scanned_graph_full(&[
            ("app/C", "lib/A", &["off/Scope"]),
            ("lib/Other", JAVA_LANG_OBJECT, &[]),
        ]);
        let v = abstract_violations_with(
            &old,
            &new,
            &fetched,
            &library_escape,
            &invoked(&[("lib/Other", "m", "()V")]),
        );
        assert_eq!(v[0].invocation_found, Some(true));
    }

    #[test]
    fn abstract_method_without_override_is_reported() {
        // lib A.m concrete -> abstract; consumer C extends A without overriding, so invoking
        // m on a C throws AbstractMethodError.
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC, &[])]);
        let graph = scanned_graph(&[("app/C", "lib/A")]);
        let v = abstract_violations(&old, &new, &fetched, &graph);
        assert_eq!(v.len(), 1, "{v:?}");
        assert_eq!(v[0].reason, Reason::MethodBecameAbstract);
        assert_eq!(v[0].source_class.as_str(), "app/C");
        assert_eq!(v[0].reference.owner.as_str(), "lib/A");
        let m = v[0].reference.member.unwrap();
        assert_eq!((m.name.as_str(), m.descriptor.as_str()), ("m", "()V"));
    }

    #[test]
    fn intermediate_concrete_override_suppresses_report() {
        // C -> B -> A: B provides a concrete override, so method selection stops at B and C
        // is fine.
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([
            amv_class("app/C", "app/B", ACC_PUBLIC, &[]),
            amv_class("app/B", "lib/A", ACC_PUBLIC, &[("m", "()V", ACC_PUBLIC)]),
        ]);
        let graph = scanned_graph(&[("app/C", "app/B"), ("app/B", "lib/A")]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn abstract_subclass_is_not_reported() {
        // C is itself abstract, so it is never instantiated directly and cannot throw.
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched =
            ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC | ACC_ABSTRACT, &[])]);
        let graph = scanned_graph(&[("app/C", "lib/A")]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn already_abstract_in_old_is_pre_existing() {
        // m abstract on both sides: C was already responsible for implementing it, so this is
        // pre-existing, not introduced by the upgrade.
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC, &[])]);
        let graph = scanned_graph(&[("app/C", "lib/A")]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn covariant_bridge_counts_as_concrete_override() {
        // A.m()Object concrete -> abstract. C overrides with a covariant return, so javac
        // emits a synthetic bridge m()Object (a real body). It must satisfy the abstract
        // method, so nothing is reported. This proves synthetic members are NOT filtered on
        // the resolution/override side.
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()Ljava/lang/Object;", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()Ljava/lang/Object;", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([amv_class(
            "app/C",
            "lib/A",
            ACC_PUBLIC,
            &[(
                "m",
                "()Ljava/lang/Object;",
                ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC,
            )],
        )]);
        let graph = scanned_graph(&[("app/C", "lib/A")]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn synthetic_method_subject_is_guarded() {
        // A synthetic method that became abstract is a compiler artifact, not a source-visible
        // API change, so it is not the subject of a report.
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC | ACC_SYNTHETIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC)],
        )]);
        let fetched = ApiIndex::build([amv_class("app/C", "lib/A", ACC_PUBLIC, &[])]);
        let graph = scanned_graph(&[("app/C", "lib/A")]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn unfetched_intermediate_class_stays_unknown() {
        // C -> D -> A, but D has no fetched member table, so method selection escapes to a
        // graph-only class and answers Unknown: never a false break. (collect_abstract_wanted
        // fetches D in a real run; here it is omitted to exercise the conservative path.)
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC | ACC_ABSTRACT,
            &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([amv_class("app/C", "app/D", ACC_PUBLIC, &[])]);
        let graph = scanned_graph(&[("app/C", "app/D"), ("app/D", "lib/A")]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn synthetic_became_final_is_guarded() {
        // The bridge/synthetic guard also covers the existing newly-final inference: a
        // synthetic bridge turning final is not reported, while a real method still is.
        let old = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC | ACC_BRIDGE)],
        )]);
        let new = ApiIndex::build([amv_class(
            "lib/A",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC | ACC_FINAL | ACC_BRIDGE)],
        )]);
        assert!(newly_final_methods(&old, &new).is_empty());

        let old_real = ApiIndex::build([amv_class(
            "lib/B",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new_real = ApiIndex::build([amv_class(
            "lib/B",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &[("m", "()V", ACC_PUBLIC | ACC_FINAL)],
        )]);
        assert!(!newly_final_methods(&old_real, &new_real).is_empty());
    }

    // ---- shape 2: an interface gains an abstract method ----

    /// ClassApi with explicit superclass, access flags, implemented interfaces, and methods.
    fn amv_full(
        name: &str,
        super_name: &str,
        access: u16,
        interfaces: &[&str],
        methods: &[(&str, &str, u16)],
    ) -> ClassApi {
        ClassApi {
            name: intern(name),
            access,
            super_name: Some(intern(super_name)),
            interfaces: interfaces.iter().map(|i| intern(i)).collect(),
            methods: build_members(methods.iter().map(|(n, d, a)| (MemberKey::new(n, d), *a))),
            fields: build_members([]),
            nest_host: None,
        }
    }

    /// A ClassGraph of (name, superclass, interfaces) edges, all from one origin.
    fn scanned_graph_full(nodes: &[(&str, &str, &[&str])]) -> ClassGraph {
        let mut g = ClassGraph::new();
        for (name, sup, ifaces) in nodes {
            let iface_syms: Vec<Sym> = ifaces.iter().map(|i| intern(i)).collect();
            g.insert_if_absent(
                intern(name),
                Some(intern(sup)),
                &iface_syms,
                &[],
                None,
                intern("consumer.jar"),
            );
        }
        g
    }

    const IFACE: u16 = ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT;

    #[test]
    fn added_abstract_interface_method_without_impl_is_reported() {
        // lib/I gains abstract b(); app/C implements I with only a() and never provides b(),
        // so calling b() on a C throws AbstractMethodError.
        let old = ApiIndex::build([amv_full(
            "lib/I",
            JAVA_LANG_OBJECT,
            IFACE,
            &[],
            &[("a", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let new = ApiIndex::build([amv_full(
            "lib/I",
            JAVA_LANG_OBJECT,
            IFACE,
            &[],
            &[
                ("a", "()V", ACC_PUBLIC | ACC_ABSTRACT),
                ("b", "()V", ACC_PUBLIC | ACC_ABSTRACT),
            ],
        )]);
        let fetched = ApiIndex::build([amv_full(
            "app/C",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &["lib/I"],
            &[("a", "()V", ACC_PUBLIC)],
        )]);
        let graph = scanned_graph_full(&[("app/C", JAVA_LANG_OBJECT, &["lib/I"])]);
        let v = abstract_violations(&old, &new, &fetched, &graph);
        assert_eq!(v.len(), 1, "{v:?}");
        assert_eq!(v[0].reason, Reason::MethodBecameAbstract);
        assert_eq!(v[0].source_class.as_str(), "app/C");
        assert_eq!(v[0].reference.owner.as_str(), "lib/I");
        let m = v[0].reference.member.unwrap();
        assert_eq!((m.name.as_str(), m.descriptor.as_str()), ("b", "()V"));
    }

    #[test]
    fn sibling_interface_default_suppresses_report() {
        // C implements both I (gains abstract b) and J (provides a default b). J's default
        // means there is no AbstractMethodError at runtime. The interface phase sees a mix of
        // an abstract (I) and a concrete (J) declaration and, without modeling specificity,
        // returns Unknown, so nothing is reported either way.
        let old = ApiIndex::build([
            amv_full("lib/I", JAVA_LANG_OBJECT, IFACE, &[], &[]),
            amv_full(
                "lib/J",
                JAVA_LANG_OBJECT,
                IFACE,
                &[],
                &[("b", "()V", ACC_PUBLIC)],
            ),
        ]);
        let new = ApiIndex::build([
            amv_full(
                "lib/I",
                JAVA_LANG_OBJECT,
                IFACE,
                &[],
                &[("b", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
            ),
            amv_full(
                "lib/J",
                JAVA_LANG_OBJECT,
                IFACE,
                &[],
                &[("b", "()V", ACC_PUBLIC)],
            ),
        ]);
        let fetched = ApiIndex::build([amv_full(
            "app/C",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &["lib/I", "lib/J"],
            &[],
        )]);
        let graph = scanned_graph_full(&[("app/C", JAVA_LANG_OBJECT, &["lib/I", "lib/J"])]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn added_abstract_object_method_is_suppressed() {
        // An interface that redeclares an Object method as abstract does not break a concrete
        // implementor: java.lang.Object supplies the implementation.
        let old = ApiIndex::build([amv_full("lib/I", JAVA_LANG_OBJECT, IFACE, &[], &[])]);
        let new = ApiIndex::build([amv_full(
            "lib/I",
            JAVA_LANG_OBJECT,
            IFACE,
            &[],
            &[(
                "toString",
                "()Ljava/lang/String;",
                ACC_PUBLIC | ACC_ABSTRACT,
            )],
        )]);
        let fetched = ApiIndex::build([amv_full(
            "app/C",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &["lib/I"],
            &[],
        )]);
        let graph = scanned_graph_full(&[("app/C", JAVA_LANG_OBJECT, &["lib/I"])]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn class_abstract_method_beats_interface_default() {
        // JVMS class-wins: a superclass's abstract method is selected over an interface
        // default, so a concrete subclass still throws AbstractMethodError. lib/A.m turns
        // abstract; C extends A and implements I whose default m must NOT rescue it.
        let old = ApiIndex::build([
            amv_full(
                "lib/A",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC,
                &[],
                &[("m", "()V", ACC_PUBLIC)],
            ),
            amv_full(
                "lib/I",
                JAVA_LANG_OBJECT,
                IFACE,
                &[],
                &[("m", "()V", ACC_PUBLIC)],
            ),
        ]);
        let new = ApiIndex::build([
            amv_full(
                "lib/A",
                JAVA_LANG_OBJECT,
                ACC_PUBLIC | ACC_ABSTRACT,
                &[],
                &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
            ),
            amv_full(
                "lib/I",
                JAVA_LANG_OBJECT,
                IFACE,
                &[],
                &[("m", "()V", ACC_PUBLIC)],
            ),
        ]);
        let fetched = ApiIndex::build([amv_full("app/C", "lib/A", ACC_PUBLIC, &["lib/I"], &[])]);
        let graph = scanned_graph_full(&[("app/C", "lib/A", &["lib/I"])]);
        let v = abstract_violations(&old, &new, &fetched, &graph);
        assert_eq!(v.len(), 1, "{v:?}");
        assert_eq!(v[0].reference.owner.as_str(), "lib/A");
    }

    #[test]
    fn reabstracting_a_shadowed_default_is_pre_existing() {
        // I's default m becomes abstract, but sub-interface J already re-declares m abstract
        // in both versions. C implements J, so J.m (abstract, maximally specific) is selected
        // and C already threw AbstractMethodError against old. The interface phase returns
        // Unknown for the old side (mixed abstract J + concrete default I), so the pre-existing
        // break is not misreported as introduced by the upgrade.
        let old = ApiIndex::build([
            amv_full(
                "lib/I",
                JAVA_LANG_OBJECT,
                IFACE,
                &[],
                &[("m", "()V", ACC_PUBLIC)],
            ),
            amv_full(
                "lib/J",
                JAVA_LANG_OBJECT,
                IFACE,
                &["lib/I"],
                &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
            ),
        ]);
        let new = ApiIndex::build([
            amv_full(
                "lib/I",
                JAVA_LANG_OBJECT,
                IFACE,
                &[],
                &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
            ),
            amv_full(
                "lib/J",
                JAVA_LANG_OBJECT,
                IFACE,
                &["lib/I"],
                &[("m", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
            ),
        ]);
        let fetched = ApiIndex::build([amv_full(
            "app/C",
            JAVA_LANG_OBJECT,
            ACC_PUBLIC,
            &["lib/J"],
            &[],
        )]);
        let graph = scanned_graph_full(&[("app/C", JAVA_LANG_OBJECT, &["lib/J"])]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }

    #[test]
    fn added_abstract_method_with_escaping_super_is_unknown() {
        // C implements I (gains abstract b) but also extends an unscanned class that could
        // supply b, so the closure escapes scope and nothing is reported.
        let old = ApiIndex::build([amv_full("lib/I", JAVA_LANG_OBJECT, IFACE, &[], &[])]);
        let new = ApiIndex::build([amv_full(
            "lib/I",
            JAVA_LANG_OBJECT,
            IFACE,
            &[],
            &[("b", "()V", ACC_PUBLIC | ACC_ABSTRACT)],
        )]);
        let fetched = ApiIndex::build([amv_full("app/C", "ext/Base", ACC_PUBLIC, &["lib/I"], &[])]);
        let graph = scanned_graph_full(&[("app/C", "ext/Base", &["lib/I"])]);
        assert!(abstract_violations(&old, &new, &fetched, &graph).is_empty());
    }
}
