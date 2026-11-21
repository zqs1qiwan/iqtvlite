package com.cjlhll.iptv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import java.io.File

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cleanOldApkFiles()

        val liveSource = Prefs.getLiveSource(this)
        val (lastUrl, lastTitle) = Prefs.getLastChannel(this)
        val playlistFileName = Prefs.getPlaylistFileName(this)
        val restoreLast = AppSettings.isRestoreLastChannel(this)

        val hasPlayableConfig = liveSource.isNotBlank() &&
            (!playlistFileName.isNullOrBlank() || !lastUrl.isNullOrBlank())

        if (hasPlayableConfig) {
            // 已配置 → 进入播放器。恢复开关决定起始频道，由 PlayerActivity 处理缓存/首台。
            val intent = Intent(this, PlayerActivity::class.java).apply {
                if (restoreLast && !lastUrl.isNullOrBlank()) {
                    putExtra("VIDEO_URL", lastUrl)
                    putExtra("VIDEO_TITLE", lastTitle ?: "Live")
                }
                if (!playlistFileName.isNullOrBlank()) {
                    putExtra("PLAYLIST_FILE_NAME", playlistFileName)
                }
            }
            startActivity(intent)
        } else {
            // 未配置或无可播放缓存 → 源配置
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun cleanOldApkFiles() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("update_") && file.name.endsWith(".apk")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
        }
    }
}
