// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import app.studiopocket.player.library.LoadedPlaylist

/**
 * What a child watches while a playlist plays (ported from the author's earlier Y1 player): the track's 16x16
 * pixel icon blown up to 240x240 with no filtering, the title, the track counter and clock, and a
 * progress bar, on a background tinted towards the playlist artwork. Track changes crossfade the
 * icon; a long title scrolls while playing and parks when paused.
 */
class NowPlayingScreen : Screen(), Artwork.Listener, PixelIcon.Listener {
    enum class Status { PLAYING, PAUSED, PREPARING, STOPPED }

    class State(
        val playlist: LoadedPlaylist?,
        val trackIndex: Int,
        val title: String,
        val positionMs: Long,
        val durationMs: Long,
        val status: Status,
        val error: String?,
        val sleepMinutesLeft: Int? = null,
        val batteryPercent: Int = -1,
        val charging: Boolean = false,
    )

    private val iconPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val ink = Ui.fill(Ui.ink)
    private val dim = Ui.fill(Ui.inkDim)
    private val accent = Ui.fill(Ui.accent)
    private val titlePaint = Ui.text(TITLE_SIZE, Ui.ink)
    private val linePaint = Ui.text(Ui.TEXT, Ui.inkDim)
    private val warnPaint = Ui.text(Ui.TEXT, Ui.accent)
    private val rect = RectF()

    private var state = State(null, 0, "", 0L, 0L, Status.STOPPED, null)
    private var background = Ui.bg
    private var trackKey: String? = null
    private var shown: Bitmap? = null
    private var previous: Bitmap? = null
    private var crossStart = 0L
    private var titleWidth = 0f
    private var titleStart = 0L

    override fun onShow() { Artwork.addListener(this); PixelIcon.addListener(this); invalidate() }
    override fun onHide() { Artwork.removeListener(this); PixelIcon.removeListener(this) }
    override fun onArtworkDecoded(id: String) { if (id == state.playlist?.id) { background = tint(); invalidate() } }
    override fun onIconDecoded(path: String) = invalidate()

    fun update(next: State) {
        val key = next.playlist?.let { "${it.id}/${next.trackIndex}" }
        if (key != trackKey) {
            trackKey = key
            previous = shown
            crossStart = if (previous == null) 0L else SystemClock.uptimeMillis()
            titleWidth = titlePaint.measureText(next.title)
            titleStart = SystemClock.uptimeMillis()
        } else if (next.status == Status.PLAYING && state.status != Status.PLAYING) {
            titleStart = SystemClock.uptimeMillis()
        }
        state = next
        background = tint()
        invalidate()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        c.drawColor(background)
        val s = state
        val now = SystemClock.uptimeMillis()
        val dimmed = s.status != Status.PLAYING
        val full = if (dimmed) DIM_ALPHA else 255
        var fade = if (crossStart == 0L) 1f else (now - crossStart) / CROSSFADE_MS.toFloat()
        if (fade >= 1f) { crossStart = 0L; previous = null; fade = 1f }
        previous?.let { icon(c, it, ((1f - fade) * full).toInt()) }
        val icon = PixelIcon.bitmap(s.playlist?.trackIconFile(s.trackIndex))
        if (icon != null) { icon(c, icon, (fade * full).toInt()); shown = icon }
        if (s.status == Status.PAUSED) pauseGlyph(c)

        // Title: ellipsised at rest; while playing, a long one scrolls once per cycle.
        val maxW = w - 2 * Ui.MARGIN
        val scrolling = titleWidth > maxW && s.status == Status.PLAYING
        if (!scrolling) {
            val t = Ui.ellipsize(titlePaint, s.title, maxW)
            c.drawText(t, (w - titlePaint.measureText(t)) / 2f, TITLE_BASELINE, titlePaint)
        } else {
            val cycle = MARQUEE_PAUSE_MS + ((titleWidth - maxW) / MARQUEE_PX_PER_MS) + MARQUEE_PAUSE_MS
            val t = ((now - titleStart) % cycle.toLong()).toFloat()
            val offset = ((t - MARQUEE_PAUSE_MS) * MARQUEE_PX_PER_MS).coerceIn(0f, titleWidth - maxW)
            val save = c.save()
            c.clipRect(Ui.MARGIN, TITLE_BASELINE - TITLE_SIZE, w - Ui.MARGIN, TITLE_BASELINE + 8f)
            c.drawText(s.title, Ui.MARGIN - offset, TITLE_BASELINE, titlePaint)
            c.restoreToCount(save)
        }

        val total = s.playlist?.manifest?.tracks?.size ?: 0
        val duration = if (s.durationMs > 0) s.durationMs else (s.playlist?.manifest?.tracks?.getOrNull(s.trackIndex)?.durationMs ?: 0L)
        val line = when {
            s.error != null -> s.error
            s.status == Status.STOPPED -> "Done"
            s.status == Status.PREPARING -> "Loading…"
            else -> "${s.trackIndex + 1} / $total    ${clock(s.positionMs)} / ${clock(duration)}"
        }
        // Low battery replaces the track line with the warning.
        if (LowBattery.warn(s.batteryPercent, s.charging)) Ui.centred(c, w, LowBattery.TEXT, warnPaint, LINE_BASELINE)
        else c.drawText(line, (w - linePaint.measureText(line)) / 2f, LINE_BASELINE, linePaint)

        s.sleepMinutesLeft?.let {
            val label = "Sleep ${it}m"
            c.drawText(label, w - Ui.MARGIN - linePaint.measureText(label), CORNER_BASELINE, linePaint)
        }

        dim.alpha = TRACK_ALPHA
        c.drawRect(BAR_MARGIN, BAR_Y, w - BAR_MARGIN, BAR_Y + BAR_H, dim)
        val fraction = if (duration > 0) (s.positionMs / duration.toFloat()).coerceIn(0f, 1f) else 0f
        if (fraction > 0f) c.drawRect(BAR_MARGIN, BAR_Y, BAR_MARGIN + (w - 2 * BAR_MARGIN) * fraction, BAR_Y + BAR_H, accent)

        if (crossStart != 0L || scrolling) invalidateDelayed(FRAME_MS)
    }

    private fun icon(c: Canvas, b: Bitmap, alpha: Int) {
        if (alpha <= 0) return
        iconPaint.alpha = alpha
        rect.set(ICON_LEFT, ICON_TOP, ICON_LEFT + ICON_SIZE, ICON_TOP + ICON_SIZE)
        c.drawBitmap(b, null, rect, iconPaint)
    }

    private fun pauseGlyph(c: Canvas) {
        ink.alpha = 255
        val mid = ICON_LEFT + ICON_SIZE / 2f
        val top = ICON_TOP + (ICON_SIZE - PAUSE_H) / 2f
        c.drawRect(mid - PAUSE_GAP / 2f - PAUSE_W, top, mid - PAUSE_GAP / 2f, top + PAUSE_H, ink)
        c.drawRect(mid + PAUSE_GAP / 2f, top, mid + PAUSE_GAP / 2f + PAUSE_W, top + PAUSE_H, ink)
    }

    private fun tint(): Int {
        val p = state.playlist ?: return Ui.bg
        Artwork.bitmap(p)
        val average = Artwork.averageColour(p)
        return if (average == Artwork.NO_COLOUR) Ui.bg else Ui.blend(Ui.bg, average, TINT)
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    private companion object {
        const val FRAME_MS = 16L
        const val CROSSFADE_MS = 200L
        const val DIM_ALPHA = 128
        const val TINT = 0.25f
        const val ICON_SIZE = 240f
        const val ICON_LEFT = (Ui.W - ICON_SIZE) / 2f
        const val ICON_TOP = 18f
        const val PAUSE_W = 28f
        const val PAUSE_H = 90f
        const val PAUSE_GAP = 22f
        const val TITLE_SIZE = 24f
        const val TITLE_BASELINE = 296f
        const val LINE_BASELINE = 326f
        const val CORNER_BASELINE = Ui.MARGIN + Ui.TEXT // top-right; the icon is centred, so the corner is free
        const val BAR_MARGIN = 12f
        const val BAR_Y = Ui.H - 10f
        const val BAR_H = 4f
        const val TRACK_ALPHA = 90
        const val MARQUEE_PAUSE_MS = 1200f
        const val MARQUEE_PX_PER_MS = 0.04f
    }
}
