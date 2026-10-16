#!/usr/bin/env python3
"""MekExDeorum mod icon (256x256 RGBA).

Content per the earlier spec: an Ex Deorum style sieve outline, the Mekanism style
"Mek" superscript in the top-right corner, a steel panel background, a blue accent
bar, a horizontal rule and - since P3 - a four-segment tier bar.  The tier chips
carry Mekanism's official BaseTier RGB, the very gen_textures.TIER_BAR constants the
in-game factory front bars are painted with, so icon and blocks share one source of
truth for tier colour.  It imports the shared palette
and the recessed-surface routine from gen_textures.py, so the mesh inside the sieve
is literally the block `top_inner` recipe at 8x the pixel scale and every colour is
a value measured from the user's reference icons.

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

from gen_textures import (ACCENT, ACCENT_LT, FRAME_DEEP, FRAME_MID, GROOVE, PANEL,
                          PANEL_HI, PANEL_LIGHT, PANEL_MID, SHADOW, TIER_BAR,
                          recess_surface)

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
TIER_STRIP = (40, 241, 215, 248)      # four equal segments, tiers 1..4

# The icon's four tier chips carry the OFFICIAL Mekanism tier colours - literally
# gen_textures.TIER_BAR, the same object the in-game factory front bars are painted
# with, which is built from BaseTier.java's BASIC/ADVANCED/ELITE/ULTIMATE
# new int[]{95,255,184} / {255,128,106} / {75,248,255} / {247,135,255}.  Sharing
# that one object is deliberate: there is no second literal copy anywhere, so the
# icon and the block textures cannot drift apart again, and tier colour has a single
# source of truth.
# The round-3 muted/calibrated strip - chips #4A7C5F / #C73E37 / #3A7883 / #C04183,
# i.e. the official colours times a gain calibrated against
# basic_infuser_factory.png - is no longer used anywhere in the mod.  It survives
# only as history in MIGRATION_REPORT.md and FIX_REPORT.md.

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


def hexs(colour):
    return "#%02X%02X%02X" % tuple(colour)


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


def tier_segments(rect=TIER_STRIP):
    """The four tier chips, left to right = tiers 1..4 (official BaseTier colours)."""
    x0, y0, x1, y1 = rect
    width = (x1 - x0 + 1) // 4
    return [((x0 + i * width, y0, x0 + (i + 1) * width - 1, y1), TIER_BAR[i + 1])
            for i in range(4)]


def build_icon(grid_alpha):
    img = Image.new("RGBA", (SIZE, SIZE), FRAME_MID + (255,))
    d = ImageDraw.Draw(img)

    # steel panel: light top/left bevel, dark bottom/right, brighter outer edge
    d.rectangle((0, 0, SIZE - 1, SIZE - 1), outline=PANEL_MID + (255,), width=BEVEL)
    d.rectangle((0, 0, SIZE - 1, SIZE - 1), outline=PANEL_HI + (255,), width=HI)
    d.rectangle((0, SIZE - BEVEL, SIZE - 1, SIZE - 1), fill=FRAME_DEEP + (255,))
    d.rectangle((SIZE - BEVEL, 0, SIZE - 1, SIZE - 1), fill=FRAME_DEEP + (255,))

    # inner recess with a groove shadow on its top/left
    d.rectangle(RECESS, fill=PANEL + (255,))
    d.rectangle((RECESS[0], RECESS[1], RECESS[2], RECESS[1] + 5), fill=GROOVE + (255,))
    d.rectangle((RECESS[0], RECESS[1], RECESS[0] + 5, RECESS[3]), fill=GROOVE + (255,))

    for (bx, by) in BOLTS:
        d.rectangle((bx, by, bx + BOLT - 1, by + BOLT - 1), fill=PANEL_LIGHT + (255,))
        d.rectangle((bx + BOLT - 3, by + BOLT - 3, bx + BOLT - 1, by + BOLT - 1),
                    fill=FRAME_DEEP + (255,))

    # sieve: steel ring outline
    d.rectangle(FRAME, fill=PANEL_LIGHT + (255,))
    d.rectangle(FRAME, outline=PANEL_HI + (255,), width=FRAME_BEVEL)
    d.rectangle((FRAME[0], FRAME[3] - FRAME_BEVEL, FRAME[2], FRAME[3]),
                fill=FRAME_DEEP + (255,))
    d.rectangle((FRAME[2] - FRAME_BEVEL, FRAME[1], FRAME[2], FRAME[3]),
                fill=FRAME_DEEP + (255,))

    # sieve mesh: the top_inner recipe (radial gradient + baked white grid)
    mesh = recess_surface(MESH_SIZE, GRID, grid_alpha, border=2)
    img.paste(Image.fromarray(mesh, "RGB"), MESH_ORIGIN)

    # blue accent bar + drop shadow, rule, and the four official tier chips
    d.rectangle(ACCENT_BAR, fill=ACCENT + (255,))
    d.rectangle(ACCENT_BAR_SHADOW, fill=SHADOW + (255,))
    d.rectangle(RULE, fill=PANEL_LIGHT + (255,))
    for rect, colour in tier_segments():
        d.rectangle(rect, fill=colour + (255,))

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
    print("sieve frame=%s mesh=%s accent_bar=%s rule=%s" % (FRAME, MESH, ACCENT_BAR, RULE))
    print("palette: panel=%s recess=%s bevel=%s dark=%s accent=%s"
          % (hexs(PANEL), hexs(PANEL_MID), hexs(PANEL_LIGHT), hexs(FRAME_DEEP), hexs(ACCENT)))
    print("tier strip %s (40,%d)-(215,%d), four equal segments:"
          % (TIER_STRIP, TIER_STRIP[1], TIER_STRIP[3]))
    pixels = img.convert("RGB").load()
    ok_tiers = True
    for index, ((rx0, ry0, rx1, ry1), colour) in enumerate(tier_segments(), start=1):
        cx, cy = (rx0 + rx1) // 2, (ry0 + ry1) // 2
        got = pixels[cx, cy]
        if got != colour:
            ok_tiers = False
        print("   x %3d..%3d  tier %d  %s  (sampled pixel %s)"
              % (rx0, rx1, index, hexs(colour), hexs(got)))
    print("verify tier strip shows the four official Mekanism tier colours = %s" % ok_tiers)
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
