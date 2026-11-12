// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import app.studiopocket.player.library.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeMenuTest {
    @Test fun opensOnTheCurrentModeAndPicksWithCentre() {
        val m = ModeMenu()
        m.open(PlayMode.REPEAT_ONE)
        assertTrue(m.isOpen)
        assertEquals(2, m.selected)
        assertEquals(ModeMenu.Result.Moved, m.onKey("KEYCODE_DPAD_DOWN"))
        assertEquals(ModeMenu.Result.Chose(PlayMode.SHUFFLE), m.onKey("KEYCODE_DPAD_CENTER"))
        assertFalse(m.isOpen)
    }

    @Test fun stopsAtEitherEnd() {
        val m = ModeMenu()
        m.open(PlayMode.NORMAL)
        assertEquals(ModeMenu.Result.Ignored, m.onKey("KEYCODE_DPAD_UP"))
        repeat(5) { m.onKey("KEYCODE_DPAD_DOWN") }
        assertEquals(ModeMenu.OPTIONS.lastIndex, m.selected)
    }

    @Test fun backClosesWithoutAChange() {
        val m = ModeMenu()
        m.open(PlayMode.SHUFFLE)
        m.onKey("KEYCODE_DPAD_UP")
        assertEquals(ModeMenu.Result.Closed, m.onKey("KEYCODE_BACK"))
        assertFalse(m.isOpen)
        assertEquals(ModeMenu.Result.Ignored, m.onKey("KEYCODE_DPAD_CENTER"))
    }
}
