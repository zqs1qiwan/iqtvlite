# FINAL 真机验收报告（CI213 / vc10213）· TR100-G9

- 时间：2026-11-22 20:37–21:12（Asia/Hong_Kong）
- 执行：acceptance（t13 attempt 1）
- 设备：TR100-G9 / Android 9 / API28 / 1080p / 240dpi / `192.168.2.245:5555`
- 包：CI run **#213**（id `45022475298`，head `b071f376067ef7047e94d9cb75aea991b9dd43e0`）/ versionCode **10213** / `2.2.0-tivimini-b071f376-validation` / app sha256 `4163f1a0d583a4ce59cb18fae7a74ccb6ea2038170bb941ec21aa917254eb689` / 新稳定签名 `9e005515…baa23`
- 源码修复范围：`9105aa8`（自定义键 dispatch 通用 fallback）+ `b071f37`（U5：播放台▶标记/信息卡节目不过滤/EPG空态三分类）
- 证据目录：`docs/evidence/t7-fullfix/`（213-* 截图 6 张）
- 报告目的：复测上一轮（t7）遗留的 B1 缺口、B2 三项，并对 5 项 UI 与既有回归做最终确认

---

## 0. 结论

| 区块 | 结论 |
|---|---|
| 安装/数据备份恢复/交付态 | ✅（备份 data 1,089,024B→仅卸 validation 一次→装 vc10213→恢复 data 成功；sha256 校验一致；force-stop 0） |
| ① B1 自定义键切台 | ✅ **通过**（F1 绑定后实际切台；恢复默认无回归；单次按下不双触发） |
| ② B2 三项 | ✅ 播放台▶标记通过 / ✅ 信息卡节目不污染通过 / ⚠️ EPG 空态三分类部分（正常态+代码/单测覆盖，失败/未配置态真机未独立构造） |
| ③ 5 项 UI 回归 | ✅ 全过（选台丰富行/不回开头/半透明/信息卡排版/U1 最近观看） |
| ④ 既有回归 | ✅ S1 小分组、数字OSD、上一台、清空、台标缓存、自动更新、不崩不灰通过；MP2 听感未验证（无音频采集） |

---

## 1. 安装与交付态

| 项 | 结果 |
|---|---|
| 装前只读体检 | boot=1，uptime=15693s，bootreason=`reboot,adb`，`/data` 2.59G 可用(22%)，installd/storaged 存活，DeadObject/ext4/I-O/installd/storaged 日志 0 |
| 数据备份 | ✅ `run-as tar` 打包 validation data → `validation-data-vc10211.tar` **1,089,024B**，经 `exec-out` 拉取核字节一致 |
| 签名冲突处理 | 新签名 `9e005515…` ≠ 旧 `3764e63a…`（vc211）→ 按队长协议例外：备份后**仅卸 `tv.iqtvlite.app.validation` 一次**，不碰其他包 |
| 安装 | ✅ `install` Success；装后 `versionCode=10213` / `versionName=2.2.0-tivimini-b071f376-validation` |
| 装后 sha256 | ✅ 拉取已装 base.apk → `4163f1a0…eb689`，与 CI 完全一致 |
| 数据恢复 | ✅ 备份 tar 回灌，`live_source`/`epg_source`/`remote_action_bindings_v1` 等 prefs 完整恢复；App 直接进 PlayerActivity 并恢复上次频道 |
| force-stop | **0 次** |
| 收尾态 | 前台 `...PlayerActivity`；`iqtv_*` 实验键 0 项；debug log.tag 全部复位空；buffer 无覆盖（稳500 默认）；Fatal/IllegalArgument 我方包 0；`/data` 2.58G(23%) |

---

## 2. ① B1 自定义键切台（上一轮缺口，本轮重点）✅

| 步骤 | 证据 | 判定 |
|---|---|---|
| 设置 → 遥控器与操作 →「下一个频道」短按绑定 | 进入捕获态 `下一个频道：短[请按键…]` | ✅ |
| 按 F1(131) | Prefs：`a=CHANNEL_DOWN,s=20|93|131|167`，UI：`短[↓/93/键131/167]` | ✅ 写回正确 |
| 退回播放器按 F1 | `last_channel_title`：`美妆` → `美人`（下一台）；再按一次 `美人` → `精选`（单次单步，无双触发） | ✅ **实际切台生效** |
| 恢复默认按键映射 | Prefs 回到 `s=20|93|167`（F1 移除），默认行为无回归 | ✅ |
| 固定键（DOWN=20）切台 | CCTV-6→CCTV-7 正常 | ✅ |

> 上一轮 t7 报告的「dispatchKeyEvent 无通用 fallback 导致自定义键不触发」缺口，经 `9105aa8` 修复后**本轮真机确认已解决**。截图 `213-01-B1-f1switch.png`。

---

## 3. ② B2 三项（复审修复）✅/⚠️

### B2① 播放台 ▶ 标记 ✅
- 代码：`DrawerUiPolicy.channelLabel(selected,…)` = 播放台加 `▶ ` 前缀；副标题末尾 ` · 播放中`（`PlayerDrawer.kt:1294-1298`）。
- 真机：播放 `精选` 时，抽屉该行显示 **`▶ 127. 精选`** 且副标题 **`无信息 · 播放中`**；同时 `row_focus idx=132` 表明浏览焦点在另一行（idx132 ≠ 播放行127）——**播放标记与浏览焦点（surfaceVariant 底色）正交可辨** ✅。
- 最近观看弹层亦见 `▶ 124. 地理`（播放台标记）✅。
- 截图 `213-02-B2-playmarker.png`。

### B2② 信息卡节目不被浏览分组污染 ✅
- 播放 CCTV-15（节目 `第二届丝路之星国际音乐大赛-10`），记录信息卡。
- 在抽屉把浏览组切到 `卫视频道`（`drawer_open … group=卫视频道`）后关抽屉再看信息卡：仍为 **`CCTV-15 音乐` / `频道 15 · 央视频道` / `第二届丝路之星国际音乐大赛-10`**，未变空、未跳变 ✅。
- 代码：`nowProgramByUrl` 已不按 `selectedGroup` 过滤（本轮修复点）；`epgDataChannel` 取实际播放台。

### B2③ EPG 空态三分类 ⚠️ 部分
- 代码：`DrawerUiPolicy.programEmptyText(configured, loadFailed, loading)` → 加载中/未配置 EPG 源/EPG 加载失败/暂无节目单（`DrawerUiPolicy.kt:21-30`），`PlayerDrawer.kt:1098-1100` 已接线；Activity `epgSourceConfigured=Prefs.getEpgSource().isNotBlank()`、`epgLoadFailed=cachedEpg==null`（:737-738/:1254）。CI #213 JVM 单测覆盖该纯函数。
- 真机：EPG 正常态有节目（`新闻联播` 等）；无 EPG 源频道信息卡显示 `暂无节目信息`；节目单列可见正常节目。
- **未独立构造**「未配置 EPG 源」「EPG 加载失败」两态：清空 EPG pref 需 Activity 重建（协议禁止 force-stop），LAN `/api/push` 空 epgSource 被应用忽略；断网失败场景未注入。按用户要求如实标注**未验证**（代码+单测已覆盖）。
- 截图 `213-03-B2-epg-empty.png`。

---

## 4. ③ 5 项 UI 回归 ✅

| 项 | 证据 |
|---|---|
| ①选台 UI 丰富行 | 抽屉每频道行「台标+N.台名+节目副标题」，`row_focus idx=20 subtitle=true title=金鹰独播剧场…`；播放台额外 ▶ 标记；行高 64dp |
| ②不回开头（按组记忆焦点） | 浏览至 idx=20 → BACK 关 → LEFT 重开 → DOWN 探针 `row_focus idx=1 via=key down`（说明重开焦点仍在 idx=20，DOWN 后到 21）；`drawer_open … group=全部` |
| ③半透明背景 | `DRAWER_OPAQUE_CONTAINER=false` + alpha=0.92 静态背景 layer（代码在位，上一轮 t7 已像素采样确认观感一致）；本轮无灰屏、视频正常透出 |
| ④信息卡排版 | 台标下 `频道 15 · 央视频道`（无 MPEG-L2）；徽标 `FHD·2ch`；技术行 `H.264·MPEG-L2·SDR`；无双重 padding |
| ⑤U1 最近观看 | `move index=1` → **`switch idx=1 group=126 美人`**，`last_channel_title` CCTV-15→美人（第 2 张卡），精确 OK→OK→右→OK 通过 ✅（截图 `213-04-U1-switch.png`、日志曾捕获该两行） |

---

## 5. ④ 既有回归

| 项 | 结果 | 证据 |
|---|---|---|
| S1 小分组 | ✅ | `山东频道` 1 行：`drawer_open total=1 … group=山东频道`，无崩溃；BACK 回 PlayerActivity（日志 `213-05-S1.log`） |
| 数字 OSD | ✅ | 输入 `1`,`0` + OK → 切到 `CCTV-12 社会与法`（第 10 组）；无残留 |
| 上一台 | ✅ | 229 → 切回 `地理` |
| 清空最近观看 | ✅ | `IQTV-RECENT: cleared=true count=0`，prefs `recent_channels_cleared=true`、`recent_channels_v1` 移除 |
| 台标缓存 | ✅ | 频道行台标正常显示；设置 `台标与缓存 96 条`；logo_cache_v1 目录在位 |
| 自动更新 | ✅ | 设置 `列表 启动时 · EPG 启动时`；`playlist_fail_count=0`/`epg_fail_count=0`；`playlist_last_success_at`/`last_epg_update_time` 更新 |
| MP2 有声 | ⚠️ 未验证听感 | 音轨 `mpeg-L2·2ch`、技术行 `H.264·MPEG-L2·SDR` 正确；无音频采集手段 |
| 不崩不灰 | ✅ | 全程我方包 Fatal/IllegalArgument 0；SurfaceView 视频正常，无灰屏/花屏 |

---

## 6. 交付态复核（收尾）

- 前台：`tv.iqtvlite.app.validation/...PlayerActivity` ✅
- `iqtv_*` 实验键：**0 项** ✅
- `log.tag.IQTV-*` 调试开关：全部复位为空 ✅
- 起播缓冲：无 prefs 覆盖，默认 `稳(500ms)`（buffer_min=500）✅
- Fatal / IllegalArgumentException（我方包）：**0** ✅
- `/data`：2,576,360 KB 可用（23%），DeadObject/ext4/I-O/installd 0 ✅
- force-stop：**0**；安装/卸载：备份 data + 仅卸 validation 一次 + 装 vc10213 + 数据恢复 ✅
- 其他包未动（tv.iqtvlite.app / .validation.test 全程在位）

---

## 7. 待补验 / 已知限制

1. B2③ EPG「未配置」「加载失败」两态真机未独立构造（协议禁 force-stop、LAN 空源被忽略、未注入断网）；代码与 JVM 单测已覆盖。
2. MP2 听感未验证（无音频采集），声道/编码显示正确。
3. t7 报告中的 S2/S3/S5/U2/U4 强时序/回看/多线路场景仍沿用其「未验证」结论（本轮未重复构造）。
