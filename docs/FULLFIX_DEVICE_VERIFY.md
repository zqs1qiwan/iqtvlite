# FULLFIX 真机验收报告（CI211 / vc10211）· TR100-G9

- 时间：2026-11-22 19:16–20:12（Asia/Hong_Kong）
- 执行：acceptance（t7 attempt 1）
- 设备：TR100-G9 / Android 9 / API28 / 1080p / 240dpi / `192.168.2.245:5555`
- 包：CI run **#211**（id `45015404890`，head `c8991adb`）/ versionCode **10211** / `2.2.0-tivimini-c8991adb-validation`
  - app sha256 `b3aa1a1e8cc7c32235e593faf9e6cacb48d2e7ebd8995c86f6c2d7c5c5fb4fe9`
  - test sha256 `cb5a200b165b418b5726822e69519fbe9a17b8eb7f37aa163621d20dca2783ee`
  - 稳定签名证书 SHA-256 `3764e63a3924d6f1ed81d15f1fd217952dd3b617904456dfd74623c31da934ac`
- 源：`https://iptv.laobaitv.net` + EPG `https://epg.laobaitv.net`（经本机 LAN `/api/push` 配置，14 组 / 147 频道）
- 证据截图/日志目录：`docs/evidence/t7-fullfix/`

---

## 0. 结论

| 区块 | 结论 |
|---|---|
| 安装与交付态 | ✅ 完成（签名冲突→备份+仅卸 validation 一次→装新包；sha256/vc 校验一致；force-stop 0 次） |
| A) S1 小分组 | ✅ 通过（1 行/4 行/24 行切换均可，落焦有效，Back 退出，无崩溃） |
| B) 用户 5 项 UI | ✅ ①②③④⑤ 均通过（详见 §2）；其中 U1「OK→OK→右→OK」硬证据完整 |
| C) HANDOFF S2–S6/U1–U5 | 部分通过、部分未验证（依赖难构造并发/时序场景）；见 §3，**未编造** |
| D) 遥控功能→按键 | ⚠️ 部分通过（设置 UI/捕获/默认迁移通过；任意自定义键实际触发存在实现缺口，见 §4） |
| E) 回归 | ✅ 通过（数字OSD/切台/最近观看/EPG/台标/不崩不灰） |

**关键缺陷（需回传开发者）**：`dispatchKeyEvent`（PlayerActivity.kt:308-534）只对固定 keycode 集合（DPAD/ENTER/BACK/MENU/166/167/92/93）调用 `resolvePlayerAction`，**无通用 fallback**（:534 `return super`）。因此设置页允许绑定任意键，但非上述集合的自定义键（实测 F1/131）按下后不会触发绑定动作。另外 F1 捕获后 Prefs/UI 均正确写入（`s=20|93|131|167` / `短[↓/93/键131/167]`），证明是**派发层缺口**而非绑定层。

---

## 1. 安装与交付态

| 项 | 结果 |
|---|---|
| 装前只读体检 | boot=1，uptime=11174s，bootreason=`reboot,adb`，`/data` 2.4G 可用(22%)，installd/storaged 存活，DeadObject/ext4/I-O/storaged/installd 日志均 0 |
| 首次 `install -r` | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（旧 vc10204 签名不同）→ 按协议处理 |
| 备份旧包 | ✅ pull 旧 validation base.apk → `_rollback_backup/validation-vc10204-base.apk`，sha256 `564792e9a80286cfd3df95a986bfbca02702a05069b2e956b6d4ba07572e12ef` |
| 仅卸 validation 一次 | ✅ `uninstall tv.iqtvlite.app.validation` → Success |
| 安装新包 | ✅ `install` Success；装后 `versionCode=10211` / `versionName=2.2.0-tivimini-c8991adb-validation` |
| 装后 sha256 校验 | ✅ 拉取已装 base.apk → `b3aa1a1e…4fe9`，与 CI app sha256 完全一致 |
| force-stop | **0 次** |
| 收尾态 | 前台 `tv.iqtvlite.app.validation/...PlayerActivity`；`iqtv_*` 实验键 0 项；debug log.tag 全部复位为空；`/data` 2.59G 可用(22%)；Fatal/IllegalArgument 我方包 0 |

> 附带说明：`com.cjlhll.iptv`（旧生产包）在装前即已缺失；据 `docs/DEVICE_HEALTH_REPORT_2026-11-21.md`，系 11-21 沙发管家（`com.shafa.market`，仍在设备上）第三方卸载所致，非本次会话造成（events 日志本次无新卸载事件）。验证相关 3 包（`tv.iqtvlite.app`、`.validation`、`.validation.test`）全程在位。

---

## 2. A) S1 小分组 + B) 用户 5 项 UI

### A) S1 小分组抽屉 ✅
| 场景 | 证据 | 判定 |
|---|---|---|
| 1 行分组（`山东频道`） | `IQTV-PERF: drawer_compose target=1 rows=1` / `drawer_open total=1 group=山东频道`；频道区仅 `147. 山东教育` | ✅ 不崩 |
| 4 行分组（`地方频道`） | `drawer_compose rows=4 total=4 group=地方频道`；`113–116` 四行 | ✅ |
| 大组（`央视频道` 24/28 行） | `drawer_compose rows=24 target=24 group=央视频道` | ✅ |
| 大→小→大切换 | 大(24)→小(1)→大(24) 连续成功，落焦有效 | ✅ |
| Back 退出 | 1 行分组落焦后 BACK → 前台回到 PlayerActivity | ✅ |
| 崩溃 | 全程 `FATAL`/`IllegalArgumentException`（我方包）0 | ✅ |

### B① 选台 UI 观感恢复 ✅
- 每个频道行具备「台标(content-desc) + `N. 台名` + 节目副标题」：`1. CCTV-1 综合` + `新闻联播`；`3. CCTV-3 综艺` + `第二届丝路之星国际音乐大赛-10` 等。
- 行距 96px = **64dp**（bounds y105→201→297…），契合 `DRAWER_ROW_HEIGHT_DP=64`。
- 聚焦行副标题保留：`IQTV-PERF: row_focus idx=N subtitle=true title=…`。
- 聚焦底色 `surfaceVariant`（代码 :1296），无蓝框（0dp 扁平）。

### B② 打开/切组不回开头 ✅
- 大组浏览至 idx=4 → BACK 关 → LEFT 重开 → 再按 DOWN 日志为 `row_focus idx=4 via=key down` → 焦点落在 idx=4（重开前位置），**不是**播放台 idx=0。
- 切组保留记忆：`SidebarController.lastFocusedUrlFor` 代码路径在位；`initialFocusUrl` 由 Activity 传入（`PlayerActivity.kt:1623` `sidebarController.lastFocusedUrlFor(selectedGroup)`）。（按组记忆的 JVM 用例在 CI #211 已 365/365 通过。）

### B③ 背景半透明 ✅
- `PerfPolicy.DRAWER_OPAQUE_CONTAINER=false`、`DRAWER_CONTAINER_ALPHA=0.92f`；`PlayerDrawer.kt:956` 在已缓存静态背景 layer 上 `alpha = …`（一处 layer blend）。
- 截图像素采样：面板灰色随底层画面微变（如 `(40,43,40)` vs `(51,51,47)`），与信息卡/设置 0.92/0.95 观感一致；无全色遮挡、无灰屏。

### B④ 信息卡排版 ✅
- dump：台标下 `频道 1 · 央视频道`（**无 MPEG-L2**，旧 bug 已修）。
- 右上徽标 `FHD · 2ch`（码率/帧率因源 Format 无值而按策略隐藏，不留 `—`，符合"未知即隐藏"）。
- 技术行 `H.264 · MPEG-L2 · SDR` 完整。
- 高度：徽标 y674→提示行 y915，容器约 240px ≈160dp（≤300dp 目标）；去掉双重 40dp padding 后长台名可见。
- `IQTV-INFOCARD: show/hide` 日志正常，FOCUSED 不自动隐藏（`autoHideMs=null`）。

### B⑤ 最近观看左右切换 + OK 跳台（U1）✅ 核心
- 先构建 6 张最近观看（167×N）。
- 精确序列：OK（出卡 IDLE）→ OK（`focus index=0` FOCUSED）→ RIGHT（`IQTV-INFOCARD: move index=1`）→ OK（`IQTV-INFOCARD: switch idx=1 group=6 CCTV-6 电影`）。
- 校验 `last_channel_title` 从 CCTV-8 → **CCTV-6 电影**，与第 2 张卡一致 ✅（旧 bug 会切到卡 0）。
- 边界：连续 RIGHT×8，`move index=5`（6 张末位 clamp，不环绕），无崩溃。
- 空/1 张：`InfoCardPolicy.resolveRecentTarget` 空/越界返回 null（CI 单测覆盖），逻辑不崩。

---

## 3. C) HANDOFF S2–S6 / U1–U5

| 项 | 结论 | 依据 / 说明 |
|---|---|---|
| U1 最近观看焦点 | ✅ 通过 | 见 §2-B⑤，硬证据完整 |
| U5 播放/焦点状态可区分 | ⚠️ 部分 | 浏览焦点底色 `surfaceVariant` 可见（代码 :1296，dump 聚焦行）；但**播放台 ▶/「播放中」标记未实现**——`DrawerChannelItem` label 仅 `"$index. ${channel.title}"`（:1285），无基于 `selected` 的标记。节目单空态三分类参数（`epgSourceConfigured/epgLoadFailed`）本包未接入。按"切换浏览分组不影响播放台信息卡"：`nowProgramByUrl` 构建代码仍按 `selectedGroup` 过滤（:1241-1247），未改为全组遍历 → 未验证通过 |
| S2 连续失败→稳定终态 / A 迟到事件不干扰 | ⚠️ 未验证 | 需可控坏流 + 并发选台；真机源稳定，未能构造迟到事件场景。代码 EpgOutcome/代际校验在位，CI 单测覆盖 |
| S3 换源竞态 B 可提交 | ⚠️ 未验证 | 需手动换 B + 后台刷 A 并发交错；真机无法精确注入时序。CI 单测覆盖 |
| S4 EPG 网络失败回退旧缓存、成功时间不推进 | ⚠️ 部分 | 观察 `epg_fail_count=0`、`epg_last_failure_at=0`、`last_epg_update_time` 有值、EPG 节目正常显示（`新闻联播` 等）；未构造断网失败场景，`EpgOutcome` 三态代码在位、CI 单测通过 |
| S5 回看 A→B 不用错节目时间 / 切 Home 恢复进度 | ⚠️ 未验证 | 测试源 catchup=0（无回看），无法构造；代码节目快照绑定与进度恢复在 CI 单测覆盖 |
| S6 扫码有效/无效会话 | ⚠️ 部分 | LAN `/api/push` 有效源推送成功（`{"ok":true}` → 自动保存并播放，source_version=3）；但本包 `SourceConfigLanServer` **无配对 token/校验**（`/api/push` 直接接收 liveSource/epgSource，:89-96），规格要求的"短时配对/无效会话拒绝"未实现，无法构造无效会话验证 |
| U2 测速返回焦点留原行 | ⚠️ 未验证 | 需 QualitySelectorDialog 多线路测速场景；本源每频道单线路，未构造 |
| U3 回看提示 / 长按 Back 不双按 | ⚠️ 部分 | 直播态 Back 单次退出行为正常；回看提示因源无回看未验证。长按 Back 逻辑代码在位（`lastBackPressTimeCatchup` 2s 门控，:466-478） |
| U4 首帧结束加载 / 失败可重试 | ⚠️ 部分 | 换台后信息卡显示真实节目与 H.264/FHD，说明首帧到达、加载结束；坏流重试耗尽场景未构造 |

> 说明：S2/S3/S5/U2 等强时序/并发/回看场景在单一稳定直播源的真机上无法可靠构造，按用户要求**如实标注未验证**、不编造。相关逻辑由 CI #211 的 365 项 JVM 单测（含各行为用例）覆盖并通过。

---

## 4. D) 遥控功能→按键全映射

| 项 | 结果 | 证据 |
|---|---|---|
| 设置页为「功能→按键」结构 | ✅ | dump：`功能 → 按键（短按 / 长按）`、`上一个频道：短[↑/92/166] 长[未绑定]`、`绑定长按键（…）`、`恢复默认按键映射` 等逐功能行 |
| 新绑定 Prefs 模型 | ✅ | `remote_action_bindings_v1` = `a=CHANNEL_UP,s=19|92|166,…;a=CHANNEL_DOWN,s=20|93|167,…` TiviMate 式序列化 |
| 旧偏好迁移后默认行为不变 | ✅ | 首启即为新格式默认；「恢复默认」可从改动态还原（`上下键：换台`/`OK键：显示信息卡`/`恢复默认`后 Prefs 回到全默认） |
| 捕获态绑定可写回 | ✅ | 点「下一个频道-短按」→ `短[请按键…]` → 按 F1(131) → Prefs `s=20|93|131|167`，UI `短[↓/93/键131/167]` |
| 绑定自定义键实际触发 | ❌ 未通过（实现缺口） | F1(131) 绑定后按 F1 不切台。根因：`dispatchKeyEvent` 仅对固定 keycode 调 `resolvePlayerAction`，无通用 fallback（:534 `return super`）。166/167 被设备系统服务（InputMonitor/AdbSwitchService/RemoteIME）拦截，adb 注入无法验证；标准 DOWN(20) 切台正常（CCTV-6→CCTV-7）证明播放器本身正常 |

---

## 5. E) 回归

| 项 | 结果 | 证据 |
|---|---|---|
| 切台不崩/不灰 | ✅ | 166/167/数字/抽屉选台多次，前台持续 PlayerActivity，无 Fatal，画面有真实 H.264/FHD 节目 |
| 数字 OSD / 跳台 | ✅ | 输入 `1`,`0`,`5` 后 `last_channel_title` 变化（跳到对应台）；OK 提交正常；无残留 |
| 上一台 / 最近观看 | ✅ | 229 上一台逻辑在位；最近观看 6 张可切换（§2-B⑤），`recent_channels_v1` 正确持久化；`最近观看（7）` 设置入口显示计数 |
| 台标缓存 | ✅ | 台标 content-desc（如 `CCTV-1 综合`）正常显示；`logo.laobaitv.net` 条目在 recent 中；设置显示 `96 条 / 141 个` 缓存计数 |
| 自动更新 | ✅ | 设置 `列表 启动时 · EPG 启动时`；`playlist_last_success_at`/`last_epg_update_time` 有值；`playlist_fail_count=0` |
| MP2 有声 | ⚠️ 未独立验证 | 音轨 dump 显示 `音轨 mpeg-L2 · 2ch`、`技术行 H.264·MPEG-L2·SDR`，解码与声道数正确；本机无声学采集手段，听感未验（如实标注） |
| 不崩不灰 | ✅ | 全程我方包 Fatal/IllegalArgument 0，SurfaceView 视频正常 |

---

## 6. 交付态与设备健康（收尾复核）

- 前台：`tv.iqtvlite.app.validation/com.cjlhll.iptv.PlayerActivity` ✅
- 实验键 `settings list global | iqtv_`：**0 项** ✅
- `log.tag.IQTV-*` 调试开关：全部复位为空 ✅
- 起播缓冲：无 prefs 覆盖，设置显示 `稳(500ms)`（buffer_min=500 默认）✅
- Fatal / IllegalArgumentException（我方包）：**0** ✅
- `/data`：2,593,172 KB 可用（22%），`DeadObject/ext4/I-O/storaged/installd` 日志 0 ✅
- force-stop：**0 次**；安装/卸载：仅 1 次 install + 1 次 uninstall（签名冲突按协议）✅
- 截图/日志：`docs/evidence/t7-fullfix/`（00 初始、01 播放、02 信息卡、03/04 抽屉、05 设置、06 遥控器设置）

---

## 7. 待修复 / 待补验清单

1. **D 缺口（建议修）**：`dispatchKeyEvent` 增加通用分支——对未命中固定 keycode 的键，用 `resolvePlayerAction(event)` 解析并执行动作，使任意绑定键真正生效；或限制设置页只接收可派发 keycode。
2. **U5 缺口**：`DrawerChannelItem` 未用 `selected` 渲染播放台 ▶/「播放中」标记；`nowProgramByUrl` 仍按 `selectedGroup` 过滤，切浏览组可能清空播放台信息卡节目。
3. **S6 缺口**：`SourceConfigLanServer./api/push` 无配对 token，规格的无效会话拒绝未实现。
4. **未验证项**：S2/S3/S5/U2/U4（强时序/回看/多线路场景，需专用测试源）；MP2 听感（需音频采集）。建议用可控坏流源与多音轨/回看源补验。
