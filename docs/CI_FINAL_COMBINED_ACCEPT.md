# CI10185 合并最终验收：抽屉收尾 + 睛彩时间轴（t48 + t49）

2026-11-22，acceptance（t50 / 合并原 t51）。设备 **TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p / 240dpi**。
包 **10185 / 2.2.0-tivimini-39e86e1d-validation**（CI run **#185** / app sha256 `1e1e415e9dfed27b1a2ac7a9e18bd65e3413540142d06627d8ec8e4c201f98f4` / artifact `14722398320`）。
对照：抽屉 **t47 / CI10179**。

---

## 0. 结论

| 项目 | 结果 | 判定 |
|---|---|---|
| **A 抽屉 · 非聚焦行单字残影** | **✅ 消失**（t47 的 `城/军/武/音` 单字节点已不再出现） | ✅ **修复** |
| **A 抽屉 · 滚动跟随 / 副标题** | ✅ DOWN×25 正常跟随（51–62、`row_focus via=key` 27 条）；聚焦行副标题正常（`idx=54 subtitle=true title=电视剧`） | ✅ **保持** |
| **A 抽屉 · jank** | ❌ **变差**：≥300ms 巨帧 **3→4**（321.9/323.1/401.6/451.5）、50th **53→89/85ms**、帧时中位 50→**82.3ms**、UI 19→**37.8ms**、Draw 2.5→**16.5ms** | ❌ **未达标且回退** |
| **B 睛彩 · 默认 OFF 诊断链路** | ✅ `IQTV-DIAG bytesRead=8430296 firstByteMs=50883240 code=open` + `buffer bufferMs=0 currentPosition=0 windowCount=1 state=2` + `diag mode=off … format=video/avc/1920x1080@-1.0 firstFrame=false state=2` + `giveup=1 stage=SOFT_TRIED` **三行齐全** | ✅ |
| **B 睛彩 · A/B 实验是否可修** | ⚠ **无法定论**：同一模式多次结果不一致（见 §3.2/§3.3）—— OFF 首测 117 失败；A 模式 118/119 成功而 117 失败；B 模式 117/118 失败、119 成功，**B 模式重测 117/118/119 又全部成功** | ⚠ **间歇性，非模式决定** |
| **B 睛彩 · CCTV-1 零影响** | ✅ ZAP 1085–1382ms、first_frame 有、state=3、MP2、Fatal=0、dropped=0 | ✅ |
| **C 回归** | ✅ 设置一级 6 入口+`稳(500ms)`、BACK#1/#2 未退出、MP2、数字 `zap=1155ms`、上一台 +1、Fatal=0、grey 我方 0、dropped=0、keys=0、`jingcai_experiment=off`（UI「关闭」） | ✅ |

**总判定：**
1. **抽屉**：残影修复 + 滚动/副标题保持 = 功能收尾完成；但 `openTarget 28→18` 使 jank **明显回退**（50th 53→89/85、Draw 2.5→16.5ms），**建议按 captain 预案回退 `DRAWER_OPEN_WINDOW_ROWS=28`**（t47 的 3 巨帧/53ms 是更好状态）。
2. **睛彩**：诊断链路（DIAG/buffer/timeline/giveup）**完全可用**；但 **A/B 时间轴改写未显示稳定的修复效果** —— 同一模式跨轮结果不一致，说明**故障是间歇性的（更可能是源侧/组播加入时序），而非 PMT PCR 单一原因**。

---

## 1. 安装与健康

| 项 | 值 |
|---|---|
| 安装 | 备份 **2,308,608 B / 370 条目** → **仅卸** `tv.iqtvlite.app.validation`（Success）→ `install -r`（Success）；签名 `3aba1db4`；vc10185 |
| 实验键 | 装前 **0** / 装后 **0** / 收尾 **0**（`settings global` 无 iqtv 键） |
| 睛彩实验 | `shared_prefs/iptv_prefs.xml → jingcai_experiment` **收尾 = off**（设置页显示「睛彩诊断实验：关闭」） |
| 其他包 | `tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv` 全程在位 |
| force-stop | **0 次**（HOME + `am kill`） |
| /data | 2,590,556–2,600,396 KB（22%）；守护进程 2；`DeadObject/LMK/ext4/I-O`=0 |
| 崩溃 | 我方 `Fatal signal` 全程 **0** |

---

## 2. A · 抽屉（t47 → t48 收尾）

### 2.1 残影（重点）

t47 在非聚焦行旁观察到单字残留（`城` / `军` / `武` / `音`）。本轮同一判据（dump 全文本中长度=1 且非数字的文本节点）：

- t47：`104. 城市剧场 | 城`、`105. 军旅剧场 | 军`、`107. 武侠剧场 | 武`、`111. 音乐现场 | 音`
- **t48：无任何单字节点** ✅（`19. CCTV-4K 超高清 … 30. 黑龙江卫视 | 你好，俄罗斯` 等均为完整台名/节目名）

→ `key("row_focused_$index")` + `clipToBounds()` + 副标题固定 16dp Box 三重保险**生效**。

### 2.2 滚动跟随 / 副标题（保持）

- DOWN×25：可见行 1–12 → **51–62** ✅；`row_focus via=key` 共 **27 条** ✅
- 副标题：`row_focus idx=54 subtitle=true title=电视剧`、`idx=55 subtitle=false title=`（无 EPG 留空）✅

### 2.3 jank（**回退**）

| 指标 | t47（openTarget=28） | **t50（openTarget=18）** | 判定 |
|---|---|---|---|
| 开合 5 次 Janky | 83 帧 / 100% | 58 帧 / **98.3% / 100%** | — |
| **50th** | **53 / 53 ms** | **89 / 85 ms** | ❌ 回退 |
| 90th | 350 / 250 | 350 / 400 | 略差 |
| 95th | 450 / 350 | 450 / 400 | 持平 |
| **≥300ms 巨帧** | **3** | **4**（321.9 / 323.1 / 401.6 / 451.5） | ❌ 回退 |
| ≥100ms | 8 | 13 | ❌ |
| 最大帧 | 341.3 | **451.5** | ❌ |
| 帧时中位 | 50.0 | **82.3** | ❌ |
| UI 中位 | 22.6 | **37.8** | ❌ |
| Draw 中位 | 2.5 | **16.5** | ❌ **6.6×** |
| `drawer_compose frames` | 13 | **8**（frames 少了，如预期） | ✅ 设计达成 |

埋点确认 `openTarget=18` 已生效：
```
drawer_compose target=18 first=3 step=2 rows=147
drawer_compose frames=8 rows=147 target=18 ms=644 lastFrameRows=1 first=3 step=2
```

> **解读**：`frames 13→8` 说明 `openTarget 28→18` 确实按设计减少了首开组合帧；但 **jank 反而显著变差**（Draw 中位 2.5→16.5ms，巨帧 3→4）。两轮测量间 **loadavg 从 12.7 升到 12.1/12.15 但 CPU 37.7%/30.8%**，未见明显更差的外部负载；更像是"每帧组合行数分布变化 + 明显抖动"。**结论：本次改动不划算，建议回退 openTarget=28（t47 状态更好）。**

---

## 3. B · 睛彩时间轴实验（117/118/119）

### 3.1 默认 OFF：诊断链路完整（✅ 本项达标）

ch117（睛彩竞技）20–40s 内**未出首帧**，日志三行齐全：

```
IQTV-JINGCAI: rebuild=1 channel=4783027072129292115
IQTV-JINGCAI: softfallback=1 channel=4783027072129292115
IQTV-JINGCAI: giveup=1 channel=… stage=SOFT_TRIED
IQTV-JINGCAI: diag mode=off bytesRead=8430296 firstByteMs=50883240 format=video/avc/1920x1080@-1.0 firstFrame=false state=2 code=open
IQTV-JINGCAI: IQTV-DIAG bytesRead=8430296 firstByteMs=50883240 code=open
IQTV-JINGCAI: buffer bufferMs=0 currentPosition=0 windowCount=1 state=2 rebuffers=0 rebuffer_ms=0 bytesRead=8430296 firstByteMs=50883240
```

- `bytesRead=8,430,296`（**~8.4MB 已读取**，证明不是"没数据"）
- `firstByteMs=50,883,240`（设备 uptime 毫秒，≈14.1h，说明首字节早已到达）
- **`bufferMs=0 currentPosition=0 windowCount=1 state=2`** → 数据进了、但缓冲区为 0、位置不动 → **解复用/时间轴侧问题**
- `code=open`（不是网络错误）

> 这印证了 t15 的方向：**不是取不到流，而是取到了却解不出可播放时间轴**。

### 3.2 实验 A（丢 PCR-only 包）—— 结果不一致

| 频道 | A 模式首测 | 备注 |
|---|---|---|
| 117 睛彩竞技 | ❌ firstFrame=false / state3=false | `diag mode=a … pcr=none pts=7517.960 offset=none mode=a pcr_pid=-1 drop_pcr_only=0 rewrite_pmt=0` |
| 118 睛彩篮球 | ✅ **firstFrame=true / state3=true**（zap 1009ms） | — |
| 119 睛彩青少 | ✅ **firstFrame=true / state3=true**（zap 776ms） | — |

关键：117 的 timeline 行显示 **`pcr=none`、`pcr_pid=-1`、`drop_pcr_only=0`** → **PMT 里根本没声明 PCR PID，A 模式"按 pcr_pid 丢 PCR-only 包"退化为空操作**（丢 0 个包）。所以 118/119 的成功**不能归因于 A**。

### 3.3 实验 B（PMT PCR_PID→0x1FFF）—— 结果不一致

| 频道 | B 模式首测 | B 模式重测 |
|---|---|---|
| 117 | ❌ false / false | ✅ **true / true**（zap 1310ms） |
| 118 | ❌ false / false（`pcr=none pts=7764.840 pcr_pid=-1 rewrite_pmt=0`） | ✅ **true / true**（zap 842ms） |
| 119 | ✅ true / true（zap 780ms） | ✅ **true / true**（zap 1140ms） |

（"B 模式重测"是因为模式切换循环未及时回到 OFF，两次测试实际都在 B 模式——反而成了**同一模式的重复测量**。）

**同一 B 模式下，117/118 从"全失败"变成"全成功"**，且 timeline 行仍显示 `pcr=none pcr_pid=-1 rewrite_pmt=0`（B 模式在无 PCR_PID 时同样是空操作）。

> **判定：A、B 在本轮均未显示可复现的修复效果；118/119 甚至 117 的成功与"模式"不对应，说明故障是间歇性的。**

### 3.4 AUTO（仅 ±60s 激活）—— t51 补充实测

AUTO 模式下 117/118/119 各 20s：

| 频道 | firstFrame / state3 | `auto timeline fix activated` | timeline 行 |
|---|---|---|---|
| 117 | ❌ false / false | **无** | — |
| 118 | ❌ false / false | **无** | `pcr=none pts=9922.760 offset=none mode=auto pcr_pid=-1 drop_pcr_only=0 rewrite_pmt=0` |
| 119 | ✅ true / true | **无** | — |

→ **AUTO 行为正确**：失败样本 `pcr=none`（根本没解析到 PCR）→ `offset=none` → 60s 闸门不成立 → **不激活、不改字节**（`drop_pcr_only=0 rewrite_pmt=0`）。
→ 这与 t15 的原始假设（PCR≈32153s vs PTS≈64307s，≈9h 偏移）**不一致**：最常见的失败形态里**没有 PCR**，而不是 PCR 与 PTS 差 9 小时。**即"AUTO 逻辑没错，但它假设的前提（有 PCR）在故障流上不成立"。**

### 3.6 健康频道在 A / B / AUTO 下均零影响（t51 补充）

CCTV-1 在三种实验模式下各测一次：

| 模式 | firstFrame | state=3 | 睛彩日志 | 结论 |
|---|---|---|---|---|
| A（丢 PCR-only） | ✅ | ✅ | **无** | 零影响 ✅ |
| B（PMT PCR→0x1FFF） | ✅ | ✅ | **无** | 零影响 ✅ |
| AUTO | ✅ | ✅ | **无** | 零影响 ✅ |

→ `JingcaiTsPipeline.mutates=false` 的直通设计成立：非睛彩频道连 pipeline 都不绑定、不产生任何睛彩日志。

### 3.5 CCTV-1 零影响（✅）

OFF 模式下：`zap=1085 / 1382ms`、`first_frame` 有、`state=3`、`MP2 48k/2ch`、`Fatal=0`、`dropped=0`。

---

## 4. C · 回归（全部通过）

| 项 | 结果 |
|---|---|
| 信息卡 v3 | ✅ **t51 已抓到完整节点**：`info_card [0,630][1920,1080]`（=**300dp**）、`logo [60,705][300,849]`（240×144px=**160×96dp**）、`title 'CCTV-1 综合' [336,728][620,803]`（**同行**）、`number '频道 1 · 央视频道 · MPEG-L2'`、`badges 'FHD' [1821,668]`、`program '新闻联播'`、`tech 'H.264 · MPEG-L2 · SDR'`、`hint '← 频道列表 · → 设置'`、`recent_row [120,918][1800,1014]` |
| 设置二级 | ✅ 一级 **6 入口齐全** + `起播缓冲 稳(500ms)` + `台标与缓存 96 条 / 326 个` + `关于 版本 10185` + 页脚提示 |
| 焦点 F1/F2 | ✅ BACK#1 / BACK#2 均仍为 `PlayerActivity` |
| MP2 | ✅ `audio/mpeg-L2` 2 条 |
| 数字 OSD | ✅ `zap=1155ms` |
| 上一台 229 | ✅ `ZAP 增量=1` |
| 不崩不灰 | ✅ `Fatal signal=0`；`grey/gray/black` 我方 0；`dropped=0` |
| 实验键 / buffer_min / 睛彩模式 | ✅ keys=0；`buffer_min=500`；`jingcai_experiment=off` |

---

## 5. 结论与建议

### 抽屉
- **保留**：残影修复（`key(...)`+`clipToBounds`+固定 16dp Box）、滚动跟随、聚焦行副标题、subList/56dp/first=3/step=2/revealTarget 单泵。
- **回退**：`PerfPolicy.DRAWER_OPEN_WINDOW_ROWS` **18 → 28**。本次 `openTarget=18` 让 jank 明显回退（50th 53→85/89、Draw 2.5→16.5ms、巨帧 3→4），**不划算**；回退后即为 t47 的"3 巨帧 / 50th 53ms"状态，配合已修的残影与滚动，是当前**最佳可交付组合**。
- 建议正式**冻结**抽屉性能线（理由同 t47 报告：单行 12–15ms 组合成本上限）。

### 睛彩
- **诊断链路可交付**：默认 OFF 下 `IQTV-DIAG` / `buffer` / `diag` / `timeline` / `giveup` 齐全，且**默认 OFF 对健康频道零影响**（这是 t49 的核心价值）。
- **A/B 实验不应作为修复方案交付**：本轮未能证明其效果，且失败样本显示 `pcr=none / pcr_pid=-1`（PMT 未声明 PCR），A/B 在该形态下都是**空操作**。
- **下一步（若继续）**：应沿 `bufferMs=0 + currentPosition=0 + bytesRead=8.4MB` 这条线索查**解复用器为何读到了数据却建不出时间轴**：①打印 PMT 完整 PID 表（是否缺 PCR_PID、stream_type 是否非标）②打印首批 TS 包的 PID 分布与 PCR/PTS 是否存在 ③若源确实无 PCR，则应改为**禁用 PCR 校时 / 用 PTS-only 时钟**（Extractor 的 `ignorePcr` / `setTimestampAdjuster` 方向），而不是"改 PCR PID"。
- **故障间歇性**必须纳入判定：建议后续每次实验**同频道重复 3 次取多数**，否则单次结果不可信（本轮已实证同一 B 模式前后两次结果相反）。

### 已知限制
1. 抽屉 jank 数值仍是本次交付的短板（回退 28 后为 3 巨帧 / 50th 53ms）。
2. 睛彩失败**间歇**，20s 观察窗不足以给出统计学结论；本轮结论是"未证明 A/B 有效"，不是"证明 A/B 无效"。
3. `firstByteMs` 为设备 uptime 毫秒（非耗时），报告中勿误读为 50883 秒。
4. 睛彩时间轴实验的**运行时开关只有 SharedPreferences 一处**（设置页「播放与画面 → 睛彩诊断实验」循环切换，顺序 关闭→E1→E2→E3→A→B→AUTO→C→关闭）；`settings global` 无对应键，自动化需读 prefs 校验（格式 `<string name="jingcai_experiment">off</string>`）。

---

## 6.1 t51 补充验收结论（同一包 vc10185，增量证据）

| 补充项 | 结果 |
|---|---|
| B4 AUTO 仅 >60s 激活 | ✅ 行为正确：三频道均未激活，因 `pcr=none`（`offset=none`）闸门不成立 |
| B6 健康频道在 A/B/AUTO 下不受影响 | ✅ CCTV-1 三模式均 firstFrame/state=3，且**无任何睛彩日志** |
| C 信息卡 v3 | ✅ 完整节点（300dp / 160×96dp / 同行 / FHD / 新闻联播 / tech / hint / recent_row） |
| C 设置一级 | ✅ 6 入口齐全 + 稳(500ms) + 版本 10185 |
| 收尾回归 | ✅ MP2 8 条、数字 `zap=633ms`、上一台 +1、BACK#1/#2 未退出、Fatal=0、grey 我方 0、dropped=0、keys=0、`jingcai_experiment=off` |

---

## 6. 交付物

- 本报告：`docs/CI_FINAL_COMBINED_ACCEPT.md`
- 原始数据：`docs/evidence/2026-11-22-t50-combined/`
  - `01-coldstart-cctv1.txt`（健康基线）
  - `02-drawer-round1/2.txt`（含直方图）、`03-framestats.txt`、`03-framestats-top.txt`
  - `04-drawer-semantics.xml`（残影检查）、`05-scroll.txt`、`05b-scroll-down25.xml`
  - `06-jingcai-117-off2.txt`、`06b-jingcai-117-off-giveup.txt`（DIAG/buffer/giveup 三行）
  - `08-A-ch117/118/119.txt`、`08-A-summary.txt`
  - `09-B-ch117/118/119.txt`、`09-B-summary.txt`
  - `10-OFF2-ch117/118/119.txt`、`10-OFF2-summary.txt`（实为 B 模式重测）
  - `13-cctv1-under-a/b/auto.txt`、`13-healthy-under-abc.txt`（健康频道三模式零影响）
  - `14-AUTO-ch117/118/119.txt`、`14-AUTO-summary.txt`（AUTO 未激活证据）
  - `15-infocard-v3-dump.xml`（信息卡 v3 完整节点）
  - `12-regression.txt`、`16-final-regression.txt`
- 复跑脚本：`.acceptance/perf-measure.ps1`、`.acceptance/adb-helpers.ps1`
