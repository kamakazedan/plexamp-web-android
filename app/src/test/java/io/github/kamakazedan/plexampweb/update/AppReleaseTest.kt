package io.github.kamakazedan.plexampweb.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseTest {
    private val page = "https://github.com/kamakazedan/plexamp-web-android/releases/tag/v0.2.4"
    private val apk = "https://github.com/kamakazedan/plexamp-web-android/releases/download/v0.2.4/plexamp-web-android-0.2.4.apk"

    // As GitHub answers for the newest release (what matters of it).
    private fun latest(tag: String = "v0.2.4", html: String = page, downloads: List<String> = listOf("$apk.sha256", apk), draft: Boolean = false, prerelease: Boolean = false) =
        JSONObject()
            .put("tag_name", tag)
            .put("html_url", html)
            .put("draft", draft)
            .put("prerelease", prerelease)
            .put("assets", JSONArray(downloads.map { JSONObject().put("name", it.substringAfterLast('/')).put("browser_download_url", it) }))
            .toString()

    @Test
    fun theNewestReleasesApk() {
        assertEquals(AppRelease("0.2.4", apk), AppRelease.of(latest()))
    }

    @Test
    fun itsPageWhereItHasNoApk() {
        assertEquals(AppRelease("0.2.4", page), AppRelease.of(latest(downloads = listOf("$apk.sha256"))))
    }

    @Test
    fun onlyThisAppsOwnReleases() {
        val elsewhere = "https://github.com/someone/else/releases/download/v0.2.4/plexamp-web-android-0.2.4.apk"
        assertEquals(AppRelease("0.2.4", page), AppRelease.of(latest(downloads = listOf(elsewhere))))
        assertEquals(AppRelease("0.2.4", page), AppRelease.of(latest(downloads = listOf("https://github.com/kamakazedan/plexamp-web-android/releases/download/../../x.apk"))))
        assertEquals(AppRelease("0.2.4", page), AppRelease.of(latest(downloads = listOf("$apk?x=1", "http://github.com/kamakazedan/plexamp-web-android/releases/download/v0.2.4/a.apk"))))
        assertNull(AppRelease.of(latest(html = "https://example.com/releases/tag/v0.2.4", downloads = emptyList())))
    }

    @Test
    fun notADraftNorAPreReleaseNorAnythingElse() {
        assertNull(AppRelease.of(latest(draft = true)))
        assertNull(AppRelease.of(latest(prerelease = true)))
        assertNull(AppRelease.of(latest(tag = "nightly")))
        assertNull(AppRelease.of("Not JSON"))
        assertNull(AppRelease.of("{}"))
    }

    @Test
    fun versionsFromTags() {
        assertEquals("0.2.4", AppRelease.versionOf("v0.2.4"))
        assertEquals("1.0", AppRelease.versionOf("1.0"))
        assertNull(AppRelease.versionOf("v1"))
        assertNull(AppRelease.versionOf("v0.2.4-beta"))
    }

    @Test
    fun whichVersionIsLater() {
        assertTrue(AppRelease.newer("0.2.4", "0.2.3"))
        assertTrue(AppRelease.newer("0.2.10", "0.2.9"))
        assertTrue(AppRelease.newer("1.0", "0.9.9"))
        assertTrue(AppRelease.newer("0.3", "0.2.9"))
        assertFalse(AppRelease.newer("0.2.3", "0.2.3"))
        assertFalse(AppRelease.newer("0.2.3", "0.2.4"))
        assertFalse(AppRelease.newer("0.2", "0.2.0"))
    }
}
