package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t13（docs/SETTINGS_IA_V2.md §8.3）：设置二级 IA 的结构契约 —— 一级恰好 6 个入口且顺序固定、
 * 每个二级页有可聚焦行且不重复、旧 tag 全部可映射、退役集合只含 `settings_epg_source`。
 */
class SettingsIaTest {

    @Test
    fun rootHasExactlySevenEntriesInTheApprovedOrder() {
        assertEquals(7, SettingsIA.ROOT_ORDER.size)
        assertEquals(
            listOf(
                SettingsPage.GENERAL,
                SettingsPage.PLAYLISTS,
                SettingsPage.EPG,
                SettingsPage.APPEARANCE,
                SettingsPage.PLAYBACK,
                SettingsPage.REMOTE,
                SettingsPage.ABOUT,
            ),
            SettingsIA.ROOT_ORDER,
        )
        assertEquals(
            listOf("通用", "节目列表", "节目预告", "外观", "播放解码", "遥控设置", "关于与更新"),
            SettingsIA.ROOT_ORDER.map { it.title },
        )
    }

    @Test
    fun everyPageHasNonEmptyUniqueItems() {
        SettingsPage.entries.forEach { page ->
            val tags = SettingsIA.itemTags(page)
            assertTrue("$page 必须至少有一行", tags.isNotEmpty())
            assertEquals("$page 的行 tag 不能重复", tags.size, tags.distinct().size)
        }
    }

    @Test
    fun everyPageStaysWithinTheFirstScreenBudget() {
        // 每个二级页保持精简（≤16 行，支持 scrollable 分组）。
        SettingsPage.entries.forEach { page ->
            assertTrue(
                "$page 有 ${SettingsIA.itemTags(page).size} 行，超过 16 行预算",
                SettingsIA.itemTags(page).size <= 16,
            )
        }
    }

    @Test
    fun rootTagsAreDistinctAndNotPageItems() {
        val rootTags = SettingsIA.ROOT_ORDER.map { SettingsIA.rootTag(it) }
        assertEquals(7, rootTags.distinct().size)
        val pageItems = SettingsPage.entries.flatMap { SettingsIA.itemTags(it) }.toSet()
        rootTags.forEach { rootTag ->
            assertFalse("一级 tag $rootTag 不能出现在二级行里", rootTag in pageItems)
            assertTrue(rootTag.startsWith("settings_root_"))
        }
    }

    @Test
    fun pageTitleTagsAreStable() {
        SettingsPage.entries.forEach { page ->
            assertEquals("settings_page_${page.id}", SettingsIA.pageTitleTag(page))
        }
        assertEquals("settings_page_playback", SettingsIA.pageTitleTag(SettingsPage.PLAYBACK))
        assertEquals("settings_page_about", SettingsIA.pageTitleTag(SettingsPage.ABOUT))
    }

    @Test
    fun legacyTagsAllMapToAPage() {
        SettingsIA.LEGACY_ACTION_TAGS.forEach { tag ->
            assertTrue(
                "旧 tag $tag 必须映射到某个页面（或在 RETIRED_TAGS 里）",
                tag in SettingsIA.LEGACY_TAG_PAGE || tag in SettingsIA.RETIRED_TAGS,
            )
        }
        // 除退役项外，每个旧 tag 都要真的出现在对应页面的行里（零丢失）。
        SettingsIA.LEGACY_ACTION_TAGS.filterNot { it in SettingsIA.RETIRED_TAGS }.forEach { tag ->
            val page = SettingsIA.LEGACY_TAG_PAGE.getValue(tag)
            assertTrue("$tag 应出现在 ${page.title} 页", tag in SettingsIA.itemTags(page))
        }
    }

    @Test
    fun retiredSetIsExactlyTheMergedEpgAndRetiredBackRows() {
        assertEquals(setOf("settings_epg_source", "settings_remote_back"), SettingsIA.RETIRED_TAGS)
        // 合并后的手动 EPG 刷新仍由 settings_refresh_epg 承载，且全仓只此一个。
        assertTrue("settings_refresh_epg" in SettingsIA.itemTags(SettingsPage.EPG))
        val manualEpgTags = SettingsPage.entries
            .flatMap { SettingsIA.itemTags(it) }
            .filter { it == "settings_refresh_epg" || it == "settings_epg_source" }
        assertEquals(listOf("settings_refresh_epg"), manualEpgTags)
    }

    @Test
    fun rootEntriesUseTheGivenSummariesAndFallBackToDash() {
        val entries = SettingsIA.rootEntries(
            mapOf(SettingsPage.GENERAL to "主页画中画 · WebUI 备份", SettingsPage.ABOUT to "v0.2.0")
        )
        assertEquals(7, entries.size)
        assertEquals("主页画中画 · WebUI 备份", entries[0].summary)
        assertEquals("v0.2.0", entries[6].summary)
        assertEquals("缺失摘要用 —", SettingsSummary.EMPTY, entries[1].summary)
        assertEquals("settings_root_playlists", entries[1].tag)
    }

    @Test
    fun listTagMatchesTheLiteralTheStaticGateChecks() {
        assertEquals("settings_list", SettingsIA.LIST_TAG)
    }
}
