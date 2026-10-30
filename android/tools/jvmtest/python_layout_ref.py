"""Эталонная геометрия раскладок из Python-версии (tclip/render.py) для AppLogicTest."""
import json, re, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from tclip.config import DEFAULT_PROFILE
from tclip.render import build_graph

CASES = [("single", 1920, 1080, None, 1.0), ("single", 1280, 544, None, 1.0), ("single", 1080, 1920, None, 1.0),
         ("single", 1920, 1080, None, 1.6), ("split", 1920, 1080, [0.7292, 0.6296, 0.25, 0.3333], 1.0),
         ("split", 1280, 720, [0.0, 0.0, 0.3, 0.4], 1.0), ("split", 1920, 1080, [0.6, 0.5, 0.35, 0.45], 1.0)]
for layout, sw, sh, cam, zoom in CASES:
    prof = dict(DEFAULT_PROFILE, layout=layout, cam_rect=cam, single_zoom=zoom)
    g, _, used = build_graph({"width": sw, "height": sh, "fps": 30}, prof, layout, False, None)
    head = f"{layout} {sw} {sh}" + (f" {json.dumps(cam).replace(' ', '')} {zoom}" if cam else f" null {zoom}")
    parts = []
    if used == "single":
        fw, fh, cw, ch = map(int, re.search(r"\[fgsrc\]scale=(\d+):(\d+),crop=(\d+):(\d+)", g).groups())
        x, y = map(int, re.search(r"\[bg\]\[fg\]overlay=(\d+):(\d+)", g).groups())
        k = sw / fw
        parts.append([x, y, cw, ch, (fw - cw) / 2 * k, (fh - ch) / 2 * (sh / fh), cw * k, ch * (sh / fh)])
    else:
        pw, ph, px, py, W, HS = map(int, re.search(r"\[scrsrc\]crop=(\d+):(\d+):(\d+):(\d+),scale=(\d+):(\d+)", g).groups())
        cw, ch, cx, cy, _, HC = map(int, re.search(r"\[camsrc\]crop=(\d+):(\d+):(\d+):(\d+),scale=(\d+):(\d+)", g).groups())
        camy = int(re.search(r"\[t1\]\[cam\]overlay=0:(\d+)", g).group(1))
        parts.append([0, 0, W, HS, px, py, pw, ph])
        parts.append([0, camy, 1080, HC, cx, cy, cw, ch])
    print(head.replace(" null", "") if not cam else head, "|", " | ".join(" ".join(f"{v:.2f}" for v in p) for p in parts))
