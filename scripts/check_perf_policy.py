#!/usr/bin/env python
"""t7: static guard for the弱机 performance switches (docs/PERF_OPT_V2.md).

Guards the single-variable optimizations that must not silently regress:

  1. `PerfPolicy.MARQUEE_ITERATIONS` is a small finite number (never Int.MAX_VALUE) and the drawer
     only attaches `basicMarquee` when the row is focused AND the drawer is visible;
  2. the drawer's urgent logo prefetch is capped and deferred (`URGENT_PREFETCH_DELAY_MS`);
  3. the settings drawer computes the six root summaries inside `remember` (no per-recompose
     formatting);
  4. the EPG cache load is deferred after the first frame (`EPG_POST_FIRST_FRAME_DELAY_MS`) and runs
     inside a trace section on Dispatchers.IO;
  5. perf instrumentation exists (`PerfTrace`) and no `Int.MAX_VALUE` marquee is left anywhere.

Exit 0 = OK, 1 = violation.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/tivimini/java/com/cjlhll/iptv"
POLICY = SRC / "PerfPolicy.kt"
TRACE = SRC / "PerfTrace.kt"
DRAWER = SRC / "PlayerDrawer.kt"
SETTINGS = SRC / "SettingsDrawer.kt"
ACTIVITY = SRC / "PlayerActivity.kt"
ZAPTRACE = SRC / "ZapTrace.kt"


def strip_comments(text: str) -> str:
    """Drop // and /* */ comments so docs may name the forbidden construct."""
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
    for path in (POLICY, TRACE, DRAWER, SETTINGS, ACTIVITY, ZAPTRACE):
        if not path.exists():
            problems.append(f"missing source file: {path.name}")
    if problems:
        for p in problems:
            print(f"check_perf_policy: FAIL {p}")
        return 1

    policy = POLICY.read_text(encoding="utf-8")
    trace = TRACE.read_text(encoding="utf-8")
    drawer = DRAWER.read_text(encoding="utf-8")
    drawer_code = strip_comments(drawer)
    settings = SETTINGS.read_text(encoding="utf-8")
    activity = ACTIVITY.read_text(encoding="utf-8")
    zaptrace = ZAPTRACE.read_text(encoding="utf-8")

    # 1) marquee bounded
    m = re.search(r"const val MARQUEE_ITERATIONS = (\d+)", policy)
    if not m:
        problems.append("PerfPolicy.kt: MARQUEE_ITERATIONS must be an integer literal")
    elif not (0 <= int(m.group(1)) <= 3):
        problems.append(f"PerfPolicy.kt: MARQUEE_ITERATIONS={m.group(1)} must be 0..3 (0 = no animation)")
    if "Int.MAX_VALUE" in drawer_code:
        problems.append("PlayerDrawer.kt: Int.MAX_VALUE marquee is forbidden (100% jank root cause)")
    if "PerfPolicy.marqueeIterations" not in drawer_code:
        problems.append("PlayerDrawer.kt: marquee must go through PerfPolicy.marqueeIterations")
    if "drawerVisible = drawerVisible" not in drawer_code:
        problems.append("PlayerDrawer.kt: marquee items must receive drawerVisible")

    # 2) prefetch deferred + capped
    if "URGENT_PREFETCH_DELAY_MS" not in policy or "MAX_URGENT_PREFETCH" not in policy:
        problems.append("PerfPolicy.kt: prefetch delay/cap constants missing")
    if "PerfPolicy.urgentPrefetchIndices" not in drawer_code:
        problems.append("PlayerDrawer.kt: urgent prefetch must use PerfPolicy.urgentPrefetchIndices")
    if "delay(PerfPolicy.URGENT_PREFETCH_DELAY_MS)" not in drawer_code:
        problems.append("PlayerDrawer.kt: urgent prefetch must be deferred to after the open animation")
    if re.search(r"delay\(200\)", drawer_code):
        problems.append("PlayerDrawer.kt: the old 200ms prefetch burst is back")

    # 3) settings summaries remembered
    if not re.search(r"val rootSummaries = remember\(", settings):
        problems.append("SettingsDrawer.kt: root summaries must be computed inside remember")
    if not re.search(r"val rootEntries = remember\(rootSummaries\)", settings):
        problems.append("SettingsDrawer.kt: root entries must be derived inside remember")

    # 4) EPG cache deferred after first frame + traced
    if "EPG_POST_FIRST_FRAME_DELAY_MS" not in zaptrace:
        problems.append("ZapTrace.kt: EPG_POST_FIRST_FRAME_DELAY_MS missing")
    if "delay(ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS)" not in activity:
        problems.append("PlayerActivity.kt: EPG cache load is not deferred after the first frame")
    if "IQTV-epg-cache-load" not in activity:
        problems.append("PlayerActivity.kt: EPG cache load must be traced")

    # 5) instrumentation
    for token in ("beginSection", "endSection", "IQTV-PERF"):
        if token not in trace:
            problems.append(f"PerfTrace.kt: missing {token}")
    if "drawer_open ms=" not in drawer_code:
        problems.append("PlayerDrawer.kt: missing the 'drawer_open ms=' perf line")
    # t91: single-shot reveal (no per-frame pump) — panel still uses revealedRows/subList/rowHeight.
    for token in ("revealedRows", "channels.subList(0, revealedRows", "PerfPolicy.DRAWER_ROW_HEIGHT_DP", "drawer_close ms="):
        if token not in drawer_code:
            problems.append("PlayerDrawer.kt: missing reveal token " + token)
    if "const val DRAWER_SCREEN_ROWS = 12" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_SCREEN_ROWS=12 required (t52)")
    # FULLFIX §1: all rows (focused AND non-focused) now compose the subtitle; `focused` only
    # drives colour/progress emphasis. The master flag DRAWER_FOCUSED_SUBTITLE is still present
    # and is routed through PerfPolicy.rowSubtitleVisible (one-line rollback).
    # (FULLFIX §1) flag lives in PerfPolicy (the master switch), checked against the policy text.
    if "const val DRAWER_FOCUSED_SUBTITLE = true" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_FOCUSED_SUBTITLE must default true (t44)")
    if "PerfPolicy.rowSubtitleVisible(" not in drawer_code:
        problems.append("PlayerDrawer.kt: subtitle visibility must go through PerfPolicy.rowSubtitleVisible (FULLFIX §1)")
    if re.search(r"val programTitle = if \(PerfPolicy\.DRAWER_FOCUSED_SUBTITLE && focused\)", drawer_code):
        problems.append("PlayerDrawer.kt: old focused-only subtitle gate is back (FULLFIX §1 regression)")
    # t48: subtitle residue guard (clip + keyed subtree) and 18-row open window
    if "clipToBounds()" not in drawer_code:
        problems.append("PlayerDrawer.kt: row content must be clipped (t48 residue guard)")
    if 'key("sub_$index")' not in drawer_code:
        problems.append("PlayerDrawer.kt: subtitle must live in its own keyed node key(\"sub_$index\") (t58 residue fix)")
    if "const val DRAWER_OPEN_WINDOW_ROWS = 28" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_OPEN_WINDOW_ROWS must be 20 (t56)")
    # t58: separate cached layers (static background + content + list) to cut fixed per-frame draw cost.
    # FULLFIX §3: the background layer now has an alpha lambda, so count both `graphicsLayer()` and
    # `graphicsLayer {` forms — still exactly 3 cached layers (background/content/list).
    layer_count = drawer_code.count("graphicsLayer()") + drawer_code.count("graphicsLayer {")
    if layer_count < 3:
        problems.append("PlayerDrawer.kt: expected >=3 graphicsLayer (background/content/list) for t58 layer caching")
    if "softWrap = false" in drawer_code:
        problems.append("PlayerDrawer.kt: softWrap = false must be removed (t60 residue root fix)")
    if "TextSanitize.programTitle(" not in drawer_code:
        problems.append("PlayerDrawer.kt: subtitle text must go through TextSanitize.programTitle (t60)")
    if "sub_narrow" not in drawer_code:
        problems.append("PlayerDrawer.kt: sub_narrow probe missing (t60)")
    if re.search(r"coerceIn\(0,\s*channelCount\s*-\s*1\)", drawer_code):
        problems.append("PlayerDrawer.kt: forbidden coerceIn(0, channelCount-1) is back (S1)")
    for helper in ("fun drawerSafeOpenTarget(", "fun drawerFirstRows(", "fun drawerSafeFirstVisibleIndex("):
        if helper not in policy:
            problems.append("PerfPolicy.kt: S1 pure helper missing: " + helper)
    # Back must still close the drawer (S1 must not change the Back branch).
    if "it.key == Key.Back" not in drawer_code or "onClose()" not in drawer_code:
        problems.append("PlayerDrawer.kt: Back branch must still close the drawer (S1 regression)")
    # FULLFIX §1: rich row restored — ThinProgressBar called in the channel row, "无信息" fallback
    # present, 3dp progress helper kept. Heavy widgets (marquee / SubcomposeLayout) still banned.
    row_start = drawer_code.find("private fun DrawerChannelItem(")
    row_end = drawer_code.find("private fun ThinProgressBar(", row_start)
    if row_start != -1 and row_end > row_start:
        row = drawer_code[row_start:row_end]
        for banned in ("FocusMarqueeText(", "SubcomposeLayout"):
            if banned in row:
                problems.append("DrawerChannelItem still contains " + banned + " (FULLFIX §1)")
        for required in ("ThinProgressBar(", "无信息", 'key("sub_$index")'):
            if required not in row:
                problems.append("DrawerChannelItem missing rich-row token " + required + " (FULLFIX §1)")
    if ".height(3.dp)" not in drawer_code:
        problems.append("PlayerDrawer.kt: ThinProgressBar must be 3dp (FULLFIX §1)")
    # row focus log + subtitle tag still required (t91 keeps rich rows / log).
    for token in ("drawer_row_subtitle", "row_focus idx=", "via=key"):
        if token not in drawer_code:
            problems.append("PlayerDrawer.kt: missing row token " + token)
    if "const val DRAWER_ROW_HEIGHT_DP = 64" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_ROW_HEIGHT_DP must be 64 (FULLFIX §1)")
    # t49 §③：恢复焦点冻结为 openAnchor（visible 翻转时快照），换台 selectedChannelUrl 不再参与 key。
    if "val openAnchor = remember(visible)" not in drawer_code:
        problems.append("PlayerDrawer.kt: openAnchor must freeze at drawer open via remember(visible) (t49)")
    if "initialFocusUrl: String? = null" not in drawer_code:
        problems.append("PlayerDrawer.kt: missing initialFocusUrl parameter (FULLFIX §2)")
    if "fun drawerInitialIndex(" not in policy:
        problems.append("PerfPolicy.kt: drawerInitialIndex pure helper missing (FULLFIX §2)")
    if "lastFocusedByGroup" not in (SRC / "SidebarController.kt").read_text(encoding="utf-8"):
        problems.append("SidebarController.kt: per-group focus memory missing (FULLFIX §2)")
    # FULLFIX §3: translucent background must be applied as a graphicsLayer alpha on the cached
    # static layer (not surface.copy(alpha=...)), and the container paint stays opaque.
    if "surface.copy(alpha = 0.92f)" in drawer_code:
        problems.append("PlayerDrawer.kt: container must not use surface.copy(alpha=0.92f) (FULLFIX §3 perf)")
    if "PerfPolicy.drawerContainerAlpha(" not in drawer_code:
        problems.append("PlayerDrawer.kt: background layer must use PerfPolicy.drawerContainerAlpha (FULLFIX §3)")
    if "alpha = PerfPolicy.drawerContainerAlpha(" not in drawer_code:
        problems.append("PlayerDrawer.kt: graphicsLayer alpha assignment missing (FULLFIX §3)")
    if "const val DRAWER_CONTAINER_ALPHA = 0.92f" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_CONTAINER_ALPHA=0.92f missing (FULLFIX §3)")
    if "fun drawerContainerAlpha(" not in policy:
        problems.append("PerfPolicy.kt: drawerContainerAlpha helper missing (FULLFIX §3)")

    # 复审 B2（U5）：播放台 ▶ 标记 / 信息卡不污染 / 节目单空态三分类。
    ui_policy = SRC / "DrawerUiPolicy.kt"
    if not ui_policy.exists():
        problems.append("DrawerUiPolicy.kt missing (review B2)")
    else:
        up = ui_policy.read_text(encoding="utf-8")
        for token in ("fun channelLabel(", "fun programEmptyText(", "fun playingNameColor(",
                      "未配置 EPG 源", "EPG 加载失败", "暂无节目单"):
            if token not in up:
                problems.append("DrawerUiPolicy.kt: missing " + token)
    # V4 §5: playing marker is a blue color (not ▶ text); channelLabel(idx,title) 2-arg.
    if "DrawerUiPolicy.channelLabel(index" not in drawer_code:
        problems.append("PlayerDrawer.kt: label must use DrawerUiPolicy.channelLabel(index,title) (V4 §5)")
    if "DrawerUiPolicy.playingNameColor(" not in drawer_code:
        problems.append("PlayerDrawer.kt: playing row must use DrawerUiPolicy.playingNameColor (V4 §5)")
    if "DrawerUiPolicy.programEmptyText(" not in drawer_code:
        problems.append("PlayerDrawer.kt: EPG empty state must use DrawerUiPolicy.programEmptyText (B2③)")
    if "epgSourceConfigured: Boolean" not in drawer_code or "epgLoadFailed: Boolean" not in drawer_code:
        problems.append("PlayerDrawer.kt: EPG three-state params missing (B2③)")
    # B2②: nowProgramByUrl must NOT be filtered by selectedGroup.
    activity_code = strip_comments(ACTIVITY.read_text(encoding="utf-8"))
    b2 = re.search(r"LaunchedEffect\(epgData, nowMillis, channelGroups, selectedGroup\)", activity_code)
    if b2:
        problems.append("PlayerActivity.kt: nowProgramByUrl must not filter by selectedGroup (B2②)")
    if "FULLFIX B2②" not in ACTIVITY.read_text(encoding="utf-8"):
        problems.append("PlayerActivity.kt: B2② info-card independence note missing")

    if "DRAWER_PROGRAM_AREA_ALPHA" not in policy:
        problems.append("PerfPolicy.kt: t72 program-area transparent alpha token missing")
    # t75: fixed center focus highlight overlay + drawer auto-close.
    if "DRAWER_CENTER_HIGHLIGHT" not in policy or "DRAWER_CENTER_HIGHLIGHT" not in drawer_code:
        problems.append("t75: fixed center focus highlight overlay missing")
    if "drawer_center_focus_highlight" not in drawer_code:
        problems.append("PlayerDrawer.kt: t75 center highlight testTag missing")
    if "suppressFocusBg" not in drawer_code:
        problems.append("PlayerDrawer.kt: t75 per-row focus bg must be suppressed (center overlay only)")
    if "DRAWER_AUTO_CLOSE_MS" not in policy or "shouldAutoCloseDrawer(" not in policy:
        problems.append("PerfPolicy.kt: t75 drawer auto-close policy missing")
    if "shouldAutoCloseDrawer(" not in activity_code:
        problems.append("PlayerActivity.kt: t75 drawer auto-close timer not wired")
    # t69 ④：滚动执行解耦后用 drawerMonotonicStep 从当前实际位置重算（原 drawerScrollDelta 调用已替换）。
    if "fun drawerOpenPositionAnchor(" not in policy:
        problems.append("PerfPolicy.kt: t69 playing-channel-first anchor policy missing")
    if "PerfPolicy.drawerOpenPositionAnchor(" not in drawer_code:
        problems.append("PlayerDrawer.kt: t69 open positioning must use drawerOpenPositionAnchor")
    # t91 架构重构：首帧直接定位 + 唯一瞬时 scrollToItem effect，无滚动 worker/补丁链。
    t91_banned = ("animateScrollBy", "scrollIntentFlow", "convergeFocusToCenter", "scrollInProgress",
                  "drawerScrollDecision", "drawerMonotonicStep", "needsSteadyCorrection",
                  "desiredScrollIndex", "desiredScrollInstant", "hasInitializedDrawerFocus",
                  "revealTarget", "liveFocusIndex", "isRapidFocus", "drawerScrollTargetIndex",
                  "postScrollIntent", "ScrollIntent")
    for sym in t91_banned:
        if sym in drawer_code:
            problems.append("PlayerDrawer.kt: t91 patch-chain symbol must be removed: " + sym)
    t91_policy_banned = ("drawerScrollDecision", "drawerMonotonicStep", "needsSteadyCorrection",
                         "isEdgeFocus", "drawerScrollTargetIndex", "drawerScrollDelta", "drawerScrollStep",
                         "shouldArmInstantLocate", "isRapidFocus", "isLongPressScroll", "ScrollDecision",
                         "drawerScrollCorrected", "drawerRevealTarget")
    for sym in t91_policy_banned:
        if re.search(r'\b(fun|const val|enum class|val)\s+' + re.escape(sym) + r'\b', policy):
            problems.append("PerfPolicy.kt: t91 patch-chain declaration must be removed: " + sym)
    if "firstVisibleForFocus(" not in drawer_code or "initialFirstVisibleItemIndex = PerfPolicy.firstVisibleForFocus" not in drawer_code:
        problems.append("PlayerDrawer.kt: t91 first-frame must use firstVisibleForFocus as initial index")
    if "fun firstVisibleForFocus(" not in policy or "fun initialRevealRows(" not in policy:
        problems.append("PerfPolicy.kt: t91 firstVisibleForFocus/initialRevealRows pure helpers missing")
    scroll_to_count = drawer_code.count("channelListState.scrollToItem(")
    if scroll_to_count != 1:
        problems.append("t91: exactly one channelListState.scrollToItem (focus effect) expected, found %d" % scroll_to_count)
    if "DRAWER_AUTO_CLOSE_MS = 7_000L" not in policy:
        problems.append("PerfPolicy.kt: t91 auto-close must be 7s product value")
    if "val showFocused = focused || logicalFocused" not in drawer_code:
        problems.append("PlayerDrawer.kt: logicalFocused highlight missing (V4 §1)")
    # t32 Bug4: EPG column narrowed ~30% + focus-only bounded marquee.
    if "const val DRAWER_PROGRAM_COLUMN_DP = 210" not in policy or "const val DRAWER_PROGRAM_TIME_COLUMN_DP = 68" not in policy:
        problems.append("PerfPolicy.kt: t32 EPG column width constants missing")
    if "PerfPolicy.DRAWER_PROGRAM_COLUMN_DP.dp" not in drawer_code:
        problems.append("PlayerDrawer.kt: program column must use DRAWER_PROGRAM_COLUMN_DP (t32 Bug4)")
    if "fun epgMarqueeIterations(" not in policy or "const val EPG_MARQUEE_ITERATIONS = 2" not in policy:
        problems.append("PerfPolicy.kt: t32 epgMarqueeIterations / bounded constant missing")
    if "epgMarqueeIterations(focused, drawerVisible)" not in drawer_code:
        problems.append("PlayerDrawer.kt: EPG rows must use bounded focus marquee (t32 Bug4)")
    if "Int.MAX_VALUE" in drawer_code:
        problems.append("PlayerDrawer.kt: Int.MAX_VALUE marquee forbidden (t32 Bug4)")
    # t38: progress bar blue only for selected/playing channel; EPG cell padding tightened.
    if "selected: Boolean," not in drawer_code or "selected = selected," not in drawer_code:
        problems.append("PlayerDrawer.kt: ThinProgressBar must take selected flag (t38)")
    if not re.search(r"onSurface\.copy\(alpha = 0\.5f\)", drawer_code):
        problems.append("PlayerDrawer.kt: non-selected progress fill must be gray (t38)")
    if ".padding(horizontal = 4.dp, vertical = 10.dp)" not in drawer_code:
        problems.append("PlayerDrawer.kt: EPG cell horizontal padding must be tightened to 4dp (t38)")
    # t41：焦点底灰白（FocusBgColor），蓝色只用于选中台文字/当前节目。
    if "val FocusBgColor" not in (SRC / "BrandFocus.kt").read_text(encoding="utf-8"):
        problems.append("BrandFocus.kt: FocusBgColor (gray-white) token missing (t41)")
    if "BrandFocus.BgColor" in drawer_code:
        problems.append("PlayerDrawer.kt: focus background must not use blue BgColor (t41)")
    if drawer_code.count("BrandFocus.FocusBgColor") < 4:
        problems.append("PlayerDrawer.kt: channel/group/program/date focus must all use FocusBgColor (t41)")
    if "BrandFocus.BlueColor" not in drawer_code or "playingNameColor(selected)" not in drawer_code:
        problems.append("PlayerDrawer.kt: selected channel name must stay blue (t41)")
    # t49 §③: zap must not re-enter open init (openAnchor frozen, no selectedChannelUrl key, revealTarget only raises).
    if "val openAnchor = remember(visible)" not in drawer_code:
        problems.append("PlayerDrawer.kt: openAnchor must freeze at open via remember(visible) (t49)")
    if "LaunchedEffect(openAnchor, visible)" not in drawer_code:
        problems.append("PlayerDrawer.kt: focus-init effect must key on openAnchor not selectedChannelUrl (t49)")
    # t72：节目单区域无分割线（无 divider=1.dp、无 width(1.dp)+onSurface.copy(0.18) 竖线）+ 透明背景。
    if "val divider = 1.dp" in drawer_code:
        problems.append("PlayerDrawer.kt: 1dp divider in width calc must be removed (t72)")
    if re.search(r"width\(1\.dp\)[\s\S]{0,80}onSurface\.copy\(alpha = 0\.18f\)", drawer_code):
        problems.append("PlayerDrawer.kt: vertical separator Spacer must be removed (t72)")
    if "PerfPolicy.DRAWER_PROGRAM_AREA_ALPHA" not in drawer_code:
        problems.append("PlayerDrawer.kt: program-area transparent background (graphicsLayer alpha) missing (t72)")
    # V4 §2: group focus selects group; RIGHT(Groups) collapses + focuses first channel;
    # selectGroup is idempotent with resetFocus; no `if (!showGroups) return` in selectedGroup effect.
    sidebar = (SRC / "SidebarController.kt").read_text(encoding="utf-8")
    if "fun selectGroup(name: String, resetFocus: Boolean = false)" not in sidebar:
        problems.append("SidebarController.kt: idempotent selectGroup(resetFocus) missing (V4 §2)")
    if "if (g != selectedGroup) onSelectGroupReset(g)" not in drawer_code:
        problems.append("PlayerDrawer.kt: group onFocus must call onSelectGroupReset (V4 §2)")
    right_groups = re.search(r"DirectionRight && activeColumn == DrawerColumn\.Groups.*?showGroups = false.*?pendingFocusToChannels = true", drawer_code, re.S)
    if not right_groups:
        problems.append("PlayerDrawer.kt: RIGHT(Groups) must collapse showGroups + focus channels (V4 §2)")
    if "onSelectGroupReset = { sidebarController.selectGroup(it, resetFocus = true) }" not in activity_code:
        problems.append("PlayerActivity.kt: onSelectGroupReset wiring missing (V4 §2)")
    sel_group_block = re.search(r"LaunchedEffect\(selectedGroup\) \{(.*?)\n    \}", drawer_code, re.S)
    if sel_group_block and "if (!showGroups) return@LaunchedEffect" in sel_group_block.group(1):
        problems.append("PlayerDrawer.kt: selectedGroup effect still early-returns when groups collapsed (V4 §2)")
    if "remember(channelGroups, selectedGroup)" not in activity_code:
        problems.append("PlayerActivity.kt: filteredChannels cache (remember) must be preserved (V4 §2)")
    # V4 §5: playing channel uses blue color, no ▶/播放中 text.
    if "PLAYING_MARK" in (SRC / "DrawerUiPolicy.kt").read_text(encoding="utf-8"):
        problems.append("DrawerUiPolicy.kt: PLAYING_MARK must be removed (V4 §5)")
    if "▶" in drawer_code or "播放中" in drawer_code:
        problems.append("PlayerDrawer.kt: no ▶/播放中 text (V4 §5 blue-color marker)")
    if "playingNameColor(" not in drawer_code or "BrandFocus.BlueColor" not in drawer_code:
        problems.append("PlayerDrawer.kt: playing row must use BrandFocus blue (V4 §5/§9)")
    # V4 §9: BrandFocus token exists and focus points use it (no hard border).
    brand = SRC / "BrandFocus.kt"
    if not brand.exists() or "0xFF2F6BFF" not in brand.read_text(encoding="utf-8"):
        problems.append("BrandFocus.kt: #2F6BFF brand-blue token missing (V4 §9)")
    if "surfaceVariant" in drawer_code and "focused -> MaterialTheme.colorScheme.surfaceVariant" in drawer_code:
        problems.append("PlayerDrawer.kt: raw surfaceVariant focus point remains (V4 §9 use BrandFocus)")

    if "if (showContent)" not in drawer_code:
        problems.append("PlayerDrawer.kt: drawer list content must be gated by showContent (do not compose when closed)")
    if "PerfPolicy.DRAWER_OPAQUE_CONTAINER" not in drawer_code:
        problems.append("PlayerDrawer.kt: drawer container must honour PerfPolicy.DRAWER_OPAQUE_CONTAINER")
    # t27/t28: cold-start experiments must be runtime switches (Settings.Global), defaults unchanged.
    tuning = (SRC / "PerfTuning.kt")
    if not tuning.exists():
        problems.append("PerfTuning.kt: runtime experiment switches missing")
    else:
        tt = tuning.read_text(encoding="utf-8")
        for key in ("iqtv_prebuffer_ms", "iqtv_after_rebuffer_ms", "iqtv_max_buffer_ms", "iqtv_min_buffer_ms"):
            if key not in tt:
                problems.append("PerfTuning.kt: missing settings key " + key)
        for token in ("ColdStartPolicy.PREBUFFER_BEFORE_PLAY_MS", "LoadControlTuning.MIN_BUFFER_MS",
                      "LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS", "LoadControlTuning.MAX_BUFFER_MS"):
            if token not in tt:
                problems.append("PerfTuning.kt: default must come from " + token)
        if "minOf(after, minBufferMs(context))" not in tt:
            problems.append("PerfTuning.kt: afterRebuffer must be clamped by minBuffer (CI10077 invariant)")
    if "PerfTuning.prebufferMs(context)" not in (SRC / "StreamPlayback.kt").read_text(encoding="utf-8"):
        problems.append("StreamPlayback.kt: prebuffer must go through PerfTuning")
    act = (SRC / "PlayerActivity.kt").read_text(encoding="utf-8")
    for token in ("PerfTuning.minBufferMs(context)", "PerfTuning.maxBufferMs(context)", "PerfTuning.afterRebufferMs(context)"):
        if token not in act:
            problems.append("PlayerActivity.kt: LoadControl must use " + token)

    if "JingcaiTsDataSource(" not in (SRC / "StreamPlayback.kt").read_text(encoding="utf-8"):
        problems.append("StreamPlayback/PlayerActivity: real DataSource byte counting must be installed")

    # t91: LOOKAHEAD constant retained (only used by prefetch window sizing now).
    if "const val DRAWER_LOOKAHEAD_ROWS = 8" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_LOOKAHEAD_ROWS must be 8 (t52)")
    if problems:
        for p in problems:
            print(f"check_perf_policy: FAIL {p}")
        return 1

    print("check_perf_policy: OK (bounded marquee, capped/deferred prefetch, remembered summaries, deferred EPG, trace)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
