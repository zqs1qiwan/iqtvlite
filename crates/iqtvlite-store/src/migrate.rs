//! 数据库 schema 与迁移。
//!
//! 迁移用 `PRAGMA user_version` 管理：**只向前，不做自动降级**（回滚靠备份文件）。
//!
//! # 命名约定
//!
//! - `rid` —— `INTEGER PRIMARY KEY`（rowid 别名）。**VACUUM 安全**，用于 FTS5 的 rowid。
//! - `id` —— 跨表引用的**业务主键**（频道是全局稳定 ID 的十六进制字符串）。
//!   用 `UNIQUE` 而不是 `PRIMARY KEY`，因为 SQLite 的 rowid 别名只能是 INTEGER。

use rusqlite::Connection;

use crate::{Result, StoreError};

/// 当前 schema 版本。
pub const SCHEMA_VERSION: u32 = 2;

/// 迁移脚本。索引 = 目标版本号。
pub const MIGRATIONS: &[&str] = &[
    // ── v1：核心表 ─────────────────────────────────────────────────────────
    r#"
    -- 播放列表源
    CREATE TABLE sources (
        id                INTEGER PRIMARY KEY,
        uuid              TEXT NOT NULL UNIQUE,
        kind              TEXT NOT NULL CHECK (kind IN ('m3u','xtream','stalker')),
        name              TEXT NOT NULL,
        base_url          TEXT,
        username_enc      BLOB,
        password_enc      BLOB,
        provider_tz       TEXT,
        allowed_output_formats TEXT NOT NULL DEFAULT '[]',
        max_connections   INTEGER,
        active_cons       INTEGER,
        exp_date          INTEGER,
        user_agent        TEXT NOT NULL DEFAULT 'TiviMate/5.1.6 (Android 12)',
        referrer          TEXT,
        sync_interval_hours     INTEGER NOT NULL DEFAULT 24,
        epg_sync_interval_hours INTEGER NOT NULL DEFAULT 6,
        last_sync_at      INTEGER,
        last_epg_sync_at  INTEGER,
        enabled           INTEGER NOT NULL DEFAULT 1,
        created_at        INTEGER NOT NULL,
        updated_at        INTEGER NOT NULL
    );

    -- 源挂载的 EPG 地址
    CREATE TABLE source_epg_urls (
        source_id INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
        url       TEXT NOT NULL,
        priority  INTEGER NOT NULL DEFAULT 30,
        PRIMARY KEY (source_id, url)
    ) WITHOUT ROWID;

    -- 分类
    CREATE TABLE categories (
        rid            INTEGER PRIMARY KEY,
        id             TEXT NOT NULL UNIQUE,
        source_id      INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
        provider_id    TEXT,
        name           TEXT NOT NULL,
        kind           TEXT NOT NULL DEFAULT 'live' CHECK (kind IN ('live','movie','series')),
        sort_order     INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX idx_categories_source ON categories(source_id, kind, sort_order);

    -- 频道 / 内容
    CREATE TABLE channels (
        rid            INTEGER PRIMARY KEY,
        id             TEXT NOT NULL UNIQUE,          -- 全局稳定 ID
        source_id      INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
        provider_id    TEXT NOT NULL,
        ordinal        INTEGER NOT NULL DEFAULT 0,
        kind           TEXT NOT NULL DEFAULT 'live' CHECK (kind IN ('live','movie','series')),
        name           TEXT NOT NULL,
        name_original  TEXT,
        tvg_name       TEXT,
        tvg_id         TEXT,
        logo_url       TEXT,
        group_title    TEXT,
        channel_number TEXT,
        language       TEXT,
        country        TEXT,
        primary_url    TEXT NOT NULL,
        catchup_type   TEXT,
        catchup_days   INTEGER,
        catchup_source TEXT,
        catchup_correction_hours REAL,
        timeshift      TEXT,
        tvg_shift_hours REAL,
        tv_archive     INTEGER NOT NULL DEFAULT 0,
        archive_days   INTEGER,
        container_extension TEXT,
        duration_secs  INTEGER,
        rating         REAL,
        plot           TEXT,
        genre          TEXT,
        year           INTEGER,
        tmdb_id        INTEGER,
        cover_url      TEXT,
        backdrop_url   TEXT,
        is_radio       INTEGER NOT NULL DEFAULT 0,
        is_adult       INTEGER NOT NULL DEFAULT 0,
        hidden         INTEGER NOT NULL DEFAULT 0,
        user_order     INTEGER,
        raw            TEXT,
        first_seen_at  INTEGER NOT NULL,
        last_seen_at   INTEGER NOT NULL,
        UNIQUE (source_id, provider_id, ordinal)
    );
    CREATE INDEX idx_channels_source_kind ON channels(source_id, kind, hidden);
    CREATE INDEX idx_channels_group        ON channels(source_id, group_title);
    CREATE INDEX idx_channels_tvgid        ON channels(source_id, tvg_id);

    -- 线路（同一频道的多码率 / 多 CDN）
    CREATE TABLE channel_streams (
        rid            INTEGER PRIMARY KEY,
        id             TEXT NOT NULL UNIQUE,
        channel_id     TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
        source_id      INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
        provider_id    TEXT NOT NULL,
        url            TEXT NOT NULL,
        protocol       TEXT NOT NULL DEFAULT 'unknown',
        format         TEXT NOT NULL DEFAULT 'unknown',
        container      TEXT,
        width          INTEGER,
        height         INTEGER,
        bitrate_kbps   INTEGER,
        fps            REAL,
        audio_lang     TEXT,
        direct_source  TEXT,
        custom_sid     TEXT,
        season         INTEGER,
        episode_num    INTEGER,
        episode_title  TEXT,
        duration_secs  INTEGER,
        priority       INTEGER NOT NULL DEFAULT 0,
        health         TEXT NOT NULL DEFAULT 'unknown',
        checked_at     INTEGER
    );
    CREATE INDEX idx_streams_channel ON channel_streams(channel_id, priority);

    -- EPG 源
    CREATE TABLE epg_sources (
        rid                INTEGER PRIMARY KEY,
        id                 TEXT NOT NULL UNIQUE,
        source_id          INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
        origin             TEXT NOT NULL DEFAULT 'manual',
        url                TEXT NOT NULL,
        enabled            INTEGER NOT NULL DEFAULT 1,
        priority           INTEGER NOT NULL DEFAULT 30,
        time_interpret     TEXT NOT NULL DEFAULT 'as_written',
        time_shift_minutes INTEGER NOT NULL DEFAULT 0,
        last_success_at    INTEGER,
        last_error         TEXT,
        programme_count    INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX idx_epg_sources_source ON epg_sources(source_id, priority);

    -- 指南里的频道
    CREATE TABLE epg_channels (
        rid             INTEGER PRIMARY KEY,
        id              TEXT NOT NULL UNIQUE,
        epg_source_id   INTEGER NOT NULL REFERENCES epg_sources(id) ON DELETE CASCADE,
        key             TEXT NOT NULL,
        first_start     INTEGER,
        last_stop       INTEGER,
        programme_count INTEGER NOT NULL DEFAULT 0,
        UNIQUE (epg_source_id, key)
    );
    CREATE INDEX idx_epg_channels_key ON epg_channels(key);

    -- 指南频道的多语言名
    CREATE TABLE epg_channel_names (
        epg_channel_id TEXT NOT NULL REFERENCES epg_channels(id) ON DELETE CASCADE,
        position       INTEGER NOT NULL,
        name           TEXT NOT NULL,
        name_key       TEXT NOT NULL,
        PRIMARY KEY (epg_channel_id, position)
    ) WITHOUT ROWID;
    CREATE INDEX idx_epg_channel_names_key ON epg_channel_names(name_key);

    -- 节目
    CREATE TABLE programmes (
        rid              INTEGER PRIMARY KEY,
        epg_channel_id   TEXT NOT NULL REFERENCES epg_channels(id) ON DELETE CASCADE,
        start_ts         INTEGER NOT NULL,
        stop_ts          INTEGER NOT NULL,
        title            TEXT NOT NULL,
        sub_title        TEXT,
        description      TEXT,
        category         TEXT,
        episode_num      TEXT,
        catchup_id       TEXT,
        icon_url         TEXT,
        rating           TEXT,
        credits          TEXT,
        previously_shown INTEGER NOT NULL DEFAULT 0,
        clumpidx         TEXT,
        raw              TEXT
    );
    -- 【关键】网格查询要的是时间切片 → 主索引必须时间优先
    CREATE INDEX idx_programmes_time    ON programmes(start_ts, epg_channel_id);
    CREATE INDEX idx_programmes_stop    ON programmes(stop_ts);
    CREATE INDEX idx_programmes_channel ON programmes(epg_channel_id, start_ts);

    -- 频道 ↔ 指南 的匹配关系（故意不做硬外键：指南可能来自别的源）
    CREATE TABLE epg_matches (
        channel_id     TEXT PRIMARY KEY REFERENCES channels(id) ON DELETE CASCADE,
        epg_channel_id TEXT REFERENCES epg_channels(id) ON DELETE SET NULL,
        match_source   TEXT NOT NULL,
        confidence     REAL NOT NULL DEFAULT 1.0
    );

    -- 收藏
    CREATE TABLE favourite_lists (
        id   TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        icon TEXT,
        sort INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE favourites (
        list_id    TEXT NOT NULL REFERENCES favourite_lists(id) ON DELETE CASCADE,
        channel_id TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
        sort       INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (list_id, channel_id)
    ) WITHOUT ROWID;
    CREATE INDEX idx_favourites_channel ON favourites(channel_id);

    -- 观看历史
    CREATE TABLE watch_history (
        rid          INTEGER PRIMARY KEY,
        channel_id   TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
        content_id   TEXT,
        position_ms  INTEGER NOT NULL DEFAULT 0,
        duration_ms  INTEGER NOT NULL DEFAULT 0,
        watched_at   INTEGER NOT NULL,
        finished     INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX idx_history_channel ON watch_history(channel_id, watched_at DESC);
    CREATE INDEX idx_history_recent  ON watch_history(watched_at DESC);

    -- 录制表：产品已明确禁用录像功能，此表仅为老版本数据库兼容性保留，不再写入。
    CREATE TABLE recordings (
        id          TEXT PRIMARY KEY,
        channel_id  TEXT,
        title       TEXT NOT NULL,
        start_ts    INTEGER NOT NULL,
        duration_s  INTEGER NOT NULL,
        file_path   TEXT NOT NULL,
        size_bytes  INTEGER,
        status      TEXT NOT NULL DEFAULT 'scheduled',
        error       TEXT
    );
    CREATE INDEX idx_recordings_start ON recordings(start_ts DESC);

    -- 提醒
    CREATE TABLE reminders (
        id          TEXT PRIMARY KEY,
        channel_id  TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
        programme_id INTEGER,
        fire_at     INTEGER NOT NULL,
        title       TEXT NOT NULL,
        notified    INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX idx_reminders_fire ON reminders(notified, fire_at);

    -- 故障切换组
    CREATE TABLE failover_groups (
        group_id   TEXT PRIMARY KEY,
        name       TEXT NOT NULL,
        created_at INTEGER NOT NULL
    );
    CREATE TABLE failover_members (
        stream_id TEXT PRIMARY KEY REFERENCES channel_streams(id) ON DELETE CASCADE,
        group_id  TEXT NOT NULL REFERENCES failover_groups(group_id) ON DELETE CASCADE,
        priority  INTEGER NOT NULL
    ) WITHOUT ROWID;
    CREATE INDEX idx_failover_group ON failover_members(group_id, priority);

    -- 设置
    CREATE TABLE kv (
        key        TEXT PRIMARY KEY,
        value      TEXT NOT NULL,
        updated_at INTEGER NOT NULL
    ) WITHOUT ROWID;

    -- 同步日志
    CREATE TABLE sync_log (
        rid        INTEGER PRIMARY KEY,
        source_id  INTEGER NOT NULL,
        kind       TEXT NOT NULL,
        started_at INTEGER NOT NULL,
        ended_at   INTEGER,
        status     TEXT NOT NULL,
        stats      TEXT,
        error      TEXT
    );
    CREATE INDEX idx_sync_log_source ON sync_log(source_id, started_at DESC);
    "#,
    // ── v2：FTS5 全文搜索 ─────────────────────────────────────────────────
    r#"
    -- contentless FTS5：索引里不存原文，省空间；查询用 rowid 回连 channels
    -- 用 'delete' 命令而不是普通 DELETE —— contentless 表的通用做法
    CREATE VIRTUAL TABLE channels_fts USING fts5(
        name, tvg_name, group_title, logo,
        content='',
        tokenize='unicode61 remove_diacritics 2 tokenchars ''_-.\'''
    );

    CREATE TRIGGER channels_fts_ai AFTER INSERT ON channels BEGIN
      INSERT INTO channels_fts(rowid, name, tvg_name, group_title, logo)
      VALUES (new.rid, new.name, new.tvg_name, new.group_title, new.logo_url);
    END;
    CREATE TRIGGER channels_fts_ad AFTER DELETE ON channels BEGIN
      INSERT INTO channels_fts(channels_fts, rowid, name, tvg_name, group_title, logo)
      VALUES ('delete', old.rid, old.name, old.tvg_name, old.group_title, old.logo_url);
    END;
    CREATE TRIGGER channels_fts_au AFTER UPDATE ON channels BEGIN
      INSERT INTO channels_fts(channels_fts, rowid, name, tvg_name, group_title, logo)
      VALUES ('delete', old.rid, old.name, old.tvg_name, old.group_title, old.logo_url);
      INSERT INTO channels_fts(rowid, name, tvg_name, group_title, logo)
      VALUES (new.rid, new.name, new.tvg_name, new.group_title, new.logo_url);
    END;

    CREATE VIRTUAL TABLE programmes_fts USING fts5(
        title, sub_title, description, category,
        content='',
        tokenize='unicode61 remove_diacritics 2'
    );

    CREATE TRIGGER programmes_fts_ai AFTER INSERT ON programmes BEGIN
      INSERT INTO programmes_fts(rowid, title, sub_title, description, category)
      VALUES (new.rid, new.title, new.sub_title, new.description, new.category);
    END;
    CREATE TRIGGER programmes_fts_ad AFTER DELETE ON programmes BEGIN
      INSERT INTO programmes_fts(programmes_fts, rowid, title, sub_title, description, category)
      VALUES ('delete', old.rid, old.title, old.sub_title, old.description, old.category);
    END;
    CREATE TRIGGER programmes_fts_au AFTER UPDATE ON programmes BEGIN
      INSERT INTO programmes_fts(programmes_fts, rowid, title, sub_title, description, category)
      VALUES ('delete', old.rid, old.title, old.sub_title, old.description, old.category);
      INSERT INTO programmes_fts(rowid, title, sub_title, description, category)
      VALUES (new.rid, new.title, new.sub_title, new.description, new.category);
    END;
    "#,
];

/// 把数据库迁移到最新版本。
pub fn migrate(conn: &Connection) -> Result<()> {
    let current: u32 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;

    if current > SCHEMA_VERSION {
        return Err(StoreError::Migration(format!(
            "数据库版本 {current} 高于当前程序支持的 {SCHEMA_VERSION}，请升级应用或恢复备份"
        )));
    }

    for (idx, sql) in MIGRATIONS.iter().enumerate() {
        let target = idx as u32 + 1;
        if target <= current {
            continue;
        }
        conn.execute_batch(sql)
            .map_err(|e| StoreError::Migration(format!("迁移到 v{target} 失败: {e}")))?;
        conn.pragma_update(None, "user_version", target as i64)?;
        tracing::info!(version = target, "数据库迁移完成");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Store;

    #[test]
    fn migration_is_idempotent() {
        let conn = crate::open_in_memory().unwrap();
        migrate(&conn).unwrap();
        migrate(&conn).unwrap();
        let v: u32 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(v, SCHEMA_VERSION);
    }

    #[test]
    fn all_expected_tables_exist() {
        let store = Store::open_in_memory().unwrap();
        let conn = store.conn();
        let mut stmt = conn
            .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
            .unwrap();
        let names: Vec<String> = stmt
            .query_map([], |r| r.get(0))
            .unwrap()
            .map(|r| r.unwrap())
            .collect();
        drop(stmt);
        drop(conn);
        for expected in [
            "sources",
            "channels",
            "channel_streams",
            "epg_sources",
            "epg_channels",
            "programmes",
            "epg_matches",
            "favourites",
            "watch_history",
            "failover_groups",
            "failover_members",
            "channels_fts",
            "programmes_fts",
        ] {
            assert!(
                names.iter().any(|n| n == expected),
                "缺少表 {expected}；现有: {names:?}"
            );
        }
    }

    #[test]
    fn fts_triggers_keep_index_in_sync() {
        let store = Store::open_in_memory().unwrap();
        let now = 1_700_000_000i64;
        {
            let conn = store.conn();
            conn.execute(
                "INSERT INTO sources (uuid, kind, name, created_at, updated_at)
                 VALUES ('u1','m3u','Test',?1,?1)",
                [now],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO channels (id, source_id, provider_id, name, primary_url, first_seen_at, last_seen_at)
                 VALUES ('c1', 1, 'p1', 'BBC One HD', 'http://x/y', ?1, ?1)",
                [now],
            )
            .unwrap();
        }

        let count = |sql: &str| -> i64 {
            let conn = store.conn();
            conn.query_row(sql, [], |r| r.get(0)).unwrap()
        };

        assert_eq!(
            count("SELECT count(*) FROM channels_fts WHERE channels_fts MATCH 'BBC'"),
            1,
            "插入后 FTS 应能搜到"
        );

        {
            let conn = store.conn();
            conn.execute("UPDATE channels SET name='ITV' WHERE id='c1'", [])
                .unwrap();
        }
        assert_eq!(
            count("SELECT count(*) FROM channels_fts WHERE channels_fts MATCH 'BBC'"),
            0,
            "更新后旧词应失效"
        );

        {
            let conn = store.conn();
            conn.execute("DELETE FROM channels WHERE id='c1'", [])
                .unwrap();
        }
        assert_eq!(
            count("SELECT count(*) FROM channels_fts WHERE channels_fts MATCH 'ITV'"),
            0,
            "删除后 FTS 应清空"
        );
    }
}
