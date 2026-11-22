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

    # 6) t16（F1/F2）focus-loss self-healing contract — these must never regress:
    #    - the banner drives the Activity mirror from the REAL node focus (not from `state`);
    #    - FOCUSED with 0 recent items self-heals to IDLE (no "FOCUSED without a focus node");
    #    - clearFocus only on FOCUSED->IDLE (never on HIDDEN, which would steal the drawer focus);
    #    - the Activity BACK fallback closes the card instead of letting the system finish().
    if "InfoCardPolicy.focusRecoveryAction" not in banner:
        problems.append("ChannelInfoBanner.kt: F1 focus-recovery state machine missing")
    if "InfoCardPolicy.aggregateNodeFocus" not in banner:
        problems.append("ChannelInfoBanner.kt: F1 node-focus aggregation missing")
    if "onFocusedChanged(false)" not in banner:
        problems.append("ChannelInfoBanner.kt: F1 must be able to push onFocusedChanged(false)")
    if "InfoCardPolicy.shouldClearFocusOnTransition" not in banner:
        problems.append("ChannelInfoBanner.kt: F2 clearFocus transition guard missing")
    if re.search(r"fun focusRecoveryAction\(state: State, recentCount: Int\)", policy) is None:
        problems.append("InfoCardPolicy.kt: focusRecoveryAction(state, recentCount) missing")
    if "fun shouldClearFocusOnTransition(" not in policy or "fun aggregateNodeFocus(" not in policy:
        problems.append("InfoCardPolicy.kt: F1/F2 pure helpers missing")
    # Activity: BACK fallback inside the info-card focus guard
    if not re.search(r"super\.dispatchKeyEvent\(event\)\)\s*return true", activity):
        problems.append("PlayerActivity.kt: info-card guard must first hand the key to Compose")
    if re.search(r"setInfoBannerOpen\?\.invoke\(false\)\s*\n\s*return true", activity) is None:
        problems.append("PlayerActivity.kt: F1 BACK fallback (close card instead of finish) missing")
    # The mirror must no longer be derived from the state enum inside the SideEffect.
    if re.search(r"onInfoCardFocusedChanged\(infoCardState == InfoCardPolicy\.State\.FOCUSED\)", activity):
        problems.append("PlayerActivity.kt: F1 mirror must not be derived from state (node focus only)")

    # 7) t24 (INFO_CARD_V3): big logo / same-row name / right program / badges / tech line / fixed heights / single line.
    if "160.dp" not in banner or "96.dp" not in banner:
        problems.append("ChannelInfoBanner.kt: logo must be >=160x96dp (v3)")
    if "info_card_logo" not in banner:
        problems.append("ChannelInfoBanner.kt: info_card_logo tag missing")
    if "34.sp" not in banner or "headlineLarge.copy" not in banner:
        problems.append("ChannelInfoBanner.kt: channel name must be headlineLarge.copy(fontSize = 34.sp)")
    for tag in ("info_card_badges", "info_card_tech", "info_card_program_time", "info_card_number", "info_card_group"):
        if tag not in banner:
            problems.append("ChannelInfoBanner.kt: missing v3 tag " + tag)
    for fn in ("fun resolutionTier(", "fun frameRateLabel(", "fun bitrateLabel(", "fun channelLabel(",
               "fun badgeCandidates(", "fun planBadges(", "fun videoCodecLabel(", "fun audioCodecLabel(",
               "fun dynamicRangeLabel(", "fun techLine(", "fun programTitleOrPlaceholder(", "fun programTimeLine(",
               "fun smallMetaLine("):
        if fn not in policy:
            problems.append("InfoCardPolicy.kt: missing v3 pure function " + fn)
    for token in ('SD("SD")', 'HD("HD")', 'FHD("FHD")', 'UHD("4K")', "fps", "Mbps", "\u5355\u58f0\u9053",
                  "H.264", "H.265", "AV1", "SDR", "HDR10", "HLG"):
        if token not in policy:
            problems.append("InfoCardPolicy.kt: missing v3 mapping token " + token)
    if banner.count("maxLines = 1") < 8:
        problems.append("ChannelInfoBanner.kt: v3 needs >=8 single-line texts")
    if "TextOverflow.Ellipsis" not in banner:
        problems.append("ChannelInfoBanner.kt: TextOverflow.Ellipsis missing")
    for bad in ("basicMarquee", "LazyRow", "animateContentSize", "wrapContentHeight"):
        if bad in banner:
            problems.append("ChannelInfoBanner.kt: forbidden v3 token " + bad)
    for h in (".height(24.dp)", ".height(16.dp)", ".height(96.dp)"):
        if h not in banner:
            problems.append("ChannelInfoBanner.kt: fixed row height missing " + h)
    if "bitrate <= 0) return null" not in policy or "channelCount <= 0" not in policy:
        problems.append("InfoCardPolicy.kt: unknown badges must be hidden (<= 0 -> null)")
    if 'else -> "SDR"' not in policy:
        problems.append("InfoCardPolicy.kt: dynamicRange must default to SDR")

    # 8) FULLFIX §4: v3.1 info-card layout / data-source corrections.
    if re.search(r"fun smallMetaLine\([^)]*audioCodec", policy):
        problems.append("InfoCardPolicy.kt: smallMetaLine must NOT carry audioCodec (FULLFIX §4.2①)")
    if "fun smallMetaLine(number: Int, group: String?): String" not in policy:
        problems.append("InfoCardPolicy.kt: smallMetaLine(number, group) missing (FULLFIX §4.2①)")
    if "fun takePositive(" not in policy:
        problems.append("InfoCardPolicy.kt: takePositive fallback helper missing (FULLFIX §4.2②)")
    if "fun selectedChannelCount(" not in SRC.joinpath("AudioTrackUi.kt").read_text(encoding="utf-8"):
        problems.append("AudioTrackUi.kt: selectedChannelCount helper missing (FULLFIX §4.2②)")
    if "audioChannelCount: Int = -1" not in banner:
        problems.append("ChannelInfoBanner.kt: audioChannelCount param missing (FULLFIX §4.2②)")
    if "videoBitrateFallback: Int = -1" not in banner or "frameRateFallback: Float = -1f" not in banner:
        problems.append("ChannelInfoBanner.kt: bitrate/frameRate fallback params missing (FULLFIX §4.2②)")
    if "vf?.channelCount" in banner:
        problems.append("ChannelInfoBanner.kt: channel count must NOT come from video Format (FULLFIX §4.2②)")
    if "InfoCardPolicy.takePositive(" not in banner:
        problems.append("ChannelInfoBanner.kt: badge sources must use InfoCardPolicy.takePositive (FULLFIX §4.2②)")
    # DP table: container 16dp top/bottom, no double 40dp horizontal padding, fixed row heights.
    if "top = 16.dp, bottom = 16.dp" not in banner:
        problems.append("ChannelInfoBanner.kt: container padding must be 16dp (FULLFIX §4.2③)")
    if banner.count("padding(start = 40.dp") > 1:
        problems.append("ChannelInfoBanner.kt: double 40dp horizontal padding still present (FULLFIX §4.2③)")
    if ".height(64.dp)" not in banner or ".clipToBounds()" not in banner:
        problems.append("ChannelInfoBanner.kt: recent strip needs height(64)+clipToBounds (FULLFIX §4.2③/U1)")
        for p in problems:
            print(f"check_info_card: FAIL {p}")
        return 1

    print("check_info_card: OK (v3 big logo/name row/badges/tech + v2 strip + focus guard + defaults)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
