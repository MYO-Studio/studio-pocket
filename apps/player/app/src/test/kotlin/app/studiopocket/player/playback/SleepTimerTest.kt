// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    @Test fun countsDownAndFadesInTheLastTenSeconds() {
        val t = SleepTimer(); t.start(15, 0)
        assertEquals(15, t.minutesLeft(0)); assertEquals(1, t.minutesLeft(14 * 60_000L + 1))
        assertEquals(1f, t.gain(15 * 60_000L - 10_000), 0.001f); assertEquals(0.5f, t.gain(15 * 60_000L - 5_000), 0.001f)
        assertFalse(t.isDue(15 * 60_000L - 1)); assertTrue(t.isDue(15 * 60_000L))
    }

    @Test fun inactiveTimerIsSilent() {
        val t = SleepTimer(); assertNull(t.minutesLeft(0)); assertEquals(1f, t.gain(0), 0f); assertFalse(t.isDue(Long.MAX_VALUE))
        t.start(15, 0); t.cancel(); assertFalse(t.active)
    }

    @Test fun startingZeroMeansOff() { val t = SleepTimer(); t.start(0, 0); assertFalse(t.active) }
}
