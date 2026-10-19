"""
The shared object library: things to count, name, sort and collect. Each sprite is ob_<name>.webp,
320×320, centred, standing on the floor (soft contact shadow) unless it floats.

Activity-specific models live in their own files (models/<world>.py); add shared ones here.
"""
from __future__ import annotations

import math

from models import group
from sdf import (Model, arc, box, capsule, cheeks, cone, cyl, ell, eyes, flat, inter, plane, raw, shell,
                 smile, sphere, star, sub, torus, uni)

S = (320, 320)

# The teaching colours, matching ui/Theme.kt Palette (slightly lighter: the renderer's light darkens a little)
COLORS = {
    "red": "#E2705F", "orange": "#F0A15A", "yellow": "#F3D266", "green": "#82C074", "blue": "#73A6DB",
    "purple": "#A98DCC", "pink": "#F1ACC3", "brown": "#AE7E5F", "white": "#F8F4EC", "black": "#4A4550",
}
LEAF = "#78B469"
STEM = "#8A6A4E"


def obj(name, target=(0, 0.9, 0), distance=11.0, ground=True, pitch=12.0, fov=20.0, yaw=0.0, reach=1.4):
    return Model(f"ob_{name}", target=target, distance=distance, ground=ground, pitch=pitch, fov=fov, yaw=yaw, reach=reach)


def leaf_on(m, at, rot, size=0.32, color=LEAF):
    m.add(ell(at, (size, size * 0.12, size * 0.5), rot=rot), color, k=0.02)


# ---------------------------------------------------------------- fruit & food

@group("ob_apple", S)
def apple():
    m = obj("apple")
    m.add(sphere((0, 0.88, 0), 0.85), "#DD6B5C")
    m.cut(sphere((0, 1.78, 0), 0.32), k=0.25)
    m.cut(sphere((0, -0.02, 0), 0.22), k=0.2)
    m.paint(sphere((-0.35, 1.2, 0.6), 0.35), "#EA8A72", soft=0.25)
    m.add(capsule((0, 1.5, 0), (0.06, 1.95, 0), 0.06), STEM, k=0.03)
    leaf_on(m, (0.32, 1.85, 0.05), (0, 0, 25))
    return m


@group("ob_pear", S)
def pear():
    m = obj("pear", target=(0, 1.0, 0))
    m.add(sphere((0, 0.7, 0), 0.72), "#B5CF6B")
    m.add(sphere((0, 1.45, 0), 0.42), "#B5CF6B", k=0.45)
    m.add(capsule((0, 1.8, 0), (0.1, 2.15, 0), 0.05), STEM, k=0.03)
    leaf_on(m, (0.32, 2.05, 0.05), (0, 0, 30), 0.26)
    return m


@group("ob_banana", S)
def banana():
    m = obj("banana", target=(0, 0.75, 0), pitch=18)
    m.add(arc((0, 1.9, 0), 1.5, 0.3, 50, rot=(0, 0, 180)), "#F2D46B")
    m.add(capsule((-1.15, 1.0, 0), (-1.28, 1.15, 0), 0.08), "#7A5E3F", k=0.05)
    m.add(sphere((1.15, 0.95, 0), 0.1), "#7A5E3F", k=0.08)
    return m


@group("ob_orange", S)
def orange():
    m = obj("orange")
    m.add(sphere((0, 0.85, 0), 0.82), "#F0A15A")
    m.add(capsule((0, 1.62, 0), (0, 1.75, 0), 0.06), STEM, k=0.03)
    leaf_on(m, (0.28, 1.72, 0.0), (0, 0, 20), 0.28)
    return m


@group("ob_lemon", S)
def lemon():
    m = obj("lemon")
    m.add(ell((0, 0.7, 0), (0.95, 0.68, 0.68)), "#F3D96B")
    m.add(sphere((0.98, 0.7, 0), 0.12), "#EBCB55", k=0.15)
    m.add(sphere((-0.98, 0.7, 0), 0.12), "#EBCB55", k=0.15)
    return m


@group("ob_strawberry", S)
def strawberry():
    m = obj("strawberry", target=(0, 0.95, 0))
    m.add(cone((0, 0.35, 0), (0, 1.25, 0), 0.25, 0.72), "#E06B66")
    for i in range(14):  # seeds
        a = i * 2.4
        y = 0.45 + (i % 5) * 0.18
        r = 0.3 + (y - 0.35) * 0.45
        m.paint(sphere((r * math.sin(a), y, r * math.cos(a) + 0.02), 0.045), "#F7E3A0", soft=0.01)
    for i in range(5):
        a = i * 2 * math.pi / 5
        m.add(ell((0.3 * math.sin(a), 1.9, 0.3 * math.cos(a)), (0.3, 0.06, 0.12), rot=(0, math.degrees(a) + 90, -10)), LEAF, k=0.03)
    m.add(capsule((0, 1.85, 0), (0, 2.1, 0), 0.05), "#6EA35F", k=0.03)
    return m


@group("ob_cherry", S)
def cherry():
    m = obj("cherry", target=(0, 1.0, 0))
    m.add(sphere((-0.45, 0.45, 0), 0.45), "#D45F62", gloss=0.25)
    m.add(sphere((0.45, 0.42, 0.1), 0.45), "#D45F62", gloss=0.25)
    m.add(arc((-0.05, 1.0, 0), 0.6, 0.04, 40, rot=(0, 0, 25)), "#6E8F4F")
    m.add(capsule((-0.45, 0.85, 0), (0.0, 1.55, 0), 0.04), "#6E8F4F")
    m.add(capsule((0.45, 0.82, 0.1), (0.0, 1.55, 0), 0.04), "#6E8F4F")
    leaf_on(m, (0.25, 1.62, 0), (0, 0, 15), 0.28)
    return m


@group("ob_grapes", S)
def grapes():
    m = obj("grapes", target=(0, 1.0, 0))
    rows = [(1.55, [0]), (1.3, [-0.25, 0.25]), (1.05, [-0.45, 0, 0.45]), (0.8, [-0.25, 0.25]), (0.55, [0])]
    for y, xs in rows:
        for x in xs:
            m.add(sphere((x, y, 0.1 * (1 - abs(x))), 0.26), "#A58ACB", k=0.02)
    m.add(capsule((0, 1.75, 0), (0.05, 2.05, 0), 0.05), STEM)
    leaf_on(m, (0.3, 1.9, 0), (0, 0, 20), 0.3)
    return m


@group("ob_blueberry", S)
def blueberry():
    m = obj("blueberry", target=(0, 0.6, 0), distance=7)
    m.add(sphere((0, 0.55, 0), 0.55), "#6F86C4")
    m.cut(sphere((0, 1.08, 0.12), 0.14), "#4E5F98", k=0.08)
    return m


@group("ob_carrot", S)
def carrot():
    m = obj("carrot", target=(0, 1.1, 0))
    m.add(cone((0, 0.15, 0.0), (0, 1.6, 0), 0.07, 0.4), "#EE9750")
    for y in (0.6, 0.9, 1.2):
        m.add(torus((0, y, 0), 0.06 + 0.2 * (y - 0.15) / 1.45 + 0.07, 0.02, rot=(0, 0, 0)), "#E08840", k=0.02)
    for a in (-25, 0, 25):
        m.add(capsule((0, 1.9, 0), (math.sin(math.radians(a)) * 0.55, 2.55, 0), 0.08), LEAF, k=0.06)
    return m


@group("ob_broccoli", S)
def broccoli():
    m = obj("broccoli", target=(0, 1.1, 0))
    m.add(cone((0, 0.1, 0), (0, 1.1, 0), 0.32, 0.22), "#A9CF86")
    for x, y, r in [(0, 1.55, 0.5), (-0.5, 1.3, 0.4), (0.5, 1.3, 0.4), (-0.25, 1.85, 0.35), (0.3, 1.85, 0.35)]:
        m.add(sphere((x, y, 0.05), r), "#6FAA5E", k=0.1)
    return m


@group("ob_tomato", S)
def tomato():
    m = obj("tomato")
    m.add(ell((0, 0.75, 0), (0.85, 0.72, 0.85)), "#E2705F", gloss=0.2)
    for i in range(5):
        a = i * 2 * math.pi / 5
        m.add(ell((0.18 * math.sin(a), 1.45, 0.18 * math.cos(a)), (0.22, 0.05, 0.08), rot=(0, math.degrees(a) + 90, -15)), LEAF, k=0.02)
    return m


@group("ob_cupcake", S)
def cupcake():
    m = obj("cupcake", target=(0, 1.0, 0))
    m.add(cone((0, 0.1, 0), (0, 0.85, 0), 0.5, 0.72), "#F0B4C4")
    for i in range(12):
        a = i * 2 * math.pi / 12
        m.cut(capsule((0.6 * math.sin(a), 0.1, 0.6 * math.cos(a)), (0.78 * math.sin(a), 0.85, 0.78 * math.cos(a)), 0.05), "#E39DB1", k=0.02)
    m.add(ell((0, 1.1, 0), (0.85, 0.42, 0.85)), "#FBEFE3", k=0.08)
    m.add(ell((0, 1.45, 0), (0.55, 0.32, 0.55)), "#FBEFE3", k=0.12)
    m.add(sphere((0, 1.85, 0), 0.22), "#DE6A62", gloss=0.3)
    for i, c in enumerate(["#9FC6E8", "#F3D266", "#A98DCC", "#82C074", "#F0A15A"]):
        a = i * 1.3
        m.add(capsule((0.5 * math.sin(a), 1.25 + 0.05 * i, 0.5 * math.cos(a)), (0.5 * math.sin(a) + 0.07, 1.27 + 0.05 * i, 0.5 * math.cos(a)), 0.035), c)
    return m


@group("ob_cookie", S)
def cookie():
    m = obj("cookie", target=(0, 0.8, 0), pitch=8)
    m.add(cyl((0, 0.85, 0), 0.16, 0.85, 0.14, rot=(90, 0, 0)), "#D9A86C")
    for x, y in [(-0.35, 1.1), (0.3, 1.2), (0.05, 0.75), (-0.4, 0.55), (0.45, 0.6)]:
        m.add(sphere((x, y, 0.16), 0.1), "#6E4A3A", k=0.04)
    return m


@group("ob_egg", S)
def egg():
    m = obj("egg")
    m.add(ell((0, 0.85, 0), (0.6, 0.85, 0.6)), "#F6EBDA")
    m.add(sphere((0, 0.55, 0), 0.6), "#F6EBDA", k=0.4)
    return m


@group("ob_watermelon", S)
def watermelon():
    m = obj("watermelon", target=(0, 0.75, 0), pitch=6)
    slice_ = inter(cyl((0, 1.3, 0), 0.22, 1.2, 0.08, rot=(90, 0, 0)), plane((0, 1, 0), 1.3))
    m.add(raw(f"max({slice_.expr},-(p.y-0.0))"), "#7CB86E")
    m.paint(raw("(length(vec2(p.x,p.y-1.3))-1.05)"), "#F7EED8", soft=0.02)
    m.paint(raw("(length(vec2(p.x,p.y-1.3))-0.95)"), "#EE7A78", soft=0.02)
    for x, y in [(-0.45, 0.75), (0, 0.6), (0.45, 0.75), (-0.2, 0.95), (0.25, 0.95)]:
        m.add(ell((x, y, 0.21), (0.05, 0.08, 0.03)), "#3E3346")
    return m


@group("ob_icecream", S)
def icecream():
    m = obj("icecream", target=(0, 1.2, 0))
    m.add(cone((0, 0.12, 0), (0, 1.3, 0), 0.06, 0.48), "#E3B47A")
    m.paint(raw("abs(sin((p.x+p.y)*9.0))*0.06-0.025"), "#CF9A5E", soft=0.01)
    m.add(sphere((0, 1.7, 0), 0.55), "#F1ACC3", k=0.08)
    m.add(sphere((0, 2.2, 0), 0.4), "#F7EFD8", k=0.12)
    m.add(sphere((0.05, 2.62, 0.05), 0.12), "#DE6A62", gloss=0.3)
    return m


@group("ob_bread", S)
def bread():
    m = obj("bread", pitch=14)
    m.add(box((0, 0.55, 0), (0.85, 0.5, 0.6), 0.3), "#E3B07A")
    m.add(ell((0, 1.05, 0), (0.9, 0.35, 0.62)), "#D79A5E", k=0.15)
    for x in (-0.4, 0.0, 0.4):
        m.cut(capsule((x - 0.1, 1.35, -0.6), (x + 0.12, 1.35, 0.6), 0.05), "#F0D2A4", k=0.03)
    return m


@group("ob_cheese", S)
def cheese():
    m = obj("cheese", pitch=16)
    wedge = inter(box((0, 0.45, 0), (0.95, 0.45, 0.6), 0.08), plane((0.45, 1, 0), 0.75))
    m.add(wedge, "#F3D06A")
    for x, y, r in [(-0.4, 0.4, 0.14), (0.1, 0.6, 0.1), (0.5, 0.3, 0.12), (-0.1, 0.25, 0.08)]:
        m.cut(sphere((x, y, 0.6), r), "#E5BD52", k=0.03)
    return m


@group("ob_milk", S)
def milk():
    m = obj("milk", target=(0, 1.0, 0))
    glass = shell(cyl((0, 0.95, 0), 0.9, 0.55, 0.1), 0.04)
    m.add(inter(glass, plane((0, 1, 0), 1.75)), "#DCE8EE", gloss=0.6)
    m.add(cyl((0, 0.75, 0), 0.65, 0.5, 0.08), "#FBF8F1")
    return m


# ---------------------------------------------------------------- nature

@group("ob_flower", S)
def flower():
    m = obj("flower", target=(0, 1.3, 0))
    m.add(capsule((0, 0, 0), (0, 1.4, 0), 0.07), "#7CB06A")
    leaf_on(m, (0.32, 0.6, 0.05), (0, 0, 30), 0.3)
    for i in range(6):
        a = i * math.pi / 3
        m.add(ell((0.45 * math.cos(a), 1.75 + 0.45 * math.sin(a), 0), (0.32, 0.32, 0.12), rot=(0, 0, math.degrees(a))), "#F1ACC3", k=0.05)
    m.add(sphere((0, 1.75, 0.08), 0.28), "#F3D266", k=0.04)
    return m


@group("ob_leaf", S)
def leaf():
    m = obj("leaf", target=(0, 0.9, 0), ground=False)
    m.add(ell((0, 1.0, 0), (0.55, 0.95, 0.1), rot=(0, 0, -20)), LEAF)
    m.add(capsule((0.22, 0.2, 0.05), (-0.2, 1.75, 0.08), 0.03), "#5E9652", k=0.02)
    return m


@group("ob_mushroom", S)
def mushroom():
    m = obj("mushroom", target=(0, 1.0, 0))
    m.add(cyl((0, 0.5, 0), 0.5, 0.3, 0.12), "#F6EBDA")
    m.add(inter(ell((0, 1.05, 0), (0.95, 0.8, 0.95)), plane((0, -1, 0), -1.0)), "#DE6A62", k=0.05)
    for x, y, z in [(0, 1.8, 0.2), (-0.5, 1.45, 0.5), (0.55, 1.4, 0.5), (0.1, 1.35, 0.8)]:
        m.paint(sphere((x, y, z), 0.14), "#FBF4E6", soft=0.02)
    return m


@group("ob_sun", S)
def sun():
    m = obj("sun", target=(0, 1.0, 0), ground=False)
    m.add(sphere((0, 1.0, 0), 0.7), "#F3CD5E")
    for i in range(10):
        a = i * 2 * math.pi / 10
        m.add(cone((0.85 * math.cos(a), 1.0 + 0.85 * math.sin(a), 0), (1.2 * math.cos(a), 1.0 + 1.2 * math.sin(a), 0), 0.16, 0.06), "#F0B957", k=0.05)
    eyes(m, 0.22, 1.12, 0.62, 0.08, "happy")
    cheeks(m, 0.4, 0.92, 0.55, 0.1)
    smile(m, 0, 0.82, 0.68, 0.18, "open")
    return m


@group("ob_moon", S)
def moon():
    m = obj("moon", target=(0, 1.0, 0), ground=False)
    q = "(p-vec3(0.0,1.0,0.0))"
    crescent = f"max(length({q}.xy)-0.95,-(length({q}.xy-vec2(0.5,0.32))-0.78))"
    m.add(raw(f"extrude({crescent},{q}.z,0.3,0.24)"), "#F3E2B0")
    eyes(m, -0.48, 0.95, 0.3, 0.07, "sleep")
    cheeks(m, -0.55, 0.75, 0.28, 0.1)
    return m


@group("ob_cloud", S)
def cloud():
    m = obj("cloud", target=(0, 1.0, 0), ground=False)
    for x, y, r in [(-0.7, 0.9, 0.5), (0, 1.15, 0.7), (0.7, 0.9, 0.5), (0.3, 0.75, 0.45), (-0.3, 0.75, 0.45)]:
        m.add(sphere((x, y, 0), r), "#F7F6F2", k=0.15)
    return m


@group("ob_rainbow", S)
def rainbow():
    m = obj("rainbow", target=(0, 0.95, 0))
    cols = ["#E2705F", "#F0A15A", "#F3D266", "#82C074", "#73A6DB", "#A98DCC"]
    for i, c in enumerate(cols):
        m.add(arc((0, 0.0, 0), 1.6 - i * 0.2, 0.1, 90), c, k=0.0)
    for x in (-1.15, 1.15):
        for dx, r in ((-0.3, 0.32), (0.05, 0.4), (0.35, 0.3)):
            m.add(sphere((x + dx, 0.25, 0.1), r), "#F7F6F2", k=0.12)
    return m


@group("ob_star", S)
def star_obj():
    m = obj("star", target=(0, 1.0, 0), ground=False)
    m.add(star((0, 1.0, 0), 1.05, 0.5, 0.2, 0.16), "#F3CD5E")
    eyes(m, 0.2, 1.08, 0.33, 0.07, "open")
    cheeks(m, 0.38, 0.9, 0.32, 0.09)
    smile(m, 0, 0.85, 0.36, 0.15, "open")
    return m


@group("ob_heart", S)
def heart():
    m = obj("heart", target=(0, 1.0, 0), ground=False)
    m.add(flat((0, 1.0, 0), "heart", 1.0, 0.24, 0.2), "#E98A93")
    return m


@group("ob_tree", S)
def tree():
    m = obj("tree", target=(0, 1.5, 0), distance=14)
    m.add(cone((0, 0, 0), (0, 1.2, 0), 0.32, 0.22), "#A27A58")
    for x, y, r in [(0, 2.1, 0.85), (-0.65, 1.65, 0.6), (0.65, 1.65, 0.6), (0, 1.5, 0.6)]:
        m.add(sphere((x, y, 0), r), "#7DB46B", k=0.2)
    for x, y in [(-0.4, 2.2), (0.45, 1.9), (0.0, 1.6)]:
        m.add(sphere((x, y, 0.75), 0.11), "#DE6A62", gloss=0.2)
    return m


@group("ob_snail", S)
def snail():
    m = obj("snail", target=(0, 0.8, 0))
    m.add(capsule((-0.9, 0.22, 0), (0.9, 0.22, 0), 0.22), "#E9D3A8")
    m.add(sphere((1.0, 0.55, 0), 0.3), "#E9D3A8", k=0.2)
    m.add(capsule((1.05, 0.75, 0), (1.15, 1.15, 0.05), 0.04), "#E9D3A8", k=0.03)
    m.add(capsule((0.92, 0.75, 0), (0.88, 1.12, 0.05), 0.04), "#E9D3A8", k=0.03)
    m.add(sphere((1.15, 1.17, 0.05), 0.07), "#3E3346")
    m.add(sphere((0.88, 1.14, 0.05), 0.07), "#3E3346")
    m.add(sphere((-0.15, 0.85, 0), 0.68), "#D8A07A")
    for r in (0.5, 0.3):
        m.add(torus((-0.15, 0.85, 0.42), r, 0.05, rot=(90, 0, 0)), "#C38962", k=0.04)
    return m


@group("ob_butterfly", S)
def butterfly():
    m = obj("butterfly", target=(0, 1.0, 0), ground=False)
    m.add(capsule((0, 0.5, 0), (0, 1.5, 0), 0.12), "#6E5A6E")
    m.add(ell((0.55, 1.25, 0), (0.55, 0.45, 0.06), rot=(0, 0, 20), sym=True), "#A98DCC")
    m.add(ell((0.45, 0.65, 0), (0.38, 0.32, 0.06), rot=(0, 0, -25), sym=True), "#F1ACC3")
    m.paint(sphere((0.6, 1.3, 0.05), 0.15, sym=True), "#F3D266", soft=0.02)
    m.add(capsule((0.03, 1.55, 0), (0.25, 1.95, 0), 0.025, sym=True), "#6E5A6E")
    return m


# ---------------------------------------------------------------- toys & things

@group("ob_ball", S)
def ball():
    m = obj("ball")
    m.add(sphere((0, 0.85, 0), 0.85), "#F7F1E6")
    m.paint(raw("abs(p.x*0.8+p.y*0.25-0.2)-0.18"), "#73A6DB", soft=0.02)
    m.paint(raw("abs(p.x*0.8-p.y*0.3+0.55)-0.16"), "#E2705F", soft=0.02)
    m.paint(raw("abs(p.y-0.85+p.x*0.1)-0.14"), "#F3D266", soft=0.02)
    return m


def balloon(color):
    m = obj(f"balloon_{color}", target=(0, 1.3, 0), ground=False, distance=12)
    c = COLORS[color]
    m.add(ell((0, 1.65, 0), (0.75, 0.88, 0.75)), c, gloss=0.25)
    m.add(cone((0, 0.9, 0), (0, 0.72, 0), 0.12, 0.09), c, k=0.08)
    m.add(capsule((0, 0.68, 0), (0.06, 0.3, 0), 0.02), "#B8AFA6")
    m.add(capsule((0.06, 0.3, 0), (-0.04, -0.15, 0), 0.02), "#B8AFA6")
    return m


for _c in ["red", "orange", "yellow", "green", "blue", "purple", "pink", "brown", "white", "black"]:
    group(f"ob_balloon_{_c}", S)(lambda c=_c: balloon(c))


@group("ob_block", S)
def block():
    m = obj("block", pitch=20, yaw=25)
    m.add(box((0, 0.7, 0), (0.7, 0.7, 0.7), 0.12), "#F3D266")
    m.add(flat((0, 0.7, 0.72), "star", 0.32, 0.04, 0.03), "#E2705F")
    m.add(flat((0.72, 0.7, 0), "circle", 0.3, 0.04, 0.03, rot=(0, 90, 0)), "#73A6DB")
    m.add(flat((0, 1.42, 0), "heart", 0.3, 0.04, 0.03, rot=(-90, 0, 0)), "#82C074")
    return m


@group("ob_teddy", S)
def teddy():
    m = obj("teddy", target=(0, 1.25, 0), distance=13)
    fur, light = "#C99067", "#EED3B3"
    m.add(ell((0, 0.7, 0), (0.62, 0.68, 0.55)), fur)
    m.paint(ell((0, 0.7, 0.45), (0.38, 0.42, 0.25)), light, soft=0.05)
    m.add(sphere((0, 1.6, 0), 0.62), fur, k=0.1)
    m.add(sphere((0.48, 2.08, -0.05), 0.22, sym=True), fur, k=0.06)
    m.paint(sphere((0.5, 2.1, 0.12), 0.12, sym=True), light, soft=0.03)
    m.add(ell((0, 1.45, 0.52), (0.26, 0.2, 0.18)), light, k=0.08)
    m.add(sphere((0, 1.55, 0.68), 0.08), "#3E3346", gloss=0.5)
    eyes(m, 0.24, 1.75, 0.5, 0.08, "open")
    smile(m, 0, 1.36, 0.69, 0.14, "open")
    m.add(ell((0.6, 0.85, 0.15), (0.18, 0.3, 0.18), rot=(0, 0, 30), sym=True), fur, k=0.08)
    m.add(ell((0.32, 0.15, 0.25), (0.22, 0.16, 0.26), sym=True), fur, k=0.06)
    m.add(flat((0, 1.08, 0.5), "heart", 0.14, 0.04, 0.03), "#E98A93")
    return m


@group("ob_car", S)
def car():
    m = obj("car", target=(0, 0.75, 0), pitch=14, yaw=-25)
    m.add(box((0, 0.6, 0), (1.15, 0.32, 0.6), 0.25), "#E2705F")
    m.add(box((-0.1, 1.05, 0), (0.6, 0.32, 0.52), 0.25), "#E2705F", k=0.08)
    m.add(box((0.15, 1.05, 0), (0.32, 0.22, 0.54), 0.12), "#CFE3EE", gloss=0.6)
    m.add(box((-0.42, 1.05, 0), (0.18, 0.22, 0.54), 0.1), "#CFE3EE", gloss=0.6)
    for x in (-0.7, 0.7):
        m.add(cyl((x, 0.3, 0), 0.62, 0.3, 0.1, rot=(90, 0, 0)), "#4A4550")
        m.add(cyl((x, 0.3, 0), 0.64, 0.12, 0.04, rot=(90, 0, 0)), "#D8D2CB")
    m.add(sphere((1.12, 0.68, 0.38), 0.1), "#F3E2B0", emit=0.4)
    return m


@group("ob_boat", S)
def boat():
    m = obj("boat", target=(0, 1.0, 0), pitch=10)
    hull = inter(ell((0, 0.6, 0), (1.25, 0.6, 0.55)), plane((0, 1, 0), 0.7))
    m.add(hull, "#73A6DB")
    m.paint(raw("abs(p.y-0.55)-0.06"), "#F7F1E6", soft=0.01)
    m.add(capsule((0, 0.7, 0), (0, 2.1, 0), 0.05), "#AE7E5F")
    sail = inter(inter(box((0.35, 1.45, 0), (0.6, 0.7, 0.04), 0.03), plane((1, 0.85, 0), 1.2)), plane((-1, 0, 0), -0.08))
    m.add(sail, "#F8F4EC")
    return m


@group("ob_kite", S)
def kite():
    m = obj("kite", target=(0, 1.0, 0), ground=False)
    m.add(flat((0, 1.3, 0), "diamond", 0.75, 0.05, 0.04, rot=(0, 0, 0)), "#F1ACC3")
    m.paint(raw("max(-p.x,p.y-1.3)"), "#F3D266", soft=0.01)
    m.add(capsule((0, 0.45, 0), (0.2, -0.1, 0), 0.025), "#B8AFA6")
    for y, c in ((0.25, "#73A6DB"), (0.0, "#82C074")):
        m.add(flat((0.12 + y * -0.3, y + 0.05, 0.02), "triangle", 0.1, 0.03, 0.02, rot=(0, 0, 90)), c)
    return m


@group("ob_drum", S)
def drum():
    m = obj("drum", pitch=18)
    m.add(cyl((0, 0.6, 0), 0.58, 0.8, 0.08), "#E2705F")
    m.add(cyl((0, 1.18, 0), 0.04, 0.82, 0.03), "#F7F1E6", k=0.02)
    for y in (0.1, 1.12):
        m.add(torus((0, y, 0), 0.82, 0.07), "#F3D266", k=0.02)
    for i in range(6):
        a = i * math.pi / 3
        m.add(capsule((0.84 * math.sin(a), 0.15, 0.84 * math.cos(a)), (0.84 * math.sin(a + 0.5), 1.07, 0.84 * math.cos(a + 0.5)), 0.03), "#F7F1E6")
    m.add(capsule((0.3, 1.3, 0.2), (1.0, 1.9, 0.4), 0.05), "#AE7E5F")
    m.add(sphere((1.0, 1.9, 0.4), 0.12), "#F7F1E6")
    return m


@group("ob_rocket", S)
def rocket():
    m = obj("rocket", target=(0, 1.3, 0), distance=13)
    m.add(ell((0, 1.3, 0), (0.5, 1.1, 0.5)), "#F7F1E6")
    m.add(inter(ell((0, 1.3, 0), (0.51, 1.11, 0.51)), plane((0, -1, 0), -1.95)), "#E2705F")
    m.add(cyl((0, 1.4, 0.46), 0.04, 0.2, 0.03, rot=(90, 0, 0)), "#73A6DB", gloss=0.6)
    m.add(torus((0, 1.4, 0.47), 0.21, 0.04, rot=(90, 0, 0)), "#F3D266")
    m.add(ell((0.55, 0.45, 0), (0.25, 0.45, 0.1), rot=(0, 0, 25), sym=True), "#E2705F", k=0.05)
    m.add(cone((0, 0.25, 0), (0, -0.05, 0), 0.3, 0.2), "#B8AFA6", k=0.04)
    return m


@group("ob_duck", S)
def duck():
    m = obj("duck", target=(0, 1.0, 0))
    y = "#F3D266"
    m.add(ell((0, 0.5, 0), (0.85, 0.5, 0.6)), y)
    m.add(cone((-0.6, 0.6, 0), (-1.0, 0.95, 0), 0.25, 0.08), y, k=0.15)
    m.add(sphere((0.35, 1.3, 0), 0.48), y, k=0.15)
    m.add(ell((0.85, 1.22, 0), (0.28, 0.1, 0.2)), "#F0A15A", k=0.04)
    eyes(m, 0.18, 1.45, 0.38, 0.07, "open")
    m.add(ell((-0.05, 0.62, 0.48), (0.4, 0.22, 0.12), rot=(0, 0, -10)), "#EBC555", k=0.06)
    return m


@group("ob_fish", S)
def fish():
    m = obj("fish", target=(0, 1.0, 0), ground=False)
    m.add(ell((0, 1.0, 0), (0.85, 0.55, 0.32)), "#F0A15A")
    m.add(cone((-0.75, 1.0, 0), (-1.3, 1.0, 0), 0.1, 0.32), "#EE8E4E", k=0.1)
    m.paint(raw("abs(p.x-0.05)-0.09"), "#F8F4EC", soft=0.02)
    eyes(m, 0.45, 1.12, 0.26, 0.08, "open")
    m.ops.pop(); m.ops.pop(); m.ops.pop()  # one eye only: it's in profile
    m.add(ell((0.5, 1.12, 0.26), (0.08, 0.09, 0.05)), "#3E3346", gloss=0.85)
    m.add(sphere((0.47, 1.16, 0.3), 0.025), "#FFFDF8", emit=1)
    m.add(arc((0.72, 0.98, 0.2), 0.08, 0.02, 60, rot=(0, 0, 180)), "#3E3346")
    return m


@group("ob_cup", S)
def cup():
    m = obj("cup", pitch=16)
    body = inter(shell(cyl((0, 0.6, 0), 0.6, 0.55, 0.12), 0.06), plane((0, 1, 0), 1.15))
    m.add(body, "#73A6DB")
    m.add(torus((0.62, 0.62, 0), 0.25, 0.06, rot=(90, 0, 0)), "#73A6DB", k=0.03)
    m.add(cyl((0, 0.5, 0), 0.5, 0.5, 0.05), "#9FC6E8")
    return m


@group("ob_book", S)
def book():
    m = obj("book", pitch=18, yaw=-20)
    m.add(box((0, 0.22, 0), (0.85, 0.22, 0.65), 0.06), "#F6EBDA")
    m.add(box((0, 0.46, 0), (0.88, 0.05, 0.68), 0.04), "#82C074")
    m.add(box((0, 0.0, 0), (0.88, 0.05, 0.68), 0.04), "#82C074")
    m.add(box((-0.86, 0.23, 0), (0.05, 0.26, 0.68), 0.04), "#82C074")
    m.add(flat((0.0, 0.52, 0), "star", 0.22, 0.02, 0.015, rot=(-90, 0, 0)), "#F3D266")
    return m


@group("ob_hat", S)
def hat():
    m = obj("hat", pitch=16)
    m.add(cyl((0, 0.08, 0), 0.06, 1.05, 0.05), "#73A6DB")
    m.add(ell((0, 0.4, 0), (0.62, 0.6, 0.62)), "#73A6DB", k=0.1)
    m.add(torus((0, 0.3, 0), 0.6, 0.08), "#F3D266", k=0.02)
    return m


@group("ob_umbrella", S)
def umbrella():
    m = obj("umbrella", target=(0, 1.3, 0), distance=13)
    canopy = inter(shell(sphere((0, 1.4, 0), 1.2), 0.03), plane((0, -1, 0), -1.75))
    m.add(canopy, "#E2705F")
    m.paint(raw("abs(sin(atan(p.x,p.z)*4.0))-0.5"), "#F7F1E6", soft=0.04)
    m.add(capsule((0, 2.65, 0), (0, 0.3, 0), 0.05), "#AE7E5F")
    m.add(arc((0.22, 0.3, 0), 0.22, 0.05, 90, rot=(0, 0, 180)), "#AE7E5F")
    return m


@group("ob_bell", S)
def bell():
    m = obj("bell", target=(0, 1.0, 0))
    b = inter(shell(uni(sphere((0, 1.25, 0), 0.6), cone((0, 1.2, 0), (0, 0.35, 0), 0.6, 0.85), 0.2), 0.06), plane((0, -1, 0), -0.3))
    m.add(b, "#F0C35A", gloss=0.45)
    m.add(sphere((0, 0.32, 0), 0.18), "#D9A93F", gloss=0.4)
    m.add(torus((0, 1.95, 0), 0.15, 0.05, rot=(90, 0, 0)), "#D9A93F")
    return m


@group("ob_gift", S)
def gift():
    m = obj("gift", pitch=20, yaw=20)
    m.add(box((0, 0.6, 0), (0.7, 0.6, 0.7), 0.08), "#A98DCC")
    m.paint(raw("abs(p.x)-0.12"), "#F3D266", soft=0.01)
    m.paint(raw("abs(p.z)-0.12"), "#F3D266", soft=0.01)
    m.add(torus((0.25, 1.32, 0), 0.22, 0.07, rot=(90, 30, 0), sym=True), "#F3D266")
    return m


@group("ob_crown", S)
def crown():
    m = obj("crown", pitch=12)
    ring = inter(shell(cyl((0, 0.5, 0), 0.45, 0.8, 0.05), 0.06), plane((0, 1, 0), 0.95))
    m.add(ring, "#F0C35A", gloss=0.45)
    for i in range(5):
        a = i * 2 * math.pi / 5
        m.add(cone((0.78 * math.sin(a), 0.85, 0.78 * math.cos(a)), (0.82 * math.sin(a), 1.35, 0.82 * math.cos(a)), 0.13, 0.07), "#F0C35A", k=0.04, gloss=0.45)
    m.add(sphere((0, 0.55, 0.84), 0.12), "#E2705F", gloss=0.6)
    return m


@group("ob_sock", S)
def sock():
    m = obj("sock")
    m.add(capsule((-0.2, 1.5, 0), (-0.2, 0.45, 0), 0.38), "#73A6DB")
    m.add(capsule((-0.2, 0.45, 0), (0.6, 0.38, 0), 0.36), "#73A6DB", k=0.15)
    m.paint(raw("abs(p.y-1.2)-0.12"), "#F7F1E6", soft=0.02)
    m.paint(sphere((0.85, 0.4, 0), 0.32), "#E2705F", soft=0.04)
    return m


@group("ob_toothbrush", S)
def toothbrush():
    m = obj("toothbrush", target=(0, 1.0, 0), pitch=14, yaw=-20)
    m.add(capsule((-1.3, 0.25, 0), (0.9, 0.35, 0), 0.13), "#82C074")
    m.add(box((0.85, 0.55, 0), (0.42, 0.2, 0.14), 0.08), "#F7F8FA", k=0.02)
    m.paint(raw("abs(sin(p.x*22.0))-0.4"), "#9FC6E8", soft=0.05)
    return m


@group("ob_soap", S)
def soap():
    m = obj("soap", pitch=20)
    m.add(box((0, 0.35, 0), (0.85, 0.35, 0.55), 0.3), "#F1ACC3")
    for x, y, r in [(-0.4, 0.95, 0.22), (0.1, 1.1, 0.3), (0.55, 0.9, 0.18), (0.3, 1.45, 0.15)]:
        m.add(sphere((x, y, 0.1), r), "#E6F0F5", gloss=0.7)
    return m
