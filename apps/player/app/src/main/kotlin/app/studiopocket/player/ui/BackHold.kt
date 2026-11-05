// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/**
 * Detects a held Back key (the hidden gesture that opens settings). Fed every key event; returns
 * true exactly once per press, on the first auto-repeat after [holdMs]. Pure, so it is unit-tested
 * on the JVM like [PlayerNav].
 */
class BackHold(private val holdMs: Long = 3000L) {
    private var fired = false

    fun onKey(keyName: String, down: Boolean, repeatCount: Int, eventTimeMs: Long, downTimeMs: Long): Boolean {
        if (keyName != "KEYCODE_BACK" || !down) return false
        if (repeatCount == 0) fired = false
        if (fired || repeatCount == 0 || eventTimeMs - downTimeMs < holdMs) return false
        fired = true
        return true
    }
}
