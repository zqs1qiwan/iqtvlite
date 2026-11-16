//! EPG 时间轴网格生成器。
//!
//! 这是 TiviMate 的招牌功能的核心数据层：给定 XMLTV 文本、频道列表（tvg-id）
//! 和时间窗口，生成「频道 × 时间片」的网格结构，供 UI 直接渲染。
//!
//! # 设计原则（见 docs/04-data-model.md §4 / docs/09-protocols.md §4）
//!
//! - **绝不物化「网格表」** —— 网格是视图，不是数据。这里在内存里组装。
//! - **索引时间优先** —— 用 `BTreeMap<start_ts, Vec<Programme>>` 做时间切片，
//!   一次扫描填充所有频道的格子。
//! - **匹配严格** —— 只用 `tvg-id` 精确匹配（大小写不敏感 + Unicode 规范化），
//!   **有 id 的频道绝不按名字匹配**（否则跨国家串台）。
//! - **只返回视口内数据** —— 15 万频道的全量网格既不可能也不需要，
//!   调用方传入可见的 tvg-id 列表。

use std::collections::{BTreeMap, HashMap, HashSet};

use iqtvlite_core::parse::xmltv::{parse_reader, XmltvChannel, XmltvProgramme};
use serde::{Deserialize, Serialize};

use crate::matcher::normalize;

/// 网格配置。
#[derive(Debug, Clone)]
pub struct GridConfig {
    /// 窗口起点（Unix 秒，UTC）
    pub window_start: i64,
    /// 窗口终点（Unix 秒，UTC）
    pub window_end: i64,
    /// 时间片宽度（分钟）。TiviMate 常用 30。
    pub slot_minutes: i32,
    /// 需要的频道 tvg-id 列表（视口内的）。空 = 所有指南频道。
    pub tvg_ids: Vec<String>,
    /// 最多返回多少个频道行（防止 UI 卡死）
    pub max_rows: usize,
}

impl Default for GridConfig {
    fn default() -> Self {
        Self {
            window_start: 0,
            window_end: 0,
            slot_minutes: 30,
            tvg_ids: Vec::new(),
            max_rows: 200,
        }
    }
}

/// 网格中的一个节目格子。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GridProgramme {
    /// 开始时间（Unix 秒，UTC）
    pub start: i64,
    /// 结束时间（Unix 秒，UTC）
    pub stop: i64,
    pub title: String,
    pub description: Option<String>,
    pub category: Option<String>,
    /// 起始列（相对窗口起点）
    pub col: i32,
    /// 跨越的列数
    pub span: i32,
    /// 当前是否在播出
    pub progress: u8,
    /// 节目级 catchup-id（回填到 catch-up URL 模板）
    pub catchup_id: Option<String>,
}

/// 一行（一个频道）。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GridRow {
    pub tvg_id: String,
    pub name: String,
    pub icon: Option<String>,
    pub programmes: Vec<GridProgramme>,
}

/// 完整网格。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EpgGrid {
    pub window_start: i64,
    pub window_end: i64,
    pub slot_minutes: i32,
    pub now: i64,
    pub rows: Vec<GridRow>,
    /// 匹配统计
    pub stats: GridStats,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct GridStats {
    /// 请求的频道数
    pub requested: usize,
    /// 匹配上的频道数
    pub matched: usize,
    /// XMLTV 里的频道总数
    pub guide_channels: usize,
    /// 窗口内的节目总数
    pub programmes: usize,
    /// 无法解析时间的节目数
    pub skipped_bad_time: u32,
}

/// 从 XMLTV 文本生成网格。
///
/// `xmltv` 可以是 gzip 解压后的 UTF-8 文本（`reqwest` 会自动解压 gzip）。
pub fn build_grid(xmltv: &str, config: &GridConfig, now: i64) -> EpgGrid {
    let mut stats = GridStats {
        requested: config.tvg_ids.len(),
        ..Default::default()
    };

    // 允许的请求集合（规范化后）
    let wanted: HashSet<String> = config.tvg_ids.iter().map(|id| normalize(id)).collect();

    // ---- ① 流式解析 XMLTV：只保留窗口内的节目 ----
    let mut channels: Vec<XmltvChannel> = Vec::new();
    // key: 规范化 tvg-id；value: (原始 key, display name)
    let mut channel_names: HashMap<String, (String, String)> = HashMap::new();
    // 时间 → 节目列表（一次扫描，天然按时间有序）
    let mut by_time: BTreeMap<i64, Vec<XmltvProgramme>> = BTreeMap::new();
    let mut skipped_bad_time = 0u32;

    let parse_result = parse_reader(
        xmltv.as_bytes(),
        |ch| {
            if !wanted.is_empty() && !wanted.contains(&normalize(&ch.id)) {
                return Ok(());
            }
            let name = ch
                .display_names
                .first()
                .cloned()
                .unwrap_or_else(|| ch.id.clone());
            channel_names.insert(normalize(&ch.id), (ch.id.clone(), name));
            channels.push(ch);
            Ok(())
        },
        |p| {
            if p.stop_ts <= config.window_start || p.start_ts >= config.window_end {
                return Ok(());
            }
            // 只保留请求频道的节目（若有过滤）
            if !wanted.is_empty() && !wanted.contains(&normalize(&p.channel)) {
                return Ok(());
            }
            by_time.entry(p.start_ts).or_default().push(p);
            Ok(())
        },
    );

    if let Ok(s) = parse_result {
        skipped_bad_time = s.skipped_bad_time;
    }
    stats.guide_channels = channels.len();
    stats.skipped_bad_time = skipped_bad_time;

    // ---- ② 按频道分组（单条扫描，不 N+1） ----
    let mut programmes_by_channel: HashMap<String, Vec<&XmltvProgramme>> = HashMap::new();
    for bucket in by_time.values() {
        for p in bucket {
            programmes_by_channel
                .entry(normalize(&p.channel))
                .or_default()
                .push(p);
        }
    }

    // ---- ③ 组装网格行 ----
    let slot_secs = (config.slot_minutes.max(1) as i64) * 60;
    let window_secs = config.window_end - config.window_start;
    let total_cols = ((window_secs + slot_secs - 1) / slot_secs).max(1) as i32;

    // 行顺序：优先按请求的 tvg_ids 顺序（即播放列表顺序），再补未请求的
    let mut order: Vec<String> = if !wanted.is_empty() {
        config.tvg_ids.iter().map(|id| normalize(id)).collect()
    } else {
        let mut keys: Vec<String> = channel_names.keys().cloned().collect();
        keys.sort();
        keys
    };

    // 去重
    let mut seen = HashSet::new();
    order.retain(|k| seen.insert(k.clone()));

    let mut rows = Vec::new();
    for key in order {
        if rows.len() >= config.max_rows {
            break;
        }
        let Some((tvg_id, name)) = channel_names.get(&key) else {
            continue;
        };
        let Some(programmes) = programmes_by_channel.get(&key) else {
            // 在请求的频道里但没有节目 → 空行（UI 显示「无节目」）
            if !wanted.is_empty() {
                rows.push(GridRow {
                    tvg_id: tvg_id.clone(),
                    name: name.clone(),
                    icon: None,
                    programmes: Vec::new(),
                });
            }
            continue;
        };

        let mut cells: Vec<GridProgramme> = Vec::new();
        for p in programmes {
            let start = p.start_ts.max(config.window_start);
            let stop = p.stop_ts.min(config.window_end);
            if stop <= start {
                continue;
            }
            let col = ((start - config.window_start) / slot_secs) as i32;
            let span = (((stop - start) + slot_secs - 1) / slot_secs) as i32;
            if col >= total_cols {
                continue;
            }
            let duration = (stop - start).max(1);
            let elapsed = (now - start).clamp(0, duration);
            let progress = ((elapsed as f64 / duration as f64) * 100.0) as u8;
            cells.push(GridProgramme {
                start: p.start_ts,
                stop: p.stop_ts,
                title: p.title.clone().unwrap_or_else(|| "未命名节目".to_string()),
                description: p.description.clone(),
                category: p.category.clone(),
                col,
                span: span.min(total_cols - col),
                progress,
                catchup_id: p.catchup_id.clone(),
            });
            stats.programmes += 1;
        }
        // 按列排序
        cells.sort_by_key(|c| c.col);

        rows.push(GridRow {
            tvg_id: tvg_id.clone(),
            name: name.clone(),
            icon: channels
                .iter()
                .find(|c| normalize(&c.id) == key)
                .and_then(|c| c.icon_urls.first().cloned()),
            programmes: cells,
        });
    }
    stats.matched = rows.iter().filter(|r| !r.programmes.is_empty()).count();

    EpgGrid {
        window_start: config.window_start,
        window_end: config.window_end,
        slot_minutes: config.slot_minutes,
        now,
        rows,
        stats,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="CCTV1"><display-name>CCTV-1 综合</display-name><icon src="cctv1.png"/></channel>
  <channel id="CCTV2"><display-name>CCTV-2 财经</display-name></channel>
  <programme start="20240101000000 +0000" stop="20240101010000 +0000" channel="CCTV1">
    <title>新闻联播</title><desc>当日新闻</desc><category>新闻</category>
  </programme>
  <programme start="20240101010000 +0000" stop="20240101023000 +0000" channel="CCTV1">
    <title>焦点访谈</title>
  </programme>
  <programme start="20240101000000 +0000" stop="20240101020000 +0000" channel="CCTV2">
    <title>经济半小时</title>
  </programme>
  <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="CCTV1">
    <title>窗口外节目</title>
  </programme>
</tv>
"#;

    fn ts(s: &str) -> i64 {
        iqtvlite_core::timeutil::parse_xmltv_time(s).unwrap().unix
    }

    #[test]
    fn builds_grid_with_time_window() {
        let cfg = GridConfig {
            window_start: ts("20240101000000"),
            window_end: ts("20240101030000"),
            slot_minutes: 30,
            tvg_ids: vec!["CCTV1".into(), "CCTV2".into()],
            ..Default::default()
        };
        let grid = build_grid(SAMPLE, &cfg, ts("20240101003000"));

        assert_eq!(grid.stats.guide_channels, 2);
        assert_eq!(grid.rows.len(), 2);
        assert_eq!(grid.rows[0].tvg_id, "CCTV1");
        assert_eq!(grid.rows[0].programmes.len(), 2, "窗口内应有 2 个节目");
        assert_eq!(grid.rows[0].programmes[0].title, "新闻联播");
        assert_eq!(
            grid.rows[0].programmes[0].span, 2,
            "1 小时 = 2 个 30 分钟片"
        );
        assert_eq!(grid.rows[0].programmes[1].span, 3, "1.5 小时 = 3 片");
        // 窗口外的节目不应出现
        assert!(!grid
            .rows
            .iter()
            .any(|r| r.programmes.iter().any(|p| p.title == "窗口外节目")));
    }

    #[test]
    fn progress_is_computed() {
        let cfg = GridConfig {
            window_start: ts("20240101000000"),
            window_end: ts("20240101030000"),
            slot_minutes: 30,
            tvg_ids: vec!["CCTV1".into()],
            ..Default::default()
        };
        // now = 00:30，节目 00:00-01:00 → 50%
        let grid = build_grid(SAMPLE, &cfg, ts("20240101003000"));
        assert_eq!(grid.rows[0].programmes[0].progress, 50);
    }

    #[test]
    fn only_requested_channels_are_returned() {
        let cfg = GridConfig {
            window_start: ts("20240101000000"),
            window_end: ts("20240101030000"),
            slot_minutes: 30,
            tvg_ids: vec!["CCTV2".into()],
            ..Default::default()
        };
        let grid = build_grid(SAMPLE, &cfg, ts("20240101003000"));
        assert_eq!(grid.rows.len(), 1);
        assert_eq!(grid.rows[0].tvg_id, "CCTV2");
    }

    #[test]
    fn tvg_id_match_is_case_insensitive() {
        let cfg = GridConfig {
            window_start: ts("20240101000000"),
            window_end: ts("20240101030000"),
            slot_minutes: 30,
            tvg_ids: vec!["cctv1".into()], // 小写
            ..Default::default()
        };
        let grid = build_grid(SAMPLE, &cfg, ts("20240101003000"));
        assert_eq!(grid.rows.len(), 1);
        assert_eq!(grid.rows[0].tvg_id, "CCTV1");
    }

    #[test]
    fn empty_tvg_ids_returns_all_channels() {
        let cfg = GridConfig {
            window_start: ts("20240101000000"),
            window_end: ts("20240101030000"),
            slot_minutes: 30,
            tvg_ids: vec![],
            ..Default::default()
        };
        let grid = build_grid(SAMPLE, &cfg, ts("20240101003000"));
        assert_eq!(grid.rows.len(), 2);
    }

    #[test]
    fn max_rows_is_respected() {
        let cfg = GridConfig {
            window_start: ts("20240101000000"),
            window_end: ts("20240101030000"),
            slot_minutes: 30,
            tvg_ids: vec![],
            max_rows: 1,
        };
        let grid = build_grid(SAMPLE, &cfg, ts("20240101003000"));
        assert_eq!(grid.rows.len(), 1);
    }

    #[test]
    fn grid_serializes_to_json() {
        let cfg = GridConfig {
            window_start: ts("20240101000000"),
            window_end: ts("20240101030000"),
            slot_minutes: 30,
            tvg_ids: vec!["CCTV1".into()],
            ..Default::default()
        };
        let grid = build_grid(SAMPLE, &cfg, ts("20240101003000"));
        let json = serde_json::to_string(&grid).unwrap();
        assert!(json.contains("新闻联播"));
        assert!(json.contains("\"col\":0"));
        assert!(json.contains("\"matched\""));
    }
}
