// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/** The "please charge" warning shown on the library and Now Playing screens (battery cell colours are unchanged). */
object LowBattery {
    const val THRESHOLD = 10
    const val TEXT = "Battery low – please charge"

    /** True at [THRESHOLD] percent or below while unplugged; a negative percent means the level is unknown. */
    fun warn(percent: Int, charging: Boolean): Boolean = percent in 0..THRESHOLD && !charging
}
