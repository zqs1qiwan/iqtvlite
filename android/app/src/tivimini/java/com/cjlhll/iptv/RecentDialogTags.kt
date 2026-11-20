package com.cjlhll.iptv

/**
 * t167: stable semantics ids for the recent-channels dialog, so uiautomator / instrumentation can
 * find and click the rows without depending on Chinese label text.
 *
 * Pure Kotlin (no Compose import) so the naming contract is unit-testable and the tags cannot drift
 * between the dialog and the tests.
 */
object RecentDialogTags {
    const val DIALOG = "recent_dialog"
    const val LIST = "recent_list"
    const val EMPTY = "recent_empty"
    const val CLEAR = "recent_clear"
    const val CLOSE = "recent_close"
    const val SETTINGS_ENTRY = "settings_recent_entry"

    fun row(index: Int): String = "recent_row_$index"

    /** log line written when the user clears the list (acceptance greps for it). */
    fun clearedLog(count: Int = 0): String = "cleared=true count=$count"

    fun isEmptyStateVisible(count: Int): Boolean = count == 0
}
