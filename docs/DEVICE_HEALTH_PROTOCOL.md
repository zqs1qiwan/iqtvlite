# 测试设备健康协议（DEVICE HEALTH PROTOCOL）

**背景**：测试机曾因我方反复安装/卸载/force-stop/备份/大量 logcat 出现 WiFi 卡死、文件系统损坏进 recovery。本协议为**强制前置门禁**：体检未过 → 不做任何写操作。

**设备**：
- TR100-G9（Android 9 / API28 / armeabi-v7a / 1080p）— `192.168.2.245:5555`
- MiTV-MFTR0（Android 14 / MT9653）— 经 `127.0.0.1:15555`（**不保证在线**）
- 两台性能不可横向对比。

---

## 1. 只读体检（每次动设备前必跑，串行、一次一步）

| # | 命令 | 阈值 / 判定 |
|---|---|---|
| 1 | `adb -s <serial> get-state` + `adb devices` | 必须 `device`；`offline/unauthorized` → 停 |
| 2 | `getprop sys.boot_completed` | 必须 `1`；否则停 |
| 3 | `df -k /data /cache /storage/emulated` | **/data 可用 < 512000 KB（500MB）→ 立即停止一切写操作并上报**；/cache < 100MB 告警 |
| 4 | `cat /proc/uptime` + `getprop ro.boot.bootreason` | 记录；`uptime < 300s` 视为“刚重启”，冷却 5 分钟再操作；bootreason 含 `recovery`/`fsck` → 停 |
| 5 | `dumpsys meminfo \| head -12` + `cat /proc/loadavg` | loadavg 1min > 2×核数 → 冷却；mem free < 150MB 告警 |
| 6 | `logcat -d -b main -t 200` 计数 | `storaged/installd/FATAL/ext4/I/O error/DeadObject` 任一 **>0 → 停并上报** |
| 7 | `logcat -d -b crash -t 100` | 仅当 **FATAL 属于我方包** 才算阻断；系统组件 FATAL 记录不阻断 |
| 8 | `du -sh /data/data/tv.iqtvlite.app* /data/data/com.cjlhll.iptv 2>/dev/null`（run-as） | 记录；单包 >100MB 告警 |
| 9 | `ls -l /sdcard/Download/*.apk /sdcard/*.tar /sdcard/*.m3u /data/local/tmp/*` | 记录遗留；仅清理**我方**文件 |
| 10 | `pm list packages -u \| grep -E 'iqtvlite\|cjlhll'` | **必须核对 4 个包是否都在**：`tv.iqtvlite.app`、`tv.iqtvlite.app.validation`、`tv.iqtvlite.app.validation.test`、`com.cjlhll.iptv` |

## 2. 安装纪律（体检通过后才允许）

1. 一律 `adb install -r <apk>`；**禁止 `adb uninstall`**。
2. 签名冲突（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）时：
   - 先 `run-as <pkg> tar` 备份 data 到 PC 证据目录并核对字节数 >0；
   - **仅**卸载 `tv.iqtvlite.app.validation`（及其 `.test`），**一次**，随后立即安装；
   - **绝不**卸载 `tv.iqtvlite.app` 与 `com.cjlhll.iptv`。
3. **禁止**批量 force-stop；每日每台设备 force-stop ≤ 3 次。
4. **禁止**连续安装多个版本；一次只装一个 pair，装完先体检再测。
5. **禁止**边播放边抓大量 logcat：单次 `logcat -d` 输出 ≤ 2000 行、间隔 ≥ 10s；调试前 `logcat -c`，避免缓冲区压力。
6. **禁止**写系统分区、改系统属性（除 `log.tag.*` 调试开关，用完 `setprop ... ""` 复位）。
7. **禁止**禁用/卸载 Google 服务或系统组件；仅记录其资源占用。

## 3. 清理范围（只清我方）

- 保留：**最新 1 个 pair（app+test）+ 1 个回滚包**；
- 删除：更旧的 CI APK、旧 backup tar、旧 logcat 导出、我方 sdcard 遗留（如 `laobaitv.m3u`）；
- 不碰：系统应用、Google 服务、用户数据、`/data/local/tmp` 中非我方文件（先确认属主）。

## 4. 失败即停（硬阻断）

出现任一情况**立即停止全部写操作并上报**：
- `/data` 可用 < 500MB；
- `storaged`/`installd`/`DeadObject`/`ext4`/`I/O error` 日志 >0；
- `sys.boot_completed` ≠ 1 或设备反复 `offline`；
- bootreason 出现 `recovery`/`fsck`；
- 我方包（4 个）任一缺失或被第三方卸载器改动；
- `pm install` 报非签名类错误（如 `INSTALL_FAILED_INSUFFICIENT_STORAGE`、`DeadObjectException`）。

## 5. 第三方卸载风险（本次事故）

设备装有 **`com.shafa.market`（沙发管家）**，其 `.AppUninstallManagerAct` 会批量卸载应用。**必须**：
- 每次体检核对 4 个包是否仍在；
- 若发现被第三方卸载，立即：①停所有操作 ②记录 events 日志时间线 ③上报 captain/用户；
- 首次使用前建议用户在系统设置中关闭第三方“清理/卸载”自动化，或临时禁用 `com.shafa.market` 的自动清理（需用户授权，我方不得自行禁用系统/第三方应用）。
