#!/usr/bin/env python3
"""Build the design prototype: one self-contained HTML file to open and click.

    python tools/gen_design.py [--out PATH]

Writes build/design/ApkCLI-design.html (and a copy at PATH). Double-click it:
it needs no server and no network, so it can be opened on a phone too, where
it fills the screen at real size (1 CSS px = 1 dp in a mobile browser).

Everything it draws comes from what the app uses, not from copies made for it:

  design/tokens.json                              colours, text styles, radii,
                                                  sizes, motion
  app/src/main/kotlin/.../icons/Codicons.kt       every icon's path data
  app/src/main/kotlin/.../icons/SetiIcons.kt      file icon glyphs and colours
  app/src/main/res/font/seti.ttf                  the file icon font
  app/src/main/res/drawable/ic_launcher_foreground.xml   the logo
  Inter 4.1 and JetBrains Mono 2.304              the text fonts, from their
                                                  releases, pinned by SHA-256

The screens themselves are design/prototype/{index.html,app.css,app.js}.
Fonts are embedded as data URIs: a page opened from disk may not fetch a font
file next to it (Chrome refuses file:// fonts as cross-origin).

Standard library only. Downloads are cached in build/design-cache.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "build" / "design-cache"
OUT = ROOT / "build" / "design" / "ApkCLI-design.html"

TOKENS = ROOT / "design" / "tokens.json"
PROTO = ROOT / "design" / "prototype"
CODICONS = ROOT / "app/src/main/kotlin/dev/apkcli/ui/icons/Codicons.kt"
SETI_KT = ROOT / "app/src/main/kotlin/dev/apkcli/ui/icons/SetiIcons.kt"
SETI_TTF = ROOT / "app/src/main/res/font/seti.ttf"
LOGO = ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"

# (zip in the cache, URL, SHA-256 of the zip, member to take, CSS family, weight range)
FONTS = [
    (
        "Inter-4.1.zip",
        "https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip",
        "9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e",
        "InterVariable.ttf",
        "Inter",
        "100 900",
    ),
    (
        "JetBrainsMono-2.304.zip",
        "https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip",
        "6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf",
        "fonts/variable/JetBrainsMono[wght].ttf",
        "JetBrains Mono",
        "100 800",
    ),
]


def kebab(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])([A-Z])", r"-\1", name).lower()


def fetch(name: str, url: str, sha256: str) -> bytes:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists():
        request = urllib.request.Request(url, headers={"User-Agent": "apkcli-gen-design"})
        with urllib.request.urlopen(request, timeout=120) as response:
            path.write_bytes(response.read())
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != sha256:
        raise SystemExit(f"error: {name} has SHA-256 {digest}, expected {sha256}")
    return data


def font_faces() -> str:
    faces = []
    for zip_name, url, sha256, member, family, weights in FONTS:
        archive = CACHE / zip_name
        fetch(zip_name, url, sha256)
        with zipfile.ZipFile(archive) as z:
            ttf = z.read(member)
        faces.append(
            f'@font-face{{font-family:"{family}";font-style:normal;font-weight:{weights};font-display:block;'
            f'src:url(data:font/ttf;base64,{base64.b64encode(ttf).decode()}) format("truetype")}}'
        )
    seti = base64.b64encode(SETI_TTF.read_bytes()).decode()
    faces.append(
        f'@font-face{{font-family:"Seti";font-display:block;src:url(data:font/ttf;base64,{seti}) format("truetype")}}'
    )
    return "\n".join(faces)


def token_css(tokens: dict) -> str:
    lines = [":root{"]
    for group, prefix in (("color", "c"), ("syntax", "syn"), ("ansi", "ansi")):
        for name, value in tokens[group].items():
            if not name.startswith("$"):
                lines.append(f"--{prefix}-{kebab(name)}:{value};")
    for name, value in tokens["radius"].items():
        lines.append(f"--r-{kebab(name)}:{value}px;")
    for name, value in tokens["size"].items():
        lines.append(f"--sz-{kebab(name)}:{value}px;")
    for name, value in tokens["motion"].items():
        if name.startswith("$"):
            continue
        if isinstance(value, list):
            lines.append(f"--e-{kebab(name)}:cubic-bezier({','.join(str(v) for v in value)});")
        else:
            lines.append(f"--m-{kebab(name)}:{value}ms;")
    sans = tokens["font"]["sans"]["family"]
    mono = tokens["font"]["mono"]["family"]
    lines.append(f'--font-sans:"{sans}",system-ui,sans-serif;')
    lines.append(f'--font-mono:"{mono}",ui-monospace,monospace;')
    lines.append(f"--seti-scale:{tokens['font']['setiScale']};")
    lines.append("}")

    for name, style in tokens["text"].items():
        font = tokens["font"][style["font"]]
        rules = [
            f"font-family:var(--font-{style['font']})",
            f"font-size:{style['size']}px",
            f"line-height:{style['line']}px",
            f"font-weight:{style['weight']}",
            f"letter-spacing:{style.get('tracking', 0)}px",
        ]
        if font["features"]:
            rules.append(f"font-feature-settings:{font['features']}")
            rules.append("font-variant-ligatures:none")
        if style.get("uppercase"):
            rules.append("text-transform:uppercase")
        lines.append(f".t-{kebab(name)}{{{';'.join(rules)}}}")
    return "\n".join(lines)


def codicons() -> dict:
    text = CODICONS.read_text(encoding="utf-8")
    icons = {}
    for name, viewport, body in re.findall(
        r'codicon\(\s*"([a-z0-9-]+)",\s*(\d+)f,\s*((?:pe?\("[^"]*"\),\s*)+)\)', text
    ):
        icons[name] = {
            "vp": int(viewport),
            "p": [[d, kind == "pe"] for kind, d in re.findall(r'(pe?)\("([^"]*)"\)', body)],
        }
    if not icons:
        raise SystemExit("error: no icons parsed from Codicons.kt")
    return icons


def kotlin_literals(source: str) -> str:
    joined = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', source))
    return re.sub(r"\\(.)", r"\1", joined)


def seti() -> dict:
    text = SETI_KT.read_text(encoding="utf-8")
    chars = re.search(r'private const val CHARS = "((?:\\u[0-9a-fA-F]{4})*)"', text).group(1)
    codes = [int(c, 16) for c in re.findall(r"\\u([0-9a-fA-F]{4})", chars)]
    # The array's entries end in .toInt(), so its close is the first ")" not
    # part of one; a plain [^)]* stopped at the first entry and left the
    # prototype's file icons without their colours.
    colour_array = re.search(r"private val COLOURS = intArrayOf\(((?:[^()]|\(\))*)\)", text).group(1)
    colours = ["#" + c for c in re.findall(r"0xFF([0-9A-Fa-f]{6})\.toInt\(\)", colour_array)]

    def table(name: str) -> dict:
        body = re.search(rf"private val {name}: Map<String, Int> by lazy \{{ parse\((.*?),\s*\) \}}", text, re.S).group(1)
        pairs = {}
        for entry in kotlin_literals(body).split(";"):
            if "=" in entry:
                key, _, value = entry.rpartition("=")
                pairs[key] = int(value)
        return pairs

    return {
        "codes": codes,
        "colours": colours,
        "names": table("DARK_NAMES"),
        "exts": table("DARK_EXTS"),
        "default": int(re.search(r"DARK_DEFAULT = (\d+)", text).group(1)),
    }


def logo_svg() -> str:
    """The launcher glyph, cropped to its own bounds plus margin, as SVG."""
    text = LOGO.read_text(encoding="utf-8")
    paths = re.findall(r'android:fillColor="(#[0-9A-Fa-f]{6,8})"\s+android:pathData="([^"]+)"', text)
    if not paths:
        raise SystemExit("error: no paths in ic_launcher_foreground.xml")
    body = "".join(f'<path fill="{colour}" d="{d}"/>' for colour, d in paths)
    # The glyph spans x 36..77, y 34..64 of the 108 canvas; a 64-unit square
    # centred on it leaves the margin a tile needs.
    return f'<svg viewBox="24.5 17 64 64" xmlns="http://www.w3.org/2000/svg">{body}</svg>'


def check_icons(js: str, icons: dict) -> None:
    """Fail on an icon the app does not have, so the two cannot drift apart.

    Looks at every string in an `ic(...)` call's first argument -- a ternary
    picking between two names counts as both -- and at `icon:` fields.
    """
    used = set(re.findall(r"""\bicon\s*:\s*['"]([a-z0-9-]+)['"]""", js))
    for call in re.finditer(r"\bic\(", js):
        depth = 0
        start = call.end()
        for i in range(start, len(js)):
            char = js[i]
            if char in "([{":
                depth += 1
            elif char in ")]}":
                if depth == 0:
                    break
                depth -= 1
            elif char == "," and depth == 0:
                break
        used |= set(re.findall(r"""['"]([A-Za-z0-9-]+)['"]""", js[start:i]))
    missing = sorted(used - set(icons))
    if missing:
        raise SystemExit(
            "error: the prototype uses icons Codicons.kt does not have: " + ", ".join(missing)
            + "\n  add them to tools/gen_codicons.py and regenerate, so the app has them too"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, help="also copy the result here")
    args = parser.parse_args()

    tokens = json.loads(TOKENS.read_text(encoding="utf-8"))
    template = (PROTO / "index.html").read_text(encoding="utf-8")
    css = (PROTO / "app.css").read_text(encoding="utf-8")
    js = (PROTO / "app.js").read_text(encoding="utf-8")

    icons = codicons()
    check_icons(js, icons)

    data = {
        "tokens": {k: v for k, v in tokens.items() if not k.startswith("$")},
        "icons": icons,
        "seti": seti(),
        "logo": logo_svg(),
    }
    style = f"<style>\n{font_faces()}\n{token_css(tokens)}\n{css}\n</style>"
    script = (
        "<script>\n"
        f"const DESIGN = {json.dumps(data, ensure_ascii=False, separators=(',', ':'))};\n"
        f"{js}\n"
        "</script>"
    )
    if "<!-- @STYLE -->" not in template or "<!-- @SCRIPT -->" not in template:
        raise SystemExit("error: index.html needs the <!-- @STYLE --> and <!-- @SCRIPT --> markers")
    html = template.replace("<!-- @STYLE -->", style).replace("<!-- @SCRIPT -->", script)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(html) // 1024} KB, {len(icons)} icons")
    if args.out:
        shutil.copyfile(OUT, args.out)
        print(f"copied to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
