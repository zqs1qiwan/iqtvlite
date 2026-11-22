# TiviMate 参考设计（2026-11-22 调研）

来源（外部资料，仅作设计参考，未复制任何反编译代码）：
- https://www.dualiptv.com/guide/remap-tivimate-remote-buttons
- https://github.com/Eliminater74/TiVIMate_Analysis/blob/main/tivimate_remote_input_menu_architecture.md
- https://store.grapevine.is/blogs/news/tivimate-remote-shortcuts

## 1. 遥控按键映射（对应本次问题 5）

**TiviMate 模型 = "功能 → 按键" 全映射，不是固定开关**：
- 设置路径：Settings → Remote Control → 选一个**功能**（如 "Next channel" / "Show info" / "Recent channels"）→ 按你想要的**任意键**完成绑定；
- 支持 Short press / Long press 两种动作分别绑定；
- 支持颜色键（KEYCODE_BUTTON_1 等）与播放控制键；
- 上下文敏感：同一键在不同 UI 状态（PLAYER_ONLY / EPG_VISIBLE / MENU_OPEN）解析为不同动作。

**架构要点（概念层，不复制代码）**：
- 全局 `dispatchKeyEvent` 拦截（Window-Callback），而非给每个子 view 挂 OnKeyListener（后者导致焦点混乱/丢失）；
- 中央 key→action router，读取当前 UI 状态后解析；
- `KeyEvent.getRepeatCount()` / `isLongPress()` 区分长短按；
- 用户映射存 SharedPreferences（序列化 action 列表）；
- 输入路由应 <1ms。

**我们要对齐的**：把现有 `remote_updown/remote_ok/remote_back/remote_digits/remote_channel_keys` 五个"固定模式开关"改成"功能列表 → 每个功能可绑定任意键值（短按/长按）"，默认值保持当前行为、老偏好可迁移。

## 2. 菜单/选台 UI

- Quick Menu 可定制：用户选择哪些动作可见、左右重排、一键恢复默认；
- 动态根据偏好列表 inflate 菜单项（不用硬编码静态布局）。

## 3. 与我们当前问题的对应

| 用户反馈 | TiviMate 做法 |
|---|---|
| 设置里是"固定切换"而非"功能:对应键值" | 每个功能独立绑定任意键，支持长短按 |
| 选台 UI 被精简过度 | 保留原始丰富布局（logo/台名/EPG 清晰分层） |
| 选台每次回到开头 | 焦点/滚动位置由状态恢复，不是每次重置 |
| 选台背景全色 | 与其他菜单一致的半透明层 |
| 信息卡右上角细节不全、台标下信息过多 | 台标下只放号码/分组；码率/分辨率/声道等到右上徽标区 |
| 最近观看不能切换/布局 bug | 焦点高亮 + 真实焦点 + OK 目标三者一致（对应 U1） |
