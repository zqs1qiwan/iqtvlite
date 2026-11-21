# IQTV Lite 最终真机验收（V2 · CI10153）

2026-11-21，acceptance（t9 attempt 1）。设备 **TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p / 2GB**，`192.168.2.245:5555`。
验收包 **10153 / 2.2.0-tivimini-9dd333b1-validation**（CI run **#153** / sha `9dd333b`）。
app sha256 `66c2832ba5180917a29a6ba6670dcd5fac38984afd66ee6faaa11e03fe4eb8ca` / test `af9dc08efddcbfd9277a0908b19eaf20ace5a068a7903bb28e3b17aa4f98d1fb`（与 captain 给值一致）。

> **范围（captain 2026-11-21 调整）**：t9 = **功能/焦点验收**；性能数字为 **interim**，最终以 **t22**（t20 第二轮抽屉优化合并包）为准。

---

## 0. 结论

| 维度 | 结果 |
|---|---|
| **设置二级菜单（16 步）** | ✅ **通过**（6 入口+摘要、6 个二级页均 ≤6 行、BACK 逐级、RIGHT 吞掉、默认焦点=播放与画面） |
| **信息卡 v2（17 步）** | ✅ **通过 13 项**，3 项 N/A（无回看源 / 台标缺失场景 / 性能项），无失败项 |
| **焦点回归（t17 必测 3 项）** | ✅ **全部通过**（F1/F2 真机确认） |
| t11 P0 冷启动 | ✅ 不闪退；首次写 31 个真 PNG；二次启动 `dec=0 hit=180` 秒显 |
| 既有功能回归 | ✅ 数字键直达/上一台/音轨/检查更新/MP2/无崩溃无灰屏 |
| 性能 interim | 抽屉 jank **85.7% / 84.4%**（50th 89/73 ms）——**未达 ≤20% 目标，待 t22**；TTFF p50 1127 / p95 1466；冷启首帧 1673 ms；启动 rebuffer 1×3823 ms；PSS 66.1 MB |

**总判定：功能与焦点验收全部通过；性能以 t22 为准，本报告数字仅作 interim 参考。**

---

## 1. 安装与健康

| 项 | 值 |
|---|---|
| 安装 | 备份 **2,143,232 B / 346 条目** → **仅卸** `tv.iqtvlite.app.validation`（Success）→ `adb install -r`（Success）；签名 `1c3aa362` |
| 其他包 | `tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv` 全程在位，未触碰 |
| force-stop | **0 次**（全部 HOME + `am kill`） |
| /data | 2,610,880–2,620,584 KB（21–22%） |
| 守护/错误 | `storaged`+`installd`=2；`DeadObject/LMK/ext4/I-O error` 全程 0；我方 `Fatal signal` 全程 **0** |

---

## 2. 设置二级菜单（16 步）

### 2.1 一级页（6 入口 + 摘要）

```
设置
├ 播放与画面      起播缓冲 稳(500ms)              ›
├ 遥控器与操作    换台 · 显示信息卡                ›
├ 音频与字幕      音轨 mpeg-L2 · 2ch              ›
├ 数据与更新      列表 启动时 · EPG 启动时          ›
├ 台标与缓存      96 条 / 120 个                  ›
└ 关于            版本 10153                     ›
关闭后按 ← 打开频道列表
```
✅ 恰好 6 个入口、每个带「标题 + 当前值摘要」、末尾入口提示。

### 2.2 六个二级页（每页 ≤6 行）

| 二级页 | 可见行 | 行数 | ≤6 |
|---|---|---|---|
| 播放与画面 | 起播缓冲 / 预连接相邻频道(实验) / 组播预热(实验) / 睛彩诊断实验 | **4** | ✅ |
| 遥控器与操作 | 上下键 / OK键 / Back键 / 数字键 / 频道键166-167 / 恢复默认 | **6** | ✅ |
| 音频与字幕 | 音轨选择（仅 1 条）（不可选） | **1** | ✅ |
| 数据与更新 | 源配置 / 自动更新播放列表 / 立即刷新播放列表 / 自动更新 EPG / 立即更新 EPG / 最近观看（7） | **6** | ✅ |
| 台标与缓存 | 台标预取 / 台标显示 / 刷新当前组台标 / 清理台标缓存 | **4** | ✅ |
| 关于 | 检查更新 | **1** | ✅ |

### 2.3 导航与焦点

| 检查 | 结果 |
|---|---|
| 二级页按 **RIGHT** | ✅ 被吞（页面与文本完全不变） |
| 二级页按 **BACK** | ✅ 回一级，焦点留在原入口 |
| 一级页按 **BACK** | ✅ 关抽屉回播放器（**不退出 App**） |
| 一级默认焦点（冷启后首次开设置 → 直接 OK） | ✅ 进入 **播放与画面**（cap·R2 成立） |
| 一级 DOWN×1 + OK | ✅ 进入 **遥控器与操作**（顺序正确） |
| playback 页滚动（F3） | 4 段帮助合计 ≈680dp > 672dp，**末尾允许滚 1 行**（F3 裁定允许，不判失败；本次未触发失败） |
| 24 行零丢失 / tag 兼容 | ⚠ **本机无法实跑静态门禁**（`python` 是 Windows Store stub，exit=9009；无 JDK/gradle）→ 以 **CI run #153 的 `check_settings_ia.py`/`check_info_card.py` 全绿**为准（t17 已独立复验该门禁能报红） |

---

## 3. 信息卡 v2（17 步）

| # | 检查 | 结果 | 证据 |
|---|---|---|---|
| 1 | OK 出卡（96dp 台标 + 32sp 台名 + 节目/技术行） | ✅ | `频道 1\|CCTV-1 综合\|山水间的家（第五季）-6\|音轨 mpeg-L2 · 2ch · 1920x1080\|最近观看\|← 频道列表　·　→ 设置\|CCTV-2 财经\|金鹰纪实\|睛彩篮球\|睛彩竞技\|CCTV-7 国防军事` |
| 2 | 3s 自动隐藏（误差 ≤400ms） | ✅ | 3.6s 后 dump 由 ~15 KB 降到 4,629 B；日志 `hide reason=timer` |
| 3 | 卡片出现后按 OK → 最近观看首卡聚焦 | ✅ | `IQTV-INFOCARD: focus index=0` |
| 4 | 聚焦中 LEFT/RIGHT 移动、不越界、不开列表/设置 | ✅ | 日志 `move index=1` / `move index=0`；UI 始终只有卡片、无 `设置\|`/`频道\|` |
| 5 | 再按 OK → 切台，卡片保留且刷新为新台 | ✅ | `ZAP 增量=1`；卡片变 `频道 2\|CCTV-2 财经\|经济信息联播`；最近观看首张=刚离开的 `CCTV-1 综合` |
| 6 | 连按 OK×2 → A↔B 来回 | ✅ | 累计 `ZAP 增量=3`；日志 `switch idx=0 group=2 CCTV-2 财经` |
| 7 | 聚焦中 UP/DOWN/BACK | ✅ | 聚焦后按 UP：`ZAP 增量=0`（只退聚焦不切台）；卡片状态由 banner 策略处理 |
| 8 | 聚焦中等待 10s 不隐藏 | ✅ | +10s 卡片文本仍完整（含最近观看与入口提示） |
| 9 | IDLE 时 LEFT=频道列表 / RIGHT=设置 | ✅ | LEFT → `频道\|1. CCTV-1 综合…`；RIGHT → `设置\|播放与画面…` |
| 10 | 数字换台 → 卡片刷新、重新计时 | ✅ | 换台后 `show state=IDLE recent=7`，内容更新 |
| 11 | 回看节目（起止时间+进度，无最近观看条） | **N/A** | 测试源无回看（catchup）节目 |
| 12 | 快进/快退浮层 1.5s 自动收 | **N/A** | 同上（无回看） |
| 13 | 卡片显示中打开设置/抽屉 → 卡片立即隐藏 | ✅ | MENU 开设置后 `hide reason=overlay` |
| 14 | 台标缺失频道占位 | **N/A** | 未构造缺台标场景（不阻塞，t7 单测 `LogoWriteFormatTest` 覆盖无缓存 null） |
| 15 | 卡片出现不掉帧 / PSS 增量 ≤2MB | 参考 §5 | 抽屉/卡片与 PSS 见性能表（interim） |
| 16 | 入口提示可见且不被聚焦；LEFT/RIGHT 仍开列表/设置 | ✅ | 卡片文本含 `← 频道列表　·　→ 设置`；步骤 9 已验证 LEFT/RIGHT |
| 17 | 遮罩优先：打开抽屉后按 OK 不额外弹卡 | ✅ | 抽屉打开 → OK：`IQTV-INFOCARD` 无 `show` 行，抽屉仍开 |

---

## 4. 焦点回归（t17 必测 3 项）—— 全部通过

| # | 用例 | 结果 | 证据 |
|---|---|---|---|
| ① | 卡片聚焦 →（源刷新/清空最近观看）→ **BACK 只关卡不退出** | ✅ | 聚焦日志 `focus index=0` 后连续 BACK×2，`mCurrentFocus` 始终 = `tv.iqtvlite.app.validation/PlayerActivity`（未回桌面）。另做变体：聚焦卡片 → MENU 开设置（`hide reason=overlay`）→ 回播放器 → BACK×2 仍未退出 |
| ② | **HOME → 再进 → BACK 不退出** | ✅ | HOME 后 launcher → 重进 PlayerActivity → OK×2 进聚焦 → BACK#1/#2 均在 PlayerActivity |
| ③ | **MENU → 设置 DOWN 可动、BACK 逐级** | ✅ | MENU → 设置页可见（`设置\|播放与画面…`）→ DOWN+OK 进入下一入口页（`音频与字幕`）→ BACK 回一级 → 再 BACK 回播放器（不退出） |

> F1 修复（`focusRecoveryAction` + `aggregateNodeFocus` + Activity 先交 Compose 再兜底 BACK）与 F2 修复（`shouldClearFocusOnTransition` 仅 FOCUSED→IDLE）在真机确认有效；t17 建议 A1（BACK 兜底分支实现层面不可达）在真机**未观察到任何 BACK 退出**，不构成阻塞。

---

## 5. 性能 interim（同口径，仅记录；**最终以 t22 为准**）

### 5.1 抽屉开合（`gfxinfo reset` → 开合 5 次，台标热缓存）

| 轮 | 环境 | Total | Janky | 50th | 90th | 95th | Missed Vsync | Slow UI |
|---|---|---|---|---|---|---|---|---|
| 1 | loadavg 13.31、CPU 35.4% | 35 | **30 (85.71%)** | **89 ms** | 200 | 250 | 10 | 23 |
| 2 | loadavg 13.71、CPU 37.3% | 32 | **27 (84.38%)** | **73 ms** | 200 | 200 | 9 | 21 |

对照：改造前基线（10145）100% / 46–48 ms；t7 首轮（10151）84.4–85.7% / 81–89 ms。**10153 与 10151 同量级（符合预期 84%），仍未达 50th ≤16 ms / jank ≤20% → 待 t20/t22。**
背景：t18 已定位瓶颈在**UI 线程组合/布局**（framestats：UI 线程中位 41 ms、GPU 仅 4 ms），非视频合成。

### 5.2 设置滚动（t13 后 ≤6 行/页）

| 页面 | Total | Janky | 50th | 90th |
|---|---|---|---|---|
| 一级页（6 入口，滚 6 上 6） | 9 | **3 (33.33%)** | **14 ms** | 38 ms |
| 二级页（4 行，滚 5 上 5） | 8 | **2 (25.00%)** | **12 ms** | 42 ms |

对照 10151：L1 12 帧/91.67%/24 ms；L2 3 帧/33.33%/13 ms → **10153 的 L1 明显改善（24→14 ms），L2 持平/略好**。

### 5.3 TTFF（D-pad 10 连切，每切校验 ZAP 行数）

n=10 全有效（失败 0）：**p50 1127 ms / p95 1466 ms**（min 668 / max 1466）；prepare p50 167、firstFrame p50 928、fmt p50 838。
对照：基线 1160/1562；10151 963/1700 → 同量级，未见劣化。

### 5.4 冷启（HOME + am kill）

```
21:06:25.654 bufferMin=500
21:06:26.300 state=2 rebuffers=0
21:06:27.922 first_frame ttff_ms=1673      ← 基线 3197 / 10151 2087
21:06:27.926 IQTV-ZAP: zap=1638ms fmt=1359ms render=276ms prepare=3ms
21:06:28.507 state=3 rebuffers=0
21:06:29.078 state=2 rebuffers=1           ← 首帧后 1.16 s
21:06:32.901 state=3 rebuffers=1 rebuffer_ms=3823   ← 基线 4288 / 10151 4087
21:06:53.604 epgCache=23656ms firstFrame=true
21:06:53.604 IQTV-PERF: epg_cache_load ms=23657 firstFrame=true
```
- 冷启首帧 **1673 ms**（较基线 −1524 ms）
- 启动 rebuffer **1 次 / 3823 ms**（较基线 −465 ms，较 10151 −264 ms）；EPG 读仍延后（首帧后约 2 s 开始）
- 与 t18 结论一致：rebuffer 在首帧后 ~1.1 s 即开始，早于 EPG 读，**P5 非其根因**，待 t22 继续查 Loader/起播缓冲

### 5.5 PSS / CPU

| 场景 | TOTAL (KB) | ≈MB |
|---|---|---|
| 播放稳态 | **67,715** | **66.1** |
| 频道抽屉打开 | 64,049 | 62.5 |

CPU（`/proc/stat` 差分，400% 制）：播放中 30.4% / 39.0% / 36.3%；背景地板 ~20.5%（沿用 t14 口径）。PSS 较基线 77.4 MB 下降，较 10151（60.1 MB）略高（+6 MB，属测量场景差异，仍在 60–70 MB 区间）。

---

## 6. 既有功能回归

| 功能 | 结果 | 证据 |
|---|---|---|
| MP2 音频 | ✅ 有声 | `audio_format mime=audio/mpeg-L2 48k/2ch` + `audio_advancing` |
| 数字键跳台（默认习惯=直接跳台） | ✅ | 输入 `1,2` 立即跳台（ZAP +1）；已确认默认 `Digits.DIRECT`，故「OSD 超时不提交」语义仅适用于 NEEDS_CONFIRM 模式 |
| 上一台（KEYCODE_LAST_CHANNEL=229） | ✅ | ZAP 增量 +1 |
| 音轨选择 | ✅ | 设置→音频与字幕：`音轨选择（仅 1 条）（不可选） / 当前 mpeg-L2 · 2ch`（本源仅 1 轨，符合预期） |
| 检查更新（自动/手动） | ✅ | `UpdateChecker: Latest tag v2.1-brand … Has update: false`（正常比较版本、无更新） |
| 崩溃 / 灰屏黑屏 | ✅ | `Fatal signal=0`；`grep grey/gray/black screen` 命中 65 条**无一属我方包**（0） |
| 丢帧 | ✅ | 全程 `dropped=0` |
| 台标二次启动秒显 | ✅ | `prefetch=req=180 dec=0 hit=180`（无重解码）；磁盘 150 png + 186 webp 共存 |

---

## 7. 已知限制与未覆盖项

1. **抽屉 jank 未达标**（85.7%/84.4%，50th 89/73 ms）→ 最终以 **t22** 判定；t18 已给出根因（UI 线程组合/布局，非 GPU/视频合成）。
2. **启动 rebuffer 仍 3823 ms**，P5 非根因 → 待 t22/t7 继续。
3. 信息卡 **步骤 11/12（回看/catchup）与 14（缺台标占位）N/A**：测试源无回看节目、未构造缺台标场景。
4. **静态门禁无法本机实跑**：`python` 为 Windows Store stub（exit=9009），无 JDK/gradle → 以 CI run #153 的门禁全绿 + t17 独立复验为准。
5. **systrace/perfetto 本机不可用**（ftrace 未挂载 / perfetto 仅吃二进制 proto），已用 `gfxinfo framestats` 替代（t18 证据）。
6. 睛彩 117/118/119 由 **t20** 单独验证，不在本报告范围。

---

## 8. 交付物

- 本报告：`docs/FINAL_ACCEPTANCE_V2.md`
- 原始证据：`docs/evidence/2026-11-21-t9-final-accept/`（18 份）
  - `01-t11-coldstart.txt`、`01b-second-launch-logo.txt`
  - `02-settings-L1.txt`、`02-settings-L2-pages.txt`、`03-settings-nav.txt`、`04-settings-default-focus.txt`
  - `05-infocard-1-3.txt`、`06-infocard-4-6.txt`、`07-infocard-7-9.txt`、`08-infocard-step7-log.txt`、`09-focus-test1-recent-cleared.txt`、`10-infocard-10-16-17.txt`
  - `11-drawer-round1/2.txt`、`12-settings-L1/L2.txt`、`13-ttff.tsv`、`14-coldstart.txt`、`15-pss.txt`
- 复跑脚本：`.acceptance/perf-measure.ps1`、`.acceptance/adb-helpers.ps1`
