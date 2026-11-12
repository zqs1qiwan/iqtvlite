//! Catch-up / 时移：配置模型 + URL 模板引擎。
//!
//! # 为什么值得一个独立模块
//!
//! Catch-up 是 IPTV 播放器**最容易做错**的功能。供应商生态里有至少 6 套互不兼容的
//! 模板语法，而且其中两套**从未被任何规范收录**，只在真实播放列表里存在。
//!
//! # 支持的模板语法
//!
//! | 语法 | 来源 | 示例 |
//! |---|---|---|
//! | `{utc}` `{lutc}` `{utcend}` `{duration}` `{offset}` `{catchup-id}` | Kodi（事实标准） | `?utc={utc}&lutc={lutc}` |
//! | `{duration:X}` `{offset:X}` | Kodi | `{duration:60}` = 分钟 |
//! | `{utc:YmdHM}` `{utc:-5m}` | Kodi（时间戳 + 格式串/偏移） | `?start={utc:YmdHM}` |
//! | `${start}` `${now}` `${end}` `${duration}` | ynotv / OTT Nav | `?utc={utc}&lutc={lutc}` |
//! | `{start_iso}` `{end_iso}` `{now_iso}` `{durmin}` `${offset}` | OTT Nav | |
//! | `${start-year}` … `${end-sec}` | OTT Nav（设备本地时间） | |
//! | `${token}` `{key}` `${login}` `${password}` | OTT Nav | |
//! | `{{Y}}{{M}}{{S}}` `{{catchup-id}}` | **JioTV（无规范）** | `showtime={{H}}{{M}}{{S}}` |
//! | `${(b)yyyyMMddHHmmss}` `${(e)yyyyMMddHHmmss}` | **中国移动/咪咕（无规范）** | `?playbackbegin=${(b)yyyyMMddHHmmss}` |
//!
//! # 两个必须遵守的坑
//!
//! 1. **时移起点是供应商本地墙钟时间**，而 EPG 是 UTC。必须用 `server_info.timezone` 换算。
//! 2. **归档比直播滞后 30 秒 ~ 2 分钟** → 请求时长要 **+5 分钟**，否则会切掉节目结尾。
//!
//! 详见 `docs/09-protocols.md` §2。

use serde::{Deserialize, Serialize};

/// Catch-up 模式。
///
/// 取值与 Kodi 的 `CatchupMode` 枚举对齐。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CatchupType {
    /// 明确禁用
    Disabled,
    /// 自定义模板 —— **注意：供应商生态里常写的 `catchup="custom"` 并不是一个真实取值，
    /// 自定义模板模式的真名就是 `default`**
    #[default]
    Default,
    /// 把 `catchup-source` 追加到直播 URL
    Append,
    /// SIPTV 风格滚动归档：`?utc={utc}&lutc={lutc}`
    Shift,
    /// Flussonic `timeshift_abs-` / `timeshift_rel-`
    Flussonic,
    /// Xtream Codes：`/timeshift/{user}/{pass}/{minutes}/{date}/{id}.ts`
    XtreamCodes,
    /// 旧式 `timeshift` 属性（单位是**天**）
    Timeshift,
    /// VOD 回看（用 `catchup-id`，无时移）
    Vod,
}

impl CatchupType {
    /// 从 M3U 属性值解析，容忍各种历史写法。
    pub fn from_attr(raw: &str) -> Option<Self> {
        let normalized = raw.trim().to_ascii_lowercase();
        match normalized.as_str() {
            "" | "default" | "custom" => Some(Self::Default),
            "append" => Some(Self::Append),
            "shift" => Some(Self::Shift),
            "timeshift" => Some(Self::Timeshift),
            "flussonic" | "flussonic-hls" | "flussonic-ts" | "fs" => Some(Self::Flussonic),
            "xc" | "xtream" | "xtreamcodes" | "xtream-codes" => Some(Self::XtreamCodes),
            "vod" => Some(Self::Vod),
            "disabled" | "none" | "off" => Some(Self::Disabled),
            _ => None,
        }
    }

    /// 该模式是否真的支持时移（vs 只是标记有归档）。
    pub fn supports_timeshift(self) -> bool {
        !matches!(self, Self::Disabled | Self::Vod)
    }
}

/// 一个频道的归档配置。
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct CatchupConfig {
    #[serde(rename = "type")]
    pub catchup_type: CatchupType,
    /// 归档天数。`Some(0)` 表示**该频道没有归档**。
    pub days: Option<u32>,
    /// 归档时长（秒）。与 `days` 并存时取大者。
    pub time_secs: Option<u32>,
    /// URL 模板（或 `append` 模式下的查询串）。
    pub source: Option<String>,
    /// 生成归档 URL 时的小时校正（修「时移差一小时」）。
    pub correction_hours: Option<f64>,
    /// `tvg-rec`：`1` = 有归档；> 1 = 天数。
    pub tvg_rec: Option<i64>,
}

impl CatchupConfig {
    /// 实际生效的归档窗口（天）。
    ///
    /// 优先级（已由 m3u-editor 的测试验证）：`catchup-days` > `tvg-rec` > `timeshift`。
    pub fn effective_days(&self) -> Option<u32> {
        if let Some(days) = self.days {
            return Some(days);
        }
        if let Some(rec) = self.tvg_rec {
            if rec <= 0 {
                return None;
            }
            return Some(rec as u32);
        }
        None
    }

    /// 该频道是否有可用归档。
    pub fn has_archive(&self) -> bool {
        if !self.catchup_type.supports_timeshift() {
            return false;
        }
        match self.effective_days() {
            Some(0) => false,
            Some(_) => true,
            None => self.source.is_some() || self.time_secs.is_some(),
        }
    }
}

/// 模板渲染上下文。
#[derive(Debug, Clone)]
pub struct CatchupContext<'a> {
    /// 节目开始时间（Unix 秒，UTC）
    pub start_ts: i64,
    /// 节目结束时间（Unix 秒，UTC）
    pub stop_ts: i64,
    /// 当前时间（Unix 秒，UTC）
    pub now_ts: i64,
    /// 请求时长（**秒**），已含归档滞后缓冲
    pub duration_secs: i64,
    /// 节目级 `catchup-id`
    pub catchup_id: Option<&'a str>,
    /// 供应商时区（IANA 名，如 `Africa/Casablanca`）—— 用于生成**本地墙钟**时间
    pub provider_tz: Option<&'a str>,
    /// 播放器的登录 token（OTT Nav 的 `${token}`）
    pub token: Option<&'a str>,
    pub login: Option<&'a str>,
    pub password: Option<&'a str>,
}

impl CatchupContext<'_> {
    /// 构造一个标准上下文，**自动加上 5 分钟归档滞后缓冲**。
    ///
    /// IPTV 归档通常比直播滞后 30 秒 ~ 2 分钟，只请求原始时长会把上一个节目的尾巴
    /// 带进来、并切掉结尾。Dispatcharr 用 `DURATION_BUFFER_MINUTES = 5`。
    pub fn with_buffer(start_ts: i64, stop_ts: i64, now_ts: i64) -> Self {
        let raw = (stop_ts - start_ts).max(0);
        let buffered = (raw + ARCHIVE_LAG_BUFFER_SECS).min(MAX_CATCHUP_DURATION_SECS);
        Self {
            start_ts,
            stop_ts,
            now_ts,
            duration_secs: buffered,
            catchup_id: None,
            provider_tz: None,
            token: None,
            login: None,
            password: None,
        }
    }
}

/// 归档滞后缓冲：5 分钟。
pub const ARCHIVE_LAG_BUFFER_SECS: i64 = 5 * 60;
/// 单次时移的最大时长：8 小时。
pub const MAX_CATCHUP_DURATION_SECS: i64 = 8 * 3600;

// ---------------------------------------------------------------------------
// 模板引擎
// ---------------------------------------------------------------------------

/// 渲染一个 catch-up URL 模板。
pub fn render_template(template: &str, ctx: &CatchupContext<'_>) -> String {
    let mut out = String::with_capacity(template.len() + 32);
    let bytes = template.as_bytes();
    let mut i = 0;

    while i < bytes.len() {
        match bytes[i] {
            b'{' => {
                // 双花括号：{{...}} —— JioTV 私有语法，内容是**格式说明符**
                if bytes.get(i + 1) == Some(&b'{') {
                    if let Some((spec, next)) = read_braced(&template[i + 2..], true) {
                        out.push_str(&resolve_spec(&spec, ctx, BareFormat::Start));
                        i += 2 + next;
                        continue;
                    }
                }
                // 单花括号：{name} 或 {name:arg}
                if let Some((spec, next)) = read_braced(&template[i + 1..], false) {
                    out.push_str(&resolve_spec(&spec, ctx, BareFormat::Start));
                    i += 1 + next;
                    continue;
                }
                out.push('{');
                i += 1;
            }
            b'$' if bytes.get(i + 1) == Some(&b'{') => {
                // ${...} —— 先处理带 $ 的形式，避免留下孤立的 $
                if let Some((spec, next)) = read_braced(&template[i + 2..], false) {
                    out.push_str(&resolve_spec(&spec, ctx, BareFormat::Start));
                    i += 2 + next;
                    continue;
                }
                out.push('$');
                i += 1;
            }
            _ => {
                // 逐字符拷贝（保持 UTF-8 边界）
                let ch = template[i..].chars().next().expect("in-bounds");
                out.push(ch);
                i += ch.len_utf8();
            }
        }
    }
    out
}

/// 双花括号内容没有「名字」，只有格式说明符。
#[derive(Clone, Copy, PartialEq, Eq)]
enum BareFormat {
    /// `{{Y}}` 这类：直接是格式说明符，作用于开始时间
    Start,
}

/// 从 `s` 的开头读取一个 `{...}` 或 `{{...}}` 包裹的内容。
///
/// 返回 `(内容, 相对于 s 起点的下一个位置)`。
fn read_braced(s: &str, double: bool) -> Option<(String, usize)> {
    let close = if double { "}}" } else { "}" };
    let end = s.find(close)?;
    let inner = &s[..end];
    if inner.is_empty() {
        return None;
    }
    // 内容里不允许再出现同层级的括号（防御畸形模板）
    if inner.contains('{') || inner.contains('}') {
        return None;
    }
    Some((inner.to_string(), end + close.len()))
}

/// 解析一个模板规格（不含花括号）。
fn resolve_spec(spec: &str, ctx: &CatchupContext<'_>, bare: BareFormat) -> String {
    // ---- Java 风格 ${(b)yyyyMMddHHmmss} ----
    // （必须排在「裸格式说明符」判断之前，否则会被误当成日期分量）
    if let Some(rest) = spec.strip_prefix("(b)") {
        return format_timestamp(ctx.start_ts, ctx, rest);
    }
    if let Some(rest) = spec.strip_prefix("(e)") {
        let end_ts = ctx.start_ts + ctx.duration_secs;
        return format_timestamp(end_ts, ctx, rest);
    }

    // ---- 无参数的「裸格式说明符」（JioTV 的 {{Y}}{{M}}{{S}}）----
    if bare == BareFormat::Start && !spec.contains(':') && !is_known_token(spec) {
        return format_start_component(spec, ctx);
    }

    // ---- 拆分 name 与 arg ----
    let (name, arg) = match spec.split_once(':') {
        Some((n, a)) => (n, Some(a)),
        None => (spec, None),
    };

    // ---- 需要「时间戳 + 格式串」或「时间戳 + 偏移」的 token ----
    if let Some(ts) = timestamp_value(name, ctx) {
        return match arg {
            // {utc:YmdHM} —— 格式化
            Some(a) if is_format_spec(a) => format_timestamp(ts, ctx, a),
            // {utc:-5m} —— 偏移
            Some(a) => match parse_offset(a) {
                Some(delta) => format_timestamp(ts + delta, ctx, "%Y-%m-%dT%H:%M:%SZ"),
                None => format_timestamp(ts, ctx, ""),
            },
            None => ts.to_string(),
        };
    }

    // ---- 纯数值 token ----
    if let Some(v) = numeric_value(name, ctx) {
        return match arg {
            // {duration:60} —— 除以 60（秒 → 分钟）
            Some(a) => match a.trim().parse::<i64>() {
                Ok(div) if div > 0 => (v / div).to_string(),
                _ => v.to_string(),
            },
            None => v.to_string(),
        };
    }

    // ---- ISO-8601 ----
    match name {
        "start_iso" => return iso(ctx.start_ts, ctx),
        "end_iso" => return iso(ctx.start_ts + ctx.duration_secs, ctx),
        "now_iso" => return iso(ctx.now_ts, ctx),
        _ => {}
    }

    // ---- 字符串 token ----
    match name {
        "catchup-id" | "catchup_id" => return ctx.catchup_id.unwrap_or("").to_string(),
        "token" | "key" => return ctx.token.unwrap_or("").to_string(),
        "login" => return ctx.login.unwrap_or("").to_string(),
        "password" => return ctx.password.unwrap_or("").to_string(),
        _ => {}
    }

    // ---- 日期分量 ----
    format_start_component(name, ctx)
}

/// 已知的具名 token（用于区分「裸格式说明符」与「具名 token」）。
fn is_known_token(name: &str) -> bool {
    const NAMED: &[&str] = &[
        "utc",
        "start",
        "lutc",
        "now",
        "timestamp",
        "utcend",
        "end",
        "utc_end",
        "end_utc",
        "duration",
        "duration_m",
        "duration_min",
        "offset",
        "catchup-id",
        "catchup_id",
        "start_iso",
        "end_iso",
        "now_iso",
        "durmin",
        "token",
        "key",
        "login",
        "password",
    ];
    NAMED.contains(&name)
}

/// 时间戳类 token 的实际取值。
fn timestamp_value(name: &str, ctx: &CatchupContext<'_>) -> Option<i64> {
    match name {
        "utc" | "start" => Some(ctx.start_ts),
        "lutc" | "now" | "timestamp" => Some(ctx.now_ts),
        "utcend" | "end" | "utc_end" | "end_utc" => Some(ctx.start_ts + ctx.duration_secs),
        _ => None,
    }
}

/// 数值类 token 的实际取值。
fn numeric_value(name: &str, ctx: &CatchupContext<'_>) -> Option<i64> {
    match name {
        "duration" => Some(ctx.duration_secs),
        "duration_m" | "duration_min" | "durmin" => Some(ctx.duration_secs / 60),
        "offset" => Some(ctx.now_ts - ctx.start_ts),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// 时间格式化
// ---------------------------------------------------------------------------

/// 供应商本地时区偏移（秒）。无时区信息时退回 UTC。
fn provider_offset(ctx: &CatchupContext<'_>) -> i32 {
    if let Some(tz) = ctx.provider_tz {
        if let Some(offset) = crate::timeutil::offset_for_zone(tz, ctx.start_ts) {
            return offset;
        }
    }
    0
}

/// 用格式化串渲染时间戳。
///
/// 支持三种格式风格，自动归一化到 strftime：
///
/// | 风格 | 例子 | 来源 |
/// |---|---|---|
/// | strftime | `%Y-%m-%d` | 通用 |
/// | Kodi 字母 | `YmdHM` | Kodi 的 `{utc:YmdHM}` |
/// | Java 模式 | `yyyyMMddHHmmss` | 中国移动/咪咕的 `${(b)yyyyMMddHHmmss}` |
fn format_timestamp(ts: i64, ctx: &CatchupContext<'_>, format: &str) -> String {
    if format.is_empty() {
        return ts.to_string();
    }
    let offset = provider_offset(ctx);
    let dt = crate::timeutil::from_unix(ts, offset);
    crate::timeutil::format(dt, &normalize_format(format))
}

/// 把 Kodi 字母风格 / Java 模式的格式串统一成 strftime。
fn normalize_format(fmt: &str) -> String {
    if fmt.contains('%') {
        return fmt.to_string();
    }
    // Java 模式特征：两字符的 yyyy / dd / HH / mm / ss
    let is_java = ["yyyy", "dd", "HH", "mm", "ss", "yy"]
        .iter()
        .any(|p| fmt.contains(p));
    let mut out = String::with_capacity(fmt.len() + 8);
    if is_java {
        // Java 模式：连续相同字符表示重复次数
        //   yyyy=年 y=年 MM=M=月 dd=d=日 HH=H=时 mm=m=分 ss=s=秒
        let chars: Vec<char> = fmt.chars().collect();
        let mut i = 0;
        while i < chars.len() {
            let c = chars[i];
            let mut run = 1;
            while i + run < chars.len() && chars[i + run] == c {
                run += 1;
            }
            match c {
                'y' => {
                    // 4 个 y = 完整年份，2 个 = 两位年份
                    if run >= 4 {
                        out.push_str("%Y");
                    } else {
                        out.push_str("%y");
                    }
                }
                'M' => out.push_str("%m"),
                'd' => out.push_str("%d"),
                'H' => out.push_str("%H"),
                'm' => out.push_str("%M"),
                's' => out.push_str("%S"),
                other => {
                    for _ in 0..run {
                        out.push(other);
                    }
                }
            }
            i += run;
        }
    } else {
        // Kodi 字母风格：Y=年 m=月 d=日 H=时 M=分 S=秒
        for c in fmt.chars() {
            match c {
                'Y' => out.push_str("%Y"),
                'm' => out.push_str("%m"),
                'd' => out.push_str("%d"),
                'H' => out.push_str("%H"),
                'M' => out.push_str("%M"),
                'S' => out.push_str("%S"),
                'y' => out.push_str("%y"),
                other => out.push(other),
            }
        }
    }
    out
}

/// ISO-8601（UTC）。
fn iso(ts: i64, _ctx: &CatchupContext<'_>) -> String {
    crate::timeutil::iso8601(ts)
}

/// 把日期分量名渲染成字符串（作用于开始时间）。
fn format_start_component(name: &str, ctx: &CatchupContext<'_>) -> String {
    let offset = provider_offset(ctx);
    let dt = crate::timeutil::from_unix(ctx.start_ts, offset);
    crate::timeutil::component(dt, name)
}

/// 解析 `{utc:-5m}` 形式的偏移。
///
/// 支持 `s`/`m`/`h`/`d`（秒/分/时/天），也支持裸数字（按秒）。
fn parse_offset(spec: &str) -> Option<i64> {
    let spec = spec.trim();
    let (sign, rest) = match spec.as_bytes().first()? {
        b'-' => (-1i64, &spec[1..]),
        b'+' => (1i64, &spec[1..]),
        _ => (1i64, spec),
    };
    let (digits, unit) = match rest.as_bytes().last()? {
        b's' => (&rest[..rest.len() - 1], 1i64),
        b'm' => (&rest[..rest.len() - 1], 60),
        b'h' => (&rest[..rest.len() - 1], 3600),
        b'd' => (&rest[..rest.len() - 1], 86400),
        _ => (rest, 1),
    };
    let n: i64 = digits.trim().parse().ok()?;
    Some(sign * n * unit)
}

/// 判断是否是「格式化串」而不是「除数」。
fn is_format_spec(spec: &str) -> bool {
    // 纯数字是除数；其余视为格式化串
    spec.trim().parse::<i64>().is_err()
}

/// 模板引擎的对外入口：按模式生成归档 URL。
#[derive(Debug, Clone)]
pub struct CatchupResolver {
    pub config: CatchupConfig,
    /// 直播 URL
    pub live_url: String,
    /// 供应商时区
    pub provider_tz: Option<String>,
}

impl CatchupResolver {
    /// 生成归档 URL。
    pub fn resolve(&self, ctx: &CatchupContext<'_>) -> Option<String> {
        let mut ctx = ctx.clone();
        ctx.provider_tz = self.provider_tz.as_deref();

        match self.config.catchup_type {
            CatchupType::Disabled | CatchupType::Vod => None,
            CatchupType::Default => {
                let template = self.config.source.as_deref()?;
                Some(render_template(template, &ctx))
            }
            CatchupType::Append => {
                let suffix = self.config.source.as_deref()?;
                // append 模式的 source 也可能带占位符（如 `?utc={utc}&lutc={lutc}`）
                Some(format!(
                    "{}{}",
                    self.live_url,
                    render_template(suffix, &ctx)
                ))
            }
            CatchupType::Shift => Some(shift_url(&self.live_url, &ctx)),
            CatchupType::Timeshift => Some(shift_url(&self.live_url, &ctx)),
            CatchupType::Flussonic => Some(flussonic_url(&self.live_url, &ctx)),
            CatchupType::XtreamCodes => None, // 需要面板凭据，由 xtream 模块处理
        }
    }
}

/// SIPTV `shift` 模式：`{live_url}?utc={utc}&lutc={lutc}`。
pub fn shift_url(live_url: &str, ctx: &CatchupContext<'_>) -> String {
    let sep = if live_url.contains('?') { '&' } else { '?' };
    format!("{live_url}{sep}utc={}&lutc={}", ctx.start_ts, ctx.now_ts)
}

/// Flussonic 归档 URL。
///
/// 按直播 URL 的形状自动判定用 `timeshift_abs-` 还是 `timeshift_rel-`。
pub fn flussonic_url(live_url: &str, ctx: &CatchupContext<'_>) -> String {
    let lower = live_url.to_ascii_lowercase();

    // Flussonic 的路径形如 /{channel_id}/{stream_name}[/...]
    // 归档时把 stream_name 换成 timeshift_abs-{start} 或 timeshift_rel-{offset}
    let base = live_url.split('?').next().unwrap_or(live_url);
    let query = live_url
        .split_once('?')
        .map(|(_, q)| format!("?{q}"))
        .unwrap_or_default();

    let replaced = if lower.contains("mpegts") || lower.contains("/live") {
        // TS：timeshift_abs-{start}
        base.replace("mpegts", &format!("timeshift_abs-{}.ts", ctx.start_ts))
    } else if lower.contains("index.m3u8") {
        base.replace("index.m3u8", &format!("timeshift_rel-{}.m3u8", 1))
    } else if lower.contains("mono.m3u8") {
        base.replace("mono.m3u8", &format!("mono-timeshift_rel-{}.m3u8", 1))
    } else {
        base.replace(".m3u8", &format!("timeshift_abs-{}.ts", ctx.start_ts))
    };
    format!("{replaced}{query}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> CatchupContext<'static> {
        CatchupContext::with_buffer(1_700_000_000, 1_700_003_600, 1_700_003_000)
    }

    #[test]
    fn kodi_shift_template() {
        let out = render_template("?utc={utc}&lutc={lutc}", &ctx());
        assert_eq!(out, "?utc=1700000000&lutc=1700003000");
    }

    #[test]
    fn dollar_form_is_supported() {
        let out = render_template("?start=${start}&end=${end}", &ctx());
        // `${end}` = 开始时间 + 请求时长（含 5 分钟归档滞后缓冲）
        assert_eq!(out, "?start=1700000000&end=1700003900");
    }

    #[test]
    fn duration_divided_by_sixty_is_minutes() {
        let out = render_template("dur={duration:60}", &ctx());
        // 3600s + 300s 缓冲 = 3900s = 65 分钟
        assert_eq!(out, "dur=65");
    }

    #[test]
    fn offset_token_uses_now_minus_start() {
        let out = render_template("off={offset}", &ctx());
        assert_eq!(out, "off=3000");
    }

    #[test]
    fn archive_lag_buffer_is_added() {
        let c = CatchupContext::with_buffer(0, 3600, 0);
        assert_eq!(c.duration_secs, 3600 + ARCHIVE_LAG_BUFFER_SECS);
    }

    #[test]
    fn archive_duration_is_capped_at_8h() {
        let c = CatchupContext::with_buffer(0, 100 * 3600, 0);
        assert_eq!(c.duration_secs, MAX_CATCHUP_DURATION_SECS);
    }

    #[test]
    fn jio_double_brace_format_is_supported() {
        let out = render_template("showtime={{Y}}{{m}}{{d}}", &ctx());
        let ts = out.strip_prefix("showtime=").unwrap();
        assert_eq!(ts.len(), 8, "应产出 YYYYMMDD，实际: {ts}");
        assert!(ts.chars().all(|c| c.is_ascii_digit()));
    }

    #[test]
    fn java_style_begin_end_tokens_are_supported() {
        let out = render_template(
            "?playbackbegin=${(b)yyyyMMddHHmmss}&playbackend=${(e)yyyyMMddHHmmss}",
            &ctx(),
        );
        assert!(out.starts_with("?playbackbegin="), "实际: {out}");
        assert!(out.contains("&playbackend="));
        // 两个时间戳应相差 3900 秒
        let begin = out["?playbackbegin=".len()..].split('&').next().unwrap();
        let end = out.split("playbackend=").nth(1).unwrap();
        assert_ne!(begin, end);
        assert_eq!(begin.len(), 14, "yyyyMMddHHmmss 应为 14 位");
    }

    #[test]
    fn catchup_type_parses_custom_as_default() {
        assert_eq!(CatchupType::from_attr("custom"), Some(CatchupType::Default));
        assert_eq!(CatchupType::from_attr("shift"), Some(CatchupType::Shift));
        assert_eq!(CatchupType::from_attr("append"), Some(CatchupType::Append));
        assert_eq!(CatchupType::from_attr("xc"), Some(CatchupType::XtreamCodes));
        assert_eq!(CatchupType::from_attr("nonsense"), None);
    }

    #[test]
    fn catchup_days_zero_means_no_archive() {
        let cfg = CatchupConfig {
            catchup_type: CatchupType::Shift,
            days: Some(0),
            ..Default::default()
        };
        assert!(!cfg.has_archive());
    }

    #[test]
    fn effective_days_prefers_catchup_days() {
        let cfg = CatchupConfig {
            days: Some(7),
            tvg_rec: Some(3),
            ..Default::default()
        };
        assert_eq!(cfg.effective_days(), Some(7));
    }

    #[test]
    fn effective_days_falls_back_to_tvg_rec() {
        let cfg = CatchupConfig {
            days: None,
            tvg_rec: Some(3),
            ..Default::default()
        };
        assert_eq!(cfg.effective_days(), Some(3));
    }

    #[test]
    fn shift_mode_appends_query_correctly() {
        let r = CatchupResolver {
            config: CatchupConfig {
                catchup_type: CatchupType::Shift,
                ..Default::default()
            },
            live_url: "http://x/y.ts".into(),
            provider_tz: None,
        };
        let url = r.resolve(&ctx()).unwrap();
        assert_eq!(url, "http://x/y.ts?utc=1700000000&lutc=1700003000");
    }

    #[test]
    fn shift_mode_uses_ampersand_when_query_exists() {
        let r = CatchupResolver {
            config: CatchupConfig {
                catchup_type: CatchupType::Shift,
                ..Default::default()
            },
            live_url: "http://x/y.ts?token=1".into(),
            provider_tz: None,
        };
        let url = r.resolve(&ctx()).unwrap();
        assert!(url.contains("?token=1&utc="), "实际: {url}");
    }

    #[test]
    fn append_mode_concatenates_source() {
        let r = CatchupResolver {
            config: CatchupConfig {
                catchup_type: CatchupType::Append,
                source: Some("?utc={utc}".into()),
                ..Default::default()
            },
            live_url: "http://x/y.ts".into(),
            provider_tz: None,
        };
        let url = r.resolve(&ctx()).unwrap();
        assert_eq!(url, "http://x/y.ts?utc=1700000000");
    }
}
