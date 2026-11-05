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
}
