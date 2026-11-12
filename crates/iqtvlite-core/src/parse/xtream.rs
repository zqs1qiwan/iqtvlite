//! Xtream Codes API —— 类型定义与 URL 构造。
//!
//! # 三个最容易踩的坑
//!
//! 1. **`get_short_episodes` / `action=get_episodes` 不存在**。
//!    剧集详情只有一个端点：`get_series_info`。
//! 2. **EPG 的 `title` / `description` 是 base64 编码的**（XMLTV 才是明文）。
//! 3. **面板从不返回完整 URL**，客户端自己拼。

use serde::{Deserialize, Serialize};

use crate::error::Result;
use crate::model::StreamFormat;

/// 登录响应。
#[derive(Debug, Clone, Deserialize)]
pub struct ProfileResponse {
    #[serde(default)]
    pub user_info: UserInfo,
    #[serde(default)]
    pub server_info: ServerInfo,
}

/// `user_info`。
///
/// ⚠️ **数字字段在 JSON 里都是字符串**。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct UserInfo {
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub auth: i32,
    #[serde(default)]
    pub status: String,
    /// 到期时间（Unix 秒，字符串）
    #[serde(default)]
    pub exp_date: String,
    #[serde(default)]
    pub is_trial: String,
    /// 当前占用连接数
    #[serde(default)]
    pub active_cons: String,
    #[serde(default)]
    pub max_connections: String,
    /// 允许的输出格式，如 `["m3u8","ts","rtmp"]`
    #[serde(default)]
    pub allowed_output_formats: Vec<String>,
}

/// `server_info`。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct ServerInfo {
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub port: String,
    #[serde(default)]
    pub https_port: String,
    #[serde(default)]
    pub server_protocol: String,
    #[serde(default)]
    pub rtmp_port: String,
    /// **时移 URL 换算必需** —— 时移起点是供应商本地墙钟时间
    #[serde(default)]
    pub timezone: String,
    #[serde(default)]
    pub timestamp_now: i64,
    #[serde(default)]
    pub time_now: String,
    #[serde(default)]
    pub xui: bool,
    #[serde(default)]
    pub version: String,
}

/// 分类。
///
/// 现代面板返回对象数组 `[{"category_id":"1","category_name":"News"}]`；
/// 经典形态是数组套数组 `[["1","News"]]`。**两种都支持。**
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(untagged)]
pub enum Category {
    Object {
        category_id: String,
        category_name: String,
        #[serde(default)]
        parent_id: i64,
    },
    Legacy(Vec<serde_json::Value>),
}

impl Category {
    pub fn id(&self) -> Option<String> {
        match self {
            Category::Object { category_id, .. } => Some(category_id.clone()),
            Category::Legacy(items) => items.first().and_then(|v| match v {
                serde_json::Value::String(s) => Some(s.clone()),
                serde_json::Value::Number(n) => Some(n.to_string()),
                _ => None,
            }),
        }
    }

    pub fn name(&self) -> Option<String> {
        match self {
            Category::Object { category_name, .. } => Some(category_name.clone()),
            Category::Legacy(items) => items.get(1).and_then(|v| match v {
                serde_json::Value::String(s) => Some(s.clone()),
                serde_json::Value::Number(n) => Some(n.to_string()),
                _ => None,
            }),
        }
    }
}

/// 直播频道。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct LiveStream {
    pub num: i64,
    pub name: String,
    #[serde(default)]
    pub stream_type: String,
    pub stream_id: i64,
    #[serde(default)]
    pub stream_icon: String,
    #[serde(default)]
    pub epg_channel_id: String,
    /// Unix 秒（字符串）
    #[serde(default)]
    pub added: String,
    #[serde(default)]
    pub category_id: String,
    #[serde(default)]
    pub category_ids: Vec<String>,
    #[serde(default)]
    pub custom_sid: String,
    /// 是否有归档
    #[serde(default)]
    pub tv_archive: i64,
    /// 归档天数
    #[serde(default)]
    pub tv_archive_duration: i64,
    #[serde(default)]
    pub direct_source: String,
    #[serde(default)]
    pub is_adult: i64,
}

/// 电影。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct VodStream {
    pub num: i64,
    pub name: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub year: String,
    pub stream_id: i64,
    #[serde(default)]
    pub stream_icon: String,
    #[serde(default)]
    pub rating: f64,
    #[serde(default)]
    pub genre: String,
    #[serde(default)]
    pub plot: String,
    #[serde(default)]
    pub release_date: String,
    #[serde(default)]
    pub cast: String,
    #[serde(default)]
    pub director: String,
    #[serde(default)]
    pub category_id: String,
    #[serde(default)]
    pub category_ids: Vec<String>,
    /// **可能是 `null`**
    #[serde(default)]
    pub container_extension: Option<String>,
    #[serde(default)]
    pub custom_sid: String,
    #[serde(default)]
    pub direct_source: String,
    #[serde(default)]
    pub episode_run_time: String,
}

/// 剧集。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Series {
    pub num: i64,
    pub name: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub year: String,
    pub series_id: i64,
    #[serde(default)]
    pub cover: String,
    #[serde(default)]
    pub plot: String,
    #[serde(default)]
    pub genre: String,
    #[serde(default)]
    pub release_date: String,
    #[serde(default)]
    pub rating: f64,
    #[serde(default)]
    pub category_id: String,
    #[serde(default)]
    pub category_ids: Vec<String>,
}

/// `get_series_info` 的响应。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct SeriesInfo {
    #[serde(default)]
    pub seasons: Vec<Season>,
    #[serde(default)]
    pub info: serde_json::Map<String, serde_json::Value>,
    /// key 是季号
    #[serde(default)]
    pub episodes: std::collections::BTreeMap<String, Vec<Episode>>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Season {
    #[serde(default)]
    pub id: i64,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub episode_count: i64,
    #[serde(default)]
    pub season_number: i64,
    #[serde(default)]
    pub cover: String,
    #[serde(default)]
    pub overview: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Episode {
    /// **通常是字符串**
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub episode_num: String,
    #[serde(default)]
    pub title: String,
    /// **可能是 `null`**
    #[serde(default)]
    pub container_extension: Option<String>,
    #[serde(default)]
    pub season: i64,
    #[serde(default)]
    pub custom_sid: String,
    #[serde(default)]
    pub direct_source: String,
    #[serde(default)]
    pub info: serde_json::Map<String, serde_json::Value>,
}

/// EPG 条目（Xtream JSON 形态）。
///
/// ⚠️ **`title` / `description` 是 base64 编码的。**
///
/// ⚠️ **数字与字符串混用** —— 不同面板对同一字段的类型不一致，
/// 所以这里所有标量都用容错反序列化。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct EpgListing {
    #[serde(default, deserialize_with = "flexible_string")]
    pub id: String,
    #[serde(default, deserialize_with = "flexible_string")]
    pub epg_id: String,
    #[serde(default, deserialize_with = "flexible_string")]
    pub channel_id: String,
    /// base64
    #[serde(default)]
    pub title: String,
    /// base64
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub lang: String,
    /// 服务器本地时间 `YYYY-MM-DD HH:MM:SS`
    #[serde(default)]
    pub start: String,
    /// 服务器本地时间（**有时是数字**）
    #[serde(default, deserialize_with = "flexible_string")]
    pub end: String,
    /// Unix 秒 —— **优先用这个**
    #[serde(default, deserialize_with = "flexible_i64")]
    pub start_timestamp: i64,
    #[serde(default, deserialize_with = "flexible_i64")]
    pub stop_timestamp: i64,
    #[serde(default, deserialize_with = "flexible_i64")]
    pub now_playing: i64,
    #[serde(default, deserialize_with = "flexible_i64")]
    pub has_archive: i64,
}

/// 容错反序列化：字符串或数字 → 字符串。
fn flexible_string<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::Error as _;
    match serde_json::Value::deserialize(deserializer)? {
        serde_json::Value::String(s) => Ok(s),
        serde_json::Value::Number(n) => Ok(n.to_string()),
        serde_json::Value::Null => Ok(String::new()),
        other => Err(D::Error::custom(format!("期望字符串或数字，实际: {other}"))),
    }
}

/// 容错反序列化：数字或字符串 → i64。
fn flexible_i64<'de, D>(deserializer: D) -> Result<i64, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::Error as _;
    match serde_json::Value::deserialize(deserializer)? {
        serde_json::Value::Number(n) => Ok(n.as_i64().unwrap_or_default()),
        serde_json::Value::String(s) => Ok(s.trim().parse().unwrap_or_default()),
        serde_json::Value::Null => Ok(0),
        other => Err(D::Error::custom(format!("期望数字或字符串，实际: {other}"))),
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct EpgResponse {
    #[serde(default)]
    pub epg_listings: Vec<EpgListing>,
}

impl EpgListing {
    /// 解码 base64 的标题。解码失败时原样返回。
    pub fn decoded_title(&self) -> String {
        decode_b64_lossy(&self.title)
    }

    /// 解码 base64 的描述。
    pub fn decoded_description(&self) -> String {
        decode_b64_lossy(&self.description)
    }
}

/// 解码 base64；失败则原样返回。
///
/// Xtream 的一些面板返回**明文**而不是 base64，所以必须容错。
pub fn decode_b64_lossy(input: &str) -> String {
    use base64::Engine;
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    match base64::engine::general_purpose::STANDARD.decode(trimmed) {
        Ok(bytes) => match String::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => trimmed.to_string(),
        },
        Err(_) => trimmed.to_string(),
    }
}

/// Xtream 面板端点构造器。
#[derive(Debug, Clone)]
pub struct XtreamEndpoints {
    pub base_url: String,
    pub username: String,
    pub password: String,
}

impl XtreamEndpoints {
    pub fn new(
        base_url: impl Into<String>,
        username: impl Into<String>,
        password: impl Into<String>,
    ) -> Self {
        Self {
            base_url: base_url.into().trim_end_matches('/').to_string(),
            username: username.into(),
            password: password.into(),
        }
    }

    /// `player_api.php` 的完整 URL。
    pub fn player_api(&self, action: Option<&str>) -> String {
        let mut url = format!(
            "{}/player_api.php?username={}&password={}",
            self.base_url,
            percent_encode(&self.username),
            percent_encode(&self.password)
        );
        if let Some(action) = action {
            url.push_str(&format!("&action={action}"));
        }
        url
    }

    /// `get_series_info` 的 URL。
    pub fn series_info(&self, series_id: i64) -> String {
        format!(
            "{}&series_id={series_id}",
            self.player_api(Some("get_series_info"))
        )
    }

    /// `get_short_epg` 的 URL。
    pub fn short_epg(&self, stream_id: i64, limit: Option<u32>) -> String {
        let mut url = format!(
            "{}&stream_id={stream_id}",
            self.player_api(Some("get_short_epg"))
        );
        if let Some(limit) = limit {
            url.push_str(&format!("&limit={limit}"));
        }
        url
    }

    /// `xmltv.php` 的 URL。
    pub fn xmltv(&self) -> String {
        format!(
            "{}/xmltv.php?username={}&password={}",
            self.base_url,
            percent_encode(&self.username),
            percent_encode(&self.password)
        )
    }

    /// 导出 M3U 的 URL。
    pub fn m3u(&self, output: &str) -> String {
        format!(
            "{}/get.php?username={}&password={}&type=m3u_plus&output={output}",
            self.base_url,
            percent_encode(&self.username),
            percent_encode(&self.password)
        )
    }

    /// 直播流 URL。
    ///
    /// `extension` 由 [`allowed_output_formats`] 决定（`ts` / `m3u8`）。
    pub fn live_url(&self, stream_id: i64, extension: &str) -> String {
        format!(
            "{}/live/{}/{}/{stream_id}.{extension}",
            self.base_url,
            percent_encode(&self.username),
            percent_encode(&self.password)
        )
    }

    /// 电影 URL。
    pub fn movie_url(&self, stream_id: i64, container: Option<&str>) -> String {
        let ext = container.filter(|c| !c.is_empty()).unwrap_or("mp4");
        format!(
            "{}/movie/{}/{}/{stream_id}.{ext}",
            self.base_url,
            percent_encode(&self.username),
            percent_encode(&self.password)
        )
    }

    /// 剧集单集 URL。
    pub fn series_url(&self, episode_id: &str, container: Option<&str>) -> String {
        let ext = container.filter(|c| !c.is_empty()).unwrap_or("mp4");
        format!(
            "{}/series/{}/{}/{episode_id}.{ext}",
            self.base_url,
            percent_encode(&self.username),
            percent_encode(&self.password)
        )
    }

    /// Xtream 时移 URL（`/timeshift/...` 形态）。
    ///
    /// ⚠️ `start` 必须是**供应商本地墙钟时间** `YYYY-MM-DD:HH-MM`。
    pub fn timeshift_url(
        &self,
        stream_id: i64,
        start_local: &str,
        minutes: i64,
        extension: &str,
    ) -> String {
        format!(
            "{}/timeshift/{}/{}/{minutes}/{start_local}/{stream_id}.{extension}",
            self.base_url,
            percent_encode(&self.username),
            percent_encode(&self.password)
        )
    }

    /// Xtream 时移 URL（`streaming/timeshift.php` 形态）。
    pub fn timeshift_script_url(&self, stream_id: i64, start_local: &str, minutes: i64) -> String {
        format!(
            "{}/streaming/timeshift.php?username={}&password={}&stream={stream_id}&start={start_local}&duration={minutes}",
            self.base_url,
            percent_encode(&self.username),
            percent_encode(&self.password)
        )
    }

    /// 从 `allowed_output_formats` 中选出直播容器的扩展名。
    ///
    /// 规则（`crispy-xtream` 的 `effective_channel_extension`）：
    /// - 列表为空 → 用偏好值
    /// - 偏好值在列表里 → 用偏好值
    /// - 否则 → 用列表里的**第一个**
    pub fn effective_channel_extension(allowed: &[String], preferred: &str) -> String {
        if allowed.is_empty() {
            return preferred.to_string();
        }
        if allowed.iter().any(|f| f.eq_ignore_ascii_case(preferred)) {
            return preferred.to_string();
        }
        allowed[0].clone()
    }

    /// 直播扩展名对应的流格式。
    pub fn format_for_extension(ext: &str) -> StreamFormat {
        match ext.to_ascii_lowercase().as_str() {
            "m3u8" | "hls" => StreamFormat::Hls,
            "mpd" | "dash" => StreamFormat::Dash,
            "ts" => StreamFormat::TransportStream,
            "mp4" => StreamFormat::Mp4,
            _ => StreamFormat::Unknown,
        }
    }
}

/// 百分号编码（凭据可能含特殊字符）。
fn percent_encode(input: &str) -> String {
    const SET: &percent_encoding::AsciiSet = &percent_encoding::NON_ALPHANUMERIC
        .remove(b'-')
        .remove(b'.')
        .remove(b'_')
        .remove(b'~');
    percent_encoding::utf8_percent_encode(input, SET).to_string()
}

/// 解析 `get_live_streams` 的响应。
pub fn parse_live_streams(body: &str) -> Result<Vec<LiveStream>> {
    Ok(serde_json::from_str(body)?)
}

/// 解析分类响应（对象数组或数组套数组）。
pub fn parse_categories(body: &str) -> Result<Vec<Category>> {
    Ok(serde_json::from_str(body)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_object_form_categories() {
        let body = r#"[{"category_id":"1","category_name":"News","parent_id":0}]"#;
        let cats = parse_categories(body).unwrap();
        assert_eq!(cats[0].id().as_deref(), Some("1"));
        assert_eq!(cats[0].name().as_deref(), Some("News"));
    }

    #[test]
    fn parses_legacy_array_form_categories() {
        let body = r#"[["1","News"]]"#;
        let cats = parse_categories(body).unwrap();
        assert_eq!(cats[0].id().as_deref(), Some("1"));
        assert_eq!(cats[0].name().as_deref(), Some("News"));
    }

    #[test]
    fn live_stream_parses_string_numbers() {
        let body = r#"{"num":1,"name":"CNN","stream_type":"live","stream_id":42,
            "epg_channel_id":"cnn.us","added":"1755551248","category_id":"5",
            "tv_archive":1,"tv_archive_duration":7}"#;
        let s: LiveStream = serde_json::from_str(body).unwrap();
        assert_eq!(s.stream_id, 42);
        assert_eq!(s.added, "1755551248");
        assert_eq!(s.tv_archive, 1);
        assert_eq!(s.tv_archive_duration, 7);
    }

    #[test]
    fn epg_title_is_base64() {
        // base64("Demo Now") / base64("Headline news")
        let epg = EpgListing {
            title: "RGVtbyBOb3c=".into(),
            description: "SGVhZGxpbmUgbmV3cw==".into(),
            ..Default::default()
        };
        assert_eq!(epg.decoded_title(), "Demo Now");
        assert_eq!(epg.decoded_description(), "Headline news");
    }

    #[test]
    fn epg_plaintext_falls_back_gracefully() {
        let epg = EpgListing {
            title: "Plain Title".into(),
            ..Default::default()
        };
        assert_eq!(epg.decoded_title(), "Plain Title");
    }

    #[test]
    fn builds_stream_urls() {
        let e = XtreamEndpoints::new("http://panel:25461", "user", "pass");
        assert_eq!(
            e.live_url(42, "ts"),
            "http://panel:25461/live/user/pass/42.ts"
        );
        assert_eq!(
            e.movie_url(7, Some("mkv")),
            "http://panel:25461/movie/user/pass/7.mkv"
        );
        assert_eq!(
            e.series_url("5523", None),
            "http://panel:25461/series/user/pass/5523.mp4"
        );
        assert_eq!(
            e.xmltv(),
            "http://panel:25461/xmltv.php?username=user&password=pass"
        );
    }

    #[test]
    fn builds_timeshift_url() {
        let e = XtreamEndpoints::new("http://panel:25461", "u", "p");
        assert_eq!(
            e.timeshift_url(42, "2024-01-01:12-00", 60, "ts"),
            "http://panel:25461/timeshift/u/p/60/2024-01-01:12-00/42.ts"
        );
        assert_eq!(
            e.timeshift_script_url(42, "2024-01-01:12-00", 60),
            "http://panel:25461/streaming/timeshift.php?username=u&password=p&stream=42&start=2024-01-01:12-00&duration=60"
        );
    }

    #[test]
    fn credentials_are_percent_encoded() {
        let e = XtreamEndpoints::new("http://p", "us er", "p@ss/word");
        assert!(e.player_api(None).contains("username=us%20er"));
        assert!(e.player_api(None).contains("password=p%40ss%2Fword"));
    }

    #[test]
    fn extension_selection_rules() {
        assert_eq!(
            XtreamEndpoints::effective_channel_extension(&[], "ts"),
            "ts",
            "空列表 → 用偏好值"
        );
        assert_eq!(
            XtreamEndpoints::effective_channel_extension(&["m3u8".into(), "ts".into()], "ts"),
            "ts",
            "偏好值在列表里 → 用偏好值"
        );
        assert_eq!(
            XtreamEndpoints::effective_channel_extension(&["m3u8".into(), "ts".into()], "mkv"),
            "m3u8",
            "偏好值不在列表 → 用第一个"
        );
    }
}
