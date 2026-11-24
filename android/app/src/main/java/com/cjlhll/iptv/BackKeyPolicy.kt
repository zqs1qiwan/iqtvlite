package com.cjlhll.iptv

/**
 * t49（GOLDEN_SPEC §2）：BACK 分层状态机 —— 单一真源裁决，任何层都 CONSUME，绝不返回
 * PASS_TO_SUPER（避免系统默认 finish 导致的"取消信息卡却退出 app"）。纯 Kotlin，可 JVM 单测。
 *
 * 优先级（命中即返回）：音频框 > 最近列表 > 质量/线路框 > 频道抽屉 > 设置二级 > 设置一级 >
 * 信息卡 FOCUSED 退聚焦 > 信息卡 IDLE 关卡 > 回看双按返回直播 > 播放器双按退出。
 */
object BackKeyPolicy {

    enum class Action {
        CLOSE_UPDATE_DIALOG,
        CLOSE_AUDIO_DIALOG,
        CLOSE_RECENT,
        CLOSE_QUALITY_DIALOG,
        CLOSE_DRAWER,
        SETTINGS_BACK_TO_ROOT,
        CLOSE_SETTINGS,
        INFO_CARD_UNFOCUS,
        INFO_CARD_CLOSE,
        TOAST_EXIT_PIP,
        EXIT_PIP,
        TOAST_RETURN_LIVE,
        RETURN_LIVE,
        TOAST_EXIT,
        EXIT_APP,
    }

    /**
     * 单一分层裁决（纯函数，无任何副作用）。
     * @param settingsHasSubPage 设置抽屉当前是否在二级页（只读查询，不触发退页副作用）。
     * @param infoCardVisible 信息卡是否可见（IDLE 或 FOCUSED）——由单一 overlay 状态派生。
     * @param infoCardFocused 信息卡是否处于 FOCUSED。
     * @param catchup 是否回看中。
     * @param lastBackWithin2s 距上次 BACK 是否在 2s 内（双按计时器单一真源，JVM 可注入）。
     * @param updateDialogOpen 更新弹窗是否打开（最高优先级模态层）。
     */
    fun decide(
        audioDialogOpen: Boolean,
        recentOpen: Boolean,
        qualityDialogOpen: Boolean,
        drawerOpen: Boolean,
        settingsOpen: Boolean,
        settingsHasSubPage: Boolean,
        infoCardVisible: Boolean,
        infoCardFocused: Boolean,
        catchup: Boolean,
        lastBackWithin2s: Boolean,
        updateDialogOpen: Boolean = false,
        inAppPipActive: Boolean = false,
    ): Action = when {
        updateDialogOpen -> Action.CLOSE_UPDATE_DIALOG
        audioDialogOpen -> Action.CLOSE_AUDIO_DIALOG
        recentOpen -> Action.CLOSE_RECENT
        qualityDialogOpen -> Action.CLOSE_QUALITY_DIALOG
        drawerOpen -> Action.CLOSE_DRAWER
        settingsOpen && settingsHasSubPage -> Action.SETTINGS_BACK_TO_ROOT
        settingsOpen -> Action.CLOSE_SETTINGS
        infoCardFocused -> Action.INFO_CARD_UNFOCUS // FOCUSED 先退聚焦，卡片仍在
        infoCardVisible -> Action.INFO_CARD_CLOSE   // IDLE 关卡到 HIDDEN
        inAppPipActive && lastBackWithin2s -> Action.EXIT_PIP
        inAppPipActive -> Action.TOAST_EXIT_PIP
        catchup && lastBackWithin2s -> Action.RETURN_LIVE
        catchup -> Action.TOAST_RETURN_LIVE
        lastBackWithin2s -> Action.EXIT_APP
        else -> Action.TOAST_EXIT
    }

    /** 所有动作都消费按键（永不 PASS_TO_SUPER）——给门禁/测试一个显式不变量。 */
    fun alwaysConsumed(action: Action): Boolean = true
}
