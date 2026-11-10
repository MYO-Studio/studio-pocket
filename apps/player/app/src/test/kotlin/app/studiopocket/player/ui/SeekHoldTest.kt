// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekHoldTest {
    private val minute = 60_000L

    @Test fun aShortPressSkipsOnRelease() {
        val h = SeekHold()
        h.press(1, 1_000, 30_000, 5 * minute)
        assertFalse(h.hold(1_400))
        assertFalse(h.scrubbing)
        assertEquals(SeekHold.Release.Skip(1), h.release())
        assertFalse(h.active)
    }

    @Test fun previousSkipsBackwards() {
        val h = SeekHold()
        h.press(-1, 0, 0, minute)
        assertEquals(SeekHold.Release.Skip(-1), h.release())
    }

    @Test fun aHoldScrubsForwardFromThePressAndSeeksOnRelease() {
        val h = SeekHold()
        h.press(1, 1_000, 30_000, 5 * minute)
        assertTrue(h.hold(1_000 + SeekHold.HOLD_MS))          // scrubbing starts, nothing moved yet
        assertTrue(h.scrubbing)
        assertEquals(30_000, h.previewMs)
        assertTrue(h.hold(1_000 + SeekHold.HOLD_MS + 1_000))  // 1 s at 10x
        assertEquals(40_000, h.previewMs)
        assertEquals(SeekHold.Release.Seek(40_000), h.release())
    }

    @Test fun aHoldScrubsBackwardsAndStopsAtTheStart() {
        val h = SeekHold()
        h.press(-1, 0, 15_000, 5 * minute)
        h.hold(SeekHold.HOLD_MS + 1_000)
        assertEquals(5_000, h.previewMs)
        h.hold(SeekHold.HOLD_MS + 3_000)
        assertEquals(0, h.previewMs)
        assertEquals(SeekHold.Release.Seek(0), h.release())
    }

    @Test fun forwardStopsShortOfTheEnd() {
        val h = SeekHold()
        h.press(1, 0, 50_000, minute)
        h.hold(SeekHold.HOLD_MS + 10_000)
        assertEquals(minute - SeekHold.END_GUARD_MS, h.previewMs)
    }

    @Test fun speedsUpTheLongerItIsHeld() {
        assertEquals(20_000, SeekHold.offsetMs(2_000))
        assertEquals(20_000 + 90_000, SeekHold.offsetMs(5_000))
        assertEquals(20_000 + 90_000 + 60_000, SeekHold.offsetMs(6_000))
        assertEquals(0, SeekHold.offsetMs(-5))
    }

    @Test fun withoutADurationAHoldStillSkips() {
        val h = SeekHold()
        h.press(1, 0, 0, 0)
        assertFalse(h.hold(5_000))
        assertEquals(SeekHold.Release.Skip(1), h.release())
    }

    @Test fun aCancelledPressDoesNothing() {
        val h = SeekHold()
        h.press(1, 0, 0, minute)
        h.hold(2_000)
        assertEquals(SeekHold.Release.None, h.release(canceled = true))
        assertEquals(SeekHold.Release.None, h.release())
    }

    @Test fun mapsTheArrowAndMediaKeys() {
        assertEquals(1, SeekHold.direction("KEYCODE_DPAD_RIGHT"))
        assertEquals(1, SeekHold.direction("KEYCODE_MEDIA_NEXT"))
        assertEquals(-1, SeekHold.direction("KEYCODE_DPAD_LEFT"))
        assertEquals(-1, SeekHold.direction("KEYCODE_MEDIA_PREVIOUS"))
        assertEquals(0, SeekHold.direction("KEYCODE_DPAD_UP"))
    }
}
