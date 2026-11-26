package com.ipdial.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun isNewerVersion_handlesSemanticAndPrefixedVersions() {
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "v2.0.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.9.9", "2.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("2.0.0", "v2.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("2.1.0", "v2.0.9"))
        assertTrue(UpdateChecker.isNewerVersion("2.0", "2.0.1"))
    }

    @Test
    fun findApkAsset_prefersDialDuoApkOverOtherAssets() {
        val release = UpdateChecker.GitHubRelease(
            tagName = "v2.0.1",
            htmlUrl = "https://github.com/nazimunaeem/IPDial/releases/tag/v2.0.1",
            assets = listOf(
                UpdateChecker.GitHubAsset(
                    name = "SHA256SUMS.txt",
                    size = 256L,
                    browserDownloadUrl = "https://example.com/SHA256SUMS.txt"
                ),
                UpdateChecker.GitHubAsset(
                    name = "DialDuo-v2.0.1.aab",
                    size = 12_000_000L,
                    browserDownloadUrl = "https://example.com/DialDuo-v2.0.1.aab"
                ),
                UpdateChecker.GitHubAsset(
                    name = "DialDuo-v2.0.1.apk",
                    size = 14_000_000L,
                    browserDownloadUrl = "https://example.com/DialDuo-v2.0.1.apk"
                )
            )
        )

        val apk = release.findApkAsset()
        assertEquals("DialDuo-v2.0.1.apk", apk?.name)
    }

    @Test
    fun findApkAsset_returnsNullWhenNoApkAttached() {
        val release = UpdateChecker.GitHubRelease(
            tagName = "v2.0.1",
            htmlUrl = "https://github.com/nazimunaeem/IPDial/releases/tag/v2.0.1",
            assets = listOf(
                UpdateChecker.GitHubAsset(
                    name = "SHA256SUMS.txt",
                    size = 128L,
                    browserDownloadUrl = "https://example.com/SHA256SUMS.txt"
                )
            )
        )

        assertNull(release.findApkAsset())
    }
}
