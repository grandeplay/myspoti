package app.myspoti.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An ad the engine plays as Spotify sends it, as the Web Player did on 2026-11-16 (free account,
 * the page as the player, an ad of 23 s played to its end): the same reports as a song, with the
 * MP3's values, and on to the song its state advances to. Skipping it (the engine's way until now)
 * looped or stopped the music once Spotify refused the skip.
 */
class AdPlaybackTest {
    private val adUrl = "https://adstudio-assets.scdn.co/mp3/0123456789abcdef.mp3"
    private val impression = "https://aet.spotify.com/v2/t?p=test"

    /** The fixture machine at state 2 on an ad track (as captured: MP3 at 160 kbit/s, 23 s, a 1 s threshold); its advance, state 3, a song. */
    private fun Rig.adPlaying(paused: Boolean = false): String {
        val frame = Json.parseToJsonElement(replaceState(2, paused = paused, seekTo = 0)).jsonObject
        val payload = frame["payloads"]!!.jsonArray[0].jsonObject
        val sm = payload["state_machine"]!!.jsonObject
        val tracks = sm["tracks"]!!.jsonArray
        val base = tracks[1].jsonObject
        val ad = JsonObject(base + mapOf(
            "content_type" to JsonPrimitive("AD"),
            "ms_played_until_update" to JsonPrimitive(1000),
            "metadata" to JsonObject(base["metadata"]!!.jsonObject + mapOf("uri" to JsonPrimitive("spotify:ad:test"), "duration" to JsonPrimitive(23000))),
            "manifest" to buildJsonObject {
                put("file_urls_mp3", buildJsonArray {
                    add(buildJsonObject {
                        put("bitrate", 160000); put("file_url", adUrl); put("track_type", "AUDIO")
                        put("impression_urls", buildJsonArray { add(JsonPrimitive(impression)) })
                    })
                })
            },
        ))
        val states = sm["states"]!!.jsonArray.mapIndexed { i, s -> if (i == 2) JsonObject(s.jsonObject + ("track" to JsonPrimitive(tracks.size))) else s }
        val newSm = JsonObject(sm + mapOf("tracks" to JsonArray(tracks + ad), "states" to JsonArray(states)))
        http.machine = newSm
        return JsonObject(frame + ("payloads" to JsonArray(listOf(JsonObject(payload + ("state_machine" to newSm)))))).toString()
    }

    private fun Rig.melody(type: String): List<JsonObject> = http.requests.filter { "/melody/v1/msg/batch" in it.url }
        .flatMap { http.json(it)["messages"]!!.jsonArray.map { m -> m.jsonObject } }
        .filter { it["type"]!!.jsonPrimitive.content == type }.map { it["message"]!!.jsonObject }

    private fun Rig.put(source: String) = http.statePuts().last { it["debug_source"]!!.jsonPrimitive.content == source }

    private fun JsonObject.str(vararg path: String): String? {
        var e: kotlinx.serialization.json.JsonElement = this
        path.forEach { e = (e as? JsonObject)?.get(it) ?: return null }
        return (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }

    private suspend fun TestScope.adToItsEnd(rig: Rig) {
        rig.engine.handleDealerFrame(rig.adPlaying())
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(23_067))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 23_000)
        runCurrent()
    }

    @Test
    fun an_ad_is_loaded_from_its_mp3_and_announced_as_the_web_player_does() = runTest {
        val rig = Rig(backgroundScope)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        rig.start()
        rig.engine.handleDealerFrame(rig.adPlaying())
        runCurrent()

        assertEquals(listOf("load plain spotify:ad:test $adUrl play=true"), rig.player.calls)
        assertTrue(events.any { it is EngineEvent.AdBreak })
        val btl = rig.put("before_track_load")
        assertEquals("file_urls_external", btl.str("sub_state", "format"))
        assertEquals("160000", btl.str("sub_state", "bitrate"))
        assertEquals(null, btl.str("sub_state", "audio_quality"))
        assertEquals("23000", btl.str("sub_state", "duration"))
        assertEquals("0", btl.str("sub_state", "playback_speed"))
        val start = rig.melody("jssdk_playback_start").single()
        assertEquals("spotify:ad:test", start.str("play_track"))
        assertEquals("", start.str("file_id"))
        // Nothing passes it: the ad is played, not skipped.
        assertTrue(rig.http.requests.none { "/connect-state/v1/player/command/" in it.url })
    }

    @Test
    fun a_playing_ad_is_reported_started_once_its_audio_plays_its_impression_called_and_no_threshold_sent() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.adPlaying())
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(23_067))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 5_000)
        runCurrent()

        assertEquals(listOf("before_track_load", "started_playing"), rig.http.debugSources())
        assertEquals("23067", rig.put("started_playing").str("sub_state", "duration"))
        // The impression address of the ad, called once (the Web Player's GET aet.spotify.com/v2/t).
        assertEquals(1, rig.http.requests.count { it.method == "GET" && it.url == impression })
    }

    @Test
    fun an_ad_played_to_its_end_is_finalized_with_its_mp3_values_and_the_next_song_follows() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        adToItsEnd(rig)
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(23_067))
        runCurrent()

        assertEquals(listOf("before_track_load", "started_playing", "track_data_finalized", "before_track_load"), rig.http.debugSources())
        // At the ad's end, stats only: no stream verification.
        assertTrue(rig.melody("track_stream_verification").isEmpty())
        val stats = rig.melody("jssdk_playback_stats").single()
        assertEquals("spotify:ad:test", stats.str("play_track"))
        assertEquals("", stats.str("file_id"))
        assertEquals("", stats.str("manifest_id"))
        assertEquals("mp3", stats.str("audio_format"))
        assertEquals("", stats.str("key_system"))
        assertEquals("", stats.str("key_system_impl"))
        assertEquals("0", stats.str("total_bytes"))
        assertEquals("0", stats.str("ms_file_duration"))
        assertEquals("23067", stats.str("ms_actual_duration"))
        assertEquals(null, stats.str("ms_license_request_latency"))
        assertEquals(setOf("mp3;160000"), stats["ms_played_per_audio_format"]!!.jsonObject.keys)
        val urls = Json.parseToJsonElement(stats.str("urls_json")!!).jsonObject["urls"]!!.jsonArray
        assertEquals(1, urls.size)
        assertEquals(adUrl, urls[0].jsonObject.str("url"))
        assertEquals("1", urls[0].jsonObject.str("segments"))

        val fin = rig.put("track_data_finalized")
        assertEquals("file_urls_external", fin.str("sub_state", "format"))
        assertEquals("mp3", fin.str("playback_stats", "audiocodec"))
        assertEquals("none", fin.str("playback_stats", "key_system"))
        assertEquals("0", fin.str("playback_stats", "ms_key_latency"))
        assertEquals("0", fin.str("playback_stats", "total_bytes"))
        assertEquals("23067", fin.str("playback_stats", "ms_total_est"))
        assertEquals(setOf("mp3;160000"), fin["playback_stats"]!!.jsonObject["ms_played_per_audio_format"]!!.jsonObject.keys)

        // On to the song its state advances to, a song like any other.
        assertEquals("load ${rig.trackUriOf(3)} @0 play=true", rig.player.calls.last { it.startsWith("load ") && !it.startsWith("load plain") })
        assertFalse(rig.http.requests.any { "/connect-state/v1/player/command/" in it.url })
    }

    @Test
    fun the_ad_is_in_the_media_timeline_with_its_mp3_address() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        adToItsEnd(rig)
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(23_067))
        // The next song's first audio after a natural end sends the timeline.
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        runCurrent()

        val events = rig.melody("media_playback_event_v2").flatMap { it["events"]!!.jsonArray.map { e -> e.jsonObject } }
        val opened = events.single { it.str("ur") == "spotify:ad:test" }
        assertEquals(adUrl, opened.str("ms", "mu"))
        assertEquals("0", opened.str("et"))
    }
}
