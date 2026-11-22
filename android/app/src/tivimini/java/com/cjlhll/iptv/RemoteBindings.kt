package com.cjlhll.iptv

/**
 * FULLFIX §7：TiviMate 式「功能 → 按键」全映射（纯 Kotlin，Android/Compose-free）。
 *
 * 旧模型 [RemoteHabits] 是 5 个固定二选一开关；本模型把「功能」放在第一位，每个功能可绑定
 * 任意 keycode（短按/长按各一组）。默认绑定与旧实现的实际行为完全等价，老偏好通过 [migrate]
 * 一次性迁移。**保留旧模型**以兼容既有单测与偏好解析，但 dispatchKeyEvent 改读本模型。
 *
 * 关键不变量：
 * - [UiContext.MENU_OPEN]（任何覆盖层打开）→ [resolve] 返回 null，覆盖层自己消费按键；
 * - 回看（[UiContext.CATCHUP]）把 LEFT/RIGHT 解析为 SEEK_*，OK/UP/DOWN 解析为 SHOW_INFO；
 * - 长短按由调用方从 KeyEvent 传入（isLongPress / repeatCount），这里只查对应集合；
 * - 未知/损坏的序列化串 → [DEFAULT]，不崩。
 */
enum class RemoteAction(val label: String) {
    CHANNEL_UP("上一个频道"),
    CHANNEL_DOWN("下一个频道"),
    OPEN_DRAWER("打开频道列表"),
    OPEN_SETTINGS("打开设置"),
    SHOW_INFO("显示信息卡"),
    RECENT_CHANNELS("最近观看"),
    LAST_CHANNEL("上一台"),
    RETURN_LIVE("返回直播"),
    SEEK_BACK("快退"),
    SEEK_FORWARD("快进"),
    PLAY_PAUSE("播放/暂停"),
    VOLUME_UP("音量+"),
    VOLUME_DOWN("音量-"),
    EXIT_APP("退出应用"),
    NONE("未绑定"),
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
        KeyBinding(RemoteAction.OPEN_SETTINGS, setOf(KEY_DPAD_RIGHT, KEY_MENU)),
        KeyBinding(RemoteAction.SHOW_INFO, setOf(KEY_DPAD_CENTER, KEY_ENTER, KEY_NUMPAD_ENTER)),
        KeyBinding(RemoteAction.RECENT_CHANNELS, setOf(KEY_LAST_CHANNEL)),
        KeyBinding(RemoteAction.RETURN_LIVE, longPressKeycodes = setOf(KEY_BACK)),
        KeyBinding(RemoteAction.EXIT_APP, longPressKeycodes = setOf(KEY_BACK)),
        KeyBinding(RemoteAction.SEEK_BACK, setOf(KEY_DPAD_LEFT)),
        KeyBinding(RemoteAction.SEEK_FORWARD, setOf(KEY_DPAD_RIGHT)),
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
        if (context == UiContext.CATCHUP) return resolveCatchup(keycode)
        for (binding in bindings) {
            if (longPress && keycode in binding.longPressKeycodes) return binding.action
        }
        for (binding in bindings) {
            if (!longPress && keycode in binding.shortPressKeycodes) return binding.action
        }
        return null
    }

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

    /** 从绑定反推一级摘要的两个标签（保持 SettingsSummary.remote 签名与 ≤22 全角字符）。 */
    fun summaryLabels(bindings: List<KeyBinding>): Pair<String, String> {
        val upDown = if (bindings.firstOrNull { it.action == RemoteAction.CHANNEL_UP }?.shortPressKeycodes?.contains(KEY_DPAD_UP) == true) {
            "换台"
        } else {
            "呼出频道列表"
        }
        val ok = if (bindings.firstOrNull { it.action == RemoteAction.SHOW_INFO }?.shortPressKeycodes?.contains(KEY_DPAD_CENTER) == true) {
            "显示信息卡"
        } else {
            "打开频道列表"
        }
        return upDown to ok
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

    /** 按键的人类可读摘要（用于设置行显示），例如 `↑ / 166 / 92`。 */
    fun keyLabel(keycode: Int): String = when (keycode) {
        KEY_DPAD_UP -> "↑"
        KEY_DPAD_DOWN -> "↓"
        KEY_DPAD_LEFT -> "←"
        KEY_DPAD_RIGHT -> "→"
        KEY_DPAD_CENTER -> "OK"
        KEY_ENTER, KEY_NUMPAD_ENTER -> "Enter"
        KEY_BACK -> "Back"
        KEY_MENU -> "菜单"
        KEY_CHANNEL_UP -> "166"
        KEY_CHANNEL_DOWN -> "167"
        KEY_PAGE_UP -> "92"
        KEY_PAGE_DOWN -> "93"
        KEY_LAST_CHANNEL -> "上一台"
        else -> "键$keycode"
    }

    fun bindingSummary(binding: KeyBinding?, longPress: Boolean): String {
        if (binding == null) return "未绑定"
        val codes = if (longPress) binding.longPressKeycodes else binding.shortPressKeycodes
        return if (codes.isEmpty()) "未绑定" else codes.sorted().joinToString(" / ") { keyLabel(it) }
    }
}
