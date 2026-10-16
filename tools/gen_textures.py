#!/usr/bin/env python3
"""Deterministic pixel-art generator for MekExDeorum machine block textures.

Writes exactly 35 16x16 RGBA PNGs: 5 machines x 7 faces
(front, back, side, top_outer, top_inner, bottom, front_bar) into
    src/main/resources/assets/mekexdeorum/textures/block/{id}_{face}.png
and (unless --no-previews) human-verification previews under _plan/previews/
(never inside the repo).

Round 4 design (texture-only tier marker, see _plan/ROUND4_BRIEF.md)
    * `universal_sieve` is the one baseline and its 7 textures are frozen.
    * each factory is that baseline plus ONE short bar on the front:
      - front     = baseline front with a solid 10x2 rectangle in texture rows
                    0..1 (the top 2 px of the north face), painted in Mekanism's
                    official tier RGB, verbatim from BaseTier.java;
      - front_bar = a byte-identical copy of that machine's front, so the model's
                    2px proud layer duplicates the panel underneath and the model
                    JSON needs no change;
      - back / side / top_outer / top_inner / bottom = byte-identical copies of
                    the baseline faces.
    No numerals, no per-tier side accents, no calibrated colour gains: round 4
    supersedes the round-3 tier styling.

Palette provenance
    * every steel value is a mode measured from the two reference icons in the
      repository root (300px-Metallurgic_Infuser.png = "inf",
      basic_infuser_factory.png = "fac") with _plan/previews/tools/measure_refs.py.

No randomness anywhere: byte-identical output on every run.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

TEX = 16

# ----------------------------------------------------------------- palette ---
# The reference material is a very dark, strictly neutral steel: 98.8% of the
# infuser's pixels have a channel spread <= 6 and the grey B-R percentile range is
# [-2, +6], i.e. no blue tint in the greys.
FRAME_DEEP = (0x0F, 0x0F, 0x0F)       # inf/fac: darkest plate edge, bevel bottom-right
FRAME_DARK = (0x15, 0x15, 0x15)       # inf: frame mode (n=1200)
FRAME = (0x1F, 0x1F, 0x1F)            # inf: frame plateau
FRAME_MID = (0x22, 0x22, 0x22)        # inf: frame mode (n=1263, most common non-white)
FRAME_LIGHT = (0x2C, 0x2C, 0x2C)      # inf: frame mode (n=1170)
GROOVE = (0x12, 0x12, 0x12)           # inf/fac: groove lines between plates
PANEL_DARK = (0x36, 0x36, 0x36)       # inf: inset plate, dark end of the panel range
PANEL = (0x3B, 0x3B, 0x3B)            # inf: inset plate mode (n=1127)
PANEL_MID = (0x43, 0x43, 0x43)        # inf/fac: raised plate mode
PANEL_LIGHT = (0x4B, 0x4B, 0x4B)      # inf: panel mode (n=1051)
PANEL_HI = (0x50, 0x50, 0x50)         # inf: brightest panel mode (n=830)
SHADOW = (0x0A, 0x0A, 0x0A)           # recess border shadow line
NAVY_DEEP = (0x0F, 0x1D, 0x2A)        # inf: navy accent window mode (n=41, hue 209)
NAVY = (0x15, 0x23, 0x30)             # inf: navy accent window mode (n=53, hue 208)
NAVY_HI = (0x1F, 0x33, 0x45)          # inf: navy window highlight
WHITE = (0xFF, 0xFF, 0xFF)            # grid lines, composited at --grid-alpha
RECESS_EDGE = (0x3A, 0x3A, 0x3A)      # recess radial gradient, edge (lightest)
RECESS_CENTRE = (0x1A, 0x1A, 0x1A)    # recess radial gradient, centre (darkest)
# Exported for tools/gen_icon.py only - no block texture below uses them any more.
ACCENT = (0x00, 0xB4, 0xFF)           # icon: blue accent bar
ACCENT_LT = (0x4F, 0xC8, 0xFF)        # icon: accent lifted for legibility

# --------------------------------------------------- official tier colours ---
# Mekanism's own tier RGB, read verbatim from
#   _research_mek/src10711/mekanism/api/tier/BaseTier.java
#     BASIC("Basic", new int[]{95, 255, 184}, MapColor.COLOR_LIGHT_GREEN)
#     ADVANCED("Advanced", new int[]{255, 128, 106}, MapColor.TERRACOTTA_PINK)
#     ELITE("Elite", new int[]{75, 248, 255}, MapColor.DIAMOND)
#     ULTIMATE("Ultimate", new int[]{247, 135, 255}, MapColor.COLOR_MAGENTA)
# Round 4 ships these values unmodified: no calibration, no gain, no rounding.
BASE_TIER = {
    1: (95, 255, 184),      # BASIC    #5FFFB8
    2: (255, 128, 106),     # ADVANCED #FF806A
    3: (75, 248, 255),      # ELITE    #4BF8FF
    4: (247, 135, 255),     # ULTIMATE #F787FF
}
TIER_NAME = {0: "baseline", 1: "BASIC", 2: "ADVANCED", 3: "ELITE", 4: "ULTIMATE"}
# Tier 0 is the universal sieve's own front_bar: the neutral grey band it has had
# since round 2, which round 4 keeps untouched ("完全不变").
BAR_NEUTRAL = (0x4A, 0x4A, 0x4A)
TIER_BAR = {0: BAR_NEUTRAL}
TIER_BAR.update(BASE_TIER)

PALETTE = (
    ("FRAME_DEEP", FRAME_DEEP),
    ("FRAME_DARK", FRAME_DARK),
    ("FRAME", FRAME),
    ("FRAME_MID", FRAME_MID),
    ("FRAME_LIGHT", FRAME_LIGHT),
    ("GROOVE", GROOVE),
    ("PANEL_DARK", PANEL_DARK),
    ("PANEL", PANEL),
    ("PANEL_MID", PANEL_MID),
    ("PANEL_LIGHT", PANEL_LIGHT),
    ("PANEL_HI", PANEL_HI),
    ("SHADOW", SHADOW),
    ("NAVY_DEEP", NAVY_DEEP),
    ("NAVY", NAVY),
    ("NAVY_HI", NAVY_HI),
    ("RECESS_EDGE", RECESS_EDGE),
    ("RECESS_CENTRE", RECESS_CENTRE),
)

# --------------------------------------------------------------- machines ----
# tier 0 == the universal sieve, the single baseline every factory is built from.
MACHINES = (
    ("universal_sieve", 0),
    ("basic_sieve_factory", 1),
    ("advanced_sieve_factory", 2),
    ("elite_sieve_factory", 3),
    ("ultimate_sieve_factory", 4),
)
BASE_FACES = ("front", "back", "side", "top_outer", "top_inner", "bottom")
FACES = BASE_FACES + ("front_bar",)
# faces a factory copies byte-for-byte from the baseline instead of building
COPIED_FACES = ("back", "side", "top_outer", "top_inner", "bottom")

# geometry constants (all 1-based-inclusive in the helpers below)
FIELD = (2, 2, 13, 13)          # inner panel field, x0 y0 x1 y1
RIVETS = ((3, 3), (12, 3), (3, 12), (12, 12))
RIVETS_SIDE = ((5, 3), (12, 3), (5, 12), (12, 12))   # clear of the navy slot
WINDOW = (4, 3, 11, 6)          # front navy status window (8x4)
SLOT = (2, 3, 3, 12)            # side navy slot (2x10), as in the reference infuser
# tier marker: 10 px wide, centred (62.5% of 16), flush with the front's top edge
FRONT_BAR_X0, FRONT_BAR_X1 = 3, 12
FRONT_BAR_Y0, FRONT_BAR_Y1 = 0, 1

# the baseline's frozen hashes, recorded before this round touched anything
BASELINE_SHA16 = {
    "back": "6B53A0D44F78F2B7",
    "bottom": "82CE4F387C82F8AF",
    "front": "F35BF610EC5AC8A8",
    "front_bar": "565E02F7A858536F",
    "side": "754609A54FC7AE7F",
    "top_inner": "BE78AB6B57B5640F",
    "top_outer": "E6C0767242B26DB8",
}


# ------------------------------------------------------------------ canvas ---
def blank(colour):
    a = np.zeros((TEX, TEX, 3), dtype=np.uint8)
    a[:, :] = colour
    return a


def rect(a, x0, y0, x1, y1, colour):
    a[y0:y1 + 1, x0:x1 + 1] = colour


def hline(a, y, x0, x1, colour):
    a[y, x0:x1 + 1] = colour


def vline(a, x, y0, y1, colour):
    a[y0:y1 + 1, x] = colour


def px(a, x, y, colour):
    a[y, x] = colour


def lighten(colour, k=1.25):
    return tuple(min(255, int(round(c * k))) for c in colour)


def steel_panel(field=PANEL, outer=PANEL_LIGHT, ring=PANEL_MID, dark=FRAME_DEEP):
    """Shared steel plate: 1px outer bevel, 1px ring, 2..13 field."""
    a = blank(field)
    hline(a, 0, 0, 15, outer)          # lit top/left edge
    vline(a, 0, 0, 15, outer)
    hline(a, 15, 0, 15, dark)          # shaded bottom/right edge
    vline(a, 15, 0, 15, dark)
    rect(a, 1, 1, 14, 14, ring)
    rect(a, *FIELD, field)
    return a


def rivets(a, positions=RIVETS):
    for (x, y) in positions:
        px(a, x, y, PANEL_LIGHT)
        px(a, x, y + 1, GROOVE)


# ------------------------------------------------------------ face builders --
def build_front():
    """Baseline front: steel plate, navy status window, plate seam. No numeral."""
    a = steel_panel()
    rivets(a)
    x0, y0, x1, y1 = WINDOW
    rect(a, x0, y0, x1, y1, NAVY_DEEP)          # navy status window (8x4)
    hline(a, y0, x0, x1, SHADOW)                # recess rim: top + left
    hline(a, y0 + 1, x0, x1, NAVY)
    vline(a, x0, y0, y1, SHADOW)
    hline(a, y1, x0, x1, FRAME_DEEP)
    vline(a, x1, y0, y1, FRAME_DEEP)
    vline(a, 7, y0 + 1, y1 - 1, NAVY_HI)        # window mullion
    hline(a, 9, 3, 12, FRAME_LIGHT)             # plate seam under the window
    hline(a, 10, 3, 12, GROOVE)
    return a


def build_back():
    a = steel_panel()
    rivets(a)
    for y in range(3, 13, 2):                   # heat-sink fins
        hline(a, y, 3, 12, PANEL_MID)
        hline(a, y + 1, 3, 12, GROOVE)
    for x in (5, 9):                            # two vertical pipes over the fins
        vline(a, x, 3, 12, PANEL_LIGHT)
        vline(a, x + 1, 3, 12, FRAME_DEEP)
    return a


def build_side():
    a = steel_panel()
    rivets(a, RIVETS_SIDE)
    x0, y0, x1, y1 = SLOT
    rect(a, x0, y0, x1, y1, NAVY_DEEP)          # navy accent window family
    hline(a, y0, x0, x1, NAVY_HI)
    vline(a, x0, y0, y1, NAVY)
    hline(a, y1, x0, x1, FRAME_DEEP)
    hline(a, 8, 5, 13, FRAME_LIGHT)             # stacked-plate seams
    hline(a, 9, 5, 13, GROOVE)
    return a


def build_top_outer():
    """Top frame ring.

    Only the outermost two texels of this 16x16 plate are ever sampled (the four
    ring strips of the block model map their 2px-wide `up` faces onto it), so the
    design is concentric: d=0 is the lit rim, d=1 the mid rim, d>=2 a dark plate
    that stays inside the recess.  The plate seams at the mid-rim let the rim read
    as four bolted segments, like the reference's segmented steel rim.
    """
    ys, xs = np.mgrid[0:TEX, 0:TEX]
    d = np.minimum(np.minimum(xs, ys), np.minimum(TEX - 1 - xs, TEX - 1 - ys))
    a = blank(FRAME)
    for ring, colour in ((0, PANEL_LIGHT), (1, PANEL_MID), (2, GROOVE), (3, FRAME_MID)):
        a[d == ring] = colour
    for (x, y) in ((7, 0), (8, 0), (7, 1), (8, 1), (0, 7), (0, 8), (1, 7), (1, 8),
                   (7, 15), (8, 15), (7, 14), (8, 14), (15, 7), (15, 8), (14, 7), (14, 8)):
        px(a, x, y, FRAME_DEEP)
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):    # bolts on the mid rim
        px(a, x, y, PANEL_HI)
    return a


def recess_surface(size, spacing, alpha, border=1):
    """Recessed surface: radial gradient (edge light -> centre darkest) with a
    baked 1px white grid. Returns an opaque uint8 (size, size, 3) array."""
    ys, xs = np.mgrid[0:size, 0:size]
    c = (size - 1) / 2.0
    d = np.sqrt((xs - c) ** 2 + (ys - c) ** 2)
    t = (d / d.max())[..., None]
    centre = np.array(RECESS_CENTRE, dtype=np.float64)
    edge = np.array(RECESS_EDGE, dtype=np.float64)
    base = centre + t * (edge - centre)
    if border:
        # The 1px shadow line sits on top of the grid: the outer ring is a solid
        # dark rim where the recess meets the frame.  Its bottom row (and every
        # other border texel) is what the recessed floor's four sides sample.
        interior = base * (1.0 - alpha) + np.array(WHITE, dtype=np.float64) * alpha
        on_grid = (((xs % spacing) == 0) | ((ys % spacing) == 0))[..., None]
        lum = np.where(on_grid, interior, base)
    else:
        lum = base
    out = np.rint(np.clip(lum, 0, 255)).astype(np.uint8)
    if border:
        ring = SHADOW
        out[:border, :] = ring
        out[size - border:, :] = ring
        out[:, :border] = ring
        out[:, size - border:] = ring
    return out


def build_top_inner(spacing, alpha):
    return recess_surface(TEX, spacing, alpha, border=1)


def build_bottom():
    a = blank(PANEL_DARK)
    hline(a, 0, 0, 15, PANEL_MID)
    vline(a, 0, 0, 15, PANEL_MID)
    hline(a, 15, 0, 15, FRAME_DEEP)
    vline(a, 15, 0, 15, FRAME_DEEP)
    rect(a, 1, 1, 14, 14, FRAME_DARK)           # measured frame mode of the bottom plate
    rect(a, *FIELD, PANEL_DARK)
    for (bx, by) in ((3, 3), (11, 3), (3, 11), (11, 11)):
        rect(a, bx, by, bx + 1, by + 1, PANEL_LIGHT)
        px(a, bx + 1, by + 1, FRAME_DEEP)
    rect(a, 5, 5, 10, 10, GROOVE)               # recessed centre vent
    rect(a, 6, 6, 9, 9, SHADOW)
    return a


def build_front_bar(bar):
    """The universal sieve's own front_bar.png: its long-standing neutral grey band.

    Rows 0..1 are the visible band (row 0 = lit top edge, row 1 = the band colour,
    because the model maps this plate to uv [0,0,16,2]); rows 2..15 continue the
    steel plate so the PNG reads correctly on its own.  Round 4 leaves this file
    byte-identical, and the factories no longer use this builder at all - their
    front_bar is a copy of their front.
    """
    a = blank(PANEL)
    hline(a, 0, 0, 15, lighten(bar))
    hline(a, 1, 0, 15, bar)
    hline(a, 2, 0, 15, FRAME_DEEP)
    hline(a, 3, 0, 15, PANEL_MID)
    rect(a, 4, 5, 11, 10, PANEL_MID)
    rect(a, 5, 6, 10, 9, PANEL)
    hline(a, 8, 5, 10, GROOVE)
    hline(a, 13, 0, 15, GROOVE)
    hline(a, 14, 0, 15, FRAME_LIGHT)
    hline(a, 15, 0, 15, FRAME_DEEP)
    return a


def add_front_bar(arr, colour):
    """Round 4 tier marker: one solid 10x2 rectangle, no gradient, no outline."""
    rect(arr, FRONT_BAR_X0, FRONT_BAR_Y0, FRONT_BAR_X1, FRONT_BAR_Y1, colour)
    return arr


def build_base_face(face, grid_spacing, grid_alpha):
    if face == "front":
        return build_front()
    if face == "back":
        return build_back()
    if face == "side":
        return build_side()
    if face == "top_outer":
        return build_top_outer()
    if face == "top_inner":
        return build_top_inner(grid_spacing, grid_alpha)
    if face == "bottom":
        return build_bottom()
    raise ValueError(face)


def to_image(arr):
    out = np.empty((TEX, TEX, 4), dtype=np.uint8)
    out[..., :3] = arr
    out[..., 3] = 255
    return Image.fromarray(out, "RGBA")


def png_bytes(img):
    """Encode once, so 'byte-identical copy' is literally the same buffer."""
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def hexs(colour):
    return "#%02X%02X%02X" % tuple(colour)


def sha16(data):
    return hashlib.sha256(data).hexdigest()[:16].upper()


def load_rgb(path):
    with Image.open(path) as im:
        return np.array(im.convert("RGB"))


# ---------------------------------------------------------------- previews ---
SCALE = 16
CELL = TEX * SCALE
PAD = 10
LABEL_H = 28


def _font(size):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:            # Pillow < 10.1 has no size argument
        return ImageFont.load_default()


def upscale(img, factor=SCALE):
    return img.resize((img.width * factor, img.height * factor), Image.NEAREST)


def write_sheet(machine_id, face_images, out_dir, notes):
    n = len(face_images)
    w = PAD + n * (CELL + PAD)
    h = LABEL_H + CELL + PAD + 30
    canvas = Image.new("RGBA", (w, h), (18, 20, 24, 255))
    d = ImageDraw.Draw(canvas)
    f = _font(12)
    for i, (face, img) in enumerate(face_images):
        x = PAD + i * (CELL + PAD)
        canvas.paste(upscale(img), (x, LABEL_H))
        d.text((x, 6), "%s_%s.png" % (machine_id, face), fill=(214, 220, 228, 255), font=f)
        d.text((x, LABEL_H + CELL + 2), face, fill=(0x7C, 0xB0, 0xD6, 255), font=f)
        if face in notes:
            d.text((x, LABEL_H + CELL + 16), notes[face], fill=(0xC8, 0xB0, 0x7C, 255), font=f)
    path = out_dir / ("sheet_%s.png" % machine_id)
    canvas.save(path)
    return path


def write_front_bar_compare(out_dir, fronts):
    """front_bar_compare.png: the 5 front.png top regions (x16 NEAREST), each next
    to a flat swatch of the official Mekanism colour it is supposed to carry."""
    f = _font(14)
    fs = _font(12)
    label_w = 300
    strip_w = TEX * SCALE
    strip_h = (FRONT_BAR_Y1 + 1) * SCALE
    sw = 34
    row_h = strip_h + 16
    w = PAD * 2 + label_w + strip_w + 24 + sw
    h = 40 + len(MACHINES) * row_h + 34
    canvas = Image.new("RGBA", (w, h), (18, 20, 24, 255))
    d = ImageDraw.Draw(canvas)
    d.text((PAD, 8), "front.png top region (rows 0..1) x16 NEAREST   vs   official Mekanism BaseTier RGB",
           fill=(214, 220, 228, 255), font=f)
    d.text((PAD, 24), "bar = solid %d x %d at x %d..%d, y %d..%d (0 px from the top edge); outline skipped"
           % (FRONT_BAR_X1 - FRONT_BAR_X0 + 1, FRONT_BAR_Y1 - FRONT_BAR_Y0 + 1,
              FRONT_BAR_X0, FRONT_BAR_X1, FRONT_BAR_Y0, FRONT_BAR_Y1),
           fill=(0x7C, 0xB0, 0xD6, 255), font=fs)
    for i, (mid, tier) in enumerate(MACHINES):
        y = 44 + i * row_h
        img = fronts[mid]
        top = img.crop((0, FRONT_BAR_Y0, TEX, FRONT_BAR_Y1 + 1))
        canvas.paste(upscale(top), (PAD + label_w, y))
        # 1px guide marks proving where x 0..2 and x 13..15 are, i.e. outside the bar
        for gx in (0, FRONT_BAR_X0, FRONT_BAR_X1 + 1, TEX):
            gxp = PAD + label_w + gx * SCALE
            d.rectangle((gxp - 1, y, gxp, y + strip_h - 1), fill=(90, 100, 112, 255))
        official = TIER_BAR[tier]
        d.rectangle((PAD + label_w + strip_w + 24, y,
                     PAD + label_w + strip_w + 24 + sw - 1, y + sw - 1),
                    fill=official + (255,))
        if tier == 0:
            d.text((PAD + 4, y + 2), "universal_sieve  (tier 0)", fill=(214, 220, 228, 255), font=f)
            d.text((PAD + 4, y + 18), "no tier bar - baseline front.png", fill=(0xC8, 0xB0, 0x7C, 255), font=fs)
            note = "baseline front_bar band %s" % hexs(BAR_NEUTRAL)
        else:
            d.text((PAD + 4, y + 2), "%s  (tier %d %s)" % (mid, tier, TIER_NAME[tier]),
                   fill=(214, 220, 228, 255), font=f)
            d.text((PAD + 4, y + 18), "bar %s  ==  official %s" % (hexs(official), hexs(BASE_TIER[tier])),
                   fill=(0xC8, 0xB0, 0x7C, 255), font=fs)
            note = "official %s %s" % (TIER_NAME[tier], hexs(official))
        d.text((PAD + label_w + strip_w + 24 + sw + 8, y + 10), note,
               fill=(0x7C, 0xB0, 0xD6, 255), font=fs)
    d.text((PAD, h - 22), "round-3 measured reference bar in basic_infuser_factory.png: "
                          "#497F60 / #4E7C61 / #47775B (same hue, darker: that render's shading)",
           fill=(0x8E, 0x98, 0xA6, 255), font=fs)
    path = out_dir / "front_bar_compare.png"
    canvas.save(path)
    return path


def write_grid2_preview(default_img, out_dir, spacing_default, spacing_alt, alpha):
    alt = to_image(build_top_inner(spacing_alt, alpha))
    labels = ("grid-spacing %d (default, shipped)" % spacing_default,
              "grid-spacing %d (8x8 cells)" % spacing_alt)
    w = PAD + 2 * (CELL + PAD)
    h = LABEL_H + CELL + PAD
    canvas = Image.new("RGBA", (w, h), (18, 20, 24, 255))
    d = ImageDraw.Draw(canvas)
    f = _font(14)
    for i, (lab, img) in enumerate(zip(labels, (default_img, alt))):
        x = PAD + i * (CELL + PAD)
        canvas.paste(upscale(img), (x, LABEL_H))
        d.text((x, 6), lab, fill=(214, 220, 228, 255), font=f)
        d.text((x, LABEL_H + CELL + 1),
               "alpha %.2f" % alpha, fill=(0x7C, 0xB0, 0xD6, 255), font=f)
    path = out_dir / ("top_inner_grid%d.png" % spacing_alt)
    canvas.save(path)
    return path


# -------------------------------------------------------------------- main ---
def main(argv=None):
    repo = Path(__file__).resolve().parents[1]
    ap = argparse.ArgumentParser(description="Generate the 35 MekExDeorum block textures.")
    ap.add_argument("--grid-spacing", type=int, default=4, choices=(2, 3, 4),
                    help="top_inner grid pitch in pixels (default 4)")
    ap.add_argument("--grid-alpha", type=float, default=0.20,
                    help="top_inner white grid alpha, must stay within 0.15-0.25 (default 0.20)")
    ap.add_argument("--out-dir", type=Path,
                    default=repo / "src/main/resources/assets/mekexdeorum/textures/block")
    ap.add_argument("--previews-dir", type=Path,
                    default=Path(__file__).resolve().parents[2] / "_plan/previews")
    ap.add_argument("--no-previews", action="store_true")
    args = ap.parse_args(argv)

    if not 0.15 <= args.grid_alpha <= 0.25:
        ap.error("--grid-alpha must be within 0.15-0.25")

    out = args.out_dir
    out.mkdir(parents=True, exist_ok=True)

    print("== gen_textures (MekExDeorum) - round 4 texture-only tier marker ==")
    print("output dir   : %s" % out)
    print("parameters   : grid_spacing=%d grid_alpha=%.2f (baked into opaque RGB)"
          % (args.grid_spacing, args.grid_alpha))
    print("palette      : " + " ".join("%s=%s" % (n, hexs(c)) for n, c in PALETTE))
    print("radial grads : top_inner edge %s centre %s (centre darkest)"
          % (hexs(RECESS_EDGE), hexs(RECESS_CENTRE)))
    print("front bar    : solid %dx%d at x %d..%d, y %d..%d (top edge, gap 0), no outline"
          % (FRONT_BAR_X1 - FRONT_BAR_X0 + 1, FRONT_BAR_Y1 - FRONT_BAR_Y0 + 1,
             FRONT_BAR_X0, FRONT_BAR_X1, FRONT_BAR_Y0, FRONT_BAR_Y1))
    print("")
    print("official tier colours, read verbatim from BaseTier.java "
          "(no calibration, no gain - round 3's calibrated gain is deleted):")
    print("  tier 1 BASIC    new int[]{95, 255, 184}   -> %s" % hexs(BASE_TIER[1]))
    print("  tier 2 ADVANCED new int[]{255, 128, 106}  -> %s" % hexs(BASE_TIER[2]))
    print("  tier 3 ELITE    new int[]{75, 248, 255}   -> %s" % hexs(BASE_TIER[3]))
    print("  tier 4 ULTIMATE new int[]{247, 135, 255}  -> %s" % hexs(BASE_TIER[4]))
    print("  tier 0 baseline neutral grey band (universal_sieve front_bar only) -> %s"
          % hexs(BAR_NEUTRAL))
    print("")

    # ---- build the baseline once, then derive every factory from it ----------
    base_arr = {face: build_base_face(face, args.grid_spacing, args.grid_alpha)
                for face in BASE_FACES}
    base_png = {face: png_bytes(to_image(base_arr[face])) for face in BASE_FACES}
    band_png = png_bytes(to_image(build_front_bar(BAR_NEUTRAL)))

    payload = {}
    for (mid, tier) in MACHINES:
        if tier == 0:
            for face in BASE_FACES:
                payload[(mid, face)] = base_png[face]
            payload[(mid, "front_bar")] = band_png
            continue
        front_png = png_bytes(to_image(add_front_bar(base_arr["front"].copy(),
                                                     TIER_BAR[tier])))
        for face in COPIED_FACES:
            payload[(mid, face)] = base_png[face]
        payload[(mid, "front")] = front_png
        payload[(mid, "front_bar")] = front_png       # proud layer duplicates the front

    print("%-24s %4s %-9s %-10s %-16s %s" %
          ("machine", "tier", "tier name", "bar", "bar rect (x,y)", "non-front faces"))
    for (mid, tier) in MACHINES:
        where = ("%d..%d, %d..%d" % (FRONT_BAR_X0, FRONT_BAR_X1, FRONT_BAR_Y0, FRONT_BAR_Y1)
                 if tier else "-")
        origin = ("copy of the front" if tier else "frozen baseline")
        print("%-24s %4d %-9s %-10s %-16s %s"
              % (mid, tier, TIER_NAME[tier], hexs(TIER_BAR[tier]), where,
                 origin if tier == 0 else "copy of universal_sieve"))

    written = []
    print("")
    print("%-42s %-18s %s" % ("file", "sha256[:16]", "provenance"))
    for (mid, tier) in MACHINES:
        for face in FACES:
            data = payload[(mid, face)]
            path = out / ("%s_%s.png" % (mid, face))
            path.write_bytes(data)
            written.append((path, data))
            if tier == 0:
                why = "frozen baseline"
            elif face == "front":
                why = "baseline front + %s bar" % hexs(TIER_BAR[tier])
            elif face == "front_bar":
                why = "byte-identical copy of %s_front.png" % mid
            else:
                why = "byte-identical copy of universal_sieve_%s.png" % face
            print("%-42s %-18s %s" % (path.name, sha16(data), why))

    expected = len(MACHINES) * len(FACES)
    print("")
    print("files written: %d block textures (%d machines x %d faces), expected %d"
          % (len(written), len(MACHINES), len(FACES), expected))

    # ---- self-verification, always re-read from disk -------------------------
    problems = []
    for p, _data in written:
        with Image.open(p) as im:
            if im.size != (TEX, TEX) or im.mode != "RGBA":
                problems.append("%s -> %s %s" % (p.name, im.size, im.mode))
    print("verify size/mode: all %d are 16x16 RGBA = %s%s"
          % (len(written), not problems, ("  FAILED: " + "; ".join(problems)) if problems else ""))

    print("verify baseline frozen (recorded pre-round-4 hashes):")
    frozen_ok = True
    for face in FACES:
        p = out / ("universal_sieve_%s.png" % face)
        got = sha16(p.read_bytes())
        want = BASELINE_SHA16[face]
        if got != want:
            frozen_ok = False
        print("   universal_sieve_%-14s %s  == recorded %s  %s"
              % (face + ".png", got, want, "OK" if got == want else "CHANGED"))

    copies_ok = True
    for (mid, tier) in MACHINES:
        if tier == 0:
            continue
        for face in COPIED_FACES:
            a = sha16((out / ("%s_%s.png" % (mid, face))).read_bytes())
            b = BASELINE_SHA16[face]
            if a != b:
                copies_ok = False
                print("   copy mismatch %s_%s.png %s != %s" % (mid, face, a, b))
        fb = (out / ("%s_front_bar.png" % mid)).read_bytes()
        fr = (out / ("%s_front.png" % mid)).read_bytes()
        if fb != fr:
            copies_ok = False
            print("   front_bar is not a copy of front for %s" % mid)
    print("verify factory non-front faces + front_bar == baseline/front bytes = %s" % copies_ok)

    base_front = load_rgb(out / "universal_sieve_front.png")
    bars_ok = True
    for (mid, tier) in MACHINES:
        if tier == 0:
            continue
        arr = load_rgb(out / ("%s_front.png" % mid))
        colour = np.array(TIER_BAR[tier], dtype=np.uint8)
        hit = (arr == colour).all(axis=-1)
        ys, xs = np.nonzero(hit)
        n = int(hit.sum())
        bbox = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())) if n else None
        diff = (arr != base_front).any(axis=-1)
        dys, dxs = np.nonzero(diff)
        dbbox = (int(dxs.min()), int(dys.min()), int(dxs.max()), int(dys.max())) if diff.any() else None
        region = arr[FRONT_BAR_Y0:FRONT_BAR_Y1 + 1, FRONT_BAR_X0:FRONT_BAR_X1 + 1]
        solid = bool((region == colour).all())
        ok = (n == 20 and bbox == (FRONT_BAR_X0, FRONT_BAR_Y0, FRONT_BAR_X1, FRONT_BAR_Y1)
              and int(diff.sum()) == 20 and solid
              and dbbox == (FRONT_BAR_X0, FRONT_BAR_Y0, FRONT_BAR_X1, FRONT_BAR_Y1))
        bars_ok = bars_ok and ok
        print("verify bar %-24s colour %s  px=%2d bbox=%s  solid=%s  front-vs-baseline diff px=%d bbox=%s  -> %s"
              % (mid, hexs(TIER_BAR[tier]), n, bbox, solid, int(diff.sum()), dbbox,
                 "OK" if ok else "FAILED"))

    # every pixel that is not neutral steel must be one of the declared accents
    tinted = {NAVY_DEEP, NAVY, NAVY_HI}
    for tier in (1, 2, 3, 4):
        tinted.add(TIER_BAR[tier])
    stray = set()
    spread_max = 0
    for (mid, tier) in MACHINES:
        for face in FACES:
            arr = load_rgb(out / ("%s_%s.png" % (mid, face))).astype(int)
            sp = arr.max(axis=-1) - arr.min(axis=-1)
            spread_max = max(spread_max, int(sp.max()))
            for c in map(tuple, arr.reshape(-1, 3)[sp.reshape(-1) > 6]):
                if c not in tinted:
                    stray.add(c)
    print("verify palette is neutral steel: every pixel with channel spread > 6 is one of the "
          "navy window %s / an official tier bar %s -> stray tinted colours = %s (max spread seen: %d)"
          % (hexs(NAVY), " ".join(hexs(TIER_BAR[t]) for t in (1, 2, 3, 4)),
             sorted(hexs(c) for c in stray) or "none", spread_max))

    for (mid, _t) in MACHINES:
        arr = load_rgb(out / ("%s_top_inner.png" % mid)).astype(np.float64)
        with Image.open(out / ("%s_top_inner.png" % mid)) as im:
            amin = im.getchannel("A").getextrema()[0]
        print("verify top_inner %-24s mean_inside=%.1f alpha_min=%d grid_rows=%s"
              % (mid, arr[1:15, 1:15].mean(), amin,
                 [i for i in range(TEX) if i % args.grid_spacing == 0]))

    outer = load_rgb(out / "universal_sieve_top_outer.png").astype(np.float64)
    inner = load_rgb(out / "universal_sieve_top_inner.png").astype(np.float64)
    grad_only = recess_surface(TEX, args.grid_spacing, args.grid_alpha, border=0).astype(np.float64)
    band = np.zeros((TEX, TEX), dtype=bool)
    band[:2, :] = band[TEX - 2:, :] = True
    band[:, :2] = band[:, TEX - 2:] = True
    print("verify depth: top_outer visible rim mean=%.1f  top_inner gradient-only mean=%.1f "
          "(%.1f darker)  top_inner as-shipped mean=%.1f (grid lifts it, by construction)"
          % (outer[band].mean(), grad_only.mean(), outer[band].mean() - grad_only.mean(),
             inner.mean()))

    if not args.no_previews:
        args.previews_dir.mkdir(parents=True, exist_ok=True)
        fronts = {}
        sheets = []
        for (mid, tier) in MACHINES:
            face_images = [(face, Image.open(out / ("%s_%s.png" % (mid, face)))) for face in FACES]
            fronts[mid] = face_images[0][1]
            if tier == 0:
                notes = {"front_bar": "baseline neutral band %s (frozen)" % hexs(BAR_NEUTRAL)}
            else:
                notes = {
                    "front": "tier %d %s bar %s (official)" % (tier, TIER_NAME[tier],
                                                               hexs(TIER_BAR[tier])),
                    "front_bar": "copy of %s_front.png" % mid,
                }
            sheets.append(write_sheet(mid, face_images, args.previews_dir, notes))
        cmp_path = write_front_bar_compare(args.previews_dir, fronts)
        g2 = write_grid2_preview(fronts["universal_sieve"], args.previews_dir,
                                 args.grid_spacing, 2, args.grid_alpha)
        for im in fronts.values():
            im.close()
        print("previews: %s" % ", ".join(p.name for p in sorted(sheets) + [cmp_path, g2]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
