//! 老白直播 EPG（https://epg.laobaitv.net）的真实格式回归测试。
//!
//! 实测（2026-11-16）：gzip 压缩，解压后 3.3 MB XML，155 个频道，
//! channel id 与播放列表 tvg-id 直接匹配（CCTV1 / CCTV2 … 100% 命中）。

use iqtvlite_core::timeutil::parse_xmltv_time;
use iqtvlite_epg::{build_grid, GridConfig};

/// 真实 EPG 的片段（结构与线上一致）。
const EPG: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="laobaiepg" generator-info-url="https://github.com/laobaiepg">
  <channel id="CCTV1">
    <display-name lang="zh">CCTV-1 综合</display-name>
    <display-name lang="zh">中央电视台综合频道</display-name>
    <icon src="CCTV1"/>
  </channel>
  <channel id="CCTV5">
    <display-name lang="zh">CCTV-5 体育</display-name>
  </channel>
  <channel id="bingtuanweishi">
    <display-name lang="zh">兵团卫视</display-name>
  </channel>
  <programme start="20240101000000 +0800" stop="20240101003000 +0800" channel="CCTV1">
    <title lang="zh">新闻联播</title>
    <desc lang="zh">当日新闻</desc>
    <category lang="zh">新闻</category>
  </programme>
  <programme start="20240101003000 +0800" stop="20240101010000 +0800" channel="CCTV1">
    <title lang="zh">焦点访谈</title>
  </programme>
  <programme start="20240101000000 +0800" stop="20240101020000 +0800" channel="CCTV5">
    <title lang="zh">体育新闻</title>
  </programme>
  <programme start="20240101000000 +0800" stop="20240101010000 +0800" channel="bingtuanweishi">
    <title lang="zh">兵团新闻</title>
  </programme>
</tv>
"#;

#[test]
fn laobaitv_epg_ids_match_playlist_tvg_ids() {
    // 播放列表里的 tvg-id 直接作为网格请求
    let cfg = GridConfig {
        window_start: parse_xmltv_time("20240101000000 +0800").unwrap().unix,
        window_end: parse_xmltv_time("20240101030000 +0800").unwrap().unix,
        slot_minutes: 30,
        tvg_ids: vec!["CCTV1".into(), "CCTV5".into(), "bingtuanweishi".into()],
        max_rows: 50,
    };
    let grid = build_grid(
        EPG,
        &cfg,
        parse_xmltv_time("20240101001500 +0800").unwrap().unix,
    );

    assert_eq!(grid.stats.requested, 3);
    assert_eq!(grid.stats.matched, 3, "三个频道的 id 都应匹配上");
    assert_eq!(grid.rows.len(), 3);
    assert_eq!(grid.rows[0].tvg_id, "CCTV1");
    assert_eq!(grid.rows[0].name, "CCTV-1 综合");
}

#[test]
fn laobaitv_epg_programmes_have_correct_time_and_cells() {
    let cfg = GridConfig {
        window_start: parse_xmltv_time("20240101000000 +0800").unwrap().unix,
        window_end: parse_xmltv_time("20240101030000 +0800").unwrap().unix,
        slot_minutes: 30,
        tvg_ids: vec!["CCTV1".into()],
        ..Default::default()
    };
    let grid = build_grid(
        EPG,
        &cfg,
        parse_xmltv_time("20240101001500 +0800").unwrap().unix,
    );
    let row = &grid.rows[0];

    assert_eq!(row.programmes.len(), 2);
    assert_eq!(row.programmes[0].title, "新闻联播");
    assert_eq!(row.programmes[0].col, 0);
    assert_eq!(row.programmes[0].span, 1, "30 分钟节目 = 1 个片");
    // now=00:15，节目 00:00-00:30 → 50%
    assert_eq!(row.programmes[0].progress, 50);
    assert_eq!(row.programmes[1].title, "焦点访谈");
    assert_eq!(row.programmes[1].col, 1);
}

#[test]
fn laobaitv_epg_timezone_offset_is_applied() {
    // +0800 的 00:00 等于 UTC 前一天 16:00
    let local = parse_xmltv_time("20240101000000 +0800").unwrap().unix;
    let utc = parse_xmltv_time("20231231160000 +0000").unwrap().unix;
    assert_eq!(local, utc, "时区偏移必须正确换算");
}
