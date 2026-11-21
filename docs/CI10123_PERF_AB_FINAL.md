# CI10123 正式性能 A/B（Zap 分段 / TTFF / rebuffer / 冷启 EPG）

2026-11-21，acceptance2（t203 attempt 1）。对象：`tv.iqtvlite.app.validation` **10123 / 2.2.0-tivimini-65ccb3fd-validation**（t201：Zap 埋点接线 + EPG 首帧门控；run 44847564722）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 身份/安装 | 通过（app `490208a9…1a9d` 13,059,991B / test `e1e3f66a…4cd0`；签名 `09dfdf79…`；10123） |
| **Zap 埋点 10 行** | **通过**：`IQTV-ZAP` 从 0 行 → **恰好 10 行**（10 次换台各 1 行，含 zap/prepare/firstFrame） |
| **TTFF p50/p95** | **p50=872ms，p95=1459ms**（avg 907）；分段 prepare≈0ms、firstFrame≈zap（全部耗时在 prepare→首帧段） |
| 对比 CI10121 基线 996/1317 | **p50 −12.4%（996→872）**，**p95 +142ms（1317→1459，尾部变差）** |
| 2 分钟稳态 rebuffer | 无 state 变化/无 rebuffer 事件（samples=0）；**但冷启出现启动 rebuffer=1、rebuffer_ms=5836（5.8s）** |
| 冷启 epgCache 顺序 | **不早于首帧 PASS**：`epgCache=17473ms firstFrame=true` 晚于 `first_frame ttff=3588ms`（但 epgCache 偏晚 17.5s） |
| 飞行模式 EPG 兜底 | **未验证**（adb 走 WLAN，关 WiFi 会断连，无法在不失联下测离线） |
| 不回归 | 167 换台/MP2/无崩溃 通过；OSD 场景变化不可判定；F1/F3/F4/清空/设置滚动未逐项 |

## 1. 身份/安装（PASS）
- app `490208a98a257b33a6b6b4212cc7c31e4d4ee7b95780febfc2464ef455c91a9d`（13,059,991B，match）/ test `e1e3f66af202f11c09840ac415b57ebdfafe8fd5190e7f7c86b40eda1b754cd0`（502,955B，match）；签名 `09dfdf79…`；10123/2.2.0-tivimini-65ccb3fd-validation；backup 10122（1,465,344B）；原包保留。

## 2. Zap 埋点（PASS，10 行样例）
`setprop log.tag.IQTV-ZAP DEBUG` + force-stop 重启后，10 次连续换台，`logcat -s IQTV-ZAP` 得到 **恰好 10 行**：
```
zap=1459ms prepare=0ms firstFrame=1459ms
zap=1160ms prepare=0ms firstFrame=1160ms
zap=872ms  prepare=0ms firstFrame=872ms
zap=749ms  prepare=0ms firstFrame=749ms
zap=1206ms prepare=0ms firstFrame=1206ms
zap=486ms  prepare=0ms firstFrame=486ms
zap=1156ms prepare=0ms firstFrame=1156ms
zap=1078ms prepare=0ms firstFrame=1078ms
zap=505ms  prepare=0ms firstFrame=505ms
zap=401ms  prepare=0ms firstFrame=401ms
```
- 计数：10 次换台 = 10 行（此前 CI10122 为 0 行，**埋点缺陷已修**）。
- 分段：`prepare=0ms`（onZap 紧邻 prepare），`firstFrame=zap`（全部耗时在 prepare→首帧，即网络/解码/首帧呈现）；**时间全部花在 firstFrame 段**。

## 3. TTFF 表（p50/p95）
| 版本/口径 | p50 | p95 |
|---|---|---|
| CI10121 基线（对照） | 996 | 1317 |
| **CI10123（本版，ZAP zap 段）** | **872** | **1459** |
| 差值 | **−124ms（−12.4%）** | **+142ms（+10.8%，变差）** |
- 分段中位：prepare 中位 **0ms**；firstFrame 中位 **872ms**（=zap）→ **瓶颈在 prepare 之后的网络/解码/首帧呈现**，不在 prepare 调度。
- 说明：CI10122 interim 765/1413 来自不同口径（IQTV-METRICS ttff_ms）与不同 run，仅供参考，不与本表直接等同。

## 4. rebuffer
- 换台过程：状态回放 `rebuffers=0`。
- **2 分钟稳态**：无 `state session` 变化/无 rebuffer 事件（samples=0）→ 稳态未见新增 rebuffer。
- **冷启启动瞬间**：`first_frame ttff=3588ms` 后出现 `state=2 rebuffers=1` → `state=3 rebuffer_ms=5836`（**启动 5.8s 重缓冲**，体感偏长，需关注；CI10121 冷启也有启动 rebuffer，量级需另比）。

## 5. 冷启 epgCache 顺序（PASS）
- `first_frame ttff_ms=3588`（09:12:43.3）
- `IQTV-ZAP: epgCache=17473ms firstFrame=true`（09:13:00.8）→ **epgCache 晚于首帧（firstFrame=true 标记）PASS**；但 17.5s 才加载完 EPG 缓存偏晚（后续可优化）。

## 6. 飞行模式 EPG 兜底（未验证）
- **无法执行**：本设备 adb 通过 WLAN（192.168.2.245:5555），关闭 WiFi/飞行模式会**立即断开 adb**，无法在不失联的前提下驱动并抓日志。需改用 USB adb 或开发在设备侧自测；本轮跳过并记录限制。

## 7. 不回归
- 167 换台：`c71913… → c88085… changed=True`；`audio_decoder ffmpegLavc60.3.100-mp3 init=4`、`audio/mpeg-L2 48k/2ch`、`AudioTrack ID:431 started`、`first_frame ttff=597`；`crash=0`。
- OSD：base bright0.0%/dark0.0%（亮场景）vs digits bright0.16%/dark1.01% → 不可判定。
- F1/F3/F4/清空/设置滚动 未逐项复测（本轮侧重性能）。

## 8. 逐项
| 项 | 结果 |
|---|---|
| 核验/安装 | 通过 |
| Zap 10 行（含分段） | 通过 |
| TTFF p50/p95 + 分段 | 通过（p50 872 / p95 1459；prepare 0 / firstFrame 872） |
| 2 分钟稳态 rebuffer | 未见新增（samples=0）；冷启启动 rebuffer 5.8s 需关注 |
| 冷启 epgCache 不早于首帧 | 通过（17.5s，firstFrame=true） |
| 飞行模式 EPG 兜底 | 未验证（adb-over-WLAN 限制） |
| 不回归 | 167/MP2/无崩溃 通过；其余未逐项 |

## 9. 结论与建议
- **Zap 埋点修复确认**（0→10 行）；TTFF **中位改善 ~12%（996→872）**，但 **p95 尾部变差（1317→1459）**，冷启启动出现 **5.8s rebuffer**。
- 时间全部花在 **prepare→firstFrame**（网络/解码/首帧），非 prepare 调度 → 若继续优化应针对首帧（预连接/解码启动/首帧渲染），而非 LoadControl min。
- 按任务回退判据（TTFF 未改善或 rebuffer 增加→回退 500）：**中位改善但尾部+冷启 rebuffer 变差，属“部分改善、需权衡”**；建议开发评估是否保留 300 臂（对比 500 臂的 p95/冷启 rebuffer 后再定），并复测运行期 jank/流畅度。
- 飞行模式 EPG 兜底需 USB adb 或设备侧自测补验。
- 未改代码、未改原包数据；设备现为 CI10123（PlayerActivity 前台，MP2 正常）。
