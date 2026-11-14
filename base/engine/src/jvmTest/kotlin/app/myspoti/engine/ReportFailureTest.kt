package app.myspoti.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A state report the network drops while a song plays. On a locked phone the QUIC connection goes
 * idle between two reports, and the next PUT fails at once (`played_threshold_reached`, 30 s after
 * `started_playing`: ERR_CONNECTION_CLOSED, QUIC error 25). The failure left the loop that takes the
 * player's events uncaught, and Android killed the app in the middle of the song.
 */
class ReportFailureTest {
    /** The player's events as the player sends them: through its listener, to the engine's own loop. */
    private fun TestScope.playerSays(rig: Rig, e: PlayerEvent) { rig.player.onEvent!!(e); runCurrent() }

    /** The player plays from [from] to [to] in 500 ms steps, through the listener. */
    private fun TestScope.playsFor(rig: Rig, from: Long, to: Long) {
        var pos = from
        while (pos < to) {
            pos = minOf(pos + 500, to)
            rig.env.now += 500
            playerSays(rig, PlayerEvent.Position(pos))
        }
    }

    private suspend fun TestScope.playing(rig: Rig) {
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        playerSays(rig, PlayerEvent.Loaded(153_889))
        playerSays(rig, PlayerEvent.Playing(0))
    }

    @Test
    fun a_report_the_network_dropped_is_sent_again() = runTest {
        val rig = Rig(backgroundScope)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        playing(rig)
        rig.http.broken["played_threshold_reached"] = 1

        playsFor(rig, 0, 30_000)
        // The second try goes out on a new connection: Spotify still counts the play.
        assertEquals(listOf("before_track_load", "started_playing", "played_threshold_reached", "played_threshold_reached"), rig.http.debugSources())
        assertTrue(events.none { it is EngineEvent.Failure }, "events: $events")
    }

    @Test
    fun a_report_that_cannot_be_sent_does_not_stop_the_song() = runTest {
        val rig = Rig(backgroundScope)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        playing(rig)
        rig.http.broken["played_threshold_reached"] = 2

        playsFor(rig, 0, 30_000)
        val failures = events.filterIsInstance<EngineEvent.Failure>()
        assertEquals(1, failures.size, "events: $events")
        // Not a player error: the app sets a song aside on those.
        assertTrue(!failures[0].message.startsWith("player:"), "failure: ${failures[0]}")

        // The player's next events are still taken: the song plays on to its end and the next one.
        playsFor(rig, 30_000, 153_500)
        playerSays(rig, PlayerEvent.Ended(153_889))
        assertEquals(listOf("track_data_finalized", "before_track_load"), rig.http.debugSources().takeLast(2))
        assertEquals("load ${rig.trackUriOf(rig.advanceOf(2))} @0 play=true", rig.player.calls.last { it.startsWith("load") })
    }
}
