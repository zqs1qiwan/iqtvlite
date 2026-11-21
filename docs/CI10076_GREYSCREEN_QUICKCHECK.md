# CI10076 灰屏修复真机快验（t33）

2026-11-19，acceptance2（t33 attempt 1）。对象：`tv.iqtvlite.app.validation` **10076 / 2.2.0-tivimini-983e153c-validation**（CI10076，TextureView 灰屏修复，run 44553646612 / source 983e153c）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。**结论先给：能出画 — 灰屏已修复**；首帧前为加载态(Compose 灰)属正常，首帧后立即显示真实画面，无持久灰屏；基础流畅度大幅改善（gfxinfo 50th 1050ms→23ms），但仍有源缓冲停顿与较高 CPU，未宣称“老机已完全流畅达标”（完整回归 t32）。

> 模型不能看图：灰屏/出画判断用 PIL 像素统计（均值/标准差/主色桶占比）+ PNG 体积旁证；截图保留供人工复核。

## 1. 产物身份与安装（PASS）

| 项 | 应用 | test |
|---|---|---|
| SHA256 | 5006e91d6bad682d5d8ef4dcdcd8630e59846389a9f16cd51575c927dc9f4a66 | 74e4a965cf7e66ac7c3b82dbe434ec2789198f0431be7f4bf5224cfe8adbf1be |
| 字节 | 14,159,123 | 502,955 |
| 包/版本 | tv.iqtvlite.app.validation / 10076 / 2.2.0-tivimini-983e153c-validation | …validation.test |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a | 24/35 |
| 签名SHA256 | 578bdb34f3c1804424267aa572266aa6087fb230a10fd2d1741d7aa02b910102 | 同左 |

本地 Get-FileHash/aapt2/apksigner 与交付一致；安装后 `pm path` 拉取 base.apk 复算 = `5006e91d…`（match）。流程：backup 旧 10075 沙箱（validation-10075-backup.tar 1,736,704B / test 2,560B）→ 仅 uninstall/install 两个 validation 包（Success）→ `dumpsys` = 10076 min24 target35 primaryCpuAbi armeabi-v7a。**原 tv.iqtvlite.app 与 com.cjlhll.iptv 及数据保留。** 证据：evidence/2026-11-19-ci-10076/（installed-app.apk 复算）。

首次冷启到 MainActivity（TotalTime 8557ms），因新装无源；经 LAN `POST /api/push`（http://192.168.2.245:28081/api/push 200 {"ok":true}）配公开源 https://iptv.laobaitv.net / EPG，转入 PlayerActivity 播放。

## 2. 冷启：首帧前灰 → 首帧后立即出画（PASS）

`am force-stop` 后 `am start -W SplashActivity`，按时间点截屏并 PIL 统计（首帧 `first_frame ttff_ms=3960`，CCTV1 1080p AVC `video/avc 1920x1080`）：

| 冷启 | t≈1s | t≈3s | t≈6s | t≈9s | 判定 |
|---|---|---|---|---|---|
| cold#1 mean RGB | **30,30,30（std0, 主色1.000）** | 107,79,65（主色0.047） | 149,149,145（0.035） | 141,80,42（0.018） | t1=加载灰，t3起=真实画面 |
| cold#2 mean RGB | 139,127,120（0.018） | 59,48,44（0.153） | 57,80,37（0.028） | 168,148,144（0.117） | 已出画（主色<0.16>多色内容） |

- **首帧前**瞬时为 Compose 背景灰（mean≈30，std0）= 加载态，正常；
- **首帧后立即显示真实画面**（均值多色、主色桶 0.02–0.15，PNG 体积显著大于纯灰），**无 10075 那种持久满屏灰**；
- 对照 10075：全程 mean 30/41/46、std≈0、主色桶 1.000 且首帧后仍灰 → 10076 已修复。
- 冷启 TotalTime 6659 / 6890 ms（本次源正在激烈缓冲：`rebuffers=8, rebuffer_ms≈12.5s`，见 §4），其慢主要来自**源/网络缓冲**而非灰屏；稳态首帧 ttff≈1–4s。

## 3. gfxinfo：卡顿大幅改善（对比 10075）

`dumpsys gfxinfo tv.iqtvlite.app.validation`（[gfxinfo-10076.txt](evidence/2026-11-19-ci-10076/gfxinfo-10076.txt)），reset 后播放窗口测：

| 指标 | CI10075 | CI10076 | 变化 |
|---|---|---|---|
| Total frames | 11 | 486 | — |
| Janky | 11 (100.00%) | 379 (77.98%) | 仍高（含源缓冲停顿） |
| 50th | 1050ms | **23ms** | ↓45×，接近 60fps |
| 90th | 2850ms | **32ms** | ↓89× |
| 95th | 3400ms | **36ms** | ↓94× |
| Missed Vsync | 9 | **2** | 明显改善 |
| Slow UI thread | 11 | 31（帧数基数大 486） | — |
| Frame deadline missed | — | 269/486 | 与源缓冲冻结相关 |

percentile 帧时已从“秒级”降到 ~16–36ms（接近流畅）；仍 77.98% janky 与 frame-deadline-missed 269 主要来自本窗口**源频繁 rebuffer（rebuffers=8、rebuffer_ms 12.5s）造成的冻结停顿**，非 UI 每帧卡死。**结论：UI/渲染流畅度显著改善；但“全程丝滑”受当前源缓冲限制，且未达原装基线（原装 50th 22ms/90th 40ms），需 t32 完整回归 + 更稳样本再定。**

## 4. SurfaceFlinger：被遮 SurfaceView 已消失（PASS）

[dumpsys SurfaceFlinger](evidence/2026-11-19-ci-10076/sf-full-10076.txt)，validation 图层（解析 [layers-z-opaque.txt 同法](evidence/2026-11-19-ci-10076/sf-full-10076.txt)）：

| CI10075（修复前） | CI10076（修复后） |
|---|---|
| `SurfaceView - ...PlayerActivity#0` **z=-2 isOpaque=1**（窗口之下被遮） | **无该 SurfaceView 图层** |
| App 窗口层 z=0 | App 窗口层 z=0/1；另有 `1920x1080 isOpaque=1` 图层 z=0（TextureView 在窗口内合成） |

`--list` 中已无 `SurfaceView - ...PlayerActivity#0`；视频经 **TextureView 在视图层级内绘制**，不再有位于窗口之后(z=-2)的独立被遮图层 → 遮挡根因消除。“退出销毁窗口才闪一帧”的机制随该图层消失而**结构性消除**（见 §6 未验证说明）。

## 5. CPU / PSS

- PSS：`dumpsys meminfo` TOTAL **72053 KiB（≈70.4 MiB）**，SwapPSS 0。
- CPU：`/proc/<pid>/stat` 1s 差分 utime 12441→12510、stime 2023→2039 = 85 ticks/s = **≈0.85 核 ≈ 85% 单核**（10075 曾 ≈20%）。**TextureView 走 GPU/额外拷贝，较 SurfaceView 更耗 CPU/带宽**，叠加当前 1080p AVC 解码与激烈 rebuffer；这是修复灰屏的代价，需在“老机流畅”目标下继续优化（限码率/避 4K HEVC/必要时原生基座方案）。

## 6. 简短 TIV 冒烟（PASS，可正常观看）

- LEFT 开抽屉：双列（频道+节目单）显示在视频之上（tiv-drawer mean 116/114/114、主色0.133；UI dump 见频道列表 CCTV-1..5）→ 可正常观看+导航。
- OK：选中/切换（tiv-ok 主色0.147，视频仍在）。
- BACK：关闭抽屉回视频（tiv-back 主色0.048，真实画面）。
- 退出无闪帧：`exit-before`=视频(0.026)；连拍 BACK 后 exitf-1..5 均多色内容(0.036–0.06)，**未见纯灰/闪帧**。但该 app 需“双 Back 退出”(TIV-14)，快验窗口内 BACK 未真正 finish（前台仍 PlayerActivity），且 16ms 级 teardown 闪帧无法被 ~0.5s 截图捕捉 → **“退出无闪帧”标 未完全验证（建议人工目视）**；机制上已因无独立被遮 SurfaceView 而消除。

## 7. 通过 / 失败 / 未验证 汇总

- PASS：身份/安装/backup/保留原包数据；**冷启出画（首帧后立即可见，无持久灰）**；SurfaceFlinger 无被遮 z=-2 SurfaceView；gfxinfo 50th/90th/95th 大幅改善；TIV 冒烟（抽屉/OK/BACK/可观看）。
- 改善但未达标：jank 率仍 77.98%、frame deadline missed 269（主因源 rebuffer）；CPU 85% 单核（TextureView 代价）。
- 未验证/保留：退出“无闪帧”未用截图捕捉到亚帧级（建议人工目视）；完整 24 TIV / SR / 长播性能 / 音频 归 t32 完整回归；MP2 音频(无声)与本任务无关仍遗留。
- 设备侧：本窗口源 (iptv.laobaitv.net) 当前 rebuffer 频繁（rewbuffers 8 / rebuffer_ms 12.5s），影响“流畅”观感，属源/网络，非本次修复回退。

## 8. 给用户的结论

**能看到画面了**：CI10076 用 TextureView 修复后，冷启约在首帧(1–4s 加载)后即显示真实直播画面，不再整屏纯灰、不再“退出才闪”。操作（开抽屉/选台/关闭）可正常观看。基础帧时已从秒级降到 ~23–36ms。**但仍非“完全流畅”**：当前源缓冲导致停顿、TextureView CPU 偏高、jank 率仍高；若要求接近原装流畅，建议按 t30 提出的原生基座/精简 Compose/限码率方案评估。完整回归见 t32。
