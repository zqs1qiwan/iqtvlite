package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the t43 finding: on a real source a group can be named "全部" (clashing with the
 * synthesised first entry) and a channel's EPG can contain two programmes with identical
 * channelId+start+end. Raw-value LazyColumn keys then collide and Compose throws
 * IllegalArgumentException when the drawer is built. These tests pin the key generator to be
 * strictly unique on such data, and stable (deterministic) across calls.
 */
class DrawerKeysTest {

    @Test
    fun groupKeysAreUniqueWhenRealGroupIsNamedAll() {
        // index 0 = synthesised "全部"; index 1 = a REAL group also named "全部".
        val groups = listOf("全部", "全部", "全部", "央视频道", "卫视")
        val keys = groups.mapIndexed { i, name -> DrawerKeys.groupKey(name, i) }
        assertEquals("synthetic 全部 must use the dedicated prefix", DrawerKeys.ALL_GROUPS_KEY, keys[0])
        assertNotEquals("real 全部 must not collide with synthetic", keys[0], keys[1])
        assertEquals("keys must be strictly unique", keys.size, keys.toSet().size)
    }

    @Test
    fun programKeysAreUniqueForDuplicateStartEnd() {
        // Two programmes same channel + identical start/end (plus a normal one).
        val keys = listOf(
            DrawerKeys.programKey("c1", 1000L, 2000L, 0),
            DrawerKeys.programKey("c1", 1000L, 2000L, 1),
            DrawerKeys.programKey("c1", 3000L, 4000L, 2)
        )
        assertEquals("duplicate start/end must still yield unique keys", keys.size, keys.toSet().size)
    }

    @Test
    fun dateKeysAreUniqueForDuplicateDates() {
        val keys = listOf(
            DrawerKeys.dateKey("2026-11-20", 0),
            DrawerKeys.dateKey("2026-11-20", 1),
            DrawerKeys.dateKey("2026-11-21", 2)
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun keysAreStableAcrossCalls() {
        assertEquals(DrawerKeys.groupKey("央视", 2), DrawerKeys.groupKey("央视", 2))
        assertEquals(DrawerKeys.programKey("c1", 1L, 2L, 3), DrawerKeys.programKey("c1", 1L, 2L, 3))
        assertEquals(DrawerKeys.dateKey("2026-11-20", 4), DrawerKeys.dateKey("2026-11-20", 4))
        assertTrue(DrawerKeys.ALL_GROUPS_KEY.isNotEmpty())
    }
}
