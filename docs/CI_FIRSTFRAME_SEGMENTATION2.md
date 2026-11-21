# 首帧三段定位（CI10129，t216 修复版复跑）

2026-11-21，acceptance2（t217 attempt 1）。对象 `tv.iqtvlite.app.validation` **10129 / 2.2.0-tivimini-84885824-validation**（run 44858617836；app `8924ee8f…b0c1` 13,059,987B / test `71281227…186a` 502,955B；签名 `eb726088…`；artifact 14689290545）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留（backup 10128 1,465,344B）。

## 0. 结论（先行）
- **最大段 = fmt（网络首包/首个 format），p50=1134ms / p95=1388ms，占 TTFF 的 ~94%**。
- dec（解码器 create/configure）**p50=7ms**、render（解码器就绪→首帧呈现）**p50=75ms / p95=317ms** —— 两者合计仅数十 ms，**不是瓶颈**。
- **下一臂应做 B（相邻频道预连接 / 首秒吞吐 / 网络首包）**，不是 A（解码预热）也不是 C（渲染）。
- **ZAP 行仍不带 fmt/dec/render 三段**（t216 修复未达成目标），但**根因已定位为新的第三处缺陷**（事件顺序），已用 logcat 时间戳直接算出三段（见 §2）。

## 1. 埋点行为（复跑验证）
| 检查 | 结果 |
|---|---|
| 冷启不按键 → 应有一行含三段 | **只有 base**：`zap=2234ms prepare=0ms firstFrame=2234ms`（有行，符合 armIfUnarmed 生效，但无三段） |
| D-pad 预热1+连切10 → 每行三段齐全 | **0/10 行带三段**，10/10 均 `MISSING-SEG`（base 形式） |
| 同频道重进不出第二行 | **通过**（`reenter zapLines=0`） |
| 行数（log-once） | 通过（每次切台 1 行） |

样例（10 连切，base 形式）：
```
zap=901ms  prepare=352ms firstFrame=549ms
zap=1264ms prepare=400ms firstFrame=864ms
zap=1737ms prepare=404ms firstFrame=1333ms
zap=1701ms prepare=354ms firstFrame=1347ms
zap=853ms  prepare=317ms firstFrame=536ms
zap=889ms  prepare=332ms firstFrame=557ms
zap=1643ms prepare=318ms firstFrame=1325ms
zap=1018ms prepare=210ms firstFrame=808ms
zap=1394ms prepare=215ms firstFrame=1179ms
zap=639ms  prepare=211ms firstFrame=428ms
```

## 2. 三段为何仍缺失（第三处根因，新发现）
用 `IQTV-METRICS` 事件时间戳对齐后可见**事件顺序与代码假设相反**：
```
11:08:47.864  decoder  name=OMX.goke.video.decoder.avc init_ms=80   ← decoder 先到
11:08:47.869  format   mime=video/avc 1920x1080                     ← format 后到
11:08:48.389  first_frame ttff_ms=2276
```
- `ZapTrace` 假设顺序是 `format → decoder → firstFrame`，于是 `decoderDelayMs() = decoderAt - formatAt`；实测 `decoderAt < formatAt` → **dec 为负数** → `summaryLine()` 的 `fmt>=0 && dec>=0 && render>=0` 不成立 → **整段被省略**（连 fmt 也一起丢）。
- 另：**切台复用同一 codec 时 `onVideoDecoderInitialized` 根本不触发**（10 次切换里仅 5 次有 decoder 事件），此时 dec 恒为 -1。
- 即：t216 修的 `onZap` 清零是必要但不充分的；**还需（a）允许 dec 为 0/负时按 0 处理或改判据，(b) 把 fmt 段定义为 `prepare→首个 format`、dec 段允许缺失（复用）**。

## 3. 三段实测（用 logcat 时间戳直算，10 次切台）
| # | TTFF | fmt(prepare→format) | dec(format→decoder) | render(decoder→首帧) |
|---|---|---|---|---|
| 1 | 1035 | 815 | 16 | 204 |
| 2 | 1281 | 1214 | 7 | 60 |
| 3 | 1702 | 1388 | -2* | 317 |
| 4 | 1277 | 1139 | 缺失* | 缺失 |
| 5 | 1543 | 1309 | 缺失* | 缺失 |
| 6 | 805 | 664 | 缺失* | 缺失 |
| 7 | 1626 | 1387 | 缺失* | 缺失 |
| 8 | 1064 | 892 | 缺失* | 缺失 |
| 9 | 1207 | 1134 | -1* | 75 |
| 10 | 870 | 768 | 55 | 48 |

\* `-2/-1` = decoder 事件早于 format（顺序颠倒）；`缺失` = codec 复用未触发 `onVideoDecoderInitialized`。

**统计**
| 段 | n | p50 | p95 | min | max | 占 TTFF p50 |
|---|---|---|---|---|---|---|
| TTFF | 10 | **1207** | 1702 | 805 | 1702 | 100% |
| **fmt** | 10 | **1134** | **1388** | 664 | 1388 | **~94%** |
| dec | 5 | 7 | 55 | -2 | 55 | ~0.6% |
| render | 5 | 75 | 317 | 48 | 317 | ~6% |

（prepare 段 = 按键→prepare，另一口径下 p50≈350–550ms，已含在 zap−firstFrame 中，不属于首帧窗口。）

## 4. 埋点包无回归
- 切换期 `rebuffers=0`；`crash=0`；MP2/avc 正常；`bufferMin=500` 正常打印。
- 与 CI10127 相比行为一致（同一套播放路径，仅多两次 `Log.isLoggable` 判断与时钟读取）。
- CPU 未做 jiffies 对比（埋点仅 3 次 `elapsedRealtime()` 调用，理论可忽略）；如需严格对比可后续补。

## 5. 下一臂建议（数据依据 + 预计上限）
**建议 B：网络首包/预连接（相邻频道预连接 + 首秒吞吐）**
- 依据：**fmt 段 p50 1134ms / p95 1388ms，占 TTFF ~94%**；dec+render 合计 p50 仅 ~82ms。
- 预计上限：若预连接能把“首包等待”从 ~1.1s 压到 ~0.3–0.5s（同网关 RTP 组播，加入组播组即可收包），**TTFF p50 有望从 ~1207ms 降到 ~500–700ms**（≈40–55% 改善）；p95 收益可能更大（尾部多由首包重试造成）。
- **不建议** A（解码预热）：dec p50=7ms，可省上限 <50ms，且 codec 复用已使多数切台无 decoder 事件。
- **不建议** C（渲染）：render p50=75ms/p95=317ms，可省上限 ~100–300ms 但需触 renderer 内部，风险高于收益。
- 前置建议：先修 §2 的三段判据（一行级：dec 缺失/负数按 0 处理），再上 B 的单变量 A/B（默认关 flag）。

## 6. 附：设备侧观察（非本任务结论）
- 采集中一度出现 **167/166 换台无响应且 IQTV 日志停写**（进程存活、AudioTrack started、`last_channel_title` 不变），force-stop 重启后恢复。疑似遥控事件被前台抢占（设备常驻 `com.dangbei.tvlauncher`/`com.qgbox`/`com.shafa.market:xg_service_v2`）或界面卡死；未复现第二次，记为**观察项**。

## 7. 判定
- 三段定位**已完成**（用 logcat 时间戳）：**fmt 段最大（~94%）→ 下一臂 B（预连接/首秒吞吐）**。
- ZAP 三段输出**仍未修好**（第三处根因：decoder 事件早于 format + 复用不触发），已给修复点；不影响本次结论。
- 未改代码、未改原包；设备 CI10129（PlayerActivity 前台）。
