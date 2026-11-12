//! iqtvlite 集成测试 crate。
//!
//! 测试放在 `tests/` 目录下（真正的集成测试形态），
//! 验证**跨 crate**的协作：协议解析 → 领域模型 → 存储 → EPG 匹配。

/// 占位 lib，让 crate 有可构建的库目标（集成测试本身在 `tests/` 下）。
pub fn placeholder() {}
