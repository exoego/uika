use crate::check::CheckReport;
use crate::model::{BreakingChange, Reason, RefKind, Violation, counts_as_reachable};
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

/// "com/google/Foo$Bar" -> "com.google.Foo$Bar". `$` is kept: it is part of the class's
/// binary name and dotting it would fabricate a nesting that does not exist for Foo$1.
fn dotted(name: &str) -> String {
    name.replace('/', ".")
}

/// Last segment of an internal class name: "org/koin/logger/SLF4JLogger" -> "SLF4JLogger".
fn simple(name: &str) -> &str {
    name.rsplit('/').next().unwrap_or(name)
}

/// One JVM type descriptor -> (Java-ish simple name, bytes consumed). None on malformed input.
fn parse_type(s: &str) -> Option<(String, usize)> {
    match s.as_bytes().first()? {
        b'B' => Some(("byte".to_string(), 1)),
        b'C' => Some(("char".to_string(), 1)),
        b'D' => Some(("double".to_string(), 1)),
        b'F' => Some(("float".to_string(), 1)),
        b'I' => Some(("int".to_string(), 1)),
        b'J' => Some(("long".to_string(), 1)),
        b'S' => Some(("short".to_string(), 1)),
        b'Z' => Some(("boolean".to_string(), 1)),
        b'V' => Some(("void".to_string(), 1)),
        b'L' => {
            let end = s.find(';')?;
            Some((simple(&s[1..end]).to_string(), end + 1))
        }
        b'[' => {
            let (inner, used) = parse_type(&s[1..])?;
            Some((format!("{inner}[]"), used + 1))
        }
        _ => None,
    }
}

/// Parameter list of a method descriptor as Java-ish simple names:
/// "(Ljava/util/concurrent/Callable;JLjava/util/concurrent/TimeUnit;Z)Ljava/lang/Object;"
/// -> "Callable, long, TimeUnit, boolean". None if the descriptor does not parse.
fn pretty_params(descriptor: &str) -> Option<String> {
    let inner = descriptor.strip_prefix('(')?;
    let mut rest = &inner[..inner.find(')')?];
    let mut params = Vec::new();
    while !rest.is_empty() {
        let (name, used) = parse_type(rest)?;
        params.push(name);
        rest = &rest[used..];
    }
    Some(params.join(", "))
}

/// A member as a Java-ish signature: "owner.name(params)" for methods,
/// "owner constructor (params)" for `<init>`, "owner.name: type" for fields.
/// A descriptor that does not parse falls back to the raw form.
fn pretty_member(owner: &str, name: &str, descriptor: &str) -> String {
    let owner = dotted(owner);
    if descriptor.starts_with('(') {
        match (pretty_params(descriptor), name) {
            (Some(p), "<init>") => format!("{owner} constructor ({p})"),
            (Some(p), _) => format!("{owner}.{name}({p})"),
            (None, _) => format!("{owner}.{name} {descriptor}"),
        }
    } else {
        match parse_type(descriptor) {
            Some((t, _)) => format!("{owner}.{name}: {t}"),
            None => format!("{owner}.{name} {descriptor}"),
        }
    }
}

/// The referenced symbol as shown on a violation line: bare owner for a class reference,
/// otherwise the pretty member signature.
fn pretty_target(v: &Violation) -> String {
    match &v.reference.member {
        None => dotted(v.reference.owner.as_str()),
        Some(m) => pretty_member(
            v.reference.owner.as_str(),
            m.name.as_str(),
            m.descriptor.as_str(),
        ),
    }
}

fn is_constructor(v: &Violation) -> bool {
    v.reference
        .member
        .is_some_and(|m| m.name.as_str() == "<init>")
}

/// The reason as printed: "method ..." reads as "constructor ..." for `<init>` references.
fn display_reason(v: &Violation) -> &'static str {
    match (v.reason, is_constructor(v)) {
        (Reason::MethodRemoved, true) => "constructor removed",
        (Reason::MethodAccessNarrowed, true) => "constructor access narrowed",
        (r, _) => r.as_str(),
    }
}

/// The error the JVM raises for a reference-style violation and when — the string a reader
/// greps production logs for. Exhaustive over `Reason` so a new variant fails here at
/// compile time; the structural arm returns None because those violations render through
/// structural_lines, which carries its own error line.
fn runtime_error(v: &Violation) -> Option<&'static str> {
    use Reason::*;
    let ctor = is_constructor(v);
    Some(match v.reason {
        ClassRemoved => "NoClassDefFoundError at first use",
        ClassAccessNarrowed => "IllegalAccessError at first use",
        ClassBecameAbstract => "InstantiationError at first `new`",
        ClassKindChanged => "IncompatibleClassChangeError at first call",
        MethodRemoved if ctor => "NoSuchMethodError at first `new`",
        MethodRemoved => "NoSuchMethodError at first call",
        FieldRemoved => "NoSuchFieldError at first access",
        MethodAccessNarrowed if ctor => "IllegalAccessError at first `new`",
        MethodAccessNarrowed => "IllegalAccessError at first call",
        FieldAccessNarrowed => "IllegalAccessError at first access",
        FieldBecameFinal => "IllegalAccessError at first write",
        StaticToInstance | InstanceToStatic => {
            if v.reference.kind == RefKind::Field {
                "IncompatibleClassChangeError at first access"
            } else {
                "IncompatibleClassChangeError at first call"
            }
        }
        ClassBecameFinal | MethodBecameFinal | ExtendsFinalClass | MethodBecameAbstract => {
            return None;
        }
    })
}

/// JAR paths shrink to the file name (the full path stays in the JSON output); directories
/// keep the full display string, whose basename alone (e.g. "main") says nothing.
fn source_display(source: &str) -> &str {
    if source.ends_with(".jar") {
        source.rsplit(['/', '\\']).next().unwrap_or(source)
    } else {
        source
    }
}

/// Graph-walk violations: the broken thing is the scanned class itself (its hierarchy no
/// longer loads or selects), so they read consumer-first, unlike reference violations
/// which group by the symbol that changed. Exhaustive over `Reason`: ClassKindChanged
/// exists in both worlds, and the graph-walk variant is the member-less Class edge.
fn is_structural(v: &Violation) -> bool {
    use Reason::*;
    match v.reason {
        ClassBecameFinal | MethodBecameFinal | ExtendsFinalClass | MethodBecameAbstract => true,
        ClassKindChanged => v.reference.member.is_none(),
        ClassRemoved | ClassAccessNarrowed | ClassBecameAbstract | MethodRemoved
        | MethodAccessNarrowed | FieldRemoved | FieldAccessNarrowed | FieldBecameFinal
        | StaticToInstance | InstanceToStatic => false,
    }
}

/// (what happened, what the JVM does about it) for one structural violation.
fn structural_lines(v: &Violation) -> (String, String) {
    let owner = dotted(v.reference.owner.as_str());
    let loads = format!(
        "-> VerifyError when {} loads",
        simple(v.source_class.as_str())
    );
    match (v.reason, v.reference.member) {
        (Reason::MethodBecameAbstract, Some(m)) => (
            format!(
                "inherits abstract {} without implementing it",
                pretty_member(
                    v.reference.owner.as_str(),
                    m.name.as_str(),
                    m.descriptor.as_str()
                )
            ),
            format!("-> AbstractMethodError when {} is called", m.name),
        ),
        (Reason::MethodBecameFinal, Some(m)) => (
            format!(
                "overrides {} which became final",
                pretty_member(
                    v.reference.owner.as_str(),
                    m.name.as_str(),
                    m.descriptor.as_str()
                )
            ),
            loads,
        ),
        (Reason::ClassBecameFinal, _) => (format!("extends {owner} which became final"), loads),
        (Reason::ExtendsFinalClass, _) => (
            format!("extends {owner} which is final on the runtime classpath"),
            loads,
        ),
        (Reason::ClassKindChanged, _) => (
            format!("extends or implements {owner} whose kind changed (class <-> interface)"),
            format!(
                "-> IncompatibleClassChangeError when {} loads",
                simple(v.source_class.as_str())
            ),
        ),
        // Only structural reasons reach here (violation_blocks partitions on is_structural);
        // a malformed member-less method reason degrades readably, not by panic.
        (r, _) => (format!("{}: {owner}", r.as_str()), loads),
    }
}

/// Per-module upgrade-check attribution, appended to the line a violation prints on.
fn modules_suffix(v: &Violation) -> String {
    if v.modules.is_empty() {
        String::new()
    } else {
        format!(" [{}]", v.modules.join(", "))
    }
}

/// Render a set of violations as blocks (no trailing newlines; the caller joins with blank
/// lines). upgrade-check violations carry a suggestion and group by the fix (one 💡 block
/// lists every reference a single piece of advice covers). The rest split by shape:
/// reference violations group by the broken symbol (the unit a fix targets), structural
/// graph-walk violations by the scanned class that no longer loads.
fn violation_blocks(violations: &[&Violation]) -> Vec<String> {
    let (with_sugg, without): (Vec<&Violation>, Vec<&Violation>) = violations
        .iter()
        .copied()
        .partition(|v| v.suggestion.is_some());
    let (structural, reference): (Vec<&Violation>, Vec<&Violation>) =
        without.into_iter().partition(|v| is_structural(v));
    let mut blocks = suggestion_blocks(&with_sugg);
    blocks.extend(reference_blocks(&reference));
    blocks.extend(structural_blocks(&structural));
    blocks
}

/// One 💡 block per distinct fix. Identical advice implies the same removed_by / referenced_by /
/// before / after (the advice string embeds the coordinates and changed versions), so the header
/// is built from any member of the group. The block reads as prose — the advice, a "why:"
/// sentence naming the version change as the cause, then one sentence per broken reference —
/// so the advice never looks like the thing the labels below it accuse. References are
/// deduplicated and ordered by sentence text, keeping the output deterministic.
fn suggestion_blocks(violations: &[&Violation]) -> Vec<String> {
    let mut grouped: BTreeMap<&str, Vec<&Violation>> = BTreeMap::new();
    for &v in violations {
        let advice = v.suggestion.as_ref().unwrap().advice.as_str();
        grouped.entry(advice).or_default().push(v);
    }
    grouped
        .into_values()
        .map(|vs| {
            let mut out = String::new();
            let s = vs[0].suggestion.as_ref().unwrap();
            writeln!(out, "💡 suggestion: {}", s.advice).unwrap();
            // Per-module upgrade-check: the modules whose classpaths exhibit this group.
            let mut modules: Vec<&str> = vs
                .iter()
                .flat_map(|v| v.modules.iter().map(String::as_str))
                .collect();
            modules.sort_unstable();
            modules.dedup();
            if !modules.is_empty() {
                writeln!(out, "    affected modules: {}", modules.join(", ")).unwrap();
            }
            match &s.referenced_by {
                Some(rb) => writeln!(
                    out,
                    "    why: {} changed {} -> {}, which breaks {rb}:",
                    s.removed_by, s.before, s.after
                )
                .unwrap(),
                None => writeln!(
                    out,
                    "    why: {} changed {} -> {}:",
                    s.removed_by, s.before, s.after
                )
                .unwrap(),
            }
            let sentences: std::collections::BTreeSet<String> =
                vs.iter().map(|v| suggestion_line(v)).collect();
            for sentence in sentences {
                writeln!(out, "        {sentence}").unwrap();
            }
            out.truncate(out.trim_end().len());
            out
        })
        .collect()
}

/// One reference in a 💡 block as an English sentence: what the library change did, and
/// what the consumer still does that no longer works. Exhaustive over `Reason`, like
/// runtime_error, so a new variant must pick its phrasing here at compile time.
fn suggestion_line(v: &Violation) -> String {
    use Reason::*;
    let class = dotted(v.source_class.as_str());
    let target = pretty_target(v);
    let owner = dotted(v.reference.owner.as_str());
    let ctor = is_constructor(v);
    let access_verb = if v.reference.kind == RefKind::Field {
        "accesses"
    } else {
        "calls"
    };
    match v.reason {
        ClassRemoved => format!("{target} was removed, but {class} still uses it"),
        MethodRemoved if ctor => format!("{target} was removed, but {class} still instantiates it"),
        MethodRemoved => format!("{target} was removed, but {class} still calls it"),
        FieldRemoved => format!("{target} was removed, but {class} still accesses it"),
        ClassAccessNarrowed => {
            format!("{target} is no longer accessible, but {class} still uses it")
        }
        MethodAccessNarrowed if ctor => {
            format!("{target} is no longer accessible, but {class} still instantiates it")
        }
        MethodAccessNarrowed => {
            format!("{target} is no longer accessible, but {class} still calls it")
        }
        FieldAccessNarrowed => {
            format!("{target} is no longer accessible, but {class} still accesses it")
        }
        FieldBecameFinal => format!("{target} became final, but {class} still writes to it"),
        ClassBecameAbstract => {
            format!("{target} became abstract, but {class} still instantiates it")
        }
        ClassKindChanged => {
            if v.reference.member.is_some() {
                format!(
                    "{owner} changed kind (class <-> interface), but {class} was compiled against the old kind"
                )
            } else {
                format!(
                    "{class} extends or implements {owner}, whose kind changed (class <-> interface)"
                )
            }
        }
        StaticToInstance => format!(
            "{target} changed from static to instance, but {class} still {access_verb} it as static"
        ),
        InstanceToStatic => format!(
            "{target} changed from instance to static, but {class} still {access_verb} it as an instance member"
        ),
        ClassBecameFinal => format!("{owner} became final, but {class} still extends it"),
        MethodBecameFinal => format!("{target} became final, but {class} still overrides it"),
        ExtendsFinalClass => {
            format!("{class} extends {owner}, which is final on the runtime classpath")
        }
        MethodBecameAbstract => {
            format!("{target} became abstract, but {class} does not implement it")
        }
    }
}

/// One ❌ block per broken symbol: the symbol as heading, the reason with the runtime
/// error it causes, then every referencing class. BTreeMap on (heading, reason) strings
/// keeps the order deterministic by string value.
fn reference_blocks(violations: &[&Violation]) -> Vec<String> {
    let mut grouped: BTreeMap<(String, &'static str), Vec<&Violation>> = BTreeMap::new();
    for &v in violations {
        grouped
            .entry((pretty_target(v), v.reason.as_str()))
            .or_default()
            .push(v);
    }
    grouped
        .into_iter()
        .map(|((target, _), vs)| {
            let mut out = String::new();
            writeln!(out, "❌ {target}").unwrap();
            match runtime_error(vs[0]) {
                Some(err) => writeln!(out, "    {} -> {err}", display_reason(vs[0])).unwrap(),
                None => writeln!(out, "    {}", display_reason(vs[0])).unwrap(),
            }
            let users: std::collections::BTreeSet<(String, String, String)> = vs
                .iter()
                .map(|v| {
                    (
                        dotted(v.source_class.as_str()),
                        source_display(v.source.as_str()).to_string(),
                        modules_suffix(v),
                    )
                })
                .collect();
            let plural = if users.len() == 1 { "" } else { "es" };
            writeln!(out, "    used by {} class{plural}:", users.len()).unwrap();
            for (class, source, mods) in users {
                writeln!(out, "        {class}  ({source}){mods}").unwrap();
            }
            out.truncate(out.trim_end().len());
            out
        })
        .collect()
}

/// One ❌ block per scanned class whose own shape broke; each entry pairs what happened
/// with the error the JVM raises for it.
fn structural_blocks(violations: &[&Violation]) -> Vec<String> {
    let mut grouped: BTreeMap<(String, String), Vec<&Violation>> = BTreeMap::new();
    for &v in violations {
        grouped
            .entry((
                dotted(v.source_class.as_str()),
                source_display(v.source.as_str()).to_string(),
            ))
            .or_default()
            .push(v);
    }
    grouped
        .into_iter()
        .map(|((class, source), mut vs)| {
            let mut out = String::new();
            writeln!(out, "❌ {class}  ({source})").unwrap();
            vs.sort_by_cached_key(|v| (pretty_target(v), v.reason.as_str()));
            for v in vs {
                let (what, err) = structural_lines(v);
                writeln!(out, "    {what}{}", modules_suffix(v)).unwrap();
                writeln!(out, "        {err}").unwrap();
            }
            out.truncate(out.trim_end().len());
            out
        })
        .collect()
}

/// Bottom line: totals plus per-tier counts. ✅ marks a clean run; ❌/❓ mirror the block
/// markers above.
fn summary_line(report: &CheckReport, reachable: usize, unproven: usize) -> String {
    let broken = report.violations.len();
    let mut line = if broken == 0 {
        format!("✅ scanned {} classes: 0 broken", report.scanned_classes)
    } else {
        format!(
            "scanned {} classes: ❌ {broken} broken",
            report.scanned_classes
        )
    };
    if report.reachability_computed && broken > 0 {
        write!(
            line,
            " (of which 💥 {reachable} reachable, ⚠️ {unproven} not proven reachable)"
        )
        .unwrap();
    }
    if report.unknown_refs > 0 {
        write!(
            line,
            ", ❓ {} unverified reference{} (hierarchy escapes scope)",
            report.unknown_refs,
            if report.unknown_refs == 1 { "" } else { "s" }
        )
        .unwrap();
    }
    if report.suppressed > 0 {
        write!(line, ", {} suppressed by --exclude-file", report.suppressed).unwrap();
    }
    line
}

/// One-line context header for plain `check`: which library pair was compared and against
/// how many scan targets. upgrade-check prints its dependency-change header instead.
pub fn check_header(
    old: &[std::path::PathBuf],
    new: &[std::path::PathBuf],
    targets: usize,
) -> String {
    fn names(paths: &[std::path::PathBuf]) -> String {
        paths
            .iter()
            .map(|p| match p.file_name() {
                Some(n) => n.to_string_lossy().into_owned(),
                None => p.display().to_string(),
            })
            .collect::<Vec<_>>()
            .join(", ")
    }
    let plural = if targets == 1 { "" } else { "s" };
    format!(
        "checked {} -> {} against {targets} scan target{plural}\n\n",
        names(old),
        names(new)
    )
}

pub fn check_text(report: &CheckReport) -> String {
    let mut sections: Vec<String> = Vec::new();
    // Reachable first (likely to break), then the ones we could not prove reachable
    // (no static path found, but reflection may still load them).
    let (reachable, unproven): (Vec<&Violation>, Vec<&Violation>) = report
        .violations
        .iter()
        .partition(|v| counts_as_reachable(v.reachable));
    if report.reachability_computed {
        if !reachable.is_empty() {
            sections.push(format!(
                "💥 reachable from the application (likely to break)\n\n{}",
                violation_blocks(&reachable).join("\n\n")
            ));
        }
        if !unproven.is_empty() {
            sections.push(format!(
                "⚠️  not proven reachable (no static path found; may still load via reflection)\n\n{}",
                violation_blocks(&unproven).join("\n\n")
            ));
        }
    } else if !report.violations.is_empty() {
        sections.push(violation_blocks(&report.violations.iter().collect::<Vec<_>>()).join("\n\n"));
    }
    sections.push(summary_line(report, reachable.len(), unproven.len()));
    sections.join("\n\n") + "\n"
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
            "    {label} {} {} -> {}",
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
            let broken = if o.broken == 0 {
                "✅ 0 broken".to_string()
            } else {
                format!("❌ {} broken", o.broken)
            };
            let unverified = if o.unknown_refs == 0 {
                "0 unverified".to_string()
            } else {
                format!("❓ {} unverified", o.unknown_refs)
            };
            writeln!(
                out,
                "    {}  scanned {} classes, {broken}, {unverified}",
                o.modules.join(", "),
                o.scanned_classes,
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
    use crate::model::{MemberKey, Suggestion, SymbolRef};

    fn class_violation(
        source_class: &str,
        owner: &str,
        reason: Reason,
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
            reason,
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

    fn member_violation(
        source_class: &str,
        owner: &str,
        name: &str,
        descriptor: &str,
        kind: RefKind,
        reason: Reason,
    ) -> Violation {
        Violation {
            source: intern("consumer.jar"),
            source_class: intern(source_class),
            reference: SymbolRef {
                kind,
                owner: intern(owner),
                member: Some(MemberKey::new(name, descriptor)),
                expected_static: None,
                field_write: None,
                instantiated: None,
            },
            reason,
            reachable: None,
            suggestion: None,
            modules: Vec::new(),
        }
    }

    /// Several references sharing one piece of advice collapse into a single 💡 block, and the
    /// block is ordered before a distinct one; a violation without a suggestion falls back to the
    /// symbol listing.
    #[test]
    fn suggestions_group_by_advice() {
        let r = report(vec![
            class_violation(
                "a/Foo",
                "x/GoneA",
                Reason::ClassRemoved,
                Some(true),
                Some("ADVICE_A"),
            ),
            class_violation(
                "a/Bar",
                "x/GoneB",
                Reason::ClassRemoved,
                Some(true),
                Some("ADVICE_A"),
            ),
            class_violation(
                "a/Baz",
                "x/GoneC",
                Reason::ClassRemoved,
                Some(true),
                Some("ADVICE_B"),
            ),
        ]);
        let out = check_text(&r);
        // Shared advice printed once, both references listed under it.
        assert_eq!(out.matches("💡 suggestion: ADVICE_A").count(), 1, "\n{out}");
        assert_eq!(out.matches("💡 suggestion: ADVICE_B").count(), 1, "\n{out}");
        assert!(
            out.contains("    why: g:owner changed 1 -> 2, which breaks g:referencer:1:"),
            "\n{out}"
        );
        assert!(
            out.contains("        x.GoneA was removed, but a.Foo still uses it"),
            "\n{out}"
        );
        assert!(
            out.contains("        x.GoneB was removed, but a.Bar still uses it"),
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
                Reason::ClassRemoved,
                Some(true),
                Some("ADVICE_A"),
            ),
            class_violation(
                "a/Bar",
                "x/Gone",
                Reason::ClassRemoved,
                Some(false),
                Some("ADVICE_A"),
            ),
        ]);
        let out = check_text(&r);
        assert_eq!(out.matches("💡 suggestion: ADVICE_A").count(), 2, "\n{out}");
        let reachable = out.find("reachable from the application").unwrap();
        let unproven = out.find("not proven reachable").unwrap();
        assert!(reachable < unproven);
        // Foo (reachable) sits in the 💥 section, Bar (unproven) in the ⚠️ section.
        assert!(out.find("a.Foo").unwrap() < unproven, "\n{out}");
        assert!(out.find("a.Bar").unwrap() > unproven, "\n{out}");
    }

    /// Violations without a suggestion group by the broken symbol, with the runtime error
    /// the reason maps to and the referencing classes listed under it.
    #[test]
    fn unattributed_violation_groups_by_symbol() {
        let mut r = report(vec![
            class_violation("a/Foo", "x/Gone", Reason::ClassRemoved, None, None),
            class_violation("a/Bar", "x/Gone", Reason::ClassRemoved, None, None),
        ]);
        r.reachability_computed = false;
        let out = check_text(&r);
        // One block for the one removed symbol, both users under it.
        assert_eq!(out.matches("❌ x.Gone").count(), 1, "\n{out}");
        assert!(
            out.contains("    class removed -> NoClassDefFoundError at first use"),
            "\n{out}"
        );
        assert!(out.contains("    used by 2 classes:"), "\n{out}");
        assert!(out.contains("        a.Bar  (consumer.jar)"), "\n{out}");
        assert!(out.contains("        a.Foo  (consumer.jar)"), "\n{out}");
        assert!(!out.contains("💡"), "\n{out}");
    }

    /// Method and field references render as Java-ish signatures: descriptors are decoded,
    /// `<init>` reads as a constructor, and the reason wording follows.
    #[test]
    fn member_references_render_as_signatures() {
        let mut r = report(vec![
            member_violation(
                "a/Foo",
                "x/TimeLimiter",
                "callWithTimeout",
                "(Ljava/util/concurrent/Callable;JLjava/util/concurrent/TimeUnit;Z)Ljava/lang/Object;",
                RefKind::Method,
                Reason::MethodRemoved,
            ),
            member_violation(
                "a/Foo",
                "x/SimpleTimeLimiter",
                "<init>",
                "(Ljava/util/concurrent/ExecutorService;)V",
                RefKind::Method,
                Reason::MethodAccessNarrowed,
            ),
            member_violation(
                "a/Foo",
                "x/Fields",
                "COUNTS",
                "[I",
                RefKind::Field,
                Reason::FieldRemoved,
            ),
        ]);
        r.reachability_computed = false;
        let out = check_text(&r);
        assert!(
            out.contains("❌ x.TimeLimiter.callWithTimeout(Callable, long, TimeUnit, boolean)"),
            "\n{out}"
        );
        assert!(
            out.contains("    method removed -> NoSuchMethodError at first call"),
            "\n{out}"
        );
        assert!(
            out.contains("❌ x.SimpleTimeLimiter constructor (ExecutorService)"),
            "\n{out}"
        );
        assert!(
            out.contains("    constructor access narrowed -> IllegalAccessError at first `new`"),
            "\n{out}"
        );
        assert!(out.contains("❌ x.Fields.COUNTS: int[]"), "\n{out}");
        assert!(
            out.contains("    field removed -> NoSuchFieldError at first access"),
            "\n{out}"
        );
    }

    /// Graph-walk violations stay consumer-first: one block per broken scanned class,
    /// each entry phrased as what the class does and the error the JVM raises.
    #[test]
    fn structural_violations_group_by_consumer_class() {
        let mut r = report(vec![
            member_violation(
                "org/koin/logger/SLF4JLogger",
                "org/koin/core/logger/Logger",
                "display",
                "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V",
                RefKind::Method,
                Reason::MethodBecameAbstract,
            ),
            member_violation(
                "org/koin/logger/SLF4JLogger",
                "org/koin/core/logger/Logger",
                "log",
                "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V",
                RefKind::Method,
                Reason::MethodBecameFinal,
            ),
        ]);
        r.reachability_computed = false;
        let out = check_text(&r);
        // One consumer-class block holding both structural breaks.
        assert_eq!(
            out.matches("❌ org.koin.logger.SLF4JLogger  (consumer.jar)")
                .count(),
            1,
            "\n{out}"
        );
        assert!(
            out.contains(
                "    inherits abstract org.koin.core.logger.Logger.display(Level, String) without implementing it"
            ),
            "\n{out}"
        );
        assert!(
            out.contains("        -> AbstractMethodError when display is called"),
            "\n{out}"
        );
        assert!(
            out.contains(
                "    overrides org.koin.core.logger.Logger.log(Level, String) which became final"
            ),
            "\n{out}"
        );
        assert!(
            out.contains("        -> VerifyError when SLF4JLogger loads"),
            "\n{out}"
        );
    }

    /// A clean run collapses to the ✅ summary line, still carrying the unverified count.
    #[test]
    fn clean_run_summary_leads_with_check_mark() {
        let mut r = report(Vec::new());
        r.unknown_refs = 16;
        let out = check_text(&r);
        assert_eq!(
            out,
            "✅ scanned 100 classes: 0 broken, ❓ 16 unverified references (hierarchy escapes scope)\n"
        );
    }

    /// The plain-check header names the compared pair and the scan target count.
    #[test]
    fn check_header_names_pair_and_targets() {
        let old = vec![std::path::PathBuf::from("/x/guava-22.0.jar")];
        let new = vec![std::path::PathBuf::from("/y/guava-23.0-rc1.jar")];
        assert_eq!(
            check_header(&old, &new, 1),
            "checked guava-22.0.jar -> guava-23.0-rc1.jar against 1 scan target\n\n"
        );
        assert_eq!(
            check_header(&old, &new, 3),
            "checked guava-22.0.jar -> guava-23.0-rc1.jar against 3 scan targets\n\n"
        );
    }

    /// The summary line notes suppressed violations only when the count is nonzero.
    #[test]
    fn suppressed_note_appears_only_when_nonzero() {
        let mut r = report(vec![class_violation(
            "a/Foo",
            "x/Gone",
            Reason::ClassRemoved,
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
