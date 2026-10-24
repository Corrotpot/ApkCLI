//! Workspace search, listing and outlining.
//!
//! This is ripgrep's engine linked in as a library rather than a `rg` binary
//! shelled out to. Two reasons:
//!
//!   * On the `play` flavour we cannot execute a downloaded `rg` at all, and
//!     bundling one per ABI costs ~4 MB each. Linking the engine costs ~900 KB
//!     once and works on both flavours.
//!   * No process spawn, no pipe, no output parsing — a grep over a 50k-file
//!     tree returns structured results directly.

use crate::error::{CoreError, Result};
use grep_matcher::Matcher;
use grep_regex::RegexMatcherBuilder;
use grep_searcher::{Searcher, SearcherBuilder, Sink, SinkContext, SinkContextKind, SinkMatch};
use ignore::{overrides::OverrideBuilder, WalkBuilder};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};

const MAX_FILE_BYTES: u64 = 8 * 1024 * 1024;
const MAX_LINE_CHARS: usize = 400;

pub fn call(op: &str, payload: &str) -> Result<String> {
    let v: Value = if payload.trim().is_empty() {
        json!({})
    } else {
        serde_json::from_str(payload)?
    };
    let out = match op {
        "grep" => grep(&v)?,
        "tree" => tree(&v)?,
        "glob" => glob(&v)?,
        "outline" => outline(&v)?,
        "read" => read_file(&v)?,
        other => return Err(CoreError::invalid(format!("unknown search op '{other}'"))),
    };
    Ok(out.to_string())
}

fn str_field(v: &Value, k: &str) -> Result<String> {
    v.get(k)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| CoreError::invalid(format!("missing string field '{k}'")))
}

fn str_list(v: &Value, k: &str) -> Vec<String> {
    v.get(k)
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(Value::as_str)
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}

fn walker(root: &Path, v: &Value) -> Result<WalkBuilder> {
    let mut wb = WalkBuilder::new(root);
    let respect_ignore = v
        .get("respectIgnore")
        .and_then(Value::as_bool)
        .unwrap_or(true);
    wb.hidden(!v.get("includeHidden").and_then(Value::as_bool).unwrap_or(false))
        .git_ignore(respect_ignore)
        .git_global(respect_ignore)
        .git_exclude(respect_ignore)
        .ignore(respect_ignore)
        .parents(respect_ignore)
        .follow_links(false)
        .max_depth(
            v.get("maxDepth")
                .and_then(Value::as_u64)
                .map(|d| d as usize),
        );

    let includes = str_list(v, "include");
    let excludes = str_list(v, "exclude");
    if !includes.is_empty() || !excludes.is_empty() {
        let mut ob = OverrideBuilder::new(root);
        for g in &includes {
            ob.add(g)
                .map_err(|e| CoreError::invalid(format!("bad include glob '{g}': {e}")))?;
        }
        for g in &excludes {
            ob.add(&format!("!{g}"))
                .map_err(|e| CoreError::invalid(format!("bad exclude glob '{g}': {e}")))?;
        }
        let ov = ob
            .build()
            .map_err(|e| CoreError::invalid(format!("glob set: {e}")))?;
        wb.overrides(ov);
    }
    // Always skip these; they are never what anyone is looking for and they
    // dominate walk time on real projects.
    wb.filter_entry(|e| {
        let name = e.file_name().to_string_lossy();
        !matches!(
            name.as_ref(),
            ".git" | "node_modules" | "target" | "build" | ".gradle" | ".kotlin"
                | ".cxx" | "__pycache__" | ".venv" | "venv" | ".idea" | "Pods"
                | ".dart_tool" | ".next" | "dist" | ".terraform"
        )
    });
    Ok(wb)
}

/* ------------------------------------------------------------------- grep */

struct JsonSink<'a> {
    path: &'a str,
    matcher: &'a grep_regex::RegexMatcher,
    hits: Vec<Value>,
    pending_context: Vec<Value>,
    limit: usize,
    total: usize,
}

fn clip(bytes: &[u8]) -> String {
    let text = String::from_utf8_lossy(bytes);
    let trimmed = text.trim_end_matches(['\n', '\r']);
    if trimmed.chars().count() > MAX_LINE_CHARS {
        let head: String = trimmed.chars().take(MAX_LINE_CHARS).collect();
        format!("{head}…")
    } else {
        trimmed.to_string()
    }
}

impl<'a> Sink for JsonSink<'a> {
    type Error = std::io::Error;

    fn matched(&mut self, _s: &Searcher, m: &SinkMatch<'_>) -> std::io::Result<bool> {
        self.total += 1;
        if self.hits.len() >= self.limit {
            return Ok(false);
        }
        // Column of the first match within the line, for editor navigation.
        let mut column: Option<u64> = None;
        let _ = self.matcher.find_at(m.bytes(), 0).map(|found| {
            column = found.map(|mm| mm.start() as u64 + 1);
        });

        self.hits.push(json!({
            "path": self.path,
            "line": m.line_number().unwrap_or(0),
            "column": column,
            "text": clip(m.bytes()),
            "before": std::mem::take(&mut self.pending_context),
            "after": Value::Array(vec![]),
        }));
        Ok(true)
    }

    fn context(&mut self, _s: &Searcher, c: &SinkContext<'_>) -> std::io::Result<bool> {
        let entry = json!({ "line": c.line_number().unwrap_or(0), "text": clip(c.bytes()) });
        match c.kind() {
            SinkContextKind::Before => self.pending_context.push(entry),
            SinkContextKind::After => {
                if let Some(last) = self.hits.last_mut() {
                    if let Some(arr) = last.get_mut("after").and_then(Value::as_array_mut) {
                        arr.push(entry);
                    }
                }
            }
            SinkContextKind::Other => {}
        }
        Ok(true)
    }

    fn context_break(&mut self, _s: &Searcher) -> std::io::Result<bool> {
        self.pending_context.clear();
        Ok(true)
    }
}

fn grep(v: &Value) -> Result<Value> {
    let pattern = str_field(v, "query")?;
    let root = PathBuf::from(str_field(v, "path")?);
    if !root.exists() {
        return Err(CoreError::not_found(format!("{}", root.display())));
    }

    let literal = v.get("literal").and_then(Value::as_bool).unwrap_or(false);
    let case_sensitive = v
        .get("caseSensitive")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let word = v.get("wholeWord").and_then(Value::as_bool).unwrap_or(false);
    let multiline = v.get("multiline").and_then(Value::as_bool).unwrap_or(false);
    let context_lines = v.get("contextLines").and_then(Value::as_u64).unwrap_or(2) as usize;
    let max_results = v
        .get("maxResults")
        .and_then(Value::as_u64)
        .unwrap_or(100)
        .min(2000) as usize;
    let max_per_file = v
        .get("maxPerFile")
        .and_then(Value::as_u64)
        .unwrap_or(40)
        .min(500) as usize;

    let effective = if literal {
        regex_escape(&pattern)
    } else {
        pattern.clone()
    };

    let mut builder = RegexMatcherBuilder::new();
    builder
        .case_insensitive(!case_sensitive)
        .case_smart(!case_sensitive)
        .word(word)
        .multi_line(multiline)
        .dot_matches_new_line(multiline);
    // A line terminator promises the engine that no match contains one: that
    // is what makes line-at-a-time search fast, and it is also why `\n` in a
    // pattern was rejected even with `multiline` on. ripgrep sets it only for
    // line-oriented searches, and so does this.
    if !multiline {
        builder.line_terminator(Some(b'\n'));
    }
    let matcher = builder
        .build(&effective)
        .map_err(|e| CoreError::invalid(format!("bad pattern: {e}")))?;

    let mut searcher: Searcher = SearcherBuilder::new()
        .line_number(true)
        .multi_line(multiline)
        .before_context(context_lines)
        .after_context(context_lines)
        .build();

    let mut all: Vec<Value> = Vec::new();
    let mut files_scanned = 0usize;
    let mut files_with_hits = 0usize;
    let mut truncated = false;

    for entry in walker(&root, v)?.build() {
        if all.len() >= max_results {
            truncated = true;
            break;
        }
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        if !entry.file_type().map(|t| t.is_file()).unwrap_or(false) {
            continue;
        }
        if entry.metadata().map(|m| m.len()).unwrap_or(0) > MAX_FILE_BYTES {
            continue;
        }
        files_scanned += 1;

        let rel = entry
            .path()
            .strip_prefix(&root)
            .unwrap_or(entry.path())
            .to_string_lossy()
            .replace('\\', "/");

        let mut sink = JsonSink {
            path: &rel,
            matcher: &matcher,
            hits: Vec::new(),
            pending_context: Vec::new(),
            limit: max_per_file.min(max_results - all.len()),
            total: 0,
        };
        if searcher.search_path(&matcher, entry.path(), &mut sink).is_err() {
            continue; // binary / unreadable, skip quietly
        }
        if !sink.hits.is_empty() {
            files_with_hits += 1;
            if sink.total > sink.hits.len() {
                truncated = true;
            }
            all.append(&mut sink.hits);
        }
    }

    Ok(json!({
        "matches": all,
        "filesScanned": files_scanned,
        "filesWithMatches": files_with_hits,
        "truncated": truncated,
    }))
}

fn regex_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for c in s.chars() {
        if "\\.+*?()|[]{}^$#&-~".contains(c) {
            out.push('\\');
        }
        out.push(c);
    }
    out
}

/* ------------------------------------------------------------------- tree */

fn tree(v: &Value) -> Result<Value> {
    let root = PathBuf::from(str_field(v, "path")?);
    let max_entries = v
        .get("maxEntries")
        .and_then(Value::as_u64)
        .unwrap_or(2000)
        .min(20_000) as usize;
    // Filtered here rather than by the caller, so files do not use up
    // `maxEntries` on the way to a list that will not show them.
    let dirs_only = v.get("dirsOnly").and_then(Value::as_bool).unwrap_or(false);

    let mut entries: Vec<Value> = Vec::new();
    let mut truncated = false;
    for entry in walker(&root, v)?.build() {
        let Ok(entry) = entry else { continue };
        let path = entry.path();
        if path == root {
            continue;
        }
        if dirs_only && !entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
            continue;
        }
        if entries.len() >= max_entries {
            truncated = true;
            break;
        }
        let rel = path
            .strip_prefix(&root)
            .unwrap_or(path)
            .to_string_lossy()
            .replace('\\', "/");
        let md = entry.metadata().ok();
        entries.push(json!({
            "path": rel,
            "name": entry.file_name().to_string_lossy(),
            "dir": entry.file_type().map(|t| t.is_dir()).unwrap_or(false),
            "depth": entry.depth(),
            "size": md.as_ref().map(|m| m.len()).unwrap_or(0),
            "mtime": md.as_ref()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0),
        }));
    }
    // Tree order: every entry directly under its own directory, directories
    // before files at each level. Sorting all directories ahead of all files,
    // as this used to, printed each file far from the directory holding it.
    entries.sort_by_cached_key(tree_key);
    Ok(json!({ "entries": entries, "truncated": truncated }))
}

/// Sort key for [tree]: one `(is_file, name)` per path component, so a
/// directory sorts right before its contents and ahead of its sibling files.
fn tree_key(entry: &Value) -> Vec<(bool, String)> {
    let path = entry["path"].as_str().unwrap_or("");
    let is_dir = entry["dir"].as_bool().unwrap_or(false);
    let parts: Vec<&str> = path.split('/').collect();
    let last = parts.len() - 1;
    parts
        .iter()
        .enumerate()
        .map(|(i, part)| (i == last && !is_dir, part.to_string()))
        .collect()
}

fn glob(v: &Value) -> Result<Value> {
    let root = PathBuf::from(str_field(v, "path")?);
    let pattern = str_field(v, "pattern")?;
    let limit = v.get("limit").and_then(Value::as_u64).unwrap_or(500) as usize;

    let gs = globset::GlobBuilder::new(&pattern)
        .literal_separator(false)
        .build()
        .map_err(|e| CoreError::invalid(format!("bad glob '{pattern}': {e}")))?
        .compile_matcher();

    let mut out: Vec<Value> = Vec::new();
    let mut truncated = false;
    for entry in walker(&root, v)?.build() {
        if out.len() >= limit {
            truncated = true;
            break;
        }
        let Ok(entry) = entry else { continue };
        if !entry.file_type().map(|t| t.is_file()).unwrap_or(false) {
            continue;
        }
        let rel = entry
            .path()
            .strip_prefix(&root)
            .unwrap_or(entry.path())
            .to_string_lossy()
            .replace('\\', "/");
        if gs.is_match(rel.as_str()) || gs.is_match(entry.file_name().to_string_lossy().as_ref()) {
            let md = entry.metadata().ok();
            out.push(json!({
                "path": rel,
                "size": md.as_ref().map(|m| m.len()).unwrap_or(0),
                "mtime": md.as_ref()
                    .and_then(|m| m.modified().ok())
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_millis() as i64)
                    .unwrap_or(0),
            }));
        }
    }
    // Most-recently-modified first: when a model globs for "*.kt" it almost
    // always wants the files someone is currently working on.
    out.sort_by_key(|e| std::cmp::Reverse(e["mtime"].as_i64().unwrap_or(0)));
    Ok(json!({ "files": out, "truncated": truncated }))
}

/* ---------------------------------------------------------------- reading */

fn read_file(v: &Value) -> Result<Value> {
    let path = PathBuf::from(str_field(v, "path")?);
    let offset = v.get("offset").and_then(Value::as_u64).unwrap_or(0) as usize;
    let limit = v.get("limit").and_then(Value::as_u64).unwrap_or(2000) as usize;

    let md = std::fs::metadata(&path)?;
    if md.is_dir() {
        return Err(CoreError::invalid(format!(
            "{} is a directory — use fs_list",
            path.display()
        )));
    }
    if md.len() > MAX_FILE_BYTES {
        return Err(CoreError::refused(format!(
            "{} is {} bytes (limit {}). Use grep or read a byte range.",
            path.display(),
            md.len(),
            MAX_FILE_BYTES
        )));
    }
    let bytes = std::fs::read(&path)?;
    if is_binary(&bytes) {
        return Ok(json!({
            "path": path.to_string_lossy(),
            "binary": true,
            "size": md.len(),
            "lines": Value::Array(vec![]),
        }));
    }
    let text = String::from_utf8_lossy(&bytes);
    let all: Vec<&str> = text.split('\n').collect();
    let total = all.len();
    let end = (offset + limit).min(total);
    let slice: Vec<Value> = if offset >= total {
        vec![]
    } else {
        all[offset..end]
            .iter()
            .enumerate()
            .map(|(k, l)| json!({ "n": offset + k + 1, "text": l }))
            .collect()
    };
    Ok(json!({
        "path": path.to_string_lossy(),
        "binary": false,
        "size": md.len(),
        "totalLines": total,
        "from": offset + 1,
        "to": end,
        "eof": end >= total,
        "lines": slice,
    }))
}

fn is_binary(bytes: &[u8]) -> bool {
    let probe = &bytes[..bytes.len().min(8192)];
    memchr::memchr(0, probe).is_some()
}

/* --------------------------------------------------------------- outline */

/// Symbol outline.
///
/// Regex-based rather than tree-sitter. That is a deliberate trade: grammars
/// cost ~400 KB each, need a per-language build step, and the model does not
/// need a precise AST — it needs "where is the function called `foo`". This
/// covers the fifteen languages that actually show up, in 900 bytes of tables.
/// `docs/ARCHITECTURE.md` records what switching to tree-sitter would buy.
fn outline(v: &Value) -> Result<Value> {
    let path = PathBuf::from(str_field(v, "path")?);
    let bytes = std::fs::read(&path)?;
    if is_binary(&bytes) {
        return Ok(json!({ "symbols": [], "binary": true }));
    }
    let text = String::from_utf8_lossy(&bytes);
    let ext = path
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase())
        .unwrap_or_default();

    let rules: &[(&str, &str)] = match ext.as_str() {
        "rs" => &[
            ("fn", r"^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:const\s+)?(?:unsafe\s+)?(?:extern\s+\x22[^\x22]*\x22\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("struct", r"^\s*(?:pub(?:\([^)]*\))?\s+)?struct\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("enum", r"^\s*(?:pub(?:\([^)]*\))?\s+)?enum\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("trait", r"^\s*(?:pub(?:\([^)]*\))?\s+)?trait\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("impl", r"^\s*impl(?:\s*<[^>]*>)?\s+(?:[A-Za-z0-9_:<>]+\s+for\s+)?([A-Za-z_][A-Za-z0-9_]*)"),
            ("mod", r"^\s*(?:pub\s+)?mod\s+([A-Za-z_][A-Za-z0-9_]*)"),
        ],
        "kt" | "kts" => &[
            ("fun", r"^\s*(?:(?:public|private|internal|protected|override|open|suspend|inline|operator|tailrec|external|abstract|final)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>]+\.)?([A-Za-z_][A-Za-z0-9_]*)"),
            ("class", r"^\s*(?:(?:public|private|internal|protected|open|abstract|sealed|data|enum|annotation|value|inner|final)\s+)*class\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("object", r"^\s*(?:(?:public|private|internal|companion)\s+)*object\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("interface", r"^\s*(?:(?:public|private|internal|sealed|fun)\s+)*interface\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("val", r"^\s*(?:(?:public|private|internal|const|lateinit)\s+)*va[lr]\s+([A-Za-z_][A-Za-z0-9_]*)\s*[:=]"),
        ],
        "java" => &[
            ("class", r"^\s*(?:(?:public|private|protected|static|final|abstract|sealed)\s+)*class\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("interface", r"^\s*(?:(?:public|private|protected)\s+)*interface\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("enum", r"^\s*(?:(?:public|private|protected)\s+)*enum\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("method", r"^\s{2,}(?:(?:public|private|protected|static|final|synchronized|native|abstract|default)\s+)+[A-Za-z0-9_<>\[\],.\s]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("),
        ],
        "py" | "pyi" => &[
            ("def", r"^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("class", r"^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)"),
        ],
        "js" | "jsx" | "mjs" | "cjs" | "ts" | "tsx" => &[
            ("function", r"^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][A-Za-z0-9_$]*)"),
            ("class", r"^\s*(?:export\s+)?(?:default\s+)?(?:abstract\s+)?class\s+([A-Za-z_$][A-Za-z0-9_$]*)"),
            ("const", r"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][A-Za-z0-9_$]*)\s*=>"),
            ("interface", r"^\s*(?:export\s+)?interface\s+([A-Za-z_$][A-Za-z0-9_$]*)"),
            ("type", r"^\s*(?:export\s+)?type\s+([A-Za-z_$][A-Za-z0-9_$]*)"),
        ],
        "go" => &[
            ("func", r"^func\s+(?:\([^)]*\)\s*)?([A-Za-z_][A-Za-z0-9_]*)"),
            ("type", r"^type\s+([A-Za-z_][A-Za-z0-9_]*)"),
        ],
        "c" | "h" | "cc" | "cpp" | "cxx" | "hpp" | "hh" => &[
            ("function", r"^[A-Za-z_][A-Za-z0-9_\s\*&:<>,]*\s+\*?([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*\{"),
            ("class", r"^\s*(?:class|struct)\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("define", r"^\s*#\s*define\s+([A-Za-z_][A-Za-z0-9_]*)"),
        ],
        "swift" => &[
            ("func", r"^\s*(?:(?:public|private|internal|fileprivate|open|static|class|final|override|mutating)\s+)*func\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("type", r"^\s*(?:(?:public|private|internal|final)\s+)*(?:class|struct|enum|protocol|extension)\s+([A-Za-z_][A-Za-z0-9_]*)"),
        ],
        "rb" => &[
            ("def", r"^\s*def\s+([A-Za-z_][A-Za-z0-9_?!=]*)"),
            ("class", r"^\s*(?:class|module)\s+([A-Za-z_][A-Za-z0-9_:]*)"),
        ],
        "php" => &[
            ("function", r"^\s*(?:(?:public|private|protected|static|final|abstract)\s+)*function\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("class", r"^\s*(?:(?:final|abstract)\s+)*(?:class|interface|trait)\s+([A-Za-z_][A-Za-z0-9_]*)"),
        ],
        "sh" | "bash" | "zsh" => &[
            ("function", r"^\s*(?:function\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{"),
        ],
        "cs" => &[
            ("class", r"^\s*(?:(?:public|private|protected|internal|static|sealed|abstract|partial|record)\s+)*(?:class|struct|interface|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("method", r"^\s{2,}(?:(?:public|private|protected|internal|static|virtual|override|async|sealed|extern|partial)\s+)+[A-Za-z0-9_<>\[\],.\s\?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("),
        ],
        "dart" => &[
            ("class", r"^\s*(?:abstract\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("function", r"^\s*(?:[A-Za-z0-9_<>\[\],\?\s]+\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*(?:async\s*)?\{"),
        ],
        "lua" => &[("function", r"^\s*(?:local\s+)?function\s+([A-Za-z_][A-Za-z0-9_.:]*)")],
        "sql" => &[
            ("table", r"(?i)^\s*create\s+(?:virtual\s+)?table(?:\s+if\s+not\s+exists)?\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("view", r"(?i)^\s*create\s+view(?:\s+if\s+not\s+exists)?\s+([A-Za-z_][A-Za-z0-9_]*)"),
            ("index", r"(?i)^\s*create\s+(?:unique\s+)?index(?:\s+if\s+not\s+exists)?\s+([A-Za-z_][A-Za-z0-9_]*)"),
        ],
        "md" | "markdown" => &[("heading", r"^(#{1,6})\s+(.+)$")],
        _ => &[],
    };

    if rules.is_empty() {
        return Ok(json!({ "symbols": [], "unsupportedExtension": ext }));
    }

    let compiled: Vec<(&str, regex::Regex)> = rules
        .iter()
        .filter_map(|(kind, pat)| regex::Regex::new(pat).ok().map(|r| (*kind, r)))
        .collect();

    let mut symbols: Vec<Value> = Vec::new();
    for (idx, line) in text.split('\n').enumerate() {
        if line.len() > 500 {
            continue;
        }
        for (kind, re) in &compiled {
            if let Some(caps) = re.captures(line) {
                let name = caps
                    .get(caps.len().saturating_sub(1))
                    .map(|m| m.as_str().trim().to_string())
                    .unwrap_or_default();
                if name.is_empty() {
                    continue;
                }
                symbols.push(json!({
                    "kind": kind,
                    "name": name,
                    "line": idx + 1,
                    "indent": line.len() - line.trim_start().len(),
                    "signature": line.trim().chars().take(160).collect::<String>(),
                }));
                break;
            }
        }
    }
    Ok(json!({ "symbols": symbols, "language": ext }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outline_finds_rust_items() {
        let dir = std::env::temp_dir().join("apkcli_outline_test");
        std::fs::create_dir_all(&dir).unwrap();
        let f = dir.join("a.rs");
        std::fs::write(&f, "pub fn alpha() {}\nstruct Beta;\npub async fn gamma() {}\n").unwrap();
        let out = outline(&json!({ "path": f.to_string_lossy() })).unwrap();
        let names: Vec<&str> = out["symbols"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| s["name"].as_str().unwrap())
            .collect();
        assert!(names.contains(&"alpha"));
        assert!(names.contains(&"Beta"));
        assert!(names.contains(&"gamma"));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn grep_reports_context() {
        let dir = std::env::temp_dir().join("apkcli_grep_test");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("f.txt"), "one\ntwo\nNEEDLE\nfour\nfive\n").unwrap();
        let out = grep(&json!({
            "path": dir.to_string_lossy(), "query": "NEEDLE", "contextLines": 1
        }))
        .unwrap();
        let m = &out["matches"][0];
        assert_eq!(m["line"], 3);
        assert_eq!(m["before"][0]["text"], "two");
        assert_eq!(m["after"][0]["text"], "four");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn multiline_pattern_spans_lines() {
        let dir = std::env::temp_dir().join("apkcli_multiline_test");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("f.kt"), "fun a() {\n    return 1\n}\nfun b() = 2\n").unwrap();
        let query = r"fun a\(\) \{\n\s+return";
        let out = grep(&json!({
            "path": dir.to_string_lossy(), "query": query, "multiline": true, "contextLines": 0
        }))
        .unwrap();
        let m = &out["matches"][0];
        assert_eq!(m["line"], 1);
        assert!(m["text"].as_str().unwrap().contains("return 1"));
        // Without the flag a `\n` in the pattern is still refused, as before.
        assert!(grep(&json!({ "path": dir.to_string_lossy(), "query": query })).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn tree_lists_each_entry_under_its_directory() {
        let dir = std::env::temp_dir().join("apkcli_tree_test");
        std::fs::remove_dir_all(&dir).ok();
        std::fs::create_dir_all(dir.join("src/util")).unwrap();
        std::fs::create_dir_all(dir.join("docs")).unwrap();
        std::fs::write(dir.join("README.md"), "r").unwrap();
        std::fs::write(dir.join("src/main.kt"), "m").unwrap();
        std::fs::write(dir.join("src/util/a.kt"), "a").unwrap();
        std::fs::write(dir.join("docs/guide.md"), "g").unwrap();

        let out = tree(&json!({ "path": dir.to_string_lossy(), "maxDepth": 4 })).unwrap();
        let paths: Vec<&str> = out["entries"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| e["path"].as_str().unwrap())
            .collect();
        assert_eq!(
            paths,
            vec!["docs", "docs/guide.md", "src", "src/util", "src/util/a.kt", "src/main.kt", "README.md"],
        );

        let dirs = tree(&json!({ "path": dir.to_string_lossy(), "maxDepth": 4, "dirsOnly": true, "maxEntries": 2 }))
            .unwrap();
        let names: Vec<&str> = dirs["entries"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| e["path"].as_str().unwrap())
            .collect();
        // Two directories fit; files did not use up the limit.
        assert_eq!(names.len(), 2);
        assert!(dirs["entries"].as_array().unwrap().iter().all(|e| e["dir"] == true));
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The pattern `symbol_search` builds (SearchTools.kt), with the name
    /// escaped the way `rustRegexEscape` does it. It used to arrive wrapped in
    /// `\Q…\E`, which this engine rejects, so the tool failed on every call.
    #[test]
    fn symbol_search_pattern_compiles_and_matches() {
        let dir = std::env::temp_dir().join("apkcli_symbol_test");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("a.kt"),
            "class Parser {\n    fun parse_args(x: Int) = x\n}\nval y = parse_args(1)\n",
        )
        .unwrap();
        let prefix = concat!(
            r"(?:fun|class|object|interface|def|fn|struct|enum|trait|impl|type|func|",
            r"const|val|var|let|protocol|extension|module|record|public|private|",
            r"static|async)\s+[\w<>\[\],.\s*&:]*\b",
        );
        let out = grep(&json!({
            "path": dir.to_string_lossy(),
            "query": format!("{prefix}parse_args"),
            "caseSensitive": true,
            "contextLines": 0,
        }))
        .unwrap();
        let lines: Vec<u64> = out["matches"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| m["line"].as_u64().unwrap())
            .collect();
        assert_eq!(lines, vec![2], "the definition, not the call on line 4");

        // Escaped meta characters are accepted.
        let escaped = grep(&json!({
            "path": dir.to_string_lossy(),
            "query": format!(r"{prefix}parse\.args\(\)\[\]"),
            "caseSensitive": true,
        }));
        assert!(escaped.is_ok());
        std::fs::remove_dir_all(&dir).ok();
    }
}
