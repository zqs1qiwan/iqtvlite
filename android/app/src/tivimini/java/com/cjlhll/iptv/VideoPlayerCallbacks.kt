package com.cjlhll.iptv

/**
 * t44（VerifyError 根修）：把 VideoPlayerScreen 的 35 个参数按职责聚合为 4 个对象，
 * 让巨型 composable 的方法参数/寄存器数量显著下降，规避 API28 旧 ART 验证器溢出。
 * 仅为参数聚合，不改变任何回调语义。
 */

/** 播放动作回调。 */
data class PlaybackCallbacks(
    val setOnChannelStep: (((Int) -> Boolean)?) -> Unit = {},
    val setOnNumberJump: (((Int) -> Boolean)?) -> Unit = {},
    val setOnPlayLastChannel: ((() -> Boolean)?) -> Unit = {},
    val setOnCatchupSeek: (((Long) -> Boolean)?) -> Unit = {},
    val setOnReturnToLive: ((() -> Boolean)?) -> Unit = {},
    val onCatchupPlaybackChanged: (Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** 覆盖层（抽屉/设置/线路/最近观看/音轨）控制器与状态镜像。 */
data class OverlayCallbacks(
    val setRecentChannelsOpenController: (((Boolean) -> Unit)?) -> Unit = {},
    val onRecentChannelsOpenChanged: (Boolean) -> Unit = {},
    val setAudioDialogOpenController: (((Boolean) -> Unit)?) -> Unit = {},
    val onAudioDialogOpenChanged: (Boolean) -> Unit = {},
    val onDrawerOpenChanged: (Boolean) -> Unit = {},
    val onDrawerActiveColumnChanged: (DrawerColumn?) -> Unit = {},
    val setSettingsDrawerOpenController: (((Boolean) -> Unit)?) -> Unit = {},
    val onSettingsDrawerOpenChanged: (Boolean) -> Unit = {},
    val setSettingsBackHandlerController: (((() -> Boolean)?) -> Unit) = {},
    val setQualityDialogOpenController: (((Boolean) -> Unit)?) -> Unit = {},
    val onQualityDialogOpenChanged: (Boolean) -> Unit = {},
)

/** 信息卡（显示/OK 模式/FOCUSED 闭环/焦点镜像）控制器。 */
data class InfoCardCallbacks(
    val setInfoBannerOpenController: (((Boolean) -> Unit)?) -> Unit = {},
    val onInfoBannerOpenChanged: (Boolean) -> Unit = {},
    val setInfoCardOkController: (((() -> Boolean)?) -> Unit) = {},
    val setOpenInfoCardFromOkController: (((() -> Boolean)?) -> Unit) = {},
    val setInfoCardFocusedActionController: ((((InfoCardPolicy.FocusedKeyAction) -> Boolean)?) -> Unit) = {},
    val onInfoCardFocusedChanged: (Boolean) -> Unit = {},
)

/** 遥控器全映射（偏好 / 绑定状态 / 捕获）。 */
data class RemoteCallbacks(
    val onRemoteSettingsChanged: (RemoteHabits.Settings) -> Unit = {},
    val remoteBindings: List<KeyBinding> = RemoteBindings.DEFAULT,
    val captureRequest: Pair<RemoteAction, Boolean>? = null,
    val onStartCapture: (RemoteAction, Boolean) -> Unit = { _, _ -> },
    val onCancelCapture: () -> Unit = {},
    val onBindingsChanged: (List<KeyBinding>) -> Unit = {},
)
