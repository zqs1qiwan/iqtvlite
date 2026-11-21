# 弱机性能基线 v2（改造前 · CI10145 / t11 热修版）

2026-11-21，acceptance（t14 attempt 1，替代 t6）。设备 **TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p / 2GB**，`192.168.2.245:5555`。
验证包 **10145 / 2.2.0-tivimini-1a7697f6-validation**（CI run **#145** / sha `1a7697f6`）。
用途：**t7 优化的对照基线**（设置二级菜单改造前 = 全量抽屉；信息卡已是 v2）。

> 本报告同时包含 **t11 P0 崩溃修复的设备侧 4 项复验**（captain 要求先验崩溃再测性能）。

---

## 0. 结论摘要

| 项 | 基线值（10145） |
|---|---|
| **t11 P0（台标 WEBP 崩溃）** | ✅ **修复成立**：冷缓存冷启动 0 崩溃、写出 120 个真 PNG、二次启动 `dec=0 hit=153` 全磁盘命中、旧 `.webp` 可读不删、写盘失败只记日志不崩 |
| **TTFF（D-pad 10 连切）** | p50 **1160 ms** / p95 **1562 ms**（min 718 / max 1562）；prepare p50 188、firstFrame p50 972、fmt p50 812、render p50 80 |
| **频道抽屉开合（gfxinfo 干净窗口）** | 33 帧 **Janky 33 = 100%**，50th **46 ms**、90/95th **300 ms**、Missed Vsync 2、Slow UI 23（两次测量一致） |
| **设置（全量）滚动** | 209 帧 **Janky 173 = 82.78%**，50th 23 ms、90th 40 ms、95th 53 ms |
| **冷启（HOME+`am kill`）** | 首帧 **3197 ms**；启动后 **rebuffer 1 次 / 4288 ms** |
| **PSS** | 播放稳态 **79.3 MB**（Java 31.8 / Native 18.4）；抽屉 64.4 MB；设置 68.3 MB；60 s 后 69.7 MB |
| **CPU（/proc/stat 差分）** | 背景地板 **20.5%**（400% 制）；播放中 **31–46%**；设置滚动时 app 单进程 102–114% |
| **信息卡 v2 抽验** | ✅ OK 出卡（大台名+技术行+最近观看快跳+入口提示）、**3 s 自动隐藏**、**聚焦不隐藏**（6 s 后 OK 仍切台） |

**一句话**：崩溃修复确证；性能基线显示**抽屉开合是最大卡顿源（100% jank、中位 46 ms）**，设置全量滚动 82.78% jank，冷启首帧 3.2 s + 4.3 s 启动 rebuffer，PSS 约 70–79 MB。

---

## 1. 环境与门禁（captain 已批准的口径）

**旧门禁 `loadavg < 4` 不适用**：本机 loadavg 常年 12–13.7，但 **CPU 空闲**——属内核 D 态线程常驻（`[VPSS_Software]`、`[ao_engine_task]`）。实测 HOME 暂停播放 60 s 后 CPU 仅 17%user+31%sys，loadavg 仍 11.9。

**新门禁（本次执行）**：①`user+sys < 40%`（400% 制）②top1 非我方进程 ③`gfxinfo reset` 后的干净窗口。loadavg/top1 仅作背景记录。

**关键测量方法修正**：`top -b -n 1` 自身在本机占 **19–62% CPU**，会严重污染读数 → 全部 CPU 数据改用 **`/proc/stat` 两次差分（700 ms 窗口）**，口径为整机 0–100%。

| 时点 | loadavg | CPU busy | top1 | /data 可用 |
|---|---|---|---|---|
| 装前 18:46:35 | 13.14/13.09/12.88 | — | — | 2,623,476 KB |
| 装后 18:48:06 | 12.19/12.82/12.81 | — | — | 2,613,144 KB |
| TTFF 前 19:08 | 13.42 | 39.4% | 自己 67.6% | — |
| 抽屉 19:10:22 | 13.71/13.47/13.12 | 33.7% | 自己 52.9% | — |
| 设置滚动 19:16:01 | 13.11/13.20/13.08 | 35.2% | 自己 114% | — |
| 结束 19:17:33 | 13.32/13.18/13.08 | — | — | 2,621,884 KB |

`storaged/installd` 全程在（2）；`DeadObject/lowmemorykiller/ext4/I/O error` 全程 **0**；我方 FATAL **0**。

---

## 2. 安装（一次卸载例外，累计 2 次，未超限）

| 步 | 结果 |
|---|---|
| 取包 | run #145 artifact `14702953973`（10,394,807 B）；app **13,125,585 B** sha256 `5ca688d4…dde6`；test sha256 `e95539d1…d35e`（与 captain 给值一致） |
| 备份 | `run-as tar` → **1,671,680 B / 318 条目**（`.acceptance/backups/validation-10141-backup.tar`） |
| 卸载 | **仅** `pm uninstall tv.iqtvlite.app.validation` → Success（`tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv` 全程在位） |
| 安装 | `adb install -r` → Success；`versionCode=10145`、签名 `65bcd97`（前版 63bf0787） |
| 数据 | 恢复 prefs/playlist/EPG（`tee` 写入）；**台标缓存刻意保持为空**以复现崩溃场景 |

---

## 3. t11 P0 崩溃修复 · 设备侧 4 项复验（在 10145 上重做）

| # | 验收点 | 结果 | 证据 |
|---|---|---|---|
| 3a | **冷启动不闪退**（台标缓存空，原必崩场景） | ✅ **通过** | 冷启动后进程存活（pid 16237→18674 多次重启均存活），`Fatal signal = 0`；`t11-3a-coldstart-10145.txt` |
| 3b | **首次写盘为 `.png`** | ✅ **通过** | 冷缓存启动后写出 **120 个 `.png` / 0 个 `.webp`**；文件头 `89 50 4e 47 0d 0a 1a 0a` = 真 PNG |
| 3c | **二次启动秒显（磁盘命中）** | ✅ **通过** | `IQTV-LOGO: prefetch=req=240 dec=0 hit=153` → **dec=0**（无重新解码），hit 占多数 |
| 3d | **旧 `.webp` 仍可读、不删除** | ✅ **通过** | 恢复 186 个旧 webp 后与 120 个 png 共存，启动正常、旧文件未被清理 |
| 3e | **写盘失败不崩** | ✅ **通过** | 用同名普通文件占住 `cache/logo_cache_v1` 制造 `mkdirs` 失败 → 输出多条 `W/IQTV-LOGO: write_failed=png`，**进程存活、播放继续**（state=3、rebuffers 正常），`Fatal = 0` |

对比修复前（10137）：同样场景下 `SIGSEGV / SEGV_MAPERR` 于 `LogoLoader.writeBitmapToDisk → Bitmap.compress(WEBP) → libhwui SkWebpEncoder::Encode`，进程直接死。

---

## 4. 信息卡 v2 抽验（10145 已含 t4）

| 检查 | 方法 | 结果 |
|---|---|---|
| OK 出卡 | `KEYCODE_DPAD_CENTER`，600 ms 后 dump | ✅ 文本节点：`频道 1 / CCTV-1 综合 / 秘境之眼-2026-318 / 音轨 mpeg-L2 · 2ch · 1920x1080 / 最近观看 / ← 频道列表　·　→ 设置 / CCTV-2 财经 / 金鹰纪实 / 睛彩篮球 / 睛彩竞技 / CCTV-7 国防军事` → **大台名 + 技术行 + 最近观看快跳 + 入口提示**齐备 |
| 3 s 自动隐藏（IDLE） | 不按键等 3.6 s 再 dump | ✅ 卡片节点消失（dump 由 14,924 B 降到 4,629 B） |
| 聚焦不隐藏（FOCUSED） | OK×2 → 等 6 s → OK，看是否切台 | ✅ **产生新 `IQTV-ZAP: zap=982ms`**（聚焦态仍在，OK 执行切台） |
| 对照（IDLE 已隐藏） | OK → 等 5 s → OK | ✅ **ZAP 行数增量 0**（仅重新显示卡片，不切台） |

> 方法说明：本机 `uiautomator dump` 对 Compose 覆盖层不稳定（时好时坏，偶发 `null root node`），因此"聚焦不隐藏"改用**确定性行为判据**（聚焦态 OK 应切台），比读 UI 树可靠。截图 diff 不可用（视频画面持续变化，暂停键 127 无效）。

---

## 5. 测量 1 · TTFF（D-pad 10 连切，间隔 3 s）

逐切记录当刻环境；每切均校验 `IQTV-ZAP` 行数确实增加（避免抽屉吞键导致假数据）。

| # | 频道 | loadavg | CPU | zap | prepare | firstFrame | fmt | render |
|---|---|---|---|---|---|---|---|---|
| 1 | CCTV-2 | 13.42 | 39.4% | 718 | 225 | 493 | 416 | 77 |
| 2 | CCTV-3 | 13.52 | 31.6% | 1562 | 224 | 1338 | 1195 | 143 |
| 3 | CCTV-4 | 14.08 | 31.0% | 1327 | 219 | 1108 | 976 | 132 |
| 4 | CCTV-5 | 13.76 | 37.1% | 1189 | 187 | 1002 | 812 | 190 |
| 5 | CCTV-6 | 13.64 | 28.6% | 1360 | 207 | 1153 | 1073 | 80 |
| 6 | CCTV-7 | 13.60 | 39.9% | 1189 | 179 | 1010 | 958 | 52 |
| 7 | CCTV-8 | 13.72 | 39.4% | 783 | 181 | 602 | 367 | 235 |
| 8 | CCTV-9 | 13.45 | 33.2% | 1048 | 179 | 869 | 766 | 103 |
| 9 | CCTV-10 | 13.38 | 33.9% | 757 | 188 | 569 | 511 | 58 |
| 10 | CCTV-11 | 13.46 | 41.3% | 1160 | 188 | 972 | 914 | 58 |

**统计（n=10，10/10 有效）**

| 段 | p50 | p95 | min | max |
|---|---|---|---|---|
| **TTFF（zap）** | **1160** | **1562** | 718 | 1562 |
| prepare | 188 | 225 | 179 | 225 |
| firstFrame | 972 | 1338 | 493 | 1338 |
| fmt（format 就绪） | 812 | 1195 | 367 | 1195 |
| render | 80 | 235 | 52 | 235 |

观察：`dec=0ms`（硬件解码器复用/极快），主要耗时在 **fmt（等首个可用样本）** 与 **firstFrame**；prepare 已很低（179–225 ms）。

> 历史对照（口径不同，仅参考）：CI10135 曾测 p50 791 / p95 1526（当时 loadavg 12.86，且未做 ZAP 校验）。本次 p50 偏高约 370 ms，但**环境与校验方式都变了**，不建议跨版本直接下结论，请以本表为 t7 对照基准。

---

## 6. 测量 2 · 频道抽屉开合（干净窗口）

方法：`gfxinfo reset` → 等 800 ms → **只做 LEFT 开 / BACK 关，共 5 次**（每次间隔 1.4 s）→ 立即读统计。**不含冷启、不含睛彩无帧窗口**。

| 轮次 | Total | Janky | 50th | 90th | 95th | Missed Vsync | Slow UI | deadline missed |
|---|---|---|---|---|---|---|---|---|
| 第 1 次 | 33 | **33 (100.00%)** | 46 ms | 300 ms | 300 ms | 2 | 23 | 23 |
| 第 2 次（复现） | 38 | **38 (100.00%)** | 48 ms | 200 ms | 250 ms | 0 | 24 | 24 |

直方图（第 2 次）：`19ms=1 20ms=1 24ms=1 26ms=1 27ms=1 28ms=2 29ms=1 30ms=2 31ms=1 32ms=1 34ms=4 38ms=1 42ms=1 46ms=1 48ms=1 57ms=2 61ms=1 65ms=1 73ms=1 89ms=1 …`
→ **没有任何一帧落在 ≤16 ms**；中位 46–48 ms ≈ **3 帧预算**。

**这是本次基线最严重的卡顿点**，也是 t7 的首要优化目标（抽屉首帧/动画/台标加载）。

---

## 7. 测量 3 · 冷启（HOME + `am kill`，未用 force-stop）

```
19:13:28  am kill → 进程数 0
19:13:28  am start -W SplashActivity
19:13:34.993  IQTV-ZAP: bufferMin=500
19:13:35.693  state=2 rebuffers=0
19:13:38.840  first_frame ttff_ms=3197  rendered_at=17805571
19:13:38.844  IQTV-ZAP: zap=3161ms prepare=3ms firstFrame=3158ms fmt=2582ms dec=0ms render=576ms
19:13:39.010  state=3 rebuffers=0
19:13:39.946  state=2 rebuffers=1            ← 首帧后 ~0.9 s 掉入缓冲
19:13:44.234  state=3 rebuffers=1 rebuffer_ms=4288
19:14:00.955  IQTV-ZAP: epgCache=22095ms firstFrame=true
```

- **冷启首帧 3197 ms**（`fmt` 2582 ms 占大头；`render` 576 ms）
- **启动 rebuffer 1 次 / 4288 ms**（首帧后立即掉缓冲，与历史"首帧后 ~4.4 s rebuffer"一致，**未改善**）
- `epgCache=22095 ms`：EPG 缓存 22 s 才就绪（后台任务，不阻塞首帧，但影响列表体验）
- 崩溃 0

---

## 8. 测量 4 · PSS / CPU 多点

统一口径：`dumpsys meminfo <pkg>` 的 `TOTAL` 行（PSS），单位 KB。

| 场景 | TOTAL (KB) | ≈MB | Java Heap | Native Heap | Views | Activities |
|---|---|---|---|---|---|---|
| A 播放稳态（CCTV-1） | **79,297** | **77.4** | 31,824 | 18,354 | — | — |
| B 频道抽屉打开 | 64,414 | 62.9 | 19,864 | 14,742 | — | — |
| C 设置抽屉打开 | 68,284 | 66.7 | 21,308 | 15,654 | — | — |
| D 冷启后 60 s | 69,657 | 68.0 | 23,192 | 14,962 | — | — |

> A 最高（77.4 MB）：播放中 Java Heap 31.8 MB + Native 18.4 MB。B/C 反而更低，说明抽屉打开时播放器已释放部分资源。历史文档曾记 134 MB，那是**冷启后立即测量**且含睛彩无帧窗口，口径不同。

**CPU（/proc/stat 差分，整机 400% 制 → 归一 0–100%）**

| 场景 | busy | user | sys |
|---|---|---|---|
| 背景地板（HOME，播放暂停） | **20.5%**（3 次：18.5/23.4/19.6） | 8.4–12.4 | 8.6–11.0 |
| 播放中 | 31–46% | 17.7–26.1 | 14.7–17.6 |
| 设置滚动中（app 单进程） | — | — | app 自身 102–114%（top 口径，>100% 因多核） |

线程分布（播放中，top -H）：主线程 21.8%、`ExoPlayer:Loade` 12.5%、`ExoPlayer:Playb` 9.3%、`MediaCodec_loop`/`CodecLooper`/`AudioTrack` ≈0% → **主线程是最大 CPU 消耗者**。

---

## 9. 测量 5 · 设置抽屉（改造前 = 全量）滚动 jank

方法：RIGHT 打开设置 → `gfxinfo reset` → **DOWN ×12 + UP ×12**（每键 320 ms）→ 读统计。

确认是**改造前的全量抽屉**（一级页直接铺 24 个可操作行）：`基础 / 源配置 / 更新EPG / 检查更新 / 最近观看（10）/ 播放与显示 / 起播缓冲：稳(500ms) / 预连接相邻频道（实验）：关 / 组播预热（实验）：关 …`

| Total | Janky | 50th | 90th | 95th | 99th | Missed Vsync | Slow UI | deadline missed |
|---|---|---|---|---|---|---|---|---|
| 209 | **173 (82.78%)** | **23 ms** | 40 ms | 53 ms | 150 ms | 13 | 50 | 51 |

→ 中位 23 ms（约 1.4 帧预算），但 82.78% 帧超标；90th 40 ms。**t3 的二级菜单改造后应显著下降**（每页 ≤6 行、减少一次布局的节点数）。

---

## 10. 供 t7 使用的对照要点

| 指标 | 本基线（改造前） | 备注 |
|---|---|---|
| TTFF p50 / p95 | **1160 / 1562 ms** | 每切均校验 ZAP 行；10/10 有效 |
| TTFF prepare p50 | 188 ms | 已很低，优化空间小 |
| TTFF fmt p50 | 812 ms | **主要瓶颈**（等首个可用样本） |
| 抽屉开合 Janky | **100%（33–38 帧）** | **第一优先级** |
| 抽屉开合 50th | 46–48 ms | 无 ≤16 ms 帧 |
| 设置滚动 Janky | **82.78%（209 帧）** | 改造后复测对比 |
| 设置滚动 50th | 23 ms | |
| 冷启首帧 | **3197 ms** | fmt 2582 占大头 |
| 启动 rebuffer | **1 次 / 4288 ms** | 与历史一致，未改善 |
| PSS 播放稳态 | **77.4 MB** | Java 31.8 / Native 18.4 |
| PSS 冷启后 60 s | 68.0 MB | |
| CPU 背景地板 | 20.5% | 测量前提，勿当优化目标 |
| CPU 播放中 | 31–46% | 主线程最大 |

**口径提醒**：CPU 必须用 `/proc/stat` 差分（`top` 自身吃 19–62% CPU）；jank 必须 `gfxinfo reset` 后只做目标动作；TTFF 必须校验 ZAP 行增量（抽屉打开时数字键会被吞）。

---

## 11. 健康与协议

- 安装：**累计 2 次卸载**（10137→10141→10145 各一次，均在 captain 批准的"一次性例外"内，**未超 2 次上限**）；仅卸 `tv.iqtvlite.app.validation`；未碰其他任何包。
- `force-stop`：**0 次**（全部用 HOME + `am kill`）。
- 未清 data/台标缓存（除 3a 刻意保持空缓存以复现原崩溃场景）；结束时台标缓存 147 png + 186 webp 共存。
- 每步后复查 `/data`（2.61–2.62 GB 可用，21–22%）、`storaged/installd`（2）、`DeadObject/LMK/ext4/I-O`（0）、我方 FATAL（0）。
- 设备结束时停在 **CI10145、CCTV-1 正常播放**，可直接继续测量或留给 t7。

## 12. 交付物

- 本报告：`docs/PERF_BASELINE_V2.md`
- 原始数据：`docs/evidence/2026-11-21-t14-perf-baseline/`
  - `m1-ttff-10145.tsv`（10 连切逐条，含环境）
  - `m2-drawer-gfxinfo-10145.txt` / `m2b-drawer-gfxinfo-hist-10145.txt`（含直方图）
  - `m3-coldstart-10145.txt`（冷启完整窗口）
  - `m4-pss-multipoint.txt` / `m4-pss-a-play.txt`
  - `m5-settings-scroll-10145.txt` / `m5-settings-scroll-gfxinfo-10145.txt`
  - `t11-3a-coldstart-10145.txt`、`t11-3a-coldstart-empty-logo-cache.txt`（2615 行原始 logcat）、`t11-3bc-second-launch-10145.txt`、`t11-3d-write-failure-no-crash.txt`
- 测量脚本（可复跑）：`.acceptance/perf-measure.ps1`（Env1/CpuDelta/GfxReset/TtffSequence/ColdStart/Pss）

## 13. 已知未纳入项

- **睛彩 117/118/119 无帧等待**按 captain 指示不纳入 jank/流畅度统计（属已知问题，t12 跟进时间轴实验）。
- 截图 diff 法不可用（视频持续变化、`KEYCODE_MEDIA_PAUSE` 在本机不冻结画面）；信息卡可见性改用 dump 尺寸 + 行为判据。
- `uiautomator dump` 对 Compose 覆盖层不稳定（偶发 `null root node`），已用重试 + 尺寸判据规避。
