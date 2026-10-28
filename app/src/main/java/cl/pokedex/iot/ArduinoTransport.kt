package cl.pokedex.iot

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Lo único pendiente de elegir con el hardware: canal autenticado Wi-Fi/BLE/USB y sus permisos. */
interface ArduinoChannel {
    fun open(bytes: (ByteArray) -> Unit, disconnected: () -> Unit)
    fun write(bytes: ByteArray)
    fun close()
}

/** JSON UTF-8 por línea; recompone paquetes fragmentados, incluso caracteres UTF-8 partidos. */
class FramedArduinoTransport(
    private val channel: ArduinoChannel,
    private val dispatch: (() -> Unit) -> Unit
) : DeviceTransport {
    override val simulated = false
    private var generation = 0L
    private var active = false
    private val buffer = ByteArrayOutputStream()
    override fun connect(receive: (String) -> Unit, disconnected: () -> Unit) {
        close(); active = true
        val current = generation
        fun lost() {
            if (active && current == generation) { close(); disconnected() }
        }
        try {
            channel.open({ bytes -> dispatch {
                if (active && current == generation) {
                    try {
                        for (byte in bytes) {
                            if (byte == 10.toByte()) {
                                if (buffer.size() > 0) {
                                    val frame = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                        .decode(ByteBuffer.wrap(buffer.toByteArray())).toString()
                                    buffer.reset(); receive(frame)
                                    if (!active || current != generation) break
                                }
                            } else if (byte != 13.toByte()) {
                                require(buffer.size() < DeviceProtocol.MAX_BYTES)
                                buffer.write(byte.toInt())
                            }
                        }
                    } catch (_: Exception) { lost() }
                }
            } }, { dispatch { lost() } })
        } catch (_: Exception) { dispatch { lost() } }
    }
    override fun send(frame: String) {
        check(active)
        require(!frame.contains('\n') && !frame.contains('\r'))
        DeviceProtocol.read(frame)
        channel.write((frame + "\n").toByteArray(Charsets.UTF_8))
    }
    override fun close() { generation++; active = false; buffer.reset(); channel.close() }
}
