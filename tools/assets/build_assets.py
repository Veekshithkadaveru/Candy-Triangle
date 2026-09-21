#!/usr/bin/env python3
"""
Candy Triangle (CT_001) -- image asset pipeline.

Single source of truth for how every image in `app/src/main/res/` is derived
from the `plinko_dev/` asset pack (DEVELOPMENT_PLAN.md Phase A1 / section 9.1).

Run from anywhere:

    python3 tools/assets/build_assets.py

The script is idempotent: it overwrites its outputs cleanly and can be re-run
any number of times. Nothing outside the directories listed in OWNED_PATHS is
touched.

Requires: Pillow (tested against 11.3.0). No other dependencies.
"""

from __future__ import annotations

import colorsys
import math
import os
import shutil
import sys
from xml.etree import ElementTree

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required:  python3 -m pip install Pillow")

# --------------------------------------------------------------------------
# Paths
# --------------------------------------------------------------------------

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "plinko_dev")
RES = os.path.join(ROOT, "app", "src", "main", "res")
NODPI = os.path.join(RES, "drawable-nodpi")
ANYDPI = os.path.join(RES, "mipmap-anydpi-v26")

OWNED_PATHS = (NODPI, ANYDPI, os.path.join(RES, "mipmap-"), os.path.join(RES, "drawable"))

# --------------------------------------------------------------------------
# Section 9.1 straight copies:  plinko_dev relative path -> res name
#
# Android resource names must match [a-z0-9_]+ and may not start with a digit,
# which is why the four panorama slices `1.png`-`4.png` become `world_N.png`.
# These are byte-for-byte copies (no re-encode) so nothing is re-compressed.
# --------------------------------------------------------------------------

COPIES = [
    # (source relative to plinko_dev,      destination file name in drawable-nodpi)
    ("elem/1.png", "world_1.png"),
    ("elem/2.png", "world_2.png"),
    ("elem/3.png", "world_3.png"),
    ("elem/4.png", "world_4.png"),
    ("elem/ball.png", "ball.png"),
    ("elem/coin.png", "coin.png"),
    ("elem/triangle.png", "triangle.png"),
    ("elem/candy/can_1.png", "can_1.png"),      # GREEN
    ("elem/candy/can_2.png", "can_2.png"),      # PURPLE
    ("elem/candy/can_3.png", "can_3.png"),      # PINK
    ("elem/candy/can_4.png", "can_4.png"),      # BLUE
    ("elem/gems/x5.png", "x5.png"),             # Sweet      x5
    ("elem/gems/x10_1.png", "x10_1.png"),       # Blast      x10
    ("elem/gems/x15.png", "x15.png"),           # Split      x15
    ("elem/gems/x25.png", "x25.png"),           # Extra Ball x25
    ("elem/gems/x35.png", "x35.png"),           # Magnet     x35
    ("elem/gems/x80.png", "x80.png"),           # Sugar Storm x80
]
# NOTE: elem/gems/x10_2.png is deliberately NOT copied under its own name.
# It exists only as the source for the generated Line Gem, x10_cyan.png.

# --------------------------------------------------------------------------
# Splash
#
# `splash 2732.jpeg` is 2732x2732. Decoded as ARGB_8888 that is
# 2732 * 2732 * 4 = 29.9 MB of heap for a screen that is visible for under a
# second -- DEVELOPMENT_PLAN.md section 13 flags asset memory as a Medium risk.
# Downscaled to 1440x1440 it decodes to 8.3 MB, which comfortably covers every
# phone display in portrait while cutting the boot-time allocation by ~72%.
# --------------------------------------------------------------------------

SPLASH_SRC = "splash 2732.jpeg"
SPLASH_DST = "splash.jpg"
SPLASH_MAX = 1440
SPLASH_QUALITY = 88

# --------------------------------------------------------------------------
# Generated recolour variants (section 9.1)
# --------------------------------------------------------------------------

# --- x10_cyan (Line Gem) ---------------------------------------------------
# elem/gems/x10_2.png and elem/gems/x35.png are both deep blue faceted gems and
# are effectively the same colour on screen (Lab dE of the 60px mean is 3.9).
# The body hues are rotated into cyan and brightened; the rotation is limited to
# the cyan..magenta band so the warm gold rim shared by every gem in the pack is
# left alone and the sprite still reads as part of the gem family.
CYAN_HUE_BAND = (160.0, 300.0)   # degrees; only hues inside this band are moved
CYAN_HUE_DELTA = -35.0           # dominant body hue 218 deg -> 183 deg (~185 cyan)
CYAN_L_GAMMA = 0.72              # lightness lift: L' = L ** 0.72 (keeps 0 and 1 fixed)
CYAN_S_SCALE = 1.10              # saturation boost, clamped at 1.0

# --- Ball skins ------------------------------------------------------------
# elem/ball.png is a glossy magenta sphere. Each skin re-targets the hue and
# rescales lightness/saturation rather than flat-filling, so the specular
# highlight and the body shading gradient survive intact:
#
#   hue' = target_hue + (hue - base_hue) * HUE_KEEP     (keeps residual variation)
#   L'   = L ** gamma,  gamma = ln(target_L) / ln(base_L)   -> L=0 and L=1 fixed,
#                                                              so the white
#                                                              specular stays white
#   S'   = min(1, S * target_S / base_S)                -> S=0 fixed, so the
#                                                              highlight stays neutral
#
# base_hue / base_L / base_S are measured from the source at run time.
BALL_HUE_KEEP = 0.5
BALL_SKINS = [
    ("ball_green.png", "#4FE06B"),
    ("ball_purple.png", "#A56BFF"),
    ("ball_blue.png", "#3FE3FF"),
    ("ball_gold.png", "#FFC23F"),
]

# --- crown_empty -----------------------------------------------------------
# Section 5.3 shows 1-3 crowns using coin.png; unearned slots sit beside earned
# ones, so the empty slot is the same sprite desaturated to near-grey and
# darkened. Alpha is untouched, so the crown silhouette still reads at HUD size.
CROWN_S_SCALE = 0.08             # 92% desaturation -> near neutral grey
CROWN_TARGET_L = 0.30            # median lightness 0.51 -> 0.30 via L ** gamma

# --------------------------------------------------------------------------
# Launcher icons (section 9.1 "icon 1024.png -> mipmap-* launcher buckets")
#
# plinko_dev/android/ already ships the rendered bucket set, so those PNGs are
# used directly rather than re-rasterising `icon 1024.png`.
#
# mipmap-ldpi is skipped on purpose: plinko_dev has no ic_launcher_foreground
# for it, and minSdk 26 never selects an ldpi bucket on a real device.
# --------------------------------------------------------------------------

MIPMAP_BUCKETS = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]
MIPMAP_FILES = ["ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png"]

# Android Studio's template shipped .webp launcher icons. A .webp and a .png
# with the same resource name in the same folder is a duplicate-resource build
# failure, so the template files have to go.
STALE_MIPMAP_FILES = ["ic_launcher.webp", "ic_launcher_round.webp"]

# The template's green adaptive-icon vectors, replaced by the plinko artwork.
STALE_DRAWABLES = ["ic_launcher_background.xml", "ic_launcher_foreground.xml"]

# @color/ic_launcher_background must resolve to #320050 -- the value from
# plinko_dev/android/values/ic_launcher_background.xml. That colour lives in
# res/values/colors.xml, which this script does NOT own or write.
IC_LAUNCHER_BACKGROUND = "#320050"

ADAPTIVE_ICON_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/assets/build_assets.py. Do not hand-edit. -->
<!-- Requires @color/ic_launcher_background ({bg}) in res/values/colors.xml. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

_written: list[str] = []
_deleted: list[str] = []


def rel(path: str) -> str:
    return os.path.relpath(path, ROOT)


def note_write(path: str, detail: str) -> None:
    _written.append(path)
    print(f"  wrote   {rel(path):48s} {detail}")


def note_delete(path: str) -> None:
    _deleted.append(path)
    print(f"  deleted {rel(path)}")


def ensure_dir(path: str) -> None:
    os.makedirs(path, exist_ok=True)


def png_detail(path: str) -> str:
    with Image.open(path) as im:
        size = im.size
        mode = im.mode
    return f"{size[0]}x{size[1]} {mode} {os.path.getsize(path) / 1024:.0f} KB"


def hex_to_hls(value: str) -> tuple[float, float, float]:
    """'#RRGGBB' -> (hue degrees, lightness 0-1, saturation 0-1)."""
    r, g, b = (int(value[i:i + 2], 16) for i in (1, 3, 5))
    h, l, s = colorsys.rgb_to_hls(r / 255.0, g / 255.0, b / 255.0)
    return h * 360.0, l, s


def measure(path: str) -> tuple[float, float, float]:
    """Circular-mean hue plus median lightness/saturation of the opaque body.

    Samples every 3rd pixel with alpha >= 200. Deterministic for a given source,
    so the derived gamma/scale factors are stable across runs.
    """
    with Image.open(path) as src:
        im = src.convert("RGBA")
    px = im.load()
    w, h = im.size
    sin_sum = cos_sum = 0.0
    lights: list[float] = []
    sats: list[float] = []
    for y in range(0, h, 3):
        for x in range(0, w, 3):
            r, g, b, a = px[x, y]
            if a < 200:
                continue
            hh, ll, ss = colorsys.rgb_to_hls(r / 255.0, g / 255.0, b / 255.0)
            rad = hh * 2.0 * math.pi
            sin_sum += math.sin(rad)
            cos_sum += math.cos(rad)
            lights.append(ll)
            sats.append(ss)
    if not lights:
        raise RuntimeError(f"{path}: no opaque pixels to measure")
    lights.sort()
    sats.sort()
    mid = len(lights) // 2
    hue = math.degrees(math.atan2(sin_sum, cos_sum)) % 360.0
    return hue, lights[mid], sats[mid]


def gamma_for(base_l: float, target_l: float) -> float:
    """Exponent g such that base_l ** g == target_l. Fixes L=0 and L=1."""
    return math.log(target_l) / math.log(base_l)


def transform_pixels(src_path: str, dst_path: str, fn) -> None:
    """Apply fn(h_deg, l, s) -> (h_deg, l, s) to every non-transparent pixel.

    Alpha and image dimensions are carried through untouched.
    """
    with Image.open(src_path) as src:
        im = src.convert("RGBA")
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            hh, ll, ss = colorsys.rgb_to_hls(r / 255.0, g / 255.0, b / 255.0)
            nh, nl, ns = fn(hh * 360.0, ll, ss)
            nr, ng, nb = colorsys.hls_to_rgb((nh % 360.0) / 360.0,
                                             min(1.0, max(0.0, nl)),
                                             min(1.0, max(0.0, ns)))
            px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    im.save(dst_path, "PNG", optimize=True)


# --------------------------------------------------------------------------
# Steps
# --------------------------------------------------------------------------

def step_copies() -> None:
    print("\n[1/6] Straight copies -> drawable-nodpi/")
    ensure_dir(NODPI)
    for src_rel, dst_name in COPIES:
        src = os.path.join(SRC, src_rel)
        dst = os.path.join(NODPI, dst_name)
        shutil.copyfile(src, dst)
        note_write(dst, f"{png_detail(dst)}  <- {src_rel}")


def step_splash() -> None:
    print("\n[2/6] Splash downscale")
    src = os.path.join(SRC, SPLASH_SRC)
    dst = os.path.join(NODPI, SPLASH_DST)
    with Image.open(src) as im:
        original = im.size
        out = im.convert("RGB")
        out.thumbnail((SPLASH_MAX, SPLASH_MAX), Image.LANCZOS)
        out.save(dst, "JPEG", quality=SPLASH_QUALITY, optimize=True, progressive=True)
    before = original[0] * original[1] * 4 / (1024 ** 2)
    with Image.open(dst) as im:
        after = im.size[0] * im.size[1] * 4 / (1024 ** 2)
    note_write(dst, f"{png_detail(dst)}  <- {SPLASH_SRC} {original[0]}x{original[1]} "
                    f"(ARGB_8888 {before:.1f} MB -> {after:.1f} MB)")


def step_cyan_gem() -> None:
    print("\n[3/6] x10_cyan (Line Gem) recolour")
    src = os.path.join(SRC, "elem/gems/x10_2.png")
    dst = os.path.join(NODPI, "x10_cyan.png")
    lo, hi = CYAN_HUE_BAND

    def fn(h: float, l: float, s: float):
        if lo <= h <= hi:
            return h + CYAN_HUE_DELTA, l ** CYAN_L_GAMMA, min(1.0, s * CYAN_S_SCALE)
        return h, l, s

    transform_pixels(src, dst, fn)
    note_write(dst, f"{png_detail(dst)}  <- elem/gems/x10_2.png  "
                    f"hue{CYAN_HUE_DELTA:+.0f} in [{lo:.0f},{hi:.0f}], "
                    f"L**{CYAN_L_GAMMA}, S*{CYAN_S_SCALE}")


def step_ball_skins() -> None:
    print("\n[4/6] Ball skin recolours")
    src = os.path.join(SRC, "elem/ball.png")
    base_h, base_l, base_s = measure(src)
    print(f"  base ball.png: hue={base_h:.1f} deg  medianL={base_l:.3f}  medianS={base_s:.3f}")
    for dst_name, target_hex in BALL_SKINS:
        t_h, t_l, t_s = hex_to_hls(target_hex)
        l_gamma = gamma_for(base_l, t_l)
        s_scale = t_s / base_s
        dst = os.path.join(NODPI, dst_name)

        def fn(h: float, l: float, s: float, _th=t_h, _g=l_gamma, _ss=s_scale):
            delta = ((h - base_h + 180.0) % 360.0) - 180.0
            return _th + delta * BALL_HUE_KEEP, l ** _g, min(1.0, s * _ss)

        transform_pixels(src, dst, fn)
        note_write(dst, f"{png_detail(dst)}  target {target_hex} "
                        f"(hue {t_h:.0f} deg, L**{l_gamma:.3f}, S*{s_scale:.3f})")


def step_crown_empty() -> None:
    print("\n[5/6] crown_empty")
    src = os.path.join(SRC, "elem/coin.png")
    dst = os.path.join(NODPI, "crown_empty.png")
    _, base_l, base_s = measure(src)
    l_gamma = gamma_for(base_l, CROWN_TARGET_L)

    def fn(h: float, l: float, s: float):
        return h, l ** l_gamma, s * CROWN_S_SCALE

    transform_pixels(src, dst, fn)
    note_write(dst, f"{png_detail(dst)}  <- elem/coin.png  "
                    f"S*{CROWN_S_SCALE}, L**{l_gamma:.3f} (median {base_l:.2f} -> {CROWN_TARGET_L})")


def step_launcher_icons() -> None:
    print("\n[6/6] Launcher icons")
    for bucket in MIPMAP_BUCKETS:
        src_dir = os.path.join(SRC, "android", f"mipmap-{bucket}")
        dst_dir = os.path.join(RES, f"mipmap-{bucket}")
        ensure_dir(dst_dir)
        for name in MIPMAP_FILES:
            src = os.path.join(src_dir, name)
            dst = os.path.join(dst_dir, name)
            shutil.copyfile(src, dst)
            note_write(dst, png_detail(dst))
        # A .webp next to a same-named .png is a duplicate-resource build error.
        for name in STALE_MIPMAP_FILES:
            stale = os.path.join(dst_dir, name)
            if os.path.exists(stale):
                os.remove(stale)
                note_delete(stale)

    # mipmap-ldpi is intentionally skipped (no foreground asset; minSdk 26).
    ldpi = os.path.join(RES, "mipmap-ldpi")
    if os.path.isdir(ldpi):
        print(f"  skipped {rel(ldpi)} (no ic_launcher_foreground in plinko_dev; minSdk 26)")

    ensure_dir(ANYDPI)
    xml = ADAPTIVE_ICON_XML.format(bg=IC_LAUNCHER_BACKGROUND)
    # aapt2 parses this far more strictly than a casual eyeball does: a literal
    # "--" inside a comment, for instance, is illegal XML and fails
    # mergeDebugResources rather than being ignored. Fail here instead.
    ElementTree.fromstring(xml)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        dst = os.path.join(ANYDPI, name)
        with open(dst, "w", encoding="utf-8") as fh:
            fh.write(xml)
        note_write(dst, "adaptive-icon -> @color/ic_launcher_background + @mipmap/ic_launcher_foreground")

    # The template vectors the adaptive icons no longer reference.
    drawable = os.path.join(RES, "drawable")
    for name in STALE_DRAWABLES:
        stale = os.path.join(drawable, name)
        if os.path.exists(stale):
            os.remove(stale)
            note_delete(stale)


def main() -> int:
    if not os.path.isdir(SRC):
        sys.exit(f"Asset pack not found: {SRC}")
    print(f"Candy Triangle asset pipeline\n  source: {rel(SRC)}\n  target: {rel(RES)}")

    step_copies()
    step_splash()
    step_cyan_gem()
    step_ball_skins()
    step_crown_empty()
    step_launcher_icons()

    print(f"\nDone. {len(_written)} file(s) written, {len(_deleted)} file(s) deleted.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
