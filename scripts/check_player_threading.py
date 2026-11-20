#!/usr/bin/env python3
"""t169 static guard: no ExoPlayer access from a background coroutine.

ExoPlayer throws `IllegalStateException: Player is accessed on the wrong thread` for any accessor
(`currentMediaItem`, `playbackState`, `volume`, …) called off the application looper. CI10109/CI10110
crashed exactly like that from `StreamPlayback$Metrics$begin$1` on `Dispatchers.Default`.

Rules checked:
  1. an inline `Dispatchers.Default { … }` / `Dispatchers.IO { … }` block containing a player access;
  2. any `launch { … }` / `async { … }` block containing a player access, unless the same block (or the
     two lines introducing it) uses `Dispatchers.Main` / `withContext(Dispatchers.Main)`.

Helper functions that only run on the application looper (e.g. an AnalyticsListener callback reading
`player.volume`) are *not* inside `launch`/`async`, so they are not flagged.

Usage: python scripts/check_player_threading.py [src-dir]
Exit code 0 = clean, 1 = violation(s).
"""
import re
import sys
from pathlib import Path

DEFAULT_SRC = Path("android/app/src/tivimini/java/com/cjlhll/iptv")

BG_INLINE = re.compile(r"Dispatchers\.(Default|IO)\s*\{")
LAUNCH = re.compile(r"\b(launch|async)\s*(\([^)]*\))?\s*\{")
MAIN_HINT = re.compile(r"Dispatchers\.Main|applicationLooper|Handler\(|runOnUiThread")
PLAYER_ACCESS = re.compile(
    r"\bplayer\s*\.\s*(currentMediaItem|currentPosition|playbackState|playWhenReady|volume|isPlaying|"
    r"duration|mediaItemCount|currentMediaItemIndex|applicationLooper|setMediaItem|prepare|seekTo)"
)


def body_until_balanced(lines, start_index, opener_line):
    """Yield (line_no, text) for the block body, tracking brace depth."""
    depth = opener_line.count("{") - opener_line.count("}")
    j = start_index + 1
    out = []
    while j < len(lines) and depth > 0 and j - start_index < 80:
        out.append((j + 1, lines[j]))
        depth += lines[j].count("{") - lines[j].count("}")
        j += 1
    return out


def scan(path: Path):
    problems = []
    lines = path.read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        # rule 1: inline background dispatcher block
        if BG_INLINE.search(line):
            for line_no, text in body_until_balanced(lines, i, line):
                if PLAYER_ACCESS.search(text):
                    problems.append((path, line_no, "background dispatcher block", text.strip()))
        # rule 2: launch/async blocks without a Main hint
        if LAUNCH.search(line):
            context = " ".join(lines[max(0, i - 2):i + 1])
            if MAIN_HINT.search(context):
                continue
            for line_no, text in body_until_balanced(lines, i, line):
                if PLAYER_ACCESS.search(text):
                    problems.append((path, line_no, "coroutine without main dispatcher", text.strip()))
    return problems


def main() -> int:
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SRC
    if not src.exists():
        print(f"check_player_threading: source dir not found: {src}")
        return 1
    problems = []
    for f in sorted(src.rglob("*.kt")):
        problems.extend(scan(f))
    if problems:
        print("check_player_threading: FAILED")
        for path, line, why, text in problems:
            print(f"  {path}:{line}: {why}: {text}")
        print("Move the access to player.applicationLooper / main thread and read a snapshot instead.")
        return 1
    print("check_player_threading: OK (no player access from background coroutines)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
