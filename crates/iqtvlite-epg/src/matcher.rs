//! 频道 ↔ 指南 匹配算法。
//!
//! # 为什么这个算法值得单独写
//!
//! 「EPG 是空的」是 IPTV 播放器**最常见的投诉**。根因几乎总是匹配算法太宽松，
//! 把频道接到了错误的节目单上；或者太严格，一个都接不上。
//!
//! # 核心规则（来自 better-iptv 的实战教训）
//!
//! > **「有 id 的频道绝不用名字匹配。」**
//!
//! 否则「另一个国家的同名频道」会串台 —— 用户看到的是完全无关国家的节目单，
//! 比「没有 EPG」更糟。
//!
//! 匹配优先级：
//!
//! | 优先级 | 规则 | 分数 | 备注 |
//! |---|---|---|---|
//! | 1 | `tvg-id` == `<channel id>` | 1.00 | 大小写不敏感 + Unicode 规范化 |
//! | 2 | `tvg-name` == `<display-name>` | 0.90 | 含「空格↔下划线」变体 |
//! | 3 | 显示名 == `<display-name>` | 0.75 | **仅当该频道没有 id 时才允许** |
//! | 4 | 手动指定 | 1.00 | 永不被自动匹配覆盖 |
//!
//! 另外：**指南给两个频道同名的，丢弃并计数，绝不随便挑一个。**

use std::collections::HashMap;

use iqtvlite_core::model::MatchSource;

/// 一个待匹配的播放列表频道。
#[derive(Debug, Clone)]
pub struct MatchCandidate {
    /// 本地频道 ID
    pub channel_id: String,
    /// `tvg-id`
    pub tvg_id: Option<String>,
    /// `tvg-name`
    pub tvg_name: Option<String>,
    /// 显示名
    pub name: String,
}

/// 一个指南频道。
#[derive(Debug, Clone)]
pub struct EpgChannelInfo {
    pub epg_channel_id: String,
    /// XMLTV `<channel id>`
    pub key: String,
    /// 所有 `<display-name>`
    pub display_names: Vec<String>,
}

/// 匹配结果。
#[derive(Debug, Clone)]
pub struct MatchOutcome {
    /// 频道 ID → 指南频道 ID + 来源 + 分数
    pub matches: Vec<MatchedChannel>,
    /// 有 id 但没匹配上的频道
    pub unmatched_with_id: Vec<String>,
    /// 没有 id 且也没匹配上的频道
    pub unmatched_without_id: Vec<String>,
    /// 指南里出现多次（重名）而被丢弃的 key
    pub ambiguous_epg_keys: Vec<String>,
}

/// 单条匹配。
#[derive(Debug, Clone)]
pub struct MatchedChannel {
    pub channel_id: String,
    pub epg_channel_id: String,
    pub match_source: MatchSource,
    pub confidence: f64,
}

/// 匹配器。
#[derive(Debug, Default)]
pub struct Matcher {
    /// 规范化后的指南频道 key → 索引
    by_key: HashMap<String, Vec<usize>>,
    /// 规范化后的 display-name → 索引
    by_name: HashMap<String, Vec<usize>>,
    channels: Vec<EpgChannelInfo>,
}

impl Matcher {
    pub fn new() -> Self {
        Self::default()
    }

    /// 载入指南频道。
    pub fn load(&mut self, channels: Vec<EpgChannelInfo>) {
        self.channels = channels;
        self.by_key.clear();
        self.by_name.clear();
        for (idx, ch) in self.channels.iter().enumerate() {
            let key = normalize(&ch.key);
            if !key.is_empty() {
                self.by_key.entry(key).or_default().push(idx);
            }
            for name in &ch.display_names {
                let n = normalize(name);
                if n.is_empty() {
                    continue;
                }
                // 「空格 ↔ 下划线」变体
                let alt = n.replace(' ', "_");
                self.by_name.entry(n.clone()).or_default().push(idx);
                if alt != n {
                    self.by_name.entry(alt).or_default().push(idx);
                }
            }
        }
    }

    /// 指南频道总数。
    pub fn len(&self) -> usize {
        self.channels.len()
    }

    pub fn is_empty(&self) -> bool {
        self.channels.is_empty()
    }

    /// 对所有候选频道做匹配。
    pub fn match_all(&self, candidates: &[MatchCandidate]) -> MatchOutcome {
        let mut matches = Vec::new();
        let mut unmatched_with_id = Vec::new();
        let mut unmatched_without_id = Vec::new();
        let mut ambiguous_epg_keys = Vec::new();
        let mut used_epg: Vec<usize> = Vec::new();

        // ── 规则 1：tvg-id 精确匹配 ──
        for cand in candidates {
            let Some(tvg_id) = cand.tvg_id.as_deref().filter(|s| !s.trim().is_empty()) else {
                continue;
            };
            let key = normalize(tvg_id);
            let Some(hits) = self.by_key.get(&key) else {
                continue;
            };
            match self.pick_unique(hits, &used_epg, &mut ambiguous_epg_keys) {
                Some(idx) => {
                    used_epg.push(idx);
                    matches.push(MatchedChannel {
                        channel_id: cand.channel_id.clone(),
                        epg_channel_id: self.channels[idx].epg_channel_id.clone(),
                        match_source: MatchSource::Id,
                        confidence: 1.0,
                    });
                }
                None => continue,
            }
        }

        // ── 规则 2：tvg-name 匹配（仅在没有 id 匹配上的频道上做） ──
        for cand in candidates {
            if matches.iter().any(|m| m.channel_id == cand.channel_id) {
                continue;
            }
            let Some(tvg_name) = cand.tvg_name.as_deref().filter(|s| !s.trim().is_empty()) else {
                continue;
            };
            let n = normalize(tvg_name);
            let Some(hits) = self.by_name.get(&n) else {
                continue;
            };
            if let Some(idx) = self.pick_unique(hits, &used_epg, &mut ambiguous_epg_keys) {
                used_epg.push(idx);
                matches.push(MatchedChannel {
                    channel_id: cand.channel_id.clone(),
                    epg_channel_id: self.channels[idx].epg_channel_id.clone(),
                    match_source: MatchSource::TvgName,
                    confidence: 0.90,
                });
            }
        }

        // ── 规则 3：显示名匹配 —— 【仅当该频道没有 id 时才允许】 ──
        for cand in candidates {
            if matches.iter().any(|m| m.channel_id == cand.channel_id) {
                continue;
            }
            // ★ 关键约束：有 id 的频道绝不用名字匹配
            if cand
                .tvg_id
                .as_deref()
                .map(|s| !s.trim().is_empty())
                .unwrap_or(false)
            {
                continue;
            }
            let n = normalize(&cand.name);
            let Some(hits) = self.by_name.get(&n) else {
                continue;
            };
            if let Some(idx) = self.pick_unique(hits, &used_epg, &mut ambiguous_epg_keys) {
                used_epg.push(idx);
                matches.push(MatchedChannel {
                    channel_id: cand.channel_id.clone(),
                    epg_channel_id: self.channels[idx].epg_channel_id.clone(),
                    match_source: MatchSource::Name,
                    confidence: 0.75,
                });
            }
        }

        // ── 统计未匹配 ──
        for cand in candidates {
            if matches.iter().any(|m| m.channel_id == cand.channel_id) {
                continue;
            }
            if cand
                .tvg_id
                .as_deref()
                .map(|s| !s.trim().is_empty())
                .unwrap_or(false)
            {
                unmatched_with_id.push(cand.channel_id.clone());
            } else {
                unmatched_without_id.push(cand.channel_id.clone());
            }
        }

        MatchOutcome {
            matches,
            unmatched_with_id,
            unmatched_without_id,
            ambiguous_epg_keys,
        }
    }

    /// 从候选索引里挑一个**唯一**的。
    ///
    /// 重名（多个指南频道同名）→ **丢弃并计数，绝不随便挑一个**。
    fn pick_unique(
        &self,
        hits: &[usize],
        used: &[usize],
        ambiguous: &mut Vec<String>,
    ) -> Option<usize> {
        let free: Vec<usize> = hits.iter().copied().filter(|i| !used.contains(i)).collect();
        match free.len() {
            0 => None,
            1 => Some(free[0]),
            _ => {
                for idx in &free {
                    let key = self.channels[*idx].key.clone();
                    if !ambiguous.contains(&key) {
                        ambiguous.push(key);
                    }
                }
                None
            }
        }
    }

    /// 匹配率（0.0 – 1.0），用于 UI 上的「EPG 匹配率 23%」提示。
    pub fn match_rate(outcome: &MatchOutcome, total: usize) -> f64 {
        if total == 0 {
            return 0.0;
        }
        outcome.matches.len() as f64 / total as f64
    }
}

/// 规范化：Unicode NFKC + 转小写 + 去多余空白。
pub fn normalize(input: &str) -> String {
    use unicode_normalization::UnicodeNormalization;
    let normalized: String = input.nfkc().collect();
    normalized
        .trim()
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn epg(id: &str, key: &str, names: &[&str]) -> EpgChannelInfo {
        EpgChannelInfo {
            epg_channel_id: id.to_string(),
            key: key.to_string(),
            display_names: names.iter().map(|s| s.to_string()).collect(),
        }
    }

    fn cand(id: &str, tvg_id: Option<&str>, tvg_name: Option<&str>, name: &str) -> MatchCandidate {
        MatchCandidate {
            channel_id: id.to_string(),
            tvg_id: tvg_id.map(str::to_string),
            tvg_name: tvg_name.map(str::to_string),
            name: name.to_string(),
        }
    }

    #[test]
    fn matches_by_tvg_id() {
        let mut m = Matcher::new();
        m.load(vec![epg("e1", "bbc.one.uk", &["BBC One"])]);
        let out = m.match_all(&[cand("c1", Some("bbc.one.uk"), None, "BBC One")]);
        assert_eq!(out.matches.len(), 1);
        assert_eq!(out.matches[0].match_source, MatchSource::Id);
        assert_eq!(out.matches[0].confidence, 1.0);
    }

    #[test]
    fn tvg_id_match_is_case_insensitive() {
        let mut m = Matcher::new();
        m.load(vec![epg("e1", "BBCOne.UK", &["BBC One"])]);
        let out = m.match_all(&[cand("c1", Some("bbconE.uk"), None, "X")]);
        assert_eq!(out.matches.len(), 1);
    }

    #[test]
    fn channel_with_id_is_never_matched_by_name() {
        // ★ 核心规则：有 id 但 id 对不上时，绝不能按名字接上
        let mut m = Matcher::new();
        m.load(vec![epg("e1", "us.bbc.one", &["BBC One"])]);
        let out = m.match_all(&[cand("c1", Some("uk.bbc.one"), None, "BBC One")]);
        assert!(out.matches.is_empty(), "不该按名字匹配: {:?}", out.matches);
        assert_eq!(out.unmatched_with_id.len(), 1);
    }

    #[test]
    fn channel_without_id_may_match_by_name() {
        let mut m = Matcher::new();
        m.load(vec![epg("e1", "bbc.one.uk", &["BBC One"])]);
        let out = m.match_all(&[cand("c1", None, None, "BBC One")]);
        assert_eq!(out.matches.len(), 1);
        assert_eq!(out.matches[0].match_source, MatchSource::Name);
        assert_eq!(out.matches[0].confidence, 0.75);
    }

    #[test]
    fn tvg_name_matches_with_score_090() {
        let mut m = Matcher::new();
        m.load(vec![epg("e1", "x.y", &["BBC One HD"])]);
        let out = m.match_all(&[cand("c1", None, Some("BBC One HD"), "Other")]);
        assert_eq!(out.matches.len(), 1);
        assert_eq!(out.matches[0].match_source, MatchSource::TvgName);
        assert_eq!(out.matches[0].confidence, 0.90);
    }

    #[test]
    fn ambiguous_epg_names_are_dropped_not_guessed() {
        // 指南里两个频道同名 → 丢弃并计数
        let mut m = Matcher::new();
        m.load(vec![
            epg("e1", "us.bbc", &["BBC One"]),
            epg("e2", "uk.bbc", &["BBC One"]),
        ]);
        let out = m.match_all(&[cand("c1", None, None, "BBC One")]);
        assert!(out.matches.is_empty(), "重名不该随便挑一个");
        assert_eq!(out.ambiguous_epg_keys.len(), 2);
    }

    #[test]
    fn one_epg_channel_is_not_used_twice() {
        let mut m = Matcher::new();
        m.load(vec![epg("e1", "x.y", &["Chan"])]);
        let out = m.match_all(&[
            cand("c1", Some("x.y"), None, "Chan"),
            cand("c2", Some("x.y"), None, "Chan"),
        ]);
        assert_eq!(out.matches.len(), 1, "一个指南频道只能被用一次");
        assert_eq!(out.unmatched_with_id.len(), 1);
    }

    #[test]
    fn underscore_variant_is_matched() {
        let mut m = Matcher::new();
        m.load(vec![epg("e1", "k", &["BBC One"])]);
        let out = m.match_all(&[cand("c1", None, Some("BBC_One"), "X")]);
        assert_eq!(out.matches.len(), 1);
    }

    #[test]
    fn match_rate_is_computed() {
        let mut m = Matcher::new();
        m.load(vec![epg("e1", "a", &["A"])]);
        let out = m.match_all(&[
            cand("c1", Some("a"), None, "A"),
            cand("c2", Some("b"), None, "B"),
        ]);
        let rate = Matcher::match_rate(&out, 2);
        assert!((rate - 0.5).abs() < f64::EPSILON);
    }

    #[test]
    fn normalization_handles_fullwidth_and_case() {
        assert_eq!(normalize("ＢＢＣ　One"), "bbc one");
    }
}
