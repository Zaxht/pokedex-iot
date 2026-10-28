package cl.pokedex.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import javax.imageio.ImageIO

fun pairingPayload(address: String, fingerprint: String, token: String): String =
    JSONObject().put("type", "pokedex-pairing").put("version", 1).put("address", address)
        .put("fingerprint", fingerprint).put("token", token).toString()

fun pairingPng(payload: String): ByteArray {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 600, 600)
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width)
        image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xffffff)
    return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
}

/** El QR contiene credenciales: esta página solo escucha en el propio PC. */
fun pairingPage(address: String, fingerprint: String, token: String, port: Int = 8444): HttpServer {
    val png = Base64.getEncoder().encodeToString(pairingPng(pairingPayload(address, fingerprint, token)))
    val html = """<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <title>Conectar Pokédex</title><style>body{font:18px system-ui;background:#f7f5ee;color:#203d36;text-align:center;margin:24px auto;max-width:720px;padding:16px}img{width:600px;max-width:100%;height:auto}h1{color:#b72e43}</style>
        <h1>Conecta tu Pokédex</h1><p>En el celular: <b>Ajustes → Conexión al PC → Leer QR del PC</b>.<br>Permite la cámara y apunta al código. Se leerá automáticamente.</p>
        <img alt="QR privado de conexión al PC" src="data:image/png;base64,$png">
        <p>Después pulsa <b>Comprobar conexión</b> en la app.<br>Celular y PC deben estar en la misma red Wi-Fi.</p>
        <p>La app recordará la conexión. Si cambia la IP del PC, vuelve a escanear.<br>Este QR contiene la clave de acceso: no lo compartas públicamente.</p></html>""".trimIndent().toByteArray(Charsets.UTF_8)
    return HttpServer.create(InetSocketAddress("127.0.0.1", port), 4).apply {
        createContext("/") { exchange ->
            try {
                val host = exchange.requestHeaders.getFirst("Host")
                val allowed = exchange.remoteAddress.address.isLoopbackAddress &&
                    host in setOf("127.0.0.1:${this.address.port}", "localhost:${this.address.port}")
                val status = when {
                    !allowed -> 403
                    exchange.requestMethod != "GET" -> 405
                    exchange.requestURI.toString() != "/" -> 404
                    else -> 200
                }
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.responseHeaders.set("X-Pokedex-Server", "1")
                exchange.responseHeaders.set("X-Pokedex-Address", address)
                exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-ancestors 'none'")
                exchange.responseHeaders.set("X-Frame-Options", "DENY")
                exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
                val body = if (status == 200) html else "No disponible".toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
    }
}
