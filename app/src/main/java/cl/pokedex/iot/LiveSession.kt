package cl.pokedex.iot

/** Invalida respuestas pendientes al detener o abandonar una sesión de cámara. */
class LiveSession {
    private var generation = 0L
    private var active = false
    fun start() { generation++; active = true }
    fun stop() { generation++; active = false }
    fun ticket() = generation
    fun accepts(ticket: Long) = active && generation == ticket
}
