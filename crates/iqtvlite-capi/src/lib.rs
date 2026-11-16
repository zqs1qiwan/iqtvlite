// FFI 边界：extern "C" 函数必须 unsafe（指针有效性由 JNI 调用方保证），
// 安全契约在模块文档里统一说明，不再逐个写 `# Safety`。
#![allow(clippy::missing_safety_doc)]

//! iqtvlite C-ABI 桥。
//!
//! Kotlin 侧通过 JNI 调用这些 `extern "C"` 函数。所有字符串都是 UTF-8，
//! 返回值是 malloc 分配的 JSON 字符串，由调用方用 [`iqt_free_string`] 释放。
//!
//! # 为什么用 C-ABI 而不是直接用 JNI crate
//!
//! - 稳定、无 GC、跨语言友好
//! - Rust 侧可以写纯单测覆盖解析逻辑，不需要 Android 设备
//! - 未来换 UI 栈（Slint）不影响这层

#[cfg(target_os = "android")]
mod jni_bridge;

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};

use iqtvlite_core::id::{global_id, m3u_provider_key, SourceId};
use iqtvlite_core::model::catchup::{CatchupConfig, CatchupContext, CatchupResolver, CatchupType};
use iqtvlite_core::parse::m3u;
use iqtvlite_dvr::{estimate_size_mb, OutputFormat, RecordingSource, RecordingTask};
use iqtvlite_epg::{build_grid, merge_sources, EpgSourceInput, GridConfig};

/// 把 Rust String 转成 C 字符串（调用方必须用 `iqt_free_string` 释放）。
fn to_c(s: String) -> *mut c_char {
    CString::new(s)
        .unwrap_or_else(|_| CString::new("{\"error\":\"NUL in string\"}").unwrap())
        .into_raw()
}

/// 把 C 字符串转成 Rust &str。
unsafe fn from_c<'a>(p: *const c_char) -> &'a str {
    if p.is_null() {
        return "";
    }
    CStr::from_ptr(p).to_str().unwrap_or("")
}

/// 释放由本库返回的字符串。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe {
            drop(CString::from_raw(p));
        }
    }
}

/// 返回库版本。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr() as *const c_char
}

/// 解析 M3U 播放列表文本，返回 JSON。
///
/// Kotlin 侧：`external fun parseM3u(content: String, sourceId: Long): String`
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_parse_m3u(content: *const c_char, source_id: i64) -> *mut c_char {
    let text = unsafe { from_c(content) };
    let result = match m3u::parse(text) {
        Ok(playlist) => {
            let src = SourceId(source_id);
            let channels: Vec<serde_json::Value> = playlist
                .entries
                .iter()
                .map(|e| {
                    let key =
                        m3u_provider_key(e.tvg_id.as_deref(), e.primary_url(), e.name.as_deref());
                    let id = global_id(src, &key).as_sqlite_int();
                    let group = e.groups.first().cloned().unwrap_or_default();
                    serde_json::json!({
                        "id": id,
                        "providerId": key,
                        "name": e.name,
                        "tvgId": e.tvg_id,
                        "logo": e.tvg_logo,
                        "group": group,
                        "groups": e.groups,
                        "url": e.primary_url(),
                        "altUrls": &e.urls[1..],
                        "radio": e.radio,
                        "userAgent": e.user_agent,
                        "referrer": e.referrer,
                        "tvgShift": e.tvg_shift,
                        "catchup": e.catchup.as_ref().map(|c| serde_json::json!({
                            "type": format!("{:?}", c.catchup_type),
                            "days": c.days,
                            "source": c.source,
                            "hasArchive": c.has_archive(),
                        })),
                    })
                })
                .collect();
            let groups: Vec<String> = {
                let mut g: Vec<String> = Vec::new();
                for e in &playlist.entries {
                    for name in &e.groups {
                        if !g.contains(name) {
                            g.push(name.clone());
                        }
                    }
                }
                g.sort();
                g
            };
            serde_json::json!({
                "ok": true,
                "epgUrls": playlist.header.epg_urls,
                "channels": channels,
                "groups": groups,
                "stats": {
                    "entries": playlist.stats.entries,
                    "skipped": playlist.stats.skipped,
                },
            })
        }
        Err(e) => serde_json::json!({ "ok": false, "error": e.to_string() }),
    };
    to_c(result.to_string())
}

/// 从 URL 中提取查询参数（tv/ 接口用）。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_extract_query(url: *const c_char, key: *const c_char) -> *mut c_char {
    let url = unsafe { from_c(url) };
    let key = unsafe { from_c(key) };
    let value = extract_query_param(url, key).unwrap_or_default();
    to_c(value)
}

fn extract_query_param(url: &str, key: &str) -> Option<String> {
    let query = url.split_once('?')?.1;
    for part in query.split('&') {
        let (k, v) = part.split_once('=').unwrap_or((part, ""));
        if k == key {
            return Some(percent_decode(v));
        }
    }
    None
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let h = hex(bytes[i + 1]);
            let l = hex(bytes[i + 2]);
            if let (Some(h), Some(l)) = (h, l) {
                out.push(h * 16 + l);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

fn hex(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

/// 从 XMLTV 文本生成 EPG 网格，返回 JSON。
///
/// `tvg_ids_json` 是 JSON 字符串数组（视口内的频道 tvg-id）。
/// `now` / `window_start` / `window_end` 是 Unix 秒（UTC）。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_parse_epg_grid(
    xmltv: *const c_char,
    tvg_ids_json: *const c_char,
    now: i64,
    window_start: i64,
    window_end: i64,
    slot_minutes: i32,
) -> *mut c_char {
    let xml = unsafe { from_c(xmltv) };
    let ids_raw = unsafe { from_c(tvg_ids_json) };
    let tvg_ids: Vec<String> = serde_json::from_str(ids_raw).unwrap_or_default();
    let config = GridConfig {
        window_start,
        window_end,
        slot_minutes: if slot_minutes > 0 { slot_minutes } else { 30 },
        tvg_ids,
        max_rows: 300,
    };
    let grid = build_grid(xml, &config, now);
    to_c(
        serde_json::to_string(&grid)
            .unwrap_or_else(|e| format!("{{\"ok\":false,\"error\":\"{}\"}}", e)),
    )
}

/// 生成 catch-up（回看）URL。
///
/// 输入是 JSON 对象（全部字段来自播放列表的 catchup 配置 + 节目时间）：
/// ```json
/// {
///   "liveUrl": "http://.../1.ts",
///   "type": "Shift",             // Default|Append|Shift|Timeshift|Flussonic|XtreamCodes|Vod
///   "source": "?utc={utc}&lutc={lutc}",
///   "days": 7, "timeSecs": 0, "tvgRec": 0, "correctionHours": 0,
///   "providerTz": "Asia/Shanghai",
///   "start": 1700000000, "stop": 1700003600, "now": 1700005000,
///   "catchupId": "optional"
/// }
/// ```
/// 返回 `{"ok":true,"url":"..."}` 或 `{"ok":false,"error":"..."}`。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_build_catchup_url(params_json: *const c_char) -> *mut c_char {
    let raw = unsafe { from_c(params_json) };
    let result = match build_catchup_url(raw) {
        Ok(url) => serde_json::json!({ "ok": true, "url": url }),
        Err(e) => serde_json::json!({ "ok": false, "error": e }),
    };
    to_c(result.to_string())
}

fn build_catchup_url(raw: &str) -> Result<String, String> {
    let v: serde_json::Value = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    let get_str = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
    let get_i64 = |k: &str| v.get(k).and_then(|x| x.as_i64()).unwrap_or(0);
    let get_f64 = |k: &str| v.get(k).and_then(|x| x.as_f64()).unwrap_or(0.0);

    let type_raw = get_str("type");
    let catchup_type = CatchupType::from_attr(&type_raw)
        .ok_or_else(|| format!("未知 catch-up 类型: {type_raw}"))?;

    let cfg = CatchupConfig {
        catchup_type,
        days: v.get("days").and_then(|x| x.as_u64()).map(|x| x as u32),
        time_secs: v.get("timeSecs").and_then(|x| x.as_u64()).map(|x| x as u32),
        source: {
            let s = get_str("source");
            if s.is_empty() {
                None
            } else {
                Some(s)
            }
        },
        correction_hours: if get_f64("correctionHours") != 0.0 {
            Some(get_f64("correctionHours"))
        } else {
            None
        },
        tvg_rec: if get_i64("tvgRec") != 0 {
            Some(get_i64("tvgRec"))
        } else {
            None
        },
    };

    let live_url = get_str("liveUrl");
    if live_url.is_empty() {
        return Err("缺少 liveUrl".to_string());
    }
    let provider_tz = {
        let s = get_str("providerTz");
        if s.is_empty() {
            None
        } else {
            Some(s)
        }
    };
    let catchup_id = {
        let s = get_str("catchupId");
        if s.is_empty() {
            None
        } else {
            Some(s)
        }
    };

    let start = get_i64("start");
    let stop = get_i64("stop");
    let now = {
        let n = get_i64("now");
        if n > 0 {
            n
        } else {
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as i64
        }
    };

    let mut ctx = CatchupContext::with_buffer(start, stop, now);
    ctx.catchup_id = catchup_id.as_deref();

    let resolver = CatchupResolver {
        config: cfg,
        live_url,
        provider_tz,
    };
    resolver
        .resolve(&ctx)
        .ok_or_else(|| "该频道不支持 catch-up（类型或模板无效）".to_string())
}

/// 生成录制任务的本地文件名与计划信息。
///
/// 输入 JSON：`{"title":"...","id":123,"url":"http://...","altUrls":[...],
///             "source":"instant|scheduled|catchup","outputDir":"/sdcard/...",
///             "startTs":0,"stopTs":0,"userAgent":"..."}`
/// 返回：`{"ok":true,"fileName":"...","outputPath":"...","format":"ts|hls",
///         "url":"...","userAgent":"...","estimateMb":N}`
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_build_recording_plan(params_json: *const c_char) -> *mut c_char {
    let raw = unsafe { from_c(params_json) };
    let result = match build_recording_plan(raw) {
        Ok(v) => v,
        Err(e) => serde_json::json!({ "ok": false, "error": e }),
    };
    to_c(result.to_string())
}

fn build_recording_plan(raw: &str) -> Result<serde_json::Value, String> {
    let v: serde_json::Value = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    let get_str = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
    let title = get_str("title");
    if title.is_empty() {
        return Err("缺少 title".into());
    }
    let id = v.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
    let url = get_str("url");
    if url.is_empty() {
        return Err("缺少 url".into());
    }
    let output_dir = {
        let d = get_str("outputDir");
        if d.is_empty() {
            "/sdcard/Download/iqtvlite".to_string()
        } else {
            d
        }
    };
    let source = match get_str("source").as_str() {
        "scheduled" => RecordingSource::Scheduled,
        "catchup" => RecordingSource::Catchup,
        _ => RecordingSource::Instant,
    };
    let alt_urls: Vec<String> = v
        .get("altUrls")
        .and_then(|x| x.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default();
    let task = RecordingTask {
        id,
        channel_id: v.get("channelId").and_then(|x| x.as_i64()).unwrap_or(0),
        title: title.clone(),
        source,
        url: url.clone(),
        alt_urls,
        user_agent: {
            let ua = get_str("userAgent");
            if ua.is_empty() {
                None
            } else {
                Some(ua)
            }
        },
        referrer: None,
        output_format: OutputFormat::from_url(&url),
        output_path: output_dir.clone(),
        start_ts: v.get("startTs").and_then(|x| x.as_i64()).unwrap_or(0),
        stop_ts: v.get("stopTs").and_then(|x| x.as_i64()).unwrap_or(0),
        max_bytes: v.get("maxBytes").and_then(|x| x.as_u64()).unwrap_or(0),
    };
    let file_name = format!("{}.{}", task.file_name(), task.output_format.extension());
    Ok(serde_json::json!({
        "ok": true,
        "fileName": file_name,
        "outputPath": format!("{}/{}", output_dir.trim_end_matches('/'), file_name),
        "format": task.output_format.extension(),
        "url": url,
        "userAgent": task.user_agent,
        "estimateMb": estimate_size_mb(2000, (task.stop_ts - task.start_ts).max(0) as u64),
    }))
}

/// 合并多个 EPG 源（按优先级叠加）。
///
/// 输入 JSON：`{"windowStart":0,"windowEnd":0,
///   "sources":[{"id":"manual","priority":10,"xml":"<tv>..."}, ...]}`
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_merge_epg_sources(params_json: *const c_char) -> *mut c_char {
    let raw = unsafe { from_c(params_json) };
    let result = match merge_epg(raw) {
        Ok(v) => v,
        Err(e) => serde_json::json!({ "ok": false, "error": e }),
    };
    to_c(result.to_string())
}

fn merge_epg(raw: &str) -> Result<serde_json::Value, String> {
    let v: serde_json::Value = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    let window_start = v.get("windowStart").and_then(|x| x.as_i64()).unwrap_or(0);
    let window_end = v
        .get("windowEnd")
        .and_then(|x| x.as_i64())
        .unwrap_or(i64::MAX);
    let sources: Vec<EpgSourceInput<'_>> = v
        .get("sources")
        .and_then(|x| x.as_array())
        .ok_or("缺少 sources")?
        .iter()
        .filter_map(|s| {
            let id = s.get("id")?.as_str()?;
            let priority = s.get("priority")?.as_i64()? as i32;
            let xml = s.get("xml")?.as_str()?;
            Some(EpgSourceInput {
                id: id.to_string(),
                priority,
                xml,
            })
        })
        .collect();
    let merged = merge_sources(&sources, window_start, window_end);
    Ok(serde_json::json!({
        "ok": true,
        "channels": merged.channels,
        "programmeCount": merged.programme_count,
        "stats": merged.stats,
    }))
}

/// 生成稳定 ID。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_stable_id(source_id: i64, provider_key: *const c_char) -> i64 {
    let key = unsafe { from_c(provider_key) };
    global_id(SourceId(source_id), key).as_sqlite_int()
}

/// 回放错误分类的常量（与 Kotlin 侧 enum 对齐）。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_error_class_unsupported() -> c_int {
    1
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_error_class_auth() -> c_int {
    2
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_error_class_rate_limited() -> c_int {
    3
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_error_class_transient() -> c_int {
    4
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iqt_error_class_not_found() -> c_int {
    5
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    #[test]
    fn parse_m3u_returns_json() {
        let input = CString::new(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"CCTV1\" group-title=\"央视\",CCTV-1 综合\nhttp://x/y.m3u8\n",
        )
        .unwrap();
        let p = unsafe { iqt_parse_m3u(input.as_ptr(), 1) };
        let s = unsafe { CStr::from_ptr(p).to_str().unwrap().to_string() };
        unsafe { iqt_free_string(p) };
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["ok"], true);
        assert_eq!(v["channels"][0]["tvgId"], "CCTV1");
        assert_eq!(v["channels"][0]["group"], "央视");
    }

    #[test]
    fn parse_epg_grid_returns_json() {
        let xml = CString::new(
            r#"<tv>
              <channel id="CCTV1"><display-name>CCTV-1</display-name></channel>
              <programme start="20240101000000 +0000" stop="20240101010000 +0000" channel="CCTV1"><title>新闻</title></programme>
            </tv>"#,
        )
        .unwrap();
        let ids = CString::new(r#"["CCTV1"]"#).unwrap();
        let base = 1_704_067_200i64; // 2024-01-01 00:00 UTC
        let ptr =
            unsafe { iqt_parse_epg_grid(xml.as_ptr(), ids.as_ptr(), base, base, base + 7200, 30) };
        let s = unsafe { CStr::from_ptr(ptr).to_str().unwrap().to_string() };
        unsafe { iqt_free_string(ptr) };
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["rows"].as_array().unwrap().len(), 1);
        assert_eq!(v["rows"][0]["programmes"][0]["title"], "新闻");
        assert_eq!(v["rows"][0]["programmes"][0]["span"], 2);
    }

    #[test]
    fn build_catchup_url_shift_mode() {
        let json = r#"{"liveUrl":"http://x/live.ts","type":"Shift","start":1700000000,"stop":1700003600,"now":1700005000}"#;
        let url = build_catchup_url(json).unwrap();
        assert_eq!(url, "http://x/live.ts?utc=1700000000&lutc=1700005000");
    }

    #[test]
    fn build_catchup_url_default_template() {
        let json = r#"{"liveUrl":"http://x/live.ts","type":"Default","source":"http://a/v-{utc}-{duration}.m3u8","start":1700000000,"stop":1700003600,"now":1700005000}"#;
        let url = build_catchup_url(json).unwrap();
        assert_eq!(url, "http://a/v-1700000000-3900.m3u8");
    }

    #[test]
    fn build_catchup_url_rejects_unknown_type() {
        let json = r#"{"liveUrl":"http://x/live.ts","type":"Bogus","start":0,"stop":0,"now":0}"#;
        assert!(build_catchup_url(json).is_err());
    }

    #[test]
    fn build_recording_plan_ts() {
        let json = r#"{"title":"CCTV-1 综合/新闻","id":123,"url":"http://x/live.ts","source":"instant","outputDir":"/sdcard/Rec"}"#;
        let ptr = unsafe { iqt_build_recording_plan(CString::new(json).unwrap().as_ptr()) };
        let s = unsafe { CStr::from_ptr(ptr).to_str().unwrap().to_string() };
        unsafe { iqt_free_string(ptr) };
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["ok"], true);
        assert_eq!(v["format"], "ts");
        assert_eq!(v["fileName"], "CCTV-1 综合_新闻_123.ts");
        assert!(v["outputPath"]
            .as_str()
            .unwrap()
            .starts_with("/sdcard/Rec/"));
    }

    #[test]
    fn build_recording_plan_hls() {
        let json = r#"{"title":"Test","id":1,"url":"http://x/live.m3u8","source":"instant"}"#;
        let ptr = unsafe { iqt_build_recording_plan(CString::new(json).unwrap().as_ptr()) };
        let s = unsafe { CStr::from_ptr(ptr).to_str().unwrap().to_string() };
        unsafe { iqt_free_string(ptr) };
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["format"], "m3u8");
    }

    #[test]
    fn query_param_works() {
        assert_eq!(
            extract_query_param("http://x/?isp=unicom&city=zibo", "city"),
            Some("zibo".to_string())
        );
        assert_eq!(
            extract_query_param("http://x/?name=%E4%B8%AD%E5%9B%BD", "name"),
            Some("中国".to_string())
        );
    }
}
