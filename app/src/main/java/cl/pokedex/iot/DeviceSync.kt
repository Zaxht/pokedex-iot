package cl.pokedex.iot

import org.json.JSONObject
import java.util.UUID

/** Estado único de la Pokédex. Los datos ocultos nunca se envían al dispositivo. */
data class DeviceSnapshot(
    val pokemonId: Int? = null, val revealed: Boolean = false,
    val name: String = "", val description: String = "",
    val led: Boolean = false, val sound: Boolean = true, val volume: Int = 70,
    val notifications: Int = 0, val view: String = "collection", val speech: Long = 0, val active: Boolean = false
) {
    init {
        require(pokemonId == null || pokemonId in 1..151)
        require(volume in 0..100 && notifications >= 0 && speech >= 0)
        require(view in setOf("collection", "entry", "scan", "friends", "settings"))
        require(name.length <= 40 && description.length <= 500)
        require(revealed || (name.isEmpty() && description.isEmpty()))
        require(!revealed || pokemonId != null)
        require(speech == 0L || (revealed && sound && active))
    }
    fun json() = JSONObject().put("pokemon", pokemonId ?: JSONObject.NULL).put("revealed", revealed)
        .put("name", name).put("description", description).put("led", led).put("sound", sound)
        .put("volume", volume).put("notifications", notifications).put("view", view).put("speech", speech).put("active", active)
    companion object {
        fun read(o: JSONObject) = DeviceSnapshot(
            if (o.isNull("pokemon")) null else o.getInt("pokemon"), o.getBoolean("revealed"),
            o.getString("name"), o.getString("description"), o.getBoolean("led"), o.getBoolean("sound"),
            o.getInt("volume"), o.getInt("notifications"), o.getString("view"), o.getLong("speech"), o.getBoolean("active"))
    }
}

data class DeviceCapabilities(val display: Boolean, val audio: Boolean, val leds: Boolean, val buttons: Boolean, val knob: Boolean) {
    fun json() = JSONObject().put("display", display).put("audio", audio).put("leds", leds).put("buttons", buttons).put("knob", knob)
    companion object {
        val SIMULATED = DeviceCapabilities(true, true, true, true, true)
        fun read(o: JSONObject) = DeviceCapabilities(o.getBoolean("display"), o.getBoolean("audio"), o.getBoolean("leds"), o.getBoolean("buttons"), o.getBoolean("knob"))
    }
}

enum class DeviceAction { NEXT, PREVIOUS, SCAN, FRIENDS, SPEAK, LED, SOUND, VOLUME }
data class DeviceInput(val action: DeviceAction, val value: Int = 0)
data class DeviceState(
    val simulated: Boolean = true, val connected: Boolean = false,
    val confirmed: DeviceSnapshot? = null, val pending: Boolean = false,
    val capabilities: DeviceCapabilities? = null,
    val message: String = "Sin conexión"
)

/** Un frame JSON por mensaje. El adaptador real debe autenticar el enlace y entregar callbacks en el hilo principal. */
interface DeviceTransport {
    val simulated: Boolean
    fun connect(receive: (String) -> Unit, disconnected: () -> Unit)
    fun send(frame: String)
    fun close()
}

/** No abre una red ni inventa confirmaciones mientras falten el transporte y los módulos reales. */
class UnavailableArduinoTransport : DeviceTransport {
    override val simulated = false
    override fun connect(receive: (String) -> Unit, disconnected: () -> Unit) = disconnected()
    override fun send(frame: String) = Unit
    override fun close() = Unit
}

object DeviceProtocol {
    const val VERSION = 1
    const val MAX_BYTES = 4096
    fun message(type: String) = JSONObject().put("v", VERSION).put("type", type)
    fun read(frame: String): JSONObject {
        require(frame.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return JSONObject(frame).also { require(integer(it, "v") == VERSION.toLong()) }
    }
    fun integer(o: JSONObject, key: String): Long {
        val raw = o.get(key)
        require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toLong().toDouble())
        return raw.toLong()
    }
    fun input(o: JSONObject): DeviceInput {
        val action = DeviceAction.valueOf(o.getString("action"))
        val value = if (action in setOf(DeviceAction.LED, DeviceAction.SOUND, DeviceAction.VOLUME)) {
            val raw = o.get("value")
            require(raw is Number && raw.toDouble() == raw.toInt().toDouble())
            raw.toInt()
        } else 0
        require(when (action) { DeviceAction.LED, DeviceAction.SOUND -> value in 0..1; DeviceAction.VOLUME -> value in 0..100; else -> true })
        return DeviceInput(action, value)
    }
}

/** El simulador usa exactamente los mismos frames y ACK que deberá usar el adaptador físico. */
class SimulatedArduinoTransport : DeviceTransport {
    override val simulated = true
    private var receive: ((String) -> Unit)? = null
    private var disconnected: (() -> Unit)? = null
    private var session = ""
    private var event = 0L
    private var sequence = 0L
    var applied: DeviceSnapshot? = null; private set
    override fun connect(receive: (String) -> Unit, disconnected: () -> Unit) {
        this.receive = receive; this.disconnected = disconnected; session = ""; event = 0; applied = null
        receive(DeviceProtocol.message("hello").put("capabilities", DeviceCapabilities.SIMULATED.json()).toString())
    }
    override fun send(frame: String) {
        val message = DeviceProtocol.read(frame)
        require(message.getString("type") == "state")
        session = message.getString("session")
        sequence = message.getLong("seq")
        applied = DeviceSnapshot.read(message.getJSONObject("state"))
        receive?.invoke(DeviceProtocol.message("ack").put("session", session).put("seq", message.getLong("seq")).toString())
    }
    fun input(input: DeviceInput) {
        if (session.isEmpty() || receive == null) return
        receive?.invoke(DeviceProtocol.message("input").put("session", session).put("seq", sequence).put("event", ++event)
            .put("action", input.action.name).put("value", input.value).toString())
    }
    override fun close() { receive = null; disconnected = null; applied = null; session = "" }
    fun disconnect() { val callback = disconnected; close(); callback?.invoke() }
}

/** Ordena estados, confirma solo el último envío e ignora ACK/eventos de conexiones anteriores. */
class DeviceSynchronizer(
    private val updated: (DeviceState) -> Unit,
    private val input: (DeviceInput) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    var state = DeviceState(); private set
    var desired = DeviceSnapshot(); private set
    private var transport: DeviceTransport? = null
    private var generation = 0L
    private var session = ""
    private var sequence = 0L
    private var lastEvent = 0L
    private var sentAt = 0L
    private var lastAckAt = 0L
    private var sent: DeviceSnapshot? = null
    private fun report(value: DeviceState) { state = value; updated(value) }

    fun connect(next: DeviceTransport) {
        generation++; transport?.close(); transport = next
        session = UUID.randomUUID().toString(); sequence = 0; lastEvent = 0; sent = null
        // Reanudar un enlace no vuelve a reproducir una narración anterior.
        desired = desired.copy(speech = 0)
        report(DeviceState(simulated = next.simulated, message = "Conectando…"))
        sentAt = now()
        val current = generation
        next.connect({ if (current == generation) receive(it) }, {
            if (current == generation) disconnect("${if (next.simulated) "Simulador desconectado" else "Arduino real sin conexión"}")
        })
    }
    fun set(value: DeviceSnapshot) {
        if (desired == value) return
        desired = value
        if (state.connected) send()
    }
    private fun send() {
        val frame = DeviceProtocol.message("state").put("session", session).put("seq", ++sequence).put("state", desired.json()).toString()
        sent = desired
        // El plazo comienza en el primer cambio pendiente: mover una perilla no lo prolonga indefinidamente.
        if (!state.pending) sentAt = now()
        report(state.copy(pending = true, message = "Esperando confirmación…"))
        try { transport?.send(frame) } catch (_: Exception) { disconnect("No se pudo enviar al dispositivo.") }
    }
    private fun receive(frame: String) {
        try {
            val o = DeviceProtocol.read(frame)
            when (o.getString("type")) {
                "hello" -> {
                    if (state.connected) return
                    val caps = DeviceCapabilities.read(o.getJSONObject("capabilities"))
                    report(state.copy(connected = true, capabilities = caps)); send()
                }
                "ack" -> if (state.connected && o.getString("session") == session && DeviceProtocol.integer(o, "seq") == sequence) {
                    lastAckAt = now()
                    report(state.copy(confirmed = sent, pending = false, message = if (state.simulated) "Simulador sincronizado" else "Arduino sincronizado"))
                }
                "input" -> {
                    if (!state.connected || state.pending || o.getString("session") != session || DeviceProtocol.integer(o, "seq") != sequence) return
                    val event = DeviceProtocol.integer(o, "event")
                    if (event <= lastEvent) return
                    val action = DeviceProtocol.input(o)
                    val caps = state.capabilities ?: return
                    if (action.action == DeviceAction.VOLUME && !caps.knob) return
                    if (action.action != DeviceAction.VOLUME && !caps.buttons) return
                    lastEvent = event
                    if (desired.active) input(action)
                }
            }
        } catch (_: Exception) { /* Frame inválido: no modifica estado ni ejecuta acciones. */ }
    }
    fun checkTimeout() {
        if ((state.pending || state.message == "Conectando…") && now() - sentAt >= 5000) disconnect("Sin confirmación del dispositivo. Reconecta para sincronizar.")
        else if (state.connected && !state.pending && now() - lastAckAt >= 2000) send()
    }
    private fun disconnect(message: String) {
        generation++; transport?.close(); sent = null
        report(DeviceState(simulated = state.simulated, message = message))
    }
    fun close() { disconnect("Sin conexión") }
}
