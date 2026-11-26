package com.cjlhll.iptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Listens for system clock / timezone adjustments (e.g., cold-boot NTP sync) so EPG
 * "now playing" queries refresh immediately instead of waiting for the next 30s tick.
 */
@Composable
fun SystemTimeSyncEffect(
    context: Context,
    onTimeSynced: (Long) -> Unit,
) {
    DisposableEffect(context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                onTimeSynced(System.currentTimeMillis())
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

/**
 * Borderless top-right `HH:mm` clock HUD driven directly by system time broadcasts
 * (`ACTION_TIME_TICK`, `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED`, `ACTION_DATE_CHANGED`)
 * and isolated in its own composable scope to avoid recomposing the player root.
 */
@Composable
fun PlayerClockOverlay(
    textScale: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var clockText by remember { mutableStateOf("") }
    DisposableEffect(context) {
        fun formatCurrentTime(): String {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            fmt.timeZone = TimeZone.getDefault()
            return fmt.format(Date(System.currentTimeMillis()))
        }
        clockText = formatCurrentTime()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                clockText = formatCurrentTime()
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    if (clockText.isNotEmpty()) {
        Text(
            text = clockText,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (20f * textScale).sp,
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f),
                    blurRadius = 8f,
                ),
            ),
            color = TvDesignTokens.TextPrimary,
            modifier = modifier,
        )
    }
}
