//! # iqtvlite-core
//!
//! iqtvlite 的**领域层**：IPTV 数据模型、协议解析、Catch-up 模板引擎。
//!
//! **硬性约束：本 crate 不得依赖任何 Android crate。**
//! 这条规则保证 90% 的业务逻辑可以在 PC 上 `cargo test` 完整验证，
//! 无需设备、无需模拟器、无需 Gradle。
//!
//! ## 模块
//!
//! | 模块 | 内容 |
//! |---|---|
//! | [`model`] | 协议中立的领域模型（频道、节目、源、线路…） |
//! | [`parse::m3u`] | M3U/M3U+ 解析器（全属性集、流式） |
//! | [`parse::xmltv`] | XMLTV 流式解析器（大文件友好） |
//! | [`parse::xtream`] | Xtream Codes 类型与端点构造 |
//! | [`model::catchup`] | Catch-up 配置与 URL 模板引擎 |
//! | [`id`] | 稳定 ID 方案 |
//! | [`timeutil`] | XMLTV 时间解析、时区换算、格式化 |

pub mod error;
pub mod id;
pub mod model;
pub mod parse;
pub mod timeutil;

pub use error::{IptvError, ParseErrorKind, Result};
pub use id::{global_id, GlobalId, SourceId};
pub use model::catchup::{CatchupConfig, CatchupContext, CatchupType};
pub use model::{
    Category, Channel, ChannelStream, ContentKind, EpgChannel, EpgMatch, MatchSource, Programme,
    Source, SourceKind, StreamFormat, StreamHealth, StreamProtocol,
};
