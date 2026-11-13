package com.music.spotui.di

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.LastSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastSessionTest {
    private fun song(i: Int) = SongsModel(i, "t$i", "a", "s", "c", "local:u$i", spotifyTrackId = "id$i", durationMs = 1000)

    @Test fun queueRoundTrips() {
        val q = listOf(song(1), song(2))
        assertEquals(q, LastSession.decodeQueue(LastSession.encodeQueue(q)))
    }

    @Test fun queueIsCappedAndBadInputIsEmpty() {
        assertEquals(100, LastSession.decodeQueue(LastSession.encodeQueue((1..150).map(::song))).size)
        assertTrue(LastSession.decodeQueue("{broken").isEmpty())
        assertTrue(LastSession.decodeQueue(null).isEmpty())
    }

    @Test fun theSavedQueueStartsAtItsSongAndHoldsOnlyItsOwn() {
        val page = listOf(song(1), song(2), song(3), song(2))
        // The page's queue still lists the song before; once each, from the song on.
        assertEquals(listOf(song(2), song(3)), LastSession.queueFor("id2", page))
        // A queue read just after a skip belongs to the song left: not saved with the new one.
        assertTrue(LastSession.queueFor("id9", page).isEmpty())
        assertTrue(LastSession.queueFor("", page).isEmpty())
    }

    @Test fun theLocalPlayersQueueKeepsEachSongsSpotifyId() {
        // As the local player maps them: a download or an extension's stream has no Spotify id of its own.
        val local = listOf(song(1), song(2), song(3)).map { it.copy(spotifyTrackId = "") }
        // A download (id1, id3) is saved with its Spotify id, a file of the device (none) is left out.
        val saved = LastSession.localQueue(local, listOf("id1", "", "id3"))
        assertEquals(listOf("id1", "id3"), saved.map { it.spotifyTrackId })
        assertEquals(listOf("t1", "t3"), saved.map { it.title })
    }

    @Test fun olderPausedEngineStateDoesNotReplaceLocalSession() {
        assertTrue(LastSession.engineStateIsStale(LastSession.SOURCE_LOCAL, 2000, 1000, true))
        assertFalse(LastSession.engineStateIsStale(LastSession.SOURCE_LOCAL, 2000, 3000, true))
        assertFalse(LastSession.engineStateIsStale(LastSession.SOURCE_LOCAL, 2000, 1000, false))
        assertFalse(LastSession.engineStateIsStale(LastSession.SOURCE_SPOTIFY, 2000, 1000, true))
    }
}
