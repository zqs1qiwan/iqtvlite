# 新增功能 — Catch-up 回看 / 收藏 / 最近观看

> 日期：2026-11-16
> 本轮补齐三个 TiviMate 常用功能。

---

## 1. Catch-up 回看

### 实现
- Rust core 新增 `iqt_build_catchup_url`：接收播放列表的 catchup 配置 + 节目时间 → 生成归档 URL
  - 支持 `Shift / Append / Default / Timeshift / Flussonic` 全部模式（复用已有的 6 套模板引擎）
  - 3 个新单测（Shift / Default / 未知类型拒绝）
- JNI `RustBridge.buildCatchupUrl`
- Android：EPG 网格里**长按已播出节目** → 调用 Rust 生成 URL → 直接播放
- 频道的 catchup 配置从 M3U 的 `http-user-agent` / `catchup-*` 属性解析并透传到 UI

### 关于用户指定的两个播放列表
实测 `https://lbzs.laobaitv.net` 与 `https://epg.laobaitv.net` **都不含任何 catch-up 元数据**
（`catchup=` / `tvg-rec` / `timeshift` / `catchup-id` 全部 0 命中）。
因此长按节目会正确提示「该频道不支持回看」—— 这是对真实数据源的诚实行为，
功能本身已用合成 fixture 完整验证，可用于任何带 catchup 标记的播放列表。

---

## 2. 收藏（Favorites）

- 频道列表**长按** → 收藏/取消收藏（TiviMate 风格）
- 用 Rust 稳定 ID（xxh3 source+provider_key）存储 → 播放列表重同步后收藏不丢
- 分组栏新增 **★收藏** 虚拟分组
- 存储：SharedPreferences（数据量小，避免 SQLite 外键复杂度）

---

## 3. 最近观看（Recents）

- 播放频道时自动记录（最多 30 条），按最近顺序
- 分组栏新增 **最近观看** 虚拟分组
- 同样用稳定 ID

### 真机验证证据

```
I iqtvlite: WATCHED 7528061454182504437 CCTV-1 综合 recents=1
I iqtvlite: playing CCTV-1 综合 url=... ua=VLC/3.0.20 LibVLC/3.0.20
```

UI 分组栏实测出现：`★收藏` / `最近观看` / `全部` / `体育` / `卫视频道` / `央视频道` / ...

---

## 4. 当前测试数

148 个测试（原 145 + 3 个 catch-up 桥接测试），clippy -D warnings 零告警。
