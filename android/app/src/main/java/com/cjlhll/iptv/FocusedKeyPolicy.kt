package com.cjlhll.iptv

/**
 * t52 §⑧：FOCUSED 态"哪些按键放行给 Activity 全局路由"的纯策略。
 *
 * 方向键 / OK 仍由信息卡闭环消费（t35 核心价值：防穿透到换台/开设置）；
 * BACK 放行给 BackKeyPolicy 单一真源（t59 ②：FOCUSED→退聚焦/IDLE→关卡，不再走信息卡闭环直接 return）；
 * 已绑定到 OPEN_SETTINGS 的非方向键（彩键 / MENU）放行，让 FOCUSED 态也能呼出设置。
 */
object FocusedKeyPolicy {
    const val KEYCODE_BACK = 4

    /**
     * @param keycode Android keyCode。
     * @param resolvedAction 该键在中央路由解析出的动作（可能为 null）。
     * @return true=放行给 Activity 全局路由（不拦截）；false=信息卡闭环消费。
     */
    fun passthrough(keycode: Int, resolvedAction: RemoteAction?): Boolean {
        // t59 ②：BACK 统一放行给 BackKeyPolicy，绝不在 FOCUSED 守卫里直接消费退 app。
        if (keycode == KEYCODE_BACK) return true
        // 方向键与确认键仍闭环（t35），不放行。
        if (keycode == 21 || keycode == 22 || keycode == 19 || keycode == 20 ||
            keycode == 23 || keycode == 66 || keycode == 160
        ) return false
        // 其它键：只有解析为 OPEN_SETTINGS 才放行（彩键/MENU/用户自定义设置键）。
        return resolvedAction == RemoteAction.OPEN_SETTINGS
    }
}
