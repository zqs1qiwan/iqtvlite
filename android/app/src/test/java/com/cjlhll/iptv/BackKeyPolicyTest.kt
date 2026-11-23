package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** t49 §①/§②：BACK 分层状态机 —— 每层明确消费，永不 PASS_TO_SUPER。 */
class BackKeyPolicyTest {

    @Test
    fun layeredPriorityHighestWins() {
        val p = BackKeyPolicy
        // 音频框 > 其它所有层
        assertEquals(
            BackKeyPolicy.Action.CLOSE_AUDIO_DIALOG,
            p.decide(true, true, true, true, true, true, true, true, true, true),
        )
        // 最近 > 质量 > 抽屉 > 设置 > 信息卡
        assertEquals(BackKeyPolicy.Action.CLOSE_RECENT, p.decide(false, true, true, true, true, true, true, true, true, true))
        assertEquals(BackKeyPolicy.Action.CLOSE_QUALITY_DIALOG, p.decide(false, false, true, true, true, true, true, true, true, true))
        assertEquals(BackKeyPolicy.Action.CLOSE_DRAWER, p.decide(false, false, false, true, true, true, true, true, true, true))
        assertEquals(BackKeyPolicy.Action.SETTINGS_BACK_TO_ROOT, p.decide(false, false, false, false, true, true, true, true, true, true))
        assertEquals(BackKeyPolicy.Action.CLOSE_SETTINGS, p.decide(false, false, false, false, true, false, true, true, true, true))
    }

    @Test
    fun infoCardLayersBeforeExit() {
        val p = BackKeyPolicy
        // FOCUSED → 先退聚焦（卡片仍在），不是关卡片更不是退出。
        assertEquals(
            BackKeyPolicy.Action.INFO_CARD_UNFOCUS,
            p.decide(false, false, false, false, false, false, true, true, false, true),
        )
        // IDLE 可见 → 关卡片到 HIDDEN。
        assertEquals(
            BackKeyPolicy.Action.INFO_CARD_CLOSE,
            p.decide(false, false, false, false, false, false, true, false, false, true),
        )
        // t59 ②：卡片可见时即便处于"双按窗口内"也只退聚焦/关卡，绝不 EXIT_APP。
        assertEquals(
            BackKeyPolicy.Action.INFO_CARD_UNFOCUS,
            p.decide(false, false, false, false, false, false, true, true, false, true),
        )
        assertEquals(
            BackKeyPolicy.Action.INFO_CARD_CLOSE,
            p.decide(false, false, false, false, false, false, true, false, false, true),
        )
    }

    @Test
    fun exitIsAlwaysDoublePressWithToast() {
        val p = BackKeyPolicy
        val noOverlays = { within2s: Boolean ->
            p.decide(false, false, false, false, false, false, false, false, false, within2s)
        }
        // 第一次 → toast；2s 内第二次 → exit；没有"单次直接 finish"。
        assertEquals(BackKeyPolicy.Action.TOAST_EXIT, noOverlays(false))
        assertEquals(BackKeyPolicy.Action.EXIT_APP, noOverlays(true))
        // 回看中双按是"返回直播"，不是退出。
        assertEquals(
            BackKeyPolicy.Action.TOAST_RETURN_LIVE,
            p.decide(false, false, false, false, false, false, false, false, true, false),
        )
        assertEquals(
            BackKeyPolicy.Action.RETURN_LIVE,
            p.decide(false, false, false, false, false, false, false, false, true, true),
        )
    }

    @Test
    fun everyActionIsConsumedNeverPassToSuper() {
        val p = BackKeyPolicy
        BackKeyPolicy.Action.entries.forEach { assertEquals(true, BackKeyPolicy.alwaysConsumed(it)) }
        // 任意组合都返回一个具体 CONSUME 动作。
        val action = p.decide(false, false, false, false, false, false, false, false, false, false)
        assertEquals(BackKeyPolicy.Action.TOAST_EXIT, action)
    }
}
