package com.cjlhll.iptv

import java.util.concurrent.atomic.AtomicLong

/**
 * 用户播放意图代次（R01）。任何新的播放意图（手动选源、参考线路、回看、上一台、生命周期重建、
 * 后台刷新切换）都必须调用 [newIntent]，从而让旧的自动 probe / 恢复任务在 await 后失效。
 */
class PlaybackIntentGate {
    private val generation = AtomicLong(0L)

    fun newIntent(): Long = generation.incrementAndGet()
    fun isCurrent(token: Long): Boolean = generation.get() == token
    fun current(): Long = generation.get()
}

/**
 * 自动选源探测协调器（R01）。探测每个候选前后都校验同一用户意图代次；新意图出现后旧探测返回 null，
 * 不再覆盖用户的新选择。生产 [com.cjlhll.iptv.PlayerActivity] 使用它驱动 playGroupAuto。
 */
class ProbeCoordinator(private val gate: PlaybackIntentGate) {
    /**
     * 返回第一个可达线路下标；若探测过程中意图失效返回 null（不得覆盖新选择）；
     * 若均不可达但意图仍有效，回退 0（保持播放一次尝试）。
     */
    suspend fun selectFirstReachable(
        urls: List<String>,
        intent: Long,
        probe: suspend (String) -> Int?,
    ): Int? {
        if (urls.isEmpty()) return null
        for ((index, url) in urls.withIndex()) {
            if (!gate.isCurrent(intent)) return null
            val latency = probe(url)
            if (!gate.isCurrent(intent)) return null
            if (latency != null) return index
        }
        return if (gate.isCurrent(intent)) 0 else null
    }
}

/** 媒体项身份 token（R02）：写入 MediaItem.mediaId，用于从真实事件来源校验 session/attempt。 */
data class MediaToken(val intent: Long, val attempt: Int, val seq: Long) {
    fun encode(): String = "i$intent-a$attempt-s$seq"

    companion object {
        fun parse(raw: String?): MediaToken? {
            if (raw.isNullOrBlank()) return null
            val m = Regex("""^i(\d+)-a(\d+)-s(\d+)$""").find(raw) ?: return null
            val (i, a, s) = m.destructured
            return MediaToken(i.toLongOrNull() ?: return null, a.toIntOrNull() ?: return null, s.toLongOrNull() ?: return null)
        }
    }
}

/**
 * 事件来源路由器（S02）。事件 token 由产生它的播放器实例/attempt 捕获后传入，
 * 不读取回调发生时的可变 currentMediaItem。旧实例/旧 attempt 事件晚到会被拒收。
 */
class AttemptEventRouter {
    @Volatile
    private var activeToken: String? = null

    fun activate(token: String) { activeToken = token }
    fun clear() { activeToken = null }
    fun currentToken(): String? = activeToken
    fun isCurrent(sourceToken: String?): Boolean = sourceToken != null && sourceToken == activeToken
}

/** S01：分离“当前媒体 attempt 身份”与“连续失败预算”。 */
sealed class RecoveryDecision {
    object Ignore : RecoveryDecision()
    data class Recover(val nextAttempt: Int) : RecoveryDecision()
    object Terminal : RecoveryDecision()
}

/**
 * 媒体 attempt 状态机（S01）。token 中的 attempt 是当前媒体身份，READY 不得改写它；
 * READY 只重置“连续失败预算”，因此恢复成功（a1 READY）后再次 error(a1) 仍能开始新恢复。
 * 生产 PlayerActivity 使用本类，纯状态因此可被真实驱动测试。
 */
class PlaybackAttemptState(private val maxRecoveries: Int = 3) {
    var mediaAttempt: Int = 0
        private set
    var recoveryBudget: Int = 0
        private set

    fun newSession() {
        mediaAttempt = 0
        recoveryBudget = 0
    }

    /** READY：连续失败预算清零，媒体身份保持不变。 */
    fun onReady() {
        recoveryBudget = 0
    }

    fun onMediaError(tokenAttempt: Int): RecoveryDecision {
        if (tokenAttempt != mediaAttempt) return RecoveryDecision.Ignore
        recoveryBudget += 1
        if (recoveryBudget > maxRecoveries) return RecoveryDecision.Terminal
        mediaAttempt = tokenAttempt + 1
        return RecoveryDecision.Recover(mediaAttempt)
    }
}

