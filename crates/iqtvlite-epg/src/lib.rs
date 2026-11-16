//! # iqtvlite-epg
//!
//! EPG（电子节目单）层：频道匹配、同步调度、网格查询。
//!
//! **核心是匹配算法** —— 它是用户投诉「EPG 是空的」的第一大来源。
//! 详见 [`matcher`]。

pub mod grid;
pub mod matcher;
pub mod merge;

pub use grid::{build_grid, EpgGrid, GridConfig, GridProgramme, GridRow, GridStats};
pub use matcher::{MatchCandidate, MatchOutcome, Matcher};
pub use merge::{merge_sources, EpgSourceInput, EpgSourceStat, MergedChannel, MergedEpg};

/// EPG 保留窗口（天）。
///
/// 150 000 频道 × ~90 节目/天 × 7 天 ≈ 9400 万行 ≈ 11 GB —— 必须限制。
pub const DEFAULT_RETENTION_DAYS: i64 = 3;
