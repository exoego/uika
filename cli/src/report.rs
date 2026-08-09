use crate::check::CheckReport;
use crate::intern::Sym;
use crate::model::{
    BreakingChange, ClassKind, Reason, RefKind, Tier, Violation, reachable_axis_valid, tier,
};
use anyhow::Result;
use serde::Serialize;
use std::collections::BTreeMap;
use std::fmt::Write;

/// Width of the tag column every `diff` line opens with. Padding to one width here is
/// what keeps the names starting at the same column: the tags used to be padded by hand
/// and had drifted into three columns (17, 23 and 24) inside a single listing. The tags
/// are kept short, because everything the column takes comes off the name, which is the
/// long part of the line. The widest is BECAME INTERFACE.
const TAG_WIDTH: usize = 16;

/// The `(descriptor changed? now: ...)` hint, as a continuation line indented to the name
/// column. The indent is derived from TAG_WIDTH, never spelled out, so it cannot drift.
fn replacement_hint(replacements: &[Sym]) -> String {
    if replacements.is_empty() {
        return String::new();
    }
    format!(
        "\n{:TAG_WIDTH$} (descriptor changed? now: {})",
        "",
        replacements
            .iter()
            .map(|d| d.as_str())
            .collect::<Vec<_>>()
            .join(", ")
    )
}

pub fn diff_text(changes: &[BreakingChange]) -> String {
    let mut out = String::new();
    let (mut classes, mut methods, mut fields) = (0usize, 0usize, 0usize);
    for c in changes {
        // One tag and one subject per change, printed through a single writeln below, so
        // no arm can invent its own column.
        let (tag, subject) = match c {
            BreakingChange::ClassRemoved { class } => {
                classes += 1;
                ("CLASS REMOVED", format!("{class}"))
            }
            BreakingChange::MethodRemoved {
                class,
                name,
                descriptor,
                replacement_descriptors,
            } => {
                methods += 1;
                (
                    "METHOD REMOVED",
                    format!(
                        "{class}.{name} {descriptor}{}",
                        replacement_hint(replacement_descriptors)
                    ),
                )
            }
            BreakingChange::FieldRemoved {
                class,
                name,
                descriptor,
                replacement_descriptors,
            } => {
                fields += 1;
                (
                    "FIELD REMOVED",
                    format!(
                        "{class}.{name} {descriptor}{}",
                        replacement_hint(replacement_descriptors)
                    ),
                )
            }
            BreakingChange::ClassAccessNarrowed { class, from, to } => {
                classes += 1;
                (
                    "CLASS NARROWED",
                    format!("{class} ({} -> {})", from.as_str(), to.as_str()),
                )
            }
            BreakingChange::ClassBecameFinal { class } => {
                classes += 1;
                ("CLASS FINAL", format!("{class}"))
            }
            BreakingChange::ClassBecameAbstract { class } => {
                classes += 1;
                ("CLASS ABSTRACT", format!("{class}"))
            }
            BreakingChange::ClassKindChanged { class, to, .. } => {
                classes += 1;
                // The tag carries the direction, since the class it names is right there:
                // "BECAME INTERFACE io/ktor/utils/io/ByteChannel".
                let tag = match to {
                    ClassKind::Interface => "BECAME INTERFACE",
                    ClassKind::Class => "BECAME CLASS",
                };
                (tag, format!("{class}"))
            }
            BreakingChange::MethodBecameAbstract {
                class,
                name,
                descriptor,
            } => {
                methods += 1;
                ("METHOD ABSTRACT", format!("{class}.{name} {descriptor}"))
            }
            BreakingChange::MethodAccessNarrowed {
                class,
                name,
                descriptor,
                from,
                to,
            } => {
                methods += 1;
                (
                    "METHOD NARROWED",
                    format!(
                        "{class}.{name} {descriptor} ({} -> {})",
                        from.as_str(),
                        to.as_str()
                    ),
                )
            }
            BreakingChange::FieldAccessNarrowed {
                class,
                name,
                descriptor,
                from,
                to,
            } => {
                fields += 1;
                (
                    "FIELD NARROWED",
                    format!(
                        "{class}.{name} {descriptor} ({} -> {})",
                        from.as_str(),
                        to.as_str()
                    ),
                )
            }
            BreakingChange::MethodStaticChanged {
                class,
                name,
                descriptor,
                from,
                to,
            } => {
                methods += 1;
                (
                    "METHOD STATIC",
                    format!(
                        "{class}.{name} {descriptor} ({} -> {})",
                        from.as_str(),
                        to.as_str()
                    ),
                )
            }
            BreakingChange::FieldStaticChanged {
                class,
                name,
                descriptor,
                from,
                to,
            } => {
                fields += 1;
                (
                    "FIELD STATIC",
                    format!(
                        "{class}.{name} {descriptor} ({} -> {})",
                        from.as_str(),
                        to.as_str()
                    ),
                )
            }
            BreakingChange::FieldBecameFinal {
                class,
                name,
                descriptor,
            } => {
                fields += 1;
                ("FIELD FINAL", format!("{class}.{name} {descriptor}"))
            }
            BreakingChange::MethodBecameFinal {
                class,
                name,
                descriptor,
            } => {
                methods += 1;
                ("METHOD FINAL", format!("{class}.{name} {descriptor}"))
            }
        };
        writeln!(out, "{tag:<TAG_WIDTH$} {subject}").unwrap();
    }
    writeln!(
        out,
        "\nbreaking changes: {} (classes: {classes}, methods: {methods}, fields: {fields})",
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

/// One JVM type descriptor -> (Java-ish simple name, bytes consumed). None on malformed
/// input. Array dimensions are consumed iteratively with the JVMS 4.3.2 cap of 255, never
/// by recursion: a corrupt descriptor can carry a 64KB run of '[' and a recursive parse
/// would overflow the stack and abort the process mid-report.
fn parse_type(s: &str) -> Option<(String, usize)> {
    let dims = s.bytes().take_while(|&b| b == b'[').count();
    if dims > 255 {
        return None;
    }
    let rest = &s[dims..];
    let (base, used) = match rest.as_bytes().first()? {
        b'B' => ("byte", 1),
        b'C' => ("char", 1),
        b'D' => ("double", 1),
        b'F' => ("float", 1),
        b'I' => ("int", 1),
        b'J' => ("long", 1),
        b'S' => ("short", 1),
        b'Z' => ("boolean", 1),
        b'V' => ("void", 1),
        b'L' => {
            let end = rest.find(';')?;
            (simple(&rest[1..end]), end + 1)
        }
        _ => return None,
    };
    Some((format!("{base}{}", "[]".repeat(dims)), dims + used))
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

/// The referenced symbol with the raw member name and descriptor (the same shape
/// pretty_member falls back to for a descriptor that does not parse). The pretty form is
/// lossy — parameter packages and the return type are erased — so this is the identity
/// distinct symbols are grouped by, and the display fallback when two of them would
/// otherwise pretty-print alike.
fn raw_target(v: &Violation) -> String {
    match &v.reference.member {
        None => dotted(v.reference.owner.as_str()),
        Some(m) => format!(
            "{}.{} {}",
            dotted(v.reference.owner.as_str()),
            m.name.as_str(),
            m.descriptor.as_str()
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
    use Reason::*;
    let owner = dotted(v.reference.owner.as_str());
    let loads = format!(
        "throws VerifyError when {} loads",
        simple(v.source_class.as_str())
    );
    match (v.reason, v.reference.member) {
        (MethodBecameAbstract, Some(m)) => (
            format!(
                "inherits abstract {} without implementing it",
                pretty_target(v)
            ),
            match v.invocation_found {
                Some(false) => format!(
                    "throws AbstractMethodError only when {} is first called (no invocation found in scanned bytecode)",
                    m.name
                ),
                _ => format!("throws AbstractMethodError when {} is called", m.name),
            },
        ),
        (MethodBecameFinal, Some(_)) => (
            format!("overrides {}, which became final", pretty_target(v)),
            loads,
        ),
        (ClassBecameFinal, _) => (format!("extends {owner}, which became final"), loads),
        (ExtendsFinalClass, _) => (
            format!("extends {owner}, which is final on the runtime classpath"),
            loads,
        ),
        (ClassKindChanged, _) => (
            format!("extends or implements {owner}, whose kind changed (class <-> interface)"),
            format!(
                "throws IncompatibleClassChangeError when {} loads",
                simple(v.source_class.as_str())
            ),
        ),
        // The graph walks always attach the member to these two reasons (check.rs); a
        // member-less one would be malformed, so degrade readably rather than panic.
        (MethodBecameAbstract | MethodBecameFinal, None) => {
            (format!("{}: {owner}", v.reason.as_str()), loads)
        }
        // Non-structural reasons render through reference_blocks (violation_blocks
        // partitions on is_structural). Listed rather than wildcarded so a new Reason
        // variant must choose its structural rendering here at compile time.
        (
            ClassRemoved | ClassAccessNarrowed | ClassBecameAbstract | MethodRemoved
            | MethodAccessNarrowed | FieldRemoved | FieldAccessNarrowed | FieldBecameFinal
            | StaticToInstance | InstanceToStatic,
            _,
        ) => (format!("{}: {owner}", v.reason.as_str()), loads),
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

/// One 💡 block per distinct fix. Groups key on the advice TOGETHER WITH every field the
/// "why:" line quotes (removed_by / before / after / referenced_by): advice alone does not
/// pin them — the removed-coordinate advice embeds no versions and the changed-coordinate
/// advice embeds only the moved delta, so per-module runs over different resolved version
/// lists can produce byte-identical advice. Keying on all quoted fields keeps every
/// why-line true for each reference under it (same advice with differing versions prints
/// as adjacent blocks). The block reads as prose — the advice, a "why:" sentence naming
/// the version change as the cause, then one sentence per broken reference — so the
/// advice never looks like the thing the labels below it accuse. References are
/// deduplicated by (sentence, raw symbol) and ordered by sentence text, keeping the
/// output deterministic.
fn suggestion_blocks(violations: &[&Violation]) -> Vec<String> {
    type WhyKey<'a> = (&'a str, &'a str, &'a str, &'a str, Option<&'a str>);
    let mut grouped: BTreeMap<WhyKey, Vec<&Violation>> = BTreeMap::new();
    for &v in violations {
        let s = v.suggestion.as_ref().unwrap();
        let key = (
            s.advice.as_str(),
            s.removed_by.as_str(),
            s.before.as_str(),
            s.after.as_str(),
            s.referenced_by.as_deref(),
        );
        grouped.entry(key).or_default().push(v);
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
            let breaks = match &s.referenced_by {
                Some(rb) => format!(", which breaks {rb}"),
                None => String::new(),
            };
            writeln!(
                out,
                "    why: {} changed {} -> {}{breaks}:",
                s.removed_by, s.before, s.after
            )
            .unwrap();
            // A sentence names the pretty target, which can collide for distinct symbols
            // (erased parameter packages / return type); a sentence spanning several raw
            // targets is re-rendered with the raw form so every break stays visible.
            // Identical (sentence, symbol) pairs — the same break surfaced by several
            // module runs — still print once.
            let mut by_sentence: BTreeMap<String, BTreeMap<String, &Violation>> = BTreeMap::new();
            for &v in &vs {
                by_sentence
                    .entry(suggestion_line(v, &pretty_target(v)))
                    .or_default()
                    .entry(raw_target(v))
                    .or_insert(v);
            }
            let mut sentences: std::collections::BTreeSet<String> = Default::default();
            for (sentence, by_raw) in by_sentence {
                if by_raw.len() == 1 {
                    sentences.insert(sentence);
                } else {
                    for (raw, v) in by_raw {
                        sentences.insert(suggestion_line(v, &raw));
                    }
                }
            }
            for sentence in sentences {
                writeln!(out, "        {sentence}").unwrap();
            }
            out.truncate(out.trim_end().len());
            out
        })
        .collect()
}

/// One reference in a 💡 block as an English sentence: what the library change did, and
/// what the consumer still does that no longer works. `target` is the rendered symbol
/// (pretty normally, raw when the caller detected a pretty collision). Exhaustive over
/// `Reason`, like runtime_error, so a new variant must pick its phrasing here at compile
/// time.
fn suggestion_line(v: &Violation, target: &str) -> String {
    use Reason::*;
    let class = dotted(v.source_class.as_str());
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
            "{target} changed from static to instance, but {class} still {access_verb} it as a static member"
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
/// error it causes, then every referencing class. Grouping is by the RAW symbol identity
/// (the pretty form erases parameter packages and the return type, so two distinct
/// removed overloads can pretty-print alike); the heading stays pretty unless it would
/// collide with another block's, in which case it degrades to the raw form so both
/// symbols stay distinguishable and the block count matches the summary's broken count.
/// BTreeMap on string keys keeps the order deterministic by string value.
fn reference_blocks(violations: &[&Violation]) -> Vec<String> {
    let mut grouped: BTreeMap<(String, &'static str, String), Vec<&Violation>> = BTreeMap::new();
    for &v in violations {
        grouped
            .entry((pretty_target(v), v.reason.as_str(), raw_target(v)))
            .or_default()
            .push(v);
    }
    let mut pretty_counts: BTreeMap<(&str, &'static str), usize> = BTreeMap::new();
    for (pretty, reason, _) in grouped.keys() {
        *pretty_counts.entry((pretty.as_str(), *reason)).or_default() += 1;
    }
    let ambiguous: std::collections::BTreeSet<(String, &'static str)> = pretty_counts
        .iter()
        .filter(|&(_, &n)| n > 1)
        .map(|(&(pretty, reason), _)| (pretty.to_string(), reason))
        .collect();
    grouped
        .into_iter()
        .map(|((pretty, reason, raw), vs)| {
            let target = if ambiguous.contains(&(pretty.clone(), reason)) {
                raw
            } else {
                pretty
            };
            let mut out = String::new();
            writeln!(out, "❌ {target}").unwrap();
            match runtime_error(vs[0]) {
                Some(err) => writeln!(out, "    {}, throws {err}", display_reason(vs[0])).unwrap(),
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
/// with the error the JVM raises for it. Grouped by the raw source path (two jars sharing
/// a basename must not merge); only the printed form is shortened.
fn structural_blocks(violations: &[&Violation]) -> Vec<String> {
    let mut grouped: BTreeMap<(String, &str), Vec<&Violation>> = BTreeMap::new();
    for &v in violations {
        grouped
            .entry((dotted(v.source_class.as_str()), v.source.as_str()))
            .or_default()
            .push(v);
    }
    grouped
        .into_iter()
        .map(|((class, source), mut vs)| {
            let mut out = String::new();
            writeln!(out, "❌ {class}  ({})", source_display(source)).unwrap();
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
fn summary_line(report: &CheckReport, reachable: usize, latent: usize, unproven: usize) -> String {
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
        let latent_segment = if latent > 0 {
            format!(", 💤 {latent} latent")
        } else {
            String::new()
        };
        write!(
            line,
            " (of which 💥 {reachable} reachable{latent_segment}, ⚠️ {unproven} not proven reachable)"
        )
        .unwrap();
    } else if latent > 0 && broken > 0 {
        write!(line, " (of which 💤 {latent} latent)").unwrap();
    }
    if report.unknown_refs > 0 {
        write!(
            line,
            ", ❓ {} unverified reference{} (hierarchy escapes the analyzed scope)",
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
/// how many scan targets (the admitted count — see CheckReport::scan_targets — so the
/// number agrees with any skip warnings above it). Paths are named by the same rule as
/// the violation lines (source_display): jars shrink to the file name, directories keep
/// the full path, whose basename alone (e.g. "main") says nothing. upgrade-check prints
/// its dependency-change header instead.
pub fn check_header(
    old: &[std::path::PathBuf],
    new: &[std::path::PathBuf],
    targets: usize,
) -> String {
    fn names(paths: &[std::path::PathBuf]) -> String {
        paths
            .iter()
            .map(|p| {
                let display = p.display().to_string();
                source_display(&display).to_string()
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

/// A reachability-tier title sandwiched in 80-column rules, so the section boundary
/// stands out when scrolling a long report.
fn section_heading(title: &str) -> String {
    const RULE: &str =
        "--------------------------------------------------------------------------------";
    format!("{RULE}\n{title}\n{RULE}")
}

/// The 💤 headings for the two report shapes, kept side by side so their wording cannot
/// drift apart: the only difference is that the ranked report can also say the class is
/// reachable, which the flat report has no basis to claim.
const LATENT_HEADING: &str = "💤 latent (no scanned code invokes the affected member)";
const LATENT_HEADING_RANKED: &str =
    "💤 latent (class reachable, but no scanned code invokes the affected member)";

pub fn check_text(report: &CheckReport) -> String {
    let mut sections: Vec<String> = Vec::new();
    // Most severe first.
    let mut breaks: Vec<&Violation> = Vec::new();
    let mut latent: Vec<&Violation> = Vec::new();
    let mut unproven: Vec<&Violation> = Vec::new();
    // Same judgement the exit gate makes, so sections and --fail-on cannot drift.
    let axis = reachable_axis_valid(report.app_roots_matched);
    for v in &report.violations {
        match tier(v, axis) {
            Tier::Breaks => breaks.push(v),
            Tier::Latent => latent.push(v),
            Tier::Unproven => unproven.push(v),
        }
    }
    if report.reachability_computed {
        if !breaks.is_empty() {
            sections.push(format!(
                "{}\n\n{}",
                section_heading("💥 reachable from the application (likely to break)"),
                violation_blocks(&breaks).join("\n\n")
            ));
        }
        if !latent.is_empty() {
            sections.push(format!(
                "{}\n\n{}",
                section_heading(LATENT_HEADING_RANKED),
                violation_blocks(&latent).join("\n\n")
            ));
        }
        if !unproven.is_empty() {
            sections.push(format!(
                "{}\n\n{}",
                section_heading(
                    "⚠️  not proven reachable (no static path found; may still load via reflection)"
                ),
                violation_blocks(&unproven).join("\n\n")
            ));
        }
    } else if !report.violations.is_empty() {
        // No reachability axis, so one flat list, but latency is scan-derived and keeps
        // its section. `unproven` is always empty here; chaining it survives that changing.
        let flat: Vec<&Violation> = breaks.iter().chain(&unproven).copied().collect();
        if !flat.is_empty() {
            sections.push(violation_blocks(&flat).join("\n\n"));
        }
        if !latent.is_empty() {
            sections.push(format!(
                "{}\n\n{}",
                section_heading(LATENT_HEADING),
                violation_blocks(&latent).join("\n\n")
            ));
        }
    }
    sections.push(summary_line(
        report,
        breaks.len(),
        latent.len(),
        unproven.len(),
    ));
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
            "per-module check: {checked} of {} modules changed their resolved versions ({})",
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
    use crate::model::{Binding, MemberKey, Suggestion, SymbolRef, Visibility};

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
            invocation_found: None,
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
            scan_targets: 1,
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
            invocation_found: None,
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

    /// A `method became abstract` violation with no invocation in scanned bytecode gets
    /// its own section between 💥 and ⚠️, and the summary counts it separately: the class
    /// is reachable, but AbstractMethodError throws at invocation, not at class load.
    #[test]
    fn latent_violations_get_their_own_section() {
        let mut latent = member_violation(
            "app/Adapter",
            "lib/Base",
            "allocateHeapBuffer",
            "(I)V",
            RefKind::Method,
            Reason::MethodBecameAbstract,
        );
        latent.reachable = Some(true);
        latent.invocation_found = Some(false);
        let mut invoked = member_violation(
            "app/Other",
            "lib/Base",
            "allocateDirectBuffer",
            "(I)V",
            RefKind::Method,
            Reason::MethodBecameAbstract,
        );
        invoked.reachable = Some(true);
        invoked.invocation_found = Some(true);
        let mut unproven = class_violation(
            "app/Dead",
            "lib/Gone",
            Reason::ClassRemoved,
            Some(false),
            None,
        );
        unproven.reachable = Some(false);

        let out = check_text(&report(vec![latent, invoked, unproven]));
        let breaks_at = out.find("reachable from the application").unwrap();
        let latent_at = out.find("💤 latent").unwrap();
        let unproven_at = out.find("not proven reachable (no static path").unwrap();
        assert!(breaks_at < latent_at && latent_at < unproven_at, "{out}");
        // Each violation sits in its own tier.
        let latent_body = &out[latent_at..unproven_at];
        assert!(
            latent_body.contains("app/Adapter".replace('/', ".").as_str())
                || latent_body.contains("Adapter"),
            "{out}"
        );
        assert!(!latent_body.contains("allocateDirectBuffer"), "{out}");
        // The latent block explains that the error waits for a first call.
        assert!(
            latent_body.contains("no invocation found in scanned bytecode"),
            "{out}"
        );
        assert!(
            out.contains("💥 1 reachable, 💤 1 latent, ⚠️ 1 not proven reachable"),
            "{out}"
        );
    }

    /// With no latent violations the summary keeps its original two-tier wording, so the
    /// common report is untouched by the new tier.
    #[test]
    fn summary_omits_latent_segment_when_empty() {
        let out = check_text(&report(vec![class_violation(
            "app/Foo",
            "lib/Gone",
            Reason::ClassRemoved,
            Some(true),
            None,
        )]));
        assert!(
            out.contains("💥 1 reachable, ⚠️ 0 not proven reachable"),
            "{out}"
        );
        assert!(!out.contains("latent"), "{out}");
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
            out.contains("    class removed, throws NoClassDefFoundError at first use"),
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
            out.contains("    method removed, throws NoSuchMethodError at first call"),
            "\n{out}"
        );
        assert!(
            out.contains("❌ x.SimpleTimeLimiter constructor (ExecutorService)"),
            "\n{out}"
        );
        assert!(
            out.contains(
                "    constructor access narrowed, throws IllegalAccessError at first `new`"
            ),
            "\n{out}"
        );
        assert!(out.contains("❌ x.Fields.COUNTS: int[]"), "\n{out}");
        assert!(
            out.contains("    field removed, throws NoSuchFieldError at first access"),
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
            out.contains("        throws AbstractMethodError when display is called"),
            "\n{out}"
        );
        assert!(
            out.contains(
                "    overrides org.koin.core.logger.Logger.log(Level, String), which became final"
            ),
            "\n{out}"
        );
        assert!(
            out.contains("        throws VerifyError when SLF4JLogger loads"),
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
            "✅ scanned 100 classes: 0 broken, ❓ 16 unverified references (hierarchy escapes the analyzed scope)\n"
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

    /// Directory inputs keep their full path in the header (the basename alone, e.g.
    /// "main", says nothing), matching the source_display rule the body uses.
    #[test]
    fn check_header_keeps_directory_paths_whole() {
        let old = vec![std::path::PathBuf::from("a/build/classes/java/main")];
        let new = vec![std::path::PathBuf::from("b/build/classes/java/main")];
        assert_eq!(
            check_header(&old, &new, 1),
            "checked a/build/classes/java/main -> b/build/classes/java/main against 1 scan target\n\n"
        );
    }

    /// Two distinct removed overloads that pretty-print alike (param packages and return
    /// types are erased) must stay two ❌ blocks, distinguished by the raw descriptor, so
    /// the block count matches the summary's broken count.
    #[test]
    fn colliding_overloads_stay_distinct_blocks() {
        let mut r = report(vec![
            member_violation(
                "a/Foo",
                "x/C",
                "m",
                "(Ljava/util/Date;)V",
                RefKind::Method,
                Reason::MethodRemoved,
            ),
            member_violation(
                "a/Foo",
                "x/C",
                "m",
                "(Ljava/sql/Date;)V",
                RefKind::Method,
                Reason::MethodRemoved,
            ),
        ]);
        r.reachability_computed = false;
        let out = check_text(&r);
        assert_eq!(out.matches("❌ x.C.m ").count(), 2, "\n{out}");
        assert!(out.contains("❌ x.C.m (Ljava/util/Date;)V"), "\n{out}");
        assert!(out.contains("❌ x.C.m (Ljava/sql/Date;)V"), "\n{out}");
        assert!(out.contains("❌ 2 broken"), "\n{out}");
    }

    /// The same collision inside a 💡 block: the two breaks stay two sentences (raw
    /// form), never silently deduplicated into one.
    #[test]
    fn colliding_overloads_stay_distinct_suggestion_lines() {
        let mut a = member_violation(
            "a/Foo",
            "x/C",
            "m",
            "(Ljava/util/Date;)V",
            RefKind::Method,
            Reason::MethodRemoved,
        );
        let mut b = member_violation(
            "a/Foo",
            "x/C",
            "m",
            "(Ljava/sql/Date;)V",
            RefKind::Method,
            Reason::MethodRemoved,
        );
        let suggestion = Suggestion {
            referenced_by: Some("g:referencer:1".to_string()),
            removed_by: "g:owner".to_string(),
            before: "1".to_string(),
            after: "2".to_string(),
            advice: "ADVICE_A".to_string(),
        };
        a.suggestion = Some(suggestion.clone());
        b.suggestion = Some(suggestion);
        a.reachable = Some(true);
        b.reachable = Some(true);
        let out = check_text(&report(vec![a, b]));
        assert_eq!(out.matches("💡 suggestion: ADVICE_A").count(), 1, "\n{out}");
        assert!(
            out.contains("x.C.m (Ljava/util/Date;)V was removed, but a.Foo still calls it"),
            "\n{out}"
        );
        assert!(
            out.contains("x.C.m (Ljava/sql/Date;)V was removed, but a.Foo still calls it"),
            "\n{out}"
        );
    }

    /// Identical advice does not imply identical versions (per-module runs can resolve
    /// different lists); the why-line must only ever cover references it is true for.
    #[test]
    fn same_advice_with_different_versions_splits_blocks() {
        let a = class_violation(
            "a/Foo",
            "x/GoneA",
            Reason::ClassRemoved,
            Some(true),
            Some("ADVICE_A"),
        );
        let mut b = class_violation(
            "a/Bar",
            "x/GoneB",
            Reason::ClassRemoved,
            Some(true),
            Some("ADVICE_A"),
        );
        b.suggestion.as_mut().unwrap().before = "1.5".to_string();
        let out = check_text(&report(vec![a.clone(), b]));
        assert_eq!(out.matches("💡 suggestion: ADVICE_A").count(), 2, "\n{out}");
        assert!(
            out.contains("why: g:owner changed 1 -> 2, which breaks g:referencer:1:"),
            "\n{out}"
        );
        assert!(
            out.contains("why: g:owner changed 1.5 -> 2, which breaks g:referencer:1:"),
            "\n{out}"
        );
        // Same versions collapse back into one block.
        let b_same = {
            let mut v = a.clone();
            v.source_class = crate::intern::intern("a/Baz");
            v
        };
        let out = check_text(&report(vec![a, b_same]));
        assert_eq!(out.matches("💡 suggestion: ADVICE_A").count(), 1, "\n{out}");
    }

    /// A corrupt descriptor with a huge array-dimension run must fall back to the raw
    /// form, never recurse per dimension (a 64KB '[' run would overflow the stack).
    #[test]
    fn absurd_array_depth_degrades_to_raw_descriptor() {
        let deep = format!("({}I)V", "[".repeat(60_000));
        let mut r = report(vec![member_violation(
            "a/Foo",
            "x/C",
            "m",
            &deep,
            RefKind::Method,
            Reason::MethodRemoved,
        )]);
        r.reachability_computed = false;
        let out = check_text(&r);
        // Raw fallback: the undecoded descriptor appears after the member name.
        assert!(out.contains("❌ x.C.m ("));
        // Legal depths still decode.
        assert_eq!(parse_type("[[I"), Some(("int[][]".to_string(), 3)));
        assert_eq!(parse_type(&"[".repeat(300)), None);
    }

    /// One change of every kind, so the listing below is the whole vocabulary of `diff`.
    fn one_of_every_change() -> Vec<BreakingChange> {
        let (class, method, field, desc) = (intern("x/C"), intern("m"), intern("F"), intern("()V"));
        vec![
            BreakingChange::ClassRemoved { class },
            BreakingChange::MethodRemoved {
                class,
                name: method,
                descriptor: desc,
                replacement_descriptors: vec![intern("(I)V")],
            },
            BreakingChange::FieldRemoved {
                class,
                name: field,
                descriptor: intern("I"),
                replacement_descriptors: Vec::new(),
            },
            BreakingChange::ClassAccessNarrowed {
                class,
                from: Visibility::Public,
                to: Visibility::PackagePrivate,
            },
            BreakingChange::ClassBecameFinal { class },
            BreakingChange::ClassBecameAbstract { class },
            BreakingChange::ClassKindChanged {
                class,
                from: ClassKind::Interface,
                to: ClassKind::Class,
            },
            BreakingChange::ClassKindChanged {
                class,
                from: ClassKind::Class,
                to: ClassKind::Interface,
            },
            BreakingChange::MethodBecameAbstract {
                class,
                name: method,
                descriptor: desc,
            },
            BreakingChange::MethodAccessNarrowed {
                class,
                name: method,
                descriptor: desc,
                from: Visibility::Public,
                to: Visibility::Protected,
            },
            BreakingChange::FieldAccessNarrowed {
                class,
                name: field,
                descriptor: intern("I"),
                from: Visibility::Protected,
                to: Visibility::Private,
            },
            BreakingChange::MethodStaticChanged {
                class,
                name: method,
                descriptor: desc,
                from: Binding::Static,
                to: Binding::Instance,
            },
            BreakingChange::FieldStaticChanged {
                class,
                name: field,
                descriptor: intern("I"),
                from: Binding::Instance,
                to: Binding::Static,
            },
            BreakingChange::FieldBecameFinal {
                class,
                name: field,
                descriptor: intern("I"),
            },
            BreakingChange::MethodBecameFinal {
                class,
                name: method,
                descriptor: desc,
            },
        ]
    }

    /// Every `diff` line starts the name at the same column, continuation lines included.
    /// The tags used to be padded by hand and had drifted into three columns at once, so
    /// this asserts the property rather than the individual spellings.
    #[test]
    fn diff_text_starts_every_name_at_one_column() {
        let out = diff_text(&one_of_every_change());
        let body = out.lines().take_while(|l| !l.is_empty());
        for line in body {
            let name_at = line.find("x/C").or_else(|| line.find('(')).unwrap();
            assert_eq!(name_at, TAG_WIDTH + 1, "misaligned: {line:?}\n{out}");
            assert!(
                line.as_bytes()[TAG_WIDTH] == b' ',
                "tag {line:?} fills the separator column\n{out}"
            );
        }
    }

    /// The parentheticals carry what the short tags leave out, and a newly-final method
    /// counts as a method: under a bucket for "other" it read as miscellaneous, which put
    /// every method of a Kotlin library (final by default) in the wrong column.
    #[test]
    fn diff_text_names_both_sides_of_a_change() {
        let out = diff_text(&one_of_every_change());
        for expected in [
            "CLASS REMOVED    x/C",
            "METHOD REMOVED   x/C.m ()V",
            "                 (descriptor changed? now: (I)V)",
            "CLASS NARROWED   x/C (public -> package-private)",
            "CLASS FINAL      x/C",
            "CLASS ABSTRACT   x/C",
            // The kind flip puts its direction in the tag, since the class it names is
            // right there on the line.
            "BECAME CLASS     x/C",
            "BECAME INTERFACE x/C",
            "METHOD ABSTRACT  x/C.m ()V",
            "METHOD NARROWED  x/C.m ()V (public -> protected)",
            "FIELD NARROWED   x/C.F I (protected -> private)",
            "METHOD STATIC    x/C.m ()V (static -> instance)",
            "FIELD STATIC     x/C.F I (instance -> static)",
            "METHOD FINAL     x/C.m ()V",
            "breaking changes: 15 (classes: 6, methods: 5, fields: 4)",
        ] {
            assert!(out.contains(expected), "missing {expected:?}\n{out}");
        }
    }

    /// `--json` says what the text says. It used to hand out the raw JVM data the text was
    /// built from, so a reader had to know that access flag 1 means public.
    #[test]
    fn diff_json_speaks_the_same_words_as_the_text() {
        let out = diff_json(&one_of_every_change()).unwrap();
        for expected in [
            r#""kind": "class_access_narrowed""#,
            r#""from": "public""#,
            r#""to": "package-private""#,
            r#""from": "protected""#,
            r#""to": "private""#,
            r#""from": "interface""#,
            r#""to": "interface""#,
            r#""from": "static""#,
            r#""to": "instance""#,
        ] {
            assert!(out.contains(expected), "missing {expected}\n{out}");
        }
        // No raw flag words or bools left to decode.
        for gone in ["old_access", "new_access", "old_static", "old_interface"] {
            assert!(!out.contains(gone), "{gone} still in\n{out}");
        }
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
