//! 协议解析层。
//!
//! 每个子模块对应一种「协议」，输出**协议中立的领域模型**（见 [`crate::model`]）。
//!
//! | 模块 | 协议 |
//! |---|---|
//! | [`m3u`] | M3U / M3U+ 播放列表 |
//! | [`xmltv`] | XMLTV 电子节目单 |
//! | [`xtream`] | Xtream Codes API |

pub mod m3u;
pub mod xmltv;
pub mod xtream;
