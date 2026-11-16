//! 多 EPG 源叠加 / 优先级合并。
//!
//! TiviMate 支持为一个播放列表挂多个 EPG 源，按优先级覆盖：
//!   数字越小优先级越高（manual=10 < m3u_header=20 < xtream=30）。
//!
//! 合并规则（见 docs/09-protocols.md §4.3）：
//! - 同一 (tvg_id, start_ts) 的节目，高优先级源覆盖低优先级源
//! - 低优先级源独有的节目保留（填补充缺）
//! - 相同优先级的源冲突时，保留先出现的（稳定顺序）
//! - 不按名字猜测匹配（避免跨国家串台）

use std::collections::HashMap;

use iqtvlite_core::parse::xmltv::{parse_reader, XmltvProgramme};
use serde::{Deserialize, Serialize};

/// 一个 EPG 源的输入。
#[derive(Debug, Clone)]
pub struct EpgSourceInput<'a> {
    /// 源标识（用于日志/调试）
    pub id: String,
    /// 优先级（数字越小越高）
    pub priority: i32,
    /// XMLTV 文本
    pub xml: &'a str,
}

/// 合并后的频道。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MergedChannel {
    pub key: String,
    pub name: String,
    pub source_id: String,
}

/// 合并结果。
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct MergedEpg {
    /// tvg-id → 显示名（高优先级优先）
    pub channels: Vec<MergedChannel>,
    /// 合并后的节目数
    pub programme_count: usize,
    /// 每个源的统计
    pub stats: Vec<EpgSourceStat>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct EpgSourceStat {
    pub id: String,
    pub priority: i32,
    pub channels: usize,
    pub programmes: usize,
    /// 被高优先级源覆盖的节目数
    pub overridden: usize,
}

/// 合并多个 XMLTV 源。
///
/// 时间窗口外的节目被忽略（与 grid 一样的规则）。
pub fn merge_sources(
    sources: &[EpgSourceInput<'_>],
    window_start: i64,
    window_end: i64,
) -> MergedEpg {
    // 按优先级稳定排序（小 = 高优先级）
    let mut ordered: Vec<&EpgSourceInput<'_>> = sources.iter().collect();
    ordered.sort_by_key(|s| s.priority);

    // 最终节目表：(规范化 tvg_id, start_ts) → 节目
    let mut merged: HashMap<(String, i64), XmltvProgramme> = HashMap::new();
    // 频道名：规范化 tvg-id → (显示名, 来源)
    let mut channels: HashMap<String, MergedChannel> = HashMap::new();
    let mut result_stats: Vec<EpgSourceStat> = Vec::new();

    for src in &ordered {
        let mut stat = EpgSourceStat {
            id: src.id.clone(),
            priority: src.priority,
            ..Default::default()
        };
        let mut src_channels: Vec<(String, String)> = Vec::new();

        let _ = parse_reader(
            src.xml.as_bytes(),
            |ch| {
                let key = crate::matcher::normalize(&ch.id);
                let name = ch
                    .display_names
                    .first()
                    .cloned()
                    .unwrap_or_else(|| ch.id.clone());
                src_channels.push((key.clone(), name.clone()));
                // 仅当不存在时才插入 → 高优先级源先处理，自然胜出
                channels.entry(key).or_insert(MergedChannel {
                    key: ch.id.clone(),
                    name,
                    source_id: src.id.clone(),
                });
                Ok(())
            },
            |p| {
                if p.stop_ts <= window_start || p.start_ts >= window_end {
                    return Ok(());
                }
                stat.programmes += 1;
                let key = (crate::matcher::normalize(&p.channel), p.start_ts);
                // 仅当不存在时插入 → 高优先级保留
                match merged.entry(key) {
                    std::collections::hash_map::Entry::Vacant(e) => {
                        e.insert(p);
                    }
                    std::collections::hash_map::Entry::Occupied(_) => {
                        stat.overridden += 1;
                    }
                }
                Ok(())
            },
        );

        stat.channels = src_channels.len();
        result_stats.push(stat);
    }

    let mut channels: Vec<MergedChannel> = channels.into_values().collect();
    channels.sort_by(|a, b| a.key.cmp(&b.key));

    MergedEpg {
        channels,
        programme_count: merged.len(),
        stats: result_stats,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ts(s: &str) -> i64 {
        iqtvlite_core::timeutil::parse_xmltv_time(s).unwrap().unix
    }

    const HIGH: &str = r#"<tv>
      <channel id="CCTV1"><display-name>央视一套（高优先级）</display-name></channel>
      <channel id="CCTV5"><display-name>央视体育</display-name></channel>
      <programme start="20240101000000 +0800" stop="20240101010000 +0800" channel="CCTV1"><title>高优先级新闻</title></programme>
      <programme start="20240101010000 +0800" stop="20240101020000 +0800" channel="CCTV1"><title>高优先级独有</title></programme>
    </tv>"#;

    const LOW: &str = r#"<tv>
      <channel id="CCTV1"><display-name>央视一套（低优先级）</display-name></channel>
      <channel id="CCTV9"><display-name>央视纪录（低优先级独有）</display-name></channel>
      <programme start="20240101000000 +0800" stop="20240101010000 +0800" channel="CCTV1"><title>低优先级新闻（应被覆盖）</title></programme>
      <programme start="20240101020000 +0800" stop="20240101030000 +0800" channel="CCTV9"><title>低优先级独有节目</title></programme>
    </tv>"#;

    #[test]
    fn high_priority_overrides_same_slot() {
        let merged = merge_sources(
            &[
                EpgSourceInput {
                    id: "manual".into(),
                    priority: 10,
                    xml: HIGH,
                },
                EpgSourceInput {
                    id: "xtream".into(),
                    priority: 30,
                    xml: LOW,
                },
            ],
            ts("20240101000000 +0800") - 1,
            ts("20240101030000 +0800") + 1,
        );
        // HIGH 2 条 + LOW 2 条（其中 1 条与 HIGH 同 slot 被覆盖）→ 3 个唯一时段
        assert_eq!(merged.programme_count, 3);
        assert!(merged.stats[0].overridden == 0, "高优先级源不应被覆盖");
        assert!(
            merged.stats[1].overridden >= 1,
            "低优先级源应有被覆盖的节目"
        );
    }

    #[test]
    fn high_priority_channel_name_wins() {
        let merged = merge_sources(
            &[
                EpgSourceInput {
                    id: "manual".into(),
                    priority: 10,
                    xml: HIGH,
                },
                EpgSourceInput {
                    id: "xtream".into(),
                    priority: 30,
                    xml: LOW,
                },
            ],
            0,
            i64::MAX,
        );
        let cctv1 = merged.channels.iter().find(|c| c.key == "CCTV1").unwrap();
        assert_eq!(cctv1.name, "央视一套（高优先级）");
        assert_eq!(cctv1.source_id, "manual");
    }

    #[test]
    fn low_priority_only_channel_is_preserved() {
        let merged = merge_sources(
            &[
                EpgSourceInput {
                    id: "manual".into(),
                    priority: 10,
                    xml: HIGH,
                },
                EpgSourceInput {
                    id: "xtream".into(),
                    priority: 30,
                    xml: LOW,
                },
            ],
            0,
            i64::MAX,
        );
        let cctv9 = merged.channels.iter().find(|c| c.key == "CCTV9").unwrap();
        assert_eq!(cctv9.name, "央视纪录（低优先级独有）");
        assert_eq!(cctv9.source_id, "xtream");
    }

    #[test]
    fn equal_priority_keeps_first() {
        let merged = merge_sources(
            &[
                EpgSourceInput {
                    id: "first".into(),
                    priority: 20,
                    xml: HIGH,
                },
                EpgSourceInput {
                    id: "second".into(),
                    priority: 20,
                    xml: LOW,
                },
            ],
            0,
            i64::MAX,
        );
        let cctv1 = merged.channels.iter().find(|c| c.key == "CCTV1").unwrap();
        assert_eq!(cctv1.source_id, "first");
    }

    #[test]
    fn window_filters_programmes() {
        let merged = merge_sources(
            &[EpgSourceInput {
                id: "x".into(),
                priority: 0,
                xml: HIGH,
            }],
            ts("20240101000000 +0800"),
            ts("20240101010000 +0800"),
        );
        // 只有 00:00-01:00 的节目在窗口内
        assert_eq!(merged.programme_count, 1);
    }
}
