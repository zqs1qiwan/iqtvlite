//! 时间处理工具：XMLTV 时间解析、供应商时区换算、格式化。
//!
//! 用 `jiff` 而非 `time`：`time` 0.3 没有 IANA 时区支持，
//! 而 Xtream 的时移 URL 需要**供应商本地墙钟时间**（来自 `server_info.timezone`）。
//! `jiff` 自带 tzdb，且对 DST 边界（重复/缺失的本地时间）处理正确。

use jiff::{civil, fmt::strtime, tz::TimeZone, Timestamp};

/// 一个「带偏移量的民用时间」。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LocalDateTime {
    pub year: i16,
    pub month: i8,
    pub day: i8,
    pub hour: i8,
    pub minute: i8,
    pub second: i8,
}

/// 把 Unix 秒 + 固定偏移量转成民用时间。
pub fn from_unix(ts: i64, offset_secs: i32) -> LocalDateTime {
    let total = ts + i64::from(offset_secs);
    match Timestamp::from_second(total)
        .ok()
        .map(|t| t.to_zoned(TimeZone::UTC))
    {
        Some(zoned) => {
            let d = zoned.datetime();
            LocalDateTime {
                year: d.year(),
                month: d.month(),
                day: d.day(),
                hour: d.hour(),
                minute: d.minute(),
                second: d.second(),
            }
        }
        None => LocalDateTime {
            year: 1970,
            month: 1,
            day: 1,
            hour: 0,
            minute: 0,
            second: 0,
        },
    }
}

/// 查询某个 IANA 时区在给定时刻的 UTC 偏移（秒）。
///
/// 时区名无效或数据库缺失时返回 `None`（调用方应退回 UTC）。
pub fn offset_for_zone(zone: &str, ts: i64) -> Option<i32> {
    let tz = TimeZone::get(zone).ok()?;
    let timestamp = Timestamp::from_second(ts).ok()?;
    let zoned = timestamp.to_zoned(tz);
    Some(zoned.offset().seconds())
}

/// 按 strftime 风格格式化。
///
/// 支持 `%Y %y %m %d %H %M %S` 等常见指令。
pub fn format(dt: LocalDateTime, format: &str) -> String {
    let Ok(civil) =
        civil::DateTime::new(dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, 0)
    else {
        return String::new();
    };
    strtime::format(format, civil).unwrap_or_default()
}

/// ISO-8601（UTC）。
pub fn iso8601(ts: i64) -> String {
    let dt = from_unix(ts, 0);
    format(dt, "%Y-%m-%dT%H:%M:%SZ")
}

/// 取日期分量。名称取自 Kodi / OTT Nav 的模板语法。
pub fn component(dt: LocalDateTime, name: &str) -> String {
    match name {
        "Y" | "EY" | "year" | "start-year" => format!("{:04}", dt.year),
        "y" | "start-yr" => format!("{:02}", dt.year % 100),
        "m" | "Em" | "month" | "start-month" => format!("{:02}", dt.month),
        "d" | "Ed" | "day" | "start-day" => format!("{:02}", dt.day),
        "H" | "EH" | "hour" | "start-hour" => format!("{:02}", dt.hour),
        "M" | "EM" | "minute" | "start-minute" => format!("{:02}", dt.minute),
        "S" | "ES" | "second" | "start-second" => format!("{:02}", dt.second),
        // 「结束时刻」的变体（OTT Nav 的 ${end-year} 等）
        "end-year" => format!("{:04}", dt.year),
        "end-month" => format!("{:02}", dt.month),
        "end-day" => format!("{:02}", dt.day),
        "end-hour" => format!("{:02}", dt.hour),
        "end-minute" => format!("{:02}", dt.minute),
        "end-sec" | "end-second" => format!("{:02}", dt.second),
        _ => String::new(),
    }
}

// ---------------------------------------------------------------------------
// XMLTV 时间解析
// ---------------------------------------------------------------------------

/// XMLTV 时间解析结果。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ParsedTime {
    /// Unix 秒（UTC）
    pub unix: i64,
    /// 是否因为无法识别的时区而按 UTC 处理
    pub timezone_unknown: bool,
}

/// 解析 XMLTV 的 `start` / `stop` 属性。
///
/// DTD 原文：*"They can be `'YYYYMMDDhhmmss'` or some initial substring…
/// You can also append a timezone to the end; if no explicit timezone is given,
/// **UTC is assumed**."*
///
/// 规则（已按 better-iptv 的单测行为对齐）：
/// - 数字段长度必须是 `4|6|8|10|12|14`；缺月日 → `1`；缺时分秒 → `0`。
/// - `24:00:00` 接受为**次日**午夜；其他 24 点、60 秒拒绝。
/// - 时区 token = 数字后第一个空白分隔词（`"… +0200 CEST"` → `+0200`）。
/// - 偏移：`+0200` / `+02:00` / `-0500`；`Z`/`UTC`/`GMT` = 0；`BST` = +60；
///   其他（`CEST`、`+2`、`0200`）视为**未知** → 按 UTC 处理但打标记。
/// - `|offset| >= 1440` 视为未知。
pub fn parse_xmltv_time(raw: &str) -> Option<ParsedTime> {
    let raw = raw.trim();
    if raw.is_empty() {
        return None;
    }

    // 拆出数字部分与时区部分
    let (digits, zone_token) = match raw.find(|c: char| !c.is_ascii_digit()) {
        Some(idx) => (&raw[..idx], raw[idx..].trim()),
        None => (raw, ""),
    };

    if !matches!(digits.len(), 4 | 6 | 8 | 10 | 12 | 14) {
        return None;
    }

    let year: i16 = digits[0..4].parse().ok()?;
    let month: u8 = if digits.len() >= 6 {
        digits[4..6].parse().ok()?
    } else {
        1
    };
    let day: u8 = if digits.len() >= 8 {
        digits[6..8].parse().ok()?
    } else {
        1
    };
    let hour: u8 = if digits.len() >= 10 {
        digits[8..10].parse().ok()?
    } else {
        0
    };
    let minute: u8 = if digits.len() >= 12 {
        digits[10..12].parse().ok()?
    } else {
        0
    };
    let second: u8 = if digits.len() >= 14 {
        digits[12..14].parse().ok()?
    } else {
        0
    };

    if !(1..=12).contains(&month) {
        return None;
    }
    if !(1..=31).contains(&day) {
        return None;
    }
    if minute > 59 || second > 59 {
        return None;
    }
    // 24:00:00 是合法的（次日午夜），其他 24 点不合法
    let day_rollover = if hour == 24 {
        if minute != 0 || second != 0 {
            return None;
        }
        1
    } else if hour > 23 {
        return None;
    } else {
        0
    };
    let hour = if day_rollover == 1 { 0 } else { hour };

    // 时区
    let (offset_secs, timezone_unknown) = parse_zone(zone_token)?;

    let date = civil::Date::new(year, month as i8, day as i8).ok()?;
    let date = if day_rollover == 1 {
        date.checked_add(std::time::Duration::from_secs(86_400))
            .ok()?
    } else {
        date
    };
    let dt = civil::DateTime::new(
        date.year(),
        date.month(),
        date.day(),
        hour as i8,
        minute as i8,
        second as i8,
        0,
    )
    .ok()?;

    let unix = dt.to_zoned(TimeZone::UTC).ok()?.timestamp().as_second() - i64::from(offset_secs);

    Some(ParsedTime {
        unix,
        timezone_unknown,
    })
}

/// 解析时区 token → (偏移秒数, 是否未知)。
fn parse_zone(token: &str) -> Option<(i32, bool)> {
    if token.is_empty() {
        return Some((0, false)); // DTD：缺省即 UTC
    }
    let first = token
        .split_whitespace()
        .next()
        .unwrap_or("")
        .to_ascii_uppercase();
    match first.as_str() {
        "Z" | "UTC" | "GMT" => return Some((0, false)),
        "BST" => return Some((3600, false)),
        _ => {}
    }
    // 数值偏移：+0200 / +02:00 / -0500
    let (sign, rest) = match first.as_bytes().first()? {
        b'+' => (1, &first[1..]),
        b'-' => (-1, &first[1..]),
        _ => return Some((0, true)), // 无法识别 → 未知，按 UTC
    };
    let digits: String = rest.chars().filter(|c| c.is_ascii_digit()).collect();
    let (h, m) = match digits.len() {
        4 => (
            digits[0..2].parse::<i32>().ok()?,
            digits[2..4].parse::<i32>().ok()?,
        ),
        2 => (digits[0..2].parse::<i32>().ok()?, 0),
        _ => return Some((0, true)),
    };
    if h > 23 || m > 59 {
        return Some((0, true));
    }
    let total = sign * (h * 3600 + m * 60);
    // 阈值 1440 是**分钟**（= 24 小时）。这里 total 是秒，所以要 ×60。
    if total.abs() >= 1440 * 60 {
        return Some((0, true));
    }
    Some((total, false))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_full_timestamp_utc() {
        let t = parse_xmltv_time("20000728173300").unwrap();
        assert!(!t.timezone_unknown);
        // 2000-07-28 17:33:00 UTC
        assert_eq!(iso8601(t.unix), "2000-07-28T17:33:00Z");
    }

    #[test]
    fn parses_with_explicit_offset() {
        let t = parse_xmltv_time("19880523083000 +0300").unwrap();
        assert_eq!(iso8601(t.unix), "1988-05-23T05:30:00Z");
        assert!(!t.timezone_unknown);
    }

    #[test]
    fn parses_offset_with_colon() {
        let a = parse_xmltv_time("19880523083000 +0300").unwrap();
        let b = parse_xmltv_time("19880523083000 +03:00").unwrap();
        assert_eq!(a.unix, b.unix);
    }

    #[test]
    fn zone_suffix_after_offset_is_ignored_for_value() {
        // 时区 token 取数字后的第一个词 → "+0200"，CEST 这个尾巴被忽略
        // 本地 2002-09-01 00:00 @ UTC+2 → UTC 2002-08-31 22:00
        let t = parse_xmltv_time("20020901000000 +0200 CEST").unwrap();
        assert_eq!(iso8601(t.unix), "2002-08-31T22:00:00Z");
    }

    #[test]
    fn missing_zone_defaults_to_utc() {
        let t = parse_xmltv_time("20020901000000").unwrap();
        assert_eq!(iso8601(t.unix), "2002-09-01T00:00:00Z");
        assert!(!t.timezone_unknown);
    }

    #[test]
    fn short_forms_fill_defaults() {
        // 只有年份
        let t = parse_xmltv_time("2002").unwrap();
        assert_eq!(iso8601(t.unix), "2002-01-01T00:00:00Z");
        // 年 + 月
        let t = parse_xmltv_time("200209").unwrap();
        assert_eq!(iso8601(t.unix), "2002-09-01T00:00:00Z");
    }

    #[test]
    fn rejects_invalid_lengths() {
        assert!(parse_xmltv_time("200").is_none());
        assert!(parse_xmltv_time("2000010100000000").is_none());
        assert!(parse_xmltv_time("abc").is_none());
        assert!(parse_xmltv_time("").is_none());
    }

    #[test]
    fn accepts_24_00_00_as_next_midnight() {
        let t = parse_xmltv_time("20240101240000").unwrap();
        assert_eq!(iso8601(t.unix), "2024-01-02T00:00:00Z");
    }

    #[test]
    fn rejects_hour_24_with_minutes() {
        assert!(parse_xmltv_time("20240101240100").is_none());
    }

    #[test]
    fn rejects_sixty_seconds() {
        assert!(parse_xmltv_time("20240101000060").is_none());
    }

    #[test]
    fn unknown_zone_is_flagged_but_parsed() {
        let t = parse_xmltv_time("20240101000000 CEST").unwrap();
        assert!(t.timezone_unknown);
        assert_eq!(iso8601(t.unix), "2024-01-01T00:00:00Z");
    }

    #[test]
    fn bst_is_plus_one_hour() {
        let t = parse_xmltv_time("20240101000000 BST").unwrap();
        assert_eq!(iso8601(t.unix), "2023-12-31T23:00:00Z");
    }

    #[test]
    fn offset_beyond_24h_is_unknown() {
        let t = parse_xmltv_time("20240101000000 +2500").unwrap();
        assert!(t.timezone_unknown);
    }

    #[test]
    fn formats_components() {
        let dt = LocalDateTime {
            year: 2024,
            month: 3,
            day: 7,
            hour: 9,
            minute: 5,
            second: 1,
        };
        assert_eq!(component(dt, "Y"), "2024");
        assert_eq!(component(dt, "m"), "03");
        assert_eq!(component(dt, "d"), "07");
        assert_eq!(component(dt, "H"), "09");
        assert_eq!(component(dt, "M"), "05");
        assert_eq!(component(dt, "S"), "01");
    }

    #[test]
    fn strftime_formatting_works() {
        let dt = LocalDateTime {
            year: 2024,
            month: 3,
            day: 7,
            hour: 9,
            minute: 5,
            second: 1,
        };
        assert_eq!(format(dt, "%Y%m%d%H%M%S"), "20240307090501");
        assert_eq!(format(dt, "%Y-%m-%d"), "2024-03-07");
    }
}
