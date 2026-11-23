# FINAL UI 真机验收报告（CI#231 / vc10231）—— 阻断：启动即崩溃

- 时间：2026-11-23 11:35–11:46（Asia/Hong_Kong）
- 执行：acceptance（t43 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #231 / versionCode **10231** / `2.2.0-tivimini-075c8bc8-validation` / app sha256 `2019bdb5e7034f4e5e401a38df2f3abb52e8d35a2628764c8ab74e521a6498da`（装后已装 base.apk 校验一致）
- 安装：新签名 `f1f794e9…` → 备份 data 3,116,032B → 仅卸 validation 一次 → install Success → 回灌；**force-stop 0**
- 崩溃日志：`docs/evidence/user-review/final-crash-verifyerror.log`

---

## 0. 结论：**阻断 —— App 无法启动，全部 6 项断言无法验证**

 installs 成功后，App 启动即崩溃（`FATAL EXCEPTION: main`，`java.lang.VerifyError`），进程无法维持，前台始终停留在 TV launcher。**t43 的全部断言（真实fps/节目名白/进度蓝/EPG留白/焦点灰白蓝字/全量回归）与用户确认截图均无法执行**，按用户/队长要求如实判失败并记录，不编造。

---

## 1. 崩溃详情（硬证据）

```
11-23 11:44:20.974 21081 21081 E AndroidRuntime: FATAL EXCEPTION: main
11-23 11:44:20.974 21081 21081 E AndroidRuntime: Process: tv.iqtvlite.app.validation, PID: 21081
11-23 11:44:20.974 21081 21081 E AndroidRuntime: java.lang.VerifyError: Verifier rejected class
  com.cjlhll.iptv.PlayerActivityKt: void com.cjlhll.iptv.PlayerActivityKt.VideoPlayerScreen(...):
  [0x176A] register v306 has type Reference: java.lang.Object but expected Boolean
  (declaration of 'com.cjlhll.iptv.PlayerActivityKt' appears in
   /data/app/tv.iqtvlite.app.validation-p0B_iSTRBW4ASnLwlKWs_A==/base.apk)
```

- **确定性复现**：自 11:44:20 起连续 4+ 次启动（PID 21081 / 21274 / 21634 / 21823）均为同一 `VerifyError`，非偶发。
- 触发点：`VideoPlayerScreen` 这个 Compose 可组合函数（参数极多）在 **API28 的 ART 验证器**上类型校验失败：`register v306 has type Reference:Object but expected Boolean` —— 典型的 Kotlin/Compose 编译器产物在旧 ART 上的寄存器类型不匹配（很可能是 t38/t40/t41 对 VideoPlayerScreen 增加/调整 Boolean 参数或 lambda 后触发的代码生成边界问题）。
- 状态：installed versionCode=10231 正确；app 进程数=0；前台=com.dangbei.tvlauncher；即 App 一启动就 crash 退回桌面。
- CI #231 本身 success 且 8 门禁/396 单测全绿，但**门禁与 JVM 单测无法捕获 ART 加载期的 VerifyError**（静态 dex 校验与旧 ART 验证器差异）。

---

## 2. 各断言判定（全部因阻断无法执行）

| 断言 | 结论 | 说明 |
|---|---|---|
| ① 真实 fps 徽标 + fps 来源日志 | ❌ 未验证（阻断） | App 无法启动 |
| ② 节目名白色非蓝 + 台标下N.台名+分组 | ❌ 未验证（阻断） | 同上 |
| ③ 仅播放台进度蓝、其他灰 | ❌ 未验证（阻断） | 同上 |
| ④ EPG 同宽多显示字 + 聚焦滚动无jank | ❌ 未验证（阻断） | 同上 |
| ⑤ 焦点底灰白/选中台蓝字/蓝不在焦点底（含各对话框） | ❌ 未验证（阻断） | 同上 |
| ⑥ 全量回归 | ❌ 未验证（阻断） | 同上 |
| 截图 final-infocard.png / final-drawer.png | ❌ 未产出（阻断） | App 无法进入 PlayerActivity，无法抓确认菜单/选台列表 |

---

## 3. 安装与收尾

- 装前体检全绿（boot=1、/data 2.59G、DeadObject/ext4/I-O/installd/storaged 0）。
- 装后 sha256 与 CI 一致；force-stop 0（崩溃由系统触发，非我方 force-stop）。
- 收尾：前台 PlayerActivity **未达成**（实际在 launcher，因 App 无法启动）；实验键 0；buffer 无覆盖；debug log.tag 已复位空；/data 2.57G(23%)；infocard2 回滚包保留。
- 未做额外安装/卸载/代码修改（遵守单次安装与只读纪律）。

---

## 4. 给队长/开发的处理建议

1. **这是阻断性回归**：vc10231 在 TR100-G9（API28/Android9）上启动即 VerifyError，无法真机验收，建议**不要交付此包**。
2. 根因方向：`PlayerActivityKt.VideoPlayerScreen` 的 Compose 产物在旧 ART 验证器失败（`register v306` 类型 Object/Boolean 不匹配）。优先检查 t38/t40/t41 对该 composable 的参数/lambda 改动：
   - 是否有 Boolean 参数被传成可空/Any/Object；
   - 参数数量过多触发旧 ART 验证器边界（可尝试拆分 VideoPlayerScreen 为更小的 composable，或减少参数）；
   - Compose compiler 版本与 Kotlin 版本匹配。
3. 修复后建议：本地除 396 单测 + 8 门禁外，**务必在真机 API28 上做一次"冷启动能进 PlayerActivity"的冒烟**（JVM/静态门禁无法捕获此类 ART VerifyError）。
4. 本轮 t43 判失败（阻断），待修复重出包后请新建验收任务，我会重新逐条硬判并抓图。
