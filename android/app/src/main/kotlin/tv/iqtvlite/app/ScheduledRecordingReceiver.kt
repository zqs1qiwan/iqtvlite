package tv.iqtvlite.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 定时录制触发器 —— AlarmManager 到点唤起，启动 RecordingService 录制。
 */
class ScheduledRecordingReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "iqtv-schedrec"
        private const val EXTRA_KEY = "key"

        fun schedule(ctx: Context, r: TiviMatePrefs.ScheduledRecording) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(ctx, ScheduledRecordingReceiver::class.java).putExtra(EXTRA_KEY, r.startTs)
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, r.startTs.toInt(), intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, r.startTs * 1000, pi)
            } else {
                am.set(android.app.AlarmManager.RTC_WAKEUP, r.startTs * 1000, pi)
            }
            Log.i(TAG, "scheduled ${r.title} @ ${r.startTs}")
        }

        fun cancel(ctx: Context, startTs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(ctx, ScheduledRecordingReceiver::class.java)
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, startTs.toInt(), intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(pi)
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getLongExtra(EXTRA_KEY, 0L)
        val r = TiviMatePrefs.scheduledRecordings(ctx).find { it.startTs == key }
        if (r == null) { Log.w(TAG, "no scheduled recording for $key"); return }
        if (r.stopTs <= System.currentTimeMillis() / 1000) {
            Log.w(TAG, "recording window already passed: ${r.title}")
            return
        }

        // 用 Rust 生成录制计划（文件名 / UA / 格式）
        try {
            val params = org.json.JSONObject().apply {
                put("title", r.title)
                put("id", r.channelId)
                put("channelId", r.channelId)
                put("url", r.liveUrl)
                val alts = org.json.JSONArray(); r.altUrls.forEach { alts.put(it) }
                put("altUrls", alts)
                put("source", "scheduled")
                put("userAgent", r.userAgent ?: "VLC/3.0.20 LibVLC/3.0.20")
                put("startTs", r.startTs)
                put("stopTs", r.stopTs)
                put("outputDir", ctx.getExternalFilesDir("recordings")?.absolutePath ?: "/sdcard/Recordings")
            }.toString()
            val res = org.json.JSONObject(RustBridge.buildRecordingPlan(params))
            if (res.optBoolean("ok", false)) {
                RecordingService.start(ctx, res.toString())
                Log.i(TAG, "REC_START ${res.optString("outputPath")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to start scheduled recording", e)
        }
    }
}
