//! Load governor.
//!
//! The requirement this serves: the editor must stay at 60/120 fps while a
//! build, an index pass and a streaming completion are all in flight. A phone
//! cannot do that by running everything at once — it thermal-throttles, the
//! scheduler starts round-robining the UI thread, and frames drop.
//!
//! So work is admitted by class, and the budget per class is a function of the
//! device's current thermal and power state:
//!
//!   Interactive — anything a human is waiting on: the keystroke that triggers
//!                 a grep, opening a file, the agent's own turn. Runs on the
//!                 big cluster at default priority. Never starved.
//!   Background  — agent-initiated work with a visible progress indicator:
//!                 tool calls, diff generation, compaction summaries.
//!   Bulk        — speculative work nobody asked for: workspace indexing,
//!                 blob pruning, FTS optimisation. Pinned to the little
//!                 cluster, niced down, and the first thing dropped when the
//!                 device warms up.
//!
//! The Kotlin side owns the actual thread pools (`core/sched/LoadGovernor.kt`);
//! this module owns the topology probing and the policy arithmetic, because
//! both need /sys reads and syscalls the JVM does not expose.

use crate::error::{CoreError, Result};
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use serde_json::{json, Value};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TaskClass {
    Interactive,
    Background,
    Bulk,
}

impl TaskClass {
    fn parse(s: &str) -> Result<TaskClass> {
        match s {
            "interactive" => Ok(TaskClass::Interactive),
            "background" => Ok(TaskClass::Background),
            "bulk" => Ok(TaskClass::Bulk),
            other => Err(CoreError::invalid(format!("unknown task class '{other}'"))),
        }
    }
}

#[derive(Clone, Debug, Default)]
struct Topology {
    /// CPU indices sorted by max frequency, descending.
    big: Vec<usize>,
    little: Vec<usize>,
    total: usize,
}

static TOPOLOGY: Lazy<RwLock<Option<Topology>>> = Lazy::new(|| RwLock::new(None));

fn read_u64(path: &str) -> Option<u64> {
    std::fs::read_to_string(path)
        .ok()?
        .trim()
        .parse::<u64>()
        .ok()
}

fn probe_topology() -> Topology {
    let total = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4);

    let mut freqs: Vec<(usize, u64)> = Vec::with_capacity(total);
    for cpu in 0..total {
        let f = read_u64(&format!(
            "/sys/devices/system/cpu/cpu{cpu}/cpufreq/cpuinfo_max_freq"
        ))
        .unwrap_or(0);
        freqs.push((cpu, f));
    }

    let max = freqs.iter().map(|(_, f)| *f).max().unwrap_or(0);
    if max == 0 {
        // No cpufreq exposed (emulator, or a kernel that hides it). Treat the
        // bottom half as little; it is a guess but a harmless one.
        let split = (total / 2).max(1);
        return Topology {
            big: (split..total).collect(),
            little: (0..split).collect(),
            total,
        };
    }

    // Anything below 85% of peak is a little/mid core for our purposes. On a
    // 1+3+4 cluster this groups prime+big together, which is what we want.
    let threshold = max * 85 / 100;
    let mut big = Vec::new();
    let mut little = Vec::new();
    for (cpu, f) in freqs {
        if f >= threshold {
            big.push(cpu);
        } else {
            little.push(cpu);
        }
    }
    if big.is_empty() {
        big.push(total.saturating_sub(1));
    }
    if little.is_empty() {
        little.push(0);
    }
    Topology { big, little, total }
}

fn topology() -> Topology {
    if let Some(t) = TOPOLOGY.read().clone() {
        return t;
    }
    let t = probe_topology();
    *TOPOLOGY.write() = Some(t.clone());
    t
}

/* ------------------------------------------------------------------- policy */

/// Android `PowerManager` thermal statuses.
const THERMAL_NONE: i64 = 0;
const THERMAL_LIGHT: i64 = 1;
const THERMAL_MODERATE: i64 = 2;
const THERMAL_SEVERE: i64 = 3;
const THERMAL_CRITICAL: i64 = 4;

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Policy {
    pub interactive: usize,
    pub background: usize,
    pub bulk: usize,
    /// Nice value applied to background workers.
    pub nice_background: i32,
    pub nice_bulk: i32,
    pub allow_indexing: bool,
    pub allow_build: bool,
    /// Pin bulk work to the little cluster.
    pub pin_bulk_to_little: bool,
    /// Milliseconds to wait between bulk work items; non-zero throttles the
    /// indexer without stopping it.
    pub bulk_pace_ms: u64,
    pub reason: String,
}

pub fn policy_for(
    thermal: i64,
    power_save: bool,
    battery_pct: i64,
    charging: bool,
    screen_on: bool,
) -> Policy {
    let topo = topology();
    let big = topo.big.len().max(1);
    let little = topo.little.len().max(1);

    // Start from the unconstrained budget.
    let mut p = Policy {
        interactive: big.min(4),
        background: ((topo.total / 2).max(1)).min(4),
        bulk: little.min(3),
        nice_background: 4,
        nice_bulk: 12,
        allow_indexing: true,
        allow_build: true,
        pin_bulk_to_little: true,
        bulk_pace_ms: 0,
        reason: "nominal".into(),
    };

    // Effective pressure level: the worst of thermal state, power-save mode and
    // a low battery. Treating these separately produced policies that
    // contradicted each other.
    let mut level = thermal;
    if power_save {
        level = level.max(THERMAL_MODERATE);
        p.reason = "power save".into();
    }
    if !charging && (0..=15).contains(&battery_pct) {
        level = level.max(THERMAL_SEVERE);
        p.reason = "battery critical".into();
    } else if !charging && (0..=30).contains(&battery_pct) {
        level = level.max(THERMAL_MODERATE);
        p.reason = "battery low".into();
    }
    if thermal >= level && thermal > THERMAL_NONE {
        p.reason = format!("thermal status {thermal}");
    }

    match level {
        THERMAL_NONE => {}
        THERMAL_LIGHT => {
            p.bulk = p.bulk.min(2);
            p.bulk_pace_ms = 25;
        }
        THERMAL_MODERATE => {
            p.background = p.background.min(2);
            p.bulk = 1;
            p.bulk_pace_ms = 120;
            p.nice_bulk = 18;
            p.allow_indexing = charging;
        }
        THERMAL_SEVERE => {
            p.interactive = 2;
            p.background = 1;
            p.bulk = 0;
            p.bulk_pace_ms = 0;
            p.allow_indexing = false;
            // A build is the single hottest thing the app can do. Refusing it
            // here is better than letting the kernel throttle every core to
            // 30% mid-compile.
            p.allow_build = false;
        }
        _ => {
            p.interactive = 1;
            p.background = 0;
            p.bulk = 0;
            p.allow_indexing = false;
            p.allow_build = false;
            p.reason = format!("thermal status {level} — emergency");
        }
    }

    // Screen off means no one is watching the editor, so background work can
    // use what would have been the interactive budget.
    if !screen_on && level <= THERMAL_LIGHT {
        p.background = (p.background + 1).min(topo.total);
        p.nice_background = 8;
    }

    if level >= THERMAL_CRITICAL {
        p.allow_build = false;
    }
    p
}

/* ---------------------------------------------------------------- syscalls */

/// Pin the calling thread to a cluster.
pub fn set_affinity(class: TaskClass) -> Result<usize> {
    let topo = topology();
    let cpus: Vec<usize> = match class {
        TaskClass::Interactive => topo.big.clone(),
        // Background gets the whole machine; the nice value, not affinity, is
        // what keeps it out of the UI's way.
        TaskClass::Background => (0..topo.total).collect(),
        TaskClass::Bulk => topo.little.clone(),
    };

    let set_bits = std::mem::size_of::<libc::cpu_set_t>() * 8;
    unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        for cpu in &cpus {
            if *cpu < set_bits {
                libc::CPU_SET(*cpu, &mut set);
            }
        }
        // pid 0 == calling thread.
        let rc = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
        if rc != 0 {
            return Err(CoreError::last_errno("sched_setaffinity"));
        }
    }
    Ok(cpus.len())
}

/// Lower the calling thread's priority. Only downward moves are possible
/// without CAP_SYS_NICE, which is exactly what we want.
pub fn set_nice(value: i32) -> Result<i32> {
    unsafe {
        // setpriority(PRIO_PROCESS, 0, value) applies to the calling *thread*
        // on Linux, despite the name.
        if libc::setpriority(libc::PRIO_PROCESS, 0, value) != 0 {
            return Err(CoreError::last_errno("setpriority"));
        }
        Ok(libc::getpriority(libc::PRIO_PROCESS, 0))
    }
}

/* -------------------------------------------------------------------- ffi */

pub fn call(op: &str, payload: &str) -> Result<String> {
    let v: Value = if payload.trim().is_empty() {
        json!({})
    } else {
        serde_json::from_str(payload)?
    };
    let out = match op {
        "topology" => {
            let t = topology();
            json!({
                "total": t.total,
                "big": t.big,
                "little": t.little,
            })
        }
        "policy" => {
            let p = policy_for(
                v.get("thermalStatus").and_then(Value::as_i64).unwrap_or(0),
                v.get("powerSave").and_then(Value::as_bool).unwrap_or(false),
                v.get("batteryPct").and_then(Value::as_i64).unwrap_or(100),
                v.get("charging").and_then(Value::as_bool).unwrap_or(false),
                v.get("screenOn").and_then(Value::as_bool).unwrap_or(true),
            );
            serde_json::to_value(&p)?
        }
        "affinity" => {
            let class = TaskClass::parse(
                v.get("class")
                    .and_then(Value::as_str)
                    .unwrap_or("background"),
            )?;
            json!({ "cpus": set_affinity(class)? })
        }
        "nice" => {
            let value = v.get("value").and_then(Value::as_i64).unwrap_or(0) as i32;
            json!({ "nice": set_nice(value)? })
        }
        "memory" => meminfo(),
        other => return Err(CoreError::invalid(format!("unknown sched op '{other}'"))),
    };
    Ok(out.to_string())
}

/// Device memory pressure, used to decide whether the `:tools` process can
/// hold a large file in memory or must stream it.
fn meminfo() -> Value {
    let text = std::fs::read_to_string("/proc/meminfo").unwrap_or_default();
    let field = |name: &str| -> u64 {
        text.lines()
            .find(|l| l.starts_with(name))
            .and_then(|l| {
                l.split_whitespace()
                    .nth(1)
                    .and_then(|v| v.parse::<u64>().ok())
            })
            .unwrap_or(0)
    };
    let total = field("MemTotal:");
    let available = field("MemAvailable:");
    json!({
        "totalKb": total,
        "availableKb": available,
        "pressure": if total == 0 { 0.0 } else { 1.0 - (available as f64 / total as f64) },
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nominal_policy_allows_everything() {
        let p = policy_for(0, false, 90, true, true);
        assert!(p.allow_build && p.allow_indexing);
        assert!(p.interactive >= 1);
    }

    #[test]
    fn severe_thermal_stops_builds() {
        let p = policy_for(3, false, 90, true, true);
        assert!(!p.allow_build);
        assert_eq!(p.bulk, 0);
    }

    #[test]
    fn power_save_is_at_least_moderate() {
        let p = policy_for(0, true, 90, true, true);
        assert_eq!(p.bulk, 1);
        assert!(p.bulk_pace_ms > 0);
    }

    #[test]
    fn low_battery_without_charger_is_severe() {
        let p = policy_for(0, false, 9, false, true);
        assert!(!p.allow_build);
    }
}
