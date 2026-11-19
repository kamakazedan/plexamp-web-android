package io.github.kamakazedan.plexampweb.gui

/**
 * When the player on screen moves to a newer version saved meanwhile. Its page keeps the
 * version it opened with till it's loaded again, and the app is seldom closed (Back only
 * hides it, and it keeps running while music plays), so it's reloaded onto the new one
 * as the app's opened, or as one's saved before anything's been touched since. Reloaded,
 * the web app brings its queue back paused where it was, so only music playing would be
 * cut off: Reload is offered then instead, and the next opening without music does it.
 */
object NewVersion {
    enum class Step {
        /** Nothing newer, or not now. */
        NONE,

        /** The player's reloaded now. */
        RELOAD,

        /** Reload is offered. */
        OFFER,
    }

    /**
     * `inUse`: the version the page on screen opened from; `newest`: the newest saved.
     * `opening`: the app's just been opened, and nothing's been touched since. `playing`:
     * music's playing. `waiting`: the page is waiting on something it asked for, which
     * a reload would lose (files being picked or saved, a sign-in being handed over).
     */
    fun step(inUse: String?, newest: String?, opening: Boolean, playing: Boolean, waiting: Boolean): Step = when {
        inUse == null || newest == null || inUse == newest || waiting -> Step.NONE
        playing || !opening -> Step.OFFER
        else -> Step.RELOAD
    }
}
