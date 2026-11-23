# REGRESSION_CHECKLIST — 可执行回归验证清单（t48）

> 版本 1.0 · 2026-11-23 · 配套单一权威 `docs/GOLDEN_SPEC.md`（判定口径以它为准）。
> 用法：每项按「前置 / 操作 / 期望 / 判定 / 证据 / 门禁」执行；判定只允许 **通过 / 失败 / 未验证** 三值，**未验证 ≠ 通过**。任何**阻断项**失败 → 不可交付。
> 设备：TR100-G9 / API28 / 1080p / `192.168.2.245:5555`；测试前从**空 Prefs / 干净启动**起（避免历史 `remote_ok` 残留干扰）。

---

## 0. 判定规则（先读）

1. **阻断项失败即不可交付**：第 0 项（冷启动冒烟）、静态门禁/单测、任何"越级 finish/VerifyError/灰屏/ANR"失败。
2. **未验证 ≠ 通过**：真机无法构造（如组播无 frameRate、166/167 被拦截、MP2 听感）按 GOLDEN_SPEC §7 记为"已知限制"，不计通过也不计失败，但必须留说明。
3. **证据必留**：每条真机项至少 dump / 日志 / 截图之一；阻断项必须全留。
4. **日志开关**：取证时 `setprop log.tag.IQTV-PERF DEBUG`、`log.tag.IQTV-INFOCARD DEBUG`，收尾复位为空。
5. **Golden 引用**：判定口径见 `GOLDEN_SPEC.md` 对应章节；根因见 `INTERACTION_REGRESSION_SPEC.md`。

---

## 第 0 项（阻断）— API28 冷启动冒烟

| 字段 | 内容 |
|---|---|
| 前置 | 卸载/重装当前待验 validation 包（签名变更时按协议备份 data→仅卸 validation 一次→装→回灌），force-stop 0 |
| 操作 | 清 logcat(main+crash) → monkey/launcher 冷启动 → 6s 后查询 `mResumedActivity` + `ps` + crash buffer |
| 期望 | `mResumedActivity=.../PlayerActivity`；app 进程 count=1；`VerifyError`/`FATAL EXCEPTION`=0；crash buffer 空；视频正常不灰 |
| 判定 | 通过/失败（**阻断项**） |
| 证据 | logcat 片段、`dumpsys activity activities | grep mResumedActivity`、截图 |
| 门禁 | `:app:assembleDebug` 绿 |
| 来源 | GOLDEN §6 / `STARTUP_FIX_VERIFY.md 第0断言`（vc10232 已通过） |

---

## 1. 静态门禁 + 单测（阻断，每次改动必跑）

命令（仓库根 `iqtvlite/`，bundled Python：`C:\Users\zqs1q\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe`）：

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PISOLATED_TEST_PACKAGE=true --stacktrace
python3 scripts/verify_apk.py "$APK"
python3 scripts/check_logo_webp.py
python3 scripts/check_info_card.py
python3 scripts/check_settings_ia.py
python3 scripts/check_settings_scroll.py
python3 scripts/check_perf_policy.py
python3 scripts/check_jingcai_timeline.py
python3 -m unittest discover -s scripts -p test_dex_contract.py
python3 scripts/verify_instrumentation_pair.py "$APK" "$TEST_APK" --report android/app/build/reports/instrumentation-contract.json
```

通过线：gradlew 全绿（test 用例数 ≥377，0 failure；lint 0 error）；8 个 `check_*` 全 `OK`；DEX 契约 7 OK；pair gate `{missing_types:0,missing_methods:0,missing_fields:0}`；APK ≤30MB、双 ABI/JNI。

| 编号 | 门禁 | 覆盖 GOLDEN |
|---|---|---|
| G1 | `check_perf_policy.py` | §3 滚动单真源、§6 性能红线 |
| G2 | `check_info_card.py` | §4 信息卡布局/7s/FOCUSED |
| G3 | `check_settings_ia.py` | §1.2 遥控映射/设置 IA |
| G4 | `check_settings_scroll.py` | §3 设置滚动/焦点 |
| G5 | `check_logo_webp.py` | §5 logo 无损 |
| G6 | `check_jingcai_timeline.py` | §6 睛彩诊断 |
| G7 | `check_player_threading.py` / `check_refresh_paths.py`（本地补跑） | §6 线程/刷新 |
| G8 | DEX 契约 + verify_apk + pair gate | §6 交付完整性 |

---

## 2. 功能真机清单

### F1 焦点视觉（GOLDEN §1.1）
- 前置：抽屉打开 / 设置页 / 信息卡 FOCUSED。操作：DPAD 移动焦点。
- 期望：焦点底灰白 `#FFFFFF14`（像素中性）；**仅当前播放台**蓝字 + 蓝进度；其他行 0 蓝像素；无蓝框。
- 判定/证据：截图取色 + dump；门禁 G2/G3。

### F2 BACK 分层（GOLDEN §2，阻断相关）
- 逐层构造：音频框→最近列表→质量框→抽屉→设置二级→设置一级→卡片 FOCUSED→卡片 IDLE→回看→播放器。
- 每层按一次 BACK：期望只退一层、`mResumedActivity` 仍 PlayerActivity；播放器第一次 BACK 出 toast「再按一次退出app」、2s 内第二次才退。
- **确认菜单场景**：FOCUSED 连按 BACK×1 退 IDLE（卡片仍在）、×2 关卡片、之后才进入双按退出——**不得在卡片可见时退 app**。
- 判定/证据：逐步 dump + toast 文本 + `mResumedActivity`；根因见 `INTERACTION_REGRESSION_SPEC §1-2`（待修项，修复前此处判失败）。

### F3 换台 / 抽屉（GOLDEN §3）
- F3a 冷启首开：第一次打开抽屉，dump 可见行含当前台、`row_focus idx` 贴当前台；无首帧"闪到开头再跳回"（连续采样 5 帧）。
- F3b 换台保持：抽屉打开浏览到深行 → 换台 → 可见行不闪回首页、`drawer_open` 不重入、`drawer_scroll_correct` 一次贴焦点。
- F3c 均匀滚动：连续 DOWN×20，滚动平滑无整页翻；gfxinfo 无 ≥300ms 巨帧。
- F3d 分组：分组列 UP/DOWN 不按 OK，右侧频道列立即跟随；→ 进入并收起分组列、从该组第一台开始；按组记忆。
- F3e 全部频道按 →：进频道列表不卡翻页；选台不需要翻页。
- 判定/证据：dump + `IQTV-PERF` 日志 + gfxinfo；门禁 G1。

### F4 确认菜单（GOLDEN §4）
- F4a 一次 OK（DIRECT）：单次 OK → `ok-open DIRECT focus recent=4`；RIGHT `move index` 逐步；OK `switch idx` 切台、last_channel 变化；高亮=焦点=OK 目标。
- F4b 7s：IDLE 显示后 ~7s `hide reason=timer`（7±0.5）；**FOCUSED 静止也 7s 收起**（当前版本 FOCUSED 不隐藏，见 t47 §7 待修）；期间有 move 操作则不收；BACK 可提前取消。
- F4c 徽标：右上 TopEnd 单行含 分辨率/帧数/声道/SDR/编码，不占独立行；台标下只 `N.台名`+分组，无编码；节目名白色非蓝。
- F4d 最近观看：4 logo 内联、无 logo 文字回退；LEFT/RIGHT/OK 切台；高度 ≈96dp+少量（≤240dp，根治目标 ≤140dp）。
- 判定/证据：dump bounds + 截图 + `IQTV-INFOCARD` 日志；门禁 G2。

### F5 logo / 台标资源（GOLDEN §5）
- 前置：源 assets 与 res 做 sha256 比对（应全一致，当前不一致——t47 §5 待修）。
- 真机：launcher 图标 + TV banner + 最近任务卡，lockup 按 +10/+35 居中、无裁切；round 与方形同源。
- 判定/证据：hash 表 + 截图；门禁 G5 + 建议 `check_brand_assets.py`。

### F6 设置呼出（GOLDEN §1.3，阻断相关）
- 默认播放器按 RIGHT → 设置抽屉打开（非"全部频道进列表"的死锁）；分组列 RIGHT → 进频道列表；回看 RIGHT → 快进；FOCUSED 态用绑定 OPEN_SETTINGS 的键（彩键/MENU 兜底）能打开设置。
- 判定/证据：dump `settings_root_*` + Prefs `remote_action_bindings_v1`；门禁 G3；根因见 `INTERACTION_REGRESSION §8`（待修）。

### F7 数字 OSD（GOLDEN §4 派生）
- 输入数字（如 21）→ OSD 出现在**右上角** bounds y<100dp；3s 后自动跳台；"需OK"模式 3s 只清 OSD 不跳；遮罩打开 OSD 隐藏；确认态蓝字瞬时。
- 判定/证据：dump `digit_osd` bounds + 日志 `IQTV-DIGITS`；门禁 `DigitOsdPolicyTest`。

### F8 其他回归（GOLDEN §6）
- 自动更新 fail=0、`last_*_success` 前进；音轨单轨门控；台标缓存计数/清空；S1 小分组不崩；上一台/最近对话框；MP2 有声（dump 解码/声道，听感标未验证）；不崩不灰 Fatal=0。
- 判定/证据：日志 + Prefs dump + 截图。

---

## 3. 改动影响矩阵（改模块 → 必跑项）

| 改动模块 | 必跑 checklist |
|---|---|
| `dispatchKeyEvent` / 遥控映射 / RemoteBindings | **第0项** + F1 + F2（全层）+ F6 + F7 + 门禁 G3/G1 + 全部按键真机 |
| `ChannelInfoBanner` / `InfoCardPolicy` | 第0项 + F1 + F2(卡片层) + F4(全) + F6(FOCUSED 呼出) + F7 + 门禁 G2 |
| `PlayerDrawer` / 滚动 / SidebarController | 第0项 + F3(全) + F1 + F2(抽屉层) + 门禁 G1 + gfxinfo |
| 主题色 / 焦点色 token | F1 + F4d + F2(对话框层) + 全部对话框截图 + 门禁 G2/G3 |
| logo / res 资源 | 第0项 + F5 + 门禁 G5 + 冷启截图 |
| 播放器 / StreamPlayback / PlaybackIntent | 第0项 + F3b + F8 + gfxinfo + VerifyError 专项 |
| AutoUpdate / EpgRepository | F8 自动更新 + F4c 徽标 + 门禁 G6/G7 |
| 任意构建/依赖变更 | **第0项** + 第1节全部门禁 + 第2节全量 |

---

## 4. 交付判定模板（每条一行，复制到验收报告）

```
| 编号 | 期望(GOLDEN §) | 判定(通过/失败/未验证) | 证据(dump/日志/截图路径) | 备注 |
| G0   | 冷启动进 PlayerActivity 无 VerifyError | 通过 | startup.log L12 | 阻断 |
| G1-G8| 门禁/单测全绿 | 通过 | ci-run-xxx | 阻断 |
| F1   | 灰白焦点/蓝字选中 | 通过 | f1.png | |
| F2   | BACK 分层不越级 finish | 失败 | f2.log: 卡片可见时退 app | 待 t47 修复 |
| F3   | 换台抽屉保持/均匀滚动 | 通过 | f3.log row_focus | |
| F4   | 一次OK/7s/徽标/4logo | 未验证(部分) | FOCUSED 7s 待修 | 已知限制 |
| ...  | | | | |
```

**最终判定**：所有阻断项（G0、G1–G8、F2 越级 finish）通过 + 功能项无"失败" → 可交付；任一阻断项失败 → 不可交付；"未验证"项须逐条引用 GOLDEN §7 已知限制或补测。

> 本清单随 GOLDEN_SPEC 更新；每次功能决策变更，先改 GOLDEN_SPEC 再同步本清单对应项与影响矩阵。
