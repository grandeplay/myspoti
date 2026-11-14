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

    /** A cluster push: the account's playback as connect-state holds it, [positionMs] of [uri] as of [timestampMs]. */
    private fun cluster(uri: String, positionMs: Long, timestampMs: Long, paused: Boolean) =
        """{"type":"message","uri":"hm://connect-state/v1/cluster","payloads":[{"cluster":{"timestamp":"$timestampMs",
            "player_state":{"timestamp":"$timestampMs","track":{"uri":"$uri"},"position_as_of_timestamp":"$positionMs",
              "duration":"254091","is_playing":true,"is_paused":$paused},
            "devices":{"web1":{"name":"Web Player (Chrome)","device_type":"COMPUTER"}}}}]}"""

    private fun Rig.seekValue() = http.requests.single { "/connect-state/v1/player/command/" in it.url && "seek_to" in it.body!!.decodeToString() }
        .let { http.json(it)["command"]!!.jsonObject["value"]!!.jsonPrimitive.content.toDouble().toLong() }

    @Test
    fun a_position_the_phone_saved_after_the_accounts_last_one_is_where_it_resumes() = runTest {
        // Closed mid-song (stopped, killed): the account last heard 0:30 (its 30 s report), the
        // phone saved 2:00 since. The resume went back to 0:30.
        val rig = Rig(backgroundScope)
        rig.start()
        val heard = rig.env.now - 170_000
        rig.engine.handleDealerFrame(cluster(rig.trackUriOf(2), 30_000, heard, paused = false))
        val saved = NativeEngine.Saved(rig.trackUriOf(2), 120_000, heard + 90_000)

        val resumed = launch { rig.engine.resumeHere(loadWaitMs = 6_000, saved) }
        runCurrent()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 30_000))
        resumed.join()
        // Sought while still paused: the song does not play a moment at 0:30 first.
        assertEquals(listOf("transfer", "seek_to", "resume"), rig.commands())
        assertEquals(120_000L, rig.seekValue())
    }

    @Test
    fun the_accounts_position_heard_after_the_phones_save_is_kept() = runTest {
        // Played on another device since: the account's position is the newer one.
        val rig = Rig(backgroundScope)
        rig.start()
        val heard = rig.env.now - 10_000
        rig.engine.handleDealerFrame(cluster(rig.trackUriOf(2), 140_000, heard, paused = true))
        val saved = NativeEngine.Saved(rig.trackUriOf(2), 120_000, heard - 60_000)

        val resumed = launch { rig.engine.resumeHere(loadWaitMs = 6_000, saved) }
        runCurrent()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 140_000))
        resumed.join()
        assertEquals(listOf("transfer", "resume"), rig.commands())
    }

    @Test
    fun a_saved_position_of_another_song_is_not_sought() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val heard = rig.env.now - 170_000
        rig.engine.handleDealerFrame(cluster(rig.trackUriOf(2), 30_000, heard, paused = false))
        val saved = NativeEngine.Saved("spotify:track:another", 120_000, heard + 90_000)

        val resumed = launch { rig.engine.resumeHere(loadWaitMs = 6_000, saved) }
        runCurrent()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 30_000))
        resumed.join()
        assertEquals(listOf("transfer", "resume"), rig.commands())
    }

    @Test
    fun the_saved_position_wins_only_when_it_is_newer_and_somewhere_else() {
        val uri = "spotify:track:t"
        val account = Cluster(null, emptyList(), uri, null, paused = false, positionMs = 78_456, timestampMs = 1_000_000, durationMs = 254_091)
        fun loaded(at: Long) = NativeEngine.Now(uri, "", "", "", null, 254_091, playing = false, positionMs = at, atMs = 0)
        fun saved(at: Long, stamp: Long = 1_100_000) = NativeEngine.Saved(uri, at, stamp)

        assertEquals(181_679L, NativeEngine.resumeFrom(saved(181_679), account, loaded(78_456)))
        // Nothing to go by: the account's state, as before.
        assertEquals(null, NativeEngine.resumeFrom(null, account, loaded(78_456)))
        assertEquals(null, NativeEngine.resumeFrom(saved(181_679), null, loaded(78_456)))
        assertEquals(null, NativeEngine.resumeFrom(saved(181_679), account, null))
        // Saved about when the account heard it (two clocks): the account's.
        assertEquals(null, NativeEngine.resumeFrom(saved(181_679, stamp = 1_001_500), account, loaded(78_456)))
        // A few seconds off: not worth a seek.
        assertEquals(null, NativeEngine.resumeFrom(saved(80_000), account, loaded(78_456)))
        // Past the song's end: nothing left of it to resume.
        assertEquals(null, NativeEngine.resumeFrom(saved(254_091), account, loaded(78_456)))
    }
}
