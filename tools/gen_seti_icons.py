#!/usr/bin/env python3
"""VS Code's own file icons for the explorer, tabs and the changes list.

    python tools/gen_seti_icons.py

VS Code's default icon theme, "Seti (Visual Studio Code)", is a font: each
file type is one glyph of seti.woff, drawn in a colour the theme names. There
are no PNGs in the repository to copy; the font *is* the original. This takes
it as it is from microsoft/vscode at a pinned commit:

  extensions/theme-seti/icons/seti.woff               the glyphs
  extensions/theme-seti/icons/vs-seti-icon-theme.json which glyph and colour for
                                                       which name, extension and
                                                       language, dark and light
  extensions/*/package.json                           which extensions and file
                                                       names each built-in language
                                                       claims, for the theme's
                                                       per-language entries

and writes

  app/src/main/res/font/seti.ttf                       the same font, unwrapped from
                                                       WOFF (Android reads TrueType;
                                                       the tables are copied as they
                                                       are, nothing is redrawn)
  app/src/main/kotlin/dev/apkcli/ui/icons/SetiIcons.kt lookup tables
  app/src/main/assets/licenses/seti-ui.txt             the MIT notice

Lookup is VS Code's: the file name, then its extensions from the longest
("spec.ts") to the shortest ("ts"), then the language VS Code would give the
file, then the default glyph. Folders get no icon, as in VS Code with Seti.

Standard library only. Downloads are cached in build/seti-cache.
"""

from __future__ import annotations

import hashlib
import json
import struct
import sys
import urllib.request
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "build" / "seti-cache"
FONT_OUT = ROOT / "app/src/main/res/font/seti.ttf"
KOTLIN_OUT = ROOT / "app/src/main/kotlin/dev/apkcli/ui/icons/SetiIcons.kt"
NOTICE = ROOT / "app/src/main/assets/licenses/seti-ui.txt"

# microsoft/vscode, last commit touching extensions/theme-seti/icons (2026-02-20).
COMMIT = "8801c3fac3b7b8758d88b1813ed0e41b6dab27bf"
RAW = f"https://raw.githubusercontent.com/microsoft/vscode/{COMMIT}/"
API = f"https://api.github.com/repos/microsoft/vscode/contents/extensions?ref={COMMIT}"

LICENCE = """Seti UI - A subtle dark colored UI theme for Atom. (https://github.com/jesseweed/seti-ui)
As shipped in Visual Studio Code: https://github.com/microsoft/vscode/tree/{commit}/extensions/theme-seti

Copyright (c) 2014 Jesse Weed

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
"""


def fetch(url: str, name: str) -> bytes:
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / name
    if cached.exists():
        return cached.read_bytes()
    request = urllib.request.Request(url, headers={"User-Agent": "apkcli-gen-seti"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    cached.write_bytes(data)
    return data


# --------------------------------------------------------------------- WOFF


def woff_to_sfnt(woff: bytes) -> bytes:
    """WOFF 1.0 to the TrueType/OpenType file it wraps: each table inflated
    where it was compressed and copied as it is, with the offset table and
    directory rebuilt and the head checksum adjusted, per the WOFF spec."""
    (signature, flavor, _length, num_tables, _reserved, _total, _major, _minor,
     _meta_off, _meta_len, _meta_orig, _priv_off, _priv_len) = struct.unpack(">4sIIHHIHHIIIII", woff[:44])
    if signature != b"wOFF":
        raise ValueError("not a WOFF 1.0 file")
    tables = []
    for i in range(num_tables):
        tag, offset, comp_len, orig_len, checksum = struct.unpack(">4sIIII", woff[44 + 20 * i: 64 + 20 * i])
        data = woff[offset: offset + comp_len]
        if comp_len < orig_len:
            data = zlib.decompress(data)
        if len(data) != orig_len:
            raise ValueError(f"table {tag!r}: {len(data)} bytes, expected {orig_len}")
        tables.append((tag, checksum, data))
    tables.sort(key=lambda t: t[0])

    power = 1
    selector = 0
    while power * 2 <= num_tables:
        power *= 2
        selector += 1
    search_range = power * 16
    header = struct.pack(">IHHHH", flavor, num_tables, search_range, selector, num_tables * 16 - search_range)

    offset = 12 + 16 * num_tables
    directory = b""
    body = b""
    head_at = None
    for tag, checksum, data in tables:
        if tag == b"head":
            head_at = offset
        directory += struct.pack(">4sIII", tag, checksum, offset, len(data))
        padded = data + b"\0" * ((4 - len(data) % 4) % 4)
        body += padded
        offset += len(padded)
    font = bytearray(header + directory + body)

    if head_at is not None:
        # checkSumAdjustment, at byte 8 of head: zero, sum, then 0xB1B0AFBA - sum.
        font[head_at + 8: head_at + 12] = b"\0\0\0\0"
        total = sum(struct.unpack(f">{len(font) // 4}I", bytes(font))) & 0xFFFFFFFF
        font[head_at + 8: head_at + 12] = struct.pack(">I", (0xB1B0AFBA - total) & 0xFFFFFFFF)
    return bytes(font)


# --------------------------------------------------------------- languages


def builtin_languages() -> dict[str, dict[str, set[str]]]:
    """Language id -> the extensions and file names VS Code's built-in
    extensions give it, all lower case."""
    listing = json.loads(fetch(API, "extensions.json"))
    languages: dict[str, dict[str, set[str]]] = {}
    for entry in listing:
        if entry.get("type") != "dir":
            continue
        name = entry["name"]
        try:
            manifest = json.loads(fetch(f"{RAW}extensions/{name}/package.json", f"pkg-{name}.json"))
        except Exception:  # noqa: BLE001 -- a folder without a manifest has no languages
            continue
        for language in manifest.get("contributes", {}).get("languages", []) or []:
            lang = language.get("id")
            if not lang:
                continue
            slot = languages.setdefault(lang, {"extensions": set(), "filenames": set()})
            for ext in language.get("extensions", []) or []:
                slot["extensions"].add(ext.lower().lstrip("."))
            for filename in language.get("filenames", []) or []:
                slot["filenames"].add(filename.lower())
    return languages


# ------------------------------------------------------------------ tables


def resolve(theme: dict, scope: dict, languages: dict) -> tuple[dict[str, str], dict[str, str], str]:
    """File names and extensions to icon ids for one variant (dark or light),
    with each language's own extensions and names below the theme's."""
    names = {k.lower(): v for k, v in (scope.get("fileNames") or {}).items()}
    exts = {k.lower(): v for k, v in (scope.get("fileExtensions") or {}).items()}
    for lang, icon in (scope.get("languageIds") or {}).items():
        claimed = languages.get(lang)
        if not claimed:
            continue
        for ext in claimed["extensions"]:
            exts.setdefault(ext, icon)
        for filename in claimed["filenames"]:
            names.setdefault(filename, icon)
    return names, exts, scope.get("file") or theme["file"]


def colour(value: str) -> int:
    value = value.lstrip("#")
    if len(value) == 3:
        value = "".join(c * 2 for c in value)
    return 0xFF000000 | int(value[:6], 16)


def kotlin_string(entries: dict[str, int]) -> str:
    """`key=index;…` as Kotlin string literals of at most 2 KB each, joined with +."""
    text = "".join(f"{key}={index};" for key, index in sorted(entries.items()))
    text = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
    chunks = [text[i: i + 2000] for i in range(0, len(text), 2000)] or [""]
    return " +\n        ".join(f"\"{chunk}\"" for chunk in chunks)


def main() -> int:
    theme_bytes = fetch(f"{RAW}extensions/theme-seti/icons/vs-seti-icon-theme.json", "theme.json")
    woff = fetch(f"{RAW}extensions/theme-seti/icons/seti.woff", "seti.woff")
    theme = json.loads(theme_bytes)
    languages = builtin_languages()

    FONT_OUT.parent.mkdir(parents=True, exist_ok=True)
    FONT_OUT.write_bytes(woff_to_sfnt(woff))

    definitions = theme["iconDefinitions"]
    used: dict[str, int] = {}
    glyphs: list[tuple[int, int]] = []

    def index_of(icon: str) -> int:
        if icon not in used:
            definition = definitions[icon]
            char = definition["fontCharacter"].lstrip("\\")
            glyphs.append((int(char, 16), colour(definition.get("fontColor", "#cccccc"))))
            used[icon] = len(glyphs) - 1
        return used[icon]

    dark_names, dark_exts, dark_default = resolve(theme, theme, languages)
    light_names, light_exts, light_default = resolve(theme, theme.get("light") or theme, languages)
    # The light variant lists only what differs; the rest is shared.
    light_names = {**dark_names, **light_names}
    light_exts = {**dark_exts, **light_exts}

    tables = {
        "DARK_NAMES": {k: index_of(v) for k, v in dark_names.items()},
        "DARK_EXTS": {k: index_of(v) for k, v in dark_exts.items()},
        "LIGHT_NAMES": {k: index_of(v) for k, v in light_names.items()},
        "LIGHT_EXTS": {k: index_of(v) for k, v in light_exts.items()},
    }
    dark_default_index = index_of(dark_default)
    light_default_index = index_of(light_default)

    glyph_chars = "".join(f"\\u{code:04x}" for code, _ in glyphs)
    glyph_colours = ", ".join(f"0x{argb:08X}.toInt()" for _, argb in glyphs)

    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_OUT.write_text(
        "// GENERATED by tools/gen_seti_icons.py -- do not edit by hand.\n"
        "//\n"
        f"// VS Code's Seti file icons, microsoft/vscode@{COMMIT[:12]}\n"
        "// extensions/theme-seti: the glyphs are the font in res/font/seti.ttf,\n"
        "// the colours and mappings those of vs-seti-icon-theme.json. Seti UI is\n"
        "// MIT, (c) 2014 Jesse Weed; the notice is assets/licenses/seti-ui.txt.\n"
        "\n"
        "package dev.apkcli.ui.icons\n"
        "\n"
        "/**\n"
        " * Which Seti glyph, in which colour, a file gets: VS Code's own lookup —\n"
        " * the name, then its extensions longest first, then the language VS Code\n"
        " * would give it — with its own tables, dark and light.\n"
        " */\n"
        "object SetiIcons {\n"
        "\n"
        "    /** A glyph of the Seti font and the colour the theme draws it in. */\n"
        "    class Glyph(val char: Char, val argb: Int)\n"
        "\n"
        f"    private const val CHARS = \"{glyph_chars}\"\n"
        "\n"
        f"    private val COLOURS = intArrayOf({glyph_colours})\n"
        "\n"
        + "".join(
            f"    private val {name}: Map<String, Int> by lazy {{ parse(\n        {kotlin_string(entries)},\n    ) }}\n\n"
            for name, entries in tables.items()
        )
        + f"    private const val DARK_DEFAULT = {dark_default_index}\n"
        f"    private const val LIGHT_DEFAULT = {light_default_index}\n"
        "\n"
        "    fun forFile(name: String, light: Boolean): Glyph {\n"
        "        val names = if (light) LIGHT_NAMES else DARK_NAMES\n"
        "        val exts = if (light) LIGHT_EXTS else DARK_EXTS\n"
        "        val lower = name.lowercase()\n"
        "        val index = names[lower]\n"
        "            ?: extensionsOf(lower).firstNotNullOfOrNull { exts[it] }\n"
        "            ?: if (light) LIGHT_DEFAULT else DARK_DEFAULT\n"
        "        return Glyph(CHARS[index], COLOURS[index])\n"
        "    }\n"
        "\n"
        "    /** `a.spec.ts` -> `spec.ts`, `ts`; a leading dot is part of the name (`.gitignore` -> `gitignore`). */\n"
        "    private fun extensionsOf(name: String): Sequence<String> = sequence {\n"
        "        var dot = name.indexOf('.')\n"
        "        while (dot >= 0 && dot < name.length - 1) {\n"
        "            yield(name.substring(dot + 1))\n"
        "            dot = name.indexOf('.', dot + 1)\n"
        "        }\n"
        "    }\n"
        "\n"
        "    private fun parse(table: String): Map<String, Int> {\n"
        "        val map = HashMap<String, Int>(table.length / 8)\n"
        "        for (entry in table.split(';')) {\n"
        "            val eq = entry.lastIndexOf('=')\n"
        "            if (eq > 0) map[entry.substring(0, eq)] = entry.substring(eq + 1).toInt()\n"
        "        }\n"
        "        return map\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    NOTICE.parent.mkdir(parents=True, exist_ok=True)
    NOTICE.write_text(LICENCE.format(commit=COMMIT), encoding="utf-8")

    print(
        f"glyphs {len(glyphs)}; dark: {len(dark_names)} names, {len(dark_exts)} extensions; "
        f"light: {len(light_names)} names, {len(light_exts)} extensions; "
        f"font {FONT_OUT.stat().st_size} bytes, theme sha256 {hashlib.sha256(theme_bytes).hexdigest()[:16]}…, "
        f"woff sha256 {hashlib.sha256(woff).hexdigest()[:16]}…; {len(languages)} languages"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
