package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t4（docs/INFO_CARD_V2.md §7.2）：信息卡底部「最近观看」的数据侧规则 —— 排除当前台、去重、
 * ≤6、最新在前、空态；以及切台后的 A↔B 快跳推演。
 */
class InfoCardRecentTest {

    private fun channel(title: String, url: String, logo: String? = null) = Channel(
        title = title,
        url = url,
        logoUrl = logo,
    )

    private fun group(key: String, title: String, url: String, logo: String? = null) = ChannelGroup(
        key = key,
        displayTitle = title,
        logoUrl = logo,
        group = null,
        variants = listOf(ChannelVariant(channel(title, url, logo), "标清")),
        defaultVariantIndex = 0,
    )

    private fun entry(key: String, url: String, variantIndex: Int = 0) = RecentChannels.Entry(
        groupKey = key,
        number = 1,
        title = key,
        logoUrl = null,
        variantIndex = variantIndex,
        fallbackUrl = url,
    )

    private val groups = listOf(
        group("g1", "CCTV-1", "http://a/1"),
        group("g2", "CCTV-2", "http://a/2"),
        group("g3", "CCTV-3", "http://a/3"),
        group("g4", "湖南卫视", "http://a/4"),
        group("g5", "东方卫视", "http://a/5"),
        group("g6", "浙江卫视", "http://a/6"),
        group("g7", "江苏卫视", "http://a/7"),
        group("g8", "北京卫视", "http://a/8"),
    )

    private fun ui(entries: List<RecentChannels.Entry>, currentKey: String?): List<RecentChannelUi> =
        RecentChannels.resolvedDistinct(entries, groups).map { resolved ->
            RecentChannelUi(
                title = resolved.group.displayTitle,
                number = resolved.groupIndex + 1,
                logoUrl = resolved.group.logoUrl ?: resolved.channel.logoUrl,
                selected = resolved.group.key == currentKey,
                groupIndex = resolved.groupIndex,
                variantIndex = resolved.variantIndex,
            )
        }

    @Test
    fun cardExcludesCurrentChannelAndCapsAtSix() {
        // 10 条存储（含当前台、含解析不到的 legacy url 行、含重复）
        val entries = listOf(
            entry("g3", "http://a/3"),
            entry("url:http://a/999", "http://a/999"),   // 解析不到 → 跳过
            entry("g1", "http://a/1"),
            entry("g2", "http://a/2"),
            entry("g4", "http://a/4"),
            entry("g5", "http://a/5"),
            entry("g6", "http://a/6"),
            entry("g7", "http://a/7"),
            entry("g8", "http://a/8"),
            entry("g1", "http://a/1"),                    // 重复 → 去重
        )
        val all = ui(entries, currentKey = "g3")
        val card = InfoCardPolicy.cardRecentItems(all) { it.selected }

        assertEquals("最多 6 张", InfoCardPolicy.MAX_RECENT, card.size)
        assertEquals("当前台只出现 0 次", 0, card.count { it.title == "CCTV-3" })
        assertEquals("顺序 = 最近优先（剔除当前台后第一条是 g1）", "CCTV-1", card.first().title)
        assertTrue("每条都带可切台索引", card.all { it.groupIndex >= 0 })
    }

    @Test
    fun emptyOrOnlyCurrentChannelYieldsNoRow() {
        assertTrue(InfoCardPolicy.cardRecentItems(emptyList<RecentChannelUi>()) { false }.isEmpty())

        val onlyCurrent = ui(listOf(entry("g1", "http://a/1")), currentKey = "g1")
        val card = InfoCardPolicy.cardRecentItems(onlyCurrent) { it.selected }
        assertTrue("只有当前台时不渲染最近观看条", card.isEmpty())
        assertFalse(InfoCardPolicy.showRecentRow(card.size, catchup = false, seekOverlayVisible = false))
    }

    @Test
    fun switchingBackAndForthAlternatesBetweenTwoChannels() {
        // A=当前台(g1)，历史最近 = g2 → 卡片只有 g2；切到 g2 后卡片第一条变回 g1。
        val history = listOf(entry("g2", "http://a/2"), entry("g1", "http://a/1"))

        val firstCard = InfoCardPolicy.cardRecentItems(ui(history, currentKey = "g1")) { it.selected }
        assertEquals(listOf("CCTV-2"), firstCard.map { it.title })

        val secondCard = InfoCardPolicy.cardRecentItems(ui(history, currentKey = "g2")) { it.selected }
        assertEquals(listOf("CCTV-1"), secondCard.map { it.title })

        // 切台后焦点回到第 0 张（规格 §5.2）
        assertEquals(0, InfoCardPolicy.nextFocusIndex(3, secondCard.size, -99))
    }

    @Test
    fun cardItemsKeepVariantIndexForTheCurrentLine() {
        val multi = listOf(
            ChannelGroup(
                key = "gx",
                displayTitle = "多线路台",
                logoUrl = null,
                group = null,
                variants = listOf(
                    ChannelVariant(channel("多线路台", "http://x/1"), "标清"),
                    ChannelVariant(channel("多线路台", "http://x/2"), "高清"),
                ),
                defaultVariantIndex = 0,
            ),
        )
        val resolved = RecentChannels.resolvedDistinct(listOf(entry("gx", "http://x/2", 1)), multi)
        val card = resolved.map {
            RecentChannelUi(
                title = it.group.displayTitle,
                number = it.groupIndex + 1,
                logoUrl = null,
                selected = false,
                groupIndex = it.groupIndex,
                variantIndex = it.variantIndex,
            )
        }
        assertEquals(1, card.size)
        assertEquals("切台应回到用户上次看的那条线路", 1, card.first().variantIndex)
    }

    // ── FULLFIX §5（U1）：高亮 = 真焦点 = OK 目标，同一真源 focusIndex ────────
    @Test
    fun focusTargetAlwaysFollowsHighlightedIndex() {
        // focusIndex 是唯一真源：目标索引恒等于 clamp(focusIndex)，空列表 → -1（不请求焦点）。
        assertEquals(0, InfoCardPolicy.focusTargetIndex(0, 6))
        assertEquals(1, InfoCardPolicy.focusTargetIndex(1, 6))
        assertEquals(5, InfoCardPolicy.focusTargetIndex(5, 6))
        assertEquals(5, InfoCardPolicy.focusTargetIndex(99, 6)) // 越界 clamp 到末尾，不环绕
        assertEquals(0, InfoCardPolicy.focusTargetIndex(-3, 6))
        assertEquals(-1, InfoCardPolicy.focusTargetIndex(0, 0)) // 空条无目标
    }

    @Test
    fun okTargetIsTheHighlightedCardNotTheEventSource() {
        // OK→OK→右→OK：高亮从卡 0 移到卡 1 后，OK 必须切到卡 1 对应的台。
        val cards = listOf("A", "B", "C", "D")
        assertEquals("初始 focusIndex=0 → 卡 A", "A", InfoCardPolicy.resolveRecentTarget(cards, 0))
        val moved = InfoCardPolicy.nextFocusIndex(0, cards.size, 1)
        assertEquals(1, moved)
        assertEquals("右移后 focusIndex=1 → OK 切到卡 B", "B", InfoCardPolicy.resolveRecentTarget(cards, moved))
        assertNull("空列表 OK 不崩、不切台", InfoCardPolicy.resolveRecentTarget(emptyList<String>(), 0))
        assertNull("越界 focusIndex OK 不崩", InfoCardPolicy.resolveRecentTarget(cards, 99))
    }
}
