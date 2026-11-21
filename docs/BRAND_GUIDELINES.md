# IQTV Lite 品牌规范（BRAND GUIDELINES）

> 版本 1.0 · 2026-11-20 · 设计负责人 designer2
> 适用：Android TV 端 `tv.iqtvlite.app`（IQTV Lite）。资产见 [docs/brand/](brand/)，生成器 [tools/generate_brand.py](brand/tools/generate_brand.py)。
> **说明：本模型不能读取/目视校验图像。** 本规范以可落地的 SVG/PNG 几何与数值给出；最终视觉需在真实渲染器/真机复核。

## 1. 品牌定位

- **一句话**：轻量、干净、看得清的直播电视。视频是主角，UI 是工具，不抢画面。
- **关键词**：Obsidian 深色、Signal 蓝、Amber 强调、方角、低装饰、零冗余动画。
- **与现有产品统一**：沿用深色电视端调性（现有 Compose `#121212` 背景 / `#1E1E1E` surface），
  品牌底色取更深的 `#0E1116` 作为图标/启动/品牌图背景，蓝/琥珀作为唯一强调色，避免多色装饰。
- **命名**：`IQTV` 主标 + `Lite` 副标；正式书写 **IQTV Lite**（可读作 "IQTV Lite"），不写 "Iqtv"、"IQ TV"。

## 2. Logo

### 2.1 标记（mark）

96×96 单位几何：深色圆角(6)磁贴 `#161B22` + `#2A3550` 细边；中央蓝色播放三角 `#4EA8FF`；
右上琥珀"直播点" `#F5A524`。含义：播放 + 信号/直播。

- 可单独作为应用图标、favicon、加载/水印标记。
- 文件：[mark_dark.svg](brand/svg/mark_dark.svg)、[mark_light.svg](brand/svg/mark_light.svg)、[mark_monochrome.svg](brand/svg/mark_monochrome.svg)。

### 2.2 横版锁定（primary lockup）

`mark` + `IQTV`(700) + `Lite`(300，琥珀)。亮暗两版 + 单色版：

| 版本 | SVG | PNG |
|---|---|---|
| 暗底（主） | [logo_primary_dark.svg](brand/svg/logo_primary_dark.svg) | [png](brand/png/logo_primary_dark_1200x360.png) |
| 亮底 | [logo_primary_light.svg](brand/svg/logo_primary_light.svg) | [png](brand/png/logo_primary_light_1200x360.png) |
| 单色白 | — | [png](brand/png/logo_monochrome_white_1200x360.png) |
| 单色黑 | [logo_monochrome.svg](brand/svg/logo_monochrome.svg) | [png](brand/png/logo_monochrome_black_1200x360.png) |

**排版规范**：`IQTV` 用 700 字重、`Lite` 用 300 字重且为强调色（暗底琥珀 / 亮底可琥珀）；
`Lite` 与 `IQTV` 基线对齐、间距 16 单位（以 140 号字为例）。不做上/下标、不缩小成"™式"小字。

### 2.3 留白与最小尺寸

- **净空**：Logo 四周至少保留 **标记高度的 0.5×** 空白；背景边界不得进入该区域。
- 横版最小宽度：屏幕 **220 px**（再小改用 mark）；TV Banner 内 Logo 不低于 150×45。
- 标记最小：**24 px**（数字 UI）/ **48 px**（应用图标）。

### 2.4 禁用示例（Don'ts）

1. 不拉伸/压缩/旋转/倾斜 Logo；等比缩放。
2. 不加投影、外发光、描边、渐变、3D 或模糊。
3. 不改变配色（仅允许：暗底用 `#F2F5FA`+`#F5A524`，亮底用 `#0E1116`+`#F5A524`，单色用白/黑或纯色重着色）。
4. 不把 Logo 放在高对比照片/视频帧上而不加 **≥60% 不透明深色底**。
5. 不拆分/重排 `IQTV` 与 `Lite`；不把 `Lite` 做成右上角小角标。
6. 不在 Logo 周围加边框/圆角容器（除非是应用图标磁贴本身）。
7. 不用旧 Android 机器人前景或旧 `banner_bitmap` 作为品牌主视觉。

## 3. 色彩

| Token | Hex | 用途 | 对比度（对 `#0E1116`） |
|---|---|---|---|
| Obsidian | `#0E1116` | 品牌底/图标/启动 | — |
| Surface | `#161B22` | 面板/抽屉底 | — |
| Elevated | `#1E242E` | 浮层/弹窗/选中底 | — |
| Divider | `#2A3550` | 1dp 分隔/描边 | — |
| Signal Blue | `#4EA8FF` | 焦点边框、选中、链接、进度 | ≈7:1（UI 大字号） |
| Amber | `#F5A524` | LIVE/当前节目/品牌点/警示 | ≈8:1 |
| Ink | `#F2F5FA` | 主文字 | ≈16:1 |
| Muted | `#A9B4C7` | 次要文字/时间 | ≈8.5:1 |
| Disabled | `#6B768B` | 禁用 | ≈4:1（仅非关键） |
| Danger | `#EF5350` | 错误 | ≈5:1 |
| Success | `#35C48F` | 成功/已连接 | ≈6:1 |

对比度按 WCAG 相对亮度估算；实现时以真机/工具复核，正文保持 **≥4.5:1**，大字号/焦点 **≥3:1**。
强调色只用于"焦点/选中/直播/警示"，不要大面积铺蓝或铺琥珀。

### 3.1 UI 状态叠加色（焦点**不描边**）

> 用户明确反馈：不要大方框/硬边框标记焦点，整体不要硬框架。界面焦点只用**底色叠加 + 文字提亮 + 2dp 细强调**，禁止 `border`/描边/外发光/大阴影。

| 用途 | 值 | 说明 |
|---|---|---|
| 焦点底 | `#FFFFFF14`（白 8%） | 叠在 `#161B22` 上 ≈ `#292D34` |
| 按下 | `#FFFFFF24`（白 14%） | 瞬时，无位移 |
| 焦点文字 | `#F2F5FA` | 字重 400→500/600 |
| 普通文字 | `#A9B4C7` | 非焦点行 |
| 结构 hairline | `#0FFFFFFF`（1px） | 仅分隔，**绝不用于焦点** |
| 选中/播放中 | `#4EA8FF` 2dp 细条/小点 | 持久，不用整行大框/粗描边 |
| NOW/直播点 | `#F5A524` 2dp | — |

完整逐页样式与 token 见 [UI_VISUAL_SPEC_TIVIMATE.md](UI_VISUAL_SPEC_TIVIMATE.md) §4/§7。

色板图：[palette_swatches.png](brand/png/palette_swatches.png)。

## 4. 字体

- **TV 优先 Roboto / Android 系统字体**（`sans-serif`）；中文回退系统 Noto Sans CJK。
- 字重：`700`（频道名/主标）、`500`（次级标题）、`400`（正文）、`300`（"Lite"、辅助）。
- TV 字号阶梯（sp，1080p 视距）：

| 角色 | 字号 | 字重 | 用途 |
|---|---|---|---|
| Display | 34 / 28 | 700 | 页面标题、信息条节目名 |
| Title | 24 / 20 | 500 | 面板标题、分组名 |
| Body | 16 / 14 | 400 | 频道名、节目名、设置项 |
| Caption | 12 | 400 | 时间、状态、辅助说明 |

- TV 正文不要小于 **12sp**；10sp 仅限非关键角标。
- 单行优先；超长用 `Ellipsis`（**不做无限跑马灯**，见 UI 规范 §6）。

## 5. Android TV 资源规格

| 资源 | 规格 | 文件 |
|---|---|---|
| 应用图标（方） | 48/72/96/144/192 | [android-res/mipmap-*](brand/android-res/) |
| 应用图标（圆） | 同上 | 同上 |
| 自适应前景 | 108dp（432px@xxxhdpi），内容 ≤66dp | `ic_launcher_foreground.png` |
| TV Banner | 320×180（16:9） | [tv_banner_320x180.png](brand/png/tv_banner_320x180.png) |
| Leanback 大图标 | 512×512 | [app_icon_leanback_512.png](brand/png/app_icon_leanback_512.png) |
| 启动标 | 512 画布，内容 56% | [splash_mark_512.png](brand/png/splash_mark_512.png) |
| 启动安全区 | 288dp 内容圆 | [splash_safe_area_1080x1920.png](brand/png/splash_safe_area_1080x1920.png) |
| 品牌色 | values | [brand_colors.xml](brand/android-res/values/brand_colors.xml) |

完整目录/命名/接入见 [android-res-sizes.md](brand/android-res-sizes.md)。

## 6. 资产文件清单

```
docs/brand/
├─ svg/
│  ├─ logo_primary_dark.svg      logo_primary_light.svg
│  ├─ logo_monochrome.svg        mark_dark.svg  mark_light.svg
│  ├─ mark_monochrome.svg        splash_mark.svg  tv_banner.svg
├─ png/
│  ├─ logo_primary_dark_1200x360.png   logo_primary_light_1200x360.png
│  ├─ logo_monochrome_white_1200x360.png  logo_monochrome_black_1200x360.png
│  ├─ tv_banner_320x180.png            app_icon_leanback_512.png
│  ├─ ic_launcher_512.png              ic_launcher_foreground_512.png
│  ├─ splash_mark_512.png  splash_mark_288.png  splash_safe_area_1080x1920.png
│  ├─ palette_swatches.png
│  └─ mipmap/ic_launcher_{mdpi..xxxhdpi}_*.png
├─ android-res/ … 可直接复制的 res 树
├─ android-res-sizes.md
└─ tools/generate_brand.py
```

## 7. 接入与边界

- 不写 Kotlin、不改 Gradle、不操作 TV；开发把 `android-res/` 复制到 `android/app/src/tivimini/res/` 并替换同名资源即可。
- 现有 `banner_bitmap.png`（≈3.4MB）建议换为 4KB 的 `tv_banner.png`，既统一品牌又减小包体。
- 现有 Compose 主题色 `primary=#BDBDBD` 建议改为 `brand_blue`，`background/surface` 保持 `#121212/#1E1E1E`
  或迁移到 `brand_obsidian/surface`；属 UI 规范（[UI_VISUAL_SPEC_TIVIMATE.md](UI_VISUAL_SPEC_TIVIMATE.md)）。
- 视觉验收（真机）需：图标在 Leanback 首页可辨识、banner 16:9 不裁切、启动标安全区不裁、对比度达标。
