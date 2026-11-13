package app.myspoti.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether the dealer still answers before an engine kept across a long pause is used again: the
 * server answers each `{"type":"ping"}` with a pong (SESSION.md, dealer), and a socket that died
 * while the app was frozen or the phone asleep was noticed by nothing (no protocol pings, no read
 * timeout), so commands went to a device the server no longer pushed to.
 */
class DealerLivenessTest {
    private val ping = """{"type":"ping"}"""
    private val hour = 3_600_000L

    @Test
    fun a_dealer_heard_lately_answers_without_a_ping() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.env.now += 10_000
        assertTrue(rig.engine.dealerAnswers(freshMs = 45_000, waitMs = 2_000))
        assertFalse(ping in rig.sockets.socket.sent)
    }

    @Test
    fun a_dealer_quiet_for_an_hour_is_pinged_and_its_pong_is_an_answer() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.env.now += hour
        val answered = async { rig.engine.dealerAnswers(freshMs = 45_000, waitMs = 2_000) }
        runCurrent()
        assertEquals(ping, rig.sockets.socket.sent.last())

        rig.sockets.socket.incoming.trySend("""{"type":"pong"}""")
        assertTrue(answered.await())
        assertEquals(0, rig.engine.quietMs())
    }

    @Test
    fun a_dealer_that_does_not_answer_its_ping_is_dead() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.env.now += hour
        assertFalse(rig.engine.dealerAnswers(freshMs = 45_000, waitMs = 2_000))
    }
}
