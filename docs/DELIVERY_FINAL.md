# IQTV Lite 交付说明（2026-11-23 · V4 最终版）

> 仓库：https://github.com/zqs1qiwan/iqtvlite ｜ 分支：`integration/tivimini-baseline`
> 最终包：CI run **#221** / versionCode **10221** / `2.2.0-tivimini-37a2f61d-validation`
> 主包 sha256 `42bca46af1e772ff1273327a5e62cfac71b38ea6cab7a5130082bb6d4e2df12c`（13,344,261 B ≈12.73MB）
> 本地：`delivery-apks/iqtvlite-ci-v4b-validation.apk`（回滚：`iqtvlite-ci-v4-validation.apk` / `iqtvlite-ci-213-validation.apk`）
> 真机验收：**核心 bug 已彻底修复**（`docs/V4B_FOCUS_REOPEN_VERIFY.md`）；`main` 未动、未打 tag

## 一、用户反馈问题 → 最终结果（真机证据）

| 问题 | 结果 |
|---|---|
| 点台换台选台列表跳开头（原 bug） | ✅ **彻底修复**：冷启首开/会话内 zap/P8b 的 `first` 均贴焦点（如焦点133→first128–137；此前 first=1/58） |
| 选台 UI 太精简 | ✅ 恢复丰富行（副标题/进度/无信息），保留全部性能修复 |
| 打开列表闪到开头又回当前台、焦点不出现 | ✅ 打开即高亮、无闪烁（logicalFocused + pending 校正） |
| 分组要点击才进入 | ✅ **聚焦即展示**：分组列上下移动右侧立即跟随、不点 OK |
| 从分组进入不从头 / 右键进不去 | ✅ → 进入频道列并收起分组、聚焦该组第一台；全部频道按 → 正常进列表 |
| 选台要翻页 | ✅ 不再翻页 |
| 播放台用「▶…播放中」文字 | ✅ 改用品牌蓝变色，无 ▶ 文字 |
| 信息卡排版/细节 | ✅ v4：右上 HD·帧数·声道徽标、台标下只「N.台名+本台分组」无编码、右侧节目+进度、底部 4 logo、223dp |
| 最近观看不能切换 | ✅ 4 logo 左右聚焦、OK 跳台（真机切到密云电视台） |
| 数字跳台在中间 | ✅ 移至右上角 [1769,93]、3s 超时、确认态蓝 |
| 遥控设置要功能→按键全映射 | ✅ TiviMate 式绑定，F1 等自定义键实际切台 |
| 全局焦点色 | ✅ 统一 logo 蓝 `#2F6BFF` 浅底白字、无硬边框 |
| logo 资源 | ✅ 按 `iqtvlite-logo-assets` 替换 launcher/banner/字标（src/tivimini/res） |

## 二、仅剩的非阻塞残留（如实）

- 焦点**已经可见**时关→重开，t15 旧 pending 分支仍触发一次多余滚动（first 138→132，约 6 行偏移，焦点仍在可视区）。不影响换台定位、不是跳开头。已记录为低优先级可选优化（`t28` 报告 §残留）：让 pending 分支也复用 `drawerFocusNeedsRevealCorrection` 判定。**未修，不影响交付**。

## 三、质量证据

- CI #221 success：8 门禁全绿、runtime contract passed、BUILD SUCCESSFUL 0 errors；本地工具链 JDK17+SDK36+build-tools35 已就绪，推送前本地 `gradlew :app:testDebugUnitTest` **380 tests / 0 fail / 0 error**。
- 真机报告：`docs/V4_DEVICE_VERIFY.md`（8 项通过）、`docs/V4B_FOCUS_REOPEN_VERIFY.md`（核心 bug 修复坐实）。
- 规格：`docs/V4_SPEC.md`、`docs/INFO_CARD_V4_CONFIRM.md`、`docs/TIVIMATE_REFERENCE_2026_11_22.md`。

## 四、设备状态与已知限制

- 设备 TR100-G9：PlayerActivity 前台、实验键 0、buffer 稳 500 默认、Fatal=0、/data 2.58G；安装全程按协议（备份 data + 仅卸 validation 一次，force-stop 0）。
- 签名每个 CI run 仍变化（vc10221 证书 `f828e395…`），升级需一次卸载；配置 4 个 GitHub secrets 可固定（见 `docs/CI_STABLE_SIGNING.md`），需用户操作。
- MP2 听感无采集手段未验证；166/167 被设备系统服务拦截无法 adb 注入；HANDOFF 第二批强时序场景（S2–S5/U2–U4）真机难构造、由单测覆盖，记录为已知限制。
