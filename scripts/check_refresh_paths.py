#!/usr/bin/env python
"""t189 (MANUAL-POLICY-01): static guard for the F3 refresh paths.

Asserts from the real sources that:
  1. nothing calls the legacy `BackgroundSourceUpdater.refreshAll(...)` / `scheduleRefresh(...)`
     (the scheduler is the only executor — a saved source must not take a second, unsynchronised path);
  2. the manual EPG entry point `AutoUpdateScheduler.requestManualEpg` exists;
  3. PlayerActivity uses `requestManualEpg` and does NOT call `refreshNow(..., epg = true)` directly,
     so one user action can only ever mean one download;
  4. the EPG effect consumes a SharedFlow (`manualEpgRequests`), not a hand-rolled boolean pump.

Exit code 0 = OK, 1 = violation (with file:line).
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/java/com/cjlhll/iptv"
SCHEDULER = SRC / "AutoUpdateScheduler.kt"
PLAYER = SRC / "PlayerActivity.kt"


def fail(msg: str) -> int:
    print(f"check_refresh_paths: FAIL {msg}")
    return 1


def main() -> int:
    problems = []

    # 1) legacy paths must be gone (definition removed too; a leftover definition would be dead code)
    for kt in SRC.glob("*.kt"):
        text = kt.read_text(encoding="utf-8")
        for lineno, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if re.search(r"\brefreshAll\s*\(", stripped) and "fun refreshAll" not in stripped:
                problems.append(f"{kt.name}:{lineno}: refreshAll() call bypasses the scheduler")
            if re.search(r"\bscheduleRefresh\s*\(", stripped) and "fun scheduleRefresh" not in stripped:
                problems.append(f"{kt.name}:{lineno}: scheduleRefresh() call bypasses the scheduler")
            if re.search(r"\bfun\s+(refreshAll|scheduleRefresh)\s*\(", stripped):
                problems.append(f"{kt.name}:{lineno}: legacy helper still defined (dead code)")

    scheduler = SCHEDULER.read_text(encoding="utf-8")
    player = PLAYER.read_text(encoding="utf-8")

    # 2) single manual entry point
    if "fun requestManualEpg(" not in scheduler:
        problems.append("AutoUpdateScheduler.kt: requestManualEpg() is missing")
    if "replay = 1" not in scheduler:
        problems.append("AutoUpdateScheduler.kt: events flow lost replay=1 (new Activity would miss the Toast)")

    # 3) the player must not bypass it
    for lineno, line in enumerate(player.splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("//"):
            continue
        if "refreshNow(" in stripped and "epg = true" in stripped:
            problems.append(f"PlayerActivity.kt:{lineno}: direct refreshNow(epg = true) bypasses requestManualEpg")
    if "requestManualEpg()" not in player:
        problems.append("PlayerActivity.kt: requestManualEpg() is never used")

    # 4) the pump consumes a flow and consults the policy
    if "manualEpgRequests.collect" not in player:
        problems.append("PlayerActivity.kt: the manual EPG pump does not consume a SharedFlow")
    if "ManualRefreshPolicy.actionFor(" not in player:
        problems.append("PlayerActivity.kt: the pump does not consult ManualRefreshPolicy.actionFor")

    if problems:
        for p in problems:
            print(f"check_refresh_paths: FAIL {p}")
        return 1

    print("check_refresh_paths: OK (scheduler is the only refresh path; one manual EPG entry point)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
