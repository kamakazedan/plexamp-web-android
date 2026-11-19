package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class TsDemuxerTest {
    private fun join(frames: List<ByteArray>): ByteArray = frames.fold(ByteArray(0)) { all, f -> all + f }

    // Fed in pieces of every size, as the network gives them.
    private fun feed(demuxer: TsDemuxer, bytes: ByteArray, random: Random) {
        var i = 0
        while (i < bytes.size) {
            val n = minOf(bytes.size - i, 1 + random.nextInt(700))
            demuxer.write(bytes, i, n)
            i += n
        }
    }

    @Test
    fun aacSegmentsBecomeOneFile() {
        val first = TsSamples.frames(30, 371, seed = 1)
        // (Sizes that end a packet with every amount of filling out, none among them.)
        val second = TsSamples.frames(25, 180, seed = 2) + TsSamples.frames(1, 169, seed = 3) + TsSamples.frames(1, 170, seed = 4) + TsSamples.frames(1, 1, seed = 5)
        val out = ByteArrayOutputStream()
        val demuxer = TsDemuxer(out)
        val random = Random(7)
        // (An ID3 stream, for timed metadata, as a segment can have: not the audio.)
        feed(demuxer, TsSamples.segment(first, extra = listOf(0x15 to 0x101)), random)
        demuxer.endOfSegment()
        feed(demuxer, TsSamples.segment(second), random)
        demuxer.finish()
        assertEquals(TsDemuxer.Codec.AAC, demuxer.codec)
        assertArrayEquals(join(first + second), out.toByteArray())
        assertEquals(out.size().toLong(), demuxer.written)
    }

    @Test
    fun mp3() {
        val frames = TsSamples.frames(10, 417)
        val out = ByteArrayOutputStream()
        val demuxer = TsDemuxer(out)
        demuxer.write(TsSamples.segment(frames, streamType = 0x03))
        demuxer.finish()
        assertEquals(TsDemuxer.Codec.MP3, demuxer.codec)
        assertEquals("mp3", demuxer.codec!!.extension)
        assertArrayEquals(join(frames), out.toByteArray())
    }

    @Test
    fun whatIsntAudioAsItShouldBe() {
        // Not a transport stream at all (an error page, say).
        assertThrows(TsDemuxer.NotAudio::class.java) { TsDemuxer(ByteArrayOutputStream()).write("<html>".repeat(40).toByteArray()) }
        // Cut short part-way through a packet.
        val cut = TsDemuxer(ByteArrayOutputStream())
        cut.write(TsSamples.segment(TsSamples.frames(2, 100)).copyOf(188 * 3 + 5))
        assertThrows(TsDemuxer.NotAudio::class.java) { cut.endOfSegment() }
        // Audio in a format that isn't kept as it is (AC-3 in private data): nothing comes of it.
        val other = TsDemuxer(ByteArrayOutputStream())
        other.write(TsSamples.segment(TsSamples.frames(2, 100), streamType = 0x81))
        assertThrows(TsDemuxer.NotAudio::class.java) { other.finish() }
    }
}
