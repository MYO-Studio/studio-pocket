// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import app.studiopocket.player.ui.PlayerNav.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackDoubleTest {
    @Test fun twoQuickPressesOnACoverFlowOrTheGroupsScreenJump() {
        val d = BackDouble()
        assertFalse(d.onPress(1_000, Screen.HOME))
        assertTrue(d.onPress(1_000 + BackDouble.WINDOW_MS, Screen.GROUPS))   // the first press went up to the groups
    }

    @Test fun slowPressesDoNot() {
        val d = BackDouble()
        assertFalse(d.onPress(1_000, Screen.GROUPS))
        assertFalse(d.onPress(1_001 + BackDouble.WINDOW_MS, Screen.GROUPS))
        assertTrue(d.onPress(1_100 + BackDouble.WINDOW_MS, Screen.GROUPS))   // ...but that slow one starts a new pair
    }

    @Test fun aThirdQuickPressStartsAgain() {
        val d = BackDouble()
        d.onPress(0, Screen.GROUPS)
        assertTrue(d.onPress(100, Screen.GROUPS))
        assertFalse(d.onPress(200, Screen.GROUPS))
    }

    @Test fun notFromNowPlayingOrSettings() {
        val d = BackDouble()
        assertFalse(d.onPress(0, Screen.PLAYER))
        assertFalse(d.onPress(100, Screen.HOME))      // the press on Now Playing does not count as a first
        val s = BackDouble()
        s.onPress(0, Screen.SETTINGS)
        assertFalse(s.onPress(100, Screen.SETTINGS))
    }
}
