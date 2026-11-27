package com.cjlhll.iptv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.iqtvlite.app.BuildConfig
import tv.iqtvlite.app.R
import java.util.Locale

/**
 * 首次运行主动语言选择页（简体中文 / English）。
 * 用户选择后持久化写入 [Prefs.setAppLanguage] 并立即进入播放器或扫码配置中心。
 */
class LanguageSelectActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = TvDesignTokens.colorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    LanguageSelectScreen(
                        onLanguageChosen = { lang ->
                            Prefs.setAppLanguage(this@LanguageSelectActivity, lang)
                            proceedAfterLanguage()
                        }
                    )
                }
            }
        }
    }

    private fun proceedAfterLanguage() {
        val liveSource = Prefs.getLiveSource(this)
        val (lastUrl, lastTitle) = Prefs.getLastChannel(this)
        val playlistFileName = Prefs.getPlaylistFileName(this)
        if (liveSource.isNotBlank() && !lastUrl.isNullOrBlank()) {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_URL", lastUrl)
                putExtra("VIDEO_TITLE", lastTitle ?: "Live")
                if (!playlistFileName.isNullOrBlank()) {
                    putExtra("PLAYLIST_FILE_NAME", playlistFileName)
                }
            }
            startActivity(intent)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}

@Composable
private fun LanguageSelectScreen(
    onLanguageChosen: (AppLang) -> Unit,
) {
    val defaultIsZh = remember { Locale.getDefault().language.equals("zh", ignoreCase = true) }
    val zhRequester = remember { FocusRequester() }
    val enRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(4) {
            withFrameNanos { }
            val target = if (defaultIsZh) zhRequester else enRequester
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    val bg = Brush.verticalGradient(listOf(Color(0xFF0A0E14), Color(0xFF0F1622)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.brand_wordmark_white),
                contentDescription = "IQTV Lite",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(56.dp),
            )

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "选择界面语言 · Choose Language",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = TvDesignTokens.TextPrimary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "可在「设置 → 外观 → 语言 / Language」随时切换",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = TvDesignTokens.TextMuted,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "You can change this anytime in Settings → Appearance → Language",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = TvDesignTokens.TextMuted.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(34.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageChoiceCard(
                    title = "简体中文",
                    subtitle = "Chinese (Simplified)",
                    hint = "按 OK 确认进入",
                    recommended = defaultIsZh,
                    focusRequester = zhRequester,
                    onClick = { onLanguageChosen(AppLang.ZH) },
                )
                LanguageChoiceCard(
                    title = "English",
                    subtitle = "英语界面",
                    hint = "Press OK to continue",
                    recommended = !defaultIsZh,
                    focusRequester = enRequester,
                    onClick = { onLanguageChosen(AppLang.EN) },
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                color = TvDesignTokens.TextMuted.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun LanguageChoiceCard(
    title: String,
    subtitle: String,
    hint: String,
    recommended: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) BrandFocus.FocusBgColor else Color(0xFF141C28)
    val borderColor = if (focused) Color(0x88FFFFFF) else Color(0x22FFFFFF)

    Column(
        modifier = Modifier
            .width(268.dp)
            .height(146.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(if (focused) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 23.sp,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                ),
                color = if (focused) Color.White else TvDesignTokens.TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = if (focused) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = if (focused) Color(0xFF84B4FF) else if (recommended) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted,
        )
    }
}
