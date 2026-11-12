//! Diffing, patch application and the forgiving search/replace that backs the
//! `fs_edit` tool.
//!
//! Models get indentation and trailing whitespace wrong constantly. A strict
//! `String::replace` turns that into a failed tool call, a retry, and a few
//! thousand wasted tokens. So `replace` degrades through four strategies
//! before giving up, and always reports which one it used so the UI can show
//! "applied with reindentation" rather than silently changing something the
//! model did not ask for. When none matches, the error says what the file has
//! instead (`miss_hint`), so the retry needs no fresh read of the file.

use crate::error::{CoreError, Result};
use similar::{ChangeTag, TextDiff};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MatchStrategy {
    /// Byte-for-byte.
    Exact,
    /// Matched after normalising trailing whitespace on each line.
    TrailingWhitespace,
    /// Matched after also reading curly quotes, dashes and the non-breaking
    /// spaces as their plain ASCII forms ([`fold_typography`]).
    Typographic,
    /// Matched after removing the common indent from the pattern; the
    /// replacement was re-indented to the target's indentation.
    Reindented,
}

/// Crosses JNI as JSON, read by `FsEditTool` as `addedLines`/`removedLines`.
/// Without the rename serde wrote `added_lines`, the Kotlin side found no
/// such key, and every edit reported "+0/-0 lines".
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EditOutcome {
    pub content: String,
    pub replacements: usize,
    pub strategy: MatchStrategy,
    pub added_lines: usize,
    pub removed_lines: usize,
    pub diff: String,
}

#[derive(Debug, Clone, Copy, serde::Serialize)]
pub struct DiffStats {
    pub added: usize,
    pub removed: usize,
}

pub fn unified(path: &str, old: &str, new: &str, context: usize) -> String {
    let diff = TextDiff::from_lines(old, new);
    diff.unified_diff()
        .context_radius(context)
        .header(&format!("a/{path}"), &format!("b/{path}"))
        .to_string()
}

pub fn stats(old: &str, new: &str) -> DiffStats {
    let diff = TextDiff::from_lines(old, new);
    let mut added = 0;
    let mut removed = 0;
    for change in diff.iter_all_changes() {
        match change.tag() {
            ChangeTag::Insert => added += 1,
            ChangeTag::Delete => removed += 1,
            ChangeTag::Equal => {}
        }
    }
    DiffStats { added, removed }
}

/* -------------------------------------------------------------------------
 * search / replace
 * ---------------------------------------------------------------------- */

/// Replace `needle` with `replacement`.
///
/// `expected` is the number of occurrences the caller believes exist:
///   * `Some(n)` — fail unless exactly `n` are found. This is the safety net
///     that stops a one-line edit from being applied in forty places.
///   * `None` — replace every occurrence.
pub fn replace(
    original: &str,
    needle: &str,
    replacement: &str,
    expected: Option<usize>,
    path_for_diff: &str,
) -> Result<EditOutcome> {
    if needle.is_empty() {
        return Err(CoreError::invalid("search string must not be empty"));
    }
    if needle == replacement {
        return Err(CoreError::invalid(
            "search and replacement are identical — nothing to do",
        ));
    }

    let attempts: [MatchStrategy; 4] = [
        MatchStrategy::Exact,
        MatchStrategy::TrailingWhitespace,
        MatchStrategy::Typographic,
        MatchStrategy::Reindented,
    ];

    // In the file's own line endings: a model writes `\n`, and a CRLF file
    // given that verbatim ends up with mixed endings.
    let replacement = with_line_endings_of(original, replacement);
    let replacement: &str = &replacement;

    for strategy in attempts {
        let Some((content, count)) = try_strategy(original, needle, replacement, strategy) else {
            continue;
        };
        if count == 0 {
            continue;
        }
        if let Some(want) = expected {
            if count != want {
                // Found it, but not the number of times the caller promised.
                // Report rather than guess.
                return Err(CoreError::refused(format!(
                    "expected {want} occurrence(s) but found {count} \
                     (strategy: {strategy:?}). Add surrounding context to the \
                     search string to disambiguate, or pass replace_all."
                )));
            }
        }
        let s = stats(original, &content);
        return Ok(EditOutcome {
            diff: unified(path_for_diff, original, &content, 3),
            content,
            replacements: count,
            strategy,
            added_lines: s.added,
            removed_lines: s.removed,
        });
    }

    // Reaching here means every strategy found zero occurrences: every path
    // that found something returned above. So there is no hit count to
    // report, and the message must not imply otherwise.
    let tried = "tried exact, trailing-whitespace-insensitive, typographic and reindented matching";
    Err(CoreError::not_found(match miss_hint(original, needle, replacement) {
        Some(hint) => format!("search string not found ({tried}). {hint}"),
        None => format!(
            "search string not found ({tried}). The file may have changed since it \
             was read — re-read it first."
        ),
    }))
}

fn try_strategy(
    original: &str,
    needle: &str,
    replacement: &str,
    strategy: MatchStrategy,
) -> Option<(String, usize)> {
    match strategy {
        MatchStrategy::Exact => {
            let count = original.matches(needle).count();
            if count == 0 {
                None
            } else {
                Some((original.replace(needle, replacement), count))
            }
        }
        MatchStrategy::TrailingWhitespace => trailing_ws_replace(original, needle, replacement),
        MatchStrategy::Typographic => typographic_replace(original, needle, replacement),
        MatchStrategy::Reindented => reindent_replace(original, needle, replacement),
    }
}

/// The plain ASCII form of a typographic look-alike: curly quotes, the
/// hyphens and dashes and the minus sign, the non-breaking and fixed-width
/// spaces. A file has them from a word processor or a web page; a model
/// writes the ASCII ones. One char for one, so a match maps back to the file.
fn fold_typography(c: char) -> char {
    match c {
        '\u{2018}' | '\u{2019}' | '\u{201A}' | '\u{201B}' => '\'',
        '\u{201C}' | '\u{201D}' | '\u{201E}' | '\u{201F}' => '"',
        '\u{2010}'..='\u{2015}' | '\u{2212}' => '-',
        '\u{00A0}' | '\u{2002}'..='\u{200A}' | '\u{202F}' | '\u{205F}' | '\u{3000}' => ' ',
        _ => c,
    }
}

/// `text` with trailing whitespace cut from every line and its typography
/// folded, and for each byte of the result the span of `text` it came from.
///
/// A span rather than an offset, as in [`normalise_with_map`]: a curly quote
/// is three bytes in the file and one here, so "the byte after it" has to be
/// the end of the whole character, not the offset of its first byte plus one.
fn folded_with_spans(text: &str) -> (String, Vec<(usize, usize)>) {
    let mut folded = String::with_capacity(text.len());
    let mut spans = Vec::with_capacity(text.len());
    let mut offset = 0usize;
    let mut lines = text.split('\n').peekable();
    while let Some(line) = lines.next() {
        for (index, c) in line.trim_end().char_indices() {
            let start = offset + index;
            let span = (start, start + c.len_utf8());
            let mut buf = [0u8; 4];
            let piece = fold_typography(c).encode_utf8(&mut buf);
            folded.push_str(piece);
            spans.extend(std::iter::repeat(span).take(piece.len()));
        }
        offset += line.len();
        if lines.peek().is_some() {
            folded.push('\n');
            spans.push((offset, offset + 1));
            offset += 1;
        }
    }
    (folded, spans)
}

/// [`trailing_ws_replace`] with typography folded as well: found in a folded
/// copy, spliced into the original, so every byte outside a match is kept.
fn typographic_replace(original: &str, needle: &str, replacement: &str) -> Option<(String, usize)> {
    let (folded_needle, _) = folded_with_spans(needle);
    if folded_needle.is_empty() {
        return None;
    }
    let (folded, spans) = folded_with_spans(original);
    let bytes = folded.as_bytes();

    let mut out = String::with_capacity(original.len() + replacement.len());
    let mut copied = 0usize;
    let mut count = 0usize;
    for (start, matched) in folded.match_indices(folded_needle.as_str()) {
        let end = start + matched.len();
        // As in trailing_ws_replace: a match that starts at a line break
        // takes the whitespace before it, one that ends at the end of a line
        // leaves that line's trailing whitespace and its `\r` in place.
        let from = if bytes[start] == b'\n' {
            if start == 0 { 0 } else { spans[start - 1].1 }
        } else {
            spans[start].0
        };
        let to = if end == folded.len() || bytes[end] == b'\n' {
            spans[end - 1].1
        } else {
            spans[end].0
        };
        let from = from.max(copied);
        out.push_str(&original[copied..from]);
        out.push_str(replacement);
        copied = to.max(from);
        count += 1;
    }
    if count == 0 {
        return None;
    }
    out.push_str(&original[copied..]);
    Some((out, count))
}

/// Lines a miss hint shows at most, and the longest part of one it shows.
const HINT_LINES: usize = 12;
const HINT_LINE_CHARS: usize = 200;

/// A replacement shorter than this, found in the file, proves nothing: short
/// text occurs anywhere.
const APPLIED_MIN_CHARS: usize = 20;

/// What to tell the model when no strategy matched, so that its next attempt
/// can succeed without reading the file again: that the replacement is
/// already there (an edit that applied, sent a second time), or the lines of
/// the file closest to `needle`. `None` when the file has nothing like it.
fn miss_hint(original: &str, needle: &str, replacement: &str) -> Option<String> {
    let (folded_file, _) = folded_with_spans(original);
    let (folded_replacement, _) = folded_with_spans(replacement);
    let applied = folded_replacement.trim();
    if applied.chars().count() >= APPLIED_MIN_CHARS && folded_file.contains(applied) {
        return Some(
            "The replacement text is already in the file, so this edit may have \
             been applied already. Check the file before sending it again."
                .to_string(),
        );
    }
    closest_lines(original, needle)
}

/// The run of lines in `original` that `needle` most likely meant, numbered
/// as fs_read numbers them.
///
/// Each line of `needle` votes for where `needle` would start, at every line
/// of the file equal to it once whitespace runs and typography are ignored.
/// Lines with no letter or digit (`}`, `*/`) do not vote: they are
/// everywhere. The start most lines agree on wins, if at least half of the
/// lines that could vote did; only the lines from its first match to its last
/// are shown, at most [`HINT_LINES`].
fn closest_lines(original: &str, needle: &str) -> Option<String> {
    fn key(line: &str) -> String {
        let folded: String = line.chars().map(fold_typography).collect();
        folded.split_whitespace().collect::<Vec<_>>().join(" ")
    }
    fn votes_at_all(key: &str) -> bool {
        key.chars().any(char::is_alphanumeric)
    }

    let lines: Vec<&str> = original
        .split('\n')
        .map(|line| line.strip_suffix('\r').unwrap_or(line))
        .collect();
    let mut positions: HashMap<String, Vec<usize>> = HashMap::new();
    for (index, line) in lines.iter().enumerate() {
        let k = key(line);
        if votes_at_all(&k) {
            positions.entry(k).or_default().push(index);
        }
    }

    let wanted: Vec<String> = needle.split('\n').map(key).collect();
    let mut voters = 0usize;
    let mut votes: HashMap<isize, usize> = HashMap::new();
    for (offset, k) in wanted.iter().enumerate() {
        if !votes_at_all(k) {
            continue;
        }
        voters += 1;
        for &index in positions.get(k).map(Vec::as_slice).unwrap_or(&[]) {
            *votes.entry(index as isize - offset as isize).or_default() += 1;
        }
    }
    // The most votes; of starts with as many, the earliest.
    let (start, count) = votes
        .into_iter()
        .max_by(|a, b| a.1.cmp(&b.1).then(b.0.cmp(&a.0)))?;
    if count == 0 || count * 2 < voters {
        return None;
    }

    let matched: Vec<usize> = wanted
        .iter()
        .enumerate()
        .filter_map(|(offset, k)| {
            let index = start + offset as isize;
            let hit = !k.is_empty()
                && index >= 0
                && (index as usize) < lines.len()
                && key(lines[index as usize]) == *k;
            hit.then_some(index as usize)
        })
        .collect();
    let from = *matched.first()?;
    let to = (*matched.last()? + 1).min(from + HINT_LINES);

    let mut hint = String::from(
        "The closest lines in the file, numbered as fs_read shows them (the numbers \
         are not part of the file):",
    );
    for (index, line) in lines[from..to].iter().enumerate() {
        let shown: String = line.chars().take(HINT_LINE_CHARS).collect();
        let cut = if line.chars().count() > HINT_LINE_CHARS { "…" } else { "" };
        hint.push_str(&format!("\n{:>5}  {shown}{cut}", from + index + 1));
    }
    hint.push_str("\nCopy `search` from these lines exactly.");
    Some(hint)
}

/// `original` with trailing whitespace removed from every line, and for each
/// byte of the result the offset of the byte in `original` it came from.
fn normalise_with_map(original: &str) -> (String, Vec<usize>) {
    let mut norm = String::with_capacity(original.len());
    let mut map = Vec::with_capacity(original.len());
    let mut offset = 0usize;
    let mut lines = original.split('\n').peekable();
    while let Some(line) = lines.next() {
        let kept = line.trim_end();
        norm.push_str(kept);
        map.extend(offset..offset + kept.len());
        offset += line.len();
        if lines.peek().is_some() {
            norm.push('\n');
            map.push(offset);
            offset += 1;
        }
    }
    (norm, map)
}

/// Replace `needle` wherever it occurs once trailing whitespace is ignored:
/// spaces, tabs, and the `\r` of a CRLF line.
///
/// The matches are found in a normalised copy but spliced into the original,
/// so every byte outside a match is kept. This used to do the replacing in the
/// normalised copy and return it — a one-line edit stripped trailing
/// whitespace from every line of the file and turned CRLF into LF.
fn trailing_ws_replace(original: &str, needle: &str, replacement: &str) -> Option<(String, usize)> {
    let norm_needle = strip_trailing_ws(needle);
    if norm_needle.is_empty() {
        return None;
    }
    let (norm, map) = normalise_with_map(original);
    let bytes = norm.as_bytes();

    let mut out = String::with_capacity(original.len() + replacement.len());
    let mut copied = 0usize;
    let mut count = 0usize;
    for (start, matched) in norm.match_indices(norm_needle.as_str()) {
        let end = start + matched.len();
        // A match that starts at a line break takes the whitespace before it
        // as well, or the old line's `\r` would sit in front of the
        // replacement's own line break.
        let from = if bytes[start] == b'\n' {
            if start == 0 { 0 } else { map[start - 1] + 1 }
        } else {
            map[start]
        };
        // One that ends at the end of a line leaves that line's trailing
        // whitespace, and its `\r`, where they were.
        let to = if end == norm.len() || bytes[end] == b'\n' {
            map[end - 1] + 1
        } else {
            map[end]
        };
        let from = from.max(copied);
        out.push_str(&original[copied..from]);
        out.push_str(replacement);
        copied = to.max(from);
        count += 1;
    }
    if count == 0 {
        return None;
    }
    out.push_str(&original[copied..]);
    Some((out, count))
}

/// Does `text` use CRLF? Mixed files go by the majority.
fn uses_crlf(text: &str) -> bool {
    let crlf = text.matches("\r\n").count();
    let lf = text.matches('\n').count() - crlf;
    crlf > 0 && crlf >= lf
}

/// `text` with the line endings `original` uses.
fn with_line_endings_of<'a>(original: &str, text: &'a str) -> std::borrow::Cow<'a, str> {
    if !text.contains('\n') || !uses_crlf(original) {
        return std::borrow::Cow::Borrowed(text);
    }
    std::borrow::Cow::Owned(text.replace("\r\n", "\n").replace('\n', "\r\n"))
}

fn strip_trailing_ws(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.split('\n').peekable();
    while let Some(line) = it.next() {
        out.push_str(line.trim_end());
        if it.peek().is_some() {
            out.push('\n');
        }
    }
    out
}

fn common_indent(lines: &[&str]) -> String {
    let mut indent: Option<&str> = None;
    for line in lines {
        if line.trim().is_empty() {
            continue;
        }
        let ws_len = line.len() - line.trim_start().len();
        let ws = &line[..ws_len];
        indent = Some(match indent {
            None => ws,
            Some(prev) => {
                let n = prev
                    .chars()
                    .zip(ws.chars())
                    .take_while(|(a, b)| a == b)
                    .count();
                &prev[..n]
            }
        });
    }
    indent.unwrap_or("").to_string()
}

fn dedent(block: &str) -> (String, String) {
    let lines: Vec<&str> = block.split('\n').collect();
    let indent = common_indent(&lines);
    if indent.is_empty() {
        return (block.to_string(), indent);
    }
    let out = lines
        .iter()
        .map(|l| l.strip_prefix(indent.as_str()).unwrap_or(l.trim_start()))
        .collect::<Vec<_>>()
        .join("\n");
    (out, indent)
}

fn indent_block(block: &str, indent: &str) -> String {
    if indent.is_empty() {
        return block.to_string();
    }
    block
        .split('\n')
        .map(|l| {
            if l.trim().is_empty() {
                l.to_string()
            } else {
                format!("{indent}{l}")
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Line-window match that ignores the absolute indentation level, then puts the
/// replacement back at the indentation the file actually uses.
fn reindent_replace(original: &str, needle: &str, replacement: &str) -> Option<(String, usize)> {
    let (needle_dedented, _) = dedent(&strip_trailing_ws(needle));
    let needle_lines: Vec<&str> = needle_dedented.split('\n').collect();
    if needle_lines.is_empty() {
        return None;
    }

    let orig_lines: Vec<&str> = original.split('\n').collect();
    if needle_lines.len() > orig_lines.len() {
        return None;
    }

    let (replacement_dedented, _) = dedent(replacement);

    let mut out: Vec<String> = Vec::with_capacity(orig_lines.len());
    let mut i = 0usize;
    let mut count = 0usize;

    while i < orig_lines.len() {
        let window_end = i + needle_lines.len();
        if window_end <= orig_lines.len() {
            let window = &orig_lines[i..window_end];
            let (window_dedented, window_indent) = dedent(&window.join("\n"));
            if strip_trailing_ws(&window_dedented) == needle_dedented {
                let mut block = indent_block(&replacement_dedented, &window_indent);
                // Whole lines were matched, so the last one's `\r` belongs to
                // the file, not to the text being replaced.
                if window.last().is_some_and(|l| l.ends_with('\r')) && !block.ends_with('\r') {
                    block.push('\r');
                }
                out.push(block);
                i = window_end;
                count += 1;
                continue;
            }
        }
        out.push(orig_lines[i].to_string());
        i += 1;
    }

    if count == 0 {
        None
    } else {
        Some((out.join("\n"), count))
    }
}

/* -------------------------------------------------------------------------
 * unified patch application
 * ---------------------------------------------------------------------- */

struct Hunk {
    old_start: usize,
    lines: Vec<(char, String)>, // ' ' context, '-' delete, '+' insert
}

/// Apply a unified diff.
///
/// Line numbers in the hunk headers are treated as hints, not gospel: the
/// context block is searched for within `drift` lines of the stated position.
/// Model-generated patches are routinely off by a few lines.
pub fn apply_unified(original: &str, patch: &str, drift: usize) -> Result<String> {
    let hunks = parse_hunks(patch)?;
    if hunks.is_empty() {
        return Err(CoreError::invalid("patch contains no @@ hunks"));
    }

    let crlf = uses_crlf(original);
    let mut lines: Vec<String> = original.split('\n').map(|s| s.to_string()).collect();
    // Apply from the bottom so earlier edits do not shift later offsets.
    let mut ordered = hunks;
    ordered.sort_by_key(|h| std::cmp::Reverse(h.old_start));

    for hunk in &ordered {
        let expected: Vec<&String> = hunk
            .lines
            .iter()
            .filter(|(tag, _)| *tag == ' ' || *tag == '-')
            .map(|(_, l)| l)
            .collect();

        let anchor = hunk.old_start.saturating_sub(1);
        let found = locate(&lines, &expected, anchor, drift).ok_or_else(|| {
            CoreError::refused(format!(
                "hunk at old line {} does not match the file (searched +/-{} lines). \
                 Re-read the file and regenerate the patch.",
                hunk.old_start, drift
            ))
        })?;

        let mut replacement: Vec<String> = Vec::new();
        let mut at = found;
        for (tag, text) in &hunk.lines {
            match tag {
                // Context is the file's own line, not the patch's copy of it:
                // the copy matched only up to trailing whitespace, and a CRLF
                // file's `\r` is not in it.
                ' ' => {
                    replacement.push(lines[at].clone());
                    at += 1;
                }
                '-' => at += 1,
                '+' if crlf && !text.ends_with('\r') => replacement.push(format!("{text}\r")),
                '+' => replacement.push(text.clone()),
                _ => {}
            }
        }
        lines.splice(found..found + expected.len(), replacement);
    }

    Ok(lines.join("\n"))
}

fn locate(
    haystack: &[String],
    needle: &[&String],
    anchor: usize,
    drift: usize,
) -> Option<usize> {
    if needle.is_empty() {
        return Some(anchor.min(haystack.len()));
    }
    let matches_at = |pos: usize| -> bool {
        if pos + needle.len() > haystack.len() {
            return false;
        }
        needle
            .iter()
            .enumerate()
            .all(|(k, want)| haystack[pos + k].trim_end() == want.trim_end())
    };

    if matches_at(anchor) {
        return Some(anchor);
    }
    for delta in 1..=drift {
        if anchor >= delta && matches_at(anchor - delta) {
            return Some(anchor - delta);
        }
        if matches_at(anchor + delta) {
            return Some(anchor + delta);
        }
    }
    None
}

fn parse_hunks(patch: &str) -> Result<Vec<Hunk>> {
    let mut hunks: Vec<Hunk> = Vec::new();
    let mut current: Option<Hunk> = None;

    // A patch ends with a newline, and splitting on it leaves an empty last
    // element. That used to become one more blank context line, so a hunk
    // matched only where a blank line followed it — at the end of the file,
    // and almost nowhere else.
    let body = patch.strip_suffix('\n').unwrap_or(patch);
    for raw in body.split('\n') {
        if raw.starts_with("@@") {
            if let Some(h) = current.take() {
                hunks.push(h);
            }
            current = Some(Hunk {
                old_start: parse_hunk_header(raw)?,
                lines: Vec::new(),
            });
            continue;
        }
        if raw.starts_with("--- ") || raw.starts_with("+++ ") || raw.starts_with("diff ")
            || raw.starts_with("index ") || raw.starts_with("new file") || raw.starts_with("deleted file")
            || raw.starts_with("similarity ") || raw.starts_with("rename ")
        {
            continue;
        }
        if raw == "\\ No newline at end of file" {
            continue;
        }
        let Some(h) = current.as_mut() else { continue };

        let mut chars = raw.chars();
        match chars.next() {
            Some('+') => h.lines.push(('+', chars.as_str().to_string())),
            Some('-') => h.lines.push(('-', chars.as_str().to_string())),
            Some(' ') => h.lines.push((' ', chars.as_str().to_string())),
            None => h.lines.push((' ', String::new())),
            Some(_) => {
                // Tolerate context lines that lost their leading space.
                h.lines.push((' ', raw.to_string()));
            }
        }
    }
    if let Some(h) = current.take() {
        hunks.push(h);
    }
    Ok(hunks)
}

fn parse_hunk_header(line: &str) -> Result<usize> {
    // @@ -12,7 +12,9 @@ optional trailing context
    let body = line
        .trim_start_matches('@')
        .trim_start()
        .split("@@")
        .next()
        .unwrap_or("");
    let old = body
        .split_whitespace()
        .find(|t| t.starts_with('-'))
        .ok_or_else(|| CoreError::invalid(format!("malformed hunk header: {line}")))?;
    let num = old.trim_start_matches('-').split(',').next().unwrap_or("0");
    num.parse::<usize>()
        .map_err(|_| CoreError::invalid(format!("malformed hunk header: {line}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_replace() {
        let out = replace("a\nb\nc\n", "b", "B", Some(1), "f.txt").unwrap();
        assert_eq!(out.content, "a\nB\nc\n");
        assert_eq!(out.strategy, MatchStrategy::Exact);
    }

    #[test]
    fn wrong_count_is_refused() {
        let err = replace("x\nx\n", "x", "y", Some(1), "f.txt").unwrap_err();
        assert_eq!(err.kind(), "refused");
    }

    #[test]
    fn reindent_matches_shifted_block() {
        let file = "class A:\n    def f(self):\n        return 1\n";
        // Model forgot the 4-space class indent.
        let needle = "def f(self):\n    return 1";
        let out = replace(file, needle, "def f(self):\n    return 2", Some(1), "a.py").unwrap();
        assert_eq!(out.strategy, MatchStrategy::Reindented);
        assert!(out.content.contains("        return 2"));
    }

    #[test]
    fn patch_applies_with_drift() {
        let original = "h1\nh2\nh3\nalpha\nbeta\ngamma\n";
        // Header claims line 1 but the block really starts at line 4.
        let patch = "@@ -1,3 +1,3 @@\n alpha\n-beta\n+BETA\n gamma\n";
        let out = apply_unified(original, patch, 20).unwrap();
        assert!(out.contains("BETA"));
        assert!(out.contains("h1"));
    }

    #[test]
    fn whitespace_tolerant_match_leaves_the_rest_of_the_file_alone() {
        // The search has no trailing space; the file does. Only the matched
        // lines may change: line 1 keeps its trailing spaces.
        let out = replace("keep  \nb \nc\n", "b\nc", "B\nC", Some(1), "f.txt").unwrap();
        assert_eq!(out.strategy, MatchStrategy::TrailingWhitespace);
        assert_eq!(out.content, "keep  \nB\nC\n");
    }

    #[test]
    fn crlf_file_stays_crlf() {
        // A multi-line LF search cannot match a CRLF file exactly, so this
        // goes through the whitespace-tolerant path — which used to return
        // the whole file converted to LF.
        let original = "one\r\ntwo\r\nthree\r\nfour\r\n";
        let out = replace(original, "two\nthree", "TWO\nTHREE", Some(1), "f.txt").unwrap();
        assert_eq!(out.content, "one\r\nTWO\r\nTHREE\r\nfour\r\n");

        let exact = replace(original, "four", "4\n5", Some(1), "f.txt").unwrap();
        assert_eq!(exact.content, "one\r\ntwo\r\nthree\r\n4\r\n5\r\n");

        let reindented = replace("x\r\n    a\r\n    b\r\ny\r\n", "a\nb", "A\nB", Some(1), "f.txt");
        assert_eq!(reindented.unwrap().content, "x\r\n    A\r\n    B\r\ny\r\n");
    }

    #[test]
    fn patch_keeps_crlf_and_the_files_own_context_lines() {
        let original = "a\r\nb\r\nc\r\n";
        let patch = "@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n";
        assert_eq!(apply_unified(original, patch, 5).unwrap(), "a\r\nB\r\nc\r\n");
    }

    #[test]
    fn trailing_newline_is_not_a_context_line() {
        // The hunk sits mid-file; what follows `gamma` is `delta`, not a blank.
        let original = "alpha\nbeta\ngamma\ndelta\n";
        let patch = "@@ -1,3 +1,3 @@\n alpha\n-beta\n+BETA\n gamma\n";
        let out = apply_unified(original, patch, 20).unwrap();
        assert_eq!(out, "alpha\nBETA\ngamma\ndelta\n");
    }

    #[test]
    fn deliberate_blank_context_line_still_counts() {
        let original = "a\n\nb\n";
        let patch = "@@ -1,2 +1,2 @@\n-a\n+A\n \n";
        let out = apply_unified(original, patch, 20).unwrap();
        assert_eq!(out, "A\n\nb\n");
    }

    #[test]
    fn typographic_quotes_and_dashes_match_their_ascii_forms() {
        // The file has what a word processor writes; the model writes ASCII.
        let file = "a\nprint(\u{201C}hi\u{201D}) \u{2014} it\u{2019}s ok\nb\n";
        let out = replace(file, "print(\"hi\") - it's ok", "print(\"bye\")", Some(1), "f.py").unwrap();
        assert_eq!(out.strategy, MatchStrategy::Typographic);
        assert_eq!(out.content, "a\nprint(\"bye\")\nb\n");
    }

    #[test]
    fn a_typographic_match_is_cut_on_whole_characters() {
        // A curly quote is three bytes in the file and one in the folded
        // copy: the cut after it must fall after all three.
        let mid = replace("say \u{201C}hi\u{201D} now\n", "\"hi\"", "'yo'", Some(1), "f.txt").unwrap();
        assert_eq!(mid.content, "say 'yo' now\n");
        let end = replace("x = \u{2018}a\u{2019}\u{00A0}+ 1\n", "'a' + 1", "'b' + 2", Some(1), "f.txt").unwrap();
        assert_eq!(end.strategy, MatchStrategy::Typographic);
        assert_eq!(end.content, "x = 'b' + 2\n");
    }

    #[test]
    fn a_typographic_match_keeps_crlf() {
        let out = replace("one\r\nit\u{2019}s\r\nthree\r\n", "it's\nthree", "IT\nTHREE", Some(1), "f.txt").unwrap();
        assert_eq!(out.strategy, MatchStrategy::Typographic);
        assert_eq!(out.content, "one\r\nIT\r\nTHREE\r\n");
    }

    #[test]
    fn a_miss_shows_the_closest_lines_numbered_as_fs_read_does() {
        let file = "fn main() {\n    let total = items.len();\n    println!(\"{}\", total);\n}\n";
        // One line of three is wrong: `count` for `total`.
        let needle = "    let total = items.len();\n    println!(\"{}\", count);\n}";
        let err = replace(file, needle, "x", Some(1), "f.rs").unwrap_err();
        assert_eq!(err.kind(), "not_found");
        let message = err.to_string();
        assert!(message.contains("\n    2      let total = items.len();"), "{message}");
        assert!(message.contains("\n    3      println!(\"{}\", total);"), "{message}");
        assert!(message.contains("\n    4  }"), "{message}");
        assert!(!message.contains("fn main"), "only the lines it meant: {message}");
    }

    #[test]
    fn a_miss_says_when_the_edit_is_already_there() {
        let file = "a\nlet greeting = \"hello, world\";\nb\n";
        let err = replace(file, "let greeting = \"hello\";", "let greeting = \"hello, world\";", Some(1), "f.js").unwrap_err();
        assert!(err.to_string().contains("already in the file"), "{err}");
    }

    #[test]
    fn a_miss_with_nothing_alike_says_to_re_read() {
        let message = replace("alpha\nbeta\n", "gamma delta", "x", Some(1), "f.txt").unwrap_err().to_string();
        assert!(message.contains("re-read it first"), "{message}");
        assert!(!message.contains("closest lines"), "{message}");
    }

    #[test]
    fn a_hint_stays_short_however_long_the_search() {
        let long = "x".repeat(300);
        let file: String = (1..=40).map(|i| format!("line number {i} {long}\n")).collect();
        // Every line right but the first, so the vote is clear.
        let mut needle: String = (1..=30).map(|i| format!("line number {i} {long}\n")).collect();
        needle.replace_range(0..4, "LINE");
        let message = replace(&file, &needle, "y", Some(1), "f.txt").unwrap_err().to_string();
        assert_eq!(message.lines().filter(|l| l.contains("line number")).count(), HINT_LINES);
        assert!(message.contains('…'));
        assert!(message.len() < HINT_LINES * (HINT_LINE_CHARS + 20) + 400);
    }

    /// The keys are the contract with `FsEditTool`; snake_case here was the
    /// "+0/-0 lines" bug.
    #[test]
    fn replace_reports_line_counts_in_camel_case() {
        let payload = serde_json::json!({
            "content": "a\nb\nc\n",
            "search": "b\n",
            "replace": "B1\nB2\n",
            "path": "f.txt",
        });
        let out: serde_json::Value = serde_json::from_str(&call("replace", &payload.to_string()).unwrap()).unwrap();
        assert_eq!(out["addedLines"], 2);
        assert_eq!(out["removedLines"], 1);
        assert_eq!(out["replacements"], 1);
        assert_eq!(out["strategy"], "exact");
        assert!(out["diff"].as_str().unwrap().contains("+B2"));
    }
}

/* -------------------------------------------------------------------------
 * ffi dispatcher
 * ---------------------------------------------------------------------- */

pub fn call(op: &str, payload: &str) -> Result<String> {
    use serde_json::{json, Value};

    let v: Value = if payload.trim().is_empty() {
        json!({})
    } else {
        serde_json::from_str(payload)?
    };
    let field = |k: &str| -> Result<String> {
        v.get(k)
            .and_then(Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| CoreError::invalid(format!("missing string field '{k}'")))
    };
    let opt = |k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_string();

    let out = match op {
        "unified" => json!({
            "diff": unified(
                &opt("path"),
                &field("old")?,
                &field("new")?,
                v.get("context").and_then(Value::as_u64).unwrap_or(3) as usize,
            )
        }),
        "stats" => {
            let st = stats(&field("old")?, &field("new")?);
            json!({ "added": st.added, "removed": st.removed })
        }
        "replace" => {
            let replace_all = v
                .get("replaceAll")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            let expected = if replace_all {
                None
            } else {
                Some(
                    v.get("expectedCount")
                        .and_then(Value::as_u64)
                        .unwrap_or(1) as usize,
                )
            };
            let outcome = replace(
                &field("content")?,
                &field("search")?,
                &opt("replace"),
                expected,
                &opt("path"),
            )?;
            serde_json::to_value(&outcome)?
        }
        "apply" => {
            let content = apply_unified(
                &field("content")?,
                &field("patch")?,
                v.get("drift").and_then(Value::as_u64).unwrap_or(40) as usize,
            )?;
            let st = stats(&field("content")?, &content);
            json!({ "content": content, "added": st.added, "removed": st.removed })
        }
        other => return Err(CoreError::invalid(format!("unknown diff op '{other}'"))),
    };
    Ok(out.to_string())
}
