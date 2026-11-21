# 设置二级 IA 实现记录（t13）

> 任务：t13「实现：设置二级菜单合并」 · 规格：[`SETTINGS_IA_V2.md`](SETTINGS_IA_V2.md) · 接线表：[`INFO_CARD_V2_WIRING.md`](INFO_CARD_V2_WIRING.md)（仅 §7 由 t4 使用）
> 代码基线：`8345078`（t4 信息卡 v2 已合）→ 交付 `b813457`
> 结论：**已实现并 CI 全绿（run #149 / versionCode 10149）**；设备侧由 acceptance(t9) 按规格 §9.2 的 16 步验证。

---

## 0. 一句话

设置抽屉从「单页 24 行」改为「一级 6 个入口（标题 + 当前值摘要）+ 6 个二级页（每页 ≤6 行）」，
Back/Left 逐级返回（二级 → 一级焦点回入口行 → 关抽屉）、二级 RIGHT 被吞掉，
24 个可操作行唯一合并 1 组（「更新EPG」=「立即刷新 EPG」），已有 tag/滚动/焦点/bringIntoView 全部保留。

---

## 1. 交付清单

| 文件 | 变更 | 行数 |
|---|---|---|
| `android/app/src/tivimini/java/com/cjlhll/iptv/SettingsIA.kt` | **新增**：`SettingsPage` / `SettingsRootEntry` / `SettingsIA` / `SettingsNavPolicy` / `SettingsSummary`（纯逻辑，无 Compose/Android import） | +258 |
| `android/app/src/tivimini/java/com/cjlhll/iptv/SettingsDrawer.kt` | 重构：一级 6 行 + 6 个 `Settings*Page` + 统一走 `SettingsNavPolicy` 的按键处理 | +716 / −300 |
| `android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt` | 接线：`setSettingsBackHandlerController` / `onBackHandlerReady`；BACK 分支先问二级处理器 | +12 |
| `android/app/src/test/java/com/cjlhll/iptv/SettingsIaTest.kt` | **新增**：根顺序/标题/每页 ≤6 行/root tag 不混入/退役集合/摘要回退 | +112 |
| `android/app/src/test/java/com/cjlhll/iptv/SettingsZeroLossTest.kt` | **新增**：24 tag 零丢失（含直接读源码）/无 LazyColumn/分区标题不可聚焦/`settings_list` 字面量 | +150 |
| `android/app/src/test/java/com/cjlhll/iptv/SettingsBackStackTest.kt` | **新增**：BACK/LEFT 逐级、RIGHT 吞掉、连按不跳级、越界 clamp、UP/DOWN 不换层 | +130 |
| `android/app/src/test/java/com/cjlhll/iptv/SettingsSummaryTest.kt` | **新增**：6 个摘要格式/宽度 ≤22 全角/空负值/解析/截断 | +110 |
| `scripts/check_settings_ia.py` | **新增**：静态门禁（见 §4） | +150 |
| `.github/workflows/apk.yml` | 构建校验步骤追加 `check_settings_ia.py` 与 `check_settings_scroll.py` | +3 |

---

## 2. 一级 / 二级结构（实现即规格）

```
一级（ROOT，settings_list 滚动容器）
├ 播放与画面    摘要「起播缓冲 稳(500ms)」/「… · 实验开」   settings_root_playback
├ 遥控器与操作  摘要「换台 · 显示信息卡」                  settings_root_remote
├ 音频与字幕    摘要「音轨 mpeg-L2 · 2ch」/「—」            settings_root_audio
├ 数据与更新    摘要「列表 启动时 · EPG 启动时」            settings_root_data
├ 台标与缓存    摘要「96 条 / 120 个」（从既有 logoCacheSummary 解析，不额外 IO） settings_root_logo
└ 关于          摘要「版本 10149」                          settings_root_about
  页脚（不可聚焦）：关闭后按 ← 打开频道列表
```

| 二级页 | 行数 | 行 tag |
|---|---:|---|
| `SettingsPlaybackPage` | 5 | `settings_playback_buffer` / `settings_quality_menu`（仅多线路时出现）/ `settings_zap_preconnect` / `settings_multicast_prewarm` / `settings_jingcai_experiment` |
| `SettingsRemotePage` | 6 | `settings_remote_updown` / `_ok` / `_back` / `_digits` / `_channel_keys` / `_reset` + 两行不可聚焦帮助（含「频道列表：← 　设置：→」） |
| `SettingsAudioPage` | 1 | `settings_audio_track`（+「当前：…」小字） |
| `SettingsDataPage` | 6 | `settings_source_config` / `settings_auto_playlist` / `settings_refresh_playlist` / `settings_auto_epg` / **`settings_refresh_epg`（合并行「立即更新 EPG」）** / `settings_recent_entry`（打开既有最近观看对话框） |
| `SettingsLogoPage` | 4 | `settings_logo_prefetch` / `settings_logo_load` / **`settings_logo_refresh`（新增）** / `settings_logo_clear` |
| `SettingsAboutPage` | 1 | `settings_check_update`（静态文本承载应用名/版本名/版本号/包名） |

**零丢失核对**：基线 24 个可操作 tag → 23 个可操作行 + 1 个退役（`settings_epg_source`，与
`settings_refresh_epg` 合并，两者都只调 `requestManualEpg()`，见规格 D-S2）。`SettingsZeroLossTest`
直接读源码逐 tag 断言，避免"模型改了 UI 没落地"。

---

## 3. 交互与按键（实现即规格 §5.1）

| 当前 | BACK | LEFT | RIGHT | OK |
|---|---|---|---|---|
| 一级 | 关抽屉 | 关抽屉 | 进入焦点行页面 | 进入焦点行页面（行本身 clickable 也走同一入口） |
| 二级 | 回一级，焦点回入口行 | 同 BACK | **吞掉（不穿透）** | 执行该行动作；不关抽屉的行焦点留在本行 |

实现要点：
- 抽屉内所有按键先映射到 `SettingsNavPolicy.Key` 再交给 `onKey`，UI 与 `SettingsBackStackTest`
  **共用同一份规则**，不会漂移；只处理 `KeyEventType.KeyDown`（KeyUp 重复触发会跳两层）。
- UP/DOWN 由 `onKey` 返回 `consumed=false`，继续交给 Compose 焦点系统（页内移动 + `bringIntoView`）。
- `PlayerActivity.dispatchKeyEvent` 的 BACK 分支先调用 `settingsBackHandler`（二级 → 一级），返回 false
  时才真正关抽屉 —— 一次按键绝不跳两级，也不会误退出播放器。
- 退出二级页再进入时，焦点回到该页上次停留的行（`pageLastFocus`，会话内记忆，不持久化）；
  首次进入落该页第一行（「线路选择」不适用时不会成为落点）。

**与 t4 信息卡互不干扰**：
- 信息卡 FOCUSED 时，`dispatchKeyEvent` 已把 DPAD/OK/BACK 交给 Compose（`isInfoCardFocused` 守卫，t4）；
- 设置抽屉打开时，信息卡的覆盖层监听立刻把卡片置 `HIDDEN`，两套覆盖层不会同时吃键；
- 抽屉的一级/二级按键在抽屉自己的 `onPreviewKeyEvent` 里消费，不依赖卡片状态。

---

## 4. 测试与门禁

### 4.1 JVM 单测（4 个新类）

| 类 | 覆盖 |
|---|---|
| `SettingsIaTest` | 一级恰好 6 项且顺序/标题固定；每页行非空、无重复、≤6；root tag 不出现在页行；退役集合只含 `settings_epg_source`；`rootEntries` 摘要回退 `—` |
| `SettingsZeroLossTest` | 24 个基线 tag 全部映射；**直接读 `SettingsDrawer.kt`** 断言每个非退役 tag 真的渲染；退役 tag 已从 UI 消失且合并行 `tag=` 只出现一次；`settings_logo_refresh` 新增；无 `LazyColumn`；`SettingsGroupHeader` 不可聚焦；`settings_list` 字面量保留 |
| `SettingsBackStackTest` | Root+BACK/LEFT→Closed；Page+BACK/LEFT→Root(入口行)；Page+RIGHT 状态不变且 consumed；Root+OK/RIGHT→Page(首行)；连按不跳级；Root(focus) 越界 clamp；UP/DOWN 不改层级 |
| `SettingsSummaryTest` | 6 个摘要格式（含规格示例 `96 条 / 120 个`、`版本 10149`）；≤22 全角；空/负值不崩；`logoFromCacheSummary` 复用抽屉已有字符串；`clamp` 按字符边界截断 |

### 4.2 静态门禁

- **`scripts/check_settings_ia.py`（新增，已接入 CI）**：
  1. `ROOT_ORDER` 恰 6 项且顺序固定、6 个页标题存在；
  2. 每个 `SettingsPage` 都有对应 `Settings*Page` composable（另加 `SettingsRootPage`）；
  3. 24 个旧 tag 中除退役项外必须出现；合并行 `tag = "settings_refresh_epg"` 恰一次；`settings_logo_refresh` 存在；
  4. `settings_root_*` 不得混入页行；
  5. 抽屉里必须保留 `settings_list` **字面量**、且不得出现 `LazyColumn`；
  6. `verticalScroll(` / `bringIntoViewRequester(` / `bringIntoView()` / `SettingsGroupHeader(` 仍在；
  7. `PlayerActivity` 接线 `onBackHandlerReady` / `setSettingsBackHandlerController`；t4 的 `isInfoCardFocused` 守卫仍在。
- **`scripts/check_settings_scroll.py` 未改而通过**（t192 门禁：可滚动 + 每行 bringIntoView + 关键 tag + 分区标题不可聚焦）。
- 另外两个既有门禁继续通过：`check_info_card.py`（t4）、`check_logo_webp.py`（t11）。

---

## 5. CI 交付 receipt

| 项 | 值 |
|---|---|
| 交付提交 | `8345078`（实现）→ `5716da6`（控制器参数编译修复）→ `d7a0a15`（单测断言）→ `b813457`（KDoc 语法） |
| CI run（绿） | **#149**，sha `b813457`，event `push`，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 13s`；`:app:testDebugUnitTest`（288 个用例全绿）+ `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10149**，versionName `2.2.0-tivimini-b8134573-validation` |
| `app-debug.apk` sha256 | `cfc04528acdb1ce6fa2a708a969a66401f094292fad3b83fdbec1882f6ddc8e2` |
| `app-debug-androidTest.apk` sha256 | `0fdd6e733b48ae465fa164ef22f3a14371b8e46a2b7bf1f2c0d7dd8796fbd46a` |
| 签名证书（主包=测试包） | `82014b3b1502c328a0deb3ebef01d79aebcb6b02c61f4a4f71da1a541aefa17a`（4 个稳定签名 secrets 未配置 → 仍是临时 debug keystore，见 `docs/CI_STABLE_SIGNING.md`） |
| 门禁 | `check_logo_webp: OK`、`check_info_card: OK`、`check_settings_ia: OK`、`check_settings_scroll: OK`、pair gate `{"missing_types":0,"missing_methods":0,"missing_fields":0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14703884716，10,431,348 B）；`iqtvlite-android-reports`（id 14704627998） |

修复过程（三次红 → 绿）：
1. `#146` 编译错：`setSettingsBackHandlerController` 被我写成"调用 setter"，实际是 `VideoPlayerScreen` 的参数，
   改为真正的参数并由 Activity 传入 `{ handler -> settingsBackHandler = handler }`；
2. `#147` 单测 4 红：源码扫描把注释里的 `LazyColumn` 也算进来、`settings_refresh_epg` 计数含注释、
   `clamp` 样本不够长、`displayUnits("ab")` 期望值写错 → 统一改为"剥注释后再断言"并修样本；
3. `#148` 语法错：测试 KDoc 里写了 `/*` 造成块注释提前嵌套 → 改成行注释。

---

## 6. 不变项与回退

- **未改**：播放器/零拷贝 SurfaceView/看门狗/睛彩实验、台标缓存逻辑、自动更新调度、EPG 单路径、
  信息卡（t4 的实现原样保留）、`RemoteHabits` 默认值（t4 已改为 `CONFIRM`）、applicationId、main/tag/Release 策略、体积门禁。
- **回退**：`git revert b813457 d7a0a15 5716da6 8345078` 即回到单页设置；无数据/Prefs 迁移，
  已存设置项与 `remote_*` / `*_interval` 等偏好键名完全不变。
- 抽屉宽度 300dp、`surface` α0.95、0dp、24dp padding、160/140ms 滑入滑出、焦点 `surfaceVariant`
  无边框 —— 与基线数值一致（规格 §0.2 红线）。

## 7. 设备侧验收（t9 执行，本轮不做）

按 `SETTINGS_IA_V2.md` §9.2 的 16 步：一级 6 行摘要、二级 ≤6 行首屏全见、BACK/LEFT 逐级且焦点回入口行、
二级 RIGHT 无变化、遥控习惯即时生效（改上下键后摘要同帧变化）、立即更新 EPG 抽屉不关且只前进一次、
最近观看清空后计数同帧归零、台标缓存清理、关于页版本号与 `dumpsys` 一致、反复开关 20 次无崩溃/无灰屏、
页脚提示可见且焦点不会落上去。本任务未执行任何 adb/设备命令。
