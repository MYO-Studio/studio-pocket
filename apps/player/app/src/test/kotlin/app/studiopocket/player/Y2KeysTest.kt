// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

import org.junit.Assert.assertEquals
import org.junit.Test

class Y2KeysTest {
    @Test
    fun labelsKnownScanCodesFromTheStockKeyLayout() {
        assertEquals("VOLUME_UP", Y2Keys.label(115))
        assertEquals("VOLUME_DOWN", Y2Keys.label(114))
        assertEquals("POWER", Y2Keys.label(116))
        assertEquals("PLAY_PAUSE", Y2Keys.label(164))
        // measured on hardware 2026-10-19
        assertEquals("RING_LEFT", Y2Keys.label(103))
        assertEquals("RING_RIGHT", Y2Keys.label(108))
        assertEquals("CENTRE", Y2Keys.label(28))
        assertEquals("ARROW_BACK", Y2Keys.label(105))
        assertEquals("scan999", Y2Keys.label(999))
    }

    @Test
    fun describesAnEventOnOneLine() {
        assertEquals("DOWN KEYCODE_DPAD_UP RING_LEFT rep0", Y2Keys.describe("DOWN", "KEYCODE_DPAD_UP", 103, 0))
        assertEquals("UP KEYCODE_UNKNOWN scan999 rep1", Y2Keys.describe("UP", "KEYCODE_UNKNOWN", 999, 1))
    }
}
