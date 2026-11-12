//! # iqtvlite-player
//!
//! 播放层。**决策逻辑与执行分离** —— 这是本项目的核心架构约束之一。
//!
//! - **决策**（本 crate，纯 Rust，可完整单测）：该重试还是换线路还是换引擎。
//! - **执行**（Kotlin / libmpv）：真正地播放、停止、解码。
//!
//! 好处：故障自愈的决策逻辑可以写**几百个单测**，
//! 不需要设备、不需要模拟器、不需要一条真实的 IPTV 流。

use serde::{Deserialize, Serialize};

/// 播放引擎种类。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EngineKind {
    /// Media3 / ExoPlayer —— 直播主引擎
    Media3,
    /// libmpv —— 兜底（长尾编码、RTSP/HEVC）
    Mpv,
}

impl EngineKind {
    pub fn label(self) -> &'static str {
        match self {
            EngineKind::Media3 => "标准引擎",
            EngineKind::Mpv => "兼容引擎",
        }
    }
}

/// 播放错误分类。
///
/// **面向用户的错误必须携带可执行动作**，而不是原始错误串。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorClass {
    /// 编码/格式不支持 → 换兼容引擎
    Unsupported,
    /// 认证失败（401/403 + 鉴权语义）→ 提示换 UA / 检查凭据
    Auth,
    /// 供应商限流 → 退避重试
    RateLimited,
    /// 瞬时故障（超时、5xx）→ 重试
    Transient,
    /// 找不到（404）→ 换线路
    NotFound,
    /// 设备/解码器丢失 → 重建引擎
    DeviceLost,
    /// 直播落后 → 等待并重试
    LiveEdge,
    /// 未知
    Unknown,
}

/// 错误详情。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaybackError {
    pub class: ErrorClass,
    /// 原始错误（技术细节，展示时折叠）
    pub detail: String,
    /// HTTP 状态码（如果有）
    pub http_status: Option<u16>,
}

impl PlaybackError {
    pub fn new(class: ErrorClass, detail: impl Into<String>) -> Self {
        Self {
            class,
            detail: detail.into(),
            http_status: None,
        }
    }

    pub fn with_status(mut self, status: u16) -> Self {
        self.http_status = Some(status);
        self
    }

    /// 根据 HTTP 状态码推断错误分类。
    ///
    /// 地理封锁的状态码来自 ynotv 的 `GEOBLOCK_STATUSES`：
    /// `403`（经典 geo/IP 封锁）、`451`（RFC 7725 授权问题）、`426`、`423`。
    pub fn from_http_status(status: u16, detail: impl Into<String>) -> Self {
        let class = match status {
            401 => ErrorClass::Auth,
            403 => ErrorClass::Auth,
            404 | 410 => ErrorClass::NotFound,
            408 | 425 | 429 => ErrorClass::RateLimited,
            451 | 426 | 423 => ErrorClass::Auth,
            500..=599 => ErrorClass::Transient,
            _ => ErrorClass::Unknown,
        };
        Self {
            class,
            detail: detail.into(),
            http_status: Some(status),
        }
    }
}

/// 用户可执行的动作。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RecoveryAction {
    /// 稍后重试
    Retry,
    /// 换一条线路
    SwitchStream,
    /// 切换 User-Agent
    ChangeUserAgent,
    /// 编辑凭据
    EditCredentials,
    /// 换兼容引擎
    UseCompatibleEngine,
    /// 用外部播放器打开
    UseExternalPlayer,
    /// 报告问题
    ReportError,
}

/// 回退决策。
#[derive(Debug, Clone, PartialEq)]
pub enum RecoveryActionPlan {
    /// 重试（延迟秒数）
    RetryAfter { seconds: u64 },
    /// 换线路
    SwitchStream,
    /// 换引擎
    SwitchEngine(EngineKind),
    /// 重建引擎实例
    RecreateEngine,
    /// 失败，并给用户可执行动作
    Fail { actions: Vec<RecoveryAction> },
}

/// 退避序列（秒）。
pub const BACKOFF_SECONDS: &[u64] = &[1, 2, 4, 8, 15];

/// 播放上下文（决策所需的信息）。
#[derive(Debug, Clone)]
pub struct PlayContext {
    /// 当前使用的引擎
    pub engine: EngineKind,
    /// 已经重试过几次
    pub attempt: u32,
    /// 是否还有备用线路
    pub has_alternative_stream: bool,
    /// 是否允许切到兼容引擎
    pub allow_fallback: bool,
    /// 兼容引擎是否可用（lite 变体里没有 mpv）
    pub compatible_engine_available: bool,
}

/// 故障自愈决策器。
///
/// 这是「更稳定」这个目标的核心。
pub struct FallbackPolicy;

impl FallbackPolicy {
    /// 根据错误和上下文决定下一步。
    pub fn decide(error: &PlaybackError, ctx: &PlayContext) -> RecoveryActionPlan {
        match error.class {
            ErrorClass::Unsupported => {
                if ctx.engine == EngineKind::Media3
                    && ctx.allow_fallback
                    && ctx.compatible_engine_available
                {
                    RecoveryActionPlan::SwitchEngine(EngineKind::Mpv)
                } else {
                    RecoveryActionPlan::Fail {
                        actions: vec![
                            RecoveryAction::UseExternalPlayer,
                            RecoveryAction::ReportError,
                        ],
                    }
                }
            }

            ErrorClass::Auth => RecoveryActionPlan::Fail {
                actions: vec![
                    RecoveryAction::ChangeUserAgent,
                    RecoveryAction::EditCredentials,
                ],
            },

            ErrorClass::RateLimited => {
                // ★ 关键：退避前必须**释放上一路连接**，否则永远解不开限流
                RecoveryActionPlan::RetryAfter {
                    seconds: backoff(ctx.attempt),
                }
            }

            ErrorClass::Transient => {
                if ctx.attempt < 3 {
                    RecoveryActionPlan::RetryAfter {
                        seconds: backoff(ctx.attempt),
                    }
                } else if ctx.has_alternative_stream {
                    RecoveryActionPlan::SwitchStream
                } else {
                    RecoveryActionPlan::Fail {
                        actions: vec![RecoveryAction::Retry, RecoveryAction::ChangeUserAgent],
                    }
                }
            }

            ErrorClass::NotFound => {
                if ctx.has_alternative_stream {
                    RecoveryActionPlan::SwitchStream
                } else if ctx.engine == EngineKind::Media3
                    && ctx.allow_fallback
                    && ctx.compatible_engine_available
                {
                    RecoveryActionPlan::SwitchEngine(EngineKind::Mpv)
                } else {
                    RecoveryActionPlan::Fail {
                        actions: vec![RecoveryAction::SwitchStream, RecoveryAction::ReportError],
                    }
                }
            }

            ErrorClass::LiveEdge => RecoveryActionPlan::RetryAfter {
                seconds: backoff(ctx.attempt),
            },

            ErrorClass::DeviceLost => RecoveryActionPlan::RecreateEngine,

            ErrorClass::Unknown => {
                if ctx.has_alternative_stream {
                    RecoveryActionPlan::SwitchStream
                } else {
                    RecoveryActionPlan::Fail {
                        actions: vec![RecoveryAction::Retry, RecoveryAction::ReportError],
                    }
                }
            }
        }
    }
}

/// 退避时长。
pub fn backoff(attempt: u32) -> u64 {
    let idx = (attempt as usize).min(BACKOFF_SECONDS.len() - 1);
    BACKOFF_SECONDS[idx]
}

// ---------------------------------------------------------------------------
// 播放状态机
// ---------------------------------------------------------------------------

/// 播放状态。
#[derive(Debug, Clone, PartialEq)]
pub enum PlaybackState {
    Idle,
    Preparing {
        engine: EngineKind,
        attempt: u32,
    },
    Connecting,
    Buffering {
        percent: u8,
    },
    Playing {
        engine: EngineKind,
        live_edge: bool,
    },
    Paused,
    Seeking,
    Recovering {
        reason: RecoveryActionPlan,
        attempt: u32,
    },
    Failed {
        error: PlaybackError,
        actions: Vec<RecoveryAction>,
    },
}

/// 播放状态机。
///
/// **状态迁移是纯函数** → 可以完整单测。
#[derive(Debug)]
pub struct PlaybackCoordinator {
    state: PlaybackState,
    attempt: u32,
    allow_fallback: bool,
    compatible_engine_available: bool,
    has_alternative_stream: bool,
}

impl PlaybackCoordinator {
    pub fn new(allow_fallback: bool, compatible_engine_available: bool) -> Self {
        Self {
            state: PlaybackState::Idle,
            attempt: 0,
            allow_fallback,
            compatible_engine_available,
            has_alternative_stream: false,
        }
    }

    pub fn state(&self) -> &PlaybackState {
        &self.state
    }

    /// 告知是否存在备用线路。
    pub fn set_alternative_stream(&mut self, available: bool) {
        self.has_alternative_stream = available;
    }

    /// 开始播放。
    pub fn start(&mut self, engine: EngineKind) {
        self.attempt = 0;
        self.state = PlaybackState::Preparing { engine, attempt: 0 };
    }

    /// 连接成功。
    pub fn on_connected(&mut self) {
        self.state = PlaybackState::Connecting;
    }

    /// 开始缓冲。
    pub fn on_buffering(&mut self, percent: u8) {
        self.state = PlaybackState::Buffering { percent };
    }

    /// 首帧渲染 → 真正开始播放。
    pub fn on_first_frame(&mut self, engine: EngineKind, live_edge: bool) {
        self.attempt = 0;
        self.state = PlaybackState::Playing { engine, live_edge };
    }

    /// 暂停 / 继续。
    pub fn on_paused(&mut self) {
        self.state = PlaybackState::Paused;
    }

    pub fn on_resumed(&mut self) {
        if let PlaybackState::Playing { engine, live_edge } = self.state {
            self.state = PlaybackState::Playing { engine, live_edge };
        }
    }

    /// 出错 → 走回退决策。
    pub fn on_error(&mut self, error: PlaybackError) -> RecoveryActionPlan {
        let ctx = PlayContext {
            engine: self.current_engine(),
            attempt: self.attempt,
            has_alternative_stream: self.has_alternative_stream,
            allow_fallback: self.allow_fallback,
            compatible_engine_available: self.compatible_engine_available,
        };
        let plan = FallbackPolicy::decide(&error, &ctx);

        match &plan {
            RecoveryActionPlan::RetryAfter { .. } => {
                self.attempt = self.attempt.saturating_add(1);
                self.state = PlaybackState::Recovering {
                    reason: plan.clone(),
                    attempt: self.attempt,
                };
            }
            RecoveryActionPlan::SwitchStream => {
                self.attempt = 0;
                self.state = PlaybackState::Recovering {
                    reason: plan.clone(),
                    attempt: 0,
                };
            }
            RecoveryActionPlan::SwitchEngine(engine) => {
                self.attempt = 0;
                self.state = PlaybackState::Preparing {
                    engine: *engine,
                    attempt: 0,
                };
            }
            RecoveryActionPlan::RecreateEngine => {
                self.state = PlaybackState::Preparing {
                    engine: self.current_engine(),
                    attempt: self.attempt,
                };
            }
            RecoveryActionPlan::Fail { actions } => {
                self.state = PlaybackState::Failed {
                    error,
                    actions: actions.clone(),
                };
            }
        }
        plan
    }

    /// 当前引擎。
    pub fn current_engine(&self) -> EngineKind {
        match &self.state {
            PlaybackState::Preparing { engine, .. } => *engine,
            PlaybackState::Playing { engine, .. } => *engine,
            PlaybackState::Recovering { .. } => EngineKind::Media3,
            _ => EngineKind::Media3,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx(engine: EngineKind, attempt: u32) -> PlayContext {
        PlayContext {
            engine,
            attempt,
            has_alternative_stream: false,
            allow_fallback: true,
            compatible_engine_available: true,
        }
    }

    #[test]
    fn unsupported_switches_to_compatible_engine() {
        let err = PlaybackError::new(ErrorClass::Unsupported, "codec not supported");
        let plan = FallbackPolicy::decide(&err, &ctx(EngineKind::Media3, 0));
        assert_eq!(plan, RecoveryActionPlan::SwitchEngine(EngineKind::Mpv));
    }

    #[test]
    fn unsupported_fails_when_no_compatible_engine() {
        let err = PlaybackError::new(ErrorClass::Unsupported, "codec");
        let mut c = ctx(EngineKind::Media3, 0);
        c.compatible_engine_available = false;
        let plan = FallbackPolicy::decide(&err, &c);
        assert!(matches!(plan, RecoveryActionPlan::Fail { .. }));
    }

    #[test]
    fn auth_error_offers_ua_and_credentials() {
        let err = PlaybackError::new(ErrorClass::Auth, "403");
        let plan = FallbackPolicy::decide(&err, &ctx(EngineKind::Media3, 0));
        match plan {
            RecoveryActionPlan::Fail { actions } => {
                assert!(actions.contains(&RecoveryAction::ChangeUserAgent));
                assert!(actions.contains(&RecoveryAction::EditCredentials));
            }
            other => panic!("期望 Fail，实际 {other:?}"),
        }
    }

    #[test]
    fn transient_retries_then_switches_stream() {
        let err = PlaybackError::new(ErrorClass::Transient, "timeout");
        let plan = FallbackPolicy::decide(&err, &ctx(EngineKind::Media3, 0));
        assert!(matches!(plan, RecoveryActionPlan::RetryAfter { .. }));

        // 重试用尽后：有备用线路 → 换线路；没有 → 失败并给出动作
        let mut with_alt = ctx(EngineKind::Media3, 3);
        with_alt.has_alternative_stream = true;
        assert_eq!(
            FallbackPolicy::decide(&err, &with_alt),
            RecoveryActionPlan::SwitchStream
        );

        let plan = FallbackPolicy::decide(&err, &ctx(EngineKind::Media3, 3));
        assert!(matches!(plan, RecoveryActionPlan::Fail { .. }));
    }

    #[test]
    fn rate_limited_always_backs_off() {
        let err = PlaybackError::new(ErrorClass::RateLimited, "too many");
        let plan = FallbackPolicy::decide(&err, &ctx(EngineKind::Media3, 5));
        assert!(matches!(plan, RecoveryActionPlan::RetryAfter { .. }));
    }

    #[test]
    fn not_found_switches_stream_then_engine() {
        let err = PlaybackError::new(ErrorClass::NotFound, "404");
        let mut c = ctx(EngineKind::Media3, 0);
        c.has_alternative_stream = true;
        assert_eq!(
            FallbackPolicy::decide(&err, &c),
            RecoveryActionPlan::SwitchStream
        );

        let c = ctx(EngineKind::Media3, 0);
        assert_eq!(
            FallbackPolicy::decide(&err, &c),
            RecoveryActionPlan::SwitchEngine(EngineKind::Mpv)
        );
    }

    #[test]
    fn backoff_is_bounded() {
        assert_eq!(backoff(0), 1);
        assert_eq!(backoff(1), 2);
        assert_eq!(backoff(4), 15);
        assert_eq!(backoff(99), 15, "退避必须有上限");
    }

    #[test]
    fn http_status_classification() {
        assert_eq!(
            PlaybackError::from_http_status(401, "").class,
            ErrorClass::Auth
        );
        assert_eq!(
            PlaybackError::from_http_status(404, "").class,
            ErrorClass::NotFound
        );
        assert_eq!(
            PlaybackError::from_http_status(429, "").class,
            ErrorClass::RateLimited
        );
        assert_eq!(
            PlaybackError::from_http_status(503, "").class,
            ErrorClass::Transient
        );
        assert_eq!(
            PlaybackError::from_http_status(451, "").class,
            ErrorClass::Auth,
            "451 是授权问题不是地理封锁"
        );
    }

    #[test]
    fn coordinator_reaches_playing_on_first_frame() {
        let mut c = PlaybackCoordinator::new(true, true);
        c.start(EngineKind::Media3);
        assert!(matches!(c.state(), PlaybackState::Preparing { .. }));
        c.on_connected();
        c.on_first_frame(EngineKind::Media3, true);
        match c.state() {
            PlaybackState::Playing { engine, live_edge } => {
                assert_eq!(*engine, EngineKind::Media3);
                assert!(*live_edge);
            }
            other => panic!("期望 Playing，实际 {other:?}"),
        }
    }

    #[test]
    fn coordinator_transitions_to_recovering_on_error() {
        let mut c = PlaybackCoordinator::new(true, true);
        c.start(EngineKind::Media3);
        c.on_error(PlaybackError::new(ErrorClass::Transient, "boom"));
        assert!(matches!(c.state(), PlaybackState::Recovering { .. }));
    }

    #[test]
    fn coordinator_switches_engine_on_unsupported() {
        let mut c = PlaybackCoordinator::new(true, true);
        c.start(EngineKind::Media3);
        c.on_error(PlaybackError::new(ErrorClass::Unsupported, "codec"));
        match c.state() {
            PlaybackState::Preparing { engine, .. } => {
                assert_eq!(*engine, EngineKind::Mpv)
            }
            other => panic!("期望 Preparing(Mpv)，实际 {other:?}"),
        }
    }

    #[test]
    fn coordinator_ends_failed_with_actions() {
        let mut c = PlaybackCoordinator::new(true, true);
        c.start(EngineKind::Media3);
        c.on_error(PlaybackError::new(ErrorClass::Auth, "403"));
        match c.state() {
            PlaybackState::Failed { actions, .. } => {
                assert!(actions.contains(&RecoveryAction::ChangeUserAgent));
            }
            other => panic!("期望 Failed，实际 {other:?}"),
        }
    }

    #[test]
    fn attempt_counter_resets_after_success() {
        let mut c = PlaybackCoordinator::new(true, true);
        c.start(EngineKind::Media3);
        c.on_error(PlaybackError::new(ErrorClass::Transient, "x"));
        c.on_error(PlaybackError::new(ErrorClass::Transient, "x"));
        c.on_first_frame(EngineKind::Media3, true);
        // 成功后 attempt 应归零，下一次错误从退避 1 秒开始
        c.on_error(PlaybackError::new(ErrorClass::Transient, "x"));
        match c.state() {
            PlaybackState::Recovering { reason, .. } => {
                assert_eq!(*reason, RecoveryActionPlan::RetryAfter { seconds: 1 });
            }
            other => panic!("期望 Recovering，实际 {other:?}"),
        }
    }
}
