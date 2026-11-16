# -*- coding: utf-8 -*-
"""Locale batch translation via Xiaomi MiMo Batch API.

Translates assets/locales/zh-cn.json into the eight target languages plus
zh-hant / zh-hk, using one batch request per language.

Usage:
  python translate_locales.py build     # write input.jsonl
  python translate_locales.py submit    # upload + create batch -> batch_id.txt
  python translate_locales.py status    # poll the batch
  python translate_locales.py apply     # download output -> locales/*.json

Env: MIMO_API_KEY (sk-...), optional MIMO_BATCH_REGION (default cn).
Batch tasks run in idle hours (01:00-08:00) — submit at night or just wait.
"""
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "assets", "locales", "zh-cn.json")
OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "locales")
INPUT = os.path.join(HERE, "input.jsonl")
OUTPUT = os.path.join(HERE, "output.jsonl")
BATCH_ID_FILE = os.path.join(HERE, "batch_id.txt")

REGION = os.environ.get("MIMO_BATCH_REGION", "cn")
BASE = f"https://batch-api-{REGION}.xiaomimimo.com/v1"
MODEL = "mimo-v2.6-flash"

# lang code -> (translator name, system prompt extra)
TARGETS = {
    "en":     "English",
    "ja":     "Japanese",
    "ko":     "Korean",
    "fr":     "French",
    "de":     "German",
    "es":     "Spanish",
    "ru":     "Russian",
    "pt":     "Portuguese",
    "zh-hant": "Traditional Chinese (Taiwan wording)",
    "zh-hk":  "Traditional Chinese (Hong Kong wording)",
}

BASE_RULES = """You are a professional app localizer for an Android chat app (UI strings).
Rules:
1. Keep every JSON key EXACTLY as given; translate only the values.
2. Preserve placeholders verbatim: {lang}, {n}, {m}, {name}, {reason}, {error} and any ${...} style tokens.
3. Keep technical tokens unchanged: zh-cn.json, sk-xxxxx, API, APK, MiMo, Xiaomi.
4. The app name 知白 is a brand name: keep it as-is (do not translate).
5. Use concise, direct UI phrasing. No emoji. No explanations.
6. Keep the trailing ellipsis style "…" where present.
7. Output ONLY a single JSON object — no markdown fences, no commentary."""


def api_key() -> str:
    k = os.environ.get("MIMO_API_KEY", "").strip()
    if not k:
        sys.exit("MIMO_API_KEY not set")
    return k


def req(method, url, headers=None, data=None, raw_body=None, form=None):
    h = {"Authorization": f"Bearer {api_key()}"}
    if headers:
        h.update(headers)
    body = None
    if form is not None:
        # multipart/form-data — (filename, content); filename="" → plain field
        boundary = "----mimobatch" + str(int(time.time() * 1000))
        parts = []
        for name, (filename, content) in form.items():
            if filename:
                disp = f"form-data; name=\"{name}\"; filename=\"{filename}\""
                ctype = b"Content-Type: application/octet-stream\r\n"
            else:
                disp = f"form-data; name=\"{name}\""
                ctype = b""
            parts.append(f"--{boundary}\r\nContent-Disposition: {disp}\r\n".encode() + ctype + b"\r\n" + content + b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        body = b"".join(parts)
        h["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif raw_body is not None:
        body = raw_body
    elif data is not None:
        body = json.dumps(data).encode()
        h["Content-Type"] = "application/json"
    r = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:800]}")


def cmd_build():
    with open(SRC, encoding="utf-8") as f:
        src = json.load(f)
    flat = json.dumps(src, ensure_ascii=False)
    with open(INPUT, "w", encoding="utf-8") as f:
        for code, lang in TARGETS.items():
            if code in ("zh-hant", "zh-hk"):
                sysline = (
                    f"You are a professional app localizer converting Simplified Chinese UI strings "
                    f"to {lang}. Same meaning, natural localized wording ({'Taiwan' if code == 'zh-hant' else 'Hong Kong'} conventions)."
                )
            else:
                sysline = f"You are a professional app localizer translating Simplified Chinese UI strings to {lang}."
            rec = {
                "custom_id": f"locale-{code}",
                "method": "POST",
                "url": "/v1/chat/completions",
                "body": {
                    "model": MODEL,
                    "temperature": 0.2,
                    "messages": [
                        {"role": "system", "content": sysline + "\n\n" + BASE_RULES},
                        {"role": "user", "content": "Translate these UI strings (JSON):\n" + flat},
                    ],
                },
            }
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"wrote {INPUT}: {len(TARGETS)} requests, {os.path.getsize(INPUT)} bytes")


def cmd_submit():
    with open(INPUT, "rb") as f:
        up = req("POST", f"{BASE}/files", form={
            "purpose": ("", b"batch"),
            "file": ("input.jsonl", f.read()),
        })
    print("uploaded:", up.get("id"), up.get("status"))
    fid = up["id"]
    batch = req("POST", f"{BASE}/batches", data={
        "input_file_id": fid,
        "endpoint": "/v1/chat/completions",
        "completion_window": "24h",
        "name": "zhibai-locales-10lang",
    })
    with open(BATCH_ID_FILE, "w") as f:
        f.write(batch["id"])
    print("batch created:", batch["id"], "status:", batch.get("status"))
    print("NOTE: batch runs in idle hours (01:00-08:00) — check back later with: python translate_locales.py status")


def cmd_status():
    if not os.path.exists(BATCH_ID_FILE):
        sys.exit("no batch_id.txt — run submit first")
    bid = open(BATCH_ID_FILE).read().strip()
    b = req("GET", f"{BASE}/batches/{bid}")
    rc = b.get("request_counts") or {}
    print(f"status={b.get('status')} completed={rc.get('completed', 0)}/{rc.get('total', 0)} failed={rc.get('failed', 0)}")
    print(f"output_file_id={b.get('output_file_id')} error_file_id={b.get('error_file_id')}")
    return b


def cmd_apply():
    b = cmd_status()
    of = b.get("output_file_id")
    if not of:
        sys.exit("no output_file_id yet")
    raw = urllib.request.Request(f"{BASE}/files/{of}/content", headers={"Authorization": f"Bearer {api_key()}"})
    with urllib.request.urlopen(raw, timeout=120) as resp:
        payload = resp.read().decode("utf-8")
    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write(payload)
    print(f"downloaded {OUTPUT} ({len(payload)} bytes)")
    ok = 0
    for line in payload.splitlines():
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        code = rec["custom_id"].replace("locale-", "")
        body = rec.get("response", {}).get("body", {})
        content = body.get("choices", [{}])[0].get("message", {}).get("content", "")
        content = re.sub(r"^```[a-z]*\n?", "", content.strip())
        content = re.sub(r"\n?```$", "", content)
        try:
            table = json.loads(content)
        except Exception as e:
            print(f"SKIP {code}: bad JSON ({e})")
            continue
        # sanity: keys must match source
        src_keys = set(json.load(open(SRC, encoding="utf-8")).keys())
        if set(table.keys()) != src_keys:
            missing = src_keys - set(table.keys())
            extra = set(table.keys()) - src_keys
            print(f"WARN {code}: key mismatch missing={sorted(missing)[:5]} extra={sorted(extra)[:5]}")
        path = os.path.join(OUT_DIR, f"{code}.json")
        with open(path, "w", encoding="utf-8", newline="") as f:
            json.dump(table, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"wrote {path} ({len(table)} keys)")
        ok += 1
    print(f"done: {ok}/{len(TARGETS)} locales written")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "build":
        cmd_build()
    elif cmd == "submit":
        cmd_submit()
    elif cmd == "status":
        cmd_status()
    elif cmd == "apply":
        cmd_apply()
    else:
        print(__doc__)
