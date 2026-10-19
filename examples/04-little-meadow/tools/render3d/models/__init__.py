"""
Model registry. A Group is one sprite family that shares a crop (all poses of a character, or a
single object). render.py walks GROUPS in order.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from sdf import Model


@dataclass
class Group:
    name: str
    build: Callable[[], list[Model]]
    size: tuple[int, int] = (512, 512)


GROUPS: list[Group] = []


def group(name: str, size=(512, 512)):
    """Decorator: register a function that returns a list of Models (or one Model)."""
    def wrap(fn):
        def build():
            out = fn()
            return out if isinstance(out, list) else [out]
        GROUPS.append(Group(name, build, size))
        return fn
    return wrap


POSES = ["idle", "blink", "talk", "happy", "sleep", "wave"]

# import order = render order
from models import characters, objects, scenes  # noqa: E402,F401
from models import numbers, colors, words, music, habits, puzzles, quiet  # noqa: E402,F401
