# UI 重构：满屏播放 + Apple TV 设计语言

> 2026-11-17

## 1. 背景：用户纠正方向

用户明确反馈：
1. 录制不是核心，且曾把设备写满卡死（已彻底删除录制功能，见 commit `8ac6fc9`）
2. TiviMate 是**满屏播放、UI 按需浮出**，导入一次后点开秒播
3. 参考 tivimini / TiviMate 仍"丑"，**最终设计对标 Apple TV**（tvOS HIG）

## 2. 真机参考（TiviMate v2.1.5）

截图（docs 外，已分析）：
- 默认状态：纯满屏视频，**无任何 UI**
- OK → 左侧半透明频道列表浮层（编号+Logo+频道名+当前节目+进度条），右侧视频透出
- 中间：节目时间浮层；右上：当前节目信息卡（节目名+时间+剩余分钟+进度）
- 所有浮层半透明黑底，圆角

## 3. 我们实现的改造

### 3.1 架构（activity_main.xml）
- `PlayerView` 改为 `match_parent` 铺满底层，`useController=false`（媒体控制条会拦截 OK/方向键）
- 所有 UI 收进 `ui_layer`（默认 `gone`）：顶栏 + 控制行 + 左侧频道面板 + EPG 面板
- `info_card`：右上换台信息卡，浮出 5s 后自动隐藏
- 浮层背景 `@drawable/panel_*_bg`：半透明黑（#D4/#E0 alpha），视频透出

### 3.2 交互（onKeyDown / onBackPressed）
| 按键 | 行为 |
|---|---|
| OK / 中心键 | 切换 UI 浮层显隐 |
| 返回 | 关闭浮层回满屏（优先） |
| 左 | 打开频道列表浮层 |
| 右 | 打开 EPG 网格浮层 |
| 上/下 | 满屏时换台；浮层打开时交给列表焦点 |

### 3.3 秒播（用户核心诉求）
- `TiviMatePrefs.saveLastChannel / lastChannel` 持久化上次频道 + 源索引
- 启动时：`loadFromCache(0)` 同步加载 → `autoPlayLastChannel()` 立即开播（3.2s），不等网络
- 网络刷新后台进行，不打断当前播放
- 真机日志：`AUTOPLAY CCTV-1 综合 (秒播, COLD_START_MS=3161)`

### 3.4 Apple TV 视觉语言（tvOS HIG 提炼）
- **配色**：近黑底 + Apple 系统蓝 `#0A84FF` + 三级灰文字（#F5F5F7/#A1A1A6/#6E6E73），颜色让位于视频内容
- **焦点**：不用硬蓝边框，改为半透明白底 + 大圆角（18dp）柔和卡片；焦点行有 `stateListAnimator` 放大 5% + elevation
- **排版**：系统字体（sans-serif），大字号（频道名 23sp / 节目 17sp / 编号 21sp），满足 10-foot 观看
- **控件**：pill 形分组 chip（24dp 圆角）、圆角 Logo（14dp）、进度条系统蓝
- **材质**：半透明浮层（thin material 近似），营造景深

> 修复：过程中出现 AppCompat 默认黄色高亮（colorAccent 未生效），
> 通过在 onBindViewHolder 显式设置背景 drawable 彻底解决。

## 4. 真机验证（TR100-G9）

| 场景 | 结果 |
|---|---|
| 默认启动 | 满屏视频，UI 全隐藏 ✅ |
| 秒播 | 3.2s 自动播放上次频道 CCTV-1 ✅ |
| OK | 频道列表浮层从左侧浮出，视频右透出 ✅ |
| 返回 | 浮层关闭回满屏 ✅ |
| 右键 | EPG 网格浮层（61 频道匹配 / 595 节目块）✅ |
| D-pad 焦点 | 圆角高亮 + 放大，清晰可见 ✅ |

截图：`ui-final.png`（满屏）、`fs-panel2.png`（频道浮层）、`epg-test.png`（EPG）、`apple-final.png`（Apple 风格浮层）

## 5. 设计取舍
- Apple TV 的视差/分层图片（parallax layered images）需要专门的 foreground/middle/background 素材，
  当前 Logo 是单张网络图，无法做真视差；用焦点放大 + 高亮近似
- 满屏时按设计不显示焦点（tvOS 规范），信息卡短暂停留后才消失
