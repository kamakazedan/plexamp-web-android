package io.github.kamakazedan.plexampweb.downloads

/**
 * Keeps downloads gentle while music plays: no faster than `bytesPerSecond` then, so they
 * take less of the phone's Wi-Fi (whose radio Bluetooth shares, on many phones), its
 * storage and its processor, and full speed otherwise. A playlist downloads in the
 * background as ever, only more slowly while it plays.
 */
class Pacer(
    /** Whether music's playing now. */
    private val busy: () -> Boolean,
    private val bytesPerSecond: Long = GENTLE,
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    // Bytes that may come now without waiting (less than none: owed), as last counted.
    private var allowed = 0L
    private var countedAt = -1L

    /** `n` bytes came: waits, while music plays, till they're due. */
    fun took(n: Int) {
        if (!busy()) {
            countedAt = -1
            return
        }
        val now = clock()
        allowed = if (countedAt < 0) {
            BURST
        } else {
            // (A second's worth at most, so a long while not downloading isn't made up for in a rush.)
            val elapsed = (now - countedAt).coerceIn(0, NANOS)
            minOf(BURST, allowed + elapsed * bytesPerSecond / NANOS)
        }
        countedAt = now
        allowed -= n
        if (allowed < 0) sleep(-allowed * 1000 / bytesPerSecond)
    }

    companion object {
        /** A megabyte a second: faster than any music plays, without crowding it. */
        const val GENTLE = 1L shl 20

        private const val BURST = 256L shl 10
        private const val NANOS = 1_000_000_000L
    }
}
