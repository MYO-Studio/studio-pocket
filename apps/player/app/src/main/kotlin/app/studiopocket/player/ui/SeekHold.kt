// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/**
 * Next/Previous on the player screen: a press skips a track when it is let go; a hold of [HOLD_MS]
 * or more scrubs instead. The scrub is a silent preview: the times and progress bar move from where
 * the track was when the button went down, faster the longer it is held ([offsetMs]), and the
 * player seeks once, to the previewed position, when the button is let go. Audio carries on where it
 * was until then. The preview stops [END_GUARD_MS] short of the end, so letting go never finishes the
 * track at once. Driven by the key's down time and its repeats' event times; pure, so it is
 * unit-tested on the JVM.
 */
class SeekHold {
    sealed class Release {
        /** A short press, or a hold that could not scrub: skip a track that way. */
        data class Skip(val direction: Int) : Release()
        /** A scrub: seek the playing track to [positionMs]. */
        data class Seek(val positionMs: Long) : Release()
        /** Nothing was pressed, or the press was cancelled. */
        object None : Release()
    }

    /** +1 (Next) or -1 (Previous) while a press is down, else 0. */
    var direction = 0; private set
    /** True once the press has been held long enough to scrub. */
    var scrubbing = false; private set
    /** The previewed position while [scrubbing]. */
    var previewMs = 0L; private set

    private var downTime = 0L
    private var startMs = 0L
    private var durationMs = 0L

    val active: Boolean get() = direction != 0

    /**
     * Button down at [downTime]. [positionMs] and [durationMs] are the playing track's; a duration of
     * 0 (nothing playing or paused, or not known yet) means a hold cannot scrub.
     */
    fun press(direction: Int, downTime: Long, positionMs: Long, durationMs: Long) {
        this.direction = direction
        this.downTime = downTime
        this.startMs = positionMs.coerceAtLeast(0L)
        this.durationMs = durationMs
        scrubbing = false
        previewMs = startMs
    }

    /** A key repeat at [eventTime]. True when the preview moved (the screen should redraw). */
    fun hold(eventTime: Long): Boolean {
        if (!active || durationMs <= 0L) return false
        val held = eventTime - downTime
        if (held < HOLD_MS) return false
        val last = maxOf(0L, durationMs - END_GUARD_MS)
        val target = (startMs + direction * offsetMs(held - HOLD_MS)).coerceIn(0L, maxOf(last, minOf(startMs, durationMs)))
        val moved = !scrubbing || target != previewMs
        scrubbing = true
        previewMs = target
        return moved
    }

    /** The button went up; [canceled] (the system took the key away) does nothing. */
    fun release(canceled: Boolean = false): Release {
        val result = when {
            !active || canceled -> Release.None
            scrubbing -> Release.Seek(previewMs)
            else -> Release.Skip(direction)
        }
        cancel()
        return result
    }

    fun cancel() {
        direction = 0
        scrubbing = false
    }

    companion object {
        /** How long Next/Previous must be held before it scrubs instead of skipping. */
        const val HOLD_MS = 500L
        /** The preview never goes closer to the end of the track than this. */
        const val END_GUARD_MS = 2_000L
        const val SLOW_MS = 2_000L
        const val MEDIUM_MS = 3_000L
        /** Scrub speeds, in track milliseconds per held millisecond. */
        const val SLOW_RATE = 10L
        const val MEDIUM_RATE = 30L
        const val FAST_RATE = 60L

        fun direction(keyName: String): Int = when (keyName) {
            "KEYCODE_DPAD_RIGHT", "KEYCODE_MEDIA_NEXT" -> 1
            "KEYCODE_DPAD_LEFT", "KEYCODE_MEDIA_PREVIOUS" -> -1
            else -> 0
        }

        /**
         * How far the preview has moved after scrubbing for [ms]: 10x for the first 2 s (20 s of
         * track), 30x for the next 3 s (90 s more), then 60x.
         */
        fun offsetMs(ms: Long): Long {
            if (ms <= 0L) return 0L
            val slow = minOf(ms, SLOW_MS)
            val medium = (minOf(ms, SLOW_MS + MEDIUM_MS) - SLOW_MS).coerceAtLeast(0L)
            val fast = (ms - SLOW_MS - MEDIUM_MS).coerceAtLeast(0L)
            return slow * SLOW_RATE + medium * MEDIUM_RATE + fast * FAST_RATE
        }
    }
}
