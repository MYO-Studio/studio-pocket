// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressTest {
    @Test fun aTapActsOnRelease() {
        val p = LongPress(600)
        p.press(1_000)
        assertFalse(p.hold(1_500))
        assertTrue(p.release())
        assertFalse(p.active)
    }

    @Test fun aHoldFiresOnceAndSwallowsTheRelease() {
        val p = LongPress(600)
        p.press(1_000)
        assertTrue(p.hold(1_600))
        assertFalse(p.hold(1_650))
        assertFalse(p.release())
    }

    @Test fun noPressOrACancelDoesNothing() {
        val p = LongPress(600)
        assertFalse(p.release())
        assertFalse(p.hold(5_000))
        p.press(0)
        assertFalse(p.release(canceled = true))
    }
}
