// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackChangeTest {
    @Test fun completionPlaysTheNextTrack() =
        assertEquals(TrackChange(2, play = true), TrackChange.afterCompletion(1, 4, sleepDue = false))

    @Test fun completionWithTheTimerDueStopsOnTheNextTrack() =
        assertEquals(TrackChange(2, play = false), TrackChange.afterCompletion(1, 4, sleepDue = true))

    @Test fun endOfPlaylistStopsAtTheTopTimerOrNot() {
        assertEquals(TrackChange(0, play = false), TrackChange.afterCompletion(3, 4, sleepDue = false))
        assertEquals(TrackChange(0, play = false), TrackChange.afterCompletion(3, 4, sleepDue = true))
    }

    @Test fun skipWrapsRound() {
        assertEquals(TrackChange(0, play = true), TrackChange.skip(3, +1, 4, playing = true, sleepDue = false))
        assertEquals(TrackChange(3, play = true), TrackChange.skip(0, -1, 4, playing = true, sleepDue = false))
    }

    @Test fun skipWhilePlayingWithTheTimerDueStopsOnTheTarget() =
        assertEquals(TrackChange(2, play = false), TrackChange.skip(1, +1, 4, playing = true, sleepDue = true))

    @Test fun skipWhenNotPlayingAlwaysPlays() =
        // Paused or stopped, a key means "play": startTrack clears an overdue timer first.
        assertEquals(TrackChange(2, play = true), TrackChange.skip(1, +1, 4, playing = false, sleepDue = true))
}
