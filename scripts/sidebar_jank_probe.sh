#!/usr/bin/env bash
# Sidebar-browsing jank probe / gate (t50). Runs on a connected device via adb; NOT executed by CI.
# Usage: scripts/sidebar_jank_probe.sh [SERIAL] [PACKAGE]
# Prints the gfxinfo janky% and 50/90/95th frame times around one "open drawer + browse" gesture.
# Gate suggestion: janky% should be clearly below the CI10078 baseline 80.07% (and 90th < 200ms,
# 95th < 500ms); see docs/UI_BRAND_PERF_REPAIR.md.
set -euo pipefail
SERIAL="${1:-${ANDROID_SERIAL:-}}"
PKG="${2:-tv.iqtvlite.app.validation}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

"${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
"${ADB[@]}" shell am start -W -n "$PKG/com.cjlhll.iptv.SplashActivity" >/dev/null
sleep 8

"${ADB[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null
# Open left sidebar, browse 8 down + 4 up, close (same gesture as the acceptance baseline).
"${ADB[@]}" shell input keyevent 21   # DPAD_LEFT -> open drawer
sleep 1
for _ in 1 2 3 4 5 6 7 8; do "${ADB[@]}" shell input keyevent 20; sleep 0.15; done
for _ in 1 2 3 4; do "${ADB[@]}" shell input keyevent 19; sleep 0.15; done
"${ADB[@]}" shell input keyevent 4    # BACK -> close drawer
sleep 1

echo "--- gfxinfo framestats (sidebar browse) ---"
"${ADB[@]}" shell dumpsys gfxinfo "$PKG" framestats \
  | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|Number Missed Vsync|Number Slow UI thread" || true
echo "Compare against CI10078 baseline: janky 80.07%, 50th 30ms, 90th 200ms, 95th 500ms."
