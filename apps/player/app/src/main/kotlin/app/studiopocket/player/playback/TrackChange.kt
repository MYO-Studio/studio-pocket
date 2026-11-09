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

        /**
         * Next/Previous stop at the first and last track (null: nothing to do), like the cover flow.
         * Only a skip while [playing] honours the timer: from paused or stopped a key means Play.
         */
        fun skip(index: Int, delta: Int, size: Int, playing: Boolean, sleepDue: Boolean): TrackChange? =
            jump(index + delta, size, playing, sleepDue)

        /** Straight to track [target], as chosen with the ring and the centre button; null when there is no such track. */
        fun jump(target: Int, size: Int, playing: Boolean, sleepDue: Boolean): TrackChange? =
            if (target !in 0 until size) null else TrackChange(target, play = !(playing && sleepDue))
    }
}
