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
 * pixel icon blown up 8x with no filtering, the track title large with the playlist name dim beneath
 * it, elapsed and total time over a rounded progress bar, and "Track n of m" at the foot, on a
 * background tinted towards the playlist artwork. Battery top right, sleep timer top left. Track
 * changes crossfade the icon; a long title scrolls while playing and parks when paused.
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
    private val titlePaint = Ui.text(TITLE_SIZE, Ui.ink, bold = true)
    private val playlistPaint = Ui.text(PLAYLIST_SIZE, Ui.inkDim)
    private val timePaint = Ui.text(TIME_SIZE, Ui.inkDim)
    private val linePaint = Ui.text(Ui.TEXT, Ui.inkDim)
    private val warnPaint = Ui.text(Ui.TEXT, Ui.warn)
    private val rect = RectF()
    private val chrome = CardRenderer()

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
            c.clipRect(Ui.MARGIN, TITLE_BASELINE - TITLE_SIZE - 4f, w - Ui.MARGIN, TITLE_BASELINE + 10f)
            c.drawText(s.title, Ui.MARGIN - offset, TITLE_BASELINE, titlePaint)
            c.restoreToCount(save)
        }

        val maxText = w - 2 * BAR_MARGIN
        Ui.centred(c, w, Ui.ellipsize(playlistPaint, s.playlist?.title ?: "", maxText), playlistPaint, PLAYLIST_BASELINE)

        val total = s.playlist?.manifest?.tracks?.size ?: 0
        val duration = if (s.durationMs > 0) s.durationMs else (s.playlist?.manifest?.tracks?.getOrNull(s.trackIndex)?.durationMs ?: 0L)
        if (s.status == Status.PLAYING || s.status == Status.PAUSED) {
            c.drawText(clock(s.positionMs), BAR_MARGIN, TIME_BASELINE, timePaint)
            val end = clock(duration)
            c.drawText(end, w - BAR_MARGIN - timePaint.measureText(end), TIME_BASELINE, timePaint)
        }

        // Rounded track, rounded purple fill; the fill never draws narrower than its own height.
        dim.alpha = TRACK_ALPHA
        rect.set(BAR_MARGIN, BAR_Y, w - BAR_MARGIN, BAR_Y + BAR_H)
        c.drawRoundRect(rect, BAR_H / 2f, BAR_H / 2f, dim)
        val fraction = if (duration > 0) (s.positionMs / duration.toFloat()).coerceIn(0f, 1f) else 0f
        if (fraction > 0f) {
            rect.right = BAR_MARGIN + maxOf(BAR_H, (w - 2 * BAR_MARGIN) * fraction)
            c.drawRoundRect(rect, BAR_H / 2f, BAR_H / 2f, accent)
        }

        // Low battery replaces the track line with the warning.
        val line = when {
            s.error != null -> s.error
            s.status == Status.STOPPED -> "Done"
            s.status == Status.PREPARING -> "Loading…"
            else -> "Track ${s.trackIndex + 1} of $total"
        }
        if (LowBattery.warn(s.batteryPercent, s.charging)) Ui.centred(c, w, LowBattery.TEXT, warnPaint, LINE_BASELINE)
        else Ui.centred(c, w, Ui.ellipsize(linePaint, line, maxText), linePaint, LINE_BASELINE)

        s.sleepMinutesLeft?.let { c.drawText("Sleep ${it}m", Ui.MARGIN, CardRenderer.BATT_BASELINE, linePaint) }
        if (s.batteryPercent >= 0) chrome.battery(c, w, s.batteryPercent, s.charging)

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
        const val ICON_SIZE = 128f // 16x16 at 8x: whole pixels, no filtering
        const val ICON_LEFT = (Ui.W - ICON_SIZE) / 2f
        const val ICON_TOP = 32f
        const val PAUSE_W = 18f
        const val PAUSE_H = 60f
        const val PAUSE_GAP = 14f
        const val TITLE_SIZE = 30f
        const val TITLE_BASELINE = 204f
        const val PLAYLIST_SIZE = 24f
        const val PLAYLIST_BASELINE = 238f
        const val TIME_SIZE = 20f
        const val TIME_BASELINE = 276f
        const val BAR_MARGIN = 28f
        const val BAR_Y = 288f
        const val BAR_H = 8f
        const val LINE_BASELINE = 334f
        const val TRACK_ALPHA = 110
        const val MARQUEE_PAUSE_MS = 1200f
        const val MARQUEE_PX_PER_MS = 0.04f
    }
}
