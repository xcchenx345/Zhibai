# -*- coding: utf-8 -*-
import json, os, re, sys

ROOT = r"C:\Users\abc15\MiMoApp\app\src\main\java\com\mimo\app"
MAP = r"C:\Users\abc15\MiMoApp\i18n\map.json"
OUT = r"E:\Users\abc15\Downloads\batch_4ecc8b5e55e542bb82e169f4_output.jsonl"

with open(MAP, encoding="utf-8") as f:
    sidecar = json.load(f)

# parse output → {custom_id: {s0: zh, ...}}
translations = {}
with open(OUT, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        cid = rec["custom_id"]
        resp = rec.get("response")
        if not resp or resp.get("status_code") != 200:
            print("SKIP fail", cid)
            continue
        content = resp["body"]["choices"][0]["message"]["content"]
        # strip accidental markdown fences
        content = content.strip()
        if content.startswith("```"):
            content = re.sub(r"^```[a-z]*\n?", "", content)
            content = re.sub(r"\n?```$", "", content)
        translations[cid] = json.loads(content)

def kotlin_escape_fix(orig, trans):
    """Make translation escape style match the original Kotlin literal."""
    # If original contains literal backslash-n (2 chars), translation should too
    if "\\n" in orig:
        # real newlines → literal \n
        trans = trans.replace("\r\n", "\n").replace("\n", "\\n")
        # double-escaped leftovers → single
        trans = trans.replace("\\\\n", "\\n")
    else:
        # original has no \n; if translation introduced real newlines, collapse
        if "\n" in trans:
            # keep single line
            trans = trans.replace("\n", " ")
    # leftover MiMoApp in paths → 知白
    trans = trans.replace("MiMoApp", "知白").replace("/sdcard/知白/", "/sdcard/知白/")
    return trans

total_repl = 0
for cid, meta in sidecar.items():
    fname = meta["file"]
    origs = meta["strings"]
    trans_map = translations.get(cid)
    if not trans_map:
        print("NO TRANSLATION", cid, fname)
        continue
    path = os.path.join(ROOT, fname.replace("/", os.sep))
    with open(path, encoding="utf-8") as f:
        src = f.read()
    n = 0
    for i, orig in enumerate(origs):
        key = f"s{i}"
        if key not in trans_map:
            print(f"  missing {cid}.{key}: {orig[:50]}")
            continue
        zh = kotlin_escape_fix(orig, trans_map[key])
        if zh == orig:
            continue
        # replace only inside quoted string literals: "orig"
        needle = '"' + orig + '"'
        repl = '"' + zh + '"'
        cnt = src.count(needle)
        if cnt == 0:
            # try without requiring exact — maybe quote style differs
            print(f"  MISS {cid}.{key} in {fname}: {orig[:60]!r}")
            continue
        src = src.replace(needle, repl)
        n += cnt
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(src)
    print(f"{fname}: {n} replacements")
    total_repl += n

print("TOTAL", total_repl)
