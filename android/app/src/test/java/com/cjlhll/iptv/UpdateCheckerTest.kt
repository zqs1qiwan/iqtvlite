package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parsesOfficialGithubReleaseAndSelectsVersionedApk() {
        val json = """
            {
              "tag_name": "v0.2.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "app-debug-androidTest.apk",
                  "browser_download_url": "https://github.com/zqs1qiwan/iqtvlite/releases/download/v0.2.0/app-debug-androidTest.apk"
                },
                {
                  "name": "IQTV-Lite-latest.apk",
                  "browser_download_url": "https://github.com/zqs1qiwan/iqtvlite/releases/download/v0.2.0/IQTV-Lite-latest.apk"
                },
                {
                  "name": "IQTV-Lite-v0.2.0.apk",
                  "browser_download_url": "https://github.com/zqs1qiwan/iqtvlite/releases/download/v0.2.0/IQTV-Lite-v0.2.0.apk"
                }
              ]
            }
        """.trimIndent()

        val info = UpdateChecker.parseReleaseJson(json, "0.1.0")!!
        assertEquals("v0.2.0", info.latestTag)
        assertTrue(info.hasUpdate)
        assertEquals(
            "https://github.com/zqs1qiwan/iqtvlite/releases/download/v0.2.0/IQTV-Lite-v0.2.0.apk",
            info.downloadUrl,
        )

        val same = UpdateChecker.parseReleaseJson(json, "0.2.0")!!
        assertFalse(same.hasUpdate)
    }

    @Test
    fun ignoresNonNumericTagsPrereleasesAndValidationSuffix() {
        assertEquals(0, UpdateChecker.compareVersions("0.1.0", "latest"))
        assertEquals(0, UpdateChecker.compareVersions("0.1.0-validation", "v0.1.0"))
        assertTrue(UpdateChecker.compareVersions("0.1.0-validation", "v0.1.1") < 0)
        assertTrue(UpdateChecker.compareVersions("0.2.0", "v0.1.9") > 0)

        val rollingLatest = """{"tag_name":"latest","draft":false,"prerelease":false,"assets":[]}"""
        assertNull(UpdateChecker.parseReleaseJson(rollingLatest, "0.1.0"))

        val pre = """{"tag_name":"v0.2.0-rc1","draft":false,"prerelease":true,"assets":[]}"""
        assertNull(UpdateChecker.parseReleaseJson(pre, "0.1.0"))
    }

    @Test
    fun extractsVersionTagFromWebRedirectUrl() {
        assertEquals(
            "v0.2.1",
            UpdateChecker.extractTagFromRedirectOrHtml(
                "https://github.com/zqs1qiwan/iqtvlite/releases/tag/v0.2.1",
                "",
            ),
        )
        assertNull(
            UpdateChecker.extractTagFromRedirectOrHtml(
                "https://github.com/zqs1qiwan/iqtvlite/releases/tag/latest",
                "",
            ),
        )
    }
}
