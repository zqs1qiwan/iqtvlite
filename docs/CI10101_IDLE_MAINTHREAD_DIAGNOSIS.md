# CI10101 空闲播放主线程忙 诊断

2026-11-21，acceptance2（t151 attempt 1）。对象：`tv.iqtvlite.app.validation` **10101 / 2.2.0-tivimini-b8f9790b-validation**（设备现装）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。**只测不改；未改代码/原包数据。** 测试源 `https://iptv.laobaitv.net`；诊断频道 AAC `audio/mp4a-latm mp4a.40.2 48k/2ch`（session c5648776…s19）。

## 0. 结论摘要

- **主线程并非持续重绘/持续忙**：抽屉关闭、仅播 AAC 静置，`gfxinfo reset` 后 10s **Total frames rendered = 0**（首次采样含启动残余为 27 帧≈2.7fps）；两次截图差异 mean_abs 37.3 来自**视频画面运动**（SurfaceView 零拷贝视频，不计入 UI 帧），非 UI 重绘。
- **线程真因（10s 持续 /proc/<pid>/task/*/stat 差分，进程总 85.5%）**：
  | 线程 | %CPU |
  |---|---|
  | **ExoPlayer:Loader** | **42.6** |
  | **ExoPlayer:Playback** | **20.2** |
  | CodecLooper | 6.1 |
  | Okio Watchdog | 6.1 |
  | MediaCodec_loop | 4.4+2.6 |
  | HwBinder | 2.0 |
  | **main（主线程）** | **0.5** |
  | Jit/Heap 等 | ≤0.8 |
  → **CPU 主因是 ExoPlayer 网络/解码管线（Loader 42.6% + Playback 20.2% + codec ~13%），不是 UI/主线程/预取/EPG**。
- **更正 t150 附录**：`top -H` 瞬时显示“main 88.5%/75.7%”属 **top -H 瞬时误读/聚合**；持续差分下 **main 仅 0.5%**。基础播放 46.6%→87% 的回退应在播放器 Loader/Playback（网络/流）方向，而非主线程。
- **无高频日志/无明显周期请求证据**：10s 空闲 logcat 仅 1–2 条与本 app 无关（xg_service_v / CodecLooper audit）；netstats 可访问但**无 per-uid openSession/字节明细**；Okio Watchdog 6.1% 提示有 socket 活动但无周期性请求证据（需 ExoPlayer VERBOSE 或 Perfetto 确认 Loader 拉取频率）。

## 1. 是否持续重绘（帧增长）
| 采样 | Total frames（10s） | 结论 |
|---|---|---|
| 第1次（reset 后含启动残余） | 27（≈2.7fps） | 启动/过渡残余 |
| 第2次（确认静置） | **0** | **空闲无 UI 重绘** |
- 截图 `idle-t0.png` vs `idle-t1.png`：mean_abs_diff **37.3**（差异来自视频运动；UI 层无新增帧）。

## 2. 帧耗时（空闲）
- 第1次 27 帧：Janky 40.74%、50th14、90th38（样本小，参考）。
- 确认静置 10s：0 帧（无 UI 绘帧，无 jank 可比）。

## 3. 线程 CPU（`top -H` vs 持续差分）
- `top -H -b -n 3 -d 2` 瞬时：main 88.5%→75.7%、Loader 45.7%→36.3%、Playback 20→18.1%、MediaCodec/CodecLooper ~6%；**瞬时值波动大且 main 名与进程同，易误读**。
- 持续 10s 差分（可靠）：见 §0 表；**main 0.5%**，Loader 42.6%、Playback 20.2% 为主。
- 另：MP2 频道同法 appCPU [57.7,61.6]≈60%，与 t150 S2(62%) 一致；两频道差异主要在 Loader。

## 4. 高频日志（10s 空闲，本 app）
- 过滤 app/EPG/logo/OkHttp/ExoPlayer：**10s 内仅 1 条**（CodecLooper audit avc denied，与 CPU 无关）；第二次采样仅 2 条（均 `t:xg_service_v`，非本 app）。
- → **无 EPG/logo/prefetch/recompose 高频日志**（此前 t130 已把 stats 移 IO、日志本就少）。

## 5. 网络/周期请求
- `dumpsys netstats`：可访问但输出仅接口头（wlan0 WIFI），**无 per-UID openSession/字节明细**（设备限制）。
- `/proc/net/xt_qtaguid/stats` awk 失败（命令/权限）；无 app OkHttp 日志（默认关闭）。
- 线索：Okio Watchdog 6.1% 说明有 socket 活动；**不能据此断言周期请求**，需 ExoPlayer VERBOSE / Perfetto / tcpdump 进一步确认 Loader 的拉取频率与目标（EPG vs 流 playlist）。

## 6. 结论与建议
- **空闲播放不是“主线程持续重绘”**（0 帧/10s、main 0.5%）；也不是预取/EPG 主线程在跑。
- **CPU 主因是 ExoPlayer Loader(42.6%) + Playback(20.2%) + codec 线程(~13%)** → 指向**播放器网络/流拉取与解码管线**（可能为 HLS/TS 分段频繁 reload、buffer 配置、或该 AAC 源本身高码率/短分段）。
- 建议下一步（不改代码，先取证）：
  1) 开 `setprop log.tag.ExoPlayer VERBOSE`（需重启进程）后抓 Loader 请求 URL/频率，确认是流 playlist 还是其他；
  2) Perfetto 抓 10s trace，看 Loader 的 `HttpDataSource` 等待/重试与 CPU 热点；
  3) 对同一 AAC 频道在 CI10093（46.6% 基线）做同法对比，确认是版本改动（LoadControl/buffer/播放列表刷新）还是源差异；
  4) 若确认 Loader 高频 reload，检查 LoadControl(1000-6000-300-1000) 与直播 buffer/刷新策略。
- 未改代码、未改原包数据；设备保持 CI10101。
