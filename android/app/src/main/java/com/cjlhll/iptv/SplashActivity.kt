package com.cjlhll.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import java.io.File

class SplashActivity : ComponentActivity() {
    companion object {
        /** t55：开屏可见 800ms，postDelayed 非阻塞；主线程仍绘制 windowBackground 字标。 */
        const val SPLASH_VISIBLE_MS = 800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cleanOldApkFiles()

        val liveSource = Prefs.getLiveSource(this)
        val (lastUrl, lastTitle) = Prefs.getLastChannel(this)
        val playlistFileName = Prefs.getPlaylistFileName(this)

        // t55：非阻塞延迟展示正式字标；不 sleep，800ms 后启动目标 Activity 并 finish。
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!Prefs.hasSelectedLanguage(this)) {
                startActivity(Intent(this, LanguageSelectActivity::class.java))
            } else if (liveSource.isNotBlank() && !lastUrl.isNullOrBlank()) {
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_URL", lastUrl)
                    putExtra("VIDEO_TITLE", lastTitle ?: "Live")
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
