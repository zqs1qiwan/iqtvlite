# 1.6 — 解包 TiviMate 取真实设计令牌 + 画面比例/解码选项

> 2026-11-17

## 1. 解包 TiviMate 研究（apktool）

用户提供 `tivimate.apk`（20.14 MB），用 apktool 2.7.0 解包：
```
java -jar apktool.jar d -s tivimate.apk -o decoded
→ 184 个 layout、196 个 color、大量 dimens/styles
```

**关键发现**：布局资源名被混淆（都是 `APKTOOL_DUPLICATE_layout_0x7f0eXXXX` 哈希名），无法逐字复制布局。
但**颜色/尺寸数值未混淆** —— 这是 TiviMate 的真实设计令牌：

| 用途 | TiviMate 真实值 |
|---|---|
| 主背景 | `#060606` / `#131313` / `#202020` / `#263238` |
| 强调蓝 | `#2196F3`（Material Blue 500），亮色 `#90CAF9` |
| 主文字 | `#EEEEEE`，次 `#BDBDBD`，三级 `#757575` |
| 蓝灰（进度底） | `#37474F` |
| 琥珀强调 | `#FFC107` |

Strings 也让功能清单更清晰（Channel options / Channel sorting / Groups sorting /
Playlists sorting / Player / Decoder / Aspect / Hide player controls / Catch-up 等）。

### 应用
`colors.xml` 全部改为上述 TiviMate 真实令牌（之前是近似 Apple 风格）。
混淆使"像素级抄布局"不可行，但配色/间距做到了 faithful 还原。

## 2. 画面比例 / 解码器选项（TiviMate 功能）

频道长按菜单新增「🖥 画面比例 / 解码」→ 播放选项对话框：
- **画面比例**：自适应 / 铺满裁切(`SCALE_TO_FIT_WITH_CROPPING`) / 完整显示黑边(`SCALE_TO_FIT`)，
  通过 `player.setVideoScalingMode` 即时生效，偏好持久化
- **硬解失败回退软解**：`DefaultRenderersFactory.setEnableDecoderFallback(true)` 开关

### 换台优化
`DefaultRenderersFactory.experimentalSetEnableMediaCodecVideoRendererPrewarming(true)`
预暖硬解渲染器，降低首个视频帧的解码器初始化耗时（冷换台的一部分）。

### 真机验证
```
启动时: I iqtv-playback: aspect mode=0 scaling=1
切换后: I iqtv-playback: aspect mode=1 scaling=2
偏好: <int name="aspect_mode" value="1" />
```
对话框正确显示两项且可切换。

## 3. 未做（遵守用户禁令）
- ❌ 录制 / 定时录制 / 本地大文件写入 —— 保持彻底删除

## 4. 测试
167 Rust 测试通过，clippy -D warnings 零告警，Kotlin assembleDebug 成功。
真机验证：TiviMate 配色、画面比例切换即时生效+持久化、播放选项对话框、秒播。
