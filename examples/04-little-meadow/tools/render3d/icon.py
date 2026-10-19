"""Launcher icon: Pip on a warm rounded square, written to every mipmap density.  uv run icon.py"""
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
APP = HERE.parent.parent / "app/src/main"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

pip = Image.open(APP / "assets/sprites/ch_pip_happy.webp").convert("RGBA")
big = 768
icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
mask = Image.new("L", (big, big), 0)
ImageDraw.Draw(mask).rounded_rectangle((24, 24, big - 24, big - 24), radius=180, fill=255)
bg = Image.new("RGBA", (big, big), (0, 0, 0, 0))
d = ImageDraw.Draw(bg)
for y in range(big):  # soft sky → meadow
    t = y / big
    c = tuple(int(a + (b - a) * t) for a, b in zip((214, 233, 236), (247, 236, 214)))
    d.line([(0, y), (big, y)], fill=c + (255,))
icon.paste(bg, (0, 0), mask)
p = pip.resize((620, 620), Image.LANCZOS)
icon.alpha_composite(p, ((big - 620) // 2, 90))
icon.putalpha(Image.composite(icon.getchannel("A"), Image.new("L", (big, big), 0), mask))
for density, px in SIZES.items():
    out = APP / f"res/mipmap-{density}"
    out.mkdir(parents=True, exist_ok=True)
    icon.resize((px, px), Image.LANCZOS).save(out / "ic_launcher.png")
print("icons written")
