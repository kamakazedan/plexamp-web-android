package io.github.kamakazedan.plexampweb.downloads

import java.io.IOException
import java.io.OutputStream

/**
 * The audio in an MPEG transport stream (the segments of a track a Plex server converts
 * for streaming, HLS's), as a file of its own: MP3's frames as an .mp3, or AAC's (ADTS)
 * as an .aac. Fed the stream as it comes, in pieces of any size, segment after segment.
 */
class TsDemuxer(private val out: OutputStream) {
    enum class Codec(val extension: String) { MP3("mp3"), AAC("aac") }

    class NotAudio(message: String) : IOException(message)

    /** The audio's format, once the stream has said (in its program map). */
    var codec: Codec? = null
        private set

    /** Audio bytes written so far. */
    var written = 0L
        private set

    private val packet = ByteArray(PACKET)
    private var filled = 0
    private var pmtPid = NONE
    private var audioPid = NONE

    // The audio's packets (PES) each start with a header, up to 9 + 255 bytes, then the audio itself.
    private enum class Pes { WAITING, HEADER, AUDIO }

    private var pes = Pes.WAITING
    private val header = ByteArray(9 + 255)
    private var headerFilled = 0

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val n = minOf(PACKET - filled, end - i)
            System.arraycopy(bytes, i, packet, filled, n)
            filled += n
            i += n
            if (filled == PACKET) {
                onPacket()
                filled = 0
            }
        }
    }

    /** A segment's end: each holds whole packets. */
    fun endOfSegment() {
        if (filled != 0) throw NotAudio("A segment ended part-way through a packet.")
    }

    /** The stream's end. */
    fun finish() {
        endOfSegment()
        if (written == 0L) throw NotAudio("There was no audio in it.")
    }

    private fun onPacket() {
        val p = packet
        if (p[0] != SYNC) throw NotAudio("It isn't an MPEG transport stream.")
        // (One the sender marked as damaged: left out.)
        if (p[1].toInt() and 0x80 != 0) return
        val starts = p[1].toInt() and 0x40 != 0
        val pid = ((p[1].toInt() and 0x1F) shl 8) or u8(p[2])
        val control = (p[3].toInt() shr 4) and 0x3
        var at = 4
        if (control and 0x2 != 0) at += 1 + u8(p[4])
        if (control and 0x1 == 0 || at >= PACKET) return
        when (pid) {
            PAT_PID -> if (starts) onPat(at)
            pmtPid -> if (starts) onPmt(at)
            audioPid -> onAudio(at, starts)
        }
    }

    // A table (the PAT or a PMT) starting in this packet, and ending in it too, as they do: its
    // contents after the 8 bytes every one starts with, without its CRC.
    private fun table(at: Int, id: Int): IntRange? {
        val start = at + 1 + u8(packet[at])
        if (start + 3 > PACKET || u8(packet[start]) != id) return null
        val length = ((packet[start + 1].toInt() and 0x0F) shl 8) or u8(packet[start + 2])
        val end = start + 3 + length - 4
        return if (length < 9 || end + 4 > PACKET) null else (start + 8) until end
    }

    // Which programs there are, and where each one's map is: the first one's is used.
    private fun onPat(at: Int) {
        val t = table(at, 0x00) ?: return
        for (i in t step 4) {
            if (i + 4 > t.last + 1) break
            val program = (u8(packet[i]) shl 8) or u8(packet[i + 1])
            if (program != 0) {
                pmtPid = ((packet[i + 2].toInt() and 0x1F) shl 8) or u8(packet[i + 3])
                return
            }
        }
    }

    // What the program's streams are: its first audio in a format kept as it is.
    private fun onPmt(at: Int) {
        val t = table(at, 0x02) ?: return
        val end = t.last + 1
        var i = t.first + 4 + (((packet[t.first + 2].toInt() and 0x0F) shl 8) or u8(packet[t.first + 3]))
        while (i + 5 <= end) {
            val type = u8(packet[i])
            val pid = ((packet[i + 1].toInt() and 0x1F) shl 8) or u8(packet[i + 2])
            val info = ((packet[i + 3].toInt() and 0x0F) shl 8) or u8(packet[i + 4])
            val found = when (type) {
                0x03, 0x04 -> Codec.MP3
                0x0F -> Codec.AAC
                else -> null
            }
            if (found != null) {
                if (codec != null && codec != found) throw NotAudio("The audio's format changed part-way.")
                codec = found
                if (pid != audioPid) pes = Pes.WAITING
                audioPid = pid
                return
            }
            i += 5 + info
        }
    }

    private fun onAudio(from: Int, starts: Boolean) {
        var i = from
        if (starts) {
            pes = Pes.HEADER
            headerFilled = 0
        }
        if (pes == Pes.WAITING) return
        while (pes == Pes.HEADER && i < PACKET) {
            header[headerFilled++] = packet[i++]
            if (headerFilled == 9 && (header[0].toInt() != 0 || header[1].toInt() != 0 || header[2].toInt() != 1)) {
                throw NotAudio("Its audio isn't in packets as it should be.")
            }
            if (headerFilled >= 9 && headerFilled == 9 + u8(header[8])) pes = Pes.AUDIO
        }
        if (pes == Pes.AUDIO && i < PACKET) {
            out.write(packet, i, PACKET - i)
            written += PACKET - i
        }
    }

    private companion object {
        const val PACKET = 188
        const val SYNC = 0x47.toByte()
        const val PAT_PID = 0
        const val NONE = -1

        fun u8(b: Byte) = b.toInt() and 0xFF
    }
}
