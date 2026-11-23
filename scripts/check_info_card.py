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
    # t32 Bug2：info_card_tech 已并入右上角徽标，不再有独立技术行。
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
    if not re.search(r"const val MAX_RECENT = 4\b", policy):
        problems.append("InfoCardPolicy.kt: MAX_RECENT must be 4 (V4 §6, was 6)")
    if not re.search(r"const val AUTO_HIDE_MS = 7000L", policy):
        problems.append("InfoCardPolicy.kt: AUTO_HIDE_MS must be 7000L (t32 Bug3)")
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
    # t49：F1 BACK fallback 已由 BackKeyPolicy.Action.INFO_CARD_CLOSE 分层消费（不再有裸 setInfoBannerOpen+return）。
    if "BackKeyPolicy.Action.INFO_CARD_CLOSE -> setInfoBannerOpen?.invoke(false)" not in activity:
        problems.append("PlayerActivity.kt: F1 BACK close-card via BackKeyPolicy missing (t49)")
    # The mirror must no longer be derived from the state enum inside the SideEffect.
    if re.search(r"onInfoCardFocusedChanged\(infoCardState == InfoCardPolicy\.State\.FOCUSED\)", activity):
        problems.append("PlayerActivity.kt: F1 mirror must not be derived from state (node focus only)")

    # 7) t24 (INFO_CARD_V3): big logo / same-row name / right program / badges / tech line / fixed heights / single line.
    if "160.dp" not in banner or "96.dp" not in banner:
        problems.append("ChannelInfoBanner.kt: logo must be >=160x96dp (v3)")
    if "info_card_logo" not in banner:
        problems.append("ChannelInfoBanner.kt: info_card_logo tag missing")
    if "headlineLarge.copy" not in banner:
        problems.append("ChannelInfoBanner.kt: channel name must use headlineLarge.copy (v4 titleWithNumber)")
    # t32 Bug2：tech 行已并入右上角徽标，旧的独立 info_card_tech tag 不再要求。
    for tag in ("info_card_badges", "info_card_program_time", "info_card_number", "info_card_group"):
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
    # t32 Bug2：tech 行移除后单行文本数下降，阈值放宽到 6。
    if banner.count("maxLines = 1") < 6:
        problems.append("ChannelInfoBanner.kt: v4 needs >=6 single-line texts")
    if "TextOverflow.Ellipsis" not in banner:
        problems.append("ChannelInfoBanner.kt: TextOverflow.Ellipsis missing")
    for bad in ("basicMarquee", "LazyRow", "animateContentSize", "wrapContentHeight"):
        if bad in banner:
            problems.append("ChannelInfoBanner.kt: forbidden v3 token " + bad)
    # t32 Bug2：徽标不再单独 24dp 行，固定高度只要求主行 96dp。
    for h in (".height(96.dp)",):
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
    # t52 §⑥：布局进一步压紧，容器 top/bottom 收到 6dp。
    if "top = 6.dp, bottom = 6.dp" not in banner:
        problems.append("ChannelInfoBanner.kt: container padding must be 6/6dp (t52 §⑥ compact)")
    if banner.count("padding(start = 40.dp") > 1:
        problems.append("ChannelInfoBanner.kt: double 40dp horizontal padding still present (FULLFIX §4.2③)")
    if ".clipToBounds()" not in banner:
        problems.append("ChannelInfoBanner.kt: recent strip needs clipToBounds (FULLFIX §4.2③/U1)")

    # 8b) V4 §6: info card v4 — 3 badges (no bitrate), groupLine, 4 logo-only 56dp cards, ≤240dp.
    if "fun groupLine(" not in policy:
        problems.append("InfoCardPolicy.kt: groupLine missing (V4 §6)")
    if "groupLine(groupLabel)" not in banner:
        problems.append("ChannelInfoBanner.kt: subtitle must use groupLine (V4 §6)")
    if 'BadgeCandidate("bitrate"' in policy:
        problems.append("InfoCardPolicy.kt: bitrate must not be a badge candidate (V4 §6)")
    # t32 Bug2：徽标改为 compactBadges 全信息一排（不占行），旧 maxWidth 180 布局已移除。
    if "compactBadges(" not in banner:
        problems.append("ChannelInfoBanner.kt: must use compactBadges (t32 Bug2, replaces maxWidth layout)")
    if ".size(40.dp)" not in banner or "36.dp" not in banner:
        problems.append("ChannelInfoBanner.kt: recent cards must be 40dp inline logo-only with 36dp logo (t52 §⑥)")

    # 9) FULLFIX §5 (U1): highlight = real focus = OK target, one source of truth (focusIndex).
    if "focusTargetIndex(" not in policy or "resolveRecentTarget(" not in policy:
        problems.append("InfoCardPolicy.kt: U1 pure helpers focusTargetIndex/resolveRecentTarget missing")
    # 忽略注释：U1 守卫注释里会提到旧的 `if (stripFocusIndex < 0)`，代码本身不得再有。
    banner_code = re.sub(r"//[^\n]*", "", banner)
    if re.search(r"if \(stripFocusIndex < 0\)", banner_code):
        problems.append("ChannelInfoBanner.kt: the stripFocusIndex<0 guard is back (U1 focus not following highlight)")
    if "InfoCardPolicy.focusTargetIndex(focusIndex, recentCount)" not in banner:
        problems.append("ChannelInfoBanner.kt: REQUEST_FOCUS must target focusIndex (U1)")
    if "stripFocusIndex = target" not in banner:
        problems.append("ChannelInfoBanner.kt: stripFocusIndex must be synced before request (U1)")
    # Root onPreviewKeyEvent must own OK and pass focusIndex (not the event-source card index).
    root_ok = re.search(r"Key\.DirectionCenter, Key\.Enter, Key\.NumPadEnter -> \{[^}]*onSwitchRecent\(", banner)
    if not root_ok:
        problems.append("ChannelInfoBanner.kt: root onPreviewKeyEvent must handle OK (U1)")
    if "onSwitchRecent(index)" in banner:
        problems.append("ChannelInfoBanner.kt: card-level onSwitchRecent(index) is back (U1 wrong OK target)")
    if "InfoCardPolicy.resolveRecentTarget(cardRecentItems, infoCardFocusIndex)" not in activity:
        problems.append("PlayerActivity.kt: onSwitchRecent must resolve target via infoCardFocusIndex (U1)")
    # Card must not have its own key path any more (single key path at the banner root).
    recent_start = banner.find("private fun RecentStrip(")
    if recent_start != -1 and ".onPreviewKeyEvent" in banner[recent_start:]:
        problems.append("ChannelInfoBanner.kt: RecentStrip still has its own onPreviewKeyEvent (U1 dual key path)")

    # 10) t32: OK mode setting, compact badge overlay (no dedicated row), channel-number audio,
    #     full badge info, 7s auto-hide.
    for token in ("enum class InfoOkMode", "fun parseInfoOkMode", "fun openCardFocusesDirectly"):
        if token not in policy:
            problems.append("InfoCardPolicy.kt: t32 Bug1 OK-mode helper missing " + token)
    if "openInfoCardFromOk?.invoke()" not in activity or "infoOkMode" not in activity:
        problems.append("PlayerActivity.kt: OK branch must call openInfoCardFromOk (t32 Bug1)")
    if "settings_info_ok_mode" not in SRC.joinpath("SettingsDrawer.kt").read_text(encoding="utf-8"):
        problems.append("SettingsDrawer.kt: settings_info_ok_mode row missing (t32 Bug1)")
    for token in ("fun channelNumberLabel(", "fun videoCodecShort(", "fun compactBadges("):
        if token not in policy:
            problems.append("InfoCardPolicy.kt: t32 Bug2 helper missing " + token)
    if "compactBadgeText" not in banner or "compactBadges(" not in banner:
        problems.append("ChannelInfoBanner.kt: must use compactBadges (t32 Bug2)")
    if ".align(Alignment.TopEnd)" not in banner:
        problems.append("ChannelInfoBanner.kt: badges must overlay top-end, not a dedicated row (t32 Bug2)")
    # 旧的独立徽标行 + 技术行必须移除（不额外占行）。
    if re.search(r"height\(24\.dp\)[\s\S]{0,120}info_card_badges", banner):
        problems.append("ChannelInfoBanner.kt: dedicated 24dp badge row still present (t32 Bug2)")
    if 'testTag("info_card_tech")' in banner:
        problems.append("ChannelInfoBanner.kt: old tech row must be removed (info moved to top-right, t32 Bug2)")

    # 11) t35: FOCUSED keys deterministically consumed, never fall through to Activity when.
    for token in ("enum class FocusedKeyAction", "fun focusedKeyAction("):
        if token not in policy:
            problems.append("InfoCardPolicy.kt: t35 focusedKeyAction helper missing " + token)
    if "infoCardFocusedAction?.invoke(action)" not in activity:
        problems.append("PlayerActivity.kt: FOCUSED fallback must invoke deterministic controller (t35)")
    # 关键回归：FOCUSED 分支必须总是 return true，不得"继续 when"（旧 bug 的根源）。
    focused_block = re.search(r"if \(isInfoCardFocused && infoCardKey\) \{(.*?)\n            \}", activity, re.S)
    if focused_block:
        body = focused_block.group(1)
        if "return true" not in body:
            problems.append("PlayerActivity.kt: FOCUSED branch must consume key (return true) - t35")
        if "super.dispatchKeyEvent(event)" not in body:
            problems.append("PlayerActivity.kt: FOCUSED branch must try Compose first - t35")
    else:
        problems.append("PlayerActivity.kt: FOCUSED key guard block missing (t35)")
    if "setInfoCardFocusedActionController" not in activity:
        problems.append("PlayerActivity.kt: t35 focused action controller registration missing")

    # 12) t38: fps fallback 25, info-card program text not blue.
    if "FALLBACK_FRAME_RATE = 25f" not in policy:
        problems.append("InfoCardPolicy.kt: t38 FALLBACK_FRAME_RATE=25f missing")
    if "if (frameRate.isNaN() || frameRate <= 0f) FALLBACK_FRAME_RATE" not in policy:
        problems.append("InfoCardPolicy.kt: frameRateLabel must fall back to 25fps (t38)")
    # 信息卡 programText has-EPG 分支不得用 primary（蓝色仅用于选台聚焦）。
    program_color_block = re.search(r"text = programText,[\s\S]{0,260}?modifier = Modifier\.testTag\(\"info_card_program\"\)", banner)
    if program_color_block and "colorScheme.primary" in program_color_block.group(0):
        problems.append("ChannelInfoBanner.kt: info-card programText must not use primary blue (t38)")

    # 13) t40: real fps inference (render frame interval) replacing pure 25 fallback.
    est_path = ROOT.joinpath("android/app/src/tivimini/java/com/cjlhll/iptv/FrameRateEstimator.kt")
    est_text = est_path.read_text(encoding="utf-8") if est_path.exists() else ""
    for token in ("fun fpsFromIntervals(", "fun resolve(", "enum class Source"):
        if token not in est_text:
            problems.append("FrameRateEstimator.kt: t40 fps inference pure helper missing " + token)
    stream = ROOT.joinpath("android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt").read_text(encoding="utf-8")
    # Media3 1.11.1 has no onVideoFrameRendered; the real per-frame callback is onVideoFrameProcessingOffset.
    if "onVideoFrameProcessingOffset" not in stream:
        problems.append("StreamPlayback.kt: must measure real fps via onVideoFrameProcessingOffset (t40)")
    if "FrameRateEstimator.FrameRateWindow" not in stream:
        problems.append("StreamPlayback.kt: must use FrameRateEstimator.FrameRateWindow for fps (t40)")
    if "frameRateFallback = fpsResolution.value" not in activity:
        problems.append("PlayerActivity.kt: inferred fps must reach banner frameRateFallback (t40)")
    if "source=${fpsResolution.source.name.lowercase()}" not in activity:
        problems.append("PlayerActivity.kt: t40 DEBUG fps source log missing")

    # 14) t52: compact info card, FOCUSED 7s auto-hide, reliable settings entry.
    if "fun infoCardHeightV4(" not in policy or "INFO_CARD_COMPACT_HEIGHT_DP" not in policy:
        problems.append("InfoCardPolicy.kt: t52 infoCardHeightV4 helper missing")
    if "Spacer(modifier = Modifier.height(24.dp))" in banner:
        problems.append("ChannelInfoBanner.kt: separate 24dp recent spacer must be removed (t52 §⑥)")
    if "State.FOCUSED -> FOCUSED_IDLE_HIDE_MS" not in policy:
        problems.append("InfoCardPolicy.kt: FOCUSED must auto-hide after 7s (t52 §⑦)")
    if "fun shouldArmAutoHide(" not in policy:
        problems.append("InfoCardPolicy.kt: shouldArmAutoHide pure helper missing (t52 §⑦)")
    if "infoCardLastInteractionMs" not in activity:
        problems.append("PlayerActivity.kt: single-source interaction timestamp missing (t52 §⑦)")
    fp = SRC / "FocusedKeyPolicy.kt"
    if not fp.exists() or "fun passthrough(" not in fp.read_text(encoding="utf-8"):
        problems.append("FocusedKeyPolicy.kt: passthrough pure policy missing (t52 §⑧)")
    if "KEY_BUTTON_1" not in (SRC / "RemoteBindings.kt").read_text(encoding="utf-8"):
        problems.append("RemoteBindings.kt: color-key settings entry missing (t52 §⑧)")
    if "FocusedKeyPolicy.passthrough" not in activity:
        problems.append("PlayerActivity.kt: FOCUSED guard must use FocusedKeyPolicy.passthrough (t52 §⑧)")

    if problems:
        for p in problems:
            print(f"check_info_card: FAIL {p}")
        return 1

    print("check_info_card: OK (v3 big logo/name row/badges/tech + v2 strip + focus guard + defaults)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
