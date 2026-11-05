// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

/**
 * A one-off countdown, pure so it can be tested: the caller supplies the clock. Gain is 1 until
 * [FADE_MS] before the end, then falls linearly to 0. Not persisted; [PlaybackService] owns one.
 */
class SleepTimer {
    private var endMs = 0L
    var active = false; private set

    fun start(minutes: Int, nowMs: Long) {
        active = minutes > 0
        endMs = if (active) nowMs + minutes * 60_000L else 0L
    }

    fun cancel() { active = false; endMs = 0L }

    fun remainingMs(nowMs: Long): Long = if (active) (endMs - nowMs).coerceAtLeast(0L) else 0L

    /** Whole minutes left, rounded up; null when no timer is running. */
    fun minutesLeft(nowMs: Long): Int? = if (active) ((remainingMs(nowMs) + 59_999L) / 60_000L).toInt() else null

    fun gain(nowMs: Long): Float = if (active) (remainingMs(nowMs).toFloat() / FADE_MS).coerceIn(0f, 1f) else 1f

    fun isDue(nowMs: Long): Boolean = active && nowMs >= endMs

    companion object {
        const val FADE_MS = 10_000L
        val OPTIONS = listOf(0, 15, 30, 45, 60)
    }
}
