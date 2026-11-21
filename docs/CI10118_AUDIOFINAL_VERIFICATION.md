# CI10118 F4 收口版 真机验证

2026-11-21，acceptance2（t194 attempt 1）。对象：`tv.iqtvlite.app.validation` **10118 / 2.2.0-tivimini-b1da5ff4-validation**（t187：换台清 override / 单音轨门控 / dedupe；run 44836837180）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

## 0. 摘要

| 项 | 结果 |
|---|---|
| 身份/安装 | **通过**（app `32ce84f3…4c6c` 13,059,987B / test `add768ae…6cee`；签名 `61832b87…`；10118；安装后 base match） |
| MP2 音频正常 | **通过**：`audio_decoder ffmpegLavc60.3.100-mp3`、`audio_format audio/mpeg-L2 48k/2ch`、AudioTrack started、无崩溃 |
| **override 不残留（多音轨选第2轨→换台→回）** | **未验证**：测试源无多音轨频道（全部单 MP2 轨），无法构造“第 2 轨” |
| **单音轨门控（不可选/Toast/不开框）** | **未验证（被 UI 阻塞）**：`音频与字幕` 分组在设置列表**屏幕外且列表不可滚动**（DOWN×10、swipe 双向均无效，dump 始终止于 `频道键 166/167`） |
| **去重（重复上报只一行）** | **未验证**（同上，音频设置不可达；源内也无重复上报样本） |
| **音频框 Back 只关自身** | **未验证**（音频框无法打开：单音轨门控 + 设置不可达） |
| 不回归 | 167 换台/MP2/AudioTrack/无崩溃 **通过**；数字 OSD 像素受场景变化影响不可判定；遥控习惯默认 PASS（上下换台/OK 开列表） |

## 1. 身份/安装（PASS）
- app `32ce84f39f1f08a417021b01713a305439eaaec4ddaf42e0916d4527022c4c6c`（13,059,987B，match）/ test `add768ae8ed77662fc4fac8aaa26bb1272008a50ceb4473549aba787a66ceeeb`（502,955B，match）；签名 `61832b87…`；10118/2.2.0-tivimini-b1da5ff4-validation；安装后 base SHA match；backup 10117（1,522,688B）后仅替换；原包保留。

## 2. MP2 音频（PASS）
- `audio_decoder session=… name=ffmpegLavc60.3.100-mp3 init_ms=31`
- `audio_format … mime=audio/mpeg-L2 codecs=unknown rate=48000 channels=2`
- `audio_track_initialized … encoding=2 rate=48000 channelConfig=12 bufferBytes=96000`
- `dumpsys audio`: `AudioTrack ID:103 … state:started usage=USAGE_MEDIA`
- 换台后（167）：`c75702…`、`first_frame ttff=1210ms`、AudioTrack started；crash=0。

## 3. 设置可访问性（阻塞点）
- 设置抽屉 dump 稳定只显示：`设置 | 基础 | 源配置 | 更新EPG | 检查更新 | 最近观看 | 遥控器与操作 | 上下键：换台 | OK键：打开频道列表 | Back键：关闭抽屉 | 数字键：直接跳台 | 频道键 166/167：启用`
- **`音频与字幕`（音轨选择行）、`数据与更新`、`台标与缓存` 在屏幕外**；`DOWN×10` 不滚动，`swipe 1700,300→900` 与 `1700,900→300` 均不滚动（dump 完全不变）。
- → 本轮**无法进入音轨选择**，所有与音频对话框相关的断言（单音轨门控、Toast、不开框、Back、去重展示）**均无法执行**。这与已知的 t192（设置列表可滚动/bringIntoView）问题一致。

## 4. 未验证明细
| 断言 | 原因 |
|---|---|
| 多音轨选第 2 轨 ▶ 保持、banner 同步 | 源内无多音轨频道（全部单 MP2），不可构造 |
| 换台后回自动、旧 override 不残留 | 同上；需要多音轨源 |
| 单音轨行显示「不可选」、OK Toast、不开框 | 音频设置不可达（列表不滚动） |
| 无音轨频道提示 | 源内无“无音轨”频道样本 + 设置不可达 |
| 重复上报只一行 / 同组不同真实轨保留 | 无重复上报样本 + 设置不可达 |
| 音频框 Back 只关自身 | 音频框无法打开 |

## 5. 不回归
- 167 换台：`NONE → c75702… changed=True`；MP2 音频正常；`crash=0`，pid 稳定。
- 默认遥控习惯：上下换台、OK 开列表正常（与 t191 默认结论一致）。
- 数字 OSD：base bright4.55%/dark3.12% → 输入 1,2 后 bright19.06%/dark4.84%（**场景变化**导致 bright 上升，不能判定 OSD；需固定暗场景复测）。
- 台标/灰屏/AAC 未测。

## 6. 逐项
| 项 | 结果 |
|---|---|
| 身份/安装 | 通过 |
| override 不残留 | 未验证（无多音轨源） |
| 单音轨门控 | 未验证（设置列表不可滚动） |
| 去重 | 未验证（同上 + 无样本） |
| Back 只关自身 | 未验证（音频框开不了） |
| MP2/AAC/加载/遥控默认/OSD/频道键/不崩 | MP2/167/遥控默认/不崩 通过；AAC/OSD/台标 未定 |

## 7. 判定与建议
- **无法完成 F4 收口验收**：核心断言均被两个外部条件阻塞——①**设置列表不可滚动**（音频与字幕分组不可达，属已知 t192 待修，真机可复现）；②**测试源无多音轨频道**（override 不残留/去重/单音轨门控需要多轨或重复上报样本）。
- 建议：①先修 t192 的列表滚动（或提供直达入口/测试用 testTag 点击路径）；②由开发提供多音轨频道 URL（或 mock 多轨源）后再复测 override/单音轨/去重；③本版仅确认 MP2 音频与换台/无崩溃未回退。
- 未改代码、未改原包数据；设备现为 CI10118（PlayerActivity 前台，MP2 正常）。
