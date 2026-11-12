# iqtvlite — 核心层实现状态

> 本目录是**纯 Rust 领域层**，零 Android 依赖。
> 这条约束让 90% 的业务逻辑可以在 PC 上 `cargo test` 完整验证 —— 不需要设备、不需要模拟器、不需要 Gradle。

---

## 状态

| Crate | 内容 | 状态 | 测试 |
|---|---|---|---|
| `iqtvlite-core` | 领域模型 / M3U / XMLTV / Xtream / Catch-up 模板 / 稳定 ID / 时间 | ✅ | 71 |
| `iqtvlite-store` | SQLite schema + 迁移 + FTS5 | ✅ | 3 |
| `iqtvlite-epg` | 频道↔指南 匹配算法 | ✅ | 10 |
| `iqtvlite-net` | reqwest 客户端 / UA 管理 / 连接池保温 | ✅ | 4 |
| `iqtvlite-player` | Player trait / 回退状态机 / 故障自愈决策 | ✅ | 13 |
| `iqtvlite-tvfocus` | 自研 TV 空间焦点导航 | ✅ | 7 |
| `iqtvlite-tests` | 真实形态 fixture 的集成测试 | ✅ | 19 |

**合计 127 个测试，clippy `-D warnings` 零告警。**

---

## 关键实现决策（全部来自 `docs/` 的调研结论）

### `iqtvlite-core`

| 模块 | 决策 | 出处 |
|---|---|---|
| `parse::m3u` | 显示名取**最后一个逗号**之后；逗号必须在引号外找 | `09-protocols.md` §1.1 |
| | 属性支持 `"..."` / `'...'` / 裸值三种形态 | §1.1 |
| | `#EXTGRP:` 是「开始指令」（Kodi 语义），`group-title` 覆盖它 | §1.1 |
| | **未知属性必须保留**在 `extras` | §1.1 |
| | `#EXTVLCOPT:` / `#EXTHTTP:` 提升为真实 HTTP 头 | §1.2 |
| | 连续 URL = 同一频道的**备用线路**；裸 URL 列表 = 独立条目 | §1.1 |
| `parse::xmltv` | **流式**解析（文件常达 50–200 MB） | §4 |
| | 捕获 `<credits>` 子树原文 | §4.1 |
| | `clumpidx` / `catchup-id` 是 `<programme>` 的**属性**（非 DTD 但真实存在） | §4.1 / §4.3 |
| `parse::xtream` | **`title`/`description` 是 base64**（XMLTV 才是明文） | §0 |
| | 数字与字符串**容错反序列化**（面板不一致） | §3.4 |
| | 分类支持对象数组与数组套数组两种形态 | §3.4 |
| | `get_short_episodes` / `get_episodes` **不存在** → 用 `get_series_info` | §0 |
| `model::catchup` | 6 套模板语法（含 JioTV `{{…}}`、中国移动 `${(b)…}`） | §2.5 |
| | 时移起点用**供应商本地墙钟时间**（IANA 时区） | §2.6 |
| | 请求时长 **+5 分钟**（归档滞后 30 s ~ 2 min） | §2.6 |
| `id` | `xxh3_64(source_id ‖ 0x1F ‖ provider_key)`，掩到 63 位 | `04-data-model.md` §2 |
| `timeutil` | XMLTV 时间严格解析（4/6/8/10/12/14 位、24:00:00、时区 token） | §4.2 |

### `iqtvlite-epg::matcher`

**「有 id 的频道绝不用名字匹配」** —— 否则会跨国家串台（better-iptv 的实战教训）。
重名的指南频道**丢弃并计数，绝不随便挑一个**。

### `iqtvlite-player`

决策与执行分离 → 回退逻辑可以写几百个纯单测，不需要设备。

---

## 本地开发

```powershell
. .\scripts\dev-env.ps1     # 设置 PATH（cargo + MinGW）
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all
```

### 工具链说明（本机现状）

- **没有 Visual Studio**（`link.exe` 缺失）→ 用 MinGW-w64 + `x86_64-pc-windows-gnu` 工具链。
- **没有 WSL** → Android NDK 交叉编译需另装 SDK/NDK；libmpv AAR 建议在 Linux 上构建。
- `rustup override set stable-x86_64-pc-windows-gnu` 已在本项目生效。

---

## 下一步（按 `docs/07-roadmap.md`）

1. **M0 spike**：Slint + Android TV 链路、Surface 合成方案（P1/P2）、中文字体、媒体按键
2. **M1 收尾**：`iqtvlite-epg` 的同步调度 + 网格查询 API、`iqtvlite-store` 的读写 API
3. **M2**：`iqtvlite-capi`（C-ABI）+ Kotlin 胶水层
