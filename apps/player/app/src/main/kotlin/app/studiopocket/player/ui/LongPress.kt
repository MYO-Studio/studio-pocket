// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/**
 * Tells a tap from a hold on one key: the tap acts when the key is let go, the hold fires once
 * after [thresholdMs] (on a key repeat) and the rest of that press is then ignored. Used for Centre
 * on Now Playing (tap: pause or play the browsed track; hold: the play-mode menu). Pure, so it is
 * unit-tested on the JVM.
 */
class LongPress(private val thresholdMs: Long = HOLD_MS) {
    private var downTime = 0L
    private var fired = false

    /** True between [press] and [release]. */
    var active = false; private set

    fun press(downTime: Long) {
        this.downTime = downTime
        active = true
        fired = false
    }

    /** A key repeat at [eventTime]: true once, when the hold first reaches [thresholdMs]. */
    fun hold(eventTime: Long): Boolean {
        if (!active || fired || eventTime - downTime < thresholdMs) return false
        fired = true
        return true
    }

    /** The key went up: true for a tap (act on it now); false after a hold, a cancel, or with no press. */
    fun release(canceled: Boolean = false): Boolean {
        val tap = active && !fired && !canceled
        active = false
        fired = false
        return tap
    }

    fun cancel() { active = false; fired = false }

    companion object {
        const val HOLD_MS = 600L
    }
}
