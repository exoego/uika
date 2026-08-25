//! Runtime class-load evidence (`--class-load-log`): opt-in logs from a JVM run of the
//! CURRENT, not yet upgraded, build — typically the base branch's test suite run with
//! `-Xlog:class+load=info:file=...` and stored as a CI artifact for the dependency PR to
//! read. A ⚠️ violation whose referencing class shows up in the log is promoted: the class
//! provably loads, and reflection-driven loading — the static walk's blind spot — is
//! exactly what such a log captures.
//!
//! Promote-only, the same stance reachability takes: an observed load lifts a violation
//! out of ⚠️, but absence of a load entry proves nothing beyond the observed runs, so
//! nothing is ever demoted or dropped on this input. The one deliberate consumer of
//! absence is `draft_excludes`, which only ever writes a file for a human to review and
//! says so in every reason it drafts.

use crate::model::{Tier, Violation, reachable_axis_valid, tier};
// "com/foo/Bar" -> "com.foo.Bar" for prose inside drafted reasons; report.rs owns the
// definition so the rendering and the drafts name classes identically.
use crate::report::dotted;
use anyhow::{Context, Result, bail};
use rustc_hash::FxHashMap;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write as _;
use std::io::{BufRead, Read};
use std::path::{Path, PathBuf};

/// Longest line the parser will look at. JVM log lines are short; the cap exists so a
/// stray file with no newline (a binary dropped into the log directory) is skipped in
/// bounded memory instead of being buffered whole.
const MAX_LINE: usize = 64 * 1024;

/// What the logs recorded for one loaded class.
enum LoadRecord {
    /// Load line(s) only, no cause stack consumed yet.
    Loaded,
    /// A `class+load+cause` stack was consumed (first stack with frames wins) and reduced
    /// to its trigger — None when every frame was loading machinery.
    Stacked(Option<String>),
}

impl LoadRecord {
    fn trigger(&self) -> Option<&str> {
        match self {
            LoadRecord::Loaded => None,
            LoadRecord::Stacked(t) => t.as_deref(),
        }
    }
}

pub struct LoadEvidence {
    /// Slashed internal name -> what was observed. Slashed so lookups match
    /// `Violation.source_class` without converting per violation. Only the composed
    /// trigger is retained per class, never raw frames: a cause-mode log
    /// (`-XX:LogClassLoadingCauseFor=*`, the shape README recommends for test suites)
    /// names every class the run loaded, and retaining even a capped stack for each of
    /// tens of thousands of classes cost hundreds of MB for data `apply` never read.
    loaded: FxHashMap<String, LoadRecord>,
    /// The log paths the evidence came from (pre-joined), for notes and drafted reasons.
    sources: String,
}

impl LoadEvidence {
    pub fn distinct_classes(&self) -> usize {
        self.loaded.len()
    }

    pub fn sources(&self) -> &str {
        &self.sources
    }

    fn observed(&self, slashed: &str) -> Option<&LoadRecord> {
        self.loaded.get(slashed)
    }
}

/// Streaming state for one open `class+load+cause` stack block. The trigger is computed
/// as the frames pass by — never retained — so cause-mode logs cost one record per class,
/// and a delegation chain of any depth still yields its trigger (a fixed retention cap
/// used to lose the trigger past 32 machinery frames).
struct StackBlock {
    /// Slashed class the block is for.
    class: String,
    /// Whether this block owns the class's stack slot (first stack with frames wins; a
    /// class whose stack was already consumed has later blocks read and dropped).
    fresh: bool,
    frames_seen: bool,
    /// First frame outside the loading machinery — the trigger candidate.
    first_useful: Option<String>,
    /// First non-machinery, non-reflective frame, sought only when `first_useful` is a
    /// reflective API: "Class.forName" alone says how, the caller says who.
    caller: Option<String>,
}

/// Per-file parser state: the map of observations plus the open stack block, if any.
struct Parser<'a> {
    loaded: &'a mut FxHashMap<String, LoadRecord>,
    current: Option<StackBlock>,
}

impl Parser<'_> {
    /// Close the open stack block, reducing its frames to the composed trigger.
    fn close_block(&mut self) {
        let Some(block) = self.current.take() else {
            return;
        };
        if !(block.fresh && block.frames_seen) {
            return;
        }
        let trigger = match block.first_useful {
            None => None,
            Some(first) if is_reflective(&first) => match block.caller {
                // "java.lang.Class.forName(Class.java:100)" -> "java.lang.Class.forName":
                // the reflective API's own source location says nothing; the caller keeps
                // its own.
                Some(caller) => {
                    let api = first.split('(').next().unwrap_or(&first);
                    Some(format!("{api} from {caller}"))
                }
                None => Some(first),
            },
            Some(first) => Some(first),
        };
        self.loaded
            .insert(block.class, LoadRecord::Stacked(trigger));
    }

    fn open_block(&mut self, class: String) {
        // First stack wins: a class several loaders define logs several stacks, and the
        // first is the one that pulled the class in.
        let fresh = !matches!(self.loaded.get(&class), Some(LoadRecord::Stacked(_)));
        self.loaded
            .entry(class.clone())
            .or_insert(LoadRecord::Loaded);
        self.current = Some(StackBlock {
            class,
            fresh,
            frames_seen: false,
            first_useful: None,
            caller: None,
        });
    }

    fn frame(&mut self, frame: &str) {
        let Some(block) = &mut self.current else {
            return;
        };
        block.frames_seen = true;
        if !block.fresh || is_machinery(frame) {
            return;
        }
        match &block.first_useful {
            None => block.first_useful = Some(frame.to_string()),
            Some(first)
                if is_reflective(first) && block.caller.is_none() && !is_reflective(frame) =>
            {
                block.caller = Some(frame.to_string());
            }
            _ => {}
        }
    }
}

/// Parse one or more class-load logs. Accepted per line, leniently:
/// - JDK unified logging with any decorators: `[0.1s][info][class,load] a.b.C source: ...`.
///   A line whose tags decorator names another stream (gc, jit) is skipped, so a log file
///   shared with other `-Xlog` streams works.
/// - `class+load+cause` blocks: `Java stack when loading a.b.C:` followed by `at ...`
///   frames. The native variant marks the class loaded; its frames are not Java frames.
/// - Undecorated class-name lines, dotted or slashed: plain lists and
///   `-XX:DumpLoadedClassList` classlists.
///
/// Anything else is ignored rather than an error: these files are produced by a JVM, can
/// interleave with other output, and get truncated by the crashes worth studying, so a
/// strict parser would reject exactly the interesting runs.
///
/// A directory reads every regular file under it (recursively, in sorted order for
/// determinism; symlinks are followed, and a symlink cycle is a clean error): parallel
/// test JVMs write per-process files (`%p` in the `-Xlog` file name, because each JVM
/// truncates a shared file on open), and a downloaded CI artifact unpacks to a directory,
/// so the directory is the natural unit to pass.
pub fn load(paths: &[PathBuf]) -> Result<LoadEvidence> {
    let mut loaded = FxHashMap::default();
    for path in paths {
        if path.is_dir() {
            for entry in walkdir::WalkDir::new(path)
                .follow_links(true)
                .sort_by_file_name()
            {
                let entry = entry.with_context(|| {
                    format!("cannot read class-load log directory {}", path.display())
                })?;
                if entry.file_type().is_file() {
                    // Binary JFR recordings live next to their plugin-converted text in
                    // the intended flow; skip them by name instead of byte-scanning up
                    // to 250MB each for newlines. An explicitly passed `.jfr` path is
                    // still read (and skipped line by line), so text evidence
                    // deliberately named `.jfr` keeps a way in.
                    if entry.path().extension().is_some_and(|ext| ext == "jfr") {
                        continue;
                    }
                    parse_file(entry.path(), &mut loaded)?;
                }
            }
        } else {
            parse_file(path, &mut loaded)?;
        }
    }
    Ok(LoadEvidence {
        loaded,
        sources: paths
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join(", "),
    })
}

fn parse_file(path: &Path, loaded: &mut FxHashMap<String, LoadRecord>) -> Result<()> {
    let file = std::fs::File::open(path)
        .with_context(|| format!("cannot read class-load log {}", path.display()))?;
    let mut reader = std::io::BufReader::new(file);
    let mut buf = Vec::new();
    let mut parser = Parser {
        loaded,
        current: None,
    };
    loop {
        buf.clear();
        let read_error = || format!("cannot read class-load log {}", path.display());
        let n = reader
            .by_ref()
            .take(MAX_LINE as u64)
            .read_until(b'\n', &mut buf)
            .with_context(read_error)?;
        if n == 0 {
            break;
        }
        if n == MAX_LINE && buf.last() != Some(&b'\n') {
            // Oversized line (a binary in the directory): skip to the next newline in
            // bounded memory, treat it like any other unrecognized line.
            loop {
                buf.clear();
                let n = reader
                    .by_ref()
                    .take(MAX_LINE as u64)
                    .read_until(b'\n', &mut buf)
                    .with_context(read_error)?;
                if n == 0 || buf.last() == Some(&b'\n') {
                    break;
                }
            }
            parser.close_block();
            continue;
        }
        let line = String::from_utf8_lossy(&buf);
        parse_line(line.trim_end(), &mut parser);
    }
    // EOF ends an open stack block like any other block boundary.
    parser.close_block();
    Ok(())
}

fn parse_line(line: &str, parser: &mut Parser<'_>) {
    // Strip unified-logging decorator groups. A tags decorator naming another stream
    // means the line belongs to a different `-Xlog` output sharing the file. Tags are
    // matched as exact comma-separated members ("class" and "load" both present), because
    // substring matching also caught the class,loader,* family; and only a multi-member
    // group counts as a foreign tags decorator, since a single-word group is just as
    // likely a level ([info]) or hostname decorator, and a tags-less decorator set
    // (-Xlog:class+load=info:file=x:time) must still fall through to the token parse.
    let mut rest = line.trim_start();
    let mut trusted = false;
    let mut foreign = false;
    let mut native = false;
    while let Some(tail) = rest.strip_prefix('[') {
        let Some(end) = tail.find(']') else { break };
        let group = &tail[..end];
        if group.contains(',') {
            let mut class_seen = false;
            let mut load_seen = false;
            for tag in group.split(',') {
                match tag.trim() {
                    "class" => class_seen = true,
                    "load" => load_seen = true,
                    "native" => native = true,
                    _ => {}
                }
            }
            if class_seen && load_seen {
                trusted = true;
            } else {
                foreign = true;
            }
        }
        rest = tail[end + 1..].trim_start();
    }
    if foreign && !trusted {
        // A foreign line is skipped outright — it must not end an open stack block,
        // because streams interleave per line and the cause block continues after it.
        return;
    }
    let rest = rest.trim();
    if let Some(class) = rest.strip_prefix("Java stack when loading ") {
        parser.close_block();
        // The JVM itself printed the header, so the name is trusted like a tagged line.
        if let Some(class) = normalize(class.trim_end_matches(':'), true) {
            parser.open_block(class);
        }
        return;
    }
    if let Some(class) = rest.strip_prefix("Native stack when loading ") {
        parser.close_block();
        if let Some(class) = normalize(class.trim_end_matches(':'), true) {
            parser.loaded.entry(class).or_insert(LoadRecord::Loaded);
        }
        return;
    }
    // Native stack frames share the class,load,cause,native tags with their header, and
    // they are not class names: the frame-kind letters ("V  [libjvm.so+0x...]",
    // "j  java.lang.Thread.run()V+8") would pass the trusted single-segment path and
    // register bogus default-package classes an obfuscated jar could then collide with.
    if native {
        return;
    }
    if let Some(frame) = rest.strip_prefix("at ") {
        // A frame belongs to the open stack block and is never read as a loaded class.
        parser.frame(frame.trim());
        return;
    }
    // Monitor annotations ("- locked <0x...> (a java.lang.Object)") interleave the frames
    // of a real stack (observed on JDK 25: synchronized loader frames carry one). They are
    // part of the block, never its end — reading one as a terminator cut every stack at
    // its first synchronized loader frame and silently lost the trigger.
    if parser.current.is_some() && rest.starts_with("- ") {
        return;
    }
    parser.close_block();
    let Some(token) = rest.split_whitespace().next() else {
        return;
    };
    if let Some(class) = normalize(token.trim_end_matches([':', ',', ';']), trusted) {
        parser.loaded.entry(class).or_insert(LoadRecord::Loaded);
    }
}

/// A token is kept as a class name when every '.'/'/'-separated segment is a plausible
/// Java identifier: not digit-first, ASCII letters/digits/'_'/'$' plus any non-ASCII byte
/// (JVM names are barely restricted, and Kotlin/Scala/obfuscators emit non-ASCII names).
/// That rejects the numeric tokens sharing the shape in mixed logs (IPs, versions) while
/// keeping inner classes. `extract.rs::slashed_class_name` applies the same shape test to
/// string constants, slashed-only and length-gated for the scan's needs.
///
/// A bare token needs at least two segments, since a default-package class is
/// indistinguishable from a stray word; a `trusted` token — one the JVM itself labeled
/// via a `[class,load]` tags decorator or a stack-block header — is a class name by
/// construction, so a single segment (default package) is accepted. Dots normalize to
/// the slashed internal form violations use.
fn normalize(token: &str, trusted: bool) -> Option<String> {
    let mut segments = 0usize;
    for segment in token.split(['.', '/']) {
        segments += 1;
        let mut bytes = segment.bytes();
        let first = bytes.next()?;
        if !(first.is_ascii_alphabetic() || first == b'_' || first == b'$' || first >= 0x80) {
            return None;
        }
        if !bytes.all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'$' || b >= 0x80) {
            return None;
        }
    }
    let min_segments = if trusted { 1 } else { 2 };
    (segments >= min_segments).then(|| token.replace('.', "/"))
}

/// The one application site, promote-only: a violation whose referencing class was
/// observed loading gains `observed_loading` and, when a cause stack was captured, its
/// trigger frame. The command layer applies this to the final violation set — after
/// per-module merging, before the exit decision — so `run_check` and the verdicts stream
/// stay untouched.
pub fn apply(violations: &mut [Violation], evidence: &LoadEvidence) {
    for v in violations.iter_mut() {
        if let Some(record) = evidence.observed(v.source_class.as_str()) {
            v.observed_loading = true;
            // Promote-only holds across evidence sets too: a later stack-less observation
            // must not clear a trigger an earlier log established.
            if let Some(trigger) = record.trigger() {
                v.load_trigger = Some(trigger.to_string());
            }
        }
    }
}

/// Class-loading machinery: the JDK's loader packages, plus any frame whose method is a
/// loader hook. The method-name rule is what catches custom loaders — a build tool's or
/// app server's loader sits in the delegation chain under its own package (a real JDK 25
/// run put `com.sun.tools.javac.launcher.MemoryClassLoader.loadClass` there), and its
/// delegation frames say nothing about what pulled the class in.
fn is_machinery(frame: &str) -> bool {
    const PACKAGES: [&str; 4] = [
        "java.lang.ClassLoader.",
        "java.security.SecureClassLoader.",
        "java.net.URLClassLoader.",
        "jdk.internal.loader.",
    ];
    if PACKAGES.iter().any(|p| frame.starts_with(p)) {
        return true;
    }
    let method = frame.split('(').next().unwrap_or(frame);
    let method = method.rsplit('.').next().unwrap_or(method);
    matches!(method, "loadClass" | "findClass" | "defineClass")
}

/// The reflective APIs whose presence explains a missing static edge. Reflection frames
/// are deliberately kept as triggers — they are the answer to why the static walk missed
/// the edge — and when one tops the stack its first non-reflective caller is named too.
fn is_reflective(frame: &str) -> bool {
    const REFLECTIVE: [&str; 6] = [
        "java.lang.Class.forName",
        "java.util.ServiceLoader",
        "java.lang.reflect.",
        "jdk.internal.reflect.",
        "sun.reflect.",
        "java.lang.invoke.",
    ];
    REFLECTIVE.iter().any(|m| frame.starts_with(m))
}

/// Escape for a TOML basic (double-quoted) string.
fn toml_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// The phrase both files uika writes to a draft path open with. Anything else in a file
/// about to be truncated was written by someone else, and is not ours to destroy.
const DRAFT_MARKER: &str = "uika --draft-exclude-file";

/// Truncate the draft file before the check runs, mirroring `--verdicts-json`'s
/// create-upfront contract: a run that errors mid-scan leaves this marker, never a stale
/// draft from an earlier run for a human to review.
pub fn create_draft_placeholder(path: &Path) -> Result<()> {
    // Content, not path identity, decides whether this file is ours to truncate. The
    // caller already refuses a draft path that resolves to an --exclude-file, but
    // canonicalize sees through spelling and symlinks only, so a HARD LINK to the exclude
    // file is a different path to the same inode and slips past it. Reading the first
    // line catches that without a per-platform inode comparison, and also catches the
    // wider mistake of aiming the draft at hand-written rules never passed as
    // --exclude-file at all.
    if let Ok(existing) = std::fs::read_to_string(path) {
        let first = existing.lines().next().unwrap_or("");
        if !existing.trim().is_empty() && !first.contains(DRAFT_MARKER) {
            bail!(
                "refusing to overwrite {}: it was not written by --draft-exclude-file. \
                 Draft to a new path and merge.",
                path.display()
            );
        }
    }
    // Parents before the write: this runs BEFORE the scan, so a path into a directory
    // that does not exist yet would fail the whole check rather than just the write.
    // Every frontend makes the dump's parents for the same reason, which makes a matching
    // draft path like target/uika/draft.toml the natural next thing to write.
    if let Some(parent) = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        std::fs::create_dir_all(parent).with_context(|| {
            format!(
                "cannot create directory {} for the draft exclude file",
                parent.display()
            )
        })?;
    }
    std::fs::write(
        path,
        "# uika --draft-exclude-file: the check did not complete; no rules were drafted.\n",
    )
    .with_context(|| format!("cannot write draft exclude file {}", path.display()))
}

/// Write draft `--exclude-file` rules for the symbols whose EVERY violation stayed ⚠️
/// with no observed load. Grouped by the referenced symbol because that is what an
/// exclude rule matches: a symbol that also breaks a reachable (or observed) class must
/// not be drafted, since the rule would waive that real break too. A member-less rule is
/// wider still — `exclude::filter` reads it as "the owner outright", member violations
/// included — so a class-level symbol is only drafted when every symbol on its owner is
/// draftable. Every reason opens with REVIEW and states exactly what the evidence does
/// and does not show. Returns the number of drafted rules; the file is always written, so
/// a requested draft is never silently absent.
pub fn draft_excludes(
    violations: &[Violation],
    app_roots_matched: Option<bool>,
    evidence: &LoadEvidence,
    path: &Path,
) -> Result<usize> {
    let axis = reachable_axis_valid(app_roots_matched);
    type SymbolKey = (&'static str, Option<(&'static str, &'static str)>);
    // BTreeMap on interned strs: drafted rules are ordered by symbol string value.
    let mut by_symbol: BTreeMap<SymbolKey, (bool, BTreeSet<String>)> = BTreeMap::new();
    for v in violations {
        let key = (
            v.reference.owner.as_str(),
            v.reference
                .member
                .map(|m| (m.name.as_str(), m.descriptor.as_str())),
        );
        let entry = by_symbol.entry(key).or_insert((true, BTreeSet::new()));
        entry.0 &= tier(v, axis) == Tier::Unproven;
        entry.1.insert(dotted(v.source_class.as_str()));
    }
    let undraftable_owners: BTreeSet<&str> = by_symbol
        .iter()
        .filter(|(_, (draftable, _))| !draftable)
        .map(|((owner, _), _)| *owner)
        .collect();

    let logs = evidence.sources();
    let mut out = String::new();
    let mut drafted = 0usize;
    writeln!(
        out,
        "# Draft exclude rules generated by uika --draft-exclude-file.\n\
         # Basis: no static path from the application reaches the referencing classes, and\n\
         # none was observed loading in: {logs}\n\
         # Absence of a load entry proves nothing beyond the observed runs. Review each\n\
         # entry and delete any you cannot justify before committing this file."
    )
    .unwrap();
    for ((owner, member), (draftable, users)) in &by_symbol {
        if !*draftable || (member.is_none() && undraftable_owners.contains(owner)) {
            continue;
        }
        drafted += 1;
        writeln!(out).unwrap();
        writeln!(out, "[[exclude]]").unwrap();
        writeln!(out, "owner = \"{}\"", toml_escape(owner)).unwrap();
        if let Some((name, descriptor)) = member {
            writeln!(out, "member = \"{}\"", toml_escape(name)).unwrap();
            writeln!(out, "descriptor = \"{}\"", toml_escape(descriptor)).unwrap();
        }
        let shown: Vec<&str> = users.iter().map(String::as_str).take(3).collect();
        let more = users.len().saturating_sub(shown.len());
        let list = if more > 0 {
            format!("{} and {more} more", shown.join(", "))
        } else {
            shown.join(", ")
        };
        writeln!(
            out,
            "reason = \"{}\"",
            toml_escape(&format!(
                "REVIEW: referenced only by {list}; no static path from the application reaches them and none was observed loading in {logs}"
            ))
        )
        .unwrap();
    }
    if drafted == 0 {
        writeln!(
            out,
            "\n# Nothing to draft: every violation is reachable, observed loading, or latent."
        )
        .unwrap();
    }
    std::fs::write(path, &out)
        .with_context(|| format!("cannot write draft exclude file {}", path.display()))?;
    Ok(drafted)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::intern::intern;
    use crate::model::{MemberKey, Reason, RefKind, SymbolRef};

    fn parse(text: &str) -> LoadEvidence {
        let mut loaded = FxHashMap::default();
        let mut parser = Parser {
            loaded: &mut loaded,
            current: None,
        };
        for line in text.lines() {
            parse_line(line.trim_end(), &mut parser);
        }
        parser.close_block();
        LoadEvidence {
            loaded,
            sources: "test.log".to_string(),
        }
    }

    fn violation(source_class: &str, owner: &str, member: Option<(&str, &str)>) -> Violation {
        Violation {
            source: intern("consumer.jar"),
            source_class: intern(source_class),
            reference: SymbolRef {
                kind: RefKind::Class,
                owner: intern(owner),
                member: member.map(|(n, d)| MemberKey::new(n, d)),
                expected_static: None,
                field_write: None,
                instantiated: None,
            },
            reason: Reason::ClassRemoved,
            reachable: Some(false),
            invocation_found: None,
            observed_loading: false,
            load_trigger: None,
            suggestion: None,
            modules: Vec::new(),
        }
    }

    /// Every accepted format lands in the same set; numeric tokens and other -Xlog
    /// streams sharing the file do not. A tag-confirmed line accepts a default-package
    /// or non-ASCII name (both are legal JVM class names); a bare token still needs two
    /// segments.
    #[test]
    fn parses_unified_logging_classlists_and_plain_lists() {
        let e = parse(
            "[0.062s][info][class,load] java.lang.Object source: shared objects file\n\
             [0.100s][info][gc,start     ] Pause Young (Normal)\n\
             [0.101s][info][class,load  ] com.example.App$Inner source: file:/app.jar\n\
             [0.102s][info][class,load] LoadIt source: file:/\n\
             [0.103s][info][class,load] com.example.日本語テスト source: file:/app.jar\n\
             io/ktor/Thing id: 12\n\
             com.example.Plain\n\
             127.0.0.1 connected\n\
             1.2.3\n\
             warning\n",
        );
        for present in [
            "java/lang/Object",
            "com/example/App$Inner",
            "LoadIt",
            "com/example/日本語テスト",
            "io/ktor/Thing",
            "com/example/Plain",
        ] {
            assert!(e.observed(present).is_some(), "{present} missing");
        }
        assert_eq!(e.distinct_classes(), 6, "numeric or bare tokens leaked in");
    }

    /// The tags decorator is matched by exact membership: the class,loader,* family
    /// shares the "class,load" substring but belongs to other streams, and a decorator
    /// set without tags at all (-Xlog:...:file=x:time) still parses via the token path.
    #[test]
    fn tag_matching_is_exact_and_survives_missing_tags_decorator() {
        let e = parse(
            "[0.1s][info][class,loader,data] create class loader data 0x0 for instance of jdk.internal.loader.ClassLoaders$AppClassLoader\n\
             [0.2s][info][class,loader,constraints] adding new constraint for name: java/lang/String\n\
             [2026-08-21T12:00:00.000+0900] java.lang.Object source: shared objects file\n",
        );
        assert!(
            e.observed("jdk/internal/loader/ClassLoaders$AppClassLoader")
                .is_none(),
            "a class,loader,data line leaked through the tag gate"
        );
        assert!(
            e.observed("java/lang/Object").is_some(),
            "a tags-less decorator set must fall through to the token parse"
        );
        assert_eq!(e.distinct_classes(), 1);
    }

    /// A cause stack is reduced to its trigger, frame lines are never read as loaded
    /// classes, and the first stack wins.
    #[test]
    fn captures_cause_stacks_and_picks_the_trigger_frame() {
        let e = parse(
            "[info][class,load,cause] Java stack when loading org.example.Plugin:\n\
             [info][class,load,cause] \tat java.lang.ClassLoader.loadClass(ClassLoader.java:600)\n\
             [info][class,load,cause] \tat java.lang.Class.forName(Class.java:100)\n\
             [info][class,load,cause] \tat com.example.Registry.discover(Registry.java:42)\n\
             [info][class,load,cause] Java stack when loading org.example.Plugin:\n\
             [info][class,load,cause] \tat com.example.Other.later(Other.java:1)\n",
        );
        // ClassLoader machinery is skipped; the reflective frame is the answer, and the
        // second stack must not override the first.
        assert_eq!(
            e.observed("org/example/Plugin").unwrap().trigger(),
            Some("java.lang.Class.forName from com.example.Registry.discover(Registry.java:42)")
        );
        assert!(e.observed("java/lang/ClassLoader").is_none());
    }

    /// The stack shape a real JDK 25 emits, verbatim (module-qualified frames, monitor
    /// annotations on synchronized loader frames, a custom loader in the delegation
    /// chain): the "- locked" lines must not end the block, the custom loader's loadClass
    /// counts as machinery, and the trigger composes the reflective API with its first
    /// real caller.
    #[test]
    fn real_jdk_stack_shape_parses_through_monitors_and_custom_loaders() {
        let e = parse(
            "[0.295s][info][class,load,cause] Java stack when loading io.ktor.utils.io.jvm.javaio.BlockingAdapter:\n\
             [0.295s][info][class,load,cause] \tat java.lang.ClassLoader.defineClass1(java.base@25.0.3/Native Method)\n\
             [0.295s][info][class,load,cause] \tat java.lang.ClassLoader.defineClass(java.base@25.0.3/ClassLoader.java:962)\n\
             [0.295s][info][class,load,cause] \tat java.security.SecureClassLoader.defineClass(java.base@25.0.3/SecureClassLoader.java:144)\n\
             [0.295s][info][class,load,cause] \tat jdk.internal.loader.BuiltinClassLoader.defineClass(java.base@25.0.3/BuiltinClassLoader.java:776)\n\
             [0.295s][info][class,load,cause] \tat jdk.internal.loader.BuiltinClassLoader.findClassOnClassPathOrNull(java.base@25.0.3/BuiltinClassLoader.java:691)\n\
             [0.295s][info][class,load,cause] \tat jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(java.base@25.0.3/BuiltinClassLoader.java:620)\n\
             [0.295s][info][class,load,cause] \t- locked <0x0000000c4f1f3d40> (a java.lang.Object)\n\
             [0.295s][info][class,load,cause] \tat jdk.internal.loader.BuiltinClassLoader.loadClass(java.base@25.0.3/BuiltinClassLoader.java:578)\n\
             [0.295s][info][class,load,cause] \tat java.lang.ClassLoader.loadClass(java.base@25.0.3/ClassLoader.java:490)\n\
             [0.295s][info][class,load,cause] \tat com.sun.tools.javac.launcher.MemoryClassLoader.loadClass(jdk.compiler@25.0.3/MemoryClassLoader.java:129)\n\
             [0.295s][info][class,load,cause] \t- locked <0x0000000c4f1c8ff8> (a com.sun.tools.javac.launcher.MemoryClassLoader)\n\
             [0.295s][info][class,load,cause] \tat java.lang.ClassLoader.loadClass(java.base@25.0.3/ClassLoader.java:490)\n\
             [0.295s][info][class,load,cause] \tat java.lang.Class.forName0(java.base@25.0.3/Native Method)\n\
             [0.295s][info][class,load,cause] \tat java.lang.Class.forName(java.base@25.0.3/Class.java:547)\n\
             [0.295s][info][class,load,cause] \tat LoadIt.main(LoadIt.java:3)\n\
             [0.295s][info][class,load,cause] \tat java.lang.invoke.LambdaForm$DMH/0x000001c00106c000.invokeStatic(java.base@25.0.3/LambdaForm$DMH)\n",
        );
        // A monitor annotation ending the stack early would leave a machinery-only
        // prefix and a None trigger, so the composed value pins both rules at once.
        assert_eq!(
            e.observed("io/ktor/utils/io/jvm/javaio/BlockingAdapter")
                .unwrap()
                .trigger(),
            Some("java.lang.Class.forName0 from LoadIt.main(LoadIt.java:3)")
        );
    }

    /// The native variant marks the class loaded without collecting VM frames. The frame
    /// lines carry the same class,load,cause,native tags as their header, and their
    /// frame-kind letters ("V", "j") must not register as trusted single-segment classes.
    #[test]
    fn native_stack_marks_loaded_without_frames() {
        let e = parse(
            "[info][class,load,cause,native] Native stack when loading com.example.N:\n\
             [info][class,load,cause,native] V  [libjvm.so+0x123abc]\n\
             [info][class,load,cause,native] j  java.lang.Thread.run()V+8\n\
             [info][class,load,cause,native] j  com.example.Caller.run()V+2\n",
        );
        let record = e.observed("com/example/N").unwrap();
        assert!(record.trigger().is_none());
        assert_eq!(
            e.distinct_classes(),
            1,
            "native frame lines leaked in as classes"
        );
    }

    /// apply is promote-only across evidence sets too: a stack-less observation applied
    /// after a stacked one must not clear the established trigger.
    #[test]
    fn apply_never_clears_an_established_trigger() {
        let stacked = parse(
            "Java stack when loading io.ktor.A:\n\
             \tat com.example.Boot.init(Boot.java:5)\n",
        );
        let plain = parse("io.ktor.A\n");
        let mut violations = vec![violation("io/ktor/A", "x/Gone", None)];
        apply(&mut violations, &stacked);
        apply(&mut violations, &plain);
        assert!(violations[0].observed_loading);
        assert_eq!(
            violations[0].load_trigger.as_deref(),
            Some("com.example.Boot.init(Boot.java:5)"),
            "a later stack-less observation cleared the trigger"
        );
    }

    /// Nothing is retained per frame, so a delegation chain of any depth still yields
    /// its trigger (a fixed retention cap used to lose it past 32 machinery frames).
    #[test]
    fn trigger_survives_arbitrarily_deep_delegation_chains() {
        let mut text = String::from("Java stack when loading a.b.C:\n");
        for i in 0..100 {
            text.push_str(&format!(
                "\tat jdk.internal.loader.Deep.m{i}(Deep.java:{i})\n"
            ));
        }
        text.push_str("\tat com.example.Boot.init(Boot.java:5)\n");
        let e = parse(&text);
        assert_eq!(
            e.observed("a/b/C").unwrap().trigger(),
            Some("com.example.Boot.init(Boot.java:5)")
        );
    }

    /// apply is promote-only: observed classes gain the flag and trigger, everything else
    /// is untouched.
    #[test]
    fn apply_marks_only_observed_classes() {
        let e = parse(
            "Java stack when loading io.ktor.A:\n\
             \tat com.example.Boot.init(Boot.java:5)\n",
        );
        let mut violations = vec![
            violation("io/ktor/A", "x/Gone", None),
            violation("io/ktor/B", "x/Gone", None),
        ];
        apply(&mut violations, &e);
        assert!(violations[0].observed_loading);
        assert_eq!(
            violations[0].load_trigger.as_deref(),
            Some("com.example.Boot.init(Boot.java:5)")
        );
        assert!(!violations[1].observed_loading);
        assert_eq!(violations[1].load_trigger, None);
    }

    /// Drafts cover only symbols whose every violation is ⚠️ and unobserved; the file
    /// round-trips through the real exclude parser. A member-less rule matches the owner
    /// outright in exclude::filter, so it is withheld whenever any symbol on the same
    /// owner is not draftable — otherwise the drafted class-level rule would waive a
    /// reachable member break too.
    #[test]
    fn drafts_only_fully_unproven_unobserved_symbols() {
        let unobserved_a = violation("app/DeadA", "lib/Gone", Some(("m", "()V")));
        let unobserved_b = violation("app/DeadB", "lib/Gone", Some(("m", "()V")));
        let mut observed = violation("app/Live", "lib/AlsoGone", None);
        observed.observed_loading = true;
        let mut reachable = violation("app/Hot", "lib/Hot", None);
        reachable.reachable = Some(true);
        // The same symbol broken by both a dead and a live class must not be drafted.
        let mixed_dead = violation("app/DeadC", "lib/Mixed", None);
        let mut mixed_live = violation("app/Hot2", "lib/Mixed", None);
        mixed_live.reachable = Some(true);
        // A draftable class-level symbol whose owner also carries a reachable member
        // break: the member-less rule would waive that break, so it must not be drafted.
        let split_class = violation("app/DeadD", "lib/Split", None);
        let mut split_member = violation("app/Hot3", "lib/Split", Some(("n", "()V")));
        split_member.reachable = Some(true);

        let evidence = parse("com.example.Whatever\n");
        let dir = std::env::temp_dir().join(format!("uika-draft-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("draft.toml");
        let drafted = draft_excludes(
            &[
                unobserved_a,
                unobserved_b,
                observed,
                reachable,
                mixed_dead,
                mixed_live,
                split_class,
                split_member,
            ],
            Some(true),
            &evidence,
            &path,
        )
        .unwrap();
        assert_eq!(drafted, 1);
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(content.contains("owner = \"lib/Gone\""), "{content}");
        assert!(content.contains("member = \"m\""), "{content}");
        assert!(content.contains("descriptor = \"()V\""), "{content}");
        assert!(
            content.contains("REVIEW: referenced only by app.DeadA, app.DeadB;"),
            "{content}"
        );
        assert!(!content.contains("lib/AlsoGone"), "{content}");
        assert!(!content.contains("lib/Hot"), "{content}");
        assert!(!content.contains("lib/Mixed"), "{content}");
        assert!(!content.contains("lib/Split"), "{content}");
        // The draft must load as a real exclude file.
        let rules = crate::exclude::load(std::slice::from_ref(&path)).unwrap();
        assert_eq!(rules.len(), 1);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// An invalid reachable axis (roots matched nothing, app_roots_matched = Some(false))
    /// drafts nothing: every reachable = Some(false) then carries no evidence, and the
    /// per-module fold in lib.rs passes exactly this value when any run's roots matched
    /// nothing — the gate fails those violations, so drafting them would propose waiving
    /// the very breaks the command exits 1 on.
    #[test]
    fn nothing_is_drafted_when_the_reachable_axis_is_invalid() {
        let v = violation("app/Dead", "lib/Gone", None);
        let evidence = parse("");
        let dir = std::env::temp_dir().join(format!("uika-draft-axis-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("draft.toml");
        let drafted =
            draft_excludes(std::slice::from_ref(&v), Some(false), &evidence, &path).unwrap();
        assert_eq!(drafted, 0);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A directory of logs (per-process files from parallel test JVMs, or an unpacked CI
    /// artifact) reads recursively as one source.
    #[test]
    fn a_directory_of_logs_reads_every_file() {
        let dir = std::env::temp_dir().join(format!("uika-load-dir-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("nested")).unwrap();
        std::fs::write(dir.join("a.log"), "com.example.A\n").unwrap();
        std::fs::write(dir.join("nested").join("b.log"), "com.example.B\n").unwrap();
        let e = load(std::slice::from_ref(&dir)).unwrap();
        assert!(e.observed("com/example/A").is_some());
        assert!(e.observed("com/example/B").is_some());
        assert_eq!(
            e.sources(),
            dir.display().to_string(),
            "the directory is one source"
        );
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A newline-less binary dropped into the log directory is skipped in bounded memory
    /// and does not derail the lines after it in other respects.
    #[test]
    fn oversized_lines_are_skipped_in_bounded_memory() {
        let dir = std::env::temp_dir().join(format!("uika-load-binary-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut junk = vec![b'x'; 3 * MAX_LINE];
        junk.extend_from_slice(b"\ncom.example.After\n");
        std::fs::write(dir.join("junk.bin"), &junk).unwrap();
        let e = load(std::slice::from_ref(&dir)).unwrap();
        assert!(e.observed("com/example/After").is_some());
        assert_eq!(e.distinct_classes(), 1);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// `.jfr` files in a directory are the binary recordings the plugins convert and
    /// leave in place; the walk skips them by NAME (never byte-scanning megabytes for
    /// newlines, never fluke-registering pool strings), while an explicitly passed
    /// `.jfr` path is still read line by line.
    #[test]
    fn jfr_named_files_are_skipped_in_the_directory_walk_only() {
        let dir = std::env::temp_dir().join(format!("uika-load-jfr-skip-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let jfr = dir.join("rec.jfr");
        // Readable text on purpose: if the walk opened the file at all, this would
        // register and the assertion below would catch it.
        std::fs::write(&jfr, "com.example.FromRecording\n").unwrap();
        std::fs::write(dir.join("plain.log"), "com.example.FromLog\n").unwrap();
        let e = load(std::slice::from_ref(&dir)).unwrap();
        assert!(e.observed("com/example/FromLog").is_some());
        assert!(
            e.observed("com/example/FromRecording").is_none(),
            "a .jfr inside the directory must be skipped by name"
        );
        let direct = load(std::slice::from_ref(&jfr)).unwrap();
        assert!(
            direct.observed("com/example/FromRecording").is_some(),
            "an explicitly passed .jfr path must still be read"
        );
        std::fs::remove_dir_all(&dir).ok();
    }

    /// With nothing to draft the file still exists and says why, so a requested draft is
    /// never silently absent.
    #[test]
    fn empty_draft_still_writes_the_file() {
        let mut v = violation("app/Hot", "lib/Hot", None);
        v.reachable = Some(true);
        let evidence = parse("");
        let dir = std::env::temp_dir().join(format!("uika-draft-empty-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("draft.toml");
        let drafted = draft_excludes(&[v], Some(true), &evidence, &path).unwrap();
        assert_eq!(drafted, 0);
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(content.contains("Nothing to draft"), "{content}");
        assert!(
            crate::exclude::load(std::slice::from_ref(&path))
                .unwrap()
                .is_empty()
        );
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The placeholder written before the check runs is a valid, empty exclude file, so
    /// an errored run leaves neither a stale draft nor an unparseable one.
    ///
    /// The stale draft carries the header, because a real one always does: every draft
    /// uika writes opens with it, and that is what tells this file apart from rules a
    /// human wrote. A headerless file is refused instead, which
    /// `a_file_uika_did_not_write_is_never_truncated` covers.
    #[test]
    fn draft_placeholder_is_a_valid_empty_exclude_file() {
        let dir = std::env::temp_dir().join(format!("uika-draft-holder-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("draft.toml");
        std::fs::write(
            &path,
            "# Draft exclude rules generated by uika --draft-exclude-file.\n\
             [[exclude]]\nowner = \"stale/Rule\"\n",
        )
        .unwrap();
        create_draft_placeholder(&path).unwrap();
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(!content.contains("stale/Rule"), "{content}");
        assert!(
            crate::exclude::load(std::slice::from_ref(&path))
                .unwrap()
                .is_empty()
        );
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A hard link to an --exclude-file is a different path to the same inode, so the
    /// caller's canonicalize-based check cannot see it. Content decides instead, which
    /// also covers a draft aimed at hand-written rules that were never passed as
    /// --exclude-file. Overwriting an earlier draft of our own stays allowed.
    #[test]
    fn a_file_uika_did_not_write_is_never_truncated() {
        let dir = std::env::temp_dir().join(format!("uika-hardlink-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let rules = dir.join("keep.toml");
        let hand_written = "[[exclude]]\nowner = \"org/example/Keep\"\nreason = \"mine\"\n";
        std::fs::write(&rules, hand_written).unwrap();
        let linked = dir.join("draft.toml");
        std::fs::hard_link(&rules, &linked).unwrap();

        let refused = super::create_draft_placeholder(&linked);
        let message = format!("{:#}", refused.unwrap_err());
        assert!(
            message.contains("not written by --draft-exclude-file"),
            "{message}"
        );
        assert_eq!(std::fs::read_to_string(&rules).unwrap(), hand_written);

        // Our own placeholder, and a real draft, are both ours to replace.
        let ours = dir.join("ours.toml");
        super::create_draft_placeholder(&ours).unwrap();
        super::create_draft_placeholder(&ours).unwrap();
        // So is an empty file the user created to hold the output.
        let empty = dir.join("empty.toml");
        std::fs::write(&empty, "").unwrap();
        super::create_draft_placeholder(&empty).unwrap();

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The placeholder runs BEFORE the scan, so a path into a directory that does not
    /// exist yet used to fail the whole check rather than just the write. Every frontend
    /// creates the dump's parents, which makes a matching draft path the natural next
    /// thing to write.
    #[test]
    fn the_draft_placeholder_creates_its_parent_directories() {
        let dir = std::env::temp_dir().join(format!("uika-draftdir-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let nested = dir.join("target").join("uika").join("draft.toml");

        super::create_draft_placeholder(&nested).unwrap();

        let content = std::fs::read_to_string(&nested).unwrap();
        assert!(content.contains("did not complete"), "{content}");

        let _ = std::fs::remove_dir_all(&dir);
    }
}
