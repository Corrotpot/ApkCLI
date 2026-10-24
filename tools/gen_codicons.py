#!/usr/bin/env python3
"""Generate Compose ImageVectors from VS Code's Codicons.

    python tools/gen_codicons.py <codicons>/src/icons

Writes app/src/main/kotlin/dev/apkcli/ui/icons/Codicons.kt.

Source, pinned: the npm package @vscode/codicons 0.0.46-24,
https://registry.npmjs.org/@vscode/codicons/-/codicons-0.0.46-24.tgz, dist
integrity sha512-KIDWUYxG6eh+DHRg+3G4bS487T+g1ZkLfB4HylOBbCcHsvLdfLMRM/m92DS1Ljhi8nglLr9ucwwWhHTEAC+DpA==;
unpacked, the icons are in package/src/icons. The design prototype
(tools/gen_design.py) reads its icons from the generated Codicons.kt, so it
draws exactly these paths.

Why Codicons: they are the icon set of VS Code (and so of every editor built on
it), designed on a 16px grid for dense developer UI. Shipping them as
ImageVectors rather than the icon font keeps them first-class in Compose --
Icon(), IconButton, tinting, sizing and semantics all work unchanged.

Only the icons listed in ICONS are emitted; R8 would strip unused ones anyway,
but a short explicit list keeps the generated file reviewable.

Strict on input: Codicon SVGs are a single <svg> holding <path> elements with
`d` and optional fill/clip rules. Anything else (transforms, opacity, other
elements) is an error rather than silently dropped, because dropping it would
render a subtly wrong glyph that nobody notices.

Licence: Codicons are CC BY 4.0 (icons) and MIT (code), (c) Microsoft. The
attribution is emitted into the generated file and ships in the APK as
app/src/main/assets/licenses/codicons.txt.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/kotlin/dev/apkcli/ui/icons/Codicons.kt"

# Kotlin name -> codicon file name (without .svg)
ICONS: dict[str, str] = {
    "Add": "add",
    "Agent": "agent",
    "Archive": "archive",
    "ArrowLeft": "arrow-left",
    "ArrowSwap": "arrow-swap",
    "ArrowUp": "arrow-up",
    # The paperclip. `attach-compact` is drawn on a 12 grid and is rejected.
    "Attach": "attach",
    "Beaker": "beaker",
    "Book": "book",
    "Check": "check",
    "Checklist": "checklist",
    "ChevronDown": "chevron-down",
    "ChevronLeft": "chevron-left",
    "ChevronRight": "chevron-right",
    "ChevronUp": "chevron-up",
    "Chip": "chip",
    "CloudDownload": "cloud-download",
    "CircleFilled": "circle-filled",
    "CircleLarge": "circle-large",
    "CircleSlash": "circle-slash",
    "Close": "close",
    "CollapseAll": "collapse-all",
    "CommentDiscussion": "comment-discussion",
    "Copy": "copy",
    "Dashboard": "dashboard",
    "Database": "database",
    "DebugRestart": "debug-restart",
    "DebugStop": "debug-stop",
    "Diff": "diff",
    # VS Code has no `undo` glyph; `discard` is the curved arrow it uses for
    # both "Undo" and "Discard Changes".
    "Discard": "discard",
    "Edit": "edit",
    "Ellipsis": "ellipsis",
    "Error": "error",
    "Eye": "eye",
    "EyeClosed": "eye-closed",
    "File": "file",
    "FileBinary": "file-binary",
    "FileCode": "file-code",
    "FileMedia": "file-media",
    "FilePdf": "file-pdf",
    "FileZip": "file-zip",
    "Files": "files",
    "Filter": "filter",
    "Flame": "flame",
    "Fold": "fold",
    "Folder": "folder",
    "FolderOpened": "folder-opened",
    "GitBranch": "git-branch",
    "GitCommit": "git-commit",
    # `github` itself is drawn on a 24x25 grid; the inverted mark is on 16.
    "Github": "github-inverted",
    "Globe": "globe",
    "GoToFile": "go-to-file",
    "Gripper": "gripper",
    "History": "history",
    "Hubot": "hubot",
    "Info": "info",
    "Json": "json",
    "KebabVertical": "kebab-vertical",
    "Key": "key",
    "Layers": "layers",
    "Layout": "layout",
    "LayoutSidebarLeft": "layout-sidebar-left",
    "LayoutSidebarLeftOff": "layout-sidebar-left-off",
    "Link": "link",
    "LinkExternal": "link-external",
    "ListTree": "list-tree",
    "ListUnordered": "list-unordered",
    "Loading": "loading",
    # The checkpoint above each message of the user's, as Kiro marks it.
    "Location": "location",
    "Lock": "lock",
    "Markdown": "markdown",
    "Mention": "mention",
    "NewFile": "new-file",
    "NewFolder": "new-folder",
    "Package": "package",
    "Pass": "pass",
    "PassFilled": "pass-filled",
    "Plug": "plug",
    "Pulse": "pulse",
    "Question": "question",
    "Redo": "redo",
    "Refresh": "refresh",
    "Repo": "repo",
    "RepoClone": "repo-clone",
    "RootFolder": "root-folder",
    "Save": "save",
    "ScreenFull": "screen-full",
    "ScreenNormal": "screen-normal",
    "Search": "search",
    "Send": "send",
    "Server": "server",
    "ServerEnvironment": "server-environment",
    "SettingsGear": "settings-gear",
    "Shield": "shield",
    "SourceControl": "source-control",
    "Sparkle": "sparkle",
    "SplitHorizontal": "split-horizontal",
    "SplitVertical": "split-vertical",
    "StopCircle": "stop-circle",
    "Sync": "sync",
    "Terminal": "terminal",
    "Thinking": "thinking",
    "Tools": "tools",
    "Trash": "trash",
    "Warning": "warning",
    "Watch": "watch",
    "WordWrap": "word-wrap",
}

ALLOWED_PATH_ATTRS = {"d", "fill-rule", "clip-rule", "fill"}
SVG_RE = re.compile(r"<svg\b([^>]*)>(.*)</svg\s*>", re.S)
ELEMENT_RE = re.compile(r"<\s*([a-zA-Z][\w:-]*)\b([^>]*?)/?>", re.S)
ATTR_RE = re.compile(r'([\w:-]+)\s*=\s*"([^"]*)"')


def parse_icon(path: Path) -> tuple[int, list[tuple[str, bool]]]:
    """Return (square viewport size, [(path data, even-odd)])."""
    text = path.read_text(encoding="utf-8").strip()
    m = SVG_RE.search(text)
    if not m:
        raise ValueError(f"{path.name}: no <svg> root")
    svg_attrs = dict(ATTR_RE.findall(m.group(1)))
    # Most codicons are on a 16 grid; the activity-bar ones (files, terminal,
    # settings-gear, source-control) are drawn on 24. Both are fine as long as
    # the viewport is carried through -- assuming 16 would draw a 24-grid icon
    # at two thirds of its intended scale, offset to the top-left.
    view_box = svg_attrs.get("viewBox", "0 0 16 16").split()
    if len(view_box) != 4 or view_box[:2] != ["0", "0"] or view_box[2] != view_box[3] \
            or view_box[2] not in ("16", "24"):
        raise ValueError(f"{path.name}: unsupported viewBox {svg_attrs.get('viewBox')!r}")
    viewport = int(view_box[2])

    paths: list[tuple[str, bool]] = []
    for element in ELEMENT_RE.finditer(m.group(2)):
        tag, raw = element.group(1), element.group(2)
        if tag.startswith("/"):
            continue
        if tag != "path":
            raise ValueError(f"{path.name}: unsupported element <{tag}>")
        attrs = dict(ATTR_RE.findall(raw))
        unknown = set(attrs) - ALLOWED_PATH_ATTRS
        if unknown:
            raise ValueError(f"{path.name}: unsupported path attribute(s) {sorted(unknown)}")
        if attrs.get("fill", "currentColor") not in ("currentColor", "black", "#000", "#000000"):
            raise ValueError(f"{path.name}: literal fill {attrs['fill']!r} would ignore tinting")
        d = " ".join(attrs.get("d", "").split())
        if not d:
            raise ValueError(f"{path.name}: empty path")
        paths.append((d, attrs.get("fill-rule") == "evenodd"))
    if not paths:
        raise ValueError(f"{path.name}: no paths")
    return viewport, paths


def kotlin_string(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    entries: list[str] = []
    errors: list[str] = []
    for kotlin_name, file_name in ICONS.items():
        svg = src / f"{file_name}.svg"
        if not svg.exists():
            errors.append(f"missing: {file_name}.svg")
            continue
        try:
            viewport, paths = parse_icon(svg)
        except ValueError as e:
            errors.append(str(e))
            continue
        args = ",\n            ".join(
            f"{'pe' if even_odd else 'p'}({kotlin_string(d)})" for d, even_odd in paths
        )
        entries.append(
            f"    /** codicon `{file_name}` */\n"
            f"    val {kotlin_name}: ImageVector by lazy {{\n"
            f"        codicon(\n"
            f"            {kotlin_string(file_name)},\n"
            f"            {viewport}f,\n"
            f"            {args},\n"
            f"        )\n"
            f"    }}\n"
        )
    if errors:
        for e in errors:
            print("error:", e)
        return 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        "// GENERATED by tools/gen_codicons.py -- do not edit by hand.\n"
        "//\n"
        "// Icons: Codicons, (c) Microsoft Corporation, licensed CC BY 4.0\n"
        "// (https://creativecommons.org/licenses/by/4.0/). Source:\n"
        "// https://github.com/microsoft/vscode-codicons. Converted from SVG to\n"
        "// Compose ImageVector; geometry unchanged.\n"
        "package dev.apkcli.ui.icons\n"
        "\n"
        "import androidx.compose.ui.graphics.Color\n"
        "import androidx.compose.ui.graphics.PathFillType\n"
        "import androidx.compose.ui.graphics.SolidColor\n"
        "import androidx.compose.ui.graphics.vector.ImageVector\n"
        "import androidx.compose.ui.graphics.vector.addPathNodes\n"
        "import androidx.compose.ui.unit.dp\n"
        "\n"
        "/**\n"
        " * VS Code's icon set, as Compose vectors.\n"
        " *\n"
        " * Drawn on a 16-unit grid (24 for the activity-bar glyphs); the default\n"
        " * size is 20dp, which is what a 16px editor icon becomes at phone density\n"
        " * and arm's length. Fill is black and is replaced by `Icon(tint = ...)`, so\n"
        " * every icon follows the theme.\n"
        " *\n"
        " * Each vector is parsed once, on first use.\n"
        " */\n"
        "object Codicons {\n\n"
        + "\n".join(entries)
        + "}\n"
        "\n"
        "private class CodiconPath(val d: String, val evenOdd: Boolean)\n"
        "\n"
        "private fun p(d: String) = CodiconPath(d, evenOdd = false)\n"
        "\n"
        "private fun pe(d: String) = CodiconPath(d, evenOdd = true)\n"
        "\n"
        "private fun codicon(name: String, viewport: Float, vararg paths: CodiconPath): ImageVector =\n"
        "    ImageVector.Builder(\n"
        "        name = \"codicon.$name\",\n"
        "        defaultWidth = 20.dp,\n"
        "        defaultHeight = 20.dp,\n"
        "        viewportWidth = viewport,\n"
        "        viewportHeight = viewport,\n"
        "    ).apply {\n"
        "        for (path in paths) {\n"
        "            addPath(\n"
        "                pathData = addPathNodes(path.d),\n"
        "                pathFillType = if (path.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,\n"
        "                fill = SolidColor(Color.Black),\n"
        "            )\n"
        "        }\n"
        "    }.build()\n",
        encoding="utf-8",
    )
    print(f"wrote {OUT.relative_to(ROOT)}: {len(entries)} icons")
    return 0


if __name__ == "__main__":
    sys.exit(main())
