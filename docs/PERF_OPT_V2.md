# 弱机性能优化 v2（t7）——实施记录 + A/B 方案

> 基线：**CI10145 / `2.2.0-tivimini-1a7697f6-validation`**（`docs/PERF_BASELINE_V2.md`，t14）。
> 目标：抽屉/菜单卡顿 50th ≤16ms、Janky ≤20%；冷启 rebuffer 与 PSS 不高于基线且放宽 TTFF 不劣化；
> 体积 ≤30MB；不改已验收功能（零拷贝 / MP2 / 缓冲500 / 遥控习惯 / 最近观看 / 台标缓存 / 自动更新 / 音轨 / 去圆角）。
>
> **本任务只改代码与 CI，不执行 adb/设备命令。** 设备侧同口径 A/B 由 acceptance(t9) 按本文 §5 执行；
> 代码里已内置 `IQTV-PERF` 埋点与 `Trace` section，t9 直接采集即可，无需再改代码。

---

## 0. 结论摘要（待 t9 用 §5 填数）

| 项 | 基线（10145） | 本轮改动 | 预期 |
|---|---|---|---|
| 抽屉开合 | Janky **100%**（33/33、38/38）、50th **46–48ms**、无 ≤16ms 帧 | 无限跑马灯 → 有限 2 圈；紧急台标预取延后 600ms 且截断到 12 个 | 抽屉静止后不再产生帧；开合帧只剩一次合成 |
| 设置滚动 | Janky **82.78%**（173/209），50th 23ms | t13 已把每页压到 ≤6 行（旧 24 行单页基线**已过期**）；一级摘要进 `remember` | 需 t9 在 t13 包上重新取基线再对比 |
| 冷启 rebuffer | 1 次 / **4288ms**（首帧 3197ms，同时 `epgCache=22095ms`） | EPG 缓存读取延后到首帧后 **+2s**（22s 解析不再压起播） | 起播窗口不与 EPG 解析抢 CPU |
| PSS | 播放稳态 **79.3MB**（Java 31.8 / Native 18.4） | 无新增常驻缓存/位图/播放器实例；仅去掉每帧动画 | 不高于基线 |
| TTFF | p50 1160 / p95 1562ms（prepare 188 / fmt 812） | **不动播放器缓冲与码率策略** | 不劣化（同一包 A/B 同时报） |

> 关键取舍：**冷启 rebuffer 本轮只做"让路"（把 22s 的 EPG 解析从起播窗口挪开），不动
> `LoadControlTuning` 的缓冲默认值**。改起播缓冲会直接拉长 TTFF，且必须在设备上 A/B 才能定值，
> 属于 t9 的测量范围（§5.4 给了可选实验 A/B 的做法）。

---

## 1. 抽屉开合 100% jank —— 根因与第一刀（首要）

### 1.1 代码里能直接证明的根因

1. **无限跑马灯（主因）**
   `PlayerDrawer.FocusMarqueeText` 旧实现：
   ```kotlin
   Modifier.basicMarquee(iterations = Int.MAX_VALUE)   // 只要该行聚焦就永远在请求下一帧
   ```
   抽屉一打开就有一个频道行被聚焦 → 该行的「当前节目名」跑马灯**永久**驱动 Compose 帧循环 →
   每帧都要把 1080p 的**半透明抽屉层（surface α0.92）在视频 SurfaceView 上重新合成**一次。
   弱机（armeabi-v7a / 2GB / 1080p）单帧 46ms，于是 gfxinfo 出现
   **“33/33 全 janky、直方图没有任何 ≤16ms 帧”** —— 这与“每帧都在做重活”完全吻合（而不是偶发卡）。
2. **开抽屉时抢 CPU 的台标预取**
   旧实现 `delay(200)` 后就把“选中±8 + 整个可视窗口”（约 17 个）台标丢给 2 路并发解码，
   正好压在开合的那几帧上（抽屉打开时 app 单进程 CPU 52.9%，见基线）。
3. 其它已确认**不是**问题（避免过度改动）：抽屉开合本身**没有**位移动画（t145 已改成 instant
   offset）；`LazyColumn` 只组合可视区；每行 `ChannelLogo` 有 `remember` 缓存，内存命中不落盘。

### 1.2 本轮改动（单变量、可一行回退）

| # | 改动 | 文件 | 回退 |
|---|---|---|---|
| P1 | 跑马灯：`Int.MAX_VALUE` → `PerfPolicy.MARQUEE_ITERATIONS = 2`，且**仅在「抽屉可见 + 该行聚焦」**时挂动画（隐藏态/非聚焦行零动画） | `PerfPolicy.kt`（新增）、`PlayerDrawer.kt` | 常量改回 `Int.MAX_VALUE` |
| P2 | 紧急台标预取：延迟 `200ms → 600ms`，集合并发数封顶 `12`（选中邻域优先→可视窗口补足），挑选逻辑抽成 `PerfPolicy.urgentPrefetchIndices` | `PlayerDrawer.kt`、`PerfPolicy.kt` | `URGENT_PREFETCH_DELAY_MS = 200L`、`MAX_URGENT_PREFETCH` 放大 |
| P3 | 开合埋点：`drawer_open ms=..`（DEBUG 门控）+ `Trace` section，供 t9 抓确定性时间线 | `PlayerDrawer.kt`、`PerfTrace.kt` | 无副作用（未开 trace 时近零开销） |

### 1.3 为什么这样能到目标

- 抽屉**静止**时不再有任何动画 requestAnimationFrame → 不再有“每帧 46ms 的重合成”；
  gfxinfo 的窗口里只会剩「打开那一帧 + 关闭那一帧」这类一次性帧。
- 打开的那一帧仍是“一次整层合成”，这是**固有成本**（1080p 半透明层叠在视频上）；若 t9 实测
  这一帧仍 >16ms，则下一步（本轮未做，需设备定值）是把抽屉背景从 α0.92 半透明改成不透明
  `surface`（省一次混合），单变量可测，但会改变视觉观感，需 captain 拍板。

---

## 2. 设置滚动

- t13 已把设置从「单页 24 行」改为「一级 6 入口 + 二级每页 ≤6 行」，**基线里的 173/209 帧（82.78%）
  是全量 24 行旧 UI 的数字，不能直接拿来和 t13 之后比**。
- 本轮只做一处低风险优化（P4）：一级六个摘要 + `rootEntries` 放进 `remember(...)`，避免父级重组
  （信息卡计时 tick、`nowMillis` 等）时反复做字符串格式化与台标统计正则。
- 二级页切换**不做转场动画**（`PerfPolicy.SETTINGS_PAGE_ANIMATED = false`），保持焦点确定性。
- 设置滚动/切换的**改后基线由 t9 在 t7 出包上重新测**（§5.3），不要用 10145 的旧值对比。

---

## 3. 冷启 rebuffer（4288ms）

### 3.1 证据链

- 基线：首帧 **3197ms**（fmt 2582 / render 576 / prepare 3），紧接着 **启动 rebuffer 1 次 / 4288ms**，
  且同一时刻 `epgCache=**22095ms**`（XMLTV 缓存读取 + 解析）。
- 结论：首帧刚出来、播放器正在补缓冲的那 4 秒，正好被 **22 秒的 EPG 解析**抢走 CPU/IO（弱机 4 核、
  2GB）。这不是播放器参数问题，而是**冷启后台任务排期**问题。

### 3.2 改动（P5）

`PlayerActivity` 的 EPG 缓存读取：在“等首帧”之后再 `delay(ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS = 2000ms)`，
然后才在 `Dispatchers.IO` 里 `EpgRepository.load(...)`，并包 `IQTV-epg-cache-load` trace。
`EPG_CACHE_FALLBACK_MS = 10000` 的兜底不变（离线/坏流仍能加载缓存）。

- 影响面：**只有节目单/抽屉数据晚约 2 秒出现**；播放、切台、信息卡均不受影响。
- 回退：`EPG_POST_FIRST_FRAME_DELAY_MS = 0L` 即恢复旧行为（一行）。

---

## 4. PSS

- 本轮**没有**新增任何常驻缓存、位图、播放器实例；`PerfPolicy`/`PerfTrace` 只是常量与函数，
  `longArrayOf(1)` 级别的额外分配可忽略。
- 基线 Native 18.4MB 的大头是解码器与 Media3，不在本任务范围（动它有回归零拷贝的风险）。
- 目标：**不高于基线**。t9 按 §5.5 复测播放稳态 PSS。

---

## 5. 设备侧 A/B 采集（acceptance / t9）

> 统一口径（照 t14）：`gfxinfo reset` 干净窗口；CPU 用 `/proc/stat` 两次差分（`top -b -n 1` 自身
> 吃 19–62% CPU 会污染）；换台必须校验 `IQTV-ZAP` 行数增量；uiautomator 用尺寸判据重试，不靠截图 diff。

### 5.0 打开埋点

```bash
adb shell setprop log.tag.IQTV-PERF DEBUG      # 之后 logcat 里出现 IQTV-PERF 行
adb logcat -c
```

### 5.1 抽屉开合（第一刀，改前/改后同机同包）

```bash
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
# 开合 5 次：MENU 开、BACK 关，每次间隔 800ms
adb shell input keyevent KEYCODE_MENU; sleep 0.8
adb shell input keyevent KEYCODE_BACK; sleep 0.8
# …重复 5 次
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation > drawer_after.txt
```
看 `Janky frames: N (P%)`、`50th/90th/95th percentile`、`HISTOGRAM`（是否出现 ≤16ms 桶），
并对照 `adb logcat -d -s IQTV-PERF` 里的 `drawer_open ms=..`。
**通过线**：50th ≤16ms 且 Janky ≤20%（`PerfPolicy.meetsJankTarget` 的口径一致）。

### 5.2 （可选）systrace/Perfetto 归因

```bash
python <perfetto>/tracebox -c config.pbtx -o /data/local/tmp/t.perfetto-trace --time 8s \
  -a tv.iqtvlite.app.validation -c am -c gfx -c sched -c freq
# 关注 section：IQTV-drawer-open / IQTV-epg-cache-load / 以及主线程 vsync 对齐
```

### 5.3 设置滚动/切页（在 t7 包上**重新取基线**）

MENU → 「关于」等二级页，D-pad 上下各 10 次 + 一级 ↔ 二级往返 5 次，`gfxinfo reset` 后统计。

### 5.4 冷启（HOME + am kill）

```bash
adb shell am force-stop tv.iqtvlite.app.validation
adb logcat -c
adb shell am start -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
# 观察 IQTV-ZAP: firstFrame=.. / state=.. rebuffers=..，以及 IQTV-PERF: epg_cache_load ms=..
```
对比基线：首帧 3197ms / rebuffer 1 次 4288ms / epgCache 22095ms。
**通过线**：启动 rebuffer ≤1 次且时长明显低于 4288ms，首帧不显著变长（TTFF 同报）。
若仍偏高，可选实验 A/B（需 captain 批准，本轮未改）：`LoadControlTuning.MIN_BUFFER_MS`
2500 → 3000 或用「稳(500ms)」tier，同时对报 TTFF。

### 5.5 PSS

```bash
adb shell dumpsys meminfo tv.iqtvlite.app.validation | grep -E 'TOTAL PSS|Java Heap|Native Heap'
```
对比基线播放稳态 79,297KB。

---

## 6. 门禁与回退

- JVM：新增 `PerfPolicyTest`（9 例）——锁死“有限跑马灯 / 预取截断 / jank 门禁口径 / EPG 延迟”。
- 静态：新增 `scripts/check_perf_policy.py`（已接 `.github/workflows/apk.yml`）——
  禁止 `Int.MAX_VALUE` 跑马灯（剥注释后判）、强制走 `PerfPolicy`、预取延后+封顶、摘要 `remember`、
  EPG 延迟 + trace。
- 既有三守卫不变：`check_logo_webp` / `check_info_card` / `check_settings_ia` + `check_settings_scroll`。
- pair gate（R8 keep）与 lint 继续跑；体积 ≤30MB（本轮只加纯逻辑与常量，无新依赖）。
- **逐项回退**：P1/P2（`PerfPolicy` 常量）、P3（无副作用）、P4（`remember` 可拆）、P5
  （`EPG_POST_FIRST_FRAME_DELAY_MS = 0L`）互不依赖，任一项可单独回滚。

---

## 7. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `9db1a30`（`perf(drawer): bound marquee + defer/cap logo prefetch; defer EPG cache after first frame (t7)`） |
| CI run（绿） | **#151**，sha `9db1a30`，event `push`，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 18s`；`:app:testDebugUnitTest`（含新增 `PerfPolicyTest` 9 例）+ `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10151**，versionName `2.2.0-tivimini-9db1a308-validation` |
| `app-debug.apk` sha256 | `94ef2fa97080ff5998225936f69084f76a1bd936b3da99be3f9158f7d65fa25a` |
| `app-debug-androidTest.apk` sha256 | `5b775023dd78ba8c653ea88b7af94aa3b307da66d4bf2a81daf141056e8515ac` |
| 门禁 | `check_logo_webp: OK`、`check_info_card: OK`、`check_settings_ia: OK`、`check_settings_scroll: OK`、**`check_perf_policy: OK`**、pair gate `{"missing_types":0,"missing_methods":0,"missing_fields":0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14705933391，10,435,427 B）；`iqtvlite-android-reports`（id 14705898468） |

> 设备侧 A/B（§5）由 acceptance(t9) 在此包上执行并回填 §0 表格；本任务未执行任何 adb/设备命令。
