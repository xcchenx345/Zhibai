# -*- coding: utf-8 -*-
"""Delta translation via Xiaomi MiMo realtime chat API.

After new keys are added to zh-cn.json (i18n migration), this translates ONLY
the keys missing from each locale file and merges them in. Realtime — no batch
scheduling wait.

Usage:
  python translate_delta.py            # all target locales
  python translate_delta.py en ja      # only these locale codes

Env: MIMO_API_KEY (sk-...) for https://api.xiaomimimo.com/v1
"""
import json
import os
import re
import sys
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "locales")
SRC = os.path.join(OUT_DIR, "zh-cn.json")

BASE = "https://api.xiaomimimo.com/v1"
MODEL = "mimo-v2.6-flash"
CHUNK = 120  # keys per request — keeps outputs under token limits

TARGETS = {
    "en":      "English",
    "ja":      "Japanese",
    "ko":      "Korean",
    "fr":      "French",
    "de":      "German",
    "es":      "Spanish",
    "ru":      "Russian",
    "pt":      "Portuguese",
    "zh-hant": "Traditional Chinese (Taiwan wording)",
    "zh-hk":   "Traditional Chinese (Hong Kong wording)",
}

BASE_RULES = """You are a professional app localizer for an Android chat app (UI strings).
Rules:
1. Keep every JSON key EXACTLY as given; translate only the values.
2. Preserve placeholders verbatim: {lang}, {n}, {m}, {name}, {reason}, {error} and any ${...} style tokens.
3. Keep technical tokens unchanged: zh-cn.json, sk-xxxxx, API, APK, MiMo, Xiaomi, ADB, AutoGLM, TTS, ASR.
4. The app name 知白 is a brand name: keep it as-is (do not translate).
5. Use concise, direct UI phrasing. No emoji. No explanations.
6. Keep the trailing ellipsis style "…" where present.
7. Output ONLY a single JSON object — no markdown fences, no commentary."""


def api_key() -> str:
    k = os.environ.get("MIMO_API_KEY", "").strip()
    if not k:
        sys.exit("MIMO_API_KEY not set")
    return k


def chat(system: str, user: str) -> str:
    body = json.dumps({
        "model": MODEL,
        "temperature": 0.2,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    }).encode()
    r = urllib.request.Request(
        f"{BASE}/chat/completions",
        data=body,
        headers={"Authorization": f"Bearer {api_key()}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(r, timeout=300) as resp:
            data = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:800]}")
    return data["choices"][0]["message"]["content"]


def parse_table(content: str, expect_keys) -> dict:
    content = content.strip()
    content = re.sub(r"^```[a-z]*\n?", "", content)
    content = re.sub(r"\n?```$", "", content)
    table = json.loads(content)
    missing = set(expect_keys) - set(table.keys())
    extra = set(table.keys()) - set(expect_keys)
    if missing or extra:
        print(f"  WARN key mismatch missing={sorted(missing)[:5]} extra={sorted(extra)[:5]}")
        for k in list(extra):
            del table[k]
    return table


def translate_one(code: str, lang: str, delta: dict, save=None) -> dict:
    if code in ("zh-hant", "zh-hk"):
        sysline = (
            f"You are a professional app localizer converting Simplified Chinese UI strings "
            f"to {lang}. Same meaning, natural localized wording ({'Taiwan' if code == 'zh-hant' else 'Hong Kong'} conventions)."
        )
    else:
        sysline = f"You are a professional app localizer translating Simplified Chinese UI strings to {lang}."
    system = sysline + "\n\n" + BASE_RULES
    out = {}
    items = list(delta.items())
    for i in range(0, len(items), CHUNK):
        chunk = dict(items[i:i + CHUNK])
        flat = json.dumps(chunk, ensure_ascii=False)
        print(f"  chunk {i // CHUNK + 1}: {len(chunk)} keys...")
        for attempt in range(3):
            content = chat(system, "Translate these UI strings (JSON):\n" + flat)
            try:
                out.update(parse_table(content, chunk.keys()))
                break
            except Exception as e:
                print(f"  retry {attempt + 1}: bad output ({e})")
        else:
            print(f"  SKIP chunk {i // CHUNK + 1} after retries")
        if save:
            save(out)
    return out


def main():
    wanted = set(sys.argv[1:]) & set(TARGETS.keys()) if len(sys.argv) > 1 else set(TARGETS)
    if not wanted:
        sys.exit(f"no valid locale codes in {sys.argv[1:]}")
    src = json.load(open(SRC, encoding="utf-8"))
    for code, lang in TARGETS.items():
        if code not in wanted:
            continue
        path = os.path.join(OUT_DIR, f"{code}.json")
        cur = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
        delta = {k: v for k, v in src.items() if k not in cur}
        if not delta:
            print(f"{code}: up to date ({len(cur)} keys)")
            continue
        print(f"{code}: {len(delta)} missing of {len(src)} -> translating to {lang}")

        def save_partial(add, cur=cur, path=path):
            cur.update(add)
            with open(path, "w", encoding="utf-8", newline="") as f:
                json.dump(cur, f, ensure_ascii=False, indent=2)
                f.write("\n")

        add = translate_one(code, lang, delta, save=save_partial)
        cur.update(add)
        with open(path, "w", encoding="utf-8", newline="") as f:
            json.dump(cur, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"{code}: wrote {path} ({len(cur)} keys, +{len(add)})")
    print("done")


if __name__ == "__main__":
    main()
