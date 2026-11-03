package com.indiotube.app

import com.indiotube.app.data.SyncClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** QR payload parsing + the manual host:port:token fallback. */
class SyncPayloadTest {

    private val client = SyncClient(OkHttpClient())

    @Test
    fun parsesTheDesktopPairingQr() {
        val real = """{"indio":"sync","v":1,"host":"192.168.1.20","port":47711,"token":"deadbeef","name":"Desk"}"""
        val pairing = client.parsePairing(real)
        assertEquals("192.168.1.20", pairing.host)
        assertEquals(47711, pairing.port)
        assertEquals("deadbeef", pairing.token)
        assertEquals("Desk", pairing.name)
    }

    @Test
    fun rejectsForeignQrCodes() {
        assertThrows(IOException::class.java) {
            client.parsePairing("""{"aw":"sync","host":"1.2.3.4","port":1,"token":"x"}""")
        }
        assertThrows(IOException::class.java) { client.parsePairing("https://youtube.com/watch?v=x") }
    }

    @Test
    fun manualFallbackAcceptsSpaceAndColonSeparated() {
        val a = client.parsePairing("192.168.1.20 47711 abcdef0123456789")
        assertEquals("192.168.1.20", a.host)
        assertEquals(47711, a.port)
        assertEquals("abcdef0123456789", a.token)

        val b = client.parsePairing("10.0.0.5:55000:feedface:DeskPC")
        assertEquals("10.0.0.5", b.host)
        assertEquals(55000, b.port)
        assertEquals("feedface", b.token)
        assertEquals("DeskPC", b.name)
    }

    @Test
    fun manualFallbackRejectsGarbage() {
        assertThrows(IOException::class.java) { client.parsePairing("just-one-field") }
        assertThrows(IOException::class.java) { client.parsePairing("host notaport token") }
    }
}
