package io.github.kamakazedan.plexampweb.downloads

import java.io.ByteArrayOutputStream

/** MPEG transport streams, as a Plex server's converted segments are: for the tests. */
object TsSamples {
    const val PMT_PID = 0x1000
    const val AUDIO_PID = 0x100

    /** A segment: the PAT, the PMT (audio of `streamType`), then each of `frames` in a PES of its own. */
    fun segment(frames: List<ByteArray>, streamType: Int = 0x0F, extra: List<Pair<Int, Int>> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        val counters = HashMap<Int, Int>()
        fun put(pid: Int, data: ByteArray, psi: Boolean) = packets(pid, if (psi) byteArrayOf(0) + data else data, counters).forEach(out::write)
        put(0, pat(), psi = true)
        put(PMT_PID, pmt(extra + (streamType to AUDIO_PID)), psi = true)
        for (frame in frames) put(AUDIO_PID, pes(frame), psi = false)
        return out.toByteArray()
    }

    fun frames(count: Int, size: Int, seed: Int = 1): List<ByteArray> {
        val random = java.util.Random(seed.toLong())
        return List(count) { ByteArray(size).also(random::nextBytes) }
    }

    private fun pat(): ByteArray {
        // Stream 1, version 0, then program 1 (its map at PMT_PID).
        val body = byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte())
        return section(0x00, body)
    }

    // `streams`: (stream type, PID), in order.
    private fun pmt(streams: List<Pair<Int, Int>>): ByteArray {
        val es = ByteArrayOutputStream()
        for ((type, pid) in streams) es.write(byteArrayOf(type.toByte(), (0xE0 or (pid shr 8)).toByte(), (pid and 0xFF).toByte(), 0xF0.toByte(), 0x00))
        // Program 1, version 0, its PCR on the audio, and 2 bytes of program info (a descriptor, skipped).
        val head = byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, (0xE0 or (AUDIO_PID shr 8)).toByte(), (AUDIO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x02, 0x05, 0x00)
        return section(0x02, head + es.toByteArray())
    }

    // A table: its id, its length, `body` (from what comes after the length), and a CRC (not checked).
    private fun section(id: Int, body: ByteArray): ByteArray {
        val length = body.size + 4
        return byteArrayOf(id.toByte(), (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte()) + body + byteArrayOf(0, 0, 0, 0)
    }

    // A PES: audio's stream id, its length, a PTS in its header, then the frame.
    private fun pes(frame: ByteArray): ByteArray {
        val length = 3 + 5 + frame.size
        return byteArrayOf(0, 0, 1, 0xC0.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte(), 0x80.toByte(), 0x80.toByte(), 0x05, 0x21, 0x00, 0x01, 0x00, 0x01) + frame
    }

    // `data` in 188-byte packets, the last one filled out with an adaptation field.
    private fun packets(pid: Int, data: ByteArray, counters: MutableMap<Int, Int>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < data.size) {
            val n = minOf(184, data.size - i)
            val p = ByteArray(188)
            val cc = counters.getOrDefault(pid, 0)
            counters[pid] = (cc + 1) and 0xF
            p[0] = 0x47
            p[1] = ((if (i == 0) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = (pid and 0xFF).toByte()
            if (n == 184) {
                p[3] = (0x10 or cc).toByte()
                System.arraycopy(data, i, p, 4, n)
            } else {
                val stuffing = 183 - n
                p[3] = (0x30 or cc).toByte()
                p[4] = stuffing.toByte()
                if (stuffing > 0) {
                    p[5] = 0
                    for (j in 6 until 5 + stuffing) p[j] = 0xFF.toByte()
                }
                System.arraycopy(data, i, p, 5 + stuffing, n)
            }
            out += p
            i += n
        }
        return out
    }
}
