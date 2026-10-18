# -*- coding: utf-8 -*-
"""Convert Android vector drawable to SVG, then let ImageMagick rasterize it."""
import re
import sys
from pathlib import Path

SRC = Path(r"C:\Users\abc15\MiMoApp\app\src\main\res\drawable\mimo_logo_foreground.xml")
OUT_SVG = Path(r"C:\Users\abc15\MiMoApp\app\src\main\res\drawable\mimo_logo_foreground.svg")
OUT_PNG = Path(r"C:\Users\abc15\MiMoApp\app\src\main\res\drawable\mimo_logo_foreground.png")

xml = SRC.read_text(encoding="utf-8")

# viewport
vw = re.search(r'android:viewportWidth="([\d.]+)"', xml).group(1)
vh = re.search(r'android:viewportHeight="([\d.]+)"', xml).group(1)

# group transform
g = re.search(
    r'<group[^>]*android:translateX="([^"]+)"[^>]*android:translateY="([^"]+)"[^>]*'
    r'android:scaleX="([^"]+)"[^>]*android:scaleY="([^"]+)"',
    xml, re.S
)
tx, ty, sx, sy = g.groups()
transform = f"translate({tx},{ty}) scale({sx},{sy})"

# paths — pathData can span lines; match until />
paths = []
for m in re.finditer(
    r'android:pathData="([^"]+)"[^/]*?android:fillColor="(#[0-9A-Fa-f]+)"',
    xml, re.S
):
    d, color = m.group(1), m.group(2)
    # #AARRGGBB → #RRGGBB (assume fully opaque)
    if len(color) == 9 and color.startswith("#FF"):
        color = "#" + color[3:]
    paths.append(f'    <path d="{d}" fill="{color}"/>')

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{vw}" height="{vh}" viewBox="0 0 {vw} {vh}">
  <g transform="{transform}">
{chr(10).join(paths)}
  </g>
</svg>
'''
OUT_SVG.write_text(svg, encoding="utf-8")
print(f"SVG written: {OUT_SVG} ({OUT_SVG.stat().st_size} bytes, {len(paths)} paths)")
