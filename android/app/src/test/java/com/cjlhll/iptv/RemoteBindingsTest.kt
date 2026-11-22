package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FULLFIX §7：功能→按键全映射的纯逻辑测试（默认行为等价、迁移、往返、上下文解析、冲突覆盖）。 */
class RemoteBindingsTest {

    // ── 默认行为与旧实现完全等价 ────────────────────────────────────────────
    @Test
    fun defaultResolveMatchesShippedBehaviour() {
        assertEquals(
            RemoteAction.CHANNEL_UP,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_UP, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.CHANNEL_DOWN,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_DOWN, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.CHANNEL_UP,
            RemoteBindings.resolve(RemoteBindings.KEY_CHANNEL_UP, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.CHANNEL_DOWN,
            RemoteBindings.resolve(RemoteBindings.KEY_PAGE_DOWN, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_LEFT, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.OPEN_SETTINGS,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_RIGHT, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.OPEN_SETTINGS,
            RemoteBindings.resolve(RemoteBindings.KEY_MENU, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.SHOW_INFO,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_CENTER, false, UiContext.PLAYER_ONLY),
        )
    }

    @Test
    fun longPressBackAndContextFiltering() {
        assertEquals(
            RemoteAction.RETURN_LIVE,
            RemoteBindings.resolve(RemoteBindings.KEY_BACK, true, UiContext.PLAYER_ONLY),
        )
        assertNull(
            "覆盖层打开时任何键都不穿透（覆盖层先赢）",
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_UP, false, UiContext.MENU_OPEN),
        )
        assertNull(
            "覆盖层打开时任何键都不穿透（覆盖层先赢）",
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_UP, true, UiContext.EPG_VISIBLE),
        )
    }

    @Test
    fun catchupContextResolvesSeekAndInfo() {
        assertEquals(
            RemoteAction.SEEK_BACK,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_LEFT, false, UiContext.CATCHUP),
        )
        assertEquals(
            RemoteAction.SEEK_FORWARD,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_RIGHT, false, UiContext.CATCHUP),
        )
        assertEquals(
            RemoteAction.SHOW_INFO,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_CENTER, false, UiContext.CATCHUP),
        )
        assertEquals(
            "回看 UP 只出卡，不换台",
            RemoteAction.SHOW_INFO,
            RemoteBindings.resolve(RemoteBindings.KEY_DPAD_UP, false, UiContext.CATCHUP),
        )
    }

    // ── 老偏好迁移 ──────────────────────────────────────────────────────────
    @Test
    fun migrateDefaultKeepsDefault() {
        val migrated = RemoteBindings.migrate(RemoteHabits.DEFAULT)
        assertEquals(RemoteBindings.DEFAULT, migrated)
    }

    @Test
    fun migrateUpDownOpenDrawerRemapsChannelKeys() {
        val legacy = RemoteHabits.DEFAULT.copy(upDown = RemoteHabits.UpDown.OPEN_DRAWER)
        val migrated = RemoteBindings.migrate(legacy)
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolveWith(migrated, RemoteBindings.KEY_DPAD_UP, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolveWith(migrated, RemoteBindings.KEY_CHANNEL_DOWN, false, UiContext.PLAYER_ONLY),
        )
    }

    @Test
    fun migrateOkOpenDrawerRemapsOkKeys() {
        val legacy = RemoteHabits.DEFAULT.copy(ok = RemoteHabits.Ok.OPEN_DRAWER)
        val migrated = RemoteBindings.migrate(legacy)
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolveWith(migrated, RemoteBindings.KEY_DPAD_CENTER, false, UiContext.PLAYER_ONLY),
        )
    }

    @Test
    fun migrateBackExitAndChannelKeysDisabled() {
        val exit = RemoteBindings.migrate(RemoteHabits.DEFAULT.copy(back = RemoteHabits.Back.EXIT))
        assertEquals(
            RemoteAction.EXIT_APP,
            RemoteBindings.resolveWith(exit, RemoteBindings.KEY_BACK, true, UiContext.PLAYER_ONLY),
        )
        val noChannelKeys = RemoteBindings.migrate(RemoteHabits.DEFAULT.copy(channelKeysEnabled = false))
        assertNull(
            RemoteBindings.resolveWith(noChannelKeys, RemoteBindings.KEY_CHANNEL_UP, false, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            "DPAD_UP 仍换台（只禁用 166/167/92/93）",
            RemoteAction.CHANNEL_UP,
            RemoteBindings.resolveWith(noChannelKeys, RemoteBindings.KEY_DPAD_UP, false, UiContext.PLAYER_ONLY),
        )
    }

    // ── 序列化往返 / 损坏回退 ───────────────────────────────────────────────
    @Test
    fun serializeParseRoundTripAndCorruptFallback() {
        val migrated = RemoteBindings.migrate(RemoteHabits.DEFAULT.copy(back = RemoteHabits.Back.EXIT))
        val raw = RemoteBindings.serialize(migrated)
        assertEquals(migrated, RemoteBindings.parse(raw))
        assertEquals(RemoteBindings.DEFAULT, RemoteBindings.parse(null))
        assertEquals(RemoteBindings.DEFAULT, RemoteBindings.parse(""))
        assertEquals(RemoteBindings.DEFAULT, RemoteBindings.parse("garbage;;;"))
        assertEquals(RemoteBindings.DEFAULT, RemoteBindings.parse("a=NOT_AN_ACTION,s=19"))
    }

    // ── 冲突覆盖：绑定新键会从所有其它 action 移除该键 ──────────────────────
    @Test
    fun bindConflictingKeyRemovesItFromOldAction() {
        val colorKey = 187 // KEYCODE_BUTTON_1
        val bound = RemoteBindings.bind(RemoteBindings.DEFAULT, RemoteAction.CHANNEL_DOWN, colorKey, false)
        assertEquals(
            RemoteAction.CHANNEL_DOWN,
            RemoteBindings.resolveWith(bound, colorKey, false, UiContext.PLAYER_ONLY),
        )
        // 从其它 action 上解绑：把 colorKey 绑到别处后，CHANNEL_DOWN 不再持有它。
        val rebound = RemoteBindings.bind(bound, RemoteAction.SHOW_INFO, colorKey, false)
        assertEquals(
            RemoteAction.SHOW_INFO,
            RemoteBindings.resolveWith(rebound, colorKey, false, UiContext.PLAYER_ONLY),
        )
        assertFalse(rebound.first { it.action == RemoteAction.CHANNEL_DOWN }.shortPressKeycodes.contains(colorKey))
    }

    @Test
    fun summaryLabelsReflectBindings() {
        val (upDown, ok) = RemoteBindings.summaryLabels(RemoteBindings.DEFAULT)
        assertEquals("换台", upDown)
        assertEquals("显示信息卡", ok)
        val migrated = RemoteBindings.migrate(RemoteHabits.DEFAULT.copy(upDown = RemoteHabits.UpDown.OPEN_DRAWER))
        assertEquals("呼出频道列表", RemoteBindings.summaryLabels(migrated).first)
    }

    // ── t10：任意键（F1=131）绑定后必须能被中央 router 解析（真机缺口回归） ──
    @Test
    fun arbitraryFunctionKeyCanResolveToChannelDown() {
        val f1 = 131 // KEYCODE_F1 — 真机可注入验证的自定义键
        // 未绑定时任意键不解析（不会误触发）。
        assertNull(RemoteBindings.resolveWith(RemoteBindings.DEFAULT, f1, false, UiContext.PLAYER_ONLY))
        // 给「下一个频道」绑定 F1 后，router 必须解析出 CHANNEL_DOWN（fallback 据此切台）。
        val bound = RemoteBindings.bind(RemoteBindings.DEFAULT, RemoteAction.CHANNEL_DOWN, f1, false)
        assertEquals(
            RemoteAction.CHANNEL_DOWN,
            RemoteBindings.resolveWith(bound, f1, false, UiContext.PLAYER_ONLY),
        )
        // 往返后仍然生效（真机重启/Prefs 读回）。
        assertEquals(
            RemoteAction.CHANNEL_DOWN,
            RemoteBindings.resolveWith(RemoteBindings.parse(RemoteBindings.serialize(bound)), f1, false, UiContext.PLAYER_ONLY),
        )
        // 覆盖层打开（MENU_OPEN）时任何键都不穿透（fallback 守卫的语义基础）。
        assertNull(RemoteBindings.resolveWith(bound, f1, false, UiContext.MENU_OPEN))
    }

    // ── V4 §3（A 方案）：RIGHT 默认 + 全部频道 → 进列表；自定义绑定尊重用户 ──
    @Test
    fun rightKeyAPlanAllGroupOpensDrawerOnlyForDefaultBinding() {
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolveRight(RemoteAction.OPEN_SETTINGS, "全部", false, false),
        )
        assertEquals(
            RemoteAction.OPEN_SETTINGS,
            RemoteBindings.resolveRight(RemoteAction.OPEN_SETTINGS, "央视频道", false, false),
        )
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolveRight(RemoteAction.OPEN_SETTINGS, "央视频道", true, false),
        )
        assertEquals(
            RemoteAction.OPEN_SETTINGS,
            RemoteBindings.resolveRight(RemoteAction.OPEN_SETTINGS, "全部", false, true),
        )
        assertEquals(
            RemoteAction.SEEK_FORWARD,
            RemoteBindings.resolveRight(RemoteAction.SEEK_FORWARD, "全部", false, false),
        )
    }

    @Test
    fun isKeyCustomBoundDetectsRightRebinding() {
        assertFalse(RemoteBindings.isKeyCustomBound(RemoteBindings.DEFAULT, RemoteBindings.KEY_DPAD_RIGHT))
        val rebound = RemoteBindings.bind(RemoteBindings.DEFAULT, RemoteAction.CHANNEL_DOWN, RemoteBindings.KEY_DPAD_RIGHT, false)
        assertTrue(RemoteBindings.isKeyCustomBound(rebound, RemoteBindings.KEY_DPAD_RIGHT))
    }
}
