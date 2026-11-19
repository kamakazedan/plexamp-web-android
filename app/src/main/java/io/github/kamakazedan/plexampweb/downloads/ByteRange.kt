package io.github.kamakazedan.plexampweb.downloads

/**
 * The part of a file a request asks for (its Range header), as the player's audio asks
 * for a downloaded track's: from `start` to `end`, both included.
 */
data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1

    /** For Content-Range: "bytes 0-99/1000". */
    fun contentRange(total: Long) = "bytes $start-$end/$total"

    companion object {
        private val SINGLE = Regex("^bytes=(\\d*)-(\\d*)$")

        /** Past the file's end: answered with 416. */
        val UNSATISFIABLE = ByteRange(-1, -2)

        /**
         * The range `header` asks for of a file of `total` bytes: null for all of it (no
         * header, or one that isn't understood, which HTTP lets a server answer with all of
         * it), or UNSATISFIABLE.
         */
        fun of(header: String?, total: Long): ByteRange? {
            val m = header?.trim()?.let { SINGLE.matchEntire(it) } ?: return null
            val (first, last) = m.destructured
            if (first.length > 18 || last.length > 18) return null
            return when {
                first.isEmpty() && last.isEmpty() -> null
                // The last so many bytes.
                first.isEmpty() -> {
                    val n = last.toLong()
                    if (n == 0L || total == 0L) UNSATISFIABLE else ByteRange(maxOf(0, total - n), total - 1)
                }
                else -> {
                    val start = first.toLong()
                    val end = if (last.isEmpty()) total - 1 else minOf(last.toLong(), total - 1)
                    when {
                        start >= total -> UNSATISFIABLE
                        end < start -> null
                        else -> ByteRange(start, end)
                    }
                }
            }
        }
    }
}
