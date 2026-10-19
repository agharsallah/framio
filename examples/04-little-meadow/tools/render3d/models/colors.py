"""
Models for Tuka's Studio (colours, shapes, making things). Sprite names start with co_.

    co_car_<c>, co_sock_<c>, co_cup_<c>   everyday things in the ten teaching colours (Color hunt)
    co_shape_<shape>_<c>                   chunky clay shapes with a little face (Shape sorter)
    co_hole_<shape>, co_sorter             the sorter box (seen from above) and the holes on its lid
    co_pot_<c>, co_bowl                    paint pots and the mixing bowl (Mixing colors)
    co_icon_*                              the activity cards
"""
from __future__ import annotations

import math

from models import group
from models.objects import COLORS, LEAF, STEM
from sdf import Model, arc, box, capsule, cheeks, cone, cyl, ell, eyes, flat, inter, plane, raw, shell, smile, sphere, star, torus

S = (320, 320)
TEACH = ["red", "orange", "yellow", "green", "blue", "purple", "pink", "brown", "white", "black"]
SHAPES = ["circle", "square", "triangle", "star", "heart", "rect", "oval", "diamond", "hexagon"]
SHAPE_COLORS = ["red", "yellow", "blue", "green", "purple"]
INK_SOFT = "#3A3040"


def obj(name, target=(0, 0.9, 0), distance=11.0, ground=True, pitch=12.0, fov=20.0, yaw=0.0, reach=1.4):
    return Model(f"co_{name}", target=target, distance=distance, ground=ground, pitch=pitch, fov=fov, yaw=yaw, reach=reach)


def shade(hex_color: str, f: float) -> str:
    """Lighter (f > 0, towards cream) or darker (f < 0, towards plum ink) version of a colour."""
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    to = (255, 250, 240) if f > 0 else (42, 32, 48)
    a = abs(f)
    return "#%02X%02X%02X" % tuple(round(c + (t - c) * a) for c, t in zip((r, g, b), to))


# ---------------------------------------------------------------- everyday things in every colour

def car(color):
    c = COLORS[color]
    tyre = "#2F2A34" if color == "black" else "#4A4550"
    m = obj(f"car_{color}", target=(0, 0.75, 0), pitch=14, yaw=-25)
    m.add(box((0, 0.6, 0), (1.15, 0.32, 0.6), 0.25), c)
    m.add(box((-0.1, 1.05, 0), (0.6, 0.32, 0.52), 0.25), c, k=0.08)
    m.add(box((0.15, 1.05, 0), (0.32, 0.22, 0.54), 0.12), "#CFE3EE", gloss=0.6)
    m.add(box((-0.42, 1.05, 0), (0.18, 0.22, 0.54), 0.1), "#CFE3EE", gloss=0.6)
    for x in (-0.7, 0.7):
        m.add(cyl((x, 0.3, 0), 0.62, 0.3, 0.1, rot=(90, 0, 0)), tyre)
        m.add(cyl((x, 0.3, 0), 0.64, 0.12, 0.04, rot=(90, 0, 0)), "#D8D2CB")
    m.add(sphere((1.12, 0.68, 0.38), 0.1), "#F3E2B0", emit=0.4)
    return m


def sock(color):
    c = COLORS[color]
    m = obj(f"sock_{color}")
    m.add(capsule((-0.2, 1.5, 0), (-0.2, 0.45, 0), 0.38), c)
    m.add(capsule((-0.2, 0.45, 0), (0.6, 0.38, 0), 0.36), c, k=0.15)
    # ribbed cuff and a toe in a deeper tone of the same colour: the sock is one colour to name
    m.add(cyl((-0.2, 1.62, 0), 0.2, 0.43, 0.12), shade(c, 0.25 if color != "white" else -0.05), k=0.04)
    for i in range(7):
        a = math.radians(-60 + i * 20)
        m.paint(capsule((-0.2 + 0.43 * math.sin(a), 1.46, 0.43 * math.cos(a)), (-0.2 + 0.43 * math.sin(a), 1.78, 0.43 * math.cos(a)), 0.025),
                shade(c, -0.12), soft=0.02)
    m.paint(sphere((0.85, 0.4, 0), 0.3), shade(c, -0.12), soft=0.05)
    m.paint(sphere((-0.45, 0.25, 0.1), 0.3), shade(c, -0.12), soft=0.05)
    return m


def cup(color):
    c = COLORS[color]
    m = obj(f"cup_{color}", target=(0, 0.85, 0), pitch=16)
    body = cyl((0, 0.75, 0), 0.75, 0.68, 0.18)
    m.add(body, c)
    m.cut(cyl((0, 1.4, 0), 0.5, 0.56, 0.12), shade(c, -0.18))
    m.add(torus((0.78, 0.8, 0), 0.34, 0.09, rot=(90, 0, 0)), c, k=0.06)
    m.paint(raw("abs(p.y-0.62)-0.07"), shade(c, 0.45) if color != "white" else "#C8DCEB", soft=0.015)
    return m


for _fam, _fn in (("car", car), ("sock", sock), ("cup", cup)):
    group(f"co_{_fam}", S)(lambda fn=_fn: [fn(c) for c in TEACH])


# ---------------------------------------------------------------- shape pieces with faces

# where the face sits on each shape, as (y offset, scale) around the shape centre
FACE = {
    "circle": (0.05, 1.0), "square": (0.05, 1.0), "triangle": (-0.22, 0.85), "star": (-0.02, 0.8),
    "heart": (0.12, 0.95), "rect": (0.02, 0.95), "oval": (0.02, 0.95), "diamond": (0.0, 0.85), "hexagon": (0.04, 1.0),
}


# the heart's distance field is drawn smaller than the others: scale it up to look the same size
SIZE_FIX = {"heart": 1.32, "triangle": 1.05}


def shape_piece(shape, color, size=0.82, th=0.26):
    c = COLORS[color]
    size *= SIZE_FIX.get(shape, 1.0)
    cy = 1.0
    m = obj(f"shape_{shape}_{color}", target=(0, cy, 0), ground=False, pitch=8, distance=11)
    m.add(flat((0, cy, 0), shape, size, th, 0.13), c, gloss=0.12)
    fy, fs = FACE[shape]
    y = cy + fy * size
    fs *= 1.3
    z = th + 0.005
    ink = INK_SOFT if color != "black" else "#F8F4EC"
    eyes(m, 0.2 * fs, y + 0.1, z, 0.075 * fs, "open", ink=ink)
    cheeks(m, 0.34 * fs, y - 0.06, z, 0.09 * fs, color="#F7B7AE" if color != "red" else "#F4A39A")
    smile(m, 0, y - 0.08, z + 0.005, 0.15 * fs, "open", ink=ink)
    return m


@group("co_shape", S)
def _shapes():
    return [shape_piece(s, c) for s in SHAPES for c in SHAPE_COLORS]


# ---------------------------------------------------------------- the sorter box and its holes

LID = "#EAD7BD"
BOX = "#93B9DE"
HOLE = "#5C4C63"
SORTER_CAM = dict(pitch=36.0, fov=20.0, distance=20.0)


@group("co_sorter", (512, 256))
def sorter():
    m = Model("co_sorter", target=(0, 0.6, 0), ground=True, reach=4.0, **SORTER_CAM)
    m.add(box((0, 0.62, 0), (2.9, 0.62, 1.1), 0.28), BOX)
    # a soft cream lid that overhangs a little
    m.add(box((0, 1.3, 0), (3.0, 0.11, 1.2), 0.1), LID, k=0.04)
    # chunky feet
    m.add(sphere((2.5, 0.12, 0.75), 0.22, sym=True), "#E7B05A", k=0.05)
    m.add(sphere((2.5, 0.12, -0.75), 0.22, sym=True), "#E7B05A", k=0.05)
    # a little star and dots on the front, like a toy
    m.add(star((0, 0.66, 1.1), 0.3, 0.5, 0.05, 0.03), "#F3D266")
    for x, col in ((-1.6, "#E2705F"), (-0.85, "#82C074"), (0.85, "#A98DCC"), (1.6, "#F1ACC3")):
        m.add(sphere((x, 0.66, 1.08), 0.14), col)
    return m


def hole(shape, size=0.55):
    size *= SIZE_FIX.get(shape, 1.0)
    m = Model(f"co_hole_{shape}", target=(0, 0.0, 0), ground=False, **SORTER_CAM)
    rot = (-90, 0, 0)  # lie flat on the lid, its top pointing away from us
    m.add(flat((0, 0.0, 0), shape, size * 1.22, 0.05, 0.04, rot=rot), shade(LID, -0.06))
    m.cut(flat((0, 0.0, 0), shape, size, 0.2, 0.03, rot=rot), shade(LID, -0.18), k=0.02)
    m.add(flat((0, -0.12, 0), shape, size, 0.02, 0.0, rot=rot), HOLE)
    return m


@group("co_hole", (256, 256))
def _holes():
    return [hole(s) for s in SHAPES]


# ---------------------------------------------------------------- paint pots and the bowl

POT_COLORS = {**{k: COLORS[k] for k in ("red", "yellow", "blue", "white", "black")}}


def pot(color):
    c = POT_COLORS[color]
    m = obj(f"pot_{color}", target=(0, 0.8, 0), pitch=26, distance=10)
    m.add(cyl((0, 0.62, 0), 0.62, 0.72, 0.2), "#B7C7CF")
    m.add(torus((0, 1.22, 0), 0.68, 0.08), "#D5E0E5", k=0.04)
    m.cut(cyl((0, 1.3, 0), 0.2, 0.62, 0.1))
    m.add(cyl((0, 1.05, 0), 0.06, 0.63, 0.04), c, gloss=0.55)
    # a drip running down the front and a painted band
    m.add(capsule((0.18, 1.2, 0.7), (0.2, 0.75, 0.72), 0.08), c, k=0.06, gloss=0.5)
    m.add(sphere((0.2, 0.72, 0.72), 0.11), c, k=0.05, gloss=0.5)
    m.paint(raw("abs(p.y-0.42)-0.12"), c, soft=0.02)
    return m


@group("co_pot", S)
def _pots():
    return [pot(c) for c in POT_COLORS]


@group("co_bowl", (512, 384))
def bowl():
    m = obj("bowl", target=(0, 0.8, 0), pitch=34, distance=12, reach=2.4)
    outer = ell((0, 1.15, 0), (1.9, 1.15, 1.9))
    m.add(inter(shell(outer, 0.1), plane((0, 1, 0), 1.25)), "#FBF6EE")
    m.add(torus((0, 1.25, 0), 1.82, 0.11), "#FBF8F2", k=0.05)
    m.add(cyl((0, 0.08, 0), 0.08, 0.9, 0.06), "#EFE4D4", k=0.12)
    outside = f"(-{outer.expr})"  # stripes only on the outside of the bowl
    m.paint(raw(f"max(abs(p.y-0.65)-0.09,{outside})"), "#9FC6E8", soft=0.015)
    m.paint(raw(f"max(abs(p.y-0.92)-0.04,{outside})"), "#F1ACC3", soft=0.015)
    return m


# ---------------------------------------------------------------- activity icons

@group("co_icon_hunt", S)
def icon_hunt():
    m = obj("icon_hunt", target=(0, 1.35, 0), ground=False, distance=13)
    for x, y, z, c in ((-0.75, 1.75, -0.2, "red"), (0.75, 1.8, -0.2, "blue"), (0.0, 2.1, 0.2, "yellow")):
        m.add(ell((x, y, z), (0.6, 0.7, 0.6)), COLORS[c], gloss=0.25)
        m.add(cone((x * 0.92, y - 0.7, z), (x * 0.9, y - 0.82, z), 0.1, 0.07), COLORS[c], k=0.06)
        m.add(capsule((x * 0.9, y - 0.85, z), (0, 0.2, 0), 0.02), "#B8AFA6")
    m.add(ell((0, 0.22, 0), (0.12, 0.08, 0.08)), "#F1ACC3")
    return m


@group("co_icon_sorter", S)
def icon_sorter():
    m = obj("icon_sorter", target=(0, 0.9, 0), pitch=24, yaw=-22)
    m.add(box((0, 0.55, 0), (0.85, 0.55, 0.85), 0.18), BOX)
    m.add(box((0, 1.12, 0), (0.92, 0.07, 0.92), 0.06), LID, k=0.03)
    m.cut(flat((-0.35, 1.2, 0.3), "circle", 0.22, 0.2, 0.03, rot=(-90, 0, 0)), HOLE)
    m.cut(flat((0.35, 1.2, -0.3), "square", 0.2, 0.2, 0.03, rot=(-90, 0, 0)), HOLE)
    m.add(flat((0.35, 1.55, 0.35), "star", 0.32, 0.12, 0.06, rot=(-15, 0, 10)), COLORS["yellow"])
    m.add(flat((-0.45, 1.42, -0.35), "triangle", 0.26, 0.1, 0.05, rot=(-20, 0, -15)), COLORS["red"])
    return m


@group("co_icon_mix", S)
def icon_mix():
    m = obj("icon_mix", target=(0, 0.75, 0), pitch=34, distance=10)
    outer = ell((0, 1.0, 0), (1.25, 0.85, 1.25))
    m.add(inter(shell(outer, 0.08), plane((0, 1, 0), 1.05)), "#FBF6EE")
    m.add(torus((0, 1.05, 0), 1.2, 0.08), "#FBF8F2", k=0.04)
    m.add(cyl((0, 0.82, 0), 0.03, 1.08, 0.02), COLORS["orange"], gloss=0.5)
    m.paint(raw("abs(length(p.xz-vec2(0.15,0.1))-0.45)-0.09"), COLORS["red"], soft=0.06)
    m.paint(sphere((-0.3, 0.84, -0.35), 0.25), COLORS["yellow"], soft=0.12)
    m.paint(raw("abs(p.y-0.5)-0.06"), "#9FC6E8", soft=0.015)
    return m


@group("co_icon_paint", S)
def icon_paint():
    m = obj("icon_paint", target=(0, 1.05, 0), pitch=10, ground=False)
    # a paint splodge behind and a fat brush in front
    for x, y, r in ((-0.35, 0.9, 0.55), (0.25, 1.05, 0.5), (-0.1, 1.45, 0.42), (0.4, 0.6, 0.3), (-0.75, 1.3, 0.22)):
        m.add(cyl((x, y, -0.3), 0.04, r, 0.03, rot=(90, 0, 0)), COLORS["blue"], k=0.12)
    m.add(capsule((0.9, 2.0, 0.1), (0.0, 0.85, 0.15), 0.1), "#E2A86A")
    m.add(cone((0.05, 0.92, 0.15), (-0.12, 0.65, 0.15), 0.14, 0.15), "#D8D2CB", k=0.03)
    m.add(cone((-0.13, 0.62, 0.15), (-0.42, 0.25, 0.15), 0.16, 0.04), COLORS["pink"], k=0.04, gloss=0.4)
    return m


@group("co_icon_coloring", S)
def icon_coloring():
    m = obj("icon_coloring", target=(0, 1.1, 0), pitch=10)
    # a page leaning back with a half-coloured flower, and a fat crayon in front
    rot = (-12, 0, 0)
    m.add(box((0, 1.15, -0.3), (0.95, 1.1, 0.05), 0.05, rot=rot), "#FBF8F2")
    cx, cy, z = -0.05, 1.35, -0.2
    for i in range(5):
        a = math.pi / 2 + i * 2 * math.pi / 5
        px, py = cx + 0.36 * math.cos(a), cy + 0.36 * math.sin(a)
        if i < 3:
            m.add(cyl((px, py, z), 0.02, 0.22, 0.0, rot=(78, 0, 0)), COLORS["pink"])
        m.add(torus((px, py, z + 0.02), 0.22, 0.035, rot=(78, 0, 0)), INK_SOFT)
    m.add(cyl((cx, cy, z + 0.03), 0.03, 0.16, 0.0, rot=(78, 0, 0)), COLORS["yellow"])
    m.add(torus((cx, cy, z + 0.05), 0.16, 0.035, rot=(78, 0, 0)), INK_SOFT)
    m.add(capsule((cx, 0.75, z - 0.1), (cx, cy - 0.38, z - 0.03), 0.035), INK_SOFT)
    col = COLORS["red"]
    a = math.radians(35)
    d = (math.cos(a), math.sin(a), 0.0)
    base = (0.15, 0.35, 0.45)
    base = (-0.25, 0.1, 0.5)
    tip = tuple(b + 1.25 * k for b, k in zip(base, d))
    m.add(capsule(base, tip, 0.24), col)
    m.add(cone(tip, tuple(t + 0.42 * k for t, k in zip(tip, d)), 0.24, 0.05), shade(col, -0.12), k=0.02)
    mid = tuple(b + 0.6 * k for b, k in zip(base, d))
    m.paint(sphere(mid, 0.27), "#FBF6EE", soft=0.01)
    return m
