"""
Models for Moss's Garden (habits): routine pictures, the brushing mouth, hands, healthy-plate foods,
the tidy-up room and Tuka's weather wardrobe. Sprite names start with hb_ (backdrops bg_hb_).
"""
from __future__ import annotations

import math
import random

from models import group
from models.characters import tuka
from models.objects import COLORS, LEAF, STEM
from sdf import (Model, arc, box, capsule, cheeks, cone, cyl, ell, eyes, flat, inter, plane, raw, shell,
                 smile, sphere, star, sub, torus, uni)

S = (320, 320)
CREAM = "#FBF6EE"
WOOD = "#C99A6B"
WOOD_DARK = "#A9785A"
WATER = "#A9D3E6"
INKY = "#3E3346"


def hb(name, target=(0, 0.9, 0), distance=11.0, ground=True, pitch=14.0, fov=20.0, yaw=0.0, reach=1.6):
    return Model(f"hb_{name}", target=target, distance=distance, ground=ground, pitch=pitch, fov=fov, yaw=yaw, reach=reach)


def step(name, **kw):
    return hb(f"step_{name}", **kw)


# ================================================================ routine pictures (hb_step_<id>)

@group("hb_step_wake", S)
def step_wake():
    m = step("wake", target=(0, 1.15, 0), distance=12.5)
    body = "#9FD3C1"
    # a round alarm clock with two bells and little feet
    m.add(cyl((0, 1.1, 0), 0.32, 0.85, 0.22, rot=(90, 0, 0)), body)
    m.add(cyl((0, 1.1, 0.2), 0.16, 0.68, 0.1, rot=(90, 0, 0)), CREAM, k=0.02)
    for s in (-1, 1):
        m.add(sphere((0.55 * s, 1.98, -0.05), 0.3), "#F3D266", gloss=0.4)
        m.add(capsule((0.4 * s, 1.75, 0), (0.2 * s, 1.95, 0), 0.06), "#B8AFA6")
        m.add(capsule((0.45 * s, 0.4, 0), (0.6 * s, 0.1, 0.05), 0.09), body, k=0.03)
    m.add(capsule((-0.25, 2.12, -0.05), (0.25, 2.12, -0.05), 0.06), "#B8AFA6")
    m.add(sphere((0, 2.18, -0.05), 0.1), "#F3D266")
    # hands at seven o'clock
    m.add(capsule((0, 1.1, 0.4), (0, 1.5, 0.4), 0.045), INKY)
    m.add(capsule((0, 1.1, 0.4), (-0.25, 0.85, 0.4), 0.045), INKY)
    m.add(sphere((0, 1.1, 0.42), 0.07), "#E2705F")
    for i in range(12):
        a = i * math.pi / 6
        m.add(sphere((0.55 * math.sin(a), 1.1 + 0.55 * math.cos(a), 0.36), 0.035), "#B8AFA6")
    # a sleepy-happy little sun peeking from behind
    m.add(sphere((1.05, 1.95, -0.6), 0.42), "#F3CD5E")
    for i in range(7):
        a = i * 2 * math.pi / 7 + 0.3
        m.add(cone((1.05 + 0.5 * math.cos(a), 1.95 + 0.5 * math.sin(a), -0.6), (1.05 + 0.72 * math.cos(a), 1.95 + 0.72 * math.sin(a), -0.6), 0.1, 0.04), "#F0B957", k=0.04)
    return m


@group("hb_step_toilet", S)
def step_toilet():
    m = step("toilet", target=(0, 1.0, 0), distance=12, yaw=-22, pitch=16)
    white, seat = "#F7F4EF", "#9FC6E8"
    m.add(box((0, 1.55, -0.55), (0.62, 0.42, 0.22), 0.16), white)                 # tank
    m.add(capsule((0.35, 1.98, -0.55), (0.35, 2.05, -0.55), 0.07), "#D8D2CB")     # flush button
    m.add(cone((0, 0.05, 0.05), (0, 0.72, 0.1), 0.38, 0.5), white, k=0.1)        # pedestal
    bowl = ell((0, 0.85, 0.2), (0.62, 0.22, 0.68))
    m.add(bowl, white, k=0.08)
    m.add(inter(shell(ell((0, 0.98, 0.2), (0.62, 0.12, 0.66)), 0.05), plane((0, 1, 0), 1.06)), seat, k=0.02)
    m.cut(ell((0, 1.04, 0.22), (0.42, 0.12, 0.46)), "#DCE8EE", k=0.04)
    m.add(ell((0, 1.42, -0.32), (0.6, 0.62, 0.07), rot=(-12, 0, 0)), seat)     # lid up
    # a roll of paper on the side
    m.add(cyl((1.05, 0.35, 0.3), 0.32, 0.28, 0.08), "#FBF8F2")
    m.add(cyl((1.05, 0.38, 0.3), 0.32, 0.09, 0.03), "#D8D2CB")
    # a little step stool
    m.add(box((-0.05, 0.12, 1.05), (0.55, 0.12, 0.3), 0.08), "#F3D266")
    return m


def tap_on(m, x, y, z, color="#D8D2CB", s=1.0):
    """A little rounded tap pointing at +z."""
    m.add(cyl((x, y, z), 0.25 * s, 0.11 * s, 0.05 * s), color, gloss=0.5)
    m.add(capsule((x, y + 0.22 * s, z), (x, y + 0.3 * s, z + 0.35 * s), 0.08 * s), color, k=0.05, gloss=0.5)
    m.add(capsule((x, y + 0.3 * s, z + 0.35 * s), (x, y + 0.15 * s, z + 0.45 * s), 0.08 * s), color, k=0.05, gloss=0.5)
    m.add(sphere((x, y + 0.42 * s, z - 0.02), 0.11 * s), "#9FC6E8", gloss=0.3)


@group("hb_step_wash", S)
def step_wash():
    m = step("wash", target=(0, 0.95, 0), distance=12, pitch=20)
    basin = inter(shell(ell((0, 0.85, 0), (1.15, 0.55, 0.8)), 0.08), plane((0, 1, 0), 0.95))
    m.add(cone((0, 0.0, 0), (0, 0.6, 0), 0.35, 0.28), "#F7F4EF")
    m.add(basin, "#F7F4EF", k=0.05)
    m.add(cyl((0, 0.62, 0), 0.03, 0.75, 0.02), WATER, gloss=0.6)
    tap_on(m, 0, 0.95, -0.62)
    m.add(ell((0, 0.92, -0.05), (0.07, 0.12, 0.07)), WATER, gloss=0.7)
    m.add(sphere((0, 0.78, -0.08), 0.06), WATER, gloss=0.7)
    # soap on the rim with bubbles
    m.add(box((0.85, 1.05, 0.1), (0.28, 0.12, 0.18), 0.1), "#F1ACC3")
    for x, y, r in [(0.75, 1.3, 0.1), (0.95, 1.36, 0.13), (0.85, 1.55, 0.08)]:
        m.add(sphere((x, y, 0.15), r), "#E6F0F5", gloss=0.7)
    return m


def tshirt(m, x, y, z, color, s=1.0, rot=(0, 0, 0), th=0.12):
    q = f"(p-vec3({x:.3f},{y:.3f},{z:.3f}))/{s:.3f}"
    body = f"sdBox2(({q}).xy-vec2(0.0,-0.1),vec2(0.5,0.6))"
    sleeves = f"sdBox2(mat2(0.8,-0.6,0.6,0.8)*(vec2(abs(({q}).x),({q}).y)-vec2(0.62,0.32)),vec2(0.28,0.2))"
    neck = f"(length(({q}).xy-vec2(0.0,0.55))-0.2)"
    d2 = f"max(min({body},{sleeves})-0.08,-{neck})"
    m.add(raw(f"extrude({d2},({q}).z,{th:.3f},0.09)*{s:.3f}"), color)


@group("hb_step_dress", S)
def step_dress():
    m = step("dress", target=(0, 1.0, 0), distance=12, pitch=18)
    # folded trousers, a t-shirt with a star on top, socks beside
    m.add(box((0, 0.2, 0), (0.85, 0.2, 0.6), 0.16), "#73A6DB")
    m.paint(raw("abs(p.x)-0.03"), "#5F8FC4", soft=0.01)
    tshirt(m, 0, 0.95, 0.15, "#F3D266", 0.85, th=0.1)
    m.add(flat((0, 0.9, 0.26), "star", 0.2, 0.03, 0.02), "#E2705F")
    m.add(capsule((1.15, 0.12, 0.45), (1.15, 0.12, -0.25), 0.13), "#E2705F")
    m.add(capsule((1.15, 0.12, 0.45), (1.45, 0.12, 0.55), 0.13), "#E2705F", k=0.06)
    m.paint(raw("abs(p.z-0.0)-0.06"), "#F7F1E6", soft=0.01)
    return m


def spoon(m, a, b, color="#D8D2CB"):
    m.add(capsule(a, b, 0.05), color, gloss=0.5)
    bx, by, bz = b
    m.add(ell((bx, by, bz), (0.14, 0.05, 0.2)), color, k=0.04, gloss=0.5)


@group("hb_step_breakfast", S)
def step_breakfast():
    m = step("breakfast", target=(0, 0.75, 0), distance=11, pitch=26)
    bowl = inter(shell(sphere((0, 1.0, 0), 0.95), 0.07), plane((0, 1, 0), 0.92))
    m.add(cyl((0, 0.08, 0), 0.08, 0.45, 0.05), "#E9A3A0")
    m.add(bowl, "#E9A3A0", k=0.05)
    m.paint(raw("abs(p.y-0.62)-0.05"), CREAM, soft=0.01)
    m.add(cyl((0, 0.82, 0), 0.06, 0.86, 0.04), "#F4E3C3")                     # porridge
    for x, z, c in [(-0.3, 0.1, "#6F86C4"), (0.15, 0.3, "#E06B66"), (0.35, -0.15, "#6F86C4"), (-0.05, -0.3, "#E06B66"), (-0.4, -0.3, "#F3D266"), (0.05, 0.05, "#6F86C4")]:
        m.add(sphere((x, 0.92, z), 0.11), c)
    spoon(m, (0.55, 0.95, -0.3), (1.25, 1.45, -0.4))
    return m


def toothpaste(m, x, y, z, rot=(0, 0, 0), s=1.0):
    m.add(ell((x, y, z), (0.6 * s, 0.17 * s, 0.25 * s), rot=rot), "#F7F4EF")
    m.paint(ell((x, y, z), (0.25 * s, 0.25 * s, 0.3 * s), rot=rot), "#9FD3C1", soft=0.03)


@group("hb_step_brush", S)
def step_brush():
    m = step("brush", target=(0, 1.15, 0), distance=12.5)
    cup = inter(shell(cyl((0, 0.6, 0), 0.6, 0.48, 0.14), 0.05), plane((0, 1, 0), 1.15))
    m.add(cup, "#F1ACC3")
    m.add(flat((0, 0.6, 0.5), "heart", 0.16, 0.03, 0.02), "#FBF6EE")
    # two toothbrushes leaning out of the cup
    for s, c in ((1, "#82C074"), (-1, "#73A6DB")):
        a, b = (0.1 * s, 0.5, 0), (0.45 * s, 2.0, 0.1)
        m.add(capsule(a, b, 0.07), c)
        m.add(box((0.48 * s + 0.06 * s, 1.95, 0.18), (0.08, 0.26, 0.07), 0.04, rot=(0, 0, -13 * s)), "#F7F8FA", k=0.01)
    toothpaste(m, 1.05, 0.18, 0.3, rot=(0, -25, 0))
    m.add(cyl((1.6, 0.18, 0.55), 0.12, 0.12, 0.04, rot=(0, 0, 90)), "#73A6DB")
    return m


@group("hb_step_hair", S)
def step_hair():
    m = step("hair", target=(0, 0.75, 0), distance=11, pitch=30, yaw=-10)
    m.add(ell((0.35, 0.18, 0), (0.75, 0.15, 0.55)), "#E9A3A0")
    m.add(capsule((-0.3, 0.16, 0), (-1.35, 0.16, 0.25), 0.14), "#E9A3A0", k=0.1)
    m.add(ell((0.35, 0.3, 0), (0.62, 0.06, 0.44)), "#FBF0E8", k=0.03)
    for i in range(-3, 4):
        for j in range(-2, 3):
            x, z = 0.35 + i * 0.16, j * 0.16
            if ((x - 0.35) / 0.55) ** 2 + (z / 0.38) ** 2 < 1:
                m.add(capsule((x, 0.32, z), (x, 0.5, z), 0.03), "#B8AFA6")
                m.add(sphere((x, 0.51, z), 0.045), "#73A6DB")
    # a little hair bow
    m.add(ell((1.15, 0.2, 0.75), (0.28, 0.2, 0.08), rot=(-60, 0, 25)), "#A98DCC")
    m.add(ell((1.55, 0.2, 0.75), (0.28, 0.2, 0.08), rot=(-60, 0, -25)), "#A98DCC")
    m.add(sphere((1.35, 0.2, 0.75), 0.11), "#957BBE")
    return m


def shoe(m, x, z, color, sole="#F7F4EF"):
    """A little trainer pointing at +x, seen from its side."""
    m.add(box((x, 0.1, z), (0.78, 0.1, 0.32), 0.09), sole)
    m.add(ell((x - 0.2, 0.42, z), (0.58, 0.36, 0.32)), color, k=0.12)
    m.add(ell((x + 0.38, 0.27, z), (0.42, 0.22, 0.3)), color, k=0.18)
    m.add(ell((x + 0.62, 0.24, z), (0.2, 0.15, 0.27)), sole, k=0.06)
    m.cut(ell((x - 0.38, 0.8, z), (0.32, 0.14, 0.24)), "#E5D9C8", k=0.05)
    m.add(box((x + 0.08, 0.55, z), (0.08, 0.2, 0.33), 0.04, rot=(0, 0, -30)), "#F3D266", k=0.02)
    m.add(box((x - 0.12, 0.62, z), (0.08, 0.18, 0.33), 0.04, rot=(0, 0, -30)), "#F3D266", k=0.02)


@group("hb_step_shoes", S)
def step_shoes():
    m = step("shoes", target=(0, 0.5, 0), distance=10, pitch=14, yaw=18)
    shoe(m, -0.12, -0.45, "#E2705F")
    shoe(m, 0.12, 0.45, "#E2705F")
    return m


@group("hb_step_water", S)
def step_water():
    m = step("water", target=(0, 1.1, 0), distance=12)
    glass = shell(cyl((0, 0.85, 0), 0.82, 0.5, 0.1), 0.035)
    m.add(inter(glass, plane((0, 1, 0), 1.6)), "#DDEBF1", gloss=0.7)
    m.paint(raw("p.y-1.3"), "#B9DCEA", soft=0.02)
    m.add(cyl((0, 0.8, 0), 0.62, 0.47, 0.05), "#9FCFE4", gloss=0.6)
    m.add(capsule((0.25, 1.0, 0.1), (0.62, 2.1, 0.1), 0.05), "#F1ACC3")         # a straw
    q = "(p-vec3(-0.95,1.75,0.2))"
    drop = f"(length({q}*vec3(1.0,0.8,1.0)-vec3(0.0,-0.05,0.0))-0.26)"
    m.add(raw(f"smin({drop},sdRoundCone({q},vec3(0.0,-0.1,0.0),vec3(0.0,0.3,0.0),0.25,0.02),0.08)"), "#9FCFE4", gloss=0.7)
    return m


def toybox(m, x=0.0, z=0.0, s=1.0, color="#82C074", lid=True):
    m.add(box((x, 0.55 * s, z), (1.0 * s, 0.55 * s, 0.6 * s), 0.12 * s), color)
    m.cut(box((x, 1.1 * s, z), (0.88 * s, 0.4 * s, 0.48 * s), 0.08 * s), "#5F9A55", k=0.03 * s)
    m.paint(raw(f"abs(p.y-{0.55 * s:.3f})-{0.06 * s:.3f}"), "#F3D266", soft=0.01)
    m.add(flat((x, 0.55 * s, z + 0.62 * s), "star", 0.18 * s, 0.03 * s, 0.02 * s), "#F3D266")
    if lid:
        m.add(box((x, 1.45 * s, z - 0.75 * s), (1.0 * s, 0.5 * s, 0.07 * s), 0.06 * s, rot=(-18, 0, 0)), color)


@group("hb_step_tidy", S)
def step_tidy():
    m = step("tidy", target=(0, 1.0, 0), distance=12, pitch=18)
    toybox(m)
    m.add(sphere((-0.45, 1.05, 0.05), 0.38), "#73A6DB")
    m.paint(inter(raw("abs(p.y-1.05+(p.x+0.45)*0.4)-0.07"), sphere((-0.45, 1.05, 0.05), 0.42)), "#F7F1E6", soft=0.02)
    m.add(box((0.45, 1.05, 0.0), (0.3, 0.3, 0.3), 0.06, rot=(0, 25, 12)), "#F3D266")
    m.add(sphere((0.05, 1.25, -0.15), 0.25), "#C99067")
    m.add(sphere((-0.08, 1.48, -0.1), 0.1), "#C99067")
    m.add(sphere((0.2, 1.48, -0.1), 0.1), "#C99067")
    return m


@group("hb_step_dinner", S)
def step_dinner():
    m = step("dinner", target=(0, 0.45, 0), distance=10, pitch=40)
    m.add(cyl((0, 0.08, 0), 0.07, 1.2, 0.06), "#F7F4EF")
    m.add(cyl((0, 0.14, 0), 0.02, 0.95, 0.02), "#EAE4DA")
    m.paint(raw("abs(length(p.xz)-1.08)-0.04"), "#9FC6E8", soft=0.01)
    # peas, carrot coins, a potato, a little fish
    for i in range(7):
        a = i * 0.9
        m.add(sphere((-0.45 + 0.17 * math.cos(a), 0.24, 0.3 + 0.17 * math.sin(a)), 0.1), "#82C074")
    for x, z in [(0.35, 0.45), (0.6, 0.2), (0.25, 0.15)]:
        m.add(cyl((x, 0.21, z), 0.05, 0.16, 0.04), "#EE9750")
    m.add(ell((0.2, 0.32, -0.45), (0.38, 0.22, 0.28)), "#E8C98E")
    m.add(ell((-0.45, 0.25, -0.35), (0.32, 0.12, 0.2), rot=(0, 20, 0)), "#F3B79A")
    m.add(cone((-0.75, 0.25, -0.25), (-0.95, 0.25, -0.15), 0.06, 0.15), "#F3B79A")
    # fork on the side
    m.add(capsule((1.45, 0.08, 0.7), (1.45, 0.08, -0.4), 0.05), "#D8D2CB", gloss=0.5)
    for dx in (-0.08, 0.0, 0.08):
        m.add(capsule((1.45 + dx, 0.08, -0.4), (1.45 + dx, 0.08, -0.75), 0.025), "#D8D2CB", gloss=0.5)
    return m


def duck(m, x, y, z, s=1.0):
    m.add(ell((x, y, z), (0.42 * s, 0.3 * s, 0.32 * s)), "#F3D266", k=0.05)
    m.add(sphere((x + 0.28 * s, y + 0.36 * s, z), 0.24 * s), "#F3D266", k=0.1 * s)
    m.add(ell((x + 0.54 * s, y + 0.32 * s, z), (0.14 * s, 0.05 * s, 0.09 * s)), "#F0A15A", k=0.02)
    m.add(sphere((x + 0.36 * s, y + 0.43 * s, z + 0.19 * s), 0.04 * s), INKY, gloss=0.6)


@group("hb_step_bath", S)
def step_bath():
    m = step("bath", target=(0, 0.9, 0), distance=12.5, pitch=18)
    tub = inter(shell(box((0, 0.75, 0), (1.35, 0.7, 0.75), 0.6), 0.08), plane((0, 1, 0), 1.25))
    m.add(tub, "#F7F4EF")
    for s in (-1, 1):
        m.add(sphere((1.0 * s, 0.1, 0.45), 0.13), "#F3D266")
        m.add(sphere((1.0 * s, 0.1, -0.45), 0.13), "#F3D266")
    m.add(box((0, 1.05, 0), (1.25, 0.08, 0.66), 0.08), "#BFE0EC", gloss=0.5)
    r = random.Random(4)
    for _ in range(14):
        x = r.uniform(-1.2, 1.2)
        z = r.uniform(-0.5, 0.55)
        m.add(sphere((x, 1.2 + r.uniform(0, 0.2), z), r.uniform(0.14, 0.28)), "#F4F8FA", k=0.05, gloss=0.5)
    duck(m, -0.15, 1.35, 0.25, 0.9)
    m.add(capsule((1.25, 1.2, -0.6), (1.25, 2.0, -0.6), 0.06), "#D8D2CB", gloss=0.5)
    m.add(capsule((1.25, 2.0, -0.6), (0.95, 2.0, -0.6), 0.06), "#D8D2CB", gloss=0.5)
    return m


@group("hb_step_pyjamas", S)
def step_pyjamas():
    m = step("pyjamas", target=(0, 1.1, 0), distance=12)
    tshirt(m, 0, 1.15, 0, "#9FC6E8", 1.05, th=0.12)
    m.paint(raw("abs(sin(p.x*7.0)*sin(p.y*7.0))-0.6+step(p.z,0.05)*2.0"), "#B9D3EC", soft=0.06)
    for x, y in [(-0.25, 1.35), (0.3, 0.95), (-0.15, 0.7), (0.35, 1.45), (-0.62, 1.55)]:
        m.add(flat((x, y, 0.14), "star", 0.09, 0.02, 0.015), "#F3E2B0")
    for y in (1.35, 1.05, 0.75):
        m.add(sphere((0, y, 0.15), 0.05), CREAM)
    m.add(arc((0, 1.72, 0.05), 0.22, 0.04, 70, rot=(0, 0, 180)), "#7FA2C9")
    return m


@group("hb_step_story", S)
def step_story():
    m = step("story", target=(0, 0.75, 0), distance=11, pitch=30)
    for s in (-1, 1):
        m.add(box((0.62 * s, 0.12, 0), (0.6, 0.06, 0.8), 0.05, rot=(0, 0, -6 * s)), "#A98DCC")
        page = box((0.6 * s, 0.24, 0), (0.56, 0.06, 0.74), 0.04, rot=(0, 0, -9 * s))
        m.add(page, "#FBF6EE", k=0.02)
    m.paint(raw("abs(sin(p.z*14.0))-0.85+step(abs(p.x),0.2)*2.0+step(0.95,abs(p.x))*2.0"), "#D8CFC4", soft=0.03)
    for x, y, z, r in [(0.15, 1.1, -0.1, 0.16), (-0.4, 1.5, 0, 0.12), (0.45, 1.75, -0.1, 0.1), (0.0, 2.0, 0, 0.07)]:
        m.add(star((x, y, z), r, 0.5, 0.05, 0.04), "#F3D266", emit=0.2)
    m.add(capsule((0, 0.35, -0.2), (0, 0.35, 0.55), 0.03), "#E2705F")
    return m


def heart_face(m, x, y, z, s, color, tilt):
    m.add(flat((x, y, z), "heart", s, 0.22 * s, 0.2 * s, rot=(0, 0, tilt)), color, k=0.02)
    tx = math.sin(math.radians(-tilt))
    for sx in (-1, 1):
        ex = x + sx * 0.3 * s + tx * 0.05
        m.add(arc((ex, y + 0.08 * s, z + 0.24 * s), 0.08 * s, 0.022 * s, 58, rot=(0, 0, tilt)), INKY)
        m.paint(sphere((x + sx * 0.5 * s, y - 0.12 * s, z + 0.2 * s), 0.12 * s), "#F2A7A0", soft=0.08 * s)
    m.add(arc((x, y - 0.0 * s, z + 0.24 * s), 0.12 * s, 0.022 * s, 50, rot=(0, 0, 180 + tilt)), INKY)


@group("hb_step_hug", S)
def step_hug():
    m = step("hug", target=(0, 1.1, 0), distance=12, ground=True)
    heart_face(m, -0.36, 1.15, -0.1, 0.78, "#E9A3A0", 16)
    heart_face(m, 0.42, 0.98, 0.18, 0.62, "#F1C3AC", -14)
    return m


@group("hb_step_sleep", S)
def step_sleep():
    m = step("sleep", target=(0, 1.1, 0), distance=13, pitch=14)
    m.add(box((0, 0.45, 0), (1.35, 0.22, 0.6), 0.12), WOOD)                      # bed frame
    m.add(box((-1.3, 0.8, 0), (0.1, 0.8, 0.62), 0.08), WOOD)                    # headboard
    m.add(box((1.3, 0.55, 0), (0.08, 0.55, 0.62), 0.07), WOOD)
    m.add(box((0.1, 0.78, 0.0), (1.15, 0.14, 0.58), 0.12), "#A98DCC", k=0.04)   # blanket
    m.paint(raw("abs(sin(p.x*6.0)*sin(p.z*6.0))-0.55"), "#B9A3D6", soft=0.05)
    m.add(ell((-0.85, 0.98, 0), (0.36, 0.16, 0.42)), CREAM, k=0.04)            # pillow
    for s in (-1, 1):
        m.add(cyl((1.15 * s, 0.12, 0.45), 0.12, 0.09, 0.03), WOOD_DARK)
    # a sleeping moon above
    q = "(p-vec3(0.4,2.1,-0.2))"
    crescent = f"max(length({q}.xy)-0.55,-(length({q}.xy-vec2(0.32,0.2))-0.45))"
    m.add(raw(f"extrude({crescent},{q}.z,0.16,0.13)"), "#F3E2B0")
    m.add(arc((0.1, 2.12, 0.0), 0.06, 0.02, 55, rot=(0, 0, 180)), INKY)
    for x, y, r in [(1.25, 2.3, 0.12), (-0.6, 2.55, 0.09), (-1.05, 1.95, 0.07)]:
        m.add(star((x, y, -0.2), r, 0.5, 0.04, 0.03), "#F3D266", emit=0.2)
    return m


# ================================================================ brushing: the big smile, the brush, sugar bugs

TOOTH = "#FBF8F2"
GUM = "#EBA3A6"
LIP = "#E28F94"


def _mouth_top(x, rx=2.3, ry=1.3):
    return ry * math.sqrt(max(0.0, 1 - (x / rx) ** 2))


@group("hb_mouth", (512, 320))
def mouth():
    m = Model("hb_mouth", ground=False, target=(0, 0, 0), distance=22, pitch=0, fov=20)
    # the inside of the mouth, a soft dark rose
    m.add(ell((0, 0, -0.35), (2.35, 1.35, 0.3)), "#A75B67")
    # gums: a band along the top and the bottom
    for sy in (1, -1):
        band = inter(ell((0, 0, -0.1), (2.32, 1.32, 0.35)), plane((0, -sy, 0), -0.82))
        m.add(band, GUM, k=0.05)
    # tongue
    m.add(ell((0, -0.4, -0.2), (1.05, 0.5, 0.3)), "#EE9AA0", k=0.06)
    m.paint(raw("abs(p.x)-0.02+step(p.y,-0.75)*2.0"), "#DE8790", soft=0.03)
    # teeth: seven on top, six below, rounded like clay beads
    for sy, xs in ((1, [-1.65, -1.12, -0.58, 0.0, 0.58, 1.12, 1.65]), (-1, [-1.4, -0.85, -0.29, 0.29, 0.85, 1.4])):
        for x in xs:
            w = 0.27 if abs(x) < 0.4 else 0.25
            edge = _mouth_top(x) - 0.2
            h = 0.4 if abs(x) < 1.3 else 0.32
            y = sy * (edge - h)
            m.add(box((x, y, 0.08), (w, h, 0.17), 0.19), TOOTH, gloss=0.35)
    # lips
    ring = raw("extrude(abs(sdEllipse2(p.xy,vec2(2.42,1.42)))-0.2,p.z-0.05,0.24,0.2)")
    m.add(ring, LIP, k=0.05)
    m.paint(raw("(p.y-1.35)*-1.0+step(p.y,0.0)*9.0"), "#EBA0A3", soft=0.12)
    return m


@group("hb_brush", (384, 192))
def brush():
    """Lying flat, bristles up, head on the left: the app turns it to face the teeth."""
    m = hb("brush", ground=False, target=(0, 0.4, 0), pitch=6, distance=12)
    handle = "#9FD3C1"
    m.add(capsule((-0.9, 0.1, 0), (1.9, 0.0, 0), 0.17), handle)
    m.add(capsule((-1.75, 0.12, 0), (-0.9, 0.1, 0), 0.13), handle, k=0.1)
    m.paint(raw("abs(sin(p.x*6.0))-0.8+step(p.x,-0.6)*2.0"), "#C2E6D8", soft=0.05)
    m.add(box((-1.35, 0.42, 0), (0.48, 0.22, 0.16), 0.09), "#F7F8FA", k=0.02)
    m.paint(raw("abs(sin(p.x*14.0))-0.35+step(p.y,0.3)*2.0"), "#E5EEF3", soft=0.04)
    # a pea of toothpaste on top, a little swirl
    m.add(ell((-1.35, 0.72, 0), (0.3, 0.13, 0.17)), "#FBFBF8", k=0.05)
    m.add(ell((-1.32, 0.86, 0), (0.18, 0.1, 0.12)), "#C9EDE0", k=0.05)
    return m


def sugar_bug(name, body, belly):
    m = hb(name, ground=False, target=(0, 0.75, 0), pitch=10, distance=9)
    m.add(ell((0, 0.7, 0), (0.75, 0.6, 0.6)), body)
    m.paint(ell((0, 0.5, 0.5), (0.45, 0.32, 0.2)), belly, soft=0.12)
    m.add(capsule((0.25, 1.2, 0), (0.42, 1.55, 0.05), 0.05, sym=True), body, k=0.04)
    m.add(sphere((0.43, 1.58, 0.05), 0.11, sym=True), belly, k=0.02)
    eyes(m, 0.26, 0.82, 0.55, 0.1, "sleep")
    cheeks(m, 0.45, 0.65, 0.5, 0.11)
    smile(m, 0, 0.62, 0.6, 0.15, "sleep")
    m.add(ell((0.55, 0.15, 0.25), (0.16, 0.1, 0.16), sym=True), body, k=0.05)
    return m


@group("hb_bug", (192, 192))
def bug_a():
    return sugar_bug("bug", "#A9D88E", "#D3EDBC")


@group("hb_bug2", (192, 192))
def bug_b():
    return sugar_bug("bug2", "#C4DE86", "#E3EFBF")


# ================================================================ washing hands

SKIN = "#F0C4A4"
SKIN_DARK = "#E2A988"
NAIL = "#F7DCD0"
CUFF = "#9FC6E8"


def hand(name, back: bool):
    """A right hand seen from the front, fingers up. Palm side shows soft palm lines, back shows nails."""
    m = hb(name, ground=False, target=(0, 1.4, 0), pitch=0, distance=14)
    sx = -1 if back else 1   # the thumb is on the other side when the hand turns round
    m.add(ell((0, 1.15, 0), (0.72, 0.82, 0.3)), SKIN)
    fingers = [(-0.45, 2.15, 0.17), (-0.15, 2.38, 0.18), (0.16, 2.33, 0.18), (0.44, 2.05, 0.16)]
    for fx, top, r in fingers:
        m.add(capsule((fx * 0.9 * sx, 1.55, 0), (fx * sx, top, 0), r), SKIN, k=0.12)
        if back:
            m.add(ell((fx * sx, top - 0.02, 0.15), (r * 0.62, r * 0.75, 0.05)), NAIL, k=0.02)
    m.add(capsule((-0.55 * sx, 0.95, 0.05), (-1.05 * sx, 1.55, 0.1), 0.19), SKIN, k=0.14)
    if back:
        m.add(ell((-1.05 * sx, 1.55, 0.25), (0.11, 0.13, 0.05), rot=(0, 0, 35 * sx)), NAIL, k=0.02)
        for fx, _, _ in fingers[:4]:
            m.paint(sphere((fx * 0.92 * sx, 1.62, 0.28), 0.07), SKIN_DARK, soft=0.06)
    else:
        m.paint(raw("abs(length(p.xy-vec2(0.55,0.6))-0.75)-0.02+step(p.z,0.15)*2.0"), SKIN_DARK, soft=0.03)
        m.paint(raw("abs(p.y-1.42+p.x*0.1)-0.018+step(p.z,0.15)*2.0+step(0.5,abs(p.x))*2.0"), SKIN_DARK, soft=0.03)
    m.add(cyl((0, 0.18, 0), 0.4, 0.42, 0.12), SKIN, k=0.2)
    m.add(cyl((0, -0.25, 0), 0.32, 0.5, 0.15), CUFF, k=0.04)
    m.paint(raw("abs(p.y+0.08)-0.05"), "#C3DCF0", soft=0.02)
    return m


@group("hb_hand", (320, 384))
def hands():
    return [hand("hand_palm", False), hand("hand_back", True)]


@group("hb_tap", S)
def tap_big():
    m = hb("tap", ground=False, target=(0, 1.0, 0), pitch=6, distance=12, yaw=-30)
    metal = "#D8D2CB"
    m.add(cyl((0, 1.4, -0.6), 0.12, 0.5, 0.06, rot=(90, 0, 0)), metal, gloss=0.6)
    m.add(capsule((0, 1.4, -0.5), (0, 1.55, 0.4), 0.2), metal, gloss=0.6, k=0.05)
    m.add(capsule((0, 1.55, 0.4), (0, 1.25, 0.95), 0.2), metal, gloss=0.6, k=0.1)
    m.add(cyl((0, 1.1, 1.0), 0.1, 0.22, 0.05), "#C3BDB6", gloss=0.5, k=0.05)
    m.add(capsule((0, 1.85, -0.2), (0, 2.1, -0.2), 0.07), metal, gloss=0.6)
    m.add(ell((0, 2.2, -0.2), (0.42, 0.11, 0.18)), "#9FC6E8", gloss=0.3)
    return m


@group("hb_towel", S)
def towel():
    m = hb("towel", ground=False, target=(0, 1.0, 0), pitch=4, distance=12)
    t = box((0, 1.0, 0), (0.95, 1.05, 0.08), 0.08)
    m.add(raw(f"{t.expr}+sin(p.x*3.0)*0.04"), "#F1ACC3")
    m.paint(raw("abs(p.y-0.25)-0.1"), CREAM, soft=0.02)
    m.paint(raw("abs(p.y-0.55)-0.05"), CREAM, soft=0.02)
    m.add(capsule((-1.15, 2.1, -0.05), (1.15, 2.1, -0.05), 0.09), WOOD)
    m.add(box((0, 2.0, 0.02), (0.95, 0.12, 0.12), 0.1), "#E99DB3", k=0.04)
    return m


# ================================================================ healthy plate

def food(name, **kw):
    return hb(name, **kw)


@group("hb_candy", S)
def candy():
    m = food("candy", target=(0, 0.6, 0), pitch=14, distance=11)
    m.add(ell((0, 0.6, 0), (0.55, 0.45, 0.45)), "#F1ACC3", gloss=0.5)
    m.paint(raw("abs(sin((p.x+p.y)*7.0))-0.55"), CREAM, soft=0.03)
    for s in (-1, 1):
        m.add(cone((0.5 * s, 0.6, 0), (1.15 * s, 0.6, 0), 0.08, 0.32), "#F1ACC3", k=0.06, gloss=0.3)
        m.cut(sphere((1.45 * s, 0.6, 0), 0.32), k=0.05)
    return m


@group("hb_lollipop", S)
def lollipop():
    m = food("lollipop", target=(0, 1.3, 0), pitch=6, distance=13)
    m.add(capsule((0, 0.05, 0), (0, 1.5, 0), 0.06), "#F7F4EF")
    m.add(cyl((0, 1.9, 0), 0.18, 0.75, 0.16, rot=(90, 0, 0)), "#A98DCC", gloss=0.4)
    m.paint(raw("abs(sin(length(p.xy-vec2(0.0,1.9))*9.0+atan(p.y-1.9,p.x)))-0.45"), "#F1ACC3", soft=0.04)
    m.add(torus((0, 1.25, 0), 0.12, 0.05), "#F3D266")
    return m


@group("hb_chips", S)
def chips():
    m = food("chips", target=(0, 1.0, 0), pitch=10, distance=12)
    bag = box((0, 1.0, 0), (0.65, 0.9, 0.22), 0.2)
    m.add(raw(f"{bag.expr}-0.05*(1.0-abs(p.y-1.0))"), "#E2705F")
    m.paint(raw("abs(p.y-1.0)-0.28"), "#F3D266", soft=0.02)
    m.add(ell((0, 1.0, 0.27), (0.3, 0.2, 0.04)), "#F7E2A6", k=0.02)
    for x, y, rz in [(-0.3, 2.02, 20), (0.15, 2.1, -15), (0.38, 1.98, 35)]:
        m.add(ell((x, y, -0.02), (0.24, 0.17, 0.04), rot=(10, 0, rz)), "#F0C973")
    m.add(box((0, 1.9, 0), (0.62, 0.06, 0.12), 0.04), "#D45F55", k=0.05)
    return m


@group("hb_corn", S)
def corn():
    m = food("corn", target=(0, 1.0, 0), pitch=10, distance=12)
    m.add(ell((0, 1.05, 0), (0.42, 0.95, 0.42)), "#F3D266")
    m.paint(raw("max(abs(sin(p.y*16.0))-0.25,abs(sin(atan(p.x,p.z)*7.0))-0.25)"), "#E6C04F", soft=0.06)
    for s, rz in ((1, -18), (-1, 18)):
        m.add(ell((0.32 * s, 0.6, 0.15), (0.2, 0.75, 0.08), rot=(0, 0, rz)), LEAF, k=0.06)
    return m


@group("hb_eggplant", S)
def eggplant():
    m = food("eggplant", target=(0, 0.9, 0), pitch=12, distance=12)
    m.add(ell((0.15, 0.62, 0), (0.62, 0.6, 0.55)), "#8A6BB0", gloss=0.35)
    m.add(ell((-0.35, 1.05, 0), (0.4, 0.42, 0.38)), "#8A6BB0", k=0.35, gloss=0.35)
    for i in range(5):
        a = i * 2 * math.pi / 5
        m.add(ell((-0.55 + 0.18 * math.cos(a), 1.4, 0.18 * math.sin(a)), (0.2, 0.06, 0.1), rot=(0, math.degrees(a), 30)), "#7DB46B", k=0.03)
    m.add(capsule((-0.6, 1.42, 0), (-0.8, 1.75, 0), 0.07), "#6E9E62", k=0.03)
    return m


@group("hb_peas", S)
def peas():
    m = food("peas", target=(0, 0.5, 0), pitch=30, distance=10)
    pod = inter(shell(ell((0, 0.35, 0), (1.15, 0.32, 0.4)), 0.05), plane((0, 1, 0), 0.4))
    m.add(pod, "#7DBA70")
    for i in range(5):
        x = -0.72 + i * 0.36
        m.add(sphere((x, 0.4, 0), 0.17), "#9ACD7C", gloss=0.2)
    m.add(capsule((1.1, 0.35, 0), (1.35, 0.55, 0), 0.05), "#6E9E62")
    return m


@group("hb_plate", (448, 320))
def plate():
    m = hb("plate", target=(0, 0.1, 0), pitch=55, distance=11, reach=2.4)
    m.add(cyl((0, 0.1, 0), 0.1, 1.6, 0.08), "#F7F4EF")
    m.add(cyl((0, 0.2, 0), 0.04, 1.25, 0.04), "#EFE9DF", k=0.03)
    m.paint(raw("abs(length(p.xz)-1.45)-0.05"), "#9FD3C1", soft=0.01)
    for i in range(10):
        a = i * 2 * math.pi / 10
        m.paint(sphere((1.45 * math.cos(a), 0.2, 1.45 * math.sin(a)), 0.06), "#F3D266", soft=0.01)
    return m


def basket(name, color, band, emblem):
    m = hb(name, target=(0, 0.95, 0), pitch=14, distance=13)
    body = inter(shell(cyl((0, 0.65, 0), 0.65, 1.1, 0.25), 0.07), plane((0, 1, 0), 1.25))
    m.add(body, color)
    m.paint(raw("max(abs(sin(p.y*12.0))-0.5,abs(sin(atan(p.x,p.z)*14.0))-0.5)"), band, soft=0.06)
    m.add(torus((0, 1.25, 0), 1.06, 0.09), band, k=0.03)
    m.add(arc((0, 1.25, 0), 1.0, 0.07, 72), band)
    m.add(cyl((0, 0.68, 1.14), 0.03, 0.33, 0.02, rot=(90, 0, 0)), CREAM)
    emblem(m)
    return m


@group("hb_basket_every", S)
def basket_every():
    def em(m):
        m.add(ell((-0.08, 0.7, 1.2), (0.12, 0.2, 0.04), rot=(0, 0, 30)), "#82C074")
        m.add(ell((0.1, 0.72, 1.2), (0.12, 0.2, 0.04), rot=(0, 0, -30)), "#82C074")
        m.add(sphere((0.0, 0.55, 1.2), 0.07), "#E2705F")
    return basket("basket_every", "#D9B07A", "#C49563", em)


@group("hb_basket_some", S)
def basket_some():
    def em(m):
        m.add(star((0, 0.68, 1.2), 0.2, 0.5, 0.03, 0.02), "#F3D266")
    return basket("basket_some", "#EFB5C6", "#E39DB1", em)


# ================================================================ tidy up: the room and the toys' homes

@group("hb_toybox", S)
def toybox_home():
    m = hb("toybox", target=(0, 0.95, 0), distance=12, pitch=16)
    toybox(m)
    return m


@group("hb_shelf", S)
def shelf():
    m = hb("shelf", target=(0, 1.2, 0), distance=12.5, pitch=6)
    m.add(box((0, 1.2, -0.1), (1.05, 1.2, 0.35), 0.1), WOOD)
    for y in (0.95, 1.95):
        m.cut(box((0, y, 0.2), (0.9, 0.38, 0.42), 0.06), "#B5865C", k=0.02)
    m.add(box((-0.55, 1.2, 0.25), (0.12, 0.22, 0.1), 0.05, rot=(0, 0, 0)), CREAM)  # a little label
    m.add(flat((-0.55, 1.2, 0.36), "heart", 0.07, 0.02, 0.015), "#E9A3A0")
    m.add(cyl((0.75, 2.55, 0.0), 0.12, 0.18, 0.05), "#E2A988")                     # a tiny plant on top
    for dx, rz in ((-0.1, 25), (0.1, -25), (0.0, 0)):
        m.add(ell((0.75 + dx, 2.8, 0.0), (0.08, 0.2, 0.06), rot=(0, 0, rz)), LEAF, k=0.03)
    return m


@group("hb_bathbasket", S)
def bathbasket():
    m = hb("bathbasket", target=(0, 0.8, 0), pitch=16, distance=11.5)
    body = inter(shell(box((0, 0.55, 0), (1.0, 0.55, 0.6), 0.35), 0.07), plane((0, 1, 0), 1.0))
    m.add(body, "#9FC6E8")
    m.paint(raw("abs(sin(p.x*9.0)*sin(p.y*9.0))-0.5"), "#B7D5EF", soft=0.05)
    for x, y, r in [(-0.75, 1.15, 0.16), (-0.5, 1.28, 0.11), (0.8, 1.1, 0.13)]:
        m.add(sphere((x, y, 0.45), r), "#F4F8FA", gloss=0.6)
    m.add(flat((0, 0.55, 0.62), "circle", 0.16, 0.03, 0.02), CREAM)
    m.add(sphere((0, 0.58, 0.67), 0.07), "#F3D266")
    return m


def _book(name, color):
    m = hb(name, pitch=18, yaw=-20)
    m.add(box((0, 0.22, 0), (0.85, 0.22, 0.65), 0.06), "#F6EBDA")
    m.add(box((0, 0.46, 0), (0.88, 0.05, 0.68), 0.04), color)
    m.add(box((0, 0.0, 0), (0.88, 0.05, 0.68), 0.04), color)
    m.add(box((-0.86, 0.23, 0), (0.05, 0.26, 0.68), 0.04), color)
    m.add(flat((0.0, 0.52, 0), "heart", 0.2, 0.02, 0.015, rot=(-90, 0, 0)), "#FBF6EE")
    return m


@group("hb_book_red", S)
def book_red():
    return _book("book_red", "#E2705F")


@group("hb_book_blue", S)
def book_blue():
    return _book("book_blue", "#73A6DB")


from models.scenes import cloud as _cloud, flower as _flower, floor as _floor, room as _room, scene as _scene, tree as _tree  # noqa: E402


@group("bg_hb_room", (1280, 800))
def hb_room():
    m = _scene("hb_room", sky=("#F3E6E0", "#F3E6E0"), haze=0.06)
    _room(m, "#F1E2DA", "#E9D3B8")
    # a big round rug in the middle: the open floor where toys lie about
    m.add(cyl((0, 0.05, -1.0), 0.06, 8.0, 0.05), "#E5D3E8")
    m.add(torus((0, 0.08, -1.0), 7.2, 0.12), "#EFE3F1")
    # a window with curtains, high in the middle
    m.add(box((0, 9.0, -8.85), (2.6, 2.0, 0.2), 0.3), "#FBF6EE")
    m.add(box((0, 9.0, -8.7), (2.2, 1.6, 0.1), 0.2), "#C9E1EA", emit=0.4)
    for s in (-1, 1):
        m.add(box((2.9 * s, 8.6, -8.5), (0.7, 2.6, 0.15), 0.3), "#F1ACC3")
    m.add(capsule((-3.8, 11.3, -8.5), (3.8, 11.3, -8.5), 0.12), WOOD)
    # a bed at the far left edge, a lamp and a plant on the right
    m.add(box((-16.5, 1.2, -5.5), (3.0, 0.7, 2.6), 0.4), "#A98DCC")
    m.add(box((-16.5, 0.45, -5.5), (3.1, 0.45, 2.7), 0.2), WOOD)
    m.add(ell((-16.5, 2.1, -7.2), (1.4, 0.4, 0.6)), CREAM)
    m.add(cyl((16.5, 0.9, -6.5), 0.9, 0.8, 0.2), "#E2A988")
    for dx, dy, r in [(0, 2.8, 1.0), (-0.8, 2.2, 0.7), (0.8, 2.4, 0.7)]:
        m.add(sphere((16.5 + dx, dy, -6.5), r), "#8DB48A", k=0.3)
    # bunting on the wall
    for i in range(9):
        x = -12 + i * 3
        y = 12.6 - 0.35 * math.cos((x / 12) * math.pi / 2)
        m.add(cone((x, y, -8.7), (x, y - 1.0, -8.7), 0.45, 0.05), ["#F3D266", "#9FD3C1", "#F1ACC3"][i % 3])
    return m


# ================================================================ dress for the weather: Tuka and the wardrobe

RAIN_Y = "#F3D266"
WOOL = "#E2705F"
WOOL_LIGHT = "#F7EFE3"


DRESS_DISTANCE = 20.0  # the umbrella reaches high: a little further back than Tuka's own sprites


def _tuka_cam(name):
    return Model(f"hb_{name}", target=(0, 1.45, 0), distance=DRESS_DISTANCE, ground=False)


def coat(m, color, trim, collar=True, long=True):
    lo = 0.12 if long else 0.42
    body = inter(inter(ell((0, 0.7, 0.03), (0.69, 0.74, 0.62)), plane((0, -1, 0), -lo)), plane((0, 1, 0), 1.12))
    m.add(inter(body, plane((0, 0, -1), 0.05)), color)
    for s in (-1, 1):
        sleeve = ell((0.56 * s, 0.86, 0.23), (0.21, 0.33 if long else 0.2, 0.21), rot=(0, 0, 20 * s))
        if not long:
            sleeve = ell((0.56 * s, 1.0, 0.2), (0.2, 0.2, 0.2), rot=(0, 0, 20 * s))
        m.add(sleeve, color, k=0.05)
    if collar:
        m.add(inter(torus((0, 1.08, 0.08), 0.42, 0.09, rot=(12, 0, 0)), plane((0, 0, -1), -0.15)), trim, k=0.03)
    return m


def raincoat_model(name):
    m = _tuka_cam(name)
    coat(m, RAIN_Y, "#E6C04F")
    for y in (0.9, 0.62, 0.34):
        m.add(sphere((0, y, 0.64 - (0.9 - y) * 0.12), 0.06), "#73A6DB", gloss=0.3)
    return m


def jacket_model(name):
    m = _tuka_cam(name)
    coat(m, "#82B6C9", "#F7EFE3")
    m.add(capsule((0, 1.0, 0.6), (0, 0.2, 0.58), 0.03), "#5F8FA6")
    for s in (-1, 1):
        m.add(box((0.32 * s, 0.4, 0.5), (0.13, 0.08, 0.05), 0.04, rot=(0, 25 * s, 0)), "#6FA3B8")
    return m


def tshirt_model(name):
    m = _tuka_cam(name)
    coat(m, "#9FD3C1", "#9FD3C1", collar=False, long=False)
    m.add(flat((0, 0.8, 0.66), "star", 0.12, 0.02, 0.015), "#F3D266")
    return m


def boots_model(name):
    m = _tuka_cam(name)
    for s in (-1, 1):
        m.add(ell((0.3 * s, 0.12, 0.2), (0.22, 0.13, 0.28)), "#E2705F", gloss=0.35)
        m.add(cyl((0.3 * s, 0.24, 0.12), 0.13, 0.18, 0.07), "#E2705F", k=0.08, gloss=0.35)
        m.add(torus((0.3 * s, 0.36, 0.12), 0.17, 0.035), "#D45F55")
    return m


def umbrella_model(name):
    m = _tuka_cam(name)
    cx = -1.2
    canopy = inter(shell(sphere((cx, 3.0, 0.1), 1.35), 0.03), plane((0, -1, 0), -3.45))
    m.add(canopy, "#73A6DB")
    m.paint(raw(f"abs(sin(atan(p.x-({cx}),p.z-0.1)*4.0))-0.5"), "#F7F1E6", soft=0.04)
    m.add(sphere((cx, 4.38, 0.1), 0.08), "#F3D266")
    m.add(capsule((cx, 4.3, 0.1), (cx + 0.05, 1.2, 0.35), 0.045), "#AE7E5F")
    m.add(capsule((cx + 0.05, 1.2, 0.35), (-0.72, 0.62, 0.38), 0.045), "#AE7E5F")
    m.add(sphere((-0.68, 0.6, 0.4), 0.13), "#DC8B62")  # Tuka's paw holding it
    return m


def sunhat_model(name):
    m = _tuka_cam(name)
    m.add(cyl((0, 2.45, 0.05), 0.04, 1.3, 0.04, rot=(-10, 0, 0)), "#F0D9A0")
    m.add(inter(ell((0, 2.5, 0.0), (0.72, 0.55, 0.65)), plane((0, -1, 0), -2.45)), "#F0D9A0", k=0.05)
    m.paint(raw("abs(sin(p.x*20.0))*abs(sin(p.z*20.0))-0.3"), "#E5C988", soft=0.05)
    m.add(torus((0, 2.58, 0.0), 0.68, 0.08, rot=(-10, 0, 0)), "#E9A3A0")
    m.add(ell((0.55, 2.62, 0.45), (0.12, 0.16, 0.08)), "#F3D266")
    return m


def sunglasses_model(name):
    m = _tuka_cam(name)
    for s in (-1, 1):
        m.add(cyl((0.37 * s, 1.9, 0.92), 0.04, 0.2, 0.03, rot=(90, 0, -0)), "#4A4550", gloss=0.8)
        m.add(torus((0.37 * s, 1.9, 0.93), 0.2, 0.035, rot=(90, 0, 0)), "#F1ACC3")
    m.add(arc((0, 1.9, 0.99), 0.13, 0.03, 50), "#F1ACC3")
    return m


def woollyhat_model(name):
    m = _tuka_cam(name)
    dome = inter(ell((0, 2.25, 0.05), (0.9, 0.75, 0.82)), plane((0, -1, 0), -2.28))
    m.add(dome, WOOL)
    m.paint(raw("abs(sin(p.y*14.0))-0.7"), "#D45F55", soft=0.04)
    m.add(torus((0, 2.32, 0.05), 0.8, 0.14), WOOL_LIGHT, k=0.04)
    m.add(sphere((0, 3.05, 0.05), 0.24), WOOL_LIGHT, k=0.05)
    return m


def scarf_model(name):
    m = _tuka_cam(name)
    ring = inter(torus((0, 1.1, 0.08), 0.5, 0.16, rot=(10, 0, 0)), plane((0, 0, -1), -0.1))
    m.add(ring, WOOL)
    m.add(box((0.32, 0.72, 0.68), (0.15, 0.38, 0.06), 0.05, rot=(0, 0, 8)), WOOL, k=0.05)
    m.paint(raw("abs(sin(p.y*18.0))-0.6+step(1.0,p.y)*2.0"), WOOL_LIGHT, soft=0.03)
    for i in range(3):
        m.add(capsule((0.24 + i * 0.08, 0.34, 0.7), (0.25 + i * 0.08, 0.24, 0.7), 0.025), WOOL_LIGHT)
    return m


def mittens_model(name):
    m = _tuka_cam(name)
    for s in (-1, 1):
        m.add(ell((0.63 * s, 0.56, 0.32), (0.19, 0.22, 0.18)), WOOL)
        m.add(ell((0.48 * s, 0.62, 0.42), (0.07, 0.1, 0.07), rot=(0, 0, -30 * s)), WOOL, k=0.04)
        m.add(ell((0.66 * s, 0.75, 0.3), (0.17, 0.07, 0.15)), WOOL_LIGHT, k=0.03)
    return m


WARDROBE = {
    "raincoat": raincoat_model, "boots": boots_model, "umbrella": umbrella_model, "sunhat": sunhat_model,
    "sunglasses": sunglasses_model, "woollyhat": woollyhat_model, "scarf": scarf_model, "mittens": mittens_model,
    "tshirt": tshirt_model, "jacket": jacket_model,
}


@group("hb_dress", (512, 512))
def dress():
    out = []
    for pose in ("idle", "blink", "happy"):
        t = tuka(pose)
        t.name = f"hb_dress_tuka_{pose}"
        t.ground = True
        t.distance = DRESS_DISTANCE
        out.append(t)
    for item, fn in WARDROBE.items():
        out.append(fn(f"dress_{item}"))
    return out


def garment(name, color, long):
    """A flat clay top for a choice card: short sleeves for a t-shirt, long ones and a longer body for coats."""
    m = hb(name, ground=False, target=(0, 1.0, 0), pitch=4, distance=11)
    bl = 0.85 if long else 0.6
    sl = (0.42, 0.2) if long else (0.26, 0.2)
    sx, sy = (0.72, 0.15) if long else (0.62, 0.32)
    rot = "mat2(0.6,-0.8,0.8,0.6)" if long else "mat2(0.8,-0.6,0.6,0.8)"
    q = "(p-vec3(0.0,1.0,0.0))"
    body = f"sdBox2(({q}).xy-vec2(0.0,{0.55 - bl:.3f}),vec2(0.5,{bl:.3f}))"
    sleeves = f"sdBox2({rot}*(vec2(abs(({q}).x),({q}).y)-vec2({sx:.3f},{sy:.3f})),vec2({sl[0]:.3f},{sl[1]:.3f}))"
    neck = f"(length(({q}).xy-vec2(0.0,0.62))-0.2)"
    m.add(raw(f"extrude(max(min({body},{sleeves})-0.08,-{neck}),({q}).z,0.14,0.1)"), color)
    return m


def wear_icon(item):
    if item == "tshirt":
        m = garment("wear_tshirt", "#9FD3C1", False)
        m.add(flat((0, 0.95, 0.16), "star", 0.18, 0.03, 0.02), "#F3D266")
    elif item == "raincoat":
        m = garment("wear_raincoat", RAIN_Y, True)
        for y in (1.25, 0.9, 0.55):
            m.add(sphere((0, y, 0.17), 0.07), "#73A6DB", gloss=0.3)
        m.add(arc((0, 1.66, 0.12), 0.24, 0.07, 70, rot=(0, 0, 180)), "#E6C04F")
    elif item == "jacket":
        m = garment("wear_jacket", "#82B6C9", True)
        m.add(capsule((0, 1.4, 0.17), (0, 0.4, 0.17), 0.035), "#5F8FA6")
        m.add(arc((0, 1.66, 0.12), 0.24, 0.08, 70, rot=(0, 0, 180)), WOOL_LIGHT)
    else:
        m = WARDROBE[item](f"wear_{item}")
        if item == "umbrella":
            m.distance = 22
    return m


for _item in WARDROBE:
    group(f"hb_wear_{_item}", (256, 256))(lambda i=_item: wear_icon(i))


# ================================================================ the weather outside the window

def sky_scene(name, sky, ground="#B5D29A", far="#CADFB4"):
    m = _scene(f"hb_sky_{name}".replace("bg_", ""), sky=sky, haze=0.04, target=(0, 3.0, 0), distance=22, fov=34)
    m.name = f"hb_sky_{name}"
    _floor(m, ground)
    m.add(ell((-10, 0, -22), (16, 5, 8)), far, k=0.6)
    m.add(ell((12, 0, -26), (18, 6.5, 9)), far, k=0.6)
    return m


@group("hb_sky_sunny", (480, 320))
def sky_sunny():
    m = sky_scene("sunny", ("#A9D3EE", "#EAF3EE"))
    m.add(sphere((7, 9.5, -20), 2.0), "#F3CD5E", emit=0.3)
    for i in range(10):
        a = i * 2 * math.pi / 10
        m.add(cone((7 + 2.4 * math.cos(a), 9.5 + 2.4 * math.sin(a), -20), (7 + 3.3 * math.cos(a), 9.5 + 3.3 * math.sin(a), -20), 0.45, 0.15), "#F0B957", k=0.1, emit=0.2)
    _cloud(m, -8, 9, -24, 1.3)
    _tree(m, -9, -8, 1.2, fruit="#E2705F")
    r = random.Random(7)
    for _ in range(14):
        _flower(m, r.uniform(-14, 14), r.uniform(-4, 4), r.choice(["#F1ACC3", "#F3D266", "#FBF4E6", "#A98DCC"]), 1.3)
    return m


@group("hb_sky_rainy", (480, 320))
def sky_rainy():
    m = sky_scene("rainy", ("#A7B2C2", "#D9DEE2"), ground="#A6C49A", far="#BCCDB3")
    for x, y, s in [(-7, 9.5, 2.0), (3, 10.5, 2.4), (10, 9, 1.7)]:
        for dx, dy, r in [(-1.0, 0, 0.8), (0, 0.35, 1.1), (1.0, 0, 0.8), (0.5, -0.2, 0.7), (-0.5, -0.2, 0.7)]:
            m.add(sphere((x + dx * s, y + dy * s, -18), r * s), "#CBD2DC", k=0.4 * s)
    for x, z in [(-4, 1), (5, -1), (0, 3)]:
        m.add(cyl((x, 0.02, z), 0.04, 1.8, 0.03), "#B9D3E2", gloss=0.6)
    _tree(m, 10, -8, 1.2, canopy="#7FA873")
    return m


@group("hb_sky_snowy", (480, 320))
def sky_snowy():
    m = sky_scene("snowy", ("#C9D6E6", "#EEF1F4"), ground="#F4F6F8", far="#E6EBF1")
    for x, y, s in [(-6, 10, 1.6), (7, 10.5, 1.9)]:
        for dx, dy, r in [(-1.0, 0, 0.8), (0, 0.35, 1.1), (1.0, 0, 0.8)]:
            m.add(sphere((x + dx * s, y + dy * s, -20), r * s), "#F7F8FA", k=0.4 * s)
    # a little snowman
    m.add(sphere((6, 1.3, -2), 1.5), "#FBFBFC")
    m.add(sphere((6, 3.4, -2), 1.0), "#FBFBFC", k=0.2)
    m.add(cone((6, 3.4, -1.05), (6, 3.3, -0.3), 0.18, 0.05), "#EE9750")
    for s in (-1, 1):
        m.add(sphere((6 + 0.35 * s, 3.75, -1.12), 0.12), INKY)
    m.add(torus((6, 2.6, -2), 0.85, 0.22), WOOL)
    # snowy pine trees
    for x, z, s in [(-9, -6, 1.2), (-12, -10, 1.0)]:
        m.add(cone((x, 0.5 * s, z), (x, 4.5 * s, z), 2.0 * s, 0.1), "#7FA873")
        m.add(cone((x, 2.5 * s, z), (x, 4.8 * s, z), 1.2 * s, 0.1), "#F7F8FA", k=0.1)
    return m


@group("hb_sky_windy", (480, 320))
def sky_windy():
    m = sky_scene("windy", ("#B6D2E6", "#EDF2EC"))
    _cloud(m, -6, 10, -22, 1.4)
    _cloud(m, 8, 11, -26, 1.2)
    # a tree bending in the wind
    m.add(cone((-8, 0, -6), (-6.8, 3.8, -6), 0.45, 0.25), "#A98262", k=0.1)
    for dx, dy, r in [(1.6, 4.4, 1.5), (0.4, 3.9, 1.1), (2.6, 3.8, 1.0), (1.4, 5.4, 0.9)]:
        m.add(ell((-8 + dx, dy, -6), (r * 1.25, r * 0.85, r)), "#86BB76", k=0.4)
    # a kite on the breeze
    m.add(flat((7, 9, -12), "diamond", 0.9, 0.08, 0.06, rot=(0, 0, 25)), "#F1ACC3")
    m.add(capsule((6.5, 8.0, -12), (3.0, 1.0, -8), 0.04), "#B8AFA6")
    return m
