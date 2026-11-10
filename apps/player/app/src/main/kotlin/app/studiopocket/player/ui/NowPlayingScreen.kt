// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import app.studiopocket.player.library.LoadedPlaylist
import app.studiopocket.player.library.PlayMode

/**
 * What a child watches while a playlist plays (ported from the author's earlier Y1 player): the track's 16x16
 * pixel icon blown up 8x with no filtering, the track title large with the playlist name dim beneath
 * it, elapsed and total time over a rounded progress bar, and "Track n of m" at the foot, on a
 * background tinted towards the playlist artwork. Battery top right; clock and sleep timer top left. Track
 * changes crossfade the icon; a long title scrolls while playing and parks when paused. While the
 * ring browses the playlist ([State.browsing]) the screen shows that track instead, dimmed under a
 * play triangle and without the times or progress bar, until centre plays it or the browse ends.
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
        /** The track the ring has browsed to, when it is not the playing one. */
        val browsing: Int? = null,
        /** +1 or -1 while Next/Previous is held to scrub ([positionMs] is then the preview), else 0. */
        val scrub: Int = 0,
        /** The clock top left ("9:36"); null hides it. */
        val clock: String? = null,
        /** The playlist's play mode, named after "Track n of m" unless it is Normal. */
        val mode: PlayMode = PlayMode.NORMAL,
    ) {
        /** The track on screen: the browsed one, else the playing one. */
        val shownTrack: Int get() = browsing ?: trackIndex
        val shownTitle: String get() = if (browsing == null) title else playlist?.manifest?.tracks?.getOrNull(browsing)?.title ?: ""
    }

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
    private val playPath = Path()
    private val scrubPath = Path()

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
        val key = next.playlist?.let { "${it.id}/${next.shownTrack}" }
        if (key != trackKey) {
            trackKey = key
            previous = shown
            crossStart = if (previous == null) 0L else SystemClock.uptimeMillis()
            titleWidth = titlePaint.measureText(next.shownTitle)
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
        val browsing = s.browsing != null
        val dimmed = browsing || s.status != Status.PLAYING
        val full = if (dimmed) DIM_ALPHA else 255
        var fade = if (crossStart == 0L) 1f else (now - crossStart) / CROSSFADE_MS.toFloat()
        if (fade >= 1f) { crossStart = 0L; previous = null; fade = 1f }
        previous?.let { icon(c, it, ((1f - fade) * full).toInt()) }
        val icon = PixelIcon.bitmap(s.playlist?.trackIconFile(s.shownTrack))
        if (icon != null) { icon(c, icon, (fade * full).toInt()); shown = icon }
        if (browsing) playGlyph(c) else if (s.status == Status.PAUSED) pauseGlyph(c)

        // Title: ellipsised at rest; while playing, a long one scrolls once per cycle.
        val maxW = w - 2 * Ui.MARGIN
        val scrolling = titleWidth > maxW && s.status == Status.PLAYING && !browsing
        if (!scrolling) {
            val t = Ui.ellipsize(titlePaint, s.shownTitle, maxW)
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
        if (!browsing && (s.status == Status.PLAYING || s.status == Status.PAUSED)) {
            // While scrubbing the elapsed time is bright, with two arrows after it pointing the way it moves.
            timePaint.color = if (s.scrub != 0) Ui.ink else Ui.inkDim
            val elapsed = clock(s.positionMs)
            c.drawText(elapsed, BAR_MARGIN, TIME_BASELINE, timePaint)
            if (s.scrub != 0) scrubGlyph(c, BAR_MARGIN + timePaint.measureText(elapsed) + SCRUB_GAP, s.scrub)
            timePaint.color = Ui.inkDim
            val end = clock(duration)
            c.drawText(end, w - BAR_MARGIN - timePaint.measureText(end), TIME_BASELINE, timePaint)
        }

        // Rounded track, rounded purple fill; the fill never draws narrower than its own height.
        if (!browsing) {
            dim.alpha = TRACK_ALPHA
            rect.set(BAR_MARGIN, BAR_Y, w - BAR_MARGIN, BAR_Y + BAR_H)
            c.drawRoundRect(rect, BAR_H / 2f, BAR_H / 2f, dim)
            val fraction = if (duration > 0) (s.positionMs / duration.toFloat()).coerceIn(0f, 1f) else 0f
            if (fraction > 0f) {
                rect.right = BAR_MARGIN + maxOf(BAR_H, (w - 2 * BAR_MARGIN) * fraction)
                c.drawRoundRect(rect, BAR_H / 2f, BAR_H / 2f, accent)
            }
        }

        // Low battery replaces the track line with the warning.
        val line = when {
            browsing -> "Track ${s.shownTrack + 1} of $total"
            s.error != null -> s.error
            s.status == Status.STOPPED -> "Done"
            s.status == Status.PREPARING -> "Loading…"
            s.mode != PlayMode.NORMAL -> "Track ${s.trackIndex + 1} of $total · ${ModeMenu.label(s.mode)}"
            else -> "Track ${s.trackIndex + 1} of $total"
        }
        if (LowBattery.warn(s.batteryPercent, s.charging)) Ui.centred(c, w, LowBattery.TEXT, warnPaint, LINE_BASELINE)
        else Ui.centred(c, w, Ui.ellipsize(linePaint, line, maxText), linePaint, LINE_BASELINE)

        val topX = chrome.clock(c, s.clock)
        s.sleepMinutesLeft?.let { c.drawText("Sleep ${it}m", topX, CardRenderer.BATT_BASELINE, linePaint) }
        if (s.batteryPercent >= 0) chrome.battery(c, w, s.batteryPercent, s.charging)

        if (crossStart != 0L || scrolling) invalidateDelayed(FRAME_MS)
    }

    /** Two small triangles from [left], pointing right for [direction] +1 and left for -1. */
    private fun scrubGlyph(c: Canvas, left: Float, direction: Int) {
        val mid = TIME_BASELINE - TIME_SIZE * 0.35f
        val half = SCRUB_H / 2f
        for (k in 0..1) {
            val x0 = left + k * SCRUB_W
            val (tail, tip) = if (direction > 0) x0 to x0 + SCRUB_W else x0 + SCRUB_W to x0
            scrubPath.rewind()
            scrubPath.moveTo(tail, mid - half)
            scrubPath.lineTo(tip, mid)
            scrubPath.lineTo(tail, mid + half)
            scrubPath.close()
            c.drawPath(scrubPath, ink)
        }
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

    /** A play triangle where the pause bars go: "centre plays this track". */
    private fun playGlyph(c: Canvas) {
        ink.alpha = 255
        val cx = ICON_LEFT + ICON_SIZE / 2f
        val cy = ICON_TOP + ICON_SIZE / 2f
        // Nudged right of centre so the triangle's area, not its box, looks centred.
        playPath.reset()
        playPath.moveTo(cx - PLAY_H * 0.35f, cy - PLAY_H / 2f)
        playPath.lineTo(cx + PLAY_H * 0.55f, cy)
        playPath.lineTo(cx - PLAY_H * 0.35f, cy + PLAY_H / 2f)
        playPath.close()
        c.drawPath(playPath, ink)
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
        const val PLAY_H = 60f
        const val TITLE_SIZE = 30f
        const val TITLE_BASELINE = 204f
        const val PLAYLIST_SIZE = 24f
        const val PLAYLIST_BASELINE = 238f
        const val TIME_SIZE = 20f
        const val TIME_BASELINE = 276f
        const val BAR_MARGIN = 28f
        const val SCRUB_GAP = 8f
        const val SCRUB_W = 9f
        const val SCRUB_H = 12f
        const val BAR_Y = 288f
        const val BAR_H = 8f
        const val LINE_BASELINE = 334f
        const val TRACK_ALPHA = 110
        const val MARQUEE_PAUSE_MS = 1200f
        const val MARQUEE_PX_PER_MS = 0.04f
    }
}
