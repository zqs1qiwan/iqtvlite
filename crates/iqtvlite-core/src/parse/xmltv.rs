//! XMLTV 流式解析器。
//!
//! # 为什么必须流式
//!
//! 一个中等规模的 Xtream 供应商的 `xmltv.php` 输出常达 **50–200 MB**，
//! 包含 10 万+ 条节目。用 DOM 解析会直接 OOM。
//! → 本解析器**逐事件**消费，只保留当前正在构建的节点。
//!
//! # 与 Xtream JSON API 的关键区别
//!
//! **Xtream JSON 的 `title`/`description` 是 base64 编码的**，
//! 而 **XMLTV 是明文**。两者不要混淆（见 `parse::xtream`）。

use std::io::BufRead;

use quick_xml::events::Event;
use quick_xml::Reader;

use crate::error::Result;
use crate::timeutil::parse_xmltv_time;

/// 解析统计。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct XmltvStats {
    pub channels: u32,
    pub programmes: u32,
    /// 时间无法解析而跳过的节目
    pub skipped_bad_time: u32,
    /// 缺少 `channel` 属性的节目
    pub skipped_no_channel: u32,
}

/// 解析过程中的一个指南频道。
#[derive(Debug, Clone, Default)]
pub struct XmltvChannel {
    /// `<channel id="">`
    pub id: String,
    pub display_names: Vec<String>,
    pub icon_urls: Vec<String>,
}

/// 解析过程中的一个节目。
#[derive(Debug, Clone, Default)]
pub struct XmltvProgramme {
    /// 节目开始时间（Unix 秒，UTC）
    pub start_ts: i64,
    /// 节目结束时间（Unix 秒，UTC）
    pub stop_ts: i64,
    /// `<programme channel="">`
    pub channel: String,
    pub title: Option<String>,
    pub sub_title: Option<String>,
    pub description: Option<String>,
    pub category: Option<String>,
    pub episode_num: Option<String>,
    pub icon_urls: Vec<String>,
    pub credits: Option<String>,
    pub previously_shown: bool,
    /// `clumpidx` —— **非 DTD 属性**，但真实存在：同一时段的多个节目（新闻/天气）用它排序
    pub clumpidx: Option<String>,
    /// `catchup-id` —— **非 DTD 属性**，Kodi 会读
    pub catchup_id: Option<String>,
}

/// 流式解析 XMLTV。
///
/// `on_channel` / `on_programme` 会被就地调用，解析器不会保留已完成的数据。
pub fn parse_reader<R, FC, FP>(
    reader: R,
    mut on_channel: FC,
    mut on_programme: FP,
) -> Result<XmltvStats>
where
    R: BufRead,
    FC: FnMut(XmltvChannel) -> Result<()>,
    FP: FnMut(XmltvProgramme) -> Result<()>,
{
    let mut reader = Reader::from_reader(reader);
    reader.config_mut().trim_text(true);
    reader.config_mut().check_end_names = false;

    let mut stats = XmltvStats::default();

    // 当前正在构建的节点
    let mut channel: Option<XmltvChannel> = None;
    let mut programme: Option<XmltvProgramme> = None;
    // 当前文本节点的缓冲（复用，避免每节点一次分配）
    let mut text_buf = String::new();
    // 当前所处的文本节点名
    let mut in_text_node: Option<String> = None;
    // credits 子树用原始 XML 片段保存
    let mut credits_buf: String = String::new();
    let mut in_credits = false;

    // quick-xml 0.42：BufRead 源必须用 `read_event_into(&mut buf)`，
    // 事件从 buf 借用 —— 所以 buf 必须活过整个事件处理过程。
    let mut buf: Vec<u8> = Vec::new();

    loop {
        let event = reader.read_event_into(&mut buf)?;
        match event {
            Event::Start(ref e) => {
                if in_credits {
                    write_start_tag(&mut credits_buf, e);
                }
                handle_start(
                    e,
                    false,
                    &mut text_buf,
                    &mut in_text_node,
                    &mut credits_buf,
                    &mut in_credits,
                    &mut channel,
                    &mut programme,
                    &mut stats,
                );
            }
            Event::Empty(ref e) => {
                if in_credits {
                    write_start_tag(&mut credits_buf, e);
                    credits_buf.push_str("/>");
                }
                handle_start(
                    e,
                    true,
                    &mut text_buf,
                    &mut in_text_node,
                    &mut credits_buf,
                    &mut in_credits,
                    &mut channel,
                    &mut programme,
                    &mut stats,
                );
            }
            Event::Text(ref e) => {
                if in_credits {
                    let t = e.xml_content(quick_xml::XmlVersion::Explicit1_0);
                    credits_buf.push_str(&t);
                } else if in_text_node.is_some() {
                    let t = e.xml_content(quick_xml::XmlVersion::Explicit1_0);
                    text_buf.push_str(&t);
                }
            }
            Event::CData(ref e) => {
                let t = e.borrow().into_inner();
                if in_credits {
                    credits_buf.push_str(&t);
                } else if in_text_node.is_some() {
                    text_buf.push_str(&t);
                }
            }
            Event::End(ref e) => {
                let name = e.name().as_ref().to_ascii_lowercase();
                if in_credits {
                    if name == "credits" {
                        credits_buf.push_str("</credits>");
                        if let Some(p) = programme.as_mut() {
                            p.credits = Some(std::mem::take(&mut credits_buf));
                        }
                        in_credits = false;
                    } else {
                        credits_buf.push_str(&format!("</{name}>"));
                    }
                } else {
                    finish_node(
                        &name,
                        &mut text_buf,
                        &mut in_text_node,
                        &mut channel,
                        &mut programme,
                    );
                }

                if name == "channel" {
                    if let Some(c) = channel.take() {
                        stats.channels += 1;
                        on_channel(c)?;
                    }
                } else if name == "programme" {
                    if let Some(p) = programme.take() {
                        if p.channel.is_empty() {
                            stats.skipped_no_channel += 1;
                        } else {
                            stats.programmes += 1;
                            on_programme(p)?;
                        }
                    }
                }
            }
            Event::Eof => break,
            _ => {}
        }
    }

    Ok(stats)
}

/// 把一个开始标签写进 XML 片段缓冲（用于捕获 `<credits>` 子树）。
fn write_start_tag(buf: &mut String, e: &quick_xml::events::BytesStart<'_>) {
    buf.push('<');
    buf.push_str(e.name().as_ref());
    for a in e.attributes().flatten() {
        let key = a.key.as_ref();
        let val = a
            .normalized_value(quick_xml::XmlVersion::Explicit1_0)
            .unwrap_or_default();
        buf.push_str(&format!(" {key}=\"{val}\""));
    }
    buf.push('>');
}

/// 处理一个开始标签。
#[allow(clippy::too_many_arguments)]
fn handle_start(
    e: &quick_xml::events::BytesStart<'_>,
    is_empty: bool,
    text_buf: &mut String,
    in_text_node: &mut Option<String>,
    credits_buf: &mut String,
    in_credits: &mut bool,
    channel: &mut Option<XmltvChannel>,
    programme: &mut Option<XmltvProgramme>,
    stats: &mut XmltvStats,
) {
    let name = e.name().as_ref().to_ascii_lowercase();

    match name.as_str() {
        "channel" => {
            let id = attr(e, "id").unwrap_or_default();
            *channel = Some(XmltvChannel {
                id,
                ..Default::default()
            });
        }
        "programme" => {
            let start_raw = attr(e, "start").unwrap_or_default();
            let stop_raw = attr(e, "stop").unwrap_or_default();
            let ch = attr(e, "channel").unwrap_or_default();
            // `clumpidx` 与 `catchup-id` 是 **<programme> 的属性**（非 DTD，但真实存在）
            let clump = attr(e, "clumpidx");
            let catchup_id = attr(e, "catchup-id");

            let start = parse_xmltv_time(&start_raw);
            let stop = if stop_raw.is_empty() {
                // `<programme>` 可能缺 `stop` → 用开始时间兜底，由上层用下一个节目推断
                start.map(|s| s.unix)
            } else {
                parse_xmltv_time(&stop_raw).map(|t| t.unix)
            };

            match (start, stop) {
                (Some(s), Some(st)) => {
                    *programme = Some(XmltvProgramme {
                        start_ts: s.unix,
                        stop_ts: st,
                        channel: ch,
                        clumpidx: clump,
                        catchup_id,
                        ..Default::default()
                    });
                }
                _ => {
                    *programme = None;
                    stats.skipped_bad_time += 1;
                    if ch.is_empty() {
                        stats.skipped_no_channel += 1;
                    }
                }
            }
        }
        "display-name" => {
            if !is_empty {
                *in_text_node = Some("display-name".into());
                text_buf.clear();
            }
        }
        "icon" => {
            if let Some(src) = attr(e, "src") {
                if let Some(c) = channel.as_mut() {
                    c.icon_urls.push(src);
                } else if let Some(p) = programme.as_mut() {
                    p.icon_urls.push(src);
                }
            }
        }
        "clumpidx" => {
            if let Some(v) = attr(e, "clumpidx") {
                if let Some(p) = programme.as_mut() {
                    p.clumpidx = Some(v);
                }
            }
        }
        "catchup-id" => {
            if let Some(v) = attr(e, "catchup-id") {
                if let Some(p) = programme.as_mut() {
                    p.catchup_id = Some(v);
                }
            }
        }
        "previously-shown" => {
            // 空元素（EMPTY 事件）：出现即表示「重播」
            if let Some(p) = programme.as_mut() {
                p.previously_shown = true;
            }
        }
        "credits" => {
            if !is_empty {
                *in_credits = true;
                credits_buf.clear();
                write_start_tag(credits_buf, e);
            }
        }
        _ => {
            // 需要取文本的叶子节点
            if !is_empty
                && matches!(
                    name.as_str(),
                    "title"
                        | "sub-title"
                        | "desc"
                        | "description"
                        | "category"
                        | "episode-num"
                        | "value"
                        | "review"
                        | "date"
                )
            {
                *in_text_node = Some(name.clone());
                text_buf.clear();
            }
        }
    }

    if is_empty {
        // Empty 事件没有对应的 End 事件，需要自己收尾
        finish_node(&name, text_buf, in_text_node, channel, programme);
    }
}

/// 把已收集的文本写入对应的字段。
fn finish_node(
    name: &str,
    text_buf: &mut String,
    in_text_node: &mut Option<String>,
    channel: &mut Option<XmltvChannel>,
    programme: &mut Option<XmltvProgramme>,
) {
    if in_text_node.as_deref() != Some(name) {
        return;
    }
    let text = std::mem::take(text_buf);
    *in_text_node = None;
    if text.trim().is_empty() {
        return;
    }

    match name {
        "display-name" => {
            if let Some(c) = channel.as_mut() {
                c.display_names.push(text);
            }
        }
        "title" => {
            if let Some(p) = programme.as_mut() {
                p.title = Some(text);
            }
        }
        "sub-title" => {
            if let Some(p) = programme.as_mut() {
                p.sub_title = Some(text);
            }
        }
        "desc" | "description" => {
            if let Some(p) = programme.as_mut() {
                p.description = Some(text);
            }
        }
        "category" => {
            if let Some(p) = programme.as_mut() {
                // 多个 `<category>` → 用 ` / ` 连接
                p.category = Some(match p.category.take() {
                    Some(prev) => format!("{prev} / {text}"),
                    None => text,
                });
            }
        }
        "episode-num" => {
            if let Some(p) = programme.as_mut() {
                p.episode_num = Some(text);
            }
        }
        _ => {}
    }
}

/// 读取 XML 属性。
fn attr(e: &quick_xml::events::BytesStart<'_>, key: &str) -> Option<String> {
    for a in e.attributes().flatten() {
        if a.key.as_ref() == key {
            return Some(
                a.normalized_value(quick_xml::XmlVersion::Explicit1_0)
                    .unwrap_or_default()
                    .to_string(),
            );
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv source-info-url="http://example.com/" generator-info-name="test">
  <channel id="bbc.one.uk">
    <display-name lang="en">BBC One</display-name>
    <display-name>BBC One HD</display-name>
    <icon src="http://logos/bbc.png" />
  </channel>
  <channel id="itv.uk">
    <display-name>ITV</display-name>
  </channel>
  <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="bbc.one.uk">
    <title lang="en">The News</title>
    <desc lang="en">Headline news</desc>
    <category>News</category>
    <episode-num system="xmltv_ns">0.1.0/1</episode-num>
    <previously-shown start="20240101100000 +0000" />
    <icon src="http://img/news.png" />
  </programme>
  <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="itv.uk">
    <title>Emmerdale</title>
    <credits><director>Jane Doe</director></credits>
  </programme>
</tv>
"#;

    #[test]
    fn parses_channels_and_programmes() {
        let mut channels = Vec::new();
        let mut programmes = Vec::new();
        let stats = parse_reader(
            SAMPLE.as_bytes(),
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

        assert_eq!(stats.channels, 2);
        assert_eq!(stats.programmes, 2);
        assert_eq!(channels[0].id, "bbc.one.uk");
        assert_eq!(channels[0].display_names.len(), 2);
        assert_eq!(channels[0].icon_urls.len(), 1);
    }

    #[test]
    fn parses_programme_fields() {
        let mut programmes = Vec::new();
        parse_reader(
            SAMPLE.as_bytes(),
            |_| Ok(()),
            |p| {
                programmes.push(p);
                Ok(())
            },
        )
        .unwrap();

        let p = &programmes[0];
        assert_eq!(p.title.as_deref(), Some("The News"));
        assert_eq!(p.description.as_deref(), Some("Headline news"));
        assert_eq!(p.category.as_deref(), Some("News"));
        assert_eq!(p.episode_num.as_deref(), Some("0.1.0/1"));
        assert!(p.previously_shown);
        assert_eq!(p.icon_urls.len(), 1);
        assert_eq!(p.channel, "bbc.one.uk");
        assert_eq!(p.stop_ts - p.start_ts, 3600);
    }

    #[test]
    fn parses_credits() {
        let mut programmes = Vec::new();
        parse_reader(
            SAMPLE.as_bytes(),
            |_| Ok(()),
            |p| {
                programmes.push(p);
                Ok(())
            },
        )
        .unwrap();
        let credits = programmes[1].credits.as_ref().unwrap();
        assert!(credits.contains("Jane Doe"), "实际: {credits}");
    }

    #[test]
    fn skips_programmes_with_bad_time() {
        let input = r#"<tv>
          <programme start="garbage" stop="garbage" channel="x"><title>T</title></programme>
          <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="x"><title>OK</title></programme>
        </tv>"#;
        let mut programmes = Vec::new();
        let stats = parse_reader(
            input.as_bytes(),
            |_| Ok(()),
            |p| {
                programmes.push(p);
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(stats.programmes, 1);
        assert_eq!(stats.skipped_bad_time, 1);
        assert_eq!(programmes[0].title.as_deref(), Some("OK"));
    }

    #[test]
    fn handles_empty_self_closing_display_name() {
        let input = r#"<tv><channel id="x"><display-name/></channel></tv>"#;
        let mut channels = Vec::new();
        parse_reader(
            input.as_bytes(),
            |c| {
                channels.push(c);
                Ok(())
            },
            |_| Ok(()),
        )
        .unwrap();
        assert_eq!(channels.len(), 1);
        assert!(channels[0].display_names.is_empty());
    }

    #[test]
    fn captures_non_standard_attributes() {
        let input = r#"<tv>
          <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="x" clumpidx="1">
            <title>T</title>
          </programme>
        </tv>"#;
        let mut programmes = Vec::new();
        parse_reader(
            input.as_bytes(),
            |_| Ok(()),
            |p| {
                programmes.push(p);
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(programmes[0].clumpidx.as_deref(), Some("1"));
    }
}
