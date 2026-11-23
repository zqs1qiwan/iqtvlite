#!/usr/bin/env python
"""Static guard for the refactored Settings two-level IA, unified RemoteBindings router, and Back policy."""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/tivimini/java/com/cjlhll/iptv"
MODEL = SRC / "SettingsIA.kt"
DRAWER = SRC / "SettingsDrawer.kt"
ACTIVITY = SRC / "PlayerActivity.kt"
CONTROLLER = SRC / "PlayerUiController.kt"
TOKENS = SRC / "TvDesignTokens.kt"

EXPECTED_ROOT = ["PLAYBACK", "REMOTE", "AUDIO", "DATA", "LOGO", "ABOUT"]
EXPECTED_TITLES = ["播放与画面", "遥控器与操作", "音频与字幕", "数据与更新", "台标与缓存", "关于"]
RETIRED = {"settings_epg_source", "settings_remote_back"}

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
    out, in_block = [], False
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
        lc = s.find("//")
        if lc != -1:
            s = s[:lc]
        out.append(s)
    return "\n".join(out)


def main() -> int:
    problems = []
    for path in (MODEL, DRAWER, ACTIVITY, CONTROLLER, TOKENS):
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
    activity_code = strip_comments(activity)
    controller_code = strip_comments(CONTROLLER.read_text(encoding="utf-8"))
    tokens_code = strip_comments(TOKENS.read_text(encoding="utf-8"))

    # 1) 6 root pages in order
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

    # 2) One composable per page
    for page in EXPECTED_ROOT:
        camel = page.capitalize()
        if not re.search(rf"private fun Settings{camel}Page\(", drawer):
            problems.append(f"SettingsDrawer.kt: missing Settings{camel}Page composable")
    if not re.search(r"private fun SettingsRootPage\(", drawer):
        problems.append("SettingsDrawer.kt: missing SettingsRootPage composable")

    # 3) Zero-loss tags (excluding retired)
    for tag in LEGACY_TAGS:
        if tag in RETIRED:
            if f'"{tag}"' in drawer_code:
                problems.append(f"SettingsDrawer.kt: retired tag {tag} must not be rendered")
            continue
        if f'"{tag}"' not in drawer and tag not in model:
            problems.append(f"missing legacy tag {tag}")
    if len(re.findall(r'tag = "settings_refresh_epg"', drawer_code)) != 1:
        problems.append("SettingsDrawer.kt: merged manual-EPG row must exist exactly once")

    # 4) Scroll + BringIntoView + no LazyColumn
    if '"settings_list"' not in drawer_code:
        problems.append('SettingsDrawer.kt: missing "settings_list" tag')
    if "LazyColumn" in drawer_code:
        problems.append("SettingsDrawer.kt: LazyColumn is forbidden")
    for token in ("verticalScroll(", "bringIntoViewRequester(", "bringIntoView()", "SettingsGroupHeader("):
        if token not in drawer:
            problems.append(f"SettingsDrawer.kt: missing {token}")

    # 5) Unified RemoteBindings router + short/long-press separation
    if "RemoteBindings.resolveWith(remoteBindings" not in activity_code:
        problems.append("PlayerActivity.kt: dispatch must resolve via RemoteBindings.resolveWith")
    if "RemoteBindings.hasLongPressBinding(" not in activity_code:
        problems.append("PlayerActivity.kt: long-press separation guard missing")
    if "handleCapture(event)" not in activity_code:
        problems.append("PlayerActivity.kt: key-capture guard missing")
    if "RemoteBindings.applyUpDownHabit(" not in activity_code or "RemoteBindings.applyOkHabit(" not in activity_code:
        problems.append("PlayerActivity.kt: quick remote habits must sync into RemoteBindings")
    for tag in ("settings_remote_action_", "settings_remote_bind_long_", "settings_remote_bindings_reset"):
        if tag not in drawer_code:
            problems.append(f"SettingsDrawer.kt: missing binding tag {tag}")

    # 6) Unified visual tokens (neutral frosted FocusBgColor, no blue BgColor focus)
    for fn in ("PlayerDrawer.kt", "SettingsDrawer.kt", "ChannelInfoBanner.kt", "RecentChannelsDialog.kt",
               "QualitySelectorDialog.kt", "AudioTrackDialog.kt", "MainActivity.kt", "PlayerActivity.kt"):
        if "BrandFocus.BgColor" in (SRC / fn).read_text(encoding="utf-8"):
            problems.append(f"{fn}: focus background must not use blue BgColor")
    if "surfaceVariant = FocusSurface" not in tokens_code or "TvDesignTokens.colorScheme()" not in activity_code:
        problems.append("TvDesignTokens / PlayerActivity: must use unified TvDesignTokens.colorScheme()")

    # 7) VideoPlayerScreen parameter count <= 12 (API28 ART VerifyError guard)
    vp = re.search(r"fun VideoPlayerScreen\((.*?)\n\) \{", activity, re.S)
    if vp:
        param_lines = [l.strip() for l in vp.group(1).splitlines()
                       if l.strip() and not l.strip().startswith("//")
                       and re.match(r"^[a-zA-Z_][a-zA-Z0-9_]*\s*:", l.strip())]
        if len(param_lines) > 12:
            problems.append(f"VideoPlayerScreen has {len(param_lines)} params, must be <=12")
    else:
        problems.append("VideoPlayerScreen signature not found")

    # 8) Unified pure Back policy via uiController.decideBack
    if "BackKeyPolicy.decide(" not in controller_code:
        problems.append("PlayerUiController.kt: must delegate BACK decisions to BackKeyPolicy.decide")
    if "uiController.decideBack(" not in activity_code or "BackKeyPolicy.Action.EXIT_APP -> finish()" not in activity_code:
        problems.append("PlayerActivity.kt: BACK must dispatch through uiController.decideBack with single finish()")
    if "再按一次退出app" not in activity:
        problems.append("PlayerActivity.kt: double-press exit toast missing")

    if problems:
        for p in problems:
            print(f"check_settings_ia: FAIL {p}")
        return 1

    print("check_settings_ia: OK (6 root entries, unified PlayerUiController, synced RemoteBindings, clean Back)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
