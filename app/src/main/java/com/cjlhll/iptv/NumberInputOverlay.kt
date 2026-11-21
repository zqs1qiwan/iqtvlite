package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * 数字缓冲反馈控制器。屏幕右上安全区显示完整输入、目标频道名或越界提示（设计第 3 节）。
 */
@Stable
class NumberBufferController {
    var text by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)
    var visible by mutableStateOf(false)

    fun show(text: String, subtitle: String?, isError: Boolean = false) {
        this.text = text
        this.subtitle = subtitle
        this.isError = isError
        this.visible = true
    }

    fun hide() {
        visible = false
        text = ""
        subtitle = null
        isError = false
    }
}

@Composable
fun NumberInputOverlay(
    controller: NumberBufferController,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    AnimatedVisibility(
        visible = controller.visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .background(TvColors.Panel.copy(alpha = 0.96f), shape)
                .border(1.dp, TvColors.FocusBorder.copy(alpha = 0.7f), shape)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = controller.text,
                style = TvType.NumberBuffer,
                color = if (controller.isError) TvColors.Error else TvColors.TextPrimary,
            )
            val subtitle = controller.subtitle
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = subtitle,
                    style = TvType.Aux,
                    color = if (controller.isError) TvColors.Error else TvColors.TextSecondary,
                )
            }
        }
    }
}
