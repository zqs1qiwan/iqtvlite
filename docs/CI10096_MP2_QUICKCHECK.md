# CI10096 MP2 软解版 真机快验（MP2 有声 / AAC 不回归）

2026-11-21，acceptance2（t120 attempt 1）。对象：`tv.iqtvlite.app.validation` **10096 / 2.2.0-tivimini-ae531f7f-validation**（CI10096 = 在 CI10093 基础上集成 Media3 FFmpeg 音频扩展，EXTENSION_RENDERER_MODE_ON；run 44773892066/source ae531f7f）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。测试源 `https://iptv.laobaitv.net`（CCTV 走本地组播 RTP 192.168.2.1:4022）。

> 模型不读图；音频以 dumpsys audio / audio_flinger / logcat / IQTV-METRICS 计数据判定（未以“adb exit0”冒充）。

## 0. 结论摘要（两条决定性）

- **① MP2 有声：是（DECISIVE PASS）。** CCTV-3 播放日志：`audio_format mime=audio/mpeg-L2 rate=48000 channels=2`、`audio_advancing playout_start_ms 递增(since_prepare 1.2s)`、`AudioTrack(0xa7efb300)::start`；`dumpsys audio` 我方 `ID:231 type:android.media.AudioTrack u/pid:10092/9511 state:started usage=USAGE_MEDIA`；`audio_track mime=audio/mpeg-L2 supported=true selected=true`。APK 内含 `lib/arm64-v8a|armeabi-v7a/libffmpegJNI.so`(+NOTICE) → FFmpeg 扩展解码 MP2。**对比集成前（t23/t32：audio/mpeg-L2 平台 0 解码器→无声），现已出声。**
- **② AAC 不回归：是（PASS）。** AAC 频道 `audio_format mime=audio/mp4a-latm` 且 `audio_advancing`（仍出声）；AVC 视频正常；**10 次 MP2↔AAC 显式切换（mimes 交替 mp4a-latm/mpeg-L2）dead=0 不崩**；累计 20+ 次切台 pid 稳定。
- **不崩**：仅后台 `com.shafa.market:xg_service_v2` 崩溃（与本 app 无关）；**无 `Process: tv.iqtvlite.app.validation` 崩溃**，pid 存活、PlayerActivity 前台。
- **资源代价（需注意）**：PSS 冷启 ~69–76MiB（CI10093 ~54–65，+~10MiB，FFmpeg .so 常驻）；稳态 appCPU p50 ~88%（CI10093 ~46.6%）——**软件音频解码抬升 CPU/PSS（权衡）**，详见 §4。

## 1. 身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | 1e518f49ba9bff16dea3fd190e08e3d0ef8670830d4c7a6752f724ad7b32dad6（12,928,915B，match） |
| test SHA256 | 0ff2f7eec80a30e2709bb16c9ea3f90f5559be41b12fa81b55bd177636cc0641（502,955B，match） |
| 签名 SHA256 | 773bf65a17980271ea450608d741080677a897423157edb58ea7a0ae0ab7c7a1（match） |
| 包/版本 | 10096 / 2.2.0-tivimini-ae531f7f-validation；label=IQTV Lite 验证版；min24/target35；primaryCpuAbi=armeabi-v7a |
| 内置 so | `lib/arm64-v8a/libffmpegJNI.so` 1,341,096B + `lib/armeabi-v7a/libffmpegJNI.so` 1,155,156B + `assets/NOTICE_FFMPEG_LGPL.txt` 1,168B |

backup 10093（validation-10093-backup.tar 1,665,024B）后仅替换 validation（卸载旧+安装 10096，Success）；安装后 base.apk 复算=`1e518f49…`（match）；原 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. MP2 有声（决定性证据，原始）

同一段（CCTV-3，key 3+OK 后）logcat：
```
IQTV-METRICS: format session=c40754…s3 mime=video/avc width=1920 height=1080
IQTV-METRICS: audio_format session=c40754…s3 mime=audio/mpeg-L2 codecs=unknown rate=48000 channels=2 bitrate=-1
IQTV-METRICS: first_frame session=c40754…s3 ttff_ms=1140
IQTV-METRICS: audio_advancing session=c40754…s3 playout_start_ms=1795192866531 since_prepare_ms=1249
D/AudioTrack: AudioTrack(0xa7efb300)::start
```
- `audio_track … mime=audio/mpeg-L2 supported=true selected=true renderer_format_support=4` → MP2 轨道被渲染器选中。
- `dumpsys audio`：`ID:231 -- type:android.media.AudioTrack -- u/pid:10092/9511 -- state:started -- usage=USAGE_MEDIA`；我方包 audio focus `gain: GAIN`；`Muted: false`。
- `dumpsys media.audio_flinger`：`1 Tracks of which 1 are active`（48kHz PCM_16 输出）。
- 早段另一会话 c75830(CCTV1) `audio_advancing` 亦在递增（playout_start_ms 1795192637642→64647245）。
→ **MP2（audio/mpeg-L2）确有音频输出（AudioTrack started + audio clock 前进）**，扩展 AAR 的 libffmpegJNI.so 已随包并在解码。

## 3. AAC 不回归 + 切换不崩

- AAC 频道：`audio_format mime=audio/mp4a-latm` 且 `audio_advancing` → AAC 仍出声（平台解码路径，EXTENSION_RENDERER_MODE_ON 不劫持支持格式）。
- **10 次显式切换** mimes：`mp4a-latm,mp4a-latm,mp4a-latm,mp4a-latm,mp4a-latm,mpeg-L2,mpeg-L2,mpeg-L2,mp4a-latm,mpeg-L2`，dead=0；累计 12+2+10 次切换 pid 稳定（9511→…→14431 均为冷启/正常，非崩溃）。
- 崩溃核查：`am_crash` 无本 app；`am_proc_died` 仅 `com.shafa.market:xg_service_v2`（后台伙伴应用反复崩溃，与本 app 无关）。**本 app 无崩溃。**
- AVC 视频：全程 `mime=video/avc 1920x1080`，出画（截图 mean 132/138/132）。
- 音画同步：audio_advancing 与 first_frame 同会话均正常推进；**精确 A/V 偏差未逐帧量化**（近似同步，未验证严格毫秒级）。

## 4. 体积 / PSS / 冷启 / CPU（记录）

| 冷启 | TotalTime | 启动 CPU 峰 | PSS |
|---|---|---|---|
| #1 | 7327ms | 353% | 76637 KiB |
| #2 | 7342ms | 327% | 70889 KiB |
| #3 | 7604ms | 292% | 77624 KiB |
- 冷启中位 ~7.34s（与 CI10093 ~7.3s 相当）；启动 CPU 峰 292–353%（偏高）。
- **稳态 appCPU%** 采样 [122.6,100,85.7,88.3] → p50 **~88.3%**；稳态 PSS **~83756KiB(≈82MiB)**。
- 对比 CI10093：appCPU p50 46.6%、PSS 稳态 ~53MiB → **FFmpeg 软音频解码抬升 CPU/PSS（预期权衡）**；冷启 PSS +~10MiB（.so 常驻）。注：稳态 CPU 采样在集中切台后不久，含过渡负载，建议静置后复测更准；但趋势为软解代价。
- 观感/品牌：与 CI10093 一致（同 brand/结构；截图 mp2-player.png / mp2-final.png / after-scan.png）。

## 5. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/仅替换 validation | 通过 |
| **MP2 有声（audio/mpeg-L2 AudioTrack started+audio_advancing+active track）** | **通过（决定性）** |
| AAC 仍平台解码有声（audio/mp4a-latm advancing） | 通过 |
| AVC 视频正常出画 | 通过 |
| MP2↔AAC 切换 10+ 不崩 / 无 AudioSink 阻塞视频 | 通过（10 次 dead=0；20+ 累计稳定） |
| 音画严格同步（毫秒级） | 未验证（近似同步） |
| 冷启/体积/PSS/CPU 记录 | 通过：中位~7.34s；PSS 冷 69–76MiB/稳态~82MiB；appCPU p50~88%（较 10093 抬升） |
| 观感/品牌与 CI10093 一致 | 通过（截图） |

## 6. 判定

- **核心达成：MP2（audio/mpeg-L2）确有声音（AudioTrack state:started + audio_advancing + active track，扩展 .so 随包）；AAC 不回归仍出声；MP2↔AAC 10 次切换不崩；AVC 正常；本 app 无崩溃。**
- **代价（需知悉/权衡，不否定功能）**：FFmpeg 软音频解码使稳态 appCPU p50 ~88%（vs 10093 46.6%）、PSS 冷 69–76MiB/稳态~82MiB（vs ~53MiB）；冷启时延相当（~7.34s）。
- 未验证：严格毫秒级音画同步（近似同步）；AAC 是否恒为平台解码（架构保证 EXTENSION_RENDERER_MODE_ON 不劫持支持格式，未逐帧确认）。
- 未改代码、未改原包数据；设备现为 CI10096（MP2 有声、可出画）。
