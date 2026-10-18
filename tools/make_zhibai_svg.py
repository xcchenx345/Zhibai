# -*- coding: utf-8 -*-
"""Strip MiMo letters from mimo.svg, keep outer frame, add hollow 知白, render PNG."""
import re
from pathlib import Path

SRC = Path(r"E:\Users\abc15\Downloads\mimo.svg")
OUT_SVG = Path(r"C:\Users\abc15\MiMoApp\tools\zhibai_hollow.svg")
OUT_PNG = Path(r"C:\Users\abc15\MiMoApp\app\src\main\res\drawable\mimo_logo_foreground.png")

svg = SRC.read_text(encoding="utf-8")

# Keep only the frame paths: Vector (white fill) + Vector_2 (black stroke body).
# Everything with id starting Vector_3 / Vector_4... is letter geometry — drop it.
# Split on <path boundaries carefully.
paths = list(re.finditer(r"<path\b[^/]*?/>", svg, re.S))
print(f"found {len(paths)} paths")

# Keep first two paths (frame fill + frame outline)
kept = paths[:2]
dropped = len(paths) - 2
print(f"keeping 2 frame paths, dropping {dropped} letter paths")

head_end = svg.index(paths[0].start()) if False else 0
# Reconstruct: everything before first path + first two paths + text + tail after last path
before = svg[: paths[0].start()]
after = svg[paths[-1].end() :]

# Hollow 知白: two chars side by side, stroke only, no fill
# Frame is roughly 0..50 viewBox. Letters sit around x=11..39, y=11..38 in original.
# Place 知白 centered with serif font, outline stroke matching black frame.
text_svg = (
    '<text x="25" y="30" '
    'font-family="Noto Serif SC, Microsoft YaHei, SimHei, serif" '
    'font-size="20" font-weight="700" '
    'text-anchor="middle" dominant-baseline="central" '
    'fill="none" stroke="white" stroke-width="1.1" '
    'letter-spacing="1">知白</text>\n  '
)

new_svg = before + "".join(p.group(0) for p in kept) + "\n  " + text_svg + after
OUT_SVG.parent.mkdir(parents=True, exist_ok=True)
OUT_SVG.write_text(new_svg, encoding="utf-8")
print(f"SVG: {OUT_SVG} ({OUT_SVG.stat().st_size} bytes)")
