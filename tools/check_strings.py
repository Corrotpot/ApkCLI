"""Compare the string resources the code uses with the ones defined.

Prints, for EN (values/) and RU (values-ru/):
  missing  - referenced by R.string / R.plurals / @string/ but not defined
  unused   - defined but referenced nowhere
  args     - a name whose %N$ placeholders differ between EN and RU

Usage: python tools/check_strings.py [--unused]
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "app" / "src" / "main"
RES = APP / "res"

REF_CODE = re.compile(r"R\.(string|plurals)\.([A-Za-z0-9_]+)")
REF_XML = re.compile(r"@(string|plurals)/([A-Za-z0-9_]+)")
ARG = re.compile(r"%(\d+)\$")


def referenced():
    refs = set()
    for path in (APP / "kotlin").rglob("*.kt"):
        for kind, name in REF_CODE.findall(path.read_text(encoding="utf-8")):
            refs.add((kind, name))
    for path in list(RES.rglob("*.xml")) + [APP / "AndroidManifest.xml"]:
        if "values" in path.parent.name:
            continue
        for kind, name in REF_XML.findall(path.read_text(encoding="utf-8")):
            refs.add((kind, name))
    return refs


def text_of(element):
    return "".join(element.itertext())


def defined(folder):
    out = {}
    path = RES / folder / "strings.xml"
    tree = ET.parse(path)
    for element in tree.getroot():
        name = element.get("name")
        if element.tag == "string":
            out[("string", name)] = text_of(element)
        elif element.tag == "plurals":
            out[("plurals", name)] = " ".join(text_of(item) for item in element)
    return out


def main():
    refs = referenced()
    en = defined("values")
    ru = defined("values-ru")
    for label, table in (("EN", en), ("RU", ru)):
        missing = sorted(refs - set(table))
        print(f"{label} missing ({len(missing)}):")
        for kind, name in missing:
            print(f"  {kind} {name}")
    if "--unused" in sys.argv:
        unused = sorted(set(en) - refs)
        print(f"unused ({len(unused)}):")
        for kind, name in unused:
            print(f"  {kind} {name}")
        only_ru = sorted(set(ru) - set(en))
        print(f"RU only ({len(only_ru)}):")
        for kind, name in only_ru:
            print(f"  {kind} {name}")
    print("args differ:")
    for key in sorted(set(en) & set(ru)):
        if set(ARG.findall(en[key])) != set(ARG.findall(ru[key])):
            print(f"  {key[0]} {key[1]}: EN {sorted(set(ARG.findall(en[key])))} RU {sorted(set(ARG.findall(ru[key])))}")


if __name__ == "__main__":
    main()
