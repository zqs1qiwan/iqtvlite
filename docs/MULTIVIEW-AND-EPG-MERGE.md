# 多画面 + 多 EPG 源叠加 + TV 设计改进

> 日期：2026-11-17
> 依据：Android TV 设计指南（10-foot UI / D-pad / 即时反馈）

---

## 1. MultiView 2x2（TiviMate Premium 功能）

### 实现
- `MultiViewActivity.kt`：2×2 四个独立 `ExoPlayer` + `PlayerView`（SurfaceView 硬解）
- **每窗口独立 UA**：解析播放列表的 `http-user-agent="VLC"`，每路用自己的 OkHttp DataSource
- **每窗口独立音频**：只有一个窗口出声，点窗口切换（`setAudioWindow`），选中窗口蓝色边框高亮
- 单路失败只显示错误提示，不影响其他窗口
- 按钮入口：主控行「多画面」

### 真机验证（TR100-G9 Android TV 盒子）
```
I iqtv-multiview: window 0 start: CCTV-1 综合 ua=VLC/3.0.20 LibVLC/3.0.20
I iqtv-multiview: window 1 start: CCTV-2 财经 ua=VLC/3.0.20 LibVLC/3.0.20
I iqtv-multiview: window 2 start: CCTV-3 综艺 ua=VLC/3.0.20 LibVLC/3.0.20
I iqtv-multiview: window 3 start: CCTV-4 中文国际 ua=VLC/3.0.20 LibVLC/3.0.20
I iqtv-multiview: audio window -> 0
I iqtv-multiview: window 0 first frame: CCTV-1 综合
I iqtv-multiview: window 1 first frame: CCTV-2 财经
```
→ 四路同时起播、各自 UA、独立音频焦点、首帧渲染成功。

---

## 2. 多 EPG 源叠加 / 优先级合并

### 实现
- `iqtvlite-epg::merge`：按优先级稳定排序（数字小 = 高优先级，manual=10 < m3u_header=20 < xtream=30）
- 同 `(tvg-id, start_ts)` 的节目高优先级覆盖低优先级；低源独有的保留（填补充缺）
- 同名频道名也按高优先级胜出
- 5 个单测覆盖：覆盖 / 胜出 / 独有保留 / 同优先级稳定 / 窗口过滤
- JNI `mergeEpgSources` 暴露给 Android

---

## 3. Android TV 设计指南落地

参考 https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv

| 原则 | 落地 |
|---|---|
| **10-foot UI**（3 米距离，文字/控件要大） | 状态栏 18sp；按钮 minHeight 48dp / minWidth 80dp；搜索框 18sp；字幕 32dp |
| **D-pad 导航 + 即时明显反馈** | `focus_border.xml`：3dp 蓝色边框 + 高亮背景，应用于所有按钮/Spinner/输入框；频道行 `channel_selector.xml` |
| **内容优先，减少文字** | 频道列表 = 编号 + logo + 名称 + 分组，紧凑一行 |
| **共享设备隐私** | 收藏/最近观看仅本地存储，无云同步、无遥测 |

---

## 4. 测试

166 个 Rust 测试通过（原 161 + 5 个多 EPG 合并），clippy -D warnings 零告警。
