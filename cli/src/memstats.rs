//! Counting allocator for memory breakdowns (enabled only with --features memstats).
//! Tracks current and peak heap usage and reports a per-phase breakdown.

#[cfg(feature = "memstats")]
pub use enabled::*;

#[cfg(feature = "memstats")]
mod enabled {
    use std::alloc::{GlobalAlloc, Layout, System};
    use std::sync::atomic::{AtomicUsize, Ordering};

    pub struct CountingAlloc;

    static CURRENT: AtomicUsize = AtomicUsize::new(0);
    static PEAK: AtomicUsize = AtomicUsize::new(0);

    unsafe impl GlobalAlloc for CountingAlloc {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            let p = unsafe { System.alloc(layout) };
            if !p.is_null() {
                let cur = CURRENT.fetch_add(layout.size(), Ordering::Relaxed) + layout.size();
                PEAK.fetch_max(cur, Ordering::Relaxed);
            }
            p
        }

        unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
            let p = unsafe { System.alloc_zeroed(layout) };
            if !p.is_null() {
                let cur = CURRENT.fetch_add(layout.size(), Ordering::Relaxed) + layout.size();
                PEAK.fetch_max(cur, Ordering::Relaxed);
            }
            p
        }

        unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
            unsafe { System.dealloc(ptr, layout) };
            CURRENT.fetch_sub(layout.size(), Ordering::Relaxed);
        }
    }

    fn mb(bytes: usize) -> f64 {
        bytes as f64 / 1024.0 / 1024.0
    }

    /// Print current heap usage and peak to stderr with the phase name.
    pub fn report(phase: &str) {
        eprintln!(
            "[mem] {phase}: current={:.0}MB peak={:.0}MB",
            mb(CURRENT.load(Ordering::Relaxed)),
            mb(PEAK.load(Ordering::Relaxed)),
        );
    }
}

#[cfg(not(feature = "memstats"))]
pub fn report(_phase: &str) {}
