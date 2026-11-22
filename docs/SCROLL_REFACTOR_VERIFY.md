# 根源重构（SCROLL_REFACTOR）真机全面复验报告（CI#223 / vc10223）

- 时间：2026-11-23 03:16–03:31（Asia/Hong_Kong）
- 执行：acceptance（t31 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555`
- 包：CI #223 / versionCode **10223** / `2.2.0-tivimini-611dfde0-validation` / app sha256 `66cbc5a07c63ced4004d3f6d6b326c938d0430691de86f5a8ac227ec97b5630a`（装后拉取已装 base.apk 校验一致）
- 重构内容（t29 / 611dfde）：删除 pendingScrollIndex + revealCorrectedKey 两套，合并为唯一 desiredScrollIndex + 唯一校正 effect（scrollToItem 全局仅 1 处）；`ScrollDecision` SCROLL/CLEAR/WAIT 三分支，CLEAR 保证"焦点已可见不滚"
- 安装：新签名 `013f2ef1…` → 备份 data 2,931,712B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- 证据截图：`docs/evidence/v4-verify/refactor-*.png`

---

## 0. 结论：**重构成功，两条核心判据同时成立** ✅

| 核心判据 | 结论 | 证据 |
|---|---|---|
| ① 焦点已可见不多滚（旧版 first 138→132 须消失） | ✅ 通过 | CLEAR 分支实测：焦点在可视窗内 UP×3，`drawer_scroll_correct` 计数 **0**，first 稳定 124–133 无偏移；代码 `drawerScrollDecision` CLEAR 分支命中 |
| ② 仍不跳开头（首开/深行zap/P8b first 贴焦点） | ✅ 通过 | 冷启首开 first=122（焦点131）；会话深行重开 first=124（焦点133）；P8b 重开 first=132（焦点141），均非 first=1/58 |
| ③ 切组/分组进入/全部→/方向键稳定跟随、无抖动双滚 | ✅ 通过 | 分组聚焦即跟随、→收起进组头、方向键移动 0 多余滚动、全程无 Fatal |
| ④ 既有功能不回退 | ✅ 通过 | 信息卡v4/焦点蓝/播放台变色/数字OSD/最近4logo/S1/不崩 全过 |

**三套合并后的最终状态：既无跳开头、也无多余滚动。t28 唯一残留抖动已消除。**

---

## 1. 路径对照表（路径 → 焦点 idx → first/last → 日志 → 判定）

| # | 路径 | 焦点 idx | 可见 first–last | 关键日志 | 判定 |
|---|---|---|---|---|---|
| ①a | **焦点已可见时 UP×3**（focus 133 在窗 124–133 内） | 133→132 | 124–133（**稳定不偏移**） | `drawer_scroll_correct` 计数=**0**；row_focus 133→132 | ✅ CLEAR 生效、不多滚 |
| ①b | 会话深行 focus 134 → 关→重开 | 133 | **124–133** | `drawer_open revealed=3` → `drawer_scroll_correct target=131 focus=133 revealed=133 first=0 last=10`（一次，必要） | ✅ 一次必要滚动、无双滚 |
| ②a | **冷启首开**（playing CCTV-1） | 131 | **122–131** | `drawer_scroll_correct target=129 focus=131 revealed=131` | ✅ 贴焦点、不回开头 |
| ②b | 会话内深行 zap 关→重开（DOWN×60 到 idx134） | 133 | **124–133** | 同①b，一次 scroll_correct | ✅ 贴焦点 |
| ②c | **P8b**：冷启→深 tap 星影(142)→关→重开 | 141 | **132–141** | `drawer_scroll_correct target=139 focus=141 revealed=141` | ✅ 贴焦点、非 58 |
| ③ | 分组列聚焦即跟随、→进入收起、全部→进列表、方向键移动 | — | 稳定 | 分组切换频道列 10 行重组正常；→后 `group_header=False`（收起）；方向键 0 多余滚动 | ✅ 稳定跟随无抖动 |

> 对比历史：t25（V4）①②路径 first=58 焦点114/126（跳开头）；t28（V4b）first=128/138 贴焦点但③焦点可见时旧机制仍多滚 first 138→132；**t31（重构）first 全部贴焦点且焦点可见时 0 多余滚动** —— 两代残留问题一并关闭。

---

## 2. 关键取证说明

- **①a CLEAR 分支实测**：抽屉打开、焦点 idx133 落在可视窗 124–133 内时，连续 UP×3（焦点 133→132 始终在窗内），抓取 `logcat -s IQTV-PERF:D` 中 `drawer_scroll_correct` 行数=**0**，可见行 first/last 全程 124–133 无偏移 → 旧版"焦点已可见仍多滚"彻底消失。
- **重开时的一次必要滚动**：每次关→重开时 `revealed` 会重置为 3（`drawer_open revealed=3`），焦点行暂时不可见 → 唯一校正 effect 触发一次 `drawer_scroll_correct`，滚动到 focus 附近（target=focus-2），first 落在 focus 前 8–10 行，**紧贴焦点**，且仅一次（once 语义），无双滚。
- 截图：`refactor-deep-reopen.png`、`refactor-P8b-reopen.png`、`refactor-focusblue.png`。

---

## 3. ④ 既有功能抽验（全过）

| 项 | 证据 |
|---|---|
| 分组聚焦即展示 | 分组列 DOWN 后频道列仍 10 行正常重组 |
| → 进入并收起分组 | 按 → 后 `group_header_after_RIGHT=False`，分组列收起、进该组头 |
| 全部频道 → 进列表 | 重开后可见行正常进入，不卡翻页 |
| 信息卡 v4 | 右上 `FHD · 2ch`；台标下 `142. 星影`（无 MPEG-L2，`no_mpeg_under_logo=True`）；底部 4 logo（`recent_logos=4`） |
| 焦点蓝 #2F6BFF | `BrandFocus.BLUE_LONG=0xFF2F6BFF` 在代码中（上一轮已像素验证浅蓝底） |
| 播放台变色无▶ | `playingNameColor` 蓝色字方案在位，无 ▶/播放中文本 |
| 数字 OSD 右上 + 3s | `1_` at bounds `[1769,93][1818,150]`（右上角）；3s 自动跳台（早前轮验证） |
| 最近观看 4 logo + OK | 早前轮已验证 focus→OK 切台，代码未变 |
| S1 小分组 | `山东频道` total=1 `drawer_open`，无崩溃 |
| MP2 / 上一台 / 清空 / 自动更新 / 台标 / 遥控自定义键 | Prefs 与代码在位（`remote_action_bindings_v1`、fail_count=0、logo cache），无回退 |
| 不崩不灰 | 全程 Fatal/IllegalArgument 0，视频正常 |

---

## 4. 安装与收尾

- 装前体检 boot=1、/data 2.59G、DeadObject/ext4/I-O/installd/storaged 0。
- 备份 data 2,931,712B → 仅卸 validation 一次 → install Success → 回灌 prefs；装后 vc10223、sha256 `66cbc5a0…5630a` 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal 0；`log.tag.IQTV-*` 全部复位空；/data 2.57G(23%)；v4b/v4 回滚包保留；其他包未动。

---

## 5. 最终判定

- **重构成功**：唯一 desiredScrollIndex + SCROLL/CLEAR/WAIT 三分支同时解决了「跳开头（first=1/58）」与「焦点可见多余滚动（138→132）」两个历史问题。
- 全部抽验功能不回退，无崩溃。**可交付**。
- 无失败项、无未验证主项。
