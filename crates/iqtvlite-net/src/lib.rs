//! # iqtvlite-net
//!
//! 网络层。所有出站请求都从这里走，**便于统一管控 UA 与连接池**。
//!
//! # 两个关键设计
//!
//! ## 1. User-Agent 管理
//!
//! 部分供应商**只放行已知播放器**的 UA。必须能切换：
//! TiviMate / VLC / OTT Navigator / 自定义。
//!
//! ## 2. 连接池保温
//!
//! 换台速度的关键之一：TLS 握手占 200–500 ms。
//! **同一个 `reqwest::Client` 会复用 keep-alive 连接**，所以 Client 必须是**长生命周期单例**。
//! 反编译 TiviMate 确认它用了 OkHttp 的 `ConnectionPool(5, 5, TimeUnit.MINUTES)` —— 同样的思路。

use std::time::Duration;

use iqtvlite_core::error::{IptvError, Result};

/// 预置的 User-Agent。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UserAgent {
    /// TiviMate（对供应商兼容性最好）
    TiviMate,
    /// VLC
    Vlc,
    /// OTT Navigator
    OttNavigator,
    /// 浏览器 UA —— 用于「是服务器封客户端还是流本身死了」的诊断探测
    Browser,
    Custom(String),
}

impl UserAgent {
    /// 实际 UA 字符串。
    pub fn as_str(&self) -> &str {
        match self {
            UserAgent::TiviMate => "TiviMate/5.1.6 (Android 12)",
            UserAgent::Vlc => "VLC/3.0.20 LibVLC/3.0.20",
            UserAgent::OttNavigator => {
                "OTT Navigator/1.7.5.2 (Linux;Android 12; en) ExoPlayerLib/2.18.1"
            }
            UserAgent::Browser => {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            }
            UserAgent::Custom(s) => s,
        }
    }

    /// 供 UI 显示的名字。
    pub fn label(&self) -> &str {
        match self {
            UserAgent::TiviMate => "TiviMate",
            UserAgent::Vlc => "VLC",
            UserAgent::OttNavigator => "OTT Navigator",
            UserAgent::Browser => "浏览器（诊断用）",
            UserAgent::Custom(_) => "自定义",
        }
    }

    /// 从字符串还原（用于持久化设置）。
    pub fn from_stored(raw: &str) -> Self {
        match raw {
            "tivimate" => UserAgent::TiviMate,
            "vlc" => UserAgent::Vlc,
            "ott_navigator" => UserAgent::OttNavigator,
            "browser" => UserAgent::Browser,
            other => UserAgent::Custom(other.to_string()),
        }
    }

    /// 持久化表示。
    pub fn to_stored(&self) -> String {
        match self {
            UserAgent::TiviMate => "tivimate".into(),
            UserAgent::Vlc => "vlc".into(),
            UserAgent::OttNavigator => "ott_navigator".into(),
            UserAgent::Browser => "browser".into(),
            UserAgent::Custom(s) => s.clone(),
        }
    }
}

/// 安装 rustls 的加密 provider（`ring`）。
///
/// reqwest 用的是 `rustls-no-provider`，所以必须由我们自己装。
/// 只装一次 —— 重复调用会返回 `Err`，忽略即可。
fn install_crypto_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

/// 网络客户端。
#[derive(Debug, Clone)]
pub struct HttpClient {
    inner: reqwest::Client,
    user_agent: UserAgent,
}

impl HttpClient {
    /// 创建客户端。
    ///
    /// `timeout` 是**整体请求**超时；直播流不走这个客户端（由 Media3 负责）。
    pub fn new(user_agent: UserAgent, timeout: Duration) -> Result<Self> {
        install_crypto_provider();
        let inner = reqwest::Client::builder()
            .user_agent(user_agent.as_str())
            .timeout(timeout)
            .connect_timeout(Duration::from_secs(10))
            // 保温连接池 —— 换台时省掉 TLS 握手
            .pool_idle_timeout(Duration::from_secs(300))
            .pool_max_idle_per_host(8)
            .gzip(true)
            .brotli(true)
            .build()
            .map_err(|e| IptvError::Network(e.to_string()))?;
        Ok(Self { inner, user_agent })
    }

    /// 当前 UA。
    pub fn user_agent(&self) -> &UserAgent {
        &self.user_agent
    }

    /// 更换 UA —— **必须重建 Client**，因为 UA 是连接池的一部分
    /// （同一连接上的请求会复用首个请求的 UA）。
    pub fn with_user_agent(&self, ua: UserAgent, timeout: Duration) -> Result<Self> {
        Self::new(ua, timeout)
    }

    /// 底层 reqwest 客户端（供流式下载使用）。
    pub fn inner(&self) -> &reqwest::Client {
        &self.inner
    }

    /// GET 并返回文本。
    pub async fn get_text(&self, url: &str) -> Result<String> {
        let resp = self
            .inner
            .get(url)
            .send()
            .await
            .map_err(|e| IptvError::Network(e.to_string()))?;
        if !resp.status().is_success() {
            return Err(IptvError::Network(format!(
                "HTTP {} for {}",
                resp.status().as_u16(),
                url
            )));
        }
        resp.text()
            .await
            .map_err(|e| IptvError::Network(e.to_string()))
    }

    /// GET 并返回字节。
    pub async fn get_bytes(&self, url: &str) -> Result<Vec<u8>> {
        let resp = self
            .inner
            .get(url)
            .send()
            .await
            .map_err(|e| IptvError::Network(e.to_string()))?;
        if !resp.status().is_success() {
            return Err(IptvError::Network(format!(
                "HTTP {} for {}",
                resp.status().as_u16(),
                url
            )));
        }
        let bytes = resp
            .bytes()
            .await
            .map_err(|e| IptvError::Network(e.to_string()))?;
        Ok(bytes.to_vec())
    }

    /// 流式下载（用于 M3U / XMLTV，避免整个文件进内存）。
    pub async fn get_stream(&self, url: &str) -> Result<reqwest::Response> {
        let resp = self
            .inner
            .get(url)
            .send()
            .await
            .map_err(|e| IptvError::Network(e.to_string()))?;
        if !resp.status().is_success() {
            return Err(IptvError::Network(format!(
                "HTTP {} for {}",
                resp.status().as_u16(),
                url
            )));
        }
        Ok(resp)
    }

    /// GET 并解析 JSON。
    pub async fn get_json<T: serde::de::DeserializeOwned>(&self, url: &str) -> Result<T> {
        let text = self.get_text(url).await?;
        serde_json::from_str(&text).map_err(|e| IptvError::Parse {
            kind: iqtvlite_core::ParseErrorKind::Syntax,
            context: e.to_string(),
        })
    }

    /// 诊断探测：用**浏览器 UA** 再试一次。
    ///
    /// 返回 `true` 表示「浏览器 UA 能通，但播放器 UA 不通」→
    /// 说明**服务器在封客户端**，而不是流本身死了。
    /// 这个区分对用户提示至关重要。
    pub async fn probe_with_browser_ua(&self, url: &str) -> Result<bool> {
        let browser = UserAgent::Browser;
        let probe = HttpClient::new(browser, Duration::from_secs(15))?;
        match probe.get_bytes(url).await {
            Ok(_) => Ok(true),
            Err(e) => {
                tracing::debug!(url, error = %e, "浏览器 UA 探测失败");
                Ok(false)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn user_agent_roundtrips() {
        for ua in [
            UserAgent::TiviMate,
            UserAgent::Vlc,
            UserAgent::OttNavigator,
            UserAgent::Browser,
            UserAgent::Custom("MyUA/1.0".into()),
        ] {
            let stored = ua.to_stored();
            assert_eq!(UserAgent::from_stored(&stored), ua);
        }
    }

    #[test]
    fn preset_uas_are_non_empty() {
        assert!(!UserAgent::TiviMate.as_str().is_empty());
        assert!(UserAgent::TiviMate.as_str().starts_with("TiviMate/"));
        assert!(UserAgent::Vlc.as_str().starts_with("VLC/"));
        assert!(UserAgent::Browser.as_str().starts_with("Mozilla/"));
    }

    #[test]
    fn client_builds_with_custom_ua() {
        let client =
            HttpClient::new(UserAgent::Custom("X/1".into()), Duration::from_secs(10)).unwrap();
        assert_eq!(client.user_agent().as_str(), "X/1");
    }

    #[test]
    fn switching_ua_rebuilds_client() {
        let client = HttpClient::new(UserAgent::TiviMate, Duration::from_secs(10)).unwrap();
        let client = client
            .with_user_agent(UserAgent::Vlc, Duration::from_secs(10))
            .unwrap();
        assert_eq!(client.user_agent().as_str(), "VLC/3.0.20 LibVLC/3.0.20");
    }
}
