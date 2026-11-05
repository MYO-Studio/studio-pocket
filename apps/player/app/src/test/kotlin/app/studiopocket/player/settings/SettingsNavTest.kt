// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavTest {
    private val three = listOf(SettingsRow("a", "A"), SettingsRow("b", "B"), SettingsRow("c", "C"))

    @Test fun movesAndStopsAtEnds() {
        val nav = SettingsNav()
        assertEquals(SettingsNav.Action.None, nav.onKey("KEYCODE_DPAD_UP", three))
        assertEquals(0, nav.selected)
        assertEquals(SettingsNav.Action.Redraw, nav.onKey("KEYCODE_DPAD_DOWN", three))
        assertEquals(SettingsNav.Action.Redraw, nav.onKey("KEYCODE_DPAD_RIGHT", three))
        assertEquals(2, nav.selected)
        assertEquals(SettingsNav.Action.None, nav.onKey("KEYCODE_DPAD_DOWN", three))
        assertEquals(2, nav.selected)
        assertEquals(SettingsNav.Action.Redraw, nav.onKey("KEYCODE_DPAD_LEFT", three))
        assertEquals(1, nav.selected)
    }

    @Test fun centreOpensActionRowsOnly() {
        val rows = listOf(SettingsRow("open", "Open"), SettingsRow("off", "Off", enabled = false))
        val nav = SettingsNav()
        assertEquals(SettingsNav.Action.Open("open"), nav.onKey("KEYCODE_ENTER", rows))
        nav.onKey("KEYCODE_DPAD_DOWN", rows)
        assertEquals(SettingsNav.Action.None, nav.onKey("KEYCODE_DPAD_CENTER", rows))
        assertFalse(nav.editing)
    }

    @Test fun editPreviewsThenCommitsOnCentre() {
        val rows = listOf(SettingsRow("bright", "Brightness", RowValue(2, listOf("1", "2", "3", "4", "5"))))
        val nav = SettingsNav()
        assertEquals(SettingsNav.Action.Redraw, nav.onKey("KEYCODE_ENTER", rows))
        assertTrue(nav.editing)
        assertEquals(2, nav.editIndex)
        assertEquals(SettingsNav.Action.Preview("bright", 3), nav.onKey("KEYCODE_DPAD_DOWN", rows))
        assertEquals(SettingsNav.Action.Commit("bright", 3), nav.onKey("KEYCODE_ENTER", rows))
        assertFalse(nav.editing)
    }

    @Test fun backWhileEditingCommits() {
        val rows = listOf(SettingsRow("v", "V", RowValue(0, listOf("x", "y"))))
        val nav = SettingsNav()
        nav.onKey("KEYCODE_ENTER", rows)
        nav.onKey("KEYCODE_DPAD_RIGHT", rows)
        assertEquals(SettingsNav.Action.Commit("v", 1), nav.onKey("KEYCODE_BACK", rows))
        assertFalse(nav.editing)
    }

    @Test fun editStopsAtLabelEnds() {
        val rows = listOf(SettingsRow("v", "V", RowValue(0, listOf("x", "y"))))
        val nav = SettingsNav()
        nav.onKey("KEYCODE_ENTER", rows)
        assertEquals(SettingsNav.Action.None, nav.onKey("KEYCODE_DPAD_UP", rows))
        assertEquals(0, nav.editIndex)
        assertEquals(SettingsNav.Action.Preview("v", 1), nav.onKey("KEYCODE_DPAD_DOWN", rows))
        assertEquals(SettingsNav.Action.None, nav.onKey("KEYCODE_DPAD_DOWN", rows))
        assertEquals(1, nav.editIndex)
    }

    @Test fun selectionClampsWhenRowsShrink() {
        val nav = SettingsNav()
        nav.onKey("KEYCODE_DPAD_DOWN", three)
        nav.onKey("KEYCODE_DPAD_DOWN", three)
        assertEquals(2, nav.selected)
        val fewer = three.take(1)
        assertEquals(SettingsNav.Action.Open("a"), nav.onKey("KEYCODE_ENTER", fewer))
        assertEquals(0, nav.selected)
    }

    @Test fun backOutsideEditingIsBack() {
        val nav = SettingsNav()
        assertEquals(SettingsNav.Action.Back, nav.onKey("KEYCODE_BACK", three))
        assertEquals(SettingsNav.Action.None, nav.onKey("KEYCODE_VOLUME_UP", three))
    }
}
