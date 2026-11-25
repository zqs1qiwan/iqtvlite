<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/logo-white-transparent.png" />
    <source media="(prefers-color-scheme: light)" srcset="assets/logo-transparent.png" />
    <img src="assets/logo-transparent.png" alt="IQTV Lite" width="420" />
  </picture>

  <p><strong>专为 Android TV 与电视盒子打造的轻量、开源、纯净高响应直播电视播放器</strong></p>
  <p><em>A clean, lightweight, open-source D-pad IPTV player built for Android TV and TV boxes</em></p>

  <p>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-2563EB.svg?style=flat-square" alt="License: GPLv3" /></a>
    <a href="https://github.com/zqs1qiwan/iqtvlite/releases/latest"><img src="https://img.shields.io/github/v/release/zqs1qiwan/iqtvlite?style=flat-square&color=16A34A&label=Release" alt="Latest Release" /></a>
    <img src="https://img.shields.io/badge/Android%20TV-7.0%2B-3DDC84?style=flat-square" alt="Android TV 7.0+" />
    <img src="https://img.shields.io/badge/UI-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87%20%7C%20English-0F172A?style=flat-square" alt="Bilingual UI" />
  </p>

  <p>
    <a href="#为什么选择-iqtv-lite">中文文档</a> ·
    <a href="#界面展示">界面展示</a> ·
    <a href="#核心功能">核心功能</a> ·
    <a href="#播放列表多线路与自定义-header-示例">播放列表与 Header</a> ·
    <a href="#安装与更新">安装与更新</a> ·
    <a href="#遥控器操作指南">遥控器操作</a> ·
    <a href="#english-documentation">English Documentation</a>
  </p>
</div>

---

## 为什么选择 IQTV Lite

- **完全免费与开源（GPLv3），零广告、无付费解锁墙**：多播放源切换、XMLTV 节目单、回看、自定义按键、手机扫码网页配置与内置更新全部免费可用，没有会员门槛。
- **极致轻量化与流畅性能优化**：安装包体积精简，底层采用 Rust 原生高性能解析核心与 SQLite EPG 索引，面对上万条频道与大体积节目单也能秒级加载与检索；选台列表与硬件解码全链路零等待，即使在入门级电视盒子上连续上下换台、快速翻组也依然顺滑跟手。
- **开箱兼容复杂直播源**：完整支持防盗链 `Referer` / `User-Agent` / `Cookie` 等请求头配置（兼容单/双引号包裹值）、同频道多线路切换与失效自动轮询、以及同 `tvg-id` 下的 4K 与标准高清独立区分。

---

## 界面展示

<table>
  <tr>
    <td width="50%" align="center">
      <img src="assets/screenshots/player-infocard.png" alt="播放信息卡与控制台" />
      <br />
      <sub><b>双层智能信息栏</b>：换台轻量通透展示当前频道；按 OK 呼出完整控制台，集成最近观看频道与 收藏 / 线路切换 / 适应 / 音轨 / 字幕 / 多画面 / 设置 快捷控制</sub>
    </td>
    <td width="50%" align="center">
      <img src="assets/screenshots/channel-drawer.png" alt="频道列表与节目预告抽屉" />
      <br />
      <sub><b>频道列表与 EPG 节目单</b>：台标展示、正在播出高亮进度、多日节目预告；再次点击当前直播台直接收起抽屉不重载</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <img src="assets/screenshots/drawer-groups-sources.png" alt="多级抽屉分组与多源切换" />
      <br />
      <sub><b>逐级抽屉与多源切换</b>：按左键依次展开「频道」「分组」「播放源」，视野清爽不遮挡画面</sub>
    </td>
    <td width="50%" align="center">
      <img src="assets/screenshots/settings.png" alt="7大分类电视端设置菜单" />
      <br />
      <sub><b>7 大分类设置中心</b>：通用、节目列表、节目预告、外观、播放解码、遥控设置、关于与更新，底部自带按键操作提示</sub>
    </td>
  </tr>
  <tr>
    <td colspan="2" align="center">
      <img src="assets/screenshots/webui-qr.png" alt="设置内嵌扫码 WebUI 配置" width="72%" />
      <br />
      <sub><b>设置内嵌扫码 WebUI</b>：无需跳转笨重控制台，在设置内直接扫码打开手机网页端，管理多源、全局请求头、播放偏好与 JSON 备份恢复</sub>
    </td>
  </tr>
</table>

---

## 核心功能

- **双层底部信息栏**：
  - **上下键换台**：弹出轻量半透明底部条，展示当前台标、频道号、节目名称、进度条与真实实时视频参数（分辨率 / 帧率 / 声道 / HDR / 编码），数秒后自动收起。
  - **按 `OK` 主动呼出**：展开完整两行控制台——上方保留当前频道展示，下方左侧展示 **最近观看频道**（台标 + 台名），右侧无缝衔接 **收藏 · 线路（多源时）· 适应（画面比例）· 音轨 · 字幕 · 多画面 · 设置** 快捷控制。
- **逐级展开选台抽屉**：
  - 按 `←` 打开 **频道 + 节目单**，聚焦长节目名自动平滑滚动；再次点击当前正在播放的直播台会直接收起抽屉，不重复加载。
  - 再按一次 `←` 展示 **分组 + 频道**，支持按默认 / 名称 / 手动排序分组，内置「全部」与「收藏夹」。
  - 若启用了多个播放源，继续按 `←` 展开最左侧 **播放源** 列表快速切换。
- **聚合多线路与失效自动轮询**：
  - 同一 `#EXTINF` 下附带多条备用地址会自动聚合为单一频道的多条线路，在 OK 菜单显示 `线路 1/N` 手动切换按钮，并在播放失败时自动轮询下一条健康线路；不同 `#EXTINF` 条目严格保持各自独立。
- **4K/HEVC 防花屏与默认字幕**：
  - 支持 4K/HEVC 切台硬件解码器防花屏重建，以及默认字幕自动加载与首选语言（自动 / 中文 / English）切换。
- **右上角紧凑数字直选台**：
  - 按数字键仅在右上角轻量弹出匹配频道号与台名列表（不遮挡主画面），支持方向键筛选或超时自动跳转。
- **设置内嵌手机扫码 WebUI（中英双语）**：
  - 在设置中直接生成局域网二维码（固定主端口 `18080`，不可用时有序回退），手机扫码即可管理播放源、全局请求头、显示与播放偏好、遥控映射及 **JSON 配置备份与恢复**；手机端已锁定视口防止输入误触缩放。
- **7 大分类设置与安全默认习惯**：
  - 涵盖「通用 · 节目列表 · 节目预告 · 外观 · 播放解码 · 遥控设置 · 关于与更新」；默认不劫持系统 `Home` 键（桌面画中画需主动开启）、默认开启双击返回退出、`→` 默认「切换上一频道」（可自由改键）。

---

## 播放列表多线路与自定义 Header 示例

### 1. 多线路聚合 vs 独立频道
- **同一频道多条备用线路**（一个 `#EXTINF` 下跟多行地址，自动合并并提供失效轮询与「线路」切换）：
```m3u
#EXTINF:-1 tvg-id="dongfangweishi" tvg-name="东方卫视" tvg-logo="https://example.com/logo/dragontv.png" http-user-agent="VLC" group-title="卫视频道",东方卫视
http://example.com/line1.m3u8|User-Agent=VLC
http://example.com/line2.m3u8|User-Agent="CustomPlayer/1.1"
http://example.com/line3.m3u8
```
- **独立频道**（各自拥有 `#EXTINF`，即使 `tvg-id` 或名称相同也保持独立显示，如 `4K` 与标准版分别呈现）：
```m3u
#EXTINF:-1 tvg-id="beijingweishi" group-title="卫视频道",北京卫视4K
https://example.com/btv4k.m3u8|Referer=https://api.example.com/
#EXTINF:-1 tvg-id="beijingweishi" group-title="卫视频道",北京卫视
http://example.com/btvhd.m3u8|User-Agent=VLC
```

### 2. 自定义 HTTP Header 写法与优先级
当同一频道同时存在多种 Header 声明时，生效优先级为：**`URL 后 | 参数` > `#EXTINF / #EXTVLCOPT` > `设置 / 网页端全局 Header`**。

- **方式 A：播放地址后用 `|` 拼接（多个 Header 使用 `&` 或 `;` 连接，值支持引号包裹）**
```text
https://example.com/live.m3u8|User-Agent=VLC&Referer=https://example.com/&Origin=https://example.com&Cookie=sid=123
```
- **方式 B：`#EXTINF` 行内属性或 `#EXTVLCOPT:` 指令**
```m3u
#EXTINF:-1 http-user-agent="VLC" http-referer="https://example.com/" http-origin="https://example.com",示例频道
http://example.com/stream.m3u8
```
- **方式 C：全局自定义 User-Agent 与全局 Header（在「设置 → 通用」或手机网页端配置，每行一条）**
```text
Referer: https://example.com/
Origin: https://example.com
X-Custom-Token: your-token
```

---

## 安装与更新

1. 前往 [**Releases 最新版本**](https://github.com/zqs1qiwan/iqtvlite/releases/latest) 下载 `IQTV-Lite-v0.2.2.apk`（或通用最新包 `IQTV-Lite-latest.apk`）。
2. 将 APK 传至 Android TV / 电视盒子安装（支持 Android 7.0 及以上，兼容 32 位与 64 位 ARM 设备）。
3. 后续可在电视端「设置 → 关于与更新 → 检查更新」直接内联检测并下载安装新版本，也可手动前往 GitHub Releases 更新。

---

## 实验功能说明

在「设置 → 播放解码 → 高级优化（实验）」中包含以下实验性或硬件适配选项：

| 功能名称 | 默认状态 | 作用说明 | 使用建议 |
| --- | --- | --- | --- |
| **4K 切台防花屏重建** | 开启 | 切换到 4K / HEVC 频道时自动重建硬件视频解码器，清理老旧电视芯片（如部分 Android 9 机顶盒）残留的上一台参考帧缓存，避免切台花屏。 | 若你的电视/盒子从不花屏且希望 4K 换台更极限流畅，可尝试关闭。 |
| **预连接相邻频道** | 关闭 | 空闲时在后台预连接当前频道的上一台与下一台（TCP/TLS 握手），以缩短上下键连续换台的等待时间。 | 对连接数或并发有限制的单播源建议保持关闭，以免增加服务器连接压力。 |
| **组播预热** | 关闭 | 仅针对家庭宽带 IPTV 组播（`udp://` / `rtp://`）环境，提前预热相邻频道的组播组加入以加速内网切台。 | 使用公网 HTTP/HLS 单播直播源的用户请保持关闭。 |
| **码流兼容模式** | 关闭 | 针对非标准广电 TS 流（如 PMT/PCR 时间戳异常导致的卡死、只出声不出画或音画不同步）提供实验性兼容调度策略。 | 仅在个别频道播放异常时按需开启测试，正常流请保持关闭。 |

---

## 遥控器操作指南

| 按键 | 功能说明 |
| --- | --- |
| `↑` / `↓` | 切换上 / 下一个频道（弹出轻量半透明当前台信息卡并自动收起） |
| `OK` / `Enter` | 呼出完整控制信息栏；再次按 `OK` 在底部最近观看频道与右侧快捷控制图标间横向选择，点击右侧齿轮进入设置 |
| `←` | 逐级打开选台抽屉：**频道 + 节目单 → 分组 → 播放源** |
| `→` | 默认**切换上一频道**（可在「设置 → 遥控设置」自由改键为打开设置或其他功能） |
| `Back` | 逐级退回或关闭菜单；在播放器全屏状态默认支持双击返回退出 |
| `数字键` | 直接输入频道号，右上角实时显示匹配台名并自动跳转 |

> 以上短按与长按行为均可在「设置 → 遥控设置」或手机网页端自由改键或置空。

---

# English Documentation

<p>
  <a href="#why-iqtv-lite">Why IQTV Lite</a> ·
  <a href="#key-features">Key Features</a> ·
  <a href="#playlist--custom-http-headers">Playlist & Headers</a> ·
  <a href="#installation--updates">Installation & Updates</a> ·
  <a href="#remote-control-guide">Remote Control</a> ·
  <a href="#license">License</a>
</p>

## Why IQTV Lite

- **100% Free & Open Source (GPLv3) — Zero Ads, Zero Paywalls**: Multi-playlist switching, XMLTV EPG, catch-up playback, custom key remapping, scan-to-configure WebUI, and in-app updates are all completely free.
- **Lightweight & Tuned for Entry-Level TV Boxes**: Powered by a native Rust playlist/XMLTV parser and SQLite EPG store for near-instant loading across 10,000+ channels. Channel zapping and hardware decoding run with zero blocking probes on the hot path.
- **Built for Real-World Streams**: Full support for anti-hotlink `Referer` / `User-Agent` / `Cookie` headers (including quoted values), single-channel multi-line failover & manual line switching, and separate 4K vs HD entries even when sharing the same `tvg-id`.

## Key Features

- **Two-Tier Bottom Info Console**:
  - **Zapping (`↑` / `↓`)**: Displays a sleek, translucent banner with channel logo, number, title, live program progress, and verified stream tags (resolution, real FPS, audio channels, HDR, codec), auto-hiding after a few seconds.
  - **Full Console (`OK`)**: Reveals the bottom control strip with **Recent Channels** on the left and quick actions on the right: **Favorite · Source Line (`Src 1/N`, when multi-line) · Aspect Ratio · Audio Track · Subtitles · Multi-Screen · Settings**.
- **Progressive 4-Column Channel Drawer (`←`)**:
  - Press `←` for **Channels + EPG** (clicking the currently playing channel closes the drawer without reloading).
  - Press `←` again for **Groups + Channels** (supports Default / Name / Manual group ordering, plus toggleable *All* and *Favorites* groups).
  - When multiple playlists are enabled, press `←` again to switch **Sources**.
- **Multi-Line Aggregation & Auto-Failover**:
  - Multiple stream URLs placed under a single `#EXTINF` block are grouped into one channel with a `Src 1/N` switcher in the `OK` menu and automatic failover on playback errors; separate `#EXTINF` entries remain independent.
- **4K/HEVC Anti-Artifacting & Default Subtitles**:
  - Optional hardware decoder rebuild on 4K/HEVC channel zaps to prevent stale frame artifacts on older Android TV SoCs, plus automatic subtitle loading with preferred language (`Auto` / `Chinese` / `English`).
- **Compact Top-Right Numeric Jump OSD**:
  - Typing digits opens a compact top-right match strip showing only matching channel numbers and names without obstructing playback.
- **Inline QR WebUI Console & Backup/Restore**:
  - Open Settings → General → Backup / Restore (or Header Edit) to display an inline LAN QR code (fixed primary port `18080`, ordered fallback). Scan from any phone browser to manage playlists, global headers, playback/display options, remote bindings, and **JSON backup/restore** with mobile viewport zoom lock.
- **7-Category Settings & Safe Defaults**:
  - Organized into **General · Playlists · EPG · Appearance · Playback · Remote · About & Updates**. Non-intrusive defaults: `Home` PiP and boot auto-start default to `OFF`, double-Back exit defaults to `ON`, and `→` defaults to **Previous Channel** (fully remappable).

## Playlist & Custom HTTP Headers

### 1. Multi-Line Aggregation vs Independent Channels
- **Multiple backup lines for one channel** (multiple URLs under a single `#EXTINF` are merged for auto-failover and manual `Src 1/N` switching):
```m3u
#EXTINF:-1 tvg-id="dragontv" tvg-name="Dragon TV" tvg-logo="https://example.com/logo/dragontv.png" http-user-agent="VLC" group-title="Satellite",Dragon TV
http://example.com/line1.m3u8|User-Agent=VLC
http://example.com/line2.m3u8|User-Agent="CustomPlayer/1.1"
http://example.com/line3.m3u8
```
- **Independent channels** (separate `#EXTINF` blocks stay separate even with the same `tvg-id`, ideal for 4K vs FHD feeds):
```m3u
#EXTINF:-1 tvg-id="btv" group-title="Satellite",BTV 4K
https://example.com/btv4k.m3u8|Referer=https://api.example.com/
#EXTINF:-1 tvg-id="btv" group-title="Satellite",BTV HD
http://example.com/btvhd.m3u8|User-Agent=VLC
```

### 2. Header Syntax & Priority
Header resolution order (highest to lowest): **`URL | params` > `#EXTINF / #EXTVLCOPT` > `Global Headers (Settings / WebUI)`**.

- **Method A — Inline URL `|` suffix** (join multiple headers with `&` or `;`; quoted values supported):
```text
https://example.com/live.m3u8|User-Agent=VLC&Referer=https://example.com/&Origin=https://example.com&Cookie=sid=123
```
- **Method B — `#EXTINF` attributes or `#EXTVLCOPT:` lines**:
```m3u
#EXTINF:-1 http-user-agent="VLC" http-referer="https://example.com/" http-origin="https://example.com",Sample Channel
http://example.com/stream.m3u8
```
- **Method C — Global User-Agent & Custom Headers** (configured in **Settings → General** or the WebUI, one per line):
```text
Referer: https://example.com/
Origin: https://example.com
X-Custom-Token: your-token
```

## Installation & Updates

1. Download `IQTV-Lite-v0.2.2.apk` (or `IQTV-Lite-latest.apk`) from [**Latest Release**](https://github.com/zqs1qiwan/iqtvlite/releases/latest).
2. Sideload onto your Android TV / TV box (`Android 7.0+`, supports both `arm64-v8a` and `armeabi-v7a`).
3. Check for future updates directly in **Settings → About & Updates → Check for Updates**, or download from GitHub Releases.

## Experimental Features

Under **Settings → Playback → Advanced (Experimental)**, the following tuning and stream-compatibility options are available:

| Option | Default | What It Does | When to Change |
| --- | --- | --- | --- |
| **4K Zap Anti-Artifacting Rebuild** | ON | Rebuilds the hardware video decoder when tuning to 4K / HEVC channels to clear stale reference frames on older Android TV SoCs. | Turn OFF only if your device never shows 4K artifacts and you want slightly faster 4K zaps. |
| **Neighbour Preconnect** | OFF | Pre-warms TCP/TLS connections to the adjacent (`±1`) channels while idle to reduce channel-switch latency. | Keep OFF on strict IPTV providers that limit concurrent connections per token/IP. |
| **Multicast Prewarm** | OFF | Pre-joins adjacent UDP multicast groups for faster LAN ISP IPTV multicast switching. | Useful only on home LAN multicast setups; keep OFF for HTTP/HLS unicast playlists. |
| **Stream Compatibility** | OFF | Experimental TS/PMT/PCR timeline compatibility modes for non-standard broadcast feeds that stall or lose A/V sync. | Enable only when testing specific streams that freeze or drift out of sync. |

## Remote Control Guide

| Key | Default Action |
| --- | --- |
| `↑` / `↓` | Switch to previous / next channel (shows translucent info banner) |
| `OK` / `Enter` | Open full console; press `OK` again to navigate recent channels & quick controls (including `Src 1/N` and Settings gear) |
| `←` | Open progressive drawer: **Channels + EPG → Groups → Sources** |
| `→` | **Previous Channel** by default (remappable in **Settings → Remote**) |
| `Back` | Step back / close overlays; double-Back to exit during fullscreen playback (default ON) |
| `0–9` | Numeric channel jump with compact top-right match list |

> All short-press and long-press actions can be freely remapped or cleared in **Settings → Remote** or via the phone WebUI.

---

## 开源许可 · License

本项目基于 [**GNU General Public License v3.0 (GPLv3)**](LICENSE) 开源发布。
This project is free software licensed under the [GNU General Public License v3.0](LICENSE).
