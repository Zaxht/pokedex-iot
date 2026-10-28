package cl.pokedex.iot

import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

data class PcSettings(val address: String, val fingerprint: String, val token: String) {
    fun validated(): PcSettings {
        val url = URL(address.trim().trimEnd('/'))
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null && url.path.isEmpty()) {
            "Usa una dirección como https://192.168.1.20:8443, sin rutas adicionales."
        }
        val pin = fingerprint.replace(":", "").replace(" ", "").trim().lowercase()
        require(pin.matches(Regex("[a-f0-9]{64}"))) { "La huella SHA-256 debe contener 64 caracteres hexadecimales." }
        require(token.trim().matches(Regex("[a-f0-9]{64}"))) { "Copia la clave de conexión completa que muestra el servidor." }
        return copy(address = url.toString(), fingerprint = pin, token = token.trim())
    }
}

class PcApiException(val status: Int, message: String) : java.io.IOException(message)

class PcClient {
    fun request(settings: PcSettings, path: String, photo: ByteArray? = null, session: String = "", data: JSONObject? = null): JSONObject {
        val config = settings.validated()
        fun verify(cert: X509Certificate) {
            cert.checkValidity()
            val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            val expected = config.fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (!MessageDigest.isEqual(actual, expected)) throw java.security.cert.CertificateException("La huella del PC no coincide.")
        }
        // Confiamos exclusivamente en el certificado cuya huella se copió del PC.
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Not a server certificate")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("No certificate")
                verify(chain[0])
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val connection = URL(config.address + path).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = tls.socketFactory
        // La identidad se verifica por certificado fijado, no por el nombre/IP variable del Wi-Fi.
        connection.hostnameVerifier = HostnameVerifier { _, session ->
            runCatching { verify(session.peerCertificates[0] as X509Certificate); true }.getOrDefault(false)
        }
        connection.connectTimeout = 8_000
        connection.readTimeout = if (photo == null) 10_000 else 200_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer ${config.token}")
        if (session.isNotEmpty()) connection.setRequestProperty("X-Session", session)
        try {
            val payload = photo ?: data?.toString()?.toByteArray(Charsets.UTF_8)
            if (payload != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (photo != null) "image/jpeg" else "application/json")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.bufferedReader().readText() } ?: "{}"
            val json = JSONObject(body)
            if (code !in 200..299) throw PcApiException(code, json.optString("error", "Error del servidor ($code)."))
            return json
        } finally { connection.disconnect() }
    }
}
