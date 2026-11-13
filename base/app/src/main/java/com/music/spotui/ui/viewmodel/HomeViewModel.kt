package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel constructor(private val repository: AppRepository = Graph.repository)  : ViewModel() {

    private val _home : MutableStateFlow<Response<HomeFeedModel>> = MutableStateFlow(Response.Loading())
    val home : StateFlow<Response<HomeFeedModel>> = _home

    /** Selected home chip ("" = All). */
    val facet = androidx.compose.runtime.mutableStateOf("")
    private var homeJob: kotlinx.coroutines.Job? = null

    private val _refreshing = MutableStateFlow(false)
    /** A pull to refresh under way: its spinner shows until the read is over. */
    val refreshing: StateFlow<Boolean> = _refreshing

    init {
        fetchHome()
        // A Home that failed (player still signing in, network back) loads again by itself:
        // when the player is ready again, then a few times with growing pauses. The copy kept on
        // storage is read again once the player is ready too: the read at launch gives up when the
        // player takes more than 30 s, and that copy stayed up for the next half hour.
        viewModelScope.launch {
            app.myspoti.host.android.MyspotiRuntime.api.ready.collect { ready ->
                if (!ready) return@collect
                if (_home.value is Response.Error) fetchHome() else refreshIfStale()
            }
        }
        viewModelScope.launch {
            var tries = 0
            _home.collect { state ->
                if (state is Response.Success) tries = 0
                if (state is Response.Error && tries < RETRY_DELAYS_MS.size) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[tries++])
                    if (_home.value is Response.Error) fetchHome()
                }
            }
        }
    }

    /** Tries again now (the Home error's button). */
    fun retry() {
        _home.value = Response.Loading()
        fetchHome()
    }

    /**
     * Home shown again (back on its tab, the app reopened): read again once the feed shown is older
     * than [HomeFreshness.STALE_MS], behind the feed shown. It was read once per launch, and an app
     * kept open for days showed the same releases and picks (reported: nothing new to discover).
     * The age is the feed's, not the last attempt's: a read that failed left the old feed up for
     * the next half hour.
     */
    fun refreshIfStale() {
        val shown = (_home.value as? Response.Success)?.data?.readAtMs
        if (HomeFreshness.needsRead(shown, reading = homeJob?.isActive == true, nowMs = System.currentTimeMillis())) fetchHome()
    }

    /**
     * While Home is on screen: [refreshIfStale] now and every [HomeFreshness.CHECK_MS] (Home left
     * open, or under the player, was never read again).
     */
    suspend fun keepFresh() {
        while (true) {
            refreshIfStale()
            kotlinx.coroutines.delay(HomeFreshness.CHECK_MS)
        }
    }

    /** Pulled down: read again now, whatever its age. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        fetchHome()
        homeJob?.invokeOnCompletion { _refreshing.value = false }
    }

    /**
     * The chip [f] tapped: it is picked, or unpicked when it already was: a sub-chip (Following) goes
     * back to its parent (Music), a top chip back to everything.
     */
    fun selectFacet(f: String) {
        facet.value = if (facet.value != f) f
            else com.music.spotui.data.entity.HomeSubChips.parentOf(f)?.takeIf { it != f } ?: ""
        fetchHome()
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 15_000, 30_000)
    }

    private fun fetchHome() {
        homeJob?.cancel()
        val f = facet.value
        homeJob = viewModelScope.launch(Dispatchers.IO) {
            repository.provideHomeFeed(f).collect { feed ->
                _home.value = feed
            }
        }
    }
}

/** When Home's feed is read again (kept pure, tested without Android). */
internal object HomeFreshness {
    /** A feed older than this is read again (was 30 min: what was just played showed late in "Jump back in"). */
    const val STALE_MS = 5 * 60_000L
    /** How often Home on screen looks at its feed's age. */
    const val CHECK_MS = 60_000L

    /**
     * The feed shown, sent by Spotify at [shownReadAtMs] (null: none shown; 0: a copy kept on
     * storage, undated), is read again: no read under way, and it is older than [STALE_MS].
     */
    fun needsRead(shownReadAtMs: Long?, reading: Boolean, nowMs: Long): Boolean =
        !reading && (shownReadAtMs == null || nowMs - shownReadAtMs >= STALE_MS)
}
