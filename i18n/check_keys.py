# -*- coding: utf-8 -*-
"""QA check for the i18n migration.

1. Every key passed to LangManager.t("...") in code exists in zh-cn.json.
2. Every locale file's key set matches zh-cn.json exactly.
3. Values contain no emoji and placeholders match zh-cn per key.

Usage: python check_keys.py
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "locales")
SRC_ROOT = os.path.join(HERE, "..", "app", "src", "main", "java")

KEY_CALL = re.compile(r'\.t\(')
LITERAL = re.compile(r'"([^"\\\n]+)"')
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿⬀-⯿️]")
PH = re.compile(r"\{[a-z]+\}")


def extract_keys(text: str) -> set:
    """Every string literal inside a .t( ... ) call, incl. if/else key args."""
    keys = set()
    for m in KEY_CALL.finditer(text):
        i = m.end()  # just after '('
        depth = 1
        j = i
        while j < len(text) and depth:
            if text[j] == '(':
                depth += 1
            elif text[j] == ')':
                depth -= 1
            j += 1
        span = text[i:j - 1]
        # first literal in the span is the key (or both branches of if/else)
        # heuristic: any literal in span before a line with .replace( or standalone
        lits = LITERAL.findall(span)
        if not lits:
            continue
        # fallback text is the last literal that is NOT dotted-key shaped when
        # there are 2+ literals and the first looks like a key; with if/else
        # both key literals look like keys and the fallback literals follow.
        keyish = [l for l in lits if re.fullmatch(r'[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9]+)+', l)]
        keys.update(keyish if keyish else [lits[0]])
    return keys


def main():
    src_path = os.path.join(OUT_DIR, "zh-cn.json")
    src = json.load(io.open(src_path, encoding="utf-8"))
    src_keys = set(src.keys())

    used = set()
    for root, _, files in os.walk(SRC_ROOT):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            text = io.open(os.path.join(root, fn), encoding="utf-8").read()
            used |= extract_keys(text)

    bad = False
    missing_in_table = sorted(used - src_keys)
    if missing_in_table:
        bad = True
        print(f"FAIL keys used in code but absent from zh-cn.json ({len(missing_in_table)}):")
        for k in missing_in_table:
            print("  ", k)
    unused = sorted(src_keys - used)
    if unused:
        print(f"INFO keys in zh-cn.json not referenced in code ({len(unused)}): {unused[:20]}{' ...' if len(unused) > 20 else ''}")

    for fn in sorted(os.listdir(OUT_DIR)):
        if not fn.endswith(".json") or fn == "zh-cn.json":
            continue
        code = fn[:-5]
        table = json.load(io.open(os.path.join(OUT_DIR, fn), encoding="utf-8"))
        keys = set(table.keys())
        if keys != src_keys:
            bad = True
            print(f"FAIL {code}: missing={sorted(src_keys - keys)[:6]} extra={sorted(keys - src_keys)[:6]}")
        for k, v in table.items():
            if k in src and isinstance(v, str):
                if set(PH.findall(v)) != set(PH.findall(src[k])):
                    bad = True
                    print(f"FAIL {code} {k}: placeholders {sorted(PH.findall(v))} != src {sorted(PH.findall(src[k]))}")
                if EMOJI.search(v):
                    bad = True
                    print(f"FAIL {code} {k}: emoji in value")
        raw = io.open(os.path.join(OUT_DIR, fn), encoding="utf-8").read().lower()
        if "zh-tw" in raw:
            bad = True
            print(f"FAIL {code}: contains zh-tw")

    print("FAIL" if bad else "OK")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
