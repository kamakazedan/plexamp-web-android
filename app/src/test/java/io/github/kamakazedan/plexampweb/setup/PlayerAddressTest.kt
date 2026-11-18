package io.github.kamakazedan.plexampweb.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAddressTest {
    @Test
    fun aNameWithoutHttpsGetsIt() {
        assertEquals("https://music.example.com/", PlayerAddress.normalize("music.example.com"))
        assertEquals("https://music.example.com/plexamp/", PlayerAddress.normalize("  music.example.com/plexamp "))
    }

    @Test
    fun anAddressOnYourOwnNetworkIsPlainHttp() {
        assertEquals("http://192.168.1.50:8080/", PlayerAddress.normalize("192.168.1.50:8080"))
        assertEquals("http://nas:8080/", PlayerAddress.normalize("nas:8080"))
        assertEquals("http://player.local/", PlayerAddress.normalize("player.local"))
        assertEquals("http://[fd00::1]:8080/", PlayerAddress.normalize("[fd00::1]:8080"))
    }

    @Test
    fun whatsTypedIsTidied() {
        assertEquals("http://music.example.com/sub/", PlayerAddress.normalize("HTTP://Music.Example.com:80/sub"))
        assertEquals("https://music.example.com/", PlayerAddress.normalize("https://music.example.com/index.html"))
        assertEquals("https://music.example.com:8443/", PlayerAddress.normalize("https://music.example.com:8443/#/settings"))
        assertEquals("http://[fd00::1]:8080/", PlayerAddress.normalize("http://[FD00::1]:8080"))
    }

    @Test
    fun whatIsntAnAddressIsRefused() {
        assertNull(PlayerAddress.normalize(""))
        assertNull(PlayerAddress.normalize("music example com"))
        assertNull(PlayerAddress.normalize("ftp://music.example.com/"))
        assertNull(PlayerAddress.normalize("https://me:secret@music.example.com/"))
        assertNull(PlayerAddress.normalize("https://"))
    }

    @Test
    fun localHosts() {
        for (host in listOf("10.0.0.1", "127.0.0.1", "172.16.0.1", "172.31.255.1", "192.168.1.5", "169.254.1.1", "100.64.0.1", "100.127.0.1", "localhost", "nas", "box.lan", "box.home.arpa", "phone.tail1234.ts.net", "[::1]", "fd12::1", "fe80::1")) {
            assertTrue(host, PlayerAddress.isLocal(host))
        }
        for (host in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.1.1", "music.example.com", "2001:db8::1", "999.1.1.1")) {
            assertFalse(host, PlayerAddress.isLocal(host))
        }
    }

    @Test
    fun onlyHttpOverTheInternetIsUnencryptedPublic() {
        assertTrue(PlayerAddress.isUnencryptedPublic("http://music.example.com/"))
        assertFalse(PlayerAddress.isUnencryptedPublic("http://192.168.1.5:8080/"))
        assertFalse(PlayerAddress.isUnencryptedPublic("https://music.example.com/"))
    }

    @Test
    fun redirectsAreFollowedOnlyToHttpAndHttps() {
        assertEquals(
            PlayerAddress.Redirect.To("https://music.example.com/"),
            PlayerAddress.redirect("http://music.example.com/", "https://music.example.com/"),
        )
        assertEquals(
            PlayerAddress.Redirect.To("https://music.example.com/plexamp/"),
            PlayerAddress.redirect("https://music.example.com/", " /plexamp/ "),
        )
        assertEquals(
            PlayerAddress.Redirect.To("https://elsewhere.example.net/"),
            PlayerAddress.redirect("https://music.example.com/", "//elsewhere.example.net/"),
        )
        for (location in listOf("file:///data/data/x", "jar:file:/x.jar!/y", "ftp://music.example.com/", "content://x/y", "mailto:me@example.com", "https://me:secret@music.example.com/", "http:///nohost", "not an address")) {
            assertEquals(location, PlayerAddress.Redirect.Nowhere, PlayerAddress.redirect("https://music.example.com/", location))
        }
    }

    @Test
    fun aRedirectFromHttpsToHttpIsNeverFollowed() {
        assertEquals(
            PlayerAddress.Redirect.Unencrypted("http://music.example.com/"),
            PlayerAddress.redirect("https://music.example.com/", "http://music.example.com/"),
        )
        // ...even on your own network.
        assertEquals(
            PlayerAddress.Redirect.Unencrypted("http://nas.local:8080/"),
            PlayerAddress.redirect("https://nas.local/", "http://nas.local:8080/"),
        )
        assertEquals(
            PlayerAddress.Redirect.To("http://192.168.1.5:8080/x/"),
            PlayerAddress.redirect("http://192.168.1.5:8080/", "x/"),
        )
    }

    @Test
    fun anotherServerIsAnotherHost() {
        assertFalse(PlayerAddress.otherServer("http://music.example.com/", "https://Music.Example.com/plexamp/"))
        assertFalse(PlayerAddress.otherServer("http://nas:8080/", "https://nas:8443/"))
        assertTrue(PlayerAddress.otherServer("https://music.example.com/", "https://www.example.com/"))
        assertTrue(PlayerAddress.otherServer("http://192.168.1.5:8080/", "http://music.example.com/"))
    }

    @Test
    fun originsAreSchemeHostAndPort() {
        assertEquals("https://music.example.com", PlayerAddress.origin("https://music.example.com/sub/"))
        assertEquals("http://192.168.1.5:8080", PlayerAddress.origin("http://192.168.1.5:8080/"))
        assertEquals("http://[fd00::1]:8080", PlayerAddress.origin("http://[fd00::1]:8080/"))
    }
}
