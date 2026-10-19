"""
Bo's Word Room (sprite prefix wo_): the farm friends for "Animal friends" (two poses each: idle and
happy), the things that letters stand for in the ABC garden when the shared objects have no match
(cat, jam, nest, quilt, van, xylophone, yo-yo, zebra; German: clown, dino, hedgehog, jacket, lion,
jellyfish, clock, bird, yak), the activity icons, and the farm backdrop bg_wo_farm.

Everything is chibi clay like the friends: big round heads, tiny bodies, muted colours.
"""
from __future__ import annotations

import math
import random

from models import group
from models.objects import COLORS, LEAF, STEM
from models.scenes import BG, cloud, flower, floor, hill, scene, tree
from sdf import (Model, arc, box, capsule, cheeks, cone, cyl, ell, eyes, flat, inter, plane, raw, shell,
                 smile, sphere, star, sub, torus, uni)

S = (320, 320)      # objects, like the shared ones
A = (384, 384)      # animals: a little more detail for the meadow and the quiz cards
INK = "#3E3346"
CREAM = "#FBF4E8"


def zs(c, r, x, y, inset=0.03):
    """z of the front surface of ellipsoid (centre c, radii r) at (x, y), minus a small inset: where faces go."""
    dx, dy = (x - c[0]) / r[0], (y - c[1]) / r[1]
    return c[2] + r[2] * math.sqrt(max(0.0, 1 - dx * dx - dy * dy)) - inset


def obj(name, target=(0, 0.9, 0), distance=11.0, ground=True, pitch=12.0, fov=20.0, yaw=0.0, reach=1.4):
    return Model(f"wo_{name}", target=target, distance=distance, ground=ground, pitch=pitch, fov=fov, yaw=yaw, reach=reach)


def critter(name, pose, target=(0, 1.2, 0), distance=14.5, pitch=10.0, yaw=0.0):
    return Model(f"wo_{name}_{pose}", target=target, distance=distance, pitch=pitch, yaw=yaw, reach=1.8)


def poses(name, fn):
    """Registers wo_<name> with the idle and happy poses (one shared crop)."""
    group(f"wo_{name}", A)(lambda: [fn("idle"), fn("happy")])


# ---------------------------------------------------------------- shared animal parts

HEAD_C = (0.0, 1.55, 0.22)
HEAD_R = (0.78, 0.68, 0.66)


def body4(m, fur, legs=None, hoof=None, body=(0.6, 0.5, 0.62), leg_r=0.17, y=0.72):
    """A small round body on four stubby legs (the head goes on top, in front)."""
    legs = legs or fur
    m.add(ell((0, y, -0.15), body), fur)
    for z in (0.2, -0.48):
        m.add(cyl((0.3, 0.24, z), 0.24, leg_r, 0.07, sym=True), legs, k=0.08)
        if hoof:
            m.paint(cyl((0.3, 0.04, z), 0.06, leg_r + 0.02, 0.03, sym=True), hoof, soft=0.02)


def head(m, fur, c=HEAD_C, r=HEAD_R, k=0.14):
    m.add(ell(c, r), fur, k=k)


def face(m, pose, eye_x=0.3, eye_y=1.66, eye_r=0.12, c=HEAD_C, r=HEAD_R, cheek=True, cheek_y=None, cheek_color="#F2A7A0"):
    mood = "happy" if pose == "happy" else "open"
    eyes(m, eye_x, eye_y, zs(c, r, eye_x, eye_y), eye_r, mood)
    if cheek:
        cy = cheek_y if cheek_y is not None else eye_y - 0.26
        cheeks(m, eye_x + 0.2, cy, zs(c, r, eye_x + 0.2, cy, 0.0), 0.14, cheek_color)


def mouth(m, pose, x, y, z, w=0.2):
    smile(m, x, y, z, w, "happy" if pose == "happy" else "open")


# ---------------------------------------------------------------- the farm friends

def cow(pose):
    m = critter("cow", pose)
    fur, patch, muzzle, horn = "#F7F1E8", "#7A6466", "#F2C3BC", "#EFE0B4"
    body4(m, fur, hoof="#6B5A5E")
    m.paint(sphere((0.45, 0.85, 0.15), 0.3), patch, soft=0.03)
    m.paint(sphere((-0.35, 0.7, -0.35), 0.32), patch, soft=0.03)
    m.add(capsule((0, 0.85, -0.7), (0.12, 0.45, -0.95), 0.05), fur, k=0.04)  # tail
    m.add(sphere((0.12, 0.42, -0.97), 0.09), patch, k=0.03)
    # ears out to the sides, horns above
    up = 10 if pose == "happy" else -10
    m.add(ell((0.86, 1.68, 0.1), (0.3, 0.13, 0.17), rot=(0, 0, up), sym=True), fur, k=0.06)
    m.paint(ell((0.92, 1.68, 0.22), (0.18, 0.07, 0.06), rot=(0, 0, up), sym=True), "#F2B8B2", soft=0.03)
    m.add(cone((0.38, 2.08, 0.1), (0.52, 2.38, 0.08), 0.1, 0.05, sym=True), horn, k=0.05)
    head(m, fur)
    m.paint(sphere((-0.42, 1.95, 0.45), 0.28), patch, soft=0.03)
    # a wide soft muzzle with nostrils
    mc, mr = (0, 1.3, 0.62), (0.52, 0.33, 0.34)
    m.add(ell(mc, mr), muzzle, k=0.08)
    for s in (-1,):
        m.add(ell((0.18, 1.38, zs(mc, mr, 0.18, 1.38, 0.0)), (0.06, 0.08, 0.04), sym=True), "#C98F8A")
    mouth(m, pose, 0, 1.17, zs(mc, mr, 0, 1.17, -0.01), 0.17)
    face(m, pose, eye_x=0.32, eye_y=1.76, cheek=True, cheek_y=1.5)
    return m


def pig(pose):
    m = critter("pig", pose)
    skin, snout, dark = "#F2B9B6", "#EB9EA2", "#C97D84"
    body4(m, skin, hoof="#D9918F")
    m.add(torus((0, 0.95, -0.78), 0.12, 0.04, rot=(0, 90, 0)), skin, k=0.03)  # curly tail
    head(m, skin, r=(0.8, 0.7, 0.66))
    r = (0.8, 0.7, 0.66)
    # ears flop forward
    tilt = -10 if pose == "happy" else 0
    m.add(cone((0.42, 2.0, 0.1), (0.72, 2.42, 0.35), 0.24, 0.05, sym=True), skin, k=0.05)
    m.paint(cone((0.45, 2.06, 0.3), (0.68, 2.36, 0.44), 0.13, 0.03, sym=True), "#E9939A", soft=0.03)
    # snout: a flat disc with two nostrils
    m.add(cyl((0, 1.38, 0.82), 0.12, 0.27, 0.1, rot=(90, 0, 0)), snout, k=0.06)
    m.add(ell((0.09, 1.38, 0.96), (0.045, 0.07, 0.03), sym=True), dark)
    mouth(m, pose, 0, 1.12, zs(HEAD_C, r, 0, 1.12, -0.02), 0.18)
    face(m, pose, eye_x=0.33, eye_y=1.72, r=r, cheek_y=1.42, cheek_color="#EE9696")
    return m


def sheep(pose):
    m = critter("sheep", pose)
    wool, skin, leg = "#F8F3EA", "#E9D6C4", "#7D6A70"
    body4(m, wool, legs=leg, body=(0.66, 0.55, 0.66))
    r_ = random.Random(4)
    for i in range(16):  # bumpy fleece
        a = i * 2.4
        y = 0.55 + (i % 4) * 0.15
        m.add(sphere((0.6 * math.sin(a), y, -0.15 + 0.55 * math.cos(a)), 0.2 + r_.uniform(0, 0.06)), wool, k=0.1)
    # the face is a soft light-brown oval inside a wool cap
    fc, fr = (0, 1.45, 0.3), (0.58, 0.58, 0.55)
    m.add(ell(fc, fr), skin, k=0.1)
    for x, y, rr in [(0, 2.0, 0.32), (-0.36, 1.92, 0.26), (0.36, 1.92, 0.26), (-0.18, 2.12, 0.22), (0.2, 2.12, 0.22),
                     (-0.55, 1.65, 0.2), (0.55, 1.65, 0.2)]:
        m.add(sphere((x, y, 0.2), rr), wool, k=0.06)
    up = 15 if pose == "happy" else -15
    m.add(ell((0.68, 1.52, 0.22), (0.26, 0.1, 0.13), rot=(0, 0, up), sym=True), skin, k=0.04)
    eyes(m, 0.22, 1.55, zs(fc, fr, 0.22, 1.55), 0.1, "happy" if pose == "happy" else "open")
    cheeks(m, 0.36, 1.33, zs(fc, fr, 0.36, 1.33, 0.0), 0.11)
    m.add(ell((0, 1.32, zs(fc, fr, 0, 1.32, 0.0)), (0.07, 0.05, 0.03)), "#B98B86")
    mouth(m, pose, 0, 1.2, zs(fc, fr, 0, 1.2, -0.01), 0.14)
    return m


def horse(pose, name="horse", coat="#C9926A", mane="#7E5644", muzzle="#E9C29E", stripes=False):
    """A pony in three-quarter view, so the long nose and the mane read; the zebra is the same pony in stripes."""
    m = critter(name, pose, yaw=-28)
    hoof = "#5E4E52"
    body4(m, coat, hoof=hoof, body=(0.56, 0.5, 0.7), leg_r=0.15)
    m.add(cone((0, 1.0, -0.8), (0.05, 0.42, -1.05), 0.1, 0.17), mane, k=0.05)  # tail
    m.add(capsule((0, 0.95, 0.05), (0, 1.45, 0.15), 0.34), coat, k=0.15)  # neck
    hc, hr = (0, 1.72, 0.12), (0.6, 0.62, 0.58)
    m.add(ell(hc, hr), coat, k=0.14)
    mc, mr = (0, 1.36, 0.62), (0.4, 0.34, 0.42)
    m.add(ell(mc, mr), coat, k=0.22)  # the long nose
    if stripes:
        ink = "#4F4858"
        body_zone = "max(p.y-1.12, 0.42-p.y)"
        m.paint(inter(raw("abs(sin(p.z*8.5+p.y*1.2))-0.38"), raw(body_zone)), ink, soft=0.02)            # body: upright bands
        m.paint(inter(raw("abs(sin(p.y*16.0))-0.4"), raw("max(p.y-0.42, 0.1-p.y)")), ink, soft=0.02)   # legs: rings
        m.paint(inter(raw("abs(sin(p.y*9.0-p.z*2.5))-0.36"), raw("1.12-p.y")), ink, soft=0.02)        # neck and head
    m.paint(ell((0, 1.28, 0.84), (0.36, 0.28, 0.26)), muzzle, soft=0.04)
    for s in (1, -1):
        m.add(ell((0.13 * s, 1.33, 1.0), (0.045, 0.06, 0.03)), "#8E6A60")
    mouth(m, pose, 0, 1.16, 1.0, 0.15)
    # ears up, a soft mane crest from the forehead down the neck
    m.add(cone((0.28, 2.22, 0.02), (0.38, 2.6, -0.02), 0.12, 0.04, sym=True), coat, k=0.05)
    m.paint(cone((0.29, 2.26, 0.1), (0.37, 2.52, 0.07), 0.06, 0.02, sym=True), "#E7B9A4", soft=0.02)
    crest = [(0, 2.35, 0.2), (0, 2.34, -0.05), (0, 2.22, -0.3), (0, 1.98, -0.45), (0, 1.7, -0.5), (0, 1.42, -0.42)]
    for i, (x, y, z) in enumerate(crest):
        m.add(sphere((x, y, z), 0.17 - i * 0.008), mane, k=0.07)
    m.add(cone((0.02, 2.3, 0.38), (0.1, 2.06, 0.62), 0.13, 0.05), mane, k=0.05)  # forelock
    eyes(m, 0.28, 1.86, zs(hc, hr, 0.28, 1.86), 0.11, "happy" if pose == "happy" else "open")
    cheeks(m, 0.4, 1.6, zs(hc, hr, 0.4, 1.6, 0.0), 0.12)
    return m


def hen(pose):
    m = critter("hen", pose, target=(0, 1.15, 0))
    feather, wing, comb, beak, leg = "#F7EFE3", "#E8DCCB", "#E2705F", "#F0B45E", "#EDAA5A"
    m.add(ell((0, 1.1, 0), (0.95, 0.95, 0.88)), feather)
    m.add(ell((0, 0.9, -0.85), (0.5, 0.42, 0.3), rot=(-35, 0, 0)), wing, k=0.12)  # tail
    flap = -40 if pose == "happy" else 12
    m.add(ell((0.92, 0.98, 0.0), (0.16, 0.45, 0.36), rot=(0, 0, flap), sym=True), wing, k=0.08)
    for x, y, r in [(0, 2.06, 0.15), (-0.16, 2.0, 0.12), (0.16, 2.0, 0.12)]:
        m.add(sphere((x, y, 0.2), r), comb, k=0.05)
    c, r = (0, 1.1, 0), (0.95, 0.95, 0.88)
    zb = zs(c, r, 0, 1.25, 0.0)
    if pose == "happy":
        m.add(cone((0, 1.32, zb - 0.05), (0, 1.36, zb + 0.28), 0.12, 0.03), beak, k=0.02)
        m.add(cone((0, 1.18, zb - 0.05), (0, 1.12, zb + 0.18), 0.1, 0.03), "#E19C4E", k=0.02)
    else:
        m.add(cone((0, 1.25, zb - 0.05), (0, 1.22, zb + 0.3), 0.13, 0.03), beak, k=0.02)
    m.add(ell((0, 1.0, zb - 0.06), (0.08, 0.13, 0.07)), comb, k=0.03)  # wattle
    eyes(m, 0.32, 1.48, zs(c, r, 0.32, 1.48), 0.12, "happy" if pose == "happy" else "open")
    cheeks(m, 0.55, 1.22, zs(c, r, 0.55, 1.22, 0.0), 0.15)
    m.add(capsule((0.28, 0.25, 0.2), (0.3, 0.06, 0.4), 0.06, sym=True), leg, k=0.03)
    m.add(capsule((0.3, 0.06, 0.4), (0.38, 0.05, 0.62), 0.05, sym=True), leg, k=0.03)
    m.add(capsule((0.3, 0.06, 0.4), (0.18, 0.05, 0.6), 0.05, sym=True), leg, k=0.03)
    return m


def dog(pose):
    m = critter("dog", pose)
    fur, ear, light, nose = "#EBCFA8", "#A8795C", "#FAEFDF", "#3E3346"
    body4(m, fur, body=(0.58, 0.5, 0.6))
    m.paint(ell((0, 0.75, 0.38), (0.32, 0.35, 0.2)), light, soft=0.05)
    wag = 35 if pose == "happy" else 15
    m.add(capsule((0, 0.9, -0.72), (0.35 * math.sin(math.radians(wag)), 1.35, -0.95), 0.08), fur, k=0.05)
    hc, hr = (0, 1.55, 0.2), (0.78, 0.68, 0.64)
    head(m, fur, hc, hr)
    m.paint(sphere((-0.33, 1.72, 0.75), 0.24), "#D8AE83", soft=0.04)  # an eye patch
    # floppy ears (lifted a little when happy)
    lift = 18 if pose == "happy" else 0
    m.add(ell((0.78, 1.52, 0.18), (0.2, 0.46, 0.2), rot=(0, 0, 18 + lift), sym=True), ear, k=0.05)
    mc, mr = (0, 1.33, 0.66), (0.36, 0.26, 0.26)
    m.add(ell(mc, mr), light, k=0.1)
    m.add(ell((0, 1.43, zs(mc, mr, 0, 1.43, -0.02)), (0.12, 0.08, 0.07)), nose, gloss=0.6)
    if pose == "happy":
        top = 1.27
        zz = zs(mc, mr, 0, 1.22, 0.0)
        m.add(inter(ell((0, top, zz), (0.13, 0.12, 0.05)), plane((0, 1, 0), top)), INK)
        m.add(ell((0, 1.14, zz + 0.01), (0.08, 0.08, 0.04)), "#E58C8C")
    else:
        smile(m, 0, 1.26, zs(mc, mr, 0, 1.26, -0.01), 0.14, "open")
    eyes(m, 0.3, 1.72, zs(hc, hr, 0.3, 1.72), 0.12, "happy" if pose == "happy" else "open")
    cheeks(m, 0.5, 1.45, zs(hc, hr, 0.5, 1.45, 0.0), 0.13)
    return m


def cat(pose):
    m = critter("cat", pose)
    fur, light, stripe, pink = "#B9B3C6", "#F6F1F2", "#968FA8", "#EFA6B0"
    body4(m, fur, body=(0.55, 0.5, 0.58), leg_r=0.15)
    m.paint(ell((0, 0.75, 0.36), (0.3, 0.35, 0.2)), light, soft=0.05)
    sway = 0.35 if pose == "happy" else 0.2
    m.add(capsule((0.1, 0.7, -0.7), (0.45, 1.2, -0.85), 0.08), fur, k=0.05)
    m.add(capsule((0.45, 1.2, -0.85), (0.45 + sway, 1.65, -0.8), 0.08), fur, k=0.05)
    hc, hr = (0, 1.55, 0.2), (0.82, 0.66, 0.62)
    head(m, fur, hc, hr)
    for x in (-0.12, 0.0, 0.12):  # forehead stripes
        m.paint(capsule((x, 2.05, 0.55), (x, 1.88, 0.75), 0.035), stripe, soft=0.02)
    m.add(cone((0.45, 1.98, 0.15), (0.62, 2.45, 0.1), 0.25, 0.05, sym=True), fur, k=0.06)
    m.paint(cone((0.47, 2.04, 0.32), (0.6, 2.36, 0.25), 0.13, 0.02, sym=True), pink, soft=0.03)
    mc, mr = (0, 1.38, 0.62), (0.3, 0.2, 0.2)
    m.add(ell((0.12, 1.36, 0.6), (0.19, 0.16, 0.18), sym=True), light, k=0.08)
    m.add(ell((0, 1.47, 0.79), (0.08, 0.055, 0.05)), pink, gloss=0.3)
    mouth(m, pose, 0, 1.34, 0.8, 0.14)
    for dy, ang in ((0.02, 6), (-0.06, -6)):  # whiskers
        m.add(capsule((0.32, 1.4 + dy, 0.72), (0.72, 1.4 + dy + math.sin(math.radians(ang)) * 0.4, 0.62), 0.012, sym=True), "#8E879C")
    eyes(m, 0.3, 1.68, zs(hc, hr, 0.3, 1.68), 0.13, "happy" if pose == "happy" else "open")
    cheeks(m, 0.52, 1.44, zs(hc, hr, 0.52, 1.44, 0.0), 0.12)
    return m


def frog(pose):
    m = critter("frog", pose, target=(0, 1.0, 0))
    skin, belly, dark = "#9ECB80", "#E4F0C8", "#7FB26A"
    bc, br = (0, 0.85, 0), (0.98, 0.82, 0.85)
    m.add(ell(bc, br), skin)
    m.paint(ell((0, 0.62, 0.62), (0.6, 0.45, 0.35)), belly, soft=0.06)
    for x, y, z in [(-0.5, 1.3, -0.4), (0.55, 1.15, -0.5), (0.1, 1.55, -0.3)]:
        m.paint(sphere((x, y, z), 0.15), dark, soft=0.03)
    # eye bumps on top
    m.add(sphere((0.42, 1.55, 0.25), 0.33, sym=True), skin, k=0.12)
    mood = "happy" if pose == "happy" else "open"
    eyes(m, 0.42, 1.58, 0.53, 0.14, mood)
    cheeks(m, 0.62, 1.05, zs(bc, br, 0.62, 1.05, 0.0), 0.15)
    # a wide smile
    z = zs(bc, br, 0, 1.08, 0.0)
    if pose == "happy":
        top = 1.12
        m.add(inter(ell((0, top, z - 0.03), (0.42, 0.22, 0.12)), plane((0, 1, 0), top)), INK)
        m.add(ell((0, 0.97, z + 0.02), (0.18, 0.06, 0.05)), "#E58C8C")
    else:
        m.add(arc((0, 1.2, zs(bc, br, 0, 1.0, -0.02)), 0.24, 0.035, 55, rot=(0, 0, 180)), INK)
    # feet
    for x in (0.45,):
        m.add(ell((x, 0.1, 0.55), (0.22, 0.08, 0.2), sym=True), skin, k=0.06)
        m.add(ell((x + 0.35, 0.18, -0.2), (0.25, 0.16, 0.38), sym=True), skin, k=0.08)
    if pose == "happy":
        m.add(capsule((0.8, 0.85, 0.2), (1.15, 1.35, 0.3), 0.12), skin, k=0.06)
    return m


def mouse(pose):
    m = critter("mouse", pose, target=(0, 1.1, 0))
    fur, inner, pink = "#C9BFC4", "#F2C2C8", "#EB9EAA"
    m.add(ell((0, 0.62, -0.05), (0.55, 0.55, 0.52)), fur)
    m.paint(ell((0, 0.6, 0.38), (0.32, 0.36, 0.2)), "#F2ECEA", soft=0.05)
    m.add(ell((0.28, 0.08, 0.3), (0.13, 0.07, 0.2), sym=True), pink, k=0.03)
    m.add(arc((0.0, 0.5, -0.9), 0.38, 0.04, 70, rot=(0, 90, 0)), pink)  # tail
    hc, hr = (0, 1.4, 0.15), (0.66, 0.6, 0.58)
    head(m, fur, hc, hr, k=0.12)
    big = 1.08 if pose == "happy" else 1.0
    m.add(cyl((0.6, 2.0, 0.0), 0.06, 0.4 * big, 0.05, rot=(90, 0, -10), sym=True), fur, k=0.06)
    m.paint(cyl((0.6, 2.0, 0.06), 0.04, 0.3 * big, 0.03, rot=(90, 0, -10), sym=True), inner, soft=0.03)
    m.add(cone((0, 1.32, 0.6), (0, 1.25, 0.85), 0.2, 0.08), fur, k=0.1)
    m.add(sphere((0, 1.27, 0.92), 0.075), pink, gloss=0.3)
    for dy in (0.03, -0.05):
        m.add(capsule((0.18, 1.28 + dy, 0.82), (0.55, 1.3 + dy * 2.5, 0.68), 0.011, sym=True), "#9C9096")
    mouth(m, pose, 0, 1.12, zs(hc, hr, 0, 1.12, -0.02) + 0.06, 0.14)
    eyes(m, 0.25, 1.52, zs(hc, hr, 0.25, 1.52), 0.11, "happy" if pose == "happy" else "open")
    cheeks(m, 0.44, 1.3, zs(hc, hr, 0.44, 1.3, 0.0), 0.11)
    return m


def bee(pose):
    m = critter("bee", pose, target=(0, 1.25, 0))
    m.ground = True
    yellow, stripe, wing = "#F3D266", "#5A5060", "#EAF2F6"
    bc, br = (0, 1.2, 0), (0.85, 0.8, 0.78)
    m.add(ell(bc, br), yellow)
    m.paint(raw("abs(p.y-0.78)-0.11"), stripe, soft=0.02)
    m.paint(raw("abs(p.y-0.48)-0.08"), stripe, soft=0.02)
    m.add(cone((0, 0.5, -0.5), (0, 0.32, -0.85), 0.14, 0.03), stripe, k=0.04)
    flap = 25 if pose == "happy" else 5
    m.add(ell((0.55, 2.0, -0.35), (0.48, 0.3, 0.06), rot=(0, 20, 35 + flap), sym=True), wing, gloss=0.5)
    m.add(capsule((0.18, 1.9, 0.3), (0.38, 2.35, 0.4), 0.03, sym=True), stripe)
    m.add(sphere((0.38, 2.37, 0.4), 0.08, sym=True), stripe)
    eyes(m, 0.3, 1.42, zs(bc, br, 0.3, 1.42), 0.13, "happy" if pose == "happy" else "open")
    cheeks(m, 0.52, 1.15, zs(bc, br, 0.52, 1.15, 0.0), 0.14)
    mouth(m, pose, 0, 1.12, zs(bc, br, 0, 1.12, -0.01), 0.18)
    m.add(ell((0.78, 1.05, 0.25), (0.12, 0.2, 0.12), rot=(0, 0, 30), sym=True), yellow, k=0.06)
    return m


for _n, _f in [("cow", cow), ("pig", pig), ("sheep", sheep), ("horse", horse), ("hen", hen), ("dog", dog),
               ("cat", cat), ("frog", frog), ("mouse", mouse), ("bee", bee)]:
    poses(_n, _f)


# ---------------------------------------------------------------- more friends for the ABC (one pose each)

@group("wo_zebra", A)
def zebra():
    m = horse("idle", name="zebra", coat="#F6F2EC", mane="#4F4858", muzzle="#5E5664", stripes=True)
    m.name = "wo_zebra"
    return m


def lion():
    m = critter("lion", "idle")
    m.name = "wo_lion"
    fur, mane, light = "#EDBE6E", "#C98A52", "#FBE9C8"
    body4(m, fur, body=(0.55, 0.5, 0.6), leg_r=0.16)
    m.add(capsule((0.1, 0.75, -0.7), (0.5, 1.05, -0.95), 0.06), fur, k=0.04)
    m.add(sphere((0.55, 1.1, -0.97), 0.13), mane, k=0.04)
    for i in range(14):  # a round fluffy mane behind the face
        a = i * 2 * math.pi / 14
        m.add(sphere((0.72 * math.cos(a), 1.6 + 0.66 * math.sin(a), 0.0), 0.3), mane, k=0.12)
    hc, hr = (0, 1.58, 0.25), (0.66, 0.6, 0.55)
    m.add(ell(hc, hr), fur, k=0.08)
    m.add(sphere((0.45, 2.08, 0.25), 0.15, sym=True), fur, k=0.05)
    m.add(ell((0.12, 1.38, 0.68), (0.17, 0.14, 0.14), sym=True), light, k=0.08)
    m.add(ell((0, 1.5, 0.82), (0.1, 0.07, 0.05)), "#9E6A5A", gloss=0.3)
    smile(m, 0, 1.36, 0.83, 0.14, "open")
    eyes(m, 0.26, 1.7, zs(hc, hr, 0.26, 1.7), 0.11)
    cheeks(m, 0.45, 1.45, zs(hc, hr, 0.45, 1.45, 0.0), 0.12)
    return m


group("wo_lion", A)(lion)


@group("wo_yak", A)
def yak():
    m = critter("yak", "idle")
    m.name = "wo_yak"
    fur, dark, horn, nose = "#9C7A68", "#7E6052", "#F1E4C6", "#D7B7A2"
    body4(m, fur, legs=dark, body=(0.66, 0.55, 0.7))
    for i in range(12):  # a long shaggy skirt
        a = -1.2 + i * 0.22
        m.add(cone((0.62 * math.sin(a), 0.75, 0.62 * math.cos(a) - 0.15), (0.66 * math.sin(a), 0.35, 0.66 * math.cos(a) - 0.15), 0.16, 0.06), fur, k=0.08)
    hc, hr = (0, 1.5, 0.25), (0.72, 0.62, 0.6)
    m.add(ell(hc, hr), fur, k=0.14)
    for x, y, r in [(0, 2.05, 0.26), (-0.22, 1.98, 0.22), (0.22, 1.98, 0.22)]:  # fringe
        m.add(sphere((x, y, 0.45), r), dark, k=0.08)
    m.add(arc((0.62, 2.12, 0.15), 0.28, 0.07, 75, rot=(0, 0, -60), sym=True), horn, k=0.03)
    m.add(ell((0.72, 1.72, 0.15), (0.22, 0.09, 0.12), sym=True), fur, k=0.04)
    mc, mr = (0, 1.25, 0.66), (0.4, 0.28, 0.28)
    m.add(ell(mc, mr), nose, k=0.08)
    m.add(ell((0.15, 1.3, zs(mc, mr, 0.15, 1.3, 0.0)), (0.05, 0.065, 0.03), sym=True), "#8E6A60")
    smile(m, 0, 1.13, zs(mc, mr, 0, 1.13, -0.01), 0.15, "open")
    eyes(m, 0.3, 1.62, zs(hc, hr, 0.3, 1.62), 0.11)
    cheeks(m, 0.5, 1.4, zs(hc, hr, 0.5, 1.4, 0.0), 0.12)
    return m


@group("wo_dino", A)
def dino():
    m = critter("dino", "idle", target=(0, 1.3, 0))
    m.name = "wo_dino"
    skin, belly, plate = "#8FC9AE", "#EEF3D8", "#F0A86A"
    m.add(cone((0.1, 0.5, -0.4), (0.9, 0.25, -1.2), 0.42, 0.1), skin, k=0.15)  # tail
    m.add(ell((0, 0.72, 0), (0.62, 0.68, 0.58)), skin, k=0.1)
    m.paint(ell((0, 0.72, 0.48), (0.36, 0.48, 0.25)), belly, soft=0.06)
    m.add(ell((0.3, 0.12, 0.2), (0.2, 0.14, 0.26), sym=True), skin, k=0.05)
    m.add(ell((0.55, 0.85, 0.3), (0.13, 0.22, 0.13), rot=(30, 0, 25), sym=True), skin, k=0.06)
    hc, hr = (0, 1.72, 0.15), (0.78, 0.68, 0.66)
    m.add(ell(hc, hr), skin, k=0.12)
    for i, (y, z, r) in enumerate([(2.42, 0.0, 0.17), (2.25, -0.42, 0.16), (1.85, -0.62, 0.15), (1.35, -0.62, 0.14), (0.85, -0.6, 0.13)]):
        m.add(cone((0, y, z), (0, y + 0.28 * (1 if i == 0 else 0.6), z - 0.25 * (i > 0)), r, 0.04), plate, k=0.06)
    eyes(m, 0.3, 1.84, zs(hc, hr, 0.3, 1.84), 0.12)
    cheeks(m, 0.52, 1.58, zs(hc, hr, 0.52, 1.58, 0.0), 0.14)
    smile(m, 0, 1.5, zs(hc, hr, 0, 1.5, -0.01), 0.24, "happy")
    for s in (1, -1):
        m.add(sphere((0.12 * s, 1.68, zs(hc, hr, 0.12 * s, 1.68, 0.0)), 0.03), "#5E8A76")
    return m


@group("wo_hedgehog", A)
def hedgehog():
    m = critter("hedgehog", "idle", target=(0, 0.95, 0))
    m.name = "wo_hedgehog"
    spike, face, nose = "#A08070", "#F0D9BE", INK
    bc, br = (0, 0.8, -0.15), (0.9, 0.75, 0.82)
    m.add(ell(bc, br), spike)
    r_ = random.Random(8)
    for i in range(46):  # soft clay spikes pointing back and out
        u, v = r_.uniform(-1, 1), r_.uniform(0.15, 1)
        a = r_.uniform(0, 2 * math.pi)
        d = (math.cos(a) * 0.9, 0.4 + abs(math.sin(a)) * 0.6, -0.7 + math.sin(a) * 0.2)
        n = math.sqrt(sum(c * c for c in d))
        d = tuple(c / n for c in d)
        base = (bc[0] + d[0] * 0.72, bc[1] + d[1] * 0.62, bc[2] + d[2] * 0.66)
        if base[2] > 0.25:
            continue
        tip = tuple(b + dd * 0.38 for b, dd in zip(base, (d[0], d[1], d[2] - 0.35)))
        m.add(cone(base, tip, 0.12, 0.03), spike, k=0.05)
    fc, fr = (0, 0.72, 0.48), (0.55, 0.5, 0.42)
    m.add(ell(fc, fr), face, k=0.15)
    m.add(cone((0, 0.62, 0.7), (0, 0.55, 1.05), 0.22, 0.06), face, k=0.1)
    m.add(sphere((0, 0.56, 1.1), 0.08), nose, gloss=0.5)
    m.add(ell((0.42, 1.12, 0.35), (0.12, 0.1, 0.07), sym=True), face, k=0.04)
    eyes(m, 0.24, 0.9, zs(fc, fr, 0.24, 0.9) + 0.02, 0.09)
    cheeks(m, 0.38, 0.66, zs(fc, fr, 0.38, 0.66, 0.0), 0.1)
    smile(m, 0, 0.42, zs(fc, fr, 0, 0.42, -0.02) + 0.06, 0.13, "open")
    m.add(ell((0.3, 0.08, 0.4), (0.14, 0.07, 0.16), sym=True), face, k=0.04)
    return m


@group("wo_bird", A)
def bird():
    m = critter("bird", "idle", target=(0, 1.0, 0))
    m.name = "wo_bird"
    body, belly, wing, beak = "#93B7DD", "#F3EEE6", "#7499C4", "#F0A15A"
    bc, br = (0, 1.0, 0), (0.78, 0.82, 0.72)
    m.add(ell(bc, br), body)
    m.paint(ell((0, 0.78, 0.5), (0.5, 0.5, 0.4)), belly, soft=0.07)
    m.add(ell((0.74, 0.9, 0.05), (0.16, 0.4, 0.34), rot=(-20, 0, 25), sym=True), wing, k=0.06)
    m.add(ell((0, 1.15, -0.85), (0.22, 0.12, 0.45), rot=(-40, 0, 0)), wing, k=0.06)  # tail, up
    m.add(capsule((0, 1.8, 0.1), (0.1, 2.02, 0.22), 0.06), wing, k=0.06)
    m.add(capsule((0, 1.8, 0.05), (-0.12, 1.98, 0.12), 0.05), wing, k=0.06)
    zb = zs(bc, br, 0, 1.1, 0.0)
    m.add(cone((0, 1.1, zb - 0.05), (0, 1.06, zb + 0.24), 0.11, 0.02), beak, k=0.02)
    eyes(m, 0.28, 1.3, zs(bc, br, 0.28, 1.3), 0.11)
    cheeks(m, 0.48, 1.05, zs(bc, br, 0.48, 1.05, 0.0), 0.12)
    m.add(capsule((0.22, 0.25, 0.2), (0.25, 0.04, 0.35), 0.05, sym=True), beak, k=0.02)
    return m


@group("wo_clown", A)
def clown():
    m = critter("clown", "idle", target=(0, 1.3, 0))
    m.name = "wo_clown"
    skin, nose, suit, dots = "#F6DCC8", "#E2705F", "#9FC6E8", "#F3D266"
    m.add(ell((0, 0.65, 0), (0.62, 0.62, 0.55)), suit)
    for x, y in [(0, 0.95), (-0.25, 0.55), (0.28, 0.6), (0.0, 0.3)]:
        m.paint(sphere((x, y, 0.5), 0.09), dots, soft=0.02)
    for i in range(10):  # ruffled collar
        a = i * 2 * math.pi / 10
        m.add(sphere((0.5 * math.sin(a), 1.2, 0.5 * math.cos(a)), 0.17), "#FBF4E8", k=0.06)
    m.add(ell((0.55, 0.65, 0.2), (0.16, 0.28, 0.16), rot=(0, 0, 20), sym=True), suit, k=0.06)
    m.add(ell((0.3, 0.1, 0.25), (0.22, 0.12, 0.3), sym=True), nose, k=0.04)  # shoes
    hc, hr = (0, 1.75, 0.1), (0.7, 0.62, 0.62)
    m.add(ell(hc, hr), skin, k=0.08)
    for x, y, r in [(0.72, 1.92, 0.24), (0.82, 1.62, 0.22), (0.58, 2.15, 0.2)]:  # hair puffs
        m.add(sphere((x, y, -0.05), r, sym=True), "#F0A15A", k=0.1)
    m.add(cone((0, 2.25, 0.05), (0.08, 2.85, 0.0), 0.3, 0.05), "#A98DCC", k=0.04)
    m.add(sphere((0.08, 2.9, 0.0), 0.11), dots)
    m.add(sphere((0, 1.68, zs(hc, hr, 0, 1.68, 0.05)), 0.15), nose, gloss=0.35)
    eyes(m, 0.27, 1.88, zs(hc, hr, 0.27, 1.88), 0.1)
    cheeks(m, 0.44, 1.62, zs(hc, hr, 0.44, 1.62, 0.0), 0.14, "#F2A0A0")
    smile(m, 0, 1.44, zs(hc, hr, 0, 1.44, -0.01), 0.26, "happy")
    return m


@group("wo_jellyfish", A)
def jellyfish():
    m = critter("jellyfish", "idle", target=(0, 1.3, 0))
    m.name = "wo_jellyfish"
    m.ground = False
    bell, inner = "#F1B6CF", "#F8D9E6"
    dome = inter(ell((0, 1.6, 0), (0.95, 0.8, 0.85)), plane((0, -1, 0), -1.35))
    m.add(dome, bell, gloss=0.3)
    m.add(torus((0, 1.36, 0), 0.82, 0.12), bell, k=0.1)
    for i in range(6):  # wavy tentacles
        a = i * 2 * math.pi / 6 + 0.3
        x0, z0 = 0.5 * math.cos(a), 0.45 * math.sin(a)
        prev = (x0, 1.3, z0)
        for j in range(1, 5):
            cur = (x0 + 0.1 * math.sin(j * 1.7 + i), 1.3 - j * 0.24, z0)
            m.add(capsule(prev, cur, 0.07 - j * 0.008), inner if i % 2 else bell, k=0.05)
            prev = cur
    eyes(m, 0.3, 1.7, zs((0, 1.6, 0), (0.95, 0.8, 0.85), 0.3, 1.7), 0.11)
    cheeks(m, 0.52, 1.5, zs((0, 1.6, 0), (0.95, 0.8, 0.85), 0.52, 1.5, 0.0), 0.13)
    smile(m, 0, 1.58, zs((0, 1.6, 0), (0.95, 0.8, 0.85), 0, 1.58, -0.01), 0.18, "open")
    return m


# ---------------------------------------------------------------- things

@group("wo_jam", S)
def jam():
    m = obj("jam", target=(0, 0.85, 0), pitch=14)
    glass = shell(cyl((0, 0.68, 0), 0.65, 0.68, 0.22), 0.03)
    m.add(inter(glass, plane((0, 1, 0), 1.22)), "#D8646A", gloss=0.7)  # jam seen through the glass
    m.paint(raw("0.98-p.y"), "#EAF1F2", soft=0.02)                       # the empty glass above it
    # a label with a little strawberry heart
    m.paint(inter(raw("abs(p.y-0.62)-0.2"), raw("0.25-p.z")), "#FBF4E8", soft=0.01)
    m.add(flat((0, 0.6, 0.7), "heart", 0.12, 0.03, 0.02), "#D2585E")
    # a gingham cloth over the lid, tied with a string
    m.add(cyl((0, 1.32, 0), 0.1, 0.8, 0.08), "#F8F2EA")
    m.add(inter(ell((0, 1.3, 0), (0.86, 0.32, 0.86)), plane((0, -1, 0), -1.0)), "#F8F2EA", k=0.05)
    m.paint(inter(raw("min(abs(fract(p.x*3.0)-0.5)-0.25, abs(fract(p.z*3.0+p.y*1.5)-0.5)-0.25)"), raw("1.06-p.y")), "#E68C8A", soft=0.01)
    m.add(torus((0, 1.18, 0), 0.7, 0.04), "#C9A27A", k=0.02)
    return m


@group("wo_nest", S)
def nest():
    m = obj("nest", target=(0, 0.6, 0), pitch=24)
    m.add(torus((0, 0.45, 0), 0.75, 0.32), "#B68A62")
    m.add(cyl((0, 0.25, 0), 0.15, 0.85, 0.12), "#B68A62", k=0.1)
    m.paint(raw("abs(sin(atan(p.x,p.z)*13.0+p.y*9.0))-0.35"), "#9C7350", soft=0.03)
    for x, z, rx in [(-0.25, 0.05, -10), (0.25, -0.08, 12), (0.02, 0.3, 0)]:
        m.add(ell((x, 0.62, z), (0.24, 0.3, 0.24), rot=(rx, 0, rx)), "#A9CDE6", k=0.02)
    for x, y in [(0.8, 0.6), (-0.7, 0.75)]:
        m.add(capsule((x, y, 0.4), (x + 0.3, y + 0.15, 0.55), 0.03), "#9C7350")
    return m


@group("wo_quilt", S)
def quilt():
    m = obj("quilt", target=(0, 0.75, 0), pitch=22, yaw=18)
    cols = ["#F1ACC3", "#9FC6E8", "#F3D266", "#A9D49B"]
    for i, c in enumerate(cols[:3]):  # a folded stack: three soft layers
        y = 0.22 + i * 0.36
        m.add(box((0, y, 0), (0.95 - i * 0.02, 0.15, 0.68), 0.14), "#FBF4E8", k=0.04)
    patch = "max(abs(fract(p.x*1.6)-0.5)-0.38, abs(fract(p.z*1.6+p.y*0.0)-0.5)-0.38)"
    m.paint(raw(f"max({patch}, sin(floor(p.x*1.6)*2.1+floor(p.z*1.6)*1.3+floor(p.y*2.8))*-1.0)"), cols[0], soft=0.01)
    m.paint(raw(f"max({patch}, sin(floor(p.x*1.6)*1.7+floor(p.z*1.6)*2.9+floor(p.y*2.8)*0.7+1.0)*-1.0+0.3)"), cols[1], soft=0.01)
    m.paint(raw(f"max({patch}, cos(floor(p.x*1.6)*2.3+floor(p.z*1.6)*1.1+floor(p.y*2.8)*1.9)*-1.0+0.5)"), cols[2], soft=0.01)
    return m


@group("wo_van", S)
def van():
    m = obj("van", target=(0, 0.85, 0), pitch=14, yaw=-28)
    body, top, glass = "#9FD3C1", "#FBF4E8", "#CFE3EE"
    m.add(box((0, 0.75, 0), (1.15, 0.5, 0.6), 0.3), body)
    m.paint(raw("(p.y-0.95)*-1.0"), top, soft=0.01)
    m.paint(raw("abs(p.y-0.92)-0.035"), "#F0A15A", soft=0.01)
    for x in (-0.55, 0.05):
        m.add(box((x, 1.0, 0.0), (0.24, 0.17, 0.62), 0.1), glass, gloss=0.6)
    m.add(box((0.85, 1.0, 0.0), (0.24, 0.17, 0.62), 0.1), glass, gloss=0.6)
    m.add(box((1.16, 1.0, 0.0), (0.04, 0.2, 0.45), 0.08), glass, gloss=0.6)
    for x in (-0.68, 0.68):
        m.add(cyl((x, 0.3, 0), 0.62, 0.27, 0.1, rot=(90, 0, 0)), "#4A4550")
        m.add(cyl((x, 0.3, 0), 0.64, 0.1, 0.04, rot=(90, 0, 0)), "#D8D2CB")
    m.add(sphere((1.18, 0.6, 0.38), 0.1), "#F3E2B0", emit=0.4)
    m.add(box((1.24, 0.45, 0), (0.06, 0.06, 0.5), 0.05), "#D8D2CB")
    return m


@group("wo_xylophone", S)
def xylophone():
    m = obj("xylophone", target=(0, 0.6, 0), pitch=32, yaw=0)
    cols = ["#E2705F", "#F0A15A", "#F3D266", "#82C074", "#73A6DB", "#A98DCC"]
    for s in (1, -1):
        m.add(box((0, 0.18, 0.42 * s), (1.35, 0.1, 0.07), 0.05, rot=(0, 7 * s, 0)), "#C99A6B")
    for i, c in enumerate(cols):
        x = -1.05 + i * 0.42
        half = 0.62 - i * 0.06
        m.add(box((x, 0.36, 0), (0.17, 0.08, half), 0.07), c, gloss=0.25)
        for s in (1, -1):
            m.add(sphere((x, 0.45, (half - 0.12) * s), 0.035), "#D8D2CB")
    for x, a in [(-0.3, 25), (0.6, -20)]:
        m.add(capsule((x, 0.6, 0.85), (x + 0.5 * math.sin(math.radians(a)), 0.55, 1.45), 0.04), "#C99A6B")
        m.add(sphere((x, 0.6, 0.82), 0.12), "#F1ACC3")
    return m


@group("wo_yoyo", S)
def yoyo():
    m = obj("yoyo", target=(0, 1.0, 0), ground=False)
    m.add(cyl((0, 0.7, 0.14), 0.12, 0.62, 0.1, rot=(90, 0, 0)), "#E2705F", gloss=0.25)
    m.add(cyl((0, 0.7, -0.14), 0.12, 0.62, 0.1, rot=(90, 0, 0)), "#E2705F", gloss=0.25)
    m.add(cyl((0, 0.7, 0), 0.1, 0.25, 0.03, rot=(90, 0, 0)), "#F3D266")
    m.add(cyl((0, 0.7, 0.27), 0.02, 0.32, 0.02, rot=(90, 0, 0)), "#F3D266", k=0.01)
    m.add(flat((0, 0.7, 0.3), "star", 0.14, 0.02, 0.015), "#FBF4E8")
    m.add(capsule((0, 1.32, 0.0), (0.05, 2.0, 0), 0.025), "#B8AFA6")
    m.add(torus((0.05, 2.12, 0), 0.12, 0.03, rot=(90, 0, 0)), "#B8AFA6")
    return m


@group("wo_jacket", S)
def jacket():
    m = obj("jacket", target=(0, 1.0, 0), ground=False)
    coat, trim = "#F3D266", "#EBC555"
    m.add(box((0, 0.95, 0), (0.6, 0.7, 0.28), 0.25), coat)
    m.add(capsule((0.55, 1.45, 0), (0.95, 0.45, 0.05), 0.2, sym=True), coat, k=0.1)
    m.add(cyl((0.98, 0.38, 0.05), 0.06, 0.19, 0.04, rot=(0, 0, 22), sym=True), trim)
    m.add(ell((0, 1.65, -0.15), (0.42, 0.3, 0.2)), trim, k=0.06)  # hood behind the collar
    m.paint(raw("abs(p.x)-0.025"), "#C9A44A", soft=0.01)
    for y in (1.35, 1.0, 0.65):
        m.add(sphere((0.15, y, 0.3), 0.06), "#73A6DB")
    m.add(box((0.32, 0.5, 0.27), (0.15, 0.1, 0.03), 0.03, sym=True), trim)
    return m


@group("wo_clock", S)
def clock():
    m = obj("clock", target=(0, 1.0, 0), pitch=8)
    body, face = "#73A6DB", "#FBF4E8"
    m.add(cyl((0, 1.0, 0), 0.32, 0.8, 0.14, rot=(90, 0, 0)), body)
    m.add(cyl((0, 1.0, 0.3), 0.04, 0.66, 0.03, rot=(90, 0, 0)), face)
    for i in range(12):
        a = i * math.pi / 6
        m.add(sphere((0.53 * math.sin(a), 1.0 + 0.53 * math.cos(a), 0.34), 0.04 if i % 3 else 0.06), INK)
    m.add(capsule((0, 1.0, 0.36), (0, 1.38, 0.36), 0.035), INK)
    m.add(capsule((0, 1.0, 0.36), (0.26, 0.9, 0.36), 0.035), INK)
    m.add(sphere((0, 1.0, 0.37), 0.06), "#E2705F")
    for s in (1, -1):
        m.add(sphere((0.5 * s, 1.85, 0), 0.24), "#F3D266", gloss=0.4)
        m.add(capsule((0.45 * s, 0.25, 0), (0.6 * s, 0.03, 0.05), 0.07), body)
    m.add(capsule((0, 1.82, 0), (0, 1.98, 0), 0.06), body)
    m.add(arc((0, 1.75, 0), 0.5, 0.05, 50), "#B8AFA6")
    return m


# ---------------------------------------------------------------- icons

def letter(m, ch, c, s, color, th=0.07):
    """A raised clay letter on a face whose normal is +z, centred at c, height ~1.2·s."""
    x, y, z = c

    def seg(a, b):
        m.add(capsule((x + a[0] * s, y + a[1] * s, z), (x + b[0] * s, y + b[1] * s, z), th), color, k=0.02)

    if ch == "A":
        seg((-0.42, -0.55), (0, 0.6)); seg((0, 0.6), (0.42, -0.55)); seg((-0.22, -0.08), (0.22, -0.08))
    elif ch == "B":
        seg((-0.35, -0.6), (-0.35, 0.6))
        for cy, r in ((0.3, 0.3), (-0.3, 0.3)):
            seg((-0.35, cy + r), (-0.05, cy + r)); seg((-0.35, cy - r), (-0.05, cy - r))
            m.add(arc((x - 0.05 * s, y + cy * s, z), r * s, th, 90, rot=(0, 0, -90)), color, k=0.02)
    elif ch == "C":
        m.add(arc((x + 0.05 * s, y, z), 0.55 * s, th, 125, rot=(0, 0, 90)), color, k=0.02)


@group("wo_blocks", S)
def blocks():
    m = obj("blocks", target=(0, 1.15, 0), pitch=14, distance=13)
    cream = "#FBF4E8"
    for (x, y, rot, col, ch) in [(-0.78, 0.62, 10, "#73A6DB", "A"), (0.78, 0.62, -8, "#E2705F", "C"), (0.0, 1.88, 4, "#F3D266", "B")]:
        m.add(box((x, y, 0), (0.6, 0.6, 0.6), 0.12, rot=(0, rot, 0)), col)
        letter(m, ch, (x + math.sin(math.radians(rot)) * 0.62, y, 0.62), 0.75, cream, 0.08)
    return m


@group("wo_crayon", S)
def crayon():
    m = obj("crayon", target=(0, 0.45, 0.1), pitch=34)
    m.add(box((0, 0.05, 0.1), (1.3, 0.05, 0.95), 0.04), "#FBF4E8")
    for i in range(8):  # the dotted guide of a letter
        t = i / 7
        m.add(sphere((-0.95 + t * 1.6, 0.12, -0.5 - 0.15 * math.sin(t * 3.1)), 0.05), "#C9C2EA")
    wave = lambda t: (-1.1 + t * 1.02, 0.11, 0.38 + 0.12 * math.sin(t * 7.85) + 0.12 * t)
    for i in range(16):  # the soft line the crayon just drew
        m.add(capsule(wave(i / 16), wave((i + 1) / 16), 0.06), "#A98DCC", k=0.02)
    a, b = (1.15, 0.6, 0.0), (0.2, 0.32, 0.45)
    tip = tuple(bb + 0.3 * (bb - aa) for aa, bb in zip(a, b))
    m.add(capsule(a, b, 0.19), "#A98DCC")
    m.add(cone(b, tip, 0.19, 0.04), "#A98DCC", k=0.03)
    mid = tuple((aa + bb) / 2 for aa, bb in zip(a, b))
    m.paint(sphere(mid, 0.36), "#FBF4E8", soft=0.01)
    m.paint(sphere(mid, 0.12), "#C9C2EA", soft=0.01)
    return m


# ---------------------------------------------------------------- the farm (Animal friends)

@group("bg_wo_farm", BG)
def farm():
    from models.scenes import meadow_base
    m = scene("wo_farm", sky=("#CFE5EC", "#F2EEDF"))
    meadow_base(m, "#B3D49C", "#C8DEB2", "#D8E8C6")
    # a red barn on the left, far enough back to stay soft
    bx, bz = -15.5, -11.0
    m.add(box((bx, 3.0, bz), (3.6, 3.0, 2.6), 0.25), "#D98A78")
    roof = inter(box((bx, 6.0, bz), (2.75, 2.75, 2.9), 0.2, rot=(0, 0, 45)), plane((0, -1, 0), -5.8))
    m.add(roof, "#9A7A84", k=0.05)
    m.add(box((bx, 1.7, bz + 2.62), (1.5, 1.7, 0.08), 0.1), "#F6EDE1")
    m.add(box((bx, 1.7, bz + 2.68), (1.25, 1.45, 0.05), 0.08), "#C77565")
    for s in (1, -1):
        m.add(capsule((bx - 1.2 * s, 0.35, bz + 2.74), (bx + 1.2 * s, 3.05, bz + 2.74), 0.1), "#F6EDE1")
    m.add(box((bx, 4.6, bz + 2.62), (0.7, 0.55, 0.08), 0.1), "#F6EDE1")
    # a low white fence across the back
    for i in range(-10, 12, 2):
        m.add(box((i, 0.75, -7.5), (0.18, 0.75, 0.1), 0.09), "#F7F1E6")
    for y in (0.55, 1.15):
        m.add(box((0, y, -7.6), (10.4, 0.09, 0.06), 0.04), "#F7F1E6")
    # round hay bales on the right, a little pond in the middle distance
    for x, z, r in [(13.5, -6.5, 1.3), (16.2, -7.6, 1.2), (14.8, -4.6, 1.1)]:
        m.add(cyl((x, r, z), 0.95, r, 0.35, rot=(90, 0, 0)), "#EBCB7E")
        m.paint(cyl((x, r, z + 0.95), 0.05, r * 0.6, 0.0, rot=(90, 0, 0)), "#DDB866", soft=0.05)
    tree(m, 18.5, -12, 1.5, fruit="#E2705F")
    tree(m, -6.5, -19, 1.1, "#9CC88A")
    cloud(m, -4, 12, -34, 1.6)
    cloud(m, 13, 13.5, -38, 2.0)
    r = random.Random(7)
    for _ in range(22):
        x = r.choice([-1, 1]) * r.uniform(7, 16)
        flower(m, x, r.uniform(-6, 4), r.choice(["#F1ACC3", "#F3D266", "#FBF4E6", "#A98DCC"]), r.uniform(0.8, 1.2))
    return m
