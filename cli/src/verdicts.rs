//! Streaming JSON Lines output of every reference verdict (`check --verdicts-json`).
//!
//! Evaluation surface for tools/jvm-probe: each line carries the raw reference as
//! extracted from the constant pool (never collapsed the way report violations
//! are) plus the verdict it received. One line is emitted per reference record —
//! the constant-pool pass plus one per scanned instruction, without call-site
//! dedup — so a member invoked from N sites yields N identical lines while the
//! report dedups them into one violation; consumers must not equate line counts
//! with violation counts. Lines are streamed as verdicts are computed, so memory
//! stays flat regardless of scan size. Exclude rules do not apply here; they
//! filter the report, not the verdict stream.
//!
//! A write failure latches: the scan continues (the report is still produced)
//! but the command fails afterwards, because a silently truncated stream would
//! let an answer-check pass on a prefix of the verdicts.

use crate::intern::Sym;
use crate::model::SymbolRef;
use anyhow::{Context, Result};
use serde::Serialize;
use std::io::{BufWriter, Write};
use std::path::Path;

#[derive(Serialize)]
struct VerdictRecord<'a> {
    source: Sym,
    source_class: Sym,
    reference: &'a SymbolRef,
    verdict: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<&'static str>,
}

/// Writes one JSON line per reference verdict. `out` is `Err` once the stream
/// has failed, holding the first failure message; the two states are mutually
/// exclusive by construction, and `record` is a no-op after a failure.
pub struct VerdictWriter {
    out: std::result::Result<Box<dyn Write>, String>,
}

fn truncated(e: impl std::fmt::Display) -> String {
    format!("verdicts output failed, stream truncated: {e}")
}

impl VerdictWriter {
    pub fn create(path: &Path) -> Result<Self> {
        let file = std::fs::File::create(path)
            .with_context(|| format!("cannot create verdicts output {}", path.display()))?;
        Ok(Self {
            out: Ok(Box::new(BufWriter::new(file))),
        })
    }

    pub fn record(
        &mut self,
        source: Sym,
        source_class: Sym,
        reference: &SymbolRef,
        verdict: &'static str,
        reason: Option<&'static str>,
    ) {
        let rec = VerdictRecord {
            source,
            source_class,
            reference,
            verdict,
            reason,
        };
        let result = match &mut self.out {
            Ok(out) => (|| -> std::io::Result<()> {
                serde_json::to_writer(&mut **out, &rec)?;
                out.write_all(b"\n")
            })(),
            Err(_) => return,
        };
        if let Err(e) = result {
            self.out = Err(truncated(e));
        }
    }

    /// Flush and return the failure message if the stream failed at any point.
    pub fn finish(self) -> Option<String> {
        match self.out {
            Ok(mut out) => out.flush().err().map(truncated),
            Err(msg) => Some(msg),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::intern::intern;
    use crate::model::{MemberKey, RefKind};

    #[test]
    fn streams_one_json_line_per_record() {
        let dir = std::env::temp_dir().join(format!("uika-verdicts-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("verdicts.jsonl");

        let mut w = VerdictWriter::create(&path).unwrap();
        let broken = SymbolRef {
            kind: RefKind::Method,
            owner: intern("com/example/Owner"),
            member: Some(MemberKey::new("gone", "()V")),
            expected_static: Some(true),
            field_write: None,
        };
        w.record(
            intern("app.jar"),
            intern("com/example/Caller"),
            &broken,
            "broken",
            Some("method removed"),
        );
        let ok = SymbolRef {
            kind: RefKind::Class,
            owner: intern("com/example/Owner"),
            member: None,
            expected_static: None,
            field_write: None,
        };
        w.record(
            intern("app.jar"),
            intern("com/example/Caller"),
            &ok,
            "ok",
            None,
        );
        assert!(w.finish().is_none());

        let text = std::fs::read_to_string(&path).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 2);
        let first: serde_json::Value = serde_json::from_str(lines[0]).unwrap();
        assert_eq!(first["verdict"], "broken");
        assert_eq!(first["reason"], "method removed");
        assert_eq!(first["reference"]["owner"], "com/example/Owner");
        assert_eq!(first["reference"]["member"]["name"], "gone");
        assert_eq!(first["reference"]["expected_static"], true);
        let second: serde_json::Value = serde_json::from_str(lines[1]).unwrap();
        assert_eq!(second["verdict"], "ok");
        assert_eq!(second["reference"]["kind"], "class");
        assert!(second.get("reason").is_none());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn write_failure_latches_and_finish_reports_it() {
        struct FailingWriter;
        impl Write for FailingWriter {
            fn write(&mut self, _: &[u8]) -> std::io::Result<usize> {
                Err(std::io::Error::other("disk full"))
            }
            fn flush(&mut self) -> std::io::Result<()> {
                Ok(())
            }
        }

        let mut w = VerdictWriter {
            out: Ok(Box::new(FailingWriter)),
        };
        let r = SymbolRef {
            kind: RefKind::Class,
            owner: intern("com/example/Owner"),
            member: None,
            expected_static: None,
            field_write: None,
        };
        w.record(intern("a.jar"), intern("com/example/C"), &r, "ok", None);
        // Latched: further records are no-ops, and finish surfaces the first error.
        w.record(intern("a.jar"), intern("com/example/C"), &r, "ok", None);
        let msg = w.finish().expect("failure must be reported");
        assert!(msg.contains("stream truncated"), "{msg}");
        assert!(msg.contains("disk full"), "{msg}");
    }
}
