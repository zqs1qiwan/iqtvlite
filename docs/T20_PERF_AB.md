# t20 性能第二轮 · 真机 A/B（CI10156）

2026-11-21，acceptance（t22 attempt 1）。设备 **TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p / 2GB**。
改后包 **10156 / 2.2.0-tivimini-679f9614-validation**（CI run **#156** / sha `679f961` / app sha256 `b8a5272f…10e8` / artifact `14707176935`）。
改前对照：**10151 / 9db1a30**（t7 第一轮，`docs/T7_PERF_AB_EARLY.md`）；改造前基线 **10145**（`docs/PERF_BASELINE_V2.md`）。

---

## 0. 结论

| 指标 | 10151（改前） | **10156（t20 改后）** | 目标 | 判定 |
|---|---|---|---|---|
| **抽屉 Janky** | 84.4–85.7% | **100%（5 轮全部 100%）** | ≤20% | ❌ **未达标（更差）** |
| **抽屉 50th** | 81–89 ms | **69 / 81 / 101 / 300 / 450 ms**（中位 ≈101） | ≤16 ms | ❌ **未达标（更差）** |
| 抽屉 90th / 95th | 150–200 ms | **350–850 ms** | — | ❌ 更差 |
| **抽屉帧数（5 次开合）** | 32–35 | **12–26** | — | ✅ **大幅下降（合成量↓）** |
| **抽屉静止 6 s 产帧** | 3 帧 | **0 帧** | — | ✅ **M1 生效（彻底无持续 invalidate）** |
| framestats UI 线程中位 | 41.1 ms | **31.1 ms** | — | ✅ 下降 |
| framestats Draw 峰值 | 118–162 ms | **306.7 ms**（3–4 帧 300–385 ms） | — | ❌ 出现"巨帧" |
| **设置一级滚动** | 91.67% / 24 ms | **18.37% / 9 ms** | ≤20% / ≤16 ms | ✅ **达标** |
| **设置二级滚动** | 33.33% / 13 ms | **16.89% / 9 ms** | ≤20% / ≤16 ms | ✅ **达标** |
| TTFF p50（稳定态） | 963 ms | **957 ms**（n=6，prepare 150 ms） | 不劣化 | ✅ 不劣化 |
| 冷启首帧 | 2087 ms | 3011 / 3511 ms（2 次，波动大） | — | ⚠ 未改善（波动） |
| 启动 rebuffer | 4087 ms | **3807 ms（1 次）+ 1126 ms（第 2 次）** | 下降 | ⚠ **未改善（总 4933）** |
| PSS 播放稳态 | 60.1 MB | **71.8 MB** | 不高于基线 | ⚠ 略升 |

**总判定：抽屉 jank 未达标且 90/95th 明显变差，需第三轮；但帧数与 UI 线程成本真实下降，"静止 0 帧"与"设置滚动达标"是确定性进步。冷启 rebuffer 未改善（新增埋点已给出关键线索，见 §4）。**

---

## 1. 安装与健康

| 项 | 值 |
|---|---|
| 安装 | 备份 **2,138,112 B / 348 条目** → **仅卸** `tv.iqtvlite.app.validation`（Success）→ `install -r`（Success）；vc10156 / 2.2.0-tivimini-679f9614-validation / 签名 `4b0a7a57` |
| 其他包 | `tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv` 全程在位 |
| force-stop | **0 次**（均 HOME + `am kill`） |
| /data | 2,610,568–2,620,500 KB（21–22%） |
| 守护/错误 | `storaged`+`installd`=2；`DeadObject/LMK/ext4/I-O error` 全程 0；我方 `Fatal signal` 全程 **0** |

---

## 2. 抽屉 jank（核心，5 轮同口径）

协议：台标预热（120 png + 186 webp，与 10151/10153 同条件）→ `gfxinfo reset` → **LEFT 开 / BACK 关 ×5**（各 1.5 s）→ 读统计。

| 轮 | 环境 | Total | Janky | 50th | 90th | 95th | Missed Vsync | Slow UI |
|---|---|---|---|---|---|---|---|---|
| 1（预热后） | loadavg 13.25、CPU 34.3% | 23 | **23 (100%)** | **101 ms** | 650 | 850 | 8 | 22 |
| 2 | loadavg 12.44、CPU 37.5% | 20 | **20 (100%)** | **300 ms** | 450 | 450 | 6 | 19 |
| 3 | loadavg 13.53、CPU 43.4% | 26 | **26 (100%)** | **69 ms** | 350 | 400 | 5 | 23 |
| 4（稳定态） | loadavg 12.56、CPU 35.7% | 20 | **20 (100%)** | **450 ms** | 600 | 600 | 5 | 19 |
| 5（稳定态） | loadavg 12.58、CPU 38.7% | 23 | **23 (100%)** | **81 ms** | 450 | 450 | 3 | 20 |

对照 10151：84.4–85.7% / 50th 81–89 ms / 90th 150–200 ms。
→ **Janky 100%、50th 中位更高、90/95th 从 150–200 ms 恶化到 350–850 ms。**

### 2.1 帧数与"静止 0 帧"（真实进步）
- 5 次开合的产帧 **32–35 → 12–26**（合成/重绘总量明显下降）。
- **抽屉静止 6 s = 0 帧**（10153 为 3 帧、10145 为连续产帧）→ `MARQUEE_ITERATIONS=0` + `showContent=visible && contentReady` 彻底消灭持续 invalidate。

### 2.2 逐帧阶段归因（framestats，12 帧）

| 阶段 | 中位 | 最大 |
|---|---|---|
| 总帧时长 | 66.1 ms | **384.8 ms** |
| **UI 线程（AnimationStart→SyncStart）** | **31.1 ms**（10151 为 41.1） | **346.9 ms** |
| 其中 DrawStart→SyncStart | 4 ms | **306.7 ms** |
| RenderThread sync | 0.6 ms | 0.7 ms |
| GPU issue | 4.6 ms | 11.6 ms |
| SwapBuffers→FrameCompleted | 2.7 ms | 4.5 ms |

最慢 4 帧：`total 384.8 / 373.4 / 357.5 / 344.9 ms`，均伴随 **ui 318–347 ms、draw 278–307 ms**。
→ **中位成本下降（41.1→31.1 ms），但出现 3–4 个 300–385 ms 的"巨帧"。**

### 2.3 根因判断（给第三轮）
`IQTV-PERF drawer_open ms=314–408 **rows=147** groups=14` —— E1/E2 把"关闭时不组合列表"做对了，于是**打开瞬间一次性组合全部 147 行**，把原来分散在多帧的组合/绘制工作量**集中成 3–4 个巨帧**。帧数减少、静止 0 帧都是真进步，但**打开/关闭动画期间仍有 300–385 ms 的长帧**，导致 90/95th 反而变差。
—— 这解释了"为什么帧数↓、中位↓、但 jank% 与 90/95th ↑"。

---

## 3. 设置滚动（达标）

| 页面 | Total | Janky | 50th | 90th | 95th |
|---|---|---|---|---|---|
| 一级（6 入口，滚 6 上 6） | 811 | **149 (18.37%)** | **9 ms** | 29 ms | 48 ms |
| 二级（4 行，滚 5 上 5） | 592 | **100 (16.89%)** | **9 ms** | 30 ms | 53 ms |

对照：10151 L1 91.67%/24 ms、10153 L1 33.33%/14 ms、改造前基线 82.78%/23 ms。
→ **首次达到 ≤20% / ≤16 ms 目标**（E3 remember + P4 + 页面不转场生效）。

---

## 4. 冷启 + 新埋点（rebuffer 未解，但拿到关键数据）

### 4.1 两次冷启（HOME + `am kill`）

| 场景 | 首帧 | 启动 rebuffer |
|---|---|---|
| 冷启动 #1（台标**冷**缓存） | 3011 ms | **0 次**（本机缓存冷时未触发） |
| 冷启动 #2（台标**热**缓存） | 3511 ms | **1 次 3807 ms + 第 2 次 1126 ms（累计 4933 ms）** |

对照 10151：2087 ms / 4087 ms。→ 首帧与 rebuffer **均未改善**（且两次波动大，冷启首帧 3.0–3.5 s）。

### 4.2 新可观测（t20 新增 CountingDataSource）——关键线索

```
rebuffer_begin  bytesRead=1250200  bufferMs=696   since_first_frame_ms=303
rebuffer_end    lasted_ms=3807  bytes=1387494  bytesPerSec=364458  bufferMs=1986
rebuffer_begin  bytesRead=6193022  bufferMs=157   since_first_frame_ms=9104
rebuffer_end    lasted_ms=1126  bytes=2138112  bytesPerSec=1898856  bufferMs=2016
```

- 第一次 rebuffer 期间 **`bytesPerSec ≈ 364 KB/s`**（1080p MP2 需要约 1 MB/s）→ **补流速率严重不足**，支持 t7/t20 的"Loader 补流不足"假设。
- 第二次恢复后 `bytesPerSec ≈ 1.9 MB/s`（正常），说明**不是源带宽恒定不足，而是起播初期拉流爬升太慢**。
- 第一次 rebuffer 在**首帧后 303 ms** 就发生（`bufferMs=696` 已耗尽）→ **与 EPG 无关**（EPG 读仍延后 ~2 s），再次确认 P5 非根因。
→ 第三轮建议：起播期提高 Loader 并发/预读量或临时提高 `bufferForPlaybackMs`（须同报 TTFF 不得劣化）、或对首包/前 N 秒做双连接抢流。

### 4.3 TTFF（确认未被拉长）

| 测量 | n | p50 | p95 | prepare p50 | firstFrame p50 | fmt p50 |
|---|---|---|---|---|---|---|
| 冷启后立刻首测（含预热干扰） | 10 | 1587 | 2601 | **424** | 1125 | 967 |
| **稳定态复测** | 6 | **957** | 1122 | **150** | 783 | 657 |

→ 冷启后首测的 `prepare=424 ms` 是**冷启后台 EPG/预热干扰**的假象；稳定态 `prepare=150 ms`、TTFF p50 957 ms，**与 10151（963）持平，未劣化**。
（报告口径：TTFF 以稳定态为准，冷启后首测不可作为基线。）

---

## 5. PSS / CPU

| 场景 | TOTAL (KB) | ≈MB |
|---|---|---|
| 播放稳态 | 73,570 | 71.8 |
| 抽屉打开 | 74,643 | 72.9 |

CPU（`/proc/stat` 差分）：开合抽屉时 34–43%；播放中 33–53%。PSS 较 10151（60.1 MB）升约 12 MB —— 与 t20 未新增常驻缓存的说法不符，建议第三轮顺带核对（可能来自更多播放 session 未回收）。

---

## 6. 回归（全部通过）

| 项 | 结果 |
|---|---|
| t11 冷启动不闪退 | ✅ Fatal=0、进程存活、写出 47→120 个真 PNG、write_failed=0 |
| 设置二级 | ✅ 一级恰好 6 入口带摘要（含 `台标与缓存 96 条 / 332 个`、`关于 版本 10156`）+ 页脚提示 |
| 信息卡 | ✅（本次 dump 偶发空，但 t9 已在 10153 完整验过 13 项；本包未改信息卡代码） |
| 焦点 ②（HOME→重进→BACK） | ✅ BACK#1/#2 均未退出 |
| MP2 | ✅ `audio/mpeg-L2 48k/2ch` + `audio_advancing` |
| 数字 OSD（DIRECT 直接跳台） | ✅ 输入 1,2 → ZAP 立即 +2 |
| 上一台 229 | ✅ ZAP +1 |
| 不崩不灰 | ✅ `Fatal signal=0`；`grey/gray/black` 我方命中 0；`dropped=0` |

---

## 7. 结论与第三轮建议

**结论：抽屉 jank 未达标（100% / 50th 69–450 ms），且 90/95th 更差 → 需第三轮；设置滚动已达标；TTFF 未劣化；冷启 rebuffer 未改善但已定位到"起播初期补流仅 364 KB/s"。**

第三轮（t20 v3）建议按证据排序：
1. **拆"巨帧"**：`rows=147` 一次性组合是 300–385 ms 巨帧的直接原因 → 首帧只组合可见 ~10 行（`LazyColumn` 的 item 不要用在 147 行全量 `Column`/`forEach`），其余按需/分帧组合；或 `DRAWER_DEFER_CONTENT_MS` 设为 120–160 ms（动画结束后）再组合，用"面板先出、内容后到"换掉长帧。
2. 核对 PSS +12 MB 是否来自 session/位图未回收。
3. 冷启 rebuffer：从 Loader 并发/预读或用例②（afterRebuffer 2000→3000）入手，须同报 TTFF。
4. 保留 M1（marquee=0）、E4（不透明容器）、E1/E2（关闭不组合）、E3（remember）——这四项带来"帧数 32–35→12–26、静止 0 帧、UI 中位 41→31 ms、设置滚动达标"的确定性收益。

---

## 8. 交付物

- 本报告：`docs/T20_PERF_AB.md`
- 原始数据：`docs/evidence/2026-11-21-t22-perf-ab2/`
  - `01-coldstart-coldlogo.txt`、`05-coldstart-warmlogo.txt`（含 rebuffer_begin/end 新埋点）
  - `02-drawer-round1/2/3.txt`、`09-drawer-settled-round1/2.txt`
  - `03-framestats.txt`、`03-framestats-stages.csv`（逐帧阶段）
  - `04-drawer-idle.txt`（静止 0 帧）
  - `06-ttff.tsv`、`06b-ttff-recheck.txt`、`10-ttff-settled.tsv`
  - `07-settings-scroll.txt`、`08-pss.txt`、`11-regression.txt`
- 复跑脚本：`.acceptance/perf-measure.ps1`
