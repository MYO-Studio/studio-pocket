// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

/**
 * How far through a playlist its last played position is, for the bar along the bottom of its
 * cover. It follows the saved resume position, not the furthest point reached: listening to
 * chapter 5 and then going back to chapter 3 shows chapter 3. By time when the manifest has every
 * track's duration, else by track. A playlist played to its end shows finished until it is played
 * again. Pure, so it is unit-tested on the JVM.
 */
object PlaylistProgress {
    /** 0..1 for track [trackIndex] at [positionMs]; null at the very start (nothing to show) or with no tracks. */
    fun fraction(tracks: List<Track>, trackIndex: Int, positionMs: Long): Float? {
        if (tracks.isEmpty()) return null
        val index = trackIndex.coerceIn(0, tracks.size - 1)
        val position = positionMs.coerceAtLeast(0L)
        if (index == 0 && position == 0L) return null
        val result = if (tracks.all { it.durationMs > 0L }) {
            val total = tracks.sumOf { it.durationMs }
            val before = tracks.take(index).sumOf { it.durationMs }
            (before + minOf(position, tracks[index].durationMs)) / total.toFloat()
        } else {
            index / tracks.size.toFloat()
        }
        return result.coerceIn(0f, 1f).takeIf { it > 0f }
    }

    /** The same for a saved state; null when the playlist has never been played. */
    fun fraction(tracks: List<Track>, state: PlaybackState?): Float? =
        state?.let { fraction(tracks, it.trackIndex, it.positionMs) }

    /** What a cover shows for a saved state: finished (a full bar and a tick), a part bar, or nothing. */
    fun cover(tracks: List<Track>, state: PlaybackState?): CoverProgress? = when {
        state == null -> null
        state.finished -> CoverProgress.FINISHED
        // A position says nothing about how much of a shuffled playlist has played.
        state.mode == PlayMode.SHUFFLE -> null
        else -> fraction(tracks, state)?.let { CoverProgress(it, finished = false) }
    }
}

/** A cover's progress bar: [fraction] 0..1, or [finished]. */
data class CoverProgress(val fraction: Float, val finished: Boolean) {
    companion object {
        val FINISHED = CoverProgress(1f, finished = true)
    }
}
