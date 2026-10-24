//! Native core for ApkCLI.
//!
//! Everything in here exists for one of two reasons:
//!
//!   1. It is hot enough that doing it on the JVM would make the UI stutter
//!      (workspace search, diffing, token accounting, the conversation store).
//!   2. It needs syscalls the JVM does not expose (PTY master/slave pairs,
//!      CPU affinity, thermal-aware thread budgets).
//!
//! The module boundary is deliberate: no module touches JNI except `ffi`, so
//! the logic is testable on a host machine with `cargo test`.

pub mod diff;
pub mod error;
pub mod proc;
pub mod pty;
pub mod sched;
pub mod search;
pub mod store;
pub mod tokens;

mod ffi;

pub use error::{CoreError, Result};

/// Called once per process from `NativeLoader`.
pub fn init_logging(verbose: bool) {
    #[cfg(target_os = "android")]
    {
        use log::LevelFilter;
        let level = if verbose { LevelFilter::Debug } else { LevelFilter::Info };
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(level)
                .with_tag("apkcli-core"),
        );
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = verbose;
    }
    log::info!("apkcli-core {} initialised", env!("CARGO_PKG_VERSION"));
}
