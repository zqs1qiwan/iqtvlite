//! # iqtvlite-tvfocus
//!
//! 自研的 **TV 空间焦点导航**。
//!
//! # 为什么必须自研
//!
//! **Slint 没有空间导航**（源码确认：`internal/core/item_focus.rs` 只有线性 tab 焦点链，
//! `ForwardFocus`/`PreviousFocus` 已被移除）。方向键只能做「上一个/下一个」。
//!
//! 但 TV 应用需要的是**二维空间导航**：EPG 网格里左右要跳时间片、上下要跳频道；
//! 频道列表 + 侧边分类栏之间要能横向穿越。
//!
//! # 设计
//!
//! 用**规则网格**模型（行 × 列）而不是通用几何算法：
//!
//! - EPG 网格 = 行（频道） × 列（时间片）
//! - 频道列表 = 单列（宽度 = 1）
//! - 分类栏 = 单行
//!
//! 规则网格的导航可以退化为「坐标 + 行列步进」，比通用几何算法**更可靠**。

use serde::{Deserialize, Serialize};

/// 导航方向。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Up,
    Down,
    Left,
    Right,
}

/// 焦点项的逻辑位置。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct GridPos {
    pub row: u16,
    pub col: u16,
}

/// 一个可聚焦项。
#[derive(Debug, Clone)]
pub struct FocusItem {
    pub id: String,
    pub pos: GridPos,
    /// 该项所在区域的 ID（用于跨区域导航，如「频道列表 ↔ 分类栏」）
    pub region: String,
}

/// 规则网格焦点模型。
#[derive(Debug, Default)]
pub struct FocusGrid {
    items: Vec<FocusItem>,
    /// 位置 → 索引
    index: std::collections::HashMap<GridPos, usize>,
    current: Option<usize>,
}

impl FocusGrid {
    pub fn new() -> Self {
        Self::default()
    }

    /// 清空并重建网格。
    pub fn rebuild(&mut self, items: Vec<FocusItem>) {
        self.items = items;
        self.index.clear();
        for (i, item) in self.items.iter().enumerate() {
            self.index.insert(item.pos, i);
        }
        // 焦点失效 → 回落到第一个
        if self.current.map(|c| c >= self.items.len()).unwrap_or(true) {
            self.current = if self.items.is_empty() { None } else { Some(0) };
        }
    }

    /// 当前焦点索引。
    pub fn current(&self) -> Option<usize> {
        self.current
    }

    /// 当前焦点项。
    pub fn current_item(&self) -> Option<&FocusItem> {
        self.current.and_then(|i| self.items.get(i))
    }

    /// 设置焦点（按索引）。
    pub fn set_current(&mut self, index: usize) -> bool {
        if index < self.items.len() {
            self.current = Some(index);
            true
        } else {
            false
        }
    }

    /// 设置焦点（按 ID）。
    pub fn focus_by_id(&mut self, id: &str) -> bool {
        if let Some(idx) = self.items.iter().position(|i| i.id == id) {
            self.current = Some(idx);
            true
        } else {
            false
        }
    }

    /// 按方向移动焦点。
    ///
    /// 返回 `true` 表示焦点发生了移动（调用方需要滚动列表到新位置）。
    pub fn move_focus(&mut self, dir: Direction) -> bool {
        let Some(current) = self.current else {
            return false;
        };
        let Some(item) = self.items.get(current) else {
            return false;
        };
        let pos = item.pos;

        // 1) 同区域内的直接邻位
        let step: (i32, i32) = match dir {
            Direction::Up => (-1, 0),
            Direction::Down => (1, 0),
            Direction::Left => (0, -1),
            Direction::Right => (0, 1),
        };
        let cand_row = pos.row as i32 + step.0;
        let cand_col = pos.col as i32 + step.1;
        if cand_row >= 0 && cand_col >= 0 {
            let c = GridPos {
                row: cand_row as u16,
                col: cand_col as u16,
            };
            if let Some(idx) = self.index.get(&c) {
                self.current = Some(*idx);
                return true;
            }
        }

        // 2) 边界处 —— 尝试跨区域（比如从频道列表右边缘进入分类栏）
        if let Some(idx) = self.cross_region(current, dir) {
            self.current = Some(idx);
            return true;
        }

        false
    }

    /// 跨区域导航：找同一行/列上「最近的其他区域」的项。
    fn cross_region(&self, current: usize, dir: Direction) -> Option<usize> {
        let item = self.items.get(current)?;
        let best: Option<(usize, i32)> = self
            .items
            .iter()
            .enumerate()
            .filter(|(i, other)| *i != current && other.region != item.region)
            .filter_map(|(i, other)| {
                let delta = match dir {
                    Direction::Left => other.pos.col as i32 - item.pos.col as i32,
                    Direction::Right => item.pos.col as i32 - other.pos.col as i32,
                    Direction::Up => other.pos.row as i32 - item.pos.row as i32,
                    Direction::Down => item.pos.row as i32 - other.pos.row as i32,
                };
                if delta > 0 {
                    Some((i, delta))
                } else {
                    None
                }
            })
            .min_by_key(|(_, d)| *d);
        best.map(|(i, _)| i)
    }

    /// 可见项（用于虚拟列表裁剪）。
    pub fn visible_range(&self, row_start: u16, row_count: u16) -> Vec<usize> {
        let row_end = row_start.saturating_add(row_count);
        self.items
            .iter()
            .enumerate()
            .filter(|(_, item)| item.pos.row >= row_start && item.pos.row < row_end)
            .map(|(i, _)| i)
            .collect()
    }

    /// 元素总数。
    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(id: &str, row: u16, col: u16, region: &str) -> FocusItem {
        FocusItem {
            id: id.to_string(),
            pos: GridPos { row, col },
            region: region.to_string(),
        }
    }

    fn grid_3x3() -> FocusGrid {
        let mut g = FocusGrid::new();
        let mut items = Vec::new();
        for r in 0..3 {
            for c in 0..3 {
                items.push(item(&format!("{r}{c}"), r, c, "grid"));
            }
        }
        g.rebuild(items);
        g
    }

    #[test]
    fn moves_in_four_directions() {
        let mut g = grid_3x3();
        g.focus_by_id("11");
        assert!(g.move_focus(Direction::Up));
        assert_eq!(g.current_item().unwrap().id, "01");
        assert!(g.move_focus(Direction::Down));
        assert_eq!(g.current_item().unwrap().id, "11");
        assert!(g.move_focus(Direction::Left));
        assert_eq!(g.current_item().unwrap().id, "10");
        assert!(g.move_focus(Direction::Right));
        assert_eq!(g.current_item().unwrap().id, "11");
    }

    #[test]
    fn boundary_does_not_move() {
        let mut g = grid_3x3();
        g.focus_by_id("00");
        assert!(!g.move_focus(Direction::Up));
        assert!(!g.move_focus(Direction::Left));
        assert_eq!(g.current_item().unwrap().id, "00");
    }

    #[test]
    fn crossing_regions_at_edge() {
        // 侧边栏（col 0，region=side）在主区（col 1..，region=main）左边
        let mut g = FocusGrid::new();
        g.rebuild(vec![
            item("side0", 0, 0, "side"),
            item("main0", 0, 1, "main"),
            item("main1", 1, 1, "main"),
        ]);
        g.focus_by_id("main0");
        assert!(g.move_focus(Direction::Left));
        assert_eq!(g.current_item().unwrap().id, "side0");
    }

    #[test]
    fn empty_grid_is_safe() {
        let mut g = FocusGrid::new();
        assert!(!g.move_focus(Direction::Up));
        assert!(g.current().is_none());
    }

    #[test]
    fn rebuild_keeps_focus_if_valid() {
        let mut g = grid_3x3();
        g.focus_by_id("12");
        let mut items = Vec::new();
        for r in 0..3 {
            for c in 0..3 {
                items.push(item(&format!("{r}{c}"), r, c, "grid"));
            }
        }
        g.rebuild(items);
        assert_eq!(g.current_item().unwrap().id, "12");
    }

    #[test]
    fn rebuild_resets_focus_when_items_vanish() {
        let mut g = grid_3x3();
        g.focus_by_id("22");
        g.rebuild(vec![item("a", 0, 0, "grid")]);
        assert_eq!(g.current_item().unwrap().id, "a");
    }

    #[test]
    fn visible_range_filters_by_row() {
        let g = grid_3x3();
        let visible = g.visible_range(1, 1);
        assert_eq!(visible.len(), 3);
        assert!(visible.iter().all(|&i| g.items[i].pos.row == 1));
    }
}
