// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

import org.junit.Assert.assertEquals
import org.junit.Test

class EventLogTest {
    @Test
    fun keepsOnlyTheNewestEntriesNewestFirst() {
        val log = EventLog(capacity = 3)
        listOf("a", "b", "c", "d").forEach(log::add)
        assertEquals(listOf("d", "c", "b"), log.snapshot())
    }

    @Test
    fun notifiesListenersOnAdd() {
        val log = EventLog()
        var calls = 0
        val listener = { calls++; Unit }
        log.addListener(listener)
        log.add("x")
        log.removeListener(listener)
        log.add("y")
        assertEquals(1, calls)
    }
}
