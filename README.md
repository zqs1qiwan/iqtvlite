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
    <a href="#为什么选择-iqtv-lite">产品优势</a> ·
    <a href="#界面展示">界面展示</a> ·
    <a href="#核心功能">核心功能</a> ·
    <a href="#播放列表多线路与自定义-header-示例">播放列表与 Header</a> ·
    <a href="#安装与更新">安装与更新</a> ·
    <a href="#遥控器操作指南">遥控器操作</a> ·
    <a href="#english-overview">English</a>
  </p>
</div>

---

## 为什么选择 IQTV Lite

- **完全免费与开源（GPLv3），零广告、无付费解锁墙**：多播放源切换、XMLTV 节目单、回看、自定义按键、手机扫码控制台与内置更新全部免费可用，没有会员门槛。
- **极致轻量化与流畅性能优化**：安装包约 **8.6 MB**，底层采用轻量化原生高性能解析核心，面对上万条频道与大体积 EPG 节目单也能秒级加载与检索；选台列表与硬件解码全链路零等待，即使在老旧入门级电视盒子上连续上下换台、快速翻组也依然顺滑跟手。
- **开箱兼容复杂直播源**：完整支持防盗链 `Referer` / `User-Agent` / `Cookie` 等请求头配置、单台多线路失效自动轮询、以及同 `tvg-id` 下的 4K 与标准高清独立区分。

---

## 界面展示

<table>
  <tr>
    <td width="50%" align="center">
      <img src="assets/screenshots/player-infocard.png" alt="播放信息卡与控制台" />
      <br />
      <sub><b>双层智能信息栏</b>：换台仅轻量通透展示当前频道；按 OK 主动呼出完整控制台，下方一行集成 5 个最近观看与画面比例 / 音轨 / 字幕 / 画中画 / 设置快捷控制</sub>
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
      <img src="assets/screenshots/settings.png" alt="简洁电视端设置菜单" />
      <br />
      <sub><b>大屏设置中心</b>：中英文实时切换、大/中/小界面缩放、按键自定义、多源管理与设置内联检查更新，无多余弹窗与打扰</sub>
    </td>
  </tr>
  <tr>
    <td colspan="2" align="center">
      <img src="assets/screenshots/webui-qr.png" alt="手机扫码与多源网页控制台" width="72%" />
      <br />
      <sub><b>手机扫码多源控制台</b>：同一局域网下手机扫码或浏览器访问，免遥控器输入直接管理多源、全局请求头与播放偏好</sub>
    </td>
  </tr>
</table>

---

## 核心功能

- **双层底部信息栏**：
  - **上下键换台**：弹出更通透轻量的底部条，铺满展示当前台标、频道号、节目名称、进度条与实时视频参数（分辨率 / 帧率 / 声道 / HDR / 编码），数秒后自动收起。
  - **按 `OK` 主动呼出**：展开稳重不透明的完整两行控制台——上方保留当前频道展示，下方一整行左侧展示 **最近观看 5 个频道**（台标 + 台名），右侧无缝衔接 **线路（多源时）· 画面比例（适应 / 铺满 / 拉伸）· 音轨 · 字幕 · 画中画（系统支持时）· 设置** 快捷控制。
- **逐级展开选台抽屉**：
  - 按 `←` 打开 **频道 + 节目单**，聚焦长节目名自动平滑滚动；再次点击当前正在播放的直播台会直接收起抽屉，不重复加载。
  - 再按一次 `←` 自动收起节目单，展示 **分组 + 频道**。
  - 若启用了多个播放源，继续按 `←` 展开最左侧 **播放源** 列表快速切换。
- **聚合多线路与失效自动轮询**：
  - 同一 `#EXTINF` 下附带多条备用地址会聚合为单一频道的多条线路，支持手动选源与播放失败时自动轮询下一条健康线路；不同 `#EXTINF` 条目严格保持各自独立。
- **手机扫码远程配置（中英双语网页端）**：
  - 支持多标签网页端直接管理「播放源与请求头」「显示与播放」「更新设置」「遥控按键」，修改立即同步到电视端。
- **双语界面与三档大屏缩放**：
  - 首次启动主动选择 **简体中文** 或 **English**，随时可在设置或网页端切换，并提供 **小 / 中 / 大** 文字与台标尺寸。

---

## 播放列表多线路与自定义 Header 示例

### 1. 多线路聚合 vs 独立频道
- **同一频道多条备用线路**（一个 `#EXTINF` 下跟多行地址，自动合并并提供失效轮询与「线路」切换）：
```m3u
#EXTINF:-1 tvg-id="dongfangweishi" tvg-name="东方卫视" tvg-logo="https://logo.laobaitv.net/东方卫视" http-user-agent="VLC" group-title="卫视频道",东方卫视
http://example.com/line1.m3u8
http://example.com/line2.m3u8
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

- **方式 A：播放地址后用 `|` 拼接（多个 Header 使用 `&` 或 `;` 连接）**
```text
https://example.com/live.m3u8|User-Agent=VLC&Referer=https://example.com/&Origin=https://example.com&Cookie=sid=123
```
- **方式 B：`#EXTINF` 行内属性或 `#EXTVLCOPT:` 指令**
```m3u
#EXTINF:-1 http-user-agent="VLC" http-referer="https://example.com/" http-origin="https://example.com",示例频道
http://example.com/stream.m3u8
```
- **方式 C：全局自定义 User-Agent 与全局 Header（在「设置 → 播放列表及节目单」或手机网页端配置，每行一条）**
```text
Referer: https://example.com/
Origin: https://example.com
X-Custom-Token: your-token
```

---

## 安装与更新

1. 前往 [**Releases 最新版本**](https://github.com/zqs1qiwan/iqtvlite/releases/latest) 下载 `IQTV-Lite-v0.1.1.apk`（或通用最新包 `IQTV-Lite-latest.apk`）。
2. 将 APK 传至 Android TV / 电视盒子安装（支持 Android 7.0 及以上，兼容 32 位与 64 位 ARM 设备）。
3. 后续可在电视端「设置 → 关于 → 检查更新」直接内联检测并下载安装新版本，也可手动前往 GitHub Releases 更新。

---

## 遥控器操作指南

| 按键 | 功能说明 |
| --- | --- |
| `↑` / `↓` | 切换上 / 下一个频道（弹出轻量半透明当前台信息卡并自动收起） |
| `OK` / `Enter` | 呼出完整控制信息栏；再次按 `OK` 在底部最近观看 5 台与右侧快捷控制图标间横向选择 |
| `←` | 逐级打开选台抽屉：**频道 + 节目单 → 分组 → 播放源** |
| `→` / `Menu` | 打开右侧电视设置菜单 |
| `Back` | 逐级退回或关闭菜单；在播放器全屏状态可按设置好的快捷键退出 |
| `数字键` | 直接输入频道号跳转 |

> 以上短按与长按行为均可在「设置 → 遥控器与操作」或手机网页端自由改键或置空。

---

## English Overview

**IQTV Lite** is a free, open-source (GPLv3), ad-free IPTV player crafted for Android TV remotes and entry-level TV boxes.

- **Lightweight & Fast**: ~8.6 MB footprint powered by a fast native playlist & XMLTV parser for instant channel zapping and smooth scrolling even on modest TV hardware.
- **Two-Tier Bottom Info Console**: Channel zapping shows a cleaner, more translucent full-width channel banner. Pressing `OK` reveals the full console adding 5 quick-switch recent channels on the left and quick channel controls (**Source · Aspect Ratio · Audio · Subtitles · PiP · Settings**) on the right.
- **Exact M3U Grouping & Auto-Failover**: Multiple URLs under one `#EXTINF` aggregate into a switchable multi-source channel with automatic failover polling, while separate `#EXTINF` entries stay independent. Supports inline `|User-Agent=...&Referer=...` (joined with `&`), `#EXTINF`/`#EXTVLCOPT` attributes, and global per-line headers.
- **Scan-to-Configure Web Console**: Scan the on-TV QR code from your phone or PC browser to manage playlists, HTTP headers, display scale (`Small` / `Medium` / `Large`), UI language (`简体中文` / `English`), and remote bindings.
- **Install**: Download the signed APK from [**Latest Release**](https://github.com/zqs1qiwan/iqtvlite/releases/latest) (`Android TV 7.0+`, `arm64-v8a` & `armeabi-v7a`).

---

## 开源许可 · License

本项目基于 [**GNU General Public License v3.0 (GPLv3)**](LICENSE) 开源发布。
This project is free software licensed under the [GNU General Public License v3.0](LICENSE).
