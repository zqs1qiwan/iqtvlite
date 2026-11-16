//! M3U / M3U+ 解析器。
//!
//! # 设计要点（全部来自 `docs/09-protocols.md` §1 的实证结论）
//!
//! 1. **显示名 = 最后一个逗号之后**。
//!    按第一个逗号切分会破坏 `tvg-name="Harbour News, Coast"`。
//! 2. **逗号必须在引号外找**。属性值内部可以含逗号。
//! 3. **属性值支持三种引号形态**：`"..."` → `'...'` → 裸值。
//! 4. **`group-title` 是权威分类**，可多组（`;` 分隔）。
//! 5. **`#EXTGRP:` 是「开始指令」**（Kodi 语义）：为后续条目设置默认分组，
//!    直到下一个 `#EXTGRP:` 或条目自带 `group-title`。
//! 6. **`#EXTINF` 可省略**（裸 URL 列表）。
//! 7. **未知属性必须保留**在 `extras` 里 —— 供应商生态的私有属性太多，丢了就再也拿不回来。
//! 8. `tvg-shift` 是 **EPG 时间偏移**，不是归档窗口。
//! 9. `#EXTVLCOPT:` / `#EXTHTTP:` 里的 UA / Referer 要提升为真实 HTTP 头。
//!
//! # 性能
//!
//! 15 万频道的播放列表必须在 30 秒内导入完成 → 解析器是**逐行流式**的，
//! 不在内存里保留原始文本。调用方用 [`parse_reader`] 边读边消费。

use std::collections::HashMap;
use std::io::BufRead;

use crate::error::{IptvError, ParseErrorKind, Result};
use crate::model::catchup::{CatchupConfig, CatchupType};

/// `#EXTM3U` 头部的属性。
#[derive(Debug, Clone, Default)]
pub struct M3uHeader {
    /// EPG 源地址。`url-tvg` 与 `x-tvg-url` 是同义词，都读。
    /// 多个地址可用 `,` 或 `;` 分隔。
    pub epg_urls: Vec<String>,
    /// 头部的默认 catch-up 配置（频道可覆盖）
    pub catchup_type: Option<CatchupType>,
    pub catchup_source: Option<String>,
    pub catchup_days: Option<u32>,
    pub catchup_time: Option<u32>,
    pub catchup_correction: Option<f64>,
    pub catchup_enable: Option<bool>,
    pub timeshift: Option<String>,
    /// EPG 时间偏移（小时）
    pub tvg_shift: Option<f64>,
    pub user_agent: Option<String>,
    pub referrer: Option<String>,
    /// 其他未识别的头部属性
    pub extras: HashMap<String, String>,
}

/// 一条 M3U 条目。
#[derive(Debug, Clone, Default)]
pub struct M3uEntry {
    /// 流地址。第一个是主地址，其余是备用线路。
    pub urls: Vec<String>,
    /// 显示名
    pub name: Option<String>,
    /// `#EXTINF` 的时长（秒）。直播通常是 `-1`。
    pub duration: Option<f64>,
    pub tvg_id: Option<String>,
    pub tvg_name: Option<String>,
    pub tvg_logo: Option<String>,
    pub tvg_language: Option<String>,
    pub tvg_country: Option<String>,
    pub tvg_url: Option<String>,
    pub tvg_chno: Option<String>,
    /// `tvg-rec`：`1` = 有归档；> 1 = 天数
    pub tvg_rec: Option<i64>,
    /// `tvg-shift` —— EPG 时间偏移（小时）
    pub tvg_shift: Option<f64>,
    /// `group-title` 原始值
    pub group_title: Option<String>,
    /// 展开后的分组列表（`;` 分隔）
    pub groups: Vec<String>,
    pub radio: bool,
    pub adult: bool,
    /// `audio-track`，形如 `"eng,rus"`，首个为默认
    pub audio_track: Option<String>,
    /// 归档配置
    pub catchup: Option<CatchupConfig>,
    /// `user-agent` / `http-user-agent`
    pub user_agent: Option<String>,
    /// `referrer` / `http-referrer`
    pub referrer: Option<String>,
    /// `#KODIPROP:` 属性
    pub kodi_props: HashMap<String, String>,
    /// `#EXTVLCOPT:` 属性
    pub vlc_options: HashMap<String, String>,
    /// 未识别的属性 —— **必须保留**
    pub extras: HashMap<String, String>,
}

impl M3uEntry {
    /// 主地址。
    pub fn primary_url(&self) -> Option<&str> {
        self.urls.first().map(String::as_str)
    }
}

/// 解析统计。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct M3uStats {
    pub entries: usize,
    /// 被跳过的行（有 `#EXTINF` 但没有 URL）
    pub skipped: usize,
    /// 未识别的 `#EXTINF` 以外的可疑行
    pub malformed: usize,
}

/// 解析结果。
#[derive(Debug, Clone, Default)]
pub struct M3uPlaylist {
    pub header: M3uHeader,
    pub entries: Vec<M3uEntry>,
    pub stats: M3uStats,
}

/// 解析整个播放列表文本。
pub fn parse(input: &str) -> Result<M3uPlaylist> {
    let input = input.strip_prefix('\u{feff}').unwrap_or(input);
    let mut entries = Vec::new();
    let (header, stats) = parse_reader(input.as_bytes(), |entry| {
        entries.push(entry);
        Ok(())
    })?;
    Ok(M3uPlaylist {
        header,
        entries,
        stats,
    })
}

/// 流式解析：逐条产出条目。
///
/// 这是导入 15 万频道播放列表的入口 —— 内存占用与播放列表大小无关。
///
/// 返回 `(头部, 统计)`。
pub fn parse_reader<R: BufRead, F>(reader: R, mut on_entry: F) -> Result<(M3uHeader, M3uStats)>
where
    F: FnMut(M3uEntry) -> Result<()>,
{
    let mut stats = M3uStats::default();
    let mut header = M3uHeader::default();

    // 待处理的条目状态
    let mut pending: Option<M3uEntry> = None;
    // 当前条目是否来自 `#EXTINF` —— 决定后续的裸 URL 是「备用线路」还是「独立条目」
    let mut pending_from_extinf = false;
    // `#EXTGRP:` 指令设置的默认分组
    let mut extgrp_group: Option<String> = None;

    let mut line = String::new();
    let mut reader = reader;
    loop {
        line.clear();
        let n = read_line(&mut reader, &mut line)?;
        if n == 0 {
            break;
        }
        let trimmed = line.trim_end_matches(['\r', '\n']).trim();
        if trimmed.is_empty() {
            continue;
        }

        if let Some(rest) = trimmed.strip_prefix("#EXTM3U") {
            parse_header_attrs(rest, &mut header);
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("#EXTINF:") {
            let (duration, attrs, name) = parse_extinf(rest);
            let mut entry = M3uEntry {
                duration,
                name,
                ..Default::default()
            };
            apply_entry_attrs(&attrs, &mut entry);
            // 上一条条目在此结束
            if let Some(mut prev) = pending.take() {
                if prev.urls.is_empty() {
                    // 有 #EXTINF 但没有 URL → 跳过（孤儿条目）
                    stats.skipped += 1;
                } else {
                    finalize_entry(&mut prev, &header, extgrp_group.as_deref());
                    stats.entries += 1;
                    on_entry(prev)?;
                }
            }
            pending = Some(entry);
            pending_from_extinf = true;
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("#EXTGRP:") {
            // Kodi 语义：开始指令，后续条目若无 group-title 则使用它
            extgrp_group = Some(rest.trim().to_string());
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("#EXTVLCOPT:") {
            if let Some((k, v)) = rest.split_once('=') {
                let key = k.trim().to_ascii_lowercase();
                let value = v.trim().to_string();
                match key.as_str() {
                    "http-user-agent" | "user-agent" => {
                        if let Some(entry) = pending.as_mut() {
                            entry.user_agent = Some(value.clone());
                        }
                    }
                    "http-referrer" | "referrer" => {
                        if let Some(entry) = pending.as_mut() {
                            entry.referrer = Some(value.clone());
                        }
                    }
                    _ => {
                        if let Some(entry) = pending.as_mut() {
                            entry.vlc_options.insert(key, value);
                        }
                    }
                }
            }
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("#EXTHTTP:") {
            if let Some((k, v)) = rest.split_once('=') {
                let key = k.trim().to_ascii_lowercase();
                let value = v.trim().to_string();
                if let Some(entry) = pending.as_mut() {
                    match key.as_str() {
                        "user-agent" => entry.user_agent = Some(value),
                        "referrer" | "referer" => entry.referrer = Some(value),
                        _ => {
                            entry.vlc_options.insert(key, value);
                        }
                    }
                }
            }
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("#KODIPROP:") {
            if let Some((k, v)) = rest.split_once('=') {
                if let Some(entry) = pending.as_mut() {
                    entry
                        .kodi_props
                        .insert(k.trim().to_string(), v.trim().to_string());
                }
            }
            continue;
        }
        if trimmed.starts_with('#') {
            // 其他指令（#EXTM3U-URL、#PLAYLIST 等）—— 忽略
            continue;
        }

        // ---- 这是一个 URL 行 ----
        if pending_from_extinf {
            // 来自 #EXTINF 的条目：连续的 URL 是**同一频道的备用线路**
            if let Some(entry) = pending.as_mut() {
                entry.urls.push(trimmed.to_string());
            }
            continue;
        }

        // 裸 URL 列表（无 #EXTINF）→ 每行一个独立条目
        if let Some(mut prev) = pending.take() {
            finalize_entry(&mut prev, &header, extgrp_group.as_deref());
            stats.entries += 1;
            on_entry(prev)?;
        }
        let mut entry = M3uEntry {
            urls: vec![trimmed.to_string()],
            ..Default::default()
        };
        finalize_entry(&mut entry, &header, extgrp_group.as_deref());
        stats.entries += 1;
        on_entry(entry)?;
    }

    // 末尾残留的条目
    if let Some(mut entry) = pending.take() {
        if entry.urls.is_empty() {
            // 有 #EXTINF 但没有有效 URL（例如老白直播里用 `#http://...` 注释掉的失效频道）
            stats.skipped += 1;
        } else {
            finalize_entry(&mut entry, &header, extgrp_group.as_deref());
            stats.entries += 1;
            on_entry(entry)?;
        }
    }

    Ok((header, stats))
}

/// 读取一行（处理 UTF-8 与 \r\n）。
fn read_line<R: BufRead>(reader: &mut R, buf: &mut String) -> Result<usize> {
    let mut raw = Vec::new();
    let n = reader.read_until(b'\n', &mut raw)?;
    if n == 0 {
        return Ok(0);
    }
    buf.push_str(&String::from_utf8_lossy(&raw));
    Ok(n)
}

/// 解析 `#EXTM3U` 头部属性。
fn parse_header_attrs(rest: &str, header: &mut M3uHeader) {
    for (key, value) in parse_attrs(rest) {
        match key.as_str() {
            "url-tvg" | "x-tvg-url" => {
                for url in value.split([';', ',']) {
                    let url = url.trim();
                    if !url.is_empty() && !header.epg_urls.iter().any(|u| u == url) {
                        header.epg_urls.push(url.to_string());
                    }
                }
            }
            "catchup" | "catchup-type" => {
                header.catchup_type = CatchupType::from_attr(&value).or(header.catchup_type)
            }
            "catchup-source" => header.catchup_source = Some(value),
            "catchup-days" => header.catchup_days = value.parse().ok(),
            "catchup-time" => header.catchup_time = value.parse().ok(),
            "catchup-correction" => header.catchup_correction = value.parse().ok(),
            "catchup-enable" => header.catchup_enable = parse_bool(&value),
            "timeshift" => header.timeshift = Some(value),
            "tvg-shift" => header.tvg_shift = value.parse().ok(),
            "user-agent" | "http-user-agent" => header.user_agent = Some(value),
            "referrer" | "http-referrer" => header.referrer = Some(value),
            _ => {
                header.extras.insert(key, value);
            }
        }
    }
}

/// 解析 `#EXTINF:` 剩余部分。
///
/// 返回 `(时长, 属性列表, 显示名)`。
fn parse_extinf(rest: &str) -> (Option<f64>, Vec<(String, String)>, Option<String>) {
    // 找到**引号外**的第一个逗号 —— 显示名是它之后的部分
    let split = find_unquoted(rest, b',').unwrap_or(rest.len());
    let (left, right) = rest.split_at(split);
    let name = right.get(1..).map(str::trim).filter(|s| !s.is_empty());

    // left = "<duration> <attrs...>"
    let left = left.trim();
    let (duration_str, attrs_str) = match left.find(char::is_whitespace) {
        Some(idx) => (&left[..idx], &left[idx..]),
        None => (left, ""),
    };
    let duration = duration_str.trim().parse::<f64>().ok();
    let attrs = parse_attrs(attrs_str);
    (duration, attrs, name.map(str::to_string))
}

/// 把条目属性应用到条目上。
fn apply_entry_attrs(attrs: &[(String, String)], entry: &mut M3uEntry) {
    for (key, value) in attrs {
        match key.as_str() {
            "tvg-id" => entry.tvg_id = Some(value.clone()),
            "tvg-name" => entry.tvg_name = Some(value.clone()),
            "tvg-logo" | "logo" | "tvg-icon" => entry.tvg_logo = Some(value.clone()),
            "tvg-language" => entry.tvg_language = Some(value.clone()),
            "tvg-country" => entry.tvg_country = Some(value.clone()),
            "tvg-url" => entry.tvg_url = Some(value.clone()),
            "tvg-chno" | "ch-number" | "tvg-ch" | "channel-id" | "tvh-chnum" => {
                entry.tvg_chno = Some(value.clone())
            }
            "tvg-rec" => entry.tvg_rec = value.parse().ok(),
            "tvg-shift" => entry.tvg_shift = value.parse().ok(),
            "group-title" => entry.group_title = Some(value.clone()),
            "radio" => entry.radio = parse_bool(value).unwrap_or(false),
            "adult" | "parent-code" => entry.adult = parse_bool(value).unwrap_or(false),
            "audio-track" => entry.audio_track = Some(value.clone()),
            "catchup" | "catchup-type" | "catchup-mode" => {
                entry.catchup = Some(CatchupConfig {
                    catchup_type: CatchupType::from_attr(value).unwrap_or_default(),
                    ..Default::default()
                })
            }
            "catchup-source" | "catchup-url" => {
                let cfg = entry.catchup.get_or_insert_with(Default::default);
                cfg.source = Some(value.clone());
            }
            "catchup-days" | "catchup-days-max" | "catchup-range" => {
                let cfg = entry.catchup.get_or_insert_with(Default::default);
                cfg.days = value.parse().ok();
            }
            "catchup-time" => {
                let cfg = entry.catchup.get_or_insert_with(Default::default);
                cfg.time_secs = value.parse().ok();
            }
            "catchup-correction" => {
                let cfg = entry.catchup.get_or_insert_with(Default::default);
                cfg.correction_hours = value.parse().ok();
            }
            "catchup-enable" => {
                let cfg = entry.catchup.get_or_insert_with(Default::default);
                cfg.catchup_type = if parse_bool(value) == Some(false) {
                    CatchupType::Disabled
                } else {
                    cfg.catchup_type
                };
            }
            "timeshift" => {
                let cfg = entry.catchup.get_or_insert_with(Default::default);
                cfg.tvg_rec = value.parse().ok();
            }
            "user-agent" | "http-user-agent" => entry.user_agent = Some(value.clone()),
            "referrer" | "http-referrer" => entry.referrer = Some(value.clone()),
            _ => {
                entry.extras.insert(key.clone(), value.clone());
            }
        }
    }
}

/// 补全条目的派生字段：分组、catch-up 继承。
fn finalize_entry(entry: &mut M3uEntry, header: &M3uHeader, extgrp_group: Option<&str>) {
    // 分组：`group-title` 权威；否则用 `#EXTGRP` 指令的默认值
    if let Some(gt) = &entry.group_title {
        entry.groups = gt
            .split(';')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect();
    } else if let Some(g) = extgrp_group {
        if !g.is_empty() {
            entry.groups = vec![g.to_string()];
        }
    }

    // catch-up 继承头部配置
    if entry.catchup.is_none() {
        if header.catchup_type.is_some()
            || header.catchup_source.is_some()
            || header.catchup_days.is_some()
            || header.timeshift.is_some()
        {
            entry.catchup = Some(CatchupConfig {
                catchup_type: header.catchup_type.unwrap_or_default(),
                days: header.catchup_days,
                time_secs: header.catchup_time,
                source: header.catchup_source.clone(),
                correction_hours: header.catchup_correction,
                tvg_rec: None,
            });
        }
    } else if let Some(cfg) = entry.catchup.as_mut() {
        // 频道未声明的字段从头部继承
        if cfg.days.is_none() {
            cfg.days = header.catchup_days;
        }
        if cfg.time_secs.is_none() {
            cfg.time_secs = header.catchup_time;
        }
        if cfg.source.is_none() {
            cfg.source = header.catchup_source.clone();
        }
        if cfg.correction_hours.is_none() {
            cfg.correction_hours = header.catchup_correction;
        }
    }

    // UA / Referer 继承
    if entry.user_agent.is_none() {
        entry.user_agent = header.user_agent.clone();
    }
    if entry.referrer.is_none() {
        entry.referrer = header.referrer.clone();
    }
    if entry.tvg_shift.is_none() {
        entry.tvg_shift = header.tvg_shift;
    }
}

/// 解析布尔属性：`1`/`true`/`yes` 为真。
fn parse_bool(value: &str) -> Option<bool> {
    match value.trim().to_ascii_lowercase().as_str() {
        "1" | "true" | "yes" | "y" => Some(true),
        "0" | "false" | "no" | "n" => Some(false),
        _ => None,
    }
}

/// 在字符串中找到**引号外**的第一个目标字节。
fn find_unquoted(s: &str, target: u8) -> Option<usize> {
    let b = s.as_bytes();
    let mut i = 0;
    let mut quote: Option<u8> = None;
    while i < b.len() {
        let c = b[i];
        match quote {
            Some(q) => {
                if c == q {
                    quote = None;
                }
            }
            None => {
                if c == b'"' || c == b'\'' {
                    quote = Some(c);
                } else if c == target {
                    return Some(i);
                }
            }
        }
        i += 1;
    }
    None
}

/// 解析属性列表。
///
/// 支持 `key="value"`、`key='value'`、`key=value` 三种形态。
fn parse_attrs(input: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let b = input.as_bytes();
    let mut i = 0;
    let mut key_start = 0;

    while i < b.len() {
        let c = b[i];
        if c == b'"' || c == b'\'' {
            // 跳过引号内容
            let q = c;
            i += 1;
            while i < b.len() && b[i] != q {
                i += 1;
            }
            i = i.saturating_add(1);
            continue;
        }
        if c == b'=' {
            let key = input[key_start..i].trim();
            if !key.is_empty() && !key.contains(char::is_whitespace) {
                // 解析值
                let mut j = i + 1;
                while j < b.len() && (b[j] == b' ' || b[j] == b'\t') {
                    j += 1;
                }
                let value = if j < b.len() && (b[j] == b'"' || b[j] == b'\'') {
                    let q = b[j];
                    j += 1;
                    let start = j;
                    while j < b.len() && b[j] != q {
                        j += 1;
                    }
                    let v = input[start..j.min(input.len())].to_string();
                    j = j.saturating_add(1);
                    v
                } else {
                    let start = j;
                    while j < b.len() && b[j] != b' ' && b[j] != b'\t' {
                        j += 1;
                    }
                    input[start..j].to_string()
                };
                out.push((key.to_string(), value));
                i = j;
                key_start = j;
                continue;
            }
            // 无效的 key（可能含空格）→ 跳过这个 '='
            i += 1;
            key_start = i;
            continue;
        }
        i += 1;
    }
    out
}

/// 便捷转换：把错误包成 `IptvError`。
pub fn parse_error(context: impl Into<String>, kind: ParseErrorKind) -> IptvError {
    IptvError::Parse {
        kind,
        context: context.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_basic_playlist() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1 tvg-id=\"ch1\" group-title=\"News\",CNN\n\
                     http://example.com/cnn\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries.len(), 1);
        let e = &p.entries[0];
        assert_eq!(e.name.as_deref(), Some("CNN"));
        assert_eq!(e.tvg_id.as_deref(), Some("ch1"));
        assert_eq!(e.groups, vec!["News".to_string()]);
        assert_eq!(e.primary_url(), Some("http://example.com/cnn"));
        assert_eq!(e.duration, Some(-1.0));
    }

    #[test]
    fn display_name_uses_last_comma() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1 tvg-name=\"Harbour News, Coast\",Harbour News\n\
                     http://x/y\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries[0].name.as_deref(), Some("Harbour News"));
        assert_eq!(
            p.entries[0].tvg_name.as_deref(),
            Some("Harbour News, Coast")
        );
    }

    #[test]
    fn comma_inside_quotes_does_not_split_name() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1 tvg-name=\"A, B, C\",Real Name\n\
                     http://x/y\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries[0].name.as_deref(), Some("Real Name"));
        assert_eq!(p.entries[0].tvg_name.as_deref(), Some("A, B, C"));
    }

    #[test]
    fn supports_three_quote_styles() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1 tvg-id=\"dq\" tvg-name='sq' tvg-logo=bare,Chan\n\
                     http://x/y\n";
        let p = parse(input).unwrap();
        let e = &p.entries[0];
        assert_eq!(e.tvg_id.as_deref(), Some("dq"));
        assert_eq!(e.tvg_name.as_deref(), Some("sq"));
        assert_eq!(e.tvg_logo.as_deref(), Some("bare"));
    }

    #[test]
    fn extgrp_is_a_begin_directive() {
        let input = "#EXTM3U\n\
                     #EXTGRP:Sports\n\
                     #EXTINF:-1,ESPN\n\
                     http://x/espn\n\
                     #EXTINF:-1,CNN\n\
                     http://x/cnn\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries[0].groups, vec!["Sports".to_string()]);
        // 没有 EXTGRP 之后的第二条 → 仍是 Sports（指令持续生效）
        assert_eq!(p.entries[1].groups, vec!["Sports".to_string()]);
    }

    #[test]
    fn group_title_overrides_extgrp() {
        let input = "#EXTM3U\n\
                     #EXTGRP:Sports\n\
                     #EXTINF:-1 group-title=\"News\",CNN\n\
                     http://x/cnn\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries[0].groups, vec!["News".to_string()]);
    }

    #[test]
    fn multi_group_semicolon_separated() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1 group-title=\"Sports;HD;4K\",ESPN\n\
                     http://x/espn\n";
        let p = parse(input).unwrap();
        assert_eq!(
            p.entries[0].groups,
            vec!["Sports".to_string(), "HD".to_string(), "4K".to_string()]
        );
    }

    #[test]
    fn header_epg_urls_support_both_names_and_separators() {
        let input = "#EXTM3U url-tvg=\"http://a/xml;http://b/xml\" x-tvg-url=\"http://c/xml\"\n";
        let p = parse(input).unwrap();
        assert_eq!(p.header.epg_urls.len(), 3);
        assert!(p.header.epg_urls.contains(&"http://c/xml".to_string()));
    }

    #[test]
    fn catchup_config_from_channel_overrides_header() {
        let input = "#EXTM3U catchup=\"shift\" catchup-days=\"7\"\n\
                     #EXTINF:-1 catchup=\"append\" catchup-source=\"?utc={utc}\",A\n\
                     http://x/a\n";
        let p = parse(input).unwrap();
        let cfg = p.entries[0].catchup.as_ref().unwrap();
        assert_eq!(cfg.catchup_type, CatchupType::Append);
        assert_eq!(cfg.source.as_deref(), Some("?utc={utc}"));
        // 未声明的天数从头部继承
        assert_eq!(cfg.days, Some(7));
    }

    #[test]
    fn catchup_inherited_from_header_when_absent() {
        let input = "#EXTM3U catchup=\"shift\" catchup-days=\"3\"\n\
                     #EXTINF:-1,A\nhttp://x/a\n";
        let p = parse(input).unwrap();
        let cfg = p.entries[0].catchup.as_ref().unwrap();
        assert_eq!(cfg.catchup_type, CatchupType::Shift);
        assert_eq!(cfg.days, Some(3));
    }

    #[test]
    fn extvlcopt_becomes_real_headers() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1,A\n\
                     #EXTVLCOPT:http-user-agent=MyUA/1.0\n\
                     #EXTVLCOPT:http-referrer=http://ref/\n\
                     http://x/a\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries[0].user_agent.as_deref(), Some("MyUA/1.0"));
        assert_eq!(p.entries[0].referrer.as_deref(), Some("http://ref/"));
    }

    #[test]
    fn unknown_attributes_are_preserved() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1 x-custom-thing=\"hello\" another=1,A\n\
                     http://x/a\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries[0].extras.get("x-custom-thing").unwrap(), "hello");
        assert_eq!(p.entries[0].extras.get("another").unwrap(), "1");
    }

    #[test]
    fn bare_url_list_is_accepted() {
        let input = "#EXTM3U\nhttp://x/a\nhttp://x/b\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries.len(), 2);
        assert_eq!(p.entries[0].primary_url(), Some("http://x/a"));
    }

    #[test]
    fn extinf_without_url_is_skipped() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1,Orphan\n\
                     #EXTINF:-1,Good\n\
                     http://x/good\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries.len(), 1);
        assert_eq!(p.entries[0].name.as_deref(), Some("Good"));
    }

    #[test]
    fn tvg_shift_is_epg_offset_not_archive() {
        let input = "#EXTM3U\n#EXTINF:-1 tvg-shift=\"2.5\",A\nhttp://x/a\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries[0].tvg_shift, Some(2.5));
    }

    #[test]
    fn bom_is_stripped() {
        let input = "\u{feff}#EXTM3U\n#EXTINF:-1,A\nhttp://x/a\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries.len(), 1);
    }

    #[test]
    fn crlf_line_endings() {
        let input = "#EXTM3U\r\n#EXTINF:-1,A\r\nhttp://x/a\r\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries.len(), 1);
        assert_eq!(p.entries[0].name.as_deref(), Some("A"));
    }

    #[test]
    fn multiple_urls_are_kept_in_order() {
        let input = "#EXTM3U\n\
                     #EXTINF:-1,A\n\
                     http://x/primary\n\
                     http://x/backup\n";
        let p = parse(input).unwrap();
        assert_eq!(p.entries[0].urls.len(), 2);
        assert_eq!(p.entries[0].primary_url(), Some("http://x/primary"));
    }
}
