//! String interning. Class names, member names, and descriptors are heavily duplicated
//! across the classpath ("java/lang/Object", "()V", "<init>", and so on), so a shared
//! pool collapses them into one stored instance.
//!
//! `Sym` is a u32 ID (Copy). Unlike Arc<str>, cloning does not need atomic refcount
//! operations, and comparison and hashing are just one integer. String bytes are appended
//! to per-shard bump arenas because malloc per string would add millions of allocation
//! overheads and fragmentation. The pool is static and is not freed before process exit.

use rustc_hash::FxHashMap;
use serde::{Serialize, Serializer};
use std::hash::{BuildHasher, Hasher, RandomState};
use std::sync::{Mutex, OnceLock, RwLock};

#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Sym(u32);

impl Sym {
    pub fn as_str(self) -> &'static str {
        pool().table.read().unwrap()[self.0 as usize]
    }
}

impl std::fmt::Display for Sym {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

impl std::fmt::Debug for Sym {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self.as_str())
    }
}

impl Serialize for Sym {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

const SHARDS: usize = 64;
const CHUNK_SIZE: usize = 256 * 1024;

#[derive(Default)]
struct Shard {
    map: FxHashMap<&'static str, Sym>,
    /// Bump arena. The last element is the chunk currently being written.
    chunks: Vec<Vec<u8>>,
}

impl Shard {
    /// Append a string to the arena and return a process-lifetime reference.
    fn alloc(&mut self, s: &str) -> &'static str {
        let need = s.len();
        if self
            .chunks
            .last()
            .is_none_or(|c| c.capacity() - c.len() < need)
        {
            self.chunks.push(Vec::with_capacity(CHUNK_SIZE.max(need)));
        }
        let chunk = self.chunks.last_mut().expect("chunk just pushed");
        let start = chunk.len();
        chunk.extend_from_slice(s.as_bytes());
        // SAFETY: Chunks are only appended within capacity, so reallocation cannot occur.
        // Pool is held by a static OnceLock and is never freed, so this slice remains valid
        // and immutable until process exit. The bytes come from &str and are UTF-8.
        unsafe {
            std::str::from_utf8_unchecked(std::slice::from_raw_parts(
                chunk.as_ptr().add(start),
                need,
            ))
        }
    }
}

struct Pool {
    /// Used for shard selection (maps inside shards use FxHash).
    hasher: RandomState,
    shards: Vec<Mutex<Shard>>,
    /// ID -> string. Append-only.
    table: RwLock<Vec<&'static str>>,
}

static POOL: OnceLock<Pool> = OnceLock::new();

fn pool() -> &'static Pool {
    POOL.get_or_init(|| Pool {
        hasher: RandomState::new(),
        shards: (0..SHARDS).map(|_| Mutex::new(Shard::default())).collect(),
        table: RwLock::new(Vec::new()),
    })
}

/// Called from rayon's parallel parser, so sharding reduces lock contention.
pub fn intern(s: &str) -> Sym {
    let pool = pool();
    let mut h = pool.hasher.build_hasher();
    h.write(s.as_bytes());
    let shard = (h.finish() as usize) % SHARDS;
    let mut shard = pool.shards[shard].lock().unwrap();
    if let Some(&sym) = shard.map.get(s) {
        return sym;
    }
    let stored = shard.alloc(s);
    let sym = {
        let mut table = pool.table.write().unwrap();
        let id = u32::try_from(table.len()).expect("intern table overflow");
        table.push(stored);
        Sym(id)
    };
    shard.map.insert(stored, sym);
    sym
}

/// Intern pool stats (unique string count, total string data bytes).
pub fn stats() -> (usize, usize) {
    let Some(pool) = POOL.get() else {
        return (0, 0);
    };
    let table = pool.table.read().unwrap();
    (table.len(), table.iter().map(|s| s.len()).sum())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn interning_dedupes_and_roundtrips() {
        let a = intern("hello/World");
        let b = intern("hello/World");
        assert_eq!(a, b);
        assert_eq!(a.as_str(), "hello/World");
        assert_ne!(a, intern("other"));
    }

    #[test]
    fn survives_chunk_rollover() {
        // Existing references must survive across chunk boundaries.
        let first = intern("rollover-first");
        let big = "x".repeat(CHUNK_SIZE + 1);
        let huge = intern(&big);
        assert_eq!(first.as_str(), "rollover-first");
        assert_eq!(huge.as_str().len(), CHUNK_SIZE + 1);
    }
}
