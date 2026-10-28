package cl.pokedex.iot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceStabilityTest {
    @Test fun oneThousandBidirectionalChangesConvergeWithoutPendingState() {
        val transport = SimulatedArduinoTransport()
        lateinit var sync: DeviceSynchronizer
        var received = 0
        sync = DeviceSynchronizer({}, { event ->
            received++
            assertEquals(DeviceAction.VOLUME, event.action)
            sync.set(sync.desired.copy(volume = event.value))
        })
        sync.connect(transport); sync.set(sync.desired.copy(active = true))
        val started = System.nanoTime()
        repeat(1000) { index ->
            sync.set(sync.desired.copy(pokemonId = index % 151 + 1, led = index % 2 == 0, notifications = index % 4))
            transport.input(DeviceInput(DeviceAction.VOLUME, index % 101))
            assertEquals(sync.desired, sync.state.confirmed)
            assertEquals(sync.desired, transport.applied)
            assertFalse(sync.state.pending)
        }
        assertEquals(1000, received)
        println("ACCEPTANCE_METRIC " + JSONObject().put("scenario", "virtual_device_roundtrips").put("cycles", 1000)
            .put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0).put("unconfirmed", 0))
        sync.close()
    }

    @Test fun oneHundredReconnectsKeepLatestStateAndDoNotReplaySpeech() {
        val transport = SimulatedArduinoTransport(); val sync = DeviceSynchronizer({}, {})
        sync.connect(transport)
        repeat(100) { index ->
            sync.set(DeviceSnapshot(25, true, "Pikachu", "Tipo Eléctrico", volume = index, speech = index + 1L, active = true))
            transport.disconnect(); assertFalse(sync.state.connected); assertNull(sync.state.confirmed)
            sync.set(sync.desired.copy(notifications = index))
            sync.connect(transport)
            assertEquals(index, sync.state.confirmed!!.volume)
            assertEquals(index, sync.state.confirmed!!.notifications)
            assertEquals(0L, sync.state.confirmed!!.speech)
            assertFalse(sync.state.pending)
        }
        sync.close()
    }
}
