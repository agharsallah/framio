"""Models for the numbers world (see DESIGN.md: sprite names start with the world prefix)."""
from __future__ import annotations

import math

from models import group
from models.objects import LEAF
from sdf import Model, capsule, ell, inter, plane, raw, shell, sphere, torus

S = (320, 320)

TWIG = "#B08560"
TWIG_DARK = "#8F6A4C"
TWIG_LIGHT = "#C9A27A"
WICKER = "#D9B27E"
WICKER_DARK = "#C49866"
BUSH = "#8DBE7C"
BUSH_DARK = "#79AD6B"


def front_half(m: Model, name: str) -> Model:
    """The same model with everything behind z = 0 removed: drawn over the things inside a bowl."""
    f = Model(name, ground=False, target=m.target, yaw=m.yaw, pitch=m.pitch, distance=m.distance, fov=m.fov, reach=m.reach)
    f.ops = list(m.ops)
    f.clip(plane((0, 0, -1), 0.0))
    return f


@group("nu_berry", S)
def berry():
    """A plump raspberry made of little round drupelets, with a leafy cap: Pip's favourite snack."""
    m = Model("nu_berry", target=(0, 0.7, 0), distance=7.5, pitch=14, reach=1.0)
    m.add(ell((0, 0.68, 0), (0.5, 0.6, 0.5)), "#D45E78")
    for row in range(6):
        y = 0.22 + row * 0.19
        rr = 0.5 * math.sqrt(max(0.0, 1 - ((y - 0.68) / 0.62) ** 2)) + 0.02
        n = max(4, int(rr * 16))
        for i in range(n):
            a = (i + 0.5 * (row % 2)) * 2 * math.pi / n
            m.add(sphere((rr * math.sin(a), y, rr * math.cos(a)), 0.15), "#DE6A82", k=0.05, gloss=0.3)
    m.paint(sphere((-0.2, 0.95, 0.45), 0.22), "#F096A8", soft=0.18)
    for i in range(5):
        a = i * 2 * math.pi / 5
        m.add(ell((0.2 * math.sin(a), 1.3, 0.2 * math.cos(a)), (0.2, 0.04, 0.08), rot=(0, math.degrees(a) + 90, -18)), LEAF, k=0.02)
    m.add(capsule((0, 1.25, 0), (0.05, 1.5, 0), 0.045), "#6EA35F", k=0.02)
    return m


@group("nu_bush", (512, 512))
def bush():
    """A round, soft berry bush. Berries are separate sprites the child can pick off it."""
    m = Model("nu_bush", target=(0, 1.6, 0), distance=19.0, pitch=8, reach=3.2)
    blobs = [(0, 1.7, 0, 1.75), (-1.45, 1.25, 0.1, 1.25), (1.45, 1.25, 0.1, 1.25), (-0.8, 2.75, -0.1, 1.15),
             (0.85, 2.8, -0.1, 1.15), (0, 3.25, -0.2, 0.9), (-2.1, 0.75, 0.3, 0.85), (2.1, 0.75, 0.3, 0.85)]
    for x, y, z, r in blobs:
        m.add(sphere((x, y, z), r), BUSH, k=0.35)
    for x, y, z, r in [(-0.7, 2.3, 1.2, 0.5), (0.9, 1.9, 1.35, 0.45), (-1.4, 1.2, 1.2, 0.4), (0.3, 3.1, 0.7, 0.4)]:
        m.paint(sphere((x, y, z), r), "#9CCB8A", soft=0.35)
    for x, y, z, r in [(-0.3, 0.6, 1.4, 0.6), (1.1, 0.6, 1.2, 0.5), (-1.9, 0.5, 0.9, 0.5), (2.0, 0.5, 0.9, 0.5)]:
        m.paint(sphere((x, y, z), r), BUSH_DARK, soft=0.4)
    for i in range(14):  # little leaves standing out of the bush
        a = i * 2.39996
        x = 2.2 * math.sin(a) * (0.4 + 0.6 * ((i * 37) % 10) / 10)
        y = 1.0 + ((i * 53) % 23) / 23 * 2.4
        z = math.sqrt(max(0.1, 3.4 - x * x * 0.5 - (y - 1.8) ** 2 * 0.6))
        m.add(ell((x, y, z), (0.22, 0.12, 0.05), rot=(-20, 0, (i * 47) % 180)), "#7FB46F", k=0.06)
    return m


@group("nu_nest", (512, 320))
def nest():
    """Pip's nest: a round twiggy bowl. nu_nest_front is its front half, drawn over Pip and the berries."""
    m = Model("nu_nest", target=(0, 0.75, 0), distance=19.0, pitch=16, reach=2.6)
    bowl = inter(shell(ell((0, 0.95, 0), (2.2, 0.95, 2.0)), 0.16), plane((0, 1, 0), 1.25))
    m.add(bowl, TWIG)
    m.add(torus((0, 1.22, 0), 1.95, 0.32, rot=(0, 0, 0)), TWIG, k=0.1)
    # soft inner shadow so it reads as a hollow
    m.paint(ell((0, 0.95, 0), (1.9, 0.75, 1.75)), TWIG_DARK, soft=0.3)
    # woven twig streaks, two directions, so it reads as twigs rather than dough
    m.paint(raw("(0.55-sin(atan(p.z,p.x)*26.0+p.y*7.0))*0.08"), TWIG_DARK, soft=0.02)
    m.paint(raw("(0.75-sin(atan(p.z,p.x)*-19.0+p.y*9.0+1.3))*0.08"), TWIG_LIGHT, soft=0.02)
    # twigs wrapped around the rim and the bowl
    for i in range(16):
        a = i * 2 * math.pi / 16
        b = a + 0.55
        r1, r2 = 2.12, 2.16
        y1 = 1.28 + 0.12 * math.sin(i * 1.7)
        y2 = 1.15 - 0.1 * math.cos(i * 2.3)
        m.add(capsule((r1 * math.sin(a), y1, r1 * math.cos(a)), (r2 * math.sin(b), y2, r2 * math.cos(b)), 0.07),
              TWIG_LIGHT if i % 2 else TWIG_DARK, k=0.04)
    for i in range(10):
        a = i * 2 * math.pi / 10 + 0.3
        y = 0.55 + (i % 3) * 0.2
        rr = 1.75 + (i % 3) * 0.15
        m.add(capsule((rr * math.sin(a), y, rr * math.cos(a)), (rr * math.sin(a + 0.7), y + 0.18, rr * math.cos(a + 0.7)), 0.06),
              TWIG_LIGHT, k=0.03)
    # a couple of stray twig ends poking out
    m.add(capsule((-2.15, 1.3, 0.4), (-2.6, 1.65, 0.6), 0.05), TWIG_DARK, k=0.02)
    m.add(capsule((2.1, 1.25, 0.5), (2.55, 1.5, 0.75), 0.05), TWIG_DARK, k=0.02)
    # a little leaf tucked in
    m.add(ell((1.2, 1.4, 1.75), (0.34, 0.05, 0.18), rot=(60, 30, 15)), LEAF, k=0.02)
    m.name = "nu_nest_back"
    return [m, front_half(m, "nu_nest_front")]


@group("nu_branch", (512, 256))
def branch():
    """A gently curved branch with leaves; apples hang from it in "How many?"."""
    m = Model("nu_branch", target=(0, 1.0, 0), distance=26.0, pitch=4, ground=False)
    pts = [(-3.6, 1.05), (-2.0, 1.2), (-0.5, 1.12), (1.0, 1.2), (2.4, 1.1), (3.6, 1.2)]
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        m.add(capsule((x1, y1, 0), (x2, y2, 0), 0.3 - 0.02 * (x1 + 3.6)), "#A9805C", k=0.12)
    m.add(capsule((-1.4, 1.17, 0), (-1.0, 1.7, 0.1), 0.08), "#A9805C", k=0.1)
    m.add(capsule((1.7, 1.15, 0), (2.1, 1.65, 0.1), 0.08), "#A9805C", k=0.1)
    for x, y, rz in [(-1.0, 1.85, 30), (2.15, 1.82, -25), (-2.9, 1.4, 20), (0.4, 1.45, -15), (3.4, 1.45, 30), (-0.2, 1.42, 10)]:
        m.add(ell((x, y, 0.15), (0.45, 0.24, 0.06), rot=(0, 0, rz)), LEAF, k=0.03)
        m.add(capsule((x - 0.3 * math.cos(math.radians(rz)), y - 0.3 * math.sin(math.radians(rz)), 0.2),
                      (x + 0.3 * math.cos(math.radians(rz)), y + 0.3 * math.sin(math.radians(rz)), 0.2), 0.025), "#6AA35C")
    return m


@group("nu_basket", (512, 384))
def basket():
    """A low woven basket. nu_basket_front is the front half, drawn over the things inside."""
    m = Model("nu_basket", target=(0, 0.8, 0), distance=17.0, pitch=18, reach=2.6)
    body = inter(shell(ell((0, 1.15, 0), (2.1, 1.1, 1.35)), 0.13), plane((0, 1, 0), 1.35))
    m.add(body, WICKER)
    m.add(torus((0, 1.35, 0), 1.98, 0.17), WICKER_DARK, k=0.05)
    m.paint(ell((0, 1.15, 0), (1.9, 0.9, 1.15)), "#C99E6E", soft=0.2)
    # woven bands
    for y in (0.45, 0.75, 1.05):
        m.paint(raw_band(y), WICKER_DARK, soft=0.03)
    m.add(ell((0, 0.06, 0), (1.45, 0.08, 0.95)), WICKER_DARK, k=0.1)
    m.name = "nu_basket_back"
    return [m, front_half(m, "nu_basket_front")]


def raw_band(y):
    return raw(f"(abs(p.y-{y:.3f})-0.05)")
