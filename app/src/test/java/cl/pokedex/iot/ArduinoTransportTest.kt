package cl.pokedex.iot

import org.junit.Assert.*
import org.junit.Test

class ArduinoTransportTest {
    private class Channel : ArduinoChannel {
        lateinit var read: (ByteArray) -> Unit
        val writes = mutableListOf<ByteArray>()
        override fun open(bytes: (ByteArray) -> Unit, disconnected: () -> Unit) { read = bytes }
        override fun write(bytes: ByteArray) { writes += bytes }
        override fun close() = Unit
    }
    @Test fun fragmentedUtf8AndMultipleFramesArriveIntact() {
        val channel = Channel(); val frames = mutableListOf<String>(); val transport = FramedArduinoTransport(channel) { it() }
        transport.connect(frames::add) { fail("Disconnected") }
        val first = "{\"v\":1,\"text\":\"Eléctrico\"}"
        "$first\r\n{}\n".toByteArray().forEach { channel.read(byteArrayOf(it)) }
        assertEquals(listOf(first,"{}"),frames)
        transport.send(DeviceProtocol.message("hello").toString())
        assertEquals(10.toByte(),channel.writes.single().last())
    }
    @Test fun oversizedOrInvalidUtf8FramesDisconnectWithoutDelivery() {
        for (bytes in listOf(ByteArray(4097) { 65 }, byteArrayOf(0xC3.toByte(),10))) {
            val channel = Channel(); var lost = 0; val frames = mutableListOf<String>()
            val transport = FramedArduinoTransport(channel) { it() }
            transport.connect(frames::add) { lost++ }
            channel.read(bytes)
            assertEquals(1,lost); assertTrue(frames.isEmpty())
        }
    }
    @Test fun oldConnectionCannotInjectDataAfterReconnect() {
        val channel = Channel(); val frames = mutableListOf<String>(); val transport = FramedArduinoTransport(channel) { it() }
        transport.connect(frames::add) { fail("Disconnected") }
        val old = channel.read
        channel.read("partial".toByteArray())
        transport.connect(frames::add) { fail("Disconnected") }
        old("old\n".toByteArray()); channel.read("new\n".toByteArray())
        assertEquals(listOf("new"),frames)
    }
}
