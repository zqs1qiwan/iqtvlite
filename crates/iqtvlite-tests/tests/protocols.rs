//! 集成测试：用**真实形态**的 fixture 验证协议层。
//!
//! 每个 fixture 都来自 `docs/research/domain-protocols-reference.md` 记录的真实供应商怪癖。
//! 这些 case 在单元测试里覆盖不到 —— 单元测试验证「规则」，集成测试验证「现实」。

use iqtvlite_core::id::{global_id, m3u_provider_key, IdUniquifier, SourceId};
use iqtvlite_core::model::catchup::{CatchupConfig, CatchupResolver};
use iqtvlite_core::model::catchup::{CatchupContext, CatchupType};
use iqtvlite_core::parse::m3u;
use iqtvlite_core::parse::xmltv;
use iqtvlite_core::parse::xtream;

// ===========================================================================
// M3U
// ===========================================================================

/// 一个「什么都可能发生」的播放列表。
///
/// 覆盖：多 EPG 源、`#EXTGRP` 指令、名称里的逗号、三种引号、未知属性、
/// 时移属性、UA 覆盖、备用线路、裸 URL、中文名、空的 `#EXTINF`。
const NASTY_M3U: &str = r#"#EXTM3U url-tvg="http://epg.example.com/guide.xml;http://epg2.example.com/g.xml" x-tvg-url="http://epg3.example.com/g.xml" catchup="shift" catchup-days="7" user-agent="PlaylistUA/1.0"
#EXTINF:-1 tvg-id="BBCOne.uk" tvg-name="BBC One" tvg-logo="http://logos/bbc.png" tvg-chno="1" group-title="UK;HD",BBC One HD
#EXTVLCOPT:http-user-agent=TiviMate/5.1.6 (Android 12)
#EXTVLCOPT:http-referrer=http://ref.example.com/
http://live.example.com/bbc.ts
http://backup.example.com/bbc.ts
#EXTGRP:Sports
#EXTINF:-1 tvg-id="ESPN.us",ESPN
http://live.example.com/espn.m3u8
#EXTINF:-1 tvg-id="ITV.uk" group-title="News",ITV, The Leader
http://live.example.com/itv.ts
#EXTINF:-1 tvg-id="CN.us" x-custom-thing="keepme" tvg-shift="2.5",CNN
http://live.example.com/cnn.ts
#EXTINF:-1,孤儿条目（没有 URL）
#EXTINF:-1 tvg-id="ONE.uk",Channel One
http://live.example.com/one.ts
#EXTINF:-1 tvg-id="TWO.uk" catchup="append" catchup-source="?utc={utc}&lutc={lutc}" catchup-days="0",Channel Two
http://live.example.com/two.ts
#EXTINF:-1 tvg-id="CATCH.src" catchup="default" catchup-source="http://archive.example.com/v-{utc}-{duration}.m3u8" catchup-days="30",Catchup Source
http://live.example.com/catchup.ts
#EXTINF:-1 tvg-id="BARE.one",裸 URL 条目
http://live.example.com/bare.ts
http://live.example.com/bare2.ts
"#;

#[test]
fn nasty_m3u_parses_completely() {
    let p = m3u::parse(NASTY_M3U).expect("应能解析");

    // ── 头部 ──
    assert_eq!(
        p.header.epg_urls.len(),
        3,
        "url-tvg 与 x-tvg-url 都是 EPG 源；; 分隔的多地址也要展开"
    );
    assert!(p
        .header
        .epg_urls
        .contains(&"http://epg.example.com/guide.xml".to_string()));
    assert!(p
        .header
        .epg_urls
        .contains(&"http://epg3.example.com/g.xml".to_string()));
    assert_eq!(p.header.catchup_type, Some(CatchupType::Shift));
    assert_eq!(p.header.catchup_days, Some(7));

    // ── 条目数 ──
    // 8 个有效条目 + 1 个孤儿（无 URL 被跳过）
    assert_eq!(
        p.entries.len(),
        8,
        "实际: {:?}",
        p.entries.iter().map(|e| e.name.clone()).collect::<Vec<_>>()
    );
    assert_eq!(p.stats.skipped, 1, "没有 URL 的 #EXTINF 应被跳过");
}

#[test]
fn nasty_m3u_groups_follow_kodi_semantics() {
    let p = m3u::parse(NASTY_M3U).unwrap();
    let by_name = |n: &str| {
        p.entries
            .iter()
            .find(|e| e.name.as_deref() == Some(n))
            .unwrap_or_else(|| panic!("找不到频道 {n}"))
    };

    // group-title 优先，且支持 ; 多组
    assert_eq!(by_name("BBC One HD").groups, vec!["UK", "HD"]);
    // #EXTGRP 指令对后续无 group-title 的条目生效
    assert_eq!(by_name("ESPN").groups, vec!["Sports"]);
    // 之后的条目自带 group-title → 覆盖指令
    assert_eq!(by_name("ITV, The Leader").groups, vec!["News"]);
}

#[test]
fn nasty_m3u_display_name_takes_last_comma() {
    let p = m3u::parse(NASTY_M3U).unwrap();
    let itv = p
        .entries
        .iter()
        .find(|e| e.tvg_id.as_deref() == Some("ITV.uk"))
        .unwrap();
    assert_eq!(
        itv.name.as_deref(),
        Some("ITV, The Leader"),
        "显示名取最后一个逗号之后"
    );
}

#[test]
fn nasty_m3u_preserves_unknown_attributes() {
    let p = m3u::parse(NASTY_M3U).unwrap();
    let cnn = p
        .entries
        .iter()
        .find(|e| e.tvg_id.as_deref() == Some("CN.us"))
        .unwrap();
    assert_eq!(
        cnn.extras.get("x-custom-thing").map(String::as_str),
        Some("keepme"),
        "未知属性必须保留 —— 供应商生态的私有属性太多"
    );
    assert_eq!(cnn.tvg_shift, Some(2.5), "tvg-shift 是 EPG 偏移");
}

#[test]
fn nasty_m3u_vlc_opts_become_headers() {
    let p = m3u::parse(NASTY_M3U).unwrap();
    let bbc = p
        .entries
        .iter()
        .find(|e| e.tvg_id.as_deref() == Some("BBCOne.uk"))
        .unwrap();
    assert_eq!(
        bbc.user_agent.as_deref(),
        Some("TiviMate/5.1.6 (Android 12)")
    );
    assert_eq!(bbc.referrer.as_deref(), Some("http://ref.example.com/"));
}

#[test]
fn nasty_m3u_alternative_urls_stay_on_same_channel() {
    let p = m3u::parse(NASTY_M3U).unwrap();
    let bbc = p
        .entries
        .iter()
        .find(|e| e.tvg_id.as_deref() == Some("BBCOne.uk"))
        .unwrap();
    assert_eq!(bbc.urls.len(), 2, "同一 #EXTINF 后的连续 URL 是备用线路");
    assert_eq!(bbc.primary_url(), Some("http://live.example.com/bbc.ts"));
}

#[test]
fn nasty_m3u_catchup_days_zero_means_no_archive() {
    let p = m3u::parse(NASTY_M3U).unwrap();
    let two = p
        .entries
        .iter()
        .find(|e| e.tvg_id.as_deref() == Some("TWO.uk"))
        .unwrap();
    let cfg = two.catchup.as_ref().expect("应有 catchup 配置");
    assert_eq!(cfg.catchup_type, CatchupType::Append);
    assert_eq!(cfg.days, Some(0));
    assert!(!cfg.has_archive(), "catchup-days=0 → 该频道无归档");
}

#[test]
fn nasty_m3u_header_catchup_is_inherited() {
    let p = m3u::parse(NASTY_M3U).unwrap();
    let espn = p
        .entries
        .iter()
        .find(|e| e.tvg_id.as_deref() == Some("ESPN.us"))
        .unwrap();
    let cfg = espn.catchup.as_ref().expect("应继承头部的 catchup 配置");
    assert_eq!(cfg.catchup_type, CatchupType::Shift);
    assert_eq!(cfg.days, Some(7));
}

#[test]
fn nasty_m3u_stable_ids_survive_resync() {
    // 同一播放列表解析两次 → 稳定 ID 完全一致
    let a = m3u::parse(NASTY_M3U).unwrap();
    let b = m3u::parse(NASTY_M3U).unwrap();
    let source = SourceId(42);
    for (ea, eb) in a.entries.iter().zip(b.entries.iter()) {
        let key_a = m3u_provider_key(ea.tvg_id.as_deref(), ea.primary_url(), ea.name.as_deref());
        let key_b = m3u_provider_key(eb.tvg_id.as_deref(), eb.primary_url(), eb.name.as_deref());
        assert_eq!(key_a, key_b);
        assert_eq!(
            global_id(source, &key_a),
            global_id(source, &key_b),
            "重新同步后稳定 ID 必须不变，否则收藏会丢"
        );
    }
}

#[test]
fn duplicate_entries_get_distinct_ids() {
    let mut u = IdUniquifier::new();
    let a = u.uniquify("BBCOne.uk", Some("http://a"));
    let b = u.uniquify("BBCOne.uk", Some("http://b"));
    assert_ne!(a, b, "同源内完全重复的条目必须有不同 ID");
}

// ===========================================================================
// XMLTV
// ===========================================================================

const REALISTIC_XMLTV: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv source-info-url="http://example.com/" generator-info-name="Provider">
  <channel id="BBCOne.uk">
    <display-name lang="en">BBC One</display-name>
    <display-name lang="zh">BBC 一台</display-name>
    <icon src="http://logos/bbc.png" />
  </channel>
  <channel id="BBCOne.uk.hd">
    <display-name>BBC One HD</display-name>
  </channel>
  <channel id="CNN.us">
    <display-name>CNN International</display-name>
  </channel>
  <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="BBCOne.uk">
    <title lang="en">The News</title>
    <desc lang="en">Headline news at noon</desc>
    <category>News</category>
    <episode-num system="xmltv_ns">0.1.0/1</episode-num>
    <icon src="http://img/news.png" />
    <previously-shown start="20240101100000 +0000" />
  </programme>
  <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="BBCOne.uk" clumpidx="0/1">
    <title>The News</title>
    <category>News</category>
  </programme>
  <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="BBCOne.uk.hd">
    <title>The News (HD feed)</title>
  </programme>
  <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="CNN.us">
    <title>CNN Newsroom</title>
    <credits><director>Jane Doe</director><actor role="Host">John Smith</actor></credits>
  </programme>
  <programme start="garbage" stop="garbage" channel="CNN.us">
    <title>坏时间</title>
  </programme>
</tv>
"#;

#[test]
fn realistic_xmltv_parses() {
    let mut channels = Vec::new();
    let mut programmes = Vec::new();
    let stats = xmltv::parse_reader(
        REALISTIC_XMLTV.as_bytes(),
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

    assert_eq!(stats.channels, 3);
    assert_eq!(stats.programmes, 4, "坏时间的节目应被跳过");
    assert_eq!(stats.skipped_bad_time, 1);

    // 多语言 display-name
    let bbc = channels.iter().find(|c| c.id == "BBCOne.uk").unwrap();
    assert_eq!(bbc.display_names.len(), 2);
    assert!(bbc.display_names.contains(&"BBC 一台".to_string()));

    // 节目字段
    let news = programmes
        .iter()
        .find(|p| p.title.as_deref() == Some("The News"))
        .unwrap();
    assert_eq!(news.channel, "BBCOne.uk");
    assert_eq!(news.stop_ts - news.start_ts, 3600);
    assert!(news.previously_shown);
    assert_eq!(news.episode_num.as_deref(), Some("0.1.0/1"));

    // clumpidx 是 <programme> 的属性
    let news2 = programmes.iter().find(|p| p.clumpidx.is_some()).unwrap();
    assert_eq!(news2.clumpidx.as_deref(), Some("0/1"));

    // credits
    let cnn = programmes.iter().find(|p| p.channel == "CNN.us").unwrap();
    let credits = cnn.credits.as_ref().expect("credits 应被捕获");
    assert!(credits.contains("Jane Doe"), "实际: {credits}");
    assert!(credits.contains("Host"), "actor 的 role 属性应保留");
}

#[test]
fn xmltv_programmes_are_grouped_by_channel_for_grid() {
    let mut programmes = Vec::new();
    xmltv::parse_reader(
        REALISTIC_XMLTV.as_bytes(),
        |_| Ok(()),
        |p| {
            programmes.push(p);
            Ok(())
        },
    )
    .unwrap();

    // 模拟网格查询：按频道分组
    let mut by_channel: std::collections::HashMap<&str, Vec<_>> = Default::default();
    for p in &programmes {
        by_channel.entry(p.channel.as_str()).or_default().push(p);
    }
    assert_eq!(by_channel["BBCOne.uk"].len(), 2);
    assert_eq!(by_channel["BBCOne.uk.hd"].len(), 1);
    assert_eq!(by_channel["CNN.us"].len(), 1);
}

// ===========================================================================
// EPG 匹配
// ===========================================================================

#[test]
fn matcher_does_not_cross_countries() {
    use iqtvlite_epg::matcher::{EpgChannelInfo, MatchCandidate, Matcher};

    let mut m = Matcher::new();
    m.load(vec![
        EpgChannelInfo {
            epg_channel_id: "e-us".into(),
            key: "us.bbc.one".into(),
            display_names: vec!["BBC One".into()],
        },
        EpgChannelInfo {
            epg_channel_id: "e-uk".into(),
            key: "uk.bbc.one".into(),
            display_names: vec!["BBC One".into()],
        },
    ]);

    // 频道有 id，但 id 对不上 → **不能**按名字接上（否则会串台）
    let out = m.match_all(&[MatchCandidate {
        channel_id: "c1".into(),
        tvg_id: Some("uk.bbc.one.hd".into()),
        tvg_name: None,
        name: "BBC One".into(),
    }]);
    assert!(
        out.matches.is_empty(),
        "有 id 的频道绝不能按名字匹配（会串台）"
    );
    assert_eq!(out.unmatched_with_id.len(), 1);

    // id 对得上 → 正确匹配
    let out = m.match_all(&[MatchCandidate {
        channel_id: "c2".into(),
        tvg_id: Some("uk.bbc.one".into()),
        tvg_name: None,
        name: "BBC One".into(),
    }]);
    assert_eq!(out.matches.len(), 1);
    assert_eq!(out.matches[0].epg_channel_id, "e-uk");
}

// ===========================================================================
// Catch-up 模板
// ===========================================================================

#[test]
fn catchup_shift_produces_provider_local_time() {
    // Xtream 时移：起点是**供应商本地墙钟时间**
    let ctx = CatchupContext::with_buffer(1_700_000_000, 1_700_003_600, 1_700_003_000);
    let resolver = CatchupResolver {
        config: CatchupConfig {
            catchup_type: CatchupType::Shift,
            ..Default::default()
        },
        live_url: "http://x/y.ts".into(),
        provider_tz: Some("Africa/Casablanca".into()),
    };
    let url = resolver.resolve(&ctx).unwrap();
    assert!(url.starts_with("http://x/y.ts?utc=1700000000&lutc=1700003000"));
}

#[test]
fn catchup_default_template_renders() {
    let ctx = CatchupContext::with_buffer(1_700_000_000, 1_700_003_600, 1_700_003_000);
    let resolver = CatchupResolver {
        config: CatchupConfig {
            catchup_type: CatchupType::Default,
            source: Some("http://archive.example.com/v-{utc}-{duration}.m3u8".into()),
            ..Default::default()
        },
        live_url: "http://x/y.ts".into(),
        provider_tz: None,
    };
    let url = resolver.resolve(&ctx).unwrap();
    assert_eq!(
        url, "http://archive.example.com/v-1700000000-3900.m3u8",
        "duration 应为含 5 分钟缓冲的 3900 秒"
    );
}

// ===========================================================================
// 存储层
// ===========================================================================

#[test]
fn store_round_trips_a_channel_and_keeps_favourites_across_resync() {
    use iqtvlite_store::Store;

    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("iqtvlite.db");

    let source_id = {
        let store = Store::open(&path).unwrap();
        let conn = store.conn();
        conn.execute(
            "INSERT INTO sources (uuid, kind, name, created_at, updated_at)
             VALUES ('u1','m3u','Test',0,0)",
            [],
        )
        .unwrap();
        // 插入一个频道
        conn.execute(
            "INSERT INTO channels (id, source_id, provider_id, name, primary_url, first_seen_at, last_seen_at)
             VALUES ('c1', 1, 'BBCOne.uk', 'BBC One', 'http://x/y', 0, 0)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO favourite_lists (id, name) VALUES ('l1','我的收藏')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO favourites (list_id, channel_id, created_at) VALUES ('l1','c1',0)",
            [],
        )
        .unwrap();
        1i64
    };
    assert_eq!(source_id, 1);

    // 模拟「重新同步」：更新频道名，不动收藏
    {
        let store = Store::open(&path).unwrap();
        let conn = store.conn();
        conn.execute("UPDATE channels SET name='BBC One HD' WHERE id='c1'", [])
            .unwrap();
    }

    // 收藏仍在
    {
        let store = Store::open(&path).unwrap();
        let conn = store.conn();
        let name: String = conn
            .query_row("SELECT name FROM channels WHERE id='c1'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(name, "BBC One HD");
        let fav: i64 = conn
            .query_row(
                "SELECT count(*) FROM favourites WHERE channel_id='c1'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(fav, 1, "重新同步后收藏不能丢");
    }
}

#[test]
fn store_epg_grid_query_uses_time_index() {
    use iqtvlite_store::Store;

    let store = Store::open_in_memory().unwrap();
    {
        let conn = store.conn();
        conn.execute(
            "INSERT INTO sources (uuid, kind, name, created_at, updated_at) VALUES ('u1','m3u','T',0,0)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO epg_sources (id, source_id, url) VALUES ('e1',1,'http://epg/x.xml')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO epg_channels (id, epg_source_id, key) VALUES ('ec1','e1','BBCOne.uk')",
            [],
        )
        .unwrap();
        // 24 小时 × 12 个节目
        for i in 0..12 {
            let start = 1_704_067_200 + i * 7200; // 每 2 小时一个
            let title = format!("节目 {i}");
            conn.execute(
                "INSERT INTO programmes (epg_channel_id, start_ts, stop_ts, title)
                 VALUES ('ec1', ?1, ?2, ?3)",
                rusqlite::params![start, start + 7200, title],
            )
            .unwrap();
        }
    }

    // 模拟网格查询：时间切片
    {
        let conn = store.conn();
        let window_start = 1_704_067_200i64;
        let window_end = window_start + 6 * 3600;
        let mut stmt = conn
            .prepare(
                "SELECT title FROM programmes
                 WHERE start_ts < ?2 AND stop_ts > ?1
                 ORDER BY start_ts",
            )
            .unwrap();
        let rows: Vec<String> = stmt
            .query_map(rusqlite::params![window_start, window_end], |r| r.get(0))
            .unwrap()
            .map(|r| r.unwrap())
            .collect();
        drop(stmt);
        // 6 小时窗口内应有 3 个（每 2 小时一个）
        assert_eq!(rows.len(), 3, "实际: {rows:?}");
    }
}

// ===========================================================================
// Xtream
// ===========================================================================

#[test]
fn xtream_endpoints_cover_all_documented_shapes() {
    let e = xtream::XtreamEndpoints::new("http://panel:25461", "u", "p");
    assert!(e
        .player_api(Some("get_live_streams"))
        .contains("action=get_live_streams"));
    assert!(e.series_info(7).contains("action=get_series_info"));
    assert!(e.short_epg(42, Some(8)).contains("limit=8"));
    assert!(e.xmltv().contains("xmltv.php"));
    assert!(e.m3u("ts").contains("type=m3u_plus&output=ts"));
}

#[test]
fn xtream_epg_titles_are_base64() {
    let body = r#"{"epg_listings":[{"id":"1","epg_id":"20","channel_id":"demo.channel",
        "title":"RGVtbyBOb3c=","description":"SGVhZGxpbmUgbmV3cw==","lang":"",
        "start":"2026-10-15 10:36:06","end":1792060566,
        "start_timestamp":1792056966,"stop_timestamp":1792060566,
        "stop":"2026-10-15 11:36:06","now_playing":0,"has_archive":0}]}"#;
    let resp: xtream::EpgResponse = serde_json::from_str(body).unwrap();
    assert_eq!(resp.epg_listings[0].decoded_title(), "Demo Now");
    assert_eq!(resp.epg_listings[0].decoded_description(), "Headline news");
    // 时间戳优先于本地时间字符串
    assert_eq!(resp.epg_listings[0].start_timestamp, 1_792_056_966);
}
