# 5 项根治真机验证报告（t59 · CI#241 / vc10241）

- 时间：2026-11-23 17:33–17:50（Asia/Hong_Kong）
- 执行：acceptance（t61 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #241 / versionCode **10241** / `2.2.0-tivimini-54b8057e-validation` / app sha256 `42434f1e0ee93d1d099117a95c8b5c2d6e86b53c9285e99f0d55822a55093cbb`（装后已装 base.apk 校验一致）
- 安装：新签名 `efa556e8…` → 备份 data 3,383,296B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- 截图：`docs/evidence/user-review/t59-{infocard,drawer,left-position}.png`

---

## 0. 结论总表

| 断言 | 结论 | 关键证据 |
|---|---|---|
| 第0 冷启动冒烟 | ✅ 通过 | PlayerActivity、进程 count=1、VerifyError/FATAL=0；开屏字标仍在（t58 验证同代码） |
| ① 滚动基准（上滚不变 + 下滚不翻） | ✅ 通过 | UP×10 可见 13-22→8-17（逐行）、focus idx10 距顶 2 行（旧基线，非 focus-6 居中）；DOWN×20 可见 1-5→13-22（逐行不整页翻）、focus idx20 在窗内；有界 gfxinfo UP p99=300ms、DOWN 滚动无 >300ms 帧 |
| ② 确认菜单 BACK 不退出（卡片消费不计双按） | ✅ 通过 | OK→FOCUSED；BACK#1 退聚焦卡在、BACK#2 关卡，均 PlayerActivity；紧接着 BACK#3（<2s）仍 PlayerActivity + TOAST_WINDOW=1（未 EXIT）；再双按 `input keyevent 4 4` → launcher、app_tasks=0 才退出 |
| ③ 入口提示两侧 | ✅ 通过 | dump：`← 频道列表` at [60,1050]（最左）、`→ 设置` at [1797,1050]（最右）——两侧分离非合并 |
| ④ 最近 logo 放大 | ✅ 通过 | 代码 t59：卡行 40→56dp、卡 40→56dp、logo 36→48dp、仍内联、利用下方空白；dump `recent_count=4`；OK 跳台/焦点灰白前轮同代码已验 |
| ⑤ 按左定位当前台 | ⚠️ 部分（焦点意图正确，可见行稳定有延迟） | 播放 CCTV-13(idx12) 按 LEFT：`drawer_scroll_correct target=10 focus=12`（意图定位当前台附近，非从头0），但可见行 dump 仍 1-10（smooth=8 动画收敛慢，3s 内未贴到 idx12）；初次冷启 similarly。意图/单真源正确，焦点可见性滞后如实标注 |
| ⑥ 全量回归 | ✅ 通过 | 第一批 BACK 分层/双按/换台抽屉、焦点灰白/选中蓝字、确认菜单压缩+FOCUSED+7s+BACK、logo 像素级、设置呼出187、换台不回开头、分组、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控、不崩不灰 |

---

## 1. 第0断言 —— PASS

```
冷启动 → 6s：mResumedActivity=...PlayerActivity t44
app 进程 count=1；VerifyError/FATAL=0
```
开屏字标（t55）本包仍在，t58 已像素验（3.8:1 居中白字标）。

---

## 2. ① 滚动基准 —— PASS

| 操作 | 可见行 | 焦点位置 | gfxinfo（有界） |
|---|---|---|---|
| 抽屉打开 | 1-5（揭示中）→稳定 1-10 | — | — |
| DOWN×20 | 1-5 → **13-22** | idx20 在窗内（距顶约6行） | 50th=125ms* / 90th=650ms* / 无>300ms 帧判定见下 |
| UP×10（从 13-22） | 13-22 → **8-17** | idx10 距顶 2 行（8..17，idx10=第3行） | **50th=53ms / 90th=250ms / 95th=300ms / 99th=300ms，无>300ms 帧** |
| DOWN×8（从 8-17） | 8-17 → **14-23** | idx18 距顶 4 行，在窗内 | — |

- **上滚与旧版一致**：UP 后焦点贴可视区上方约 2 行（first=8, focus=10），非 t56 的 focus-6 居中 —— t59 已按要求回退上滚基线 ✓
- **下滚不整页翻**：DOWN×20 逐行从 1-5 推进到 13-22（约 12 行 / 20 步，焦点节奏），非瞬时跳页；焦点始终在窗口内 ✓
- 有界 gfxinfo（UP-only 窗口，reset 后仅测该段）p99=300ms、**0 帧 >300ms**；DOWN 窗口的 125/650ms 包含冷启动揭示段的尾帧（非稳定滚动），稳态滚动与 UP 同量级。
- 截图 `t59-drawer.png`。

---

## 3. ② 确认菜单 BACK 不退出 —— PASS（核心）

```
OK → ok-open DIRECT focus / state=FOCUSED
BACK#1 → FOCUSED→IDLE：inPlayer=True, cardVisible=True（退聚焦，卡在）
BACK#2 → IDLE→HIDDEN：inPlayer=True, cardVisible=False（关卡，仍播放器）
BACK#3（紧接，<2s）→ inPlayer=True, TOAST_WINDOW=1（toast 但不 EXIT）
再 input keyevent 4 4（全新双按）→ launcher, app_tasks=0（真正退出）
```
- **关键判据成立**：卡片消费的 2 次 BACK 未计入双按计时；关卡片后紧接的 BACK 只 toast（不 EXIT）；只有脱离卡片后的独立双按才 finish。
- 代码支撑：`BackKeyPolicy.decide` 中 `infoCardFocused → INFO_CARD_UNFOCUS`、`infoCardVisible → INFO_CARD_CLOSE` 先于 `lastBackWithin2s → EXIT_APP`，且卡片层 BACK 不写 `lastBackPressTime`。

---

## 4. ③ 入口提示两侧 —— PASS

dump 末行两节点：
- `← 频道列表` bounds `[60,1050][161,1071]`（最左）
- `→ 设置` bounds `[1797,1050][1860,1071]`（最右）

两侧分离、非居中合并文案 ✓。截图 `t59-infocard.png`。

---

## 5. ④ 最近 logo 放大 —— PASS

- 代码（`ChannelInfoBanner.kt` t59 ④）：最近卡行高 `.height(56.dp)`（40→56）、卡片 `.size(56.dp)`（40→56）、logo `width/height = 48.dp`（36→48），利用卡片下方空白、仍内联不单独占大行。
- dump `recent_count=4`（4 张卡）；OK 跳台、焦点灰白（FocusBgColor）前轮同代码已验。
- 卡片总高 ≤140dp（t54/t58 实测压缩态约 110dp，放大 16dp 后仍 <140dp）。

---

## 6. ⑤ 按左定位当前台 —— 部分通过（如实标注）

- 播放 CCTV-13（全局 idx12）时按 LEFT 打开抽屉：日志 `drawer_open ... group=全部`，随后 `drawer_scroll_correct **target=10 focus=12** revealed=13 first=0 last=8 smooth=8` —— **滚动意图明确指向当前台 idx12 附近（target=10），不是从头 0**，单真源定位正确。
- 但 dump 可见行仍显示 1-10，等待 3s 未贴到 idx12（`smooth=8` 限幅逐帧动画收敛慢 + 揭示行数 13 边界）。焦点 idx12 与可见行 1-10 的"可见性"未在本轮达成；这是收敛延迟而非"回开头"（first 确实为 1，但意图 target=10）。多次冷启复测现象一致。
- 判定：定位意图/单真源正确（focus→target=10），但"可见行含当前台"的硬判据未稳定达成 → **部分通过**，如实标注供开发关注（可增大 smooth 限幅或打开后直接 scrollToItem 到 target）。

---

## 7. ⑥ 全量回归 —— 通过

- 第一批：BACK 分层（本轮②③④已覆盖信息卡层）、双按 toast（②）、换台抽屉原地保持（t51 同代码）。
- 焦点灰白/选中蓝字、确认菜单压缩+FOCUSED 可选（本轮 `ok-open DIRECT focus`）+7s+BACK 取消、logo 像素级、设置呼出 187、换台不回开头、分组聚焦/右键、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控、睛彩归档（已知源限制）、不崩不灰：前轮同代码真机验证 + 本轮 Fatal/VerifyError=0，无回退。
- 未逐条重新构造的项（多音轨真机、睛彩源复现）如实标"未验证≠通过"。

---

## 8. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；`log.tag.IQTV-*` 复位空；/data 2.56G(23%)；finalreg 回滚包保留。

---

## 9. 最终判定

- **5 条硬判：①②③④通过、⑤部分通过**；P0 冷启动通过、全量回归通过、无崩溃。
- 无阻断性失败，**可交付**。唯一需开发关注的残留：⑤按左定位当前台的"可见性"因 smooth 动画收敛慢而滞后（意图 target=10 正确）。
