package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacerTest {
    private var now = 1_000_000_000L
    private var playing = true
    private var slept = 0L

    // A clock that the pacer's own waits move on.
    private val pacer = Pacer(
        busy = { playing },
        bytesPerSecond = 1_000_000,
        clock = { now },
        sleep = { ms ->
            slept += ms
            now += ms * 1_000_000
        },
    )

    @Test
    fun fullSpeedWhileNothingPlays() {
        playing = false
        repeat(1000) { pacer.took(64 * 1024) }
        assertEquals(0L, slept)
    }

    @Test
    fun aMegabyteASecondWhileMusicPlays() {
        // 10 MB at once, as fast as they come: about ten seconds, less the little allowed at once.
        repeat(160) { pacer.took(62_500) }
        assertTrue("slept $slept ms", slept in 9_600L..10_000L)
    }

    @Test
    fun aLongWhileNotDownloadingIsntMadeUpForInARush() {
        pacer.took(1)
        now += 3_600_000_000_000L // an hour
        repeat(160) { pacer.took(62_500) }
        assertTrue("slept $slept ms", slept in 9_600L..10_000L)
    }

    @Test
    fun aSlowConnectionIsntSlowedFurther() {
        // Half a megabyte a second, as it comes: no waiting.
        repeat(100) {
            pacer.took(50_000)
            now += 100_000_000
        }
        assertEquals(0L, slept)
    }

    @Test
    fun musicStartingSlowsItThenAndNotBefore() {
        playing = false
        repeat(100) { pacer.took(1_000_000) }
        assertEquals(0L, slept)
        playing = true
        repeat(10) { pacer.took(1_000_000) }
        assertTrue("slept $slept ms", slept in 9_000L..10_000L)
    }
}
