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
    # t42: lean channel row (logo + number + name only) and bounded open window with step=2
    # t44: subtitle only on the focused row (guarded by `focused`), heavy widgets still banned.
    if "PerfPolicy.DRAWER_FOCUSED_SUBTITLE" not in drawer_code:
        problems.append("PlayerDrawer.kt: focused-subtitle flag missing (t44)")
    if "val programTitle = if (PerfPolicy.DRAWER_FOCUSED_SUBTITLE && focused)" not in drawer_code:
        problems.append("PlayerDrawer.kt: subtitle must be gated on focused (t44)")
    if "const val DRAWER_FOCUSED_SUBTITLE = true" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_FOCUSED_SUBTITLE must default true (t44)")
    # t48: subtitle residue guard (clip + keyed subtree) and 18-row open window
    if "clipToBounds()" not in drawer_code:
        problems.append("PlayerDrawer.kt: row content must be clipped (t48 residue guard)")
    if 'key("sub_$index")' not in drawer_code:
        problems.append("PlayerDrawer.kt: subtitle must live in its own keyed node key(\"sub_$index\") (t58 residue fix)")
    if "const val DRAWER_OPEN_WINDOW_ROWS = 28" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_OPEN_WINDOW_ROWS must be 20 (t56)")
    # t58: separate cached layers (static background + content + list) to cut fixed per-frame draw cost
    if drawer_code.count("graphicsLayer()") < 3:
        problems.append("PlayerDrawer.kt: expected >=3 graphicsLayer() (background/content/list) for t58 layer caching")
    if "softWrap = false" not in drawer_code:
        problems.append("PlayerDrawer.kt: subtitle must set softWrap = false (t58 residue fix)")
    if "openTarget" not in drawer_code:
        problems.append("PlayerDrawer.kt: bounded open window (openTarget) missing (t42)")
    # the channel row must not carry the EPG subtitle / marquee / progress bar any more
    row_start = drawer_code.find("private fun DrawerChannelItem(")
    row_end = drawer_code.find("private fun ThinProgressBar(", row_start)
    if row_start != -1 and row_end > row_start:
        row = drawer_code[row_start:row_end]
        for banned in ("FocusMarqueeText(", "ThinProgressBar(", "无信息", "SubcomposeLayout"):
            if banned in row:
                problems.append("DrawerChannelItem still contains " + banned + " (t42 lean row)")
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
    if "const val DRAWER_ROW_HEIGHT_DP = 56" not in policy:
        problems.append("PerfPolicy.kt: DRAWER_ROW_HEIGHT_DP must be 56")

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
