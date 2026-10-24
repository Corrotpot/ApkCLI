#!/usr/bin/env python3
"""Verify zero-runpath.py against synthetic ELFs.

The real binaries this is meant for cannot be built here, and the Android
system libraries available for testing have no RUNPATH, so the write path — the
part that could corrupt a 25 MB payload — goes unexercised. These fixtures cover
it: a 64-bit and a 32-bit ELF, each with a PT_DYNAMIC segment holding
DT_RUNPATH, DT_RPATH, DT_NEEDED and DT_NULL.

Checks that matter:
  * DT_RUNPATH and DT_RPATH d_val become 0.
  * DT_NEEDED is untouched — zeroing the wrong tag would unlink a dependency.
  * File size is unchanged. Resizing is what breaks Android's segment-count
    validation, which is the whole reason this exists instead of patchelf.

Usage:  python tools/test_zero_runpath.py
"""

from __future__ import annotations

import struct
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PATCHER = HERE / "zero-runpath.py"

DT_NEEDED, DT_RPATH, DT_RUNPATH, DT_NULL = 1, 15, 29, 0
PT_DYNAMIC = 2


def build_elf(is64: bool) -> tuple[bytes, int]:
    """Return (image, dynamic_offset) for a minimal but well-formed ELF."""
    ehsize = 64 if is64 else 52
    phentsize = 56 if is64 else 32
    phoff = ehsize
    phnum = 1
    dyn_off = phoff + phentsize * phnum

    entries = [
        (DT_NEEDED, 0x11),
        (DT_RUNPATH, 0x22),
        (DT_RPATH, 0x33),
        (DT_NULL, 0),
    ]
    entry_fmt = "<qQ" if is64 else "<iI"
    entry_size = 16 if is64 else 8
    dynamic = b"".join(struct.pack(entry_fmt, tag, val) for tag, val in entries)
    dyn_size = len(dynamic)

    # --- ELF header --------------------------------------------------------
    header = bytearray(ehsize)
    header[0:4] = b"\x7fELF"
    header[4] = 2 if is64 else 1          # EI_CLASS
    header[5] = 1                          # EI_DATA little endian
    header[6] = 1                          # EI_VERSION
    struct.pack_into("<H", header, 16, 3)  # e_type ET_DYN
    struct.pack_into("<H", header, 18, 0xB7 if is64 else 0x28)  # aarch64 / arm

    if is64:
        struct.pack_into("<Q", header, 0x20, phoff)
        struct.pack_into("<H", header, 0x36, phentsize)
        struct.pack_into("<H", header, 0x38, phnum)
    else:
        struct.pack_into("<I", header, 0x1C, phoff)
        struct.pack_into("<H", header, 0x2A, phentsize)
        struct.pack_into("<H", header, 0x2C, phnum)

    # --- program header: one PT_DYNAMIC ------------------------------------
    ph = bytearray(phentsize)
    struct.pack_into("<I", ph, 0, PT_DYNAMIC)
    if is64:
        struct.pack_into("<Q", ph, 0x08, dyn_off)   # p_offset
        struct.pack_into("<Q", ph, 0x20, dyn_size)  # p_filesz
    else:
        struct.pack_into("<I", ph, 0x04, dyn_off)   # p_offset
        struct.pack_into("<I", ph, 0x14, dyn_size)  # p_filesz

    # Trailing bytes stand in for the rest of a real image; the patcher must
    # not touch them.
    tail = b"\xAB" * 256
    return bytes(header) + bytes(ph) + dynamic + tail, dyn_off


def read_entries(image: bytes, dyn_off: int, is64: bool) -> list[tuple[int, int]]:
    entry_fmt = "<qQ" if is64 else "<iI"
    entry_size = 16 if is64 else 8
    out = []
    pos = dyn_off
    while pos + entry_size <= len(image):
        tag, val = struct.unpack_from(entry_fmt, image, pos)
        out.append((tag, val))
        if tag == DT_NULL:
            break
        pos += entry_size
    return out


def run_case(is64: bool) -> list[str]:
    label = "ELF64" if is64 else "ELF32"
    failures: list[str] = []
    image, dyn_off = build_elf(is64)

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / f"test{'64' if is64 else '32'}.so"
        path.write_bytes(image)
        original_size = path.stat().st_size

        result = subprocess.run(
            [sys.executable, str(PATCHER), str(path)],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            failures.append(f"{label}: patcher exited {result.returncode}: {result.stderr.strip()}")
            return failures

        patched = path.read_bytes()

        if path.stat().st_size != original_size:
            failures.append(
                f"{label}: size changed {original_size} -> {path.stat().st_size}"
            )

        entries = dict(read_entries(patched, dyn_off, is64))

        if entries.get(DT_RUNPATH) != 0:
            failures.append(f"{label}: DT_RUNPATH is {entries.get(DT_RUNPATH)}, expected 0")
        if entries.get(DT_RPATH) != 0:
            failures.append(f"{label}: DT_RPATH is {entries.get(DT_RPATH)}, expected 0")
        if entries.get(DT_NEEDED) != 0x11:
            failures.append(
                f"{label}: DT_NEEDED was modified to {entries.get(DT_NEEDED):#x} "
                "— a dependency would be unlinked"
            )
        if not patched.endswith(b"\xAB" * 256):
            failures.append(f"{label}: trailing image bytes were modified")
        if patched[:16] != image[:16]:
            failures.append(f"{label}: ELF identification bytes were modified")

        if "zeroed DT_RUNPATH" not in result.stdout:
            failures.append(f"{label}: did not report zeroing DT_RUNPATH")

    return failures


def main() -> int:
    if not PATCHER.exists():
        print(f"patcher not found at {PATCHER}", file=sys.stderr)
        return 2

    all_failures = run_case(is64=True) + run_case(is64=False)

    # A non-ELF file must be refused, not silently mangled.
    with tempfile.TemporaryDirectory() as tmp:
        junk = Path(tmp) / "notelf.so"
        junk.write_bytes(b"this is not an elf file" * 10)
        before = junk.read_bytes()
        subprocess.run([sys.executable, str(PATCHER), str(junk)], capture_output=True)
        if junk.read_bytes() != before:
            all_failures.append("non-ELF input was modified")

    if all_failures:
        print("FAIL")
        for failure in all_failures:
            print(f"  {failure}")
        return 1

    print("ok: ELF64 and ELF32 RUNPATH/RPATH zeroed, NEEDED preserved, size unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
