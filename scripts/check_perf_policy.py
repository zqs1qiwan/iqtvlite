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
    elif not (1 <= int(m.group(1)) <= 3):
        problems.append(f"PerfPolicy.kt: MARQUEE_ITERATIONS={m.group(1)} must be 1..3 (finite)")
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

    if problems:
        for p in problems:
            print(f"check_perf_policy: FAIL {p}")
        return 1

    print("check_perf_policy: OK (bounded marquee, capped/deferred prefetch, remembered summaries, deferred EPG, trace)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
