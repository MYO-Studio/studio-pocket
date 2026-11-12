// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/**
 * Caps the ring's momentum. A fast spin sends ring steps (about two per notch) faster than a screen
 * can move and redraw, and Android holds the extra steps in its input queue and hands them over one
 * by one, so the screen kept moving long after the ring stopped. A ring step that waited in that
 * queue longer than [MAX_LAG_MS] is dropped: it is cheap to drop, so the backlog drains at once and
 * the screen stops within about [MAX_LAG_MS] of the ring. Other keys are never dropped. Pure, so it
 * is unit-tested on the JVM.
 */
object RingLag {
    const val MAX_LAG_MS = 150L

    fun isRing(keyName: String): Boolean = keyName == "KEYCODE_DPAD_UP" || keyName == "KEYCODE_DPAD_DOWN"

    /** True for a ring step pressed at [eventTime] that is too old to act on at [now] (both uptime ms). */
    fun drop(keyName: String, eventTime: Long, now: Long): Boolean = isRing(keyName) && now - eventTime > MAX_LAG_MS
}
