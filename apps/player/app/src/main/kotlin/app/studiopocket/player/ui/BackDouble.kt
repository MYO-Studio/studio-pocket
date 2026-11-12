// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/**
 * Back pressed twice quickly on the groups screen or a cover flow jumps to Now Playing. The first
 * press still does its usual job at once (up a level, or nothing on the root screen), so a single
 * Back never waits; only a second press within [WINDOW_MS] jumps. Both presses must start on the
 * groups screen or a cover flow: from Now Playing, Back then Back again would otherwise land back on
 * Now Playing, and settings keeps Back for its own pages. Fed each Back key down (not repeats);
 * pure, so it is unit-tested on the JVM.
 */
class BackDouble {
    private var lastPress = NEVER

    /** A Back press at [eventTime] (uptime ms) on [screen]. True when it completes a double press. */
    fun onPress(eventTime: Long, screen: PlayerNav.Screen): Boolean {
        if (!eligible(screen)) { lastPress = NEVER; return false }
        val double = lastPress != NEVER && eventTime - lastPress in 0..WINDOW_MS
        lastPress = if (double) NEVER else eventTime
        return double
    }

    private fun eligible(screen: PlayerNav.Screen) = screen == PlayerNav.Screen.GROUPS || screen == PlayerNav.Screen.HOME

    companion object {
        /** The most time between the two presses' downs. */
        const val WINDOW_MS = 400L
        private const val NEVER = Long.MIN_VALUE
    }
}
