#!/usr/bin/env python3
"""Generate the Compose theme from design/tokens.json.

    python tools/gen_theme.py

Writes:

  app/src/main/kotlin/dev/apkcli/ui/theme/DesignTokens.kt   colours, text styles,
                                                            radii, sizes, motion
  app/src/main/res/font/inter.ttf                           Inter 4.1, variable
  app/src/main/res/font/jetbrains_mono.ttf                  JetBrains Mono 2.304
  app/src/main/assets/licenses/{inter,jetbrains-mono}.txt   their OFL notices

The same tokens.json drives the HTML prototype (tools/gen_design.py), so the
two cannot drift: change a value there, run both generators, and the prototype
and the app move together. Nothing in DesignTokens.kt is edited by hand.

The fonts are the same files the prototype embeds, taken from the same pinned
release zips (SHA-256 checked, cached in build/design-cache). Bundling them is
what makes "1:1" true on a phone: the system sans differs between Pixel,
Samsung and Xiaomi, so with it no two devices would match the prototype, or
each other.

Text layout follows CSS: line height distributed evenly around the glyphs
(LineHeightStyle.Alignment.Center, Trim.None) and no font padding, which is
exactly how a browser lays out the prototype's text. Letter spacing is in sp
because CSS px at 1 CSS px = 1 dp is what the tokens mean.

Standard library only.
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_design  # noqa: E402  (same fetch, same pins)

ROOT = Path(__file__).resolve().parent.parent
TOKENS = ROOT / "design" / "tokens.json"
OUT = ROOT / "app/src/main/kotlin/dev/apkcli/ui/theme/DesignTokens.kt"
FONT_DIR = ROOT / "app/src/main/res/font"
LICENSES = ROOT / "app/src/main/assets/licenses"

# zip in the cache, member, resource name, licence member, licence file
FONT_FILES = [
    ("Inter-4.1.zip", "InterVariable.ttf", "inter.ttf", "LICENSE.txt", "inter.txt"),
    ("JetBrainsMono-2.304.zip", "fonts/variable/JetBrainsMono[wght].ttf", "jetbrains_mono.ttf", "OFL.txt", "jetbrains-mono.txt"),
]

# The weights the text styles use. Each becomes one Font entry over the same
# variable file, pinned to that weight with a variation setting.
WEIGHTS = (400, 500, 600, 700)


def pascal(name: str) -> str:
    return name[:1].upper() + name[1:]


def argb(value: str) -> str:
    """#RRGGBB or #RRGGBBAA (CSS order) to Compose's 0xAARRGGBB."""
    hexpart = value.lstrip("#")
    if len(hexpart) == 6:
        return f"0xFF{hexpart.upper()}"
    if len(hexpart) == 8:
        return f"0x{hexpart[6:].upper()}{hexpart[:6].upper()}"
    raise SystemExit(f"error: unsupported colour {value!r}")


def number(value: float) -> str:
    text = f"{value:g}"
    return text if "." in text or "e" in text else text


def kotlin_float(value: float) -> str:
    text = f"{value:g}"
    return f"{text}f" if "." in text else f"{text}f"


def colour_block(name: str, group: dict) -> list[str]:
    out = [f"    object {name} {{"]
    for key, value in group.items():
        if key.startswith("$"):
            continue
        out.append(f"        val {pascal(key)} = Color({argb(value)})")
    out.append("    }")
    return out


def text_styles(tokens: dict) -> list[str]:
    features = {
        family: spec["features"].replace('"', "'")
        for family, spec in tokens["font"].items()
        if isinstance(spec, dict)
    }
    out = [
        "    /**",
        "     * Every text style the design uses. Built once; components read these",
        "     * instead of MaterialTheme.typography so a token change reaches them all.",
        "     * An uppercase style (overline) is uppercased by the component that",
        "     * draws it — Compose has no text-transform.",
        "     */",
        "    object Type {",
    ]
    for key, style in tokens["text"].items():
        family = "Fonts.Sans" if style["font"] == "sans" else "Fonts.Mono"
        tracking = style.get("tracking", 0)
        lines = [
            f"fontFamily = {family}",
            f"fontSize = {number(style['size'])}.sp",
            f"lineHeight = {number(style['line'])}.sp",
            f"fontWeight = FontWeight.W{style['weight']}",
            f"letterSpacing = {'(' + number(tracking) + ')' if tracking < 0 else number(tracking)}.sp",
        ]
        feature = features.get(style["font"], "")
        if feature:
            lines.append(f'fontFeatureSettings = "{feature}"')
        lines.append("platformStyle = NoFontPadding")
        lines.append("lineHeightStyle = CssLineHeight")
        out.append(f"        val {pascal(key)} = TextStyle(")
        out.extend(f"            {line}," for line in lines)
        out.append("        )")
    uppercase = [pascal(k) for k, s in tokens["text"].items() if s.get("uppercase")]
    out.append("")
    out.append("        /** Styles whose text is set in capitals. */")
    out.append(f"        val Uppercase: Set<TextStyle> get() = setOf({', '.join(uppercase)})")
    out.append("    }")
    return out


def dp_block(name: str, group: dict, doc: str) -> list[str]:
    out = [f"    /** {doc} */", f"    object {name} {{"]
    for key, value in group.items():
        if key.startswith("$"):
            continue
        out.append(f"        val {pascal(key)} = {number(value)}.dp")
    out.append("    }")
    return out


def motion_block(group: dict) -> list[str]:
    out = [
        "    /** Durations in milliseconds and the easing curves, as the prototype animates. */",
        "    object Motion {",
    ]
    for key, value in group.items():
        if key.startswith("$"):
            continue
        if isinstance(value, list):
            args = ", ".join(kotlin_float(v) for v in value)
            out.append(f"        val {pascal(key)}: Easing = CubicBezierEasing({args})")
        else:
            out.append(f"        const val {pascal(key)} = {value}")
    out.append("    }")
    return out


def avatar_block(group: dict) -> list[str]:
    hues = ", ".join(f"Color({argb(h)})" for h in group["hues"])
    alpha = int(group["alpha"], 16) / 255
    return [
        "    /**",
        "     * Project tiles: the letter in a hue picked from the name, on the same hue at",
        f"     * {round(alpha * 100)}% — the sum of the lower-cased name's UTF-16 units, mod {len(group['hues'])}.",
        "     */",
        "    object Avatar {",
        f"        val Hues: List<Color> = listOf({hues})",
        f"        const val BackgroundAlpha = {alpha:.4f}f",
        "",
        "        fun hueFor(name: String): Color {",
        "            val sum = name.lowercase().sumOf { it.code }",
        "            return Hues[sum % Hues.size]",
        "        }",
        "    }",
    ]


def kotlin(tokens: dict) -> str:
    lines = [
        "// GENERATED by tools/gen_theme.py from design/tokens.json -- do not edit by hand.",
        "@file:Suppress(\"MagicNumber\")",
        "",
        "package dev.apkcli.ui.theme",
        "",
        "import androidx.compose.animation.core.CubicBezierEasing",
        "import androidx.compose.animation.core.Easing",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.text.ExperimentalTextApi",
        "import androidx.compose.ui.text.PlatformTextStyle",
        "import androidx.compose.ui.text.TextStyle",
        "import androidx.compose.ui.text.font.Font",
        "import androidx.compose.ui.text.font.FontFamily",
        "import androidx.compose.ui.text.font.FontVariation",
        "import androidx.compose.ui.text.font.FontWeight",
        "import androidx.compose.ui.text.style.LineHeightStyle",
        "import androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.unit.sp",
        "import dev.apkcli.R",
        "",
        "/**",
        " * The black theme's tokens. See design/tokens.json for what each one is for;",
        " * the HTML prototype is built from the same file.",
        " */",
        "object Design {",
        "",
    ]
    lines += colour_block("Colors", tokens["color"])
    lines.append("")
    lines += colour_block("Syntax", tokens["syntax"])
    lines.append("")
    lines += colour_block("Ansi", tokens["ansi"])
    lines.append("")
    lines += [
        "    /** Inter and JetBrains Mono, each one variable file pinned per weight. */",
        "    object Fonts {",
        "        val Sans: FontFamily = FontFamily(" + ", ".join(f"weighted(R.font.inter, {w})" for w in WEIGHTS) + ")",
        "        val Mono: FontFamily = FontFamily(" + ", ".join(f"weighted(R.font.jetbrains_mono, {w})" for w in WEIGHTS[:3]) + ")",
        "",
        "        // Variation settings are still marked experimental in Compose 1.11;",
        "        // they map to Typeface.Builder.setFontVariationSettings, API 26+,",
        "        // which is the app's minSdk.",
        "        @OptIn(ExperimentalTextApi::class)",
        "        private fun weighted(resource: Int, weight: Int) = Font(",
        "            resId = resource,",
        "            weight = FontWeight(weight),",
        "            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),",
        "        )",
        "    }",
        "",
        "    private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)",
        "",
        "    /** How CSS distributes line height: evenly above and below, nothing trimmed. */",
        "    private val CssLineHeight = LineHeightStyle(",
        "        alignment = LineHeightStyle.Alignment.Center,",
        "        trim = LineHeightStyle.Trim.None,",
        "    )",
        "",
    ]
    lines += text_styles(tokens)
    lines.append("")
    lines += dp_block("Radius", tokens["radius"], "Corner radii, dp.")
    lines.append("")
    lines += dp_block("Size", tokens["size"], "Fixed sizes, dp.")
    lines.append("")
    lines += motion_block(tokens["motion"])
    lines.append("")
    lines += avatar_block(tokens["avatar"])
    lines.append("")
    lines.append(f"    /** File-type glyphs are set at this multiple of their box, as VS Code does. */")
    lines.append(f"    const val SetiScale = {tokens['font']['setiScale']}f")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def fonts() -> None:
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    LICENSES.mkdir(parents=True, exist_ok=True)
    pins = {entry[0]: entry for entry in gen_design.FONTS}
    for zip_name, member, resource, licence_member, licence_file in FONT_FILES:
        _, url, sha256, *_ = pins[zip_name]
        gen_design.fetch(zip_name, url, sha256)
        with zipfile.ZipFile(gen_design.CACHE / zip_name) as archive:
            (FONT_DIR / resource).write_bytes(archive.read(member))
            (LICENSES / licence_file).write_bytes(archive.read(licence_member))
        print(f"wrote res/font/{resource} and assets/licenses/{licence_file}")


def main() -> int:
    tokens = json.loads(TOKENS.read_text(encoding="utf-8"))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(kotlin(tokens), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")
    fonts()
    return 0


if __name__ == "__main__":
    sys.exit(main())
