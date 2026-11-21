#!/usr/bin/env python
"""t192: static guard for the settings drawer reachability fix.

Asserts from the real source that the settings list can actually scroll and that every row keeps
itself visible while the D-pad moves:

  1. `SettingsDrawer` uses `verticalScroll(...)` (or a LazyColumn) on the list;
  2. the list carries the `settings_list` test tag;
  3. rows use `bringIntoViewRequester(...)` + call `bringIntoView()` when focused;
  4. the group header is NOT focusable (it must not steal focus while scrolling);
  5. the key rows that used to fall below the 1080p fold still carry their test tags
     (settings_refresh_epg / settings_auto_epg / settings_audio_track / settings_logo_clear).

Exit 0 = OK, 1 = violation.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/tivimini/java/com/cjlhll/iptv"
DRAWER = SRC / "SettingsDrawer.kt"

REQUIRED_TAGS = [
    "settings_list",
    "settings_refresh_epg",
    "settings_auto_epg",
    "settings_refresh_playlist",
    "settings_auto_playlist",
    "settings_audio_track",
    "settings_logo_clear",
    "settings_remote_updown",
]


def main() -> int:
    text = DRAWER.read_text(encoding="utf-8")
    problems = []

    if "verticalScroll(" not in text and "LazyColumn" not in text:
        problems.append("SettingsDrawer.kt: the settings list is not scrollable")

    for tag in REQUIRED_TAGS:
        if f'"{tag}"' not in text:
            problems.append(f"SettingsDrawer.kt: missing testTag {tag}")

    if "bringIntoViewRequester(" not in text:
        problems.append("SettingsDrawer.kt: rows do not request bring-into-view")
    if "bringIntoView()" not in text:
        problems.append("SettingsDrawer.kt: bringIntoView() is never called")

    # the group header must stay a plain label (no focusable/clickable)
    idx = text.find("private fun SettingsGroupHeader(")
    if idx == -1:
        problems.append("SettingsDrawer.kt: SettingsGroupHeader not found")
    else:
        block = text[idx: idx + 600]
        if "focusable()" in block or "clickable {" in block:
            problems.append("SettingsDrawer.kt: the group header can take focus")

    if problems:
        for p in problems:
            print(f"check_settings_scroll: FAIL {p}")
        return 1

    print("check_settings_scroll: OK (list scrolls, rows bring themselves into view, key tags present)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
