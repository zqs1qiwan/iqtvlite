# S1 验证报告 · 小分组抽屉崩溃修复

本文记录 S1（小分组抽屉崩溃：0/1/2 频道 + 大组切小组）修复后的本地验证证据。
验证范围仅限静态门禁与仓库内 JVM 行为用例源码/公式核对；**未推送、未发包、未操作真机**。

- 基线：仓库 `iqtvlite`，分支工作树（`git status` 见下）。
- 修复摘要：消除两处 `lo>hi` 的 `coerceIn` 崩溃（打开目标窗口、首帧行数、首可见索引钳制），
  统一收敛到 `PerfPolicy` 的纯函数；大组切小组 / 落焦 / Back 分支语义保持不变。

## 1. 改动文件清单

`git -C iqtvlite status --short`（验证时）：

```
 M android/app/src/test/java/com/cjlhll/iptv/PerfPolicyTest.kt
 M android/app/src/tivimini/java/com/cjlhll/iptv/PerfPolicy.kt
 M android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt
 M scripts/check_perf_policy.py
?? docs/REVIEW_HANDOFF.md
```

S1 修复实际改动 4 个文件（`git diff --stat`：148 insertions, 4 deletions）：

| 文件 | 改动 |
|---|---|
| `android/app/src/tivimini/java/com/cjlhll/iptv/PerfPolicy.kt` | 新增 4 个纯函数：`drawerFirstRows(n)`、`drawerSafeOpenTarget(idx,n)`、`drawerSafeFirstVisibleIndex(idx,n)`、`drawerFocusIndexInRange(idx,n)` |
| `android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt` | 打开路径改用 `drawerSafeOpenTarget` / `drawerFirstRows`；方向键落焦索引改用 `drawerSafeFirstVisibleIndex`；删除原 `coerceIn(FIRST, size)` / `coerceIn(0, count-1)`；Back 分支未改 |
| `android/app/src/test/java/com/cjlhll/iptv/PerfPolicyTest.kt` | 新增 6 个 JVM 行为用例（见 §4） |
| `scripts/check_perf_policy.py` | 新增 8 项 S1 静态断言（安全 helper 必须被调用、旧 lo>hi 模式不得回归、Back 分支保留、helper 存在） |

`docs/REVIEW_HANDOFF.md` 为交接文件（非本次修复改动）。

## 2. 运行环境探测

| 探测项 | 命令 | 结果 |
|---|---|---|
| Python（bundled） | `& "C:\Users\zqs1q\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" --version` | Python 3.12.14 ✅ |
| 系统 python | `python --version` | WindowsApps 存根，无实际版本输出；改用 bundled python |
| JAVA_HOME | `echo $env:JAVA_HOME` | 空（未设置） |
| java / javac | `java -version` / `javac -version` / `cmd /c "java -version"` | 全部 “not recognized”，PATH 中无 JDK |
| JDK 安装搜索 | `Get-ChildItem "C:\Program Files","C:\Program Files (x86)" -Filter java.exe -Recurse` | 无结果（无 JDK17） |
| Android SDK platforms | `Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\platforms"` | 目录不存在/为空（无 Android 36 平台） |
| Android SDK platform-tools | `Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk"` | 仅存在 `platform-tools` |

结论：**本机无 JDK17、无 Android 36 平台 JAR，无法执行 Gradle / JVM 单测与真机验收**（详见 §5）。

## 3. 静态门禁（8 项，全部通过）

工作目录 `iqtvlite`，解释器为 bundled Python 3.12.14，逐脚本执行 `python scripts/<name>.py`：

| # | 命令 | 输出摘要 | 退出码 |
|---|---|---|---|
| 1 | `python scripts/check_info_card.py` | `check_info_card: OK (v3 big logo/name row/badges/tech + v2 strip + focus guard + defaults)` | 0 ✅ |
| 2 | `python scripts/check_jingcai_timeline.py` | `check_jingcai_timeline: OK (pure TS/PMT/PCR policy, 60s gate, default off, real byte counting, A/B/AUTO/D/E + probe/F/G/H)` | 0 ✅ |
| 3 | `python scripts/check_logo_webp.py` | `check_logo_webp: OK (PNG writes, defensive wrapper, legacy .webp readable, no WEBP encoder)` | 0 ✅ |
| 4 | `python scripts/check_perf_policy.py` | `check_perf_policy: OK (bounded marquee, capped/deferred prefetch, remembered summaries, deferred EPG, trace)`（含本次新增 8 项 S1 断言） | 0 ✅ |
| 5 | `python scripts/check_player_threading.py` | `check_player_threading: OK (no player access from background coroutines)` | 0 ✅ |
| 6 | `python scripts/check_refresh_paths.py` | `check_refresh_paths: OK (scheduler is the only refresh path; one manual EPG entry point)` | 0 ✅ |
| 7 | `python scripts/check_settings_ia.py` | `check_settings_ia: OK (6 root entries, page composables, 24 tags zero-loss, back handler wired)` | 0 ✅ |
| 8 | `python scripts/check_settings_scroll.py` | `check_settings_scroll: OK (list scrolls, rows bring themselves into view, key tags present)` | 0 ✅ |

合计 **8/8 PASS**。

### 3.1 S1 静态断言细节（`check_perf_policy.py`）

新增断言验证以下不变式，全部满足（脚本退出码 0）：

- `PlayerDrawer.kt` 打开路径调用 `PerfPolicy.drawerSafeOpenTarget(` / `drawerFirstRows(` / `drawerSafeFirstVisibleIndex(`；
- 旧危险模式 `coerceIn(DRAWER_FIRST_FRAME_ROWS, channels.size)` 与 `coerceIn(0, channelCount-1)` 不得回归；
- `PerfPolicy.kt` 中三个安全 helper 定义存在；
- Back 分支仍保留 `it.key == Key.Back` 与 `onClose()`（S1 未改变返回行为）。

## 4. JVM 行为用例（源码核对 + Python 公式镜像）

### 4.1 新增 6 个 JVM 用例（`PerfPolicyTest.kt`，未执行，原因见 §5）

| 用例 | 覆盖点 |
|---|---|
| `s1DrawerFirstRowsClampsToActualRowCount` | 0 行→0、1 行→1、2 行→2、3 行→3、大组 147→3、负数→0 |
| `s1SafeOpenTargetNeverExceedsRowCountForSmallGroups` | 0 行→0/0、1 行→1、2 行→2、3 行→3（旧实现崩溃处） |
| `s1SafeOpenTargetPreservesLargeGroupWindowSemantics` | 大组窗口语义不变：147 行 idx0→28、idx40→68 |
| `s1SafeOpenTargetStaysWithinBoundsForAnyInput` | 0..200 行 × -5..200 初始索引全边界扫描，结果恒在 `[firstRows(n), n]` 内、不抛异常 |
| `s1LargeGroupToSmallGroupTargetAndFocusStayValid` | 大组 idx100→128；切到 2 行小组后 idx0→2 不崩、落焦索引 0 有效、越界索引无效、空组无有效焦点 |
| `s1FirstVisibleIndexClampHandlesEmptyAndSmallGroups` | 空组越界→0（不抛）、1 行→0、2 行→1、大组越界→末尾 146 |

`DrawerKeysTest.kt`（现有 4 用例）核查：分组 key 唯一性、节目 key 重复时段唯一性、日期 key 唯一性、跨调用稳定性；
S1 未触及该文件，无需回归。

### 4.2 Python 公式镜像（实际执行，替代无法运行的 JVM 用例做逻辑验证）

命令：bundled python 内联脚本，按 Kotlin 公式 1:1 复刻四个 helper，扫描 n=0..200 × idx=-5..200：

```
boundary violations: 0
0 rows:    first=0 target(0)=0 target(50)=0 fvi(99)=0 focusInRange(0)=False
1 row:     first=1 target(0)=1 target(999)=1 fvi(5)=0
2 rows:    first=2 target(0)=2 target(999)=2 fvi(99)=1
large 147: first=3 target(0)=28 target(40)=68 target(100)=128 fvi(999)=146
[exit code: 0]
```

结论：全边界 **0 违例**，所有结果落在合法区间，0 行不再抛异常，与 6 个 JVM 用例断言逐一吻合。

## 5. 覆盖场景核对

| 场景 | 验证方式与证据 | 结果 |
|---|---|---|
| 0 行（空分组） | 公式镜像 `first=0, target=0, fvi(99)=0`（不抛）；静态断言确认无 lo>hi 模式；用例 `s1FirstVisibleIndexClampHandlesEmptyAndSmallGroups` / `s1SafeOpenTargetNeverExceedsRowCountForSmallGroups` | ✅ 逻辑验证通过（JVM 未运行） |
| 1 行 | 公式镜像 `first=1, target<=1`；用例断言覆盖 | ✅ |
| 2 行 | 公式镜像 `first=2, target<=2`；用例断言覆盖 | ✅ |
| 大组切小组 | 静态断言保留大组窗口语义；公式镜像 `147 行 idx100→128`、`切到 2 行 idx0→2`；用例 `s1LargeGroupToSmallGroupTargetAndFocusStayValid` | ✅ 逻辑验证通过 |
| 落焦（focus） | `check_perf_policy.py` 保留 `onRowFocusedScroll` / focus 相关 token；落焦索引改用 `drawerSafeFirstVisibleIndex`（源码 diff + 断言）；用例验证索引 0 有效、越界无效 | ✅ |
| Back 退出 | `PlayerDrawer.kt` 第 864–868 行 `it.key == Key.Back → onClose()` 未改；静态断言检查 Back 分支保留 | ✅ 源码未改 + 静态门禁通过 |

## 6. 环境限制与未执行项

- **Gradle / JVM 单测未执行**：本机无 JDK（`JAVA_HOME` 空、`java`/`javac` 不在 PATH、Program Files 全盘搜索无 `java.exe`），且无 Android 36 平台 JAR
  （`$env:LOCALAPPDATA\Android\Sdk\platforms` 不存在，仅 `platform-tools`）。因此 `./gradlew test` 相关
  `PerfPolicyTest` / `DrawerKeysTest` 命令**未运行**，退出码不存在，不作编造。
- **真机验收未执行**：按指示不操作真机；0/1/2 行打开、大组切小组、落焦、Back 的真实 UI 行为需在有 JDK17+Android 36
  平台与设备的环境由 CI/人工补测。
- **替代证据**：8 项静态门禁全绿 + Python 公式镜像全边界 0 违例 + 6 个 JVM 用例/静态断言源码核对；逻辑层修复已充分覆盖，
  但不等同于真实设备上的 Compose 运行时验证。
- 未推送、未发包、未修改团队状态文件。

## 7. 结论

S1 修复通过本地全部**可执行**验证（8/8 静态门禁 + 公式镜像全边界 + 断言/用例源码核对）。
覆盖场景在逻辑层均有证据支撑；JVM 单测与真机因环境限制未运行，需下游在具备 JDK17/Android 36/设备的环境中补验。
