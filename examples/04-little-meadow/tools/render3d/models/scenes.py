"""
Full-screen backdrops, bg_<name>.webp at 1280×800. Clay dioramas seen from a child's height,
busy only at the edges and bottom: the middle stays calm for the activity on top. A light haze
keeps them pale so the foreground (friends, cards) always reads first.
"""
from __future__ import annotations

import math
import random

from models import group
from sdf import Model, arc, box, capsule, cone, cyl, ell, flat, inter, plane, raw, sphere, star, torus

BG = (1280, 800)


def scene(name, sky=("#C8E0EA", "#EEEBDD"), haze=0.08, target=(0, 2.6, 0), distance=22.0, pitch=4.0, fov=38.0):
    return Model(f"bg_{name}", scene=True, ground=False, sky=sky, haze=haze, target=target, distance=distance, pitch=pitch, fov=fov)


def floor(m, color, y=0.0):
    m.add(raw(f"(p.y-{y:.3f})"), color)


def hill(m, x, z, rx, ry, rz, color):
    m.add(ell((x, 0, z), (rx, ry, rz)), color, k=0.6)


def tree(m, x, z, s=1.0, canopy="#86BB76", trunk="#A98262", fruit=None):
    m.add(cone((x, 0, z), (x, 1.6 * s, z), 0.35 * s, 0.22 * s), trunk, k=0.1)
    for dx, dy, r in [(0, 2.6, 1.1), (-0.8, 2.0, 0.8), (0.85, 2.05, 0.8), (0.1, 3.3, 0.75)]:
        m.add(sphere((x + dx * s, dy * s, z), r * s), canopy, k=0.35 * s)
    if fruit:
        for dx, dy in [(-0.5, 2.3), (0.6, 2.7), (0.1, 1.9)]:
            m.add(sphere((x + dx * s, dy * s, z + 0.95 * s), 0.13 * s), fruit)


def flower(m, x, z, color, s=1.0):
    m.add(capsule((x, 0, z), (x, 0.55 * s, z), 0.04 * s), "#7CB06A")
    for i in range(5):
        a = i * 2 * math.pi / 5
        m.add(sphere((x + 0.15 * s * math.cos(a), 0.62 * s + 0.15 * s * math.sin(a), z), 0.11 * s), color, k=0.04)
    m.add(sphere((x, 0.62 * s, z + 0.05), 0.08 * s), "#F3D266")


def cloud(m, x, y, z, s=1.0):
    for dx, dy, r in [(-1.0, 0, 0.8), (0, 0.35, 1.1), (1.0, 0, 0.8), (0.5, -0.2, 0.7), (-0.5, -0.2, 0.7)]:
        m.add(sphere((x + dx * s, y + dy * s, z), r * s), "#FBFAF6", k=0.4 * s)


def meadow_base(m, near="#A9CE96", far="#C2DCAF", farther="#D3E5C3"):
    floor(m, near)
    hill(m, -14, -30, 22, 6, 10, farther)
    hill(m, 16, -34, 26, 8, 12, farther)
    hill(m, -9, -16, 14, 3.2, 8, far)
    hill(m, 12, -18, 15, 3.6, 8, far)


@group("bg_meadow", BG)
def meadow():
    m = scene("meadow")
    meadow_base(m)
    tree(m, -15, -10, 1.3)
    tree(m, 16, -12, 1.5, fruit="#E2705F")
    tree(m, 9, -26, 1.0, "#9CC88A")
    cloud(m, -9, 11, -30, 1.6)
    cloud(m, 12, 13, -36, 2.0)
    r = random.Random(3)
    for _ in range(26):
        x = r.choice([-1, 1]) * r.uniform(5, 15)
        z = r.uniform(-6, 5)
        flower(m, x, z, r.choice(["#F1ACC3", "#F3D266", "#FBF4E6", "#A98DCC", "#F0A15A"]), r.uniform(0.8, 1.3))
    return m


@group("bg_nest", BG)
def nest():
    m = scene("nest", sky=("#D9E8E6", "#F6EEDC"))
    meadow_base(m, "#B5D29A", "#CADFB4", "#DCE8C9")
    # Pip's tree on the right with a nest on a low branch
    m.add(cone((16.5, 0, -9), (16, 7, -9), 1.1, 0.7), "#A98262", k=0.3)
    m.add(capsule((16, 4.6, -9), (12.2, 6.2, -8.5), 0.38), "#A98262", k=0.3)
    for dx, dy, r in [(0, 9.0, 2.8), (-3, 8.2, 2.1), (2.6, 7.6, 2.2)]:
        m.add(sphere((16 + dx, dy, -10), r), "#8FC07E", k=0.8)
    m.add(torus((12.4, 6.65, -8.5), 0.95, 0.38), "#C99A6B", k=0.15)
    for i in range(3):
        m.add(ell((12.0 + i * 0.4, 6.85, -8.3 + (i % 2) * 0.3), (0.28, 0.36, 0.28)), "#F6EBDA", k=0.02)
    tree(m, -16, -14, 1.2)
    cloud(m, -6, 12, -34, 1.7)
    r = random.Random(5)
    for _ in range(18):
        flower(m, r.choice([-1, 1]) * r.uniform(6, 15), r.uniform(-5, 4), r.choice(["#F3D266", "#FBF4E6", "#F0A15A"]), r.uniform(0.8, 1.2))
    return m


def room(m, wall, floor_color, back=-9.0):
    floor(m, floor_color)
    m.add(raw(f"({back:.2f}-p.z)*-1.0"), wall)  # back wall: everything behind z = back is solid
    m.add(box((0, 0.3, back + 0.2), (40, 0.3, 0.25), 0.1), "#FBF6EE", k=0.05)  # skirting


@group("bg_studio", BG)
def studio():
    m = scene("studio", sky=("#F5E9DA", "#F5E9DA"), haze=0.06)
    room(m, "#F2E3D1", "#E4CBA9")
    # round window with sky
    m.add(cyl((-6, 7.5, -8.9), 0.25, 2.6, 0.2, rot=(90, 0, 0)), "#FBF6EE", k=0.05)
    m.add(cyl((-6, 7.5, -8.75), 0.2, 2.2, 0.1, rot=(90, 0, 0)), "#BFDCE8", emit=0.5)
    # rug
    m.add(cyl((0, 0.05, -1.5), 0.06, 7.5, 0.05), "#EBC2B4")
    m.add(torus((0, 0.08, -1.5), 6.6, 0.12), "#F3D9CC")
    # easel on the left, paint pots on the right
    m.add(capsule((-15, 0, -4), (-14, 8, -5), 0.22), "#B98E68")
    m.add(capsule((-11, 0, -4), (-12, 8, -5), 0.22), "#B98E68")
    m.add(box((-13, 5.5, -4.3), (2.6, 2.0, 0.15), 0.1, rot=(-6, 0, 0)), "#FBF8F2")
    for x, y, c in [(-13.6, 6.1, "#73A6DB"), (-12.4, 5.4, "#F3D266"), (-13.2, 4.8, "#E2705F")]:
        m.add(sphere((x, y, -4.05), 0.45), c)
    for i, c in enumerate(["#E2705F", "#F3D266", "#73A6DB", "#82C074", "#A98DCC"]):
        x = 10 + i * 1.3
        m.add(cyl((x, 0.55, -3 - (i % 2)), 0.55, 0.55, 0.12), "#FBF6EE")
        m.add(cyl((x, 1.05, -3 - (i % 2)), 0.08, 0.47, 0.05), c)
    # shelf with toys
    m.add(box((10, 6.8, -8.5), (4.5, 0.15, 0.6), 0.08), "#C99A6B")
    m.add(sphere((8, 7.5, -8.3), 0.6), "#73A6DB")
    m.add(box((10.3, 7.55, -8.3), (0.65, 0.65, 0.5), 0.12), "#F3D266")
    m.add(cone((12.5, 6.95, -8.3), (12.5, 8.4, -8.3), 0.6, 0.05), "#82C074")
    return m


@group("bg_room", BG)
def word_room():
    m = scene("room", sky=("#E6EDF1", "#E6EDF1"), haze=0.06)
    room(m, "#E3EAF0", "#E8D6BE")
    # bookshelf on the right
    m.add(box((14, 4.5, -8.3), (3.2, 4.5, 0.6), 0.15), "#C99A6B")
    r = random.Random(2)
    for row, y in enumerate((1.4, 4.4, 7.4)):
        x = 11.3
        while x < 16.4:
            w = r.uniform(0.35, 0.6)
            h = r.uniform(1.4, 2.2)
            m.add(box((x + w / 2, y + h / 2 - 0.9, -7.7), (w / 2 - 0.04, h / 2, 0.45), 0.08),
                  r.choice(["#E2705F", "#73A6DB", "#F3D266", "#82C074", "#A98DCC", "#F1ACC3"]))
            x += w
    # big letter blocks on the floor, left
    for i, (x, z, c) in enumerate([(-14, -2, "#F3D266"), (-12, -3.5, "#73A6DB"), (-13, -1.2, "#E2705F")]):
        y = 0.9 if i < 2 else 2.7
        m.add(box((x, y, z), (0.9, 0.9, 0.9), 0.15, rot=(0, 15 * i, 0)), c)
    # a lamp and a window
    m.add(box((-4, 8.2, -8.9), (2.4, 2.0, 0.2), 0.3), "#FBF6EE")
    m.add(box((-4, 8.2, -8.75), (2.0, 1.6, 0.1), 0.2), "#C9E1EA", emit=0.4)
    cloud_small = [(-5, 8.6), (-3.2, 7.8)]
    for x, y in cloud_small:
        m.add(sphere((x, y, -8.6), 0.35), "#FBFAF6")
    return m


@group("bg_music", BG)
def music_room():
    m = scene("music", sky=("#E3F0EA", "#E3F0EA"), haze=0.06)
    room(m, "#DDEDE5", "#E5D2B6")
    # a little stage
    m.add(cyl((0, 0.25, -6), 0.25, 9, 0.2), "#E8C6A8")
    # notes on the wall
    for x, y, c in [(-9, 9, "#A98DCC"), (-6, 10.5, "#73A6DB"), (6, 10, "#F1ACC3"), (9.5, 8.6, "#F3D266")]:
        m.add(sphere((x, y, -8.6), 0.5), c)
        m.add(capsule((x + 0.42, y, -8.6), (x + 0.42, y + 2.0, -8.6), 0.1), c)
    # drum left, maracas right
    m.add(cyl((-14, 1.2, -3), 1.2, 1.7, 0.15), "#E2705F")
    m.add(cyl((-14, 2.45, -3), 0.06, 1.72, 0.05), "#FBF6EE")
    m.add(sphere((14, 1.0, -3), 0.9), "#F3D266")
    m.add(capsule((14, 1.5, -3), (14.6, 3.2, -3), 0.15), "#B98E68")
    m.add(sphere((15.8, 0.8, -4), 0.8), "#82C074")
    return m


@group("bg_garden", BG)
def garden():
    m = scene("garden", sky=("#D7EAE4", "#F1EFE1"))
    meadow_base(m, "#AFD199", "#C5DDAF", "#D6E7C6")
    # vegetable beds at the edges
    for side in (-1, 1):
        x0 = side * 12.5
        m.add(box((x0, 0.35, -3), (3.6, 0.35, 2.2), 0.2), "#B88B66")
        for i in range(4):
            x = x0 - 2.6 + i * 1.75
            m.add(sphere((x, 0.95, -3), 0.55), "#7DB46B", k=0.1)
            if side < 0:
                m.add(cone((x, 0.7, -2.0), (x, 0.0, -1.95), 0.25, 0.05), "#EE9750")
            else:
                m.add(sphere((x, 1.15, -2.3), 0.32), "#E2705F")
    # fence far back
    for i in range(-14, 15, 2):
        m.add(box((i, 1.4, -12), (0.25, 1.4, 0.12), 0.12), "#F4EDE1")
    m.add(box((0, 1.9, -12.1), (15, 0.15, 0.08), 0.06), "#F4EDE1")
    # pond in the middle distance
    m.add(cyl((-3, 0.0, -9), 0.08, 3.2, 0.06, rot=(0, 0, 0)), "#A9CFE0", gloss=0.5)
    tree(m, 17, -9, 1.4, fruit="#F0A15A")
    cloud(m, 8, 12, -34, 1.6)
    return m


@group("bg_path", BG)
def path():
    m = scene("path", sky=("#EEDFE3", "#F5EEE4"))
    meadow_base(m, "#B6D3A0", "#CCE0B8", "#DCE9CC")
    # stepping stones curving into the distance
    for i in range(12):
        t = i / 11
        x = 6 * math.sin(t * 3.0) - 1
        z = 4 - t * 30
        m.add(ell((x, 0.05, z), (1.5 - t * 0.6, 0.18, 1.0 - t * 0.4)), "#EADFD0", k=0.1)
    # balloons and blocks at the sides
    m.add(box((-14, 0.9, -2), (0.9, 0.9, 0.9), 0.15, rot=(0, 20, 0)), "#F1ACC3")
    m.add(box((-12.2, 0.9, -3), (0.9, 0.9, 0.9), 0.15, rot=(0, -10, 0)), "#73A6DB")
    m.add(box((-13.2, 2.7, -2.6), (0.9, 0.9, 0.9), 0.15, rot=(0, 5, 0)), "#F3D266")
    for x, y, c in [(13, 9, "#E2705F"), (15, 10, "#A98DCC"), (14, 7.5, "#F3D266")]:
        m.add(ell((x, y, -5), (0.9, 1.1, 0.9)), c)
    tree(m, 16, -14, 1.3)
    cloud(m, -10, 12, -34, 1.6)
    return m


@group("bg_night", BG)
def night():
    m = scene("night", sky=("#2E3159", "#5B5E8F"), haze=0.0)
    floor(m, "#4C5A6E")
    hill(m, -12, -22, 20, 6, 10, "#56627C")
    hill(m, 14, -26, 24, 7, 10, "#5A6683")
    hill(m, 0, -14, 12, 2.5, 6, "#516078")
    # moon and stars (they glow softly)
    q = "(p-vec3(11.0,13.0,-30.0))"
    crescent = f"max(length({q}.xy)-2.4,-(length({q}.xy-vec2(1.2,0.8))-2.0))"
    m.add(raw(f"extrude({crescent},{q}.z,0.6,0.5)"), "#F3E7C9", emit=0.85)
    r = random.Random(11)
    for _ in range(34):
        x, y = r.uniform(-30, 30), r.uniform(8, 20)
        if abs(x - 11) < 4 and abs(y - 13) < 4:
            continue
        m.add(star((x, y, -32), r.uniform(0.25, 0.5), 0.5, 0.1, 0.08), "#F7EFD6", emit=0.9)
    # the quiet tree with lanterns, left
    m.add(cone((-14, 0, -6), (-14.3, 8, -6), 1.2, 0.7), "#6E5F6E", k=0.3)
    for dx, dy, r_ in [(0, 10, 3.2), (-3, 9, 2.4), (3.2, 8.6, 2.4)]:
        m.add(sphere((-14 + dx, dy, -7), r_), "#5E6F86", k=0.8)
    for x, y in [(-11.5, 7.6), (-16.5, 7.0), (-13.5, 6.6)]:
        m.add(capsule((x, y + 0.9, -4.5), (x, y + 0.4, -4.5), 0.03), "#B9B3C9")
        m.add(sphere((x, y, -4.5), 0.42), "#F3D9A0", emit=0.9)
    return m
