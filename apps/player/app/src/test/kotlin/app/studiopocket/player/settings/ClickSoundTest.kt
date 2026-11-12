// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickSoundTest {
    @Test fun mapsTheSystemValueToOffOrOn() {
        assertEquals("Off", ClickSound.LABELS[ClickSound.toIndex(0)])
        assertEquals("On", ClickSound.LABELS[ClickSound.toIndex(1)])
        assertEquals(0, ClickSound.fromIndex(0))
        assertEquals(1, ClickSound.fromIndex(1))
    }
}
