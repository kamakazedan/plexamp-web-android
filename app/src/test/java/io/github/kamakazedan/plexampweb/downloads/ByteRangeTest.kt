package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteRangeTest {
    @Test
    fun ranges() {
        assertNull(ByteRange.of(null, 1000))
        assertEquals(ByteRange(0, 999), ByteRange.of("bytes=0-", 1000))
        assertEquals(ByteRange(100, 199), ByteRange.of("bytes=100-199", 1000))
        assertEquals(ByteRange(900, 999), ByteRange.of("bytes=900-5000", 1000))
        assertEquals(ByteRange(500, 999), ByteRange.of("bytes=-500", 1000))
        assertEquals(ByteRange(0, 999), ByteRange.of("bytes=-5000", 1000))
        assertEquals(100L, ByteRange(100, 199).length)
        assertEquals("bytes 100-199/1000", ByteRange(100, 199).contentRange(1000))
    }

    @Test
    fun pastTheEnd() {
        assertEquals(ByteRange.UNSATISFIABLE, ByteRange.of("bytes=1000-", 1000))
        assertEquals(ByteRange.UNSATISFIABLE, ByteRange.of("bytes=-0", 1000))
        assertEquals(ByteRange.UNSATISFIABLE, ByteRange.of("bytes=0-", 0))
    }

    @Test
    fun anythingElseIsTheWholeFile() {
        assertNull(ByteRange.of("bytes=5-3", 1000))
        assertNull(ByteRange.of("bytes=0-1,5-6", 1000))
        assertNull(ByteRange.of("items=0-1", 1000))
        assertNull(ByteRange.of("bytes=-", 1000))
        assertNull(ByteRange.of("bytes=99999999999999999999-", 1000))
    }
}
