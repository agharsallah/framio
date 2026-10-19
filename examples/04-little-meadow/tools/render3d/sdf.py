"""
A tiny modelling DSL: a Model is an ordered list of signed-distance operations (add, cut, clip,
paint) that render.py compiles into one GLSL map() function and ray-marches on the Mac's GPU.

Coordinates: y is up, the model stands on y = 0, +z faces the camera. Units are arbitrary;
characters are about 2–3 units tall. Angles are in degrees.

Every primitive takes `sym=True` to mirror itself across x = 0 (eyes, ears, wings, feet):
describe the +x copy and the -x one comes for free.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np


# ---------------------------------------------------------------- colours

def hex_rgb(h: str) -> tuple[float, float, float]:
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))  # type: ignore[return-value]


def glsl_color(c) -> str:
    """A '#rrggbb' string, an (r, g, b) tuple in 0..1, or a GLSL expression (str starting with '=')."""
    if isinstance(c, str) and c.startswith("="):
        return c[1:]
    r, g, b = hex_rgb(c) if isinstance(c, str) else c
    return f"srgb(vec3({r:.4f},{g:.4f},{b:.4f}))"


# ---------------------------------------------------------------- transforms

def _rot_matrix(rx: float, ry: float, rz: float) -> np.ndarray:
    """Local → world rotation, applied x then y then z."""
    ax, ay, az = (math.radians(a) for a in (rx, ry, rz))
    cx, sx, cy, sy, cz, sz = math.cos(ax), math.sin(ax), math.cos(ay), math.sin(ay), math.cos(az), math.sin(az)
    mx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    my = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    mz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return mz @ my @ mx


def v3(t) -> str:
    return f"vec3({float(t[0]):.5f},{float(t[1]):.5f},{float(t[2]):.5f})"


def _local(c, rot, sym: bool) -> str:
    """GLSL expression for the point in the primitive's local frame."""
    p = "pm" if sym else "p"
    q = f"({p}-{v3(c)})"
    if rot and any(rot):
        m = _rot_matrix(*rot).T  # world → local
        cols = ",".join(f"{m[r, col]:.5f}" for col in range(3) for r in range(3))  # GLSL mat3 is column-major
        q = f"(mat3({cols})*{q})"
    return q


@dataclass
class Shape:
    expr: str  # GLSL float expression in terms of p / pm


# ---------------------------------------------------------------- primitives

def sphere(c, r, sym=False) -> Shape:
    return Shape(f"(length({_local(c, None, sym)})-{r:.5f})")


def ell(c, r, rot=(0, 0, 0), sym=False) -> Shape:
    """Ellipsoid with semi-axes r = (rx, ry, rz)."""
    return Shape(f"sdEllipsoid({_local(c, rot, sym)},{v3(r)})")


def capsule(a, b, r, sym=False) -> Shape:
    p = "pm" if sym else "p"
    return Shape(f"sdCapsule({p},{v3(a)},{v3(b)},{r:.5f})")


def cone(a, b, r1, r2, sym=False) -> Shape:
    """Round cone: sphere r1 at a, sphere r2 at b, smoothly hulled."""
    p = "pm" if sym else "p"
    return Shape(f"sdRoundCone({p},{v3(a)},{v3(b)},{r1:.5f},{r2:.5f})")


def box(c, half, r=0.0, rot=(0, 0, 0), sym=False) -> Shape:
    return Shape(f"sdRoundBox({_local(c, rot, sym)},{v3(half)},{r:.5f})")


def cyl(c, h, r, rr=0.0, rot=(0, 0, 0), sym=False) -> Shape:
    """Capped cylinder along local y: half height h, radius r, edge rounding rr."""
    return Shape(f"sdCylinder({_local(c, rot, sym)},{h:.5f},{r:.5f},{rr:.5f})")


def torus(c, big, small, rot=(0, 0, 0), sym=False) -> Shape:
    """Ring in the local xz plane."""
    return Shape(f"sdTorus({_local(c, rot, sym)},vec2({big:.5f},{small:.5f}))")


def arc(c, big, small, half_angle, rot=(0, 0, 0), sym=False) -> Shape:
    """Part of a ring in the local xy plane, centred on +y, spanning ±half_angle (an eyelid, a smile, a handle)."""
    a = math.radians(half_angle)
    return Shape(f"sdCappedTorus({_local(c, rot, sym)},vec2({math.sin(a):.5f},{math.cos(a):.5f}),{big:.5f},{small:.5f})")


def star(c, r, inner=0.5, th=0.15, rr=0.08, rot=(0, 0, 0), sym=False) -> Shape:
    """Puffy five-point star facing +z."""
    return Shape(f"extrude(sdStar5(({_local(c, rot, sym)}).xy,{r:.5f},{inner:.5f}),({_local(c, rot, sym)}).z,{th:.5f},{rr:.5f})")


def flat(c, shape2d: str, size: float, th=0.15, rr=0.08, rot=(0, 0, 0), sym=False) -> Shape:
    """A 2D shape (circle, square, triangle, heart, hexagon, diamond, oval, rect) extruded along z, rounded edges."""
    q = _local(c, rot, sym)
    s = f"{size:.5f}"
    d2 = {
        "circle": f"(length(({q}).xy)-{s})",
        "square": f"sdBox2(({q}).xy,vec2({s}))",
        "rect": f"sdBox2(({q}).xy,vec2({size * 1.45:.5f},{size * 0.8:.5f}))",
        "triangle": f"sdTriangle(({q}).xy/{s})*{s}",
        "heart": f"sdHeart(({q}).xy/{s}*0.95+vec2(0.0,0.55))*{s}/0.95",
        "hexagon": f"sdHexagon(({q}).xy,{size * 0.9:.5f})",
        "diamond": f"sdRhombus(({q}).xy,vec2({size * 0.8:.5f},{size * 1.15:.5f}))",
        "oval": f"sdEllipse2(({q}).xy,vec2({size * 1.3:.5f},{size * 0.85:.5f}))",
        "star": f"sdStar5(({q}).xy,{size * 1.15:.5f},0.5)",
    }[shape2d]
    return Shape(f"extrude({d2},({q}).z,{th:.5f},{rr:.5f})")


def plane(normal, offset=0.0) -> Shape:
    """Half-space dot(p, n) > offset is outside (use with clip/cut)."""
    n = np.array(normal, dtype=float)
    n /= np.linalg.norm(n)
    return Shape(f"(dot(p,{v3(n)})-({offset:.5f}))")


def raw(expr: str) -> Shape:
    return Shape(expr)


def inter(a: Shape, b: Shape) -> Shape:
    return Shape(f"max({a.expr},{b.expr})")


def sub(a: Shape, b: Shape, k=0.0) -> Shape:
    return Shape(f"smax({a.expr},-({b.expr}),{k:.5f})")


def uni(a: Shape, b: Shape, k=0.0) -> Shape:
    return Shape(f"smin({a.expr},{b.expr},{k:.5f})")


def shell(a: Shape, th: float) -> Shape:
    """Hollow version of a (a cup, a bowl): use with a clip plane to open it."""
    return Shape(f"(abs({a.expr})-{th:.5f})")


# ---------------------------------------------------------------- the model

@dataclass
class Op:
    kind: str  # add | cut | clip | paint
    shape: Shape
    color: str
    k: float
    gloss: float
    emit: float


@dataclass
class Model:
    name: str
    ground: bool = True  # draw a soft contact shadow on y = 0
    ground_y: float = 0.0
    reach: float = 1.8  # floor shadow radius around the target
    # scenes (backdrops): opaque, sky gradient, haze towards a light paper tone
    scene: bool = False
    sky: tuple = ("#CFE4EC", "#F3EEE2")
    haze: float = 0.18
    # camera: orbit around target
    target: tuple = (0.0, 1.2, 0.0)
    yaw: float = 0.0
    pitch: float = 10.0
    distance: float = 14.0
    fov: float = 20.0
    ops: list[Op] = field(default_factory=list)

    def add(self, shape: Shape, color, k=0.0, gloss=0.0, emit=0.0) -> "Model":
        """Union; k > 0 blends smoothly (clay pressed together), colour blends across the seam too."""
        self.ops.append(Op("add", shape, glsl_color(color), k, gloss, emit))
        return self

    def cut(self, shape: Shape, color=None, k=0.0) -> "Model":
        """Subtract; the carved surface takes `color` (default: what was there)."""
        self.ops.append(Op("cut", shape, glsl_color(color) if color is not None else "", k, 0.0, 0.0))
        return self

    def clip(self, shape: Shape, k=0.0) -> "Model":
        """Intersect: keep only what is inside `shape`."""
        self.ops.append(Op("clip", shape, "", k, 0.0, 0.0))
        return self

    def paint(self, shape: Shape, color, soft=0.015, gloss=None) -> "Model":
        """Colour the surface inside `shape` without changing geometry (cheeks, bellies, spots)."""
        self.ops.append(Op("paint", shape, glsl_color(color), soft, -1.0 if gloss is None else gloss, 0.0))
        return self

    # ---- GLSL

    def glsl(self) -> str:
        dist, full = [], []
        for op in self.ops:
            e = op.shape.expr
            if op.kind == "add":
                dist.append(f"  dU(d,{e},{op.k:.4f});")
                full.append(f"  cU(d,c,m,{e},{op.color},vec2({op.gloss:.3f},{op.emit:.3f}),{op.k:.4f});")
            elif op.kind == "cut":
                dist.append(f"  dS(d,{e},{op.k:.4f});")
                col = op.color or "c"
                full.append(f"  cS(d,c,m,{e},{col},{op.k:.4f});")
            elif op.kind == "clip":
                dist.append(f"  dI(d,{e},{op.k:.4f});")
                full.append(f"  cI(d,{e},{op.k:.4f});")
            elif op.kind == "paint":
                full.append(f"  cP(c,m,{e},{op.color},{op.k:.4f},{op.gloss:.3f});")
        head = "  vec3 pm=vec3(abs(p.x),p.y,p.z);\n"
        return (
            "float mapD(vec3 p){\n" + head + "  float d=1e9;\n" + "\n".join(dist) + "\n  return d;\n}\n"
            "float mapC(vec3 p,out vec3 c,out vec2 m){\n" + head + "  float d=1e9; c=vec3(0.5); m=vec2(0.0);\n"
            + "\n".join(full) + "\n  return d;\n}\n"
        )


# ---------------------------------------------------------------- shared character parts

INK = "#2E2433"      # eyes: warm near-black, never pure black
WHITE = "#FFFDF8"


def eyes(m: Model, x: float, y: float, z: float, r: float, mood: str = "open", tilt: float = 0.0, ink=INK,
         look=(0.0, 0.0)) -> None:
    """
    A pair of eyes on a surface whose normal is roughly +z.
    mood: open | blink | happy (^ ^) | sleep (u u) | wide
    look: shifts the pupils (x, y) as a fraction of r.
    """
    if mood in ("open", "wide"):
        rr = r * (1.15 if mood == "wide" else 1.0)
        m.add(ell((x, y, z), (rr, rr * 1.15, rr * 0.7), sym=True), ink, gloss=0.85)
        hx, hy = x - rr * 0.35 + look[0] * rr * 0.3, y + rr * 0.45 + look[1] * rr * 0.3
        m.add(sphere((hx, hy, z + rr * 0.55), rr * 0.3, sym=True), WHITE, emit=1.0)
        m.add(sphere((hx + rr * 0.45, hy - rr * 0.55, z + rr * 0.5), rr * 0.13, sym=True), WHITE, emit=1.0)
    elif mood in ("blink", "sleep"):
        m.add(arc((x, y + r * 0.35, z + r * 0.25), r * 0.75, r * 0.15, 55, rot=(0, 0, 180 + tilt), sym=True), ink)
    elif mood == "happy":
        m.add(arc((x, y - r * 0.45, z + r * 0.25), r * 0.8, r * 0.19, 58, rot=(0, 0, 0 + tilt), sym=True), ink)


def cheeks(m: Model, x: float, y: float, z: float, r: float, color="#F2A7A0") -> None:
    m.paint(sphere((x, y, z), r, sym=True), color, soft=r * 0.6)


def smile(m: Model, x: float, y: float, z: float, w: float, mood: str = "open", ink=INK) -> None:
    """Mouth centred at (x, y, z). mood: open (small smile) | talk | happy (open laugh) | sleep | o"""
    if mood in ("open", "blink"):
        m.add(arc((x, y + w * 0.6, z), w * 0.7, w * 0.11, 48, rot=(0, 0, 180)), ink)
    elif mood == "sleep":
        m.add(arc((x, y + w * 0.4, z), w * 0.5, w * 0.1, 40, rot=(0, 0, 180)), ink)
    elif mood in ("talk", "o"):
        m.add(ell((x, y, z), (w * 0.32, w * 0.38, w * 0.2)), ink)
        m.add(ell((x, y - w * 0.15, z + w * 0.06), (w * 0.2, w * 0.13, w * 0.12)), "#E58C8C")
    elif mood == "happy":
        # a "D" on its side: the lower half of an ellipse, with a tongue
        top = y + w * 0.12
        m.add(inter(ell((x, top, z), (w * 0.55, w * 0.5, w * 0.22)), plane((0, 1, 0), top)), ink)
        m.add(inter(ell((x, y - w * 0.22, z + w * 0.07), (w * 0.32, w * 0.16, w * 0.14)), plane((0, 1, 0), top - w * 0.02)), "#E58C8C")
