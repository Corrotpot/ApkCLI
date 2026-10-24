#!/usr/bin/env python3
"""Print the design prototype's model lists, from models.dev.

    python tools/gen_catalogue.py            # paste the output into app.js
    python tools/gen_catalogue.py --refresh  # re-download the index first

The app never ships a model list: it asks the endpoint's own `GET /models`
after a key is saved. These lists only exist so the prototype's screens have
something to draw, so nothing here names a model version. Each connection
declares a rule -- which models.dev provider, which authors, how many -- and
the newest matching models win. Re-run it and the lists move on by themselves.

models.dev is the open model database OpenCode builds its provider catalogue
from, so the fields are the ones OpenCode's provider config carries:
`limit.context`, `limit.output`, and the `attachment` / `reasoning` /
`tool_call` capability flags. They map onto the app's ModelSpec as
context -> contextWindow, output -> maxOutputTokens, tool_call -> supportsTools.

The numbers are each model's own specification; a gateway in front of a model
may allow less, which is why the Kiro rows say so.

Standard library only. The index (~5 MB) is cached in build/design-cache.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

URL = "https://models.dev/api.json"
CACHE = Path(__file__).resolve().parent.parent / "build" / "design-cache" / "models-dev.json"

# Model families that are not what a coding agent talks to.
SKIP = ("image", "realtime", "audio", "tts", "whisper", "embed", "moderation", "search", "transcribe")

RULES = {
    # connection: (models.dev provider, authors to keep or None, how many, bare ids?)
    "anthropic": ("anthropic", None, 6, False),
    "openai": ("openai", None, 6, False),
    "openrouter": (
        "openrouter",
        ("anthropic", "openai", "google", "z-ai", "deepseek", "qwen", "moonshotai", "minimax", "x-ai"),
        10,
        False,
    ),
    # Whatever the gateway's tier exposes: Claude plus the open-weight models
    # Kiro proxies, served under bare ids.
    "kiro": ("anthropic", None, 3, True),
    "kiro+": (
        "openrouter",
        ("z-ai", "deepseek", "qwen", "minimax"),
        4,
        True,
    ),
}


def index(refresh: bool) -> dict:
    if refresh and CACHE.exists():
        CACHE.unlink()
    if not CACHE.exists():
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        last: Exception | None = None
        for _ in range(6):
            try:
                request = urllib.request.Request(URL, headers={"User-Agent": "apkcli-gen-catalogue"})
                with urllib.request.urlopen(request, timeout=120) as response:
                    CACHE.write_bytes(response.read())
                break
            except Exception as exc:  # noqa: BLE001 - retry any transport failure
                last = exc
                time.sleep(2)
        else:
            raise SystemExit(f"error: could not download {URL}: {last}")
    return json.loads(CACHE.read_text(encoding="utf-8"))


def pick(data: dict, provider: str, authors, count: int, bare: bool) -> list[dict]:
    models = data.get(provider, {}).get("models")
    if not models:
        raise SystemExit(f"error: models.dev has no provider {provider!r}")
    chosen = []
    for model in models.values():
        model_id = model.get("id", "")
        if authors and model_id.split("/")[0] not in authors:
            continue
        if any(word in model_id.lower() for word in SKIP):
            continue
        if not model.get("tool_call"):
            continue
        # A model that draws is not one an agent writes code with, whatever
        # its name says.
        if "image" in ((model.get("modalities") or {}).get("output") or []):
            continue
        limit = model.get("limit") or {}
        if not limit.get("context") or not limit.get("output"):
            continue
        chosen.append(model)
    chosen.sort(key=lambda m: (m.get("release_date") or "", m.get("id", "")), reverse=True)

    rows = []
    for model in chosen[:count]:
        model_id = model.get("id", "")
        limit = model["limit"]
        inputs = (model.get("modalities") or {}).get("input") or []
        rows.append({
            "id": model_id.split("/")[-1] if bare else model_id,
            # "Claude Opus 5.5 (latest)" is the alias's name, not a model's.
            "name": (model.get("name") or model_id).replace(" (latest)", ""),
            "context": limit["context"],
            "output": limit["output"],
            "vision": "image" in inputs,
            "reasoning": bool(model.get("reasoning")),
        })
    return rows


def render(rows: list[dict]) -> str:
    out = []
    for row in rows:
        fields = [f"{k}: {json.dumps(row[k], ensure_ascii=False)}" for k in ("id", "name", "context", "output")]
        if row["vision"]:
            fields.append("vision: true")
        if row["reasoning"]:
            fields.append("reasoning: true")
        out.append("    { " + ", ".join(fields) + " },")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--refresh", action="store_true", help="re-download the index")
    args = parser.parse_args()
    data = index(args.refresh)

    print("const CATALOGUE = {")
    for connection in ("anthropic", "openai", "openrouter", "kiro"):
        rows = pick(data, *RULES[connection])
        if connection == "kiro":
            rows += pick(data, *RULES["kiro+"])
        print(f"  {connection}: [")
        print(render(rows))
        print("  ],")
    print("  custom: [],")
    print("};")
    return 0


if __name__ == "__main__":
    sys.exit(main())
