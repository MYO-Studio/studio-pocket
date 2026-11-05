// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackHoldTest {
    @Test
    fun firesOnceAtThreeSeconds() {
        val h = BackHold()
        assertFalse(h.onKey("KEYCODE_BACK", true, 0, 1000, 1000))
        assertFalse(h.onKey("KEYCODE_BACK", true, 40, 3990, 1000))
        assertTrue(h.onKey("KEYCODE_BACK", true, 41, 4010, 1000))
        assertFalse(h.onKey("KEYCODE_BACK", true, 42, 4050, 1000))
        assertFalse(h.onKey("KEYCODE_BACK", false, 0, 4100, 1000))
        assertFalse(h.onKey("KEYCODE_BACK", true, 0, 5000, 5000))   // new press re-arms
        assertTrue(h.onKey("KEYCODE_BACK", true, 75, 8000, 5000))
    }

    @Test
    fun ignoresOtherKeysAndShortPresses() {
        val h = BackHold()
        assertFalse(h.onKey("KEYCODE_ENTER", true, 140, 9000, 1000))
        assertFalse(h.onKey("KEYCODE_BACK", false, 0, 1500, 1000))
    }
}
