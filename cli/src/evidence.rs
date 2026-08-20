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
use anyhow::{Context, Result};
use rustc_hash::FxHashMap;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write as _;
use std::io::BufRead;
use std::path::{Path, PathBuf};

/// Cause stacks are only read for their topmost useful frame, and real stacks run
/// hundreds of frames deep, so retention is capped. 32, not less: the loader-delegation
/// machinery alone ran 10 frames deep on a real JDK 25 stack (defineClass through a custom
/// loader's loadClass), and the trigger is the first frame PAST the machinery.
const MAX_FRAMES: usize = 32;

pub struct LoadEvidence {
    /// Slashed internal name -> retained `class+load+cause` frames of the first observed
    /// stack (empty when only load lines were seen). Slashed so lookups match
    /// `Violation.source_class` without converting per violation.
    loaded: FxHashMap<String, Vec<String>>,
    /// The log files the evidence came from, for notes and drafted reasons.
    sources: Vec<String>,
}

impl LoadEvidence {
    pub fn distinct_classes(&self) -> usize {
        self.loaded.len()
    }

    pub fn sources(&self) -> &[String] {
        &self.sources
    }

    fn observed(&self, slashed: &str) -> Option<&[String]> {
        self.loaded.get(slashed).map(Vec::as_slice)
    }
}

/// Parse one or more class-load logs. Accepted per line, leniently:
/// - JDK unified logging with any decorators: `[0.1s][info][class,load] a.b.C source: ...`.
///   A line whose decorator groups name other tags (gc, jit) is skipped, so a log file
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
/// A directory reads every regular file under it (recursively, in sorted path order for
/// determinism) as one source: parallel test JVMs write per-process files (`%p` in the
/// `-Xlog` file name, because each JVM truncates a shared file on open), and a downloaded
/// CI artifact unpacks to a directory, so the directory is the natural unit to pass.
pub fn load(paths: &[PathBuf]) -> Result<LoadEvidence> {
    let mut evidence = LoadEvidence {
        loaded: FxHashMap::default(),
        sources: Vec::new(),
    };
    for path in paths {
        if path.is_dir() {
            let mut files = Vec::new();
            collect_files(path, &mut files)?;
            files.sort();
            for file in &files {
                parse_file(file, &mut evidence.loaded)?;
            }
        } else {
            parse_file(path, &mut evidence.loaded)?;
        }
        evidence.sources.push(path.display().to_string());
    }
    Ok(evidence)
}

fn collect_files(dir: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
    let entries = std::fs::read_dir(dir)
        .with_context(|| format!("cannot read class-load log directory {}", dir.display()))?;
    for entry in entries {
        let path = entry
            .with_context(|| format!("cannot read class-load log directory {}", dir.display()))?
            .path();
        if path.is_dir() {
            collect_files(&path, files)?;
        } else {
            files.push(path);
        }
    }
    Ok(())
}

fn parse_file(path: &Path, loaded: &mut FxHashMap<String, Vec<String>>) -> Result<()> {
    let file = std::fs::File::open(path)
        .with_context(|| format!("cannot read class-load log {}", path.display()))?;
    let mut reader = std::io::BufReader::new(file);
    let mut buf = Vec::new();
    // The class whose Java cause stack is being collected, across lines.
    let mut current: Option<String> = None;
    loop {
        buf.clear();
        if reader
            .read_until(b'\n', &mut buf)
            .with_context(|| format!("cannot read class-load log {}", path.display()))?
            == 0
        {
            break;
        }
        let line = String::from_utf8_lossy(&buf);
        parse_line(line.trim_end(), &mut current, loaded);
    }
    Ok(())
}

fn parse_line(
    line: &str,
    current: &mut Option<String>,
    loaded: &mut FxHashMap<String, Vec<String>>,
) {
    // Strip unified-logging decorator groups. The tags are one of them, so a line
    // decorated with something other than class+load belongs to another stream sharing
    // the file. An undecorated line may still be a plain class list.
    let mut rest = line.trim_start();
    let mut saw_group = false;
    let mut saw_class_load = false;
    while let Some(tail) = rest.strip_prefix('[') {
        let Some(end) = tail.find(']') else { break };
        saw_group = true;
        saw_class_load |= tail[..end].contains("class,load");
        rest = tail[end + 1..].trim_start();
    }
    if saw_group && !saw_class_load {
        return;
    }
    let rest = rest.trim();
    if let Some(class) = rest.strip_prefix("Java stack when loading ") {
        *current = match normalize(class.trim_end_matches(':')) {
            Some(class) => {
                let frames = loaded.entry(class.clone()).or_default();
                // First stack wins: a class several loaders define logs several stacks,
                // and the first is the one that pulled the class in.
                frames.is_empty().then_some(class)
            }
            None => None,
        };
        return;
    }
    if let Some(class) = rest.strip_prefix("Native stack when loading ") {
        if let Some(class) = normalize(class.trim_end_matches(':')) {
            loaded.entry(class).or_default();
        }
        *current = None;
        return;
    }
    if let Some(frame) = rest.strip_prefix("at ") {
        // A frame belongs to the open stack block and is never read as a loaded class.
        if let Some(class) = current {
            let frames = loaded.entry(class.clone()).or_default();
            if frames.len() < MAX_FRAMES {
                frames.push(frame.trim().to_string());
            }
        }
        return;
    }
    // Monitor annotations ("- locked <0x...> (a java.lang.Object)") interleave the frames
    // of a real stack (observed on JDK 25: synchronized loader frames carry one). They are
    // part of the block, never its end — reading one as a terminator cut every stack at
    // its first synchronized loader frame and silently lost the trigger.
    if current.is_some() && rest.starts_with("- ") {
        return;
    }
    *current = None;
    let Some(token) = rest.split_whitespace().next() else {
        return;
    };
    if let Some(class) = normalize(token.trim_end_matches([':', ',', ';'])) {
        loaded.entry(class).or_default();
    }
}

/// A token is kept as a class name when every '.'/'/'-separated segment is a plausible
/// Java identifier (letters, digits, '_', '$', not digit-first). That rejects the numeric
/// tokens sharing the shape in mixed logs (IPs, versions) while keeping inner classes. At
/// least two segments are required, since a default-package class is indistinguishable
/// from a bare word. Dots normalize to the slashed internal form violations use.
fn normalize(token: &str) -> Option<String> {
    let mut segments = 0usize;
    for segment in token.split(['.', '/']) {
        segments += 1;
        let mut bytes = segment.bytes();
        let first = bytes.next()?;
        if !(first.is_ascii_alphabetic() || first == b'_' || first == b'$') {
            return None;
        }
        if !bytes.all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'$') {
            return None;
        }
    }
    (segments >= 2).then(|| token.replace('.', "/"))
}

/// The one application site, promote-only: a violation whose referencing class was
/// observed loading gains `observed_loading` and, when a cause stack was captured, its
/// trigger frame. The command layer applies this to the final violation set — after
/// per-module merging, before the exit decision — so `run_check` and the verdicts stream
/// stay untouched.
pub fn apply(violations: &mut [Violation], evidence: &LoadEvidence) {
    for v in violations.iter_mut() {
        if let Some(frames) = evidence.observed(v.source_class.as_str()) {
            v.observed_loading = true;
            v.load_trigger = trigger(frames);
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

/// The frame that pulled the class in: the topmost frame outside the class-loading
/// machinery. Reflection frames (Class.forName, ServiceLoader, Method.invoke) are
/// deliberately kept — they are the answer to why the static walk missed the edge.
fn trigger(frames: &[String]) -> Option<String> {
    // The reflective APIs whose presence explains a missing static edge. When one is the
    // topmost useful frame, the first frame below the reflective plumbing is named too:
    // "Class.forName" alone says how, the caller says who.
    const REFLECTIVE: [&str; 6] = [
        "java.lang.Class.forName",
        "java.util.ServiceLoader",
        "java.lang.reflect.",
        "jdk.internal.reflect.",
        "sun.reflect.",
        "java.lang.invoke.",
    ];
    let reflective = |f: &str| REFLECTIVE.iter().any(|m| f.starts_with(m));
    let first = frames.iter().find(|f| !is_machinery(f))?;
    if reflective(first)
        && let Some(caller) = frames.iter().find(|f| !is_machinery(f) && !reflective(f))
    {
        // "java.lang.Class.forName(Class.java:100)" -> "java.lang.Class.forName": the
        // reflective API's own source location says nothing; the caller keeps its own.
        let api = first.split('(').next().unwrap_or(first);
        return Some(format!("{api} from {caller}"));
    }
    Some(first.clone())
}

/// Escape for a TOML basic (double-quoted) string.
fn toml_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// "com/foo/Bar" -> "com.foo.Bar" for prose inside drafted reasons.
fn dotted(name: &str) -> String {
    name.replace('/', ".")
}

/// Write draft `--exclude-file` rules for the symbols whose EVERY violation stayed ⚠️
/// with no observed load. Grouped by the referenced symbol because that is what an
/// exclude rule matches: a symbol that also breaks a reachable (or observed) class must
/// not be drafted, since the rule would waive that real break too. Every reason opens
/// with REVIEW and states exactly what the evidence does and does not show. Returns the
/// number of drafted rules; the file is always written, so a requested draft is never
/// silently absent.
pub fn draft_excludes(
    violations: &[Violation],
    app_roots_matched: Option<bool>,
    evidence: &LoadEvidence,
    path: &Path,
) -> Result<usize> {
    let axis = reachable_axis_valid(app_roots_matched);
    type SymbolKey = (String, Option<(String, String)>);
    // BTreeMap on string keys: drafted rules are ordered by symbol string value.
    let mut by_symbol: BTreeMap<SymbolKey, (bool, BTreeSet<String>)> = BTreeMap::new();
    for v in violations {
        let key = (
            v.reference.owner.as_str().to_string(),
            v.reference.member.map(|m| {
                (
                    m.name.as_str().to_string(),
                    m.descriptor.as_str().to_string(),
                )
            }),
        );
        let entry = by_symbol.entry(key).or_insert((true, BTreeSet::new()));
        entry.0 &= tier(v, axis) == Tier::Unproven;
        entry.1.insert(dotted(v.source_class.as_str()));
    }

    let logs = evidence.sources().join(", ");
    let mut out = String::new();
    let mut drafted = 0usize;
    writeln!(
        out,
        "# Draft exclude rules generated by uika --draft-exclude-file."
    )
    .unwrap();
    writeln!(
        out,
        "# Basis: no static path from the application reaches the referencing classes, and"
    )
    .unwrap();
    writeln!(out, "# none was observed loading in: {logs}").unwrap();
    writeln!(
        out,
        "# Absence of a load entry proves nothing beyond the observed runs. Review each"
    )
    .unwrap();
    writeln!(
        out,
        "# entry and delete any you cannot justify before committing this file."
    )
    .unwrap();
    for ((owner, member), (draftable, users)) in &by_symbol {
        if !draftable {
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
        let mut shown: Vec<&str> = users.iter().map(String::as_str).take(3).collect();
        let more = users.len().saturating_sub(shown.len());
        let list = if more > 0 {
            shown.push("");
            format!("{} and {more} more", shown[..shown.len() - 1].join(", "))
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
        let mut evidence = LoadEvidence {
            loaded: FxHashMap::default(),
            sources: vec!["test.log".to_string()],
        };
        let mut current = None;
        for line in text.lines() {
            parse_line(line.trim_end(), &mut current, &mut evidence.loaded);
        }
        evidence
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
    /// streams sharing the file do not.
    #[test]
    fn parses_unified_logging_classlists_and_plain_lists() {
        let e = parse(
            "[0.062s][info][class,load] java.lang.Object source: shared objects file\n\
             [0.100s][info][gc,start     ] Pause Young (Normal)\n\
             [0.101s][info][class,load  ] com.example.App$Inner source: file:/app.jar\n\
             io/ktor/Thing id: 12\n\
             com.example.Plain\n\
             127.0.0.1 connected\n\
             1.2.3\n\
             warning\n",
        );
        for present in [
            "java/lang/Object",
            "com/example/App$Inner",
            "io/ktor/Thing",
            "com/example/Plain",
        ] {
            assert!(e.observed(present).is_some(), "{present} missing");
        }
        assert_eq!(e.distinct_classes(), 4, "numeric or bare tokens leaked in");
    }

    /// A cause stack is captured for its class, frame lines are never read as loaded
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
        let frames = e.observed("org/example/Plugin").unwrap();
        assert_eq!(frames.len(), 3, "second stack must not append: {frames:?}");
        // ClassLoader machinery is skipped; the reflective frame is the answer.
        assert_eq!(
            trigger(frames).as_deref(),
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
        let frames = e
            .observed("io/ktor/utils/io/jvm/javaio/BlockingAdapter")
            .unwrap();
        assert!(
            frames.iter().any(|f| f.starts_with("LoadIt.main")),
            "a monitor annotation ended the stack early: {frames:?}"
        );
        assert_eq!(
            trigger(frames).as_deref(),
            Some("java.lang.Class.forName0 from LoadIt.main(LoadIt.java:3)"),
            "frames: {frames:?}"
        );
    }

    /// The native variant marks the class loaded without collecting VM frames.
    #[test]
    fn native_stack_marks_loaded_without_frames() {
        let e = parse(
            "[info][class,load,cause,native] Native stack when loading com.example.N:\n\
             V  [libjvm.so+0x123abc]\n\
             j  java.lang.Thread.run()V\n",
        );
        assert_eq!(e.observed("com/example/N"), Some(&[][..]));
        assert_eq!(e.distinct_classes(), 1);
    }

    /// Frame retention is capped; the trigger only ever needs the top of the stack.
    #[test]
    fn frame_capture_is_capped() {
        let mut text = String::from("Java stack when loading a.b.C:\n");
        for i in 0..100 {
            text.push_str(&format!("\tat p.Q.m{i}(Q.java:{i})\n"));
        }
        let e = parse(&text);
        assert_eq!(e.observed("a/b/C").unwrap().len(), MAX_FRAMES);
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
    /// round-trips through the real exclude parser.
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
        // The draft must load as a real exclude file.
        let rules = crate::exclude::load(std::slice::from_ref(&path)).unwrap();
        assert_eq!(rules.len(), 1);
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
        assert_eq!(e.sources().len(), 1, "the directory is one source");
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
}
