// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/** The "please charge" warning shown on the library and Now Playing screens, and the battery cell's colour. */
object LowBattery {
    const val THRESHOLD = 10
    const val TEXT = "Battery low – please charge"

    /** True at [THRESHOLD] percent or below while unplugged; a negative percent means the level is unknown. */
    fun warn(percent: Int, charging: Boolean): Boolean = percent in 0..THRESHOLD && !charging

    /** Above this percent the cell is green. */
    const val GOOD_ABOVE = 50
    /** At or below this percent the cell is red; between the two it is amber. */
    const val DANGER_AT = 20

    /** The battery cell's colour: accent while charging, otherwise green, amber or red by level. */
    fun cellColour(percent: Int, charging: Boolean): Int = when {
        charging -> Ui.accent
        percent > GOOD_ABOVE -> Ui.good
        percent > DANGER_AT -> Ui.warn
        else -> Ui.danger
    }
}
