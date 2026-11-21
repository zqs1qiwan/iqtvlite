# CI10107 清空硬断言 真机验证

2026-11-21，acceptance2（t162 attempt 1）。对象：`tv.iqtvlite.app.validation` **10107 / 2.2.0-tivimini-5c04b1c6-validation**（t159：清空持久化/Back/OK；run 44813428778）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

> 模型不读图；以 **SharedPreferences（run-as 直读）+ 设置页「最近观看（N）」计数** 为持久化硬证据，辅以 session/截图。

## 0. 结论（硬断言未通过/无法执行）

- **未能通过**：多次尝试后 **`recent_channels_cleared` 始终 false、`recent_channels_v1` 始终存在（7 条 cctv2–cctv8）**，force-stop 重启后依旧 → **“清空→立即空→重启仍空”未被满足/未被证明**。
- **原因判定（自动化侧）**：最近观看 dialog 能打开（按 OK 后 BACK 返回设置页，说明有浮层），但 **uiautomator 始终 dump 不到 dialog 内节点**（root 空），无法可靠定位「清空」；D-pad DOWN×7/×8/×25 + OK、以及 7 个估算坐标 tap 均未触发清空（prefs 不变，session 不变）。
- **不能据此断定代码缺陷**：也可能是遥控/自动化无法把焦点落到“清空”行（Compose dialog 语义未暴露）。需人工或带 Compose 语义的 UI 自动化复测；本报告只给“未验证/未通过”。

## 1. 身份/安装（PASS）
- app `3f22a3bad243b070179ebd2577b5d8f2fda40c5e47229ae0d37d0c4a3682f367`（12,994,455B，match）/ test `b9e71cc8a507f36db0b3931f49634e7c0f812e417c0b5a28bcd042b3117caf16`（502,955B，match）；签名 `1b7c4169…`；10107/2.2.0-tivimini-5c04b1c6-validation；安装后 versionCode=10107。
- 注：本次由 10106 直接替换为 10107，**未对 10106 做 tar 备份**（仅卸载重装；原 tv.iqtvlite.app / com.cjlhll.iptv 未动）。

## 2. 清空硬断言（原始证据）
### 2.1 前置：列表非空
- 设置页：`最近观看（7）`。
- prefs `recent_channels_v1`（run-as 直读）含 7 条：`cctv8…cctv2`（CCTV-8/7/6/5/4/3/2），`recent_channels_cleared=false`。
- 已播放 3 台：`c75702…`(CCTV2?)、`c40754…`、`c24277…`。

### 2.2 清空尝试（全部失败）
| 尝试 | 动作 | prefs 结果 |
|---|---|---|
| A | 设置→DOWN×3→OK→DOWN×25→OK | `cleared=false`，`recent_v1` 仍 7 条 |
| B | 同上 DOWN×7 / ×8 + OK | 同上（session 未变） |
| C | 伪装“清空”按钮坐标 tap（700,650/700/780/820/860/900/940） | 同上 |
| D | 每次尝试后 force-stop→重启→再读 prefs | **`cleared=false`，`recent_v1` 仍 7 条** |

- 逆向发现：设置页打开时焦点实际在**源配置**（OK 会打开源配置页）；DOWN×3 后 OK 未见源配置页，dialog 可开但节点不可见。
- 现象记录：某次设置显示 `内存 0 条 · 磁盘 120 个`（内存被清空但磁盘仍 120；非本次目标，仅记录）。

## 3. 其他断言（依赖清空，未能执行）
| 项 | 结果 |
|---|---|
| 清空后切新台→列表仅 1 条（换台才重建） | **未验证**（清空未执行） |
| 清空后同频道恢复/playlist commit 不得重建 | **未验证** |
| Back 只关列表 | **部分**：dialog 打开时 BACK 后设置页仍可见（settingsVisible=True）→ 倾向于“只关列表”；但 dialog 节点不可见，无法 100% 确认 |
| OK 选非当前台真正切换 | **未验证**（无法定位/选择非当前行） |
| 不回归（OSD/166-167/MP2/AAC/台标/不崩） | **未测**（本轮聚焦清空；设备可正常出画） |

## 4. 逐项
| 项 | 结果 |
|---|---|
| 身份/安装 | 通过 |
| **清空→立即空→重启仍空** | **未通过/无法执行**（prefs 未变） |
| 切新台仅 1 条重建 | 未验证 |
| 同频道恢复不重建 | 未验证 |
| Back 只关列表 | 部分（设置仍在，倾向通过） |
| OK 非当前台切换 | 未验证 |
| 不回归 | 未测 |

## 5. 判定与建议
- **硬断言未通过**：清空在自动化下未被触发，prefs 证据显示 `recent_channels_cleared` 未置 true、列表未被清除。
- **建议**：①人工（或 Compose 语义/无障碍节点完善后的自动化）在 10107 复测「设置→最近观看→清空」是否可点；②若人工也点不到，属**清空入口/焦点缺陷**，需开发修（清空行需可聚焦/可点，或提供设置页直接清空入口）；③建议开发为清空路径加日志（cleared 置 true 时打点），便于真机判定。
- 未改代码、未改原包数据；设备现为 CI10107。
