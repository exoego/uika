//! Read + Seek adapter that reads through fixed-size sliding windows.
//!
//! Mapping or reading a whole JAR keeps roughly the whole file resident during processing.
//! Sliding windows (a few MB) with pread cap residency at the window size, and syscalls stay
//! near the number of refills.
//!
//! Two windows are kept: zip 2.x seeks back and forth for every entry while parsing the
//! central directory, moving from CD (end of file) to the local header (earlier) and back
//! to CD. With one window every entry refills and amplifies reads. With two LRU windows,
//! the CD side and data side each stay in their own window and both only slide forward.

use std::io::{self, Read, Seek, SeekFrom};

/// Positioned-read abstraction (tests use byte slices).
pub trait ReadAt {
    fn read_at(&self, buf: &mut [u8], offset: u64) -> io::Result<usize>;
    fn len(&self) -> u64;
}

impl ReadAt for std::fs::File {
    fn read_at(&self, buf: &mut [u8], offset: u64) -> io::Result<usize> {
        std::os::unix::fs::FileExt::read_at(self, buf, offset)
    }

    fn len(&self) -> u64 {
        self.metadata().map(|m| m.len()).unwrap_or(0)
    }
}

impl ReadAt for &[u8] {
    fn read_at(&self, buf: &mut [u8], offset: u64) -> io::Result<usize> {
        let slice: &[u8] = self;
        let offset = usize::try_from(offset).unwrap_or(usize::MAX);
        if offset >= slice.len() {
            return Ok(0);
        }
        let n = buf.len().min(slice.len() - offset);
        buf[..n].copy_from_slice(&slice[offset..offset + n]);
        Ok(n)
    }

    fn len(&self) -> u64 {
        let slice: &[u8] = self;
        slice.len() as u64
    }
}

#[derive(Default)]
struct Window {
    start: u64,
    buf: Vec<u8>,
}

impl Window {
    fn contains(&self, pos: u64) -> bool {
        pos >= self.start && pos < self.start + self.buf.len() as u64
    }
}

pub struct WindowedReader<S> {
    source: S,
    source_len: u64,
    window_size: usize,
    windows: [Window; 2],
    /// Most recently hit window. On a miss, replace the other one (LRU).
    last_used: usize,
    pos: u64,
}

impl<S: ReadAt> WindowedReader<S> {
    pub fn new(source: S, window_size: usize) -> Self {
        let source_len = source.len();
        Self {
            source,
            source_len,
            window_size,
            windows: [Window::default(), Window::default()],
            last_used: 0,
            pos: 0,
        }
    }

    /// Return the window containing pos, or refill the LRU side from pos.
    fn window_for(&mut self, pos: u64) -> io::Result<Option<usize>> {
        if self.windows[self.last_used].contains(pos) {
            return Ok(Some(self.last_used));
        }
        let other = 1 - self.last_used;
        if self.windows[other].contains(pos) {
            self.last_used = other;
            return Ok(Some(other));
        }
        // miss: refill the LRU side (= other).
        let want = self
            .window_size
            .min(self.source_len.saturating_sub(pos) as usize);
        let window = &mut self.windows[other];
        window.start = pos;
        window.buf.resize(want, 0);
        let mut filled = 0;
        while filled < want {
            let n = self
                .source
                .read_at(&mut window.buf[filled..], pos + filled as u64)?;
            if n == 0 {
                break;
            }
            filled += n;
        }
        window.buf.truncate(filled);
        self.last_used = other;
        Ok((filled > 0).then_some(other))
    }
}

impl<S: ReadAt> Read for WindowedReader<S> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() || self.pos >= self.source_len {
            return Ok(0);
        }
        let Some(w) = self.window_for(self.pos)? else {
            return Ok(0);
        };
        let window = &self.windows[w];
        let offset = (self.pos - window.start) as usize;
        let n = buf.len().min(window.buf.len() - offset);
        buf[..n].copy_from_slice(&window.buf[offset..offset + n]);
        self.pos += n as u64;
        Ok(n)
    }
}

impl<S: ReadAt> Seek for WindowedReader<S> {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let new_pos = match pos {
            SeekFrom::Start(p) => Some(p),
            SeekFrom::End(d) => self.source_len.checked_add_signed(d),
            SeekFrom::Current(d) => self.pos.checked_add_signed(d),
        };
        // Do not invalidate windows. Reads check containment, so seeks within a window avoid refills.
        match new_pos {
            Some(p) => {
                self.pos = p;
                Ok(p)
            }
            None => Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "seek before start",
            )),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn data() -> Vec<u8> {
        (0u32..10_000).flat_map(|i| i.to_le_bytes()).collect()
    }

    #[test]
    fn reads_across_window_boundaries() {
        let data = data();
        // A full 40KB sequential read must match even with a 1000B window.
        let mut r = WindowedReader::new(data.as_slice(), 1000);
        let mut out = Vec::new();
        r.read_to_end(&mut out).unwrap();
        assert_eq!(out, data);
    }

    #[test]
    fn seek_and_read_matches_slice() {
        let data = data();
        let mut r = WindowedReader::new(data.as_slice(), 1000);
        // Mix forward, backward, and end-relative seeks.
        for (seek, len) in [
            (SeekFrom::Start(5), 10usize),
            (SeekFrom::Start(2500), 1500), // Crosses a window boundary.
            (SeekFrom::Current(-100), 50), // Backward seek within a window.
            (SeekFrom::End(-8), 8),
            (SeekFrom::Start(0), 4),
        ] {
            let pos = r.seek(seek).unwrap() as usize;
            let mut buf = vec![0u8; len];
            let mut filled = 0;
            while filled < len {
                let n = r.read(&mut buf[filled..]).unwrap();
                if n == 0 {
                    break;
                }
                filled += n;
            }
            assert_eq!(&buf[..filled], &data[pos..pos + filled]);
            assert_eq!(filled, len.min(data.len() - pos));
        }
    }

    #[test]
    fn alternating_far_reads_use_both_windows() {
        // Like zip CD parsing: alternating reads near the end and earlier in the file
        // should stay in the two windows and advance independently.
        let data = data();
        let mut r = WindowedReader::new(data.as_slice(), 1000);
        let tail_base = data.len() - 2000;
        for i in 0..100usize {
            let front = i * 16;
            let tail = tail_base + i * 16;
            for pos in [front, tail] {
                r.seek(SeekFrom::Start(pos as u64)).unwrap();
                let mut buf = [0u8; 16];
                let mut filled = 0;
                while filled < buf.len() {
                    let n = r.read(&mut buf[filled..]).unwrap();
                    if n == 0 {
                        break;
                    }
                    filled += n;
                }
                assert_eq!(&buf[..filled], &data[pos..pos + filled]);
            }
        }
    }

    #[test]
    fn read_past_eof_returns_zero() {
        let data = data();
        let mut r = WindowedReader::new(data.as_slice(), 64);
        r.seek(SeekFrom::End(100)).unwrap();
        let mut buf = [0u8; 8];
        assert_eq!(r.read(&mut buf).unwrap(), 0);
    }
}
