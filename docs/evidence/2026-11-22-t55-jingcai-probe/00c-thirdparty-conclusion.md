# 第三方对照预演结论（t55 第 3 步，只读，2026-11-22）

对照对象：com.baiguotv.tv（白果TV，captain 批准；com.cleanroom.iptv 无启动 Activity 已排除）
目标 URL：http://192.168.2.1:4022/rtp/239.3.1.125:8001（睛彩竞技）

## 尝试 1：隐式 VIEW intent
am start -a android.intent.action.VIEW -d '<url>'
结果：Error: Activity not started, unable to resolve Intent（设备上无任何应用注册 http/https VIEW）
→ 无法把 URL 交给第三方播放器

## 尝试 2：显式组件 + extras
am start -n com.baiguotv.tv/com.hongguotv.nativeapp.MainActivity --es url '<url>'
结果：应用被拉起（mCurrentFocus=com.baiguotv.tv/...MainActivity），但日志仅有：
  I/Baiguotv: Identity refresh / Identity warm ok（自身账号/身份日志）
  W/hongguotv-artwo: avc granted { write } /dev/ion（图形缓冲）
  —— 无任何 media/RTP/239.3.1.125/ExoPlayer/MediaCodec 相关日志，is 无拉流迹象
→ extras 被忽略，未播放目标流

## 只读合规确认
- 未 uninstall / 未 pm clear / 未 force-stop 对方；白果TV 进程仍在（2 个）
- 结束后已把 IQTV 拉回前台并校验 mCurrentFocus=PlayerActivity ✅

## 结论
**第三方对照不可行**：无法向设备上的第三方播放器注入目标 URL（无 VIEW 处理器；显式启动忽略 extras），
因此无法完成「同一 117 URL 在第三方播放器上是否可播」的对照判定。按 captain 指示如实记录，不做推断。
