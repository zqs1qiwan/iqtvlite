package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t234: rebuild → soft-fallback → give up, exactly once each, reset by every user zap. */
class ZapRebuildPolicyTest {

    @Test
    fun theLadderIsRebuildThenSoftThenGiveUp() {
        assertEquals(ZapRebuildPolicy.Action.REBUILD, ZapRebuildPolicy.actionFor(ZapRebuildPolicy.Stage.NORMAL))
        assertEquals(ZapRebuildPolicy.Action.SOFT, ZapRebuildPolicy.actionFor(ZapRebuildPolicy.Stage.REBUILT))
        assertEquals(ZapRebuildPolicy.Action.GIVE_UP, ZapRebuildPolicy.actionFor(ZapRebuildPolicy.Stage.SOFT_TRIED))
    }

    @Test
    fun onlyTheSoftStepUsesTheSoftwareVideoRenderer() {
        assertFalse(ZapRebuildPolicy.softVideo(ZapRebuildPolicy.Stage.NORMAL))
        assertFalse("the first rebuild keeps hardware video", ZapRebuildPolicy.softVideo(ZapRebuildPolicy.Stage.REBUILT))
        assertTrue(ZapRebuildPolicy.softVideo(ZapRebuildPolicy.Stage.SOFT_TRIED))
    }

    @Test
    fun stagesAdvanceAndSaturate() {
        assertEquals(ZapRebuildPolicy.Stage.REBUILT, ZapRebuildPolicy.next(ZapRebuildPolicy.Stage.NORMAL))
        assertEquals(ZapRebuildPolicy.Stage.SOFT_TRIED, ZapRebuildPolicy.next(ZapRebuildPolicy.Stage.REBUILT))
        assertEquals(
            "never past the last stage (no loop)",
            ZapRebuildPolicy.Stage.SOFT_TRIED,
            ZapRebuildPolicy.next(ZapRebuildPolicy.Stage.SOFT_TRIED)
        )
    }

    @Test
    fun theLadderRunsOncePerChannelAndStops() {
        val ladder = ZapRebuildPolicy.Ladder()
        assertEquals(ZapRebuildPolicy.Stage.NORMAL, ladder.stageFor("c117"))
        assertEquals(ZapRebuildPolicy.Stage.REBUILT, ladder.advance("c117"))
        assertEquals(ZapRebuildPolicy.Stage.SOFT_TRIED, ladder.advance("c117"))
        assertEquals(ZapRebuildPolicy.Stage.SOFT_TRIED, ladder.advance("c117"))
        assertEquals(
            "a third timeout must give up, not rebuild again",
            ZapRebuildPolicy.Action.GIVE_UP,
            ZapRebuildPolicy.actionFor(ladder.stageFor("c117"))
        )
    }

    @Test
    fun aUserZapGivesTheChannelAFreshLadder() {
        val ladder = ZapRebuildPolicy.Ladder()
        ladder.advance("c117")
        ladder.advance("c117")
        ladder.onUserZap("c117")
        assertEquals(ZapRebuildPolicy.Stage.NORMAL, ladder.stageFor("c117"))
        assertEquals(ZapRebuildPolicy.Action.REBUILD, ZapRebuildPolicy.actionFor(ladder.stageFor("c117")))
    }

    @Test
    fun channelsAreIndependentAndClearResetsEverything() {
        val ladder = ZapRebuildPolicy.Ladder()
        ladder.advance("c117")
        assertEquals("another channel is untouched", ZapRebuildPolicy.Stage.NORMAL, ladder.stageFor("c118"))
        ladder.clear()
        assertEquals(ZapRebuildPolicy.Stage.NORMAL, ladder.stageFor("c117"))
    }

    @Test
    fun theLogLinesAreStable() {
        assertEquals("rebuild=1 channel=c117", ZapRebuildPolicy.logLine(ZapRebuildPolicy.Action.REBUILD, "c117"))
        assertEquals("softfallback=1 channel=c118", ZapRebuildPolicy.logLine(ZapRebuildPolicy.Action.SOFT, "c118"))
        assertEquals("giveup=1 channel=c119", ZapRebuildPolicy.logLine(ZapRebuildPolicy.Action.GIVE_UP, "c119"))
    }

    @Test
    fun aHealthyChannelNeverLeavesTheNormalStage() {
        // The watchdog only fires after 8 s without a first frame, so a channel that renders keeps
        // Stage.NORMAL and the ladder never runs — the default path is unchanged.
        val ladder = ZapRebuildPolicy.Ladder()
        assertEquals(ZapRebuildPolicy.Stage.NORMAL, ladder.stageFor("cctv1"))
        assertFalse(ZapRebuildPolicy.softVideo(ladder.stageFor("cctv1")))
    }
}
