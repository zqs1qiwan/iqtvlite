package tv.iqtvlite.app

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放服务 —— MediaSession 让系统媒体键（播放/暂停）与音频焦点正确工作。
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        @Volatile
        var sharedPlayer: ExoPlayer? = null
            private set

        fun ensurePlayer(context: android.content.Context): ExoPlayer {
            return sharedPlayer ?: synchronized(this) {
                sharedPlayer ?: ExoPlayer.Builder(context).build().also { sharedPlayer = it }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ensurePlayer(this)
        mediaSession = MediaSession.Builder(this, player)
            .setId("iqtvlite-session")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
            sharedPlayer = null
        }
        super.onDestroy()
    }
}
