// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

/**
 * Where a track change lands and whether it plays, pure so it can be tested. A stop means
 * [PlaybackService] saves [index] at 0 s so the next Play starts that track. A sleep timer that is
 * due at a track change stops playback there; otherwise startTrack would only clear the timer and
 * play on at full gain.
 */
data class TrackChange(val index: Int, val play: Boolean) {
    companion object {
        /** Track [index] of [size] finished. The end of the playlist stops at the top. */
        fun afterCompletion(index: Int, size: Int, sleepDue: Boolean): TrackChange =
            if (index + 1 >= size) TrackChange(0, play = false) else TrackChange(index + 1, play = !sleepDue)

        /** Next/Previous wrap round. Only a skip while [playing] honours the timer: from paused or stopped a key means Play. */
        fun skip(index: Int, delta: Int, size: Int, playing: Boolean, sleepDue: Boolean): TrackChange =
            TrackChange(((index + delta) % size + size) % size, play = !(playing && sleepDue))
    }
}
