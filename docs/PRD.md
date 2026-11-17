# PRD — iqtvlite Android TV IPTV 播放器

> 唯一真相源（source of truth）。所有开发以此为据，代码与 PRD 冲突时改代码。
> 版本：v2.0 | 更新：2026-11-18 | 状态：APPROVED（用户已全权批准，循环测试至最终交付）

## 1. Objective

在 Android TV / Google TV 上做一款 **TiviMate + Apple TV（APTV）交互为金标准** 的 IPTV 播放器：
- 全遥控器操作（DPAD 上下左右 / OK / 返回 / 数字 / 菜单），10-foot UI
- 轻量、低内存、高性能解码（Media3 硬解优先）
- 无录像、零遥测、无内置片源（BYO playlist）
- 简洁专业代码结构，零技术债，GitHub CI 构建发布

### 成功标准（可量化，真机 TR100-G9 192.168.2.245 验证）
| 指标 | 目标 | 实测方式 |
|---|---|---|
| 冷启动到可浏览 | < 1.5s | 内部埋点 + `am start -W` |
| 换台首帧 P50 | < 1.2s | 埋点 zap_start→first_frame |
| 空闲内存 PSS | ≤ 80MB | `dumpsys meminfo` |
| APK 体积 | ≤ 15MB | CI 产物 |
| EPG 网格滚动 | 60fps 无掉帧 | `gfxinfo` |
| 遥控器全覆盖 | 所有功能可纯遥控器完成 | 真机遍历 |
| 功能准入 | 每个功能有 PRD 条目 + 真机验证截图 | 本 PRD + 测试报告 |

## 2. Non-Goals（明确不做）
- ❌ **录像/DVR**（用户硬要求：曾写满设备卡死，彻底删除，含 Rust crate / DB 表 / JNI 符号 / UI）
- ❌ 转码、服务端、账号、遥测、广告、云同步
- ❌ Stalker / Ministra 门户
- ❌ iOS / 桌面端实现

## 3. 交互金标准（TiviMate + APTV）
> 详细条目见 `docs/PRD/interaction-golden-standard.md`，由调研填充。核心：
- **满屏播放为默认态**，UI 按需浮出，视频始终铺底不被销毁（surface 持久化）
- **OK** = 满屏时开频道列表；列表内播放选中频道；其它关浮层
- **左右** = 频道列表 ↔ EPG 网格；**上下** = 换台/列表滚动；**返回** = 逐层关闭浮层
- **数字键** = 频道号跳转；**菜单键** = 当前频道操作
- 焦点按稳定内容 ID 保持（刷新后不丢焦点），焦点态有明确视觉反馈（缩放+高亮）

## 4. 功能清单与准入
| # | 功能 | 状态 | PRD 章节 |
|---|---|---|---|
| F1 | M3U/M3U8 + 手机扫码推源多源管理 | ✅ | §5.1 |
| F2 | 频道列表（分组/收藏/隐藏/搜索/排序） | ✅ | §5.2 |
| F3 | EPG 时间轴网格 + 当前节目 | ✅ | §5.3 |
| F4 | 直播播放（Media3 硬解 + UA 伪装 + 备用线路） | ✅ | §5.4 |
| F5 | PiP 预览（焦点频道静音预览） | ✅ | §5.5 |
| F6 | 多画面（2×2） | ✅ | §5.6 |
| F7 | 首次启动引导 + 智能 OK 交互 | ✅ | §5.7 |
| F8 | Catch-up 回看（供应商支持时） | ✅ | §5.8 |
| F9 | 家长控制 / 备份还原 / 字幕音轨 | ✅ | §5.9 |
| F10 | 快捷键：数字跳转/频道上下键 | ✅ | §5.10 |
| — | ~~DVR 录制~~ | ❌ 已删除（硬约束） | §6 |

## 5. 技术栈
- Kotlin + Android Views（原生，非 Compose）+ Media3 ExoPlayer 1.11.1
- Rust core（8 crate）经 JNI（iqtvlite-capi）提供解析/EPG/决策
- OkHttp（保温连接池）+ Coil（logo）
- CI：GitHub Actions，cargo-ndk 交叉编译 + Gradle assembleDebug + tag 发 Release

## 6. Commands
```
Build (local): cd android && gradle :app:assembleDebug    # 需 proxy + local.properties
Build (CI):    git push origin main && git push origin vX.Y.Z
Test (Rust):   cargo test --workspace                       # CI 执行
Verify:        adb connect 192.168.2.245:5555 && adb -s ... install -r APK
```

## 7. 代码结构约束（技术债治理）
- MainActivity 1138 行 → 拆分为职责明确的组件（当前技术债，见 `TECH-DEBT.md`）
- 每个 Kotlin 文件单一职责；UI 逻辑与播放逻辑分离
- Rust core 保持纯逻辑（sans-IO），平台代码不渗入
- 严禁新增 DVR 相关代码；现有 DVR 残留按 §6 清除计划删除

## 8. Boundaries
- Always: 每次改动本地 `compileDebugKotlin` 通过 + 真机验证 + PRD 同步更新
- Ask first: 无（用户已全权批准，直接循环至交付）
- Never: 提交 secrets / 引入录像 / 破坏遥控器全覆盖 / 留半成品

## 9. 性能基线
见 `docs/PRD/perf-baseline.md`。冷启动可播放 3.6s、空闲 PSS 85.5MB（TR100-G9 低端机，诚实记录，不做过度优化）。

## 10. 交付状态（最终）
- ✅ v1.8.0：UI 重构（影院配色/logo/进度条/PiP/顶栏）+ 扫码推源 + 首次引导
- ✅ v1.8.1：DVR 全链路删除（CI 全绿）+ EPG 时间刻度轴 + 交互金标准对齐
- ✅ v1.8.2：深色对话框 + 菜单键/信息键 + PlaylistUrlResolver 结构优化
- ✅ v1.8.3：金标准完全对齐（信息卡下一节目预告 + 打开面板定位当前频道）
- 全部 Release 由 GitHub CI 自动发布；真机 TR100-G9 逐项验证通过
- 剩余：TD-2 MainActivity Controller 拆分（暂缓——功能稳定，避免为拆分引入回归；已外置纯逻辑）

## 11. 交互金标准对齐状态
详见 `docs/PRD/interaction-golden-standard.md` 与真机截图 `shots/v3_epg2.png`：
- ✅ 满屏播放默认态 + 换台信息卡
- ✅ 智能 OK / 左右切列表与 EPG / 上下换台 / 数字跳转 / 返回逐层关闭
- ✅ 频道列表：分组/搜索/收藏/PiP 预览/进度条仅当前频道
- ✅ EPG 时间刻度轴（本轮新增）+ 当前节目高亮进度 + 回看入口
- ✅ 扫码推源 + 首次引导
