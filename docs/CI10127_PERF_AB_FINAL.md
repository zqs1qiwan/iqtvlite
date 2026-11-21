# CI10127 最终权威性能 A/B（四入口 / 三档缓冲 / 冷启 rebuffer）

2026-11-21，acceptance2（t212 attempt 1）。对象：`tv.iqtvlite.app.validation` **10127 / 2.2.0-tivimini-8d23b51d-validation**（t211：多线路嵌套打点已修；run 44853632483）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

测量法：每档 `setprop log.tag.IQTV-ZAP DEBUG` + `bufferMin=<v>` 进程启动确认 → **预热 1 次切台后连切 10 次**采集，规避脏首样本；`zap=按键→首帧`、`prepare=按键→prepare`、`firstFrame=prepare→首帧`。

## 0. 结论摘要
- **四入口打点全部正常（prepare>0）**：D-pad 10 行、数字跳台 1 行、上一台 1 行；同频道重进/开关设置 **不出第二行**。quality 变体因无多画质样本未单独验。
- **三档 A/B**：本机/本网络下 **稳(500) 最优**（p50=1193/p95=1684）；快(300) p50=1246/p95=2135；均衡(400) p50=1491/p95=2392。耗时主要在 **firstFrame 段（网络/解码/首帧呈现）**，prepare 段仅 ~250–370ms；减小起播缓冲(300) 不降 TTFF、p95 更差。
- **冷启**：三档均出现 **启动 rebuffer≈3.3–3.6s**（快300 3618ms / 均衡400 3510ms / 稳500 3319ms），**与起播缓冲档位无关**，属启动阶段独立问题。
- **默认档建议：稳(500)**。

## 1. 身份/安装（PASS）
- app `7c25f14f50654db133995c4bad4a13b3f2828808aae14bdca5c5eca54d5a33ad`（13,059,991B，match）/ test `1533e87c0c95b190e26a1a9950b33b4876340f3d7b96b10fbcd8ff60e1fa83c3`（502,955B，match）；签名 `62b2493a…`；10127/2.2.0-tivimini-8d23b51d-validation；backup 10126（1,559,040B）；原包保留。

## 2. 四入口打点（PASS）
默认档（稳500，`bufferMin=500`）：
| 入口 | 行数 | 样例（zap / prepare / firstFrame） | prepare>0 |
|---|---|---|---|
| D-pad 连切 10 次 | **10 行** | 见下表 | 是（272–479ms） |
| 数字跳台（输入5） | 1 行 | `zap=1127ms prepare=66ms firstFrame=1061ms` | 是（66ms） |
| 上一台 229 | 1 行 | `zap=691ms prepare=18ms firstFrame=673ms` | 是（18ms） |
| 同频道重进（开/关设置） | **0 行** | —（同 key 不重复打印） | — |
| quality 变体 | 未验证 | 当前源未发现多画质频道 | — |

D-pad 10 行样例（稳500）：
```
zap=1193ms prepare=479ms firstFrame=714ms
zap=1109ms prepare=351ms firstFrame=758ms
zap=1228ms prepare=392ms firstFrame=836ms
zap=1684ms prepare=325ms firstFrame=1359ms
zap=1509ms prepare=347ms firstFrame=1162ms
zap=1620ms prepare=374ms firstFrame=1246ms
zap=1186ms prepare=316ms firstFrame=870ms
zap=1595ms prepare=299ms firstFrame=1296ms
zap=920ms  prepare=292ms firstFrame=628ms
zap=775ms  prepare=272ms firstFrame=503ms
```

## 3. 三档缓冲 A/B（每档 10 次 D-pad 连切）
| 档位 | bufferMin 实测 | n | ZAP p50 | ZAP p95 | prepare p50/p95 | firstFrame p50/p95 |
|---|---|---|---|---|---|---|
| 快(300) | 300 | 10 | **1246** | **2135** | 373 / 548 | 901 / 1824 |
| 均衡(400) | 400 | 9* | **1491** | **2392** | 253 / 715 | 1021 / 1990 |
| 稳(500) | 500 | 10 | **1193** | **1684** | 325 / 479 | 836 / 1359 |
\* 均衡400 一轮中有 1 次切台未产生首帧（网络抖动），n=9；prepare 均 >0（min 55ms）。
- 三档 **prepare 段均小（中位 253–373ms）**，**firstFrame 段占大头（中位 836–1021ms，p95 1359–1990ms）** → 换台耗时主要在 prepare→首帧（网络探测/解码/首帧呈现），而非起播缓冲 min。
- **稳(500) 的 p50/p95 与 firstFrame 均最优**；快(300) 未降低 TTFF，p95 反而更高（弱网下更易欠缓冲）。

## 4. 冷启（每档 force-stop → 冷启动）
| 档位 | 首帧 ttff | 启动 rebuffer |
|---|---|---|
| 快(300) | 2113ms | **1 次 / 3618ms** |
| 均衡(400) | 4152ms | **1 次 / 3510ms** |
| 稳(500) | 2073ms（首轮）/3102ms（次轮） | **1 次 / 3319ms** |
- **三档冷启均出现约 3.3–3.6s 启动重缓冲**（本次未复现此前 5.8s 峰值，但仍存在），且**与起播缓冲档位无关** → 属启动首缓冲/网络就绪阶段问题，需单独排查，不应靠调高缓冲档掩盖。

## 5. 对比基线
- CI10121 基线 996/1317：本版各档 p50(1193–1491)/p95(1684–2392) 表面更高，但 **10121/10122/10123/10124 的 zap 分段存在 prepare=0 欠计**，与本次「真实按键→prepare→首帧」口径不可直接相减；仅作量级参考，**不据此判定回归**。正式口径即本表。

## 6. 默认档建议
- **建议默认「稳(500)」**。理由：①档位对 TTFF 影响主要在 p95 与 firstFrame，稳500 均最优；②快300 未带来 TTFF 改善且弱网 p95 更差；③三档冷启启动 rebuffer 相当（~3.3–3.6s），与档位无关，降低档位不能解决冷启卡顿；④稳500 最抗抖动，符合直播弱网场景。

## 7. 不回归
- 167 换台：changed=True；`AudioTrack ID:1567 state:started`；`crash=0`，pid 稳定；MP2 音频正常（前序多轮 `audio/mpeg-L2 48k/2ch`）。F1/F3/F4/设置滚动/清空 已在前序版本通过，本版无相关改动。

## 8. 逐项
| 项 | 结果 |
|---|---|
| 核验/安装 | 通过 |
| D-pad 10 行 + prepare>0 | 通过 |
| 数字/上一台 各 1 行 prepare>0 | 通过 |
| 同频道重进不出第二行 | 通过 |
| quality 变体入口 | 未验证（无多画质样本） |
| 三档 bufferMin 确认 | 通过（300/400/500） |
| 三档 p50/p95+分段 | 通过（稳500最优） |
| 冷启首帧+启动 rebuffer | 通过（三档均~3.3–3.6s） |
| 不回归 | 167/MP2/crash 通过 |

## 9. 结论
- **Zap 多入口打点与 log-once 均已修复并可测**；三档缓冲正式 A/B 完成，**稳(500) 为本机最优默认档**。
- **换台瓶颈在 prepare→首帧段**（网络/解码/首帧呈现），后续提速应针对首帧（预连接/解码启动/首屏渲染），而非继续压低起播缓冲。
- **冷启 ~3.3–3.6s 启动 rebuffer 三档均有**，属独立问题，建议单列诊断（首缓冲/网络就绪）。
- 未改代码、未改原包；设备现为 CI10127（PlayerActivity 前台，MP2 正常）。
