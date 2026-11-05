// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

/** The choices on the Screen page: Settings.System values and their labels. */
object ScreenSteps {
    val BRIGHTNESS = intArrayOf(20, 70, 130, 190, 255)
    val TIMEOUT_MS = intArrayOf(15_000, 30_000, 60_000, 120_000, 300_000)
    val TIMEOUT_LABELS = listOf("15 s", "30 s", "1 min", "2 min", "5 min")

    /** Index of the step closest to [value] (the first of two equally close). */
    fun nearest(steps: IntArray, value: Int): Int {
        var best = 0
        for (i in steps.indices) if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) best = i
        return best
    }
}
