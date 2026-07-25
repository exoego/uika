//! Streaming JSON Lines output of every reference verdict (`check --verdicts-json`).
//!
//! Evaluation surface for tools/jvm-probe: each line carries the raw reference as
//! extracted from the constant pool (never collapsed the way report violations
//! are) plus the verdict it received. Lines are streamed as verdicts are
//! computed, so memory stays flat regardless of scan size. Exclude rules do not
//! apply here; they filter the report, not the verdict stream.

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

/// Writes one JSON line per reference verdict. A write failure is remembered and
/// surfaced once by `finish` instead of aborting the check mid-scan.
pub struct VerdictWriter {
    out: Option<Box<dyn Write>>,
    error: Option<String>,
}

impl VerdictWriter {
    pub fn create(path: &Path) -> Result<Self> {
        let file = std::fs::File::create(path)
            .with_context(|| format!("cannot create verdicts output {}", path.display()))?;
        Ok(Self {
            out: Some(Box::new(BufWriter::new(file))),
            error: None,
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
        let Some(out) = self.out.as_mut() else { return };
        let rec = VerdictRecord {
            source,
            source_class,
            reference,
            verdict,
            reason,
        };
        let result = (|| -> std::io::Result<()> {
            serde_json::to_writer(&mut *out, &rec)?;
            out.write_all(b"\n")
        })();
        if let Err(e) = result {
            self.error = Some(format!("verdicts output failed, stream truncated: {e}"));
            self.out = None;
        }
    }

    /// Flush and return the deferred write error, if any.
    pub fn finish(mut self) -> Option<String> {
        if let Some(out) = self.out.as_mut()
            && let Err(e) = out.flush()
        {
            return Some(format!("verdicts output failed, stream truncated: {e}"));
        }
        self.error.take()
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
}
