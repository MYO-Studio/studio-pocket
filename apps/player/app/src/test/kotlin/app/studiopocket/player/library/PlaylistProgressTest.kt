// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistProgressTest {
    private fun track(n: Int, durationMs: Long) = Track("t$n", "Chapter $n", "t$n.mp3", durationMs, 1, "0".repeat(64), "audio/mpeg")
    private val chapters = listOf(track(1, 60_000), track(2, 60_000), track(3, 120_000))   // 4 minutes

    @Test fun neverPlayedOrAtTheVeryStartShowsNothing() {
        assertNull(PlaylistProgress.fraction(chapters, null))
        assertNull(PlaylistProgress.fraction(chapters, 0, 0))
        assertNull(PlaylistProgress.fraction(emptyList(), 2, 1_000))
    }

    @Test fun countsTheTracksBeforeAndThePositionInTheCurrentOne() {
        assertEquals(0.125f, PlaylistProgress.fraction(chapters, 0, 30_000)!!, 0.0001f)
        assertEquals(0.5f, PlaylistProgress.fraction(chapters, 2, 0)!!, 0.0001f)
        assertEquals(0.75f, PlaylistProgress.fraction(chapters, 2, 60_000)!!, 0.0001f)
    }

    @Test fun followsTheLastPlayedPositionNotTheFurthest() {
        val atChapterThree = PlaybackState("p", 2, 0, "2026-11-10T00:00:00Z")
        val backAtChapterOne = PlaybackState("p", 0, 30_000, "2026-11-10T00:01:00Z")
        assertEquals(0.5f, PlaylistProgress.fraction(chapters, atChapterThree)!!, 0.0001f)
        assertEquals(0.125f, PlaylistProgress.fraction(chapters, backAtChapterOne)!!, 0.0001f)
    }

    @Test fun withoutEveryDurationItCountsTracks() {
        val unknown = listOf(track(1, 0), track(2, 60_000), track(3, 60_000), track(4, 60_000))
        assertEquals(0.5f, PlaylistProgress.fraction(unknown, 2, 5_000)!!, 0.0001f)
        assertNull(PlaylistProgress.fraction(unknown, 0, 5_000))
    }

    @Test fun clampsAnOutOfRangeState() {
        assertEquals(1f, PlaylistProgress.fraction(chapters, 9, 999_999)!!, 0.0001f)
    }
}
