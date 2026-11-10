// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeCapTest {
    @Test fun clampsOnlyAboveTheCap() {
        assertEquals(8, VolumeCap.target(12, 8)); assertNull(VolumeCap.target(8, 8)); assertNull(VolumeCap.target(15, VolumeCap.NONE))
    }

    @Test fun blocksVolumeUpAtTheCap() {
        assertTrue(VolumeCap.blocksUp(8, 8)); assertFalse(VolumeCap.blocksUp(7, 8)); assertFalse(VolumeCap.blocksUp(15, VolumeCap.NONE))
    }

    @Test fun lastLabelIsNoLimit() {
        val l = VolumeCap.labels(15); assertEquals(15, l.size); assertEquals("No limit", l.last()); assertEquals("1", l.first())
        assertEquals(VolumeCap.NONE, VolumeCap.fromIndex(14, 15)); assertEquals(14, VolumeCap.toIndex(VolumeCap.NONE, 15))
        assertEquals(8, VolumeCap.fromIndex(7, 15)); assertEquals(7, VolumeCap.toIndex(8, 15))
    }

    @Test fun stepsOneAtATimeWithinZeroMaxAndTheCap() {
        assertEquals(6, VolumeCap.step(5, 15, VolumeCap.NONE, up = true))
        assertEquals(4, VolumeCap.step(5, 15, VolumeCap.NONE, up = false))
        assertEquals(15, VolumeCap.step(15, 15, VolumeCap.NONE, up = true))
        assertEquals(0, VolumeCap.step(0, 15, VolumeCap.NONE, up = false))
        assertEquals(8, VolumeCap.step(7, 15, 8, up = true))
        assertEquals(8, VolumeCap.step(8, 15, 8, up = true))
        assertEquals(10, VolumeCap.step(10, 15, 8, up = true))
        assertEquals(9, VolumeCap.step(10, 15, 8, up = false))
    }
}
