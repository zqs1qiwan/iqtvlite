package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t223: the digit OSD must disappear after 3 s in BOTH modes (needs-OK used to stay forever). */
class DigitOsdPolicyTest {

    private val direct = RemoteHabits.Digits.DIRECT
    private val needsOk = RemoteHabits.Digits.NEEDS_CONFIRM

    @Test
    fun bothModesUseTheSameThreeSecondTimeout() {
        assertEquals(3_000L, DigitOsdPolicy.TIMEOUT_MS)
        assertTrue("every input restarts the timer", DigitOsdPolicy.restartOnEveryInput())
    }

    @Test
    fun theTimeoutOnlyJumpsInDirectMode() {
        assertEquals(DigitOsdPolicy.TimeoutAction.SUBMIT, DigitOsdPolicy.timeoutAction(direct))
        // needs-OK: hide the OSD, never jump — the user did not confirm anything
        assertEquals(DigitOsdPolicy.TimeoutAction.CLEAR, DigitOsdPolicy.timeoutAction(needsOk))
    }

    @Test
    fun okSubmitsAndBackCancelsInBothModes() {
        assertEquals(DigitOsdPolicy.TimeoutAction.SUBMIT, DigitOsdPolicy.onConfirm())
        assertEquals(DigitOsdPolicy.TimeoutAction.CLEAR, DigitOsdPolicy.onCancel())
    }

    @Test
    fun appendingStopsAtTheInputCap() {
        assertEquals("1", DigitOsdPolicy.append("", 1))
        assertEquals("12", DigitOsdPolicy.append("1", 2))
        val six = "123456"
        assertEquals("no growth past the cap", six, DigitOsdPolicy.append(six, 7))
        assertEquals(6, DigitOsdPolicy.MAX_DIGITS)
    }

    @Test
    fun anOpenOverlayHidesTheOsd() {
        assertTrue(DigitOsdPolicy.visibleWhen(anyOverlayOpen = false))
        assertFalse(DigitOsdPolicy.visibleWhen(anyOverlayOpen = true))
    }
}
