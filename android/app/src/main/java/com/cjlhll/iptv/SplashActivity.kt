package com.cjlhll.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import java.io.File

class SplashActivity : ComponentActivity() {
    companion object {
        /** 开屏短时展示官方字标，同时预热磁盘/网络首选项。 */
        const val SPLASH_VISIBLE_MS = 260L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cleanOldApkFiles()

        val liveSource = Prefs.getLiveSource(this)
        val resumeLast = Prefs.isResumeLastChannel(this)
        val (lastUrl, lastTitle) = if (resumeLast) Prefs.getLastChannel(this) else null to null
        val playlistFileName = Prefs.getPlaylistFileName(this)

        // 非阻塞短时展示字标后进入目标 Activity 并 finish。
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!Prefs.hasSelectedLanguage(this)) {
                startActivity(Intent(this, LanguageSelectActivity::class.java))
            } else if (liveSource.isNotBlank() && (!lastUrl.isNullOrBlank() || !resumeLast)) {
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_URL", lastUrl ?: "")
                    putExtra("VIDEO_TITLE", if (lastUrl.isNullOrBlank()) "" else (lastTitle ?: "Live"))
                    if (!playlistFileName.isNullOrBlank()) {
                        putExtra("PLAYLIST_FILE_NAME", playlistFileName)
                    }
                }
                startActivity(intent)
            } else {
                startActivity(Intent(this, MainActivity::class.java))
            }
            finish()
        }, SPLASH_VISIBLE_MS)
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
