//! 稳定 ID 方案。
//!
//! **为什么需要**：供应商会在每次同步时重排频道、改 URL、改 `tvg-id`。
//! 如果收藏 / 历史 / 观看进度用「行号」或「URL」做主键，重新同步后就会全乱。
//!
//! **方案**（见 `docs/04-data-model.md` §2）：
//!
//! ```text
//! global_id = xxh3_64( source_id_le_bytes || 0x1F || provider_key ) & 0x7FFF_FFFF_FFFF_FFFF | 1
//! ```
//!
//! - `source_id`：播放列表的本地持久 ID，一旦分配**永不变**。
//! - `provider_key`：供应商侧的频道标识（Xtream `stream_id` / M3U `tvg-id` / 回退哈希）。
//! - `0x1F`（单元分隔符）：保证 `source=1,key="23"` 不会与 `source=12,key="3"` 碰撞。
//! - 掩到 63 位并置 bit 0 → 始终是正数，可直接存进 SQLite `INTEGER`。
//!
//! 真正的护栏是数据库上的 `UNIQUE (source_id, provider_key)`；哈希只负责「跨源唯一」。

use serde::{Deserialize, Serialize};
use xxhash_rust::xxh3::xxh3_64;

/// 播放列表的持久 ID。由存储层分配，**一旦分配永不变**。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SourceId(pub i64);

impl SourceId {
    #[inline]
    fn as_bytes(&self) -> [u8; 8] {
        self.0.to_le_bytes()
    }
}

/// 全局稳定 ID（跨播放列表唯一）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct GlobalId(pub u64);

impl GlobalId {
    /// 转成可放进 SQLite `INTEGER` 的正数。
    #[inline]
    pub fn as_sqlite_int(self) -> i64 {
        (self.0 & 0x7FFF_FFFF_FFFF_FFFF) as i64 | 1
    }
}

/// 单元分隔符：在 UTF-8 与供应商 ID 中都不会出现。
const UNIT_SEPARATOR: u8 = 0x1F;

/// 计算频道/内容的全局稳定 ID。
pub fn global_id(source: SourceId, provider_key: &str) -> GlobalId {
    let mut buf = Vec::with_capacity(9 + provider_key.len());
    buf.extend_from_slice(&source.as_bytes());
    buf.push(UNIT_SEPARATOR);
    buf.extend_from_slice(provider_key.as_bytes());
    GlobalId(xxh3_64(&buf))
}

/// 清洗供应商 ID 中的字符。
///
/// 规则与 ynotv 保持一致：`[^a-zA-Z0-9._-]` → `_`。
/// 目的是让 ID 可读、可用于文件名、且不会与数据库语法冲突。
pub fn sanitize_provider_key(raw: &str) -> String {
    raw.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// 从 M3U 条目推导 `provider_key`。
///
/// 优先级（与 `crispy-m3u` / ynotv 一致）：
/// 1. `tvg-id`（清洗后）
/// 2. URL 的 DJB2 哈希 → `url_{hash:08x}`
/// 3. 频道名的 DJB2 哈希 → `name_{hash:08x}`
/// 4. `"unknown"`
pub fn m3u_provider_key(tvg_id: Option<&str>, url: Option<&str>, name: Option<&str>) -> String {
    if let Some(tvg_id) = tvg_id {
        let sanitized = sanitize_provider_key(tvg_id);
        if !sanitized.is_empty() {
            return sanitized;
        }
    }
    if let Some(url) = url {
        return format!("url_{:08x}", djb2(url));
    }
    if let Some(name) = name {
        return format!("name_{:08x}", djb2(name));
    }
    "unknown".to_string()
}

/// DJB2 哈希（32 位，溢出语义与 JavaScript 一致）。
///
/// 用途是**可读的短哈希**（`url_1a2b3c4d`），不是安全哈希。
pub fn djb2(input: &str) -> u32 {
    let mut hash: u32 = 5381;
    for b in input.as_bytes() {
        hash = hash.wrapping_mul(33).wrapping_add(u32::from(*b));
    }
    hash
}

/// 去重器：处理同一播放列表内的**完全重复**条目。
///
/// 参考实现：`crispy-m3u/src/id.rs` 的 `uniquify_stable_id`。
/// 重复项按 `provider_key + url` 的稳定排序依次加 `_1`、`_2` 后缀，
/// 保证**重新同步后顺序不变**。
#[derive(Debug, Default)]
pub struct IdUniquifier {
    seen: std::collections::HashSet<String>,
}

impl IdUniquifier {
    pub fn new() -> Self {
        Self::default()
    }

    /// 为一个条目分配播放列表内唯一的 ID。
    pub fn uniquify(&mut self, base: &str, url: Option<&str>) -> String {
        if self.seen.insert(base.to_string()) {
            return base.to_string();
        }
        if let Some(url) = url {
            let seeded = format!("{base}_{:08x}", djb2(url));
            if self.seen.insert(seeded.clone()) {
                return seeded;
            }
            return self.resolve_collision(&seeded);
        }
        self.resolve_collision(base)
    }

    fn resolve_collision(&mut self, base: &str) -> String {
        let mut counter: u32 = 1;
        loop {
            let candidate = format!("{base}_{counter}");
            if self.seen.insert(candidate.clone()) {
                return candidate;
            }
            counter = counter.saturating_add(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn global_id_is_stable_and_positive() {
        let src = SourceId(1);
        let a = global_id(src, "ch1");
        let b = global_id(src, "ch1");
        assert_eq!(a, b, "同一 (source, key) 必须产生同一 ID");
        assert!(a.as_sqlite_int() > 0, "SQLite INTEGER 必须为正");
    }

    #[test]
    fn global_id_separates_source_boundaries() {
        // source=1,key="23" 与 source=12,key="3" 不能碰撞
        let a = global_id(SourceId(1), "23");
        let b = global_id(SourceId(12), "3");
        assert_ne!(a, b, "分隔符必须防止前缀歧义");
    }

    #[test]
    fn global_id_differs_across_sources() {
        let a = global_id(SourceId(1), "BBCOne.uk");
        let b = global_id(SourceId(2), "BBCOne.uk");
        assert_ne!(a, b, "不同源的同名频道必须有不同 ID");
    }

    #[test]
    fn provider_key_prefers_tvg_id() {
        let k = m3u_provider_key(Some("CNN.us"), Some("http://x/y"), Some("CNN"));
        assert_eq!(k, "CNN.us");
    }

    #[test]
    fn provider_key_falls_back_to_url_then_name() {
        let k = m3u_provider_key(None, Some("http://example.com/s"), Some("X"));
        assert!(k.starts_with("url_"), "应回退到 URL 哈希，实际: {k}");

        let k = m3u_provider_key(None, None, Some("My Channel"));
        assert!(k.starts_with("name_"), "应回退到名称哈希，实际: {k}");

        assert_eq!(m3u_provider_key(None, None, None), "unknown");
    }

    #[test]
    fn provider_key_sanitizes_illegal_chars() {
        let k = m3u_provider_key(Some("BBC One/UK:HD"), None, None);
        assert_eq!(k, "BBC_One_UK_HD");
    }

    #[test]
    fn uniquifier_suffixes_duplicates_deterministically() {
        let mut u = IdUniquifier::new();
        assert_eq!(u.uniquify("ch", None), "ch");
        assert_eq!(u.uniquify("ch", None), "ch_1");
        assert_eq!(u.uniquify("ch", None), "ch_2");
    }

    #[test]
    fn uniquifier_uses_url_seed_before_counter() {
        let mut u = IdUniquifier::new();
        assert_eq!(u.uniquify("ch", Some("http://a")), "ch");
        // 第二个同名但 URL 不同 → 用 URL 哈希区分，而不是 _1
        let second = u.uniquify("ch", Some("http://b"));
        assert!(second.starts_with("ch_") && second != "ch_1");
    }
}
