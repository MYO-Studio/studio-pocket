// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/**
 * The small clock top left: "9:36", or "21:36" when the player is set to 24-hour time. No AM/PM,
 * to stay small. Pure, so it is unit-tested on the JVM.
 */
object ClockText {
    fun format(hourOfDay: Int, minute: Int, is24Hour: Boolean): String {
        val h = if (is24Hour) hourOfDay else (hourOfDay % 12).let { if (it == 0) 12 else it }
        return "$h:${minute.toString().padStart(2, '0')}"
    }
}
