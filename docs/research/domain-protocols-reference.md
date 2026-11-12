# IPTV Domain Protocols & Data Models — Verified Reference

Research date: 2026-11-12. Every claim below is tagged with its source. Anything I could
not confirm from a primary source is flagged **[UNVERIFIED]** or **[CONTRADICTED]**.

Primary sources used (URLs are stable):
- XMLTV DTD: https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd
- Kodi `pvr.iptvsimple` README (branch `Piers`): https://github.com/kodi-pvr/pvr.iptvsimple/blob/Piers/README.md
- Kodi sources: `src/iptvsimple/CatchupController.cpp`, `src/iptvsimple/data/Channel.cpp`, `src/iptvsimple/data/EpgEntry.cpp`
- OTT Navigator FAQ: https://faq.ott-nav.com/formats/m3u , https://faq.ott-nav.com/formats/catchup , https://faq.ott-nav.com/formats/user-agent
- `m3ue/m3u-editor` (Laravel Xtream/M3U panel): https://github.com/m3ue/m3u-editor
- `moabualruz/crispy-*` Rust crates (crispy-m3u, crispy-xtream, crispy-catchup, crispy-stalker, crispy-iptv-types)
- `tbeezy/ynotv`: https://github.com/tbeezy/ynotv
- `mewset/better-iptv`: https://github.com/mewset/better-iptv
- `iptv-org/iptv`: https://github.com/iptv-org/iptv
- `Dispatcharr/Dispatcharr` `apps/timeshift/helpers.py`
- `gtaman92/XtreamCodesExtendAPI` `player_api.php`
- `Fazzani/xtreamcode` `xcodestream/url_api_builder.py`
- `QUASSI` stalker write-up: https://www.quassi.nl/2020/03/23/the-wonderful-world-of-stalker-iptv-2/
- `esxbr/plugin.video.stalker`, `shubhsheth/stb-reader`, `agsimeonov/StalkerTalker`, `erkexzcx/stalkerhek`

---

## 1. M3U / M3U+ playlist format

### 1.1 File structure

```
#EXTM3U <header attrs>
#EXTINF:<duration> attr="value" attr2="value2",<Display Name>
#EXTGRP:<Group>
#EXTVLCOPT:http-user-agent=...
#KODIPROP:inputstream=inputstream.adaptive
#WEBPROP:web-regex="..."
<stream URL>
```

- `#EXTINF:<duration>`: `-1` = unknown/infinite (live), `0` tolerated, or real seconds (VOD).
  Duration is ignored for live.
- **Display name = everything after the LAST comma.** `ynotv` uses `content.lastIndexOf(',')`
  (`packages/local-adapter/src/m3u-parser.ts`). Kodi's parser and Emby's docs warn that
  splitting on the *first* comma breaks attribute values containing commas
  (`tvg-name="Harbour News, Coast"`). → **Use lastIndexOf.**
- Attribute values: double-quoted is the convention, but real parsers accept single-quoted
  and unquoted. `ynotv` `extractAttribute()` tries `"…"`, then `'…'`, then bare.
  `notsurewhoisthis/iptv-parser` README: "Quoted or unquoted values supported; quotes can be
  single or double."
- URL line may carry Kodi header suffixes: `|user-agent=<ua>`, `|name1=val1&name2=val2`;
  prefix unsupported headers with `!` (`|!name=val`). Kodi README.
- `#EXTGRP:` — **begin directive** in Kodi: sets groups for all following entries until the
  next `#EXTGRP:` or until an entry carries its own `group-title`. `crispy-m3u`
  `src/parser.rs` implements exactly that and splits the value on `;`.
  Axenov Lab IPTV docs: `group-title` is "functionally identical to `#EXTGRP`".
  → **Treat `group-title` as the authoritative per-channel category; treat `#EXTGRP` as a
  category default that a later `group-title` overrides.**
- `#EXTINF` may be omitted entirely (bare URL list) — `crispy-m3u` accepts this.
- Encoding: UTF-8 without BOM; CRLF line endings required by iptv-org's linter.

### 1.2 Complete attribute inventory

**`#EXTM3U` header attributes**

| Attribute | Meaning | Source |
|---|---|---|
| `url-tvg` | XMLTV guide URL(s) for the whole playlist | Kodi (as `x-tvg-url`), OTT Nav, crispy-m3u |
| `x-tvg-url` | **Synonym of `url-tvg`** | Kodi README, OTT Nav ("Alias: `x-tvg-url`"), crispy-m3u `EPG_URL_ATTRS = ["x-tvg-url","url-tvg"]` |
| `url-logo` | Base URL prefixed to relative `tvg-logo` | OTT Nav |
| `tvg-shift` | Default EPG time shift, hours | Kodi, OTT Nav |
| `catchup` / `catchup-type` | Default catch-up mode for all channels | Kodi, OTT Nav |
| `catchup-source` | Default catch-up URL template | Kodi, OTT Nav |
| `catchup-days` | Default archive depth, days | Kodi, OTT Nav |
| `catchup-time` | Default archive depth, seconds | OTT Nav |
| `catchup-correction` | Default timezone correction, hours | Kodi, OTT Nav |
| `catchup-enable` | Per-channel only | OTT Nav |
| `timeshift` | Legacy SIPTV combined shift+days | Kodi, OTT Nav |
| `max-conn` | Max simultaneous streams the account allows | OTT Nav |
| `billed-till` / `billed-msg` | Account expiry (unix s) / free-text message | OTT Nav |
| `refresh` / `refresh_at` | Reload interval (1–24 h, 25–299 min, ≥300 s) / ISO-8601 UTC instant | OTT Nav (since 1.6.6.1 / 1.7.2.1) |
| `m3uautoload` | `1` = auto-load EPG on open | OTT Nav |
| `user-agent` / `http-user-agent` | Default UA for all entries | `iptv-parser` README |
| `provider`, `provider-type`, `provider-logo`, `provider-countries`, `provider-languages` | Provider metadata; `provider-type` ∈ `addon|satellite|cable|aerial|iptv|unknown` | Kodi README, crispy-m3u |

**`#EXTINF` channel attributes**

| Attribute | Meaning |
|---|---|
| `tvg-id` | EPG channel id. Must equal XMLTV `<channel id="">`. **Case-sensitive by default.** |
| `tvg-name` | EPG display name; fallback matching key |
| `tvg-logo` | Logo URL (alias `logo`, `tvg-icon`); relative → `url-logo` prefixed, `.png` appended if missing |
| `tvg-chno` | Channel number (aliases `ch-number`, `tvg-ch`, `channel-id`, `tvh-chnum`) |
| `tvg-language` | Broadcast language (ISO 639-2 name or code) |
| `tvg-country` | Broadcast country (ISO 3166-1 alpha-2) |
| `tvg-shift` | Per-channel EPG offset in **hours**. Kodi range −12…+14 h; OTT Nav −12…+12; outside that range some players read the value as **seconds** |
| `tvg-rec` | `1` = has archive; `>1` = number of days (alias for `catchup-days`) |
| `tvg-url` | Per-channel EPG URL (in crispy-m3u's known-attr list; rare) |
| `group-title` | Category. `;`-separated for multiple categories (Kodi, OTT Nav) |
| `radio` | `radio="true"` = audio-only |
| `adult` / `parent-code` | Restriction flags; `parent-code` != `0000` = restricted |
| `audio-track` / `video-track` | Auto-select Nth track; `audio-track="eng,rus"` (ISO 639-2, first = default) |
| `aspect-ratio` | `16:9`, `4:3`, … |
| `type` / `tvg-type` | `playlist` = inline include; also `movie`, `series`, `radio` |
| `content-type` | Alias of `type`; `stream|video|playlist` |
| `catchup` / `catchup-type` / `catchup-mode` | Catch-up mode (see §5) |
| `catchup-source` / `catchup-url` | Catch-up URL template |
| `catchup-days` / `catchup-days-max` / `catchup-range` | Archive depth, days |
| `catchup-time` | Archive depth, seconds |
| `catchup-correction` | Hours correction applied when generating the archive URL |
| `catchup-enable` | `1`/`0` per-channel override of the header |
| `timeshift` | Legacy SIPTV: **days** of archive |
| `media`, `media-dir`, `media-size` | VOD/media library entry flags (Kodi) |
| `provider*` | As header |
| `user-agent` / `http-user-agent`, `referrer` / `http-referrer` | Per-channel HTTP headers |
| `realtime` | `false` to stop Kodi treating a live stream as VOD |

**`#EXTVLCOPT:key=value`** — `http-user-agent`, `http-referrer` are lifted into real HTTP
headers (and dropped from the property list) by Kodi.
**`#EXTHTTP:key=value`** — OTT Nav's header directive (also `stream_headers` in OTC).

**`x-display-name` and `x-cc`: NOT IPTV attributes.** grep.app across the GitHub index
returns 197 hits for `x-display-name` (OpenAPI tags, Emacs `x-display-name`, X11) and 16 for
`x-cc` (RabbitMQ headers, 1C:Enterprise) — **zero** in any IPTV/M3U project. **[UNVERIFIED /
almost certainly not a real thing]** — treat as ignorable vendor noise.

### 1.3 Catch-up attribute semantics (authoritative)

Modes — Kodi `CatchupMode` enum (`Channel.h`, ported verbatim into `crispy-catchup`
`src/mode.rs`):

```
Disabled = 0, Default = 1, Append = 2, Shift = 3,
Flussonic = 4, XtreamCodes = 5, Timeshift = 6, Vod = 7
```

`crispy-iptv-types` `CatchupType` (lowercase serde) = `default|append|shift|timeshift|
flussonic|fs|xc|vod`.

| Attribute | Exact semantics |
|---|---|
| `catchup` (= `catchup-type`, `catchup-mode`) | The mode. `catchup="custom"` is **not** a real value — the custom-template mode is called **`default`**. |
| `catchup-source` | In `default` mode this **is** the whole archive URL (with tokens). In `append` mode it is the **query appended** to the live URL. OTT Nav: "A value without `/` is ignored for every type except `append`." `m3u-editor` resolves it as: *relative source → always appended; absolute source → always used as-is, whatever the mode says.* |
| `catchup-days` | Days of archive. `catchup-days="0"` on a channel ⇒ **no archive for that channel**. |
| `catchup-time` | Same in **seconds** (`10800` = 3 h). When both are present, the **larger** wins (OTT Nav). |
| `catchup-correction` | Hours added to the EPG-derived time when generating the archive URL. Fixes "catchup plays an hour off". |
| `catchup-enable` | `1`/`0`, channel-level override of the header declaration. |
| `tvg-rec` | `1` = archive exists; a value > 1 = number of days. |
| `timeshift` | **Days** of archive (legacy SIPTV, combines shift + days). If the header declares no archive type, `timeshift` also implies `catchup-type="timeshift"`. |

**Precedence, verified by test** (`m3ue/m3u-editor` `tests/Feature/M3uImportCatchupShiftTest.php`):
`catchup-days` > `tvg-rec` > `timeshift`; and **`tvg-shift` is NOT an archive window** — it is
the EPG offset. The test file documents a real bug where a duplicate `shift` key in a PHP
attribute map silently made the `tvg-shift` fallback unreachable.

**Which channels have an archive** (OTT Nav, verified): if *no* channel has any archive
attribute but the header declares one, **all** channels have it. As soon as one channel has
one, only channels with their own marker have it.

### 1.4 EPG source URL: `url-tvg` vs `x-tvg-url`

- They are **synonyms**. Kodi reads only `x-tvg-url`. OTT Nav reads `url-tvg` with
  `x-tvg-url` as alias. `crispy-m3u` lists `EPG_URL_ATTRS = ["x-tvg-url", "url-tvg"]`;
  `ynotv` `extractAttribute(line, ['url-tvg','x-tvg-url'])`.
- **Single URL or multiple?** OTT Nav: "Several addresses can be separated by `,` or `;`."
  Kodi reads a single value. iptv-org's internal playlists use `x-tvg-url="…"` in the header
  (`docs/playlists.md`). Some real-world playlists put `url-tvg` on the **`#EXTINF` line**
  (see `joaoguidugli/FTA-IPTV-Brasil/playlist.m3u8`) — non-standard but present in the wild.
  → **Read both names, split on `,` and `;`, try each URL until one yields programmes.**
- Compression: `.xml`, `.xml.gz`, `.xz`. OTT Nav detects compression **from the content,
  not the extension**; `.zip` is ignored. Kodi's `pvr.iptvsimple` supports gzip and xz.

### 1.5 iptv-org playlist conventions

- Stream id format (`docs/stream-id.md`): `<ChannelID>[@<FeedID>]`, e.g. `ExampleTV.us@HD`.
  ChannelID = channel name without spaces/special chars + `.` + country code.
  FeedID = feed name stripped of spaces, e.g. `HD`, `Plus1`, `Pluto`, `East`, `MENA`.
- Entry template (`docs/stream-description-scheme.md`):
  ```
  #EXTINF:-1 tvg-id="STREAM_ID",STREAM_TITLE (QUALITY) [LABEL]
  STREAM_URL
  ```
  `QUALITY` ∈ `2160p|1080p|720p|480p|360p`; `LABEL` ∈ `Geo-blocked|Not 24/7`.
  `STREAM_TITLE` may not contain `,`, `[` or `]`.
  Supported URL protocols: `HTTPS HTTP MMS MMSH RTSP RTMP SRT RTP UDP`.
- Public generated playlists add `tvg-logo` and `group-title` derived from the database:
  ```
  #EXTM3U x-tvg-url="https://example.com/guide.xml"
  #EXTINF:-1 tvg-id="ExampleTV.us@SD" tvg-logo="https://example.com/logo.png" group-title="Movies",Example TV (720p)
  ```
- Per-channel headers are expressed with `#EXTVLCOPT`:
  ```
  #EXTVLCOPT:http-referrer=http://example.com/
  #EXTVLCOPT:http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)
  ```
- Internal playlists: `.m3u` extension, `#EXTM3U` first line, CRLF endings, UTF-8 no BOM.
- Full list: https://iptv-org.github.io/iptv/index.m3u ; per-country in `streams/*.m3u`.
- Real example (`streams/uk.m3u`):
  ```
  #EXTINF:-1 tvg-id="5USA.uk@SD",5USA (360p)
  https://lb.iptvnetwork.net/ls28/10115/playlist.m3u8
  #EXTINF:-1 tvg-id="AkaalChannel.uk@SD",Akaal Channel (396p) [Geo-blocked]
  https://cdn-globecast.akamaized.net/live/eds/akaal_tv/hls1_smart_akaal/akaal_tv.m3u8
  ```

---

## 2. Xtream Codes API

### 2.1 Endpoint files

| File | Purpose | Auth |
|---|---|---|
| `player_api.php` | Player API (categories, streams, VOD, series, EPG) | `username`+`password` query params |
| `xmltv.php` | Full XMLTV guide | same |
| `get.php` | M3U playlist generation | same |
| `panel_api.php` | Panel API (bulk data, `available_channels`) | same |
| `api.php` | Admin API (IP-based auth) | IP allowlist |
| `system_api.php` | Server-to-server | — |
| `portal.php` | MAG / Stalker portal | MAC + bearer token |
| `enigma2.php` | Enigma2 receivers (XML response) | user/pass |

Default Xtream Codes ports: **HTTP 25461, HTTPS 25463** (worldofiptvcom docs).
`panel_api.php` in the original panel supports exactly one action: `get_epg`
(`gtaman92/XtreamCodesExtendAPI/panel_api.php`: `$valid_actions = array("get_epg")`,
params `stream_id` and optional `from_now`). iptv-org's `docs/xtream-codes.md` uses
`http(s)://{host}:{port}/panel_api.php?username={u}&password={p}` in a browser as the
"is this an Xtream server?" probe.

### 2.2 `player_api.php` actions and params

Base: `http://SERVER:PORT/player_api.php?username=USER&password=PASS`

| Action | Extra params | Returns |
|---|---|---|
| *(none)* / `get_profile` | — | `{user_info, server_info}` |
| `get_live_categories` | — | live categories |
| `get_live_streams` | `category_id` (optional) | live channels |
| `get_vod_categories` | — | VOD categories |
| `get_vod_streams` | `category_id` (optional) | movies |
| `get_vod_info` | `vod_id` (required) | one movie |
| `get_series_categories` | — | series categories |
| `get_series` | `category_id` (optional) | series list |
| `get_series_info` | `series_id` (required) | series + seasons + episodes |
| `get_short_epg` | `stream_id` (required), `limit` (optional, default **4**) | upcoming programmes |
| `get_simple_data_table` | `stream_id` (required) | full guide for one stream, incl. past |

Extras, panel-specific:
- `get_epg` + `stream_id` + `from_now=1` — **panel_api.php** (not player_api). Times are
  Unix seconds; `from_now=1` returns only what has not ended. An expired line gets `[]`.
- `get_vod_latest`, `get_series_latest`, `action=search` with `type=live|movies|series`
  and URL-encoded `context` — **Xtream-Masters only** (documented at
  https://ottpanel.tv/player_api.html).
- Legacy alias `get_simple_date_table` — accepted and normalized by tunlx.

### 2.3 `get_short_episodes` and `get_episodes` — **do not exist in the Xtream API**

Verified negative:
- grep.app (GitHub code index): `get_short_episodes` → **0 hits**; `action=get_episodes` →
  **0 hits**.
- Not present in any of: Xtream-Masters `player_api` reference, xui.live engine docs,
  tunlx reference, `worldofiptvcom/xtream-codes-api`, maykiptv, SMART OTT,
  `ryanbr/xtream_rust` `api.md`, `chazlarson/py-xtream-codes`, `gtaman92/XtreamCodesExtendAPI`.

The nearest real things are:
- `get_short_epg` — **programmes**, not episodes.
- `get_series_info` → `episodes` map keyed by season.
- `get_epg` on **panel_api.php**.
- `get_ordered_list` on Stalker/Ministra (the analogue for series episodes).

**Recommendation:** implement `get_series_info`; if you want to be defensive, probe
`action=get_short_episodes&series_id=…` once per panel and cache the result if it answers,
but treat a failure as "unsupported".

### 2.4 Exact JSON field names

**Login**
```json
{
  "user_info": {
    "username":"USER", "password":"PASS", "message":"...", "auth":1,
    "status":"Active", "exp_date":"1758143248", "is_trial":"0",
    "active_cons":"0", "created_at":"1755551248", "max_connections":"1",
    "allowed_output_formats":["m3u8","ts","rtmp"]
  },
  "server_info": {
    "url":"host", "port":"80", "https_port":"", "server_protocol":"http",
    "rtmp_port":"8001", "timezone":"Africa/Casablanca",
    "timestamp_now":1756823649, "time_now":"2025-09-02 15:34:09",
    "xui":true, "version":"1.5.12", "revision":null
  }
}
```
Numeric fields arrive as **strings**. `auth` is `1`/`0` (not bool). Some panels add
`server_info.process`.

**Categories** — modern panels return **objects**:
```json
[{"category_id":"1","category_name":"News","parent_id":0}]
```
`gtaman92/XtreamCodesExtendAPI/player_api.php` builds exactly this shape.
The classic Xtream Codes v1 "array of arrays" form (`[["1","News"]]`, `category_id` first)
is widely reported but **I could not confirm it from a primary source in this session**
— **[UNVERIFIED]**. Support both: if the element is an array, take `[0]` as
`category_id` and `[1]` as `category_name`.

**Live channel** (`get_live_streams`)
`num`, `name`, `stream_type` ("live"), `stream_id` (int), `stream_icon`, `thumbnail`,
`epg_channel_id`, `added` (unix s, string), `category_id`, `category_ids` (array),
`custom_sid`, `tv_archive` (0/1), `direct_source` (usually `""`), `tv_archive_duration`
(days, int), `is_adult` (bool).
From `gtaman92` panel source, the emitted live object is exactly:
`num, name, stream_type, stream_id, stream_icon, epg_channel_id, added, category_id,
tv_archive, direct_source, tv_archive_duration`.

**Movie listing** (`get_vod_streams`)
`num`, `name`, `title` (clean title), `year`, `stream_type` ("movie"), `stream_id`,
`stream_icon`, `rating` (float 0–10), `rating_5based` (= `ceil(rating/2.0)` — verified in
the panel PHP source), `genre`, `added`, `episode_run_time`, `category_id`, `category_ids`,
`container_extension` (`mp4`/`mkv`/`avi`; **can be null**), `custom_sid`, `direct_source`,
`release_date` (also `releasedate`), `cast`, `director`, `plot`, `youtube_trailer`.

**Movie info** (`get_vod_info`) → `{ "info": {...}, "movie_data": {...} }`
`info` keys: `kinopoisk_url`, `tmdb_id`, `name`, `o_name`, `cover_big`, `movie_image`,
`release_date`, `episode_run_time`, `youtube_trailer`, `director`, `actors`, `cast`,
`description`, `plot`, `age`, `mpaa_rating`, `rating_count_kinopoisk`, `country`, `genre`,
`backdrop_path`, `duration_secs`, `duration`, `bitrate`, `releasedate`, `subtitles`,
`rating`, `tmdb`.
- **Quirk:** `tmdb_id` is frequently `''`; the real id may live in `info.tmdb`
  (`ynotv` uses `rawInfo.tmdb_id || rawInfo.tmdb`).
- **Quirk:** a missing movie returns `info: []` (empty array), not an error
  (`crispy-xtream/src/client.rs` treats `info.is_none()` as `NotFound`).

**Series listing** (`get_series`)
`num`, `name`, `title`, `year`, `series_id`, `stream_type` ("series"), `cover`, `plot`,
`cast`, `director`, `genre`, `release_date`/`releaseDate`, `last_modified`, `rating`,
`rating_5based`, `backdrop_path`, `youtube_trailer`, `episode_run_time`, `category_id`,
`category_ids`.

**Series info** (`get_series_info`)
```json
{
  "seasons": [{"id":1,"name":"Season 1","episode_count":10,"overview":"…","air_date":"2026-01-01",
               "cover":"…","season_number":1,"cover_big":"…","vote_average":0}],
  "info": { /* same keys as the series listing */ },
  "episodes": {
    "1": [{"id":"5523","episode_num":"1","title":"Ep 1","container_extension":"mp4",
           "info":{"release_date":"","plot":"","duration_secs":0,"duration":"00:00:00",
                   "tmdb_id":"","bitrate":0,"rating":"","season":"1",
                   "cover_big":null,"movie_image":null},
           "subtitles":[], "custom_sid":"", "added":1792058766, "season":1, "direct_source":""}]
  }
}
```
Episode `id` is a **string** in most panels (`"5523"`) — parse as int defensively.

**EPG** (`get_short_epg` / `get_simple_data_table`)
```json
{"epg_listings":[{
  "id":"1792056966", "epg_id":"20", "channel_id":"demo.channel",
  "title":"RGVtbyBOb3c=", "description":"PG5vdz4gJiAidGhlbiI=",
  "lang":"", "start":"2026-10-15 10:36:06", "end":1792060566,
  "start_timestamp":1792056966, "stop_timestamp":1792060566,
  "stop":"2026-10-15 11:36:06",
  "now_playing":0, "has_archive":0
}]}
```
- **`title` and `description` are base64-encoded** in the classic Xtream JSON API
  (verified: `RGVtbyBOb3c=` decodes to `Demo Now`; `crispy-xtream` has a
  `decode_epg_field()` helper). XMLTV is *not* base64.
- `start`/`end`/`stop` are `YYYY-MM-DD HH:MM:SS` **in the server's local time**;
  `start_timestamp`/`stop_timestamp` are Unix epoch — prefer the timestamps.
- `now_playing` (0/1) and `has_archive` (0/1) appear in `get_simple_data_table`.

### 2.5 Stream URL construction

```
Live TV   : http://SERVER:PORT/live/USER/PASS/STREAM_ID.ts       (or .m3u8)
Movie     : http://SERVER:PORT/movie/USER/PASS/STREAM_ID.{container_extension}
Episode   : http://SERVER:PORT/series/USER/PASS/EPISODE_ID.{container_extension}
Timeshift : http://SERVER:PORT/timeshift/USER/PASS/{minutes}/YYYY-MM-DD:HH-MM/STREAM_ID.ts
HLS segs  : http://SERVER:PORT/hls/USER/PASS/STREAM_ID/{segment}.ts
M3U export: http://SERVER:PORT/get.php?username=USER&password=PASS&type=m3u_plus&output=ts
```

- The panel never returns full stream URLs; the client builds them.
- Which live container a line may use is in `user_info.allowed_output_formats`
  (e.g. `["m3u8","ts","rtmp"]`). `crispy-xtream`'s `effective_channel_extension()`:
  if `allowed_output_formats` is empty → use the preferred; if the preferred is in the list →
  use it; else use the **first** allowed.
- VOD/episode extension comes from `container_extension`; **null** is valid
  (`ynotv` types it `string | null`), so default to `mp4` (live: `ts`).
- Credentials in the URL must be percent-encoded (`ynotv` uses `encodeURIComponent`).

---

## 3. XMLTV EPG format

### 3.1 DTD (verified verbatim against the official DTD)

```
<!ELEMENT tv (channel*, programme*)>
<!ATTLIST tv date CDATA #IMPLIED
             source-info-url CDATA #IMPLIED
             source-info-name CDATA #IMPLIED
             source-data-url CDATA #IMPLIED
             generator-info-name CDATA #IMPLIED
             generator-info-url  CDATA #IMPLIED >

<!ELEMENT channel (display-name+, icon*, url*) >
<!ATTLIST channel id CDATA #REQUIRED >

<!ELEMENT display-name (#PCDATA)>
<!ATTLIST display-name lang CDATA #IMPLIED>

<!ELEMENT icon EMPTY>
<!ATTLIST icon src CDATA #REQUIRED  width CDATA #IMPLIED  height CDATA #IMPLIED>

<!ELEMENT url (#PCDATA)>
<!ATTLIST url system CDATA #IMPLIED>

<!ELEMENT programme (title+, sub-title*, desc*, credits?, date?,
                     category*, keyword*, language?, orig-language?,
                     length?, icon*, url*, country*, episode-num*,
                     video?, audio?, previously-shown?, premiere?,
                     last-chance?, new?, subtitles*, rating*,
                     star-rating*, review*, image* )>
<!ATTLIST programme start     CDATA #REQUIRED
                    stop      CDATA #IMPLIED
                    pdc-start CDATA #IMPLIED
                    vps-start CDATA #IMPLIED
                    showview  CDATA #IMPLIED
                    videoplus CDATA #IMPLIED
                    channel   CDATA #REQUIRED
                    clumpidx  CDATA "0/1" >
```
Child element details:
- `credits` = `(director*, actor*, writer*, adapter*, producer*, composer*, editor*,
  presenter*, commentator*, guest*)`; `actor` has `role` and `guest` attributes.
- `length` has REQUIRED `units` ∈ `seconds|minutes|hours`.
- `episode-num` has `system` (default `"onscreen"`). `xmltv_ns` = three dot-separated,
  **zero-indexed** numbers, optionally `X/Y` (`1 . 0 . 0/1` = series 2, episode 1, part 1/1);
  `onscreen` = verbatim credit text; `dd_progid` = Schedules Direct/Gracenote id (not in the
  DTD but ubiquitous in NA guides).
- `previously-shown` = EMPTY, attributes `start`, `channel`.
- `premiere` / `last-chance` = `#PCDATA` + `lang`; `new` = EMPTY.
- `subtitles` = `(language?)` + `type` ∈ `teletext|onscreen|deaf-signed`.
- `rating` = `(value, icon*)` + `system`; `star-rating` = `(value, icon*)` + `system`.
- `review` = `#PCDATA` with `type` REQUIRED (`text|url`), plus `source`, `reviewer`, `lang`.
- `image` = `#PCDATA` with `type` ∈ `poster|backdrop|still|person|character`,
  `size` ∈ `1|2|3`, `orient` ∈ `P|L`, `system`.
- `video` = `(present?, colour?, aspect?, quality?)`; `audio` = `(present?, stereo?)`.
- **Element order is significant** — strict validators reject out-of-order children.

### 3.2 Time format

From the DTD header (verbatim):
> "All dates and times in this DTD follow the same format, loosely based on ISO 8601. They
> can be `'YYYYMMDDhhmmss'` or some initial substring, for example if you only know the year
> and month you can have `'YYYYMM'`. You can also append a timezone to the end; if no
> explicit timezone is given, **UTC is assumed**. Examples: `'200007281733 BST'`,
> `'200209'`, `'19880523083000 +0300'`. (BST == +0100.)"

Kodi README: "the attributes `start`/`stop` in the format `YYYmmddHHMMSS +/-HHMM`".

Strict parser behaviour (`mewset/better-iptv` `src-tauri/src/epg_domain/time.rs`, verified by
its unit tests):
- Digit run must be **4, 6, 8, 10, 12 or 14** digits. Missing month/day → `1`; missing
  hour/minute/second → `0`.
- `24:00:00` is accepted as midnight of the **next** day; any other hour 24 and second 60
  are rejected.
- Zone token = the first whitespace-separated word after the digits
  (`"20261020140000 +0200 CEST"` → `+0200`).
- Numeric offsets accepted as `+0200`, `+02:00`, `-0500`; `Z`/`UTC`/`GMT` = 0; `BST` = +60.
  Anything else (`CEST`, `+2`, `0200`) = **unknown zone**. `|offset| >= 1440` = unknown.
- **A missing zone means UTC** per the DTD — but many providers write local time and label it
  `+0000`. `tvg-shift` (hours) and `catchup-correction` exist to correct that; better-iptv
  also offers "treat the guide's times as this computer's local time".

### 3.3 Mapping `tvg-id` / `epg_channel_id` → XMLTV `channel id`

- M3U `tvg-id` ≡ Xtream `epg_channel_id` ≡ XMLTV `<channel id="…">`.
- Kodi's 3-pass matching (README, verbatim):
  1. `<channel id>` == `tvg-id` → match.
  2. `<display-name>` (as-is, or with spaces replaced by `_`) == `tvg-name` → match.
  3. `<display-name>` == the M3U channel name (text after the comma) → match.
  Kodi also has an "Ignore Case for EPG Channel IDs" setting; OTT Nav matches
  case-insensitively as a fallback.
- **better-iptv is deliberately stricter** (`epg_domain/matching.rs`): if the channel *has*
  an id, it is **never** matched by name — "a name that also exists in another country's
  guide brought that country's schedule". Only a channel with **no** id is matched by name
  (`tvg-name`, then display name). A display name that one guide source gives to two
  different channels is **dropped and counted**, never resolved by picking the first.
  A manual override (`epg_match_source='manual'`) is never overwritten by the matcher.
- **Practical quirk:** some providers put *several* ids in `epg_channel_id`, separated by
  `;` or `,` (e.g. `BBC1.uk;BBC One UK`). Split and try each. **[Widely reported; not
  confirmed from a primary source in this session — UNVERIFIED but cheap to support.]**

### 3.4 Programme ↔ catch-up window

- The programme's `start`/`stop` define the nominal window; `duration = stop - start`.
- **The archive request must be slightly longer than the programme.** Dispatcharr
  (`apps/timeshift/helpers.py`) uses `DURATION_BUFFER_MINUTES = 5` with the comment:
  "IPTV archives commonly lag live by about 30 seconds to 2 minutes, so a bare programme
  length tends to include the previous show's tail and clip the end."
  `MAX_DURATION_MINUTES = 480`, `DEFAULT_DURATION_MINUTES = 120`.
- Archive *availability* is a separate axis from programme times:
  - Xtream per channel: `tv_archive` (0/1) and `tv_archive_duration` (days).
  - Xtream per programme: `has_archive` in `get_simple_data_table`.
  - M3U per channel: `catchup-days` / `catchup-time` / `tvg-rec` / `timeshift`.
  - `catchup-days="0"` ⇒ no archive (OTT Nav).
- `catchup-id` — **a non-DTD attribute on `<programme>`**, read by Kodi as
  `GetAttributeValue(programmeNode, "catchup-id", m_catchupId)`
  (`pvr.iptvsimple/src/iptvsimple/data/EpgEntry.cpp:206`) and exposed as `{catchup-id}`.
  Kodi README example:
  ```xml
  <programme start="20201021230000" channel="131@footv"
             catchup-id="plugin://plugin.video.footv/?mode=vod&amp;&amp;catchupid=episode1"
             stop="20201022010000">
  ```

### 3.5 Catch-up URL templates — exact format strings

**Xtream Codes — two equivalent forms**

```
# path form (minutes; start in the SERVER's local timezone)
http://host:port/timeshift/USER/PASS/{duration_minutes}/YYYY-MM-DD:HH-MM/STREAM_ID.ts

# script form
http://host:port/streaming/timeshift.php?username=USER&password=PASS
                        &stream=STREAM_ID&start=YYYY-MM-DD:HH-MM&duration=MINUTES
```
The script form is verified verbatim in `Fazzani/xtreamcode`
`xcodestream/url_api_builder.py`:
`"catchup": "{}://{}:{}/streaming/timeshift.php?username={}&password={}&stream=2&start=2019-04-19:16-00&duration=120"`.

**Auto-generated `xc` template** — identical in Kodi, `crispy-catchup` and `m3u-editor`:
```
{host}/timeshift/{user}/{pass}/{duration:60}/{Y}-{m}-{d}:{H}-{M}/{id}.{ext}
```
Verified unit test in `crispy-catchup/src/provider.rs`:
`http://list.tv:8080/timeshift/my@account.xc/my_password/{duration:60}/{Y}-{m}-{d}:{H}-{M}/1477.ts`
(`{duration:60}` = duration in **minutes**; `{ext}` = `.ts` when the live URL has no
extension, otherwise the live URL's extension e.g. `.m3u8`).
`m3u-editor` regex: `~^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/([^/.]+)(\.m3u8?)?$~`.

**Flussonic** — regex `^(https?://[^/]+)/(.*)/([^/]*)(mpegts|\.m3u8)(\?.+=.+)?$`:

| Live URL | Generated archive URL |
|---|---|
| `http://ch01.spr24.net/151/mpegts?token=my_token` | `http://ch01.spr24.net/151/timeshift_abs-${start}.ts?token=my_token` |
| `http://list.tv:8888/325/index.m3u8?token=secret` | `http://list.tv:8888/325/timeshift_rel-{offset:1}.m3u8?token=secret` |
| `http://list.tv:8888/325/mono.m3u8?token=secret` | `http://list.tv:8888/325/mono-timeshift_rel-{offset:1}.m3u8?token=secret` |
| `http://list.tv:8888/325/live?token=my_token` (TS hint) | `http://list.tv:8888/325/timeshift_abs-${start}.ts?token=my_token` |

`{offset:1}` = seconds elapsed since the programme start (i.e. relative rewind).
**Divergence to note:** OTT Navigator's `flussonic-hls` mode instead emits
`archive-{start}-{duration}.m3u8` / `video-{start}-{duration}.m3u8` / `timeshift_abs-{start}.m3u8`.
Kodi and OTT Nav therefore disagree on `flussonic-hls`. Support both by inspecting the live
URL shape (if it ends `/index.m3u8` → `timeshift_rel-`; if it contains `/mpegts` → `timeshift_abs-`).

**SIPTV `shift`** — identical in Kodi, OTT Nav, `crispy-catchup` and `m3u-editor`:
```
{live_url}?utc={utc}&lutc={lutc}      (use & if the live URL already has a query)
```
`{utc}` = programme start (unix s), `{lutc}` = now (unix s).

**Manual `default` template** (Kodi README, Channel F):
```
#EXTINF:0 catchup="default" catchup-days="1" catchup-source="http://yoururl/channeld/video-{utc}-{duration}.m3u8",Channel F
http://yoururl/channeld/video.m3u8
```

### 3.6 Template variable reference

**Kodi `pvr.iptvsimple` (the de-facto standard)** — from the README appendix, verbatim:

| Token | Meaning |
|---|---|
| `{utc}` | programme start, unix epoch |
| `${start}` | same as `{utc}` |
| `{lutc}` | now, unix epoch |
| `${now}`, `${timestamp}` | same as `{lutc}` |
| `{utcend}` | programme start + duration |
| `${end}` | same as `{utcend}` |
| `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` | 4-digit year / 2-digit month / day / hour / minute / second of the start |
| `{duration}`, `${duration}` | programme duration (+ any start/end buffer) |
| `{duration:X}` | duration ÷ X seconds (X integer ≥ 1; e.g. `{duration:60}` → minutes) |
| `{offset:X}` | (now − start) ÷ X seconds |
| `{catchup-id}` | the programme's `catchup-id` XMLTV attribute |

> "all timestamp specifiers (`{utc}`, `${start}`, `${end}` etc.) also support an optional extra
> argument which is a format string. The format string can contain `Y`, `m`, `d`, `H`, `M`,
> `S`."

Examples straight from the README:
```
?utc={utc}&lutc={lutc}
&t={Y}-{m}-{d}-{H}-{M}-{S}
?offset={offset:60}
?start={utc:YmdHM}&end=${end:YmdHM}
?start={utc:Ymd-H-M}&end=${end:Ymd-H-M}
```
`{lutc}`, `${now}`, `${timestamp}` are also valid in the **live** channel URL.

**OTT Navigator additions**: `{start_iso}` / `${start_iso}`, `{end_iso}` / `${end_iso}`,
`{now_iso}` (ISO-8601 UTC, since 1.7.1.3); `${offset}` (seconds from start to now);
`{durmin}` (duration in minutes); `${start-year}` … `${end-sec}` (device-local parts, since
1.6.6.5); `${token}` / `{key}`; `${login}` / `${password}`; `{catchup-id}` (since 1.7.6.1).
Time-shifting syntax: `{utc:-5m}`, `${start:+1h}`, `{utcend:+10m}` (offsets: `m`, `h`, `d`,
or bare seconds); whole-URL form `{{shift:start:-5m}}` / `{{shift:end:+10m}}` (one per URL).

**ynotv** (`packages/local-adapter/src/m3u-catchup.ts`) supports:
`${start}`/`{start}`, `${end}`/`{end}`, `${utc}`/`{utc}`,
`${utcend}`/`${utc_end}`/`${end_utc}` (and bare forms), `${duration}`/`{duration}`,
`${duration_m}`/`${duration_min}` (minutes), `{offset}`, `${catchup-id}`/`{catchup-id}`,
plus date-part tokens `{Y}{m}{d}{H}{M}{S}` (UTC) and `{EY}{Em}{Ed}{EH}{EM}{ES}` (end).
Note ynotv substitutes `${...}` **before** `{...}` so the `$` is never left behind.

**Non-Kodi variants seen in the wild — implement defensively:**
- **Double-brace** `{{…}}`: JioTV (`plugin.video.jiotv`) emits
  `catchup="vod" catchup-source="…channel_id={id}&showtime={{H}}{{M}}{{S}}&srno={{Y}}{{m}}{{d}}&programId={{catchup-id}}" catchup-days="7"`.
- **Java-style date patterns** `${(b)yyyyMMddHHmmss}` / `${(e)yyyyMMddHHmmss}`
  (`(b)`=begin, `(e)`=end) used by Chinese/Migu playlists:
  `catchup="append" catchup-source="?playbackbegin=${(b)yyyyMMddHHmmss}&playbackend=${(e)yyyyMMddHHmmss}"`,
  `?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}`.
  `[Not part of any standard — UNVERIFIED as a convention, but present in many shipped playlists.]`
- **Flussonic literal shortcut**: `video-${start}-${duration}`.

---

## 4. Stalker / Ministra portal API

### 4.1 Endpoints (verified)

| Step | Request |
|---|---|
| Handshake | `GET http://host:25461/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml` → `{"js":{"token":"C00F7332ED272F00D5FD3E82F567A282"}}` |
| Ministra variant | `GET http://host/stalker_portal/server/load.php?type=stb&action=handshake&prehash=0&token=<TOKEN>&JsHttpRequest=1-xml` |
| Profile (activates the session) | `…&type=stb&action=get_profile` with `hd, ver, num_banks, stb_type, image_version, auth_second_step, hw_version, not_valid_token, sn, device_id, device_id2, signature` |
| Live categories | `…&type=itv&action=get_genres` |
| Live list | `…&type=itv&action=get_ordered_list&genre=<id>&p=<page>&sortby=number&hd=0&fav=0&force_ch_link_check=` |
| VOD categories | `…&type=vod&action=get_categories` |
| Series categories | `…&type=series&action=get_categories` |
| VOD / series list | `…&type=vod&action=get_ordered_list&category=<id>&p=<page>` |
| Play | `…&type=itv&action=create_link&cmd=<cmd>&forced_storage=undefined&disable_ad=0&download=0&JsHttpRequest=1-xml` → `{"js":{"id":"47534","cmd":"ffmpeg http://ip.tv:8000:80/…/47534?play_token=Hsv87nbU99"},"streamer_id":0,"link_id":0,"load":0,"error":""}` |
| Episode | `…&type=vod&action=create_link&cmd=<cmd>&series=<episode_number>` |
| Archive | `…&type=tv_archive&action=create_link` |
| EPG | `…&type=itv&action=get_short_epg&ch_id=<id>&size=<n>` then `…&type=itv&action=get_epg_info&ch_id=<id>` |
| Account | `…&type=account_info&action=get_main_info` → `js.login, js.mac, js.status, js.expire_billing_date, js.subscribed_till` |
| Keepalive | `…&type=watchdog&action=get_events` |

**Headers** (mandatory):
```
User-Agent: Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3
X-User-Agent: Model: MAG250; Link: WiFi
Cookie: mac=00%3A1A%3A79%3A…%3A; stb_lang=en; timezone=Europe%2FParis
Referer: <base>/stalker_portal/c/index.html
Authorization: Bearer <token>
```
**The MAC must be percent-encoded** in the cookie (`00%3A1A%3A79%3A…`) — `crispy-stalker`
`src/session.rs` `cookie_header()` and `ynotv` both do this explicitly ("CRITICAL FIX").
Token validity **3600 s** (`ynotv` `STALKER_TOKEN_VALIDITY_SECONDS`; `crispy-stalker`
`DEFAULT_TOKEN_VALIDITY_SECS`). Device identity is derived from the MAC: serial = MD5(mac)
(13 chars), device_id = SHA-256(mac) (64 chars), hw_version_2 = SHA-1(mac), plus a
`signature` and a `metrics` blob.

**There is no `full_search` action.** Search is `search=<phrase>` riding on the ordinary
`get_ordered_list` call. `ynotv` detects portals that ignore it by comparing the searched
`total_items` against an unsearched call and then reports "this middleware does not support
search". Pagination base is per-portal (0- or 1-based) and `ynotv` *learns* the offset.

### 4.2 Recommendation: **DEFER Stalker to v2**

One paragraph: Stalker/Ministra is a different authentication world — no username/password,
just a MAC address that must be presented as a MAG set-top box, plus a bearer token obtained
through a handshake that sometimes 404s and needs a locally generated token+prehash retry
(`Cyogenus/IPTV-MAC-STALKER-PLAYER-BY-MY-1`). Playback is two hops: every channel carries an
opaque `cmd` (often `ffrt4://…`) that must be exchanged for a real URL through
`create_link`, so a channel change costs an extra round trip on a path that is already
latency-sensitive, and the returned `cmd` sometimes still has an `ffmpeg ` prefix that must be
stripped. On top of that, per-portal quirks dominate: `get_ordered_list` returns only ~14
items by default, the page base differs per portal, `type=series` may be absent (so series
must be hunted under `type=vod` with `is_series=1`), episode `cmd` values must be fetched
individually via `get_ordered_list(episode_id=…)` before `create_link`, and search may be
silently ignored. `ynotv` — the most complete open implementation I found — devotes a
137 KB `stalker-client.ts` plus six test files to it. Meanwhile the installed base is
shrinking relative to Xtream/M3U. **Recommendation:** ship M3U + Xtream + catch-up + failover
in v1, and add Stalker in v2 behind a feature flag, budgeted as its own subsystem (separate
crate, its own session/token manager, its own pagination heuristics). If your target market is
EU/MENA Mag-box-only users, invert this decision.

---

## 5. Catch-up / timeshift implementation

### 5.1 "Jump back into a live stream"

1. User taps a programme → `programme.start`, `programme.stop` (UTC epoch).
2. Resolve the channel's catchup config: **mode**, **source template**, **days**,
   **granularity** (`crispy-catchup`: 1 s if the template contains `{utc}`/`${start}`/`{S}`/
   `{offset:1}`, else 60 s — the duration is rounded down to that granularity).
3. `duration = min((stop − start) + BUFFER, MAX)`; BUFFER ≈ 5 min (Dispatcharr).
   Clamp to "now": `crispy-catchup` caps `duration` so `start + duration <= now`.
4. Substitute the template → the archive URL.
5. Hand it to the player. For HLS the archive URL returns a **VOD segment list**; for TS it
   is a continuous MPEG-TS stream that starts at the archive point.

### 5.2 The three distinct meanings of "timeshift"

| Thing | Where | Mechanism |
|---|---|---|
| **SIPTV `timeshift` attribute** | M3U | Legacy tag combining shift+days; **days of archive**. Implies `catchup-type="timeshift"` if the header declares no type. Same output as `shift`. |
| **`catchup="shift"`** | M3U | Server-side rolling archive. The player rewrites the live URL with `?utc={utc}&lutc={lutc}`; the server keeps the rolling buffer. Exact format string: `{live_url}?utc={utc}&lutc={lutc}` (use `&` if the URL already has a query). |
| **Player-side pause buffer** | local | ynotv's `useTimeshift.ts` subscribes to mpv's `demuxer-cache-state` property and emits `timeshift-update` events carrying `{cacheStart, cacheEnd, timePos, behindLive, cachedDuration}`. Independent of any provider archive. |

A fourth, provider-specific: Flussonic's **sliding rewind window**
`http://host/STREAM/rewind-{window_seconds}.m3u8` — the number is the *manifest window
length*, not an offset into the past.

### 5.3 Mode-by-mode URL construction

| Mode | Result |
|---|---|
| `default` | `catchup-source` verbatim (with tokens). Without a `catchup-source`, behaves like `shift`. |
| `append` | `{live_url}{catchup-source}` (source is the query). |
| `shift` | `{live_url}?utc={utc}&lutc={lutc}` |
| `archive` | `{live_url}?archive={start}&archive_end={end}` (OTT Nav) |
| `timeshift` | `{live_url}?timeshift={start}&timenow={now}` (OTT Nav) |
| `flussonic` / `flussonic-hls` / `flussonic-ts` / `fs` / `flussonic-dash` | see §3.5 |
| `xc` | `{host}/timeshift/{user}/{pass}/{duration:60}/{Y}-{m}-{d}:{H}-{M}/{id}.{ext}` |
| `vod` | `{catchup-id}` (or the given `catchup-source` containing it). **Does not support timeshifting.** |

OTT Nav rules: "No type and no `catchup-source`: `shift` is used." and "A type other than
`default`/`append` together with `catchup-source`: the source replaces the stream URL and the
type's rule is applied to it."

`crispy-catchup` also computes two derived flags you should mirror:
- `supports_timeshifting`: true iff the template has ≥1 `{…}` specifier and it is **not**
  only `{catchup-id}` and the mode is not `vod`.
- `terminates`: true iff the template contains `{duration}`, `{duration:`, `{lutc}`,
  `{lutc:`, `${timestamp}`, `${timestamp:`, `{utcend}`, `{utcend:`, `${end}`, `${end:` —
  i.e. the archive is a **bounded window**, not an open-ended stream.

### 5.4 Provider-side timezone is mandatory for Xtream timeshift

The Xtream `timeshift` start string `YYYY-MM-DD:HH-MM` is **wall-clock in the panel's own
timezone**, while EPG times are UTC. Get the zone from `server_info.timezone`
(`Fazzani/xtreamcode` also hard-codes the format). `m3u-editor` documents the bug and the fix:
it converts the client's UTC epoch into the provider's zone before formatting, and it
deliberately uses **the failover channel's own playlist timezone**, not the timezone of the
playlist the client authenticated against, because a failover can swap in a channel from a
different provider. `Dispatcharr` does the same via `server_info.timezone` and
`convert_timestamp_to_provider_tz()`.

Timestamps a client may send, per Dispatcharr's normalizer (all verified against real
clients): `YYYY-MM-DD:HH-MM`, `YYYY-MM-DD:HH-MM-SS`, `YYYY-MM-DD_HH-MM`,
`YYYY-MM-DD:HH:MM[:SS]`, `YYYY-MM-DD HH:MM[:SS]`, ISO-8601 (`2026-07-09T14:00:00Z`), and
unix epoch as 10 or 13 digits.

---

## 6. Data model (SQLite)

### 6.1 Design principles taken from the reference implementations

- `better-iptv` separates the **guide store** (`epg_source`, `epg_channel`,
  `epg_channel_name`, `epg_programme`) from the **playlist store** (`playlists`,
  `channels`, `categories`) and joins them through a **match key** rather than a foreign key,
  because a channel's guide may come from a different source than the playlist.
- `epg_programme` is `PRIMARY KEY (channel_id, start)` **WITHOUT ROWID** — the grid needs
  "this channel's programmes ordered by start".
- Guide ingest uses a **separate staging DB** (`ATTACH … AS ingest`) with
  `journal_mode=OFF; synchronous=OFF`, batches of 1000 rows, and a swap in chunks of
  20 000 rows so the write lock is never held long. `better-iptv` measures ~50–135 ms per
  swap transaction for 43 k–430 k programmes.
- `epg_channel_name` is a `WITHOUT ROWID` side table with a `name_key` index, so a name can
  map to several channels without a join.
- `better-iptv` deliberately does **not** use FTS5 for EPG search; it uses
  `instr(key, ?) > 0 OR instr(name_key, ?) > 0` and ranks in Rust. That works but is O(n)
  scan. For 100 k+ channels use FTS5 as below.

### 6.2 DDL

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous  = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA temp_store   = MEMORY;
PRAGMA cache_size   = -40000;   -- ~40 MiB page cache

-- ============================================================ sources
CREATE TABLE source (
  id            INTEGER PRIMARY KEY,
  kind          TEXT NOT NULL CHECK (kind IN ('m3u','xtream','stalker')),
  name          TEXT NOT NULL,
  base_url      TEXT,                       -- xtream host / m3u url
  username      TEXT,                       -- xtream
  password      TEXT,                       -- xtream
  mac           TEXT,                       -- stalker
  token         TEXT,                       -- stalker bearer
  provider_tz   TEXT,                       -- server_info.timezone (xtream)
  server_time   INTEGER,                    -- server_info.timestamp_now
  allowed_output_formats TEXT,              -- JSON array from user_info
  max_connections   INTEGER,
  active_cons       INTEGER,
  exp_date          INTEGER,               -- unix s
  epg_urls      TEXT,                       -- JSON array: url-tvg / x-tvg-url candidates
  user_agent    TEXT,
  referrer      TEXT,
  enabled       INTEGER NOT NULL DEFAULT 1,
  last_sync_at  INTEGER,
  last_error    TEXT,
  UNIQUE (kind, base_url, COALESCE(username,''), COALESCE(mac,''))
);

-- ============================================================ categories
CREATE TABLE category (
  id            INTEGER PRIMARY KEY,
  source_id     INTEGER NOT NULL REFERENCES source(id) ON DELETE CASCADE,
  kind          TEXT NOT NULL CHECK (kind IN ('live','vod','series')),
  provider_cat_id TEXT,                     -- the panel's category_id
  name          TEXT NOT NULL,
  parent_id     TEXT,                       -- panel parent_id
  display_order INTEGER NOT NULL DEFAULT 0,
  UNIQUE (source_id, kind, provider_cat_id)
);
CREATE INDEX idx_cat_source ON category(source_id, kind, display_order);

-- ============================================================ channels (logical)
CREATE TABLE channel (
  id              INTEGER PRIMARY KEY,
  source_id       INTEGER NOT NULL REFERENCES source(id) ON DELETE CASCADE,
  -- stable identity (see 6.4)
  stable_id       INTEGER NOT NULL,          -- xxh3_64(source_id || 0x1f || provider_key)
  provider_key    TEXT NOT NULL,             -- tvg-id, or the Xtream stream_id
  name            TEXT NOT NULL,             -- display name after the last comma
  tvg_name        TEXT,
  logo            TEXT,
  epg_id          TEXT,                      -- tvg-id / epg_channel_id (raw)
  group_title     TEXT,
  channel_num     INTEGER,                   -- tvg-chno
  country         TEXT,                      -- tvg-country
  language        TEXT,                      -- tvg-language
  is_radio        INTEGER NOT NULL DEFAULT 0,
  is_adult        INTEGER NOT NULL DEFAULT 0,
  tvg_shift_hours REAL,                      -- tvg-shift
  tv_archive      INTEGER NOT NULL DEFAULT 0,   -- xtream tv_archive
  archive_days    INTEGER,                      -- catchup-days / tv_archive_duration
  catchup_mode    TEXT,                        -- default|append|shift|timeshift|flussonic|fs|xc|vod
  catchup_source  TEXT,                        -- catchup-source template
  catchup_correction_hours REAL,
  enabled         INTEGER NOT NULL DEFAULT 1,
  provider_order  INTEGER,                     -- position in the provider listing
  sort_order      INTEGER NOT NULL DEFAULT 0,
  UNIQUE (source_id, provider_key)
);
CREATE INDEX idx_chan_source   ON channel(source_id, provider_order);
CREATE INDEX idx_chan_epg      ON channel(source_id, epg_id);
CREATE INDEX idx_chan_group    ON channel(source_id, group_title, provider_order);
CREATE INDEX idx_chan_stable   ON channel(stable_id);

-- channel <-> category (many-to-many: Xtream exposes category_id AND category_ids)
CREATE TABLE channel_category (
  channel_id  INTEGER NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
  category_id INTEGER NOT NULL REFERENCES category(id) ON DELETE CASCADE,
  is_primary  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (channel_id, category_id)
) WITHOUT ROWID;
CREATE INDEX idx_cc_cat ON channel_category(category_id);

-- ============================================================ streams (playable variants)
-- One logical channel can have several streams: SD/HD/FHD/4K feeds, TS vs HLS,
-- direct_source mirrors, VOD containers, series episodes.
CREATE TABLE stream (
  id              INTEGER PRIMARY KEY,
  channel_id      INTEGER REFERENCES channel(id) ON DELETE CASCADE,
  source_id       INTEGER NOT NULL REFERENCES source(id) ON DELETE CASCADE,
  stable_id       INTEGER NOT NULL,
  provider_key    TEXT NOT NULL,              -- xtream stream_id / episode id / m3u url
  kind            TEXT NOT NULL CHECK (kind IN ('live','vod','episode')),
  url             TEXT NOT NULL,              -- fully-built playable URL
  container_ext   TEXT,                       -- mp4|mkv|avi|ts|m3u8
  format          TEXT,                       -- ts|m3u8|rtmp|mpd
  quality         TEXT,                       -- sd|hd|fhd|4k (parsed / provider-declared)
  width           INTEGER, height INTEGER, fps REAL,
  audio_channels  TEXT, video_codec TEXT, audio_codec TEXT,
  direct_source   TEXT,                       -- xtream direct_source
  custom_sid      TEXT,
  season          INTEGER, episode_num INTEGER, episode_title TEXT,
  duration_secs   INTEGER,
  added_at        INTEGER,                    -- xtream "added"
  container_extension TEXT,
  UNIQUE (source_id, provider_key)
);
CREATE INDEX idx_stream_channel ON stream(channel_id, quality);
CREATE INDEX idx_stream_stable  ON stream(stable_id);

-- ============================================================ guide
CREATE TABLE epg_source (
  id            INTEGER PRIMARY KEY,
  source_id     INTEGER REFERENCES source(id) ON DELETE CASCADE, -- NULL = global
  origin        TEXT NOT NULL CHECK (origin IN ('manual','xtream','m3u_header')),
  url           TEXT,
  enabled       INTEGER NOT NULL DEFAULT 1,
  priority      INTEGER NOT NULL DEFAULT 30,   -- manual 10 < m3u_header 20 < xtream 30
  time_interpret TEXT NOT NULL DEFAULT 'as_written',
  time_shift_minutes INTEGER NOT NULL DEFAULT 0,
  last_success_at INTEGER, last_result TEXT, last_error TEXT,
  programme_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE epg_channel (
  id            INTEGER PRIMARY KEY,
  epg_source_id INTEGER NOT NULL REFERENCES epg_source(id) ON DELETE CASCADE,
  key           TEXT NOT NULL,                -- normalised channel id
  first_start   INTEGER, last_stop INTEGER,
  programme_count INTEGER NOT NULL DEFAULT 0,
  UNIQUE (epg_source_id, key)
);
CREATE INDEX idx_epgchan_key ON epg_channel(key);

CREATE TABLE epg_channel_name (
  channel_id INTEGER NOT NULL REFERENCES epg_channel(id) ON DELETE CASCADE,
  position   INTEGER NOT NULL,
  name       TEXT NOT NULL,
  name_key   TEXT NOT NULL,
  PRIMARY KEY (channel_id, position)
) WITHOUT ROWID;
CREATE INDEX idx_epgname_key ON epg_channel_name(name_key);

CREATE TABLE programme (
  epg_channel_id INTEGER NOT NULL REFERENCES epg_channel(id) ON DELETE CASCADE,
  start          INTEGER NOT NULL,            -- unix s, UTC, after the source's time policy
  stop           INTEGER NOT NULL,
  title          TEXT NOT NULL,
  sub_title      TEXT,
  description    TEXT,
  category       TEXT,
  episode_num    TEXT,
  catchup_id     TEXT,
  icon           TEXT,
  rating         TEXT,
  credits        TEXT,                        -- JSON
  PRIMARY KEY (epg_channel_id, start)
) WITHOUT ROWID;

-- The grid needs a TIME-first index: (epg_channel_id, start) cannot serve
-- "all programmes overlapping [t0,t1] across N channels" efficiently.
CREATE INDEX idx_prog_time   ON programme(start, epg_channel_id);
CREATE INDEX idx_prog_stop   ON programme(stop);

-- channel -> epg_channel match (never a hard FK: the guide may come from another source)
CREATE TABLE epg_match (
  channel_id   INTEGER NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
  epg_channel_id INTEGER REFERENCES epg_channel(id) ON DELETE SET NULL,
  match_source TEXT CHECK (match_source IN ('id','tvg_name','name','manual')),
  PRIMARY KEY (channel_id)
) WITHOUT ROWID;

-- ============================================================ user data
CREATE TABLE favourite (
  channel_id INTEGER NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (channel_id)
) WITHOUT ROWID;

CREATE TABLE watch_history (
  id          INTEGER PRIMARY KEY,
  channel_id  INTEGER NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
  stream_id   INTEGER REFERENCES stream(id) ON DELETE SET NULL,
  started_at  INTEGER NOT NULL,
  ended_at    INTEGER,
  position_secs INTEGER NOT NULL DEFAULT 0,
  duration_secs  INTEGER
);
CREATE INDEX idx_hist_channel ON watch_history(channel_id, started_at DESC);
CREATE INDEX idx_hist_recent ON watch_history(started_at DESC);

CREATE TABLE recording (
  id            INTEGER PRIMARY KEY,
  channel_id    INTEGER NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
  stream_id     INTEGER REFERENCES stream(id) ON DELETE SET NULL,
  title         TEXT,
  start_at      INTEGER NOT NULL,
  stop_at       INTEGER,
  file_path     TEXT,
  bytes         INTEGER,
  state         TEXT NOT NULL DEFAULT 'scheduled'
                CHECK (state IN ('scheduled','recording','done','failed','cancelled')),
  error         TEXT
);
CREATE INDEX idx_rec_pending ON recording(state, start_at);

-- ============================================================ failover
CREATE TABLE failover_group (
  group_id  TEXT PRIMARY KEY,
  name      TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE failover_member (
  stream_id INTEGER NOT NULL REFERENCES stream(id) ON DELETE CASCADE,
  group_id  TEXT NOT NULL REFERENCES failover_group(group_id) ON DELETE CASCADE,
  priority  INTEGER NOT NULL,
  PRIMARY KEY (stream_id)
) WITHOUT ROWID;
CREATE INDEX idx_fm_group ON failover_member(group_id, priority);

-- ============================================================ FTS5
CREATE VIRTUAL TABLE channel_fts USING fts5(
  name, tvg_name, group_title, logo,
  content='channel', content_rowid='id',
  tokenize="unicode61 remove_diacritics 2 tokenchars '_-.'"
);
CREATE TRIGGER ch_ai AFTER INSERT ON channel BEGIN
  INSERT INTO channel_fts(rowid, name, tvg_name, group_title, logo)
  VALUES (new.id, new.name, new.tvg_name, new.group_title, new.logo);
END;
CREATE TRIGGER ch_ad AFTER DELETE ON channel BEGIN
  INSERT INTO channel_fts(channel_fts, rowid, name, tvg_name, group_title, logo)
  VALUES ('delete', old.id, old.name, old.tvg_name, old.group_title, old.logo);
END;
CREATE TRIGGER ch_au AFTER UPDATE ON channel BEGIN
  INSERT INTO channel_fts(channel_fts, rowid, name, tvg_name, group_title, logo)
  VALUES ('delete', old.id, old.name, old.tvg_name, old.group_title, old.logo);
  INSERT INTO channel_fts(rowid, name, tvg_name, group_title, logo)
  VALUES (new.id, new.name, new.tvg_name, new.group_title, new.logo);
END;

CREATE VIRTUAL TABLE programme_fts USING fts5(
  title, sub_title, description, category,
  content='programme', content_rowid='rowid',
  tokenize="unicode61 remove_diacritics 2"
);
-- NOTE: programme is WITHOUT ROWID, so it has no INTEGER PRIMARY KEY rowid that
-- FTS5 can mirror. Either add `id INTEGER PRIMARY KEY` to programme (making it a
-- rowid table) or use an external-content FTS over a VIEW with a synthetic rowid.
```

**FTS5 caveat (important):** `programme` as `WITHOUT ROWID` has **no** `rowid`, so
`content='programme', content_rowid='rowid'` cannot be used. Either:
(a) drop `WITHOUT ROWID` from `programme` and add `id INTEGER PRIMARY KEY` (a rowid
alias) — costs ~8 bytes/row but makes external-content FTS5 trivial; or
(b) keep `WITHOUT ROWID` and use a **contentless** FTS5 table
(`content=''`) with explicit rowids you manage, plus `INSERT INTO programme_fts(rowid, …)`
triggers; or
(c) skip FTS over programmes entirely and search `title` with a prefix index
(`CREATE INDEX idx_prog_title ON programme(title COLLATE NOCASE)`) — acceptable for
one-word lookups, poor for multi-word.
Recommendation: **(a)** for programmes (row count is bounded by ~150 k channels × 100
programmes ≈ 15 M rows; 8 bytes/row is irrelevant next to the FTS index itself), and
`WITHOUT ROWID` for `epg_channel_name`, `channel_category`, `epg_match`,
`failover_member` where the composite PK *is* the lookup key.

### 6.3 The EPG grid join at 100 k+ channels

The grid shows N channels × a time window T (typically 2–6 h). The query must be a single
pass over the time axis, then a hash-join to the channel set:

```sql
-- 1. resolve the visible channels (typically 20-200 rows)
SELECT id, name, epg_channel_id FROM channel
 WHERE source_id = ? AND enabled = 1
 ORDER BY provider_order LIMIT ?;

-- 2. one time-sliced scan, joined to those channels
SELECT p.epg_channel_id, p.start, p.stop, p.title, p.description
  FROM programme p
  JOIN epg_match m ON m.epg_channel_id = p.epg_channel_id
 WHERE m.channel_id IN (?,?,…)
   AND p.start < ?            -- window end
   AND p.stop  > ?            -- window start
 ORDER BY p.epg_channel_id, p.start;
```
`idx_prog_time (start, epg_channel_id)` serves the `p.start < ?` bound; the
`p.stop > ?` bound is a filter. For a 6 h window across 200 channels you get ~1 200 rows —
trivially fast. The real cost is the **scan**, so bound it with `p.start >= :window_start
- 3600` (programmes longer than an hour are rare) to avoid walking the whole index.

For a **full-day** grid (all channels × 24 h) do **not** join — instead precompute:
```sql
SELECT epg_channel_id, start, stop, title FROM programme
 WHERE start >= ? AND start < ? ORDER BY epg_channel_id, start;
```
and build the grid in memory. This is what `better-iptv` does: it never materialises a
"grid" table, it slices `epg_programme` and renders.

Scale notes:
- 150 000 channels × ~90 programmes/day × 7 days ≈ **94 M programme rows**. At ~120 bytes
  each that is ~11 GB of programme table — too much for a phone. **Prune to the window you
  actually keep** (e.g. 3 days) and index only `(epg_channel_id, start)`.
- better-iptv's `epg/store.rs` prunes: "Rows that ended before a channel's first new
  start … stay until they ended a day ago" — the same idea.
- Store only channels that actually have programmes: better-iptv's matcher counts
  `unmatched_with_id` and reports it to the UI ("A channel shows no guide … A channel whose
  id the guide does not know stays empty rather than getting a guess").
- Never `SELECT *` the channel table into memory; page by `provider_order` with a keyset
  cursor (`WHERE (source_id, provider_order) > (?, ?)`).

### 6.4 Stable ID (favourites must survive a playlist re-sync)

Goal: a favourite / watch-history row keyed to a *provider stream* that keeps its identity
across re-syncs, playlist re-downloads, and provider-side renumbering.

**Recommended:** `xxh3_64` over `source_id || 0x1F || provider_key`.

```rust
// xxhash-rust 0.8.19, feature "xxh3"
use xxhash_rust::xxh3::xxh3_64;

fn stable_id(source_id: i64, provider_key: &str) -> i64 {
    let mut buf = Vec::with_capacity(16 + provider_key.len());
    buf.extend_from_slice(&source_id.to_le_bytes());
    buf.push(0x1F);                       // unit separator: cannot occur in an id
    buf.extend_from_slice(provider_key.as_bytes());
    // Fold to a positive i64 so it can be a SQLite INTEGER PRIMARY KEY.
    ((xxh3_64(&buf) & 0x7FFF_FFFF_FFFF_FFFF) as i64) | 1
}
```
- `xxhash-rust` 0.8.19 (2026-09-28) exposes `xxh3_64` behind the `xxh3` feature and
  `const_xxh3_64` behind `const_xxh3`. Verified on docs.rs.
- **Why 0x1F separator:** prevents `source=1, key="23"` colliding with `source=12,
  key="3"`.
- **Why not a raw u64:** SQLite `INTEGER` is signed 64-bit. Masking to 63 bits and OR-ing
  bit 0 loses 1 bit of entropy and guarantees a non-zero id. Collision probability at
  10^6 ids with 63 bits ≈ 5×10⁻⁸ — acceptable; still add `UNIQUE (source_id, provider_key)`
  as the real guard and fall back to a salted rehash on conflict.
- **What is `provider_key`?**
  - Xtream live: the numeric `stream_id` (stable across re-syncs; the panel reassigns it
    only if the stream is recreated).
  - Xtream VOD: `stream_id`; series episodes: the episode `id`.
  - M3U: prefer `tvg-id` (sanitised); else hash the stream URL; else the name.
- **Reference implementations:**
  - `ynotv` `packages/local-adapter/src/m3u-parser.ts` `generateStableStreamId()`:
    `` `${sourceId}_${sanitisedTvgId}` ``, with a DJB2 hash of the URL appended on
    collision, then `_1`, `_2`, … Sanitisation: `tvgId.replace(/[^a-zA-Z0-9._-]/g, '_')`.
    The comment states the intent verbatim: "This ensures favorites, custom groups, and EPG
    remain matched after re-sync."
  - `crispy-m3u` `src/id.rs` is a faithful Rust port of exactly that function
    (`djb2_hash`, `generate_stable_id_base`, `uniquify_stable_id`, `resolve_collision`).
  - `crispy-m3u` priority order: `tvg_id` > `url` > `name`; URL fallback is
    `url_{djb2:08x}`, name fallback `name_{djb2:08x}`, final fallback `"unknown"`.
- **Recommended hybrid** (what I would ship): a **two-level** identity.
  1. `channel.stable_id` = xxh3 of `(source_id, provider_key)` — the hard key.
  2. A `channel_alias` table mapping *secondary* keys (tvg-id, normalised name, logo
     hash) to `channel_id`, so a favourite can be re-attached after the provider renames
     the channel but keeps the same `tvg-id`, and so a user can carry a favourite across
     two sources that expose the same EPG id.
  This is what `better-iptv` effectively does with `channels.epg_match_key` /
  `epg_match_source` plus the manual "Fix a channel…" UI.

### 6.5 Pragma / ingestion recipe (measured, from `better-iptv`)

```
ingest connection:  PRAGMA journal_mode = WAL; synchronous = NORMAL;
                    foreign_keys = OFF; temp_store = MEMORY;
                    cache_size = -2048; wal_autocheckpoint = 0; journal_size_limit = 4 MiB
staging DB:         ATTACH 'ingest.db' AS ingest  (journal_mode=OFF, synchronous=OFF)
staging table:      seq INTEGER PRIMARY KEY, knum INTEGER, start, stop, keep, title,
                    description, category   + INDEX (knum)
swap:               INSERT OR FAIL INTO programme SELECT … FROM ingest.epg_ingest
                    WHERE knum = ? AND keep = 1     -- one channel per transaction
                    chunk size 20 000 rows
```
`foreign_keys = OFF` on the ingest connection is deliberate: with FKs on, SQLite gives every
insert into a child table of an immediate foreign key a statement journal, which under
`temp_store = memory` holds every reused page in RAM.

---

## 7. Provider quirks that break players

### 7.1 Connection limits / "the provider refused the stream"

- Most lines allow **one** stream. `better-iptv`'s FAQ (verbatim): *"Many providers allow
  one stream per line and count the previous one as open for a little while after you stop
  it, so quick zapping trips the limit. Some of them are more generous with players they
  recognise."* The error text used there is
  **"Couldn't play …. The provider refused the stream, maybe because your line is already in use."**
- **The exact grace period is provider-specific** and is published nowhere; commonly
  reported as a few seconds up to ~30 s. **[The "30 s" figure is not specified by any
  primary source I found — treat it as folklore.]**
- `user_info.active_cons` vs `user_info.max_connections` tell you the current occupancy.
  A recording also holds a connection; catch-up playback counts as a connection.
- Mitigations: (a) fully close the previous HTTP connection / kill the player process
  before opening the next; (b) serialise opens per source behind a mutex; (c) on refusal,
  wait 1–3 s and retry once before surfacing an error; (d) `active_cons` polling.
- IP pinning: providers may block an account that streams from several IPs. Keep one egress
  IP for the session; don't switch VPN mid-session.

### 7.2 User-Agent blocking

- The provider must see a UA it recognises. Observed real strings:
  - `better-iptv` (`src-tauri/src/http.rs`):
    `const TIVIMATE_HTTP_USER_AGENT: &str = "TiviMate/4.7.0 (Linux;Android 10) ExoPlayerLib/2.18.1";`
    `const VLC_HTTP_USER_AGENT: &str = "VLC/3.0.20 LibVLC/3.0.20";`
  - Dispatcharr migration: `TiviMate/5.16 (Android 12)`, later corrected to
    `TiviMate/5.1.6 (Android 12)`.
  - `technomancer702/nodecast-tv`: `tivimate: 'TiviMate/4.7.0'`, `vlc: 'VLC/3.0.20 LibVLC/3.0.20'`.
  - OTT Navigator's own default (documented):
    `OTT Navigator/1.7.5.2 (Linux;Android 12; en; HASH)` for API requests and
    `OTT Navigator/1.7.5.2 (Linux;Android 12; HASH) ExoPlayerLib/2.x.x` for streams.
  - `ynotv` default: `VLC/3.0.18 LibVLC/3.0.18`.
- The UA must be sent on **playlist downloads, EPG downloads and every stream request**
  (`better-iptv`: "the app then identifies itself as that player for playlist downloads, the
  guide and every stream").
- **Diagnostic technique worth copying** (`ynotv` `stream_probe.rs`): probe the same URL
  twice, once with the player's UA and once with a browser UA
  (`BROWSER_PROBE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36
  (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"`). If the browser UA works and the
  player UA doesn't, the server is **blocking the client**, not the stream being dead.
  The comment says exactly that.

### 7.3 Referrer / header checks

- Sources express required headers as `#EXTVLCOPT:http-referrer=…`,
  `#EXTVLCOPT:http-user-agent=…`, `#EXTHTTP:…`, or the Kodi URL suffix
  `|referer=…&user-agent=…`.
- OTT Nav's precedence: app-wide setting → channel's `#EXTHTTP`/`stream_headers` →
  channel's own `#EXTVLCOPT:http-user-agent` / `|User-Agent=` → provider's
  "HTTP User Agent (Content)" → "HTTP User Agent (API)" → the app default.

### 7.4 HTTP vs HTTPS / mixed content

- `server_info.https_port` is often empty and the panel may not have TLS on the streaming
  port. Xtream-Masters' error table: "Works over HTTP, fails over HTTPS → SSL is not set up
  on that port, or the app uses the HTTP port with `https://`". Use `http://` unless
  `https_port` is configured; never mix an `https://` EPG URL with `http://` streams in a
  webview (mixed-content blocking) — use an HTTP EPG URL or proxy it.
- Some providers geo-/protocol-restrict the *panel* port but not the *streaming* port.

### 7.5 `tvg-id` mismatch → empty EPG (the #1 support ticket)

- Symptom: channels list fine, grid empty.
- Causes: (a) `tvg-id` ≠ XMLTV `<channel id>` (case-sensitive by default); (b) the guide
  uses `display-name` only; (c) the provider's `epg_channel_id` contains several ids
  separated by `;`/`,`; (d) the guide's times are off by hours (looks like "no programme
  now"); (e) the guide was downloaded but the channel→guide match was never computed.
- Diagnostics to ship: show *matched / total* channel counts, the exact `tvg-id` used, the
  guide's candidate `display-name`s, and a manual "Fix a channel…" picker
  (all three exist in `better-iptv`).

### 7.6 HLS vs TS variant preference

- `user_info.allowed_output_formats` decides what the line may use. If your preferred
  format isn't allowed, fall back to the first allowed one (`crispy-xtream`
  `effective_channel_extension`).
- Practical trade-offs: `.ts` is more robust for fast zapping (no manifest re-fetch) but
  many panels serve it as a chunked HTTP stream that stalls under some players; `.m3u8` is
  more robust for long playback and gives proper seek points, but every zapping re-fetches
  the manifest. **Offer a user setting (TS preferred / HLS preferred / Auto) and remember
  it per source.**
- For catch-up, an HLS archive URL returns a **VOD segment list** — easy to seek; a TS
  archive URL is a single continuous stream.

### 7.7 DRM

- Detection: the HLS master manifest contains `#EXT-X-KEY` or `#EXT-X-SESSION-KEY`.
  `ynotv` `detect_hls_drm()` maps:
  - Widevine: `com.widevine.alpha` / `edef8ba9-79d6-4ace-a3c8-27dcd51d21ed`
  - FairPlay: `com.apple.streamingkeydelivery` / `skd://`
  - PlayReady: `playready` / `9a04f079-9840-4286-ab92-e65be0885f95`
  - Sample-AES: `sample-aes`
- **Consequence:** FFmpeg/mpv cannot decrypt Widevine, FairPlay or PlayReady. Widevine
  requires a CDM (Chromium + a shaka-packager/Widevine-CDM licence server, or an
  ExoPlayer/DashPlayer stack with a licence server). If you ship an mpv/FFmpeg-based
  player, **Widevine streams are unplayable** — detect them, mark the channel as DRM, and
  either (a) route to an external player / WebView, or (b) refuse with a clear message.
  Do **not** let the player hang on a DRM stream.

### 7.8 Geo-blocking

- HTTP status codes used by `ynotv` (`const GEOBLOCK_STATUSES: &[u16] = &[403, 451, 426, 423];`):
  - `403` Forbidden — the classic geo/IP block.
  - `451` Unavailable For Legal Reasons (RFC 7725) — licensing, not geography.
  - `426` Upgrade Required — TLS/version requirement.
  - `423` Locked.
- iptv-org labels such streams in the display name: `[Geo-blocked]`, `[Not 24/7]`.
- A stream can also be geo-blocked *inside* the manifest (segments 403 while the playlist
  200s) — probe a segment, not just the playlist.

### 7.9 Other concrete quirks

- **Placeholder streams**: `ynotv`'s `PLACEHOLDER_PATHS` = `/video/black.ts`, `/black.ts`,
  `/blank.ts`, `/video/blank.ts`, `/placeholder.ts`, `/video/placeholder.ts`, `/null.ts`,
  `/video/null.ts`. A 200 on one of these is a dead channel, not a live one.
- **`container_extension` can be `null`** — default to `mp4` (VOD) / `ts` (live).
- **`get_vod_info` on a missing id returns `info: []`**, not an error.
- **`tmdb_id` is often `''`** while the real id is in `info.tmdb`.
- **EPG titles/descriptions in the Xtream JSON API are base64**; in XMLTV they are not.
  Detect: valid base64 of printable UTF-8 → decode. (`crispy-xtream` `decode_epg_field`.)
- **Numeric fields are strings** (`stream_id` is an int, but `added`, `exp_date`,
  `created_at`, `max_connections`, `active_cons` are strings).
- **`epg_channel_id` may hold several ids** separated by `;`/`,` — split and try each.
- **Guide times may be local time labelled `+0000`** — `tvg-shift` / `catchup-correction`
  exist for this; `better-iptv` adds a "treat the guide's times as this computer's local
  time" toggle.
- **Archive lag**: request `duration + ~5 min` or the end of the programme is clipped.
- **Xtream timeshift start is provider-local time** — convert with `server_info.timezone`.
- **XMLTV `<programme>` may omit `stop`** — infer it from the next programme's start, or
  from `start + duration` if the source provides one (`crispy-catchup` treats a missing
  stop as `NO_STOP`).
- **`clumpidx="0/1"`** — two programmes can share a timeslot (News; Weather). Handle by
  ordering on `clumpidx`.

### 7.10 Failover groups — how existing players do it

**ynotv** (`packages/ui/src/services/failover-groups.ts`, `failoverMatcher.ts`) is the most
complete open implementation I found:

- **Data model**: `failover_groups(group_id, name, created_at)` and
  `failover_members(stream_id UNIQUE, group_id, priority)`. Priorities are renormalised
  0,1,2,… with no gaps after every removal or reorder. A channel may belong to at most one
  group (enforced in the app with a pre-flight check, because the UNIQUE constraint would
  abort a whole transaction).
- **Candidate walk**: `getFailoverCandidatesAfter(stream_id)` returns the members after the
  current one ordered by `priority`, filtered to channels that are enabled and whose source
  is enabled.
- **Auto-clustering** (`failoverMatcher.ts` `parseChannelInfo` + `scoreChannelPair`):
  - Parse the channel name into: country prefix (`[US]`, `(US)`, `US | `), east/west feed
    (`east|est|(east)|(ny)` vs `west|pst|(west)|(la)`), US callsign (`(WABC)`, `KOVR` —
    with a stop-list of ~45 English words that look like callsigns: `WITH WHEN WHAT WILL
    WANT WELL WILD WAVE WARS …`), and quality (`4k|8k|uhd` → 4k; `fhd|1080p|1080i|raw` → fhd;
    `hd|720p` → hd; else sd).
  - **Hard rules (any failure ⇒ not a pair):**
    1. Feed separation — `strict_separate` mode refuses any east/west mismatch;
       `merge_neutral_east` (default) treats neutral as east-compatible but still refuses
       east-vs-west.
    2. Country compatibility — `same_only` (default), `acceptable_set`, or `any`.
    3. **Different callsigns ⇒ never link** (they are different local stations). Same
       callsign ⇒ score **0.98**.
  - **Scores:** exact clean-name match across sources ⇒ **0.98**; same name but different
    quality ⇒ **0.96** ("Quality Variant"); token Jaccard ≥ 0.8 ⇒ **0.88**;
    ≥ 0.6 ⇒ **0.76**. Default `minConfidence` = **0.75**.
  - **Quality priority** for member ordering: `4k(0) > fhd(1) > hd(2) > sd(3)`.
  - User-facing: "Always Play Primary" mode, an "Auto-cluster duplicate channels into
    failover groups" modal, and a sports-team linking mode where a team's channel list is
    an ordered failover chain with auto-swap.
- **m3u-editor** has the same concept in Laravel: `ChannelFailover(user_id, channel_id,
  channel_failover_id, sort, metadata)` — note it also uses the **failover channel's own
  provider timezone** when building the catch-up window, and redirects the timeshift request
  to the failover stream when the primary has no archive support.
- **better-iptv** does *not* implement failover groups; it implements automatic stream
  fallback: "If a stream cannot be played, the app tells you instead of doing nothing",
  plus a `channel-probe` style health check and iptv-org logo fallback.

**Recommendation for a TiviMate-class player:** implement (1) a per-source health probe that
classifies each stream as `alive | dead | geoblocked | drm | placeholder` (the taxonomy
above is ynotv's and is a good one), (2) manual failover groups with priority ordering,
and (3) *optional* auto-clustering behind the name-parsing heuristic above — but treat
auto-clustering as a convenience, never as a source of truth, because it will happily link
"CNN" from two different countries if the user relaxes the country rule.

---

## Appendix A — quick endpoint cheat sheet

```
# Xtream Codes
http://HOST:PORT/player_api.php?username=U&password=P                       # auth
http://HOST:PORT/player_api.php?username=U&password=P&action=get_live_categories
http://HOST:PORT/player_api.php?username=U&password=P&action=get_live_streams[&category_id=N]
http://HOST:PORT/player_api.php?username=U&password=P&action=get_vod_categories
http://HOST:PORT/player_api.php?username=U&password=P&action=get_vod_streams[&category_id=N]
http://HOST:PORT/player_api.php?username=U&password=P&action=get_vod_info&vod_id=N
http://HOST:PORT/player_api.php?username=U&password=P&action=get_series_categories
http://HOST:PORT/player_api.php?username=U&password=P&action=get_series[&category_id=N]
http://HOST:PORT/player_api.php?username=U&password=P&action=get_series_info&series_id=N
http://HOST:PORT/player_api.php?username=U&password=P&action=get_short_epg&stream_id=N[&limit=4]
http://HOST:PORT/player_api.php?username=U&password=P&action=get_simple_data_table&stream_id=N
http://HOST:PORT/xmltv.php?username=U&password=P
http://HOST:PORT/get.php?username=U&password=P&type=m3u_plus&output=ts   # output=m3u8
http://HOST:PORT/panel_api.php?username=U&password=P[&action=get_epg&stream_id=N&from_now=1]
http://HOST:PORT/live/U/P/ID.ts|.m3u8
http://HOST:PORT/movie/U/P/ID.{container_extension}
http://HOST:PORT/series/U/P/EPISODE_ID.{container_extension}
http://HOST:PORT/timeshift/U/P/{minutes}/YYYY-MM-DD:HH-MM/ID.ts
http://HOST:PORT/streaming/timeshift.php?username=U&password=P&stream=ID&start=YYYY-MM-DD:HH-MM&duration=MIN

# Stalker / Ministra
http://HOST:25461/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml
http://HOST:25461/portal.php?type=stb&action=get_profile
http://HOST:25461/portal.php?type=itv&action=get_genres
http://HOST:25461/portal.php?type=itv&action=get_ordered_list&genre=N&p=1
http://HOST:25461/portal.php?type=vod&action=get_categories
http://HOST:25461/portal.php?type=vod&action=get_ordered_list&category=N&p=1
http://HOST:25461/portal.php?type=itv&action=create_link&cmd=CMD&forced_storage=undefined&disable_ad=0&download=0
http://HOST:25461/portal.php?type=itv&action=get_short_epg&ch_id=N&size=M
http://HOST:25461/portal.php?type=account_info&action=get_main_info
http://HOST:25461/portal.php?type=watchdog&action=get_events
```

## Appendix B — source index

| Fact | Source |
|---|---|
| XMLTV DTD, element order, time format | https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd |
| Catchup format specifiers, M3U/XMLTV elements, EXTGRP semantics | https://github.com/kodi-pvr/pvr.iptvsimple/blob/Piers/README.md |
| `catchup-id` attribute read | `pvr.iptvsimple/src/iptvsimple/data/EpgEntry.cpp:206` |
| Catchup modes enum, granularity, terminating source | `crispy-catchup/src/mode.rs` |
| Catchup template engine, `is_within_catchup_window` | `crispy-catchup/src/template.rs` |
| Flussonic/Xtream auto-generated templates + regexes | `crispy-catchup/src/provider.rs` |
| Xtream JSON field names, base64 EPG, `info: []` quirk | `crispy-xtream/src/types.rs`, `src/client.rs` |
| Xtream URL construction, `effective_channel_extension` | `crispy-xtream/src/url.rs` |
| M3U attribute list, EPG URL attrs, EXTGRP, stable id | `crispy-m3u/src/parser.rs`, `src/id.rs` |
| Stalker endpoints, headers, token TTL, device ids | `crispy-stalker/src/client.rs`, `src/session.rs` |
| Catchup modes + `{catchup-id}` + archive window + `DURATION_BUFFER_MINUTES` | `Dispatcharr/apps/timeshift/helpers.py` |
| Timeshift URL rewriting, provider TZ, catchup-source generation | `m3ue/m3u-editor/app/Services/PlaylistService.php` + `tests/Feature/CatchupSourceModesTest.php`, `TimeshiftUrlRewritingTest.php`, `M3uImportCatchupShiftTest.php` |
| M3U stable id, catchup URL builder, Xtream client, failover | `tbeezy/ynotv` (`m3u-parser.ts`, `m3u-catchup.ts`, `xtream-client.ts`, `failover-groups.ts`, `failoverMatcher.ts`, `stream_probe.rs`) |
| SQLite schema, EPG store/ingest, matcher, UA constants | `mewset/better-iptv` (`db/schema.rs`, `epg/store.rs`, `epg_domain/matching.rs`, `epg_domain/time.rs`, `http.rs`) |
| iptv-org conventions | `iptv-org/iptv/docs/{stream-id,stream-description-scheme,playlists,xtream-codes}.md` |
| Xtream timeshift script form | `Fazzani/xtreamcode/xcodestream/url_api_builder.py` |
| Xtream panel field emission (`rating_5based`, live object) | `gtaman92/XtreamCodesExtendAPI/player_api.php`, `panel_api.php` |
| Stalker handshake/create_link | https://www.quassi.nl/2020/03/23/the-wonderful-world-of-stalker-iptv-2/ , `esxbr/plugin.video.stalker` |
| User-Agent strings in the wild | `mewset/better-iptv/src-tauri/src/http.rs`, `Dispatcharr/core/migrations/0011_*`, `technomancer702/nodecast-tv/server/db.js`, https://faq.ott-nav.com/formats/user-agent |

## Appendix C — explicitly unverified / flagged

1. **`get_short_episodes` and `action=get_episodes` do not exist** in the Xtream Codes
   player API (grep.app: 0 hits; absent from every reference doc read). Implement
   `get_series_info`.
2. **The array-of-arrays category form** (`[["1","News"]]`) is widely reported for legacy
   Xtream Codes but could not be confirmed from a primary source in this session; every
   primary source I read returns the object form. Support both.
3. **The "30 s" grace period** for a stopped stream still counting as open is folklore; no
   primary source states a number.
4. **`epg_channel_id` containing several `;`/`,`-separated ids** is widely reported; not
   confirmed from a primary source in this session — but it is cheap to support.
5. **`x-display-name` and `x-cc`** are not IPTV attributes (0 relevant hits in the GitHub
   code index).
6. **`catchup="custom"`** is not a real mode value; the custom-template mode is `default`.
7. **Java-style `${(b)yyyyMMddHHmmss}` tokens** and **`{{…}}` double-brace tokens** are
   real in shipped playlists but are not part of any published specification.
8. **Flussonic `flussonic-hls` output differs between Kodi and OTT Navigator**
   (`timeshift_rel-` vs `archive-`). Support both shapes, driven by the live URL.
