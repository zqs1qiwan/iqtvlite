package com.cjlhll.iptv

/**
 * IQTV Lite「功能 → 按键」全映射（纯 Kotlin，Android/Compose-free）。
 *
 * 旧模型 [RemoteHabits] 是 5 个固定二选一开关；本模型把「功能」放在第一位，每个功能可绑定
 * 任意 keycode（短按/长按各一组）。老偏好通过 [migrate] 一次性迁移。
 *
 * 关键不变量：
 * - [UiContext.MENU_OPEN]（任何覆盖层打开）→ [resolve] 返回 null，覆盖层自己消费按键；
 * - 回看（[UiContext.CATCHUP]）优先匹配用户显式绑定的 SEEK_* / RETURN_LIVE，其余由 LEFT/RIGHT → SEEK_*、OK/UP/DOWN → SHOW_INFO 原生承载；
 * - 长短按由调用方从 KeyEvent 传入（isLongPress / repeatCount），这里只查对应集合；
 * - 未知/损坏的序列化串 → [DEFAULT]，不崩。
 */
enum class RemoteAction(val label: String, val labelEn: String) {
    CHANNEL_UP("上一个频道", "Previous channel"),
    CHANNEL_DOWN("下一个频道", "Next channel"),
    OPEN_DRAWER("打开频道列表", "Open channel list"),
    OPEN_SETTINGS("打开设置", "Open settings"),
    SHOW_INFO("显示信息卡", "Show info card"),
    RECENT_CHANNELS("最近观看", "Recent channels"),
    LAST_CHANNEL("上一台", "Last channel"),
    RETURN_LIVE("返回直播", "Return to live"),
    SEEK_BACK("快退", "Seek back"),
    SEEK_FORWARD("快进", "Seek forward"),
    PLAY_PAUSE("播放/暂停", "Play / Pause"),
    VOLUME_UP("音量+", "Volume +"),
    VOLUME_DOWN("音量-", "Volume -"),
    EXIT_APP("退出应用", "Exit app"),
    NONE("未绑定", "Unbound");

    fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) labelEn else label
}

/** 当前 UI 上下文；覆盖层打开时用 [UiContext.MENU_OPEN] 表示“覆盖层先赢”。 */
enum class UiContext { PLAYER_ONLY, EPG_VISIBLE, MENU_OPEN, CATCHUP }

/** 一个功能对应的按键集合（短按 / 长按）。 */
data class KeyBinding(
    val action: RemoteAction,
    val shortPressKeycodes: Set<Int> = emptySet(),
    val longPressKeycodes: Set<Int> = emptySet(),
)

object RemoteBindings {

    /** 新偏好 key（Prefs 里为 SharedPreferences 键）。 */
    const val PREFS_KEY = "remote_action_bindings_v1"

    // Android KeyEvent 常量（这里写成字面量，保持本文件 Android-free）。
    const val KEY_DPAD_UP = 19
    const val KEY_DPAD_DOWN = 20
    const val KEY_DPAD_LEFT = 21
    const val KEY_DPAD_RIGHT = 22
    const val KEY_DPAD_CENTER = 23
    const val KEY_BACK = 4
    const val KEY_MENU = 82
    /** t52 §⑧：设备可用"彩键"设置入口（MENU 在部分 TV launcher 被拦截）。 */
    const val KEY_BUTTON_1 = 187
    const val KEY_ENTER = 66
    const val KEY_NUMPAD_ENTER = 160
    const val KEY_CHANNEL_UP = 166
    const val KEY_CHANNEL_DOWN = 167
    const val KEY_PAGE_UP = 92
    const val KEY_PAGE_DOWN = 93
    const val KEY_LAST_CHANNEL = 229

    /** 设置页可配置的功能（按展示顺序）；NONE 等内部项不在 UI 列出。 */
    val CONFIGURABLE_ACTIONS: List<RemoteAction> = listOf(
        RemoteAction.CHANNEL_UP,
        RemoteAction.CHANNEL_DOWN,
        RemoteAction.OPEN_DRAWER,
        RemoteAction.OPEN_SETTINGS,
        RemoteAction.SHOW_INFO,
        RemoteAction.RECENT_CHANNELS,
        RemoteAction.LAST_CHANNEL,
        RemoteAction.RETURN_LIVE,
        RemoteAction.SEEK_BACK,
        RemoteAction.SEEK_FORWARD,
        RemoteAction.EXIT_APP,
    )

    /** 默认绑定：与旧实现（RemoteHabits.DEFAULT）行为完全等价。 */
    val DEFAULT: List<KeyBinding> = listOf(
        KeyBinding(RemoteAction.CHANNEL_UP, setOf(KEY_DPAD_UP, KEY_CHANNEL_UP, KEY_PAGE_UP)),
        KeyBinding(RemoteAction.CHANNEL_DOWN, setOf(KEY_DPAD_DOWN, KEY_CHANNEL_DOWN, KEY_PAGE_DOWN)),
        KeyBinding(RemoteAction.OPEN_DRAWER, setOf(KEY_DPAD_LEFT)),
        // t52 §⑧：OPEN_SETTINGS 增加设备可用彩键（MENU 在部分 TV launcher 被拦截）作为可靠入口。
        KeyBinding(RemoteAction.OPEN_SETTINGS, setOf(KEY_DPAD_RIGHT, KEY_MENU, KEY_BUTTON_1)),
        KeyBinding(RemoteAction.SHOW_INFO, setOf(KEY_DPAD_CENTER, KEY_ENTER, KEY_NUMPAD_ENTER)),
        KeyBinding(RemoteAction.RECENT_CHANNELS, setOf(KEY_LAST_CHANNEL)),
        KeyBinding(RemoteAction.RETURN_LIVE, longPressKeycodes = setOf(KEY_BACK)),
        // t49 §②：EXIT_APP 默认不预置任何键（原也绑 BACK 长按=死配置，长按永远解析成 RETURN_LIVE）。
        // 退出播放器统一走 BackKeyPolicy 双按状态机（先 toast 再退）；EXIT_APP 仅供用户显式绑定。
        KeyBinding(RemoteAction.EXIT_APP),
        // 回看快进/快退由回看上下文原生支持 ←/→，此处默认留空以免与呼出选台/设置按键视觉冲突
        KeyBinding(RemoteAction.SEEK_BACK),
        KeyBinding(RemoteAction.SEEK_FORWARD),
    )

    /**
     * 中央解析：先按上下文过滤，再查短/长集合。
     * - MENU_OPEN / EPG_VISIBLE → null（覆盖层先赢，返回 NONE 语义由调用方处理）；
     * - CATCHUP：LEFT/RIGHT → SEEK_*，OK/UP/DOWN → SHOW_INFO（保持 t193 CATCHUP-01）；
     * - PLAYER_ONLY：按本绑定的 short/long 集合查表；同一键多 action 时按列表顺序取第一个
     *   （稳定、可预测），这让彩键重绑后若未移除旧绑定仍是确定行为。
     */
    fun resolve(keycode: Int, longPress: Boolean, context: UiContext): RemoteAction? {
        if (context == UiContext.MENU_OPEN || context == UiContext.EPG_VISIBLE) return null
        if (context == UiContext.CATCHUP) return resolveCatchup(keycode)
        // PLAYER_ONLY：长按优先匹配 long 集合，未命中再退回 short 集合（避免短按绑定抢走长按）。
        for (binding in DEFAULT) {
            if (longPress && keycode in binding.longPressKeycodes) return binding.action
        }
        for (binding in DEFAULT) {
            if (!longPress && keycode in binding.shortPressKeycodes) return binding.action
        }
        return null
    }

    /** 用**自定义绑定**解析（设置改动后立即生效）；规则与 [resolve] 一致。 */
    fun resolveWith(
        bindings: List<KeyBinding>,
        keycode: Int,
        longPress: Boolean,
        context: UiContext,
    ): RemoteAction? {
        if (context == UiContext.MENU_OPEN || context == UiContext.EPG_VISIBLE) return null
        if (context == UiContext.CATCHUP) {
            for (binding in bindings) {
                if ((binding.action == RemoteAction.SEEK_BACK ||
                        binding.action == RemoteAction.SEEK_FORWARD ||
                        binding.action == RemoteAction.RETURN_LIVE) &&
                    ((longPress && keycode in binding.longPressKeycodes) ||
                        (!longPress && keycode in binding.shortPressKeycodes))
                ) {
                    return binding.action
                }
            }
            return resolveCatchup(keycode)
        }
        for (binding in bindings) {
            if (binding.action == RemoteAction.SEEK_BACK ||
                binding.action == RemoteAction.SEEK_FORWARD ||
                binding.action == RemoteAction.RETURN_LIVE
            ) continue
            if (longPress && keycode in binding.longPressKeycodes) return binding.action
        }
        for (binding in bindings) {
            if (binding.action == RemoteAction.SEEK_BACK ||
                binding.action == RemoteAction.SEEK_FORWARD ||
                binding.action == RemoteAction.RETURN_LIVE
            ) continue
            if (!longPress && keycode in binding.shortPressKeycodes) return binding.action
        }
        // 兜底：若长按未绑定但短按绑定了，返回 null（不误触发短按）
        return null
    }

    /**
     * 判断某键在当前上下文中是否配置了长按动作。
     * - false → 短按可在 ACTION_DOWN(repeatCount=0) 零延迟即时触发；
     * - true  → 短按延迟到 ACTION_UP（且未触发长按）才执行，避免长按必然先误触发短按。
     */
    fun hasLongPressBinding(
        bindings: List<KeyBinding>,
        keycode: Int,
        context: UiContext,
    ): Boolean {
        if (context != UiContext.PLAYER_ONLY) return false
        return bindings.any { keycode in it.longPressKeycodes }
    }

    /** 把快速习惯「上下键」同步写入功能→按键绑定真源（彻底消除旧开关失效问题）。 */
    fun applyUpDownHabit(
        bindings: List<KeyBinding>,
        upDown: RemoteHabits.UpDown,
        channelKeysEnabled: Boolean,
    ): List<KeyBinding> {
        val navKeys = setOf(KEY_DPAD_UP, KEY_DPAD_DOWN)
        val chUpKeys = if (channelKeysEnabled) setOf(KEY_CHANNEL_UP, KEY_PAGE_UP) else emptySet()
        val chDownKeys = if (channelKeysEnabled) setOf(KEY_CHANNEL_DOWN, KEY_PAGE_DOWN) else emptySet()
        val allUpDown = navKeys + setOf(KEY_CHANNEL_UP, KEY_CHANNEL_DOWN, KEY_PAGE_UP, KEY_PAGE_DOWN)
        return bindings.map { b ->
            when (b.action) {
                RemoteAction.CHANNEL_UP -> b.copy(
                    shortPressKeycodes = if (upDown == RemoteHabits.UpDown.CHANNEL_STEP) {
                        (b.shortPressKeycodes - allUpDown) + KEY_DPAD_UP + chUpKeys
                    } else {
                        b.shortPressKeycodes - allUpDown
                    }
                )
                RemoteAction.CHANNEL_DOWN -> b.copy(
                    shortPressKeycodes = if (upDown == RemoteHabits.UpDown.CHANNEL_STEP) {
                        (b.shortPressKeycodes - allUpDown) + KEY_DPAD_DOWN + chDownKeys
                    } else {
                        b.shortPressKeycodes - allUpDown
                    }
                )
                RemoteAction.OPEN_DRAWER -> b.copy(
                    shortPressKeycodes = if (upDown == RemoteHabits.UpDown.OPEN_DRAWER) {
                        (b.shortPressKeycodes - allUpDown) + navKeys + chUpKeys + chDownKeys
                    } else {
                        b.shortPressKeycodes - allUpDown
                    }
                )
                else -> b
            }
        }
    }

    /** 把快速习惯「OK键」同步写入功能→按键绑定真源。 */
    fun applyOkHabit(bindings: List<KeyBinding>, ok: RemoteHabits.Ok): List<KeyBinding> {
        val okKeys = setOf(KEY_DPAD_CENTER, KEY_ENTER, KEY_NUMPAD_ENTER)
        return bindings.map { b ->
            when (b.action) {
                RemoteAction.SHOW_INFO -> b.copy(
                    shortPressKeycodes = if (ok == RemoteHabits.Ok.CONFIRM) {
                        b.shortPressKeycodes + okKeys
                    } else {
                        b.shortPressKeycodes - okKeys
                    }
                )
                RemoteAction.OPEN_DRAWER -> b.copy(
                    shortPressKeycodes = if (ok == RemoteHabits.Ok.OPEN_DRAWER) {
                        b.shortPressKeycodes + okKeys
                    } else {
                        b.shortPressKeycodes - okKeys
                    }
                )
                else -> b.copy(shortPressKeycodes = b.shortPressKeycodes - okKeys)
            }
        }
    }

    /** 把快速习惯「频道键 166/167」同步写入功能→按键绑定真源。 */
    fun applyChannelKeysHabit(bindings: List<KeyBinding>, enabled: Boolean): List<KeyBinding> {
        val upKeys = setOf(KEY_CHANNEL_UP, KEY_PAGE_UP)
        val downKeys = setOf(KEY_CHANNEL_DOWN, KEY_PAGE_DOWN)
        val allCh = upKeys + downKeys
        val upIsDrawer = bindings.firstOrNull { it.action == RemoteAction.OPEN_DRAWER }
            ?.shortPressKeycodes?.contains(KEY_DPAD_UP) == true
        return bindings.map { b ->
            when (b.action) {
                RemoteAction.CHANNEL_UP -> b.copy(
                    shortPressKeycodes = if (enabled && !upIsDrawer) b.shortPressKeycodes + upKeys else b.shortPressKeycodes - allCh
                )
                RemoteAction.CHANNEL_DOWN -> b.copy(
                    shortPressKeycodes = if (enabled && !upIsDrawer) b.shortPressKeycodes + downKeys else b.shortPressKeycodes - allCh
                )
                RemoteAction.OPEN_DRAWER -> b.copy(
                    shortPressKeycodes = if (enabled && upIsDrawer) b.shortPressKeycodes + allCh else b.shortPressKeycodes - allCh
                )
                else -> b
            }
        }
    }

    /** 从当前唯一真源 bindings 反推快速预设展示态，确保设置摘要与实际行为 100% 一致。 */
    fun deriveUpDownHabit(bindings: List<KeyBinding>): RemoteHabits.UpDown =
        if (bindings.firstOrNull { it.action == RemoteAction.CHANNEL_UP }?.shortPressKeycodes?.contains(KEY_DPAD_UP) == true) {
            RemoteHabits.UpDown.CHANNEL_STEP
        } else {
            RemoteHabits.UpDown.OPEN_DRAWER
        }

    fun deriveOkHabit(bindings: List<KeyBinding>): RemoteHabits.Ok =
        if (bindings.firstOrNull { it.action == RemoteAction.OPEN_DRAWER }?.shortPressKeycodes?.contains(KEY_DPAD_CENTER) == true) {
            RemoteHabits.Ok.OPEN_DRAWER
        } else {
            RemoteHabits.Ok.CONFIRM
        }

    fun deriveChannelKeysEnabled(bindings: List<KeyBinding>): Boolean =
        bindings.any { KEY_CHANNEL_UP in it.shortPressKeycodes || KEY_CHANNEL_DOWN in it.shortPressKeycodes }

    private fun resolveCatchup(keycode: Int): RemoteAction? = when (keycode) {
        KEY_DPAD_LEFT -> RemoteAction.SEEK_BACK
        KEY_DPAD_RIGHT -> RemoteAction.SEEK_FORWARD
        KEY_DPAD_CENTER, KEY_ENTER, KEY_NUMPAD_ENTER, KEY_DPAD_UP, KEY_DPAD_DOWN -> RemoteAction.SHOW_INFO
        KEY_BACK -> RemoteAction.RETURN_LIVE
        else -> null
    }

    /** 稳定序列化：`action:name,short:1|2,long:3;...`（无 JSON 依赖、可 JVM 往返）。 */
    fun serialize(bindings: List<KeyBinding>): String = bindings.joinToString(";") { b ->
        listOf(
            "a=${b.action.name}",
            "s=${b.shortPressKeycodes.sorted().joinToString("|")}",
            "l=${b.longPressKeycodes.sorted().joinToString("|")}",
        ).joinToString(",")
    }

    /** 解析序列化串；未知/空/损坏 → [DEFAULT]（原样副本，不共享可变状态）。 */
    fun parse(raw: String?): List<KeyBinding> {
        if (raw.isNullOrBlank()) return DEFAULT
        val out = ArrayList<KeyBinding>()
        try {
            for (part in raw.split(";")) {
                if (part.isBlank()) continue
                var action: RemoteAction? = null
                var shorts = emptySet<Int>()
                var longs = emptySet<Int>()
                for (field in part.split(",")) {
                    val eq = field.indexOf('=')
                    if (eq <= 0) continue
                    val k = field.substring(0, eq)
                    val v = field.substring(eq + 1)
                    when (k) {
                        "a" -> action = RemoteAction.entries.firstOrNull { it.name == v }
                        "s" -> shorts = parseCodes(v)
                        "l" -> longs = parseCodes(v)
                    }
                }
                val a = action ?: return DEFAULT
                out.add(KeyBinding(a, shorts, longs))
            }
        } catch (_: RuntimeException) {
            return DEFAULT
        }
        return if (out.isEmpty()) DEFAULT else out
    }

    private fun parseCodes(v: String): Set<Int> =
        if (v.isBlank()) emptySet()
        else v.split("|").mapNotNull { it.trim().toIntOrNull() }.toSet()

    /**
     * 老偏好 → 新绑定。5 种旧组合都映射到等价的新绑定：
     * - 默认 → DEFAULT；
     * - upDown=OPEN_DRAWER → UP/DOWN/166/167/92/93 改绑 OPEN_DRAWER；
     * - ok=OPEN_DRAWER → OK 三个键改绑 OPEN_DRAWER；
     * - back=EXIT → BACK 长按改绑 EXIT_APP（去掉 RETURN_LIVE 的 BACK 长按）；
     * - channelKeysEnabled=false → 从换台绑定里移除 166/167/92/93。
     */
    fun migrate(legacy: RemoteHabits.Settings): List<KeyBinding> {
        var bindings = DEFAULT.map { it.copy() }
        fun replace(action: RemoteAction, transform: (KeyBinding) -> KeyBinding) {
            bindings = bindings.map { if (it.action == action) transform(it) else it }
        }
        if (legacy.upDown == RemoteHabits.UpDown.OPEN_DRAWER) {
            for (a in listOf(RemoteAction.CHANNEL_UP, RemoteAction.CHANNEL_DOWN)) {
                replace(a) { it.copy(shortPressKeycodes = emptySet()) }
            }
            replace(RemoteAction.OPEN_DRAWER) { b ->
                b.copy(shortPressKeycodes = b.shortPressKeycodes + setOf(
                    KEY_DPAD_UP, KEY_DPAD_DOWN, KEY_CHANNEL_UP, KEY_CHANNEL_DOWN, KEY_PAGE_UP, KEY_PAGE_DOWN
                ))
            }
        }
        if (legacy.ok == RemoteHabits.Ok.OPEN_DRAWER) {
            replace(RemoteAction.SHOW_INFO) { it.copy(shortPressKeycodes = emptySet()) }
            replace(RemoteAction.OPEN_DRAWER) { b ->
                b.copy(shortPressKeycodes = b.shortPressKeycodes + setOf(KEY_DPAD_CENTER, KEY_ENTER, KEY_NUMPAD_ENTER))
            }
        }
        if (legacy.back == RemoteHabits.Back.EXIT) {
            replace(RemoteAction.RETURN_LIVE) { it.copy(longPressKeycodes = emptySet()) }
            replace(RemoteAction.EXIT_APP) { b -> b.copy(longPressKeycodes = b.longPressKeycodes + KEY_BACK) }
        }
        if (!legacy.channelKeysEnabled) {
            val disable = setOf(KEY_CHANNEL_UP, KEY_CHANNEL_DOWN, KEY_PAGE_UP, KEY_PAGE_DOWN)
            for (a in listOf(RemoteAction.CHANNEL_UP, RemoteAction.CHANNEL_DOWN)) {
                replace(a) { it.copy(shortPressKeycodes = it.shortPressKeycodes - disable) }
            }
        }
        return bindings
    }

    /** FULLFIX V4 §3：某功能当前的按键集合是否与出厂默认完全一致（未自定义）。 */
    fun isDefaultBinding(bindings: List<KeyBinding>, action: RemoteAction): Boolean {
        val cur = bindings.firstOrNull { it.action == action } ?: return false
        val def = DEFAULT.firstOrNull { it.action == action } ?: return false
        return cur.shortPressKeycodes == def.shortPressKeycodes && cur.longPressKeycodes == def.longPressKeycodes
    }

    /**
     * 从绑定反推"某 keycode 是否被用户显式改绑过"：若该键在 DEFAULT 中属于 action A，
     * 但在当前绑定中不再属于 A，则视为自定义。用于 V4 §3 RIGHT 特例判断。
     */
    fun isKeyCustomBound(bindings: List<KeyBinding>, keycode: Int): Boolean {
        val defaultAction = DEFAULT.firstOrNull { keycode in it.shortPressKeycodes || keycode in it.longPressKeycodes }?.action
            ?: return true // DEFAULT 里根本没有该键 → 视为自定义
        val current = bindings.firstOrNull { keycode in it.shortPressKeycodes || keycode in it.longPressKeycodes }?.action
        return current != defaultAction
    }

    /**
     * V4 §3（A 方案）：RIGHT 键决策。
     * 仅当 RIGHT 是**默认绑定**（用户未自定义）且 selectedGroup=全部（或焦点在分组列）时，
     * 把默认的 OPEN_SETTINGS 特例化为 OPEN_DRAWER；用户显式重绑 RIGHT 时尊重用户绑定；
     * CATCHUP 的 SEEK_FORWARD 等非 OPEN_SETTINGS 动作不受影响。
     */
    fun resolveRight(
        resolved: RemoteAction?,
        selectedGroup: String,
        inGroupColumn: Boolean,
        rightCustomBound: Boolean,
        allGroupName: String = "全部",
    ): RemoteAction? {
        if (resolved != RemoteAction.OPEN_SETTINGS) return resolved
        if (rightCustomBound) return resolved
        // t52 §⑧：RIGHT 特例收窄为"仅分组列内"——播放器 PLAYER_ONLY 的 RIGHT 恢复 OPEN_SETTINGS，
        // "全部频道按→进列表"由分组列内 → 承担。selectedGroup 不再制造死锁（保留参数兼容）。
        if (inGroupColumn) return RemoteAction.OPEN_DRAWER
        return resolved
    }

    /** 从绑定反推一级摘要的两个标签（保持 SettingsSummary.remote 签名与 ≤22 全角字符）。 */
    fun summaryLabels(bindings: List<KeyBinding>, lang: AppLang = AppLang.ZH): Pair<String, String> {
        val isChStep = bindings.firstOrNull { it.action == RemoteAction.CHANNEL_UP }?.shortPressKeycodes?.contains(KEY_DPAD_UP) == true
        val isInfoOk = bindings.firstOrNull { it.action == RemoteAction.SHOW_INFO }?.shortPressKeycodes?.contains(KEY_DPAD_CENTER) == true
        return if (lang == AppLang.EN) {
            (if (isChStep) "Zap" else "Drawer") to (if (isInfoOk) "Info card" else "Drawer")
        } else {
            (if (isChStep) "换台" else "呼出频道列表") to (if (isInfoOk) "显示信息卡" else "打开频道列表")
        }
    }

    /** 绑定一个键到某功能：从其它 action 移除该键（同上下文冲突自动覆盖），返回新列表。 */
    fun bind(
        bindings: List<KeyBinding>,
        action: RemoteAction,
        keycode: Int,
        longPress: Boolean,
    ): List<KeyBinding> = bindings.map { b ->
        if (longPress) {
            val cleaned = b.longPressKeycodes - keycode
            if (b.action == action) b.copy(longPressKeycodes = cleaned + keycode) else b.copy(longPressKeycodes = cleaned)
        } else {
            val cleaned = b.shortPressKeycodes - keycode
            if (b.action == action) b.copy(shortPressKeycodes = cleaned + keycode) else b.copy(shortPressKeycodes = cleaned)
        }
    }

    /** 清除某功能的短按或长按按键绑定。 */
    fun clearBinding(
        bindings: List<KeyBinding>,
        action: RemoteAction,
        longPress: Boolean,
    ): List<KeyBinding> = bindings.map { b ->
        if (b.action != action) b
        else if (longPress) b.copy(longPressKeycodes = emptySet())
        else b.copy(shortPressKeycodes = emptySet())
    }

    /** 按键的人类可读摘要（用于设置行显示），例如 `↑ / 166 / 92`。 */
    fun keyLabel(keycode: Int, lang: AppLang = AppLang.ZH): String = when (keycode) {
        KEY_DPAD_UP -> "↑"
        KEY_DPAD_DOWN -> "↓"
        KEY_DPAD_LEFT -> "←"
        KEY_DPAD_RIGHT -> "→"
        KEY_DPAD_CENTER -> "OK"
        KEY_ENTER, KEY_NUMPAD_ENTER -> "Enter"
        KEY_BACK -> "Back"
        KEY_MENU -> if (lang == AppLang.EN) "Menu" else "菜单"
        KEY_CHANNEL_UP -> "166"
        KEY_CHANNEL_DOWN -> "167"
        KEY_PAGE_UP -> "92"
        KEY_PAGE_DOWN -> "93"
        KEY_LAST_CHANNEL -> if (lang == AppLang.EN) "Prev" else "上一台"
        else -> if (lang == AppLang.EN) "Key $keycode" else "键$keycode"
    }

    fun bindingSummary(binding: KeyBinding?, longPress: Boolean, lang: AppLang = AppLang.ZH): String {
        val emptyText = if (lang == AppLang.EN) "Unbound" else "未绑定"
        if (binding == null) return emptyText
        val codes = if (longPress) binding.longPressKeycodes else binding.shortPressKeycodes
        return if (codes.isEmpty()) emptyText else codes.sorted().joinToString(" / ") { keyLabel(it, lang) }
    }
}
