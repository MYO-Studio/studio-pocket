// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryTest {
    @Test fun warnsAtTenOrBelowWhenUnplugged() {
        assertTrue(LowBattery.warn(10, false))
        assertFalse(LowBattery.warn(11, false))
        assertFalse(LowBattery.warn(5, true))
        assertFalse(LowBattery.warn(-1, false))
    }

    @Test fun cellIsGreenAmberOrRedByLevel() {
        assertEquals(Ui.good, LowBattery.cellColour(100, false))
        assertEquals(Ui.good, LowBattery.cellColour(51, false))
        assertEquals(Ui.warn, LowBattery.cellColour(50, false))
        assertEquals(Ui.warn, LowBattery.cellColour(21, false))
        assertEquals(Ui.danger, LowBattery.cellColour(20, false))
        assertEquals(Ui.danger, LowBattery.cellColour(0, false))
    }

    @Test fun cellIsAccentWhileCharging() {
        assertEquals(Ui.accent, LowBattery.cellColour(5, true))
        assertEquals(Ui.accent, LowBattery.cellColour(90, true))
    }
}
