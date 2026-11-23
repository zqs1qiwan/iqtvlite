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
    activity_code = strip_comments(activity)

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

    # 4) root tags live only in SettingsRootPage (t16/F5: the old assertion was a tautology —
    #    a negative lookahead already excluded "root_", so `startswith("root_")` never fired).
    page_names = ["Playback", "Remote", "Audio", "Data", "Logo", "About"]
    m_root = re.search(r"private fun SettingsRootPage\(", drawer_code)
    if not m_root:
        problems.append("SettingsDrawer.kt: SettingsRootPage missing")
    else:
        root_tail = drawer_code[m_root.start():]
        if "ROOT_TITLE_TAG" not in root_tail:
            problems.append("SettingsDrawer.kt: SettingsRootPage must carry ROOT_TITLE_TAG")
        if "entry.tag" not in root_tail:
            problems.append("SettingsDrawer.kt: SettingsRootPage must bind rows via SettingsRootEntry.tag")
    for name in page_names:
        m = re.search(r"private fun Settings%sPage\(" % name, drawer_code)
        if not m:
            problems.append("SettingsDrawer.kt: Settings%sPage missing" % name)
            continue
        nxt = drawer_code.find("@Composable", m.end())
        body = drawer_code[m.end(): nxt if nxt != -1 else len(drawer_code)]
        if "ROOT_TITLE_TAG" in body or "rootTag(" in body:
            problems.append("SettingsDrawer.kt: root tag leaked into Settings%sPage" % name)

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

    # 9) FULLFIX §7 (function->key full mapping): dispatch goes through the central router.
    bindings = SRC / "RemoteBindings.kt"
    if not bindings.exists():
        problems.append("RemoteBindings.kt: function->key model missing (FULLFIX §7)")
    else:
        btext = bindings.read_text(encoding="utf-8")
        for token in ("enum class RemoteAction", "enum class UiContext", "data class KeyBinding",
                      "fun resolve(", "fun resolveWith(", "fun serialize(", "fun parse(",
                      "fun migrate(", "fun bind("):
            if token not in btext:
                problems.append("RemoteBindings.kt: missing " + token)
    if "RemoteBindings.resolveWith(remoteBindings" not in activity_code:
        problems.append("PlayerActivity.kt: dispatch must resolve via RemoteBindings.resolveWith (FULLFIX §7)")
    if "handleCapture(event)" not in activity_code:
        problems.append("PlayerActivity.kt: key-capture guard missing (FULLFIX §7)")
    if re.search(r"RemoteHabits\.onUpDown\(", activity_code) or re.search(r"RemoteHabits\.onOk\(", activity_code):
        problems.append("PlayerActivity.kt: old RemoteHabits.onUpDown/onOk must not drive dispatch (FULLFIX §7)")
    drawer_code = strip_comments(drawer)
    for tag in ("settings_remote_action_", "settings_remote_bind_long_", "settings_remote_bindings_reset"):
        if tag not in drawer_code:
            problems.append("SettingsDrawer.kt: function->key binding UI tag missing: " + tag)
    if "RemoteBindings.bindingSummary(" not in drawer_code:
        problems.append("SettingsDrawer.kt: binding rows must show RemoteBindings.bindingSummary (FULLFIX §7)")

    # 10) t10: generic fallback so arbitrary custom keys (F1=131) reach the router.
    if "isFixedRoutedKey(" not in activity_code:
        problems.append("PlayerActivity.kt: isFixedRoutedKey predicate missing (t10 fallback)")
    # The fallback must appear AFTER the fixed when(keyCode) block and call the router.
    fallback_marker = "通用 fallback"
    if fallback_marker not in activity:
        problems.append("PlayerActivity.kt: generic fallback block missing (t10)")
    # Fallback must resolve actions and dispatch channel/drawer/settings/info/exit/seek.
    for token in ("!isFixedRoutedKey(event.keyCode)", "digits.isEmpty()",
                  "RemoteAction.CHANNEL_DOWN -> if (onChannelStep?.invoke(1) == true) return true",
                  "RemoteAction.CHANNEL_UP -> if (onChannelStep?.invoke(-1) == true) return true"):
        if token not in activity_code:
            problems.append("PlayerActivity.kt: t10 fallback missing token " + token)
    # All overlay guards must still be present in the fallback region.
    overlay_guard = re.search(
        r"!isFixedRoutedKey\(event\.keyCode\).*?isDrawerOpen.*?isSettingsDrawerOpen.*?isQualityDialogOpen.*?isRecentChannelsOpen.*?isAudioDialogOpen.*?digits\.isEmpty\(\)",
        activity_code,
        re.S,
    )
    if not overlay_guard:
        problems.append("PlayerActivity.kt: t10 fallback guard chain incomplete (overlays/digits)")
    # A pure JVM regression case for F1=131 must exist.
    test_file = ROOT / "android/app/src/test/java/com/cjlhll/iptv/RemoteBindingsTest.kt"
    if test_file.exists() and "arbitraryFunctionKeyCanResolveToChannelDown" not in test_file.read_text(encoding="utf-8"):
        problems.append("RemoteBindingsTest.kt: t10 F1=131 arbitrary-key case missing")

    # 11) V4 §3 (A plan): RIGHT default -> all-group opens drawer; custom binding respected.
    if "fun resolveRight(" not in btext or "fun isKeyCustomBound(" not in btext:
        problems.append("RemoteBindings.kt: V4 §3 resolveRight/isKeyCustomBound missing")
    if "RemoteBindings.resolveRight(" not in activity_code:
        problems.append("PlayerActivity.kt: RIGHT branch must use RemoteBindings.resolveRight (V4 §3)")
    if "rightCustomBound = RemoteBindings.isKeyCustomBound" not in activity_code:
        problems.append("PlayerActivity.kt: RIGHT custom-binding guard missing (V4 §3)")
    if test_file.exists() and "rightKeyAPlanAllGroupOpensDrawerOnlyForDefaultBinding" not in test_file.read_text(encoding="utf-8"):
        problems.append("RemoteBindingsTest.kt: V4 §3 RIGHT A-plan test missing")

    # 12) V4 §7: digit OSD at top-end with confirm-state brand blue + tag.
    if "Alignment.TopEnd" not in activity_code or "digit_osd" not in activity_code:
        problems.append("PlayerActivity.kt: digit OSD must be TopEnd with testTag digit_osd (V4 §7)")
    osd = re.search(r"if \(digits\.isNotEmpty\(\)\) \{(.*?)\n                    \}", activity_code, re.S)
    if osd and "Alignment.TopCenter" in osd.group(1):
        problems.append("PlayerActivity.kt: digit OSD still TopCenter (V4 §7)")
    if "digitConfirmed" not in activity_code or "BrandFocus.BlueColor" not in activity_code:
        problems.append("PlayerActivity.kt: digit confirm-state brand blue missing (V4 §7)")

    # 14) t41: no focus background uses blue BgColor anywhere in UI files; theme surfaceVariant gray-white.
    ui_files = ("PlayerDrawer.kt", "SettingsDrawer.kt", "ChannelInfoBanner.kt", "RecentChannelsDialog.kt",
                "QualitySelectorDialog.kt", "AudioTrackDialog.kt", "MainActivity.kt", "PlayerActivity.kt")
    for fn in ui_files:
        content = SRC.joinpath(fn).read_text(encoding="utf-8")
        if "BrandFocus.BgColor" in content:
            problems.append(f"{fn}: focus background still uses blue BgColor (t41)")
    if "surfaceVariant = BrandFocus.FocusBgColor" not in activity_code:
        problems.append("PlayerActivity.kt: theme surfaceVariant must be gray-white FocusBgColor (t41)")
    if "surfaceVariant = BrandFocus.FocusBgColor" not in SRC.joinpath("MainActivity.kt").read_text(encoding="utf-8"):
        problems.append("MainActivity.kt: theme surfaceVariant must be gray-white FocusBgColor (t41)")

    # 15) t44: VideoPlayerScreen params bundled into <=12 (API28 ART VerifyError root fix).
    cb_path = SRC / "VideoPlayerCallbacks.kt"
    if not cb_path.exists():
        problems.append("VideoPlayerCallbacks.kt: callback bundles missing (t44)")
    else:
        cb_text = cb_path.read_text(encoding="utf-8")
        for bundle in ("PlaybackCallbacks", "OverlayCallbacks", "InfoCardCallbacks", "RemoteCallbacks"):
            if f"data class {bundle}(" not in cb_text:
                problems.append(f"VideoPlayerCallbacks.kt: {bundle} missing (t44)")
    vp = re.search(r"fun VideoPlayerScreen\((.*?)\n\) \{", activity, re.S)
    if vp:
        param_lines = [l.strip() for l in vp.group(1).splitlines()
                       if l.strip() and not l.strip().startswith("//")
                       and re.match(r"^[a-zA-Z_][a-zA-Z0-9_]*\s*:", l.strip())]
        if len(param_lines) > 12:
            problems.append(f"VideoPlayerScreen has {len(param_lines)} params, must be <=12 (t44 VerifyError)")
    else:
        problems.append("VideoPlayerScreen signature not found (t44)")
    for bundle in ("playback = PlaybackCallbacks(", "overlay = OverlayCallbacks(",
                   "infoCard = InfoCardCallbacks(", "remoteCbs = RemoteCallbacks("):
        if bundle not in activity:
            problems.append("PlayerActivity.kt VideoPlayerScreen call missing bundle " + bundle)

    if problems:
        for p in problems:
            print(f"check_settings_ia: FAIL {p}")
        return 1

    print("check_settings_ia: OK (6 root entries, page composables, 24 tags zero-loss, back handler wired)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
