# IQTV Lite 交付说明（2026-11-22 · 全量修复最终版）

> 仓库：https://github.com/zqs1qiwan/iqtvlite ｜ 分支：`integration/tivimini-baseline`
> 最终包：CI run **#213**（id 45022475298，head `b071f376`）/ versionCode **10213** / `2.2.0-tivimini-b071f376-validation`
> 主包 sha256 `4163f1a0d583a4ce59cb18fae7a74ccb6ea2038170bb941ec21aa917254eb689`（13,223,889 B ≈12.61MB）
> 本地：`delivery-apks/iqtvlite-ci-213-validation.apk`（回滚：`iqtvlite-ci-211-validation.apk`）
> 最终真机验收：**通过**（`docs/FULLFIX_FINAL_VERIFY.md`，TR100-G9）；`main` 未动、未打 tag

## 一、本轮（REVIEW_HANDOFF + 用户 5 项）修复清单

| 项 | 内容 | 状态 |
|---|---|---|
| S1 | 小分组抽屉崩溃（0/1/2 频道 coerceIn lo>hi） | ✅ 真机 1/4/24 行通过 |
| 用户① | 选台 UI 恢复初始丰富布局（行高 64、副标题/进度/无信息回归，保留性能修复） | ✅ 真机 |
| 用户② | 打开/切组按上次浏览位置恢复，不回开头 | ✅ 真机 idx20 |
| 用户③ | 抽屉背景半透明 alpha=0.92（缓存层，不牺牲性能） | ✅ 真机 |
| 用户④ | 信息卡台标下仅「频道 N / 分组」，编码移到右上徽标，减少空白 | ✅ 真机无 MPEG-L2、徽标 FHD·2ch |
| 用户⑤ | 遥控改 TiviMate 式「功能→任意按键（短按/长按）」全映射 | ✅ 真机 F1=131 绑定切台 |
| U1 | 最近观看左右切换 + OK 跳台（高亮=真实焦点=OK 目标单一真源） | ✅ 真机 OK→OK→右→OK |
| U5 | 播放台 ▶ 标记、信息卡节目不被浏览分组污染、EPG 空态三分类 | ✅ 真机前两项；三态代码+单测覆盖 |

**修复 commit 链**（全在 `integration/tivimini-baseline`，均已推送）：
`ef8f826`(S1) → `1c7638b`(UI/定位/背景/信息卡) → `723af6a`(U1) → `737d5ff`+`f762bf4`(遥控映射) → `b52e4e1`+`c8991ad`(编译/单测修复) → `9105aa8`(自定义键 fallback) → `b071f37`(U5 三项)

## 二、最终真机数据（TR100-G9 / Android 9）

- **B1 自定义键**：给「下一个频道」绑定 F1(131) → 按 F1 单步切台（美妆→美人→精选，无双触发）；恢复默认后 F1 移除、DOWN 正常。
- **B2**：抽屉 `▶ 127.精选 / 无信息·播放中` 与浏览焦点正交；播放 CCTV-15 节目后切浏览组，信息卡节目仍为 CCTV-15。
- **5 项 UI**：rich 行、按组记忆不回开头、半透明、信息卡排版、U1 切台全部通过。
- **回归**：S1 单频道不崩 + Back 退出、数字 OSD(1,0→CCTV-12)、上一台(229→地理)、清空持久(`cleared=true count=0`)、台标 96 条缓存、自动更新 fail=0、Fatal/IllegalArgument=0、无灰屏。
- **收尾**：前台 PlayerActivity、实验键 0、buffer 稳 500、Fatal=0、/data 2.58G(23%)。

## 三、质量证据

- CI #213 success：365 JVM 单测全绿、verify_apk / 8 项静态门禁 / DEX 契约 / instrumentation pair / runtime contract 全过。
- 复审：`docs/FULLFIX_REVIEW.md`（B1/B2 阻断均已关闭）、`docs/FULLFIX_FINAL_VERIFY.md`（最终真机）。
- 规格：`docs/FULLFIX_SPEC.md`（502 行）、TiviMate 参考 `docs/TIVIMATE_REFERENCE_2026_11_22.md`。

## 四、已知限制（如实）

1. **EPG 空态「未配置/加载失败」两态**真机未独立构造（禁 force-stop、LAN 空源被忽略、未注入断网）；代码 + 单测已覆盖，正常态与「暂无节目信息」真机可见。
2. **HANDOFF 第二批 S2–S6/U2–U4**（播放重试契约/源保存竞态/回看身份/扫码 token/测速抢焦点/回看提示/换台终态）为强时序或多线路场景，真机难可靠构造；365 JVM 单测覆盖通过，记录为已知限制并另立任务。
3. **MP2 听感**：显示 `mpeg-L2·2ch`，设备无音频采集手段，听感未验证。
4. **166/167 频道键**被设备系统服务（InputMonitor/AdbSwitchService）拦截，adb 注入不可验证；标准键与 F1 可测。
5. **签名**：本轮每 run 仍变（vc213 证书 `9e005515…` ≠ vc211），真机升级需一次卸载；配置 4 个 GitHub secrets 后固定（见 `docs/CI_STABLE_SIGNING.md`），之前 CI #211 曾短暂稳定，后续 run 又变化，需用户检查 secrets 是否生效。

## 五、后续可选

- 配置 GitHub secrets 固定签名 → 永久 `install -r` 免卸载；
- 另立任务完成 HANDOFF 第二批（S2–S6/U2–U4）的真机场景验证；
- 抽屉 60fps（当前 50th 32–38ms）需自绘虚拟列表，单独立项。
