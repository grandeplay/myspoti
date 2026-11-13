package com.music.spotui.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFreshnessTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test fun aFeedJustReadIsKept() {
        assertFalse(HomeFreshness.needsRead(shownReadAtMs = now - minute, reading = false, nowMs = now))
    }

    @Test fun aFeedOlderThanTheLimitIsReadAgain() {
        assertTrue(HomeFreshness.needsRead(shownReadAtMs = now - HomeFreshness.STALE_MS, reading = false, nowMs = now))
    }

    @Test fun theCopyKeptOnStorageIsReadAgainWhateverItsAge() {
        // Kept by a version that did not date it, or dated long ago: 0 or old, both read again.
        assertTrue(HomeFreshness.needsRead(shownReadAtMs = 0L, reading = false, nowMs = now))
    }

    @Test fun nothingShownIsRead() {
        assertTrue(HomeFreshness.needsRead(shownReadAtMs = null, reading = false, nowMs = now))
    }

    @Test fun aReadThatFailedDoesNotMakeTheOldFeedFresh() {
        // The feed shown is the old one: a failed read a moment ago left it as old as it was.
        assertTrue(HomeFreshness.needsRead(shownReadAtMs = now - 40 * minute, reading = false, nowMs = now))
    }

    @Test fun aReadUnderWayIsNotStartedAgain() {
        assertFalse(HomeFreshness.needsRead(shownReadAtMs = now - 40 * minute, reading = true, nowMs = now))
    }
}
