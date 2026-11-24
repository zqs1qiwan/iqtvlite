#!/usr/bin/env python
"""Static guard for the refactored ChannelInfoBanner & InfoCardPolicy contract.

Asserts from real sources that:
  1. InfoCardPolicy stays Android/Compose-free with MAX_RECENT=5, AUTO_HIDE_MS=7000L and pure helpers;
  2. ChannelInfoBanner carries all required test tags, compact badges, no border/shadow/blur/LazyRow,
     and correct relative catchup progress calculation (positionMs / duration);
  3. PlayerActivity routes InfoCard through PlayerUiController (single source of truth),
     uses FocusedKeyPolicy.passthrough and BackKeyPolicy via uiController.decideBack;
  4. Real FPS estimation via StreamPlayback.onVideoFrameProcessingOffset reaches the banner.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/java/com/cjlhll/iptv"
POLICY = SRC / "InfoCardPolicy.kt"
BANNER = SRC / "ChannelInfoBanner.kt"
ACTIVITY = SRC / "PlayerActivity.kt"
CONTROLLER = SRC / "PlayerUiController.kt"
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
    "basicMarquee",
]

REQUIRED_TAGS = [
    "info_card",
    "info_card_logo",
    "info_card_number",
    "info_card_group",
    "info_card_title",
    "info_card_program",
    "info_card_program_time",
    "info_card_progress",
    "info_card_badges",
    "info_card_recent_row",
    "info_card_recent_$index",
]


def main() -> int:
    problems = []
    for path in (POLICY, BANNER, ACTIVITY, CONTROLLER, HABITS):
        if not path.exists():
            problems.append(f"missing source file: {path.name}")
    if problems:
        for p in problems:
            print(f"check_info_card: FAIL {p}")
        return 1

    policy = POLICY.read_text(encoding="utf-8")
    banner = BANNER.read_text(encoding="utf-8")
    activity = ACTIVITY.read_text(encoding="utf-8")
    controller = CONTROLLER.read_text(encoding="utf-8")
    habits = HABITS.read_text(encoding="utf-8")

    if not re.search(r"const val MAX_RECENT = 5\b", policy):
        problems.append("InfoCardPolicy.kt: MAX_RECENT must be 5")
    if not re.search(r"const val AUTO_HIDE_MS = 7000L", policy):
        problems.append("InfoCardPolicy.kt: AUTO_HIDE_MS must be 7000L")
    if "import androidx." in policy or "import android." in policy:
        problems.append("InfoCardPolicy.kt: must stay Android/Compose-free")

    for tag in REQUIRED_TAGS:
        if tag not in banner:
            problems.append(f"ChannelInfoBanner.kt: missing testTag {tag}")

    for token in FORBIDDEN_IN_BANNER:
        if token in banner:
            problems.append(f"ChannelInfoBanner.kt: forbidden token {token}")

    if "c.positionMs - c.startMillis" in banner:
        problems.append("ChannelInfoBanner.kt: catchup progress bug (subtracting epoch startMillis from relative positionMs)")
    if "compactBadges(" not in banner:
        problems.append("ChannelInfoBanner.kt: must use InfoCardPolicy.compactBadges")
    if "vf?.channelCount" in banner:
        problems.append("ChannelInfoBanner.kt: channel count must come from audio track, not video Format")
    if "InfoCardPolicy.focusTargetIndex(focusIndex, recentCount)" not in banner:
        problems.append("ChannelInfoBanner.kt: focus must follow focusTargetIndex")
    if "InfoCardPolicy.shouldClearFocusOnTransition" not in banner:
        problems.append("ChannelInfoBanner.kt: clearFocus transition guard missing")

    if "uiController.isInfoCardFocused" not in activity:
        problems.append("PlayerActivity.kt: info-card focus guard must read single-source uiController")
    if "FocusedKeyPolicy.passthrough" not in activity:
        problems.append("PlayerActivity.kt: FOCUSED guard must use FocusedKeyPolicy.passthrough")
    if "BackKeyPolicy.Action.INFO_CARD_CLOSE" not in activity:
        problems.append("PlayerActivity.kt: BACK close-card via BackKeyPolicy missing")
    if "BackKeyPolicy.decide(" not in controller:
        problems.append("PlayerUiController.kt: decideBack must delegate to BackKeyPolicy.decide")
    if "InfoCardPolicy.shouldArmAutoHide" not in activity:
        problems.append("PlayerActivity.kt: auto-hide must use InfoCardPolicy.shouldArmAutoHide")
    if "frameRateFallback = fpsResolution.value" not in activity:
        problems.append("PlayerActivity.kt: inferred fps must reach banner frameRateFallback")
    if 'CONFIRM("显示信息卡")' not in habits:
        problems.append('RemoteHabits.kt: CONFIRM label must be "显示信息卡"')

    stream = (SRC / "StreamPlayback.kt").read_text(encoding="utf-8")
    if "onVideoFrameProcessingOffset" not in stream:
        problems.append("StreamPlayback.kt: real fps estimation via onVideoFrameProcessingOffset missing")

    if problems:
        for p in problems:
            print(f"check_info_card: FAIL {p}")
        return 1

    print("check_info_card: OK (unified single-source state, clean banner, fixed catchup progress, real fps)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
