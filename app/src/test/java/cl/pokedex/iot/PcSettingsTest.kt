package cl.pokedex.iot

import org.junit.Assert.*
import org.junit.Test

class PcSettingsTest {
    private val key = "a".repeat(64)
    @Test fun requiresHttpsAndNormalizesPin() {
        val config = PcSettings("https://192.168.1.20:8443/", "AB:".repeat(31) + "AB", key).validated()
        assertEquals("https://192.168.1.20:8443", config.address)
        assertEquals("ab".repeat(32), config.fingerprint)
    }
    @Test(expected = IllegalArgumentException::class) fun refusesPlainHttp() {
        PcSettings("http://192.168.1.20:8443", key, key).validated()
    }
    @Test(expected = IllegalArgumentException::class) fun refusesMissingPin() {
        PcSettings("https://192.168.1.20:8443", "", key).validated()
    }
    @Test(expected = IllegalArgumentException::class) fun refusesUrlCredentials() {
        PcSettings("https://user:pass@192.168.1.20:8443", key, key).validated()
    }
}
