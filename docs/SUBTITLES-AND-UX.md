# 字幕能力与 TV 观感改进

> 日期：2026-11-17
> 用户反馈：录像不必要；**字幕能力 / UI-UX / 电视观感 / 播放能力**更重要。
> 尤其 Red Bull 频道有字幕，而原播放器支持不好。

---

## 1. 字幕问题诊断（真实数据）

Red Bull 频道的 master playlist（`https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8`）：

```
#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="CC",LANGUAGE="eng",NAME="English",INSTREAM-ID="CC1"
#EXT-X-STREAM-INF:BANDWIDTH=...,CLOSED-CAPTIONS="CC"
```

→ 字幕是 **CEA-608 内嵌字幕**（`INSTREAM-ID=CC1`），藏在 TS 视频流里，**不是外部 WebVTT 文件**。

**为什么原播放器支持不好**：
Media3 默认不会把 TS 流里的 CEA-608 暴露为可选字幕轨 —— 必须显式注册字幕格式 + 开启字幕解析。

---

## 2. 实现

### 2.1 播放器层（`PlaybackService.kt`）

```kotlin
val ccFormats = listOf(
    Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
        .setAccessibilityChannel(1)  // CC1
        .setLanguage("en")
        .build()
)
val extractorsFactory = DefaultExtractorsFactory()
    .setTsSubtitleFormats(ccFormats)                    // ← 注册 CEA-608
    .setTsExtractorTimestampSearchBytes(1_000_000)      // ← 兼容无 AUD/IDR 的 TS

val mediaSourceFactory = DefaultMediaSourceFactory(
    dataSourceFactory, extractorsFactory, DefaultSubtitleParserFactory(),
).experimentalParseSubtitlesDuringExtraction(true)
```

### 2.2 字幕管理（`SubtitleManager.kt`）

- 从 `player.currentTracks` 提取所有 TEXT 轨（CEA-608 / WebVTT / TS 内嵌）
- 「字幕」按钮循环切换：关闭 → 轨道 1 → 轨道 2 → … → 关闭
- 用 `TrackSelectionOverride` 选中指定轨道

### 2.3 TV 大屏字幕样式（10-foot UI）

```kotlin
CaptionStyleCompat(
    Color.WHITE,                        // 白字
    Color.parseColor("#C0000000"),      // 半透明黑底
    Color.TRANSPARENT,
    EDGE_TYPE_OUTLINE, Color.BLACK, null,
)
setFixedTextSize(COMPLEX_UNIT_DIP, 32f) // 大字号
```

---

## 3. 真机验证证据

```
I iqtvlite: TRACKS_CHANGED groups=4 textTracks=1
I iqtvlite:   TEXT group 2 len=1 format=application/cea-608 lang=null
I iqtvlite: SUBTITLES_AVAILABLE count=1 labels=[字幕 [CC]]
I iqtv-subtitle: subtitle -> 字幕 [CC]
```

→ CEA-608 字幕轨被正确识别并可通过 UI 切换。

---

## 4. TV 观感改进

| 项 | 改进 |
|---|---|
| 频道焦点 | 新增 `channel_selector.xml`：3dp 蓝色边框 + 深色高亮背景（原来只有淡淡的 ripple） |
| 状态栏 | 字号 15sp → 18sp，颜色提亮 |
| 字幕 | 32dp 大字号 + 描边 + 半透明黑底，任何画面清晰可读 |
| 控制按钮 | 字幕按钮加入顶部控制行 |

---

## 5. 测试

161 个 Rust 测试通过，clippy -D warnings 零告警。
