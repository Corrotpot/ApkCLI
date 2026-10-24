#!/usr/bin/env python3
"""Probe each scheme's model-listing route. No credentials are sent.

    python tools/probe_models.py

Answers one question per scheme: is there a listing route, is a credential the
only thing missing, and does the answer carry the limits ModelSpec needs. Run
it when the schemes in docs/PROVIDERS.md need re-checking -- the percentages
there came from this script.

A 401 is the good answer for the keyed routes: it proves the route exists and
that a key is the only thing between the app and the list.
"""
import json
import ssl
import urllib.error
import urllib.request

TIMEOUT = 30
UA = {"User-Agent": "apkcli-probe"}


def get(url, headers=None, label=""):
    request = urllib.request.Request(url, headers={**UA, **(headers or {})})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read()
        except (urllib.error.URLError, ssl.SSLError, TimeoutError) as exc:
            if attempt == 3:
                print(f"  {label}: transport failure: {exc}")
                return None, b""
    return None, b""


def short(body, n=300):
    try:
        return json.dumps(json.loads(body), ensure_ascii=False)[:n]
    except Exception:  # noqa: BLE001
        return body[:n].decode("utf-8", "replace")


print("=" * 78)
print("OpenRouter  GET /api/v1/models  (docs say no auth needed)")
status, body = get("https://openrouter.ai/api/v1/models", label="openrouter")
print("  status:", status)
if status == 200:
    data = json.loads(body)["data"]
    print("  models:", len(data))
    total = len(data)

    def share(name, test):
        hits = sum(1 for m in data if test(m))
        print(f"    {name:34} {hits:5}/{total}  {100 * hits // total:3}%")

    share("context_length", lambda m: m.get("context_length"))
    share("top_provider.context_length", lambda m: (m.get("top_provider") or {}).get("context_length"))
    share("top_provider.max_completion_tokens", lambda m: (m.get("top_provider") or {}).get("max_completion_tokens"))
    share("architecture.input_modalities", lambda m: (m.get("architecture") or {}).get("input_modalities"))
    share("supported_parameters", lambda m: m.get("supported_parameters"))
    share("  ...includes tools", lambda m: "tools" in (m.get("supported_parameters") or []))
    share("reasoning (object present)", lambda m: m.get("reasoning"))
    share("reasoning.supported_efforts", lambda m: (m.get("reasoning") or {}).get("supported_efforts"))
    share("pricing.prompt", lambda m: (m.get("pricing") or {}).get("prompt"))
    sample = next((m for m in data if (m.get("reasoning") or {}).get("supported_efforts")), data[0])
    print("  sample:", json.dumps({
        "id": sample.get("id"),
        "context_length": sample.get("context_length"),
        "top_provider": sample.get("top_provider"),
        "input_modalities": (sample.get("architecture") or {}).get("input_modalities"),
        "reasoning": sample.get("reasoning"),
    }, ensure_ascii=False)[:600])
else:
    print("  body:", short(body))

print("=" * 78)
print("OpenAI  GET /v1/models  (no key)")
status, body = get("https://api.openai.com/v1/models", label="openai")
print("  status:", status, "->", short(body, 240))

print("=" * 78)
print("Anthropic  GET /v1/models  (no key, with the version header)")
status, body = get(
    "https://api.anthropic.com/v1/models",
    {"anthropic-version": "2023-06-01"},
    label="anthropic",
)
print("  status:", status, "->", short(body, 240))

print("Anthropic  GET /v1/models  (no key, no version header)")
status, body = get("https://api.anthropic.com/v1/models", label="anthropic-noversion")
print("  status:", status, "->", short(body, 240))

print("=" * 78)
print("Kiro  the AWS surface, no listing route is documented")
for url in (
    "https://codewhisperer.us-east-1.amazonaws.com/",
    "https://codewhisperer.us-east-1.amazonaws.com/v1/models",
):
    status, body = get(url, label=url)
    print(f"  {url}\n    status: {status} -> {short(body, 160)}")
