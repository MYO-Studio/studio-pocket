// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenWakeTest {
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    @Test
    fun thePlayersOwnPlayPauseWakesTheScreenWhenItIsOff() =
        assertTrue(ScreenWake.shouldWake(screenOn = false, action = down, repeatCount = 0, scanCode = 164))

    @Test
    fun notWhenTheScreenIsAlreadyOn() =
        assertFalse(ScreenWake.shouldWake(screenOn = true, action = down, repeatCount = 0, scanCode = 164))

    @Test
    fun onlyOnTheFirstDownOfAPress() {
        assertFalse(ScreenWake.shouldWake(screenOn = false, action = up, repeatCount = 0, scanCode = 164))
        assertFalse(ScreenWake.shouldWake(screenOn = false, action = down, repeatCount = 1, scanCode = 164))
    }

    @Test
    fun headsetAndBluetoothMediaKeysNeverWakeIt() {
        assertFalse(ScreenWake.shouldWake(screenOn = false, action = down, repeatCount = 0, scanCode = 0))
        assertFalse(ScreenWake.shouldWake(screenOn = false, action = down, repeatCount = 0, scanCode = 226))
    }
}
