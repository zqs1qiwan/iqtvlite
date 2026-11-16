//! iqtvlite DVR — 录制任务模型与状态机。
//!
//! 与播放层一样，**决策与执行分离**：
//! - 本 crate（纯 Rust）决定「录什么、何时开始、何时停止、失败如何恢复」—— 可完整单测
//! - Android 侧 OkHttp 负责把字节流写到文件 —— 由 JNI 调用
//!
//! # 录制方式
//!
//! IPTV 录制 = **零转码的字节落盘**（TS 流直接写 .ts，最省 CPU、最稳）。
//! HLS 流则录制「媒体播放列表 + 分片」成本地可播的归档。

use serde::{Deserialize, Serialize};

/// 录制来源类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RecordingSource {
    /// 即时录制：从当前直播流开始录
    Instant,
    /// 定时录制：按 EPG 节目的 start/stop 录
    Scheduled,
    /// Catch-up 归档录制：录供应商已有的回看流
    Catchup,
}

/// 输出容器格式。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OutputFormat {
    /// MPEG-TS，直接写字节（零转码）
    Ts,
    /// HLS：保存 m3u8 + 分片
    Hls,
}

impl OutputFormat {
    /// 根据流 URL 推断输出格式。
    pub fn from_url(url: &str) -> Self {
        let lower = url.to_ascii_lowercase();
        if lower.contains(".m3u8") || lower.contains("hls") {
            OutputFormat::Hls
        } else {
            OutputFormat::Ts
        }
    }

    pub fn extension(self) -> &'static str {
        match self {
            OutputFormat::Ts => "ts",
            OutputFormat::Hls => "m3u8",
        }
    }
}

/// 录制任务。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecordingTask {
    /// 任务 ID（稳定 ID 派生）
    pub id: i64,
    /// 频道稳定 ID
    pub channel_id: i64,
    /// 频道名 / 节目标题
    pub title: String,
    /// 来源类型
    pub source: RecordingSource,
    /// 流 URL
    pub url: String,
    /// 备用线路
    pub alt_urls: Vec<String>,
    /// UA（按频道）
    pub user_agent: Option<String>,
    /// Referer
    pub referrer: Option<String>,
    /// 输出格式
    pub output_format: OutputFormat,
    /// 输出文件路径（不含扩展名）
    pub output_path: String,
    /// 计划开始时间（Unix 秒）；即时录制为 0
    pub start_ts: i64,
    /// 计划结束时间（Unix 秒）；0 = 一直录到手动停止
    pub stop_ts: i64,
    /// 单文件最大字节数（0 = 不限）
    pub max_bytes: u64,
}

impl RecordingTask {
    /// 生成本地文件名（TiviMate 风格：日期 + 标题）。
    pub fn file_name(&self) -> String {
        sanitize_filename(&format!("{}_{}", self.title, self.id))
    }

    /// 当前是否应立即开始。
    pub fn should_start(&self, now: i64) -> bool {
        match self.source {
            RecordingSource::Instant | RecordingSource::Catchup => true,
            RecordingSource::Scheduled => self.start_ts == 0 || now >= self.start_ts,
        }
    }

    /// 当前是否应停止（计划时间到 / 超过大小上限）。
    pub fn should_stop(&self, now: i64, bytes_written: u64) -> bool {
        if self.max_bytes > 0 && bytes_written >= self.max_bytes {
            return true;
        }
        if self.stop_ts > 0 && now >= self.stop_ts {
            return true;
        }
        false
    }
}

/// 录制状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RecordingState {
    /// 已计划，等待开始时间
    Scheduled,
    /// 正在录制
    Recording,
    /// 成功完成
    Done,
    /// 失败（带错误）
    Failed,
    /// 用户取消
    Cancelled,
}

/// 状态机事件。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecordingEvent {
    /// 开始条件满足
    Start,
    /// 停止条件满足（计划时间到 / 用户停止）
    Stop,
    /// 写入了 N 字节
    Progress(u64),
    /// I/O 错误
    IoError(String),
    /// 用户取消
    Cancel,
    /// 流断开，尝试切换备用线路
    StreamLost,
    /// 备用线路切换成功
    Switched,
}

/// 状态机迁移结果（决定执行层该做什么）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecordingAction {
    /// 开始写文件（URL 为要用的线路）
    StartWriting(String),
    /// 继续写
    Continue,
    /// 正常收尾
    Finalize,
    /// 切换到备用线路后继续写
    SwitchStream(String),
    /// 删除部分文件并报错
    FailAndCleanup(String),
    /// 删除部分文件并标记取消
    CancelAndCleanup,
    /// 无动作（忽略事件）
    None,
}

/// 录制状态机（纯逻辑，可单测）。
#[derive(Debug, Clone)]
pub struct RecordingMachine {
    pub state: RecordingState,
    pub bytes_written: u64,
    pub stream_index: usize,
    error: Option<String>,
}

impl Default for RecordingMachine {
    fn default() -> Self {
        Self {
            state: RecordingState::Scheduled,
            bytes_written: 0,
            stream_index: 0,
            error: None,
        }
    }
}

impl RecordingMachine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn error(&self) -> Option<&str> {
        self.error.as_deref()
    }

    /// 处理一个事件，返回执行层应做的动作。
    pub fn handle(&mut self, task: &RecordingTask, event: RecordingEvent) -> RecordingAction {
        use RecordingEvent::*;
        match (self.state, event) {
            (RecordingState::Scheduled, Start) => {
                self.state = RecordingState::Recording;
                RecordingAction::StartWriting(task.url.clone())
            }
            (RecordingState::Scheduled, Cancel) => {
                self.state = RecordingState::Cancelled;
                RecordingAction::CancelAndCleanup
            }
            (RecordingState::Recording, Progress(n)) => {
                self.bytes_written += n;
                if task.should_stop_now(self.bytes_written) {
                    self.state = RecordingState::Done;
                    RecordingAction::Finalize
                } else {
                    RecordingAction::Continue
                }
            }
            (RecordingState::Recording, Stop) => {
                self.state = RecordingState::Done;
                RecordingAction::Finalize
            }
            (RecordingState::Recording, StreamLost) => {
                if self.stream_index + 1 < task.all_urls().len() {
                    self.stream_index += 1;
                    RecordingAction::SwitchStream(task.all_urls()[self.stream_index].clone())
                } else {
                    self.state = RecordingState::Failed;
                    self.error = Some("所有线路都断开".to_string());
                    RecordingAction::FailAndCleanup("所有线路都断开".to_string())
                }
            }
            (RecordingState::Recording, Switched) => RecordingAction::Continue,
            (RecordingState::Recording, IoError(e)) => {
                self.state = RecordingState::Failed;
                self.error = Some(e.clone());
                RecordingAction::FailAndCleanup(e)
            }
            (RecordingState::Recording, Cancel) => {
                self.state = RecordingState::Cancelled;
                RecordingAction::CancelAndCleanup
            }
            (_, _) => RecordingAction::None,
        }
    }
}

impl RecordingTask {
    fn all_urls(&self) -> Vec<String> {
        let mut v = Vec::with_capacity(1 + self.alt_urls.len());
        v.push(self.url.clone());
        v.extend(self.alt_urls.iter().cloned());
        v
    }

    fn should_stop_now(&self, bytes: u64) -> bool {
        self.max_bytes > 0 && bytes >= self.max_bytes
    }
}

/// 清理文件名中的非法字符。
pub fn sanitize_filename(s: &str) -> String {
    s.chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => c,
        })
        .collect::<String>()
        .trim()
        .to_string()
}

/// 估算录制文件所需空间（MB）。
///
/// 基于码率（kbps）× 时长（秒）/ 8 / 1024。
pub fn estimate_size_mb(bitrate_kbps: u64, duration_secs: u64) -> u64 {
    bitrate_kbps.saturating_mul(duration_secs) / 8 / 1024
}

#[cfg(test)]
mod tests {
    use super::*;

    fn task() -> RecordingTask {
        RecordingTask {
            id: 1,
            channel_id: 42,
            title: "CCTV-1 综合/新闻".to_string(),
            source: RecordingSource::Instant,
            url: "http://x/live.ts".into(),
            alt_urls: vec!["http://y/live.ts".into()],
            user_agent: None,
            referrer: None,
            output_format: OutputFormat::Ts,
            output_path: "/sdcard/record".into(),
            start_ts: 0,
            stop_ts: 3600,
            max_bytes: 0,
        }
    }

    #[test]
    fn output_format_from_url() {
        assert_eq!(OutputFormat::from_url("http://x/a.ts"), OutputFormat::Ts);
        assert_eq!(
            OutputFormat::from_url("http://x/a.m3u8?x=1"),
            OutputFormat::Hls
        );
    }

    #[test]
    fn sanitize_filename_removes_illegal_chars() {
        let s = sanitize_filename("CCTV-1 综合/新闻:直播?");
        assert!(!s.contains('/'));
        assert!(!s.contains(':'));
        assert!(!s.contains('?'));
    }

    #[test]
    fn instant_task_starts_immediately() {
        let t = task();
        assert!(t.should_start(0));
        assert_eq!(
            RecordingMachine::new().handle(&t, RecordingEvent::Start),
            RecordingAction::StartWriting("http://x/live.ts".into())
        );
    }

    #[test]
    fn scheduled_task_waits_for_start_time() {
        let mut t = task();
        t.source = RecordingSource::Scheduled;
        t.start_ts = 1000;
        assert!(!t.should_start(999));
        assert!(t.should_start(1000));
    }

    #[test]
    fn recording_stops_at_stop_time() {
        let t = task();
        assert!(t.should_stop(3600, 0));
        assert!(!t.should_stop(3599, 0));
    }

    #[test]
    fn recording_stops_at_max_bytes() {
        let mut t = task();
        t.max_bytes = 100;
        assert!(t.should_stop(0, 100));
        assert!(!t.should_stop(0, 99));
    }

    #[test]
    fn state_machine_full_lifecycle() {
        let t = task();
        let mut m = RecordingMachine::new();
        assert_eq!(m.state, RecordingState::Scheduled);
        assert_eq!(
            m.handle(&t, RecordingEvent::Start),
            RecordingAction::StartWriting(t.url.clone())
        );
        assert_eq!(m.state, RecordingState::Recording);
        assert_eq!(
            m.handle(&t, RecordingEvent::Progress(100)),
            RecordingAction::Continue
        );
        assert_eq!(m.bytes_written, 100);
        assert_eq!(
            m.handle(&t, RecordingEvent::Stop),
            RecordingAction::Finalize
        );
        assert_eq!(m.state, RecordingState::Done);
    }

    #[test]
    fn state_machine_switches_to_backup_stream() {
        let t = task();
        let mut m = RecordingMachine::new();
        m.handle(&t, RecordingEvent::Start);
        assert_eq!(
            m.handle(&t, RecordingEvent::StreamLost),
            RecordingAction::SwitchStream("http://y/live.ts".into())
        );
        // 再次断开 → 失败
        assert!(matches!(
            m.handle(&t, RecordingEvent::StreamLost),
            RecordingAction::FailAndCleanup(_)
        ));
        assert_eq!(m.state, RecordingState::Failed);
    }

    #[test]
    fn state_machine_handles_io_error_and_cancel() {
        let t = task();
        let mut m = RecordingMachine::new();
        m.handle(&t, RecordingEvent::Start);
        assert!(matches!(
            m.handle(&t, RecordingEvent::IoError("disk full".into())),
            RecordingAction::FailAndCleanup(_)
        ));
        assert_eq!(m.error(), Some("disk full"));

        let mut m2 = RecordingMachine::new();
        assert_eq!(
            m2.handle(&t, RecordingEvent::Cancel),
            RecordingAction::CancelAndCleanup
        );
        assert_eq!(m2.state, RecordingState::Cancelled);
    }

    #[test]
    fn max_bytes_finalizes_on_progress() {
        let mut t = task();
        t.max_bytes = 50;
        let mut m = RecordingMachine::new();
        m.handle(&t, RecordingEvent::Start);
        assert_eq!(
            m.handle(&t, RecordingEvent::Progress(60)),
            RecordingAction::Finalize
        );
        assert_eq!(m.state, RecordingState::Done);
    }

    #[test]
    fn estimate_size() {
        // 2000 kbps × 3600s ≈ 879 MB
        let mb = estimate_size_mb(2000, 3600);
        assert!(mb > 800 && mb < 950, "实际 {mb} MB");
    }
}
