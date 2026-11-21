# IQTV Lite 交付总结（2026-11-22）

> 仓库：https://github.com/zqs1qiwan/iqtvlite ｜ 分支：`integration/tivimini-baseline`
> 最终包：CI run #187，versionCode **10187**，`2.2.0-tivimini-1f206133-validation`
> 主包 sha256 `bfefb8cfb28204a88c7d228bbd9367eea08a3c4dc8117bb9675ca52ec7b7dabf`，**13,191,121 B（≈12.6MB，≪30MB 门禁）**
> 本地 APK：`delivery-apks/iqtvlite-ci-10187-validation.apk`（回滚：`iqtvlite-ci-10130-validation.apk`）
> 真机最终复验：**可交付**（docs/CI_FINAL_PACKAGE_VERIFY.md）；`main` 未动、未打 tag

## 一、本目标（AgentTeams 第五轮）交付了什么

| 需求 | 状态 | 证据 |
|---|---|---|
| 设置合并为二级、不全部铺开 | ✅ | 一级 6 入口带摘要；二级 4/6/1/6/4/1 行；Back/Left 逐级；24 项零丢失 |
| 遥控习惯设置 | ✅ | 上下/OK/Back/数字/频道键可配，**免暂停即时生效** |
| 数字换台可见、不黑 | ✅ | 3s OSD、需-OK 模式、遮罩不残留、DIRECT 自动提交 |
| 自动更新播放列表/EPG | ✅ | 单执行者、失败退避、手动单次刷新、保存源单路径、取消不触发 |
| 源音频/声道/分辨率显示 | ✅ | 信息卡徽标 `FHD·25fps·8.3Mbps·2ch` + 技术行 `H.264·MPEG-L2·SDR` |
| 快速返回上一台 / 最近观看 | ✅ | 229 上一台；最近观看 ≤10、去重、清空重启仍空；信息卡底部快跳 |
| 台标缓存（重复加载/慢半拍） | ✅ | 预取分层 + 内存/磁盘 LRU；二次启动 `dec=0 hit=153+`；预取/显示开关、刷新当前组、清理（memory:0 disk:0） |
| 信息卡（logo/台名突出 + 最近观看快跳） | ✅ | 160×96dp logo + 34sp 台名同行；右侧节目+剩余+进度；右上 4 徽标；底部 ≤6 快跳；300dp 高 |
| 菜单/换台卡顿 | ⚠ 大幅改善（见下） | 抽屉 UI 中位 41.1→22.6ms、设置滚动 82.78%→18.4% |
| 睛彩 117/118/119 | ⚠ 未修复（定位完成） | 源无 PCR、间歇；见第五节 |

## 二、九轮抽屉 jank 优化净收益（10145 → 10179/10187）

| 指标 | 起点 | 最终 |
|---|---|---|
| 抽屉静止产帧 | 持续无限（跑马灯） | **0** |
| UI 线程中位 | 41.1ms | **22.1ms** |
| Draw 中位 | — | **5.0ms** |
| 50th | 46–48ms | **57 / 53ms** |
| ≥300ms 巨帧 | 6 | **1**（315.9ms） |
| 菜单滚动跟随 | 完全不动 | **稳定**（DOWN×25 / 快按全跟随） |
| 聚焦行节目 | 每行都有 | **仅聚焦行**（信息不丢、成本可控） |
| 设置滚动 | 91.67% / 24ms | **18.4% / 9ms（达标）** |

**已知限制（如实）**：剩 1 个 ≥300ms 巨帧 / 50th 57ms 是单行组合成本 12–15ms 的物理上限；
九轮证明「巨帧 0 且 50th ≤50」不可同时满足。真达标需换渲染方案（自绘虚拟列表/整行预渲染），建议单独立项。

## 三、功能与观感

- 观感：tivimini 原版双列 633dp + 去圆角 Flat；焦点无蓝框（浅底+字重）；设置/信息卡均 0dp 无硬边框。
- 播放：零拷贝 SurfaceView 基座、Media3 硬解、**MP2 软解**（FFmpeg 音频扩展，LGPL NOTICE 随包）。
- 起播缓冲三档（快300/均衡400/**稳500 默认**，实测稳档最优）。
- 崩溃热修：台标 **WEBP 写盘 SIGSEGV**（首次安装必崩）→ 改 PNG 写盘 + 防御写，真机 5/5 通过。
- 信息卡焦点：F1（聚焦丢失后 BACK 误退出）、F2（MENU→设置焦点被清）已修并真机验证。

## 四、性能数据（同机 TR100-G9 / Android 9）

- TTFF p50 **957–963ms**、p95 1466–1700ms（fmt 网络首包占 ~94%）。
- 冷启首帧 **1673–2087ms**（基线 3197ms）；首帧后启动 rebuffer 3.8–4.3s（源侧/网络瞬时吞吐）。
- PSS 播放稳态 **60–77MB**（口径敏感，不作回归判据）。
- CPU：主线程 21.8% 为最大项；Loader 12.5%。

## 五、睛彩 117/118/119：定位结论（未修复）

**已排除**：源不可达（设备 curl 8.4MB/8s、码流 H.264 Main@4.0 progressive、IDR/SPS/PPS 齐全）、解码器不支持（同机 CCTV 正常）、我们 extractor（format 已解析）。

**根因证据**：失败样本 `pcr=none / pcr_pid=-1`，`bufferMs=0 / currentPosition=0 / windowCount=1 / state=2`，已读 8.4MB；
A/B 实验（丢 PCR-only、改 PMT PCR_PID）在无 PCR 流上**均为空操作**；同一 B 模式前后结果相反 → **间歇性**。

**判定**：更像源侧/组播加入时序 + 解复用器建不出时间轴。**下一步方向**：无 PCR 时改用 **PTS-only 时钟**（ignorePcr/timestampAdjuster），实验须同频道 3 次取多数。A/B/AUTO 开关已随包提供（默认关，偏差>60s 才自动启用）。

## 六、设备健康与操作规范

- 全程遵守 `docs/DEVICE_HEALTH_PROTOCOL.md`：先只读体检、只 `pm install -r`、签名冲突时备份后仅卸 validation（累计 ≤3 次）、force-stop 0、每步查 `/data` 与 `storaged/installd/DeadObject`。
- 曾因高频装卸导致设备 WiFi 卡死/进 recovery → 已改为“单次安装 + 合并验收”。

## 七、CI 与签名

- 新增稳定签名方案（4 个 secrets：`VALIDATION_KEYSTORE_BASE64/PASSWORD`、`VALIDATION_KEY_ALIAS/PASSWORD`）；配置后 validation 包跨 run 同签名，设备可一直 `install -r`、不再卸载。
- 未配置时仍每 run 临时 debug 签名（需一次卸载）。生成脚本：`scripts/gen-validation-keystore.ps1`；文档 `docs/CI_STABLE_SIGNING.md`。
- 门禁：APK ≤30MB、pair gate、dex contract、DEX 契约、`check_player_threading / check_refresh_paths / check_settings_scroll / check_info_card / check_logo_webp / check_perf_policy / check_settings_ia / check_jingcai_timeline`。

## 八、回退方式（一行级）

| 项 | 回退 |
|---|---|
| 起播缓冲 | 设置 → 播放与画面 → 起播缓冲（快/均衡/稳） |
| 抽屉聚焦副标题 | `PerfPolicy.DRAWER_FOCUSED_SUBTITLE=false` |
| 抽屉打开窗口 | `PerfPolicy.DRAWER_OPEN_WINDOW_ROWS`（28 为最佳） |
| 睛彩实验 | 设置 → 播放与画面 → 睛彩诊断实验 → 关闭 |
| 冷启实验 | `settings delete global iqtv_*` |
| 遥控 OK 默认 | `DEFAULT.ok=OPEN_DRAWER` |

## 九、建议的后续立项

1. **自绘虚拟列表/整行预渲染**：冲抽屉 50th ≤16ms 的唯一路径。
2. **睛彩 PTS-only 时钟**：验证无 PCR 流的可播性。
3. **冷启首秒吞吐**：属源/网络侧，可评估“首帧后缓冲提示”体验优化。
4. 多音轨真机验证（需多音轨测试源）。
