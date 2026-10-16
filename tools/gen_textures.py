#!/usr/bin/env python3
"""Deterministic pixel-art generator for MekExDeorum machine block textures.

Writes exactly 35 16x16 RGBA PNGs: 5 machines x 7 faces
(front, back, side, top_outer, top_inner, bottom, front_bar) into
    src/main/resources/assets/mekexdeorum/textures/block/{id}_{face}.png
and (unless --no-previews) human-verification previews under _plan/previews/
(never inside the repo).

Design source
    * _plan/SPEC_FREEZE.md section 6  - face set, recessed top, accent bars,
      Roman numerals, top_inner gradient/grid numbers.
    * 任务提示词.md section 4 (P3)     - Mekanism layered style, per-tier front
      top bar as its own texture layer.
    * the two reference icons in the repository root - every colour below is a
      mode measured from them (see PALETTE and the tier-bar calibration block).

No randomness anywhere: byte-identical output on every run.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

TEX = 16

# ----------------------------------------------------------------- palette ---
# Every value is a mode of the user's reference icons (300px-Metallurgic_Infuser.png
# = "inf", basic_infuser_factory.png = "fac"), measured with
# _plan/previews/tools/measure_refs.py.  The reference material is a very dark,
# strictly neutral steel: 98.8% of the infuser's pixels have a channel spread <= 6
# and the grey B-R percentile range is [-2, +6], i.e. no blue tint in the greys.
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
SHADOW = (0x0A, 0x0A, 0x0A)           # spec 6.2 recess border shadow line
NAVY_DEEP = (0x0F, 0x1D, 0x2A)        # inf: navy accent window mode (n=41, hue 209)
NAVY = (0x15, 0x23, 0x30)             # inf: navy accent window mode (n=53, hue 208)
NAVY_HI = (0x1F, 0x33, 0x45)          # inf: navy window highlight
ACCENT = (0x00, 0xB4, 0xFF)           # spec 6.3 side accent bar blue (kept as frozen)
ACCENT_LT = (0x4F, 0xC8, 0xFF)        # icon only: accent lifted for legibility
NUMERAL = (0xE8, 0xED, 0xF2)          # spec 6.3 Roman numeral ink
WHITE = (0xFF, 0xFF, 0xFF)            # grid lines, composited at --grid-alpha
RECESS_EDGE = (0x3A, 0x3A, 0x3A)      # spec 6.2 radial gradient, edge (lightest)
RECESS_CENTRE = (0x1A, 0x1A, 0x1A)    # spec 6.2 radial gradient, centre (darkest)

# --------------------------------------------------- tier bar calibration -----
# Mekanism's authoritative tier colours (_research_mek/src10711/mekanism/api/tier/
# BaseTier.java):
BASE_TIER = {
    1: (0x5F, 0xFF, 0xB8),   # BASIC     #5FFFB8
    2: (0xFF, 0x80, 0x6A),   # ADVANCED  #FF806A
    3: (0x4B, 0xF8, 0xFF),   # ELITE     #4BF8FF
    4: (0xF7, 0x87, 0xFF),   # ULTIMATE  #F787FF
}
# Measured in basic_infuser_factory.png: the basic factory's top bar is the only
# green-dominant region (340 px, bbox x=18..116 y=10..103).  Its three brightest
# texels - the lit faces of the bar under the icon renderer - are
MEASURED_BAR_TEXELS = ((0x49, 0x80, 0x60), (0x4E, 0x7C, 0x61), (0x47, 0x77, 0x5B))
# so the calibration target is their mean, and the transform is the per-channel
# gain that maps BaseTier.BASIC onto it:
_bar_mean = tuple(sum(c[i] for c in MEASURED_BAR_TEXELS) / 3.0 for i in range(3))
GAIN = tuple(_bar_mean[i] / BASE_TIER[1][i] for i in range(3))
BAR_MEAN = tuple(int(round(v)) for v in _bar_mean)


def _tint(colour, gain=GAIN):
    return tuple(min(255, int(round(colour[i] * gain[i]))) for i in range(3))


BAR_NEUTRAL = (0x4A, 0x4A, 0x4A)      # fix doc 4.2.4 neutral bar for tier 0
TIER_BAR = {0: BAR_NEUTRAL}
TIER_BAR.update({t: _tint(c) for t, c in BASE_TIER.items()})

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
    ("ACCENT", ACCENT),
    ("NUMERAL", NUMERAL),
    ("RECESS_EDGE", RECESS_EDGE),
    ("RECESS_CENTRE", RECESS_CENTRE),
)

# --------------------------------------------------------------- machines ----
# (id, tier, accent bar count, Roman numeral)
MACHINES = (
    ("universal_sieve", 0, 0, None),
    ("basic_sieve_factory", 1, 1, "I"),
    ("advanced_sieve_factory", 2, 2, "II"),
    ("elite_sieve_factory", 3, 3, "III"),
    ("ultimate_sieve_factory", 4, 4, "IV"),
)
FACES = ("front", "back", "side", "top_outer", "top_inner", "bottom", "front_bar")

# geometry constants (all 1-based-inclusive in the helpers below)
FIELD = (2, 2, 13, 13)          # inner panel field, x0 y0 x1 y1
RIVETS = ((3, 3), (12, 3), (3, 12), (12, 12))
RIVETS_SIDE = ((5, 3), (12, 3), (5, 12), (12, 12))   # clear of the navy slot
BAR_Y0, BAR_Y1 = 4, 11          # accent bars run inside the field
WINDOW = (4, 3, 11, 6)          # front navy status window (8x4)
SLOT = (2, 3, 3, 12)            # side navy slot (2x10), as in the reference infuser
NUM_RIGHT, NUM_BOTTOM = 12, 14  # bottom-right ink corner for the numeral

# 3x5 hand-coded Roman numeral font, one 3-wide cell per glyph. I is a single
# stroke centred in its cell (as a Roman numeral is written), V fills 3 columns;
# the advance is trimmed to the ink so I/II/III/IV stay small in a 16px corner.
GLYPHS = {
    "I": ("010", "010", "010", "010", "010"),
    "V": ("101", "101", "101", "010", "010"),
}
GLYPH_W, GLYPH_H, GLYPH_GAP = 3, 5, 1


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
def accent_columns(n):
    """Evenly spread n 1px accent bars across the field (tiers 1..4 -> 1..4 bars)."""
    if n <= 0:
        return []
    span = FIELD[2] - FIELD[0] + 1
    return [FIELD[0] + (i * span) // (n + 1) for i in range(1, n + 1)]


def build_side(bars):
    a = steel_panel()
    rivets(a, RIVETS_SIDE)
    x0, y0, x1, y1 = SLOT
    rect(a, x0, y0, x1, y1, NAVY_DEEP)          # navy accent window family
    hline(a, y0, x0, x1, NAVY_HI)
    vline(a, x0, y0, y1, NAVY)
    hline(a, y1, x0, x1, FRAME_DEEP)
    hline(a, 8, 5, 13, FRAME_LIGHT)             # stacked-plate seams
    hline(a, 9, 5, 13, GROOVE)
    for x in accent_columns(bars):
        vline(a, x, BAR_Y0, BAR_Y1, ACCENT)
        if x + 1 <= FIELD[2]:
            vline(a, x + 1, BAR_Y0, BAR_Y1, GROOVE)   # 1px drop shadow of the bar
    return a


def numeral_bitmap(text):
    rows = [""] * GLYPH_H
    for i, ch in enumerate(text):
        g = GLYPHS[ch]
        ink = [c for c in range(GLYPH_W)
               if any(g[y][c] == "1" for y in range(GLYPH_H))]
        if i:
            rows = [r + "0" * GLYPH_GAP for r in rows]
        rows = [rows[y] + "".join(g[y][c] for c in ink) for y in range(GLYPH_H)]
    return rows


def numeral_layout(text):
    rows = numeral_bitmap(text)
    w = len(rows[0])
    return rows, NUM_RIGHT - w + 1, NUM_BOTTOM - GLYPH_H + 1


def build_front(numeral):
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
    if numeral:
        rows, nx, ny = numeral_layout(numeral)
        for gy, row in enumerate(rows):
            for gx, ch in enumerate(row):
                if ch == "1":
                    px(a, nx + gx, ny + gy, NUMERAL)
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
    """Spec 6.2 recessed surface: radial gradient (edge light -> centre darkest)
    with a baked 1px white grid. Returns an opaque uint8 (size, size, 3) array."""
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
    """Front top bar layer, one per tier (fix doc 4.2.2/4.2.3).

    The shipped block model puts a 2px-tall `front_bar` element on the top of the
    front face and maps it to uv [0,0,16,2], i.e. rows 0..1 of this texture are the
    visible band (row 0 = lit top edge, row 1 = the calibrated tier colour).  Rows
    2..15 continue the steel plate so the PNG reads correctly on its own.
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


def build_face(face, tier, bars, numeral, grid_spacing, grid_alpha):
    if face == "front":
        return build_front(numeral)
    if face == "back":
        return build_back()
    if face == "side":
        return build_side(bars)
    if face == "top_outer":
        return build_top_outer()
    if face == "top_inner":
        return build_top_inner(grid_spacing, grid_alpha)
    if face == "bottom":
        return build_bottom()
    if face == "front_bar":
        return build_front_bar(TIER_BAR[tier])
    raise ValueError(face)


def to_image(arr):
    out = np.empty((TEX, TEX, 4), dtype=np.uint8)
    out[..., :3] = arr
    out[..., 3] = 255
    return Image.fromarray(out, "RGBA")


def hexs(colour):
    return "#%02X%02X%02X" % tuple(colour)


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
    h = LABEL_H + CELL + PAD + 16
    canvas = Image.new("RGBA", (w, h), (18, 20, 24, 255))
    d = ImageDraw.Draw(canvas)
    f = _font(12)
    for i, (face, img) in enumerate(face_images):
        x = PAD + i * (CELL + PAD)
        canvas.paste(upscale(img), (x, LABEL_H))
        d.text((x, 6), "%s_%s.png" % (machine_id, face), fill=(214, 220, 228, 255), font=f)
        d.text((x, LABEL_H + CELL + 1), face, fill=(0x7C, 0xB0, 0xD6, 255), font=f)
        if face in notes:
            d.text((x, LABEL_H + CELL + 15), notes[face], fill=(0xC8, 0xB0, 0x7C, 255), font=f)
    path = out_dir / ("sheet_%s.png" % machine_id)
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
        ap.error("--grid-alpha must be within 0.15-0.25 (spec 6.2)")

    out = args.out_dir
    out.mkdir(parents=True, exist_ok=True)

    print("== gen_textures (MekExDeorum) ==")
    print("output dir   : %s" % out)
    print("parameters   : grid_spacing=%d grid_alpha=%.2f (baked into opaque RGB) "
          "bar_y=%d..%d numeral_corner=(%d,%d) glyph=%dx%d gap=%d"
          % (args.grid_spacing, args.grid_alpha, BAR_Y0, BAR_Y1,
             NUM_RIGHT, NUM_BOTTOM, GLYPH_W, GLYPH_H, GLYPH_GAP))
    print("palette      : " + " ".join("%s=%s" % (n, hexs(c)) for n, c in PALETTE))
    print("radial grads : top_inner edge %s centre %s (centre darkest)"
          % (hexs(RECESS_EDGE), hexs(RECESS_CENTRE)))
    print("")
    print("tier bar calibration (measured in basic_infuser_factory.png):")
    print("  measured lit bar texels : %s" % " ".join(hexs(c) for c in MEASURED_BAR_TEXELS))
    print("  calibration target mean : %s" % hexs(BAR_MEAN))
    print("  BaseTier.BASIC source   : %s" % hexs(BASE_TIER[1]))
    print("  per-channel gain        : R %.6f  G %.6f  B %.6f" % GAIN)
    print("  BaseTier colour -> calibrated bar colour:")
    for tier in (1, 2, 3, 4):
        print("    tier %d  %s -> %s" % (tier, hexs(BASE_TIER[tier]), hexs(TIER_BAR[tier])))
    print("    tier 0  neutral grey  -> %s (fix doc 4.2.4)" % hexs(TIER_BAR[0]))
    print("")
    print("%-24s %4s %5s %-16s %-8s %-10s %s" %
          ("machine", "tier", "bars", "accent x", "numeral", "bar colour", "numeral x0,y0"))

    written = []
    images = {}
    for (mid, tier, bars, numeral) in MACHINES:
        cols = accent_columns(bars)
        num = numeral_layout(numeral)[1:] if numeral else None
        print("%-24s %4d %5d %-16s %-8s %-10s %s" %
              (mid, tier, bars, ",".join(str(c) for c in cols) if cols else "-",
               numeral or "-", hexs(TIER_BAR[tier]), ("%d,%d" % num) if num else "-"))
        face_images = []
        for face in FACES:
            arr = build_face(face, tier, bars, numeral, args.grid_spacing, args.grid_alpha)
            img = to_image(arr)
            path = out / ("%s_%s.png" % (mid, face))
            img.save(path)
            written.append(path)
            images[(mid, face)] = img
            face_images.append((face, img))
        if not args.no_previews:
            args.previews_dir.mkdir(parents=True, exist_ok=True)
            write_sheet(mid, face_images, args.previews_dir,
                        {"front_bar": "bar %s (tier %d)" % (hexs(TIER_BAR[tier]), tier)})

    expected = len(MACHINES) * len(FACES)
    print("")
    print("files written: %d block textures (%d machines x %d faces), expected %d"
          % (len(written), len(MACHINES), len(FACES), expected))

    # ---- self-verification (re-read from disk, never trust in-memory values) --
    bad = []
    for p in written:
        with Image.open(p) as im:
            if im.size != (TEX, TEX) or im.mode != "RGBA":
                bad.append("%s -> %s %s" % (p.name, im.size, im.mode))
    print("verify size/mode: all %d are 16x16 RGBA = %s%s"
          % (len(written), not bad, ("  FAILED: " + "; ".join(bad)) if bad else ""))

    ok_bars = True
    for (mid, tier, bars, numeral) in MACHINES:
        arr = np.array(images[(mid, "side")].convert("RGB"))
        found = sorted({x for x in range(TEX) if (arr[:, x] == np.array(ACCENT)).all(axis=-1).any()})
        want = accent_columns(bars)
        if found != want:
            ok_bars = False
            print("  accent mismatch %s: found %s expected %s" % (mid, found, want))
    print("verify accent bars: tier0=0, tier1=1, tier2=2, tier3=3, tier4=4 (1px %s) = %s"
          % (hexs(ACCENT), ok_bars))

    others = all(
        not (np.array(images[(mid, f)].convert("RGB")) == np.array(ACCENT)).all(axis=-1).any()
        for (mid, _t, _b, _n) in MACHINES for f in FACES if f != "side")
    print("verify accent colour appears only on side faces = %s" % others)

    ok_bar_img = True
    for (mid, tier, _b, _n) in MACHINES:
        arr = np.array(images[(mid, "front_bar")].convert("RGB"))
        row0, row1 = tuple(arr[0, 0]), tuple(arr[1, 0])
        want1 = TIER_BAR[tier]
        want0 = lighten(want1)
        if row0 != want0 or row1 != want1:
            ok_bar_img = False
            print("  front_bar mismatch %s: row0 %s row1 %s expected %s / %s"
                  % (mid, hexs(row0), hexs(row1), hexs(want0), hexs(want1)))
    print("verify front_bar rows 0/1 = lit/tier colour for all 5 machines = %s" % ok_bar_img)

    for (mid, _t, _b, _n) in MACHINES:
        arr = np.array(images[(mid, "top_inner")].convert("RGB")).astype(np.float64)
        inner = arr[1:15, 1:15]
        print("verify top_inner %-24s mean_inside=%.1f alpha_min=%d grid_rows=%s"
              % (mid, inner.mean(), images[(mid, "top_inner")].getchannel("A").getextrema()[0],
                 [i for i in range(TEX) if i % args.grid_spacing == 0]))

    outer = np.array(images[("universal_sieve", "top_outer")].convert("RGB")).astype(np.float64)
    inner = np.array(images[("universal_sieve", "top_inner")].convert("RGB")).astype(np.float64)
    grad_only = recess_surface(TEX, args.grid_spacing, args.grid_alpha, border=0).astype(np.float64)
    # only the outer 2px rim of top_outer is ever visible from above (the four ring
    # strips map their 2px `up` faces onto it), so compare that against the recess
    band = np.zeros((TEX, TEX), dtype=bool)
    band[:2, :] = band[TEX - 2:, :] = True
    band[:, :2] = band[:, TEX - 2:] = True
    print("verify depth: top_outer visible rim mean=%.1f  top_inner gradient-only mean=%.1f "
          "(%.1f darker)  top_inner as-shipped mean=%.1f (grid lifts it, by construction)"
          % (outer[band].mean(), grad_only.mean(), outer[band].mean() - grad_only.mean(),
             inner.mean()))

    tinted = {NAVY_DEEP, NAVY, NAVY_HI}
    spread_max = 0
    stray = set()
    for (mid, tier, _b, _n) in MACHINES:
        allowed = tinted | {ACCENT, NUMERAL, TIER_BAR[tier], lighten(TIER_BAR[tier])}
        for face in FACES:
            arr = np.array(images[(mid, face)].convert("RGB")).astype(int)
            sp = arr.max(axis=-1) - arr.min(axis=-1)
            if sp.max() > spread_max:
                spread_max = int(sp.max())
            flat = arr.reshape(-1, 3)
            for c in map(tuple, flat[sp.reshape(-1) > 6]):
                if c not in allowed:
                    stray.add(c)
    print("verify palette is neutral steel: every pixel with channel spread > 6 is one of the "
          "navy window %s / accent %s / numeral %s / the tier bar -> stray tinted colours = %s "
          "(max spread seen: %d)" % (hexs(NAVY), hexs(ACCENT), hexs(NUMERAL),
                                     sorted(hexs(c) for c in stray) or "none", spread_max))

    if not args.no_previews:
        write_grid2_preview(images[("universal_sieve", "top_inner")], args.previews_dir,
                            args.grid_spacing, 2, args.grid_alpha)
        sheets = sorted(args.previews_dir.glob("sheet_*.png"))
        g2 = args.previews_dir / "top_inner_grid2.png"
        print("previews (%d): %s" % (len(sheets) + 1,
              ", ".join(p.name for p in sheets + [g2])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
