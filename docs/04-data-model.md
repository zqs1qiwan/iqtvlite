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
// xxhash-rust 0.8.19, feature "xxh3" → xxhash_rust::xxh3::xxh3_64
fn stable_id(source_id: i64, provider_key: &str) -> i64 {
    let mut buf = Vec::with_capacity(16 + provider_key.len());
    buf.extend_from_slice(&source_id.to_le_bytes());
    buf.push(0x1F);                       // 单元分隔符：id 中不可能出现
    buf.extend_from_slice(provider_key.as_bytes());
    ((xxh3_64(&buf) & 0x7FFF_FFFF_FFFF_FFFF) as i64) | 1  // 折叠成正 i64
}
```

- `0x1F` 分隔符避免 `source=1,key="23"` 与 `source=12,key="3"` 碰撞。
- 掩到 63 位并置 bit 0 → 始终是正数 SQLite `INTEGER`。100 万 id 下碰撞概率 ≈ 5×10⁻⁸。
- **`UNIQUE (source_id, provider_key)` 才是真正的护栏**；哈希冲突时加盐重算。
- `provider_key` 取值：Xtream 直播 → 数字 `stream_id`；点播 → `stream_id`；剧集 → 集 `id`；M3U → 清洗后的 `tvg-id`，否则 URL 的哈希，否则名称。

**参考实现**：ynotv 的 `generateStableStreamId()` = `` `${sourceId}_${sanitizedTvgId}` ``，冲突时加 DJB2 URL 哈希后缀，再加 `_1`/`_2`…；清洗规则 `tvgId.replace(/[^a-zA-Z0-9._-]/g,'_')`。其注释原话：*"This ensures favorites, custom groups, and EPG remain matched after re-sync."*

**增强建议**：在 `stable_id` 之外再加一张 `channel_alias` 表（`tvg-id` / 规范化名 / logo 哈希 → `channel_id`），这样频道被供应商改名后收藏仍能存活，也能跨两个源搬运同一 EPG id。

**三级 ID 策略（补充）**：

```rust
pub struct ChannelKey {
    source_id: SourceId,      // UUID
    provider_id: ProviderId,  // tvg-id | stream_id | name|group
    ordinal: u32,             // 同源内重复时的序号
}
```

- `provider_id` 缺失时用 `name|group-title` 做二级键，再缺失用 URL 的哈希。
- `ordinal` 只在**完全重复**时递增，且由「provider_id + url」的稳定排序决定 → 重新同步后顺序稳定。

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

> **建模要点**：节目单的数据量比频道大 2–3 个数量级。150 000 频道 × ~90 节目/天 × 7 天 ≈ **9400 万行 ≈ 11 GB**。
> → **必须限制保留窗口**（默认 3 天），并用**时间优先索引**支撑网格查询。

```sql
-- EPG 源（一个播放列表可以挂多个 EPG 源）
CREATE TABLE epg_sources (
    id            TEXT PRIMARY KEY,
    source_id     TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    origin        TEXT NOT NULL,          -- 'manual' | 'xtream' | 'm3u_header'
    url           TEXT NOT NULL,
    enabled       INTEGER NOT NULL DEFAULT 1,
    priority      INTEGER NOT NULL DEFAULT 30,   -- manual 10 < m3u_header 20 < xtream 30
    time_interpret TEXT NOT NULL DEFAULT 'as_written',
    time_shift_minutes INTEGER NOT NULL DEFAULT 0,
    last_success_at INTEGER,
    last_error    TEXT,
    programme_count INTEGER NOT NULL DEFAULT 0
);

-- 指南里的频道（可能匹配不到任何播放列表频道）
CREATE TABLE epg_channels (
    id            TEXT PRIMARY KEY,
    epg_source_id TEXT NOT NULL REFERENCES epg_sources(id) ON DELETE CASCADE,
    key           TEXT NOT NULL,          -- XMLTV <channel id>（或 display-name 兜底）
    first_start   INTEGER,
    last_stop     INTEGER,
    programme_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (epg_source_id, key)
);
CREATE INDEX idx_epgchan_key ON epg_channels(key);

-- 指南频道的多语言名（用于按名匹配）
CREATE TABLE epg_channel_names (
    epg_channel_id TEXT NOT NULL REFERENCES epg_channels(id) ON DELETE CASCADE,
    position       INTEGER NOT NULL,
    name           TEXT NOT NULL,
    name_key       TEXT NOT NULL,         -- 规范化后（去空格/小写/去符号）
    PRIMARY KEY (epg_channel_id, position)
) WITHOUT ROWID;
CREATE INDEX idx_epgname_key ON epg_channel_names(name_key);

-- 节目
CREATE TABLE programmes (
    id          INTEGER PRIMARY KEY,      -- ← rowid 别名，FTS5 需要它（见 §3.7 警告）
    epg_channel_id TEXT NOT NULL REFERENCES epg_channels(id) ON DELETE CASCADE,
    start_ts    INTEGER NOT NULL,         -- Unix epoch 秒 (UTC)
    stop_ts     INTEGER NOT NULL,
    title       TEXT NOT NULL,
    sub_title   TEXT,
    description TEXT,
    category    TEXT,
    episode_num TEXT,
    catchup_id  TEXT,                     -- 非 DTD 属性，Kodi 会读
    icon_url    TEXT,
    rating      TEXT,
    credits     TEXT,                     -- JSON
    previously_shown INTEGER NOT NULL DEFAULT 0,
    clumpidx    TEXT,                     -- "0/1"，同一时段多节目时排序
    raw         TEXT,
    UNIQUE (epg_channel_id, start_ts, title)
);
-- 【关键】网格查询要的是【时间切片】，所以主索引是时间优先，不是频道优先
CREATE INDEX idx_prog_time ON programmes(start_ts, epg_channel_id);
CREATE INDEX idx_prog_stop ON programmes(stop_ts);
CREATE INDEX idx_prog_chan ON programmes(epg_channel_id, start_ts);
```

**频道 ↔ 指南的匹配关系单独建表**（故意不做硬外键 —— 指南可能来自另一个源）：

```sql
CREATE TABLE epg_matches (
    channel_id     TEXT PRIMARY KEY REFERENCES channels(id) ON DELETE CASCADE,
    epg_channel_id TEXT REFERENCES epg_channels(id) ON DELETE SET NULL,
    match_source   TEXT NOT NULL,   -- 'id' | 'tvg_name' | 'name' | 'manual'
    confidence     REAL NOT NULL DEFAULT 1.0
);
```

**匹配规则（照搬 better-iptv 的严格策略，这是血的教训）**：

| 优先级 | 规则 | 分数 |
|---|---|---|
| 1 | `tvg-id` 精确匹配 `<channel id>`（大小写不敏感 + Unicode 规范化） | 1.00 |
| 2 | `tvg-name` == `<display-name>`（空格→`_` 变体也算） | 0.90 |
| 3 | 显示名 == `<display-name>` —— **仅当该频道没有 id 时才允许** | 0.75 |
| 4 | 手动指定 | 1.00（永不被覆盖） |

> ⚠️ **「有 id 的频道绝不用名字匹配」** —— 否则「另一个国家的同名频道」会串台。
> ⚠️ 指南给两个频道同名的，**丢弃并计数，绝不随便挑一个**。
> ⚠️ 匹配率低于阈值时在设置页提示（「EPG 匹配率 23%，可能是 tvg-id 不匹配」+ 提供手动修正器）。

### 3.5b Failover 组

```sql
CREATE TABLE failover_groups (
    group_id   TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE failover_members (
    stream_id TEXT PRIMARY KEY REFERENCES channel_streams(id) ON DELETE CASCADE,
    group_id  TEXT NOT NULL REFERENCES failover_groups(group_id) ON DELETE CASCADE,
    priority  INTEGER NOT NULL          -- 归一化为 0,1,2,… 不留空档
) WITHOUT ROWID;
CREATE INDEX idx_fm_group ON failover_members(group_id, priority);
```
> **一个频道最多属于一个组** —— 必须在**应用层预检**（否则 UNIQUE 约束会回滚整个事务）。

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
-- 外部内容表模式 + trigger 维护
CREATE VIRTUAL TABLE channels_fts USING fts5(
    name, tvg_name, group_title, logo,
    content='channels', content_rowid='id',
    tokenize='unicode61 remove_diacritics 2 tokenchars ''_-.\'''
);
CREATE TRIGGER ch_ai AFTER INSERT ON channels BEGIN
  INSERT INTO channels_fts(rowid,name,tvg_name,group_title,logo)
  VALUES (new.id,new.name,new.tvg_name,new.group_title,new.logo);
END;
CREATE TRIGGER ch_ad AFTER DELETE ON channels BEGIN
  INSERT INTO channels_fts(channels_fts,rowid,name,tvg_name,group_title,logo)
  VALUES ('delete',old.id,old.name,old.tvg_name,old.group_title,old.logo);
END;
CREATE TRIGGER ch_au AFTER UPDATE ON channels BEGIN
  INSERT INTO channels_fts(channels_fts,rowid,name,tvg_name,group_title,logo)
  VALUES ('delete',old.id,old.name,old.tvg_name,old.group_title,old.logo);
  INSERT INTO channels_fts(rowid,name,tvg_name,group_title,logo)
  VALUES (new.id,new.name,new.tvg_name,new.group_title,new.logo);
END;

CREATE VIRTUAL TABLE programmes_fts USING fts5(
    title, sub_title, description, category,
    content='programmes', content_rowid='id',
    tokenize='unicode61 remove_diacritics 2'
);
-- 同款 triggers（略）
```

> ⚠️ **`WITHOUT ROWID` 与 FTS5 冲突**：`programmes` 若用 `WITHOUT ROWID` 就没有 rowid，`content='programmes', content_rowid='rowid'` 会失效。
> **决策**：`programmes` **不用** `WITHOUT ROWID`，加 `id INTEGER PRIMARY KEY`（rowid 别名，8 字节/行，可忽略）。
> `epg_channel_names` / `channel_category` / `failover_members` 保留 `WITHOUT ROWID`（复合主键本身就是查找键）。

**中文分词问题**：`unicode61` 按 Unicode 空白切分，中文没有空格 → 整句变成一个 token。

| 方案 | 说明 |
|---|---|
| **A. `trigram` tokenizer** | SQLite 3.34+ 内置。支持子串匹配，中文可用（3 字以上） |
| **B. 自定义 tokenizer** | 用 `tokenchars` 扩展 + 预分词写入 shadow 表 |
| **C. 双索引** | `unicode61`（英文/编号）+ `trigram`（中文子串），查询时并集 |

**决策：采用 C（双 FTS 表）** —— 中文用 trigram 索引做子串查询，英文用 unicode61 索引做词查询，结果 UNION。简单且覆盖好。

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

### 4.0 原则：**绝不物化「网格表」**

网格是**视图**，不是表。两步查询：

```sql
-- ① 取可见频道（20–200 行），keyset 分页，绝不 SELECT *
SELECT id, name, epg_id FROM channels
 WHERE source_id = ? AND hidden = 0
 ORDER BY sort_order LIMIT ?;

-- ② 一次时间切片扫描，关联到这些频道
SELECT p.epg_channel_id, p.start_ts, p.stop_ts, p.title, p.description
  FROM programmes p
  JOIN epg_matches m ON m.epg_channel_id = p.epg_channel_id
 WHERE m.channel_id IN (?,?,…)
   AND p.start_ts < ?            -- 窗口上界
   AND p.stop_ts  > ?            -- 窗口下界
 ORDER BY p.epg_channel_id, p.start_ts;
```

**索引**：`idx_prog_time(start_ts, epg_channel_id)` 服务 `start_ts < ?` 的上界；`stop_ts > ?` 是过滤条件。
**扫描窗口要夹紧**：`start_ts >= :window_start - 3600`（超过 1 小时的节目很少见）。

### 4.1 场景预算

| 视图 | 规模 | 查询 | 目标 |
|---|---|---|---|
| 可见区 | 20 频道 × 6 h | 单条 IN 查询，~240–500 行 | < 16 ms |
| 整日 | 100 频道 × 24 h | 跳过 join，直接 `WHERE start_ts BETWEEN` 后在内存里建网格 | < 30 ms |
| 全量日 | 150 000 频道 | 不可能也不需要 —— 只对**可见频道**做时间切片 |

> better-iptv 的做法印证了这一点：整日网格直接 `SELECT epg_channel_id,start,stop,title FROM programme WHERE start>=? AND start<?`，在内存里组装。

### 4.2 避免 N+1

绝不能「先查频道列表，再对每个频道查节目」——那是 20 次往返。用**单条 IN 查询 + 分组**：

```rust
let mut by_channel: HashMap<ChannelId, Vec<Programme>> = ...;
for p in rows { by_channel.entry(p.channel_id).or_default().push(p); }
```

### 4.3 未匹配频道（EPG 有但播放列表没有）

`epg_matches` 表里没有对应行的 → 保留在 `programmes` / `epg_channels` 里，但 UI 中不显示。
**记录匹配率**（`matched / total`），低于阈值时在设置页提示并提供手动修正器。

### 4.4 EPG 数据量与保留策略

| 项 | 数值 |
|---|---|
| 150 000 频道 × ~90 节目/天 × 7 天 | ≈ **9400 万行 ≈ 11 GB** |
| **默认保留窗口** | **3 天** |
| 清理规则 | 「结束时间早于该频道首个新节目起点，且已结束超过 1 天」的行才删 |

---

## 4.5 同步与导入

### 4.5.1 播放列表同步（增量）

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

### 4.5.2 EPG 同步（增量 + 过期清理）

```
1. 下载 XMLTV（流式解析，边下边入库）
2. 对每个 programme：已存在 → 跳过；否则 → INSERT
3. 清理：DELETE FROM programmes WHERE stop_ts < now - retention_days
4. 更新 EPG 匹配率统计
```

### 4.5.3 导入配方（实测有效，来自 better-iptv）

大批量导入用 **staging DB + ATTACH + 逐频道事务交换**，不要直接往主库插：

```
主库连接   : journal_mode=WAL; synchronous=NORMAL; foreign_keys=OFF;
             temp_store=MEMORY; cache_size=-2048; wal_autocheckpoint=0; journal_size_limit=4MiB
staging DB : ATTACH 'ingest.db' AS ingest   (journal_mode=OFF, synchronous=OFF)
staging 表 : seq INTEGER PRIMARY KEY, knum INTEGER, start, stop, keep, title, description, category
             + INDEX (knum)
交换       : INSERT OR FAIL INTO programmes SELECT … FROM ingest.epg_ingest
             WHERE knum = ? AND keep = 1     -- 每个频道一个事务，块大小 20 000 行
```

> **`foreign_keys=OFF` 是刻意的**：开着 FK 时，SQLite 会给每条插入子表的记录分配 statement journal，在 `temp_store=memory` 下会把所有复用页留在内存里。
> 实测：4.3 万 ~ 43 万节目的交换事务约 50–135 ms。

### 4.5.4 调度

| 任务 | 默认周期 | 触发时机 |
|---|---|---|
| 播放列表同步 | 24 h | 应用启动（**后台，不阻塞**）+ 定时 |
| EPG 同步 | 6 h | 应用启动（**后台，不阻塞**）+ 定时 |
| 手动刷新 | — | 用户长按 |

**关键设计**：启动时**绝不**同步等待。流程是：

```
App 启动
  ├─ 立即：读本地 SQLite → 渲染 UI（< 200 ms）
  ├─ 立即：后台任务启动 EPG/playlist 同步（tokio task，不阻塞 UI）
  └─ 同步完成 → 发事件 → UI 局部刷新（Slint 的 model 增量更新）
```

这与 TiviMate 的根本差异，也是「启动快」的来源。

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

## 6. 性能目标

| 操作 | 目标 | 措施 |
|---|---|---|
| 应用启动 → 首屏 | < 200 ms | 只读 SQLite，不读网络 |
| 15 万频道导入 | < 30 s | 流式解析 + 批量事务（1000/批）+ WAL |
| 15 万频道列表首屏 | < 50 ms | 索引 + 虚拟列表 |
| EPG 网格 100 频道 × 6h | < 16 ms | 单条 IN 查询 + `idx_prog_time` |
| EPG 全量导入 (100k 节目) | < 60 s | 流式解析 + staging DB 交换 |
| 单频道节目查询 | < 1 ms | `idx_prog_chan` |
| 收藏切换 | < 5 ms | 主键索引 |
| 15 万频道 × 3 天 EPG 落库体积 | < 5 GB | 3 天保留窗口 |

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
