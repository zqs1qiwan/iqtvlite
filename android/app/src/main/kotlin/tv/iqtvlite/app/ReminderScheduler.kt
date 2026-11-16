package tv.iqtvlite.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 节目提醒 —— 用 Android AlarmManager 真实调度通知。
 *
 * 设置提醒时把 (title, startTs) 写进 PendingIntent，到点由系统唤起本 Receiver 发通知。
 * 即使 App 被杀，AlarmManager 仍会触发（系统级调度）。
 */
class ReminderScheduler : BroadcastReceiver() {
    companion object {
        private const val CHANNEL = "iqtvlite_reminders"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TVG = "tvgId"

        fun schedule(ctx: Context, tvgId: String, title: String, startTs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, ReminderScheduler::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TVG, tvgId)
            }
            val pi = PendingIntent.getBroadcast(
                ctx, (tvgId + startTs).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val triggerMs = startTs * 1000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
            android.util.Log.i("iqtv-reminder", "scheduled: $title @ $startTs")
        }

        fun cancel(ctx: Context, tvgId: String, startTs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, ReminderScheduler::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, (tvgId + startTs).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(pi)
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "节目提醒"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "iqtvlite 节目提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pi = PendingIntent.getActivity(
            ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("节目即将开始")
            .setContentText(title)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify((title.hashCode() and 0x7fffffff), n)
        android.util.Log.i("iqtv-reminder", "fired: $title")
    }
}
