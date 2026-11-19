package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAccountTest {
    private val player = "https://music.example.com"

    @Test
    fun onlyAddressesTheSignInIsSafeAt() {
        assertTrue(DownloadAccount.usable("https://1-2-3-4.abc.plex.direct:32400", player))
        assertTrue(DownloadAccount.usable("https://music.example.com/pms", player))
        assertTrue(DownloadAccount.usable("http://192.168.1.5:8080/pms", "http://192.168.1.5:8080"))
        assertTrue(DownloadAccount.usable("http://music.lan/pms", "http://music.lan"))
        assertTrue(DownloadAccount.usable("http://music.lan:80/pms", "http://MUSIC.lan"))
        // Plain http:// anywhere but the player itself: never.
        assertFalse(DownloadAccount.usable("http://192.168.1.10:32400", player))
        assertFalse(DownloadAccount.usable("http://192.168.1.5:9090/pms", "http://192.168.1.5:8080"))
        assertFalse(DownloadAccount.usable("https://user:pw@example.com", player))
        assertFalse(DownloadAccount.usable("https://example.com/?x=1", player))
        assertFalse(DownloadAccount.usable("file:///data", player))
        assertFalse(DownloadAccount.usable("not an address", player))
    }

    @Test
    fun anAccountAsThePlayerGivesIt() {
        val account = DownloadAccount.of(
            serverId = "abc123",
            token = "token-123456",
            owner = DownloadAccount.ownerOf("abc123", "dan"),
            uris = listOf("https://a.plex.direct:32400/", "http://192.168.1.10:32400", "https://music.example.com/pms", "https://a.plex.direct:32400"),
            device = "Kitchen\u0000",
            playerOrigin = player,
        )!!
        assertEquals(listOf("https://a.plex.direct:32400", "https://music.example.com/pms"), account.uris)
        assertEquals("Kitchen", account.device)
        assertEquals(account, DownloadAccount.fromJson(account.toJson()))
        assertFalse(account.toString().contains("token-123456"))
    }

    @Test
    fun onesThatArentRight() {
        val owner = DownloadAccount.ownerOf("abc", "dan")
        assertNull(DownloadAccount.of("abc", "token-123456", owner, listOf("http://192.168.1.10:32400"), "", player))
        assertNull(DownloadAccount.of("a/b", "token-123456", owner, listOf("https://a.plex.direct"), "", player))
        assertNull(DownloadAccount.of("abc", "x", owner, listOf("https://a.plex.direct"), "", player))
        assertNull(DownloadAccount.of("abc", "token 123456", owner, listOf("https://a.plex.direct"), "", player))
        assertNull(DownloadAccount.fromJson("{"))
        assertEquals("plexamp-web (Android)", DownloadAccount.of("abc", "token-123456", owner, listOf("https://a.plex.direct"), " ", player)!!.device)
    }

    @Test
    fun theOwnerIsAFingerprint() {
        val dan = DownloadAccount.ownerOf("abc", "dan")
        assertEquals(dan, DownloadAccount.ownerOf("abc", "dan"))
        assertNotEquals(dan, DownloadAccount.ownerOf("abc", "sam"))
        assertNotEquals(dan, DownloadAccount.ownerOf("def", "dan"))
        assertFalse(dan.contains("dan"))
    }
}
