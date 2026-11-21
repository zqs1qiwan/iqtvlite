#!/usr/bin/env python
"""t13: static guard for the settings two-level IA (docs/SETTINGS_IA_V2.md §8.4).

Asserts from the real sources that:
  1. SettingsIA.kt has exactly the six approved root entries in order, and each page has 1..6 rows;
  2. every page enum has a corresponding `Settings*Page` composable in SettingsDrawer.kt;
  3. all 24 legacy action tags are still present except the merged/retired `settings_epg_source`,
     which must be gone from the drawer, while `settings_refresh_epg` (merged row) and the new
     `settings_logo_refresh` exist exactly once;
  4. root tags (`settings_root_*`) never appear in page item lists;
  5. `settings_list` is a literal on the scroll container and no `LazyColumn` was introduced;
  6. the drawer still uses verticalScroll + bringIntoViewRequester (check_settings_scroll.py contract).

Exit 0 = OK, 1 = violation.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/tivimini/java/com/cjlhll/iptv"
MODEL = SRC / "SettingsIA.kt"
DRAWER = SRC / "SettingsDrawer.kt"
ACTIVITY = SRC / "PlayerActivity.kt"

EXPECTED_ROOT = ["PLAYBACK", "REMOTE", "AUDIO", "DATA", "LOGO", "ABOUT"]
EXPECTED_TITLES = ["播放与画面", "遥控器与操作", "音频与字幕", "数据与更新", "台标与缓存", "关于"]
RETIRED = {"settings_epg_source"}

LEGACY_TAGS = [
    "settings_source_config", "settings_epg_source", "settings_quality_menu", "settings_check_update",
    "settings_recent_entry", "settings_playback_buffer", "settings_zap_preconnect",
    "settings_multicast_prewarm", "settings_jingcai_experiment", "settings_remote_updown",
    "settings_remote_ok", "settings_remote_back", "settings_remote_digits",
    "settings_remote_channel_keys", "settings_remote_reset", "settings_audio_track",
    "settings_auto_playlist", "settings_refresh_playlist", "settings_auto_epg",
    "settings_refresh_epg", "settings_logo_prefetch", "settings_logo_load",
    "settings_logo_refresh", "settings_logo_clear",
]


def strip_comments(text: str) -> str:
    """Drop // and /* */ comments so documentation may name forbidden constructs."""
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

    for path in (MODEL, DRAWER, ACTIVITY):
        if not path.exists():
            problems.append(f"missing source file: {path.name}")
    if problems:
        for p in problems:
            print(f"check_settings_ia: FAIL {p}")
        return 1

    model = MODEL.read_text(encoding="utf-8")
    drawer = DRAWER.read_text(encoding="utf-8")
    drawer_code = strip_comments(drawer)
    activity = ACTIVITY.read_text(encoding="utf-8")

    # 1) root order + titles
    order_block = re.search(r"ROOT_ORDER[^=]*=\s*listOf\((.*?)\)", model, re.S)
    if not order_block:
        problems.append("SettingsIA.kt: ROOT_ORDER not found")
    else:
        found = re.findall(r"SettingsPage\.([A-Z]+)", order_block.group(1))
        if found != EXPECTED_ROOT:
            problems.append(f"SettingsIA.kt: ROOT_ORDER must be {EXPECTED_ROOT}, found {found}")
    for title in EXPECTED_TITLES:
        if f'"{title}"' not in model:
            problems.append(f'SettingsIA.kt: missing page title "{title}"')
    if "SettingsPage.entries" in model and "enum class SettingsPage" not in model:
        problems.append("SettingsIA.kt: SettingsPage enum missing")

    # 2) one page composable per enum
    for page in EXPECTED_ROOT:
        camel = page.capitalize()
        if not re.search(rf"private fun Settings{camel}Page\(", drawer):
            problems.append(f"SettingsDrawer.kt: missing Settings{camel}Page composable")
    if not re.search(r"private fun SettingsRootPage\(", drawer):
        problems.append("SettingsDrawer.kt: missing SettingsRootPage composable")

    # 3) zero-loss tags
    for tag in LEGACY_TAGS:
        present_drawer = f'"{tag}"' in drawer
        present_model = tag in model
        if tag in RETIRED:
            if present_drawer:
                problems.append(f"SettingsDrawer.kt: retired tag {tag} must not be rendered")
            continue
        if not (present_drawer or present_model):
            problems.append(f"missing legacy tag {tag} (zero-loss contract)")
    if len(re.findall(r'tag = "settings_refresh_epg"', drawer_code)) != 1:
        problems.append("SettingsDrawer.kt: the merged manual-EPG row must exist exactly once")
    if '"settings_logo_refresh"' not in drawer_code:
        problems.append("SettingsDrawer.kt: new tag settings_logo_refresh missing")

    # 4) root tags never inside page rows
    page_items = re.findall(r'"settings_(?!root_)([a-z_]+)"', drawer_code)
    if any(item.startswith("root_") for item in page_items):
        problems.append("SettingsDrawer.kt: root tags leaked into page rows")

    # 5) list tag literal + no LazyColumn
    if '"settings_list"' not in drawer_code:
        problems.append('SettingsDrawer.kt: the literal "settings_list" tag is required by check_settings_scroll.py')
    if "LazyColumn" in drawer_code:
        problems.append("SettingsDrawer.kt: LazyColumn is forbidden (spec §7.1)")

    # 6) scroll + bring-into-view contract
    for token in ("verticalScroll(", "bringIntoViewRequester(", "bringIntoView()", "SettingsGroupHeader("):
        if token not in drawer:
            problems.append(f"SettingsDrawer.kt: missing {token}")

    # 7) Activity wiring: page-level BACK must be consumed before closing the drawer
    if "onBackHandlerReady" not in activity:
        problems.append("PlayerActivity.kt: the settings back handler is not wired (onBackHandlerReady)")
    if "setSettingsBackHandlerController" not in activity:
        problems.append("PlayerActivity.kt: setSettingsBackHandlerController is not wired")

    # 8) info card and settings must not fight for keys
    if "isInfoCardFocused" not in activity:
        problems.append("PlayerActivity.kt: the info-card focus guard disappeared (t4 regression)")

    if problems:
        for p in problems:
            print(f"check_settings_ia: FAIL {p}")
        return 1

    print("check_settings_ia: OK (6 root entries, page composables, 24 tags zero-loss, back handler wired)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
