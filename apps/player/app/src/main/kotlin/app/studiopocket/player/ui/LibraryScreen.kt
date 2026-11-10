// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import app.studiopocket.player.library.CoverProgress
import app.studiopocket.player.library.LoadedLibrary
import app.studiopocket.player.library.LoadedPlaylist

/**
 * The cover flow (ported from the library screen of the author's earlier Y1 player): one playlist card at a time at the
 * Yoto card ratio, the neighbours fanned out at either edge, the ring sliding between them. It
 * shows one group's playlists; when groups are on, the group's name sits small at the top left.
 * Selection lives in [app.studiopocket.player.ui.PlayerNav]; the activity calls [select] after every key.
 *
 * Nothing decodes in [draw]: covers come from [Artwork]'s background decoder.
 */
class LibraryScreen : Screen(), Artwork.Listener {
    /** What the screen shows; set by the activity whenever any of it changes. */
    class State(
        val library: LoadedLibrary,
        /** The open group's playlists (all of them in flat mode). */
        val playlists: List<LoadedPlaylist>,
        /** Identifies the open group, so opening another jumps instead of sliding. */
        val groupKey: String,
        /** "‹ Bedtime", or null in flat mode. */
        val groupLabel: String?,
        val storageAvailable: Boolean,
        val playingTitle: String?,
        val playing: Boolean,
        val batteryPercent: Int,
        val charging: Boolean,
        /** The clock top left ("9:36"); null hides it. */
        val clock: String? = null,
        /** Each playlist's cover bar (by id): how far through it is, or finished; absent shows no bar. */
        val progress: Map<String, CoverProgress> = emptyMap(),
    )

    private val cards = CardRenderer()
    private val emptyTitle = Ui.text(EMPTY_SIZE, Ui.ink, bold = true)
    private val emptySub = Ui.text(Ui.TEXT, Ui.inkDim)
    private val footer = Ui.text(Ui.TEXT, Ui.inkDim)

    private var state = State(LoadedLibrary.empty(null), emptyList(), "", null, true, null, false, -1, false)
    private var playlists: List<LoadedPlaylist> = emptyList()
    private var selected = 0
    private var jump = true
    private var fromPos = 0f
    private var animStart = 0L

    override fun onShow() { Artwork.addListener(this); invalidate() }
    override fun onHide() { Artwork.removeListener(this) }
    override fun onArtworkDecoded(id: String) = invalidate()

    fun update(next: State) {
        if (next.groupKey != state.groupKey) jump = true
        state = next
        playlists = next.playlists
        if (selected > lastIndex()) selected = lastIndex()
        invalidate()
    }

    /** Moves the strip to [index] (clamped to the playlists; 0 when empty), sliding from wherever it is; a new group jumps. */
    fun select(index: Int) {
        val target = index.coerceIn(0, lastIndex())
        if (jump) { jump = false; selected = target; animStart = 0L; invalidate(); return }
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
            cards.clock(c, s.clock)
            Ui.centred(c, w, "Connected to computer", emptyTitle, h / 2f)
            Ui.centred(c, w, "Unplug the cable to play", emptySub, h / 2f + EMPTY_GAP)
            return
        }
        val count = playlists.size
        val pos = position()
        val first = maxOf(0, Math.floor(pos.toDouble()).toInt() - 1)
        val last = minOf(count - 1, Math.ceil(pos.toDouble()).toInt() + 1)
        var low = first
        var high = last
        while (low <= high) {
            val i = if (Math.abs(low - pos) >= Math.abs(high - pos)) low++ else high--
            drawCard(c, i, pos)
        }
        if (playlists.isEmpty()) drawEmptyHint(c, w)
        val hidden = s.library.hidden.size
        if (s.library.syncInProgress) Ui.centred(c, w, "Sync in progress – unplug when the computer says it is done", footer, h - PROBLEM_BASELINE)
        else if (LowBattery.warn(s.batteryPercent, s.charging)) Ui.centred(c, w, LowBattery.TEXT, footer, h - PROBLEM_BASELINE)
        else if (hidden > 0) Ui.centred(c, w, if (hidden == 1) "1 playlist could not be read" else "$hidden playlists could not be read", footer, h - PROBLEM_BASELINE)
        val pillLeft = s.playingTitle?.let { cards.pill(c, w, it, s.playing) }
        val labelX = cards.clock(c, s.clock)
        s.groupLabel?.let { cards.groupLabel(c, it, (pillLeft ?: GROUP_LABEL_MAX + Ui.MARGIN) - labelX - GROUP_LABEL_GAP, labelX) }
        if (animStart != 0L) invalidateDelayed(FRAME_MS)
    }

    private fun lastIndex() = maxOf(0, playlists.size - 1)

    private fun drawCard(c: Canvas, index: Int, pos: Float) {
        val distance = index - pos
        val depth = Math.min(1f, Math.abs(distance))
        val fan = distance.coerceIn(-1f, 1f)
        val left = CARD_LEFT + distance * STEP
        val pivotX = left + CARD_W / 2f
        val scale = 1f - depth * NEIGHBOUR_SCALE_DROP
        val alpha = Math.round(255f - depth * (255 - NEIGHBOUR_ALPHA))
        val save = c.save()
        c.rotate(fan * FAN_ANGLE, pivotX, CARD_BOTTOM)
        c.scale(scale, scale, pivotX, CARD_BOTTOM)
        cards.card(c, playlists[index], left, alpha, index == selected, state.progress[playlists[index].id])
        c.restoreToCount(save)
    }

    private fun drawEmptyHint(c: Canvas, w: Int) {
        val problem = state.library.problem
        Ui.centred(c, w, "No playlists yet", emptyTitle, EMPTY_TOP)
        Ui.centred(c, w, problem ?: "Add some with Studio Pocket on your computer", emptySub, EMPTY_TOP + EMPTY_GAP)
    }

    private fun position(): Float {
        if (animStart == 0L) return selected.toFloat()
        val elapsed = SystemClock.uptimeMillis() - animStart
        if (elapsed >= SLIDE_MS || elapsed < 0L) { animStart = 0L; return selected.toFloat() }
        val u = 1f - elapsed / SLIDE_MS.toFloat()
        val eased = 1f - u * u * u
        return fromPos + (selected - fromPos) * eased
    }

    companion object {
        const val CARD_TOP = 24f
        const val CARD_BOTTOM = Ui.H - CARD_TOP
        const val CARD_H = CARD_BOTTOM - CARD_TOP
        const val CARD_RATIO = 0.63f
        val CARD_W: Float = Math.round(CARD_H * CARD_RATIO).toFloat()
        val CARD_LEFT: Float = (Ui.W - CARD_W) / 2f
        const val STEP = 252f
        const val FAN_ANGLE = 7f
        const val NEIGHBOUR_SCALE_DROP = 0.08f
        const val NEIGHBOUR_ALPHA = 190
        const val SLIDE_MS = 220L
        const val FRAME_MS = 16L
        const val EMPTY_SIZE = 28f
        const val EMPTY_GAP = 30f
        const val EMPTY_TOP = 60f
        const val PROBLEM_BASELINE = 6f
        /** The group label stops short of the now-playing pill, or at this width when there is none. */
        const val GROUP_LABEL_MAX = 200f
        const val GROUP_LABEL_GAP = 8f
    }
}
