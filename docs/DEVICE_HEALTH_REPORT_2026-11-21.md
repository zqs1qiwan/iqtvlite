# 设备健康体检报告 — 2026-11-21 14:2x

执行人：acceptance2（t229）。**只读体检，未安装、未卸载、未 force-stop、未跑性能。**
依据：`docs/DEVICE_HEALTH_PROTOCOL.md`。

## 0. 结论（先行）

| 项目 | 结果 |
|---|---|
| 存储（/data） | **健康**：3.2G 总，646–665M 已用，**可用 2.5G（2,655,636 KB）**，远超 500MB 红线 |
| 存储（/cache） | 健康：742M 可用（1% 用） |
| 存储（/sdcard） | 健康：2.2G 可用（25% 用） |
| 内存/负载 | 健康：free ~1.0GB；loadavg 13.35→3.14（4 核，短时偏高）；CPU 326% idle |
| 日志异常计数（main -t 200） | `storaged=0`、`installd=0`、`FATAL=0`、`ext4=0`、`I/O error=0`、`DeadObject=0` |
| crash buffer | 2 条 FATAL，**均属系统 `com.android.system.optimizer`**（ClassNotFound，非我方），不阻断 |
| boot | `sys.boot_completed=1`；**`ro.boot.bootreason=reboot,adb`**（adb 触发，非 recovery/fsck）；**uptime 314s→375s（刚重启）** |
| 文件系统损坏迹象 | **未发现**（无 ext4/I/O error/recovery 痕迹；`/cache/recovery` 空、`/data/tombstones` 空） |
| **我方包完整性** | **❌ 异常：4 个包只剩 2 个**（详见 §2） |
| MiTV（Android 14）通道 | **不可用**：`127.0.0.1:15555` → `connection refused` |
| 第三方干扰 | **`com.shafa.market`（沙发管家）`xg_service_v2` 崩溃重启 16 次/800 事件**；且 14:22 其 `AppUninstallManagerAct` 运行 |

**判定：存储/文件系统“健康”，但设备刚重启、第三方卸载器已改动我方包 → 按协议 §4 立即停止安装与测试并上报。**

## 1. 存储与资源（原始）
```
/dev/block/mmcblk0p23 3.2G 646M 2.5G 20% /data
/dev/block/mmcblk0p19 767M 1.1M 742M 1% /cache
/data/media            3.0G 749M 2.2G 25% /storage/emulated
df -k /data → 3403720  661208  2655636  20%
/proc/uptime → 314.86 850.77（后续 375.54）   loadavg 13.35 7.49 3.14
Mem: 2093056k total, 1084996k used, 1008060k free
```
我方包占用（重启前实测）：
- `/data/data/tv.iqtvlite.app` **468K**
- `/data/data/tv.iqtvlite.app.validation` **1.9M**
- `/data/data/com.cjlhll.iptv` **2.2M**
APK：`tv.iqtvlite.app` 13,140,066B（11-18）、`validation` 13,076,433B、`com.cjlhll.iptv` 22,910,016B（11-18）。

## 2. ⚠️ 严重：我方两个包在 14:22 被第三方卸载器移除

**14:16（首次体检时）** `pm list packages` 仍有 4 个包：
```
package:tv.iqtvlite.app
package:tv.iqtvlite.app.validation.test
package:com.cjlhll.iptv          ← 用户原始生产包
package:tv.iqtvlite.app.validation
```
**14:22 之后（重启完成后）** 只剩 2 个：
```
package:tv.iqtvlite.app
package:tv.iqtvlite.app.validation.test   ← 孤立 test 包
```
`pm list packages -u` 同样只剩这两个 → **`com.cjlhll.iptv` 与 `tv.iqtvlite.app.validation` 已被彻底卸载**（`/data/data/...` 目录消失、`/data/app/*cjlhll*` 与 `*validation*` 目录消失、无残留 APK）。

**时间线证据（`logcat -b events`）**：
```
14:22:14.881  am_on_paused_called:  [0,com.shafa.market.AppUninstallManagerAct,performPause]
14:22:15.538  am_destroy_activity:  [0,...,com.shafa.market/.AppUninstallManagerAct,finish-imm:...]
14:22:15.558  am_on_stop_called:    [0,com.shafa.market.AppUninstallManagerAct,LIFECYCLER_STOP_ACTIVITY]
14:22:38.061  am_pss: [...,com.android.packageinstaller,...]
14:22:39.090  am_create_activity: com.android.packageinstaller/.permission.ui.GrantPermissionsActivity
```
→ **沙发管家（`com.shafa.market`）的“应用卸载管理”界面被打开并执行了卸载**；我方从未对 `com.cjlhll.iptv` 执行过任何卸载命令（历史命令仅 `uninstall tv.iqtvlite.app.validation` 与其 `.test`）。

**影响**：
1. **用户原始生产包 `com.cjlhll.iptv` 丢失**（22.9MB APK + 2.2MB 数据），我方 PC 缓存中**没有该 APK 备份**（`local/ci-delivery` 仅存 validation 系列），**当前无法从本机恢复**。
2. 验证包 `tv.iqtvlite.app.validation`（10134）也被卸载，t227 的全部测试前提消失。
3. 孤立包 `tv.iqtvlite.app.validation.test` 仍在（无对应 app）。

**恢复需求（需用户/现场）**：
- 从原始来源重新安装 `com.cjlhll.iptv`（用户或原构建产物）；
- 如需继续验收，重新安装 validation（`pm install -r`，装完即体检）；
- 在系统设置中关闭沙发管家的“自动清理/卸载”，或临时停用其自动清理功能（**需用户授权，我方不自行禁用第三方应用**）。

## 3. 其它观察
- **设备刚重启**（uptime 314s，bootreason `reboot,adb`）：与用户反馈“WiFi 卡死/进 recovery”吻合的是**失联现象**；本次未见 recovery/fsck 痕迹（可能在重启中已被轮转清除）。
- **反复失联史**：t219（约 10 分钟）、t222（12:29 起抖动）、t224（>33 分钟不可达）、本次 14:20 前后再次 offline→重启。建议检查供电与 WiFi 省电（`settings put global wifi_sleep_policy 2`，需用户授权）。
- **`com.shafa.market:xg_service_v2` 崩溃重启 16 次/800 事件**（TPush 服务），持续占用 CPU；属第三方应用自身缺陷，仅记录。
- **遗留文件**：
  - `/sdcard/laobaitv.m3u`（30,218B，我方测试播放列表）——重启后已不存在；
  - `/sdcard/Download` 无 APK；
  - `/data/local/tmp` 下有 `.apkt/.bt1688/.rivalkill/.st.sh` 等 **shell 属主**目录（11:31，非我方创建，疑似其它工具/脚本）与 `misc.bak`（root，4KB）——**仅记录，未删除**。

## 4. 本次是否执行清理
**未执行任何删除**（只读体检）。PC 侧缓存清理建议（我方文件）：
- 保留：`iqtvlite-ci-10134-validation.apk`（最新 pair）+ `iqtvlite-ci-10130-validation.apk`（回滚）；
- 可删：10129/10131 及其 `androidTest`、旧 backup tar/logcat（待 captain 确认后执行）。

## 5. 下一步（门禁）
1. **暂停一切安装/性能测试**，直到用户确认 `com.cjlhll.iptv` 恢复方案；
2. 现场确认：设备供电/散热、WiFi 省电已关、沙发管家自动清理已关；
3. 恢复后按协议 §1 重新体检（活包 4 个齐全、/data≥500MB、log 无 storaged/installd/DeadObject、uptime>300s）；
4. 体检通过才允许 `pm install -r` 安装 validation 并继续 t227（OSD/看门狗/睛彩）。
