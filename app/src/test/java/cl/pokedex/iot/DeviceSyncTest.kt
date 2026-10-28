package cl.pokedex.iot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceSyncTest {
    private class Wire : DeviceTransport {
        override val simulated = false
        lateinit var receive: (String) -> Unit
        lateinit var disconnected: () -> Unit
        val sent = mutableListOf<JSONObject>()
        override fun connect(receive: (String) -> Unit, disconnected: () -> Unit) { this.receive = receive; this.disconnected = disconnected }
        override fun send(frame: String) { sent += DeviceProtocol.read(frame) }
        override fun close() = Unit
        fun hello(caps: DeviceCapabilities = DeviceCapabilities.SIMULATED) = receive(DeviceProtocol.message("hello").put("capabilities", caps.json()).toString())
        fun ack(frame: JSONObject = sent.last()) = receive(DeviceProtocol.message("ack").put("session", frame.getString("session")).put("seq", frame.getLong("seq")).toString())
        fun event(number: Long, action: String, value: Any = 0, frame: JSONObject = sent.last()) = receive(DeviceProtocol.message("input")
            .put("session", frame.getString("session")).put("seq", frame.getLong("seq")).put("event", number).put("action", action).put("value", value).toString())
    }

    @Test fun appStateIsAppliedAndAcknowledgedBySimulator() {
        val simulator = SimulatedArduinoTransport(); val sync = DeviceSynchronizer({}, {})
        sync.connect(simulator)
        val snapshot = DeviceSnapshot(25, true, "Pikachu", "Tipo Eléctrico", led = true, volume = 35, notifications = 2, view = "entry", active = true)
        sync.set(snapshot)
        assertEquals(snapshot, simulator.applied)
        assertEquals(snapshot, sync.state.confirmed)
        assertFalse(sync.state.pending)
    }
    @Test fun physicalInputsReturnThroughSameProtocolAndUpdateBothStates() {
        val simulator = SimulatedArduinoTransport()
        lateinit var sync: DeviceSynchronizer
        sync = DeviceSynchronizer({}, { event ->
            when (event.action) {
                DeviceAction.VOLUME -> sync.set(sync.desired.copy(volume = event.value))
                DeviceAction.LED -> sync.set(sync.desired.copy(led = event.value == 1))
                else -> fail("Unexpected event")
            }
        })
        sync.connect(simulator); sync.set(sync.desired.copy(active = true))
        simulator.input(DeviceInput(DeviceAction.VOLUME, 23)); simulator.input(DeviceInput(DeviceAction.LED, 1))
        assertEquals(23, sync.desired.volume); assertTrue(sync.desired.led)
        assertEquals(sync.desired, simulator.applied); assertEquals(sync.desired, sync.state.confirmed)
    }
    @Test fun requestsCanTurnNotificationLedOnAndOff() {
        val sync = DeviceSynchronizer({}, {}); sync.connect(SimulatedArduinoTransport())
        sync.set(sync.desired.copy(notifications = 2)); assertEquals(2, sync.state.confirmed!!.notifications)
        sync.set(sync.desired.copy(notifications = 1)); assertEquals(1, sync.state.confirmed!!.notifications)
        sync.set(sync.desired.copy(notifications = 0)); assertEquals(0, sync.state.confirmed!!.notifications)
    }
    @Test fun realModeNeverInventsAnAppliedState() {
        val sync = DeviceSynchronizer({}, {})
        sync.connect(SimulatedArduinoTransport()); sync.set(sync.desired.copy(pokemonId = 4, led = true))
        sync.connect(UnavailableArduinoTransport())
        assertFalse(sync.state.simulated); assertFalse(sync.state.connected); assertNull(sync.state.confirmed)
        sync.set(sync.desired.copy(volume = 12))
        assertEquals(4, sync.desired.pokemonId); assertNull(sync.state.confirmed)
    }
    @Test fun reconnectRestoresLatestStateWithoutReplayingSpeech() {
        val sim = SimulatedArduinoTransport(); val sync = DeviceSynchronizer({}, {})
        sync.connect(sim); sync.set(sync.desired.copy(pokemonId = 25, revealed = true, name = "Pikachu", volume = 42, speech = 7, active = true))
        sim.disconnect(); assertNull(sync.state.confirmed)
        sync.set(sync.desired.copy(notifications = 3))
        sync.connect(sim)
        assertEquals(25, sync.state.confirmed!!.pokemonId); assertEquals(42, sync.state.confirmed!!.volume)
        assertEquals(3, sync.state.confirmed!!.notifications); assertEquals(0L, sync.state.confirmed!!.speech)
    }
    @Test fun delayedAcknowledgementCannotConfirmANewerState() {
        val wire = Wire(); val sync = DeviceSynchronizer({}, {})
        sync.connect(wire); wire.hello(); wire.ack()
        sync.set(sync.desired.copy(led = true)); val older = wire.sent.last()
        sync.set(sync.desired.copy(volume = 10)); wire.ack(older)
        assertTrue(sync.state.pending); assertEquals(70, sync.state.confirmed!!.volume)
        wire.ack(); assertFalse(sync.state.pending); assertEquals(10, sync.state.confirmed!!.volume)
    }
    @Test fun duplicateOldAndBackgroundButtonEventsAreIgnored() {
        val events = mutableListOf<DeviceInput>(); val wire = Wire(); val sync = DeviceSynchronizer({}, events::add)
        sync.connect(wire); wire.hello(); wire.ack()
        wire.event(1, "NEXT"); assertTrue(events.isEmpty())
        val inactive = wire.sent.last()
        sync.set(sync.desired.copy(active = true)); wire.ack()
        wire.event(2, "NEXT", frame = inactive); assertTrue(events.isEmpty())
        wire.event(2, "NEXT"); wire.event(2, "NEXT"); wire.event(1, "PREVIOUS")
        assertEquals(listOf(DeviceInput(DeviceAction.NEXT)), events)
    }
    @Test fun callbacksFromReplacedTransportCannotControlNewSession() {
        val old = Wire(); val next = Wire(); val events = mutableListOf<DeviceInput>(); val sync = DeviceSynchronizer({}, events::add)
        sync.connect(old); old.hello(); sync.set(sync.desired.copy(active = true)); old.ack()
        sync.connect(next); next.hello(); next.ack()
        old.event(1, "SCAN"); old.disconnected(); old.ack()
        assertTrue(events.isEmpty()); assertTrue(sync.state.connected)
        next.event(1, "SCAN"); assertEquals(1, events.size)
    }
    @Test fun malformedUnknownAndUnsupportedInputsCannotChangeState() {
        val events = mutableListOf<DeviceInput>(); val wire = Wire(); val sync = DeviceSynchronizer({}, events::add)
        sync.connect(wire); wire.hello(DeviceCapabilities(true, false, true, true, false))
        sync.set(sync.desired.copy(active = true)); wire.ack()
        wire.receive("x".repeat(5000)); wire.receive("{}"); wire.receive("{\"v\":99,\"type\":\"hello\"}")
        wire.event(1, "DELETE_ACCOUNT"); wire.event(2, "LED", 2); wire.event(3, "SOUND", "1")
        wire.event(4, "VOLUME", 0.5); wire.event(5, "VOLUME", 42)
        assertTrue(events.isEmpty()); assertTrue(sync.state.connected)
        wire.event(6, "FRIENDS"); assertEquals(DeviceAction.FRIENDS, events.single().action)
    }
    @Test fun timeoutClearsConfirmationAndContinuousUpdatesCannotDelayIt() {
        var time = 0L; val wire = Wire(); val sync = DeviceSynchronizer({}, {}, { time })
        sync.connect(wire); wire.hello(); wire.ack()
        sync.set(sync.desired.copy(led = true))
        time = 4000; sync.set(sync.desired.copy(volume = 3))
        time = 5001; sync.checkTimeout()
        assertFalse(sync.state.connected); assertNull(sync.state.confirmed)
        wire.ack(); assertNull(sync.state.confirmed)
    }
    @Test fun missingHandshakeTimesOut() {
        var time = 0L; val sync = DeviceSynchronizer({}, {}, { time })
        sync.connect(Wire()); time = 5000; sync.checkTimeout()
        assertFalse(sync.state.connected); assertTrue(sync.state.message.startsWith("Sin confirmación"))
    }
    @Test fun idleConnectionIsCheckedWithoutReplayingOrLosingState() {
        var time = 0L; val wire = Wire(); val sync = DeviceSynchronizer({}, {}, { time })
        sync.connect(wire); wire.hello(); wire.ack()
        time = 2000; sync.checkTimeout()
        assertEquals(2,wire.sent.size); assertTrue(sync.state.pending)
        assertEquals(0L,wire.sent.last().getJSONObject("state").getLong("speech"))
        time = 7000; sync.checkTimeout()
        assertFalse(sync.state.connected); assertNull(sync.state.confirmed)
    }
    @Test fun hiddenSpeciesDoNotLeakLabelsAndFramesRoundTrip() {
        val hidden = DeviceSnapshot(pokemonId = 150)
        assertEquals(hidden, DeviceSnapshot.read(hidden.json()))
        try { hidden.copy(name = "Mewtwo"); fail("Unknown species label leaked") } catch (_: IllegalArgumentException) { }
        try { hidden.copy(volume = 101); fail("Invalid knob") } catch (_: IllegalArgumentException) { }
    }
}
