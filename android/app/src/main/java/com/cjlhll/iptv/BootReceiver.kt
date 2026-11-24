package com.cjlhll.iptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启接收器：仅当用户开启「开机时自动启动」时拉起播放器。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isAutoStartOnBoot(context)) return
        val launch = Intent(context, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(launch) }
    }
}
