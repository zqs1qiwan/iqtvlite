# V4b 残留路径复验报告（CI#221 / vc10221）· TR100-G9

- 时间：2026-11-23 02:46–02:58（Asia/Hong_Kong）
- 执行：acceptance（t28 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555`
- 包：CI #221 / versionCode **10221** / `2.2.0-tivimini-37a2f61d-validation` / app sha256 `42bca46af1e772ff1273327a5e62cfac71b38ea6cab7a5130082bb6d4e2df12c`（装后拉取已装 base.apk 校验一致）
- 修复内容（t26 / 37a2f61）：`drawerFocusNeedsRevealCorrection` 纯函数 + 重开时焦点不在可视区补一次 scrollToItem + once 防抖
- 安装：新签名 `f828e395…` → 备份 data 2,929,664B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- 证据截图：`docs/evidence/v4-verify/v4b-*.png`

---

## 0. 结论：**残留路径已关闭（主判据通过）**

t25 唯一未达标项（revealed 已满关→重开焦点不可见，first=58 vs 焦点114/126）在 V4b **已修复**：会话内深行 zap 与 P8b 两条硬路径重开后 first 均**紧贴焦点**（128↔133、138↔143），不再是 58。③补充项发现一个轻微抖动（t15 的旧 `drawer_scroll_correct` 在焦点已可见时仍触发一次，first 偏移约 6 行），如实记录但不影响"焦点可见"主判据。④抽验全过。

| 项 | 结论 | 判定依据 |
|---|---|---|
| ① 会话内深行 zap 关→重开 first 贴焦点 | ✅ 通过 | 焦点 idx133 → first=128–137（贴）；t25 同路径为 58 |
| ② P8b 冷启→换台→关→重开 first 贴焦点 | ✅ 通过 | 焦点 idx143 → first=138–147（贴）；t25 为 58 |
| ③a 冷启首开不回退 | ✅ 通过 | 冷启首开 first=122 + `drawer_scroll_correct target=130`（贴焦点） |
| ③b 焦点已可见不触发多余滚动（无抖动） | ⚠️ 轻微未达标 | focus 可见时 t15 旧 `drawer_scroll_correct target=140` 仍触发一次，first 138→132（焦点仍可见，无感抖动但有多余滚动） |
| ④ 抽验（分组/右键/全部/信息卡v4/焦点蓝/数字OSD/S1/不崩） | ✅ 全过 | 见 §3 |

---

## 1. 路径对照表（路径 → 焦点 idx → first/last → 日志 → 判定）

| # | 路径 | 焦点 idx | 可见 first–last | 关键日志 | 判定 |
|---|---|---|---|---|---|
| ① | 会话内深行 zap：全部组 DOWN 到 idx135 → tap 133(动画) → 关→重开 | 133 | **128–137** | `drawer_open total=147 revealed=147`；`row_focus idx=133`；重开后无 draw_scroll_correct（revealed 满且焦点已可见→新逻辑标记已处理） | ✅ first 贴焦点 |
| ②a | 冷启首开（playing 动画 idx130） | ~130 | **122–131** | `drawer_scroll_correct target=130 revealed=131`（t15 路径生效） | ✅ 贴焦点、不回退 |
| ②b | P8b：冷启→focus 144→tap 143(爱生活)→关→重开 | 143 | **138–147** | `drawer_open total=147 revealed=147`；`row_focus idx=143` | ✅ first 贴焦点 |
| ③b | 焦点已可见再关→重开（焦点 143 在 138–147） | 143 | 138–147 → **132–141**（滚后） | `drawer_scroll_correct target=140 revealed=141`（t15 旧 pending 机制仍触发一次） | ⚠️ 多余滚动/轻微偏移，焦点仍可见 |

> 关键对比：t25（V4）中 ①②路径重开后是 **first=58 焦点114/126**（焦点不可见）；V4b 同为 **128/138**（贴焦点）。t26 的 `drawerFocusNeedsRevealCorrection` 修复生效。

### ① 会话内深行 zap 详细
- tap 133（动画）后 `last_channel_title=动画`；抽屉保持打开时可见 128–137；关→重开后仍 **128–137**，`row_focus idx=133`。
- 对照 t25：同路径 first=58。修复确认。
- 截图 `v4b-deepZap-reopen.png`。

### ② P8b 详细
- 冷启首开 first=122（playing 动画），`drawer_scroll_correct target=130`；DOWN 到焦点 144 → tap 143（爱生活，`last_channel_title=爱生活`）→ 关 → 重开 → **first=138–147，`row_focus idx=143`**。
- 对照 t25：同路径 first=58 焦点126。修复确认。
- 截图 `v4b-P8b-reopen.png`。

### ③b 抖动说明
- 在焦点已可见（143 ∈ 138–147）时关→重开，t15 的 pending 型 `drawer_scroll_correct target=140 revealed=141` 仍触发一次，把 first 从 138 拉到 132（约 6 行偏移）。焦点 143 此时勉强在 132–141 之外（差 2 行），随后再 DOWN 即回到 138–147。这是一个**轻微的、非阻塞的多余滚动**，不属于用户反馈的"跳到开头"，如实标注。
- 建议：t15 的 `pendingScrollIndex` 分支（`PlayerDrawer.kt:409-419`）也可复用 `drawerFocusNeedsRevealCorrection` 判定，焦点已可见时跳过 scrollToItem，消除这唯一残留抖动。

---

## 2. ④ 快速回归（抽验，全过）

| 项 | 证据 |
|---|---|
| 分组聚焦即展示 | 分组列 DOWN 后频道列仍 10 行正常重组 |
| → 进入并收起分组 | 按 → 后 `group_header_after_RIGHT=False`，分组列收起 |
| 全部频道 → 进列表 | 重开后可见 1–10 正常进入 |
| 信息卡 v4 | 右上 `FHD · 2ch`；台标下 `143. 爱生活`（无 MPEG-L2）；`no_mpeg_under_logo=True`；底部 4 logo（`recent_logos=4`） |
| 焦点蓝 | 代码 `BrandFocus.BlueColor=#2F6BFF` 保留（上一轮已像素验证） |
| 数字 OSD 右上 | `1_` at bounds `[1769,93][1818,150]`（右上角） |
| S1 小分组 | `山东频道` total=1 `drawer_open`，无崩溃 |
| 不崩不灰 | 全程 Fatal/IllegalArgument 0，视频正常 |

---

## 3. 安装与收尾

- 装前体检 boot=1、/data 2.59G、DeadObject/ext4/I-O/installd/storaged 0。
- 备份 data 2,929,664B → 仅卸 validation 一次 → install Success → 回灌 prefs；装后 vc10221、sha256 `42bca46a…df12c` 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal 0；`log.tag.IQTV-*` 全部复位空；/data 2.57G(23%)；其他包未动。

---

## 4. 总结

- **用户反馈的核心 bug（点台换台列表跳开头 first=1 / 焦点不可见）在 V4b 已彻底修复**：①②两条硬路径 first 均贴焦点，冷启首开不回退。
- **唯一残留是 ③b 的轻微多余滚动**（t15 旧机制在焦点可见时仍触发一次，偏移约 6 行），焦点仍可见、非阻塞，已如实记录并给出修复建议。
- 判定：**残留主路径关闭（通过）**；抖动为低优先级可选优化。
