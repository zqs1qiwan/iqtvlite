package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * t13（docs/SETTINGS_IA_V2.md §4/§8.3）：**零丢失**契约 —— 基线 24 个可操作行全部映射到新 IA，
 * 唯一合并是「更新EPG」=「立即刷新 EPG」（同一动作 requestManualEpg）。
 *
 * 除了模型断言，还直接读源码确认"模型改了、UI 没落地"这种假通过不会发生。
 */
class SettingsZeroLossTest {

    private fun sourceDir(): File {
        val candidates = listOf(
            "src/tivimini/java/com/cjlhll/iptv",             // Gradle test working dir = android/app
            "app/src/tivimini/java/com/cjlhll/iptv",         // working dir = android
            "../app/src/tivimini/java/com/cjlhll/iptv",
        ).map { File(it) }
        return candidates.firstOrNull { it.isDirectory }
            ?: error("cannot locate app sources; tried ${candidates.map { it.absolutePath }}")
    }

    private fun read(name: String): String = File(sourceDir(), name).readText(Charsets.UTF_8)

    @Test
    fun baselineHasTwentyFourActionRows() {
        assertEquals(24, SettingsIA.LEGACY_ACTION_TAGS.size)
        assertEquals(24, SettingsIA.LEGACY_ACTION_TAGS.distinct().size)
    }

    @Test
    fun everyLegacyTagIsEitherMappedOrRetired() {
        SettingsIA.LEGACY_ACTION_TAGS.forEach { tag ->
            val mapped = tag in SettingsIA.LEGACY_TAG_PAGE
            val retired = tag in SettingsIA.RETIRED_TAGS
            assertTrue("$tag 既没映射也没标记退役", mapped || retired)
            if (retired) {
                assertTrue("退役项仍应保留映射（便于迁移与门禁）", mapped)
            }
        }
        assertEquals(1, SettingsIA.RETIRED_TAGS.size)
    }

    @Test
    fun drawerReallyRendersEveryNonRetiredRow() {
        val drawer = read("SettingsDrawer.kt")
        SettingsPage.entries.flatMap { SettingsIA.itemTags(it) }
            .filterNot { it in SettingsIA.RETIRED_TAGS }
            .forEach { tag ->
                val present = if (tag == RecentDialogTags.SETTINGS_ENTRY) {
                    // 最近观看入口沿用既有常量（tag 值相同，避免两处字面量漂移）。
                    drawer.contains("RecentDialogTags.SETTINGS_ENTRY")
                } else {
                    drawer.contains("\"$tag\"")
                }
                assertTrue("SettingsDrawer.kt 缺少可操作行 $tag（零丢失）", present)
            }
    }

    @Test
    fun retiredEpgSourceTagIsGoneFromTheUi() {
        val drawer = read("SettingsDrawer.kt")
        assertTrue(
            "退役 tag settings_epg_source 不得再出现在抽屉 UI 里",
            !drawer.contains("\"settings_epg_source\""),
        )
        // 合并后的行仍在，且只出现一次（一个手动刷新入口，t189 单路径）。
        assertEquals(1, Regex("settings_refresh_epg").findAll(drawer).count())
    }

    @Test
    fun newLogoRefreshTagExists() {
        val drawer = read("SettingsDrawer.kt")
        assertTrue("新增 tag settings_logo_refresh", drawer.contains("\"settings_logo_refresh\""))
        assertTrue("settings_logo_refresh" in SettingsIA.itemTags(SettingsPage.LOGO))
    }

    @Test
    fun recentWatchingEntryLivesInTheDataPage() {
        assertTrue(RecentDialogTags.SETTINGS_ENTRY in SettingsIA.itemTags(SettingsPage.DATA))
        assertEquals(
            SettingsPage.DATA,
            SettingsIA.LEGACY_TAG_PAGE.getValue(RecentDialogTags.SETTINGS_ENTRY),
        )
        val drawer = read("SettingsDrawer.kt")
        assertTrue("最近观看对话框入口仍在（数据与更新 → 观看记录）", drawer.contains("onOpenRecent"))
    }

    @Test
    fun noLazyColumnWasIntroduced() {
        val drawer = read("SettingsDrawer.kt")
        assertTrue("设置列表必须继续用 verticalScroll（不引入 LazyColumn）", !drawer.contains("LazyColumn"))
        assertTrue(drawer.contains("verticalScroll("))
        assertTrue(drawer.contains("bringIntoViewRequester("))
        assertTrue(drawer.contains("bringIntoView()"))
    }

    @Test
    fun groupHeaderStaysALabel() {
        val drawer = read("SettingsDrawer.kt")
        val idx = drawer.indexOf("private fun SettingsGroupHeader(")
        assertTrue("SettingsGroupHeader 必须保留（check_settings_scroll.py 依赖）", idx >= 0)
        val block = drawer.substring(idx, minOf(drawer.length, idx + 600))
        assertTrue("分区标题不可聚焦", !block.contains("focusable()") && !block.contains("clickable {"))
    }

    @Test
    fun settingsListTagIsALiteralSoTheStaticGateKeepsWorking() {
        val drawer = read("SettingsDrawer.kt")
        assertTrue(
            "check_settings_scroll.py 按字面量查找 settings_list，抽屉里必须保留该字面量",
            drawer.contains("\"settings_list\""),
        )
        assertEquals("settings_list", SettingsIA.LIST_TAG)
    }
}
