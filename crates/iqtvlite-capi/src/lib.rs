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
use iqtvlite_core::parse::m3u;

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
pub extern "C" fn iqt_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe {
            drop(CString::from_raw(p));
        }
    }
}

/// 返回库版本。
#[unsafe(no_mangle)]
pub extern "C" fn iqt_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr() as *const c_char
}

/// 解析 M3U 播放列表文本，返回 JSON。
///
/// Kotlin 侧：`external fun parseM3u(content: String, sourceId: Long): String`
#[unsafe(no_mangle)]
pub extern "C" fn iqt_parse_m3u(content: *const c_char, source_id: i64) -> *mut c_char {
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
pub extern "C" fn iqt_extract_query(url: *const c_char, key: *const c_char) -> *mut c_char {
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

/// 生成稳定 ID。
#[unsafe(no_mangle)]
pub extern "C" fn iqt_stable_id(source_id: i64, provider_key: *const c_char) -> i64 {
    let key = unsafe { from_c(provider_key) };
    global_id(SourceId(source_id), key).as_sqlite_int()
}

/// 回放错误分类的常量（与 Kotlin 侧 enum 对齐）。
#[unsafe(no_mangle)]
pub extern "C" fn iqt_error_class_unsupported() -> c_int {
    1
}
#[unsafe(no_mangle)]
pub extern "C" fn iqt_error_class_auth() -> c_int {
    2
}
#[unsafe(no_mangle)]
pub extern "C" fn iqt_error_class_rate_limited() -> c_int {
    3
}
#[unsafe(no_mangle)]
pub extern "C" fn iqt_error_class_transient() -> c_int {
    4
}
#[unsafe(no_mangle)]
pub extern "C" fn iqt_error_class_not_found() -> c_int {
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
        let p = iqt_parse_m3u(input.as_ptr(), 1);
        let s = unsafe { CStr::from_ptr(p).to_str().unwrap().to_string() };
        iqt_free_string(p);
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["ok"], true);
        assert_eq!(v["channels"][0]["tvgId"], "CCTV1");
        assert_eq!(v["channels"][0]["group"], "央视");
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
