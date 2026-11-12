// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

/**
 * The parent-set volume cap, in steps of the music stream (15 on the Y2). [NONE] means no cap, and
 * then nothing here ever asks for a volume change. Pure, so it is unit-tested on the JVM.
 */
object VolumeCap {
    const val NONE = -1

    /** The volume to set: the cap when [current] is above it, else null (leave it alone). */
    fun target(current: Int, cap: Int): Int? = if (cap != NONE && cap >= 0 && current > cap) cap else null

    /** Whether Volume Up should be swallowed: already at (or above) the cap. */
    fun blocksUp(current: Int, cap: Int): Boolean = cap != NONE && cap >= 0 && current >= cap

    /**
     * The level one Volume key press moves to: one step [up] or down from [current], never below 0,
     * above [max] or (going up) above the cap. Already above the cap, up leaves it where it is.
     */
    fun step(current: Int, max: Int, cap: Int, up: Boolean): Int {
        if (!up) return maxOf(0, current - 1)
        val ceiling = if (cap != NONE && cap >= 0) minOf(cap, max) else max
        return if (current >= ceiling) current else current + 1
    }

    /** "1" .. "max-1", then "No limit" (a cap at max would be no limit). */
    fun labels(max: Int): List<String> = (1 until max).map { it.toString() } + "No limit"

    /** The row index for [cap]; no cap, or a cap at or above [max], is the last ("No limit"). */
    fun toIndex(cap: Int, max: Int): Int {
        val last = maxOf(0, max - 1)
        return if (cap == NONE || cap >= max) last else (cap - 1).coerceIn(0, last)
    }

    /** The cap for row [index]; the last index is [NONE]. */
    fun fromIndex(index: Int, max: Int): Int = if (index >= max - 1) NONE else maxOf(1, index + 1)
}
