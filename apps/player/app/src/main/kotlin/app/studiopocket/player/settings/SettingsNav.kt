// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

/** One line of the settings list. A row with a [value] is edited in place; one without is an action. */
data class SettingsRow(val id: String, val label: String, val value: RowValue? = null, val enabled: Boolean = true)

/** A value row picks one of [labels]; [index] is the current choice. */
data class RowValue(val index: Int, val labels: List<String>)

/**
 * Pure navigation model for the settings list, driven by key names like [app.studiopocket.player.ui.PlayerNav].
 * Ring/arrows move the selection (stopping at the ends); Centre opens an action row or starts editing a
 * value row; while editing, ring/arrows change the value (previewed) and Centre or Back commits it.
 * Free of Android types so it is unit-tested on the JVM.
 */
class SettingsNav {
    sealed class Action {
        object None : Action()
        object Redraw : Action()
        data class Open(val rowId: String) : Action()
        data class Preview(val rowId: String, val index: Int) : Action()
        data class Commit(val rowId: String, val index: Int) : Action()
        object Back : Action()
    }

    var selected = 0
    var editing = false
    var editIndex = 0

    fun onKey(keyName: String, rows: List<SettingsRow>): Action {
        selected = selected.coerceIn(0, maxOf(0, rows.lastIndex))
        val row = rows.getOrNull(selected)
        val delta = when (keyName) {
            "KEYCODE_DPAD_UP", "KEYCODE_DPAD_LEFT" -> -1
            "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_RIGHT" -> 1
            else -> 0
        }
        val value = row?.value
        if (editing) {
            if (row == null || value == null) { editing = false; return Action.None }
            if (delta != 0) {
                val target = (editIndex + delta).coerceIn(0, maxOf(0, value.labels.lastIndex))
                if (target == editIndex) return Action.None
                editIndex = target
                return Action.Preview(row.id, target)
            }
            return when (keyName) {
                "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_BACK" -> { editing = false; Action.Commit(row.id, editIndex) }
                else -> Action.None
            }
        }
        if (delta != 0) {
            val target = (selected + delta).coerceIn(0, maxOf(0, rows.lastIndex))
            if (target == selected) return Action.None
            selected = target
            return Action.Redraw
        }
        return when (keyName) {
            "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER" -> when {
                row == null || !row.enabled -> Action.None
                value != null -> { editing = true; editIndex = value.index; Action.Redraw }
                else -> Action.Open(row.id)
            }
            "KEYCODE_BACK" -> Action.Back
            else -> Action.None
        }
    }

    fun reset() { selected = 0; editing = false; editIndex = 0 }
}
