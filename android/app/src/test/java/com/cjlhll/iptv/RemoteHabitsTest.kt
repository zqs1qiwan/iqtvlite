package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t186 (F1): remote habits — defaults, cycling, and the "overlays always win" rule. */
class RemoteHabitsTest {

    private val defaults = RemoteHabits.DEFAULT

    @Test
    fun defaultsKeepTheShippedBehaviour() {
        assertEquals(RemoteHabits.UpDown.CHANNEL_STEP, defaults.upDown)
        assertEquals(RemoteHabits.Ok.OPEN_DRAWER, defaults.ok)
        assertEquals(RemoteHabits.Back.CLOSE_OVERLAY, defaults.back)
        assertEquals(RemoteHabits.Digits.DIRECT, defaults.digits)
        assertTrue(defaults.channelKeysEnabled)
    }

    @Test
    fun unknownOrMissingValuesFallBackToDefaults() {
        assertEquals(RemoteHabits.UpDown.CHANNEL_STEP, RemoteHabits.parseUpDown(null))
        assertEquals(RemoteHabits.UpDown.CHANNEL_STEP, RemoteHabits.parseUpDown("garbage"))
        assertEquals(RemoteHabits.Ok.OPEN_DRAWER, RemoteHabits.parseOk(null))
        assertEquals(RemoteHabits.Back.CLOSE_OVERLAY, RemoteHabits.parseBack(""))
        assertEquals(RemoteHabits.Digits.DIRECT, RemoteHabits.parseDigits("x"))
    }

    @Test
    fun cyclingWrapsThroughEveryOption() {
        assertEquals(RemoteHabits.UpDown.OPEN_DRAWER, RemoteHabits.nextUpDown(RemoteHabits.UpDown.CHANNEL_STEP))
        assertEquals(RemoteHabits.UpDown.CHANNEL_STEP, RemoteHabits.nextUpDown(RemoteHabits.UpDown.OPEN_DRAWER))

        assertEquals(RemoteHabits.Ok.CONFIRM, RemoteHabits.nextOk(RemoteHabits.Ok.OPEN_DRAWER))
        assertEquals(RemoteHabits.Ok.OPEN_DRAWER, RemoteHabits.nextOk(RemoteHabits.Ok.CONFIRM))

        assertEquals(RemoteHabits.Back.EXIT, RemoteHabits.nextBack(RemoteHabits.Back.CLOSE_OVERLAY))
        assertEquals(RemoteHabits.Back.CLOSE_OVERLAY, RemoteHabits.nextBack(RemoteHabits.Back.EXIT))

        assertEquals(RemoteHabits.Digits.NEEDS_CONFIRM, RemoteHabits.nextDigits(RemoteHabits.Digits.DIRECT))
        assertEquals(RemoteHabits.Digits.DIRECT, RemoteHabits.nextDigits(RemoteHabits.Digits.NEEDS_CONFIRM))
    }

    @Test
    fun upDownHabitMapsToTheRightAction() {
        assertEquals(
            RemoteHabits.UpDownAction.CHANNEL_UP,
            RemoteHabits.onUpDown(defaults, overlayOpen = false, up = true)
        )
        assertEquals(
            RemoteHabits.UpDownAction.CHANNEL_DOWN,
            RemoteHabits.onUpDown(defaults, overlayOpen = false, up = false)
        )
        val drawer = defaults.copy(upDown = RemoteHabits.UpDown.OPEN_DRAWER)
        assertEquals(
            RemoteHabits.UpDownAction.OPEN_DRAWER,
            RemoteHabits.onUpDown(drawer, overlayOpen = false, up = true)
        )
    }

    @Test
    fun okHabitMapsToTheRightAction() {
        assertEquals(RemoteHabits.OkAction.OPEN_DRAWER, RemoteHabits.onOk(defaults, overlayOpen = false))
        val confirm = defaults.copy(ok = RemoteHabits.Ok.CONFIRM)
        assertEquals(RemoteHabits.OkAction.SHOW_INFO, RemoteHabits.onOk(confirm, overlayOpen = false))
    }

    @Test
    fun backHabitOnlyAffectsThePlayerForeground() {
        assertEquals(RemoteHabits.BackAction.CLOSE_OVERLAY, RemoteHabits.onBack(defaults, overlayOpen = false))
        val exit = defaults.copy(back = RemoteHabits.Back.EXIT)
        assertEquals(RemoteHabits.BackAction.EXIT, RemoteHabits.onBack(exit, overlayOpen = false))
        // an overlay still closes itself, never exits the app
        assertEquals(RemoteHabits.BackAction.CLOSE_OVERLAY, RemoteHabits.onBack(exit, overlayOpen = true))
    }

    @Test
    fun catchupKeepsOkOnTheInfoBanner() {
        // t193 (CATCHUP-01): during catch-up, OK must not open the channel list (it would switch
        // channels and drop the replay) — it shows the info banner regardless of the habit.
        assertTrue(
            RemoteHabits.onOk(defaults, overlayOpen = false, catchupPlayback = true) ==
                RemoteHabits.OkAction.SHOW_INFO
        )
        RemoteHabits.Ok.entries.forEach { ok ->
            val s = defaults.copy(ok = ok)
            assertTrue(
                "$ok must show the banner during catch-up",
                RemoteHabits.onOk(s, overlayOpen = false, catchupPlayback = true) ==
                    RemoteHabits.OkAction.SHOW_INFO
            )
        }
        // live TV keeps the configured habit
        assertEquals(
            RemoteHabits.OkAction.OPEN_DRAWER,
            RemoteHabits.onOk(defaults, overlayOpen = false, catchupPlayback = false)
        )
        // and an overlay still wins over catch-up
        assertEquals(
            RemoteHabits.OkAction.NONE,
            RemoteHabits.onOk(defaults, overlayOpen = true, catchupPlayback = true)
        )
    }

    @Test
    fun aSettingsChangeAppliesToTheNextKeyWithoutAnyPause() {
        // t193 (REMOTE-INSTANT-01): the Activity mirrors the composable's value, so the moment the
        // row is clicked the next decision already uses the new setting (no onResume needed).
        var live = RemoteHabits.DEFAULT
        assertEquals(
            RemoteHabits.UpDownAction.CHANNEL_UP,
            RemoteHabits.onUpDown(live, overlayOpen = false, up = true)
        )

        // user cycles 上下键 → 呼出频道列表 (the row writes Prefs + pushes the new value)
        live = live.copy(upDown = RemoteHabits.nextUpDown(live.upDown))
        assertEquals(
            "the very next key must follow the new habit",
            RemoteHabits.UpDownAction.OPEN_DRAWER,
            RemoteHabits.onUpDown(live, overlayOpen = false, up = true)
        )

        // and BACK switches to EXIT immediately as well
        live = live.copy(back = RemoteHabits.nextBack(live.back))
        assertEquals(
            RemoteHabits.BackAction.EXIT,
            RemoteHabits.onBack(live, overlayOpen = false)
        )
        // overlays still take precedence right after the change
        assertEquals(
            RemoteHabits.BackAction.CLOSE_OVERLAY,
            RemoteHabits.onBack(live, overlayOpen = true)
        )
    }

    @Test
    fun digitsStillSubmitThroughTheSameDecision() {
        // t193 TEST-01: the digits habit only decides *when* the OSD submits.
        assertEquals(RemoteHabits.DigitAction.DIRECT, RemoteHabits.onDigit(RemoteHabits.DEFAULT))
        assertEquals(
            RemoteHabits.DigitAction.NEEDS_CONFIRM,
            RemoteHabits.onDigit(RemoteHabits.DEFAULT.copy(digits = RemoteHabits.Digits.NEEDS_CONFIRM))
        )
    }

    @Test
    fun overlaysAlwaysWin() {
        // every habit combination is neutralised while an overlay is open
        RemoteHabits.UpDown.entries.forEach { upDown ->
            RemoteHabits.Ok.entries.forEach { ok ->
                val s = RemoteHabits.Settings(upDown = upDown, ok = ok)
                assertEquals(
                    "up/down must not leak under an overlay ($upDown/$ok)",
                    RemoteHabits.UpDownAction.NONE,
                    RemoteHabits.onUpDown(s, overlayOpen = true, up = true)
                )
                assertEquals(
                    RemoteHabits.OkAction.NONE,
                    RemoteHabits.onOk(s, overlayOpen = true)
                )
            }
        }
    }

    @Test
    fun digitsHabitDecidesWhetherEnterIsRequired() {
        assertEquals(RemoteHabits.DigitAction.DIRECT, RemoteHabits.onDigit(defaults))
        assertEquals(
            RemoteHabits.DigitAction.NEEDS_CONFIRM,
            RemoteHabits.onDigit(defaults.copy(digits = RemoteHabits.Digits.NEEDS_CONFIRM))
        )
    }

    @Test
    fun channelKeysCanBeDisabledIndependently() {
        assertTrue(RemoteHabits.channelKeysEnabled(defaults))
        assertFalse(RemoteHabits.channelKeysEnabled(defaults.copy(channelKeysEnabled = false)))
        // disabling 166/167 must not change the D-pad mapping
        val disabled = defaults.copy(channelKeysEnabled = false)
        assertEquals(
            RemoteHabits.UpDownAction.CHANNEL_UP,
            RemoteHabits.onUpDown(disabled, overlayOpen = false, up = true)
        )
    }

    @Test
    fun labelsArePresentForEveryOption() {
        RemoteHabits.UpDown.entries.forEach { assertTrue(it.label.isNotBlank()) }
        RemoteHabits.Ok.entries.forEach { assertTrue(it.label.isNotBlank()) }
        RemoteHabits.Back.entries.forEach { assertTrue(it.label.isNotBlank()) }
        RemoteHabits.Digits.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}
