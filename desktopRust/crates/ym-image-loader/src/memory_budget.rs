//! The JVM `MemoryBudget.jvm.kt`: the desktop memory LRU budget.

const MIN_BUDGET: u64 = 16 * 1024 * 1024;
const MAX_BUDGET: u64 = 30 * 1024 * 1024;
const FALLBACK_BUDGET: u64 = MAX_BUDGET;

/// ~1/128 of physical RAM, 16–30 MB; 30 MB when the OS does not say. One Now Playing square alone is
/// ~2 MB.
pub fn default_memory_cache_budget() -> u64 {
    physical_memory_bytes().map_or(FALLBACK_BUDGET, |physical| (physical / 128).clamp(MIN_BUDGET, MAX_BUDGET))
}

#[cfg(unix)]
fn physical_memory_bytes() -> Option<u64> {
    // SAFETY: sysconf only reads system configuration values.
    let (pages, page_size) = unsafe { (libc::sysconf(libc::_SC_PHYS_PAGES), libc::sysconf(libc::_SC_PAGESIZE)) };
    (pages > 0 && page_size > 0).then(|| pages as u64 * page_size as u64)
}

#[cfg(target_os = "windows")]
fn physical_memory_bytes() -> Option<u64> {
    use windows::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
    let mut status = MEMORYSTATUSEX { dwLength: size_of::<MEMORYSTATUSEX>() as u32, ..Default::default() };
    // SAFETY: `status` is a valid MEMORYSTATUSEX with its length set, as the call requires.
    unsafe { GlobalMemoryStatusEx(&mut status) }.ok().map(|()| status.ullTotalPhys)
}

#[cfg(not(any(unix, target_os = "windows")))]
fn physical_memory_bytes() -> Option<u64> {
    None
}
