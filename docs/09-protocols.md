# 09 — 协议层实现指南

> 从 `docs/research/domain-protocols-reference.md`（调研原始记录，78 KB）提炼的**可执行实现指南**。
> 每条都标注了置信度：**已验证** / **[未验证]** / **[否定]（该说法不成立）**。

---

## 0. 三条最容易被搞错的（先看这个）

| # | 常见说法 | 实际 | 依据 |
|---|---|---|---|
| 1 | Xtream 有 `action=get_short_episodes` / `get_episodes` | ❌ **不存在**。用 `get_series_info` | grep.app 全库 0 命中；所有 API 参考中均无 |
| 2 | Xtream JSON 的 `title`/`description` 是明文 | ❌ **是 base64**（`RGVtbyBOb3c=` → `Demo Now`）。XMLTV 才是明文 | 面板 PHP 源码 |
| 3 | `catchup="custom"` 是一种模式 | ❌ 不是。自定义模板模式的真名是 **`default`** | Kodi `CatchupMode` 枚举 |

---

## 1. M3U / M3U+

### 1.1 结构

```
#EXTM3U <header attrs>
#EXTINF:<duration> attr="value",<Display Name>
#EXTGRP:<Group>
#EXTVLCOPT:http-user-agent=...
#KODIPROP:inputstream=inputstream.adaptive
<stream URL>
```

**关键解析规则**：

| 规则 | 说明 |
|---|---|
| **显示名 = 最后一个逗号之后** | `content.lastIndexOf(',')`。按第一个逗号切会破坏 `tvg-name="Harbour News, Coast"` |
| 属性值引号 | 优先 `"…"`，其次 `'…'`，最后裸值 |
| `group-title` 是权威分类 | **可多组，用 `;` 分隔** |
| `#EXTGRP:` 是「开始指令」 | Kodi 语义：设置后续条目的默认组，直到下一个 `#EXTGRP` 或条目自带 `group-title`。**`group-title` 优先** |
| `#EXTINF` 可省略 | 允许裸 URL 列表 |
| 编码 | UTF-8 无 BOM；iptv-org 要求 CRLF |

### 1.2 属性全集

**`#EXTM3U` 头**：
`url-tvg` · `x-tvg-url`（同义）· `url-logo` · `tvg-shift` · `catchup`/`catchup-type` · `catchup-source` · `catchup-days` · `catchup-time` · `catchup-correction` · `catchup-enable` · `timeshift` · `max-conn` · `billed-till` · `billed-msg` · `refresh` · `refresh_at` · `m3uautoload` · `user-agent`/`http-user-agent` · `provider` · `provider-type` · `provider-logo` · `provider-countries` · `provider-languages`

**`#EXTINF` 频道级**：
`tvg-id` · `tvg-name` · `tvg-logo`（别名 `logo`/`tvg-icon`）· `tvg-chno`（别名 `ch-number`/`tvg-ch`/`channel-id`/`tvh-chnum`）· `tvg-language` · `tvg-country` · `tvg-shift` · `tvg-rec` · `tvg-url` · `group-title` · `radio` · `adult`/`parent-code` · `audio-track`（`"eng,rus"`，首个为默认）· `video-track` · `aspect-ratio` · `type`/`tvg-type` · `content-type` · `catchup`/`catchup-type`/`catchup-mode` · `catchup-source`/`catchup-url` · `catchup-days`/`catchup-days-max`/`catchup-range` · `catchup-time` · `catchup-correction` · `catchup-enable` · `timeshift` · `media`/`media-dir`/`media-size` · `user-agent`/`http-user-agent` · `referrer`/`http-referrer` · `realtime`

**`#EXTVLCOPT:`** → `http-user-agent` / `http-referrer` 会被 Kodi 提升为真实 HTTP 头。**`#EXTHTTP:`** 是 OTT Nav 的等价物。

> ❌ **`x-display-name` 和 `x-cc` 不是 IPTV 属性** —— 全库检索 197 / 16 次命中全部是 OpenAPI 标签、RabbitMQ 头等噪音。**直接忽略。**

### 1.3 EPG 源 URL

- `url-tvg` 与 `x-tvg-url` 是**同义词**（Kodi 只读后者，OTT Nav 两个都读）。
- **多个地址用 `,` 或 `;` 分隔** → 两个都读、都拆、逐个尝试。
- 有些播放列表把 `url-tvg` 放在 `#EXTINF` 行上（非标准但真实存在）。
- 压缩：`.xml` / `.xml.gz` / `.xz`。**OTT Nav 按内容探测而非扩展名**。`.zip` 忽略。

### 1.4 iptv-org 约定

- Stream id = `<ChannelID>[@<FeedID>]`，如 `ExampleTV.us@HD`
- ChannelID = 去掉空格的名称 + `.` + 国家码
- 标题格式：`名称 (清晰度) [标签]`，清晰度 ∈ `2160p|1080p|720p|480p|360p`，标签 ∈ `Geo-blocked|Not 24/7`
- 标题**不得含** `,` `[` `]`
- 协议：`HTTPS HTTP MMS MMSH RTSP RTMP SRT RTP UDP`

---

## 2. Catch-up / 时移（产品核心功能，必须精确）

### 2.1 模式枚举（Kodi `CatchupMode` 权威）

```
Disabled=0, Default=1, Append=2, Shift=3, Flussonic=4, XtreamCodes=5, Timeshift=6, Vod=7
```
（`crispy-iptv-types` 的 serde 值：`default|append|shift|timeshift|flussonic|fs|xc|vod`）

### 2.2 属性语义

| 属性 | 语义 |
|---|---|
| `catchup` | 模式。**`custom` 不是合法值**，自定义模板模式叫 `default` |
| `catchup-source` | `default` 模式：**这就是完整的归档 URL**（含占位符）。`append` 模式：追加到直播 URL 的**查询串**。含 `/` 的相对路径按 `m3u-editor` 的规则总是被追加；绝对路径总是原样使用 |
| `catchup-days` | 归档天数。**`catchup-days="0"` 表示该频道无归档** |
| `catchup-time` | 归档时长，**单位是秒**（`10800` = 3 小时）。与 `catchup-days` 并存时**取大者** |
| `catchup-correction` | 生成归档 URL 时的小时校正（修「时移差一小时」） |
| `catchup-enable` | `1`/`0`，频道级覆盖头部设置 |
| `tvg-rec` | `1` = 有归档；> 1 = 天数 |
| `timeshift` | **天数**（legacy SIPTV）。若头部未声明归档类型，它隐含 `catchup-type="timeshift"` |

**优先级（已由测试验证）**：`catchup-days` > `tvg-rec` > `timeshift`。
> **`tvg-shift` 不是归档窗口**，它是 **EPG 时间偏移**。二者极易混淆。

**哪些频道有归档**：若**没有任何**频道带归档属性但头部声明了 → **所有**频道都有。一旦有一个频道带了 → 只有自带标记的频道有。

### 2.3 模式 → URL 规则

| 模式 | 生成的 URL |
|---|---|
| `default` | `catchup-source` 原样（无 source 时退化为 `shift`） |
| `append` | `{live_url}{catchup-source}` |
| `shift` | `{live_url}?utc={utc}&lutc={lutc}`（live URL 已有 query 则用 `&`） |
| `xc` | `{host}/timeshift/{user}/{pass}/{duration:60}/{Y}-{m}-{d}:{H}-{M}/{id}.{ext}` |
| `flussonic` | 见下表（按直播 URL 形状自动判定） |
| `vod` | 用 `{catchup-id}`，**无时移** |

### 2.4 Flussonic（按直播 URL 形状自动判定）

| 直播 URL | 归档 URL |
|---|---|
| `http://ch01.spr24.net/151/mpegts?token=T` | `http://ch01.spr24.net/151/timeshift_abs-${start}.ts?token=T` |
| `http://list.tv:8888/325/index.m3u8?token=S` | `http://list.tv:8888/325/timeshift_rel-{offset:1}.m3u8?token=S` |
| `http://list.tv:8888/325/mono.m3u8?token=S` | `http://list.tv:8888/325/mono-timeshift_rel-{offset:1}.m3u8?token=S` |
| `http://list.tv:8888/325/live?token=T`（TS 提示） | `http://list.tv:8888/325/timeshift_abs-${start}.ts?token=T` |

> **Kodi 与 OTT Navigator 在 `flussonic-hls` 上不一致**：OTT Nav 发 `archive-{start}-{duration}.m3u8` / `video-{start}-{duration}.m3u8`。**两种都支持**，按直播 URL 形状判定。

### 2.5 占位符全集

**Kodi（事实标准）**：

| 占位符 | 含义 |
|---|---|
| `{utc}` / `${start}` | 节目开始时间，unix epoch |
| `{lutc}` / `${now}` / `${timestamp}` | 当前时间，unix epoch |
| `{utcend}` / `${end}` | 开始 + 时长 |
| `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` | 节目开始时间的年月日时分秒 |
| `{duration}` / `${duration}` | 节目时长（含缓冲） |
| `{duration:X}` | 时长 ÷ X 秒（X ≥ 1；`{duration:60}` = 分钟） |
| `{offset:X}` | (now − start) ÷ X 秒 |
| `{catchup-id}` | `<programme>` 上的 `catchup-id` 属性（**非 DTD 标准属性**，但 Kodi 读它） |

所有时间占位符都支持附加格式串（`Y m d H M S`）：
`?utc={utc}&lutc={lutc}` · `&t={Y}-{m}-{d}-{H}-{M}-{S}` · `?offset={offset:60}` · `?start={utc:YmdHM}&end=${end:YmdHM}`

**OTT Nav 扩展**：`{start_iso}`/`{end_iso}`/`{now_iso}` · `${offset}` · `{durmin}` · `${start-year}`…`${end-sec}`（设备本地）· `${token}`/`{key}` · `${login}`/`${password}` · `{catchup-id}`
**时移语法**：`{utc:-5m}`、`${start:+1h}`、整 URL 包裹 `{{shift:start:-5m}}`（一个 URL 只能一个）

**真实存在但无规范（防御性支持）**：
- **双花括号 `{{…}}`**（JioTV）：`catchup="vod" catchup-source="…&showtime={{H}}{{M}}{{S}}&srno={{Y}}{{m}}{{d}}&programId={{catchup-id}}"`
- **Java 风格 `${(b)yyyyMMddHHmmss}`**（`(b)`=开始，`(e)`=结束）—— **中国移动/咪咕系播放列表在用**：
  `catchup="append" catchup-source="?playbackbegin=${(b)yyyyMMddHHmmss}&playbackend=${(e)yyyyMMddHHmmss}"`

### 2.6 跳转算法

```
1. 用户点节目 → 取 (start, stop)
2. 解析频道的 catchup 配置（模式、模板、天数、粒度）
   粒度：模板含 {utc}/{start}/{S}/{offset:1} → 1 秒；否则 60 秒（时长向下取整到粒度）
3. duration = min((stop - start) + 5 分钟, 480 分钟)   ← 多要 5 分钟！
   并夹紧 start + duration ≤ now
4. 替换模板占位符
5. 播放
```

> **为什么要 +5 分钟**：IPTV 归档通常比直播**滞后 30 秒 ~ 2 分钟**，只请求节目原始时长会把上一个节目的尾巴带进来、并切掉结尾。Dispatcharr 用 `DURATION_BUFFER_MINUTES = 5`，`MAX_DURATION_MINUTES = 480`，`DEFAULT_DURATION_MINUTES = 120`。

> **Xtream 时移的时间是供应商本地墙钟时间**，而 EPG 是 UTC → 必须用 `server_info.timezone` 转换。`m3u-editor` 明确记录了这个 bug 与修法。

### 2.7 「时移」的三种不同含义

1. **SIPTV `timeshift` 属性** —— legacy，单位是**天数**，输出等同 `shift`。
2. **`catchup="shift"`** —— 服务端滚动归档，改写直播 URL 加 `?utc={utc}&lutc={lutc}`。
3. **播放器本地暂停缓冲** —— 与供应商无关，纯客户端缓存（ynotv 订阅 mpv 的 `demuxer-cache-state` 事件实现）。

外加第四种：**Flussonic 的滑动回看** `http://host/STREAM/rewind-{window_seconds}.m3u8`（数字是**窗口长度**，不是偏移）。

---

## 3. Xtream Codes API

### 3.1 端点

| 文件 | 用途 |
|---|---|
| `player_api.php` | 播放器 API（主） |
| `xmltv.php` | 节目单 |
| `get.php` | M3U 导出 |
| `panel_api.php` | 批量操作；原版面板只支持 `action=get_epg` |
| `portal.php` | MAG/Stalker |

默认端口 **HTTP 25461 / HTTPS 25463**。
> 判活技巧：在浏览器打开 `http://server:port/panel_api.php`，能返回 JSON 就是 Xtream 面板。

### 3.2 `player_api.php` 动作全表

Base: `http://SERVER:PORT/player_api.php?username=USER&password=PASS`

| action | 额外参数 | 返回 |
|---|---|---|
| 无 / `get_profile` | — | `{user_info, server_info}` |
| `get_live_categories` | — | 直播分类 |
| `get_live_streams` | `category_id`（可选） | 直播频道 |
| `get_vod_categories` | — | 点播分类 |
| `get_vod_streams` | `category_id`（可选） | 电影 |
| `get_vod_info` | `vod_id` | 单个电影 |
| `get_series_categories` | — | 剧集分类 |
| `get_series` | `category_id`（可选） | 剧集列表 |
| `get_series_info` | `series_id` | 剧集 + 季 + 集 |
| `get_short_epg` | `stream_id`, `limit`（可选，**默认 4**） | 即将播出的节目 |
| `get_simple_data_table` | `stream_id` | 单频道完整节目单 |

`panel_api.php` 另有 `action=get_epg` + `stream_id` + `from_now=1`（Unix 秒时间戳；`from_now=1` 只返回未结束的节目；已过期返回 `[]`）。

> `action=search`、`get_vod_latest`、`get_series_latest` 是 **Xtream-Masters 专属**，标准面板没有。

### 3.3 JSON 字段（精确）

**登录**：
```json
{"user_info":{"username":"U","password":"P","auth":1,"status":"Active",
  "exp_date":"1758143248","is_trial":"0","active_cons":"0","created_at":"1755551248",
  "max_connections":"1","allowed_output_formats":["m3u8","ts","rtmp"]},
 "server_info":{"url":"host","port":"80","https_port":"","server_protocol":"http",
  "rtmp_port":"8001","timezone":"Africa/Casablanca","timestamp_now":1756823649,
  "time_now":"2025-09-02 15:34:09","xui":true,"version":"1.5.12"}}
```
⚠️ **数字字段都是字符串**；`auth` 是 `1`/`0`。

**直播频道**（`get_live_streams`）：`num` · `name` · `stream_type`("live") · `stream_id`(int) · `stream_icon` · `thumbnail` · `epg_channel_id` · `added`(unix 秒，字符串) · `category_id` · `category_ids`(数组) · `custom_sid` · `tv_archive`(0/1) · `direct_source` · `tv_archive_duration`(天) · `is_adult`

**电影**（`get_vod_streams`）：`num` · `name` · `title` · `year` · `stream_type`("movie") · `stream_id` · `stream_icon` · `rating`(0–10) · `rating_5based`(= `ceil(rating/2.0)`) · `genre` · `added` · `episode_run_time` · `category_id` · `category_ids` · `container_extension`(可 **null**) · `custom_sid` · `direct_source` · `release_date`/`releasedate` · `cast` · `director` · `plot` · `youtube_trailer`

**电影详情**（`get_vod_info`）→ `{info:{…}, movie_data:{…}}`；`info` 键含 `kinopoisk_url` · `tmdb_id` · `name` · `o_name` · `cover_big` · `movie_image` · `release_date` · `episode_run_time` · `youtube_trailer` · `director` · `actors` · `cast` · `description` · `plot` · `age` · `mpaa_rating` · `country` · `genre` · `backdrop_path` · `duration_secs` · `duration` · `bitrate` · `releasedate` · `subtitles` · `rating` · `tmdb`

**剧集列表**（`get_series`）：`num` · `name` · `title` · `year` · `series_id` · `stream_type`("series") · `cover` · `plot` · `cast` · `director` · `genre` · `release_date`/`releaseDate` · `last_modified` · `rating` · `rating_5based` · `backdrop_path` · `youtube_trailer` · `episode_run_time` · `category_id` · `category_ids`

**剧集详情**（`get_series_info`）：
```json
{"seasons":[{"id":1,"name":"Season 1","episode_count":10,"overview":"…","air_date":"2026-01-01",
   "cover":"…","season_number":1,"cover_big":"…","vote_average":0}],
 "info":{ /* series 字段 */ },
 "episodes":{"1":[{"id":"5523","episode_num":"1","title":"Ep 1","container_extension":"mp4",
   "info":{"release_date":"","plot":"","duration_secs":0,"duration":"00:00:00","tmdb_id":"",
           "bitrate":0,"rating":"","season":"1","cover_big":null,"movie_image":null},
   "subtitles":[],"custom_sid":"","added":1792058766,"season":1,"direct_source":""}]}}
```
⚠️ 集的 `id` 通常是**字符串**。

**EPG**（`get_short_epg` / `get_simple_data_table`）：
```json
{"epg_listings":[{"id":"1792056966","epg_id":"20","channel_id":"demo.channel",
 "title":"RGVtbyBOb3c=","description":"PG5vdz4gJiAidGhlbiI=","lang":"",
 "start":"2026-10-15 10:36:06","end":1792060566,"start_timestamp":1792056966,
 "stop_timestamp":1792060566,"stop":"2026-10-15 11:36:06","now_playing":0,"has_archive":0}]}
```
⚠️ **title/description 是 base64**。`start`/`end`/`stop` 是**服务器本地时间**字符串；**优先用 `start_timestamp`/`stop_timestamp`**（Unix epoch）。

**分类**：现代面板返回**对象数组** `[{"category_id":"1","category_name":"News","parent_id":0}]`。
经典的数组套数组 `[["1","News"]]` 被广泛报告但**未能从一手来源确认 [未验证]** → **两种都支持**。

### 3.4 流 URL 构造

```
直播   : http://HOST:PORT/live/USER/PASS/STREAM_ID.ts        （或 .m3u8）
电影   : http://HOST:PORT/movie/USER/PASS/STREAM_ID.{container_extension}
剧集   : http://HOST:PORT/series/USER/PASS/EPISODE_ID.{container_extension}
时移   : http://HOST:PORT/timeshift/USER/PASS/{minutes}/YYYY-MM-DD:HH-MM/STREAM_ID.ts
HLS 分片: http://HOST:PORT/hls/USER/PASS/STREAM_ID/{segment}.ts
M3U    : http://HOST:PORT/get.php?username=U&password=P&type=m3u_plus&output=ts
```

- 面板**从不返回完整 URL**，客户端自己拼。
- `user_info.allowed_output_formats`（如 `["m3u8","ts","rtmp"]`）决定直播容器：
  空列表 → 用偏好值；偏好值在列表里 → 用偏好值；否则 → **用列表第一个**。
- `container_extension` 可能为 **null** → 默认 `mp4`（点播）/ `ts`（直播）。
- 凭据要 **百分号编码**。

---

## 4. XMLTV

### 4.1 DTD 关键点

```xml
<!ELEMENT tv (channel*, programme*)>
<!ELEMENT channel (display-name+, icon*, url*) >
<!ATTLIST channel id CDATA #REQUIRED >
<!ELEMENT programme (title+, sub-title*, desc*, credits?, date?, category*,
                     keyword*, language?, orig-language?, length?, icon*, url*,
                     country*, episode-num*, video?, audio?, previously-shown?,
                     premiere?, last-chance?, new?, subtitles*, rating*,
                     star-rating*, review*, image* )>
<!ATTLIST programme start CDATA #REQUIRED  stop CDATA #IMPLIED
                    pdc-start CDATA #IMPLIED  vps-start CDATA #IMPLIED
                    showview CDATA #IMPLIED  videoplus CDATA #IMPLIED
                    channel CDATA #REQUIRED  clumpidx CDATA "0/1" >
```

- `credits` = `(director*, actor*, writer*, adapter*, producer*, composer*, editor*, presenter*, commentator*, guest*)`
- `length` 的 `units` **必填**，∈ `seconds|minutes|hours`
- `episode-num` 的 `system` 默认 `"onscreen"`；`xmltv_ns` 是**从 0 开始**的点分数字，可带 `X/Y`
- `previously-shown` EMPTY，属性 `start`/`channel`
- `subtitles` 的 `type` ∈ `teletext|onscreen|deaf-signed`
- `rating`/`star-rating` 的 `system`；`review` 的 `type` **必填**（`text|url`）
- `image` 的 `type` ∈ `poster|backdrop|still|person|character`
- ⚠️ **子元素顺序有意义** —— 严格校验器会拒绝乱序。

### 4.2 时间格式

DTD 原文：*"They can be `'YYYYMMDDhhmmss'` or some initial substring… You can also append a timezone to the end; **if no explicit timezone is given, UTC is assumed**."*

严格解析规则（已单测验证）：
- 数字段长度必须是 **4|6|8|10|12|14**；缺月日 → `1`；缺时分秒 → `0`。
- `24:00:00` 接受为**次日**午夜；其他 24 点和 60 秒拒绝。
- 时区 token = 数字后第一个空白分隔词（`"… +0200 CEST"` → `+0200`）。
- 偏移：`+0200` / `+02:00` / `-0500`；`Z`/`UTC`/`GMT` = 0；`BST` = +60；其他（`CEST`、`+2`、`0200`）= **未知**。
- **缺时区 = UTC**（按 DTD）。但很多供应商写本地时间却标 `+0000` → 需要 `tvg-shift` / `catchup-correction` / 「视为本地时间」开关。

### 4.3 频道匹配

- M3U `tvg-id` ≡ Xtream `epg_channel_id` ≡ XMLTV `<channel id>`。
- Kodi 三级匹配：① `<channel id>` == `tvg-id`；② `<display-name>` == `tvg-name`；③ `<display-name>` == M3U 显示名。
- **better-iptv 更严格**：**有 id 的频道绝不用名字匹配** —— 否则「另一个国家的同名频道」会串台。只有**没有 id** 的频道才按名字匹配。指南给两个频道同名的，**丢弃并计数，绝不随便挑一个**。
- ⚠️ `epg_channel_id` 可能含多个以 `;`/`,` 分隔的 id → 拆开逐个试。**[广泛报告，未从一手来源确认，但支持成本极低]**

---

## 5. 供应商怪癖（产品化清单）

### 5.1 连接数限制

> better-iptv FAQ 原文：*"Many providers allow one stream per line and **count the previous one as open for a little while after you stop it**, so quick zapping trips the limit. Some of them are more generous with players they recognise."*

- 宽限期**因供应商而异**，公开资料里**没有确切数字**（常说的「30 秒」是**民间经验，非一手来源**）。
- `user_info.active_cons` vs `max_connections` 可查当前占用。**录制和时移各占一个连接。**
- 对策：完全关闭上一条 HTTP 连接再开新的；按源串行化打开；被拒后等 1–3 秒重试一次；**保持单一出口 IP**（多 IP 会被封号）。

### 5.2 User-Agent 拦截

真实在用的 UA 字符串：

| 来源 | UA |
|---|---|
| better-iptv | `TiviMate/4.7.0 (Linux;Android 10) ExoPlayerLib/2.18.1` / `VLC/3.0.20 LibVLC/3.0.20` |
| Dispatcharr | `TiviMate/5.1.6 (Android 12)` |
| nodecast-tv | `TiviMate/4.7.0` / `VLC/3.0.20 LibVLC/3.0.20` |
| OTT Navigator | `OTT Navigator/1.7.5.2 (Linux;Android 12; en; HASH) ExoPlayerLib/2.x.x` |

**UA 必须同时用于播放列表下载、EPG 下载和每一个流请求。**

**诊断技巧（值得抄）**：用播放器 UA 和浏览器 UA 各探测一次。
浏览器 UA 能通、播放器 UA 不通 → 服务器**在封客户端**，不是流死了。

### 5.3 HTTP/HTTPS

- `server_info.https_port` 常为空，流端口可能根本没配 TLS。
- **用 `http://`，除非配了 `https_port`**。
- 绝不要在 WebView 里混 `https://` 的 EPG 和 `http://` 的流（mixed-content 拦截）。

### 5.4 EPG 匹配失败的五个原因

1. `tvg-id` ≠ `<channel id>`（默认**大小写敏感**）
2. 指南只有 `display-name` 没有 id
3. `epg_channel_id` 含多个 id
4. **指南时间偏了几小时**（表现为「现在没节目」）
5. 匹配压根没算

→ 必须提供诊断 UI：已匹配/总数、用的哪个 `tvg-id`、指南的候选 `display-name`、**手动修正的频道选择器**。

### 5.5 TS vs HLS

- `allowed_output_formats` 决定线路能用哪种。
- **`.ts`**：换台更稳（不用重取 manifest），但很多面板用 chunked HTTP 发送，部分播放器会卡。
- **`.m3u8`**：长播更稳、有真实 seek 点，但每次换台要重取 manifest。
- → **提供用户设置（TS / HLS / 自动），按源记忆**。
- 时移方面：HLS 归档 URL 返回 VOD 分片列表（好 seek）；TS 归档 URL 是一条连续流。

### 5.6 DRM 检测（必须做，否则会卡死）

从 HLS master manifest 找 `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`：

| 标识 | 系统 |
|---|---|
| `com.widevine.alpha` / `edef8ba9-79d6-4ace-a3c8-27dcd51d21ed` | Widevine |
| `com.apple.streamingkeydelivery` / `skd://` | FairPlay |
| `playready` / `9a04f079-9840-4286-ab92-e65be0885f95` | PlayReady |
| `sample-aes` | Sample-AES |

> **FFmpeg / mpv 无法解密 Widevine、FairPlay、PlayReady。** mpv 播放 Widevine 流是**不可播放**的。
> → 检测到 DRM 就标记该频道，路由到外部播放器/WebView，或明确报错。**绝不能让播放器在 DRM 流上无限转圈。**

### 5.7 地理封锁状态码

```
403 Forbidden              → 经典 geo/IP 封锁
451 Unavailable For Legal Reasons (RFC 7725) → 授权问题，不是地理
426 Upgrade Required       → TLS/版本
423 Locked
```
另外：**封锁可能藏在 manifest 内部**（playlist 200，segment 403）→ **要探测分片，不能只探测 playlist**。

### 5.8 占位流（死频道）

```
/video/black.ts   /black.ts   /blank.ts   /video/blank.ts
/placeholder.ts   /video/placeholder.ts   /null.ts   /video/null.ts
```
返回 200 的这些路径 = 死频道。

### 5.9 其他

- `container_extension` 可为 null → 默认 `mp4`/`ts`
- `get_vod_info` 对不存在的 id 返回 `info: []`（不是错误）
- `tmdb_id` 常为 `''`，真 id 在 `info.tmdb`
- Xtream JSON 的 EPG 标题是 **base64**，XMLTV 是明文
- 数字字段都是字符串
- 指南时间可能本地时间标 `+0000`
- **归档滞后** → 请求时长 +5 分钟
- **Xtream 时移起点是供应商本地时间** → 用 `server_info.timezone` 换算
- XMLTV `<programme>` 可能缺 `stop` → 用下一个节目的 start 推断
- `clumpidx="0/1"` → 同一时段可能有多个节目（新闻/天气），按它排序

---

## 6. Failover 组（自动故障切换）

ynotv 的实现是最完整的开源参考。

**模型**：`failover_groups(group_id, name, created_at)` + `failover_members(stream_id UNIQUE, group_id, priority)`；优先级归一化为 0,1,2,… 不留空档；**一个频道最多属于一个组**（应用层预检，否则 UNIQUE 约束会回滚整个事务）。

**自动聚类**从频道名解析：
- 国家前缀（`[US]`、`(US)`、`US | `）
- 东西向信号（`east|est|(east)|(ny)` vs `west|pst|(west)|(la)`）
- 美国呼号（`(WABC)`、`KOVR`）+ 一份 ~45 词的英文停用词表（`WITH WHEN WHAT WILL WANT WELL WILD WAVE WARS …`）
- 清晰度：`4k|8k|uhd`→4k；`fhd|1080p|1080i|raw`→fhd；`hd|720p`→hd；否则 sd

**硬性规则（任一不满足即不成对）**：
1. **信号分离** —— `strict_separate` 拒绝任何东西向不匹配；默认 `merge_neutral_east` 把中性视为东向兼容，但**仍拒绝东 vs 西**
2. **国家兼容** —— `same_only`（默认）/ `acceptable_set` / `any`
3. **不同呼号 → 绝不关联**（不同的本地台）；同呼号 → 0.98

**评分**：跨源精确同名 → **0.98**；同名不同清晰度 → **0.96**；token Jaccard ≥ 0.8 → **0.88**；≥ 0.6 → **0.76**。默认 `minConfidence` **0.75**。清晰度优先级 `4k(0) > fhd(1) > hd(2) > sd(3)`。

UI：「Always Play Primary」+ 自动聚类弹窗 + 体育队模式（一个频道列表 = 一条有序 failover 链 + 自动切换）。

> **建议**：v1 实现 (1) 每源健康探活（`alive|dead|geoblocked|drm|placeholder`）+ (2) **手动** failover 组 + (3) 自动聚类放到后面。**自动聚类绝不能当事实来源** —— 用户放松国家规则后，它会很乐意把两个国家的「CNN」连起来。

---

## 7. Stalker / Ministra —— **推迟到 v2**

**理由**（不是「难」，是「不划算」）：
1. **认证模型完全不同** —— 没有用户名密码，靠 **MAC 地址伪装成 MAG 机顶盒** + token。
2. **每次换台多一次 `create_link` 往返**，在延迟敏感路径上。
3. **每个门户的怪癖压倒一切** —— 页码 0/1 基、`type=series` 常缺失（要在 `type=vod` 里用 `is_series=1` 找剧）、单集的 `cmd` 要先 `get_ordered_list(episode_id=…)` 单独取、搜索可能被静默忽略。

最完整的开源实现（ynotv）花了 **137 KB 的 `stalker-client.ts` + 六个测试文件**。

**v1 范围**：M3U + Xtream + catch-up + failover。**v2 再加 Stalker**，独立 crate + 独立 session/token 管理器，用 feature flag 控制。

> **若目标市场是纯 EU/MENA 的 Mag-box 用户，则反过来优先做 Stalker。**
