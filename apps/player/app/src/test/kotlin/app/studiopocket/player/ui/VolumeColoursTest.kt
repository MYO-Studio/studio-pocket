// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeColoursTest {
    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF

    @Test fun hsvMatchesKnownColours() {
        assertEquals(0xFFFF0000.toInt(), VolumeColours.hsv(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), VolumeColours.hsv(120f, 1f, 1f))
        assertEquals(0xFFFFFF00.toInt(), VolumeColours.hsv(60f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), VolumeColours.hsv(240f, 1f, 1f))
    }

    @Test fun runsGreenToRedAndStaysRedAtTheTop() {
        val n = 15
        val first = VolumeColours.segment(0, n)
        assertTrue(g(first) > r(first) && g(first) > b(first))
        val last = VolumeColours.segment(n - 1, n)
        assertTrue(r(last) > g(last) * 3 && r(last) > b(last))
        for (i in 1 until n) assertTrue(VolumeColours.hue(i, n) <= VolumeColours.hue(i - 1, n))
        assertEquals(VolumeColours.RED_HUE, VolumeColours.hue(12, n), 0.01f)
        assertEquals(VolumeColours.RED_HUE, VolumeColours.hue(14, n), 0.01f)
    }

    @Test fun middleIsYellowish() {
        val mid = VolumeColours.segment(5, 15)
        assertTrue(r(mid) > 150 && g(mid) > 150 && b(mid) < 100)
    }
}
