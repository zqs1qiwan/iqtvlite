#!/usr/bin/env python3
"""IQTV Lite brand asset generator.

Deterministic generator for the vector/bitmap assets under iqtv/docs/brand/.
Run with the bundled Python (Pillow 12) on Windows.

The model cannot visually verify rendered output; these are geometry/text
specifications + rendered bitmaps. Re-check on a real renderer for pixel parity.
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SVG = os.path.join(ROOT, "svg")
PNG = os.path.join(ROOT, "png")
RES = os.path.join(ROOT, "android-res")

# ---- Brand tokens ---------------------------------------------------------
BG = (14, 17, 22)        # #0E1116 Obsidian
SURFACE = (22, 27, 34)   # #161B22
ELEV = (30, 36, 46)      # #1E242E
DIV = (42, 53, 80)       # #2A3550
BLUE = (78, 168, 255)    # #4EA8FF Signal Blue
AMBER = (245, 165, 36)   # #F5A524 Amber
INK = (242, 245, 250)    # #F2F5FA
MUTED = (169, 180, 199)  # #A9B4C7
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)

FONT_BOLD = r"C:\Windows\Fonts\arialbd.ttf"
FONT_REG = r"C:\Windows\Fonts\arial.ttf"
FONT_LIGHT = r"C:\Windows\Fonts\segoeuil.ttf"

MARK_UNIT = 96
TRI = [(34, 28), (34, 68), (70, 48)]
DOT = (80, 26, 5)


def C(c):
    return (c[0], c[1], c[2], 255)


def hexc(c) -> str:
    return "#%02X%02X%02X" % c[:3]


def font(path, size):
    return ImageFont.truetype(path, size)


def _mkdirs():
    for d in (SVG, PNG, os.path.join(PNG, "mipmap")):
        os.makedirs(d, exist_ok=True)
    for sub in ("mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi",
                "mipmap-xxxhdpi", "drawable-nodpi", "values", "mipmap-anydpi-v26"):
        os.makedirs(os.path.join(RES, sub), exist_ok=True)


def draw_mark(d: ImageDraw.ImageDraw, x, y, size, mono=None, tile=True):
    s = size / MARK_UNIT
    if mono is None:
        r = max(2, int(round(6 * s)))
        d.rounded_rectangle([x, y, x + size, y + size], radius=r, fill=C(SURFACE),
                            outline=C(DIV), width=max(1, int(round(1.5 * s))))
        tri_fill, dot_fill = BLUE, AMBER
    else:
        tri_fill = dot_fill = mono
    d.polygon([(x + px * s, y + py * s) for px, py in TRI], fill=C(tri_fill))
    cx, cy, rr = DOT[0] * s, DOT[1] * s, DOT[2] * s
    d.ellipse([x + cx - rr, y + cy - rr, x + cx + rr, y + cy + rr], fill=C(dot_fill))


def mark_svg(mono=None, with_tile=True, size=96):
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
             f'viewBox="0 0 {MARK_UNIT} {MARK_UNIT}">']
    if with_tile and mono is None:
        parts.append(f'<rect x="0" y="0" width="{MARK_UNIT}" height="{MARK_UNIT}" rx="6" '
                     f'fill="{hexc(SURFACE)}" stroke="{hexc(DIV)}" stroke-width="1.5"/>')
    fill = hexc(mono) if mono is not None else None
    tri = fill if fill else hexc(BLUE)
    dot = fill if fill else hexc(AMBER)
    parts.append('<polygon points="%s" fill="%s"/>'
                 % (" ".join(f"{x},{y}" for x, y in TRI), tri))
    parts.append(f'<circle cx="{DOT[0]}" cy="{DOT[1]}" r="{DOT[2]}" fill="{dot}"/>')
    parts.append("</svg>")
    return "\n".join(parts)


def _lockup_metrics():
    fb = font(FONT_BOLD, 140)
    fl = font(FONT_LIGHT, 140)
    w_iqtv = fb.getbbox("IQTV")[2] - fb.getbbox("IQTV")[0]
    w_lite = fl.getbbox("Lite")[2] - fl.getbbox("Lite")[0]
    return fb, fl, w_iqtv, w_lite


def _lockup_geom():
    fb, fl, w_iqtv, w_lite = _lockup_metrics()
    mark_size = 190
    total = mark_size + 36 + w_iqtv + 16 + w_lite
    mark_x = max(40, (1200 - total) // 2)
    mark_y = (360 - mark_size) // 2
    text_x = mark_x + mark_size + 36
    return fb, fl, w_iqtv, w_lite, mark_size, mark_x, mark_y, text_x


def lockup_svg(bg, ink, lite, mono=None):
    fb, fl, w_iqtv, w_lite, mark_size, mark_x, mark_y, text_x = _lockup_geom()
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="360" '
             'viewBox="0 0 1200 360">']
    if bg is not None:
        parts.append(f'<rect width="1200" height="360" fill="{hexc(bg)}"/>')
    scale = mark_size / MARK_UNIT
    parts.append(f'<g transform="translate({mark_x},{mark_y}) scale({scale:.4f})">')
    parts.append(f'<rect x="0" y="0" width="{MARK_UNIT}" height="{MARK_UNIT}" rx="6" '
                 f'fill="{hexc(SURFACE)}" stroke="{hexc(DIV)}" stroke-width="1.5"/>')
    parts.append(f'<polygon points="34,28 34,68 70,48" fill="{hexc(BLUE)}"/>')
    parts.append(f'<circle cx="80" cy="26" r="5" fill="{hexc(AMBER)}"/>')
    parts.append("</g>")
    fam = "Roboto, Segoe UI, Arial, sans-serif"
    iq = hexc(ink) if mono is None else hexc(mono)
    li = hexc(lite) if mono is None else hexc(mono)
    parts.append(f'<text x="{text_x}" y="180" font-family="{fam}" font-size="140" '
                 f'font-weight="700" fill="{iq}" dominant-baseline="central">IQTV</text>')
    parts.append(f'<text x="{text_x + w_iqtv + 16}" y="180" font-family="{fam}" '
                 f'font-size="140" font-weight="300" fill="{li}" '
                 f'dominant-baseline="central">Lite</text>')
    parts.append("</svg>")
    return "\n".join(parts)


def tv_banner_svg():
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" '
            'viewBox="0 0 320 180">\n'
            f'<rect width="320" height="180" fill="{hexc(BG)}"/>\n'
            f'<rect width="4" height="180" fill="{hexc(AMBER)}"/>\n'
            f'<g transform="translate(26,48) scale({84/MARK_UNIT:.4f})">\n'
            f'<rect width="96" height="96" rx="6" fill="{hexc(SURFACE)}" stroke="{hexc(DIV)}" stroke-width="1.5"/>\n'
            f'<polygon points="34,28 34,68 70,48" fill="{hexc(BLUE)}"/>\n'
            f'<circle cx="80" cy="26" r="5" fill="{hexc(AMBER)}"/>\n'
            '</g>\n'
            '<text x="126" y="86" font-family="Roboto, Segoe UI, Arial, sans-serif" font-size="42" '
            f'font-weight="700" fill="{hexc(INK)}" dominant-baseline="central">IQTV</text>\n'
            '<text x="232" y="86" font-family="Roboto, Segoe UI, Arial, sans-serif" font-size="42" '
            f'font-weight="300" fill="{hexc(AMBER)}" dominant-baseline="central">Lite</text>\n'
            '<text x="126" y="118" font-family="Roboto, Segoe UI, Arial, sans-serif" font-size="14" '
            f'fill="{hexc(MUTED)}" dominant-baseline="central">LIVE TV</text>\n'
            '</svg>')


def render_lockup(path, bg, ink, lite, mono=None):
    W, H = 1200, 360
    img = Image.new("RGBA", (W, H), (C(bg) if bg is not None else (0, 0, 0, 0)))
    d = ImageDraw.Draw(img)
    fb, fl, w_iqtv, w_lite, mark_size, mark_x, mark_y, text_x = _lockup_geom()
    draw_mark(d, mark_x, mark_y, mark_size, mono=mono)
    d.text((text_x, 180), "IQTV", font=fb, fill=C(ink if mono is None else mono), anchor="lm")
    d.text((text_x + w_iqtv + 16, 180), "Lite", font=fl,
           fill=C(lite if mono is None else mono), anchor="lm")
    img.save(path, "PNG")


def render_tv_banner(path):
    W, H = 320, 180
    img = Image.new("RGBA", (W, H), C(BG))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 4, H], fill=C(AMBER))
    mark_size = 84
    mark_x, mark_y = 26, (H - mark_size) // 2
    draw_mark(d, mark_x, mark_y, mark_size)
    fb = font(FONT_BOLD, 42)
    fl = font(FONT_LIGHT, 42)
    tx = mark_x + mark_size + 16
    d.text((tx, 86), "IQTV", font=fb, fill=C(INK), anchor="lm")
    w_iq = fb.getbbox("IQTV")[2] - fb.getbbox("IQTV")[0]
    d.text((tx + w_iq + 6, 86), "Lite", font=fl, fill=C(AMBER), anchor="lm")
    d.text((tx, 118), "LIVE TV", font=font(FONT_REG, 14), fill=C(MUTED), anchor="lm")
    img.save(path, "PNG")


def render_icon(path, size, round_icon=False, bg=BG, mark_ratio=0.60, transparent=False):
    if round_icon:
        bgimg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        bd = ImageDraw.Draw(bgimg)
        bd.ellipse([0, 0, size - 1, size - 1], fill=C(bg))
        ms = int(size * mark_ratio)
        draw_mark(bd, (size - ms) // 2, (size - ms) // 2, ms)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(bgimg, (0, 0), mask)
        out.save(path, "PNG")
        return
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0) if transparent else C(bg))
    d = ImageDraw.Draw(img)
    ms = int(size * mark_ratio)
    draw_mark(d, (size - ms) // 2, (size - ms) // 2, ms)
    img.save(path, "PNG")


def render_splash(path, size, mark_ratio):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ms = int(size * mark_ratio)
    draw_mark(d, (size - ms) // 2, (size - ms) // 2, ms)
    img.save(path, "PNG")


def render_palette(path):
    chips = [("Obsidian", BG), ("Surface", SURFACE), ("Elevated", ELEV),
             ("Signal Blue", BLUE), ("Amber", AMBER), ("Ink", INK),
             ("Muted", MUTED), ("Divider", DIV)]
    W, H = 1200, 300
    img = Image.new("RGBA", (W, H), C(BG))
    d = ImageDraw.Draw(img)
    f = font(FONT_BOLD, 16)
    fh = font(FONT_REG, 13)
    cw = W // len(chips)
    for i, (name, col) in enumerate(chips):
        x = i * cw + 12
        d.rounded_rectangle([x, 60, x + cw - 24, 180], radius=4, fill=C(col), outline=C(DIV))
        d.text((x, 40), name, font=f, fill=C(INK), anchor="lm")
        d.text((x, 210), hexc(col), font=fh, fill=C(MUTED), anchor="lm")
    img.save(path, "PNG")


def render_safe_area(path):
    W, H = 1080, 1920
    img = Image.new("RGBA", (W, H), C(BG))
    d = ImageDraw.Draw(img)
    cx, cy = W // 2, H // 2
    d.ellipse([cx - 288, cy - 288, cx + 288, cy + 288], outline=C(AMBER), width=3)
    d.ellipse([cx - 192, cy - 192, cx + 192, cy + 192], outline=C(BLUE), width=3)
    tmp = os.path.join(PNG, "_tmp_splash.png")
    render_splash(tmp, 512, 0.56)
    sm = Image.open(tmp).convert("RGBA")
    img.alpha_composite(sm, (cx - 256, cy - 256))
    os.remove(tmp)
    d.text((cx, cy - 360), "Splash safe area (illustrative 1080x1920)",
           font=font(FONT_BOLD, 26), fill=C(INK), anchor="mm")
    d.text((cx, cy + 340),
           "outer amber: 576dp safe circle  /  inner blue: 384dp icon-safe circle",
           font=font(FONT_REG, 20), fill=C(MUTED), anchor="mm")
    img.save(path, "PNG")


def write_res_tree():
    sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
             "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
    for d, s in sizes.items():
        render_icon(os.path.join(RES, d, "ic_launcher.png"), s)
        render_icon(os.path.join(RES, d, "ic_launcher_round.png"), s, round_icon=True)
    render_icon(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png"),
                432, mark_ratio=0.52)
    render_icon(os.path.join(PNG, "ic_launcher_foreground_512.png"),
                512, transparent=True, mark_ratio=0.58)
    render_tv_banner(os.path.join(RES, "drawable-nodpi", "tv_banner.png"))
    render_splash(os.path.join(RES, "drawable-nodpi", "splash_mark.png"), 512, 0.56)
    render_splash(os.path.join(PNG, "splash_mark_288.png"), 288, 0.56)
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/brand_obsidian"/>\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
                '</adaptive-icon>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(RES, "mipmap-anydpi-v26", name), "w", encoding="utf-8") as f:
            f.write(adaptive)
    with open(os.path.join(RES, "values", "brand_colors.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <color name="brand_obsidian">#FF0E1116</color>\n'
                '    <color name="brand_surface">#FF161B22</color>\n'
                '    <color name="brand_surface_elevated">#FF1E242E</color>\n'
                '    <color name="brand_divider">#FF2A3550</color>\n'
                '    <color name="brand_blue">#FF4EA8FF</color>\n'
                '    <color name="brand_amber">#FFF5A524</color>\n'
                '    <color name="brand_ink">#FFF2F5FA</color>\n'
                '    <color name="brand_muted">#FFA9B4C7</color>\n'
                '</resources>\n')


def main():
    _mkdirs()
    svgs = {
        "logo_primary_dark.svg": lockup_svg(BG, INK, AMBER),
        "logo_primary_light.svg": lockup_svg(WHITE, BG, AMBER),
        "logo_monochrome.svg": lockup_svg(None, None, None, mono=BLACK),
        "mark_dark.svg": mark_svg(),
        "mark_light.svg": mark_svg(),
        "mark_monochrome.svg": mark_svg(mono=BLACK, with_tile=False),
        "tv_banner.svg": tv_banner_svg(),
        "splash_mark.svg": mark_svg(),
    }
    for name, content in svgs.items():
        with open(os.path.join(SVG, name), "w", encoding="utf-8") as f:
            f.write(content + "\n")
    render_lockup(os.path.join(PNG, "logo_primary_dark_1200x360.png"), BG, INK, AMBER)
    render_lockup(os.path.join(PNG, "logo_primary_light_1200x360.png"), WHITE, BG, AMBER)
    render_lockup(os.path.join(PNG, "logo_monochrome_white_1200x360.png"), None, WHITE, WHITE, mono=WHITE)
    render_lockup(os.path.join(PNG, "logo_monochrome_black_1200x360.png"), None, BLACK, BLACK, mono=BLACK)
    render_tv_banner(os.path.join(PNG, "tv_banner_320x180.png"))
    render_icon(os.path.join(PNG, "app_icon_leanback_512.png"), 512, mark_ratio=0.62)
    render_icon(os.path.join(PNG, "ic_launcher_512.png"), 512, mark_ratio=0.58)
    render_splash(os.path.join(PNG, "splash_mark_512.png"), 512, 0.56)
    render_palette(os.path.join(PNG, "palette_swatches.png"))
    render_safe_area(os.path.join(PNG, "splash_safe_area_1080x1920.png"))
    write_res_tree()
    for name, s in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        render_icon(os.path.join(PNG, "mipmap", f"ic_launcher_{name}_{s}.png"), s)
    print("OK: generated brand assets under", ROOT)


if __name__ == "__main__":
    main()
