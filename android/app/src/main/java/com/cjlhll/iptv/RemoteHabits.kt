package com.cjlhll.iptv

/**
 * t186 (F1): remote-control habits — pure decision logic.
 *
 * The Activity asks these functions what a key should do; nothing here touches Compose, the player or
 * Android APIs, so every mapping is unit-testable. **Existing overlays always win**: when the drawer,
 * settings, quality dialog, recent list or audio dialog is open the habits are bypassed and the
 * overlay keeps its own D-pad/OK/BACK handling.
 */
object RemoteHabits {

    enum class UpDown(val label: String, val labelEn: String) {
        CHANNEL_STEP("换台", "Zap channels"),
        OPEN_DRAWER("呼出频道列表", "Open channel list");

        fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) labelEn else label
    }

    enum class Ok(val label: String) {
        OPEN_DRAWER("打开频道列表"),
        // t4 (INFO_CARD_V2 D-C1 / cap·R1)：枚举名不变（已存偏好继续可解析），只改 label；
        // 频道列表仍由 LEFT 一键打开，设置由 RIGHT 一键打开。
        CONFIRM("显示信息卡");

        fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) {
            when (this) {
                OPEN_DRAWER -> "Open channel list"
                CONFIRM -> "Show info card"
            }
        } else label
    }

    enum class Back(val label: String, val labelEn: String) {
        CLOSE_OVERLAY("关闭抽屉", "Close overlay"),
        EXIT("直接退出", "Exit directly");

        fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) labelEn else label
    }

    enum class Digits(val label: String, val labelEn: String) {
        DIRECT("直接跳台", "Auto-jump"),
        NEEDS_CONFIRM("先显示输入条需OK", "Require OK");

        fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) labelEn else label
    }

    data class Settings(
        val upDown: UpDown = UpDown.CHANNEL_STEP,
        val ok: Ok = Ok.CONFIRM,
        val back: Back = Back.CLOSE_OVERLAY,
        val digits: Digits = Digits.DIRECT,
        val channelKeysEnabled: Boolean = true,
    )

    /**
     * t186/t193 (DEFAULT-01): the shipped defaults. Documented in docs/REMOTE_HABITS_SETTINGS*.md:
     *  - 上下键 = 换台
     *  - OK键   = 显示信息卡 (t4/D-C1；回看中同样是显示信息条，见 [onOk])
     *  - Back键 = 关闭抽屉 (双按退出)
     *  - 数字键 = 直接跳台
     *  - 频道键 166/167 = 启用
     *
     * 一行回退：`ok = Ok.OPEN_DRAWER`（枚举名不变，Prefs 无需迁移）。
     */
    val DEFAULT = Settings()

    // ---- persistence helpers (names are stable Prefs values) ----
    fun parseUpDown(raw: String?): UpDown = UpDown.entries.firstOrNull { it.name == raw } ?: DEFAULT.upDown
    fun parseOk(raw: String?): Ok = Ok.entries.firstOrNull { it.name == raw } ?: DEFAULT.ok
    fun parseBack(raw: String?): Back = Back.entries.firstOrNull { it.name == raw } ?: DEFAULT.back
    fun parseDigits(raw: String?): Digits = Digits.entries.firstOrNull { it.name == raw } ?: DEFAULT.digits

    fun nextUpDown(v: UpDown): UpDown = UpDown.entries[(v.ordinal + 1) % UpDown.entries.size]
    fun nextOk(v: Ok): Ok = Ok.entries[(v.ordinal + 1) % Ok.entries.size]
    fun nextBack(v: Back): Back = Back.entries[(v.ordinal + 1) % Back.entries.size]
    fun nextDigits(v: Digits): Digits = Digits.entries[(v.ordinal + 1) % Digits.entries.size]

    // ---- decisions ----
    enum class UpDownAction { NONE, CHANNEL_UP, CHANNEL_DOWN, OPEN_DRAWER }

    /**
     * UP/DOWN while no overlay is open.
     * @param overlayOpen any of drawer/settings/quality/recent/audio is showing (guard wins)
     */
    fun onUpDown(settings: Settings, overlayOpen: Boolean, up: Boolean): UpDownAction {
        if (overlayOpen) return UpDownAction.NONE
        return when (settings.upDown) {
            UpDown.CHANNEL_STEP -> if (up) UpDownAction.CHANNEL_UP else UpDownAction.CHANNEL_DOWN
            UpDown.OPEN_DRAWER -> UpDownAction.OPEN_DRAWER
        }
    }

    enum class OkAction { NONE, OPEN_DRAWER, SHOW_INFO }

    fun onOk(settings: Settings, overlayOpen: Boolean, catchupPlayback: Boolean = false): OkAction {
        if (overlayOpen) return OkAction.NONE
        // t193 (CATCHUP-01): while a catch-up programme is playing, OK must NOT open the channel list
        // (that switches channels and would drop the replay). It shows the info banner instead, which
        // is also what the pre-F1 behaviour was.
        if (catchupPlayback) return OkAction.SHOW_INFO
        return when (settings.ok) {
            Ok.OPEN_DRAWER -> OkAction.OPEN_DRAWER
            Ok.CONFIRM -> OkAction.SHOW_INFO
        }
    }

    enum class BackAction { NONE, CLOSE_OVERLAY, EXIT }

    /**
     * BACK when the player itself is in front (no overlay): either it is swallowed so the next BACK
     * exits (关闭抽屉 mode keeps the historical double-press guard), or it exits immediately.
     */
    fun onBack(settings: Settings, overlayOpen: Boolean): BackAction {
        if (overlayOpen) return BackAction.CLOSE_OVERLAY // overlay handling is unchanged and always wins
        return when (settings.back) {
            Back.CLOSE_OVERLAY -> BackAction.CLOSE_OVERLAY
            Back.EXIT -> BackAction.EXIT
        }
    }

    enum class DigitAction { DIRECT, NEEDS_CONFIRM }

    /** Digits always feed the OSD; the difference is whether Enter is required to submit. */
    fun onDigit(settings: Settings): DigitAction = when (settings.digits) {
        Digits.DIRECT -> DigitAction.DIRECT
        Digits.NEEDS_CONFIRM -> DigitAction.NEEDS_CONFIRM
    }

    /** 166/167 (CHANNEL_UP/DOWN key codes) can be disabled entirely. */
    fun channelKeysEnabled(settings: Settings): Boolean = settings.channelKeysEnabled
}
