#!/usr/bin/env python3
"""Find accidental nested block comments.

Kotlin and Rust both *nest* /* */ comments. A stray "/*" inside a comment --
which happens naturally when prose contains a glob like /system/bin/* or a
path like foo/*.so -- silently swallows everything up to the next "*/", which
may be hundreds of lines later and inside a string literal.

This is a lexer-level bug that no amount of reading the code reveals, so it
gets its own checker. Run standalone or via tools/audit.py.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXTS = {".kt", ".kts", ".rs"}
SKIP_DIRS = {"build", ".gradle", ".git", "target", ".idea"}


def scan(text: str) -> list[tuple[int, int, int]]:
    """Return [(line, col, depth)] for every nested comment opener."""
    hits: list[tuple[int, int, int]] = []
    depth = 0           # block comment nesting depth
    in_str = False      # inside "..."
    in_char = False     # inside '...' (Rust char / lifetime handled loosely)
    in_raw = False      # inside Kotlin """...""" or Rust r"..."
    i = 0
    line = 1
    col = 1
    n = len(text)

    def advance(k: int) -> None:
        nonlocal i, line, col
        for _ in range(k):
            if i < n and text[i] == "\n":
                line += 1
                col = 1
            else:
                col += 1
            i += 1

    while i < n:
        c = text[i]
        two = text[i : i + 2]
        three = text[i : i + 3]

        if depth > 0:
            if two == "/*":
                hits.append((line, col, depth + 1))
                depth += 1
                advance(2)
                continue
            if two == "*/":
                depth -= 1
                advance(2)
                continue
            advance(1)
            continue

        if in_raw:
            if three == '"""':
                in_raw = False
                advance(3)
                continue
            advance(1)
            continue

        if in_str:
            if c == "\\":
                advance(2)
                continue
            if c == '"':
                in_str = False
            advance(1)
            continue

        if in_char:
            if c == "\\":
                advance(2)
                continue
            if c == "'":
                in_char = False
            advance(1)
            continue

        # not inside anything
        if three == '"""':
            in_raw = True
            advance(3)
            continue
        if two == 'r"' or two == 'r#':
            # Rust raw string: skip to the matching terminator.
            j = i + 1
            hashes = 0
            while j < n and text[j] == "#":
                hashes += 1
                j += 1
            if j < n and text[j] == '"':
                term = '"' + "#" * hashes
                end = text.find(term, j + 1)
                advance((end + len(term) - i) if end != -1 else (n - i))
                continue
        if c == '"':
            in_str = True
            advance(1)
            continue
        if c == "'":
            in_char = True
            advance(1)
            continue
        if two == "//":
            nl = text.find("\n", i)
            advance((nl - i) if nl != -1 else (n - i))
            continue
        if two == "/*":
            depth = 1
            advance(2)
            continue
        advance(1)

    return hits


def main() -> int:
    bad = 0
    checked = 0
    for path in sorted(ROOT.rglob("*")):
        if path.suffix not in EXTS or not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        checked += 1
        rel = path.relative_to(ROOT)
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            print(f"warn: {rel}: not utf-8")
            continue
        lines = text.splitlines()
        for ln, col, depth in scan(text):
            bad += 1
            src = lines[ln - 1] if ln - 1 < len(lines) else ""
            print(f"error: {rel}:{ln}:{col}: nested block comment (depth {depth})")
            print(f"       {src.strip()}")
    print(f"\nchecked {checked} files, {bad} nested comment opener(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
