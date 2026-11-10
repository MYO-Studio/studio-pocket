// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingLagTest {
    @Test fun dropsOnlyRingStepsThatWaitedTooLong() {
        assertFalse(RingLag.drop("KEYCODE_DPAD_DOWN", 1_000, 1_000 + RingLag.MAX_LAG_MS))
        assertTrue(RingLag.drop("KEYCODE_DPAD_DOWN", 1_000, 1_001 + RingLag.MAX_LAG_MS))
        assertTrue(RingLag.drop("KEYCODE_DPAD_UP", 1_000, 2_000))
    }

    @Test fun neverDropsOtherKeys() {
        for (k in listOf("KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_BACK", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT", "KEYCODE_MEDIA_PLAY_PAUSE")) {
            assertFalse(k, RingLag.drop(k, 0, 10_000))
        }
    }
}
