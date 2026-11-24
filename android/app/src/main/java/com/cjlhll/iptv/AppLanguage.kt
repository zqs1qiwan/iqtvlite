package com.cjlhll.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IQTV Lite 应用语言枚举与响应式双语分发（简体中文 / English）。
 * 不依赖 Android Context / Resources，纯 Kotlin 模块也可直接引用。
 */
enum class AppLang(val code: String, val nativeName: String) {
    ZH("zh", "简体中文"),
    EN("en", "English");

    companion object {
        fun parse(raw: String?): AppLang =
            if (raw.equals("en", ignoreCase = true)) EN else ZH
    }
}

object AppStrings {
    private val _lang = MutableStateFlow(AppLang.ZH)
    val langFlow: StateFlow<AppLang> = _lang.asStateFlow()

    val current: AppLang get() = _lang.value

    fun set(lang: AppLang) {
        _lang.value = lang
    }

    fun tr(zh: String, en: String): String =
        if (_lang.value == AppLang.EN) en else zh

    fun tr(lang: AppLang, zh: String, en: String): String =
        if (lang == AppLang.EN) en else zh
}

/** Compose 内自动订阅语言切换，切换后全屏零重启实时刷新文案。 */
@Composable
fun tr(zh: String, en: String): String {
    val lang by AppStrings.langFlow.collectAsState()
    return if (lang == AppLang.EN) en else zh
}
