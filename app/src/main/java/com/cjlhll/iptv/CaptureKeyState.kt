package com.cjlhll.iptv

/**
 * 按键捕获状态机（T02）。仅在一次完整 Down→Up 且键一致时提交一次；重复 Down 不重复；
 * Back 取消；方向键 Down/Up 都被消费，不参与背景空间焦点搜索。
 * 纯 Kotlin，可本地 JVM 测试；CaptureOverlay 使用同一实现。
 */
sealed class CaptureKeyResult {
    object Consume : CaptureKeyResult()
    object Cancel : CaptureKeyResult()
    data class Submit(val keyCode: Int) : CaptureKeyResult()
}

class CaptureKeyState {
    private var pressedKeyCode: Int? = null

    fun reset() { pressedKeyCode = null }

    fun onKey(isKeyDown: Boolean, keyCode: Int, repeatCount: Int = 0): CaptureKeyResult {
        if (isKeyDown) {
            if (keyCode == BACK_KEYCODE) return CaptureKeyResult.Cancel
            if (repeatCount == 0) pressedKeyCode = keyCode
            return CaptureKeyResult.Consume
        }
        // KeyUp
        if (keyCode == BACK_KEYCODE) {
            pressedKeyCode = null
            return CaptureKeyResult.Cancel
        }
        val pressed = pressedKeyCode
        pressedKeyCode = null
        return if (pressed != null && pressed == keyCode) CaptureKeyResult.Submit(keyCode)
        else CaptureKeyResult.Consume
    }

    companion object {
        const val BACK_KEYCODE = 4
    }
}
