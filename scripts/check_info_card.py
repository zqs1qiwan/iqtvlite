#!/usr/bin/env python
"""t4: static guard for the info-card v2 implementation (docs/INFO_CARD_V2.md §7.4).

Asserts from the real sources that:
  1. InfoCardPolicy exists with MAX_RECENT=6 / AUTO_HIDE_MS=3000 and the full pure API;
  2. ChannelInfoBanner renders the 96x60 logo, headlineLarge name, titleLarge programme,
     40dp recent-card logo, the info_card_* tags and the entry hint;
  3. the banner has no border/BorderStroke/shadow/elevation/blur/graphicsLayer and no LazyRow;
  4. PlayerActivity wires the focus guard (isInfoCardFocused + setInfoCardOkController) and the
     old hard-coded `delay(5000)` info-banner timer is gone;
  5. RemoteHabits ships the approved default (CONFIRM "显示信息卡").

Exit 0 = OK, 1 = violation.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/tivimini/java/com/cjlhll/iptv"
POLICY = SRC / "InfoCardPolicy.kt"
BANNER = SRC / "ChannelInfoBanner.kt"
ACTIVITY = SRC / "PlayerActivity.kt"
HABITS = SRC / "RemoteHabits.kt"

FORBIDDEN_IN_BANNER = [
    "BorderStroke",
    "border(",
    "shadow(",
    "elevation",
    "blur(",
    "graphicsLayer",
    "LazyRow",
    "animateContentSize",
]

REQUIRED_TAGS = [
    "info_card",
    "info_card_logo",
    "info_card_number",
    "info_card_title",
    "info_card_program",
    "info_card_progress",
    "info_card_tech",
    "info_card_recent_label",
    "info_card_recent_row",
    "info_card_recent_$index",
    "info_card_hint",
]


def main() -> int:
    problems = []

    for path in (POLICY, BANNER, ACTIVITY, HABITS):
        if not path.exists():
            problems.append(f"missing source file: {path.name}")
    if problems:
        for p in problems:
            print(f"check_info_card: FAIL {p}")
        return 1

    policy = POLICY.read_text(encoding="utf-8")
    banner = BANNER.read_text(encoding="utf-8")
    activity = ACTIVITY.read_text(encoding="utf-8")
    habits = HABITS.read_text(encoding="utf-8")

    # 1) pure policy
    if not re.search(r"const val MAX_RECENT = 6\b", policy):
        problems.append("InfoCardPolicy.kt: MAX_RECENT must be 6")
    if not re.search(r"const val AUTO_HIDE_MS = 3000L", policy):
        problems.append("InfoCardPolicy.kt: AUTO_HIDE_MS must be 3000L")
    for fn in ("fun isVisible(", "fun autoHideMs(", "fun canFocusRecent(", "fun nextFocusIndex(",
               "fun showRecentRow(", "fun onExternalZap(", "fun techLine("):
        if fn not in policy:
            problems.append(f"InfoCardPolicy.kt: missing {fn}")
    if "cardRecentItems(" not in policy:
        problems.append("InfoCardPolicy.kt: missing cardRecentItems(")
    if "import androidx." in policy or "import android." in policy:
        problems.append("InfoCardPolicy.kt: must stay Android/Compose-free (JVM testable)")

    # 2) banner visual contract
    if "96.dp" not in banner or "60.dp" not in banner:
        problems.append("ChannelInfoBanner.kt: the big logo must be 96x60dp")
    if "headlineLarge" not in banner:
        problems.append("ChannelInfoBanner.kt: the channel name must use headlineLarge")
    if "titleLarge" not in banner:
        problems.append("ChannelInfoBanner.kt: the programme title must use titleLarge")
    if "40.dp" not in banner:
        problems.append("ChannelInfoBanner.kt: recent cards need a 40dp logo")
    if "InfoCardPolicy.ENTRY_HINT" not in banner:
        problems.append("ChannelInfoBanner.kt: the entry hint must come from InfoCardPolicy.ENTRY_HINT")
    if "← 频道列表" not in policy:
        problems.append("InfoCardPolicy.kt: ENTRY_HINT must mention 频道列表 (LEFT)")
    for tag in REQUIRED_TAGS:
        if tag not in banner:
            problems.append(f"ChannelInfoBanner.kt: missing testTag {tag}")

    # 3) no borders/shadows/blur, no LazyRow
    for token in FORBIDDEN_IN_BANNER:
        if token in banner:
            problems.append(f"ChannelInfoBanner.kt: forbidden token {token}")
    if re.search(r"RoundedCornerShape\((?!0\.dp|2\.dp)", banner):
        problems.append("ChannelInfoBanner.kt: only 0dp/2dp shapes are allowed")

    # 4) Activity wiring
    if "isInfoCardFocused" not in activity or "setInfoCardOkController" not in activity:
        problems.append("PlayerActivity.kt: info-card focus guard / second-stage OK wiring missing")
    if "onInfoCardFocusedChanged" not in activity:
        problems.append("PlayerActivity.kt: onInfoCardFocusedChanged mirror missing")
    if "delay(5000)" in activity:
        problems.append("PlayerActivity.kt: the old hard-coded delay(5000) info-banner timer must be gone")
    if "InfoCardPolicy.autoHideMs" not in activity:
        problems.append("PlayerActivity.kt: auto-hide must go through InfoCardPolicy.autoHideMs")
    if "InfoCardPolicy.cardRecentItems" not in activity:
        problems.append("PlayerActivity.kt: recent cards must be derived via InfoCardPolicy.cardRecentItems")

    # 5) habits default
    if 'CONFIRM("显示信息卡")' not in habits:
        problems.append('RemoteHabits.kt: CONFIRM label must be "显示信息卡"')
    if "val ok: Ok = Ok.CONFIRM" not in habits:
        problems.append("RemoteHabits.kt: DEFAULT.ok must be Ok.CONFIRM")

    if problems:
        for p in problems:
            print(f"check_info_card: FAIL {p}")
        return 1

    print("check_info_card: OK (policy + 96x60/headlineLarge + recent strip + tags + focus guard + defaults)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
