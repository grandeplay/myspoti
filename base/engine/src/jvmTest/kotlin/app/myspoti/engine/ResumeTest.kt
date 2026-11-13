package app.myspoti.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A resume on the engine as the Web Player sends it: on a device that holds nothing (a new engine
 * after a relaunch, one whose dealer closed) the account's paused session is taken first, then
 * resumed (the captures' transfer -> paused load -> resume). A bare resume there was a command to a
 * device with no session: the Play button did nothing.
 */
class ResumeTest {
    /** The commands sent, in order: a transfer, or a player command's endpoint. */
    private fun Rig.commands() = http.requests.mapNotNull { r ->
        when {
            "/connect-state/v1/connect/transfer/" in r.url -> "transfer"
            "/connect-state/v1/player/command/" in r.url -> http.json(r)["command"]!!.jsonObject["endpoint"]!!.jsonPrimitive.content
            else -> null
        }
    }

    @Test
    fun an_engine_holding_nothing_takes_the_session_then_resumes() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val resumed = launch { rig.engine.resumeHere(loadWaitMs = 6_000) }
        runCurrent()
        // Nothing resumed before the session is here.
        assertEquals(listOf("transfer"), rig.commands())

        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 60_271))
        resumed.join()
        assertEquals(listOf("transfer", "resume"), rig.commands())
        assertEquals("load ${rig.trackUriOf(2)} @60271 play=false", rig.player.calls.first())
    }

    @Test
    fun an_engine_holding_a_song_resumes_it_as_before() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 0))

        rig.engine.resumeHere(loadWaitMs = 6_000)
        assertEquals(listOf("resume"), rig.commands())
    }

    @Test
    fun no_session_to_take_is_a_failure_not_a_resume_into_nothing() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()

        val failure = runCatching { rig.engine.resumeHere(loadWaitMs = 6_000) }.exceptionOrNull()
        assertTrue(failure is EngineException.Protocol, "failure: $failure")
        assertEquals(listOf("transfer"), rig.commands())
    }
}
