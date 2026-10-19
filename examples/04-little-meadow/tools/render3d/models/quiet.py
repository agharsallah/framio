"""
Models for Luma's Quiet Tree (sprite prefix qt_, backdrops bg_qt_).

- qt_<friend>_<feeling>: the friends with feeling faces for the Feelings game and the stories. The
  bodies, colours and cameras are the same as in characters.py, only the faces change (brows, a tear,
  mouth shapes, puffed cheeks, sleepy lids), so they sit next to FriendView without looking different.
- props for breathing, feelings and the picture stories.
- three calm backdrops: the dark night-light sky, a rainy meadow and a lavender dusk.
"""
from __future__ import annotations

import math
import random

from models import group
from models.scenes import BG, cloud, floor, flower, hill, scene, tree
from sdf import (INK, Model, arc, box, capsule, cheeks, cone, cyl, ell, eyes, flat, inter, plane, raw, smile,
                 sphere, star, torus)

FEELINGS = ["happy", "sad", "angry", "scared", "surprised", "sleepy", "proud", "calm"]
TEAR = "#A9D3EE"
GOLD = "#E7B565"


def surf_z(c, r, x, y, sink=0.03):
    """z of an ellipsoid's front surface (centre c, radii r) at (x, y), pushed in by `sink`."""
    q = 1 - (x / r[0]) ** 2 - ((y - c[1]) / r[1]) ** 2
    return c[2] + r[2] * math.sqrt(max(q, 0.02)) - sink


# ---------------------------------------------------------------- face parts

def brows(m: Model, head, x, y, r, kind, color):
    """Brows above a pair of eyes at (±x, y) of radius r. kind: sad | angry | raised | worried | none."""
    if kind == "none":
        return
    by = y + r * 2.0
    th = r * 0.21
    if kind == "raised":
        by += r * 0.45
        a, b = (x - r * 0.75, by - r * 0.1), (x + r * 0.75, by - r * 0.1)
        mid = (x, by + r * 0.18)
        for p0, p1 in ((a, mid), (mid, b)):
            m.add(capsule((p0[0], p0[1], surf_z(*head, p0[0], p0[1])), (p1[0], p1[1], surf_z(*head, p1[0], p1[1])), th, sym=True), color, k=0.02)
        return
    lift = {"sad": (0.55, -0.12), "worried": (0.62, 0.05), "angry": (-0.45, 0.38)}[kind]
    inner = (x - r * 0.8, by + r * lift[0])
    outer = (x + r * 0.7, by + r * lift[1])
    m.add(capsule((inner[0], inner[1], surf_z(*head, *inner)), (outer[0], outer[1], surf_z(*head, *outer)), th, sym=True), color, k=0.02)


def tear(m: Model, head, x, y, r):
    """One soft tear under the right-hand eye (the child's left)."""
    tx, ty = -x - r * 0.15, y - r * 1.75
    tz = surf_z(*head, tx, ty, sink=-0.01)
    m.add(sphere((tx, ty, tz), r * 0.42), TEAR, k=0.01, gloss=0.9, emit=0.15)
    m.add(cone((tx, ty, tz), (tx, ty + r * 0.75, tz - 0.01), r * 0.4, r * 0.06), TEAR, k=0.03, gloss=0.9, emit=0.15)


def mouth(m: Model, x, y, z, w, kind):
    """smile | laugh | frown | pout | o_small | o_big | yawn | calm | none"""
    if kind == "none":
        return
    if kind == "smile":
        smile(m, x, y, z, w, "open")
    elif kind == "laugh":
        smile(m, x, y, z, w, "happy")
    elif kind == "calm":
        smile(m, x, y, z, w, "sleep")
    elif kind == "frown":
        m.add(arc((x, y - w * 0.55, z - w * 0.05), w * 0.6, w * 0.1, 44), INK)
    elif kind == "pout":
        m.add(arc((x, y - w * 0.62, z - w * 0.06), w * 0.5, w * 0.13, 40), INK)
    elif kind == "o_small":
        m.add(ell((x, y, z), (w * 0.17, w * 0.21, w * 0.14)), INK)
    elif kind == "o_big":
        m.add(ell((x, y - w * 0.05, z), (w * 0.34, w * 0.44, w * 0.2)), INK)
        m.add(ell((x, y - w * 0.25, z + w * 0.07), (w * 0.2, w * 0.13, w * 0.12)), "#E58C8C")
    elif kind == "yawn":
        m.add(ell((x, y - w * 0.05, z), (w * 0.28, w * 0.4, w * 0.2)), INK)
        m.add(ell((x, y - w * 0.28, z + w * 0.06), (w * 0.18, w * 0.11, w * 0.12)), "#E58C8C")


def sleepy_lids(m: Model, x, y, z, r, lid_color):
    """Open eyes with heavy lids covering their top half."""
    eyes(m, x, y, z, r, "open", look=(0.0, -0.5))
    m.add(inter(ell((x, y, z + r * 0.05), (r * 1.18, r * 1.32, r * 0.86), sym=True), plane((0, -1, 0), -(y - r * 0.05))), lid_color, k=0.01)
    m.add(arc((x, y + r * 0.35, z + r * 0.45), r * 0.95, r * 0.11, 62, rot=(0, 0, 180), sym=True), INK)


def face(m: Model, feeling, head, ex, ey, ez, er, brow_color, lid_color, cheek, mouth_at, cheek_color="#F2A7A0"):
    """The eyes, brows, cheeks and mouth of one feeling (mouth_at = (x, y, z, w) or None for a beak)."""
    eye_mood = {"happy": "happy", "sad": "open", "angry": "open", "scared": "wide", "surprised": "wide",
                "sleepy": None, "proud": "happy", "calm": "sleep"}[feeling]
    look = {"sad": (0.0, -0.7), "scared": (0.3, 0.0), "angry": (0.0, -0.2)}.get(feeling, (0.0, 0.0))
    if eye_mood is None:
        sleepy_lids(m, ex, ey, ez, er, lid_color)
    else:
        eyes(m, ex, ey, ez, er, eye_mood, look=look)
    brows(m, head, ex, ey, er, {"sad": "sad", "angry": "angry", "scared": "worried", "surprised": "raised"}.get(feeling, "none"), brow_color)
    cx, cy, cz, cr = cheek
    if feeling == "angry":
        # puffed cheeks: a little extra clay, a warmer blush
        m.add(sphere((cx * 0.95, cy, surf_z(*head, cx * 0.95, cy, sink=cr * 0.95)), cr * 1.2, sym=True), lid_color, k=0.1)
        cheeks(m, cx, cy, cz + 0.04, cr * 1.25, "#EE958B")
    elif feeling == "proud":
        cheeks(m, cx, cy, cz, cr * 1.1, "#F09A98")
    else:
        cheeks(m, cx, cy, cz, cr, cheek_color)
    if feeling == "sad":
        tear(m, head, ex, ey, er)
    if mouth_at is not None:
        mx, my, mz, mw = mouth_at
        kind = {"happy": "laugh", "sad": "frown", "angry": "pout", "scared": "o_small", "surprised": "o_big",
                "sleepy": "yawn", "proud": "smile", "calm": "calm"}[feeling]
        mouth(m, mx, my, mz, mw, kind)


def badge(m: Model, x, y, z):
    """Proud: a little gold star on the chest."""
    m.add(star((x, y, z), 0.17, 0.5, 0.05, 0.04), GOLD, k=0.02, gloss=0.6)


# ---------------------------------------------------------------- the friends with feelings (bodies as in characters.py)

def pip_f(feeling: str) -> Model:
    m = Model(f"qt_pip_{feeling}", target=(0, 1.15, 0), distance=13)
    body, belly, wing, beak, feet = "#F1C56E", "#FBEAC6", "#E3A955", "#EE9461", "#E9925F"
    head = ((0, 1.15, 0), (1.08, 1.02, 0.98))
    m.add(ell(*head), body)
    m.paint(ell((0, 0.9, 0.55), (0.72, 0.62, 0.6)), belly, soft=0.08)
    m.add(cone((0, 2.05, 0.05), (0.02, 2.5, 0.2), 0.14, 0.06), wing, k=0.08)
    m.add(cone((0.12, 2.02, 0.0), (0.38, 2.38, 0.05), 0.11, 0.05), wing, k=0.06)
    m.add(cone((-0.12, 2.02, 0.0), (-0.36, 2.34, 0.08), 0.11, 0.05), wing, k=0.06)
    if feeling in ("scared",):
        m.add(ell((0.92, 1.25, 0.25), (0.17, 0.5, 0.36), rot=(0, 0, 32), sym=True), wing, k=0.08)
    else:
        m.add(ell((1.0, 1.0, 0.05), (0.17, 0.5, 0.38), rot=(0, 0, 12), sym=True), wing, k=0.08)
    m.add(ell((0, 0.75, -0.95), (0.35, 0.18, 0.35), rot=(-30, 0, 0)), wing, k=0.1)
    face(m, feeling, head, 0.36, 1.42, 0.86, 0.13, "#B07A35", body, (0.62, 1.12, 0.8, 0.17), None)
    if feeling in ("happy", "surprised", "sleepy", "scared"):
        gape = {"happy": 1.0, "surprised": 1.25, "sleepy": 1.35, "scared": 0.7}[feeling]
        m.add(cone((0, 1.22, 0.92), (0, 1.25, 1.28), 0.15, 0.03), beak, k=0.03)
        m.add(cone((0, 1.06 - 0.05 * (gape - 1), 0.9), (0, 1.0 - 0.12 * (gape - 1), 1.18), 0.12, 0.03), "#D9774E", k=0.03)
        m.add(sphere((0, 1.12 - 0.04 * (gape - 1), 0.98), 0.07 * gape), "#B95A45")
    else:
        m.add(cone((0, 1.15, 0.92), (0, 1.12, 1.3), 0.16, 0.03), beak, k=0.03)
    if feeling == "proud":
        badge(m, 0, 0.78, surf_z(*head, 0, 0.78, sink=0.0))
    m.add(capsule((0.32, 0.12, 0.25), (0.38, 0.06, 0.55), 0.07, sym=True), feet, k=0.02)
    m.add(capsule((0.32, 0.12, 0.25), (0.18, 0.06, 0.52), 0.06, sym=True), feet, k=0.02)
    return m


def tuka_f(feeling: str) -> Model:
    m = Model(f"qt_tuka_{feeling}", target=(0, 1.45, 0), distance=16)
    fur, cream, dark, inner = "#DC8B62", "#FBEDDF", "#74514C", "#F1C3AC"
    m.add(cone((0.35, 0.35, -0.45), (1.05, 0.95, -0.5), 0.22, 0.36), fur, k=0.1)
    m.add(sphere((1.15, 1.25, -0.45), 0.3), cream, k=0.18)
    m.add(ell((0, 0.72, 0), (0.62, 0.68, 0.55)), fur, k=0.1)
    m.paint(ell((0, 0.78, 0.45), (0.36, 0.45, 0.3)), cream, soft=0.06)
    if feeling == "scared":
        m.add(ell((0.42, 1.05, 0.42), (0.15, 0.28, 0.15), rot=(0, 0, -20), sym=True), fur, k=0.08)
    else:
        m.add(ell((0.55, 0.82, 0.22), (0.16, 0.3, 0.16), rot=(0, 0, 20), sym=True), fur, k=0.08)
    m.add(ell((0.3, 0.12, 0.18), (0.2, 0.14, 0.26), sym=True), dark, k=0.04)
    head = ((0, 1.8, 0.05), (0.95, 0.8, 0.8))
    m.add(ell(*head), fur, k=0.12)
    m.paint(ell((0, 1.5, 0.55), (0.78, 0.42, 0.5)), cream, soft=0.08)
    m.add(ell((0, 1.56, 0.74), (0.32, 0.24, 0.28)), cream, k=0.12)
    m.add(sphere((0, 1.66, 1.02), 0.09), "#3A2B30", gloss=0.6)
    # ears droop a little when sad or scared, stand up when surprised
    tip = {"sad": (0.92, 2.74, -0.1), "scared": (0.95, 2.7, -0.1), "surprised": (0.76, 3.02, -0.05)}.get(feeling, (0.8, 2.95, -0.05))
    m.add(cone((0.45, 2.3, 0.0), tip, 0.3, 0.06, sym=True), fur, k=0.08)
    m.paint(cone((0.48, 2.36, 0.22), (tip[0] - 0.02, tip[1] - 0.09, tip[2] + 0.17), 0.17, 0.03, sym=True), inner, soft=0.03)
    m.paint(sphere((tip[0] + 0.02, tip[1] + 0.03, tip[2]), 0.2, sym=True), dark, soft=0.04)
    face(m, feeling, head, 0.37, 1.9, 0.76, 0.12, "#8A4A38", fur, (0.64, 1.62, 0.64, 0.15), (0, 1.44, 0.98, 0.2))
    if feeling == "proud":
        badge(m, 0, 0.92, 0.52)
    return m


def moss_f(feeling: str) -> Model:
    m = Model(f"qt_moss_{feeling}", target=(0, 1.2, 0), distance=16, pitch=12)
    skin, shell, plate, rim, leaf = "#A9CFA1", "#7FA37B", "#9DBD8F", "#D9CC95", "#8CC07A"
    m.add(inter(ell((0, 0.75, -0.35), (1.25, 1.0, 1.05)), plane((0, -1, 0), -0.35)), shell)
    for x, y, z in [(0, 1.6, -0.4), (0.7, 1.2, -0.2), (-0.7, 1.2, -0.2), (0.95, 0.75, 0.15), (-0.95, 0.75, 0.15)]:
        m.paint(sphere((x, y, z), 0.42), plate, soft=0.05)
    m.add(torus((0, 0.38, -0.35), 1.12, 0.14, rot=(0, 0, 0)), rim, k=0.08)
    m.add(ell((0.85, 0.22, 0.5), (0.24, 0.2, 0.26), sym=True), skin, k=0.06)
    m.add(ell((0.95, 0.22, -0.8), (0.22, 0.18, 0.24), sym=True), skin, k=0.06)
    # a scared turtle pulls his head in a little; a proud one stretches up
    dy = {"scared": -0.12, "sad": -0.05, "proud": 0.06}.get(feeling, 0.0)
    m.add(capsule((0, 0.55, 0.35), (0, 1.0 + dy, 0.6), 0.32), skin, k=0.12)
    head = ((0, 1.35 + dy, 0.75), (0.68, 0.6, 0.58))
    m.add(ell(*head), skin, k=0.14)
    sprout_tilt = {"sad": -0.12, "sleepy": -0.1}.get(feeling, 0.0)
    m.add(capsule((0, 1.9 + dy, 0.7), (0.02 + sprout_tilt, 2.2 + dy, 0.68), 0.04), "#6E9E62", k=0.03)
    lx = 0.02 + sprout_tilt
    m.add(ell((lx + 0.15, 2.28 + dy - abs(sprout_tilt) * 0.6, 0.68), (0.2, 0.08, 0.12), rot=(0, 0, 25 - sprout_tilt * 250)), leaf, k=0.03)
    m.add(ell((lx - 0.17, 2.25 + dy - abs(sprout_tilt) * 0.3, 0.68), (0.17, 0.07, 0.1), rot=(0, 0, -30 - sprout_tilt * 150)), leaf, k=0.03)
    face(m, feeling, head, 0.26, 1.48 + dy, 1.25, 0.11, "#4F7A4A", skin, (0.46, 1.25 + dy, 1.18, 0.13), (0, 1.17 + dy, 1.32, 0.2))
    if feeling == "proud":
        badge(m, 0, 0.62, 0.66)
    return m


def bo_f(feeling: str) -> Model:
    m = Model(f"qt_bo_{feeling}", target=(0, 1.3, 0), distance=16)
    skin, inner, nail = "#A3BDD8", "#EDC6CC", "#F4EBDD"
    ear_rot = {"sad": (0, -28, 8), "scared": (0, -40, 2), "surprised": (0, -18, -14)}.get(feeling, (0, -28, -8))
    m.add(ell((0.95, 1.62, -0.2), (0.6, 0.66, 0.12), rot=ear_rot, sym=True), skin, k=0.05)
    m.paint(ell((1.0, 1.6, -0.02), (0.42, 0.48, 0.12), rot=ear_rot, sym=True), inner, soft=0.04)
    m.add(ell((0, 0.62, 0), (0.7, 0.6, 0.6)), skin)
    head = ((0, 1.58, 0.05), (0.88, 0.82, 0.78))
    m.add(ell(*head), skin, k=0.15)
    for x in (-0.1, 0.0, 0.1):
        m.add(capsule((x, 2.3, 0.05), (x * 2.2, 2.55, 0.1), 0.045), "#8EA9C6", k=0.05)
    raised = feeling in ("happy", "surprised", "scared", "sleepy", "proud")
    if raised:
        lift = {"surprised": 1.62, "scared": 1.5, "sleepy": 1.45}.get(feeling, 1.55)
        m.add(cone((0, 1.42, 0.7), (0.05, 1.25, 1.02), 0.24, 0.16), skin, k=0.08)
        m.add(cone((0.05, 1.25, 1.02), (0.12, lift, 1.28), 0.16, 0.12), skin, k=0.06)
        mouth_at = (0, 1.06, 0.82, 0.24)
    else:
        m.add(cone((0, 1.42, 0.7), (0, 0.98, 1.0), 0.24, 0.15), skin, k=0.08)
        m.add(cone((0, 0.98, 1.0), (0.1, 0.82, 1.18), 0.15, 0.12), skin, k=0.05)
        mouth_at = None
    m.add(cyl((0.33, 0.2, 0.12), 0.2, 0.21, 0.08, sym=True), skin, k=0.06)
    m.paint(ell((0.33, 0.06, 0.32), (0.15, 0.08, 0.06), sym=True), nail, soft=0.02)
    if feeling == "scared":
        m.add(ell((0.48, 0.9, 0.38), (0.16, 0.28, 0.16), rot=(0, 0, -25), sym=True), skin, k=0.08)
    else:
        m.add(ell((0.6, 0.72, 0.2), (0.17, 0.3, 0.17), rot=(0, 0, 20), sym=True), skin, k=0.08)
    face(m, feeling, head, 0.35, 1.74, 0.72, 0.13, "#6680A3", skin, (0.56, 1.48, 0.62, 0.15), mouth_at)
    if feeling == "proud":
        badge(m, 0, 0.66, 0.6)
    return m


for _name, _fn in (("pip", pip_f), ("tuka", tuka_f), ("moss", moss_f), ("bo", bo_f)):
    group(f"qt_{_name}")(lambda fn=_fn: [fn(f) for f in FEELINGS])


# ---------------------------------------------------------------- props

def prop(name, distance=9.0, target=(0, 0.8, 0), pitch=12.0, ground=True, reach=1.6, yaw=0.0):
    return Model(name, target=target, distance=distance, pitch=pitch, ground=ground, reach=reach, yaw=yaw)


@group("qt_branch", (512, 256))
def branch():
    """Luma's branch for the night light: dusky bark, a few dark leaves, a tiny lantern."""
    m = prop("qt_branch", distance=24, target=(0, 0.2, 0), pitch=8, ground=False)
    bark, leaf, leaf2 = "#4A3F50", "#3E4A64", "#465470"
    m.add(cone((-3.8, -0.35, 0), (3.1, 0.12, 0), 0.3, 0.12), bark, k=0.1)
    m.add(cone((1.4, 0.0, 0), (2.4, 0.8, -0.1), 0.12, 0.06), bark, k=0.1)
    m.add(cone((-1.9, -0.2, 0), (-2.7, -0.75, 0.05), 0.11, 0.05), bark, k=0.1)
    r = random.Random(5)
    # small clusters of soft oval leaves at the twigs' ends and along the top
    for cx, cy, n in [(3.2, 0.25, 7), (2.45, 0.95, 6), (-2.8, -0.85, 5), (0.6, 0.12, 3), (-0.9, -0.05, 3)]:
        for _ in range(n):
            a = r.uniform(0, 2 * math.pi)
            x, y = cx + 0.35 * math.cos(a) * r.uniform(0.3, 1), cy + 0.3 * math.sin(a) * r.uniform(0.3, 1) + 0.12
            m.add(ell((x, y, r.uniform(-0.15, 0.2)), (0.32, 0.14, 0.1), rot=(r.uniform(-20, 20), 0, math.degrees(a) * 0.4 - 10)), r.choice([leaf, leaf2]), k=0.04)
    m.add(capsule((-1.2, -0.12, 0.05), (-1.2, -0.7, 0.05), 0.02), "#B9B3C9")
    m.add(sphere((-1.2, -0.95, 0.05), 0.24), "#F3D9A0", emit=0.9)
    return m


def candle(m: Model, lit: bool):
    m.add(cyl((0, 0.1, 0), 0.1, 0.9, 0.08), "#E8D6F0", k=0.02)  # a little saucer
    m.add(torus((0, 0.18, 0), 0.82, 0.07), "#D9C3E8", k=0.03)
    m.add(cyl((0, 0.95, 0), 0.8, 0.42, 0.12), "#FBF3E6", k=0.04)
    for a in (0.6, 2.4, 4.1):  # soft drips
        m.add(capsule((0.4 * math.cos(a), 1.72, 0.4 * math.sin(a)), (0.41 * math.cos(a), 1.45 - 0.1 * a % 0.3, 0.41 * math.sin(a)), 0.06), "#FBF3E6", k=0.05)
    m.add(capsule((0, 1.7, 0), (0, 1.95, 0), 0.035), "#5A4A50")
    if lit:
        m.add(cone((0, 2.05, 0), (0, 2.55, 0), 0.16, 0.02), "#F6C66C", emit=0.9)
        m.add(ell((0, 2.08, 0.02), (0.08, 0.14, 0.08)), "#FBEFD0", emit=1.0)


@group("qt_candle", (320, 320))
def candle_plain():
    m = prop("qt_candle", distance=10, target=(0, 1.2, 0))
    candle(m, False)
    return m


@group("qt_candlelit", (320, 320))
def candle_lit():
    m = prop("qt_candlelit", distance=10, target=(0, 1.3, 0))
    candle(m, True)
    return m


# the hand: palm towards us, fingers spread like a starfish. Finger bases/tips are listed so the app
# can trace them (tools: see HAND_FINGERS; projected into sprite fractions in Breathe.kt).
HAND_FINGERS = [  # (angle from vertical in degrees, +x is to the right, length)
    (-62, 1.0), (-24, 1.4), (-4, 1.55), (16, 1.42), (36, 1.12),
]


def hand_model(name):
    m = prop(name, distance=13, target=(0, 1.4, 0), pitch=0, ground=False)
    skin, nail = "#F2C7A5", "#F8DCCB"
    m.add(ell((0, 1.15, 0), (0.72, 0.82, 0.26)), skin)
    m.add(capsule((0, 0.5, 0), (0, 0.1, 0), 0.44), skin, k=0.25)
    for i, (ang, ln) in enumerate(HAND_FINGERS):
        a = math.radians(ang)
        base = (0.62 * math.sin(a) * (1.25 if i == 0 else 1.0), 1.15 + 0.7 * math.cos(a) - (0.35 if i == 0 else 0.0), 0.0)
        tip = (base[0] + ln * math.sin(a), base[1] + ln * math.cos(a), 0.02)
        m.add(capsule(base, tip, 0.17 if i else 0.2), skin, k=0.12)
        m.paint(ell((tip[0] - 0.02 * math.sin(a), tip[1] - 0.06 * math.cos(a), 0.2), (0.11, 0.12, 0.08), rot=(0, 0, -ang)), nail, soft=0.03)
    m.paint(sphere((0, 1.0, 0.3), 0.45), "#F6D2B8", soft=0.2)
    return m


@group("qt_hand", (512, 512))
def hand():
    return hand_model("qt_hand")


@group("qt_balloon", (320, 400))
def balloon():
    """Balloon belly: a round, soft lilac balloon (it grows as we breathe in)."""
    m = prop("qt_balloon", distance=11, target=(0, 1.6, 0), ground=False)
    m.add(ell((0, 2.0, 0), (1.0, 1.12, 1.0)), "#C6B3E6", gloss=0.55)
    m.add(cone((0, 0.92, 0), (0, 0.78, 0), 0.06, 0.13), "#B7A2DB", k=0.04)
    m.add(capsule((0, 0.75, 0), (0.08, 0.0, 0.0), 0.02), "#A89BB5")
    m.add(capsule((0.08, 0.0, 0.0), (-0.05, -0.6, 0.0), 0.02), "#A89BB5", k=0.04)
    return m


@group("qt_basket", (320, 320))
def basket():
    m = prop("qt_basket", distance=10, target=(0, 0.75, 0))
    wicker, dark = "#D2A577", "#B98A5F"
    m.add(inter(ell((0, 0.85, 0), (1.15, 0.8, 0.9)), plane((0, 1, 0), 0.95)), wicker)
    m.cut(ell((0, 1.0, 0), (1.0, 0.65, 0.76)), dark)
    m.clip(plane((0, -1, 0), -0.12), k=0.05)
    for y in (0.4, 0.65):
        m.add(torus((0, y, 0), 1.0 if y > 0.5 else 0.9, 0.05), dark, k=0.03)
    m.add(arc((0, 1.0, 0), 0.95, 0.07, 80, rot=(0, 15, 0)), wicker, k=0.03)
    r = random.Random(4)
    for i in range(14):
        x, z = r.uniform(-0.75, 0.75), r.uniform(-0.45, 0.5)
        c = r.choice(["#DE6A5E", "#6E7FC6", "#DE6A5E", "#B0558A"])
        m.add(sphere((x, 0.98 + r.uniform(0, 0.12) - abs(x) * 0.1, z), 0.17), c, gloss=0.5)
    m.add(ell((0.3, 1.18, 0.35), (0.16, 0.05, 0.1), rot=(0, 30, 20)), "#7DBA70")
    return m


@group("qt_bush", (400, 320))
def bush():
    m = prop("qt_bush", distance=13, target=(0, 1.0, 0))
    for dx, dy, r in [(0, 1.0, 1.0), (-0.95, 0.7, 0.75), (0.95, 0.72, 0.75), (-0.4, 1.6, 0.6), (0.45, 1.55, 0.62)]:
        m.add(sphere((dx, dy, 0), r), "#7DB46B", k=0.3)
    r = random.Random(9)
    lumps = [(0, 1.0, 1.0), (-0.95, 0.7, 0.75), (0.95, 0.72, 0.75), (-0.4, 1.6, 0.6), (0.45, 1.55, 0.62)]
    placed = 0
    while placed < 16:
        x, y = r.uniform(-1.4, 1.4), r.uniform(0.45, 1.9)
        zs = [math.sqrt(rr * rr - (x - dx) ** 2 - (y - dy) ** 2) for dx, dy, rr in lumps if (x - dx) ** 2 + (y - dy) ** 2 < (rr * 0.85) ** 2]
        if not zs:
            continue
        m.add(sphere((x, y, max(zs) + 0.02), 0.13), r.choice(["#DE6A5E", "#6E7FC6"]), gloss=0.5)
        placed += 1
    return m


@group("qt_raincloud", (480, 320))
def raincloud():
    m = prop("qt_raincloud", distance=12, target=(0, 0.2, 0), ground=False)
    for dx, dy, r in [(-1.1, 0, 0.8), (0, 0.4, 1.1), (1.1, 0, 0.85), (0.55, -0.25, 0.7), (-0.55, -0.25, 0.7)]:
        m.add(sphere((dx, dy, 0), r), "#B6BFCF", k=0.4)
    m.paint(ell((0, -0.5, 0.4), (1.8, 0.4, 0.8)), "#9EA8BB", soft=0.3)
    return m


@group("qt_nestbed", (400, 280))
def nestbed():
    """Pip's bed: a cozy nest with a pillow and a little patchwork blanket."""
    m = prop("qt_nestbed", distance=11, target=(0, 0.5, 0), pitch=22)
    m.add(torus((0, 0.4, 0), 1.25, 0.38), "#C99A6B", k=0.1)
    m.add(cyl((0, 0.22, 0), 0.2, 1.3, 0.15), "#B88B5F", k=0.1)
    for i in range(18):
        a = i * 2 * math.pi / 18
        m.add(capsule((1.25 * math.cos(a), 0.45 + 0.25 * math.sin(3 * a), 1.25 * math.sin(a)),
                      (1.25 * math.cos(a + 0.5), 0.5, 1.25 * math.sin(a + 0.5)), 0.05), "#A87F55", k=0.03)
    m.add(ell((0, 0.62, -0.45), (0.6, 0.25, 0.4)), "#FBF6EE", k=0.05)
    m.add(ell((0, 0.5, 0.3), (1.05, 0.2, 0.75)), "#9FB7DE", k=0.05)
    for x, z, c in [(-0.45, 0.2, "#F1ACC3"), (0.35, 0.55, "#F3D266"), (0.5, 0.0, "#F7EFE3")]:
        m.paint(sphere((x, 0.7, z), 0.2), c, soft=0.03)
    return m


@group("qt_talk", (320, 320))
def talk():
    """'Talk to someone': a soft speech bubble with three dots."""
    m = prop("qt_talk", distance=10, target=(0, 1.2, 0), ground=False)
    m.add(ell((0, 1.3, 0), (1.25, 0.9, 0.35)), "#FBF6EE")
    m.add(cone((-0.55, 0.6, 0), (-0.9, 0.2, 0), 0.25, 0.05), "#FBF6EE", k=0.12)
    for x in (-0.45, 0.0, 0.45):
        m.add(sphere((x, 1.3, 0.33), 0.13), "#9D97D2")
    return m


@group("qt_pillow", (320, 320))
def pillow():
    """'Have a rest': a puffy pillow with a little moon."""
    m = prop("qt_pillow", distance=10, target=(0, 0.6, 0), pitch=18)
    m.add(box((0, 0.45, 0), (1.15, 0.35, 0.8), 0.32), "#E9E4F6", k=0.0)
    m.add(ell((0, 0.6, 0), (1.05, 0.28, 0.72)), "#E9E4F6", k=0.2)
    q = "(p-vec3(0.0,0.88,0.15))"
    moon = f"max(length({q}.xz)-0.32,-(length({q}.xz-vec2(0.15,-0.12))-0.27))"
    m.add(raw(f"extrude({moon},{q}.y,0.04,0.03)"), "#F3D88E", k=0.02)
    return m


@group("qt_icecream", (320, 320))
def dropped_icecream():
    """Tuka's ice cream, dropped: the cone on its side, a pink scoop melting on the grass."""
    m = prop("qt_icecream", distance=9, target=(0, 0.35, 0), pitch=25)
    m.add(ell((0.15, 0.08, 0.1), (0.8, 0.1, 0.6)), "#F2B5C6", k=0.05, gloss=0.3)
    m.add(sphere((0.25, 0.3, 0.05), 0.42), "#F2B5C6", k=0.2, gloss=0.3)
    m.add(cone((-0.25, 0.32, -0.05), (-1.2, 0.18, -0.25), 0.33, 0.06), "#E2B277", k=0.02)
    for t in (0.3, 0.6):
        m.paint(torus((-0.25 - 0.95 * t, 0.32 - 0.14 * t, -0.05 - 0.2 * t), 0.33 - 0.27 * t, 0.025, rot=(0, 0, 90)), "#C99456", soft=0.01)
    m.add(sphere((0.4, 0.68, 0.1), 0.07), "#DE6A5E")
    return m


# ---------------------------------------------------------------- backdrops

@group("bg_qt_sky", BG)
def night_sky():
    """The night light: a very dark, plain sky (the app draws the stars), soft hills, a low moon."""
    m = scene("qt_sky", sky=("#101126", "#272A4A"), haze=0.0)
    floor(m, "#1D2034")
    hill(m, -12, -22, 20, 5, 10, "#22263D")
    hill(m, 14, -26, 24, 6, 10, "#262A43")
    hill(m, 2, -12, 12, 1.8, 6, "#1F2238")
    q = "(p-vec3(13.0,10.0,-30.0))"
    crescent = f"max(length({q}.xy)-2.0,-(length({q}.xy-vec2(1.0,0.7))-1.7))"
    m.add(raw(f"extrude({crescent},{q}.z,0.5,0.4)"), "#CFC6AE", emit=0.7)
    return m


@group("bg_qt_rain", BG)
def rain():
    """Bo's big rain: an overcast meadow, soft grey-blue, a big sheltering tree on the right."""
    m = scene("qt_rain", sky=("#9AA5B9", "#CDD3DA"), haze=0.06)
    floor(m, "#93AE92")
    hill(m, -14, -30, 22, 6, 10, "#AABBAA")
    hill(m, 16, -34, 26, 8, 12, "#ADBDAE")
    hill(m, -9, -16, 14, 3.2, 8, "#9DB49C")
    m.add(cone((15, 0, -6), (14.5, 7.5, -6), 1.2, 0.75), "#8B7466", k=0.3)
    m.add(capsule((14.6, 5.5, -6), (10.5, 7.3, -5.6), 0.4), "#8B7466", k=0.3)
    for dx, dy, r in [(0, 9.6, 3.4), (-3.4, 8.6, 2.6), (2.8, 8.2, 2.5), (-5.6, 7.6, 1.8)]:
        m.add(sphere((15 + dx, dy, -7), r), "#6F9271", k=0.8)
    for x, y, z, s in [(-9, 12, -26, 2.0), (2, 13.5, -30, 2.6), (12, 12.5, -28, 2.2), (-17, 10.5, -24, 1.6)]:
        for dx, dy, r in [(-1.0, 0, 0.8), (0, 0.35, 1.1), (1.0, 0, 0.8)]:
            m.add(sphere((x + dx * s, y + dy * s, z), r * s), "#B9C1CE", k=0.4 * s)
    for x, z, rx in [(-6, -2, 2.2), (4, 1, 1.6), (-12, 2, 1.4)]:
        m.add(ell((x, 0.0, z), (rx, 0.06, rx * 0.45)), "#B3C4D3", gloss=0.8)
    r = random.Random(7)
    for _ in range(10):
        flower(m, r.choice([-1, 1]) * r.uniform(7, 15), r.uniform(-5, 3), r.choice(["#C9B6D9", "#E3D7C2"]), r.uniform(0.8, 1.1))
    return m


@group("bg_qt_dusk", BG)
def dusk():
    """A lavender evening on the hill: calm for breathing, the sunset at the end of Moss's walk."""
    m = scene("qt_dusk", sky=("#A9A2D3", "#F4CDB2"), haze=0.05)
    floor(m, "#A7BF9C")
    hill(m, -14, -30, 22, 6, 10, "#B7B9C6")
    hill(m, 16, -34, 26, 8, 12, "#BDB6C9")
    hill(m, -9, -16, 14, 3.2, 8, "#ABBFA5")
    hill(m, 12, -18, 15, 3.6, 8, "#B0C3A6")
    m.add(sphere((4, 1.5, -40), 3.2), "#F7D7A4", emit=0.85)
    for x, y, z, s in [(-10, 12, -32, 1.4), (13, 13.5, -36, 1.8)]:
        for dx, dy, rr in [(-1.0, 0, 0.8), (0, 0.35, 1.1), (1.0, 0, 0.8)]:
            m.add(sphere((x + dx * s, y + dy * s, z), rr * s), "#F2E1E2", k=0.4 * s)
    # the Quiet Tree on the left with three lanterns
    m.add(cone((-15, 0, -7), (-15.2, 7.5, -7), 1.2, 0.7), "#8C7280", k=0.3)
    for dx, dy, rr in [(0, 9.6, 3.2), (-3, 8.6, 2.4), (3.2, 8.3, 2.4)]:
        m.add(sphere((-15 + dx, dy, -8), rr), "#8A9AB4", k=0.8)
    for x, y in [(-12.4, 7.2), (-17.2, 6.8), (-14.2, 6.3)]:
        m.add(capsule((x, y + 0.9, -5.5), (x, y + 0.4, -5.5), 0.03), "#CFC6D9")
        m.add(sphere((x, y, -5.5), 0.42), "#F6DCA5", emit=0.9)
    r = random.Random(12)
    for _ in range(14):
        flower(m, r.choice([-1, 1]) * r.uniform(7, 15), r.uniform(-5, 3), r.choice(["#D9C3EA", "#F6D8C8", "#FBF4E6"]), r.uniform(0.8, 1.2))
    return m
