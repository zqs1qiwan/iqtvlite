#!/usr/bin/env python
"""t15: static guard for the 睛彩 PCR/PTS timeline experiments (docs/JINGCAI_TIMELINE_DIAGNOSIS.md).

Asserts from the real sources that:
  1. `JingcaiTimeline` is Android/Compose-free pure logic and carries the parsers + the 60s gate;
  2. the timeline experiments A/B/AUTO are the only packet rewriters and the default is OFF;
  3. bytes come from a real DataSource read counter (`JingcaiDiag`), not `onLoadCompleted` only;
  4. the give-up path logs `giveup=1` + a timeline diagnostic line;
  5. the wrapper (`JingcaiTsDataSource`) exists and counts every byte.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/java/com/cjlhll/iptv"
TIMELINE = SRC / "JingcaiTimeline.kt"
PIPELINE = SRC / "JingcaiTsPipeline.kt"
DS = SRC / "JingcaiTsDataSource.kt"
DIAG = SRC / "JingcaiDiag.kt"
EXP = SRC / "JingcaiExperiment.kt"
PLAYBACK = SRC / "StreamPlayback.kt"
TEST = ROOT / "android/app/src/test/java/com/cjlhll/iptv/JingcaiTimelineTest.kt"


def main() -> int:
    problems = []
    for p in (TIMELINE, PIPELINE, DS, DIAG, EXP, PLAYBACK, TEST):
        if not p.exists():
            problems.append(f"missing source file: {p.relative_to(ROOT).as_posix()}")
    # t54: App-side TS probe + experiments D/E/F/G/H (all default off, pure logic in JingcaiTimeline)
    timeline = (SRC / "JingcaiTimeline.kt").read_text(encoding="utf-8")
    for token in ("probeTs", "probeLine", "firstPatOffset", "rewritePesTimestamps", "stripPcrFlag",
                  "nullRatioPct", "pesLenZero", "pmtIntervalPackets"):
        if token not in timeline:
            problems.append("JingcaiTimeline.kt: missing t54 token " + token)
    exp = (SRC / "JingcaiExperiment.kt").read_text(encoding="utf-8")
    for token in ('D("d"', 'E("e"', 'F("f"', 'G("g"', 'H("h"', "delayedStart", "dropLeadingBytes", "DELAYED_START_MS"):
        if token not in exp:
            problems.append("JingcaiExperiment.kt: missing t54 token " + token)
    play = (SRC / "StreamPlayback.kt").read_text(encoding="utf-8")
    if "IQTV-JINGCAI-PROBE" not in play:
        problems.append("StreamPlayback.kt: TS probe log line missing (t54)")
    if "PerfTuning.probeTsEnabled(context)" not in play:
        problems.append("StreamPlayback.kt: probe must be gated by iqtv_probe_ts (t54)")
    if "JingcaiExperiment.delayedStart(experiment)" not in play:
        problems.append("StreamPlayback.kt: experiment F delayed start not wired (t54)")


    if problems:
        for p in problems:
            print(f"check_jingcai_timeline: FAIL {p}")
        return 1

    timeline = TIMELINE.read_text(encoding="utf-8")
    pipeline = PIPELINE.read_text(encoding="utf-8")
    ds = DS.read_text(encoding="utf-8")
    diag = DIAG.read_text(encoding="utf-8")
    exp = EXP.read_text(encoding="utf-8")
    playback = PLAYBACK.read_text(encoding="utf-8")

    # 1) pure logic + parsers
    if "import androidx." in timeline or "import android." in timeline:
        problems.append("JingcaiTimeline.kt must stay Android/Compose-free (JVM testable)")
    if not re.search(r"const val MAX_OFFSET_SECONDS = 60\.0", timeline):
        problems.append("JingcaiTimeline.kt: the 60s offset gate is missing")
    for fn in ("fun isPcrOnlyPacket(", "fun rewritePmtPcrPid(", "fun crc32Mpeg2(",
               "fun pmtPcrPid(", "fun readVideoPts(", "fun needsTimelineFix("):
        if fn not in timeline:
            problems.append(f"JingcaiTimeline.kt: missing {fn}")

    # 2) default OFF + only A/B/AUTO rewrite packets
    if not re.search(r'OFF\("off"', exp):
        problems.append("JingcaiExperiment.kt: the default mode must be off")
    if not re.search(r"fun touchesPacketBytes\(mode: Mode\): Boolean =\s*\n?\s*mode == Mode\.A \|\| mode == Mode\.B \|\| mode == Mode\.AUTO", exp.replace("\r", "")):
        problems.append("JingcaiExperiment.kt: only A/B/AUTO may rewrite packet bytes (C is a design placeholder)")
    for mid in ('A("a"', 'B("b"', 'AUTO("auto"'):
        if mid not in exp:
            problems.append(f"JingcaiExperiment.kt: missing timeline mode {mid}")

    # 3) real byte counting, not onLoadCompleted only
    if "JingcaiDiag.onBytes" not in ds:
        problems.append("JingcaiTsDataSource.kt: every read must count real bytes via JingcaiDiag.onBytes")
    if "JingcaiTsDataSource(" not in playback:
        problems.append("StreamPlayback.kt: the DataSource wrapper must be installed")
    if "JingcaiDiag.bytesRead()" not in playback:
        problems.append("StreamPlayback.kt: diagnostic bytes must come from JingcaiDiag.bytesRead()")
    if "firstByteMs" not in playback and "firstByteMs" not in diag:
        problems.append("StreamPlayback/JingcaiDiag: firstByteMs missing (IQTV-DIAG contract)")

    # 4) give-up diagnostics
    if "giveup=1" not in playback:
        problems.append("StreamPlayback.kt: the give-up log must include giveup=1")
    if "timelineDiagnosticLine" not in playback:
        problems.append("StreamPlayback.kt: a timeline diagnostic line is required on give-up")

    # 5) pipeline drops only PCR-only packets / rewrites PMT via the pure policy
    if "JingcaiTimeline.isPcrOnlyPacket" not in pipeline:
        problems.append("JingcaiTsPipeline.kt: must use JingcaiTimeline.isPcrOnlyPacket")
    if "JingcaiTimeline.rewritePmtPcrPid" not in pipeline:
        problems.append("JingcaiTsPipeline.kt: must use JingcaiTimeline.rewritePmtPcrPid")
    if "ALIGN_OFF" not in pipeline:
        problems.append("JingcaiTsPipeline.kt: must fall back to pass-through when alignment fails")

    if problems:
        for p in problems:
            print(f"check_jingcai_timeline: FAIL {p}")
        return 1

    print("check_jingcai_timeline: OK (pure TS/PMT/PCR policy, 60s gate, default off, real byte counting, A/B/AUTO/D/E + probe/F/G/H)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
