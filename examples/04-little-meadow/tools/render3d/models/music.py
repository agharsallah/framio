"""Models for the music world (see DESIGN.md: sprite names start with the world prefix)."""
from __future__ import annotations

import math

from models import group
from models.objects import COLORS, LEAF
from sdf import (Model, arc, box, capsule, cheeks, cone, cyl, ell, eyes, flat, inter, plane, shell, smile, sphere,
                 star, torus, uni)

S = (320, 320)
WOOD = "#C9976B"
WOOD_DARK = "#A97A55"
CREAM = "#FBF3E6"
GOLD = "#EBC266"

# The xylophone's eight bars, C5..C6, in rainbow order (the app tints nothing: colours live here)
BAR_COLORS = [COLORS["red"], COLORS["orange"], COLORS["yellow"], COLORS["green"], "#7FC7B5", COLORS["blue"],
              COLORS["purple"], COLORS["pink"]]
BAR_LONG = 4.4
BAR_SHORT = 3.0


def lighter(c: str, f: float) -> str:
    r, g, b = (int(c[i:i + 2], 16) for i in (1, 3, 5))
    return "#" + "".join(f"{int(v + (255 - v) * f):02X}" for v in (r, g, b))


def bar_length(i: int) -> float:
    return BAR_LONG - (BAR_LONG - BAR_SHORT) * i / 7


@group("mu_bar", (160, 512))
def bars():
    """Eight clay bars, longest (low C) first, all in one crop so they line up by their centres."""
    out = []
    for i, c in enumerate(BAR_COLORS):
        L = bar_length(i)
        m = Model(f"mu_bar_{i}", ground=False, target=(0, 2.5, 0), distance=19.0, pitch=10)
        m.add(box((0, 2.5, 0), (0.5, L / 2, 0.2), 0.18), c, gloss=0.15)
        m.paint(box((0, 2.5 + L * 0.05, 0.25), (0.3, L * 0.38, 0.1), 0.1), lighter(c, 0.22), soft=0.3, gloss=0.25)
        for s in (-1, 1):  # the two pegs that hold it to the frame
            m.add(sphere((0, 2.5 + s * L * 0.36, 0.2), 0.11), CREAM, k=0.03, gloss=0.4)
        out.append(m)
    return out


@group("mu_xylo", S)
def xylo_icon():
    """A little xylophone on its frame, for the activity card."""
    m = Model("mu_xylo", target=(0, 0.7, 0), distance=16, pitch=38, reach=2.2)
    for s in (-1, 1):
        m.add(capsule((-1.9, 0.3, s * 0.95), (1.9, 0.3, s * 0.62), 0.14), WOOD, k=0.02)
    for i in range(6):
        x = -1.6 + i * 0.64
        L = 2.4 - i * 0.22
        m.add(box((x, 0.55, 0.15), (0.26, 0.11, L / 2), 0.09), BAR_COLORS[[0, 1, 2, 3, 5, 6][i]], gloss=0.15)
    for x, rz, c in [(0.9, -35, "#E9A3A0"), (1.5, -20, "#9D97D2")]:
        m.add(capsule((x - 0.4, 0.95, 1.4), (x + 0.6, 1.0, 2.6), 0.05), WOOD_DARK)
        m.add(sphere((x - 0.45, 0.95, 1.35), 0.2), c, gloss=0.2)
    return m


@group("mu_mallet", S)
def mallets():
    """Two crossed marimba mallets: the marimba sound."""
    m = Model("mu_mallet", target=(0, 1.2, 0), distance=11, pitch=10, reach=1.4)
    for s, c in ((1, "#E9A3A0"), (-1, "#9D97D2")):
        m.add(capsule((s * 0.9, 0.2, 0), (-s * 0.45, 2.0, 0.1 * s), 0.07), WOOD)
        m.add(sphere((-s * 0.5, 2.1, 0.1 * s), 0.38), c, gloss=0.2, k=0.03)
    return m


@group("mu_musicbox", S)
def musicbox():
    """A small music box with a golden crank and a star on its lid."""
    m = Model("mu_musicbox", target=(0, 0.9, 0), distance=11, pitch=16, reach=1.6)
    m.add(box((0, 0.6, 0), (0.9, 0.55, 0.65), 0.14), "#A98DCC")
    m.add(box((0, 1.25, 0), (0.95, 0.14, 0.7), 0.12), "#BBA3DA", k=0.02)
    m.add(box((0, 0.45, 0.66), (0.7, 0.04, 0.02), 0.02), GOLD)
    m.add(star((0, 0.85, 0.66), 0.22, 0.5, 0.04, 0.03), GOLD, gloss=0.3)
    m.add(capsule((0.95, 0.7, 0), (1.25, 0.7, 0), 0.06), GOLD)
    m.add(capsule((1.25, 0.7, 0), (1.25, 1.05, 0.15), 0.05), GOLD)
    m.add(sphere((1.25, 1.1, 0.17), 0.1), "#E9A3A0")
    # a tiny dancer's note popping out of the box
    m.add(sphere((-0.15, 1.65, 0.2), 0.16), "#F1ACC3")
    m.add(capsule((0.0, 1.65, 0.2), (0.0, 2.15, 0.2), 0.04), "#F1ACC3")
    return m


@group("mu_lamb", S)
def lamb():
    """A woolly little lamb: "Mary had a little lamb"."""
    m = Model("mu_lamb", target=(0, 1.1, 0), distance=12.5, pitch=10, yaw=-18, reach=1.8)
    wool = "#FBF7EF"
    face = "#E9D3C2"
    for x in (-0.55, 0.55):
        for z in (-0.3, 0.3):
            m.add(capsule((x, 0.05, z), (x, 0.7, z), 0.13), "#B79A8A")
    m.add(ell((0, 1.05, 0), (0.95, 0.62, 0.6)), wool)
    for i in range(16):  # curly wool puffs
        a = i * 2.39996
        y = 1.05 + 0.5 * math.sin(i * 1.3)
        x = 0.8 * math.cos(a)
        z = 0.5 * math.sin(a)
        m.add(sphere((x, y, z), 0.32), wool, k=0.12)
    m.add(ell((1.05, 1.5, 0.2), (0.36, 0.42, 0.34)), face, k=0.1)
    m.add(sphere((0.95, 1.9, 0.18), 0.26), wool, k=0.1)
    m.add(ell((0.8, 1.6, 0.48), (0.2, 0.08, 0.12), rot=(0, 30, -30)), "#D8B9A5", k=0.03)
    m.add(ell((1.05, 1.6, -0.15), (0.2, 0.08, 0.12), rot=(0, -30, -30)), "#D8B9A5", k=0.03)
    m.add(sphere((1.12, 1.55, 0.52), 0.06), "#3A3040", gloss=0.8)
    m.add(sphere((1.36, 1.55, 0.38), 0.06), "#3A3040", gloss=0.8)
    m.paint(sphere((1.18, 1.38, 0.55), 0.09), "#F2A7A0", soft=0.06)
    m.add(sphere((-0.95, 1.2, -0.05), 0.2), wool, k=0.05)
    return m


@group("mu_clock", S)
def clock():
    """A round little alarm clock with two bells: "Morning bells are ringing"."""
    m = Model("mu_clock", target=(0, 1.1, 0), distance=11, pitch=8, reach=1.4)
    m.add(cyl((0, 1.05, 0), 0.3, 0.85, 0.2, rot=(90, 0, 0)), "#8FAFD3")
    m.add(cyl((0, 1.05, 0.22), 0.1, 0.68, 0.06, rot=(90, 0, 0)), CREAM, k=0.02)
    for s in (-1, 1):
        m.add(inter(sphere((s * 0.55, 1.95, 0), 0.32), plane((0, -1, 0), -1.85)), GOLD, gloss=0.3)
        m.add(capsule((s * 0.35, 1.7, 0), (s * 0.5, 1.9, 0), 0.06), GOLD)
        m.add(capsule((s * 0.45, 0.3, 0), (s * 0.6, 0.05, 0), 0.09), "#8FAFD3")
    m.add(capsule((0, 1.05, 0.34), (0, 1.45, 0.34), 0.04), "#3E3346")
    m.add(capsule((0, 1.05, 0.34), (0.3, 1.05, 0.34), 0.04), "#3E3346")
    m.add(sphere((0, 1.05, 0.36), 0.06), "#E9A3A0")
    for i in range(12):
        a = i * math.pi / 6
        m.add(sphere((0.55 * math.sin(a), 1.05 + 0.55 * math.cos(a), 0.32), 0.035), "#7A6E80")
    return m


@group("mu_bun", S)
def bun():
    """A soft golden bun with a cream cross: "Hot cross buns"."""
    m = Model("mu_bun", target=(0, 0.7, 0), distance=10, pitch=42, reach=1.5)
    m.add(inter(ell((0, 0.35, 0), (1.0, 0.85, 1.0)), plane((0, -1, 0), -0.05)), "#E2A964")
    m.paint(ell((0, 1.15, 0), (0.8, 0.3, 0.8)), "#C98A4A", soft=0.3)
    for half in ((0.95, 0.6, 0.11), (0.11, 0.6, 0.95)):
        m.paint(box((0, 1.1, 0), half, 0.05), CREAM, soft=0.03)
    return m


@group("mu_bubbles", S)
def bubbles():
    """Three shiny bubbles, for the activity card."""
    m = Model("mu_bubbles", target=(0, 1.2, 0), distance=11, pitch=6, ground=False)
    for x, y, r, c in [(-0.45, 0.95, 0.72, "#CFE3F2"), (0.65, 1.55, 0.55, "#E4D9F3"), (0.35, 0.35, 0.35, "#D8EFE6")]:
        m.add(sphere((x, y, 0), r), c, gloss=0.9)
        m.paint(sphere((x - r * 0.4, y + r * 0.45, r * 0.75), r * 0.28), "#FFFFFF", soft=r * 0.12, gloss=1.0)
        m.paint(torus((x, y, 0), r * 0.92, r * 0.12, rot=(90, 0, 0)), "#BFD2EA", soft=r * 0.2)
    return m


def drum_face(m: Model, y: float, z: float, mood: str, w: float = 1.0) -> None:
    eyes(m, 0.26 * w, y + 0.12 * w, z, 0.085 * w, "happy" if mood == "happy" else "open")
    cheeks(m, 0.44 * w, y - 0.08 * w, z, 0.12 * w)
    smile(m, 0, y - 0.1 * w, z + 0.02, 0.22 * w, "happy" if mood == "happy" else "open")


@group("mu_drum_big", S)
def drum_big():
    """The big drum: low and round, terracotta with a cream skin and a friendly face."""
    out = []
    for mood in ("idle", "happy"):
        m = Model(f"mu_drum_big{'' if mood == 'idle' else '_happy'}", target=(0, 1.1, 0), distance=11.5, pitch=20, reach=1.8)
        m.add(cyl((0, 0.75, 0), 0.72, 1.05, 0.12), "#DC8B62")
        m.add(cyl((0, 1.47, 0), 0.04, 1.06, 0.03), CREAM, k=0.02)
        for y in (0.08, 1.42):
            m.add(torus((0, y, 0), 1.06, 0.08), GOLD, k=0.02, gloss=0.3)
        for i in range(8):  # the zigzag ropes
            a = i * math.pi / 4
            m.add(capsule((1.08 * math.sin(a), 0.16, 1.08 * math.cos(a)), (1.08 * math.sin(a + 0.4), 1.34, 1.08 * math.cos(a + 0.4)), 0.03), CREAM)
        drum_face(m, 0.75, 1.07, mood, 1.25)
        for s in (-1, 1):
            m.add(cyl((s * 0.6, 0.0, 0.5), 0.06, 0.12, 0.03), "#A9785A")
        out.append(m)
    return out


@group("mu_drum_small", S)
def drum_small():
    """The small drum: a tall sky-blue bongo with a higher, brighter voice."""
    out = []
    for mood in ("idle", "happy"):
        m = Model(f"mu_drum_small{'' if mood == 'idle' else '_happy'}", target=(0, 1.1, 0), distance=11.5, pitch=20, reach=1.4)
        m.add(cyl((0, 0.82, 0), 0.8, 0.62, 0.1), "#8FAFD3")
        m.add(cyl((0, 1.63, 0), 0.03, 0.63, 0.02), CREAM, k=0.02)
        m.add(torus((0, 1.58, 0), 0.64, 0.07), "#F3D266", k=0.02, gloss=0.3)
        m.add(torus((0, 0.08, 0), 0.64, 0.07), "#F3D266", k=0.02, gloss=0.3)
        for y in (0.5, 1.15):
            m.add(torus((0, y, 0), 0.63, 0.03), "#B9CDE4", k=0.01)
        drum_face(m, 0.85, 0.64, mood, 0.85)
        out.append(m)
    return out


@group("mu_bell", S)
def bell():
    """A honey hand bell on a wooden handle: the ting."""
    out = []
    for mood in ("idle", "happy"):
        m = Model(f"mu_bell{'' if mood == 'idle' else '_happy'}", target=(0, 1.2, 0), distance=11.5, pitch=12, reach=1.5)
        body = inter(shell(uni(sphere((0, 1.35, 0), 0.62), cone((0, 1.3, 0), (0, 0.32, 0), 0.62, 0.92), 0.25), 0.07), plane((0, -1, 0), -0.25))
        m.add(body, "#F0C35A", gloss=0.35)
        m.add(torus((0, 0.3, 0), 0.9, 0.07), "#E3AE45", k=0.03, gloss=0.3)
        m.add(sphere((0, 0.3, 0), 0.17), "#D9A93F")
        m.add(capsule((0, 1.95, 0), (0, 2.55, 0), 0.13), WOOD, k=0.05)
        m.add(sphere((0, 2.65, 0), 0.2), WOOD_DARK, k=0.05)
        drum_face(m, 1.1, 0.78, mood, 1.0)
        out.append(m)
    return out
