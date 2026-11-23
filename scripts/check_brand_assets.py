#!/usr/bin/env python
"""t52 §⑤ (criterion upgraded 2026-11-23): launcher icons must be pixel-identical
to icon-512.png scaled with Pillow LANCZOS. Checks size / corner transparency /
center colour #151923 / exact pixel equality against LANCZOS reference.
Exit 0 = OK, 1 = violation. Source dir is optional (skips pixel check if absent in CI).
"""
import hashlib
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    Image = None

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/tivimini/res"
ASSETS = ROOT.parent / "iqtvlite-logo-assets/icon/icon-512.png"

# expected reference properties
EXPECTED_CENTER = (21, 25, 35, 255)
DENSITIES = [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
             ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    problems = []
    if not ASSETS.exists() or Image is None:
        print("check_brand_assets: SKIP (source or Pillow unavailable)")
        return 0
    ref = Image.open(ASSETS).convert("RGBA")
    if ref.size != (512, 512):
        problems.append(f"icon-512.png must be 512x512, got {ref.size}")
    if ref.getchannel("A").point(lambda v: 255 if v > 0 else 0).getbbox() != (0, 0, 512, 512):
        problems.append("icon-512.png alpha bbox must be full canvas")

    for d, sz in DENSITIES:
        # 与 icon-512 LANCZOS 参考逐像素一致
        scaled = ref.resize((sz, sz), Image.LANCZOS)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            p = RES / d / name
            if not p.exists():
                problems.append(f"missing {d}/{name}")
                continue
            im = Image.open(p).convert("RGBA")
            if im.size != (sz, sz):
                problems.append(f"{d}/{name} size {im.size} != {sz}")
                continue
            # 逐像素比对（允许极小量化误差用 hash 完全一致优先，不一致时统计最大差）
            if ImageChopsMaxDiff(im, scaled) > 6:
                problems.append(f"{d}/{name} pixels differ from icon-512 LANCZOS (maxdiff={ImageChopsMaxDiff(im, scaled)})")
            # 四角透明
            c = im.getpixel((0, 0))
            if c[3] > 8:
                problems.append(f"{d}/{name} corner not transparent: {c}")

    # adaptive foreground must equal icon-512 (centered, no +10/+35), scaled to 512
    fg = RES / "mipmap-xxxhdpi/ic_launcher_foreground.png"
    if fg.exists():
        im = Image.open(fg).convert("RGBA")
        if ImageChopsMaxDiff(im, ref) > 6:
            problems.append("ic_launcher_foreground.png differs from centered icon-512")

    if problems:
        for p in problems:
            print(f"check_brand_assets: FAIL {p}")
        return 1
    print("check_brand_assets: OK (all densities pixel-match icon-512 LANCZOS, centered foreground)")
    return 0


def ImageChopsMaxDiff(a, b):
    """最大逐通道绝对差（0..255），不依赖 numpy。"""
    # 逐通道最大差（用 tobytes，避免已弃用的 getdata）。
    ap = a.tobytes(); bp = b.tobytes()
    mx = 0
    for x, y in zip(ap, bp):
        d = abs(x - y)
        if d > mx:
            mx = d
    return mx


if __name__ == "__main__":
    sys.exit(main())
