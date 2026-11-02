#!/usr/bin/env python3
"""Builds the app icons from docs/icon/paper-1024.png: adaptive and legacy launcher icons, TV banner. Needs Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
ART = Image.open(ROOT / "docs/icon/paper-1024.png").convert("RGB")
NAVY = ART.crop((0, 0, 60, 60)).resize((1, 1), Image.BOX).getpixel((0, 0))


def scene(width: int, height: int, art_size: int) -> Image.Image:
    """The artwork on a navy canvas, its edges melting into the canvas so no seam shows."""
    canvas = Image.new("RGB", (width, height), NAVY)
    art = ART.resize((art_size, art_size), Image.LANCZOS)
    edge = art_size // 12
    mask = Image.new("L", (art_size, art_size), 0)
    ImageDraw.Draw(mask).rectangle((edge, edge, art_size - edge, art_size - edge), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(edge / 2))
    canvas.paste(art, ((width - art_size) // 2, (height - art_size) // 2), mask)
    return canvas


def shaped(image: Image.Image, radius: float) -> Image.Image:
    """Rounded corners; radius is a share of the side, 0.5 gives a circle."""
    big = image.size[0] * 4
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=big * radius, fill=255)
    out = image.convert("RGBA")
    out.putalpha(mask.resize(image.size, Image.LANCZOS))
    return out


DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
# Adaptive canvas is 108 dp, launchers show about the central 72: the art shrinks so the TV stays inside that.
full = scene(1440, 1440, 860)
inner = full.crop((240, 240, 1200, 1200))
for name, k in DENSITIES.items():
    folder = RES / f"mipmap-{name}"
    folder.mkdir(exist_ok=True)
    full.resize((int(108 * k),) * 2, Image.LANCZOS).save(folder / "ic_launcher_foreground.png", optimize=True)
    small = inner.resize((int(48 * k),) * 2, Image.LANCZOS)
    shaped(small, 0.2).save(folder / "ic_launcher.png", optimize=True)
    shaped(small, 0.5).save(folder / "ic_launcher_round.png", optimize=True)

adaptive = RES / "mipmap-anydpi-v26"
adaptive.mkdir(exist_ok=True)
XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
(adaptive / "ic_launcher.xml").write_text(XML)
(adaptive / "ic_launcher_round.xml").write_text(XML)
(RES / "values/ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">#{NAVY[0]:02X}{NAVY[1]:02X}{NAVY[2]:02X}</color>\n</resources>\n'
)
banner = RES / "drawable-xhdpi"
banner.mkdir(exist_ok=True)
scene(1280, 720, 900).resize((640, 360), Image.LANCZOS).save(banner / "banner.png", optimize=True)
