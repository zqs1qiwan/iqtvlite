package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 信息条 EPG 三态：已配置有数据 / 已配置但本频道无数据 / 未配置 EPG。 */
enum class BannerEpgState { CONFIGURED, NO_DATA, NOT_CONFIGURED }

data class CatchupConfiguration(
    val startMillis: Long,
    val endMillis: Long,
    val positionMs: Long
)

private val bannerTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 底部信息条（对应设计第 3 节）。第一行 logo + 全局编号/频道名 + 播放状态；
 * 第二行“正在播放 时间 标题”与进度；第三行“接下来 时间 标题”。缺数据时给出稳定占位，
 * 不伪造节目内容。
 */
@Composable
fun ChannelInfoBanner(
    visible: Boolean,
    channel: Channel?,
    channelNumber: Int = -1,
    nowTitle: String? = null,
    nowTimeText: String? = null,
    nowProgress: Float? = null,
    nextTitle: String? = null,
    nextTimeText: String? = null,
    epgState: BannerEpgState = BannerEpgState.CONFIGURED,
    videoFormat: Format? = null,
    catchupConfiguration: CatchupConfiguration? = null,
    catchupActive: Boolean = false,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val containerColor = TvColors.Panel.copy(alpha = 0.94f)
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    val content: @Composable () -> Unit = {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 0.dp)
                .background(containerColor, shape)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Column {
                if (catchupConfiguration != null) {
                    CatchupProgressRow(
                        startMillis = catchupConfiguration.startMillis,
                        endMillis = catchupConfiguration.endMillis,
                        positionMs = catchupConfiguration.positionMs,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(
                        logoUrl = channel?.logoUrl,
                        fallbackTitle = channel?.title ?: "",
                        modifier = Modifier.size(width = 80.dp, height = 48.dp),
                        width = 80.dp,
                        height = 48.dp,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val name = channel?.title ?: "未知频道"
                        val titleText = if (channelNumber > 0) "$channelNumber. $name" else name
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = titleText,
                                style = TvType.BannerTitle,
                                color = TvColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (catchupActive) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("回看中", style = TvType.Aux, color = TvColors.Playing)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    VideoSpecs(videoFormat)
                }

                Spacer(modifier = Modifier.height(10.dp))

                ProgramLine(
                    label = if (catchupActive) "回看" else "正在播放",
                    timeText = nowTimeText,
                    title = when {
                        catchupActive -> nowTitle
                        epgState == BannerEpgState.NOT_CONFIGURED -> "尚未配置EPG"
                        nowTitle.isNullOrBlank() -> "暂无当前节目信息"
                        else -> nowTitle
                    },
                    emphasized = true,
                    showProgress = !catchupActive && nowProgress != null && epgState == BannerEpgState.CONFIGURED,
                    progress = nowProgress,
                )

                if (!catchupActive) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ProgramLine(
                        label = "接下来",
                        timeText = nextTimeText,
                        title = when {
                            epgState == BannerEpgState.NOT_CONFIGURED -> "配置EPG后可显示后续节目"
                            nextTitle.isNullOrBlank() -> "暂无后续节目信息"
                            else -> nextTitle
                        },
                        emphasized = false,
                        showProgress = false,
                        progress = null,
                    )
                }
            }
        }
    }

    if (animate) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                content()
            }
        }
    } else {
        if (!visible) return
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            content()
        }
    }
}

@Composable
private fun ProgramLine(
    label: String,
    timeText: String?,
    title: String?,
    emphasized: Boolean,
    showProgress: Boolean,
    progress: Float?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TvType.Aux, color = TvColors.TextSecondary, modifier = Modifier.width(72.dp))
        if (!timeText.isNullOrBlank()) {
            Text(timeText, style = TvType.Aux, color = TvColors.TextSecondary, modifier = Modifier.width(96.dp))
        }
        Text(
            text = title.orEmpty(),
            style = if (emphasized) TvType.Body else TvType.Aux,
            color = if (emphasized) TvColors.TextPrimary else TvColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    if (showProgress && progress != null) {
        BannerProgressBar(progress = progress, modifier = Modifier.padding(start = 72.dp, top = 4.dp))
    }
}

@Composable
private fun VideoSpecs(videoFormat: Format?) {
    val width = videoFormat?.width ?: 0
    val height = videoFormat?.height ?: 0
    val codec = videoFormat?.sampleMimeType?.substringAfter("/").orEmpty()
    if (width <= 0 && height <= 0 && codec.isBlank()) return
    Column(horizontalAlignment = Alignment.End) {
        if (width > 0 && height > 0) {
            Text("${width}x${height}", style = TvType.Aux, color = TvColors.TextDisabled)
        }
        if (codec.isNotBlank()) {
            Text(codec, style = TvType.Aux, color = TvColors.TextDisabled)
        }
    }
}

@Composable
private fun CatchupProgressRow(startMillis: Long, endMillis: Long, positionMs: Long) {
    val total = (endMillis - startMillis).coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val zone = ZoneId.systemDefault()
    val startText = bannerTimeFormatter.format(Instant.ofEpochMilli(startMillis).atZone(zone))
    val endText = bannerTimeFormatter.format(Instant.ofEpochMilli(endMillis).atZone(zone))

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(startText, style = TvType.Aux, color = TvColors.TextSecondary)
        Spacer(modifier = Modifier.width(16.dp))
        Box(modifier = Modifier.weight(1f).height(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.Center)
                    .background(TvColors.TextSecondary.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .background(TvColors.Playing, RoundedCornerShape(2.dp)),
            )
            val bias = if (progress.isNaN()) -1f else (progress * 2) - 1
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .align(BiasAlignment(bias.coerceIn(-1f, 1f), 0f))
                    .background(Color.White, CircleShape),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(endText, style = TvType.Aux, color = TvColors.TextSecondary)
    }
}

@Composable
private fun BannerProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(TvColors.TextSecondary.copy(alpha = 0.18f), RoundedCornerShape(999.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(clamped)
                .background(TvColors.Playing, RoundedCornerShape(999.dp)),
        )
    }
}
