package com.cjlhll.iptv

/**
 * Stable, collision-free LazyColumn keys for the drawer (t46).
 *
 * A real source can legitimately contain a group whose name is "全部" (identical text to the
 * synthesised first entry from [ChannelGrouper.sortedDrawerGroups]) and can contain two programmes
 * with identical channelId+startMillis+endMillis. Using the raw value as a key then produces
 * duplicate keys and Compose throws IllegalArgumentException when the drawer list is built. An
 * index tie-breaker guarantees uniqueness while keeping keys stable across ordinary refreshes
 * (list order is preserved, so the same (value,index) yields the same key).
 */
internal object DrawerKeys {
    const val ALL_GROUPS_KEY = "@all"

    /** index 0 is always the synthesised "全部"; real groups (index >= 1) get a distinct prefix. */
    fun groupKey(name: String, index: Int): String =
        if (index == 0) ALL_GROUPS_KEY else "G:$name#$index"

    fun programKey(channelId: String, startMillis: Long, endMillis: Long, index: Int): String =
        "P:$channelId:$startMillis:$endMillis#$index"

    fun dateKey(date: String, index: Int): String = "D:$date#$index"
}
