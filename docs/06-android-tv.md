# 06 — Android TV 平台适配

> 本文是**硬约束集合**，不是建议清单。每一条都来自 Android 官方文档或 AOSP 源码。

---

## 1. Surface 合成方案（ADR-008，最高风险项）

### 1.1 问题

`SurfaceView` 不是普通 View —— 它在 SurfaceFlinger 里是**独立 layer**，默认位于应用窗口**之下**。我们要让 Rust/Slint 的原生 UI 画在视频**之上**。

### 1.2 澄清：`gatherTransparentRegion` 不是解法

很多技术博客（含我最初引用的那篇）建议「覆写 `gatherTransparentRegion` 让 SurfaceFlinger 尊重覆盖层」。**这是错的。**

AOSP `core/java/android/view/SurfaceView.java` 的实现：

```java
@Override
public boolean gatherTransparentRegion (Region region) {
    if (isAboveParent() || ! mDrawFinished) {
        return super.gatherTransparentRegion(region);
    }
    boolean opaque = true;
    if ((mPrivateFlags & PFLAG_SKIP_DRAW) == 0) {
        opaque = super.gatherTransparentRegion(region);   // 本 View 自己绘制 → 从 region 移除
    } else if (region != null) {
        int w = getWidth(); int h = getHeight();
        if (w > 0 && h > 0) {
            getLocationInWindow(mLocation);
            region.op(l, t, l+w, t+h, Region.Op.UNION);   // 打洞
        }
    }
    return opaque;
}
```

它的作用是**窗口级优化**：告诉 SurfaceFlinger「我这块矩形是透明的，别覆盖写不透明黑」。
它**不控制 Z-order、不创建第二个窗口、不让覆盖层出现在 SurfaceView 之上**。覆写不当会得到黑屏或丢失视频层。

### 1.3 真正的机制：Z-order

| API | 语义 |
|---|---|
| `setZOrderOnTop(true)` | Surface 在**父窗口之上**（`TYPE_APPLICATION_MEDIA_OVERLAY`）→ 窗口内**没有任何东西**能画在它上面 |
| `setZOrderMediaOverlay(true)` | Surface 在**其他 SurfaceView 之上**、但在**父窗口之下**（传统做法） |
| **`setCompositionOrder(int)`**（**API 36 / Android 16**） | 现代通用 API。正值 = 父窗口之上，负值 = 父窗口之后；**同值时相对顺序未定义**。前两者在 API 36 **已废弃** |

**关键事实**：同一 Activity 内的两个 SurfaceView，**若都用负的 composition order，它们的相对顺序由数值大小决定**（大者在上）；**相同数值则顺序未定义**。

### 1.4 四个候选方案对比

| 方案 | 做法 | 视频可见 | UI 可见 | 开销 | TV 稳健性 | 结论 |
|---|---|---|---|---|---|---|
| **(a) 两窗口** | 视频 Activity + UI Activity（`TYPE_APPLICATION_MEDIA_OVERLAY` + `FLAG_TRANSLUCENT`） | ✅ | ✅ | 双窗口双缓冲 | ❌ **差**：焦点/D-pad 在两窗口间路由脆弱；需 `SYSTEM_ALERT_WINDOW` 权限（Play 政策风险） | ❌ 排除 |
| **(b) TextureView** | `player.setVideoTextureView(tv)` | ✅ | ✅ | **功耗 +30%**（ExoPlayer#1084：390mA vs 295mA）；GPU→CPU→GPU 拷贝；**丢失原生 HDR** | ⚠️ 可用但 4K/HDR 受损 | ⚠️ 仅兜底 |
| **(c) SurfaceControl.Transaction** | `buildReparentTransaction()` / `applyTransactionOnDraw()` | ✅ | ✅ | 低 | ✅ Media3 有官方 demo（`demos/surface`），可在不中断播放的前提下 reparent | ✅ 高级场景（PiP/多画面/离屏） |
| **(d) 同 Activity 内兄弟 View** | `FrameLayout`：`SurfaceView`（视频）+ 原生 UI View（**兄弟节点，在其上**） | ✅ | ✅ | 低（单窗口） | ✅ **最佳** | ✅ **采用** |

Android 文档明确支持 (d)：
> *"The view hierarchy will take care of correctly compositing with the Surface any siblings of the SurfaceView that would normally appear on top of it."*

### 1.5 采用方案 (d) 的具体结构

```xml
<!-- res/layout/activity_main.xml -->
<FrameLayout android:id="@+id/root"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:keepScreenOn="true">

    <!-- 视频层：SurfaceView -->
    <SurfaceView android:id="@+id/video_surface"
        android:layout_width="match_parent" android:layout_height="match_parent" />

    <!-- UI 层：Rust/Slint 原生 View，作为兄弟节点在其上 -->
    <iqtvlite.ui.SlintView android:id="@+id/slint_ui"
        android:layout_width="match_parent" android:layout_height="match_parent" />
</FrameLayout>
```

**Z-order 设置**：
```kotlin
// API 36+ (Android 16)
if (Build.VERSION.SDK_INT >= 36) {
    videoSurface.setCompositionOrder(-2)   // 负值 = 在父窗口之后
} else {
    @Suppress("DEPRECATION")
    videoSurface.setZOrderMediaOverlay(true)  // 传统路径：在其他 SurfaceView 之上、父窗口之下
}
```

**注意**：Slint 1.18 的 Android 后端走 `android-activity` 的 `NativeActivity`，**渲染到一个由平台胶水创建的 SurfaceView**，我们无法从 Rust 设置它的 composition order。**因此采用 (d) 需要我们自己写 Kotlin Activity + 一个 `SlintView`（自定义 `View`）**，而不是用 Slint 的 `NativeActivity` 后端。

**这是 M0 spike 必须验证的核心项。**备选：Slint 是否提供把 UI 渲染到自定义 `Surface`/`ANativeWindow` 的 API（`slint::platform::android`），若有则可让 Slint 渲染进我们自己的 SurfaceView，从而完全掌控 Z-order。

### 1.6 备选：两个 SurfaceView 同窗口

如果 Slint 无法嵌入 View，则：

```
Activity window
 ├─ SurfaceView #1 (video)   compositionOrder = -2   ← ExoPlayer 输出
 └─ SurfaceView #2 (Slint)   compositionOrder = -1   ← Rust UI
```

两个都在父窗口之后，靠 composition order 定序。**Rust UI 必须带 alpha 渲染**，让视频从洞里透出来。
风险：Slint 的 SurfaceView 由平台胶水创建，我们无法设置其 composition order → 可能需要 patch `slint` 或 `android-activity`。

---

## 2. Manifest 与商店合规

### 2.1 必需的 Manifest 配置

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-feature android:name="android.software.leanback" android:required="false"/>
  <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
  <!-- 不要 required="true"，除非我们实现 TIF（那会限制 Play 分发） -->
  <uses-feature android:name="android.hardware.live_tv" android:required="false"/>

  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
  <uses-permission android:name="android.permission.WAKE_LOCK"/>

  <application android:icon="@mipmap/ic_launcher"
               android:banner="@drawable/banner"
               android:theme="@style/Theme.Leanback">

    <activity android:name=".MainActivity"
        android:screenOrientation="landscape"
        android:exported="true"
        android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize|uiMode">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
      </intent-filter>
    </activity>

    <service android:name=".PlaybackService"
        android:foregroundServiceType="mediaPlayback"
        android:exported="true">
      <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService"/>
      </intent-filter>
    </service>
  </application>
</manifest>
```

### 2.2 商店素材规格

| 项 | 规格 |
|---|---|
| **Banner** | **320 × 180 px**，放在 `drawables/xhdpi/`，**每个支持语言一张**，必须含应用名 |
| **图标** | 至少 **160 × 160 px** at xhdpi |

### 2.3 质量审核项（会被审计）

| ID | 要求 |
|---|---|
| `TV-LM` / `TV-LB` / `TV-BN` | 启动器图标、320×180 banner、banner 含应用名 |
| `TV-ML` | `LEANBACK_LAUNCHER` intent |
| `TV-MT` | 不要求触摸屏 |
| `TV-PS` | minSdk ≤ 31 为佳 |
| `TV-IE` / `TV-IH` / `TV-IQ` / `TV-IS` | PiP 规则（见 §6） |
| `TV-ME` | 内存占用 |

---

## 3. 系统 EPG：**不要**发布到 `TvContract.Programs`

### 3.1 事实

`TvContract.Programs` / `TvContract.Channels` 属于 **TV Input Framework (TIF)**，需要：

```xml
<uses-permission android:name="com.android.providers.tv.permission.WRITE_EPG_DATA"/>
<uses-feature android:name="android.software.live_tv" android:required="true"/>
```
外加 `TvInputService` + `EpgSyncJobService` + 设置页 Activity。

**而 TIF 仅对 OEM 开放，且只在 Android 5.0–7.1（API 21–25）有效**：

> *"The TV Input Framework is meant to be used by OEMs to build channels for the Android system TV app. It is supported in Android 5.0 (API level 21) through Android 7.1 (API level 25) **only**. Third-party apps should build channels for their content using the Android TV home screen APIs."*
> — developer.android.com/training/tv/tif

### 3.2 结论

**播放器应用不需要、也不应该发布系统 EPG。** 我们在自己的 SQLite 里渲染 EPG（这正是 TiviMate 的做法）。

**替代方案**：用 Home Screen 预览 API 让频道出现在 Google TV 桌面：

```kotlin
val channel = PreviewChannel.Builder()
    .setChannelId(channelId)
    .setType(TvContractCompat.Channels.TYPE_PREVIEW)
    .setDisplayName(name)
    .setAppLinkIntentUri(uri)
    .build()
// 需要一个 BroadcastReceiver 处理 ACTION_INITIALIZE_PROGRAMS
```

配合 `PreviewProgram.Builder()` 可以在桌面显示当期节目卡片。**这是 v2 的加分项。**

---

## 4. PiP：**不可用**

### 4.1 硬性禁止

> *"Your app must declare a meta-data value attribute of `com.google.android.tv.pip.category`... **Usage types that don't fall into any of these categories, in particular any playback of media content, are not allowed in picture-in-picture mode on TV.**"*

允许的类别：`communication`、`smartHome`、`health`、`ticker`。**没有「媒体播放」这一类。**

> ⚠️ TiviMate 的「PiP」是**应用内 mini-player**，不是 Android 系统 PiP。我们也这样做。

### 4.2 其他 PiP 限制

- Android 8.0 引入 PiP，但 **Android TV 上支持不佳**；**Google TV 在 Android 13 之前完全不支持**。
- **Android 14 (API 34) 才有 `enterPictureInPictureMode()`**；更早的 TV 设备完全不支持。
- 用 `PackageManager.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)` 检测。

---

## 5. 媒体会话、音频焦点、遥控器

### 5.1 MediaSessionService

```kotlin
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setLoadControl(ipTvLoadControl())
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setId("iqtvlite-session")
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }
}
```

**Media3 1.11 的变化**：媒体按键事件现在由 Media3 内部处理（`MediaSessionService.onStartCommand` 收到后不再路由回平台 session）。

### 5.2 音频焦点

```kotlin
player.setAudioAttributes(
    AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build(),
    /* handleAudioFocus = */ true   // ExoPlayer 自己管理 AudioFocusRequest
)
```

**TV 上额外要求**：
- `WAKE_LOCK` 权限 + `player.setWakeMode(C.WAKE_MODE_LOCAL)`（或 `NETWORK`）→ 播放时 CPU 不休眠。
- 处理 `ACTION_AUDIO_BECOMING_NOISY`（拔耳机）→ 注册 `BroadcastReceiver`。
- **HDMI/ARC 断开**（电视切输入或关机）→ 配合系统的电源管理集成暂停播放。

### 5.3 遥控器按键

`MediaSession` 自动处理：`KEYCODE_MEDIA_PLAY` / `_PAUSE` / `_PLAY_PAUSE` / `_STOP` / `_NEXT` / `_PREVIOUS` / `_REWIND` / `_FAST_FORWARD` / `_SKIP_FORWARD` / `_SKIP_BACKWARD`。

**频道上下键不是标准媒体键**，需自己处理：
```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_CHANNEL_UP   -> { zapper.next(); true }
    KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapper.prev(); true }
    else -> super.onKeyDown(keyCode, event)
}
```
（OwnTV 也是这么做的，它的 README 明确列了「Remote Shortcuts — 把备用按键、数字键、频道键和媒体键映射到 26 个动作」。）

**D-pad**：`SlintView`（自定义 View）覆写 `onKeyDown` / `dispatchKeyEvent`，把 `KEYCODE_DPAD_*` 转发给 Slint。

### 5.4 不依赖的按键

TV 审核清单要求所有元素都能用 D-pad 和游戏手柄操作。**不要依赖** `KEYCODE_BUTTON_START` / `KEYCODE_MENU` / `KEYCODE_SELECT` / `KEYCODE_SEARCH`。

### 5.5 Recents / 恢复

- 任何 Activity 都会出现在 recents，无需特殊配置。
- 想要「从 recents 恢复播放」，设 `android:alwaysRetainTaskState="true"` 并持久化频道 + 播放位置。

---

## 6. 其他 TV 约束

| 项 | 事实 | 我们的做法 |
|---|---|---|
| **Ambient Mode** | 视频播放时必须阻止设备进入 Ambient Mode | 根 View `android:keepScreenOn="true"` + `setKeepScreenOn(true)` |
| **Overscan** | 电视会过扫描，边缘内容会被裁掉 | 所有布局留安全边距（建议 ≥ 24dp） |
| **低内存设备** | `ActivityManager.isLowRamDevice()` | 降级：关闭预热池、降低 EPG 网格密度 |
| **系统搜索** | 实现 `android.media.tv.action.QUERY` | v2：让频道出现在系统搜索框 |
| **Compose for TV** | `androidx.tv:tv-material` 1.1.0；Leanback UI Toolkit **已废弃** | 我们用 Slint，不受影响 |
| **系统 UI 叠加** | leanback 系统 UI 在应用窗口之上绘制 → 会盖住视频 | 正确行为；全屏用 `WindowInsetsController.hide(Type.systemBars())` |
| **截图保护** | DRM 内容需要 | `SurfaceView.setSecure(true)` + `FLAG_SECURE` |

---

## 7. 解码能力探测

### 7.1 运行时探测

```kotlin
// 1) 该格式+profile 是否有解码器？
val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
val format = MediaFormat().apply {
    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10)
}
val codecName = list.findDecoderForFormat(format) ?: /* 不支持 */

// 2) 显示设备是否支持该 HDR 类型？
val supported = display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
val hasHlg = supported.contains(Display.HdrCapabilities.HDR_TYPE_HLG)
val hasDv  = supported.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
```

**注意**：`MediaCodecList.getCodecInfoAt(int)` 已废弃，用 `getCodecInfos()`；`MediaCodecInfo.isAlias()` 是 API 29+。

### 7.2 Dolby Vision 在 TV 上的现实

**大多数 Android TV 盒子（Amlogic / Realtek SoC）根本没有 Dolby Vision 硬件解码器。**

现实的 DV 策略：
1. 检测到 DV 流 → 检查显示设备是否支持 DV。
2. 不支持 → 优先选 **HDR10 base layer 轨道**（Profile 8.1），Media3 的轨道选择器已内置此逻辑（`ExoPlayer` 会「prefer other tracks to Dolby Vision if display does not support it」）。
3. Profile 5（**无 base layer**）→ 用 Rust `dolby_vision` crate 转成 8.1 → 交给 ExoPlayer 以 HDR10 播放。

**结论：DV 支持完全不需要 libmpv。** 这是我们与 mpv 路线最大的差异化优势。

### 7.3 Media3 各格式支持度

| 容器 | HLS | DASH | 直接流 | RTSP |
|---|---|---|---|---|
| **MPEG-TS** | ✅ | ❌（官方「无支持计划」） | ✅ | — |

**TS 流的两个经典坑**（IPTV 播放器天天遇到）：
> *"Some MPEG-TS files do not contain access unit delimiters (AUDs)... Similarly, some MPEG-TS files do not contain IDR keyframes."*
> → ExoPlayer 会**卡在 buffering 状态**。

**解法**：
```kotlin
val extractorsFactory = DefaultExtractorsFactory()
    .setEnableTsInsertionPackets(false)
    .setTsDurationReader(DefaultTsDurationReader(1_000_000))

// 无 AUD 时：
DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
// 无 IDR 时：
DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
```

> **这是「更稳定」的一个具体落点**：M3U 里的 TS 流经常没有 AUD，默认配置会卡死。我们要在探测到 TS 时主动开这两个 flag。

**RTSP 限制**（`media3-exoplayer-rtsp`）：仅 H264（SDP 的 `fmtp` **必须**带 SPS/PPS）、AAC(ADTS)、AC-3。**无 HEVC、无字幕、无 DRM。** 传输：RTP/UDP 单播或 TCP 内联。BASIC + DIGEST 认证。

---

## 8. 换台速度调优（实测数据支撑）

### 8.1 Media3 默认缓冲（对 IPTV 来说过大）

Media3 1.11.x `DefaultLoadControl.java`：

```java
DEFAULT_MIN_BUFFER_MS                     = 50_000;   // 50 秒
DEFAULT_MAX_BUFFER_MS                     = 50_000;   // 50 秒
DEFAULT_BUFFER_FOR_PLAYBACK_MS            =  1_000;   // 1 秒 (ExoPlayer 2.x 是 2.5s)
DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000;  // 2 秒 (2.x 是 5s)
```

### 8.2 IPTV 直播的推荐配置

```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMsForStreaming(
        /* minBufferMs = */                     1_500,
        /* maxBufferMs = */                     6_000,
        /* bufferForPlaybackMs = */               500,
        /* bufferForPlaybackAfterRebufferMs = */ 1_000
    )
    .setBackBuffer(/* backBufferDurationMs = */ 10_000, /* retainFromKeyframe = */ true)
    .build()
```

**最关键的单一开关** —— `LiveConfiguration.setTargetOffsetMs()`：

```java
// DefaultLoadControl.shouldStartPlayback()
if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
    minBufferDurationUs = Math.min(parameters.targetLiveOffsetUs / 2, minBufferDurationUs);
}
```
→ **把所需起始缓冲减半**。

```kotlin
val mediaItem = MediaItem.Builder()
    .setUri(url)
    .setLiveConfiguration(
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(3_000)      // 追直播点
            .setMinPlaybackSpeed(0.97f)     // 缓速追帧
            .setMaxPlaybackSpeed(1.03f)
            .build()
    )
    .build()
```

### 8.3 起播成本分解与对策

| 成本 | 对策 |
|---|---|
| ① manifest 拉取（1 RTT + TLS 握手） | **OkHttp `ConnectionPool` 保温**（TiviMate 的做法，反编译确认：min 1000 / max 2500 / playback 500 / afterRebuffer 1000 + `ConnectionPool(5, 5, TimeUnit.MINUTES)`）→ 省掉每次换台的 TLS 握手（200–500 ms） |
| ② DRM 授权（1 RTT） | 无（IPTV 通常无 DRM） |
| ③ 缓冲到 `bufferForPlaybackMs` | 上面的 LoadControl 调参 |
| ④ 解码器初始化 | **复用 ExoPlayer 实例**：`setMediaItem()` 而非 `release()`（`stop()` + `clearMediaItems()` 会释放解码器） |

**Media3 1.11 新增 `PlayerPool` / `rememberPooledPlayer`** —— 正是为滑动窗口 UI 设计的预加载机制，可直接用于我们的「预热下一个频道」。

### 8.4 社区基准

| 来源 | 数值 | 质量 |
|---|---|---|
| **ExoPlayer#8703**（官方回复） | *"The regular behavior is about **1–1.5 sec** loading between zapping"*（光纤网络） | ⭐ 一手来源 |
| TiviMate 反编译分析 | 自定义 LoadControl + 保温连接池 → **亚秒级换台** | ⚠️ 逆向，非官方确认 |
| 供应商博客（ptiptv.com） | MPEG-TS 2.5–4.5 s；HLS 0.3–0.8 s；RTSP 3.0–5.0 s | ⚠️ 营销内容，仅方向性参考 |

**⚠️ 重要**：**未找到**任何可信的「ExoPlayer 在 Android TV 硬件上冷启动」的公开基准数据（Fire TV / Shield / Chromecast with Google TV，HLS vs MPEG-TS 分列）。
→ **必须自己在目标硬件上实测**。这写进了 `07-roadmap.md` 的 M0 验收条件。

---

## 9. 未知与待验证清单

| # | 待验证 | 风险 |
|---|---|---|
| 1 | `SurfaceView.setCompositionOrder` 需要 **API 36**。API 30–35 的电视盒子怎么办？ | 🔴 高 —— 可能需要 `minSdk 36`（会排除大量盒子），或维护两条路径 |
| 2 | Slint 1.18 能否把 UI 渲染进**我们自己的** `Surface`/`ANativeWindow`（而非平台创建的 SurfaceView） | 🔴 高 —— 决定要不要自写 `SlintView` |
| 3 | `jarnedemeulemeester/libmpv-android` v1.0.0 的 `.so` 是否 **16 KB 页对齐**（Android 15+ 要求） | 🟠 中 |
| 4 | Slint 的许可选项是否与 **GPL-3.0** 兼容（若要捆绑 libmpv） | 🟠 中 —— 需法务确认 |
| 5 | 目标盒子（Amlogic s905W2 / Mali-G31？）上 ExoPlayer 的真实起播时间 | 🟠 中 |
| 6 | `MediaSessionService` 在某些厂商 ROM 上是否被杀后台 | 🟡 低中 |
| 7 | Android TV 上 `setAudioAttributes(..., handleAudioFocus=true)` 是否被系统正确路由到 HDMI/ARC | 🟡 低中 |
