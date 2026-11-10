// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTextTest {
    @Test fun twelveHour() {
        assertEquals("9:36", ClockText.format(9, 36, false))
        assertEquals("9:05", ClockText.format(21, 5, false))
        assertEquals("12:00", ClockText.format(0, 0, false))
        assertEquals("12:30", ClockText.format(12, 30, false))
    }

    @Test fun twentyFourHour() {
        assertEquals("21:05", ClockText.format(21, 5, true))
        assertEquals("0:00", ClockText.format(0, 0, true))
    }
}
