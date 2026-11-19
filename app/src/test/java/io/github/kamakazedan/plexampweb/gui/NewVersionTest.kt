package io.github.kamakazedan.plexampweb.gui

import io.github.kamakazedan.plexampweb.gui.NewVersion.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewVersionTest {
    private fun step(
        inUse: String? = "old",
        newest: String? = "new",
        opening: Boolean = true,
        playing: Boolean = false,
        waiting: Boolean = false,
    ) = NewVersion.step(inUse, newest, opening, playing, waiting)

    @Test
    fun aNewerVersionIsUsedAsTheAppsOpenedWithNothingPlaying() {
        assertEquals(Step.RELOAD, step())
    }

    @Test
    fun musicPlayingIsNeverCutOff() {
        assertEquals(Step.OFFER, step(playing = true))
        assertEquals(Step.OFFER, step(playing = true, opening = false))
    }

    @Test
    fun aVersionSavedWhileThePlayersInUseIsOffered() {
        assertEquals(Step.OFFER, step(opening = false))
    }

    @Test
    fun nothingNewerNothingToDo() {
        assertEquals(Step.NONE, step(newest = "old"))
        assertEquals(Step.NONE, step(newest = null))
        // (A page that didn't open from a saved copy, or hasn't opened yet.)
        assertEquals(Step.NONE, step(inUse = null))
    }

    @Test
    fun notWhileThePageWaitsOnSomethingItAskedFor() {
        assertEquals(Step.NONE, step(waiting = true))
        assertEquals(Step.NONE, step(waiting = true, playing = true))
    }

    @Test
    fun whyACheckFailedIsKeptAndReadBack() {
        for (failed in listOf(
            GuiUpdater.Outcome.Failed(GuiUpdater.Reason.REFUSED, 403),
            GuiUpdater.Outcome.Failed(GuiUpdater.Reason.MISSING, 502),
            GuiUpdater.Outcome.Failed(GuiUpdater.Reason.UNREACHABLE),
        )) {
            assertEquals(failed, GuiUpdater.Outcome.Failed.of(failed.code))
        }
        for (code in listOf(null, "", "REFUSED", "GONE 404", "REFUSED x", "REFUSED 403 extra")) {
            assertNull(code, GuiUpdater.Outcome.Failed.of(code))
        }
    }
}
