"""
The Little Meadow friends. Each is a chibi clay figure: big head, small body, soft muted colours.
Poses: idle, blink, talk, happy, sleep, wave (see models.POSES).
"""
from __future__ import annotations

from models import POSES, group
from sdf import (Model, arc, capsule, cheeks, cone, cyl, ell, eyes, inter, plane, smile, sphere, torus)

EYE_MOOD = {"idle": "open", "blink": "blink", "talk": "open", "happy": "happy", "sleep": "sleep", "wave": "open"}
MOUTH = {"idle": "open", "blink": "open", "talk": "talk", "happy": "happy", "sleep": "sleep", "wave": "happy"}


# ---------------------------------------------------------------- Pip: a round honey bird, the guide

def pip(pose: str) -> Model:
    m = Model(f"ch_pip_{pose}", target=(0, 1.15, 0), distance=13)
    body, belly, wing, beak, feet = "#F1C56E", "#FBEAC6", "#E3A955", "#EE9461", "#E9925F"
    m.add(ell((0, 1.15, 0), (1.08, 1.02, 0.98)), body)
    m.paint(ell((0, 0.9, 0.55), (0.72, 0.62, 0.6)), belly, soft=0.08)
    # tuft: three soft feathers
    m.add(cone((0, 2.05, 0.05), (0.02, 2.5, 0.2), 0.14, 0.06), wing, k=0.08)
    m.add(cone((0.12, 2.02, 0.0), (0.38, 2.38, 0.05), 0.11, 0.05), wing, k=0.06)
    m.add(cone((-0.12, 2.02, 0.0), (-0.36, 2.34, 0.08), 0.11, 0.05), wing, k=0.06)
    # wings: the right one waves
    if pose == "wave":
        m.add(ell((1.08, 1.55, 0.1), (0.18, 0.5, 0.36), rot=(0, 0, -32)), wing, k=0.06)
        m.add(ell((-1.0, 1.0, 0.05), (0.17, 0.5, 0.38), rot=(0, 0, -12)), wing, k=0.08)
    elif pose == "sleep":
        m.add(ell((1.0, 0.98, 0.05), (0.17, 0.5, 0.38), rot=(0, 0, 10), sym=True), wing, k=0.08)
    else:
        m.add(ell((1.0, 1.0, 0.05), (0.17, 0.5, 0.38), rot=(0, 0, 12), sym=True), wing, k=0.08)
    # tail
    m.add(ell((0, 0.75, -0.95), (0.35, 0.18, 0.35), rot=(-30, 0, 0)), wing, k=0.1)
    # face
    eyes(m, 0.36, 1.42, 0.86, 0.13, EYE_MOOD[pose])
    cheeks(m, 0.62, 1.12, 0.8, 0.17)
    if pose in ("talk", "happy", "wave"):
        # open beak: two halves
        m.add(cone((0, 1.22, 0.92), (0, 1.25, 1.28), 0.15, 0.03), beak, k=0.03)
        m.add(cone((0, 1.06, 0.9), (0, 1.0, 1.18), 0.12, 0.03), "#D9774E", k=0.03)
        m.add(sphere((0, 1.12, 0.98), 0.07), "#B95A45")
    else:
        m.add(cone((0, 1.15, 0.92), (0, 1.12, 1.3), 0.16, 0.03), beak, k=0.03)
    # feet
    for dz in (0.0,):
        m.add(capsule((0.32, 0.12, 0.25 + dz), (0.38, 0.06, 0.55), 0.07, sym=True), feet, k=0.02)
        m.add(capsule((0.32, 0.12, 0.25), (0.18, 0.06, 0.52), 0.06, sym=True), feet, k=0.02)
    return m


@group("ch_pip")
def _pip():
    return [pip(p) for p in POSES]


# ---------------------------------------------------------------- Tuka: a curious fox kit

def tuka(pose: str) -> Model:
    m = Model(f"ch_tuka_{pose}", target=(0, 1.45, 0), distance=16)
    fur, cream, dark, inner = "#DC8B62", "#FBEDDF", "#74514C", "#F1C3AC"
    # tail first, so the body blends over its root
    m.add(cone((0.35, 0.35, -0.45), (1.05, 0.95, -0.5), 0.22, 0.36), fur, k=0.1)
    m.add(sphere((1.15, 1.25, -0.45), 0.3), cream, k=0.18)
    # body
    m.add(ell((0, 0.72, 0), (0.62, 0.68, 0.55)), fur, k=0.1)
    m.paint(ell((0, 0.78, 0.45), (0.36, 0.45, 0.3)), cream, soft=0.06)
    # arms
    if pose == "wave":
        m.add(capsule((0.5, 1.0, 0.15), (0.95, 1.55, 0.25), 0.15), fur, k=0.08)
        m.add(ell((-0.55, 0.82, 0.22), (0.16, 0.3, 0.16), rot=(0, 0, -20)), fur, k=0.08)
    else:
        m.add(ell((0.55, 0.82, 0.22), (0.16, 0.3, 0.16), rot=(0, 0, 20), sym=True), fur, k=0.08)
    # feet
    m.add(ell((0.3, 0.12, 0.18), (0.2, 0.14, 0.26), sym=True), dark, k=0.04)
    # head
    m.add(ell((0, 1.8, 0.05), (0.95, 0.8, 0.8)), fur, k=0.12)
    m.paint(ell((0, 1.5, 0.55), (0.78, 0.42, 0.5)), cream, soft=0.08)
    m.add(ell((0, 1.56, 0.74), (0.32, 0.24, 0.28)), cream, k=0.12)
    m.add(sphere((0, 1.66, 1.02), 0.09), "#3A2B30", gloss=0.6)
    # ears
    m.add(cone((0.45, 2.3, 0.0), (0.8, 2.95, -0.05), 0.3, 0.06, sym=True), fur, k=0.08)
    m.paint(cone((0.48, 2.36, 0.22), (0.78, 2.86, 0.12), 0.17, 0.03, sym=True), inner, soft=0.03)
    m.paint(sphere((0.82, 2.98, -0.05), 0.2, sym=True), dark, soft=0.04)
    # face
    eyes(m, 0.37, 1.9, 0.76, 0.12, EYE_MOOD[pose])
    cheeks(m, 0.64, 1.62, 0.64, 0.15)
    smile(m, 0, 1.44, 0.98, 0.2, MOUTH[pose])
    return m


@group("ch_tuka")
def _tuka():
    return [tuka(p) for p in POSES]


# ---------------------------------------------------------------- Moss: a slow, kind turtle with a leaf sprout

def moss(pose: str) -> Model:
    m = Model(f"ch_moss_{pose}", target=(0, 1.2, 0), distance=16, pitch=12)
    skin, shell, plate, rim, leaf = "#A9CFA1", "#7FA37B", "#9DBD8F", "#D9CC95", "#8CC07A"
    # shell behind the head
    m.add(inter(ell((0, 0.75, -0.35), (1.25, 1.0, 1.05)), plane((0, -1, 0), -0.35)), shell)
    for i, (x, y, z) in enumerate([(0, 1.6, -0.4), (0.7, 1.2, -0.2), (-0.7, 1.2, -0.2), (0.95, 0.75, 0.15), (-0.95, 0.75, 0.15)]):
        m.paint(sphere((x, y, z), 0.42), plate, soft=0.05)
    m.add(torus((0, 0.38, -0.35), 1.12, 0.14, rot=(0, 0, 0)), rim, k=0.08)
    # legs
    m.add(ell((0.85, 0.22, 0.5), (0.24, 0.2, 0.26), sym=True), skin, k=0.06)
    m.add(ell((0.95, 0.22, -0.8), (0.22, 0.18, 0.24), sym=True), skin, k=0.06)
    # neck + head
    m.add(capsule((0, 0.55, 0.35), (0, 1.0, 0.6), 0.32), skin, k=0.12)
    m.add(ell((0, 1.35, 0.75), (0.68, 0.6, 0.58)), skin, k=0.14)
    if pose == "wave":
        m.add(capsule((0.8, 0.3, 0.55), (1.25, 0.95, 0.65), 0.16), skin, k=0.08)
    # sprout
    m.add(capsule((0, 1.9, 0.7), (0.02, 2.2, 0.68), 0.04), "#6E9E62", k=0.03)
    m.add(ell((0.17, 2.28, 0.68), (0.2, 0.08, 0.12), rot=(0, 0, 25)), leaf, k=0.03)
    m.add(ell((-0.15, 2.25, 0.68), (0.17, 0.07, 0.1), rot=(0, 0, -30)), leaf, k=0.03)
    # face
    eyes(m, 0.26, 1.48, 1.25, 0.11, EYE_MOOD[pose])
    cheeks(m, 0.46, 1.25, 1.18, 0.13)
    smile(m, 0, 1.17, 1.32, 0.2, MOUTH[pose])
    return m


@group("ch_moss")
def _moss():
    return [moss(p) for p in POSES]


# ---------------------------------------------------------------- Luma: a sleepy lavender owl, keeper of bedtime

def luma(pose: str) -> Model:
    m = Model(f"ch_luma_{pose}", target=(0, 1.3, 0), distance=15)
    feather, belly, disc, wing, gold = "#9D97D2", "#ECE7F7", "#F6EFE0", "#827BBE", "#E7B565"
    m.add(ell((0, 1.15, 0), (0.95, 1.12, 0.85)), feather)
    m.paint(ell((0, 0.85, 0.55), (0.6, 0.62, 0.45)), belly, soft=0.06)
    for x, y in [(-0.2, 1.05), (0.2, 1.05), (0, 0.8), (-0.32, 0.72), (0.32, 0.72)]:
        m.add(arc((x, y, 0.78 - abs(x) * 0.25), 0.1, 0.022, 60, rot=(0, 0, 180)), "#C9C2EA", k=0.01)
    # ear tufts
    m.add(cone((0.5, 2.0, 0.0), (0.78, 2.45, -0.05), 0.22, 0.06, sym=True), feather, k=0.1)
    # wings
    if pose == "wave":
        m.add(ell((1.05, 1.4, 0.05), (0.2, 0.55, 0.4), rot=(0, 0, -35)), wing, k=0.06)
        m.add(ell((-0.9, 1.0, 0.0), (0.2, 0.6, 0.45), rot=(0, 0, -10)), wing, k=0.08)
    else:
        m.add(ell((0.9, 1.0, 0.0), (0.2, 0.6, 0.45), rot=(0, 0, 10), sym=True), wing, k=0.08)
    # face discs, eyes, beak
    m.add(ell((0.34, 1.55, 0.6), (0.38, 0.38, 0.2), sym=True), disc, k=0.06)
    eyes(m, 0.34, 1.56, 0.76, 0.16, EYE_MOOD[pose])
    if pose in ("talk", "happy", "wave"):
        m.add(cone((0, 1.42, 0.86), (0, 1.3, 1.0), 0.1, 0.03), gold, k=0.02)
        m.add(ell((0, 1.24, 0.86), (0.08, 0.05, 0.05)), "#B95A45")
    else:
        m.add(cone((0, 1.42, 0.86), (0, 1.25, 0.98), 0.1, 0.03), gold, k=0.02)
    cheeks(m, 0.62, 1.3, 0.68, 0.13, "#E9A9B5")
    # feet
    m.add(capsule((0.28, 0.08, 0.45), (0.3, 0.06, 0.62), 0.07, sym=True), gold, k=0.02)
    m.add(capsule((0.28, 0.08, 0.45), (0.18, 0.06, 0.6), 0.06, sym=True), gold, k=0.02)
    if pose == "sleep":  # a soft nightcap
        m.add(cone((0.1, 2.05, 0.0), (0.75, 2.6, -0.1), 0.42, 0.08), "#7E8FC9", k=0.05)
        m.add(sphere((0.82, 2.62, -0.1), 0.13), "#F3E7C9")
    return m


@group("ch_luma")
def _luma():
    return [luma(p) for p in POSES]


# ---------------------------------------------------------------- Bo: a gentle powder-blue elephant who loves music and letters

def bo(pose: str) -> Model:
    m = Model(f"ch_bo_{pose}", target=(0, 1.3, 0), distance=16)
    skin, inner, nail = "#A3BDD8", "#EDC6CC", "#F4EBDD"
    # ears behind the head
    m.add(ell((0.95, 1.62, -0.2), (0.6, 0.66, 0.12), rot=(0, -28, -8), sym=True), skin, k=0.05)
    m.paint(ell((1.0, 1.6, -0.02), (0.42, 0.48, 0.12), rot=(0, -28, -8), sym=True), inner, soft=0.04)
    m.add(ell((0, 0.62, 0), (0.7, 0.6, 0.6)), skin)
    m.add(ell((0, 1.58, 0.05), (0.88, 0.82, 0.78)), skin, k=0.15)
    # hair tuft
    for x in (-0.1, 0.0, 0.1):
        m.add(capsule((x, 2.3, 0.05), (x * 2.2, 2.55, 0.1), 0.045), "#8EA9C6", k=0.05)
    # trunk: down and curled, or raised when talking / happy
    if pose in ("talk", "happy", "wave"):
        m.add(cone((0, 1.42, 0.7), (0.05, 1.25, 1.02), 0.24, 0.16), skin, k=0.08)
        m.add(cone((0.05, 1.25, 1.02), (0.12, 1.55, 1.28), 0.16, 0.12), skin, k=0.06)
        smile(m, 0, 1.06, 0.82, 0.24, MOUTH[pose])
    else:
        m.add(cone((0, 1.42, 0.7), (0, 0.98, 1.0), 0.24, 0.15), skin, k=0.08)
        m.add(cone((0, 0.98, 1.0), (0.1, 0.82, 1.18), 0.15, 0.12), skin, k=0.05)
    # legs and arms
    m.add(cyl((0.33, 0.2, 0.12), 0.2, 0.21, 0.08, sym=True), skin, k=0.06)
    m.paint(ell((0.33, 0.06, 0.32), (0.15, 0.08, 0.06), sym=True), nail, soft=0.02)
    if pose == "wave":
        m.add(capsule((0.55, 0.85, 0.1), (1.0, 1.4, 0.2), 0.16), skin, k=0.08)
        m.add(ell((-0.6, 0.72, 0.2), (0.17, 0.3, 0.17), rot=(0, 0, -20)), skin, k=0.08)
    else:
        m.add(ell((0.6, 0.72, 0.2), (0.17, 0.3, 0.17), rot=(0, 0, 20), sym=True), skin, k=0.08)
    eyes(m, 0.35, 1.74, 0.72, 0.13, EYE_MOOD[pose])
    cheeks(m, 0.56, 1.48, 0.62, 0.15)
    return m


@group("ch_bo")
def _bo():
    return [bo(p) for p in POSES]
