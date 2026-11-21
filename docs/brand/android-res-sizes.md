# Android TV 品牌资源规格与接入清单（IQTV Lite）

本文件给出可直接落入 Android `res/` 的尺寸、目录、命名与接入方式。资产由
`docs/brand/tools/generate_brand.py` 生成；`docs/brand/android-res/` 是**可直接复制**的 res 树。包身份沿用 `tv.iqtvlite.app`（JNI/签名不受影响）。

## 1. res 树（可直接复制到 `android/app/src/<variant>/res/`）

```
docs/brand/android-res/
├─ mipmap-mdpi/   ic_launcher.png (48×48)   ic_launcher_round.png (48×48)
├─ mipmap-hdpi/   ic_launcher.png (72×72)   ic_launcher_round.png (72×72)
├─ mipmap-xhdpi/  ic_launcher.png (96×96)   ic_launcher_round.png (96×96)
├─ mipmap-xxhdpi/ ic_launcher.png (144×144) ic_launcher_round.png (144×144)
├─ mipmap-xxxhdpi/ ic_launcher.png (192×192) ic_launcher_round.png (192×192)
│                  ic_launcher_foreground.png (432×432 = 108dp @xxxhdpi，内容在中央 66dp 安全区)
├─ mipmap-anydpi-v26/ ic_launcher.xml, ic_launcher_round.xml  (adaptive 模板)
├─ drawable-nodpi/ tv_banner.png (320×180)  splash_mark.png (512×512)
└─ values/ brand_colors.xml
```

> `drawable-nodpi` 仅供 TV banner/启动标这类固定像素用途；**不要**把 banner 放进
> `drawable/` 让系统按密度缩放（会糊）。若项目仍用 `app_banner.xml` + `banner_bitmap`，
> 建议把 `tv_banner.png` 放到 `drawable-nodpi/tv_banner.png`，并让 banner 资源指向它或
> 替换原 `banner_bitmap`；`banner.xml` 的 320×180 透明占位可保留。

## 2. 尺寸与命名总表

| 用途 | 文件 | 尺寸 | 目录 | 备注 |
|---|---|---|---|---|
| 应用图标方 | ic_launcher.png | 48/72/96/144/192 | mipmap-{mdpi..xxxhdpi} | 已有同名则替换 |
| 应用图标圆 | ic_launcher_round.png | 48/72/96/144/192 | mipmap-* | 圆形遮罩 |
| 自适应前景 | ic_launcher_foreground.png | 432×432 | mipmap-xxxhdpi | 108dp，内容 ≤66dp |
| 自适应 XML | ic_launcher.xml / ic_launcher_round.xml | — | mipmap-anydpi-v26 | 引用 `@color/brand_obsidian` + foreground |
| TV Banner | tv_banner.png | 320×180 | drawable-nodpi | Leanback 首页 banner（16:9） |
| Leanback 图标 | app_icon_leanback_512.png | 512×512 | 任意（媒体/详情） | 大图标，仅标记无文字 |
| 启动标 | splash_mark.png | 512×512 | drawable-nodpi | Android 12+ splash 图标 |
| 启动标安全区 | splash_mark_288.png / 见 safe-area 图 | 288×288 | 参考 | 内容在中央 288dp 内 |
| 主 Logo 横版 | logo_primary_dark/light_1200x360.png | 1200×360 | 文档/关于页 | 亮/暗背景 |
| 单色 Logo | logo_monochrome_white/black_1200x360.png | 1200×360 | 水印/印刷 | 单色可重着色 |
| 品牌色 | brand_colors.xml | — | values | 见下 |

## 3. 自适应图标（API 26+）

```xml
<!-- res/mipmap-anydpi-v26/ic_launcher.xml -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/brand_obsidian"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
```

- 前景内容必须落在中央 **66dp** 安全圆（108dp 画布的 2/3），否则被厂商遮罩裁切。
- 本仓库 `android-res/mipmap-xxxhdpi/ic_launcher_foreground.png` 已按 52% 内容比生成（中央约 56dp），
  若厂商遮罩较激进仍安全。
- 旧的 `ic_launcher_foreground.xml`（Android 默认机器人前景）应被本 PNG 替换；`ic_launcher_background.xml`
  可改为纯色 `@color/brand_obsidian`。

## 4. 启动标安全区

Android 12+ `windowSplashScreenAnimatedIcon` 建议 288×288dp 画布、内容在中央约 192dp。
本资产 `splash_mark.png` 为 512 画布、标记占 56%（≈287px），留出安全边距。
示意见 `docs/brand/png/splash_safe_area_1080x1920.png`（外圈 576dp / 内圈 384dp）。

## 5. 品牌色 `values/brand_colors.xml`

```xml
<color name="brand_obsidian">#FF0E1116</color>
<color name="brand_surface">#FF161B22</color>
<color name="brand_surface_elevated">#FF1E242E</color>
<color name="brand_divider">#FF2A3550</color>
<color name="brand_blue">#FF4EA8FF</color>
<color name="brand_amber">#FFF5A524</color>
<color name="brand_ink">#FFF2F5FA</color>
<color name="brand_muted">#FFA9B4C7</color>
```

## 6. 接入注意

- 资产为 PNG/矢量，**不写 Kotlin、不改 Gradle**；将 `android-res/` 内容复制到对应 variant 的 `res/` 即可。
- 当前项目 `res.setSrcDirs(listOf("src/tivimini/res"))`，因此目标是 `android/app/src/tivimini/res/`。
- 替换后旧 `banner_bitmap.png`（约 3.4MB）可由 `tv_banner.png`（约 4KB）替代，顺带减小包体；
  若保留旧图，至少让 banner 资源引用新图。
- 深色电视端：图标/启动标背景统一 `#0E1116`，与视频黑底和 UI surface 协调。
