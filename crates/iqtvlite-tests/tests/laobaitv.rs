//! 老白直播真实播放列表的解析回归测试。
//!
//! 样本来自用户提供的两个 URL（2026-11-16 抓取）：
//!   - https://lbzs.laobaitv.net  —— 真实 M3U（UTF-8），含 `x-tvg-url`
//!   - http://iptv.laobaitv.net   —— HTML 落地页，需要提取真实播放列表地址
//!
//! 这里固化的是**格式特征**而非完整内容（播放列表含有时效 token，不能入库）。

use iqtvlite_core::parse::m3u;

/// 老白直播的 M3U 片段（真实格式，含 Kodi 风格的 `|User-Agent=VLC` 后缀、
/// 中文 tvg-id、中文 group-title、GBK/UTF-8 混合可能、x-tvg-url 头）。
const SAMPLE: &str = r#"#EXTM3U x-tvg-url="https://epg.laobaitv.net"
#####央视频道#####
#EXTINF:-1 tvg-id="CCTV1" tvg-name="CCTV-1 综合" tvg-logo="https://logo.laobaitv.net/CCTV-1 综合" http-user-agent="VLC" group-title="央视频道",CCTV-1 综合
http://audiovisual.vsd.gehua.net.cn/live/CCTV1HD_1200.m3u8?sign=test&auth_key=1794828349-0-0-36d2afe74b6b43445ed6160c60079d6d|User-Agent=VLC
#EXTINF:-1 tvg-id="CCTV2" tvg-name="CCTV-2 财经" group-title="央视频道",CCTV-2 财经
http://example.com/cctv2.m3u8|User-Agent=VLC
#####卫视频道#####
#EXTINF:-1 tvg-id="HunanTV" group-title="卫视频道",湖南卫视
http://example.com/hunan.m3u8
#EXTINF:-1 tvg-id="CloudflareTV" group-title="老白TV-其他直播源",Cloudflare TV
https://cloudflare.tv/hls/live.m3u8
# 注释掉的失效频道（以 # 开头，应被忽略）
#EXTINF:-1 tvg-id="Dead" group-title="财经",第一财经
#https://live.laobaitv.net/smg/dycj|User-Agent=VLC
"#;

#[test]
fn laobaitv_header_epg_url_is_parsed() {
    let p = m3u::parse(SAMPLE).unwrap();
    assert_eq!(
        p.header.epg_urls,
        vec!["https://epg.laobaitv.net".to_string()],
        "x-tvg-url 必须被解析（EPG 源）"
    );
}

#[test]
fn laobaitv_channels_are_parsed() {
    let p = m3u::parse(SAMPLE).unwrap();
    // 4 个有效频道（失效的那条 URL 被注释掉 → 孤儿条目被跳过）
    assert_eq!(p.entries.len(), 4, "实际: {:?}", p.entries.iter().map(|e| &e.name).collect::<Vec<_>>());

    let cctv1 = p
        .entries
        .iter()
        .find(|e| e.tvg_id.as_deref() == Some("CCTV1"))
        .expect("CCTV1 应存在");
    assert_eq!(cctv1.name.as_deref(), Some("CCTV-1 综合"));
    assert_eq!(cctv1.groups, vec!["央视频道"]);
    assert!(cctv1.primary_url().unwrap().contains("CCTV1HD_1200.m3u8"), "URL 含 |User-Agent 后缀，解析时需保留原样由播放器剥离");
    assert_eq!(cctv1.user_agent.as_deref(), Some("VLC"), "http-user-agent 属性应被提升");
}

#[test]
fn laobaitv_groups_are_chinese() {
    let p = m3u::parse(SAMPLE).unwrap();
    let mut groups: Vec<String> = p
        .entries
        .iter()
        .flat_map(|e| e.groups.iter().cloned())
        .collect();
    groups.sort();
    groups.dedup();
    assert!(groups.contains(&"央视频道".to_string()));
    assert!(groups.contains(&"卫视频道".to_string()));
    assert!(groups.contains(&"老白TV-其他直播源".to_string()));
}

#[test]
fn commented_out_stream_is_skipped() {
    let p = m3u::parse(SAMPLE).unwrap();
    assert!(
        !p.entries.iter().any(|e| e.name.as_deref() == Some("第一财经")),
        "以 # 开头的 URL 行是注释，对应的 #EXTINF 应作为孤儿条目被跳过"
    );
}

#[test]
fn playback_url_suffix_preserved_for_player() {
    let p = m3u::parse(SAMPLE).unwrap();
    let cctv1 = p.entries.iter().find(|e| e.tvg_id.as_deref() == Some("CCTV1")).unwrap();
    // Kodi 风格的 |User-Agent=VLC 后缀必须保留 —— Kotlin 侧播放时会 split("|") 并剥离
    assert!(cctv1.primary_url().unwrap().contains("|User-Agent=VLC"));
}
