// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Canvas
import android.os.SystemClock
import app.studiopocket.player.library.GroupList
import app.studiopocket.player.library.LoadedLibrary
import app.studiopocket.player.library.PlaylistGroup

/**
 * The groups screen: one square per group (Recent, the user's groups, All), the selected one
 * centred with its neighbours scaled down and dimmed at either side, sliding with
 * [LibraryScreen]'s easing. Selection lives in [PlayerNav]; the activity calls [select] after
 * every key. Nothing decodes in [draw]: group icons come from [PixelIcon]'s background decoder.
 */
class GroupsScreen : Screen(), PixelIcon.Listener {
    class State(
        val groups: GroupList,
        val library: LoadedLibrary,
        val storageAvailable: Boolean,
        val playingTitle: String?,
        val playing: Boolean,
        val batteryPercent: Int,
        val charging: Boolean,
    )

    private val squares = GroupRenderer()
    private val cards = CardRenderer()
    private val emptyTitle = Ui.text(LibraryScreen.EMPTY_SIZE, Ui.ink, bold = true)
    private val emptySub = Ui.text(Ui.TEXT, Ui.inkDim)
    private val footer = Ui.text(Ui.TEXT, Ui.inkDim)

    private var state = State(GroupList.EMPTY, LoadedLibrary.empty(null), true, null, false, -1, false)
    private var groups: List<PlaylistGroup> = emptyList()
    /** The second line under each square, made on update so draw() allocates nothing. */
    private var subtitles: Array<String> = emptyArray()
    private var selected = 0
    private var placed = false
    private var fromPos = 0f
    private var animStart = 0L

    override fun onShow() { PixelIcon.addListener(this); invalidate() }
    override fun onHide() { PixelIcon.removeListener(this) }
    override fun onIconDecoded(path: String) = invalidate()

    fun update(next: State) {
        state = next
        groups = next.groups.groups
        subtitles = Array(groups.size) { i -> subtitle(groups[i]) }
        if (selected >= groups.size) selected = maxOf(0, groups.size - 1)
        invalidate()
    }

    /** Moves to square [index], sliding from wherever the strip is; the first placement jumps. */
    fun select(index: Int) {
        val target = index.coerceIn(0, maxOf(0, groups.size - 1))
        if (!placed) { placed = true; selected = target; animStart = 0L; invalidate(); return }
        if (target == selected) return
        fromPos = position()
        selected = target
        animStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        c.drawColor(Ui.bg)
        val s = state
        if (s.batteryPercent >= 0) cards.battery(c, w, s.batteryPercent, s.charging)
        if (!s.storageAvailable) {
            Ui.centred(c, w, "Connected to computer", emptyTitle, h / 2f)
            Ui.centred(c, w, "Unplug the cable to play", emptySub, h / 2f + LibraryScreen.EMPTY_GAP)
            return
        }
        val pos = position()
        val first = maxOf(0, Math.floor(pos.toDouble()).toInt() - 1)
        val last = minOf(groups.size - 1, Math.ceil(pos.toDouble()).toInt() + 1)
        var low = first
        var high = last
        while (low <= high) {
            val i = if (Math.abs(low - pos) >= Math.abs(high - pos)) low++ else high--
            drawSquare(c, i, pos)
        }
        val hidden = s.library.hidden.size
        if (s.library.syncInProgress) Ui.centred(c, w, "Sync in progress – unplug when the computer says it is done", footer, h - LibraryScreen.PROBLEM_BASELINE)
        else if (LowBattery.warn(s.batteryPercent, s.charging)) Ui.centred(c, w, LowBattery.TEXT, footer, h - LibraryScreen.PROBLEM_BASELINE)
        else if (hidden > 0) Ui.centred(c, w, if (hidden == 1) "1 playlist could not be read" else "$hidden playlists could not be read", footer, h - LibraryScreen.PROBLEM_BASELINE)
        s.playingTitle?.let { cards.pill(c, w, it, s.playing) }
        if (animStart != 0L) invalidateDelayed(LibraryScreen.FRAME_MS)
    }

    private fun drawSquare(c: Canvas, index: Int, pos: Float) {
        val g = groups[index]
        val distance = index - pos
        val depth = Math.min(1f, Math.abs(distance))
        val left = GroupRenderer.SQUARE_LEFT + distance * STEP
        val scale = 1f - depth * (1f - NEIGHBOUR_SCALE)
        val alpha = Math.round(255f - depth * (255 - LibraryScreen.NEIGHBOUR_ALPHA))
        val save = c.save()
        c.scale(scale, scale, left + GroupRenderer.SQUARE / 2f, GroupRenderer.SQUARE_TOP + GroupRenderer.SQUARE / 2f)
        val isSelected = index == selected
        squares.square(c, g, left, alpha, isSelected, isInert(g), if (isSelected) subtitles[index] else null)
        c.restoreToCount(save)
    }

    private fun isInert(g: PlaylistGroup) = g.kind == PlaylistGroup.Kind.RECENT && g.playlists.isEmpty()

    private fun subtitle(g: PlaylistGroup): String = when {
        isInert(g) -> "Nothing yet"
        g.playlists.size == 1 -> "1 playlist"
        else -> "${g.playlists.size} playlists"
    }

    private fun position(): Float {
        if (animStart == 0L) return selected.toFloat()
        val elapsed = SystemClock.uptimeMillis() - animStart
        if (elapsed >= LibraryScreen.SLIDE_MS || elapsed < 0L) { animStart = 0L; return selected.toFloat() }
        val u = 1f - elapsed / LibraryScreen.SLIDE_MS.toFloat()
        val eased = 1f - u * u * u
        return fromPos + (selected - fromPos) * eased
    }

    companion object {
        const val STEP = 196f
        const val NEIGHBOUR_SCALE = 0.82f
    }
}
