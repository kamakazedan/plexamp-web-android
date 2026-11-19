package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRulesTest {
    private fun spec(codec: String, bitrate: Int = 0, size: Long = 0, durationMs: Long = 180_000) =
        TrackSpec("1", "/library/parts/1/100/file.$codec", size, codec, durationMs, bitrate, null)

    @Test
    fun originalKeepsFilesThePlayerPlaysAsTheyAre() {
        assertNull(DownloadRules.kbpsFor(spec("flac", 900), "original"))
        assertNull(DownloadRules.kbpsFor(spec("mp3", 320), "original"))
        // (Where the server doesn't say, as it is: as the player streams it.)
        assertNull(DownloadRules.kbpsFor(spec("", 0), "original"))
    }

    @Test
    fun originalConvertsFilesThePlayerCantPlay() {
        assertEquals(DownloadRules.CONVERTED_ORIGINAL, DownloadRules.kbpsFor(spec("alac", 1000), "original"))
        assertEquals(DownloadRules.CONVERTED_ORIGINAL, DownloadRules.kbpsFor(spec("flac", 900), "original", playable = setOf("mp3")))
    }

    @Test
    fun aLowerQualityConvertsUnlessTheFileIsAlreadyThatSmall() {
        assertEquals(128, DownloadRules.kbpsFor(spec("flac", 900), "128"))
        assertEquals(192, DownloadRules.kbpsFor(spec("mp3", 320), "192"))
        // A 128 kbps MP3 at 192: as it is (converting would only make it bigger).
        assertNull(DownloadRules.kbpsFor(spec("mp3", 128), "192"))
        // ...but never a lossless file, whatever bitrate it says.
        assertEquals(320, DownloadRules.kbpsFor(spec("flac", 300), "320"))
        // ...nor one the player can't play.
        assertEquals(192, DownloadRules.kbpsFor(spec("wma", 128), "192"))
    }

    @Test
    fun aTracksVersionChangesWithItsFileOrTheQuality() {
        val flac = spec("flac", 900, 30_000_000)
        val v = DownloadRules.versionOf(flac, "original")
        assertEquals(v, DownloadRules.versionOf(flac, "original"))
        assertNotEquals(v, DownloadRules.versionOf(flac, "320"))
        assertNotEquals(v, DownloadRules.versionOf(flac.copy(partKey = "/library/parts/1/200/file.flac"), "original"))
        assertNotEquals(v, DownloadRules.versionOf(flac.copy(partSize = 30_000_001), "original"))
        // A small MP3 kept as it is at any quality at or above its own is the same download.
        val mp3 = spec("mp3", 128, 3_000_000)
        assertEquals(DownloadRules.versionOf(mp3, "original"), DownloadRules.versionOf(mp3, "192"))
    }

    @Test
    fun estimates() {
        assertEquals(30_000_000L, DownloadRules.estimate(spec("flac", 900, 30_000_000), null))
        // 180 s at 128 kbps: 180 * 128000 / 8.
        assertEquals(2_880_000L, DownloadRules.estimate(spec("flac", 900, 30_000_000), 128))
        // No size: by its bitrate, or else as CD quality.
        assertEquals(180_000L * 320 / 8, DownloadRules.estimate(spec("mp3", 320), null))
        assertEquals(180_000L * 1411 / 8, DownloadRules.estimate(spec("flac"), null))
    }

    @Test
    fun typesAndExtensions() {
        assertEquals("audio/flac", DownloadRules.mimeOf("1-ab.flac"))
        assertEquals("audio/mpeg", DownloadRules.mimeOf("1-ab.MP3"))
        assertEquals("audio/mp4", DownloadRules.mimeOf("1-ab.m4a"))
        assertEquals("audio/aac", DownloadRules.mimeOf("1-ab.aac"))
        assertEquals("application/octet-stream", DownloadRules.mimeOf("1-ab"))
        assertEquals("flac", DownloadRules.extensionOf("/library/parts/1/100/file.FLAC"))
        assertEquals("bin", DownloadRules.extensionOf("/library/parts/1/100/file"))
        assertEquals("bin", DownloadRules.extensionOf("/library/parts/1/100/file.a b"))
    }

    @Test
    fun theReserve() {
        assertEquals(1L shl 30, DownloadRules.reserve(128L shl 30))
        assertEquals((8L shl 30) / 10, DownloadRules.reserve(8L shl 30))
    }
}
