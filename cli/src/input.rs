use crate::intern::{Sym, intern};
use crate::window::WindowedReader;
use anyhow::{Context, Result};
use rayon::prelude::*;
use std::fs::File;
use std::io::Read;
use std::path::Path;
use walkdir::WalkDir;
use zip::ZipArchive;

/// One class loaded from a JAR entry or a file inside a directory.
pub struct LoadedClass {
    pub entry_name: String,
    pub bytes: Vec<u8>,
    /// Origin (JAR path or directory path). Sym avoids duplicating a String per class.
    pub source: Sym,
}

const CLASS_MAGIC: [u8; 4] = [0xCA, 0xFE, 0xBA, 0xBE];

/// Version-specific classes in multi-release JARs and module-info are out of scope.
fn is_scannable(name: &str) -> bool {
    name.ends_with(".class")
        && !name.ends_with("module-info.class")
        && !name.starts_with("META-INF/versions/")
}

/// Enumerate all classes from a JAR file or class directory.
/// Use for_each_batch for huge inputs so inflated bytes are not held all at once.
pub fn load(path: &Path) -> Result<Vec<LoadedClass>> {
    let mut classes = Vec::new();
    for_each_batch(path, usize::MAX, |batch| {
        classes.extend(batch);
        Ok(())
    })?;
    Ok(classes)
}

/// Load at most batch_size classes at a time and pass them to the callback.
/// Streaming API that caps concurrently held inflated bytes to one batch.
/// Large buffers miss malloc's small-object cache and bounce through mmap/munmap (sys time),
/// so never accumulate the whole JAR in a Vec.
pub fn for_each_batch(
    path: &Path,
    batch_size: usize,
    f: impl FnMut(Vec<LoadedClass>) -> Result<()>,
) -> Result<()> {
    if path.is_dir() {
        batch_dir(path, batch_size, f)
    } else {
        batch_jar(path, batch_size, f)
    }
}

/// Sliding window size for JAR reads. Two windows times this size is the cap on
/// resident read memory per JAR.
fn jar_window() -> usize {
    std::env::var("UIKA_WINDOW")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(1024 * 1024)
}

fn batch_jar(
    path: &Path,
    batch_size: usize,
    f: impl FnMut(Vec<LoadedClass>) -> Result<()>,
) -> Result<()> {
    let file = File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
    let source = intern(&path.display().to_string());
    // Fast path: parse the central directory directly, coalesce contiguous spans,
    // and inflate entries in parallel. zip64 and compression methods other than
    // deflate fall back to the zip crate path.
    match fast_entries(&file) {
        Some(entries) => batch_jar_fast(&file, source, &entries, batch_size, f),
        None => batch_jar_zip(file, path, source, batch_size, f),
    }
}

/// Metadata for one entry taken from the central directory.
struct CdEntry {
    name: String,
    local_header_offset: u64,
    /// Up to the next entry's local header (or CD for the last entry): the upper bound of this entry's span.
    end: u64,
    compressed: u64,
    uncompressed: u64,
    method: u16,
}

const SPAN_MAX: u64 = 8 * 1024 * 1024;
const GAP_MAX: u64 = 1024 * 1024;

/// If the fast path can be used, return scannable entries in offset order.
fn fast_entries(file: &File) -> Option<Vec<CdEntry>> {
    let (mut entries, cd_offset) = parse_central_directory(file)?;
    entries.sort_by_key(|e| e.local_header_offset);
    for i in 0..entries.len() {
        entries[i].end = entries
            .get(i + 1)
            .map(|next| next.local_header_offset)
            .unwrap_or(cd_offset);
    }
    entries.retain(|e| is_scannable(&e.name));
    // Fall back for the whole JAR if anything other than stored / deflate is mixed in.
    if entries.iter().any(|e| e.method != 0 && e.method != 8) {
        return None;
    }
    Some(entries)
}

/// Coalesce contiguous entries into spans read by one pread, then inflate in parallel.
/// Resident read memory per JAR is one span (max 8MB) plus the inflated batch.
fn batch_jar_fast(
    file: &File,
    source: Sym,
    entries: &[CdEntry],
    batch_size: usize,
    mut f: impl FnMut(Vec<LoadedClass>) -> Result<()>,
) -> Result<()> {
    let mut span = Vec::new();
    let mut i = 0;
    while i < entries.len() {
        let start = entries[i].local_header_offset;
        let mut j = i + 1;
        while j < entries.len()
            && j - i < batch_size
            && entries[j].end - start <= SPAN_MAX
            && entries[j].local_header_offset - entries[j - 1].end <= GAP_MAX
        {
            j += 1;
        }
        let end = entries[i..j].iter().map(|e| e.end).max().unwrap_or(start);
        span.resize((end - start) as usize, 0);
        if read_exact_at(file, &mut span, start).is_none() {
            anyhow::bail!("failed to read jar span at offset {start}");
        }
        let batch: Vec<LoadedClass> = entries[i..j]
            .par_iter()
            .filter_map(|e| match decode_entry(&span, start, e, source) {
                Ok(loaded) => loaded,
                Err(err) => {
                    eprintln!("warning: {source}!{}: {err}", e.name);
                    None
                }
            })
            .collect();
        f(batch)?;
        i = j;
    }
    Ok(())
}

/// Follow the local header and inflate one entry inside a span.
fn decode_entry(
    span: &[u8],
    span_start: u64,
    e: &CdEntry,
    source: Sym,
) -> Result<Option<LoadedClass>> {
    let base = (e.local_header_offset - span_start) as usize;
    let header = span
        .get(base..base + 30)
        .context("local header out of span")?;
    if header[..4] != [0x50, 0x4b, 0x03, 0x04] {
        anyhow::bail!("bad local header signature");
    }
    // Re-read name/extra lengths because local headers can differ from the central directory.
    let name_len = u16le(header, 26).context("truncated header")? as usize;
    let extra_len = u16le(header, 28).context("truncated header")? as usize;
    let data_start = base + 30 + name_len + extra_len;
    let data = span
        .get(data_start..data_start + e.compressed as usize)
        .context("entry data out of span")?;
    let bytes = match e.method {
        0 => data.to_vec(),
        8 => {
            let mut out = Vec::with_capacity(e.uncompressed as usize);
            flate2::read::DeflateDecoder::new(data)
                .read_to_end(&mut out)
                .context("deflate error")?;
            out
        }
        m => anyhow::bail!("unsupported compression method {m}"),
    };
    if bytes.len() < 4 || bytes[..4] != CLASS_MAGIC {
        return Ok(None);
    }
    Ok(Some(LoadedClass {
        entry_name: e.name.clone(),
        bytes,
        source,
    }))
}

/// Fallback path: read in CD order with the zip crate (for zip64 and other uncommon cases).
fn batch_jar_zip(
    file: File,
    path: &Path,
    source: Sym,
    batch_size: usize,
    mut f: impl FnMut(Vec<LoadedClass>) -> Result<()>,
) -> Result<()> {
    let reader = WindowedReader::new(file, jar_window());
    let mut archive =
        ZipArchive::new(reader).with_context(|| format!("not a zip/jar: {}", path.display()))?;
    let mut batch = Vec::new();
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i)?;
        if !is_scannable(entry.name()) {
            continue;
        }
        let entry_name = entry.name().to_string();
        let mut bytes = Vec::with_capacity(entry.size() as usize);
        entry.read_to_end(&mut bytes)?;
        if bytes.len() < 4 || bytes[..4] != CLASS_MAGIC {
            continue;
        }
        batch.push(LoadedClass {
            entry_name,
            bytes,
            source,
        });
        if batch.len() >= batch_size {
            f(std::mem::take(&mut batch))?;
        }
    }
    if !batch.is_empty() {
        f(batch)?;
    }
    Ok(())
}

/// Read the central directory directly and return entry metadata.
/// Return None for zip64 or unexpected structures so the caller uses the zip crate path.
fn parse_central_directory(file: &File) -> Option<(Vec<CdEntry>, u64)> {
    use crate::window::ReadAt;
    let len = ReadAt::len(file);
    // Search for EOCD (PK\x05\x06) from the end. Read enough for max comment length + record length.
    let tail_len = len.min(66_000);
    let mut tail = vec![0u8; tail_len as usize];
    read_exact_at(file, &mut tail, len - tail_len)?;
    let pos = tail
        .windows(4)
        .rposition(|w| w == [0x50, 0x4b, 0x05, 0x06])?;
    let eocd = &tail[pos..];
    if eocd.len() < 22 {
        return None;
    }
    let total_entries = u16le(eocd, 10)? as usize;
    let cd_size = u32le(eocd, 12)? as u64;
    let cd_offset = u32le(eocd, 16)? as u64;
    // 0xFFFF / 0xFFFFFFFF are zip64 markers -> fallback.
    if total_entries == 0xFFFF
        || cd_size == u64::from(u32::MAX)
        || cd_offset == u64::from(u32::MAX)
        || cd_offset + cd_size > len
    {
        return None;
    }
    let mut cd = vec![0u8; cd_size as usize];
    read_exact_at(file, &mut cd, cd_offset)?;
    let mut entries = Vec::with_capacity(total_entries);
    let mut p = 0usize;
    for _ in 0..total_entries {
        if cd.len() < p + 46 || cd[p..p + 4] != [0x50, 0x4b, 0x01, 0x02] {
            return None;
        }
        let rec = &cd[p..];
        let flags = u16le(rec, 8)?;
        let method = u16le(rec, 10)?;
        let compressed = u32le(rec, 20)?;
        let uncompressed = u32le(rec, 24)?;
        let name_len = u16le(rec, 28)? as usize;
        let extra_len = u16le(rec, 30)? as usize;
        let comment_len = u16le(rec, 32)? as usize;
        let local_header_offset = u32le(rec, 42)?;
        // Fall back for zip64 markers and encrypted entries.
        if local_header_offset == u32::MAX
            || compressed == u32::MAX
            || uncompressed == u32::MAX
            || flags & 0x1 != 0
        {
            return None;
        }
        let name = std::str::from_utf8(cd.get(p + 46..p + 46 + name_len)?)
            .ok()?
            .to_string();
        entries.push(CdEntry {
            name,
            local_header_offset: u64::from(local_header_offset),
            end: 0, // Filled after sorting in fast_entries.
            compressed: u64::from(compressed),
            uncompressed: u64::from(uncompressed),
            method,
        });
        p += 46 + name_len + extra_len + comment_len;
    }
    Some((entries, cd_offset))
}

fn read_exact_at(file: &File, buf: &mut [u8], offset: u64) -> Option<()> {
    use crate::window::ReadAt;
    let mut filled = 0;
    while filled < buf.len() {
        match file.read_at(&mut buf[filled..], offset + filled as u64) {
            Ok(0) => return None,
            Ok(n) => filled += n,
            Err(_) => return None,
        }
    }
    Some(())
}

fn u16le(bytes: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_le_bytes([
        *bytes.get(offset)?,
        *bytes.get(offset + 1)?,
    ]))
}

fn u32le(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes([
        *bytes.get(offset)?,
        *bytes.get(offset + 1)?,
        *bytes.get(offset + 2)?,
        *bytes.get(offset + 3)?,
    ]))
}

/// For pass 2: read only the specified entries.
/// JARs use direct by_name seeks and do not scan all entries.
/// Individual read failures are returned as warnings; only successfully read entries are passed to the callback.
pub fn fetch_entries(
    path: &Path,
    entries: &[(Sym, String)],
    mut f: impl FnMut(Sym, &[u8]),
) -> Result<Vec<String>> {
    let mut warnings = Vec::new();
    if path.is_dir() {
        for (name, entry) in entries {
            match std::fs::read(path.join(entry)) {
                Ok(bytes) => f(*name, &bytes),
                Err(e) => warnings.push(format!("{}!{entry}: {e}", path.display())),
            }
        }
        return Ok(warnings);
    }
    let file = File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
    // Direct seeks to a small number of entries only need a smaller window.
    let reader = WindowedReader::new(file, 256 * 1024);
    let mut archive =
        ZipArchive::new(reader).with_context(|| format!("not a zip/jar: {}", path.display()))?;
    let mut bytes = Vec::new();
    for (name, entry) in entries {
        match archive.by_name(entry) {
            Ok(mut ze) => {
                bytes.clear();
                match ze.read_to_end(&mut bytes) {
                    Ok(_) => f(*name, &bytes),
                    Err(e) => warnings.push(format!("{}!{entry}: {e}", path.display())),
                }
            }
            Err(e) => warnings.push(format!("{}!{entry}: {e}", path.display())),
        }
    }
    Ok(warnings)
}

fn batch_dir(
    path: &Path,
    batch_size: usize,
    mut f: impl FnMut(Vec<LoadedClass>) -> Result<()>,
) -> Result<()> {
    let source = intern(&path.display().to_string());
    let mut batch = Vec::new();
    for entry in WalkDir::new(path) {
        let entry = entry?;
        if !entry.file_type().is_file() {
            continue;
        }
        let entry_name = entry
            .path()
            .strip_prefix(path)
            .unwrap_or(entry.path())
            .to_string_lossy()
            .replace('\\', "/");
        if !is_scannable(&entry_name) {
            continue;
        }
        let bytes = std::fs::read(entry.path())?;
        if bytes.len() < 4 || bytes[..4] != CLASS_MAGIC {
            continue;
        }
        batch.push(LoadedClass {
            entry_name,
            bytes,
            source,
        });
        if batch.len() >= batch_size {
            f(std::mem::take(&mut batch))?;
        }
    }
    if !batch.is_empty() {
        f(batch)?;
    }
    Ok(())
}
