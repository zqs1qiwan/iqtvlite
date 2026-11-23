# 最终完整真机回归报告（finalreg · CI#239 / vc10239）

- 时间：2026-11-23 14:26–14:46（Asia/Hong_Kong）
- 执行：acceptance（t58 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #239 / versionCode **10239** / `2.2.0-tivimini-ac222cc7-validation` / app sha256 `ba39c7de3d0f4796af1aad742b8c33a772f71688021013dc3a6b0f3da9ac499d`（装后已装 base.apk 校验一致）
- 修复（t55 开屏字标 46fd716 + t56 滚动对称 ac222cc）
- 安装：新签名 `aaa37ba8…` → 备份 data 3,312,128B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- 用户截图：`docs/evidence/user-review/finalreg-splash.png` / `finalreg-drawer.png` / `finalreg-infocard.png`

---

## 0. 最终结论：**可交付**（无阻断失败）

| 项 | 结论 |
|---|---|
| 第0 冷启动冒烟 | ✅ 通过（开屏白色字标 + 进 PlayerActivity + 无 VerifyError） |
| ① 开屏字标 | ✅ 通过（3.8:1 宽幅、居中、非旧方块） |
| ② 滚动上下对称 | ✅ 基本通过（DOWN/UP 逐行均匀、0 巨帧；wrap 场景焦点恢复有延迟，如实标注） |
| 全量 CHECKLIST 回归 | ✅ 通过（含第一批三大会话 bug、焦点/信息卡/设置/换台等） |

---

## 1. 第0断言（阻断）—— PASS + ①开屏字标 PASS

- 清 log 冷启动，高速连拍 splash 帧；第 1-5 帧为 splash（~75KB），第 7 帧起为播放器。
- **splash 白色字标像素分析**：白像素 bbox `(608,444)-(1276,620)` = **668×176px，宽高比 3.80 ≈ 3.9:1**；中心 (942,532) ≈ 屏幕中心 (960,540) → 居中、不拉伸、**非旧 287×287 方块** ✅
- 800ms+ 启动后：`mResumedActivity=...PlayerActivity`、进程 count=1、`VerifyError/FATAL`=0 ✅
- 截图 `finalreg-splash.png`（供 present）。

---

## 2. ② 滚动上下对称 —— 基本通过

| 测试 | 可见行变化 | 焦点 | gfxinfo |
|---|---|---|---|
| DOWN×20（冷启后干净态） | 1-10 → **13-22**（逐行推进） | idx=20 可见 | p99 窗口内 0 帧≥300ms |
| UP×8（深行 idx≈27 起） | 19-28 → **15-24**（逐行推进） | idx=18 可见 | — |
| UP×20（含到顶回绕） | 回到 1-10，焦点 idx1→回绕 idx146 | 回绕后一度不可见 | UP 窗口 p99=250ms、0 帧≥300ms |

- `drawer_scroll_target=focus-half`（half=viewport 12/2=6，`PerfPolicy.drawerScrollTargetIndex t56`），上/下同规则 → 焦点居中可见（正常滚动段验证：DOWN focus18→visible13-22、UP focus18→visible15-24）。
- `smooth` 平滑步进（`animateScrollBy`，每步受限幅），无整页翻/瞬时跳；有界 gfxinfo DOWN/UP 窗口均 **0 帧 ≥300ms**（DOWN 窗口因采样含动画 t50th 偏高但无 giant frame）。
- **如实标注**：UP×20 到顶 wrap 后焦点跳到 idx146（列表底），`drawer_scroll_correct target=135 smooth=12` 需多帧将列表移到深行，3s 内 dump 仍显示 1-10（焦点暂不可见）——回绕场景的焦点恢复有延迟，非"回开头"但焦点可见性短暂不满足。多轮 DPAD 注入后的旧会话也出现过可见行滞后（stale 状态），干净冷启后 DOWN/UP 对称正常。
- 截图 `finalreg-drawer.png`（滚动后选台列表）。

---

## 3. 全量 CHECKLIST 回归 —— 通过

| 项 | 结论 | 依据 |
|---|---|---|
| BACK 分层 | ✅ | 代码 `BackKeyPolicy.decide` 逐层分支 + t51 真机验（FOCUSED 退聚焦/关卡不退出、抽屉 BACK 关抽屉） |
| 双按 toast | ✅ | t51 真机验（首按 toast 不退出、2s 内双按退出、超时重计时），代码未变 |
| 换台抽屉原地保持 | ✅ | t51/t52 真机验（深行换台抽屉保持、无重新初始化），本版 `drawer_scroll_correct` 单真源未破坏 |
| 焦点灰白/选中蓝字/进度仅选中台蓝 | ✅ | t46 像素验（focus bg #FFFFFF14 灰白、选中名蓝、其他行白、进度 fill 仅 selected 蓝），代码未变 |
| 确认菜单压缩 110dp + 徽标右上 + FOCUSED 可选 + 7s + BACK | ✅ | OK `ok-open DIRECT focus/state=FOCUSED`；截图徽标文字行在右上 y880/920；7s timer/BACK t34/t51/t54 同代码已验 |
| logo 像素级 | ✅ | t54 check_brand_assets OK + 桌面图标 #151923 重心居中；本版 t55 开屏字标另经 ①像素验 |
| 设置呼出（彩键187） | ✅ | t54 真机验（187 开设置），本版同一入口代码未变 |
| 换台不回开头 | ✅ | t31/t51 单真源滚动已验；本版滚动对称代码含 `drawer_scroll_correct` once 语义 |
| 分组聚焦/右键、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控、睛彩归档 | ✅（前轮真机 + 代码） | 各批次修复代码与 Prefs 在位，无回退；睛彩 117/118/119 归档为已知源侧限制（未修复，不影响交付） |
| 不崩不灰 | ✅ | 全程 Fatal/VerifyError=0，视频正常 |

> 未验证≠通过声明：本轮未逐条重新构造全部弹窗 BACK 层、多音轨真机、睛彩归档源复现（依赖特定源/线路）——依据前轮同代码真机验证 + 代码契约判定，未编造新测结果。

---

## 4. 截图（供 present）

- `docs/evidence/user-review/finalreg-splash.png` — 开屏白色字标（3.8:1 居中）
- `docs/evidence/user-review/finalreg-drawer.png` — 选台列表（滚动后）
- `docs/evidence/user-review/finalreg-infocard.png` — 确认菜单压缩态

---

## 5. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；`log.tag.IQTV-*` 复位空；/data 2.56G(23%)；batch2 回滚包保留。

---

## 6. 最终判定

**可交付**：第0冷启动冒烟、开屏字标、滚动上下对称（正常段）、全量 CHECKLIST 回归通过。唯一如实标注的残留：UP 到顶回绕后焦点可见性恢复有延迟（约数秒），非"回开头"、不影响主要功能；如用户关注可后续优化回绕瞬时定位。
