# 1.1 — 定时录制端到端验证 + IPv6 黑洞修复 + 续播接入

> 日期：2026-11-17

## 1. 本轮核心突破：定时录制端到端真机验证成功

之前定时录制能触发、但 `.ts` 文件始终 0 字节。本轮定位到**根本原因**并修复。

### 1.1 根因：电视盒子 IPv6 出口不通

在 TR100-G9（Android 9）上实测：
```
curl -4 hlsztemgsplive.miguvideo.com  → HTTP 200 (106 ms)
curl -6 hlsztemgsplive.miguvideo.com  → HTTP 000 (10 s 超时)
系统 DNS 返回: [2408:8207:...::1 (IPv6), 192.168.2.1 (IPv4)]
```
系统 DNS 同时返回 AAAA/A 记录但 IPv6 出口不通。**OkHttp 优先 IPv6 → 连接永久挂起**，
而播放列表/分片请求之前设了 `readTimeout(0)`（无限），所以录制线程卡死在第一次 `fetchText`。

### 1.2 修复
1. 新增 `NetUtil.ipv4OnlyDns`：DNS 只保留 IPv4 地址（无 A 记录时回退全部）
2. `RecordingService` / `MainActivity` / `PlaybackService` 的 OkHttp 客户端全部 `.dns(NetUtil.ipv4OnlyDns)`
3. `RecordingService` 的 `readTimeout` 从 `0`（无限）改为 `20s`，杜绝永久挂起
4. **保留 Migu URL 里的 `|User-Agent=VLC`**：实测剥离或 `%7C` 编码都会 403，
   OkHttp 4.x 会原样保留 raw `|`，必须原样传给服务器

### 1.3 端到端验证日志（真机，CCTV3 Migu 流）
```
I iqtv-schedrec: onReceive key=... total=1
I iqtv-schedrec: REC_START .../TEST_SCED_REC_123.m3u8
I iqtv-dvr: REC_START .../TEST_SCED_REC_123.ts ua=VLC/3.0.20 LibVLC/3.0.20
I iqtv-dvr: HLS master->variant
I iqtv-dvr: HLS media playlist segments=4
I iqtv-dvr: HLS segment bytes=3228524
I iqtv-dvr: HLS segment bytes=2849140
I iqtv-dvr: REC_PROGRESS 23 MB
I iqtv-dvr: REC_DONE bytes=30922052 file=.../TEST_SCED_REC_123.ts
文件: 30,922,052 bytes (≈30 MB)
TS 同步字节: 47 40 11 14 00 42 f0 28   ← 0x47，且 188 字节边界对齐 = 有效 MPEG-TS
```
→ **AlarmManager 到点触发 → 生成录制计划 → RecordingService 拉流 → HLS 分片拼接 → 有效 TS 落盘**，
整条链路首次完整走通。停止服务时正常输出 `REC_DONE`。

## 2. 断点续播真正接入播放

- `playCurrentUrl`：VOD/剧集（非直播、非广播）在 `STATE_READY` 后一次性 `seekTo(上次位置)`，
  日志 `RESUME <频道> -> <位置>ms`；直播永远从边缘播放，不续播
- `saveCurrentResume`：暂停（`STATE_READY && !playWhenReady`）、播放结束、`onDestroy` 时保存位置，
  日志 `SAVE_RESUME <频道> <pos>/<dur>`
- 修复：Media3 没有 `STATE_PAUSED`（暂停 = READY + playWhenReady=false）；并移除了重复的
  `onPlaybackStateChanged` override

## 3. 定时录制闹钟恢复
`MainActivity.onCreate` 现在同时恢复「提醒」和「定时录制」的 AlarmManager 闹钟
（设备重启 / 进程被杀后重新注册）。

## 4. AFR / 音频直通设置真机生效验证
注入 `playback_prefs={"afr":true,"passthrough":true,"external":true}` 后启动，真机日志：
```
I iqtv-playback: prefs applied afr=true passthrough=true
```
→ 播放偏好确实被读取并应用到 ExoPlayer（`setVideoChangeFrameRateStrategy(ONLY_IF_SEAMLESS)`）。

外部播放器兜底：`tryNextUrl` 在「所有线路失败」且 `external=true` 时调用 `openExternalPlayer`
（ACTION_VIEW + UA）。**注意**：当前测试盒子未安装任何可播 m3u8 的播放器
（`cmd package query-activities ... mpegurl` → No activities found），因此无法观察到实际外跳，
代码路径已编译通过；如需真机验证外跳，请先在盒子安装 VLC。

## 5. 性能数据（前序真机验证）
| 指标 | 实测 |
|---|---|
| 热启动（缓存命中） | 3.15 s（本轮复测） |
| 换台首帧 ZAP_MS | 1932ms 冷 → 654ms → 346ms 热（avg 977ms） |

## 6. 测试
167 个 Rust 测试通过，clippy -D warnings 零告警，Kotlin `assembleDebug` 成功。

## 7. 设备状态说明（重要）
为隔离网络问题，本轮曾通过 ADB 打开飞行模式，导致这台**通过 WiFi 连接的 ADB 设备离线**
（`192.168.2.245:5555` 端口关闭）。需要人工在盒子上关闭飞行模式（或重启 WiFi / 重启盒子）
后重新 `adb connect 192.168.2.245:5555` 才能恢复连接。代码与验证证据已完成，不受影响。
