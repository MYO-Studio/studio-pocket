// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStepsTest {
    @Test fun nearestPicksClosestStep() {
        assertEquals(1, ScreenSteps.nearest(ScreenSteps.BRIGHTNESS, 100))
        assertEquals(4, ScreenSteps.nearest(ScreenSteps.BRIGHTNESS, 255))
        assertEquals(2, ScreenSteps.nearest(ScreenSteps.TIMEOUT_MS, 60_000))
        assertEquals(0, ScreenSteps.nearest(ScreenSteps.TIMEOUT_MS, -1))
    }
}
