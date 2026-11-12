// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import app.studiopocket.player.library.PlayMode

/**
 * The play-mode menu over Now Playing (hold Centre): Normal, Repeat all, Repeat one, Shuffle, for
 * the loaded playlist only. Opens on the playlist's current mode; the ring and arrows move (stopping
 * at either end), Centre picks, Back closes without a change. Pure, so it is unit-tested on the JVM;
 * [ModeMenuOverlay] draws it.
 */
class ModeMenu {
    sealed class Result {
        object Ignored : Result()
        object Moved : Result()
        data class Chose(val mode: PlayMode) : Result()
        object Closed : Result()
    }

    var isOpen = false; private set
    /** The playlist's mode when the menu opened (it carries the tick). */
    var current = PlayMode.NORMAL; private set
    var selected = 0; private set

    fun open(current: PlayMode) {
        this.current = current
        selected = OPTIONS.indexOf(current).coerceAtLeast(0)
        isOpen = true
    }

    fun close() { isOpen = false }

    /** One key down while open. */
    fun onKey(keyName: String): Result {
        if (!isOpen) return Result.Ignored
        return when (keyName) {
            "KEYCODE_DPAD_UP", "KEYCODE_DPAD_LEFT" -> move(-1)
            "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_RIGHT" -> move(1)
            "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER" -> { isOpen = false; Result.Chose(OPTIONS[selected]) }
            "KEYCODE_BACK" -> { isOpen = false; Result.Closed }
            else -> Result.Ignored
        }
    }

    private fun move(delta: Int): Result {
        val target = (selected + delta).coerceIn(0, OPTIONS.lastIndex)
        if (target == selected) return Result.Ignored
        selected = target
        return Result.Moved
    }

    companion object {
        val OPTIONS = listOf(PlayMode.NORMAL, PlayMode.REPEAT_ALL, PlayMode.REPEAT_ONE, PlayMode.SHUFFLE)

        fun label(mode: PlayMode): String = when (mode) {
            PlayMode.NORMAL -> "Normal"
            PlayMode.REPEAT_ALL -> "Repeat all"
            PlayMode.REPEAT_ONE -> "Repeat one"
            PlayMode.SHUFFLE -> "Shuffle"
        }
    }
}
