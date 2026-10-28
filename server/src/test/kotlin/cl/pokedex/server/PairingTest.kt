package cl.pokedex.server

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.imageio.ImageIO

class PairingTest {
    @Test fun generatedQrDecodesExactCredentials() {
        val payload = pairingPayload("https://192.168.1.20:8443", "a".repeat(64), "b".repeat(64))
        val image = ImageIO.read(ByteArrayInputStream(pairingPng(payload)))
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))).text
        assertEquals(payload, decoded)
        assertEquals("pokedex-pairing", JSONObject(decoded).getString("type"))
    }
    @Test fun localPageDoesNotExposeOtherRoutes() {
        val server = pairingPage("https://192.168.1.20:8443", "a".repeat(64), "b".repeat(64), 0)
        server.start()
        try {
            assertTrue(server.address.address.isLoopbackAddress)
            val client = HttpClient.newHttpClient()
            val base = "http://127.0.0.1:${server.address.port}"
            fun get(path: String) = client.send(HttpRequest.newBuilder(URI(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString())
            val response = get("/")
            assertEquals(200, response.statusCode())
            assertEquals("no-store", response.headers().firstValue("Cache-Control").get())
            assertTrue(response.body().contains("data:image/png;base64,"))
            assertEquals(404, get("/unknown").statusCode())
            assertEquals(404, get("/?anything=1").statusCode())
            val post = client.send(HttpRequest.newBuilder(URI(base)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, post.statusCode())
        } finally { server.stop(0) }
    }
}
