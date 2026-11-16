//! JNI 导出，供 Kotlin 的 `external fun` 直接调用。

use jni::errors::Error as JniError;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::{iqt_extract_query, iqt_free_string, iqt_parse_m3u, iqt_stable_id, iqt_version};
use std::ffi::CString;
use std::os::raw::c_char;

unsafe fn c_to_string(ptr: *mut c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    let s = std::ffi::CStr::from_ptr(ptr).to_string_lossy().to_string();
    unsafe { iqt_free_string(ptr) };
    s
}

/// 把 Outcome 转成值；JNI 出错时返回 default（绝不 panic 跨 FFI）。
fn unwrap_outcome<T: Default>(o: jni::EnvOutcome<'_, T, JniError>) -> T {
    match o.into_outcome() {
        jni::Outcome::Ok(v) => v,
        jni::Outcome::Err(e) => {
            tracing::warn!("JNI error: {e}");
            T::default()
        }
        jni::Outcome::Panic(_) => {
            tracing::error!("JNI panic caught");
            T::default()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tv_iqtvlite_app_RustBridge_00024version<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let outcome: jni::EnvOutcome<'local, jstring, JniError> = env.with_env(|env| {
        let v = unsafe { std::ffi::CStr::from_ptr(unsafe { iqt_version() }) }
            .to_string_lossy()
            .to_string();
        Ok(env.new_string(v)?.into_raw())
    });
    unwrap_outcome(outcome)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tv_iqtvlite_app_RustBridge_00024parseM3u<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    content: JString<'local>,
    source_id: i64,
) -> jstring {
    let outcome: jni::EnvOutcome<'local, jstring, JniError> = env.with_env(|env| {
        let s: String = env.get_string(&content)?.into();
        let c = CString::new(s).unwrap_or_default();
        let ptr = unsafe { iqt_parse_m3u(c.as_ptr(), source_id) };
        let result = unsafe { c_to_string(ptr) };
        Ok(env.new_string(result)?.into_raw())
    });
    unwrap_outcome(outcome)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tv_iqtvlite_app_RustBridge_00024stableId<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source_id: i64,
    key: JString<'local>,
) -> i64 {
    let outcome: jni::EnvOutcome<'local, i64, JniError> = env.with_env(|env| {
        let s: String = env.get_string(&key)?.into();
        let c = CString::new(s).unwrap_or_default();
        Ok(unsafe { iqt_stable_id(source_id, c.as_ptr()) })
    });
    unwrap_outcome(outcome)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tv_iqtvlite_app_RustBridge_00024extractQuery<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    key: JString<'local>,
) -> jstring {
    let outcome: jni::EnvOutcome<'local, jstring, JniError> = env.with_env(|env| {
        let u: String = env.get_string(&url)?.into();
        let k: String = env.get_string(&key)?.into();
        let cu = CString::new(u).unwrap_or_default();
        let ck = CString::new(k).unwrap_or_default();
        let ptr = unsafe { iqt_extract_query(cu.as_ptr(), ck.as_ptr()) };
        let result = unsafe { c_to_string(ptr) };
        Ok(env.new_string(result)?.into_raw())
    });
    unwrap_outcome(outcome)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tv_iqtvlite_app_RustBridge_00024freeString<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ptr: i64,
) {
    let outcome: jni::EnvOutcome<'local, (), JniError> = env.with_env(|_env| {
        unsafe { iqt_free_string(ptr as *mut c_char) };
        Ok(())
    });
    unwrap_outcome(outcome)
}
