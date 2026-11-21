package com.cjlhll.iptv

import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvTokensTest {
    @Test
    fun panelWidthIsOneThirdUnlessCappedAt360() {
        // W=960 -> W/3 = 320 (<360)
        assertEquals(320f, computePanelWidth(960.dp).value, 0.01f)
        // W=1280 -> W/3 ≈ 426 -> capped 360
        assertEquals(360f, computePanelWidth(1280.dp).value, 0.01f)
        // W=600 -> 200
        assertEquals(200f, computePanelWidth(600.dp).value, 0.01f)
    }

    @Test
    fun systemAndBasicKeysAreForbiddenForRemap() {
        assertTrue(AppSettings.isForbiddenKey(KeyEvent.KEYCODE_BACK))
        assertTrue(AppSettings.isForbiddenKey(KeyEvent.KEYCODE_HOME))
        assertTrue(AppSettings.isForbiddenKey(KeyEvent.KEYCODE_POWER))
        assertTrue(AppSettings.isForbiddenKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(AppSettings.isForbiddenKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(AppSettings.isForbiddenKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(AppSettings.isForbiddenKey(KeyEvent.KEYCODE_MENU))
        for (d in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            assertTrue(AppSettings.isForbiddenKey(d))
        }
    }

    @Test
    fun directionAndFunctionKeysAreAssignable() {
        assertTrue(AppSettings.isAssignableKey(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(AppSettings.isAssignableKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(AppSettings.isAssignableKey(KeyEvent.KEYCODE_GUIDE))
        assertTrue(AppSettings.isAssignableKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(AppSettings.isAssignableKey(KeyEvent.KEYCODE_CHANNEL_UP))
        assertFalse(AppSettings.isAssignableKey(KeyEvent.KEYCODE_BACK))
        assertFalse(AppSettings.isAssignableKey(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun defaultRemoteMappingIsDistinctAndComplete() {
        val keys = RemoteAction.entries.map { AppSettings.defaultKey(it) }
        assertEquals(RemoteAction.entries.size, keys.toSet().size)
    }

    @Test
    fun programClickAndRefreshDefaultsExist() {
        assertEquals("直接回看", ProgramClickMode.DIRECT.label)
        assertEquals("确认后回看", ProgramClickMode.CONFIRM.label)
        assertEquals("启动时 + 每6小时", PlaylistRefreshPolicy.STARTUP_6H.label)
        assertEquals("启动时 + 每6小时", EpgRefreshPolicy.STARTUP_6H.label)
    }
}
