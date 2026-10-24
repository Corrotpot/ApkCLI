#!/usr/bin/env python3
"""Neutralise DT_RPATH / DT_RUNPATH in an ELF file, in place.

Why not patchelf: `patchelf --remove-rpath` rebuilds the program header table
and can change the number and ordering of PT_LOAD segments. Android's linker
(11+) validates segment layout strictly and rejects the result with
"has invalid ELF header" or a bad-mapping abort.

What this does instead: find the .dynamic section, locate DT_RPATH (15) and
DT_RUNPATH (29) entries, and set their d_val to 0 — which makes the string
offset point at the mandatory empty string at index 0 of .dynstr. Nothing
moves, nothing is resized, every offset in the file stays valid.

Usage:
    python3 zero-runpath.py libgit.so [more.so ...]
"""

from __future__ import annotations

import struct
import sys

DT_NULL = 0
DT_RPATH = 15
DT_RUNPATH = 29

PT_DYNAMIC = 2


def patch(path: str) -> int:
    with open(path, "r+b") as fh:
        data = bytearray(fh.read())

        if data[:4] != b"\x7fELF":
            print(f"{path}: not an ELF file", file=sys.stderr)
            return 0

        ei_class = data[4]          # 1 = 32-bit, 2 = 64-bit
        ei_data = data[5]           # 1 = little endian
        endian = "<" if ei_data == 1 else ">"
        is64 = ei_class == 2

        # --- program header table location -------------------------------
        if is64:
            e_phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
            e_phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
            e_phnum = struct.unpack_from(endian + "H", data, 0x38)[0]
        else:
            e_phoff = struct.unpack_from(endian + "I", data, 0x1C)[0]
            e_phentsize = struct.unpack_from(endian + "H", data, 0x2A)[0]
            e_phnum = struct.unpack_from(endian + "H", data, 0x2C)[0]

        # --- find PT_DYNAMIC ---------------------------------------------
        dyn_off = dyn_size = 0
        for i in range(e_phnum):
            base = e_phoff + i * e_phentsize
            p_type = struct.unpack_from(endian + "I", data, base)[0]
            if p_type != PT_DYNAMIC:
                continue
            if is64:
                dyn_off = struct.unpack_from(endian + "Q", data, base + 0x08)[0]
                dyn_size = struct.unpack_from(endian + "Q", data, base + 0x20)[0]
            else:
                dyn_off = struct.unpack_from(endian + "I", data, base + 0x04)[0]
                dyn_size = struct.unpack_from(endian + "I", data, base + 0x14)[0]
            break

        if not dyn_off:
            print(f"{path}: no PT_DYNAMIC segment, nothing to do")
            return 0

        # --- walk the dynamic array --------------------------------------
        entry_size = 16 if is64 else 8
        tag_fmt = endian + ("q" if is64 else "i")
        val_fmt = endian + ("Q" if is64 else "I")

        patched = 0
        pos = dyn_off
        end = dyn_off + dyn_size
        while pos + entry_size <= min(end, len(data)):
            d_tag = struct.unpack_from(tag_fmt, data, pos)[0]
            if d_tag == DT_NULL:
                break
            if d_tag in (DT_RPATH, DT_RUNPATH):
                struct.pack_into(val_fmt, data, pos + (entry_size // 2), 0)
                patched += 1
                name = "DT_RPATH" if d_tag == DT_RPATH else "DT_RUNPATH"
                print(f"{path}: zeroed {name}")
            pos += entry_size

        if patched:
            fh.seek(0)
            fh.write(data)
            fh.truncate()
        else:
            print(f"{path}: no RPATH/RUNPATH present")
        return patched


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    total = 0
    for path in argv[1:]:
        try:
            total += patch(path)
        except OSError as exc:
            print(f"{path}: {exc}", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
