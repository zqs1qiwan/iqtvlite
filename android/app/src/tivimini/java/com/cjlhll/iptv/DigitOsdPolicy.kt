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
}
