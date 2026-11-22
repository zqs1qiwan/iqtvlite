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
    # t29: per-frame incremental row reveal (no 120ms "empty then burst")
    for token in ("PerfPolicy.DRAWER_INCREMENTAL_ROWS", "revealedRows",
                  "channels.subList(0, revealedRows", "PerfPolicy.DRAWER_ROW_HEIGHT_DP", "drawer_close ms=",
                  "PerfPolicy.DRAWER_LOOKAHEAD_ROWS"):
        if token not in drawer_code:
            problems.append("PlayerDrawer.kt: missing incremental reveal token " + token)
    if "onRowFocusedScroll" not in drawer_code:
        problems.append("PlayerDrawer.kt: focus-driven scroll follow missing (t39)")
    if "const val DRAWER_LOOKAHEAD_ROWS = 16" not in policy or "const val DRAWER_SCREEN_ROWS = 12" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_LOOKAHEAD_ROWS=12 / DRAWER_SCREEN_ROWS=12 required (t56)")
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
    if "openTarget" not in drawer_code:
        problems.append("PlayerDrawer.kt: bounded open window (openTarget) missing (t42)")
    # S1: small-group drawer crash guard. The old `coerceIn(FIRST_FRAME_ROWS, channels.size)`
    # and `coerceIn(0, channelCount - 1)` throw IllegalArgumentException when lo>hi (0–2 rows / empty
    # groups). The safe open-target/first-rows/first-visible helpers in PerfPolicy must be used,
    # and the raw lo>hi patterns must be gone.
    if "PerfPolicy.drawerSafeOpenTarget(" not in drawer_code:
        problems.append("PlayerDrawer.kt: S1 open target must go through PerfPolicy.drawerSafeOpenTarget")
    if "PerfPolicy.drawerFirstRows(" not in drawer_code:
        problems.append("PlayerDrawer.kt: S1 first rows must go through PerfPolicy.drawerFirstRows")
    if "PerfPolicy.drawerSafeFirstVisibleIndex(" not in drawer_code:
        problems.append("PlayerDrawer.kt: S1 first-visible clamp must go through PerfPolicy.drawerSafeFirstVisibleIndex")
    if re.search(r"coerceIn\(PerfPolicy\.DRAWER_FIRST_FRAME_ROWS,\s*channels\.size\)", drawer_code):
        problems.append("PlayerDrawer.kt: forbidden lo>hi coerceIn(FIRST_FRAME_ROWS, channels.size) is back (S1)")
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
    # t46: single frame-paced reveal pump (target + 2 rows/frame), no one-shot bulk growth
    for token in ("revealTarget", "snapshotFlow { revealTarget }", "drawer_row_subtitle",
                  "row_focus idx=", "via=key"):
        if token not in drawer_code:
            problems.append("PlayerDrawer.kt: missing t46 token " + token)
    if "revealedRows = next" in drawer_code:
        problems.append("PlayerDrawer.kt: one-shot reveal growth is back (t46 requires frame-paced pump)")
    if "drawer_reveal_grow" not in drawer_code:
        problems.append("PlayerDrawer.kt: scroll-driven reveal grow missing (t39)")
    if "drawer_compose frames=" not in drawer_code:
        problems.append("PlayerDrawer.kt: missing drawer_compose probe")
    if "const val DRAWER_ROWS_PER_FRAME = 2" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_ROWS_PER_FRAME must be 1 (t56)")
    if "const val DRAWER_FIRST_FRAME_ROWS = 3" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_FIRST_FRAME_ROWS must be 2 (t56)")
    if "const val DRAWER_ROW_HEIGHT_DP = 64" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_ROW_HEIGHT_DP must be 64 (FULLFIX §1)")
    # FULLFIX §2: initial scroll index must consult the remembered browse focus (not only playing URL).
    if "remember(channelIndexByUrl, selectedChannelUrl, openFocusUrl)" not in drawer_code:
        problems.append("PlayerDrawer.kt: initialScrollIndex must use frozen openFocusUrl (FULLFIX §2)")
    if "remember(visible) { if (visible) initialFocusUrl else null }" not in drawer_code:
        problems.append("PlayerDrawer.kt: restore focus must be frozen at drawer open (FULLFIX §2)")
    if "PerfPolicy.drawerInitialIndex(" not in drawer_code:
        problems.append("PlayerDrawer.kt: initial index must go through PerfPolicy.drawerInitialIndex (FULLFIX §2)")
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
        for token in ("fun channelLabel(", "val PLAYING_MARK", "fun programEmptyText(",
                      "未配置 EPG 源", "EPG 加载失败", "暂无节目单"):
            if token not in up:
                problems.append("DrawerUiPolicy.kt: missing " + token)
    if "DrawerUiPolicy.channelLabel(selected" not in drawer_code:
        problems.append("PlayerDrawer.kt: selected row must render the playing marker via DrawerUiPolicy.channelLabel (B2①)")
    if "playingSuffix" not in drawer_code:
        problems.append("PlayerDrawer.kt: playing suffix (· 播放中) missing (B2①)")
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

    # t15: scroll correction decoupled from row reveal (cold-open / P8b deep-row).
    if "fun drawerScrollCorrected(" not in policy or "fun drawerScrollTargetIndex(" not in policy:
        problems.append("PerfPolicy.kt: t15 scroll-correction pure helpers missing")
    # V4 §4: scroll target must use channelCount-2 upper bound (999/147 -> 145, CI #216).
    if "coerceIn(0, channelCount - 2)" not in policy:
        problems.append("PerfPolicy.kt: drawerScrollTargetIndex must use coerceIn(0, channelCount-2) (V4 §4)")
    for token in ("pendingScrollIndex", "drawerScrollCorrected(revealedRows, pendingScrollIndex)",
                  "drawer_scroll_correct", "PerfPolicy.drawerScrollTargetIndex("):
        if token not in drawer_code:
            problems.append("PlayerDrawer.kt: t15 token missing " + token)
    # once semantics: pending must be cleared to -1 before scrolling.
    if "pendingScrollIndex = -1" not in drawer_code:
        problems.append("PlayerDrawer.kt: t15 pending once-clear (=-1) missing")
    # gating effect must depend on revealedRows.
    if "LaunchedEffect(visible, revealedRows, pendingScrollIndex)" not in drawer_code:
        problems.append("PlayerDrawer.kt: t15 correction effect must be keyed by revealedRows")
    # the old first-frame unconditional scrollToItem at open must be replaced by pending registration.
    if re.search(r"if \(targetIndex !in visibleIndices\) \{\s*runCatching \{ channelListState\.scrollToItem\(targetScroll\)", drawer_code):
        problems.append("PlayerDrawer.kt: old first-frame unconditional scrollToItem is back (t15 regression)")
    # V4 §1: list initial index fixed to 0 (no first-frame clamp flicker); logicalFocused drives highlight.
    if "initialFirstVisibleItemIndex = 0" not in drawer_code:
        problems.append("PlayerDrawer.kt: channelListState initial index must be 0 (V4 §1)")
    if "val showFocused = focused || logicalFocused" not in drawer_code:
        problems.append("PlayerDrawer.kt: logicalFocused highlight missing (V4 §1)")
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

    if problems:
        for p in problems:
            print(f"check_perf_policy: FAIL {p}")
        return 1

    print("check_perf_policy: OK (bounded marquee, capped/deferred prefetch, remembered summaries, deferred EPG, trace)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
