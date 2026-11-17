# 技术债审计（TECH-DEBT.md）

> 逐条登记，按优先级治理。完成标准：改代码 + 真机验证 + 更新本表状态。

## TD-1 【P0】录像/DVR 残留未清除（违反用户硬约束）
用户明确：不做录像（曾写满设备卡死）。当前残留：
- `crates/iqtvlite-dvr/` 整个 crate（RecordingTask/RecordingMachine/状态机 ~400 行）
- `Cargo.toml` workspace members + workspace.dependencies 中的 iqtvlite-dvr
- `crates/iqtvlite-capi/src/lib.rs`：`use iqtvlite_dvr::{...}`、`iqt_build_recording_plan()`、`build_recording_plan()`、2 个测试
- `crates/iqtvlite-capi/src/jni_bridge.rs`：import + JNI 方法 `Java_...buildRecordingPlan`
- `crates/iqtvlite-store/src/migrate.rs`：`recordings` 表 + 索引 + 迁移列表中的 `"recordings"`（**注意**：不能破坏 schema version/迁移兼容性，需保留表但注释说明或安全移除）
- `android/.../RustBridge.kt`：`external fun buildRecordingPlan()`
- 文档：`docs/00-overview.md` §2.1 "DVR 录制"、README 状态表 "DVR"
治理：全链路删除 DVR；保留 DB 历史表但停止迁移引用（避免老用户 DB 崩溃）；更新文档。
**验收**：`grep -ri 'dvr\|record' crates/ android/ docs/` 无功能性残留；`cargo test` + `compileDebugKotlin` 通过；真机播放正常。

## TD-2 【P1】MainActivity 1138 行巨型类
职责混杂：播放/EPG/频道列表/焦点/设置/推源/计时/数字跳转/菜单。
治理：拆分为多个 Controller/Helper（PlaybackController、EpgController、ChannelListController、RemoteNavController），MainActivity 只做生命周期与装配。
**验收**：MainActivity < 300 行；每模块单一职责；真机功能不回退。

## TD-3 【P1】文档严重过时
README/docs 说 "Slint UI 待 M0 spike"、"DVR 是 v1.0 目标"，实际已 Kotlin 原生 UI 跑通且 DVR 被禁。
治理：更新 README + 00-overview + 07-roadmap 反映真实架构与状态；PRD.md 为唯一真相源。

## TD-4 【P2】交互细节待金标准对齐
依赖交互调研（子任务进行中）填充 `docs/PRD/interaction-golden-standard.md`，逐条对照实现。

## TD-5 【P2】性能基准未测
PRD 中的冷启动/内存/换台指标尚未真机量化。
治理：埋点已有（COLD_PHASE/ZAP_MS），补测 dumpsys meminfo + gfxinfo，记录到测试报告。

## 状态
- 本轮优先：TD-1（P0，硬约束）→ TD-4（金标准）→ TD-2/TD-3 → TD-5
