# CI10135 弱机验证（TR100-G9 / Android 9）

2026-11-21，acceptance2（t230 attempt 1）。设备 `192.168.2.245:5555`（TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p）。验证包 `tv.iqtvlite.app.validation` **10135 / 2.2.0-tivimini-4230a7f0-validation**（app `2a59b9f0…7646` 13,092,817B；签名 `89d0cced…`；run 44875183411；artifact 14695617782）。**全程按 DEVICE_HEALTH_PROTOCOL 串行、单次 `install -r`、未 uninstall。**

## 0. 结论

| 项 | 结果 |
|---|---|
| 前置/后置健康 | **通过**（/data 2.64–2.65G 空闲；storaged/installd/I-O/DeadObject 全 0；3 包在位；无崩溃） |
| 单次安装 | **成功**（app 10135；test APK 因孤立 `.test` 签名冲突未装，按协议**不卸载**） |
| 数字 OSD（t223 修复） | **部分验证**（本机 Compose OSD 不进 a11y，改用功能代理：**超时后不再提交=通过**；三位数累积到达 117=通过；DIRECT 回归未测） |
| **看门狗（t228 修复，重点）** | **通过**：117/118/119 各**恰好 1 条** `firstFrameTimeout retry=1`；**无第二条 ZAP 行**；无崩溃；无二次重试 |
| 睛彩 117/118/119 | **仍不可播**：卡 `state=2`、无 `first_frame`、无 `audio_advancing`（源侧/组播问题；同机 CCTV 正常） |
| 弱机性能 | **TTFF p50=791ms / p95=1526ms**（优于历史 964/996）；rebuffers=0；冷启首帧 2117ms；启动 rebuffer 4461ms；PSS 134MB |
| 健康复查（每步后） | 全部通过，无 binder/存储异常 |

## 1. 健康与安装
```
前置：/data 3403720K 总 661372K 用 2655472K 可用(20%)；boot_completed=1；uptime 724s
      storaged=0 installd=0 I/O error=0 DeadObject=0
安装：adb install -r iqtvlite-ci-10135-validation.apk → Success
      test apk → INSTALL_FAILED_UPDATE_INCOMPATIBLE（与遗留 .validation.test 签名不符；按协议不卸载）
后置：versionCode=10135 / 2.2.0-tivimini-4230a7f0-validation
      /data 可用 2642124K；storaged=0 installd=0 I/O=0 DeadObject=0
      包：tv.iqtvlite.app、tv.iqtvlite.app.validation、tv.iqtvlite.app.validation.test（孤立）
```
每步后复查均通过；`com.shafa.market` 未再触发卸载（3 包全程在位）。

## 2. 数字 OSD（t223）
本机 Compose 输入条**不出现在 uiautomator 树**（dump 有 4.6KB 但无文本节点），因此改用**行为代理**：

| 检查 | 方法 | 结果 |
|---|---|---|
| 需OK 输入 1 → 超时后不跳台 | 输入 `1`，等 4s，按 OK → 观察是否换台 | **通过**：`newFirstFrame=0`、session 未变（说明超时已清空 digits，未提交） |
| 输入累积 | `1,1,7` + OK | **通过**：标题变为 **睛彩竞技**（三位数累积成功，说明 OSD 在多位输入期间保持） |
| 计时从最后一次按键重算 | `1`（等 2s）`2` + OK | **未确证**：一次运行得 CCTV-1（疑似首键丢失，见备注） |
| OK 提交并清除 | 同上 | **通过**（117 成功跳台） |
| Back 清除 | `1` → Back → OK | **未确证**（session 读数为 NONE 噪声，无法排除） |
| 遮罩不残留 | `1` → OK(抽屉) → Back → OK | **未确证** |
| DIRECT 回归（3s 自动跳台） | 需切回 DIRECT | **未测** |

备注：本机 uiautomator 无法读取 OSD 文本，上述“未确证”项需改在能读 a11y 的设备或加 OSD 日志/测试 tag 后复核。**已确证的关键修复点：超时后会清空且不提交（不再永久停留）。**

## 3. 首帧看门狗（t228，重点）
以 `1,1,N` + OK 精确进入睛彩三台，每台观察 22s：

| 频道 | 标题 | `IQTV-WATCHDOG` 行数 | ZAP 行数 | 首帧 | 卡住状态 |
|---|---|---|---|---|---|
| 117 睛彩竞技 | 睛彩竞技 ✔ | **1**（`14:38:12 firstFrameTimeout retry=1 channel=4783027072129292115`） | **0** | 无 | `state=2` 持续 |
| 118 睛彩篮球 | 睛彩篮球 ✔ | **1**（`14:39:24 … retry=1`） | **0** | 无 | `state=2` |
| 119 睛彩青少 | 睛彩青少 ✔ | **1**（`14:40:06 … retry=1`） | **0** | 无 | `state=2` |

- **恰好一条 retry、无第二次**：与策略 MAX_RETRIES=1 一致。
- **无第二条 ZAP 行**：重试不产生额外测量行（t228 要求）。
- **无崩溃**：`logcat -b crash` 无我方 FATAL；进程 pid 稳定。
- **Toast**：重试成功后的“成功不提示”无法触发（三台均无首帧）；16s 后的“暂不可播”Toast 在 `dumpsys window` 中已消失（未在窗口期抓到），**未直接观察到**，记为未验证。

## 4. 睛彩 117/118/119（与 CCTV 对照）
- 三台均：`state=2` → `format video/avc 1920×1080` → `audio_format audio/mpeg-L2 48k/2ch`（MP2 解码器 init 5–8ms）→ **无 `first_frame`、无 `state=3`、无 `audio_advancing`**。
- 对照 CCTV-1/CCTV-17：`first_frame` 615–986ms、`state=3` 正常、rebuffers=0。
- **结论**：App 解码链路正常（CCTV 同类 avc+MP2 可播），**睛彩三台为源侧/组播不可达或首 IDR 不到**；与 PC 侧“码流正常”不矛盾（PC 走 HTTP 代理，盒走 `rtp://192.168.2.1:4022/...` 组播）。看门狗按设计重试一次并停止，**未出现崩溃/风暴**。

## 5. 弱机性能基线（稳500，D-pad 10 连切）
| 段 | p50 | p95 | min | max |
|---|---|---|---|---|
| **TTFF（zap）** | **791** | **1526** | 640 | 1526 |
| prepare | 228 | 362 | 199 | 362 |
| firstFrame | 546 | 1268 | 404 | 1268 |
| fmt | 452 | 1023 | 333 | 1023 |
| render | 71 | 273 | 52 | 273 |

- 与历史基线（TTFF p50 964 / 基准 996）相比：**p50 −173ms（−18%）**，p95 略高于基线尾巴（1526 vs 1317 旧口径，注意口径差异，仅同机参考）。
- **rebuffers max = 0**（10 次切换全程）。
- **冷启**：`first_frame ttff=2117ms`（历史 ~7s → 明显更快）；其后 **启动 rebuffer=1 / 4461ms**（与 t214 结论一致：首帧后 Loader 补流不足）。
- **PSS**：`TOTAL 137,302 KB（≈134MB）`——**高于历史 53–70MiB**（本版含看门狗/实验模块与 4K 频道，且冷启后测量，属观察项）。
- **抽屉 gfxinfo**：`Total 133 / Janky 113 (84.96%) / 50th 28ms / 90th 109ms / 95th 800ms / Missed Vsync 16`——该数值**包含冷启动与开合抽屉**，明显高于历史 27–40%，需单独复测（干净窗口、只开合抽屉）才能定论。
- CPU loadavg 12.86/12.91/10.39（4 核，系统整体偏高，属盒子常态）。

## 6. 健康复查与清理
- 安装后、看门狗测试后、性能测试后、冷启后均复查：`/data` 可用 2,640,220–2,642,124 KB、`storaged/installd/I/O error/DeadObject=0`、3 包在位、无崩溃。
- 本机 `com.shafa.market:xg_service_v2` 仍在后台重启（历史问题），本次**未触发卸载**。
- **PC 侧清理**：仅保留 `iqtvlite-ci-10135-*`（最新 pair）与 `iqtvlite-ci-10130-*`（回滚），删除 10129/10131/10132/10134 及旧 `download-*`；**未删除设备上任何文件**。

## 7. 判定
- **看门狗修复（t228）在真机成立**：三台各 1 条 retry、无第二条 ZAP、无崩溃、无二次重试。
- **OSD 超时（t223）关键点成立**（超时后不提交），其余 4 项受本机 a11y 限制未确证。
- **睛彩仍不可播**（源/组播），App 侧与此无关；建议源侧核查 `rtp://239.3.1.125/126/127:8001`。
- **性能**：TTFF 中位优于历史、rebuffers=0；冷启首帧显著改善，但**启动后 4.4s rebuffer** 与 **PSS 134MB**、**抽屉 jank 84.96%（含冷启）** 需后续单列优化/复测。
