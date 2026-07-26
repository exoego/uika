//! JDK API model from ct.sym (opt-in via `--jdk-release`).
//!
//! Hierarchy traversal that escapes the analyzed scope into JDK types normally
//! ends as Unknown (conservative OK). With a JDK API index layered at the bottom
//! of both resolution scopes, those traversals conclude instead. ct.sym ships
//! with every JDK and holds API stub class files for historical releases; the
//! stubs are regular class files, so the existing parser reads them. The current
//! JDK's own release is not in ct.sym (javac reads it from the runtime image),
//! so `--jdk-release` must name an older release than the installed JDK.
//!
//! The same index is layered into the old and the new scope, so an API member
//! missing from ct.sym resolves NotFound on both sides and the old-relative
//! gate keeps it unreported. For reference verdicts the layer can therefore
//! turn Unknown into OK or into a genuine old-side-resolvable break, but never
//! invent one from ct.sym incompleteness. One check consumes the layer
//! single-sided: extends-final (version lag) reads class ACC_FINAL from the
//! declared release's stub with no old-side JDK to cancel against, so its
//! verdicts hold for `--jdk-release N`, not necessarily the deployed runtime
//! (e.g. java.awt.PointerInfo became final in 19).
//!
//! Layer order is (new, fetched, jdk), first-wins: the model treats ct.sym as
//! the LAST classpath entry, deliberately inverting real boot delegation.
//! jdk-first would resolve a checked javax pair (e.g. a jaxb-api upgrade under
//! `--jdk-release 8`) from the JDK's bundled copy and mask the pair's own
//! changes, and shadowing the scanned classpath would hide the standalone spec
//! jars that ARE the runtime provider on modern JDKs. Known cost: a stale
//! JDK-namespace copy bundled in the OLD library resolves on the old side while
//! the new side falls through to the stub, reporting a removal a boot-delegating
//! JVM never linked. Consistent with the flat-classpath doctrine; do not reorder.
//!
//! ct.sym entry layouts handled here:
//! - JDK 12+: `<codes>/<module>/<binary/name>.sig` (module dirs contain a '.').
//! - JDK 9-11: `<codes>/<binary/name>.sig` (no module level), plus
//!   `<code>-modules/...` dirs that javac itself skips by the '-' in the name.
//! - JDK 8: `META-INF/sym/rt.jar/**.class` — unsupported, rejected with a
//!   distinct error.
//!
//! Codes are base-36 digits, one per release ('8', '9', 'A' = 10, ...). Real
//! files keep '6'/'7' in joint dirs for classes unchanged since those releases,
//! so the codes charset must accept ALL base-36 digits even though only 8..=35
//! are selectable.

use crate::index::{ApiIndex, object_sym};
use crate::intern::Sym;
use crate::model::ClassApi;
use crate::window::WindowedReader;
use anyhow::{Context, Result, bail};
use rayon::prelude::*;
use rustc_hash::{FxHashMap, FxHashSet};
use std::fs::File;
use std::io::Read;
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

/// Valid codes-directory names consist only of base-36 digits (uppercase).
/// This keeps joint dirs containing '6'/'7' (classes unchanged since those
/// releases) while rejecting "9-modules", "META-INF", and the like.
fn is_codes_dir(codes: &str) -> bool {
    !codes.is_empty()
        && codes
            .chars()
            .all(|c| c.is_ascii_digit() || c.is_ascii_uppercase())
}

/// Split one ct.sym entry into (codes, class name), handling both the 12+
/// layout (with a module level, whose names always contain a '.') and the
/// 9-11 layout (without one). Returns None for anything that is not a class
/// stub (directories, module-info, system-modules, JDK 8 layout).
fn parse_entry(entry: &str) -> Option<(&str, &str)> {
    let (codes, rest) = entry.split_once('/')?;
    if !is_codes_dir(codes) {
        return None;
    }
    let class_path = match rest.split_once('/') {
        Some((first, tail)) if first.contains('.') => tail,
        _ => rest,
    };
    let name = class_path.strip_suffix(".sig")?;
    // Module descriptors are not classes for resolution purposes.
    if name == "module-info" {
        return None;
    }
    Some((codes, name))
}

/// The ct.sym file inside a JDK home, if present.
pub fn ct_sym_in(home: impl AsRef<Path>) -> Option<PathBuf> {
    let p = home.as_ref();
    if p.is_file() {
        return Some(p.to_path_buf());
    }
    let ct = p.join("lib").join("ct.sym");
    ct.is_file().then_some(ct)
}

/// Locate ct.sym from the environment. UIKA_JDK (a JDK home or a direct path
/// to ct.sym) is authoritative when set: a bad explicit pin surfaces as an
/// error instead of silently analyzing against a different JDK's data.
/// JAVA_HOME is the fallback.
pub fn find_ct_sym() -> Option<PathBuf> {
    if let Ok(p) = std::env::var("UIKA_JDK")
        && !p.is_empty()
    {
        return ct_sym_in(&p);
    }
    std::env::var("JAVA_HOME").ok().and_then(ct_sym_in)
}

/// Locate and open the indexer for `--jdk-release`. Lives here so the lookup
/// order and the error wording stay next to the code that implements them.
pub fn indexer_for(release: Option<u32>) -> Result<Option<JdkIndexer>> {
    let Some(release) = release else {
        return Ok(None);
    };
    let Some(ct_sym) = find_ct_sym() else {
        let hint = match std::env::var("UIKA_JDK") {
            Ok(p) if !p.is_empty() => {
                format!("UIKA_JDK is set to {p} but it is not a ct.sym file and has no lib/ct.sym")
            }
            _ => "set UIKA_JDK to a JDK home or a ct.sym file (checked first), \
                  or JAVA_HOME to a JDK home"
                .to_string(),
        };
        bail!("--jdk-release {release} needs a JDK: {hint}");
    };
    Ok(Some(JdkIndexer::open(&ct_sym, release)?))
}

/// Lazily fetching view of one release inside ct.sym. The archive stays open:
/// the central directory is parsed once, and closure levels read entries by
/// name from the retained handle.
pub struct JdkIndexer {
    archive: ZipArchive<WindowedReader<File>>,
    /// class name (e.g. "java/lang/String") -> ct.sym entry path. Owned
    /// strings, freed with the indexer; names are deliberately NOT interned
    /// (only the fetched closure's names recur, and extract_api interns those).
    entries: FxHashMap<Box<str>, Box<str>>,
}

impl JdkIndexer {
    /// Read the ct.sym entry list once and keep the entries of `release`.
    pub fn open(ct_sym: &Path, release: u32) -> Result<Self> {
        let Some(code) = release_code(release) else {
            bail!("unsupported --jdk-release {release} (expected 8..=35)");
        };
        let file = File::open(ct_sym)
            .with_context(|| format!("cannot open ct.sym: {}", ct_sym.display()))?;
        let archive = ZipArchive::new(WindowedReader::new(file, 256 * 1024))
            .with_context(|| format!("not a zip: {}", ct_sym.display()))?;

        let mut entries: FxHashMap<Box<str>, Box<str>> = FxHashMap::default();
        for entry in archive.file_names() {
            if let Some((codes, name)) = parse_entry(entry)
                && codes.contains(code)
            {
                entries.insert(name.into(), entry.into());
            }
        }
        if entries.is_empty() {
            // Only now is the available set needed; the happy path never pays for it.
            let mut releases: Vec<u32> = archive
                .file_names()
                .filter_map(parse_entry)
                .flat_map(|(codes, _)| codes.chars().filter_map(code_release))
                .collect::<FxHashSet<u32>>()
                .into_iter()
                .collect();
            releases.sort_unstable();
            if releases.is_empty() {
                bail!(
                    "no release-coded API stubs found in {} \
                     (pre-JDK-9 ct.sym layout, or not a ct.sym)",
                    ct_sym.display(),
                );
            }
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
        Ok(Self { archive, entries })
    }

    /// Build an ApiIndex holding the transitive super/interface closure of
    /// `roots`, reading only the needed stubs. Roots outside ct.sym are skipped
    /// (they stay Unknown for resolution, unchanged from today). Read and parse
    /// failures become warnings; the class is then simply absent, which the
    /// old-relative gate keeps conservative.
    pub fn fetch_closure(
        &mut self,
        roots: impl IntoIterator<Item = Sym>,
    ) -> (ApiIndex, Vec<String>) {
        let mut index = ApiIndex::new();
        let mut warnings = Vec::new();
        let mut requested: FxHashSet<Sym> = FxHashSet::default();
        let mut pending: Vec<Sym> = roots
            .into_iter()
            .filter(|name| self.entries.contains_key(name.as_str()) && requested.insert(*name))
            .collect();
        while !pending.is_empty() {
            // Fetch in string order: Sym ids vary run to run, and the warning
            // order below is user-visible.
            pending.sort_unstable_by_key(|s| s.as_str());
            let mut level: Vec<(Sym, Vec<u8>)> = Vec::with_capacity(pending.len());
            for name in pending.drain(..) {
                let entry = self.entries[name.as_str()].as_ref();
                match self.archive.by_name(entry) {
                    Ok(mut ze) => {
                        let mut bytes = Vec::with_capacity(ze.size() as usize);
                        match ze.read_to_end(&mut bytes) {
                            Ok(_) => level.push((name, bytes)),
                            Err(e) => warnings.push(format!("ct.sym!{name}: {e}")),
                        }
                    }
                    Err(e) => warnings.push(format!("ct.sym!{name}: {e}")),
                }
            }
            // Stubs are small; parsing them in parallel matches the repo's
            // parallel-inflate/parse pattern without holding more than a level.
            let parsed: Vec<Result<ClassApi, String>> = level
                .par_iter()
                .map(|(name, bytes)| {
                    crate::classfile::RawClass::parse(bytes)
                        .and_then(|rc| crate::extract::extract_api(&rc))
                        .map_err(|e| format!("ct.sym!{name}: {e}"))
                })
                .collect();
            for result in parsed {
                let api = match result {
                    Ok(api) => api,
                    Err(w) => {
                        warnings.push(w);
                        continue;
                    }
                };
                let supers = api
                    .super_name
                    .into_iter()
                    .chain(api.interfaces.iter().copied());
                for next in supers {
                    if next != object_sym()
                        && self.entries.contains_key(next.as_str())
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

    #[test]
    fn parses_the_jdk12_layout_with_module_dirs() {
        assert_eq!(
            parse_entry("GH/java.base/java/lang/Object.sig"),
            Some(("GH", "java/lang/Object"))
        );
        assert_eq!(
            parse_entry("89ABCDEFGHIJK/jdk.net/jdk/net/Sockets.sig"),
            Some(("89ABCDEFGHIJK", "jdk/net/Sockets"))
        );
    }

    #[test]
    fn parses_the_jdk9_to_11_layout_without_module_dirs() {
        // Package segments never contain '.', so nothing is stripped.
        assert_eq!(
            parse_entry("9AB/java/lang/String.sig"),
            Some(("9AB", "java/lang/String"))
        );
        // Joint dirs keep codes for releases below 8 (API unchanged since then).
        assert_eq!(
            parse_entry("678/java/util/Map.sig"),
            Some(("678", "java/util/Map"))
        );
    }

    #[test]
    fn skips_non_class_entries() {
        // JDK 9-11 module descriptors live under "<code>-modules".
        assert_eq!(parse_entry("A-modules/java.base/module-info.sig"), None);
        // JDK 12+ module descriptors sit inside regular codes dirs.
        assert_eq!(parse_entry("GH/java.base/module-info.sig"), None);
        // JDK 8 layout and metadata files.
        assert_eq!(
            parse_entry("META-INF/sym/rt.jar/java/lang/Object.class"),
            None
        );
        assert_eq!(parse_entry("L/system-modules"), None);
        // Directory entries.
        assert_eq!(parse_entry("GH/java.base/java/lang/"), None);
    }
}
