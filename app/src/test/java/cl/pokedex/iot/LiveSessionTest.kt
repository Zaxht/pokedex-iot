package cl.pokedex.iot

import org.junit.Assert.*
import org.junit.Test

class LiveSessionTest {
    @Test fun stoppingDiscardsLateRecognition() {
        val session = LiveSession()
        session.start()
        val pending = session.ticket()
        assertTrue(session.accepts(pending))
        session.stop()
        assertFalse(session.accepts(pending))
    }
    @Test fun restartNeverAcceptsPreviousSessionResult() {
        val session = LiveSession()
        session.start()
        val old = session.ticket()
        session.stop(); session.start()
        assertFalse(session.accepts(old))
        assertTrue(session.accepts(session.ticket()))
    }
}
