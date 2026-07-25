//! JDK API model from ct.sym (opt-in via `--jdk-release`).
//!
//! Hierarchy traversal that escapes the analyzed scope into JDK types normally
//! ends as Unknown (conservative OK). With a JDK API index layered at the bottom
//! of both resolution scopes, those traversals conclude instead. ct.sym ships
//! with every JDK and holds API stub class files for all historical releases
//! (`<release codes>/<module>/<binary name>.sig`); the stubs are regular class
//! files, so the existing parser reads them. The current JDK's own release is
//! not in ct.sym (javac reads it from the runtime image), so `--jdk-release`
//! must name an older release than the installed JDK.
//!
//! The same index is layered into the old and the new scope, so an API member
//! missing from ct.sym resolves NotFound on both sides and the old-relative
//! gate keeps it unreported. The layer can therefore turn Unknown into OK or
//! into a genuine old-side-resolvable break, but never invent one from ct.sym
//! incompleteness.

use crate::index::{ApiIndex, object_sym};
use crate::intern::{Sym, intern};
use anyhow::{Context, Result, bail};
use rustc_hash::{FxHashMap, FxHashSet};
use std::fs::File;
use std::path::{Path, PathBuf};
use zip::ZipArchive;

/// ct.sym encodes each release as one base-36 uppercase digit: 8 -> '8',
/// 9 -> '9', 10 -> 'A', ..., 21 -> 'L'. A directory named "BCDEF" holds the
/// classes whose API is identical across releases 11..=15.
fn release_code(release: u32) -> Option<char> {
    if !(8..=35).contains(&release) {
        return None;
    }
    char::from_digit(release, 36).map(|c| c.to_ascii_uppercase())
}

fn code_release(code: char) -> Option<u32> {
    code.to_digit(36).filter(|r| *r >= 8)
}

/// Locate ct.sym from the environment: UIKA_JDK (a JDK home or a direct path
/// to ct.sym) first, then JAVA_HOME.
pub fn find_ct_sym() -> Option<PathBuf> {
    let candidate = |home: &str| {
        let p = PathBuf::from(home);
        if p.is_file() {
            return Some(p);
        }
        let ct = p.join("lib").join("ct.sym");
        ct.is_file().then_some(ct)
    };
    if let Ok(p) = std::env::var("UIKA_JDK")
        && let Some(found) = candidate(&p)
    {
        return Some(found);
    }
    std::env::var("JAVA_HOME").ok().and_then(|p| candidate(&p))
}

/// Lazily fetching view of one release inside ct.sym.
pub struct JdkIndexer {
    ct_sym: PathBuf,
    /// class name (interned, e.g. "java/lang/String") -> ct.sym entry path.
    entries: FxHashMap<Sym, String>,
}

impl JdkIndexer {
    /// Read the ct.sym entry list once and keep the entries of `release`.
    pub fn open(ct_sym: &Path, release: u32) -> Result<Self> {
        let Some(code) = release_code(release) else {
            bail!("unsupported --jdk-release {release} (expected 8..=35)");
        };
        let file = File::open(ct_sym)
            .with_context(|| format!("cannot open ct.sym: {}", ct_sym.display()))?;
        let archive = ZipArchive::new(std::io::BufReader::new(file))
            .with_context(|| format!("not a zip: {}", ct_sym.display()))?;

        let mut entries = FxHashMap::default();
        let mut available: FxHashSet<u32> = FxHashSet::default();
        for entry in archive.file_names() {
            // "<codes>/<module>/<binary/name>.sig"
            let Some((codes, rest)) = entry.split_once('/') else {
                continue;
            };
            let Some(class_path) = rest
                .split_once('/')
                .and_then(|(_module, path)| path.strip_suffix(".sig"))
            else {
                continue;
            };
            available.extend(codes.chars().filter_map(code_release));
            if codes.contains(code) {
                entries.insert(intern(class_path), entry.to_string());
            }
        }
        if entries.is_empty() {
            let mut releases: Vec<u32> = available.into_iter().collect();
            releases.sort_unstable();
            bail!(
                "release {release} not present in {} (available: {}; \
                 the installed JDK's own release is served from its runtime \
                 image, not ct.sym, so pick an older one)",
                ct_sym.display(),
                releases
                    .iter()
                    .map(u32::to_string)
                    .collect::<Vec<_>>()
                    .join(", "),
            );
        }
        Ok(Self {
            ct_sym: ct_sym.to_path_buf(),
            entries,
        })
    }

    /// Build an ApiIndex holding the transitive super/interface closure of
    /// `roots`, reading only the needed stubs. Roots outside ct.sym are skipped
    /// (they stay Unknown for resolution, unchanged from today). Read and parse
    /// failures become warnings; the class is then simply absent, which the
    /// old-relative gate keeps conservative.
    pub fn fetch_closure(&self, roots: impl IntoIterator<Item = Sym>) -> (ApiIndex, Vec<String>) {
        let mut index = ApiIndex::new();
        let mut warnings = Vec::new();
        let mut requested: FxHashSet<Sym> = FxHashSet::default();
        let mut pending: Vec<Sym> = roots
            .into_iter()
            .filter(|name| self.entries.contains_key(name) && requested.insert(*name))
            .collect();
        while !pending.is_empty() {
            let batch: Vec<(Sym, String)> = pending
                .drain(..)
                .map(|name| (name, self.entries[&name].clone()))
                .collect();
            let mut apis = Vec::new();
            let fetched = crate::input::fetch_entries(&self.ct_sym, &batch, |name, bytes| {
                match crate::classfile::RawClass::parse(bytes)
                    .and_then(|rc| crate::extract::extract_api(&rc))
                {
                    Ok(api) => apis.push(api),
                    Err(e) => warnings.push(format!("ct.sym!{name}: {e}")),
                }
            });
            match fetched {
                Ok(w) => warnings.extend(w),
                Err(e) => {
                    warnings.push(format!("ct.sym: {e}"));
                    break;
                }
            }
            for api in apis {
                let supers = api
                    .super_name
                    .into_iter()
                    .chain(api.interfaces.iter().copied());
                for next in supers {
                    if next != object_sym()
                        && self.entries.contains_key(&next)
                        && requested.insert(next)
                    {
                        pending.push(next);
                    }
                }
                index.insert_if_absent(api);
            }
        }
        index.shrink_to_fit();
        (index, warnings)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn release_codes_follow_ct_sym_base36_convention() {
        assert_eq!(release_code(8), Some('8'));
        assert_eq!(release_code(9), Some('9'));
        assert_eq!(release_code(10), Some('A'));
        assert_eq!(release_code(17), Some('H'));
        assert_eq!(release_code(21), Some('L'));
        assert_eq!(release_code(7), None);
        assert_eq!(release_code(36), None);
        assert_eq!(code_release('H'), Some(17));
        assert_eq!(code_release('7'), None);
    }
}
