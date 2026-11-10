// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

import java.util.Random

/**
 * Shuffle without a stored order: only the position is saved, as in every other mode, and each
 * next track is picked at random when it is needed. In memory it avoids tracks already played this
 * round until every track has played, then starts a new round, and keeps the tracks played so
 * Previous can go back through them. Both are lost when the service restarts, which only means a
 * track may come round again sooner. Shuffle never ends on its own. Pure apart from [random], so it
 * is unit-tested on the JVM.
 */
class ShuffleOrder(private val random: Random = Random()) {
    private val played = HashSet<Int>()
    private val history = ArrayList<Int>()

    fun reset() {
        played.clear()
        history.clear()
    }

    /** The track after [current] of [size]: a random one not yet played this round (never [current] itself while there is another). */
    fun next(current: Int, size: Int): Int {
        if (size <= 1) return 0
        played.add(current)
        var pool = (0 until size).filter { it !in played }
        if (pool.isEmpty()) {
            played.clear()
            played.add(current)
            pool = (0 until size).filter { it != current }
        }
        remember(current)
        return pool[random.nextInt(pool.size)]
    }

    /** [from] was left for a track chosen by hand (the ring and centre), so Previous comes back to it. */
    fun moved(from: Int) {
        played.add(from)
        remember(from)
    }

    /** The track played before the current one, or null when there is none to go back to. */
    fun previous(): Int? = if (history.isEmpty()) null else history.removeAt(history.lastIndex)

    private fun remember(index: Int) {
        history.add(index)
        if (history.size > MAX_HISTORY) history.removeAt(0)
    }

    companion object {
        const val MAX_HISTORY = 500
    }
}
