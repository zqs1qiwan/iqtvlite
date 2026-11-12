//! # iqtvlite-store
//!
//! SQLite 存储层。**零 Android 依赖** —— 全部逻辑可在 PC 上测试。
//!
//! # 设计要点
//!
//! - **WAL 模式** → 读写不互斥，UI 线程可以边写边读。
//! - **单写者** → 写操作串行化到一个线程（见 [`Store`] 的写通道），避免 `SQLITE_BUSY`。
//! - **FTS5 外部内容表 + trigger** → 索引自动跟随，无需手工维护。
//! - **迁移用 `PRAGMA user_version`** → 简单、可审计、不做自动降级。

pub mod migrate;

use std::path::Path;
use std::sync::Arc;

use parking_lot::{Mutex, MutexGuard};
use rusqlite::{Connection, OpenFlags};
use thiserror::Error;

pub use migrate::{migrate, MIGRATIONS, SCHEMA_VERSION};

#[derive(Debug, Error)]
pub enum StoreError {
    #[error("数据库错误: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("迁移失败: {0}")]
    Migration(String),
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error("序列化错误: {0}")]
    Json(#[from] serde_json::Error),
}

pub type Result<T, E = StoreError> = std::result::Result<T, E>;

/// 打开数据库连接并应用推荐 PRAGMA。
///
/// - `journal_mode = WAL` —— 读写并发
/// - `synchronous = NORMAL` —— WAL 下足够安全，且快得多
/// - `foreign_keys = ON` —— 引用完整性
/// - `temp_store = MEMORY` —— 临时表在内存
/// - `cache_size = -40000` —— 约 40 MB 页缓存
pub fn open(path: impl AsRef<Path>) -> Result<Connection> {
    let conn = Connection::open(path.as_ref())?;
    configure(&conn)?;
    Ok(conn)
}

/// 打开内存库（测试用）。
pub fn open_in_memory() -> Result<Connection> {
    let conn = Connection::open_in_memory()?;
    configure(&conn)?;
    Ok(conn)
}

/// 应用 PRAGMA。
pub fn configure(conn: &Connection) -> Result<()> {
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.pragma_update(None, "synchronous", "NORMAL")?;
    conn.pragma_update(None, "foreign_keys", "ON")?;
    conn.pragma_update(None, "temp_store", "MEMORY")?;
    conn.pragma_update(None, "cache_size", -40_000i64)?;
    Ok(())
}

/// 数据库句柄。
///
/// `rusqlite::Connection` 是 `Send` 但**不是** `Sync` → 用 `Mutex` 包起来。
///
/// 这与架构设计一致：**单写者**（所有写操作串行化到一个线程），
/// UI 线程用 [`Store::reader`] 拿独立的只读连接（WAL 允许并发读）。
#[derive(Clone)]
pub struct Store {
    inner: Arc<Mutex<Connection>>,
}

impl Store {
    pub fn new(conn: Connection) -> Self {
        Self {
            inner: Arc::new(Mutex::new(conn)),
        }
    }

    /// 打开（或创建）数据库文件并迁移到最新版本。
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let conn = open(path)?;
        migrate(&conn)?;
        Ok(Self::new(conn))
    }

    /// 在内存库上建库并迁移（测试用）。
    pub fn open_in_memory() -> Result<Self> {
        let conn = open_in_memory()?;
        migrate(&conn)?;
        Ok(Self::new(conn))
    }

    /// 独占访问底层连接（写操作用）。
    pub fn conn(&self) -> MutexGuard<'_, Connection> {
        self.inner.lock()
    }

    /// 当前 schema 版本。
    pub fn schema_version(&self) -> Result<u32> {
        let conn = self.inner.lock();
        let v: u32 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        Ok(v)
    }
}

/// 存储层错误向领域层错误的转换。
impl From<StoreError> for iqtvlite_core::IptvError {
    fn from(e: StoreError) -> Self {
        match e {
            StoreError::Sqlite(e) => iqtvlite_core::IptvError::Parse {
                kind: iqtvlite_core::ParseErrorKind::Syntax,
                context: e.to_string(),
            },
            StoreError::Json(e) => iqtvlite_core::IptvError::Parse {
                kind: iqtvlite_core::ParseErrorKind::Syntax,
                context: e.to_string(),
            },
            other => iqtvlite_core::IptvError::Parse {
                kind: iqtvlite_core::ParseErrorKind::Syntax,
                context: other.to_string(),
            },
        }
    }
}

/// 打开只读连接（用于 UI 线程）。
pub fn open_readonly(path: impl AsRef<Path>) -> Result<Connection> {
    let conn = Connection::open_with_flags(
        path.as_ref(),
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    conn.pragma_update(None, "temp_store", "MEMORY")?;
    Ok(conn)
}
