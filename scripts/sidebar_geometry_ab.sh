#!/usr/bin/env bash
# Sidebar A/B geometry + jank forensics (t111).
#
# Runs the SAME protocol on two APKs (baseline CI10090 = 165d94f, and the new flat pair) so the
# geometry numbers are comparable instead of being measured in different drawer states.
#
# Usage: scripts/sidebar_geometry_ab.sh <old.apk> <new.apk> [serial]
#
# Requires: adb, device unlocked on the TV launcher/player, and the playlist already configured.
set -u
OLD_APK=${1:?old apk}
NEW_APK=${2:?new apk}
SERIAL=${3:-}
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
OUT="local/sidebar-ab"
mkdir -p "$OUT"

install_and_run() {
  local apk="$1" tag="$2"
  "${ADB[@]}" install -r -d "$apk" >/dev/null || { echo "install failed: $apk"; exit 1; }
  "${ADB[@]}" shell am force-stop tv.iqtvlite.app.validation
  "${ADB[@]}" shell monkey -p tv.iqtvlite.app.validation -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 12
  # Open the drawer with LEFT (same key that user uses), then settle 2s.
  "${ADB[@]}" shell input keyevent 21
  sleep 2
  # Capture the drawer while it is open (same focus state: default = current channel).
  "${ADB[@]}" shell screencap -p /sdcard/sidebar-$tag.png
  "${ADB[@]}" pull /sdcard/sidebar-$tag.png "$OUT/sidebar-$tag.png" >/dev/null
  "${ADB[@]}" shell rm /sdcard/sidebar-$tag.png
  # Jank probe (same open + 8 DOWN + 4 UP + close protocol).
  "${ADB[@]}" shell dumpsys gfxinfo tv.iqtvlite.app.validation reset >/dev/null
  for i in $(seq 1 8); do "${ADB[@]}" shell input keyevent 20; sleep 0.35; done
  for i in $(seq 1 4); do "${ADB[@]}" shell input keyevent 19; sleep 0.35; done
  "${ADB[@]}" shell input keyevent 4
  sleep 1
  "${ADB[@]}" shell dumpsys gfxinfo tv.iqtvlite.app.validation > "$OUT/gfxinfo-$tag.txt"
}

install_and_run "$OLD_APK" old
install_and_run "$NEW_APK" new

python - "$OUT" <<'PY'
import re, sys, pathlib
out = pathlib.Path(sys.argv[1])
for tag in ("old", "new"):
    text = (out / f"gfxinfo-{tag}.txt").read_text(errors="ignore")
    m = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", text)
    p50 = re.search(r"50th percentile:\s*(\d+)ms", text)
    p90 = re.search(r"90th percentile:\s*(\d+)ms", text)
    p95 = re.search(r"95th percentile:\s*(\d+)ms", text)
    print(tag, "janky=", m.group(2) if m else "?", "%", "p50=", p50.group(1) if p50 else "?",
          "p90=", p90.group(1) if p90 else "?", "p95=", p95.group(1) if p95 else "?")
PY

echo "Screenshots: $OUT/sidebar-old.png vs $OUT/sidebar-new.png"
echo "Compare the same channel row: channel text left edge (expect 96dp), row pitch (expect 96px at 1x),"
echo "header top-left (expect 0,0 when the panel is at the left edge), and the selected row marker (▶ + '· 播放中')."
echo "Both APKs must be measured in the same drawer state (open, same channel, same scroll offset)."
