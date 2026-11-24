#!/usr/bin/env python
"""Static guard for performance, weak-device rendering, and true center-locked drawer invariants."""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/tivimini/java/com/cjlhll/iptv"
POLICY = SRC / "PerfPolicy.kt"
TRACE = SRC / "PerfTrace.kt"
DRAWER = SRC / "PlayerDrawer.kt"
NATIVE_DRAWER = SRC / "NativeChannelDrawerView.kt"
SETTINGS = SRC / "SettingsDrawer.kt"
ACTIVITY = SRC / "PlayerActivity.kt"
ZAPTRACE = SRC / "ZapTrace.kt"
EPGSQL = SRC / "EpgSqlStore.kt"


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
    for path in (POLICY, TRACE, DRAWER, NATIVE_DRAWER, SETTINGS, ACTIVITY, ZAPTRACE, EPGSQL):
        if not path.exists():
            problems.append(f"missing source file: {path.name}")
    if problems:
        for p in problems:
            print(f"check_perf_policy: FAIL {p}")
        return 1

    policy = POLICY.read_text(encoding="utf-8")
    drawer_code = strip_comments(DRAWER.read_text(encoding="utf-8"))
    native_drawer_code = strip_comments(NATIVE_DRAWER.read_text(encoding="utf-8"))
    settings = SETTINGS.read_text(encoding="utf-8")
    activity_code = strip_comments(ACTIVITY.read_text(encoding="utf-8"))

    # 1) Bounded marquee (never Int.MAX_VALUE)
    m = re.search(r"const val MARQUEE_ITERATIONS = (\d+)", policy)
    if not m or not (0 <= int(m.group(1)) <= 3):
        problems.append("PerfPolicy.kt: MARQUEE_ITERATIONS must be 0..3")
    if "Int.MAX_VALUE" in drawer_code or "Int.MAX_VALUE" in native_drawer_code:
        problems.append("Drawer: Int.MAX_VALUE marquee is forbidden")

    # 2) Native 60fps RecyclerView center-locked drawer invariants
    if "NativeChannelDrawerView(" not in activity_code:
        problems.append("PlayerActivity.kt: must mount NativeChannelDrawerView on rootLayout")
    if "RecyclerView(context)" not in native_drawer_code:
        problems.append("NativeChannelDrawerView.kt: must use native RecyclerView for 60fps weak-device scrolling")
    if "itemAnimator = null" not in native_drawer_code:
        problems.append("NativeChannelDrawerView.kt: must disable itemAnimator for zero-jank focus updates")
    if "scrollToPositionWithOffset" not in native_drawer_code or "boundedCenterOffset" not in native_drawer_code:
        problems.append("NativeChannelDrawerView.kt: must use boundedCenterOffset + scrollToPositionWithOffset for natural bounded center scroll")
    if "ZoneId.systemDefault()" not in native_drawer_code:
        problems.append("NativeChannelDrawerView.kt: must format EPG times/dates in ZoneId.systemDefault()")
    if "channels.subList(0, revealedRows" in drawer_code:
        problems.append("PlayerDrawer.kt: old subList truncation cliff must stay removed")

    # 3) Prefetch deferred & capped; settings summaries remembered
    if "delay(PerfPolicy.URGENT_PREFETCH_DELAY_MS)" not in native_drawer_code:
        problems.append("NativeChannelDrawerView.kt: urgent logo prefetch must be deferred")
    if not re.search(r"val rootSummaries = remember\(", settings):
        problems.append("SettingsDrawer.kt: rootSummaries must be computed inside remember")

    # 4) Deferred EPG load + SQLite store (no 22s Java ObjectInputStream bottleneck)
    if "delay(ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS)" not in activity_code:
        problems.append("PlayerActivity.kt: EPG cache load must stay deferred after first frame")
    if "EpgSqlStore.queryNowPrograms" not in activity_code:
        problems.append("PlayerActivity.kt: must use EpgSqlStore indexed query")
    if "IQTV-epg-cache-load" not in activity_code:
        problems.append("PlayerActivity.kt: EPG cache load trace missing")

    # 5) Zero-wait zap (no blocking SourceLatencyProber.probe inside playGroupAuto) + hardware recovery
    if "SourceLatencyProber.probe(" in activity_code:
        problems.append("PlayerActivity.kt: blocking SourceLatencyProber.probe must not sit on zap hot path")
    if "ensureHardwarePlayerForNewZap" not in activity_code:
        problems.append("PlayerActivity.kt: must recover hardware MediaCodec on zap after soft-video fallback")

    # 6) Runtime tuning switches + DataSource byte counter kept intact
    if "PerfTuning.minBufferMs(context)" not in activity_code or "PerfTuning.maxBufferMs(context)" not in activity_code:
        problems.append("PlayerActivity.kt: LoadControl must read PerfTuning")
    if "JingcaiTsDataSource(" not in (SRC / "StreamPlayback.kt").read_text(encoding="utf-8"):
        problems.append("StreamPlayback.kt: JingcaiTsDataSource byte counting missing")

    if problems:
        for p in problems:
            print(f"check_perf_policy: FAIL {p}")
        return 1

    print("check_perf_policy: OK (true center focus, SQLite EPG, zero-wait zap, cached layers, bounded marquee)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
