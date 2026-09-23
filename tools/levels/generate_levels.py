#!/usr/bin/env python3
"""
Candy Triangle -- level generator (DEVELOPMENT_PLAN.md Phase C1).

Single source of truth for `app/src/main/assets/levels.json`: 40 main levels
(ids 1-40, worlds 1-4) plus the four Sweet Rooms (ids 101-104, codes B1-B4,
world 0). Run from anywhere:

    python3 tools/levels/generate_levels.py            # write levels.json
    python3 tools/levels/generate_levels.py --check    # fail if levels.json is stale
    python3 tools/levels/generate_levels.py --report   # also print a per-level table

Deterministic: the output is byte-identical across runs (fixed key order,
fixed formatting, trailing newline). Standard library only.

How targets are derived
-----------------------
Objective targets that depend on how many candies of a colour end up on the
board (COLLECT_CANDY) are computed here, not guessed: this script replays the
game's `CandyPlacer` exactly -- same gap enumeration, same float32 arithmetic,
same `java.util.SplittableRandom` sequence -- so it knows the per-colour counts
the app will place, and caps every COLLECT_CANDY target at available / 1.5
(the C2 feasibility rule, section 11.3). `LevelAuthoringReportTest` re-checks
the counts on the real Kotlin code.

Authoring rules enforced by `validate()` (script aborts on any violation):
  * node (0,0) is a hole on every FULL level (B1 note 16: row 0's axis peg
    bounces a straight shot back into the apex);
  * gems sit >= 74 u (perpendicular) from both walls -- 36 u peg-to-wall
    clearance + 30 u gem radius + an 8 u margin -- on an active (peg-carrying)
    node, never on or within 1 row of a moving row, >= 1.9 d from each other,
    never before their introLevel, at most one SUGAR_STORM per level;
  * every fixed candy lands on an eligible gap (the level would log and skip
    it otherwise);
  * COLLECT_CANDY / COLLECT_GEM targets <= available / 1.5, CLEAR_COLOR colour
    present, crowns 1 <= two < three <= balls - 1, world 4 has exactly two
    objectives.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import struct
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CONFIG_PATH = os.path.join(ASSETS, "config.json")
OUT_PATH = os.path.join(ASSETS, "levels.json")

SCHEMA_VERSION = 1
COLORS = ["GREEN", "PURPLE", "PINK", "BLUE"]  # CandyColor.entries order -- the weighted draw depends on it
GEM_INTRO = {
    "SWEET": 3, "BLAST": 6, "LINE": 11, "SPLIT": 15,
    "EXTRA_BALL": 21, "MAGNET": 25, "SUGAR_STORM": 31,
}
GEM_MARGIN = 74.0        # min perpendicular gem-centre-to-wall distance (36 + 30 + 8)
FEASIBILITY = 1.5        # section 11.3
GEM_CAP = {"SUGAR_STORM": 1}  # config gems[].effect.maxPerLevel
SEED_TRIES = 50          # seeds id*100+7, +17, +27, ... until the design's colour intent shows up


# ---------------------------------------------------------------------------
# float32 emulation -- CandyPlacer / LatticeGeometry compute in Kotlin Float.
# A basic op on two float32 values evaluated in double and rounded once to
# float32 equals the float32 op exactly, so this reproduces the app bit-for-bit.
# ---------------------------------------------------------------------------

def f32(x: float) -> float:
    return struct.unpack("<f", struct.pack("<f", x))[0]


class Board:
    """The parts of config.json's board/physics blocks the geometry needs, as float32."""

    def __init__(self, cfg: dict):
        b = cfg["board"]
        p = cfg["physics"]
        self.axis = f32(b["centerAxisX"])
        self.y_top = f32(b["latticeBand"]["yTop"])
        self.y_bottom = f32(b["latticeBand"]["yBottom"])
        self.base_y = f32(b["baseY"])
        self.row_factor = f32(b["lattice"]["rowSpacingFactor"])
        self.walls = [
            tuple(f32(b["walls"][side][k]) for k in ("x1", "y1", "x2", "y2"))
            for side in ("left", "right")
        ]
        self.candy_r = f32(p["candyRadius"])
        self.gem_r = f32(p["gemRadius"])
        self.peg_r = f32(p["pegRadius"])

    def row_h(self, d):
        return f32(d * self.row_factor)

    def last_node_row(self, d):
        return int(f32(f32(self.y_bottom - self.y_top) / self.row_h(d)))

    def node_x(self, r, c, d):
        return f32(self.axis + f32(f32(c - f32(r / 2.0)) * d))

    def node_y(self, r, d):
        return f32(self.y_top + f32(r * self.row_h(d)))

    def gap_rows(self, d):
        return self.last_node_row(d)

    def valid_gap(self, r, c, d):
        return 0 <= r < self.gap_rows(d) and 0 <= c <= 2 * r

    def gap_center(self, r, c, d):
        k = c // 2
        x = self.node_x(r, k, d)
        y = self.node_y(r, d)
        h = self.row_h(d)
        if c % 2 == 0:
            return x, f32(y + f32(h * f32(2.0 / 3.0)))
        return f32(x + f32(d / 2.0)), f32(y + f32(h * f32(1.0 / 3.0)))

    def wall_clearance(self, x, y):
        best = None
        for (x1, y1, x2, y2) in self.walls:
            dx = f32(x2 - x1)
            dy = f32(y2 - y1)
            ln = f32(math.sqrt(f32(f32(dx * dx) + f32(dy * dy))))
            nx = f32(-dy / ln)
            ny = f32(dx / ln)
            mx = f32(f32(x1 + x2) / 2.0)
            my = f32(f32(y1 + y2) / 2.0)
            if f32(f32(f32(self.axis - mx) * nx) + f32(f32(f32(self.base_y * 0.5) - my) * ny)) < 0.0:
                nx, ny = -nx, -ny
            v = f32(f32(f32(x - x1) * nx) + f32(f32(y - y1) * ny))
            best = v if best is None else min(best, v)
        return best


# ---------------------------------------------------------------------------
# java.util.SplittableRandom (SplitMix64), exactly as the JDK specifies it.
# ---------------------------------------------------------------------------

M64 = (1 << 64) - 1
GOLDEN_GAMMA = 0x9E3779B97F4A7C15


def _i32(v: int) -> int:
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v & 0x80000000 else v


class SplittableRandom:
    def __init__(self, seed: int):
        self.seed = seed & M64

    def _next_seed(self) -> int:
        self.seed = (self.seed + GOLDEN_GAMMA) & M64
        return self.seed

    @staticmethod
    def _mix32(z: int) -> int:
        z = ((z ^ (z >> 33)) * 0x62A9D9ED799705F5) & M64
        return _i32((((z ^ (z >> 28)) * 0xCB24D0A5C88C35B3) & M64) >> 32)

    def next_int(self, bound: int) -> int:
        r = self._mix32(self._next_seed())
        m = bound - 1
        if bound & m == 0:
            return r & m
        u = (r & 0xFFFFFFFF) >> 1
        while True:
            r = u % bound
            if _i32(u + m - r) >= 0:
                return r
            u = (self._mix32(self._next_seed()) & 0xFFFFFFFF) >> 1


# ---------------------------------------------------------------------------
# Game-rule replicas used for validation and target derivation.
# ---------------------------------------------------------------------------

def gem_xy(board, g, d):
    return board.node_x(g[1], g[2], d), board.node_y(g[1], d)


def place_candies(board, lvl):
    """Replica of board/CandyPlacer.place. Returns (candies, skipped_fixed, free_after)."""
    d = lvl["d"]
    reach = f32(board.gem_r + board.candy_r)
    gems = [gem_xy(board, g, d) for g in lvl["gems"]]

    def eligible(r, c):
        if not board.valid_gap(r, c, d):
            return False
        x, y = board.gap_center(r, c, d)
        if board.wall_clearance(x, y) < board.candy_r:
            return False
        for gx, gy in gems:
            dx = f32(gx - x)
            dy = f32(gy - y)
            if f32(f32(dx * dx) + f32(dy * dy)) < f32(reach * reach):
                return False
        return True

    out, taken, skipped = [], set(), []
    for color, r, c in lvl["fixed"]:
        if (r, c) in taken or not eligible(r, c):
            skipped.append((color, r, c))
            continue
        taken.add((r, c))
        out.append((color, r, c))
    free = [(r, c) for r in range(board.gap_rows(d)) for c in range(2 * r + 1)
            if (r, c) not in taken and eligible(r, c)]
    weights = [max(0, lvl["weights"].get(k, 0)) for k in COLORS]
    total = sum(weights)
    rng = SplittableRandom(lvl["seed"])
    n = min(lvl["count"], len(free))
    for _ in range(n if total > 0 else 0):
        i = rng.next_int(len(free))
        gap = free[i]
        free[i] = free[-1]
        free.pop()
        roll = rng.next_int(total)
        color = COLORS[-1]
        for idx, w in enumerate(weights):
            if roll < w:
                color = COLORS[idx]
                break
            roll -= w
        out.append((color, gap[0], gap[1]))
    return out, skipped, len(free), lvl["count"] > n + 0


# ---------------------------------------------------------------------------
# Layout helpers (design-time only; float64 is fine here).
# ---------------------------------------------------------------------------

def gem_col_range(r):
    """Columns of node row r whose gem clears both walls by GEM_MARGIN (scale-free, see validate)."""
    lo = 1 if r <= 4 else (2 if r <= 11 else 3)
    return lo, r - lo


def node_xy64(r, c, d):
    return 500 + (c - r / 2) * d, 150 + r * d * 0.8660254


def blob(center, radius, d, last_row):
    """Lattice nodes within radius*d of node `center` (radius 1.1 -> 7 nodes, 2.1 -> 19)."""
    cx, cy = node_xy64(center[0], center[1], d)
    out = []
    for r in range(last_row + 1):
        for c in range(r + 1):
            x, y = node_xy64(r, c, d)
            if math.hypot(x - cx, y - cy) <= radius * d + 1e-6:
                out.append([r, c])
    return out


def centre_col(r, off=0):
    """Node column at horizontal offset `off` (in d) from the axis on row r; r and 2*off same parity."""
    return int(round(r / 2 + off))


# ---------------------------------------------------------------------------
# Ramps (config.json worlds[]) and crowns.
# ---------------------------------------------------------------------------

def rnd(x: float) -> int:
    return int(math.floor(x + 0.5))


def world_of(cfg, level_id):
    for w in cfg["worlds"]:
        if w["levelFrom"] <= level_id <= w["levelTo"]:
            return w
    raise ValueError(level_id)


def ramp(cfg, level_id):
    w = world_of(cfg, level_id)
    t = (level_id - w["levelFrom"]) / (w["levelTo"] - w["levelFrom"])
    balls = rnd(w["ballsFrom"] + (w["ballsTo"] - w["ballsFrom"]) * t)
    d = rnd(w["spacingFrom"] + (w["spacingTo"] - w["spacingFrom"]) * t)
    return w["index"], balls, d, w["cupSpeed"]


def crowns_for(balls, two_frac=0.25, three_frac=0.5):
    two = max(1, rnd(balls * two_frac))
    three = max(two + 1, rnd(balls * three_frac))
    three = min(three, balls - 1)
    two = min(two, three - 1)
    return [two, three]


# ---------------------------------------------------------------------------
# The level designs.
#
# Each entry is a dict of design choices; `build()` fills in the derived parts.
# Objective specs:
#   ("CANDY", colour|None, frac)   COLLECT_CANDY, target = round(frac * available) capped at available/1.5
#   ("GEM", type|None, n)          COLLECT_GEM
#   ("SCORE", n) / ("CUP", n) / ("CLEAR", colour) / ("CHAIN", length, times)
# ---------------------------------------------------------------------------

U = {"GREEN": 1, "PURPLE": 1, "PINK": 1, "BLUE": 1}


def w(**kw):
    out = {k: 0 for k in COLORS}
    out.update(kw)
    return out


DESIGNS = {
    # ----- World 1 . Sugar Stage: FULL lattice, colour collection, score, cup -----
    1: dict(count=28, weights=U, gems=[],
            obj=[("CANDY", None, 0.40)]),
    2: dict(count=30, weights=w(GREEN=1, PURPLE=1, PINK=2, BLUE=1), gems=[],
            obj=[("CANDY", "PINK", 0.50)]),
    3: dict(count=30, weights=U, gems=[("SWEET", 4, 2), ("SWEET", 7, 2), ("SWEET", 7, 5)],
            obj=[("GEM", "SWEET", 2)]),
    4: dict(count=32, weights=U, gems=[("SWEET", 5, 2), ("SWEET", 8, 3), ("SWEET", 8, 6)],
            obj=[("SCORE", 1000)]),
    5: dict(count=32, weights=w(GREEN=1, PURPLE=1, PINK=1, BLUE=2), gems=[("SWEET", 6, 2), ("SWEET", 6, 4)],
            obj=[("CANDY", "BLUE", 0.50), ("CUP", 1)]),
    6: dict(count=34, weights=U, gems=[("BLAST", 4, 2), ("BLAST", 8, 2), ("BLAST", 8, 6), ("SWEET", 10, 4)],
            obj=[("GEM", "BLAST", 2)]),
    7: dict(count=34, weights=w(GREEN=1, PURPLE=2, PINK=1, BLUE=1), gems=[("BLAST", 6, 3), ("SWEET", 9, 2), ("SWEET", 9, 7)],
            obj=[("CANDY", "PURPLE", 0.55)]),
    8: dict(count=36, weights=w(GREEN=1, PURPLE=1, PINK=2, BLUE=1), gems=[("BLAST", 5, 2), ("BLAST", 9, 6), ("SWEET", 7, 4)],
            obj=[("CANDY", "PINK", 0.55), ("SCORE", 3000)]),
    9: dict(count=36, weights=U, gems=[("SWEET", 5, 3), ("BLAST", 8, 2), ("BLAST", 10, 7)],
            obj=[("CANDY", None, 0.50), ("CUP", 2)]),
    10: dict(count=38, weights=w(GREEN=2, PURPLE=1, PINK=1, BLUE=1),
             gems=[("BLAST", 4, 2), ("SWEET", 7, 2), ("SWEET", 7, 5), ("BLAST", 10, 5)],
             obj=[("CANDY", "GREEN", 0.60), ("SCORE", 4000)]),

    # ----- World 2 . Velvet Swirl: holes, cluster layouts, clear-colour, chains -----
    11: dict(count=36, weights=U, holes=[("blob", (6, 3), 1.1)],
             gems=[("LINE", 4, 2), ("LINE", 9, 2), ("LINE", 9, 7), ("SWEET", 11, 5)],
             obj=[("GEM", "LINE", 2)]),
    12: dict(count=36, weights=w(GREEN=2, PURPLE=1, PINK=2, BLUE=0), pattern="CLUSTERS",
             clusters=[((3, 1), 1.1), ((6, 2), 1.1), ((6, 4), 1.1), ((9, 3), 1.1), ((9, 6), 1.1), ((12, 4), 1.1), ((12, 8), 1.1)],
             clear_fixed=("BLUE", 3),
             gems=[("BLAST", 6, 3), ("LINE", 9, 6)],
             obj=[("CLEAR", "BLUE")]),
    13: dict(count=38, weights=w(GREEN=1, PURPLE=1, PINK=3, BLUE=1), holes=[("blob", (5, 1), 1.1), ("blob", (5, 4), 1.1)],
             gems=[("SWEET", 8, 4), ("LINE", 11, 2), ("LINE", 11, 9)],
             obj=[("CHAIN", 3, 1), ("CANDY", "PINK", 0.55)]),
    14: dict(count=38, weights=w(GREEN=1, PURPLE=2, PINK=1, BLUE=1), pattern="CLUSTERS",
             clusters=[((2, 1), 1.1), ((5, 1), 1.1), ((5, 4), 1.1), ((8, 2), 1.1), ((8, 6), 1.1), ((11, 4), 1.8), ((11, 8), 1.1), ((11, 1), 1.1)],
             gems=[("SWEET", 5, 2), ("BLAST", 8, 6), ("LINE", 11, 4)],
             obj=[("CANDY", "PURPLE", 0.60), ("SCORE", 3500)]),
    15: dict(count=38, weights=U, holes=[("blob", (7, 1), 1.1), ("blob", (7, 6), 1.1)],
             gems=[("SPLIT", 4, 2), ("SPLIT", 9, 3), ("SPLIT", 9, 6), ("LINE", 12, 6)],
             obj=[("GEM", "SPLIT", 2)]),
    16: dict(count=38, weights=w(GREEN=0, PURPLE=1, PINK=1, BLUE=1), pattern="CLUSTERS",
             clusters=[((3, 2), 1.1), ((6, 1), 1.1), ((6, 5), 1.1), ((9, 4), 1.8), ((12, 2), 1.1), ((12, 10), 1.1), ((13, 6), 1.1)],
             clear_fixed=("GREEN", 4),
             gems=[("SPLIT", 9, 4), ("BLAST", 6, 4)],
             obj=[("CLEAR", "GREEN"), ("CUP", 1)]),
    17: dict(count=40, weights=w(GREEN=1, PURPLE=1, PINK=1, BLUE=3), holes=[("blob", (4, 2), 1.1), ("blob", (9, 2), 1.1), ("blob", (9, 7), 1.1)],
             gems=[("SPLIT", 6, 4), ("SWEET", 12, 6), ("LINE", 7, 2)],
             obj=[("CHAIN", 3, 1), ("SCORE", 7000)]),
    18: dict(count=40, weights=w(GREEN=1, PURPLE=1, PINK=0, BLUE=2), pattern="CLUSTERS",
             clusters=[((2, 1), 1.1), ((5, 2), 1.8), ((8, 1), 1.1), ((8, 7), 1.1), ((10, 4), 1.8), ((13, 3), 1.1), ((13, 10), 1.1)],
             clear_fixed=("PINK", 5),
             gems=[("BLAST", 5, 2), ("SPLIT", 10, 4), ("LINE", 13, 10)],
             obj=[("CANDY", "BLUE", 0.60), ("CLEAR", "PINK")]),
    19: dict(count=42, weights=w(GREEN=1, PURPLE=3, PINK=1, BLUE=1), holes=[("blob", (6, 3), 1.8), ("blob", (11, 2), 1.1), ("blob", (11, 9), 1.1)],
             gems=[("SWEET", 3, 1), ("SPLIT", 9, 7), ("BLAST", 9, 2), ("LINE", 13, 6)],
             obj=[("CHAIN", 3, 1), ("CANDY", None, 0.55)]),
    20: dict(count=42, weights=w(GREEN=2, PURPLE=0, PINK=1, BLUE=2), pattern="CLUSTERS",
             clusters=[((3, 1), 1.1), ((3, 2), 1.1), ((6, 3), 1.8), ((9, 1), 1.1), ((9, 8), 1.1), ((11, 5), 1.8), ((14, 3), 1.1), ((14, 11), 1.1)],
             clear_fixed=("PURPLE", 5),
             gems=[("SPLIT", 6, 3), ("LINE", 11, 5), ("BLAST", 9, 7)],
             obj=[("CLEAR", "PURPLE"), ("CHAIN", 3, 1), ("SCORE", 4000)]),

    # ----- World 3 . Neon Ribbons: moving peg rows, Extra Ball, Magnet -----
    21: dict(count=40, weights=U, moving=[(9, 30, 3.5)],
             gems=[("EXTRA_BALL", 4, 2), ("EXTRA_BALL", 6, 2), ("EXTRA_BALL", 6, 4), ("SWEET", 12, 6)],
             obj=[("GEM", "EXTRA_BALL", 2)]),
    22: dict(count=42, weights=w(GREEN=1, PURPLE=1, PINK=2, BLUE=1), moving=[(7, 35, 3.5)],
             gems=[("SPLIT", 4, 2), ("EXTRA_BALL", 10, 5), ("LINE", 12, 3)],
             obj=[("CANDY", "PINK", 0.60), ("CUP", 2)]),
    23: dict(count=42, weights=w(GREEN=2, PURPLE=1, PINK=2, BLUE=0), moving=[(6, 35, 3.0), (11, 40, 3.5)],
             clear_fixed=("BLUE", 6),
             gems=[("BLAST", 4, 2), ("EXTRA_BALL", 8, 4), ("LINE", 14, 7)],
             obj=[("CLEAR", "BLUE"), ("SCORE", 8000)]),
    24: dict(count=44, weights=w(GREEN=3, PURPLE=1, PINK=1, BLUE=1), holes=[("blob", (4, 2), 1.1)], moving=[(8, 40, 3.0)],
             gems=[("SPLIT", 6, 2), ("SPLIT", 6, 4), ("EXTRA_BALL", 11, 5), ("SWEET", 13, 10)],
             obj=[("CHAIN", 3, 2), ("CANDY", "GREEN", 0.60)]),
    25: dict(count=44, weights=U, moving=[(9, 40, 3.0)],
             gems=[("MAGNET", 4, 2), ("MAGNET", 6, 2), ("MAGNET", 6, 4), ("EXTRA_BALL", 12, 6)],
             obj=[("GEM", "MAGNET", 2)]),
    26: dict(count=44, weights=w(GREEN=0, PURPLE=2, PINK=1, BLUE=1), pattern="CLUSTERS",
             clusters=[((3, 1), 1.1), ((5, 2), 1.8), ((8, 1), 1.1), ((8, 7), 1.1), ((10, 4), 1.8), ((13, 3), 1.8), ((13, 10), 1.8)],
             moving=[(8, 35, 3.5)],
             clear_fixed=("GREEN", 6),
             gems=[("MAGNET", 5, 2), ("SPLIT", 10, 4), ("BLAST", 13, 10)],
             obj=[("CANDY", "PURPLE", 0.60), ("CLEAR", "GREEN")]),
    27: dict(count=46, weights=U, holes=[("blob", (5, 2), 1.1), ("blob", (10, 5), 1.1)], moving=[(7, 45, 3.0), (12, 40, 4.0)],
             gems=[("MAGNET", 3, 1), ("EXTRA_BALL", 9, 2), ("LINE", 9, 7), ("SPLIT", 14, 7)],
             obj=[("SCORE", 15000), ("CUP", 2)]),
    28: dict(count=46, weights=w(GREEN=1, PURPLE=1, PINK=1, BLUE=3), moving=[(5, 30, 2.5), (10, 45, 3.5)],
             gems=[("SPLIT", 7, 2), ("MAGNET", 7, 5), ("BLAST", 13, 6)],
             obj=[("CHAIN", 3, 2), ("CANDY", "BLUE", 0.60)]),
    29: dict(count=48, weights=w(GREEN=2, PURPLE=2, PINK=0, BLUE=1), holes=[("blob", (6, 3), 1.8)], moving=[(9, 45, 3.0), (13, 50, 3.5)],
             clear_fixed=("PINK", 7),
             gems=[("MAGNET", 3, 1), ("BLAST", 11, 3), ("EXTRA_BALL", 11, 8), ("SPLIT", 15, 6)],
             obj=[("CLEAR", "PINK"), ("GEM", None, 2)]),
    30: dict(count=50, weights=w(GREEN=1, PURPLE=1, PINK=2, BLUE=1), pattern="CLUSTERS",
             clusters=[((2, 1), 1.1), ((4, 2), 1.8), ((7, 1), 1.1), ((7, 6), 1.1), ((10, 5), 2.1), ((13, 2), 1.1), ((13, 11), 1.1), ((15, 7), 1.8)],
             moving=[(7, 40, 3.0)],
             gems=[("MAGNET", 4, 2), ("SPLIT", 10, 5), ("EXTRA_BALL", 13, 3), ("LINE", 15, 7)],
             obj=[("CANDY", None, 0.55), ("CHAIN", 3, 1), ("SCORE", 11000)]),

    # ----- World 4 . Golden Thread: Sugar Storm, exactly two objectives -----
    31: dict(count=48, weights=U, moving=[(12, 40, 3.5)],
             gems=[("SUGAR_STORM", 6, 3), ("SWEET", 3, 1), ("BLAST", 9, 2), ("BLAST", 9, 7)],
             obj=[("GEM", "SUGAR_STORM", 1), ("CANDY", None, 0.50)]),
    32: dict(count=50, weights=w(GREEN=1, PURPLE=2, PINK=1, BLUE=1), holes=[("blob", (8, 4), 1.1)], moving=[(5, 35, 3.0), (13, 45, 3.5)],
             gems=[("SUGAR_STORM", 10, 7), ("LINE", 10, 2), ("SPLIT", 3, 2)],
             obj=[("CANDY", "PURPLE", 0.62), ("CUP", 2)]),
    33: dict(count=50, weights=w(GREEN=2, PURPLE=1, PINK=0, BLUE=2), pattern="CLUSTERS",
             clusters=[((3, 1), 1.1), ((5, 3), 1.8), ((8, 1), 1.1), ((8, 7), 1.1), ((11, 5), 2.1), ((14, 2), 1.1), ((14, 12), 1.1), ((16, 8), 1.8)],
             moving=[(8, 40, 3.0)],
             clear_fixed=("PINK", 7),
             gems=[("SUGAR_STORM", 11, 5), ("MAGNET", 5, 3), ("EXTRA_BALL", 14, 11)],
             obj=[("CLEAR", "PINK"), ("SCORE", 25000)]),
    34: dict(count=52, weights=w(GREEN=1, PURPLE=1, PINK=5, BLUE=1), holes=[("blob", (4, 2), 1.1), ("blob", (13, 3), 1.1), ("blob", (13, 10), 1.1)], moving=[(7, 45, 3.0), (10, 40, 2.5)],
             gems=[("SPLIT", 2, 1), ("BLAST", 13, 6), ("MAGNET", 15, 9)],
             obj=[("CHAIN", 4, 1), ("CANDY", "PINK", 0.62)]),
    35: dict(count=52, weights=U, moving=[(6, 40, 3.0), (11, 45, 3.5)],
             gems=[("SUGAR_STORM", 8, 4), ("EXTRA_BALL", 3, 1), ("LINE", 13, 3), ("LINE", 13, 10), ("SWEET", 15, 7)],
             obj=[("SCORE", 30000), ("GEM", None, 3)]),
    36: dict(count=54, weights=w(GREEN=0, PURPLE=2, PINK=1, BLUE=2), pattern="CLUSTERS",
             clusters=[((2, 1), 1.1), ((5, 1), 1.1), ((5, 4), 1.1), ((8, 3), 2.1), ((11, 1), 1.1), ((11, 10), 1.1), ((13, 6), 2.1), ((16, 3), 1.1), ((16, 13), 1.1)],
             moving=[(11, 45, 3.5)],
             clear_fixed=("GREEN", 8),
             gems=[("MAGNET", 8, 3), ("SPLIT", 13, 6), ("BLAST", 5, 3)],
             obj=[("CLEAR", "GREEN"), ("CUP", 2)]),
    37: dict(count=54, weights=w(GREEN=1, PURPLE=1, PINK=1, BLUE=3), holes=[("blob", (6, 3), 1.8), ("blob", (13, 6), 1.1)], moving=[(9, 45, 3.0), (15, 50, 4.0)],
             gems=[("SUGAR_STORM", 11, 3), ("SPLIT", 3, 1), ("EXTRA_BALL", 11, 8)],
             obj=[("CANDY", "BLUE", 0.65), ("SCORE", 40000)]),
    38: dict(count=56, weights=w(GREEN=5, PURPLE=1, PINK=1, BLUE=1), holes=[("blob", (4, 2), 1.1), ("blob", (14, 7), 1.1)], moving=[(6, 40, 2.5), (10, 45, 3.0), (16, 50, 3.5)],
             gems=[("MAGNET", 8, 4), ("SPLIT", 13, 3), ("BLAST", 13, 10)],
             obj=[("CHAIN", 4, 1), ("CANDY", "GREEN", 0.65)]),
    39: dict(count=56, weights=w(GREEN=2, PURPLE=2, PINK=2, BLUE=0), pattern="CLUSTERS",
             clusters=[((3, 1), 1.1), ((5, 2), 1.8), ((7, 6), 1.1), ((9, 3), 2.1), ((12, 9), 1.8), ((14, 4), 1.8), ((16, 10), 1.1), ((17, 14), 1.1)],
             moving=[(12, 45, 3.0)],
             clear_fixed=("BLUE", 8),
             gems=[("SUGAR_STORM", 9, 3), ("MAGNET", 5, 2), ("EXTRA_BALL", 14, 4)],
             obj=[("CLEAR", "BLUE"), ("GEM", None, 2)]),
    40: dict(count=58, weights=w(GREEN=1, PURPLE=2, PINK=2, BLUE=1), holes=[("blob", (5, 2), 1.1), ("blob", (9, 6), 1.1)], moving=[(7, 45, 3.0), (12, 50, 2.5), (16, 50, 3.5)],
             gems=[("SUGAR_STORM", 10, 3), ("MAGNET", 14, 8), ("SPLIT", 3, 1), ("LINE", 14, 4)],
             obj=[("CANDY", "PINK", 0.66), ("SCORE", 50000)]),
}

# Sweet Rooms B1-B4 (ids 101-104, world 0): 15 balls, FULL lattice, one-colour flood, one high
# SCORE objective. Only SWEET/BLAST gems: a Sweet Room unlocks from a 200-candy jar, which a
# player can reach before World 2's gems have been introduced.
SWEET_ROOMS = {
    101: dict(code="B1", color="GREEN", d=80, count=64, gems=[("SWEET", 5, 2), ("BLAST", 9, 3), ("SWEET", 9, 6)], score=6500),
    102: dict(code="B2", color="PURPLE", d=78, count=68, gems=[("BLAST", 5, 3), ("SWEET", 9, 2), ("BLAST", 9, 7)], score=7000),
    103: dict(code="B3", color="PINK", d=76, count=72, gems=[("SWEET", 4, 2), ("BLAST", 8, 4), ("SWEET", 12, 3), ("SWEET", 12, 9)], score=7500),
    104: dict(code="B4", color="BLUE", d=74, count=76, gems=[("BLAST", 4, 2), ("SWEET", 8, 2), ("BLAST", 8, 6), ("SWEET", 12, 6)], score=8000),
}
SWEET_ROOM_CUP = 260


# ---------------------------------------------------------------------------
# Build + validate.
# ---------------------------------------------------------------------------

class DesignError(Exception):
    pass


def resolve_holes(spec, d, last_row):
    out = []
    for kind, centre, radius in spec:
        assert kind == "blob"
        out.extend(blob(centre, radius, d, last_row))
    return out


def fixed_spread(board, lvl, color, n):
    """n fixed candies of `color` spread down the board on alternating sides of the axis."""
    rows = board.gap_rows(lvl["d"])
    picks = []
    lo_row, hi_row = 2, rows - 2
    for i in range(n):
        r = lo_row + rnd(i * (hi_row - lo_row) / max(1, n - 1))
        side = -1 if i % 2 == 0 else 1
        # Target a column about a third of the way to the wall, then walk inward until eligible.
        base = r + side * max(1, rnd(r * 0.25))
        for step in range(0, 2 * r + 1):
            c = base - side * step
            if not (0 <= c <= 2 * r) or (r, c) in {(p[1], p[2]) for p in picks}:
                continue
            probe = dict(lvl, fixed=[(color, r, c)] + [p for p in picks], count=0)
            placed, skipped, _, _ = place_candies(board, probe)
            if not skipped:
                picks.append((color, r, c))
                break
        else:
            raise DesignError(f"level {lvl['id']}: no eligible gap for fixed candy on gap row {r}")
    return picks


def active_nodes(lvl, last_row):
    if lvl["pattern"] == "CLUSTERS":
        return {tuple(n) for cl in lvl["clusters"] for n in cl["nodes"]}
    holes = {tuple(h) for h in lvl["holes"]}
    return {(r, c) for r in range(last_row + 1) for c in range(r + 1) if (r, c) not in holes}


def validate(board, lvl, candies, skipped):
    lid, d = lvl["id"], lvl["d"]
    err = []
    last = board.last_node_row(d)
    active = active_nodes(lvl, last)
    moving_rows = {m["row"] for m in lvl["movingRows"]}
    gems = lvl["gems"]
    if lvl["pattern"] == "FULL" and [0, 0] not in lvl["holes"]:
        err.append("node (0,0) must be a hole")
    if lvl["pattern"] == "CLUSTERS" and (0, 0) in active:
        err.append("a cluster covers node (0,0)")
    for t, r, c in gems:
        x, y = gem_xy(board, (t, r, c), d)
        clr = board.wall_clearance(x, y)
        if clr < GEM_MARGIN:
            err.append(f"gem {t}@({r},{c}) only {clr:.1f} u from a wall")
        if (r, c) not in active:
            err.append(f"gem {t}@({r},{c}) is not on an active node")
        if any(abs(r - m) <= 1 for m in moving_rows):
            err.append(f"gem {t}@({r},{c}) on/next to a moving row")
        if not lvl["isBonus"] and lid < GEM_INTRO[t]:
            err.append(f"gem {t} before its intro level {GEM_INTRO[t]}")
    for i in range(len(gems)):
        for j in range(i + 1, len(gems)):
            a = node_xy64(gems[i][1], gems[i][2], d)
            b = node_xy64(gems[j][1], gems[j][2], d)
            if math.hypot(a[0] - b[0], a[1] - b[1]) < 1.9 * d:
                err.append(f"gems {gems[i]} and {gems[j]} closer than 1.9 d")
    if sum(1 for g in gems if g[0] == "SUGAR_STORM") > 1:
        err.append("more than one SUGAR_STORM")
    for m in lvl["movingRows"]:
        if not 30 <= m["amplitude"] <= 50 or not 2.5 <= m["periodSeconds"] <= 4.0:
            err.append(f"moving row {m} out of band")
        if lvl["world"] < 3:
            err.append("moving rows before world 3")
        if not 1 <= m["row"] <= last:
            err.append(f"moving row {m['row']} outside the lattice")
        if not any(n[0] == m["row"] for n in active):
            err.append(f"moving row {m['row']} carries no pegs")
    if skipped:
        err.append(f"fixed candies skipped: {skipped}")
    if lvl["_seeded_short"]:
        err.append("candies.count exceeds free eligible gaps")
    by_color = {k: 0 for k in COLORS}
    for col, _, _ in candies:
        by_color[col] += 1
    gem_types = [g[0] for g in gems]
    for o in lvl["objectives"]:
        t = o["type"]
        if t == "COLLECT_CANDY":
            avail = by_color[o["color"]] if "color" in o else len(candies)
            if o["count"] < 1 or o["count"] * FEASIBILITY > avail:
                err.append(f"{o} infeasible (available {avail})")
        elif t == "COLLECT_GEM":
            avail = gem_types.count(o["gem"]) if "gem" in o else len(gems)
            cap = GEM_CAP.get(o.get("gem"))
            if cap is not None:  # a capped gem cannot be placed 1.5x over: target <= cap, placed >= target
                bad = not 1 <= o["count"] <= min(cap, avail)
            else:
                bad = o["count"] < 1 or o["count"] * FEASIBILITY > avail
            if bad:
                err.append(f"{o} infeasible (gems {avail})")
        elif t == "CLEAR_COLOR":
            if not 1 <= by_color[o["color"]] <= 10:
                err.append(f"{o}: {by_color[o['color']]} candies of that colour")
        elif t == "CHAIN":
            if not 3 <= o["chain"] <= 4 or not 1 <= o.get("count", 1) <= 2:
                err.append(f"{o} out of band")
        elif t == "CUP":
            if not 1 <= o["count"] <= 3:
                err.append(f"{o} out of band")
    intro = [t for t, lvl_no in GEM_INTRO.items() if lvl_no == lid]
    for t in intro:
        if not any(o["type"] == "COLLECT_GEM" and o.get("gem") == t for o in lvl["objectives"]):
            err.append(f"intro level for {t} lacks a COLLECT_GEM objective")
    if lvl["world"] == 4 and len(lvl["objectives"]) != 2:
        err.append("world 4 levels need exactly two objectives")
    two, three = lvl["crowns"]
    if not (1 <= two < three <= lvl["balls"] - 1):
        err.append(f"crowns {lvl['crowns']} invalid for {lvl['balls']} balls")
    if err:
        raise DesignError(f"level {lid}: " + "; ".join(err))
    return by_color


def build_objectives(spec, by_color, total):
    out = []
    for o in spec:
        kind = o[0]
        if kind == "CANDY":
            _, color, frac = o
            avail = by_color[color] if color else total
            target = min(int(avail / FEASIBILITY), max(1, rnd(frac * avail)))
            obj = {"type": "COLLECT_CANDY"}
            if color:
                obj["color"] = color
            obj["count"] = target
        elif kind == "GEM":
            obj = {"type": "COLLECT_GEM"}
            if o[1]:
                obj["gem"] = o[1]
            obj["count"] = o[2]
        elif kind == "SCORE":
            obj = {"type": "SCORE", "score": o[1]}
        elif kind == "CUP":
            obj = {"type": "CUP", "count": o[1]}
        elif kind == "CLEAR":
            obj = {"type": "CLEAR_COLOR", "color": o[1]}
        elif kind == "CHAIN":
            obj = {"type": "CHAIN", "chain": o[1], "count": o[2]}
        else:
            raise ValueError(kind)
        out.append(obj)
    return out


def build_level(board, cfg, lid):
    if lid in SWEET_ROOMS:
        s = SWEET_ROOMS[lid]
        world, balls, d, cup = 0, cfg["jar"]["sweetRoomBalls"], s["d"], SWEET_ROOM_CUP
        design = dict(count=s["count"], weights=w(**{s["color"]: 1}), gems=s["gems"],
                      obj=[("SCORE", s["score"])])
        crowns = crowns_for(balls)
    else:
        world, balls, d, cup = ramp(cfg, lid)
        design = DESIGNS[lid]
        crowns = crowns_for(balls)
    last = board.last_node_row(d)
    pattern = design.get("pattern", "FULL")
    clusters = [{"nodes": blob(c, r, d, last)} for c, r in design.get("clusters", [])]
    holes = [] if pattern == "CLUSTERS" else [[0, 0]] + [
        h for h in resolve_holes(design.get("holes", []), d, last) if h != [0, 0]]
    lvl = {
        "id": lid, "world": world, "balls": balls, "crowns": crowns, "d": d, "pattern": pattern,
        "holes": holes, "clusters": clusters,
        "movingRows": [{"row": r, "amplitude": a, "periodSeconds": p} for r, a, p in design.get("moving", [])],
        "seed": lid * 100 + 7, "count": design["count"], "weights": design["weights"],
        "fixed": [], "gems": list(design["gems"]), "cup": cup,
        "isBonus": lid in SWEET_ROOMS, "code": SWEET_ROOMS[lid]["code"] if lid in SWEET_ROOMS else None,
    }
    if "clear_fixed" in design:
        color, n = design["clear_fixed"]
        lvl["fixed"] = fixed_spread(board, lvl, color, n)
    # Pick the first seed whose draw honours the design: every colour a COLLECT_CANDY objective
    # names comes out as (joint) most plentiful, so the target isn't starved by an unlucky draw.
    wanted = [o[1] for o in design["obj"] if o[0] == "CANDY" and o[1]]
    for k in range(SEED_TRIES):
        lvl["seed"] = lid * 100 + 7 + 10 * k
        candies, skipped, _, short = place_candies(board, lvl)
        counts = {c: sum(1 for x in candies if x[0] == c) for c in COLORS}
        if all(counts[c] == max(counts.values()) for c in wanted):
            break
    else:
        raise DesignError(f"level {lid}: no seed in {SEED_TRIES} tries makes {wanted} the top colour")
    lvl["_seeded_short"] = short
    by_color = {k: 0 for k in COLORS}
    for col, _, _ in candies:
        by_color[col] += 1
    lvl["objectives"] = build_objectives(design["obj"], by_color, len(candies))
    validate(board, lvl, candies, skipped)
    lvl["_by_color"] = by_color
    return lvl


def to_json_level(lvl):
    out = {"id": lvl["id"]}
    if lvl["code"]:
        out["code"] = lvl["code"]
    out["world"] = lvl["world"]
    out["balls"] = lvl["balls"]
    out["crowns"] = lvl["crowns"]
    out["layout"] = {
        "spacing": lvl["d"],
        "pattern": lvl["pattern"],
        "holes": lvl["holes"],
        "clusters": lvl["clusters"],
        "movingRows": lvl["movingRows"],
    }
    out["candies"] = {
        "seed": lvl["seed"],
        "count": lvl["count"],
        "weights": {k: lvl["weights"].get(k, 0) for k in COLORS},
        "fixed": [{"color": c, "row": r, "col": k} for c, r, k in lvl["fixed"]],
    }
    out["gems"] = [{"type": t, "row": r, "col": c} for t, r, c in lvl["gems"]]
    out["cup"] = {"speed": lvl["cup"]}
    out["objectives"] = lvl["objectives"]
    return out


# ---------------------------------------------------------------------------
# Serialisation: objects one key per line, but small objects and arrays of scalars/pairs inline.
# ---------------------------------------------------------------------------

def _scalar(v):
    return json.dumps(v, ensure_ascii=False)


def _is_flat(v):
    if isinstance(v, list):
        return all(not isinstance(x, (dict, list)) or (isinstance(x, list) and all(
            not isinstance(y, (dict, list)) for y in x)) for x in v)
    if isinstance(v, dict):
        return all(not isinstance(x, (dict, list)) for x in v.values())
    return True


def _inline(v):
    if isinstance(v, dict):
        if not v:
            return "{}"
        return "{ " + ", ".join(f"{_scalar(k)}: {_inline(x)}" for k, x in v.items()) + " }"
    if isinstance(v, list):
        return "[" + ", ".join(_inline(x) for x in v) + "]"
    return _scalar(v)


def dump(v, indent=0):
    pad = "  " * indent
    inner = "  " * (indent + 1)
    if isinstance(v, dict):
        if _is_flat(v) and len(_inline(v)) <= 100:
            return _inline(v)
        items = [f"{inner}{_scalar(k)}: {dump(x, indent + 1)}" for k, x in v.items()]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    if isinstance(v, list):
        if not v:
            return "[]"
        if _is_flat(v):
            return _inline(v)
        items = [f"{inner}{dump(x, indent + 1)}" for x in v]
        return "[\n" + ",\n".join(items) + "\n" + pad + "]"
    return _scalar(v)


def generate(cfg):
    board = Board(cfg)
    ids = list(range(1, 41)) + sorted(SWEET_ROOMS)
    levels, errors = [], []
    for i in ids:
        try:
            levels.append(build_level(board, cfg, i))
        except DesignError as e:
            errors.append(str(e))
    if errors:
        raise DesignError("\n  " + "\n  ".join(errors))
    doc = {"schemaVersion": SCHEMA_VERSION, "levels": [to_json_level(l) for l in levels]}
    return dump(doc) + "\n", levels


def report(levels):
    print(f"{'id':>4} {'w':>1} {'b':>2} {'d':>2} {'crowns':>7} {'cand':>4} {'G/P/Pk/B':>12} {'gems':>4}  objectives")
    for l in levels:
        bc = l["_by_color"]
        objs = ", ".join(
            o["type"] + "(" + ",".join(str(o[k]) for k in ("color", "gem", "chain", "count", "score") if k in o) + ")"
            for o in l["objectives"])
        print(f"{l['id']:>4} {l['world']:>1} {l['balls']:>2} {l['d']:>2} {str(l['crowns']):>7} "
              f"{sum(bc.values()):>4} {'/'.join(str(bc[c]) for c in COLORS):>12} {len(l['gems']):>4}  {objs}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="exit 1 if levels.json is not up to date")
    ap.add_argument("--report", action="store_true", help="print a per-level summary")
    args = ap.parse_args()
    with open(CONFIG_PATH, encoding="utf-8") as fh:
        cfg = json.load(fh)
    try:
        text, levels = generate(cfg)
    except DesignError as e:
        sys.exit(f"design error: {e}")
    if args.report:
        report(levels)
    if args.check:
        current = open(OUT_PATH, encoding="utf-8").read() if os.path.exists(OUT_PATH) else None
        if current != text:
            sys.exit("levels.json is stale; run tools/levels/generate_levels.py")
        print("levels.json is up to date")
        return
    with open(OUT_PATH, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print(f"wrote {os.path.relpath(OUT_PATH, ROOT)} ({len(levels)} levels)")


if __name__ == "__main__":
    main()
