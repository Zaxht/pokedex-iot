package cl.pokedex.iot

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Ejecuta el cliente HTTPS de producción contra un servidor local con un certificado temporal. */
class PcClientSecurityTest {
    companion object {
        @ClassRule @JvmField val temp = TemporaryFolder()
        private lateinit var tls: SSLContext
        private lateinit var fingerprint: String
        @BeforeClass @JvmStatic fun certificate() {
            val keysFile = File(temp.root, "test.p12")
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
            val keytool = File(System.getProperty("java.home"), "bin/keytool$suffix")
            val output = File(temp.root, "keytool.log")
            val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keysFile.path, "-storepass", "temporary-test-only", "-keypass", "temporary-test-only",
                "-dname", "CN=127.0.0.1", "-ext", "SAN=IP:127.0.0.1", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Test certificate creation timed out") }
            check(process.exitValue() == 0) { "Could not create temporary certificate: ${output.readText()}" }
            val password = "temporary-test-only".toCharArray()
            val keys = KeyStore.getInstance("PKCS12").apply { keysFile.inputStream().use { load(it, password) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            fingerprint = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("test").encoded).joinToString("") { "%02x".format(it) }
        }
    }

    private fun endpoint(block: (PcSettings, AtomicInteger) -> Unit) {
        // SSLServerSocket forma parte de las API Java disponibles para las pruebas locales Android.
        val server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val worker = Executors.newSingleThreadExecutor()
        val targetVisits = AtomicInteger()
        worker.submit {
            while (!server.isClosed) {
                try {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.inputStream.bufferedReader(Charsets.US_ASCII)
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        var headerBytes = 0
                        while (true) {
                            val header = reader.readLine() ?: return@use
                            if (header.isEmpty()) break
                            headerBytes += header.length
                            check(headerBytes < 8192)
                        }
                        val redirect = path == "/redirect"
                        if (path == "/target") targetVisits.incrementAndGet()
                        val payload = "{\"ok\":true}"
                        val status = if (redirect) "302 Found" else "200 OK"
                        val location = if (redirect) "Location: /target\r\n" else ""
                        val response = "HTTP/1.1 $status\r\n${location}Content-Type: application/json\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload"
                        socket.outputStream.write(response.toByteArray(Charsets.US_ASCII))
                        socket.outputStream.flush()
                    }
                } catch (_: java.io.IOException) { /* Incluye el rechazo de certificado esperado por el cliente. */ }
            }
        }
        try { block(PcSettings("https://127.0.0.1:${server.localPort}", fingerprint, "a".repeat(64)), targetVisits) }
        finally { server.close(); worker.shutdownNow() }
    }

    @Test fun acceptsThePinnedCertificateOverHttps() = endpoint { settings, _ ->
        assertTrue(PcClient().request(settings, "/ok").getBoolean("ok"))
    }
    @Test fun rejectsAnotherFingerprintBeforeReadingApplicationData() = endpoint { settings, visits ->
        try { PcClient().request(settings.copy(fingerprint = "0".repeat(64)), "/target"); fail("Wrong certificate accepted") }
        catch (_: SSLException) { }
        assertEquals(0, visits.get())
    }
    @Test fun doesNotFollowRedirectsWithCredentials() = endpoint { settings, visits ->
        try { PcClient().request(settings, "/redirect"); fail("Redirect followed") }
        catch (e: PcApiException) { assertEquals(302, e.status) }
        assertEquals(0, visits.get())
    }
}
