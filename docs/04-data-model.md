# 04 — 数据模型与存储设计

> 存储引擎：**SQLite**（`rusqlite` bundled）。原则：**协议原始数据与规范化数据分离**；**稳定 ID**；**EPG 只增不重**。

---

## 1. 设计原则

1. **原始数据不丢**。供应商的原始字段（尤其是未知属性）必须保留，否则换供应商/换解析器时会丢信息。
2. **稳定 ID**。收藏、历史、观看进度必须跨「播放列表重同步」存活。供应商会重排频道顺序、改 URL。
3. **EPG 只增不重**。EPG 是「时间轴上的事实」，重复同步应当是 **upsert + 过期清理**，不是全量替换。
4. **单写者**。所有写操作在一个专用线程上，WAL 模式，批量事务。
5. **零网络依赖可浏览**。断网时本地数据足以渲染完整 UI。

---

## 2. 稳定 ID 方案

### 2.1 分层 ID

```
global_id  = xxh3_128( source_id || '\0' || provider_id )     ← 全局唯一，跨源
provider_id = 供应商侧 ID（Xtream 的 stream_id / M3U 的 tvg-id 或行内序号）
```

- `source_id` 是本地生成的 UUID v4（每个播放列表一个，永不变）。
- `provider_id`：
  - Xtream：`stream_id`（数字字符串）
  - M3U：优先 `tvg-id`；缺失时用 `name|group-title`；再缺失用 URL 的 hash

### 2.2 为什么不能只用 tvg-id

- 不同供应商可能有相同的 `tvg-id`（例如都叫 `BBCOne.uk`）→ 必须带 `source_id` 前缀。
- `tvg-id` 缺失时供应商会在下次同步换掉 → 用 `name|group` 做二级键，并保留 URL 作为三级回退。
- M3U 中**完全重复**的条目很常见 → 用 `uniquify` 加序号后缀（照抄 `crispy-m3u` 的 `uniquify_stable_id` 思路）。

### 2.3 三级 ID 策略

```rust
pub struct ChannelKey {
    source_id: SourceId,      // UUID
    provider_id: ProviderId,  // tvg-id | stream_id | name|group
    ordinal: u32,             // 同源内重复时的序号
}

impl ChannelKey {
    /// 全局稳定 ID —— 收藏/历史用它
    pub fn global_id(&self) -> GlobalId {
        GlobalId(xxh3_128(source_id.bytes, provider_id.bytes, ordinal))
    }
}
```

**关键**：`ordinal` 只在**完全重复**时递增，且由「provider_id + url」的稳定排序决定 → 重新同步后顺序稳定。

---

## 3. Schema

### 3.1 源（播放列表）

```sql
CREATE TABLE sources (
    id            TEXT PRIMARY KEY,          -- UUID v4
    kind          TEXT NOT NULL,             -- 'm3u' | 'xtream' | 'stalker'
    name          TEXT NOT NULL,
    -- Xtream 凭据（加密后存储，见 §5）
    base_url      TEXT,
    username_enc  BLOB,
    password_enc  BLOB,
    -- M3U
    playlist_url  TEXT,
    playlist_etag TEXT,
    playlist_last_modified TEXT,
    -- EPG 源（可多个，见 source_epg）
    -- 同步策略
    sync_interval_hours  INTEGER NOT NULL DEFAULT 24,
    epg_sync_interval_hours INTEGER NOT NULL DEFAULT 6,
    last_sync_at   INTEGER,
    last_epg_sync_at INTEGER,
    -- 行为开关
    user_agent     TEXT NOT NULL DEFAULT 'TiviMate/5.0.6 (Android TV)',
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL,
    -- 软删除：保留历史数据
    enabled        INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE source_epg (
    source_id   TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    epg_id      TEXT NOT NULL,               -- 本地 EPG 源 ID
    url         TEXT NOT NULL,
    kind        TEXT NOT NULL DEFAULT 'xmltv',  -- 'xmltv' | 'xtream'
    priority    INTEGER NOT NULL DEFAULT 0,     -- 多 EPG 源时的覆盖优先级
    PRIMARY KEY (source_id, epg_id)
);
```

### 3.2 分类

```sql
CREATE TABLE categories (
    id           TEXT PRIMARY KEY,           -- xxh3(source_id, provider_category_id)
    source_id    TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    provider_id  TEXT NOT NULL,              -- Xtream category_id
    name         TEXT NOT NULL,
    name_original TEXT,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    kind         TEXT NOT NULL DEFAULT 'live', -- 'live' | 'movie' | 'series'
    UNIQUE (source_id, provider_id, kind)
);
CREATE INDEX idx_categories_source ON categories(source_id, kind, sort_order);
```

### 3.3 频道 / 内容

```sql
CREATE TABLE channels (
    id            TEXT PRIMARY KEY,          -- 全局稳定 ID (xxh3_128)
    source_id     TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    provider_id   TEXT NOT NULL,
    ordinal       INTEGER NOT NULL DEFAULT 0,
    kind          TEXT NOT NULL DEFAULT 'live',  -- 'live' | 'movie' | 'series'
    -- 展示
    name          TEXT NOT NULL,
    name_original TEXT,
    tvg_name      TEXT,
    tvg_id        TEXT,
    logo_url      TEXT,
    group_title   TEXT,
    channel_number TEXT,
    language      TEXT,
    country       TEXT,
    -- 播放
    primary_url   TEXT NOT NULL,
    -- 时移
    catchup_type  TEXT,          -- 'append' | 'shift' | 'flussonic' | 'custom' | 'xc'
    catchup_days  INTEGER,
    catchup_source TEXT,         -- URL 模板
    timeshift     TEXT,
    tvg_shift     REAL,          -- EPG 时间偏移小时数
    -- VOD / 剧集
    container_extension TEXT,
    duration_secs  INTEGER,
    rating         REAL,
    plot           TEXT,
    year           INTEGER,
    tmdb_id        INTEGER,
    poster_url     TEXT,
    backdrop_url   TEXT,
    -- 原始数据（保真）
    raw            TEXT,          -- JSON: 供应商原始对象
    -- 状态
    is_radio      INTEGER NOT NULL DEFAULT 0,
    hidden        INTEGER NOT NULL DEFAULT 0,
    user_order    INTEGER,       -- 用户自定义排序（NULL = 用供应商顺序）
    first_seen_at INTEGER NOT NULL,
    last_seen_at  INTEGER NOT NULL,
    UNIQUE (source_id, provider_id, ordinal)
);
CREATE INDEX idx_channels_source_kind ON channels(source_id, kind, hidden);
CREATE INDEX idx_channels_category     ON channels(source_id, group_title);
CREATE INDEX idx_channels_tvgid        ON channels(source_id, tvg_id);
```

### 3.4 多码率线路

Xtream 供应商常为同一频道提供多条线路（不同 CDN / 清晰度）。这直接对应 TiviMate 的「备用线路」和 ynoTV 的「failover 组」。

```sql
CREATE TABLE channel_streams (
    id          INTEGER PRIMARY KEY,
    channel_id  TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    source_id   TEXT NOT NULL,
    url         TEXT NOT NULL,
    protocol    TEXT NOT NULL,      -- 'http' | 'https' | 'rtmp' | 'rtsp' | 'udp'
    format      TEXT NOT NULL,      -- 'hls' | 'dash' | 'ts' | 'unknown'
    container   TEXT,               -- 'mp4' | 'mkv' | 'ts'
    width       INTEGER, height     INTEGER,
    bitrate_kbps INTEGER,
    fps         REAL,
    audio_lang  TEXT,
    is_default  INTEGER NOT NULL DEFAULT 0,
    priority    INTEGER NOT NULL DEFAULT 0,
    -- 探活结果
    health      TEXT NOT NULL DEFAULT 'unknown', -- 'unknown'|'alive'|'dead'|'geo_blocked'|'drm'
    checked_at  INTEGER
);
CREATE INDEX idx_streams_channel ON channel_streams(channel_id, priority);
```

### 3.5 节目单（EPG）

```sql
CREATE TABLE programmes (
    id          INTEGER PRIMARY KEY,
    source_id   TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    epg_id      TEXT NOT NULL,      -- 来自哪个 EPG 源（多源合并用）
    tvg_id      TEXT NOT NULL,      -- XMLTV channel id
    channel_id  TEXT,               -- 解析出的本地频道（可能为 NULL = 未匹配）
    start_ts    INTEGER NOT NULL,   -- Unix epoch 秒 (UTC)
    stop_ts     INTEGER NOT NULL,
    -- 内容
    title       TEXT NOT NULL,
    sub_title   TEXT,
    description TEXT,
    category    TEXT,
    episode_num TEXT,
    season      INTEGER,
    episode     INTEGER,
    icon_url    TEXT,
    rating      TEXT,
    credits     TEXT,               -- JSON
    previously_shown INTEGER NOT NULL DEFAULT 0,
    -- 原始
    raw         TEXT,
    UNIQUE (source_id, epg_id, tvg_id, start_ts, title)
);
CREATE INDEX idx_prog_channel_time ON programmes(channel_id, start_ts);
CREATE INDEX idx_prog_tvg_time      ON programmes(source_id, tvg_id, start_ts);
CREATE INDEX idx_prog_time         ON programmes(start_ts, stop_ts);
```

> **`UNIQUE` 里带 `title`**：同一频道同一时刻偶尔会有两个条目（供应商数据噪声），带 title 避免被误合并。

### 3.6 收藏 / 历史 / 录制

```sql
CREATE TABLE favourite_lists (
    id       TEXT PRIMARY KEY,
    name     TEXT NOT NULL,
    icon     TEXT,
    sort     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE favourites (
    list_id    TEXT NOT NULL REFERENCES favourite_lists(id) ON DELETE CASCADE,
    channel_id TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    sort       INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (list_id, channel_id)
);
CREATE INDEX idx_fav_channel ON favourites(channel_id);

CREATE TABLE watch_history (
    channel_id  TEXT PRIMARY KEY REFERENCES channels(id) ON DELETE CASCADE,
    content_id  TEXT,                -- VOD/剧集用
    position_ms INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    watched_at  INTEGER NOT NULL,
    finished    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE recordings (
    id           TEXT PRIMARY KEY,
    channel_id   TEXT,
    title        TEXT NOT NULL,
    start_ts     INTEGER NOT NULL,
    duration_s   INTEGER NOT NULL,
    file_path    TEXT NOT NULL,
    size_bytes   INTEGER,
    status       TEXT NOT NULL DEFAULT 'scheduled', -- 'scheduled'|'recording'|'done'|'failed'
    error        TEXT
);
CREATE INDEX idx_recordings_start ON recordings(start_ts DESC);

CREATE TABLE reminders (
    id          TEXT PRIMARY KEY,
    channel_id  TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    programme_id INTEGER,
    fire_at     INTEGER NOT NULL,
    title       TEXT NOT NULL,
    notified    INTEGER NOT NULL DEFAULT 0
);
```

### 3.7 搜索（FTS5）

```sql
CREATE VIRTUAL TABLE channels_fts USING fts5(
    name, tvg_name, group_title, tvg_id,
    content='',
    tokenize='unicode61 remove_diacritics 2'
);
-- content='' → 独立存储（不依赖外部表），用 triggers 手工维护
CREATE VIRTUAL TABLE programmes_fts USING fts5(
    title, description,
    content='',
    tokenize='unicode61 remove_diacritics 2'
);
```

**中文分词问题**：`unicode61` 会把中文当成「一个长 token」（因为它按 Unicode 空白切分，中文没有空格）。三种解法：

| 方案 | 说明 |
|---|---|
| **A. `trigram` tokenizer** | SQLite 3.34+ 内置。支持子串匹配，中文可用（3 字以上）。SQLite 3.34+ 默认编译进 rusqlite bundled |
| **B. 自定义 tokenizer** | 用 `tokenchars` 扩展 + 预分词写入 shadow 表 |
| **C. 双索引** | `unicode61`（英文/编号）+ `trigram`（中文子串），查询时并集 |

**决策：采用 C（双 FTS 表）** —— 中文用 trigram 索引做子串查询，英文用 unicode61 索引做词查询，查询结果 UNION。简单且覆盖好。

> trigram 需要查询串 **≥ 3 字符**。对 1–2 字的中文查询，走 `LIKE 'x%'` 前缀匹配 + 频道数不大时的线性扫描兜底。

### 3.8 设置与元数据

```sql
CREATE TABLE kv (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE sync_log (
    id        INTEGER PRIMARY KEY,
    source_id TEXT NOT NULL,
    kind      TEXT NOT NULL,   -- 'playlist' | 'epg'
    started_at INTEGER NOT NULL,
    ended_at   INTEGER,
    status    TEXT NOT NULL,   -- 'ok' | 'error' | 'partial'
    stats     TEXT             -- JSON: {channels: N, added: N, updated: N, removed: N}
    error     TEXT
);
CREATE INDEX idx_sync_log_source ON sync_log(source_id, started_at DESC);
```

---

## 4. EPG 网格的查询设计

### 4.1 场景

网格视图：可见 **20 个频道 × 6 小时**，每格 30 分钟 → 视口内 **20 × 12 = 240 个节目格**。但**当前节目**需要跨所有可见频道取。

```sql
-- 1) 取可见频道的「当前 + 接下来 N 个」节目
SELECT p.*
FROM programmes p
WHERE p.channel_id IN (?1)          -- 20 个频道
  AND p.stop_ts > ?2                -- now - 30min（留出跨界重叠）
  AND p.start_ts < ?3               -- now + 6h
ORDER BY p.channel_id, p.start_ts;
```

**索引**：`idx_prog_channel_time (channel_id, start_ts)` → 20 次索引范围扫描，每次几十行。**亚毫秒**。

### 4.2 避免 N+1

绝不能「先查频道列表，再对每个频道查节目」——那是 20 次往返。用**单条 IN 查询 + 分组**：

```rust
let mut by_channel: HashMap<ChannelId, Vec<Programme>> = ...;
for p in rows { by_channel.entry(p.channel_id).or_default().push(p); }
```

### 4.3 全量节目表视图（整日 24h × 100 频道 = 4800 格）

- 一次查询取 `100 频道 × 24h`，约 2000–5000 行 → 几毫秒。
- **UI 侧虚拟化**：只构建可见区域的组件，滚动时按需查询/复用。
- **网格滚动时预取**：提前 1 屏预取（滚动方向感知）。

### 4.4 未匹配频道（EPG 有但播放列表没有）

```
channels.tvg_id  ←→  programmes.tvg_id
```

匹配规则：
1. 精确匹配 `tvg_id`（大小写不敏感 + Unicode 规范化）。
2. 失败时 `tvg_name` ≈ `channels.name` 模糊匹配（`strsim` 或归一化编辑距离）。
3. 仍未匹配 → 保留在 `programmes` 里但 `channel_id = NULL`，UI 中不显示。
4. **记录匹配率**（`matched / total`），低于阈值时在设置页提示「EPG 匹配率 23%，可能是 tvg-id 不匹配」——这是用户最常见的 EPG 投诉来源。

---

## 5. 凭据加密

Xtream 的用户名/密码需要存储。

| 方案 | 说明 |
|---|---|
| **Android Keystore + AES-GCM** | 密钥存放在 Android Keystore（硬件支持时是 TEE 内的 StrongBox），Rust 侧通过 JNI 调用 Keystore API 加密/解密。**推荐** |
| `keyring` crate | 桌面用，Android 上不可用 |
| `argon2` 派生静态密钥 | **不安全**（密钥在二进制里）—— ❌ |

**决策**：Android Keystore（AndroidKeyStore AES-256-GCM），密钥不可导出。Rust 侧定义 `SecretStore` trait，Android 实现走 JNI，其他平台（开发机）用文件 + 0600 权限。

```rust
pub trait SecretStore: Send + Sync {
    fn set(&self, key: &str, plaintext: &[u8]) -> Result<()>;
    fn get(&self, key: &str) -> Result<Option<Vec<u8>>>;
    fn delete(&self, key: &str) -> Result<()>;
}
```

---

## 6. 同步流程

### 5.1 播放列表同步（增量）

```
1. 取 playlist_url 的 ETag / Last-Modified
   ├─ 未变化 → 跳过
   └─ 变化   → 下载
2. 解析（流式，不整体载入内存）
3. 与本地比对：
   - provider_id 存在        → UPDATE（保留 hidden / user_order / favourites）
   - provider_id 不存在      → INSERT
   - 本地有但本次没有        → 标记 last_seen_at 不更新（软删除，N 天后清理）
4. 重建 FTS 索引（增量）
5. 写入 sync_log
```

**关键**：更新时**绝不能**覆盖 `hidden`、`user_order`、`favourites` 关联。这三样是用户数据。

### 5.2 EPG 同步（增量 + 过期清理）

```
1. 下载 XMLTV（流式解析，边下边入库）
2. 对每个 programme：
   - 已存在（source_id, epg_id, tvg_id, start_ts, title 相同）→ 跳过
   - 否则 → INSERT
3. 清理：DELETE FROM programmes
   WHERE source_id = ? AND epg_id = ?
     AND stop_ts < now - retention_days
4. 更新 EPG 匹配率统计
```

### 5.3 调度

| 任务 | 默认周期 | 触发时机 |
|---|---|---|
| 播放列表同步 | 24 h | 应用启动（**后台，不阻塞**）+ 定时 |
| EPG 同步 | 6 h | 应用启动（**后台，不阻塞**）+ 定时 |
| 手动刷新 | — | 用户下拉/长按 |

**关键设计**：启动时**绝不**同步等待。流程是：

```
App 启动
  ├─ 立即：读本地 SQLite → 渲染 UI（< 200 ms）
  ├─ 立即：后台任务启动 EPG/playlist 同步（tokio task，不阻塞 UI）
  └─ 同步完成 → 发事件 → UI 局部刷新（Slint 的 model 增量更新）
```

这与 TiviMate 的根本差异，也是「启动快」的来源。

---

## 7. 性能目标

| 操作 | 目标 | 措施 |
|---|---|---|
| 应用启动 → 首屏 | < 200 ms | 只读 SQLite，不读网络 |
| 15 万频道导入 | < 30 s | 流式解析 + 批量事务（1000/批）+ WAL |
| 15 万频道列表首屏 | < 50 ms | 索引 + 虚拟列表 |
| EPG 网格 100 频道 × 6h | < 16 ms | 单条 IN 查询 + 索引 |
| EPG 全量导入 (100k 节目) | < 60 s | 流式解析 + `INSERT OR IGNORE` |
| 单频道节目查询 | < 1 ms | `idx_prog_channel_time` |
| 收藏切换 | < 5 ms | 主键索引 |

---

## 8. 迁移

- `rusqlite` + `user_version` pragma 手动管理版本。
- 迁移脚本写成 Rust 数组：
  ```rust
  const MIGRATIONS: &[&str] = &[ "CREATE TABLE ...", "ALTER TABLE ...", ... ];
  fn migrate(conn: &Connection) -> Result<()> {
      let v: u32 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
      for (i, sql) in MIGRATIONS.iter().enumerate().skip(v as usize) {
          conn.execute_batch(sql)?;
          conn.pragma_update(None, "user_version", (i + 1) as i64)?;
      }
      Ok(())
  }
  ```
- **不做自动降级**（回滚靠备份文件）。

---

## 9. 备份与还原

- 导出为 **单个 ZIP**：
  ```
  iqtvlite-backup-2026-11-12.zip
  ├── db.sqlite          (WAL checkpoint 后)
  ├── secrets.json       (不含密码明文；提示用户重新输入，或用备份密码加密)
  ├── settings.json
  └── meta.json          (版本、schema version)
  ```
- 还原：校验 schema version → 兼容则替换，不兼容则提示。
- **可选端到端加密**：用用户设置的备份密码（Argon2id 派生密钥 + AES-GCM）。
