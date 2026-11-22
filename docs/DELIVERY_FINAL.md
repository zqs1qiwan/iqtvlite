# IQTV Lite 交付说明（2026-11-23 · 根源重构最终版）

> 仓库：https://github.com/zqs1qiwan/iqtvlite ｜ 分支：`integration/tivimini-baseline`
> 最终包：CI run **#223** / versionCode **10223** / `2.2.0-tivimini-611dfde0-validation`
> 主包 sha256 `66cbc5a07c63ced4004d3f6d6b326c938d0430691de86f5a8ac227ec97b5630a`（13,327,873 B ≈12.71MB）
> 本地：`delivery-apks/iqtvlite-ci-v4c-validation.apk`（回滚：v4b / v4 / ci-213）
> 真机验收：**根源重构通过，两条判据同时成立**（`docs/SCROLL_REFACTOR_VERIFY.md`）；`main` 未动、未打 tag

## 一、本轮目标：根源修复（非补丁）

用户要求「不要补丁式修复，要从根源修复」。复核发现选台列表滚动有**三套互不协调机制并存**（t15 `pendingScrollIndex` / t26 `revealCorrectedKey` / `onRowFocusedScroll` 各自 `scrollToItem`），这是"焦点已可见仍多滚一次"的根源。

**根治**：合并为单一真源 `desiredScrollIndex` + 唯一校正 effect，`scrollToItem` 全局**仅剩 1 处**；`ScrollDecision` 三分支（SCROLL/CLEAR/WAIT），CLEAR 保证焦点已可见不滚。真机两条判据同时成立：
- ① 焦点已可见时关→重开：`scroll_correct` 计数 **0**，可见区稳定不偏移（旧版多余滚动彻底消失）；
- ② 仍不跳开头：冷启/深行 zap/P8b 的 first 全贴焦点（122/124/132 对应焦点 131/133/141），非 first=1/58。

## 二、全部用户问题 → 真机结果

| 问题 | 结果 |
|---|---|
| 点台换台列表跳开头 | ✅ 修复（first 贴焦点，非 1/58） |
| 焦点已可见仍多滚 | ✅ 修复（CLEAR 分支，scroll_correct=0） |
| 选台 UI 太精简 / 打开闪烁 / 焦点不高亮 | ✅ 恢复丰富行、打开即高亮、无闪烁 |
| 分组要点才进 / 右键进不去 / 从头开始 | ✅ 聚焦即展示、→进入收起分组从组头、全部→正常进列表 |
| 播放台 ▶ 文字 | ✅ 品牌蓝变色，无 ▶ |
| 信息卡排版 | ✅ v4：右上 HD·帧数·声道、台标下只序号+台名+分组、右侧节目+进度、底部 4 logo、~223dp |
| 最近观看不能切换 | ✅ 4 logo 左右聚焦 + OK 跳台 |
| 数字跳台位置 | ✅ 右上角 + 3s 超时 + 确认态蓝 |
| 遥控功能→按键映射 | ✅ TiviMate 式，自定义键实际切台 |
| 全局焦点色 / logo 资源 | ✅ 统一 logo 蓝 `#2F6BFF`、资源按 `iqtvlite-logo-assets` 替换 |
| 回归 | ✅ S1 小分组、MP2、上一台、清空、自动更新、台标、遥控自定义键、不崩 |

## 三、质量证据

- 本地工具链完整（JDK17 + Android 36 + build-tools 35）：每次推送前 `gradlew :app:testDebugUnitTest` **383 tests / 0 fail / 0 error** + 8/8 静态门禁 + diff check 全绿。
- CI #223 success，0 errors；真机报告 `docs/SCROLL_REFACTOR_VERIFY.md`，截图 `docs/evidence/v4-verify/refactor-*.png`。
- 规格/参考：`docs/V4_SPEC.md`、`docs/INFO_CARD_V4_CONFIRM.md`、`docs/TIVIMATE_REFERENCE_2026_11_22.md`。

## 四、已知限制 / 建议

1. 签名每 run 变化（vc10223 `013f2ef1…`），升级需一次卸载；配置 4 个 GitHub secrets 可永久免卸载（`docs/CI_STABLE_SIGNING.md`，需用户操作）。
2. MP2 听感无采集手段未验证；166/167 被设备系统服务拦截无法 adb 注入；HANDOFF 第二批强时序场景由单测覆盖。
3. 当前包为最终可交付版，可作为正式版本使用。
