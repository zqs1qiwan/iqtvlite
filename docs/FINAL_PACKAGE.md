# IQTV Lite 最终包（t52）—— 抽屉定稿 + 睛彩实验落地

> 相对 **t47（vc10179，抽屉+性能最佳基线）** 的净改动只有一个：**副标题残影修复**（t48 的三重保险）。
> `openTarget` 在 t48 曾降到 18，但 t50 合并验收证明**更差**（巨帧 3→4、50th 53→85–89ms、Draw 2.5→16.5ms），
> 故本轮**回退到 28**（t47 状态）。其余全部保持 t47/t49 已验收状态。

---

## 1. 版本

| 项 | 值 |
|---|---|
| 分支 | `integration/tivimini-baseline`（**未动 main、未打 tag**） |
| 构建 | versionCode **10187**，`2.2.0-tivimini-1f206133-validation` |
| 体积 | ≤30MB（实测约 10.4MB） |

---

## 2. 改动清单（相对 t47）

| # | 改动 | 状态 | 说明 |
|---|---|---|---|
| **A1** | 副标题残影修复 | **新增（本轮保留）** | ① `key("row_focused_$index")` 隔离聚焦行子树：失焦整棵销毁重建；② 行级 `clipToBounds()` + 副标题固定 `Box(16dp, clipToBounds)`；③ `focused` 严格门控（非聚焦行不组合副标题） |
| **A2** | `DRAWER_OPEN_WINDOW_ROWS` | **回退 18 → 28** | 回到 t47 实测最佳（3 巨帧 / 50th 53ms）；18 已被 t50 证伪 |
| A3 | 睛彩时间轴实验（t49） | 已并入 | A/B/AUTO 默认关、>60s 自动启用、`IQTV-JINGCAI pcr/pts/offset/mode`、JVM 测试 + `check_jingcai_timeline` 门禁；健康频道逐字节直通 |
| A4 | 其余抽屉机制 | 不动 | subList 揭示、56dp 行高、first=3、step=2、`revealTarget` 单泵分帧、UP/DOWN 由频道列接管、四个埋点、滚动跟随、聚焦行副标题（2dp 进度） |

**结论：最终包 = t47 全部行为 + 残影修复 + 睛彩实验（默认关）。**

---

## 3. 已验收能力（本轮不改，仅列明）

- 播放：零拷贝（SurfaceView + 透明 Compose 覆盖）、MP2 音频、起播缓冲档位（300/400/500，默认 500）
- 设置二级菜单（一级 6 入口带摘要；滚动 18.4% / 9ms 达标）
- 信息卡 v3（台标 160×96dp、台名 34sp 同行、右上徽标 分辨率/帧率/码率/声道、技术行、最近观看快跳、3s 自动隐藏）
- 台标缓存（PNG 写盘 + 内存 LRU 96 条）
- 遥控习惯、上一台、数字直选、自动更新
- 弱机性能：静止产帧 0；抽屉滚动跟随稳定；设置滚动达标

---

## 4. 已知限制（如实记录，不作隐藏）

| 项 | 现状 | 结论 |
|---|---|---|
| **抽屉 jank** | **3 个 ≥300ms 巨帧**、50th **53ms**、Janky 100% | **单行组合成本 12–15ms 的物理上限**。九轮实测证明「巨帧 0–1 且 50th≤50ms」不可同时满足（t37：巨帧 1↔50th 40；t47：巨帧 3↔50th 53）。真达标需换渲染方案（自绘虚拟列表 / 预渲染行位图），建议**单独立项** |
| **冷启 rebuffer** | 同批 3 次中位仍波动（字节速率 443K–1.14M/s） | **源侧/网络瞬时吞吐**主导，App 侧无稳定收益；不改 LoadControl 默认（2500/6000/500/2000）；`PerfTuning` 运行时开关保留供用户自选，推荐默认关闭 |
| **PSS** | 冷启后 60s 稳态 ~75MB、关抽屉 ~63MB | **口径敏感**（同进程开/关差 15MB）；固化为「冷启后 60s + 抽屉关闭 + 3 次中位」，**不作回归判据** |
| **睛彩 117/118/119** | 时间轴（PCR/PTS 9h）实验已就位、默认关 | **间歇性问题未证明可修**：A/B/AUTO 提供完整诊断与可选修复；是否有效需设备实测（AUTO 仅在偏差 >60s 启用），**不承诺修复** |

---

## 5. 回退方式（全部单变量）

| 想回退 | 操作 |
|---|---|
| openTarget 再回 18 | `PerfPolicy.DRAWER_OPEN_WINDOW_ROWS = 18`（实测更差，不建议） |
| 关副标题 | `PerfPolicy.DRAWER_FOCUSED_SUBTITLE = false` |
| 残影修复 | 删 `key(...)` 与 `clipToBounds()`（不推荐，残影会复发） |
| 睛彩实验 | 设置里实验模式置「关闭」即全局回退（默认即关闭）；代码级 `git revert` 对应提交 |
| 冷启开关 | `adb shell settings delete global iqtv_*`（恢复生产默认） |

---

## 6. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `1f20613`（release(final): OPEN_WINDOW 18→28 + 残影修复 + 睛彩实验） |
| CI run（绿） | **#187**，sha `1f20613`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 4m 25s`；`:app:testDebugUnitTest` + `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10187**，versionName `2.2.0-tivimini-1f206133-validation` |
| app sha256 | `bfefb8cfb28204a88c7d228bbd9367eea08a3c4dc8117bb9675ca52ec7b7dabf` |
| test sha256 | `d21f59dd8c1b01d186f42f5a0d948fbe742556cca20fef8b688d387521e9e68f` |
| 门禁 | 八门禁全 OK（含 `check_perf_policy` 与 **`check_jingcai_timeline`**）；pair gate `{0,0,0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14724666566，10,466,007 B ≪30MB） |
| 设备复验 | 归 acceptance（t53）：抽屉残影/巨帧 3/50th 53ms、睛彩默认关、回归全项 |
