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
 * The groups screen: one stacked tile per group (Recent, the user's groups, All), the selected one
 * centred with its neighbours scaled down and dimmed at either side, sliding with
 * [LibraryScreen]'s easing. Once the selection has rested for [DWELL_MS] its stack fans open over
 * [OPEN_MS]; moving away closes it over [CLOSE_MS]. Selection lives in [PlayerNav]; the activity
 * calls [select] after every key. Nothing decodes in [draw]: icons come from [PixelIcon] and
 * covers and group pictures from [CoverThumbs], both on the background decoder.
 */
class GroupsScreen : Screen(), PixelIcon.Listener, CoverThumbs.Listener {
    class State(
        val groups: GroupList,
        val library: LoadedLibrary,
        val storageAvailable: Boolean,
        val playingTitle: String?,
        val playing: Boolean,
        val batteryPercent: Int,
        val charging: Boolean,
        /** The clock top left ("9:36"); null hides it. */
        val clock: String? = null,
    )

    private val tiles = GroupRenderer()
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
    /** When the selection came (or will come) to rest; the fan opens [DWELL_MS] after it. */
    private var restStart = 0L
    /** The tile last fanned, closing from [closeFrom] since [closeStart]; -1 when none is closing. */
    private var closing = -1
    private var closeFrom = 0f
    private var closeStart = 0L

    override fun onShow() {
        PixelIcon.addListener(this); CoverThumbs.addListener(this)
        restStart = SystemClock.uptimeMillis(); closing = -1
        invalidate()
    }
    override fun onHide() { PixelIcon.removeListener(this); CoverThumbs.removeListener(this) }
    override fun onIconDecoded(path: String) = invalidate()
    override fun onThumbDecoded(path: String) = invalidate()

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
        val now = SystemClock.uptimeMillis()
        if (!placed) { placed = true; selected = target; animStart = 0L; restStart = now; invalidate(); return }
        if (target == selected) return
        val was = fan(now)
        if (was > 0f) { closing = selected; closeFrom = was; closeStart = now }
        fromPos = position()
        selected = target
        animStart = now
        restStart = now + LibraryScreen.SLIDE_MS
        invalidate()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        c.drawColor(Ui.bg)
        val s = state
        if (s.batteryPercent >= 0) cards.battery(c, w, s.batteryPercent, s.charging)
        cards.clock(c, s.clock)
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
            drawTile(c, i, pos)
        }
        val hidden = s.library.hidden.size
        if (s.library.syncInProgress) Ui.centred(c, w, "Sync in progress – unplug when the computer says it is done", footer, h - LibraryScreen.PROBLEM_BASELINE)
        else if (LowBattery.warn(s.batteryPercent, s.charging)) Ui.centred(c, w, LowBattery.TEXT, footer, h - LibraryScreen.PROBLEM_BASELINE)
        else if (hidden > 0) Ui.centred(c, w, if (hidden == 1) "1 playlist could not be read" else "$hidden playlists could not be read", footer, h - LibraryScreen.PROBLEM_BASELINE)
        s.playingTitle?.let { cards.pill(c, w, it, s.playing) }
        scheduleNext()
    }

    /** The next frame: every frame while sliding, fanning or closing, else once when the dwell ends. */
    private fun scheduleNext() {
        val now = SystemClock.uptimeMillis()
        if (animStart != 0L || closeAmount(now) > 0f) { invalidateDelayed(LibraryScreen.FRAME_MS); return }
        if (!canFan(selected)) return
        val rested = now - restStart
        if (rested < DWELL_MS) invalidateDelayed(maxOf(LibraryScreen.FRAME_MS, DWELL_MS - rested))
        else if (rested < DWELL_MS + OPEN_MS) invalidateDelayed(LibraryScreen.FRAME_MS)
    }

    /** How far the selected tile is fanned, 0..1: shut while sliding and during the dwell. */
    private fun fan(now: Long): Float {
        if (animStart != 0L || !canFan(selected)) return 0f
        val t = (now - restStart - DWELL_MS) / OPEN_MS.toFloat()
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val u = 1f - t
        return 1f - u * u * u
    }

    private fun closeAmount(now: Long): Float {
        if (closing < 0) return 0f
        val t = (now - closeStart) / CLOSE_MS.toFloat()
        if (t >= 1f || t < 0f) { closing = -1; return 0f }
        return closeFrom * (1f - t)
    }

    /** Only a stack fans; a single card (an icon or picture on its own) and an empty Recent stay still. */
    private fun canFan(index: Int) = index in groups.indices && groups[index].faces.size > 1 && !isInert(groups[index])

    private fun drawTile(c: Canvas, index: Int, pos: Float) {
        val g = groups[index]
        val distance = index - pos
        val depth = Math.min(1f, Math.abs(distance))
        val cx = Ui.W / 2f + distance * STEP
        val scale = 1f - depth * (1f - NEIGHBOUR_SCALE)
        val alpha = Math.round(255f - depth * (255 - LibraryScreen.NEIGHBOUR_ALPHA))
        val save = c.save()
        c.scale(scale, scale, cx, GroupRenderer.CARD_MID_Y)
        val isSelected = index == selected
        val now = SystemClock.uptimeMillis()
        val open = when (index) {
            selected -> fan(now)
            closing -> closeAmount(now)
            else -> 0f
        }
        tiles.tile(c, g, cx, alpha, isSelected, isInert(g), if (isSelected) subtitles[index] else null, open)
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
        const val DWELL_MS = 500L
        const val OPEN_MS = 180L
        const val CLOSE_MS = 150L
    }
}
