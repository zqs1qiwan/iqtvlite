//! 领域模型 —— 协议无关的规范化表示。
//!
//! 这一层是**协议中立**的：M3U / Xtream / Stalker 各自的原始结构在解析层被归一化到这里。
//! 好处是上层（存储、UI、播放）完全不需要知道数据来自哪种协议。

use serde::{Deserialize, Serialize};

use crate::id::GlobalId;

pub mod catchup;

pub use catchup::{CatchupConfig, CatchupType};

/// 播放列表（源）的类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceKind {
    /// 普通 M3U / M3U+ 播放列表（URL 或本地文件）
    M3u,
    /// Xtream Codes 面板
    Xtream,
    /// Stalker / Ministra 门户（v2）
    Stalker,
}

/// 内容类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ContentKind {
    Live,
    Movie,
    Series,
}

/// 流媒体的传输协议。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StreamProtocol {
    Http,
    Https,
    Rtmp,
    Rtmps,
    Rtsp,
    Udp,
    Rtp,
    Mms,
    Mmsh,
    Unknown,
}

/// 流的容器/清单格式。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StreamFormat {
    /// HLS（m3u8）
    Hls,
    /// MPEG-DASH（mpd）
    Dash,
    /// 裸 MPEG-TS
    TransportStream,
    /// 渐进式 MP4
    Mp4,
    /// Matroska
    Mkv,
    Unknown,
}

/// 一个播放列表源。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Source {
    pub id: crate::id::SourceId,
    pub kind: SourceKind,
    pub name: String,
    /// Xtream 的面板地址；M3U 的播放列表地址
    pub base_url: Option<String>,
    pub username: Option<String>,
    pub password: Option<String>,
    /// Xtream `server_info.timezone` —— 时移 URL 换算必需
    pub provider_timezone: Option<String>,
    /// Xtream `user_info.allowed_output_formats`
    pub allowed_output_formats: Vec<String>,
    /// EPG 源地址（`url-tvg` / `x-tvg-url`，可能有多个）
    pub epg_urls: Vec<String>,
    pub user_agent: String,
    pub referrer: Option<String>,
    /// 上次成功同步时间（Unix 秒）
    pub last_sync_at: Option<i64>,
    pub last_epg_sync_at: Option<i64>,
}

/// 分类（分组）。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Category {
    pub id: GlobalId,
    pub source_id: crate::id::SourceId,
    /// 供应商侧的分类 ID
    pub provider_id: Option<String>,
    pub name: String,
    pub kind: ContentKind,
    pub sort_order: i32,
}

/// 频道（直播）或内容条目（点播/剧集）。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Channel {
    /// 全局稳定 ID —— 收藏/历史用它，重新同步后不变
    pub id: GlobalId,
    pub source_id: crate::id::SourceId,
    /// 供应商侧 ID
    pub provider_id: String,
    pub kind: ContentKind,
    pub name: String,
    pub tvg_name: Option<String>,
    pub tvg_id: Option<String>,
    pub logo_url: Option<String>,
    /// 所属分组（`group-title`）。一个频道可能属于多个组。
    pub groups: Vec<String>,
    pub channel_number: Option<String>,
    pub language: Option<String>,
    pub country: Option<String>,
    pub primary_url: String,
    pub is_radio: bool,
    pub is_adult: bool,
    /// `tvg-shift` —— **EPG 时间偏移（小时）**，不是归档窗口
    pub tvg_shift_hours: Option<f64>,
    /// 归档（catch-up）配置
    pub catchup: Option<CatchupConfig>,
    /// Xtream `tv_archive` (0/1)
    pub tv_archive: bool,
    /// Xtream `tv_archive_duration`（天）
    pub archive_days: Option<u32>,
    /// 点播/剧集元数据
    pub year: Option<i32>,
    pub plot: Option<String>,
    pub rating: Option<f64>,
    pub genre: Option<String>,
    pub cover_url: Option<String>,
    pub backdrop_url: Option<String>,
    pub duration_secs: Option<i64>,
    pub tmdb_id: Option<i64>,
    /// 供应商原始 JSON —— 保留以便将来支持新字段
    pub raw: Option<serde_json::Value>,
}

/// 一条可供播放的线路。
///
/// 同一个频道可能有多个线路（不同 CDN、不同清晰度、TS vs HLS），
/// 这直接对应「备用线路」和「故障切换组」。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChannelStream {
    pub id: GlobalId,
    pub channel_id: GlobalId,
    pub source_id: crate::id::SourceId,
    pub provider_id: String,
    pub url: String,
    pub protocol: StreamProtocol,
    pub format: StreamFormat,
    pub container: Option<String>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub bitrate_kbps: Option<u32>,
    pub fps: Option<f64>,
    pub audio_lang: Option<String>,
    pub direct_source: Option<String>,
    pub custom_sid: Option<String>,
    /// 剧集：季/集
    pub season: Option<i32>,
    pub episode_num: Option<i32>,
    pub episode_title: Option<String>,
    pub duration_secs: Option<i64>,
    pub priority: i32,
    /// 健康状态：`unknown` | `alive` | `dead` | `geo_blocked` | `drm` | `placeholder`
    pub health: StreamHealth,
}

/// 线路健康状态（由探活得出）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StreamHealth {
    #[default]
    Unknown,
    Alive,
    Dead,
    GeoBlocked,
    /// 检测到 DRM（Widevine/FairPlay/PlayReady）—— mpv 无法播放
    Drm,
    /// 占位流（black.ts 等）—— 死频道
    Placeholder,
}

/// 一条节目单记录。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Programme {
    /// 指南频道 ID
    pub epg_channel_id: GlobalId,
    /// 开始时间（Unix 秒，UTC）
    pub start_ts: i64,
    /// 结束时间（Unix 秒，UTC）
    pub stop_ts: i64,
    pub title: String,
    pub sub_title: Option<String>,
    pub description: Option<String>,
    pub category: Option<String>,
    pub episode_num: Option<String>,
    /// 非 DTD 属性，Kodi 会读
    pub catchup_id: Option<String>,
    pub icon_url: Option<String>,
    pub rating: Option<String>,
    /// `clumpidx` —— 同一时段的多个节目（新闻/天气）用它排序
    pub clumpidx: Option<String>,
    pub credits: Option<serde_json::Value>,
}

/// 指南里的一个频道（可能匹配不到播放列表里的任何频道）。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EpgChannel {
    pub id: GlobalId,
    pub epg_source_id: GlobalId,
    /// XMLTV `<channel id>`
    pub key: String,
    /// 所有语言的 `<display-name>`
    pub display_names: Vec<String>,
    pub icon_urls: Vec<String>,
    pub first_start: Option<i64>,
    pub last_stop: Option<i64>,
    pub programme_count: u32,
}

/// 频道与指南频道的匹配关系。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MatchSource {
    /// `tvg-id` 精确匹配
    Id,
    /// `tvg-name` == `<display-name>`
    TvgName,
    /// 显示名 == `<display-name>`（**仅当频道没有 id 时才允许**）
    Name,
    /// 用户手动指定
    Manual,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EpgMatch {
    pub channel_id: GlobalId,
    pub epg_channel_id: GlobalId,
    pub match_source: MatchSource,
    pub confidence: f64,
}
