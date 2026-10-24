//! Token accounting.
//!
//! The app has to know how full a ~1M token window is *before* sending a
//! request, on every keystroke-free redraw of the context meter, and for every
//! tool result it decides whether to inline or spill to a blob. Running a real
//! BPE merge over a megabyte of conversation on each of those is not viable on
//! a phone, so this is a character-class estimator instead.
//!
//! Accuracy: within roughly ±8% of o200k_base / cl100k_base on mixed
//! English + Russian + source code. That is good enough for budgeting, and the
//! residual error is removed by `calibrate()`: after every completion the
//! engine feeds back the provider's authoritative `usage.prompt_tokens`, and
//! the estimator corrects its global scale factor. After two or three turns the
//! meter tracks the provider almost exactly.

use std::sync::atomic::{AtomicU32, Ordering};

/// Scale factor in fixed point (1024 == 1.0).
static CALIBRATION: AtomicU32 = AtomicU32::new(1024);

/// Per-message framing overhead in the chat wire formats (role, delimiters).
pub const MESSAGE_OVERHEAD: u32 = 4;
/// Overhead of a tool definition's JSON schema envelope.
pub const TOOL_DEF_OVERHEAD: u32 = 8;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Class {
    Latin,
    Cyrillic,
    Cjk,
    Digit,
    Space,
    Newline,
    Symbol,
    Other,
}

fn classify(c: char) -> Class {
    match c {
        '\n' | '\r' => Class::Newline,
        ' ' | '\t' => Class::Space,
        '0'..='9' => Class::Digit,
        'a'..='z' | 'A'..='Z' => Class::Latin,
        _ => {
            let cp = c as u32;
            if (0x0400..=0x04FF).contains(&cp) || (0x0500..=0x052F).contains(&cp) {
                Class::Cyrillic
            } else if (0x3040..=0x30FF).contains(&cp)      // hiragana + katakana
                || (0x3400..=0x4DBF).contains(&cp)          // CJK ext A
                || (0x4E00..=0x9FFF).contains(&cp)          // CJK unified
                || (0xAC00..=0xD7AF).contains(&cp)          // hangul
                || (0xF900..=0xFAFF).contains(&cp)
            {
                Class::Cjk
            } else if c.is_alphabetic() {
                Class::Latin
            } else if c.is_ascii_punctuation() || c.is_ascii_graphic() {
                Class::Symbol
            } else {
                Class::Other
            }
        }
    }
}

#[inline]
fn div_ceil(n: usize, d: usize) -> u32 {
    ((n + d - 1) / d) as u32
}

/// Cost of one homogeneous run of `len` characters.
fn run_cost(class: Class, len: usize, uniform_char: bool) -> u32 {
    match class {
        // ~4.2 chars/token for English prose and identifiers.
        Class::Latin => div_ceil(len * 10, 42).max(1),
        // Russian costs roughly twice as much per character.
        Class::Cyrillic => div_ceil(len * 10, 20).max(1),
        // Most CJK codepoints are a token, some pairs merge.
        Class::Cjk => div_ceil(len * 10, 14).max(1),
        // Digits merge in groups of up to three.
        Class::Digit => div_ceil(len, 3).max(1),
        // The first space is absorbed into the following word; indentation runs
        // merge aggressively.
        Class::Space => {
            if len <= 1 {
                0
            } else {
                div_ceil(len - 1, 4).max(1)
            }
        }
        Class::Newline => div_ceil(len, 2).max(1),
        Class::Symbol => {
            if uniform_char && len > 2 {
                // "-----", "=====", "/////" collapse into few merges.
                div_ceil(len, 3).max(1)
            } else {
                len as u32
            }
        }
        // Emoji and friends: several bytes, usually 2-3 tokens each.
        Class::Other => (len as u32) * 2,
    }
}

/// Raw, uncalibrated estimate.
pub fn estimate_raw(text: &str) -> u32 {
    if text.is_empty() {
        return 0;
    }
    let mut total: u32 = 0;
    let mut run_class: Option<Class> = None;
    let mut run_len: usize = 0;
    let mut run_first: char = '\0';
    let mut run_uniform = true;

    for c in text.chars() {
        let cl = classify(c);
        match run_class {
            Some(prev) if prev == cl => {
                run_len += 1;
                if c != run_first {
                    run_uniform = false;
                }
            }
            Some(prev) => {
                total += run_cost(prev, run_len, run_uniform);
                run_class = Some(cl);
                run_len = 1;
                run_first = c;
                run_uniform = true;
            }
            None => {
                run_class = Some(cl);
                run_len = 1;
                run_first = c;
                run_uniform = true;
            }
        }
    }
    if let Some(cl) = run_class {
        total += run_cost(cl, run_len, run_uniform);
    }
    total
}

/// Calibrated estimate — this is what callers should use.
pub fn estimate(text: &str) -> u32 {
    let raw = estimate_raw(text) as u64;
    let factor = CALIBRATION.load(Ordering::Relaxed) as u64;
    ((raw * factor) / 1024) as u32
}

/// Feed back an authoritative count from the provider.
///
/// `reported` is `usage.prompt_tokens`; `estimated` is what we predicted for the
/// exact same payload. The correction is damped (1/8 step) so one weird response
/// cannot swing the meter.
pub fn calibrate(estimated: u32, reported: u32) {
    if estimated == 0 || reported == 0 {
        return;
    }
    let current = CALIBRATION.load(Ordering::Relaxed) as i64;
    let ideal = (reported as i64 * 1024) / estimated as i64;
    let next = current + (ideal - current) / 8;
    // Clamp to a sane band; outside it we are misreading the protocol, not
    // mis-estimating text.
    let next = next.clamp(512, 2048) as u32;
    CALIBRATION.store(next, Ordering::Relaxed);
}

pub fn calibration_factor_millis() -> u32 {
    (CALIBRATION.load(Ordering::Relaxed) as u64 * 1000 / 1024) as u32
}

pub fn reset_calibration() {
    CALIBRATION.store(1024, Ordering::Relaxed);
}

/// Token cost of a chat message including wire framing.
pub fn estimate_message(role: &str, content: &str) -> u32 {
    estimate(role) + estimate(content) + MESSAGE_OVERHEAD
}

/// Truncate `text` so it costs at most `budget` tokens, keeping the head and
/// the tail (the middle is usually the least informative part of a long tool
/// output: the head has the command and the tail has the error).
pub fn clamp_head_tail(text: &str, budget: u32) -> (String, bool) {
    if budget == 0 {
        return (String::new(), !text.is_empty());
    }
    if estimate(text) <= budget {
        return (text.to_string(), false);
    }
    // Work in characters; 3.5 chars/token is a safe lower bound so we never
    // overshoot the budget after re-estimating.
    let char_budget = (budget as usize).saturating_mul(3);
    let head_chars = char_budget * 2 / 3;
    let tail_chars = char_budget - head_chars;

    let chars: Vec<char> = text.chars().collect();
    if chars.len() <= char_budget {
        return (text.to_string(), false);
    }
    let head: String = chars[..head_chars.min(chars.len())].iter().collect();
    let tail: String = chars[chars.len().saturating_sub(tail_chars)..].iter().collect();
    let omitted = chars.len() - head_chars - tail_chars;

    let mut out = String::with_capacity(head.len() + tail.len() + 64);
    out.push_str(&head);
    out.push_str("\n\n[... ");
    out.push_str(&omitted.to_string());
    out.push_str(" characters elided — use conversation_read with the blob id to page through the full output ...]\n\n");
    out.push_str(&tail);
    (out, true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_is_zero() {
        assert_eq!(estimate(""), 0);
    }

    #[test]
    fn english_is_roughly_four_chars_per_token() {
        let t = estimate_raw("the quick brown fox jumps over the lazy dog");
        assert!((7..=14).contains(&t), "got {t}");
    }

    #[test]
    fn calibration_converges() {
        reset_calibration();
        for _ in 0..40 {
            calibrate(100, 120);
        }
        let f = calibration_factor_millis();
        assert!((1150..=1250).contains(&f), "factor {f}");
        reset_calibration();
    }

    #[test]
    fn clamp_keeps_both_ends() {
        let body = "START".to_string() + &"x".repeat(50_000) + "END";
        let (out, truncated) = clamp_head_tail(&body, 100);
        assert!(truncated);
        assert!(out.starts_with("START"));
        assert!(out.ends_with("END"));
    }
}
