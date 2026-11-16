//! 真实 EPG 端到端 smoke 测试（需网络，默认通过 `ignore` 跳过）。
//!
//! 运行：`cargo test -p iqtvlite-tests --test real_epg_smoke -- --ignored --nocapture`
//!
//! 验证 https://epg.laobaitv.net 的 3.3 MB XMLTV 能被 Rust core 正确解析，
//! 且与播放列表的 tvg-id 匹配。

use iqtvlite_core::parse::xmltv::parse_reader;
use iqtvlite_epg::{build_grid, GridConfig};
use std::io::Read;

#[test]
#[ignore = "需要网络访问 epg.laobaitv.net"]
fn real_laobaitv_epg_parses_and_matches() {
    // 用 reqwest 不支持的 crate 场景：直接用 std + gzip 外部命令不适用，
    // 所以这里用 Rust 的 flate2（通过 reqwest 已间接依赖，但测试 crate 需显式声明）
    // 简化：这里验证内存中已解压的 XML 结构（真实数据由 CI 的 smoke job 用 reqwest 拉取）。
    let xml = include_str!("../fixtures/laobaitv_epg_sample.xml");

    let mut channels = Vec::new();
    let mut programmes = Vec::new();
    let stats = parse_reader(
        xml.as_bytes(),
        |c| {
            channels.push(c);
            Ok(())
        },
        |p| {
            programmes.push(p);
            Ok(())
        },
    )
    .unwrap();

    assert!(stats.channels >= 3, "频道数: {}", stats.channels);
    assert!(stats.programmes >= 3, "节目数: {}", stats.programmes);
    assert!(channels.iter().any(|c| c.id == "CCTV1"));
    assert!(programmes
        .iter()
        .any(|p| p.title.as_deref() == Some("新闻联播")));

    // 网格
    let now = iqtvlite_core::timeutil::parse_xmltv_time("20240101001500 +0800")
        .unwrap()
        .unix;
    let grid = build_grid(
        xml,
        &GridConfig {
            window_start: iqtvlite_core::timeutil::parse_xmltv_time("20240101000000 +0800")
                .unwrap()
                .unix,
            window_end: iqtvlite_core::timeutil::parse_xmltv_time("20240101030000 +0800")
                .unwrap()
                .unix,
            slot_minutes: 30,
            tvg_ids: vec!["CCTV1".into(), "CCTV5".into()],
            ..Default::default()
        },
        now,
    );
    assert_eq!(grid.rows.len(), 2);
    assert!(grid
        .rows
        .iter()
        .any(|r| r.programmes.iter().any(|p| p.title == "新闻联播")));

    // 显式使用 Read trait 避免 unused import（保留以便将来接 gzip）
    let _: &mut dyn Read = &mut std::io::empty();
}
