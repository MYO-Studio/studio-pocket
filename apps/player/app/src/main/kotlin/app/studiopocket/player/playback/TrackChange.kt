// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

import app.studiopocket.player.library.PlayMode

/**
 * Where a track change lands and whether it plays, pure so it can be tested. A stop means
 * [PlaybackService] saves [index] at 0 s so the next Play starts that track. A sleep timer that is
 * due at a track change stops playback there; otherwise startTrack would only clear the timer and
 * play on at full gain.
 */
data class TrackChange(val index: Int, val play: Boolean) {
    companion object {
        /**
         * Track [index] of [size] finished. Normal: the next track, and the end of the playlist stops
         * at the top. Repeat one: the same track again. Repeat all: the next, round to the first.
         * Shuffle: [shuffleNext], picked by [ShuffleOrder]; it never ends. A due sleep timer stops on
         * the track that would have played, in every mode.
         */
        fun afterCompletion(index: Int, size: Int, sleepDue: Boolean, mode: PlayMode = PlayMode.NORMAL, shuffleNext: Int = 0): TrackChange =
            when (mode) {
                PlayMode.NORMAL -> if (index + 1 >= size) TrackChange(0, play = false) else TrackChange(index + 1, play = !sleepDue)
                PlayMode.REPEAT_ONE -> TrackChange(index, play = !sleepDue)
                PlayMode.REPEAT_ALL -> TrackChange(if (size > 0) (index + 1) % size else 0, play = !sleepDue)
                PlayMode.SHUFFLE -> TrackChange(shuffleNext.coerceIn(0, maxOf(0, size - 1)), play = !sleepDue)
            }

        /**
         * Next/Previous stop at the first and last track (null: nothing to do), like the cover flow,
         * in Normal and Repeat one. Repeat all goes round. Shuffle goes to [shuffleTarget] (a random
         * next, or the track played before) when there is one, else as Normal.
         * Only a skip while [playing] honours the timer: from paused or stopped a key means Play.
         */
        fun skip(
            index: Int, delta: Int, size: Int, playing: Boolean, sleepDue: Boolean,
            mode: PlayMode = PlayMode.NORMAL, shuffleTarget: Int? = null,
        ): TrackChange? {
            val target = when {
                mode == PlayMode.SHUFFLE && shuffleTarget != null -> shuffleTarget
                mode == PlayMode.REPEAT_ALL && size > 0 -> ((index + delta) % size + size) % size
                else -> index + delta
            }
            return jump(target, size, playing, sleepDue)
        }

        /** Straight to track [target], as chosen with the ring and the centre button; null when there is no such track. */
        fun jump(target: Int, size: Int, playing: Boolean, sleepDue: Boolean): TrackChange? =
            if (target !in 0 until size) null else TrackChange(target, play = !(playing && sleepDue))
    }
}
