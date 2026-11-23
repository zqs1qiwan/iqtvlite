package com.cjlhll.iptv

/**
 * t52 §⑧：FOCUSED 态"哪些按键放行给 Activity 全局路由"的纯策略。
 *
 * 方向键 / OK / BACK 仍由信息卡闭环消费（t35 核心价值：防穿透到换台/开设置）；
 * 已绑定到 OPEN_SETTINGS 的非方向键（彩键 / MENU）放行，让 FOCUSED 态也能呼出设置。
 */
object FocusedKeyPolicy {

    /**
     * @param keycode Android keyCode。
     * @param resolvedAction 该键在中央路由解析出的动作（可能为 null）。
     * @return true=放行给 Activity 全局路由（不拦截）；false=信息卡闭环消费。
     */
    fun passthrough(keycode: Int, resolvedAction: RemoteAction?): Boolean {
        // 方向键与确认/返回键永远闭环（t35），不放行。
        if (keycode == 21 || keycode == 22 || keycode == 19 || keycode == 20 ||
            keycode == 23 || keycode == 66 || keycode == 160 || keycode == 4
        ) return false
        // 其它键：只有解析为 OPEN_SETTINGS 才放行（彩键/MENU/用户自定义设置键）。
        return resolvedAction == RemoteAction.OPEN_SETTINGS
    }
}
