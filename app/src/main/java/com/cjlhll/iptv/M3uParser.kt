package com.cjlhll.iptv

object M3uParser {
    private val groupRegex = Regex("group-title=\"([^\"]*)\"")
    private val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")
    private val tvgIdRegex = Regex("tvg-id=\"([^\"]*)\"")
    private val tvgNameRegex = Regex("tvg-name=\"([^\"]*)\"")
    private val userAgentRegex = Regex("""http-user-agent=(?:"([^"]*)"|'([^']*)'|([^,\\s]*))""", RegexOption.IGNORE_CASE)
    private val catchupRegex = Regex("catchup=[\"']?([^\"'\\s]*)[\"']?")
    private val catchupSourceRegex = Regex("catchup-source=[\"']?([^\"']*)[\"']?")
    private val responseTimeRegex = Regex("""response-time=["']?([^"'\s,]*)["']?""", RegexOption.IGNORE_CASE)

    fun parse(content: String): List<Channel> {
        val channels = ArrayList<Channel>()

        var pendingTitle: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        var pendingTvgId: String? = null
        var pendingTvgName: String? = null
        var pendingUserAgent: String? = null
        var pendingCatchup: String? = null
        var pendingCatchupSource: String? = null
        var pendingResponseTimeMs: Int? = null

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pendingGroup = groupRegex.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                pendingLogo = logoRegex.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                pendingTvgId = tvgIdRegex.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                pendingTvgName = tvgNameRegex.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                val userAgentMatch = userAgentRegex.find(line)
                pendingUserAgent = listOfNotNull(
                    userAgentMatch?.groups?.get(1)?.value,
                    userAgentMatch?.groups?.get(2)?.value,
                    userAgentMatch?.groups?.get(3)?.value,
                ).firstOrNull()?.let(::normalizeUserAgent)
                pendingCatchup = catchupRegex.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                pendingCatchupSource = catchupSourceRegex.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                pendingResponseTimeMs = parseResponseTimeMs(
                    responseTimeRegex.find(line)?.groupValues?.getOrNull(1)
                )
                pendingTitle = line.substringAfterLast(',', missingDelimiterValue = "").trim().takeIf { it.isNotBlank() }
                continue
            }

            if (line.startsWith('#')) continue

            val url = line
            val title = pendingTitle ?: url

            channels.add(
                Channel(
                    title = title,
                    url = url,
                    group = pendingGroup,
                    logoUrl = pendingLogo,
                    tvgId = pendingTvgId,
                    tvgName = pendingTvgName,
                    userAgent = pendingUserAgent,
                    catchupMode = pendingCatchup ?: "append",
                    catchupSource = pendingCatchupSource ?: "?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}",
                    responseTimeMs = pendingResponseTimeMs,
                )
            )

            pendingTitle = null
            pendingGroup = null
            pendingLogo = null
            pendingTvgId = null
            pendingTvgName = null
            pendingUserAgent = null
            pendingCatchup = null
            pendingCatchupSource = null
            pendingResponseTimeMs = null
        }

        return channels
    }

    internal fun normalizeUserAgent(raw: String): String {
        val value = raw.trim()
        if (value.equals("VLC", ignoreCase = true) || value.startsWith("VLC/", ignoreCase = true)) {
            return "VLC/3.0.20 LibVLC/3.0.20"
        }
        return value
    }

    internal fun parseResponseTimeMs(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.trim().lowercase().removeSuffix("ms").trim()
        return digits.toIntOrNull()?.takeIf { it >= 0 }
    }
}
