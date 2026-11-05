// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

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
}
