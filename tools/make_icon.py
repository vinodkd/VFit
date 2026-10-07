#!/usr/bin/env python3
"""Renders the launcher icon foreground: a white "V" plus the flexed-biceps emoji (Noto Color Emoji, Apache 2.0).
Writes app/src/main/res/mipmap-<density>/ic_launcher_fg.png. Run from the repo root: python3 tools/make_icon.py"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageOps

EMOJI_FONT = "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"
S = 432                       # xxxhdpi size of the 108dp adaptive-icon layer
u = S / 108.0                 # 1dp

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
# "V" (same shape as the vector monochrome layer), shifted left to make room for the arm
v = [(25, 34), (35, 34), (45, 61), (55, 34), (65, 34), (50, 74), (40, 74)]
d.polygon([(x * u, y * u) for x, y in v], fill=(255, 255, 255, 255))

# Noto Color Emoji is a bitmap font that only renders at size 109
font = ImageFont.truetype(EMOJI_FONT, 109)
glyph = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
ImageDraw.Draw(glyph).text((0, 0), "\U0001F4AA", font=font, embedded_color=True)
glyph = ImageOps.mirror(glyph.crop(glyph.getbbox()))   # Unicode has no left-arm variant; mirror so the fist faces away from the V
size = int(25 * u)
glyph = glyph.resize((size, int(size * glyph.height / glyph.width)), Image.LANCZOS)
img.alpha_composite(glyph, (int(55 * u), int(45 * u)))

root = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
for name, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    out = os.path.join(root, f"mipmap-{name}")
    os.makedirs(out, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher_fg.png"))
# preview on the background colour, with the 66dp safe-zone circle
prev = Image.new("RGBA", (S, S), (0x1F, 0x4E, 0x79, 255))
prev.alpha_composite(img)
ImageDraw.Draw(prev).ellipse([(54 - 33) * u, (54 - 33) * u, (54 + 33) * u, (54 + 33) * u], outline=(255, 0, 0, 255))
prev.save(os.path.join(os.path.dirname(__file__), "..", "build", "icon_preview.png"))
print("ok")

# web logo / favicon for docs/: the icon on its navy background, rounded, cropped to the safe zone
docs = os.path.join(os.path.dirname(__file__), "..", "docs", "screenshots")
os.makedirs(docs, exist_ok=True)
z0, z1 = int(19 * u), int(89 * u)                       # 70dp square around the 66dp safe zone
logo = Image.new("RGBA", (S, S), (0x1F, 0x4E, 0x79, 255))
logo.alpha_composite(img)
logo = logo.crop((z0, z0, z1, z1))
mask = Image.new("L", logo.size, 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, logo.width - 1, logo.height - 1], radius=logo.width // 5, fill=255)
logo.putalpha(mask)
logo.resize((128, 128), Image.LANCZOS).save(os.path.join(docs, "logo.png"))
logo.resize((32, 32), Image.LANCZOS).save(os.path.join(docs, "favicon.png"))
print("web logo ok")
