// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class ShuffleOrderTest {
    @Test fun playsEveryTrackOnceARoundBeforeRepeatingAny() {
        val s = ShuffleOrder(Random(7))
        var current = 3
        val seen = mutableListOf(current)
        repeat(9) { current = s.next(current, 10); seen += current }
        assertEquals((0 until 10).toSet(), seen.toSet())
        val nextRound = s.next(current, 10)
        assertNotEquals(current, nextRound)
    }

    @Test fun neverPicksTheCurrentTrackWhileThereIsAnother() {
        val s = ShuffleOrder(Random(1))
        var current = 0
        repeat(50) { val n = s.next(current, 2); assertNotEquals(current, n); current = n }
        assertEquals(0, ShuffleOrder().next(0, 1))
    }

    @Test fun previousGoesBackThroughWhatPlayed() {
        val s = ShuffleOrder(Random(3))
        val a = 2
        val b = s.next(a, 6)
        s.next(b, 6)
        assertEquals(b, s.previous())
        assertEquals(a, s.previous())
        assertNull(s.previous())
    }

    @Test fun aTrackChosenByHandIsRememberedForPrevious() {
        val s = ShuffleOrder(Random(3))
        s.moved(4)
        assertEquals(4, s.previous())
    }

    @Test fun resetForgetsTheRound() {
        val s = ShuffleOrder(Random(3))
        s.next(1, 4)
        s.reset()
        assertNull(s.previous())
    }
}
