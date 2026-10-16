#!/usr/bin/env python3
"""MekExDeorum mod icon (256x256 RGBA).

Content per 任务提示词.md 6.5: an Ex Deorum style sieve outline, the Mekanism
style "Mek" superscript in the top-right corner, a Mekanism steel panel
background, a blue accent bar and a horizontal line underneath. Redrawn in the
new texture style: it imports the shared palette and the same recessed-surface
routine as gen_textures.py, so the mesh inside the sieve is literally the block
top_inner recipe at 8x the pixel scale.

Writes MekExDeorum/icon.png, MekExDeorum/src/main/resources/icon.png and the
human-verification preview _plan/previews/icon_preview.png. Deterministic.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.dont_write_bytecode = True   # importing the shared palette must not litter tools/ with __pycache__

from gen_textures import (ACCENT, ACCENT_LT, EDGE_DK, EDGE_HI, EDGE_LT, PANEL_BASE,
                          PANEL_FIELD, RIM_GROOVE, SHADOW, recess_surface)

SIZE = 256

# panel geometry (edge thickness scaled up from the 16px block textures)
BEVEL = 6
HI = 2
RECESS = (10, 10, SIZE - 11, SIZE - 11)
BOLTS = ((12, 12), (SIZE - 20, 12), (12, SIZE - 20), (SIZE - 20, SIZE - 20))
BOLT = 8

# sieve: 128x128 mesh opening inside a 16px steel ring, same mesh recipe as top_inner
MESH_ORIGIN = (64, 68)
MESH_SIZE = 128
FRAME = (48, 52, 207, 211)
MESH = (MESH_ORIGIN[0], MESH_ORIGIN[1],
        MESH_ORIGIN[0] + MESH_SIZE - 1, MESH_ORIGIN[1] + MESH_SIZE - 1)
GRID = 32                      # == grid-spacing 4 * 8x pixel scale
FRAME_BEVEL = 4

ACCENT_BAR = (48, 220, 207, 227)
ACCENT_BAR_SHADOW = (48, 228, 207, 229)
RULE = (40, 236, 215, 238)

# 5x7 glyphs (same shapes as the legacy 1.0.4 gen_icon.js), only "Mek" is needed.
FONT5x7 = {
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "e": ("00000", "00000", "01110", "10001", "11111", "10000", "01110"),
    "k": ("10000", "10010", "10100", "11000", "10100", "10010", "10001"),
}
SUP_SCALE = 4
SUP_STRETCH = 3
SUP_RIGHT = RECESS[2] - 10     # = 235, 10px margin inside the recess
SUP_TOP = 26
SUP_SHADOW = (3, 3)


def draw_text(d, text, x0, y0, sx, sy, colour):
    x = x0
    for ch in text:
        g = FONT5x7.get(ch)
        if g is None:
            x += 4 * sx
            continue
        for gy, row in enumerate(g):
            for gx, c in enumerate(row):
                if c == "1":
                    d.rectangle((x + gx * sx, y0 + gy * sy,
                                 x + gx * sx + sx - 1, y0 + gy * sy + sy - 1),
                                fill=colour + (255,))
        x += 6 * sx
    return x


def superscript_width(text, sx):
    return len(text) * 6 * sx - sx


def build_icon(grid_alpha):
    img = Image.new("RGBA", (SIZE, SIZE), PANEL_BASE + (255,))
    d = ImageDraw.Draw(img)

    # steel panel: light top/left bevel, dark bottom/right, brighter outer edge
    d.rectangle((0, 0, SIZE - 1, SIZE - 1), outline=EDGE_LT + (255,), width=BEVEL)
    d.rectangle((0, 0, SIZE - 1, SIZE - 1), outline=EDGE_HI + (255,), width=HI)
    d.rectangle((0, SIZE - BEVEL, SIZE - 1, SIZE - 1), fill=EDGE_DK + (255,))
    d.rectangle((SIZE - BEVEL, 0, SIZE - 1, SIZE - 1), fill=EDGE_DK + (255,))

    # inner recess with a groove shadow on its top/left
    d.rectangle(RECESS, fill=PANEL_FIELD + (255,))
    d.rectangle((RECESS[0], RECESS[1], RECESS[2], RECESS[1] + 5), fill=RIM_GROOVE + (255,))
    d.rectangle((RECESS[0], RECESS[1], RECESS[0] + 5, RECESS[3]), fill=RIM_GROOVE + (255,))

    for (bx, by) in BOLTS:
        d.rectangle((bx, by, bx + BOLT - 1, by + BOLT - 1), fill=EDGE_LT + (255,))
        d.rectangle((bx + BOLT - 3, by + BOLT - 3, bx + BOLT - 1, by + BOLT - 1),
                    fill=EDGE_DK + (255,))

    # sieve: steel ring outline
    d.rectangle(FRAME, fill=EDGE_LT + (255,))
    d.rectangle(FRAME, outline=EDGE_HI + (255,), width=FRAME_BEVEL)
    d.rectangle((FRAME[0], FRAME[3] - FRAME_BEVEL, FRAME[2], FRAME[3]),
                fill=EDGE_DK + (255,))
    d.rectangle((FRAME[2] - FRAME_BEVEL, FRAME[1], FRAME[2], FRAME[3]),
                fill=EDGE_DK + (255,))

    # sieve mesh: the top_inner recipe (radial gradient + baked white grid)
    mesh = recess_surface(MESH_SIZE, GRID, grid_alpha, border=2)
    img.paste(Image.fromarray(mesh, "RGB"), MESH_ORIGIN)

    # blue accent bar + drop shadow + horizontal rule underneath
    d.rectangle(ACCENT_BAR, fill=ACCENT + (255,))
    d.rectangle(ACCENT_BAR_SHADOW, fill=SHADOW + (255,))
    d.rectangle(RULE, fill=EDGE_LT + (255,))

    # "Mek" superscript, top-right corner
    sup_x = SUP_RIGHT - superscript_width("Mek", SUP_SCALE) + 1
    draw_text(d, "Mek", sup_x + SUP_SHADOW[0], SUP_TOP + SUP_SHADOW[1],
              SUP_SCALE, SUP_STRETCH, SHADOW)
    draw_text(d, "Mek", sup_x, SUP_TOP, SUP_SCALE, SUP_STRETCH, ACCENT_LT)
    return img, (sup_x, SUP_TOP)


def main(argv=None):
    repo = Path(__file__).resolve().parents[1]
    ap = argparse.ArgumentParser(description="Generate the MekExDeorum mod icon.")
    ap.add_argument("--grid-alpha", type=float, default=0.20,
                    help="sieve mesh grid alpha (matches top_inner, default 0.20)")
    ap.add_argument("--previews-dir", type=Path,
                    default=Path(__file__).resolve().parents[2] / "_plan/previews")
    args = ap.parse_args(argv)

    img, sup_pos = build_icon(args.grid_alpha)

    targets = [repo / "icon.png", repo / "src/main/resources/icon.png"]
    for t in targets:
        t.parent.mkdir(parents=True, exist_ok=True)
        img.save(t)
    args.previews_dir.mkdir(parents=True, exist_ok=True)
    preview = args.previews_dir / "icon_preview.png"
    img.save(preview)

    print("== gen_icon (MekExDeorum) ==")
    print("size=%dx%d mode=%s mesh_grid_spacing=%dpx mesh_grid_alpha=%.2f "
          "mesh_scale=8x (= block grid-spacing 4) superscript=Mek@%d,%d scale=%d stretch=%d"
          % (img.width, img.height, img.mode, GRID, args.grid_alpha,
             sup_pos[0], sup_pos[1], SUP_SCALE, SUP_STRETCH))
    print("sieve frame=%s mesh=%s accent_bar=%s rule=%s"
          % (FRAME, MESH, ACCENT_BAR, RULE))
    digests = {}
    for t in targets:
        data = t.read_bytes()
        digests[t] = hashlib.sha256(data).hexdigest()
        with Image.open(t) as im:
            print("wrote %-58s %dx%d %s %d bytes sha256=%s"
                  % (t, im.width, im.height, im.mode, len(data), digests[t][:16]))
    print("preview %s (%dx%d)" % (preview, img.width, img.height))
    same = len(set(digests.values())) == 1
    print("verify both icon copies are byte-identical = %s" % same)
    print("verify icon is 256x256 RGBA = %s"
          % (img.size == (256, 256) and img.mode == "RGBA"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
