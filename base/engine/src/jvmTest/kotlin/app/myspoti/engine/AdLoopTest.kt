package app.myspoti.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An ad Spotify sends back after the skip that passed it. Once an ad was left out, Spotify points
 * `skip_next` at the ad as well (docs/api/ADS.md): each skip brought the same ad back, and the
 * engine skipped it again, about three times a second, until Spotify refused everything of the
 * account for a minute (429 "Too many requests for user"): next and play did nothing.
 */
class AdLoopTest {
    private fun Rig.adFrame(machineId: String) =
        replaceState(2, paused = false, seekTo = 0, machineId = machineId).replace("\"content_type\":\"TRACK\"", "\"content_type\":\"AD\"")

    private fun Rig.skips() = http.requests.count { "/connect-state/v1/player/command/" in it.url && "skip_next" in it.body!!.decodeToString() }

    @Test
    fun an_ad_sent_back_after_its_skip_is_not_skipped_again() = runTest {
        val rig = Rig(backgroundScope)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        rig.start()

        rig.engine.handleDealerFrame(rig.adFrame("ChTad1"))
        runCurrent()
        assertEquals(1, rig.skips())

        // Spotify's answer to the skip: the same ad again.
        rig.engine.handleDealerFrame(rig.adFrame("ChTad2"))
        runCurrent()
        rig.engine.handleDealerFrame(rig.adFrame("ChTad3"))
        runCurrent()
        assertEquals(1, rig.skips())
        // Each ad sent back is a line in the log, not a request.
        val failures = events.filterIsInstance<EngineEvent.Failure>()
        assertEquals(2, failures.size, "events: $events")
        // Not a player error: the app sets a song aside on those.
        assertTrue(failures.none { it.message.startsWith("player:") }, "failures: $failures")
    }

    @Test
    fun the_next_ad_after_a_song_is_skipped_again() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.adFrame("ChTad1"))
        runCurrent()

        // The skip worked: the next song came, and plays.
        val next = rig.advanceOf(2)
        rig.engine.handleDealerFrame(rig.replaceState(next, paused = false, seekTo = 0, machineId = "ChTsong"))
        runCurrent()
        assertEquals("load ${rig.trackUriOf(next)} @0 play=true", rig.player.calls.last())

        // A later ad is passed as the first one was.
        rig.engine.handleDealerFrame(rig.adFrame("ChTad2"))
        runCurrent()
        assertEquals(2, rig.skips())
    }
}
