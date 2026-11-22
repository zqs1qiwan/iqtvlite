# V4 真机验收报告（CI#220 / vc10220）· TR100-G9

- 时间：2026-11-23 02:11–02:32（Asia/Hong_Kong）
- 执行：acceptance（t25 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 / 1080p / 240dpi
- 包：CI run **#220** / versionCode **10220** / `2.2.0-tivimini-25f5eb96-validation` / app sha256 `c49c4d58ef8b89c08bcb4ee9571aacf464858cf8c4c58ffbb6c8b504aa88db75`
- 新签名 `0b11c667…` → 协议处理：备份 data 2,899,968B → 仅卸 validation 一次 → 装 V4 → 回灌 data（prefs 完整恢复）；装后 sha256 与 CI 一致；**force-stop 0 次**
- 证据截图：`docs/evidence/v4-verify/`（9 张）
- 调试开关：`IQTV-PERF/INFOCARD/RECENT/DIGITS/ZAP=DEBUG`（收尾全部复位空）

---

## 0. 结论总表

| 项 | 结论 | 一句话依据 |
|---|---|---|
| ① 当前频道打开不闪+即高亮 | ✅ 通过 | 打开即 `row_focus idx=7`（当前 CCTV-7），可见 1–10 含该行；新增 `drawer_scroll_correct target=4` 落地 |
| ② 分组聚焦即跟随+→进入收起从组头 | ✅ 通过 | 分组列 DOWN×2 不点 OK，频道列即变 20–29（卫视）；→ 后分组收起、焦点 idx=20 |
| ③ 全部频道按→能进列表 | ✅ 通过 | 从全部按 →，分组头消失、可见 1–10、焦点 idx=1，不再卡在翻页 |
| ④ 换台不回开头（12 路径 + 冷启 + P8b） | ⚠️ **主 bug 未复现但严格判据未全达标** | 全程**未出现 first=1**（原 bug 消除）；但部分路径 first 未紧贴焦点（P8b first=58 vs 焦点126），详见 §4 |
| ⑤ 焦点蓝 + 播放台变色无▶ | ✅ 通过 | 焦点行像素 `(40,51,71)` 偏蓝、其余 `(48,50,52)` 中性；代码 playing 用蓝色字、无 ▶/播放中文字 |
| ⑥ 信息卡 v4 | ✅ 通过 | 右上 `FHD·2ch` 单行紧凑；台标下 `1. CCTV-1 综合` + `其他频道`（无 MPEG-L2）；右侧节目/进度；底部 4 logo；高约 223dp ≤240dp |
| ⑦ 最近观看 4 logo | ✅ 通过 | 4 张卡；focus idx=0→RIGHT→idx=1→OK `switch idx=1 group=114 密云电视台`，实际切台生效 |
| ⑧ 数字 OSD 右上 + 3s | ✅ 通过 | OSD `21_` at bounds [1742,93][1818,150]（右上）；3s 后自动跳台；确认态蓝代码在位（180ms 蓝字） |
| ⑨ 回归 | ✅ 通过 | S1 1 行组不崩、自动更新 fail=0、台标/自定义键模型在位、Fatal=0、不灰 |

---

## 1. 安装与交付态

- 装前体检：boot=1、uptime=35778s、bootreason=`reboot,adb`、/data 2.59G、DeadObject/ext4/I-O/installd/storaged 日志 0。
- 备份 data `validation-data-vc10213.tar` 2,899,968B（exec-out 核字节一致）→ 仅卸 validation 一次 → `install` Success → 回灌 data。
- 装后：`versionCode=10220`、`versionName=2.2.0-tivimini-25f5eb96-validation`；已装 base.apk sha256 `c49c4d58…db75` 与 CI 一致。
- 收尾：前台 `tv.iqtvlite.app.validation/...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/IllegalArgument 0；/data 2.59G(23%)；其他包未动。

---

## 2. ①③ 打开与进入列表

- **①** 当前 CCTV-7 打开抽屉：`drawer_compose target=34`、`drawer_open ... group=全部`，`drawer_scroll_correct target=4 revealed=5`（V4 新增修正行），随后 `row_focus idx=7`（当前台）；可见行 1–10 含 idx7 → 打开即高亮，无"闪到开头又回当前台"。
- **③** 从「全部频道」组按 →：分组头消失（`group_header_after=False`）、可见 1–10、`row_focus idx=1` → 能进入列表。
- 截图 `01-open.png`、`03-allgroup-enter.png`。

---

## 3. ② 分组聚焦语义

- 分组列（12 组：全部/央视/卫视/其他/体育/北京/地方/山东/影视/棋牌/港澳台/电竞）。
- **聚焦即跟随**：分组列 DOWN×2（不点 OK）→ 频道列立即变 `20. 湖南卫视4K … 29. 辽宁卫视`（卫视频道），日志 `drawer_open ... group=卫视频道` ✅
- **→ 进入并收起**：按 → 后 `groups_still_visible=False`（分组列收起），焦点 `row_focus idx=20`（该组第一台）✅
- **切换不卡**：多次切组均正常重组（`drawer_compose frames=11/13`，无异常巨帧/ANR）。
- 截图 `02-group-enter.png`。

---

## 4. ④ 换台不回开头（重点，诚实记录）

V4 新增 `drawer_scroll_correct target=.. revealed=..` 机制（`PlayerDrawer.kt`），在分帧揭示覆盖目标后补一次滚动校正。

| 路径 | 操作 | 可见行 first/last | 焦点 idx | scroll_correct | first=1? | first 贴焦点? |
|---|---|---|---|---|---|---|
| 会话内深行 zap | 全部组 DOWN 到 idx117 → tap 114 → 关→重开 | 58–67 | 114 | 无（revealed=141 已足够） | 否 | ❌ 不贴（差约 50） |
| **冷启首开** | 冷启→首开（playing 密云/美人 idx114/126） | **104–113** | ~111 | `target=111 revealed=113` | 否 | ✅ 贴焦点 |
| **P8b** 冷启→zap→重开 | 冷启首开→DOWN 到 idx125→tap 126→关→重开 | 58–67 | 126 | 无（revealed=141） | 否 | ❌ 不贴 |
| 全部组最终复测 | 切回全部→focus idx61→tap→关→重开 | 54–63 | 61 | `target=18 revealed=19` | 否 | ✅ 贴焦点 |

**判定**：
- **原始 bug（first=1 / 焦点可见区脱节到首行）在 V4 全程未复现** —— 冷启首开从上一版的 first=1 变为 first=104（贴焦点），证明 `drawer_scroll_correct` 修复生效。
- **但用户给的新硬判据「first 必须贴焦点 idx」未全部达标**：在「抽屉保持挂载、revealed 已很高（141）时关→重开」的路径（会话内深行 zap、P8b），列表停在 58–67 而焦点在 114/126，`scroll_correct` 未触发（因为 revealed 已 > target，补偿逻辑只在 revealed<target 时触发），造成焦点在可见区之外。这不像原来的"回开头"，但焦点不可见仍是体验问题。
- 建议开发：`drawer_scroll_correct` 的触发条件除了 `revealed < target`，也应在"打开后焦点行不在 visibleItemsInfo"时补一次 `scrollToItem(focusIdx - lookahead)`，覆盖 revealed 已满但滚动位置陈旧的场景。
- 截图 `04-cold-firstopen.png`。

---

## 5. ⑤⑥⑦⑧ 细节

- **⑤ 焦点蓝/播放台变色**：焦点行 y110 像素 `(40,51,71)`（蓝占优）vs 非焦点 `(48,50,52)`（中性）→ 浅蓝底 #2F6BFF22 生效；代码 `BrandFocus.BLUE=0xFF2F6BFF`、`playingNameColor→BrandFocus.BlueColor`、无 ▶/播放中文本（dump `contains_播放中=False`、`contains_▶=False`）。截图 `05-focus-blue.png`。
- **⑥ 信息卡 v4**：CCTV-1 显示 `1. CCTV-1 综合` + 分组小字 `其他频道`（无 MPEG-L2）；右上 `FHD · 2ch` 单行；右侧节目 `晚间新闻`；技术行 `H.264 · MPEG-L2 · SDR`；底部 4 个最近观看 logo（代码：无 logo 时首字/序号回退，符合设计）；卡片 y≈686–1020 ≈223dp ≤240dp。截图 `06-*.png`。
- **⑦ 最近观看 4 logo**：`focus index=0` → RIGHT `move index=1` → OK `switch idx=1 group=114 密云电视台` → 实际切台成功（蓝底焦点代码在 `ChannelInfoBanner.kt`）。
- **⑧ 数字 OSD**：输入 `21` 出现 `请输入频道号` + `21_`，bounds `[1742,93][1818,150]`（右上角）；3s 后自动跳台（频道变化验证）；确认态蓝代码 `digitConfirmed→BrandFocus.BlueColor` + 180ms 保留。截图 `08*.png`。

---

## 6. ⑨ 回归

- S1 小分组：`山东频道` 1 行 `drawer_open total=1`，无崩溃，可见行 147（1 行）。
- 自动更新：`playlist_fail_count=0`/`epg_fail_count=0`，`playlist_last_success_at`/`last_epg_update_time` 有值。
- 台标缓存：台标正常显示；遥控自定义键：`remote_action_bindings_v1` 新模型在位，fallback 派发代码 `PlayerActivity.kt:578-588` 保留（上一轮 B1 已真机验证）。
- 不崩不灰：全程 Fatal/IllegalArgument 0，视频正常，无灰屏。

---

## 7. 结论与建议

1. **用户原报告的「点台换台列表跳到开头（first=1）」在 V4 已修复**（冷启首开 first=104 贴焦点，`drawer_scroll_correct` 生效）。
2. **但新硬判据「每条路径 first 必须贴焦点」未全达标**：抽屉保持挂载 + revealed 已满的关→重开路径存在焦点不可见（first≈58 vs 焦点 114/126）。如实判该项 ⚠️ 部分通过。
3. 其余①②③⑤⑥⑦⑧⑨全部通过。
4. 建议（供开发）：扩展 `drawer_scroll_correct` 触发条件，覆盖"焦点行不在 visibleItemsInfo"的陈旧滚动场景。
