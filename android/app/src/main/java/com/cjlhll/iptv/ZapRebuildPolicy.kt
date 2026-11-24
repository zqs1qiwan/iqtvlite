package com.cjlhll.iptv

/**
 * t234: the 睛彩 decoder-rebuild ladder.
 *
 * Device evidence: for 睛彩 117/118/119 the video decoder is **never created** (OMX/ACodec counters stay
 * 0, no init/configure/start failure, no dropped frames) while the format `avc/1920x1080` is already known
 * and audio starts fine. The stream has no AUD NALs and a sparse PCR, unlike CCTV which decodes fine.
 * Re-preparing the *same* ExoPlayer cannot help there — the MediaCodec is never even attempted — so the
 * watchdog's retry escalates to **rebuilding the player**, and only then to a **software video fallback**.
 *
 * Ladder (per channel key, reset on every user zap):
 *   1st 8 s without a first frame → REBUILD  (release the player, build a new one, replay the same URL)
 *   2nd 8 s without a first frame → SOFT     (same, but with the FFmpeg video renderer appended)
 *   after that                    → GIVE_UP  (toast, stop — never a loop)
 */
object ZapRebuildPolicy {

    enum class Stage { NORMAL, REBUILT, SOFT_TRIED }

    enum class Action { REBUILD, SOFT, GIVE_UP }

    /** What to do at this stage when another 8 s passed without a frame. */
    fun actionFor(stage: Stage): Action = when (stage) {
        Stage.NORMAL -> Action.REBUILD
        Stage.REBUILT -> Action.SOFT
        Stage.SOFT_TRIED -> Action.GIVE_UP
    }

    fun next(stage: Stage): Stage = when (stage) {
        Stage.NORMAL -> Stage.REBUILT
        Stage.REBUILT -> Stage.SOFT_TRIED
        Stage.SOFT_TRIED -> Stage.SOFT_TRIED
    }

    /** Should this stage build the player with the software (FFmpeg) video renderer? */
    fun softVideo(stage: Stage): Boolean = stage == Stage.SOFT_TRIED

    /** Debug line (caller gates on `Log.isLoggable`). */
    fun logLine(action: Action, channelKey: String): String = when (action) {
        Action.REBUILD -> "rebuild=1 channel=$channelKey"
        Action.SOFT -> "softfallback=1 channel=$channelKey"
        Action.GIVE_UP -> "giveup=1 channel=$channelKey"
    }

    /**
     * Per-channel stage bookkeeping. A user zap resets the channel to [Stage.NORMAL] so the ladder is
     * always "rebuild once, soft once" per zap — never a loop, never carried over to the next channel.
     */
    class Ladder {
        private val stages = HashMap<String, Stage>()

        @Synchronized
        fun onUserZap(channelKey: String) {
            stages.remove(channelKey)
        }

        @Synchronized
        fun stageFor(channelKey: String): Stage = stages[channelKey] ?: Stage.NORMAL

        @Synchronized
        fun advance(channelKey: String): Stage {
            val nextStage = next(stageFor(channelKey))
            stages[channelKey] = nextStage
            return nextStage
        }

        @Synchronized
        fun clear() {
            stages.clear()
        }
    }
}
