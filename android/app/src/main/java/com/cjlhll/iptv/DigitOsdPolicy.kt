package com.cjlhll.iptv

/**
 * t223: digit-OSD timing policy (pure, unit-tested).
 *
 * The device found that with 「数字键 = 先显示输入条需OK」 the OSD stayed on screen indefinitely: only
 * the DIRECT mode started the 3 s timer, so NEEDS_CONFIRM cancelled the job and never re-armed it.
 *
 * Both modes now share the same timeout; what differs is what happens when it fires:
 *  - DIRECT       → submit the typed number (jump), then clear;
 *  - NEEDS_CONFIRM→ just clear (no jump — the user still has to press OK).
 */
object DigitOsdPolicy {

    /** Same value as the DIRECT branch has always used. */
    const val TIMEOUT_MS = 3_000L

    /** Matches the existing input cap. */
    const val MAX_DIGITS = 6

    enum class TimeoutAction { SUBMIT, CLEAR }

    /** What the timer must do when it fires. */
    fun timeoutAction(mode: RemoteHabits.Digits): TimeoutAction =
        if (mode == RemoteHabits.Digits.DIRECT) TimeoutAction.SUBMIT else TimeoutAction.CLEAR

    /** t223: every digit input (including an append) restarts the timer. */
    fun restartOnEveryInput(): Boolean = true

    /** Appending is ignored past [MAX_DIGITS] so the OSD cannot grow unbounded. */
    fun append(current: String, digit: Int): String =
        if (current.length >= MAX_DIGITS) current else current + digit.toString()

    /** OK submits immediately (both modes), BACK/overlay cancels immediately. */
    fun onConfirm(): TimeoutAction = TimeoutAction.SUBMIT

    fun onCancel(): TimeoutAction = TimeoutAction.CLEAR

    /** An open overlay must hide the OSD instead of leaving it on top. */
    fun visibleWhen(anyOverlayOpen: Boolean): Boolean = !anyOverlayOpen

    data class DigitMatch(
        val groupIndex: Int,
        val number: Int,
        val title: String,
        val logoUrl: String?,
    )

    /** 数字输入实时匹配频道：精确频道号优先，其次前缀匹配，最多返回 [limit] 条。 */
    fun matchChannels(digits: String, groups: List<ChannelGroup>, limit: Int = 6): List<DigitMatch> {
        if (digits.isBlank() || groups.isEmpty() || limit <= 0) return emptyList()
        val exact = ArrayList<DigitMatch>(1)
        val prefix = ArrayList<DigitMatch>(limit)
        for (i in groups.indices) {
            val number = i + 1
            val numStr = number.toString()
            if (numStr == digits) {
                val ch = groups[i].defaultChannel
                exact.add(DigitMatch(i, number, groups[i].displayTitle, groups[i].logoUrl ?: ch.logoUrl))
            } else if (numStr.startsWith(digits) && prefix.size < limit) {
                val ch = groups[i].defaultChannel
                prefix.add(DigitMatch(i, number, groups[i].displayTitle, groups[i].logoUrl ?: ch.logoUrl))
            }
        }
        return (exact + prefix).take(limit)
    }
}
