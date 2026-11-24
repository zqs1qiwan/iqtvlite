#!/usr/bin/env python
"""t11 (P0): static guard for the logo disk-cache write format.

Background: on the target device (TR100-G9 / API 28) `Bitmap.compress(WEBP)` crashed with
SIGSEGV inside `libhwui SkWebpEncoder::Encode -> ImportYUVAFromRGBA (pc 0x0)`, killing the
process on the first cold start (docs/LOGO_WEBP_CRASH_FIX.md). A native crash cannot be caught
by try/catch, so the guard asserts the encoder format itself, the defensive write wrapper and the
legacy `.webp` read compatibility.

Exit 0 = OK, 1 = violation.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/java/com/cjlhll/iptv"
LOADER = SRC / "LogoLoader.kt"
STORE = SRC / "LogoDiskStore.kt"
TEST = ROOT / "android/app/src/test/java/com/cjlhll/iptv/LogoWriteFormatTest.kt"
DOC = ROOT / "docs/LOGO_WEBP_CRASH_FIX.md"

WEBP_TOKENS = [
    "CompressFormat.WEBP",
    "CompressFormat.WEBP_LOSSY",
    "CompressFormat.WEBP_LOSSLESS",
]


def strip_comments(text: str) -> str:
    """Drop // and /* */ comments so documentation may name the forbidden token."""
    out = []
    in_block = False
    for line in text.splitlines():
        s = line
        if in_block:
            end = s.find("*/")
            if end == -1:
                continue
            s = s[end + 2:]
            in_block = False
        start = s.find("/*")
        if start != -1:
            end = s.find("*/", start + 2)
            if end == -1:
                s = s[:start]
                in_block = True
            else:
                s = s[:start] + s[end + 2:]
        line_comment = s.find("//")
        if line_comment != -1:
            s = s[:line_comment]
        out.append(s)
    return "\n".join(out)


def main() -> int:
    problems = []

    for path in (LOADER, STORE):
        if not path.exists():
            problems.append(f"missing source file: {path.name}")
            continue
        code = strip_comments(path.read_text(encoding="utf-8"))
        for token in WEBP_TOKENS:
            if token in code:
                problems.append(f"{path.name}: WEBP encoding is forbidden (found {token})")

    loader = LOADER.read_text(encoding="utf-8") if LOADER.exists() else ""
    store = STORE.read_text(encoding="utf-8") if STORE.exists() else ""

    if "Bitmap.CompressFormat.PNG" not in loader:
        problems.append("LogoLoader.kt: write path must encode with Bitmap.CompressFormat.PNG")
    if "writeAtomicallyDefensively" not in loader or "runCatching" not in loader:
        problems.append("LogoLoader.kt: the disk write must be wrapped defensively (runCatching)")
    if "WRITE_FORMAT_NAME" not in loader:
        problems.append("LogoLoader.kt: WRITE_FORMAT_NAME must stay exposed for the JVM regression test")
    if "write_failed=png" not in loader:
        problems.append("LogoLoader.kt: a failed write must log write_failed=png")

    if not re.search(r'const\s+val\s+EXTENSION\s*=\s*"png"', store):
        problems.append('LogoDiskStore.kt: EXTENSION must be "png"')
    if not re.search(r'LEGACY_EXTENSIONS\s*:\s*List<String>\s*=\s*listOf\("webp"\)', store):
        problems.append('LogoDiskStore.kt: LEGACY_EXTENSIONS must keep "webp" readable')
    if "DISK_EXTENSIONS" not in store:
        problems.append("LogoDiskStore.kt: stats/prune/clear must cover DISK_EXTENSIONS")

    if ".${LogoDiskStore.EXTENSION}" not in loader:
        problems.append("LogoLoader.kt: cache file names must be built from LogoDiskStore.EXTENSION")
    if "legacyCacheFile(" not in loader or "readableCacheFile(" not in loader:
        problems.append("LogoLoader.kt: legacy .webp must stay readable (legacyCacheFile/readableCacheFile)")

    if not TEST.exists():
        problems.append("missing regression test: LogoWriteFormatTest.kt")
    if not DOC.exists():
        problems.append("missing fix record: docs/LOGO_WEBP_CRASH_FIX.md")

    if problems:
        for p in problems:
            print(f"check_logo_webp: FAIL {p}")
        return 1

    print("check_logo_webp: OK (PNG writes, defensive wrapper, legacy .webp readable, no WEBP encoder)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
