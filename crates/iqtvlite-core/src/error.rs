//! iqtvlite 领域层错误类型。

use thiserror::Error;

/// 领域层统一错误。
///
/// 设计原则：**面向用户的错误必须携带可执行动作**，而不是原始错误串。
/// 因此这里保留 `kind`（可机读分类）与 `context`（技术细节），
/// 由上层（`iqtvlite-app`）把它翻译成「标题 + 可选动作列表」。
#[derive(Debug, Error)]
pub enum IptvError {
    #[error("解析失败: {context}")]
    Parse {
        kind: ParseErrorKind,
        context: String,
    },

    #[error("网络错误: {0}")]
    Network(String),

    #[error("无效的 URL: {0}")]
    InvalidUrl(String),

    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),

    #[error("数据格式错误: {0}")]
    Json(#[from] serde_json::Error),

    #[error("未找到: {0}")]
    NotFound(String),
}

impl From<quick_xml::Error> for IptvError {
    fn from(e: quick_xml::Error) -> Self {
        IptvError::Parse {
            kind: ParseErrorKind::Syntax,
            context: e.to_string(),
        }
    }
}

/// 解析错误分类 —— 可机读，用于统计与 UI 提示。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ParseErrorKind {
    /// 语法错误（该行完全无法理解）
    Syntax,
    /// 缺少必填字段（如 M3U 条目没有 URL）
    MissingField,
    /// 时间格式无法解析
    Time,
    /// 编码 / 字符集问题
    Encoding,
    /// 未知但可忽略的属性（不作为错误返回，仅记录）
    UnknownAttribute,
}

pub type Result<T, E = IptvError> = std::result::Result<T, E>;
