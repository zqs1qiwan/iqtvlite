# CI10128 首帧分段定位（fmt/dec/render）

2026-11-21，acceptance2。对象 `tv.iqtvlite.app.validation` **10128 / 2.2.0-tivimini-bd60d593-validation**（pair run44856764556，app `f93048b9…3c69` 13,059,991B / test `e59cf425…4265` 502,955B；签名 `4505da41…`；artifact 14687418881）。设备 `192.168.2.245:5555`。只替换 validation；原包保留（backup 10127 1,578,496B）。

## 0. 结论（关键）
- **CI10128 的 fmt/dec/render 分段埋点存在但不输出**：`setprop log.tag.IQTV-ZAP DEBUG` + force-stop 重启 + 预热1次 + 连切10次，**10/10 行仅 `zap=/prepare=/firstFrame=`，均无 `fmt=/dec=/render=`**。
- **根因（源码级，可复现）**：`ZapTrace.onZap()`（`onZapRequested`→`onZap`）只重置 `zapAt/prepareAt/firstFrameAt/lastZapMs`，**未重置 `formatAt`/`decoderAt`**。上一频道残留的 `formatAt < 新 prepareAt` → `formatDelayMs()`/`decoderDelayMs()` 返回 **-1** → `summaryLine()` 走 base 分支（`if (fmt>=0 && dec>=0 && render>=0)` 不成立），**省略 fmt/dec/render**。故自第二个频道起全部丢失分段。
- **故无法按设计给出 fmt/dec/render 三段 p50/p95**；只能给 zap/prepare/firstFrame 二段拆分（见 §2）。
- **埋点包无回归**：安装/启动/切换正常，rebuffers=0（切换期）、crash=0、MP2 正常。

## 1. 采到的 10 行（原样，无 fmt/dec/render）
```
zap=591ms prepare=238ms firstFrame=353ms
zap=791ms prepare=231ms firstFrame=560ms
zap=1020ms prepare=263ms firstFrame=757ms
zap=1504ms prepare=239ms firstFrame=1265ms
zap=742ms prepare=226ms firstFrame=516ms
zap=1612ms prepare=240ms firstFrame=1372ms
zap=845ms prepare=312ms firstFrame=533ms
zap=1172ms prepare=309ms firstFrame=863ms
zap=1076ms prepare=222ms firstFrame=854ms
zap=1216ms prepare=244ms firstFrame=972ms
```
（预热 1 次后 10 连切；10 行，log-once 正常，同频道不重复。）

## 2. 可用分段统计（10 样本）
| 段 | p50 | p95 | min | max | 占 zap 中位 |
|---|---|---|---|---|---|
| zap（按键→首帧） | 1020 | 1612 | 591 | 1612 | 100% |
| prepare（按键→prepare） | 239 | 312 | 222 | 312 | ~23% |
| firstFrame（prepare→首帧） | **757** | **1372** | 353 | 1372 | **~74%** |

## 3. 埋点缺失根因核对
- APK 内字符串存在：`classes.dex` 含 `fmt=`、`render=`、`onInputFormatChanged`、`onVideoDecoderInitialized`（说明构建**含** t213 代码，非缺代码）。
- 源码 `ZapTrace.onZap()`（ZapTrace.kt:66-71）：
  ```
  fun onZap(now) { zapAt=now; prepareAt=0; firstFrameAt=0; lastZapMs=-1 }  // 缺 formatAt=0; decoderAt=0
  ```
- `formatDelayMs()`（:96）要求 `formatAt>=prepareAt`；新 zap 后 `prepareAt` 更新而 `formatAt` 仍是上一台旧值（更小）→ 返回 -1；`summaryLine()`（:146）条件 `fmt>=0 && dec>=0 && render>=0` 不满足 → 只输出 base。
- **修复**：`onZap()` 增 `formatAt = 0L; decoderAt = 0L`（一行）；再重测即可得 fmt/dec/render。

## 4. 下一臂建议（初步，依据现有二段）
- 时间花在 **firstFrame 段（prepare→首帧，中位 757ms，占 ~74%）**，prepare 仅 ~23%。
- 在 fmt/dec/render 修好前，只能确定**瓶颈在 prepare 之后**（网络填充/解码初始化/首帧渲染三者合并）。据 t212 冷启观察（解码器 init ~81ms、EPG 延后到 18.6s 与首帧无关），**预估大头在“网络填充+首帧呈现”而非解码器创建**。
- **建议下一臂优先做 firstFrame 段 A/B**：
  - A：**网络预连接/首秒吞吐**（相邻频道预连、或提高初期 loader 优先）——预期上限：若网络填充占 firstFrame 的多数，可省数百 ms；
  - B：**解码器预热/首帧快速出图**（Surface 首帧提交路径）——预期上限较小（解码器 init 仅 ~80ms）；
  - C：**minBuffer/起播预缓冲策略**——t212 已证对 TTFF 中位影响小，优先级最低。
- **前提**：先修 `ZapTrace.onZap` 的 formatAt/decoderAt 重置（一行），重跑本任务拿三段 p50/p95，再据“dec vs render”精确排序 A/B/C。**在此之前无法给出严谨的分段上限，仅二段结论：firstFrame 主导。**

## 5. 回归
- rebuffers：切换期 state 行 rebuffers=0；crash=0；安装后正常起播、MP2/avc 正常。未改代码/原包。

## 6. 受控实验（证明“永远拿不到 fmt/dec/render”）
冷启动（bufferMin=500，本次首播源为 **video/hevc 3840×2160**）：
- 首播：`bufferMin=500` → `decoder OMX.goke.video.decoder.hevc init_ms=99` → `format video/hevc 3840×2160` → `first_frame ttff_ms=10542` → `epgCache=9967ms firstFrame=true`。**首播无 IQTV-ZAP 行**——因自动播放未走用户 arm，`zapKey=null`，`summaryLineOnce()` 立即返回 null（代码 :130 `val key = zapKey ?: return null`）。
- 随后 1 次切台：`zap=2668ms prepare=711ms firstFrame=1957ms`——**仍只 base，无 fmt/dec/render**。
- **根因链**：初始自动播放已把 `formatAt`/`decoderAt` 置为“开机时刻”；此后每次用户切台 `onZap()` 只清 `zapAt/prepareAt/firstFrameAt`，**不清 `formatAt/decoderAt`** → 旧 `formatAt < 新 prepareAt` → `formatDelayMs()/decoderDelayMs()`=-1 → `summaryLine()` base 分支。**结论：当前 CI10128 下，用户切台永远无法产出 fmt/dec/render**（首播无行、切台退化）。这是**确定性缺陷**，非偶发。

## 7. 判定
- **fmt/dec/render 三段埋点未生效（源码重置缺陷 + 首播未 arm）**，本任务**无法产出三段 p50/p95**；已给二段数据与确定性修复点。
- **修复（一行级）**：`ZapTrace.onZap()` 增加 `formatAt = 0L; decoderAt = 0L`；另建议自动播放也调用 `onZapRequested`（用 groupKey/channelKey）以便首播可测。
- 建议：修好出新 CI 后复跑本任务拿三段；**在拿到 dec/render 精确值前，据现状唯一可用结论：firstFrame 段主导（~74%），下一臂方向为 firstFrame（网络填充/解码后首帧呈现），A/B 之二选需修复后定**。
