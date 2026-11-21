# CI10097 数字换台 OSD 真机验证

2026-11-21，acceptance2（t125 attempt 1）。对象：`tv.iqtvlite.app.validation` **10097 / 2.2.0-tivimini-8cfc03d0-validation**（CI10097 = CI10096 + t124 数字 OSD 修复；run 44787285293）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p，density240/1.5x）在线。测试源 `https://iptv.laobaitv.net`。

> 模型不读图；OSD 以 PIL 像素统计（深底/亮字）判定 + 截图供人工；数值为原始采集。

## 0. 结论摘要（数字 OSD 修复 PASS）

- **OSD 可见/对比度 PASS**：按数字后顶部中央出现**深底面板 + 亮字**（区域 bright%（亮字像素）0.00→**0.95%**、dark%（深底）0.00→**14.93%**，mean 由 87/97/98→105/110/135）；两行“请输入频道号 / 12_”依 t124 规格 + 亮字像素存在。（截图 `osd-12.png` 供人工核对文字）。
- **~3s 自动消失 PASS**：等待 3.2s 后同区域 bright 0%、dark 0.44% → OSD 消失。
- **Back 取消 PASS**：OSD 显示中按 Back，区域 bright 0.16%→0%、dark 12%→0% → 取消。
- **有效号跳台 PASS**：输入 “2”+ENTER → 新会话 s4（`audio/mpeg-L2`，first_frame ttff1214）→ 跳转生效。
- **无效号 Toast PASS**：输入 “999”+ENTER → **未跳台**（first_frame 0），且 logcat `NotificationService: Toast … pkg=tv.iqtvlite.app.validation`（我方 Toast 出现；“没有频道 999”文本依 t124 规格，未 OCR）。
- **不误触发 PASS**：抽屉打开时注入数字 → 无 OSD 亮字（bright 0.06%）、无跳台（first_frame 0）、抽屉仍开（headers=[频道,节目单]）。
- **不回归**：MP2 有声（s5 `audio/mpeg-L2` 48000/2ch + `audio_advancing` + `AudioTrack ID:799 state:started`）；无本 app 崩溃（FATAL=0，pid 稳定）；观感同 CI10096 同源（`look-10097.png`）。AAC 未复测（同 AAR/内核，t120/t121 已验 AAC 有声）。

## 1. 身份/安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | f267cc7e28aadd76f300df84292eb86093007a11db08984d42080f90bdd2b900（12,928,911B，match） |
| test SHA256 | 9116dd08cdd6b9e6be7ce6fe9c9b885abd8c0399419a5eebd4facefdbd89e832（502,955B，match） |
| 签名 SHA256 | 196a0e5920dcad2270d30cd7f72d142d2519da1d21db64906411a284f3b0cc4d |
| 包/版本 | 10097 / 2.2.0-tivimini-8cfc03d0-validation；label=IQTV Lite 验证版；min24/target35；primaryCpuAbi=armeabi-v7a |

backup 10096（validation-10096-backup.tar 1,403,904B）后卸载旧+安装 10097（Success）；安装后 base.apk 复算=`f267cc7e…b900`（match）；原 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. OSD 可见性/对比度（原始像素）

顶部中央区域 crop (560,40)-(1360,340)：

| 帧 | mean RGB | bright%（亮字 R,G,B>200） | dark%（深底 R<45,G<45,B<55） |
|---|---|---|---|
| base（无OSD） | 87.1, 96.6, 97.6 | 0.00 | 0.00 |
| **输入 1,2（OSD）** | 105.0, 109.6, 134.6 | **0.95** | **14.93** |
| 3.2s 后 | 130.2, 124.2, 110.4 | 0.00 | 0.44 |
- → 深底面板(#E610141A 暗靛) + 亮字(#F2F5FA) 均出现，对比充分；3.2s 后消失。截图：`osd-base.png / osd-12.png / osd-after-3s.png`。

## 3. Back 取消
- `osd-precancel.png`：bright 0.16% / dark 12.00%（OSD 在）→ 按 Back → `osd-postcancel.png`：bright 0% / dark 0%（OSD 消失）。PASS。
- 注：该窗口 first_frame=1（疑为当前会话延迟首帧/上下文残留，非跳台，像素已证 OSD 取消）。

## 4. 有效号跳台
- 输入 “2” + ENTER：`first_frame session=…s4 ttff_ms=1214 mime=audio/mpeg-L2` → 新会话建立（跳转生效）。PASS。

## 5. 无效号 Toast
- 输入 “999” + ENTER：`first_frame count=0`（未跳台）+ logcat `W/NotificationService: Toast already killed. pkg=tv.iqtvlite.app.validation`（我方 Toast 出现）→ PASS（文本“没有频道 999”依 t124 源码，未 OCR）。

## 6. 不误触发（抽屉打开时）
- 抽屉打开 + 注入 1,2：`misfire-drawer.png` 顶部区域 bright 0.06%（无亮字/无 OSD）、first_frame 0（无跳台）、uiautomator headers=[频道,节目单]（抽屉仍开）。PASS。

## 7. 不回归
- MP2（167 切台 s5）：`audio_format mime=audio/mpeg-L2 48000/2ch` + `audio_advancing since_prepare 876ms` + `dumpsys audio ID:799 AudioTrack u/pid 10094/26123 state:started` → MP2 有声不回归。PASS。
- 无本 app 崩溃：`app FATAL=0`，pid 26123 稳定。PASS。
- 观感：`look-10097.png` 与 CI10096 同源一致。
- 抽屉 jank（本次仅 6s 短静置的粗探）：251 帧 / 58.17% / 90th121ms —**不可与 25s 静置基线(CI10096 27.30%/69)直接比较**（采样前置了连续切台/OSD，属瞬态），非本次判据，仅记录。

## 8. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| OSD 深底亮字可见/对比 | 通过（bright0.95%/dark14.93%） |
| ~3s 自动消失 | 通过（3.2s 后 bright0/dark0.44） |
| Back 取消 | 通过 |
| 有效号跳台 | 通过（“2”+ENTER→新会话 s4） |
| 无效号 Toast | 通过（未跳台 + 我方 Toast 出现；文本未OCR） |
| 抽屉打开数字不误触发 | 通过 |
| MP2 有声 | 通过（mpeg-L2+audio_advancing+AudioTrack started） |
| AAC 不回归 | 未复测（同 AAR/内核，t120/t121 已验有声） |
| 不崩 | 通过（FATAL 0） |
| 观感同 CI10096 | 通过 |

## 9. 判定
- **数字换台 OSD 修复通过真机验证**：清晰可见（深底+亮字，对比充分）、~3s 自动消失、Back 取消、有效号跳台、无效号 Toast、抽屉打开不误触发；MP2 有声不回归、无崩溃、观感同 CI10096。
- 未验证/受限：OSD 文字内容未 OCR（像素+源码 t124 推断，截图供人工确认）；AAC 本次未单独复测。
- 未改代码、未改原包数据；设备现为 CI10097。
