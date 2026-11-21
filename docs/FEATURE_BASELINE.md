# 新需求现状基线（CI10096，只测不改）

2026-11-21，acceptance2（t123 attempt 1）。对象：`tv.iqtvlite.app.validation` **10096 / 2.2.0-tivimini-ae531f7f-validation**（当前设备版本）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p，density240/1.5x）在线。测试源 `https://iptv.laobaitv.net`（CCTV/睛彩走本地组播）。**只测不改：未改代码、未改原包数据。**

> 模型不读图；数值来自 iptables/gfxinfo/logcat/dumpsys/PIL 像素统计；截图存证据目录供人工。

## 0. 结论摘要

| 需求 | 现状基线 |
|---|---|
| 换台 TTFF | 10 连切：**p50=996ms，p95=1317ms**（541/625/688/747/996/1001/1014/1170/1287/1317） |
| 抽屉 jank | 稳定态开抽屉+8↓4↑：**27.30% janky / 50th11 / 90th69 / 95th150ms（282帧）**（尚可，但开抽屉/首次内容有卡顿） |
| 数字 OSD | **CI10096 上注入数字键未检出可见 OSD**（base≈digit1 区域近似一致；无 a11y 节点）→ 基线缺陷：数字输入不可见/无 OSD（源码工作树已有 t124 OSD 未进本 build） |
| 台标时延 | 清缓存后开抽屉：行1 台标区占位(均值28)持续 ≥6.3s，约 **6.3–8.7s 后才有内容** → **台标/首屏内容加载 ~6–9s（慢，网络/缓存冷启动）**；无 LogoLoader 日志，重复加载无法从日志确认 |
| 睛彩解码 | 睛彩组 3 台（117睛彩竞技/118睛彩篮球/119睛彩青少）；睛彩竞技**可播**：`audio_decoder name=ffmpegLavc60.3.100-mp3 init_ms=9`、`audio_format mime=audio/mpeg-L2 48000/2ch`、video avc1080p、dropped=0、无本 app 解码错误、出画 → **未见解码失败（历史无声问题已被 FFmpeg 扩展修复）** |
| 音轨/声道 | 可得：audio_flinger **48000Hz / 2ch(front-L/R) / PCM_16**；IQTV-METRICS `audio_format mime/rate/channels`；media_session state=3 |

## 1. 换台 / 菜单卡顿

### 1.1 换台 TTFF（连续 10 台，CHANNEL_DOWN）
原始（ms）：`541, 625, 688, 747, 996, 1001, 1014, 1170, 1287, 1317` → **p50=996，p95=1317，min=541，max=1317**。证据 `ttff-10switches.json`。
- 体感“卡”场景：**切台瞬间**（部分台首帧 >1.2s，含 rebuffer/初始化）；**抽屉打开**（首次内容绘制慢，尤其冷缓存见 §3）；**EPG 加载**（切入时节目列“暂无节目单”短暂）。

### 1.2 抽屉 gfxinfo jank（稳定态 25s 空闲→reset→LEFT+8↓+4↑）
`Total frames 282；Janky 77 (27.30%)；50th 11ms；90th 69ms；95th 150ms；Missed Vsync 10`。证据 `drawer-gfx.txt`。

## 2. 数字换台 OSD（现状缺陷）

- 注入 KEYCODE_1/2/3（短间隔）后立即截图：行/中心区域像素 **base mean≈[147,100,106] vs digit1 ≈[147,99,108]**（近似一致），全帧 diff 主要由视频运动主导（bbox 全屏、mean27），**uiautomator 无数字输入框节点** → **CI10096 未见可见数字 OSD**。
- 推断：当前 build 数字键疑似触发数字跳台但**无可视输入框/文字（不可见/黑字类缺陷）**；源码工作树已含 `t124: visible digit-entry OSD — dark panel + high-contrast brand ink`（PlayerActivity L190/228-241，3s 超时、BACK 取消、OK 提交），**该 OSD 未包含在 CI10096(ae531f7f) 内**。
- 证据：`osd-base.png / osd-1.png / osd-12.png / osd-before.png / osd-after-123.png`、`osd-dump.xml`（空）。

## 3. 台标加载时延（清缓存后）

- 清缓存：force-stop 后 `run-as` 显示缓存含 `logo_cache_v1`（已随 `rm -rf cache/*` 清空），源配置在 files/ 保留。
- 开抽屉后按 ~2s 节奏连续截图（screencap 本身约 2s/张）分析行1 台标裁剪区 `(96,100)-(156,190)`：

| 帧(开抽屉后) | 台标区 mean | std | 判定 |
|---|---|---|---|
| ~0.0s | 28,28,28 | 0 | 占位/空(均匀) |
| ~2.2s | 28,28,28 | 0 | 占位 |
| ~4.3s | 28,28,28 | 0 | 占位 |
| ~6.3s | 28,28,28 | 0 | 占位 |
| ~8.7s | 62.8 | 79.7 | 有内容（台标/文字出现） |
| ~10.7s | 62.8 | 79.7 | 有内容 |
| ~12.6s | 62.8 | 79.7 | 有内容 |

- → **清缓存冷启后，台标/首屏内容约在 6.3–8.7s 才出现（~6–9s，偏慢；网络 logo.laobaitv.net 拉取 + 首次绘制）**。logcat 无 LogoLoader/logo 相关日志（无法据此确认是否重复加载）。
- 证据：`logo-t9/2249/4291/6322/8695/10731/12605.png`、`logo_analyze.py`。

## 4. 睛彩系列解码

- 源 M3U 含组 `睛彩`，3 台：`117.睛彩竞技 / 118.睛彩篮球 / 119.睛彩青少`（tvg-id 睛彩竞技/睛彩篮球/睛彩青少）。证据 `jingcai-channels.xml`。
- 播放 **117.睛彩竞技**（session s34）logcat：
```
IQTV-METRICS: audio_decoder session=…s34 name=ffmpegLavc60.3.100-mp3 init_ms=9
IQTV-METRICS: audio_format session=…s34 mime=audio/mpeg-L2 rate=48000 channels=2
IQTV-METRICS: format session=…s34 mime=video/avc width=1920 height=1080
IQTV-METRICS: state session=…s34 state=3 rebuffers=0 rebuffer_ms=0 dropped=0
```
- 判定：睛彩竞技**音轨为 MPEG-1 Layer II，由 FFmpeg(`ffmpegLavc60.3.100-mp3`) 软解（init 9ms），视频 avc 1080p，dropped=0，出画(mean 88/65/53 非灰)**；**未出现 MediaCodec/ExoPlayer 初始化失败/黑屏/无音**。历史“睛彩/CCTV2 MP2 无声”问题在当前 MP2 扩展版已被修复。
- 备注（对 t121 的补证）：`ffmpegLavc60.3.100-mp3` 即 FFmpeg 扩展版本串 → **FFmpeg 扩展确已加载**。
- 证据：`jingcai-channels.xml`、`jingcai-play.png`、上方 logcat。

## 5. 音轨/声道信息可得性（供后续 UI）

- `dumpsys media.audio_flinger`：**Sample rate 48000 Hz；Channel count 2；Channel mask front-left/front-right；HAL/Processing PCM_16**。
- `IQTV-METRICS audio_format`：`mime=audio/mpeg-L2 rate=48000 channels=2`（切不同台可得 mp4a.40.2 等）。
- `dumpsys media_session`：我方 `state=PlaybackState {state=3, speed=1.0}`。
- → **音轨数(1)/声道(2/stereo)/采样率(48k)/编码(mime) 均可从 dumpsys + IQTV-METRICS 获取，供后续 UI 显示音轨/声道。**

## 6. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 换台 TTFF p50/p95 | 通过：996 / 1317ms（10 台） |
| 抽屉 janky%/90th | 通过：27.30% / 69ms |
| 数字 OSD 可见性/颜色 | 基线缺陷：CI10096 未见可见 OSD（不可见/无）；t124 OSD 未进本 build |
| 台标加载时延 | 通过（记录）：清缓存后 ~6–9s 出现；无日志判重复 |
| 睛彩解码 | 通过（已测）：睛彩竞技可播，audio=mpeg-L2 经 FFmpeg(lavc60.3.100) 软解、avc、dropped0、无错；未复现失败 |
| 音轨/声道可得性 | 通过：48000/2ch/mime 可取 |

## 7. 判定
- 基线已采集（换台 TTFF、抽屉 jank、数字 OSD 缺失、台标冷加载慢、睛彩解码现状、音轨信息可得性），仅测不改。
- 关键缺陷现状：**数字换台 OSD 在本版不可见（待 t124 落地）**；**台标冷缓存加载 ~6–9s 偏慢**；睛彩系列当前可播（MP2 经 FFmpeg），历史无声已解。
- 未改代码、未改原包数据；设备保持 CI10096。
