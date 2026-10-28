package cl.pokedex.iot

import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private val settings = PcSettings("https://192.168.1.20:8443", "a".repeat(64), "b".repeat(64))
    @Test fun restoresAllConnectionFields() { assertEquals(settings, Pairing.decode(Pairing.encode(settings))) }
    @Test fun rejectsUnrelatedOrFutureQr() {
        for (raw in listOf("https://example.com", Pairing.encode(settings).replace("pokedex-pairing", "other"),
            Pairing.encode(settings).replace("\"version\":1", "\"version\":2"))) {
            assertTrue(runCatching { Pairing.decode(raw) }.isFailure)
        }
    }
    @Test fun rejectsInsecureOrMissingCredentials() {
        for (bad in listOf(settings.copy(address = "http://192.168.1.20:8443"), settings.copy(token = ""),
            settings.copy(fingerprint = "invalid"), settings.copy(address = "https://user:pass@example.com"))) {
            assertTrue(runCatching { Pairing.decode(Pairing.encode(bad)) }.isFailure)
        }
    }
}
