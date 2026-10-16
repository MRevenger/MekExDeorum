#!/usr/bin/env python3
"""Deterministic pixel-art generator for MekExDeorum machine block textures.

Writes exactly 30 16x16 RGBA PNGs: 5 machines x 6 faces, into
    src/main/resources/assets/mekexdeorum/textures/block/{id}_{face}.png
and (unless --no-previews) human-verification previews under _plan/previews/
(never inside the repo).

Design source: _plan/SPEC_FREEZE.md section 6 and 任务提示词.md 6.2/6.3.
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
# One shared steel palette. Every machine/face is built from these values only,
# so the five machines read as one family.
PANEL_FIELD = (0x3A, 0x3F, 0x48)      # main steel panel surface (side/front/back/top_outer)
PANEL_BASE = (0x2A, 0x2E, 0x35)       # darker base plate (bottom)
RIM_GROOVE = (0x1E, 0x21, 0x26)       # 1px groove ring between bevel and field
EDGE_HI = (0x6B, 0x72, 0x80)          # brightest metal highlight (top_outer outer edge)
EDGE_LT = (0x4E, 0x54, 0x5E)          # light bevel, rivets, heat-sink fins
EDGE_DK = (0x16, 0x18, 0x1C)          # dark bevel (bottom/right)
SHADOW = (0x0A, 0x0A, 0x0A)           # spec 6.2 recess border shadow line
DARK = (0x1A, 0x1A, 0x1A)             # flat dark status-indicator region
RECESS_EDGE = (0x3A, 0x3A, 0x3A)      # spec 6.2 radial gradient, edge (lightest)
RECESS_CENTRE = (0x1A, 0x1A, 0x1A)    # spec 6.2 radial gradient, centre (darkest)
ACCENT = (0x00, 0xB4, 0xFF)           # spec 6.3 accent blue
ACCENT_LT = (0x4F, 0xC8, 0xFF)        # icon only: accent lifted for legibility on dark steel
NUMERAL = (0xE8, 0xED, 0xF2)          # Roman numeral ink
WHITE = (0xFF, 0xFF, 0xFF)            # grid lines, composited at --grid-alpha

PALETTE = (
    ("PANEL_FIELD", PANEL_FIELD),
    ("PANEL_BASE", PANEL_BASE),
    ("RIM_GROOVE", RIM_GROOVE),
    ("EDGE_HI", EDGE_HI),
    ("EDGE_LT", EDGE_LT),
    ("EDGE_DK", EDGE_DK),
    ("SHADOW", SHADOW),
    ("DARK", DARK),
    ("RECESS_EDGE", RECESS_EDGE),
    ("RECESS_CENTRE", RECESS_CENTRE),
    ("ACCENT", ACCENT),
    ("NUMERAL", NUMERAL),
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
FACES = ("front", "back", "side", "top_outer", "top_inner", "bottom")

# geometry constants (all 1-based-inclusive in the helpers below)
FIELD = (2, 2, 13, 13)          # inner panel field, x0 y0 x1 y1
RIVETS = ((3, 3), (12, 3), (3, 12), (12, 12))
BAR_Y0, BAR_Y1 = 4, 11          # accent bars run inside the field
STATUS = (4, 4, 11, 7)          # front status-indicator region (8x4, flat)
NUM_RIGHT, NUM_BOTTOM = 12, 12  # bottom-right ink corner for the numeral

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


def machine_panel(field=PANEL_FIELD, edge_hi=EDGE_LT):
    """Shared steel panel: bevel, 1px groove ring, inner field."""
    a = blank(field)
    hline(a, 0, 0, 15, edge_hi)
    vline(a, 0, 0, 15, edge_hi)
    hline(a, 15, 0, 15, EDGE_DK)
    vline(a, 15, 0, 15, EDGE_DK)
    rect(a, 1, 1, 14, 14, RIM_GROOVE)
    rect(a, *FIELD, field)
    px(a, 15, 15, SHADOW)
    return a


def rivets(a, positions=RIVETS):
    for (x, y) in positions:
        px(a, x, y, EDGE_LT)
        px(a, x, y + 1, RIM_GROOVE)


# ------------------------------------------------------------ face builders --
def accent_columns(n):
    """Evenly spread n 1px accent bars across the field (tiers 1..4 -> 1..4 bars)."""
    if n <= 0:
        return []
    span = FIELD[2] - FIELD[0] + 1
    return [FIELD[0] + (i * span) // (n + 1) for i in range(1, n + 1)]


def build_side(bars):
    a = machine_panel()
    rivets(a)
    for x in accent_columns(bars):
        vline(a, x, BAR_Y0, BAR_Y1, ACCENT)
        if x + 1 <= FIELD[2]:
            vline(a, x + 1, BAR_Y0, BAR_Y1, RIM_GROOVE)   # 1px drop shadow of the bar
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
    a = machine_panel()
    rivets(a)
    x0, y0, x1, y1 = STATUS
    rect(a, x0, y0, x1, y1, DARK)          # flat dark status-indicator window
    hline(a, y0, x0, x1, SHADOW)           # recess rim: top + left
    vline(a, x0, y0, y1, SHADOW)
    hline(a, y1 + 2, FIELD[0] + 1, FIELD[2], RIM_GROOVE)   # panel seam under the window
    if numeral:
        rows, nx, ny = numeral_layout(numeral)
        for gy, row in enumerate(rows):
            for gx, ch in enumerate(row):
                if ch == "1":
                    px(a, nx + gx, ny + gy, NUMERAL)
    return a


def build_back():
    a = machine_panel()
    rivets(a)
    for y in range(FIELD[1] + 1, FIELD[3]):
        rect(a, FIELD[0], y, FIELD[2], y, EDGE_LT if y % 2 else RIM_GROOVE)
    rect(a, FIELD[0], FIELD[3], FIELD[2], FIELD[3], RIM_GROOVE)
    for x in (5, 9):                        # two vertical pipes over the heat sink
        vline(a, x, FIELD[1] + 1, FIELD[3], EDGE_LT)
        vline(a, x + 1, FIELD[1] + 1, FIELD[3], EDGE_DK)
    return a


def build_top_outer():
    # Same panel as the sides, only the outer edge pixels are brighter (metal highlight).
    a = machine_panel(edge_hi=EDGE_HI)
    rivets(a)
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
        # dark rim where the recess meets the frame.
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
    a = blank(PANEL_BASE)
    hline(a, 0, 0, 15, EDGE_LT)
    vline(a, 0, 0, 15, EDGE_LT)
    hline(a, 15, 0, 15, EDGE_DK)
    vline(a, 15, 0, 15, EDGE_DK)
    rect(a, 1, 1, 14, 14, RIM_GROOVE)
    rect(a, *FIELD, PANEL_BASE)
    for (bx, by) in ((FIELD[0], FIELD[1]), (12, FIELD[1]), (FIELD[0], 12), (12, 12)):
        rect(a, bx, by, bx + 1, by + 1, EDGE_LT)
        px(a, bx + 1, by + 1, EDGE_DK)
    rect(a, 5, 5, 10, 10, RIM_GROOVE)      # recessed centre vent
    rect(a, 6, 6, 9, 9, SHADOW)
    px(a, 15, 15, SHADOW)
    return a


def build_face(face, bars, numeral, grid_spacing, grid_alpha):
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
    raise ValueError(face)


def to_image(arr):
    out = np.empty((TEX, TEX, 4), dtype=np.uint8)
    out[..., :3] = arr
    out[..., 3] = 255
    return Image.fromarray(out, "RGBA")


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


def write_sheet(machine_id, face_images, out_dir):
    n = len(face_images)
    w = PAD + n * (CELL + PAD)
    h = LABEL_H + CELL + PAD
    canvas = Image.new("RGBA", (w, h), (18, 20, 24, 255))
    d = ImageDraw.Draw(canvas)
    f = _font(12)
    for i, (face, img) in enumerate(face_images):
        x = PAD + i * (CELL + PAD)
        canvas.paste(upscale(img), (x, LABEL_H))
        d.text((x, 6), "%s_%s.png" % (machine_id, face), fill=(214, 220, 228, 255), font=f)
        d.text((x, LABEL_H + CELL + 1), face, fill=(0x7C, 0xB0, 0xD6, 255), font=f)
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
    ap = argparse.ArgumentParser(description="Generate the 30 MekExDeorum block textures.")
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
    print("palette      : " + " ".join("%s=#%02X%02X%02X" % ((n,) + c) for n, c in PALETTE))
    print("radial grads : top_inner edge #%02X%02X%02X centre #%02X%02X%02X (centre darkest)"
          % (RECESS_EDGE + RECESS_CENTRE))
    print("")
    print("%-24s %4s %5s %-16s %-8s %s" %
          ("machine", "tier", "bars", "accent x", "numeral", "numeral x0,y0"))

    written = []
    images = {}
    layouts = {}
    for (mid, tier, bars, numeral) in MACHINES:
        cols = accent_columns(bars)
        num = numeral_layout(numeral)[1:] if numeral else None
        layouts[mid] = (tier, bars, cols, numeral, num)
        print("%-24s %4d %5d %-16s %-8s %s" %
              (mid, tier, bars, ",".join(str(c) for c in cols) if cols else "-",
               numeral or "-", ("%d,%d" % num) if num else "-"))
        face_images = []
        for face in FACES:
            arr = build_face(face, bars, numeral, args.grid_spacing, args.grid_alpha)
            img = to_image(arr)
            path = out / ("%s_%s.png" % (mid, face))
            img.save(path)
            written.append(path)
            images[(mid, face)] = img
            face_images.append((face, img))
        if not args.no_previews:
            args.previews_dir.mkdir(parents=True, exist_ok=True)
            write_sheet(mid, face_images, args.previews_dir)

    print("")
    print("files written: %d block textures (%d machines x %d faces)"
          % (len(written), len(MACHINES), len(FACES)))

    # ---- self-verification (re-read from disk, never trust in-memory values) --
    bad = []
    for p in written:
        with Image.open(p) as im:
            if im.size != (TEX, TEX) or im.mode != "RGBA":
                bad.append("%s -> %s %s" % (p.name, im.size, im.mode))
    print("verify size/mode: all 16x16 RGBA = %s%s"
          % (not bad, ("  FAILED: " + "; ".join(bad)) if bad else ""))

    ok_bars = True
    for (mid, tier, bars, numeral) in MACHINES:
        arr = np.array(images[(mid, "side")].convert("RGB"))
        found = sorted({x for x in range(TEX) if (arr[:, x] == np.array(ACCENT)).all(axis=-1).any()})
        expected = accent_columns(bars)
        if found != expected:
            ok_bars = False
            print("  accent mismatch %s: found %s expected %s" % (mid, found, expected))
    print("verify accent bars: tier0=0, tier1=1, tier2=2, tier3=3, tier4=4 (1px #00B4FF) = %s"
          % ok_bars)

    others = all(
        not (np.array(images[(mid, f)].convert("RGB")) == np.array(ACCENT)).all(axis=-1).any()
        for (mid, _t, _b, _n) in MACHINES for f in FACES if f != "side")
    print("verify accent colour appears only on side faces = %s" % others)

    for (mid, _t, _b, _n) in MACHINES:
        arr = np.array(images[(mid, "top_inner")].convert("RGB")).astype(np.float64)
        inner = arr[1:15, 1:15]
        print("verify top_inner %-24s mean_inside=%.1f alpha_channel=%d grid_rows=%s"
              % (mid, inner.mean(), images[(mid, "top_inner")].getchannel("A").getextrema()[0],
                 [i for i in range(TEX) if i % args.grid_spacing == 0]))

    outer = np.array(images[("universal_sieve", "top_outer")].convert("RGB")).astype(np.float64)
    inner = np.array(images[("universal_sieve", "top_inner")].convert("RGB")).astype(np.float64)
    grad_only = recess_surface(TEX, args.grid_spacing, args.grid_alpha, border=0).astype(np.float64)
    print("verify depth: top_outer mean=%.1f  top_inner gradient-only mean=%.1f (%.1f darker)  "
          "top_inner as-shipped mean=%.1f (grid lifts it, by construction)"
          % (outer.mean(), grad_only.mean(), outer.mean() - grad_only.mean(), inner.mean()))

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
