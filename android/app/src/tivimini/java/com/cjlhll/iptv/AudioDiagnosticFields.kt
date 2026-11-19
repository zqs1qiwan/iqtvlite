package com.cjlhll.iptv

/** Only bounded, typed format tokens enter diagnostics; never labels, URIs or error messages. */
internal object AudioDiagnosticFields {
    private val mimePattern = Regex("(?:audio|video|text|application)/[A-Za-z0-9.+_-]{1,64}")
    private val codecPattern = Regex("[A-Za-z0-9.,_+\\-]{1,96}")
    private val languagePattern = Regex("[A-Za-z0-9_-]{1,32}")
    private val namePattern = Regex("[A-Za-z0-9._-]{1,128}")

    private fun checked(value: String?, pattern: Regex): String = when {
        value == null -> "unknown"
        pattern.matches(value) -> value
        else -> "redacted"
    }

    fun mime(value: String?): String = checked(value, mimePattern)
    fun codecs(value: String?): String = checked(value, codecPattern)
    fun language(value: String?): String = checked(value, languagePattern)
    fun name(value: String?): String = checked(value, namePattern)
}
