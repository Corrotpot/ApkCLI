#!/usr/bin/env python3
"""Static audit of the ApkCLI sources.

Not a compiler. It checks the specific classes of mistake that are invisible on
re-reading but fatal at build time, and that a type checker would catch in a
second if one were available here:

  * Kotlin imports of project symbols that nothing declares.
  * R.* resource references with no matching resource definition.
  * `libs.*` version-catalog aliases that the catalog does not define.
  * Rust `mod` declarations with no file, and JNI symbol names that do not match
    the Kotlin side.
  * Manifest `android:name` classes that do not exist.
  * Unbalanced braces/parens, which usually means a bad edit.

Exit code is the number of findings, so it can gate a commit.

Usage:
    python tools/audit.py [project_root]
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent)

findings: list[tuple[str, str, str]] = []  # (severity, location, message)


def report(severity: str, location: str, message: str) -> None:
    findings.append((severity, location, message))


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT)).replace("\\", "/")
    except ValueError:
        return str(path)


# ---------------------------------------------------------------------------
# collect sources
# ---------------------------------------------------------------------------

GENERATED_DIRS = {"build", "target", ".gradle", ".idea", ".git", "jniLibs", "prebuilt"}


def walk(pattern: str) -> list[Path]:
    """rglob, minus anything a build tool produced.

    Every discovery in this file goes through here. Scanning
    app/build/intermediates once produced 18 phantom errors about AGP's
    generated DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, which is exactly the
    kind of noise that trains you to ignore the audit.
    """
    return sorted(
        p for p in ROOT.rglob(pattern) if not (GENERATED_DIRS & set(p.parts))
    )


kotlin_files = walk("*.kt")
rust_files = walk("*.rs")

sources: dict[Path, str] = {}
for path in kotlin_files + rust_files:
    try:
        sources[path] = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        report("ERROR", rel(path), "not valid UTF-8")

# ---------------------------------------------------------------------------
# 1. Kotlin: do project imports resolve?
# ---------------------------------------------------------------------------

# fully-qualified name -> file that declares it
declared: dict[str, Path] = {}
# package -> set of top-level member names (functions, properties, typealiases)
package_members: dict[str, set[str]] = defaultdict(set)

# The name is what follows the last dot of an optional receiver, because an
# extension function's importable name is `str`, not `JsonObject`:
#
#   fun JsonObject.str(key: String): String?        -> str
#   fun <T> Iterable<T>.second(): T                 -> second
#   fun TerminalBuffer.Row.annotated(...)           -> annotated
#   inline fun jsonOf(vararg pairs: ...): JsonObject -> jsonOf
#
# A name is never followed by a dot: that would make it part of a receiver
# with more than one segment, which the lazy receiver match stopped short of.
DECL_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+|value\s+|enum\s+|annotation\s+|inner\s+|final\s+|inline\s+|suspend\s+|operator\s+|infix\s+|external\s+|const\s+|lateinit\s+|expect\s+|actual\s+)*"
    r"(class|interface|object|fun|val|var|typealias)\s+"
    r"(?:<[^>]*>\s*)?"                      # generic parameters
    r"(?:[\w.<>?, \[\]]*?\.)?"              # optional extension receiver
    r"([A-Za-z_][A-Za-z0-9_]*)(?![\w.])",
    re.MULTILINE,
)

# Classes the toolchain generates. They never exist in source, so an import of
# one is correct even though nothing declares it.
GENERATED_SUFFIXES = (".R", ".BuildConfig")

# Imported for their operator convention, used by syntax rather than by name.
IMPLICIT_OPERATORS = {
    "getValue",
    "setValue",
    "provideDelegate",
    "component1",
    "component2",
    "invoke",
    "iterator",
    "contains",
    "rangeTo",
    "compareTo",
    "plus",
    "minus",
    "times",
    "div",
    "unaryMinus",
}
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)

file_package: dict[Path, str] = {}

for path in kotlin_files:
    text = sources.get(path, "")
    match = PACKAGE_RE.search(text)
    if not match:
        report("ERROR", rel(path), "no package declaration")
        continue
    package = match.group(1)
    file_package[path] = package

    for decl_match in DECL_RE.finditer(text):
        kind, name = decl_match.group(1), decl_match.group(2)
        # Only top-level declarations count for import resolution. Approximate
        # "top level" as: the declaration starts at column 0.
        line_start = text.rfind("\n", 0, decl_match.start()) + 1
        indent = decl_match.start() - line_start
        prefix = text[line_start : decl_match.start()]
        if indent > 0 and prefix.strip() != "":
            continue
        if kind in ("class", "interface", "object", "typealias"):
            declared[f"{package}.{name}"] = path
        package_members[package].add(name)

# Nested types: `Outer.Inner` is importable, and so is `Outer.Inner.Deeper`
# (SubagentRun.Step.Kind) and a member of an unnamed companion
# (SubagentRun.Companion.MAX_STEPS). A nested declaration belongs to the
# nearest declaration above it that is indented less.
NESTED_RE = re.compile(
    r"^([ \t]+)(?:@\w+\s*)*"
    r"((?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+|value\s+|enum\s+|annotation\s+|inner\s+|companion\s+|final\s+)*)"
    r"(class|interface|object)(?:\s+([A-Za-z_][A-Za-z0-9_]*))?",
    re.MULTILINE,
)
for path in kotlin_files:
    text = sources.get(path, "")
    package = file_package.get(path)
    if not package:
        continue
    # Top-level declarations in order; a nested one belongs to the nearest
    # top-level declaration above it. (Taking the file's first declaration
    # as every nested type's parent misfiled LlmTypes.kt, which declares
    # several classes with members of their own.)
    def first_char(match):
        # Both patterns open with `^\s*`, which can swallow blank lines: the
        # declaration itself starts at its first non-blank character.
        return match.start() + len(match.group(0)) - len(match.group(0).lstrip())

    tops = []
    for decl_match in DECL_RE.finditer(text):
        start = first_char(decl_match)
        if start == text.rfind("\n", 0, start) + 1 and decl_match.group(1) in (
            "class",
            "interface",
            "object",
        ):
            tops.append((start, decl_match.group(2)))
    if not tops:
        continue
    # (start, indent, qualified name below the package), top-level ones first.
    entries = [(start, 0, name) for start, name in tops]
    for nested in NESTED_RE.finditer(text):
        name = nested.group(4)
        if name is None:
            # `companion object {` is `Companion`; an anonymous `object :` is nothing.
            if "companion" not in nested.group(2):
                continue
            name = "Companion"
        entries.append((first_char(nested), len(nested.group(1).expandtabs(4)), name))
    entries.sort()
    qualified: list[tuple[int, str]] = []  # (indent, name) of each entry, in order
    for start, indent, name in entries:
        if indent == 0:
            qualified.append((0, name))
            continue
        parent = next((q for i, q in reversed(qualified) if i < indent), None)
        if parent is None:
            continue
        full = f"{parent}.{name}"
        qualified.append((indent, full))
        declared[f"{package}.{full}"] = path

IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+\w+)?", re.MULTILINE)

for path in kotlin_files:
    text = sources.get(path, "")
    for import_match in IMPORT_RE.finditer(text):
        fqn = import_match.group(1)
        if not fqn.startswith("dev.apkcli"):
            continue
        if fqn in declared:
            continue
        if fqn.endswith(GENERATED_SUFFIXES):
            continue
        # Member import: package + name, e.g. dev.apkcli.core.util.jsonOf
        package, _, name = fqn.rpartition(".")
        if name in package_members.get(package, set()):
            continue
        # Nested member import, e.g. Schema.string
        grandparent, _, parent = package.rpartition(".")
        if f"{grandparent}.{parent}" in declared:
            owner_file = declared[f"{grandparent}.{parent}"]
            if re.search(rf"\b(fun|val|var)\s+(?:[\w.<>]+\.)?{re.escape(name)}\b", sources.get(owner_file, "")):
                continue
            if re.search(rf"\bfun\s+\w+\.{re.escape(name)}\b", sources.get(owner_file, "")):
                continue
            # Extension declared inside the object, receiver-qualified.
            if re.search(rf"fun\s+[\w<>.]+\.{re.escape(name)}\s*\(", sources.get(owner_file, "")):
                continue
        report("ERROR", rel(path), f"import does not resolve: {fqn}")

# ---------------------------------------------------------------------------
# 1b. Unused imports
#
# Only a warning from the Kotlin compiler, so they accumulate — and after heavy
# editing they are a reliable signal that something was moved or removed and the
# header was not updated.
# ---------------------------------------------------------------------------

for path in kotlin_files:
    text = sources.get(path, "")
    header_end = 0
    body_lines = []
    imports: list[tuple[str, str]] = []  # (simple name, full line)
    for line in text.splitlines():
        import_match = re.match(r"\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?", line)
        if import_match:
            alias = import_match.group(2)
            simple = alias or import_match.group(1).rpartition(".")[2]
            imports.append((simple, import_match.group(1)))
        else:
            body_lines.append(line)

    body = "\n".join(body_lines)
    for simple, fqn in imports:
        if simple == "*" or fqn.endswith(".*"):
            continue
        # Operator conventions are invoked by syntax, not by name. `var x by
        # remember { mutableStateOf(0) }` needs getValue and setValue imported
        # and they never appear as identifiers, so a name search reports every
        # one of them as unused.
        if simple in IMPLICIT_OPERATORS:
            continue
        if not re.search(rf"\b{re.escape(simple)}\b", body):
            report("WARN", rel(path), f"unused import: {fqn}")

# ---------------------------------------------------------------------------
# 2. Android resources
# ---------------------------------------------------------------------------

resource_defs: dict[str, set[str]] = defaultdict(set)

for values_file in walk("res/values/*.xml"):
    text = values_file.read_text(encoding="utf-8")
    for match in re.finditer(r'<(string|color|style|dimen|bool|integer)\s+name="([^"]+)"', text):
        kind = "string" if match.group(1) == "string" else match.group(1)
        resource_defs[kind].add(match.group(2))

for res_dir in walk("res/*"):
    if not res_dir.is_dir():
        continue
    folder = res_dir.name.split("-")[0]
    if folder in ("values",):
        continue
    for item in res_dir.iterdir():
        if item.is_file():
            resource_defs[folder].add(item.stem)

R_REF_RE = re.compile(r"\bR\.(string|drawable|mipmap|xml|color|style|dimen|raw|font)\.(\w+)")

for path in kotlin_files:
    text = sources.get(path, "")
    for match in R_REF_RE.finditer(text):
        kind, name = match.group(1), match.group(2)
        if name not in resource_defs.get(kind, set()):
            report("ERROR", rel(path), f"R.{kind}.{name} is not defined")

# Manifest and XML @resource references
for xml_file in walk("AndroidManifest.xml") + walk("res/**/*.xml"):
    text = xml_file.read_text(encoding="utf-8")
    for match in re.finditer(r'"@(string|drawable|mipmap|xml|color|style)/([\w.]+)"', text):
        kind, name = match.group(1), match.group(2)
        if name.startswith("android:"):
            continue
        if name not in resource_defs.get(kind, set()):
            report("ERROR", rel(xml_file), f"@{kind}/{name} is not defined")

# ---------------------------------------------------------------------------
# 3. Gradle version catalog
# ---------------------------------------------------------------------------

catalog_path = ROOT / "gradle" / "libs.versions.toml"
if catalog_path.exists():
    catalog = catalog_path.read_text(encoding="utf-8")

    def section(name: str) -> str:
        match = re.search(rf"^\[{name}\]\s*$(.*?)(?=^\[|\Z)", catalog, re.MULTILINE | re.DOTALL)
        return match.group(1) if match else ""

    def aliases(name: str) -> set[str]:
        return {
            m.group(1).replace("-", ".")
            for m in re.finditer(r"^([\w-]+)\s*=", section(name), re.MULTILINE)
        }

    lib_aliases = aliases("libraries")
    plugin_aliases = aliases("plugins")
    version_aliases = aliases("versions")

    for gradle_file in walk("*.gradle.kts"):
        text = gradle_file.read_text(encoding="utf-8")
        for match in re.finditer(r"\blibs\.plugins\.([\w.]+)", text):
            if match.group(1) not in plugin_aliases:
                report("ERROR", rel(gradle_file), f"libs.plugins.{match.group(1)} not in catalog")
        for match in re.finditer(r"\blibs\.versions\.([\w.]+?)(?:\.get\(\))", text):
            if match.group(1) not in version_aliases:
                report("ERROR", rel(gradle_file), f"libs.versions.{match.group(1)} not in catalog")
        for match in re.finditer(r"\blibs\.(?!plugins\.|versions\.)([\w.]+)", text):
            alias = match.group(1)
            if alias not in lib_aliases:
                report("ERROR", rel(gradle_file), f"libs.{alias} not in catalog")

# ---------------------------------------------------------------------------
# 4. Rust modules and the JNI contract
# ---------------------------------------------------------------------------

for lib_rs in walk("src/lib.rs"):
    crate_dir = lib_rs.parent
    text = lib_rs.read_text(encoding="utf-8")
    for match in re.finditer(r"^\s*(?:pub\s+)?mod\s+(\w+)\s*;", text, re.MULTILINE):
        module = match.group(1)
        if not (crate_dir / f"{module}.rs").exists() and not (crate_dir / module / "mod.rs").exists():
            report("ERROR", rel(lib_rs), f"mod {module}; has no matching file")

ffi_path = ROOT / "native" / "apkcli-core" / "src" / "ffi.rs"
native_kt = ROOT / "core" / "src" / "main" / "kotlin" / "dev" / "apkcli" / "core" / "nativebridge" / "Native.kt"

if ffi_path.exists() and native_kt.exists():
    ffi_text = ffi_path.read_text(encoding="utf-8")
    kt_text = native_kt.read_text(encoding="utf-8")

    exported = {
        m.group(1)
        for m in re.finditer(r"Java_dev_apkcli_core_nativebridge_Native_(\w+)", ffi_text)
    }
    kotlin_externals = {
        m.group(1) for m in re.finditer(r"external\s+fun\s+(\w+)", kt_text)
    }

    for name in sorted(kotlin_externals - exported):
        report("ERROR", rel(native_kt), f"external fun {name} has no Rust export")
    for name in sorted(exported - kotlin_externals):
        report("WARN", rel(ffi_path), f"Rust exports {name} with no Kotlin declaration")

    # The exception class the Rust side throws by name must exist with a
    # String constructor, or every native failure becomes NoSuchMethodError.
    exception_fqn = re.search(r'EXCEPTION_CLASS[^"]*"([^"]+)"', ffi_text)
    if exception_fqn:
        dotted = exception_fqn.group(1).replace("/", ".")
        if dotted not in declared:
            report("ERROR", rel(ffi_path), f"throws {dotted}, which no Kotlin file declares")
        else:
            owner = declared[dotted]
            if "constructor(encoded: String)" not in sources.get(owner, ""):
                report(
                    "ERROR",
                    rel(owner),
                    "no single-String constructor; JNI throw_new will fail",
                )

# ---------------------------------------------------------------------------
# 5. Manifest classes
# ---------------------------------------------------------------------------

for manifest in walk("AndroidManifest.xml"):
    text = manifest.read_text(encoding="utf-8")
    module_root = manifest.parent.parent.parent  # src/main/AndroidManifest.xml -> module
    namespace_match = None
    for gradle_file in [module_root / "build.gradle.kts"]:
        if gradle_file.exists():
            namespace_match = re.search(
                r'namespace\s*=\s*"([\w.]+)"', gradle_file.read_text(encoding="utf-8")
            )
    namespace = namespace_match.group(1) if namespace_match else None

    for match in re.finditer(r'android:name="([\w.$]+)"', text):
        name = match.group(1)
        if not ("." in name or name.startswith(".")):
            continue
        if name.startswith("android.") or name.startswith("androidx."):
            continue
        fqn = f"{namespace}{name}" if name.startswith(".") and namespace else name
        if not fqn.startswith("dev.apkcli"):
            continue
        if fqn not in declared:
            report("ERROR", rel(manifest), f"android:name={name} -> {fqn} does not exist")

# ---------------------------------------------------------------------------
# 6. Balance check — catches a truncated or mis-merged edit
# ---------------------------------------------------------------------------

CHAR_LITERAL_RE = re.compile(r"'(?:\\.|[^\\'])'")


def strip_literals(text: str) -> str:
    """Remove strings, chars and comments so bracket counting means something.

    Two language details make a naive version wrong, and both bit this script:

      * **Rust raw strings.** `r#"...{...}..."#` holds the SQL schema, full of
        braces and parens. Escapes do not apply inside, and the hash count has
        to match.
      * **Rust lifetimes.** `Formatter<'_>` and `Row<'a>` start with a quote.
        Treating that as a char literal scans forward to the *next* quote
        anywhere in the file, swallowing whatever braces lie between — which is
        exactly what produced four phantom "unbalanced" findings.
    """
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]

        # Rust raw string: r"..." or r#"..."# with any number of hashes.
        if ch == "r" and i + 1 < n:
            probe = i + 1
            hashes = 0
            while probe < n and text[probe] == "#":
                hashes += 1
                probe += 1
            if probe < n and text[probe] == '"':
                terminator = '"' + "#" * hashes
                end = text.find(terminator, probe + 1)
                i = n if end < 0 else end + len(terminator)
                continue

        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end < 0 else end + 3
            continue

        if ch == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue

        if ch == "'":
            match = CHAR_LITERAL_RE.match(text, i)
            if match:
                i = match.end()
            else:
                # A lifetime. Emit nothing and step over just the quote.
                i += 1
            continue

        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
            continue

        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue

        out.append(ch)
        i += 1
    return "".join(out)


for path, text in sources.items():
    stripped = strip_literals(text)
    for open_ch, close_ch, label in (("{", "}", "braces"), ("(", ")", "parens"), ("[", "]", "brackets")):
        delta = stripped.count(open_ch) - stripped.count(close_ch)
        if delta != 0:
            report("ERROR", rel(path), f"unbalanced {label}: {delta:+d}")

# ---------------------------------------------------------------------------
# 7. Suspicious patterns worth a human look
# ---------------------------------------------------------------------------

SMELLS = [
    (r"\bTODO\b|\bFIXME\b|\btodo!\(|\bunimplemented!\(", "unfinished marker"),
    (r"\bprintln!\(|\bSystem\.out\.print", "stray print"),
    (r"\.printStackTrace\(\)", "printStackTrace instead of logging"),
    (r"GlobalScope", "GlobalScope leaks its coroutines"),
    (r"\brunBlocking\b", "runBlocking can deadlock on a dispatcher thread"),
    (r"\.unwrap\(\)", "unwrap() panics; across JNI that is an abort risk"),
    (r"\.expect\(", "expect() panics"),
    (r"!!", "!! throws NullPointerException"),
    (r"\bThread\(", "raw Thread; confirm it is daemon and bounded"),
]


def test_module_ranges(text: str) -> list[tuple[int, int]]:
    """Character ranges of `#[cfg(test)] mod tests { ... }` blocks.

    A panic in a test is the point of the test, so flagging `unwrap()` there is
    noise that buries the real findings.
    """
    ranges = []
    for match in re.finditer(r"#\[cfg\(test\)\]", text):
        brace = text.find("{", match.end())
        if brace < 0:
            continue
        end = matching_brace(text, brace)
        if end is not None:
            ranges.append((match.start(), end))
    return ranges


RUST_RAW_STRING = re.compile(r'b?r(#*)"')
RUST_CHAR = re.compile(r"'(?:\\u\{[0-9a-fA-F]+\}|\\x[0-9a-fA-F]{2}|\\.|[^'\\\n])'")


def matching_brace(text: str, open_at: int) -> int | None:
    """Index of the `}` closing the `{` at [open_at], skipping literals and comments.

    Counting raw braces got it wrong as soon as a test held one in a string —
    a regex like `r"fn a\\(\\) \\{"` — and the whole test module was then
    audited as production code.
    """
    depth = 0
    i = open_at
    n = len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            nl = text.find("\n", i)
            i = n if nl < 0 else nl
            continue
        if text.startswith("/*", i):
            close = text.find("*/", i + 2)
            i = n if close < 0 else close + 2
            continue
        raw = RUST_RAW_STRING.match(text, i)
        if raw and (i == 0 or not (text[i - 1].isalnum() or text[i - 1] == "_")):
            terminator = '"' + raw.group(1)
            close = text.find(terminator, raw.end())
            i = n if close < 0 else close + len(terminator)
            continue
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            i = j + 1
            continue
        if c == "'":
            char = RUST_CHAR.match(text, i)
            if char:  # otherwise a lifetime: `'a`
                i = char.end()
                continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


for path, text in sources.items():
    skip = test_module_ranges(text) if path.suffix == ".rs" else []
    for pattern, message in SMELLS:
        for match in re.finditer(pattern, text):
            if any(start <= match.start() <= end for start, end in skip):
                continue
            line = text.count("\n", 0, match.start()) + 1
            report("WARN", f"{rel(path)}:{line}", message)

# ---------------------------------------------------------------------------
# nested block comments
#
# Kotlin and Rust both nest /* */. A glob in prose ("/system/bin/*") silently
# opens a nested comment and swallows code until the next "*/" -- which was
# found 50 lines later inside the string "**/*.so" in app/build.gradle.kts,
# producing 232 cascading parse errors pointing at the wrong line. Cheap to
# detect, extremely expensive to debug by reading.
# ---------------------------------------------------------------------------

try:
    from check_nested_comments import scan as scan_nested
except ImportError:  # running from outside tools/
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from check_nested_comments import scan as scan_nested

for path in kotlin_files + rust_files + walk("*.kts"):
    text = sources.get(path)
    if text is None:
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
    for line, _col, depth in scan_nested(text):
        report(
            "ERROR",
            f"{rel(path)}:{line}",
            f"nested block comment (depth {depth}); the enclosing /* */ will not "
            "close where you think it does",
        )

# ---------------------------------------------------------------------------
# output
# ---------------------------------------------------------------------------

errors = [f for f in findings if f[0] == "ERROR"]
warnings = [f for f in findings if f[0] == "WARN"]

print(f"audited {len(kotlin_files)} Kotlin and {len(rust_files)} Rust files\n")

if errors:
    print(f"=== {len(errors)} ERRORS ===")
    for _, location, message in errors:
        print(f"  {location}: {message}")
    print()

if warnings:
    by_message: dict[str, list[str]] = defaultdict(list)
    for _, location, message in warnings:
        by_message[message].append(location)
    print(f"=== {len(warnings)} warnings ===")
    for message, locations in sorted(by_message.items(), key=lambda kv: -len(kv[1])):
        print(f"  {message} ({len(locations)})")
        for location in locations[:12]:
            print(f"      {location}")
        if len(locations) > 12:
            print(f"      ... and {len(locations) - 12} more")
    print()

if not errors and not warnings:
    print("clean")

sys.exit(len(errors))
