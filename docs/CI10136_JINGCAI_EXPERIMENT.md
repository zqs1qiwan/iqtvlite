# CI10136 睛彩实验定位（默认 / E1 / E2 / E3）

2026-11-21，acceptance2（t232 attempt 1）。设备 `192.168.2.245:5555`（TR100-G9 / Android 9 / armeabi-v7a / 1080p）。验证包 **10136 / 2.2.0-tivimini-8c1f3ebb-validation**（app `07b805dc…3206` 13,092,813B；签名 `2913b033…`；run 44877851760；artifact 14697261176）。按设备健康协议执行。

## 0. 结论（先行）

**四组实验全部 `bytes=0`** → **故障在「数据源层」：ExoPlayer 一个字节都没收到**，与 extractor、解码、渲染、字幕注入、TS 搜索深度、HTTP 数据源实现**全部无关**。

| 组 | mode | 诊断行 | firstFrame | state |
|---|---|---|---|---|
| 默认（关闭） | off | `bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2` | ✗ | 2 |
| E1 TS 搜索 4MB | e1 | `bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2` | ✗ | 2 |
| E2 系统 HTTP 源 | e2 | `bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2` | ✗ | 2 |
| E3 禁用字幕注入 | e3 | `bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2` | ✗ | 2 |
| 对照 CCTV-1 | — | 正常 | ✓ `ttff=826ms` | 3 |

**注**：`format=video/avc/1920x1080` 非空但 `bytes=0` 看似矛盾——该字段取自 `onDownstreamFormatChanged`（由 MediaSource/解析层根据 URL 推断或首包元信息），**在未收到任何载荷字节时也可被设置**；`bytes` 是 `onLoadCompleted` 累计（真实收到的字节），**bytes=0 才是硬证据**。

## 1. 安装与健康
```
前置：/data 可用 2,640,488KB；storaged=0 installd=0 I/O=0 DeadObject=0；3 包在位
安装：install -r → INSTALL_FAILED_UPDATE_INCOMPATIBLE（10135→10136 签名不同）
      按协议例外：先备份 validation data（validation-10135-backup.tar 1,873,920B）
      → 仅卸载 tv.iqtvlite.app.validation 一次 → install -r → Success（10136）
后置：/data 可用 2,640,180KB；storaged=0 installd=0 I/O=0 DeadObject=0；3 包在位；无崩溃
```
未卸载 `tv.iqtvlite.app` / `com.cjlhll.iptv`（后者已不在设备上，见 t229）；`com.shafa.market` 本次未再卸载任何包。

## 2. 默认组（mode=off）原始行
```
15:05:03.356  state=2 rebuffers=0 dropped=0
15:05:04.293  format video/avc 1920x1080
15:05:04.375  audio_format audio/mpeg-L2 48k/2ch
15:05:11.596  IQTV-WATCHDOG: firstFrameTimeout retry=1 channel=4783027072129292115
15:05:19.616  IQTV-JINGCAI: mode=off bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2
```
→ 看门狗 8s 重试一次（符合策略），再 8s 放弃并打印诊断行。

## 3. 实验组
设置路径：设置 → 播放与显示 → **睛彩诊断实验**（循环：关闭 → E1 → E2 → E3）。每次切换后重新选 117（`1,1,7`+OK），等 ~20s。

| 组 | prefs | 诊断行（时间） | firstFrame | state=3 |
|---|---|---|---|---|
| E1 | `jingcai_experiment=e1` | `15:07:54 mode=e1 bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2` | 0 | 0 |
| E2 | `jingcai_experiment=e2` | `15:09:24 mode=e2 bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2` | 0 | 0 |
| E3 | `jingcai_experiment=e3` | `15:10:53 mode=e3 bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2` | 0 | 0 |

三组切换**均被正确读取**（prefs 与 UI 文案一致：「E1 TS搜索4MB」/「E2 系统HTTP源」/「E3 禁用字幕注入」），证明实验开关链路正常；**但 bytes 始终 0**。

## 4. 对照与恢复
- **CCTV-1**：`first_frame ttff=826ms`、`state=3`、`rebuffers=0`、`audio/mpeg-L2` → 环境与解码链路正常。
- 结束时已把实验**改回「关闭」**（`jingcai_experiment=off`，UI「睛彩诊断实验：关闭」）。
- 每步后 `/data` 2,640,180KB、`storaged/installd/I/O/DeadObject=0`、3 包在位。

## 5. 分层结论
按预设判读规则：
- `bytes=0` → **数据源层**（最上游）。
- `bytes>0 && format=none` → extractor（**未命中**）
- `format!=none && firstFrame=false` → 解码/渲染（**形式命中但被 bytes=0 推翻**）

**因此：不是 extractor、不是解码、不是渲染、不是字幕注入、不是 TS 搜索深度，也不是 HTTP 数据源实现差异（E2 换 DefaultHttpDataSource 同样 bytes=0）。**

## 5b. ★ 关键反转：设备侧 curl 证明源与网关完全正常
在**同一台设备**上直接拉睛彩三条 URL（只读，不写设备）：
```
117 rtp 239.3.1.125:8001 → http=200 bytes=8,422,400 time=7.9997s   (≈8.4 Mbps)
118 udp 239.3.1.126:8001 → http=200 bytes=8,422,400 time=7.9992s
119 rtp 239.3.1.127:8001 → http=200 bytes=8,422,400 time=8.0001s
CCTV-1 rtp 239.3.1.64:8124 → http=200 bytes=8,685,600 time=7.9995s
```
→ **源可用、网关 4022 正常转发、组播可达、带宽足够（8.4 vs 8.7 Mbps 同量级）**。
**因此 `bytes=0` 只能是 App 内这条取流路径的问题**，而非网络/源。

## 6. 建议根因（据 curl 证据重排）
1. **App 数据源创建路径与该 URL 不兼容（最可能）**：`StreamPlayback` 用的是
   `OkHttpDataSource.Factory(NetUtil.ipv4OkHttpClient)` + `setDefaultRequestProperties(headers)`（含 UA）。
   对 `http://192.168.2.1:4022/rtp/...` 这种**无限长直播流**，OkHttp 的读超时/响应处理与 curl 的流式读取不同：
   若 OkHttp 侧 `readTimeout` 到期、或 `callTimeout`、或连接池/拦截器对长响应处理不当 → **拿不到 body 字节**（但 `format` 已被 MediaSource 推断）。
   E2 换 `DefaultHttpDataSource` 也 0 字节，说明**不只是 OkHttp 实现差异**，更像 URL/请求层被拦截（见 2）。
2. **请求头/UA 被网关拒绝 body**：curl 不带我们设置的 `User-Agent` 与自定义 headers。网关可能对**特定 UA/Referer** 返回 200 但**不吐流**（或返回极慢）。E2 保留原 UA/头 → 同样 0 字节，**与“UA 被特殊对待”一致**。
3. **IPv4 客户端/代理设置**：`NetUtil.ipv4OkHttpClient` 可能强制 IPv4 或走代理；curl 走系统默认。若网关仅对直连（无代理/特定源地址）转发，则 App 侧 0 字节。
4. **（次要）** ExoPlayer 读超时极长 → 表现长时间 `state=2` 而非立即报错。

**已排除**：extractor 解析、TS 搜索深度（E1）、HTTP 数据源实现差异本身（E2）、字幕注入（E3）、解码器、渲染、看门狗逻辑（重试/放弃均正确）、**源与网关不可达（curl 8.4Mbps 证伪）**。

**待补一步硬证据（建议下一步任务）**：在 App 内对同一 URL 打印**请求级**信息（HTTP 状态码、首字节时间、已读字节、异常类型），或临时把该 URL 交给系统 `DefaultHttpDataSource` 并记录 `open()` 返回值/异常；也可在设备上 `adb shell run-as <pkg> curl ...` 用**我们实际使用的 UA/headers** 复现。

## 7. 下一步建议
1. **App 侧请求级诊断（最高优先，最可能一步定位）**：在 `StreamPlayback` 的数据源外包一层记录：`open()` 的 HTTP 状态码、首字节耗时、累计读取字节、异常；并把**我们实际使用的 UA/headers** 在设备侧用 curl 复现：
   ```
   adb shell curl -s -m 8 -H 'User-Agent: <我们的UA>' -o /dev/null \
     -w 'http=%{http_code} bytes=%{size_download} ttfb=%{time_starttransfer}\n' \
     'http://192.168.2.1:4022/rtp/239.3.1.125:8001'
   ```
   若该 UA 下 bytes=0 → **UA/头被网关特殊处理**（立刻可修：改用与 curl 一致的 UA 或去掉自定义头）。
2. **首字节超时（体验改进）**：数据源层加“6s 内 0 字节即失败”，把“无限缓冲”变为明确错误 + 提示「该频道无数据」。
3. **已可排除的方向不必再试**：E1（TS 搜索深度）、E3（字幕注入）、解码/渲染；E2 已证明换 DefaultHttpDataSource 无效。
4. **若 1 仍无法定位**：旁路只读抓包（设备侧 `tcpdump` 需 root/权限，或网关侧镜像）确认 App 的 TCP 是否真的收到 body。

## 8. 交付物
- 本报告：`docs/CI10136_JINGCAI_EXPERIMENT.md`
- 证据：`docs/evidence/2026-11-21-ci-10136/`（`validation-10135-backup.tar` 1,873,920B、设置 dump、诊断脚本）
- 设备状态：CI10136 已装（`jingcai_experiment=off`），3 包在位，健康检查全绿。
