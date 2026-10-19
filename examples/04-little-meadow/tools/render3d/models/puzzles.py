"""
Models for Pip's Puzzle Path (sprites pz_*): the memory card motif, the pattern train, the 3D shape
cards and their real-world twins, activity icons, and five jigsaw pictures (800×560 clay scenes with
the friends in them).

The jigsaw pictures reuse the real friends and objects: `place()` re-poses another Model's ops at a
position, scale and turn, so Pip in the nest is exactly the Pip from characters.py.
"""
from __future__ import annotations

import math
import random
import re

from models import group
from models import characters as ch
from models import objects as ob
from models.scenes import cloud, flower, hill
from sdf import (Model, Op, Shape, _rot_matrix, arc, box, capsule, cheeks, cone, cyl, ell, eyes, flat, inter, plane,
                 raw, smile, sphere, star, torus, v3)

S = (320, 320)
PIC = (800, 560)

_PM = re.compile(r"\bpm\b")
_P = re.compile(r"\bp\b")


def place(m: Model, src: Model, at=(0.0, 0.0, 0.0), s=1.0, yaw=0.0, roll=0.0, bound=((0.0, 1.0, 0.0), 1.9)) -> Model:
    """
    Adds every op of `src` to `m`, scaled by `s`, turned by `yaw` (about y) and `roll` (about z), moved to `at`.
    Paint ops are limited to the sphere `bound` (in src units): an object's stripes are infinite bands
    that would otherwise colour the whole scene.
    """
    q = f"(p-{v3(at)})"
    if yaw or roll:
        rm = _rot_matrix(0, yaw, roll).T  # world → local
        cols = ",".join(f"{rm[r, c]:.5f}" for c in range(3) for r in range(3))
        q = f"(mat3({cols})*{q})"
    q = f"({q}/{s:.5f})"
    qm = f"vec3(abs({q}.x),{q}.y,{q}.z)"
    for op in src.ops:
        e = _PM.sub("\x00", op.shape.expr)
        e = _P.sub(q, e).replace("\x00", qm)
        if op.kind == "paint" and bound is not None:
            e = f"max({e},length({q}-{v3(bound[0])})-{bound[1]:.4f})"
        m.ops.append(Op(op.kind, Shape(f"(({e})*{s:.5f})"), op.color, op.k * s, op.gloss, op.emit))
    return m


def picture(name, sky=("#BFDDEB", "#F2EEDF"), target=(0.0, 2.0, 0.0), distance=16.0, pitch=4.0, fov=30.0):
    return Model(f"pz_pic_{name}", scene=True, ground=False, sky=sky, haze=0.02, target=target, distance=distance, pitch=pitch, fov=fov)


# ---------------------------------------------------------------- memory: the card back motif

@group("pz_card_back", S)
def card_back():
    """A pressed-clay flower with two leaves, set on the card's sage face in Compose."""
    m = Model("pz_card_back", target=(0, 0, 0), distance=11, pitch=0, ground=False)
    m.add(ell((0.55, -0.75, 0), (0.62, 0.26, 0.1), rot=(0, 0, 35)), "#7FB06F", k=0.03)
    m.add(ell((-0.6, -0.7, 0), (0.55, 0.24, 0.1), rot=(0, 0, -30)), "#8ABB79", k=0.03)
    m.add(capsule((0, -1.25, -0.05), (0, -0.2, -0.05), 0.07), "#6E9E62")
    for i in range(5):
        a = math.pi / 2 + i * 2 * math.pi / 5
        m.add(ell((0.5 * math.cos(a), 0.15 + 0.5 * math.sin(a), 0), (0.36, 0.3, 0.14), rot=(0, 0, math.degrees(a))), "#FBF2E4", k=0.05)
    m.add(sphere((0, 0.15, 0.1), 0.28), "#F1C56E", k=0.04)
    for i in range(7):  # seeds on the middle
        a = i * 2.4
        m.paint(sphere((0.13 * math.cos(a), 0.15 + 0.13 * math.sin(a), 0.36), 0.035), "#D99A4E", soft=0.01)
    return m


# ---------------------------------------------------------------- patterns: a little clay train

TRAIN = {"body": "#E9A3A0", "trim": "#F6EFE4", "roof": "#9D97D2", "wheel": "#5E5468", "hub": "#F1C56E"}


def wheels(m, xs, r=0.34):
    for x in xs:
        m.add(cyl((x, r, 0.62), 0.09, r, 0.05, rot=(90, 0, 0)), TRAIN["wheel"])
        m.add(cyl((x, r, 0.7), 0.04, r * 0.4, 0.03, rot=(90, 0, 0)), TRAIN["hub"])


def train_engine():
    m = Model("pz_train_engine", target=(0, 1.0, 0), distance=12, pitch=8, reach=2.4)
    # cab at the back (left), boiler pointing right, the way the train goes
    m.add(box((-0.55, 1.05, 0), (0.62, 0.72, 0.55), 0.16), TRAIN["body"])
    m.add(box((-0.55, 1.9, 0), (0.78, 0.1, 0.66), 0.08), TRAIN["roof"])
    m.cut(box((-0.55, 1.25, 0.6), (0.32, 0.28, 0.2), 0.12), "#FBF6EE")
    m.add(cyl((0.55, 0.92, 0), 0.62, 0.5, 0.18, rot=(0, 0, 90)), TRAIN["body"], k=0.05)
    m.add(cyl((1.18, 0.92, 0), 0.06, 0.4, 0.05, rot=(0, 0, 90)), TRAIN["trim"])
    m.add(cone((0.75, 1.35, 0), (0.85, 2.0, 0), 0.16, 0.26), TRAIN["roof"], k=0.04)
    m.add(box((0.0, 0.42, 0), (1.25, 0.12, 0.52), 0.06), TRAIN["trim"])
    wheels(m, (-0.75, 0.05, 0.8))
    return m


def train_wagon():
    m = Model("pz_train_wagon", target=(0, 1.0, 0), distance=12, pitch=8, reach=2.0)
    # an open tub: what it carries is drawn on top in Compose
    tub = inter(box((0, 0.85, 0), (0.95, 0.42, 0.55), 0.16), plane((0, 1, 0), 1.18))
    m.add(tub, "#A9CFE0")
    m.cut(box((0, 1.25, 0), (0.8, 0.3, 0.42), 0.1), "#8FB7CC")
    m.add(box((0, 1.2, 0), (1.0, 0.07, 0.6), 0.05), TRAIN["trim"], k=0.02)
    m.cut(box((0, 1.3, 0), (0.82, 0.12, 0.44), 0.06), "#8FB7CC")
    m.add(box((0, 0.42, 0), (1.0, 0.1, 0.5), 0.05), TRAIN["trim"])
    m.add(capsule((-1.0, 0.55, 0), (-1.3, 0.55, 0), 0.06), TRAIN["wheel"])
    wheels(m, (-0.55, 0.55))
    return m


@group("pz_train", (400, 280))
def train():
    """Engine and wagon share one crop, so they line up on the same rails at the same scale."""
    return [train_engine(), train_wagon()]


# ---------------------------------------------------------------- 3D shapes (cards for the quiz)

SHAPE_COLORS = {
    "sphere": "#E9A3A0", "cube": "#8FAFD3", "cylinder": "#8DB48A",
    "cone": "#E7B05A", "pyramid": "#9D97D2", "torus": "#DC8B62",
}


def shape_model(name):
    m = Model(f"pz_shape_{name}", target=(0, 0.95, 0), distance=11, pitch=16, yaw=-22, reach=1.6)
    c = SHAPE_COLORS[name]
    if name == "sphere":
        m.add(sphere((0, 0.9, 0), 0.9), c)
    elif name == "cube":
        m.add(box((0, 0.78, 0), (0.75, 0.75, 0.75), 0.1), c)
    elif name == "cylinder":
        m.add(cyl((0, 0.85, 0), 0.85, 0.65, 0.08), c)
    elif name == "cone":
        m.add(raw("max(dot(vec2(length(p.xz),p.y-1.9),vec2(0.89443,0.44721)),-p.y)"), c)  # height 1.9, radius 0.95
    elif name == "pyramid":
        # four sloped faces over a square base
        h, b = 1.6, 0.95
        k = 1.0 / math.hypot(h, b)
        e = f"max(max(max((abs(p.x)*{h:.4f}+p.y*{b:.4f}-{h * b:.4f})*{k:.5f},(abs(p.z)*{h:.4f}+p.y*{b:.4f}-{h * b:.4f})*{k:.5f}),-p.y),0.0-1.0)"
        m.add(raw(e), c)
    elif name == "torus":
        m.add(torus((0, 0.42, 0), 0.72, 0.36, rot=(18, 0, 0)), c)
    return m


for _s in SHAPE_COLORS:
    group(f"pz_shape_{_s}", S)(lambda s=_s: shape_model(s))


@group("pz_ex_tent", S)
def tent():
    """Real-world pyramid: a little camping tent with an open flap."""
    m = Model("pz_ex_tent", target=(0, 0.9, 0), distance=11, pitch=14, yaw=-25, reach=1.8)
    h, b = 1.7, 1.15
    k = 1.0 / math.hypot(h, b)
    e = f"max(max((abs(p.x)*{h:.4f}+p.y*{b:.4f}-{h * b:.4f})*{k:.5f},(abs(p.z)*{h:.4f}+p.y*{b:.4f}-{h * b:.4f})*{k:.5f}),-p.y)"
    m.add(raw(e), "#8DB48A")
    m.paint(raw(f"abs(p.x)-0.04"), "#F6EFE4", soft=0.02)
    m.cut(raw("max(max(abs(p.x)*1.6+p.y*0.8-0.8,-p.y),-(p.z-0.4))"), "#4F6B52", k=0.02)
    m.add(capsule((0, 1.65, 0), (0, 2.05, 0), 0.04), "#A98262")
    m.add(ell((0.17, 1.98, 0), (0.17, 0.09, 0.02)), "#E2705F")
    return m


@group("pz_ex_donut", S)
def donut():
    """Real-world torus: a pink-frosted doughnut with sprinkles."""
    m = Model("pz_ex_donut", target=(0, 0.45, 0), distance=10, pitch=32, reach=1.6)
    m.add(torus((0, 0.38, 0), 0.68, 0.36), "#E3B47A")
    icing = inter(torus((0, 0.4, 0), 0.68, 0.385), raw("(0.46+0.05*sin(atan(p.z,p.x)*7.0)-p.y)"))
    m.add(icing, "#F1ACC3", k=0.02, gloss=0.25)
    r = random.Random(4)
    cols = ["#F6EFE4", "#73A6DB", "#F3D266", "#82C074", "#A98DCC"]
    for i in range(22):
        a = i * 2 * math.pi / 22 + r.uniform(-0.1, 0.1)
        rr = 0.68 + r.uniform(-0.16, 0.16)
        x, z = rr * math.cos(a), rr * math.sin(a)
        y = 0.4 + math.sqrt(max(0.0, 0.385 ** 2 - (rr - 0.68) ** 2)) + 0.005
        m.add(capsule((x - 0.04, y, z), (x + 0.04, y, z + 0.03), 0.022), cols[i % len(cols)])
    return m


# ---------------------------------------------------------------- activity icons

@group("pz_icon_jigsaw", S)
def icon_jigsaw():
    m = Model("pz_icon_jigsaw", target=(0, 0.8, 0), distance=10, pitch=30, reach=1.8)
    # one chunky piece: square with a knob on top and right, a hole on the left
    base = "sdBox2(p.xz-vec2(0.0,0.0),vec2(0.62))"
    knobs = "min(length(p.xz-vec2(0.0,-0.82))-0.27,length(p.xz-vec2(0.82,0.0))-0.27)"
    hole = "length(p.xz-vec2(-0.62,0.0))-0.24"
    d2 = f"max(min({base},{knobs}),-({hole}))"
    m.add(raw(f"extrude({d2},p.y-0.25,0.22,0.1)"), "#E9A3A0")
    m.add(flat((-0.05, 0.48, 0.05), "heart", 0.26, 0.06, 0.05, rot=(-90, 0, 0)), "#FBF2E4")
    return m


@group("pz_icon_shadow", S)
def icon_shadow():
    m = Model("pz_icon_shadow", target=(0.0, 0.9, 0), distance=12, pitch=10, reach=2.2)
    place(m, ob.duck(), at=(-0.75, 0, 0.3), s=0.85, yaw=20)
    duck2 = ob.duck()
    for op in duck2.ops:
        op.color = "srgb(vec3(0.33,0.30,0.40))"
        op.gloss, op.emit = 0.0, 0.0
    place(m, duck2, at=(0.9, 0, -0.8), s=0.85, yaw=20)
    return m


@group("pz_icon_shapes", S)
def icon_shapes():
    m = Model("pz_icon_shapes", target=(0, 0.95, 0), distance=12, pitch=14, yaw=-15, reach=2.2)
    m.add(box((-0.55, 0.55, 0), (0.55, 0.55, 0.55), 0.08), SHAPE_COLORS["cube"])
    m.add(sphere((0.75, 0.6, 0.35), 0.6), SHAPE_COLORS["sphere"])
    m.add(raw("max(dot(vec2(length(p.xz-vec2(-0.55,0.0)),p.y-2.35),vec2(0.89443,0.44721)),-(p.y-1.1))"), SHAPE_COLORS["cone"])
    return m


# ---------------------------------------------------------------- jigsaw pictures (800×560, opaque)

def leafy(m, x, y, z, r, color="#8FC07E"):
    for dx, dy, rr in [(0, 0, 1.0), (-0.75, -0.3, 0.75), (0.8, -0.25, 0.8), (0.1, 0.6, 0.7)]:
        m.add(sphere((x + dx * r, y + dy * r, z), rr * r), color, k=0.35 * r)


def floor(m, color, y=0.0):
    m.add(raw(f"(p.y-({y:.3f}))"), color)


def grass(m, near="#A9CE96", far="#C7E0B3", y=0.0):
    floor(m, near, y)
    hill(m, -12, -24, 20, 5 + y, 9, far)
    hill(m, 14, -26, 22, 6 + y, 10, far)


@group("pz_pic_nest", PIC)
def pic_nest():
    m = picture("nest", sky=("#B9DAEA", "#F3EFDF"), target=(0, 2.2, 0))
    grass(m, "#B2D49C", "#CDE3B9", y=-1.9)
    leafy(m, -5.6, -1.4, -1.0, 1.0, "#9CCB8A")
    leafy(m, -3.9, -1.7, 0.5, 0.6, "#8FC07E")
    r = random.Random(3)
    bed = Model("bed")
    for i in range(9):
        x = -4.4 + i * 1.15 + r.uniform(-0.3, 0.3)
        flower(bed, x, r.uniform(0.6, 2.2), r.choice(["#F1ACC3", "#F3D266", "#FBF4E6", "#A98DCC", "#F0A15A"]), r.uniform(0.8, 1.1))
    place(m, bed, at=(0, -1.9, 0))
    # the tree on the right with a branch reaching in
    m.add(cone((6.6, -3, -1.5), (6.2, 8, -1.5), 1.2, 0.8), "#A98262", k=0.3)
    m.add(capsule((6.2, 1.3, -1.2), (-0.5, 0.95, -0.3), 0.36), "#A98262", k=0.3)
    m.add(capsule((3.0, 1.2, -0.9), (2.4, 2.4, -1.1), 0.16), "#A98262", k=0.15)
    leafy(m, 6.3, 6.0, -2.5, 2.4)
    leafy(m, 2.4, 2.8, -1.4, 0.55, "#9CCB8A")
    # the nest: a woven ring with a bowl
    m.add(ell((0.4, 1.35, 0), (1.55, 0.6, 1.2)), "#B88A5E", k=0.1)
    m.add(torus((0.4, 1.75, 0), 1.42, 0.38), "#C99A6B", k=0.15)
    weave = f"max(abs(sin(p.x*7.0+p.y*9.0))*0.12-0.05,{ell((0.4, 1.55, 0), (1.95, 0.9, 1.7)).expr})"
    m.paint(raw(weave), "#B3855C", soft=0.02)
    place(m, ch.pip("happy"), at=(0.4, 1.05, 0.15), s=1.25)
    m.add(ell((1.55, 2.0, 0.35), (0.3, 0.38, 0.3), rot=(0, 0, -15)), "#E9F0F3", k=0.02)
    m.paint(sphere((1.5, 2.15, 0.62), 0.07), "#9FC3D6", soft=0.03)
    # sky: a sleepy sun and clouds
    place(m, ob.sun(), at=(-4.6, 3.9, -2.0), s=1.15)
    cloud(m, -1.6, 6.0, -8, 0.9)
    cloud(m, 3.0, 6.6, -12, 1.1)
    place(m, ob.butterfly(), at=(-3.2, 1.1, 0.5), s=0.55, yaw=20)
    return m


@group("pz_pic_paint", PIC)
def pic_paint():
    m = picture("paint", sky=("#F4E6D6", "#F4E6D6"), target=(0, 2.1, 0))
    floor(m, "#E4C8A2", -0.4)
    m.add(raw("(p.z+5.0)"), "#F3E1CF")  # wall
    m.add(box((0, -0.2, -4.8), (20, 0.3, 0.2), 0.1), "#FBF6EE")
    # bunting across the wall
    m.add(arc((0.5, 13.9, -4.6), 9.0, 0.03, 30, rot=(0, 0, 180)), "#C9B8A8")
    for i, c in enumerate(["#E2705F", "#F3D266", "#73A6DB", "#82C074", "#A98DCC", "#F1ACC3", "#F0A15A", "#73A6DB", "#E2705F"]):
        a = math.radians(-24 + i * 6)
        x, y = 0.5 + 9.0 * math.sin(a), 13.9 - 9.0 * math.cos(a)
        m.add(flat((x, y - 0.32, -4.55), "triangle", 0.3, 0.03, 0.03, rot=(0, 0, 180)), c)
    # a round window with a bit of sky and a cloud
    m.add(cyl((-4.6, 4.4, -4.9), 0.22, 1.5, 0.15, rot=(90, 0, 0)), "#FBF6EE", k=0.03)
    m.add(cyl((-4.6, 4.4, -4.75), 0.15, 1.25, 0.08, rot=(90, 0, 0)), "#BFDCEA", emit=0.4)
    m.add(sphere((-4.9, 4.2, -4.55), 0.32), "#FBFAF6", k=0.1)
    m.add(sphere((-4.5, 4.35, -4.55), 0.4), "#FBFAF6", k=0.1)
    # easel with a rainbow painting
    m.add(capsule((0.9, -0.4, -1.4), (1.6, 4.6, -1.9), 0.12), "#B98E68")
    m.add(capsule((4.1, -0.4, -1.4), (3.4, 4.6, -1.9), 0.12), "#B98E68")
    m.add(capsule((2.5, -0.4, -2.6), (2.5, 4.2, -2.0), 0.1), "#B98E68")
    m.add(box((2.5, 1.0, -1.35), (1.7, 0.08, 0.2), 0.05), "#B98E68")
    m.add(box((2.5, 2.6, -1.55), (1.55, 1.3, 0.08), 0.05, rot=(-6, 0, 0)), "#FFFDF8")
    for i, c in enumerate(["#E2705F", "#F0A15A", "#F3D266", "#82C074", "#73A6DB", "#A98DCC"]):
        m.add(arc((2.5, 1.75, -1.42), 1.05 - i * 0.14, 0.065, 90, rot=(-6, 0, 0)), c)
    # Tuka, turned towards the easel, with a brush
    place(m, ch.tuka("happy"), at=(-1.6, -0.4, 0.4), s=1.2, yaw=28)
    m.add(capsule((-0.6, 0.85, 1.1), (0.25, 1.65, 0.75), 0.06), "#C99A6B")
    m.add(sphere((0.3, 1.72, 0.72), 0.12), "#73A6DB", k=0.05)
    # paint pots
    for i, c in enumerate(["#E2705F", "#F3D266", "#73A6DB", "#82C074"]):
        x = -4.5 + i * 0.95
        m.add(cyl((x, -0.05, 1.4), 0.35, 0.36, 0.08), "#FBF6EE")
        m.add(cyl((x, 0.31, 1.4), 0.04, 0.3, 0.03), c)
    m.add(ell((5.0, 0.0, 1.0), (0.75, 0.15, 0.5)), "#F6EBDA")  # palette
    for dx, c in [(-0.3, "#E2705F"), (0.0, "#73A6DB"), (0.3, "#F3D266")]:
        m.add(sphere((5.0 + dx, 0.15, 1.15), 0.12), c)
    return m


@group("pz_pic_balloons", PIC)
def pic_balloons():
    m = picture("balloons", sky=("#B6D8EC", "#F1F0E2"), target=(0, 2.4, 0))
    grass(m, "#A9CE96", "#C7E0B3", y=-0.3)
    m.add(ell((0, -0.3, 0), (5.5, 0.55, 3.5)), "#B5D7A0", k=0.8)
    place(m, ch.bo("wave"), at=(-0.7, 0.0, 0.8), s=1.5)
    hand = (0.82, 2.1, 1.1)
    cols = [("#E2705F", (-0.6, 5.0, -0.5)), ("#F3D266", (0.9, 5.6, -0.8)), ("#73A6DB", (2.3, 4.8, -0.4)), ("#A98DCC", (1.4, 4.1, 0.2))]
    for c, (x, y, z) in cols:
        m.add(ell((x, y, z), (0.62, 0.74, 0.62)), c, gloss=0.25)
        m.add(cone((x, y - 0.72, z), (x, y - 0.86, z), 0.1, 0.07), c, k=0.05)
        m.add(capsule((x, y - 0.86, z), hand, 0.018), "#B8AFA6")
    cloud(m, -4.8, 5.2, -6, 0.9)
    cloud(m, 5.2, 6.0, -8, 1.0)
    r = random.Random(7)
    for _ in range(16):
        x = r.choice([-1, 1]) * r.uniform(2.8, 7.5)
        flower(m, x, r.uniform(-1.5, 2.0), r.choice(["#F1ACC3", "#F3D266", "#FBF4E6", "#A98DCC", "#F0A15A"]), r.uniform(0.9, 1.3))
    for i in range(7):  # a row of flowers in front
        flower(m, -4.6 + i * 1.5 + r.uniform(-0.3, 0.3), 4.2, r.choice(["#F1ACC3", "#F3D266", "#A98DCC", "#F0A15A"]), 0.85)
    place(m, ob.ball(), at=(3.6, -0.25, 2.6), s=0.55, bound=((0, 0.85, 0), 0.9))
    return m


@group("pz_pic_garden", PIC)
def pic_garden():
    m = picture("garden", sky=("#C6E2E6", "#F2EFDF"), target=(0, 1.9, 0))
    grass(m, "#AFD199", "#C5DDAF", y=0.0)
    for i in range(-8, 9, 1):  # a little fence behind
        m.add(box((i * 0.9, 0.9, -3.5), (0.16, 0.9, 0.08), 0.08), "#F6EFE4")
    m.add(box((0, 1.15, -3.6), (8, 0.1, 0.05), 0.04), "#F6EFE4")
    # vegetable bed in front of Moss
    m.add(box((3.4, 0.25, 0.6), (2.0, 0.25, 0.9), 0.15), "#B88B66")
    for i in range(3):
        x = 2.2 + i * 1.2
        m.add(cone((x, 0.55, 0.6), (x, 0.1, 0.75), 0.24, 0.05), "#EE9750")
        m.add(ell((x - 0.1, 0.9, 0.6), (0.08, 0.32, 0.06), rot=(0, 0, 18)), "#78B469")
        m.add(ell((x + 0.1, 0.9, 0.6), (0.08, 0.3, 0.06), rot=(0, 0, -18)), "#78B469")
    place(m, ch.moss("happy"), at=(-1.3, 0.0, 0.8), s=1.45, yaw=10)
    place(m, ob.mushroom(), at=(-4.6, 0.0, 0.6), s=0.8)
    place(m, ob.snail(), at=(1.8, 0.0, 2.0), s=0.5, yaw=-20)
    place(m, ob.sun(), at=(4.6, 4.2, -3.0), s=1.0)
    cloud(m, -3.2, 5.6, -8, 1.0)
    r = random.Random(9)
    for _ in range(14):
        x = r.uniform(-7, 7)
        if -3 < x < 5.5:
            x = r.choice([-1, 1]) * r.uniform(5.5, 7.5)
        flower(m, x, r.uniform(-2.5, 2.0), r.choice(["#F1ACC3", "#F3D266", "#FBF4E6", "#A98DCC"]), r.uniform(0.9, 1.3))
    for i in range(6):  # stepping stones and flowers in front
        m.add(ell((-3.8 + i * 1.5, 0.0, 4.2 + (i % 2) * 0.4), (0.55, 0.12, 0.35)), "#EADFD0", k=0.05)
    for i in range(5):
        flower(m, -4.2 + i * 2.0 + r.uniform(-0.3, 0.3), 5.2, r.choice(["#F1ACC3", "#F3D266", "#A98DCC", "#F0A15A"]), 0.8)
    place(m, ob.butterfly(), at=(-4.0, 2.3, 0.5), s=0.5, yaw=-20)
    return m


@group("pz_pic_moon", PIC)
def pic_moon():
    m = picture("moon", sky=("#363A6B", "#6F72A6"), target=(0, 2.2, 0))
    floor(m, "#55648A", -1.3)
    hill(m, 6, -14, 12, 1.6, 6, "#5E6D93")
    # the branch Luma sits on
    m.add(cone((-6.4, -3, -1.5), (-6.0, 7, -1.5), 1.1, 0.75), "#7A6878", k=0.3)
    m.add(capsule((-6.0, 1.0, -1.2), (2.6, 0.75, -0.2), 0.32), "#7A6878", k=0.3)
    leafy(m, -6.2, 6.2, -2.5, 2.3, "#5F7392")
    place(m, ch.luma("idle"), at=(-0.6, 0.95, 0.0), s=1.4)
    bush = Model("bush")
    for x, z, rr in [(-3.2, 1.5, 0.9), (-1.9, 2.4, 0.6), (3.0, 2.0, 0.8), (4.6, 1.0, 1.0)]:
        leafy(bush, x, rr * 0.4, z, rr, "#4E5F84")
    for i, (x, z) in enumerate([(-4.6, 3.2), (-0.8, 3.6), (1.2, 3.0), (5.6, 3.4), (2.4, 4.4)]):
        bush.add(capsule((x, 0, z), (x, 0.5, z), 0.035), "#6F86A4")
        bush.add(sphere((x, 0.55, z), 0.14), "#F3E2B0", emit=0.8)
    place(m, bush, at=(0, -1.3, 0), bound=None)
    # a smiling crescent moon and twinkly stars
    place(m, ob.moon(), at=(4.0, 3.0, -1.5), s=1.55)
    r = random.Random(12)
    for _ in range(22):
        x, y = r.uniform(-8, 8), r.uniform(3.5, 7.5)
        if abs(x - 4.0) < 1.9 and abs(y - 4.5) < 2.0:
            continue
        if x < -3.5 and y < 7.0:
            continue
        m.add(star((x, y, -6), r.uniform(0.12, 0.22), 0.5, 0.05, 0.04), "#F7EFD6", emit=0.9)
    for x, y in [(1.3, 0.25), (-2.3, 0.3)]:  # two little lanterns on the branch
        m.add(capsule((x, y + 0.42, 0.2), (x, y + 0.75, 0.0), 0.02), "#B9B3C9")
        m.add(sphere((x, y + 0.2, 0.2), 0.24), "#F6CF86", emit=1.0)
    return m
