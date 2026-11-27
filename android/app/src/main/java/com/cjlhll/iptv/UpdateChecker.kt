package com.cjlhll.iptv

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO_OWNER = "zqs1qiwan"
    private const val REPO_NAME = "iqtvlite"
    private const val API_LATEST_RELEASE_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val WEB_LATEST_RELEASE_URL =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    data class UpdateInfo(
        val latestTag: String,
        val currentVersion: String,
        val hasUpdate: Boolean,
        val downloadUrl: String,
    )

    suspend fun checkUpdate(context: Context): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val currentVersion = getCurrentVersion(context)
                Log.d(TAG, "Current version: $currentVersion")

                val client = NetUtil.ipv4OkHttpClient
                val userAgent = "IQTV-Lite/$currentVersion (Android)"

                val apiReq = Request.Builder()
                    .url(API_LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", userAgent)
                    .build()

                runCatching {
                    client.newCall(apiReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                val parsed = parseReleaseJson(body, currentVersion)
                                if (parsed != null) return@withContext parsed
                            }
                        } else {
                            Log.w(TAG, "GitHub API latest returned HTTP ${resp.code}, trying web redirect")
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "GitHub API latest failed: ${e.message}, trying web redirect")
                }

                // 回退：跟随 GitHub /releases/latest 302 跳转提取最新正式 Tag
                val webReq = Request.Builder()
                    .url(WEB_LATEST_RELEASE_URL)
                    .header("User-Agent", userAgent)
                    .build()

                client.newCall(webReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Web latest release request failed: ${resp.code}")
                        return@withContext null
                    }
                    val finalUrl = resp.request.url.toString()
                    val html = resp.body?.string().orEmpty()
                    val latestTag = extractTagFromRedirectOrHtml(finalUrl, html) ?: return@withContext null
                    val downloadUrl = defaultApkDownloadUrl(latestTag)
                    UpdateInfo(
                        latestTag = latestTag,
                        currentVersion = currentVersion,
                        hasUpdate = compareVersions(currentVersion, latestTag) < 0,
                        downloadUrl = downloadUrl,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking update: ${e.message}", e)
                null
            }
        }
    }

    suspend fun checkUpdateAndGetInfo(context: Context): UpdateInfo? = checkUpdate(context)

    private val DRAFT_TRUE_REGEX = Regex(""""draft"\s*:\s*true\b""")
    private val PRERELEASE_TRUE_REGEX = Regex(""""prerelease"\s*:\s*true\b""")
    private val TAG_NAME_REGEX = Regex(""""tag_name"\s*:\s*"([^"\\]+)"""")
    private val APK_DOWNLOAD_URL_REGEX =
        Regex(""""browser_download_url"\s*:\s*"([^"\\]+\.apk)"""", RegexOption.IGNORE_CASE)

    internal fun parseReleaseJson(json: String, currentVersion: String): UpdateInfo? {
        if (json.isBlank()) return null
        if (DRAFT_TRUE_REGEX.containsMatchIn(json) || PRERELEASE_TRUE_REGEX.containsMatchIn(json)) {
            return null
        }
        val tag = TAG_NAME_REGEX.find(json)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (parseVersionParts(tag) == null) return null

        val apkUrls = APK_DOWNLOAD_URL_REGEX.findAll(json)
            .mapNotNull { match ->
                val url = match.groupValues[1].replace("\\/", "/").trim()
                val name = url.substringAfterLast('/').substringBefore('?')
                if (url.isBlank() ||
                    !name.endsWith(".apk", ignoreCase = true) ||
                    name.contains("androidTest", ignoreCase = true)
                ) {
                    null
                } else {
                    name to url
                }
            }
            .toList()
        val preferredUrl = apkUrls.firstOrNull { (name, _) ->
            name.startsWith("IQTV-Lite-", ignoreCase = true) && !name.contains("latest", ignoreCase = true)
        }?.second
            ?: apkUrls.firstOrNull { (name, _) ->
                name.startsWith("IQTV-Lite-", ignoreCase = true)
            }?.second
            ?: apkUrls.firstOrNull()?.second
            ?: defaultApkDownloadUrl(tag)

        return UpdateInfo(
            latestTag = tag,
            currentVersion = currentVersion,
            hasUpdate = compareVersions(currentVersion, tag) < 0,
            downloadUrl = preferredUrl,
        )
    }

    internal fun extractTagFromRedirectOrHtml(finalUrl: String, html: String): String? {
        val marker = "/releases/tag/"
        if (finalUrl.contains(marker)) {
            val tag = finalUrl.substringAfter(marker).substringBefore('?').substringBefore('#').trim()
            if (parseVersionParts(tag) != null) return tag
        }
        val match = Regex("""/releases/tag/([^\s"'/?#]+)""").find(html)?.groupValues?.getOrNull(1)?.trim()
        return match?.takeIf { parseVersionParts(it) != null }
    }

    internal fun defaultApkDownloadUrl(tag: String): String =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/IQTV-Lite-$tag.apk"

    private fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            packageInfo.versionName ?: "0.1.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "0.1.0"
        }
    }

    internal fun parseVersionParts(raw: String?): List<Int>? {
        val cleaned = raw?.trim().orEmpty()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (cleaned.isEmpty()) return null
        val segments = cleaned.split('.')
        val nums = segments.map { it.toIntOrNull() ?: return null }
        return nums.takeIf { it.isNotEmpty() }
    }

    internal fun compareVersions(current: String, latest: String): Int {
        val currentParts = parseVersionParts(current) ?: return 0
        val latestParts = parseVersionParts(latest) ?: return 0
        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (c < l) return -1
            if (c > l) return 1
        }
        return 0
    }
}
