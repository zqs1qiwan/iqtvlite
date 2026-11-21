# CI10121 F1 收口 真机验证（免暂停即时生效/Back退出/数字需OK）

2026-11-21，acceptance2（t196 attempt 1）。对象：`tv.iqtvlite.app.validation` **10121 / 2.2.0-tivimini-276a2275-validation**（t193：REMOTE-INSTANT-01 / CATCHUP-01 / REMOTE-BACK-01；run 44842867824）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

## 0. 摘要

| 项 | 结果 |
|---|---|
| 身份/安装 | **通过**（app `0ecefd29…e845` 13,059,991B / test `64941dea…9285`；签名 `95ec4461…`；10121） |
| **免暂停即时生效（上下键=呼出列表）** | **通过**：设置改为 `remote_updown=OPEN_DRAWER` 后，仅关闭设置抽屉（不重启/不暂停），按 **UP 立即打开频道抽屉**（`drawerOpen=True`，无新 `format session`，未切台） |
| **免暂停即时生效（OK=确认）** | **通过（行为）**：`remote_ok=CONFIRM` 后按 OK **未打开频道列表**（`drawerOpen=False`）；信息条因 3s 自动消失 + dump 空未捕获 |
| **Back=直接退出** | **通过**：遮罩打开时第 1 次 BACK **先关遮罩**（设置关闭、仍在播放器）；第 2 次 BACK **直接退出**（`mResumedActivity` → dangbei launcher） |
| **数字=需OK** | **部分**：`remote_digits=NEEDS_CONFIRM` + 行文案「先显示输入条需OK」生效；输入 1,2 后 5s **未立即跳台**（无新 format session）；随后 OK/BACK 的新 session 未观察到（频道号对照组不明确） → 提交/取消未确证 |
| **恢复默认** | **通过**：点「恢复默认」后 prefs 中 `remote_updown/remote_ok/remote_back/remote_digits/remote_channel_keys` **全部移除**（回默认） |
| 回看中 OK | **未验证**（未构造回看样本） |
| 不回归 | 167 换台 / MP2 / 无崩溃 **通过**；OSD/上一台/最近观看清空/音频入口/台标/灰屏未测 |

## 1. 身份/安装（PASS）
- app `0ecefd29b43991bf125f36575da02ddacd1db28bfa3f6b563eb91c211dcee845`（13,059,991B，match）/ test `64941dea15aa1d6e7ec7b4553d0328e54d05403d5c1ca17e988e2d831ea29285`（502,955B，match）；签名 `95ec4461…`；10121/2.2.0-tivimini-276a2275-validation；安装后 10121；backup 10120（1,464,320B）后仅替换；原包保留。

## 2. 免暂停即时生效（PASS）
### 2.1 上下键=呼出列表
- 设置中点击「上下键：换台」→ 行变「上下键：呼出频道列表」，prefs `remote_updown=OPEN_DRAWER`。
- **不重启、不暂停**：按一次 BACK 关闭设置抽屉（仍在 PlayerActivity，pid 12543 不变）→ 按 **UP**：
  - dump：`drawerOpen=True`，列表内容 `频道|无信息|武|139. 武术|…|144. 中国教育电视台一台`
  - `format session` 无新增（未切台）→ **即时生效 PASS**。
- 对照（t191 在 10117 上同类操作不重启不生效，需 force-stop）→ 本版修复确认。

### 2.2 OK=确认
- 设置中点击「OK键：打开频道列表」→ 行变「OK键：确认」，prefs `remote_ok=CONFIRM`。
- 关闭设置后按 **OK**：`drawerOpen=False`（**未打开列表**）→ 行为符合“确认”。
- 信息条（ChannelInfoBanner）因 3 秒自动隐藏 + uiautomator 多次 dump 为空，**未捕获到文本**；仅以“未开列表”作为行为证据。

## 3. Back=直接退出（PASS）
- 设置 `remote_back=EXIT`（行「Back键：直接退出」）。
- 遮罩打开（设置抽屉）时第 1 次 BACK：设置关闭、`mResumedActivity` 仍 `PlayerActivity` → **遮罩先自关**。
- 第 2 次 BACK（无遮罩）：`mResumedActivity` → `com.dangbei.tvlauncher/.IndexActivity` → **直接退出 PASS**（进程 12543 仍存活属正常后台）。

## 4. 数字=需OK（部分）
- 设置中点击「数字键：直接跳台」→ 行变「数字键：先显示输入条需OK」，prefs `remote_digits=NEEDS_CONFIRM`。
- 输入 `1`、`2` 后等待 5s：**无新 `format session`**（未跳台）→ 与“需 OK 才跳”一致。
- 随后按 OK：`format session` 仍为原 session（`changed=False`），仅出现同 session 的 `first_frame s4`；再测 BACK 取消亦无新 session → **“OK 提交跳转”与“BACK 取消”未被区分证实**（所选频道号在当前分组可能不存在）。
- 判定：设置生效与“不立即跳台”**通过**；提交/取消**未确认**。

## 5. 恢复默认（PASS）
- 点击「恢复默认」→ prefs `grep remote` **无任何输出**（五个 key 全部移除）→ 回默认。

## 6. 回看中 OK（未验证）
- 未构造回看样本（EPG 历史节目回看），未验证“OK 出信息条、不开列表、不打断回看”。

## 7. 不回归
- 167 换台：`NONE → c88085… changed=True`；`audio_format audio/mpeg-L2 48k/2ch`、`first_frame ttff=573ms`、`AudioTrack ID:87 state:started`；`crash=0`，pid 12543 稳定。
- 数字 OSD/上一台/最近观看清空/音频入口/台标/灰屏 未测。

## 8. 逐项
| 项 | 结果 |
|---|---|
| 免暂停即时生效（上下键=列表） | **通过** |
| OK=确认（不开列表） | 通过（信息条未捕获） |
| Back=直接退出 + 遮罩先自关 | **通过** |
| 回看中 OK 出信息条不开列表 | 未验证 |
| 数字=需OK（不立即跳台 / OK 提交 / BACK 取消） | 部分（不立即跳台通过；提交/取消未确认） |
| 恢复默认五项回默认 | **通过**（prefs 清空） |
| 不回归 | 167/MP2/无崩溃 通过；其余未测 |

## 9. 判定
- **F1 收口的核心“免暂停即时生效”与“Back 直接退出（遮罩先自关）”真机通过**；恢复默认通过。
- 数字键“需 OK”的设置生效与“不立即跳台”通过；提交/取消因频道号对照不明确未确认。
- 回看中 OK 未验证（需回看样本）。
- 未改代码、未改原包数据；设备现为 CI10121（PlayerActivity 前台，MP2 正常）。
