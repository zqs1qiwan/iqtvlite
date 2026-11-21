# CI10130 预连接 A/B（关 vs 开）—— 决定性性能验证

2026-11-21，acceptance2（t219 attempt 1）。对象 `tv.iqtvlite.app.validation` **10130 / 2.2.0-tivimini-8fd8d213-validation**（run 44861163859；app `3999e0da…35bf` 13,076,433B / test `c3e45c6d…06b4a` 502,955B；签名 `400ceeae…`；artifact 14688954871）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留（backup 10129 1,465,344B）。

## 0. 结论（先行）
- **A) 三段判据修复：通过**。冷启首播行与 10 连切**每行都带 `fmt/dec/render`**（此前仅首行/全缺）。
- **B) 预连接 A/B：未达标，建议保持默认关**。
  - 开启后 `IQTV-PRECONNECT` 确实生效：每轮 `attempted=2 connected=2 elapsed=15–30ms`。
  - 但 **TTFF p50 未改善**（OFF 964ms → ON 1373ms 首轮；ABBA 中 ON 1111/1350 vs OFF 1277/1988，差异落在噪声内），**全部轮次 p50 均 >900ms，远未达 ≤700ms 判定线**。
  - **fmt 段未见缩短**（OFF 560ms → ON 1107ms 首轮；ABBA ON 754–1039 vs OFF 1101–1228）。
  - 资源：PSS 波动大（OFF 75.8/120.8/92.5MB vs ON 99.4/101.6/98.6MB），**ON 组中位数偏高约 6–24MB**；无崩溃。
- **关键洞察**：预连接只做 `connect-only`（TCP 建连 15–30ms），但实际换台的 fmt 段是 **RTP/UDP 组播的“首个 TS 包/首个关键帧等待”**，TCP 建连不是瓶颈 → **预连接无法缩短 TTFF**。下一臂应针对「组播 join + 首关键帧（IDR）等待」，而不是 TCP 预连接。

## 1. A) 三段判据（PASS）
设备 `setprop log.tag.IQTV-ZAP DEBUG`，force-stop 冷启（bufferMin=500）：
- 首播行：`zap=2115ms prepare=0ms firstFrame=2115ms fmt=1842ms dec=0ms render=273ms` —— **三段齐全**（dec 因 decoder 早于 format 已按 0 处理，符合 t218 设计）。
- 预热 1 + 连切 10：**10/10 行带三段**，样例：
```
zap=1720ms prepare=440ms firstFrame=1280ms fmt=1055ms dec=0ms render=225ms
zap=1139ms prepare=394ms firstFrame=745ms  fmt=645ms  dec=0ms render=100ms
zap=1379ms prepare=342ms firstFrame=1037ms fmt=708ms  dec=0ms render=329ms
zap=834ms  prepare=332ms firstFrame=502ms  fmt=367ms  dec=0ms render=135ms
zap=856ms  prepare=308ms firstFrame=548ms  fmt=469ms  dec=0ms render=79ms
zap=1043ms prepare=306ms firstFrame=737ms  fmt=560ms  dec=0ms render=177ms
zap=964ms  prepare=271ms firstFrame=693ms  fmt=622ms  dec=0ms render=71ms
zap=1361ms prepare=216ms firstFrame=1145ms fmt=1040ms dec=0ms render=105ms
zap=772ms  prepare=198ms firstFrame=574ms  fmt=435ms  dec=0ms render=139ms
zap=886ms  prepare=229ms firstFrame=657ms  fmt=494ms  dec=0ms render=163ms
```
| 段 | p50 | p95 | min | max |
|---|---|---|---|---|
| TTFF | 964 | 1720 | 772 | 1720 |
| prepare | 306 | 440 | 198 | 440 |
| fmt | **560** | 1055 | 367 | 1055 |
| dec | **0** | 0 | 0 | 0 |
| render | 135 | 329 | 71 | 329 |

（与 t217 用 logcat 时间戳直算的 fmt p50=1134 相比偏低，因本次样本网络更好；趋势一致：**fmt 段最大，dec≈0，render 小**。）

## 2. B) 预连接关/开对比
设置「播放与显示 → 预连接相邻频道（实验）」：默认 `zap_preconnect_enabled=false`；点击后 `=true`，行文本「预连接相邻频道（实验）：开」。

### 2.1 首轮（各 n=10，关→开）
| 指标 | OFF | ON | 差 |
|---|---|---|---|
| TTFF p50 / p95 | **964 / 1720** | **1373 / 1761** | +409 / +41 |
| fmt p50 / p95 | **560 / 1055** | **1107 / 1354** | +547 / +299 |
| prepare p50 | 306 | 243 | −63 |
| render p50 / p95 | 135 / 329 | 121 / 204 | −14 / −125 |
| `IQTV-PRECONNECT` 行数 | 0 | **10**（attempted=2 connected=2 elapsed 15–30ms） | — |
| PSS（meminfo TOTAL） | 75,774 KB | 99,358 KB | +23.6MB |
| crash | 0 | 0 | — |

### 2.2 ABBA 复核（交替关/开/关/开）
| 轮次 | 状态 | n | TTFF p50 | TTFF p95 | fmt p50 | PRECONNECT | PSS |
|---|---|---|---|---|---|---|---|
| OFF-A | 关 | 2* | 1988 | 2425 | 1228 | 0 | 120.8MB |
| ON-B | 开 | 8* | 1350 | 2176 | 1039 | 10 | 101.6MB |
| OFF-C | 关 | 5* | 1277 | 2224 | 1101 | 0 | 92.5MB |
| ON-D | 开 | 10 | **1111** | **1343** | **754** | 10 | 98.6MB |
| ON-run2 | 开 | 10 | 983 | 1765 | 696 | 10 | — |

\* 部分切台未产生新 ZAP 行（同一频道/列表边界），样本不足；网络抖动明显（OFF 从 964 到 1988）。

### 2.3 判读
- **开启确实生效**（10/10 轮 `attempted=2 connected=2`，耗时 15–30ms），但**没有转化为 TTFF 改善**：首轮 ON 反而劣化（+409ms p50），ABBA 中 ON 的 1111/1350 与 OFF 的 1277/1988 差异**落在观测噪声内**，无法证明改善。
- **所有轮次 p50 ≥964ms（多数 >1100ms），远未达到判定线 ≤700ms**。
- **fmt 段（网络首包）未被预连接缩短** → 证明瓶颈不是 TCP 建连，而是 **UDP/RTP 组播的首包/首关键帧等待**；connect-only 预热对组播流无实际作用。
- 资源：PSS 在 ON 组中位偏高（98–102MB vs 75.8/92.5MB），并有 2 个后台连接短时存在（512MB 盒子需谨慎）。

## 3. 判定与建议
- **判定：不达标 → 保持默认关**（`zap_preconnect_enabled=false` 已恢复；实测 prefs 已回 false）。
  理由：①TTFF p50 未达 ≤700ms；②未观察到一致改善（首轮劣化，ABBA 差异在噪声内）；③PSS 有偏高迹象，无收益不值得承担资源与 2 个连接的风险。
- **对 B 臂的结论**：**TCP 预连接路线无效**（连接 15–30ms 相对于 fmt 560–1100ms 可忽略；且组播无 TCP 建连）。
- **下一臂建议（数据支撑）**：针对 **fmt 段的真实构成 = 组播 join + 首个 TS 包 + 首个 IDR 关键帧**：
  1. **提前 join 组播组并预读首个关键帧**（不是 connect-only），或在抽屉/空闲时对相邻频道做**短时收流并缓存首个 IDR**（内存代价需评估）；
  2. 或 **请求侧加速首包**（更短的重试/更低的首包等待超时、并行探测首个可用 variant，现 playGroupAuto 已在做 variant 探测但可缩短间隔）；
  3. **不建议**继续在 TCP 预连接上投入；**不建议** A（dec≈0ms）与 C（render 71–329ms，需触内部 API，收益小风险高）。

## 4. 不回归
- `167` 换台：`format video/avc 1920×1080` + `audio ffmpegLavc60.3.100-mp3 init=5` + `audio/mpeg-L2 48k/2ch` + `first_frame ttff=699ms` + `state=3 rebuffers=0 dropped=0`；`AudioTrack ID:1111 state:started`。
- 设置列表可滚动且「预连接相邻频道（实验）」行可达可点（`preconnectRowReachable=True`）；开关切关后 prefs 回 `false`。
- `crash=0`，进程稳定。
- 数字 OSD/清空/F1/F3/F4 未逐项复测（本版仅新增预连接开关与判据，无相关改动）。

## 5. 附表：原始日志
- 关闭组 10 行：`docs/evidence/2026-11-21-ci-10130/zap-off-10.txt`
- 开启组 10 行 + PRECONNECT：`docs/evidence/2026-11-21-ci-10130/zap-on-10.txt`
- ABBA 汇总：`docs/evidence/2026-11-21-ci-10130/abba.json`
- 截图：`look-10130.png`、`settings-preconnect-off.png`
