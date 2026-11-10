// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import app.studiopocket.player.library.CoverProgress
import app.studiopocket.player.library.LoadedPlaylist

/**
 * The painter for [LibraryScreen] (ported from the author's earlier Y1 player): one playlist card, the
 * now-playing pill and the battery cell. Every paint, rect and path is
 * allocated once and reused: this runs up to four times a frame at 60 Hz during a slide.
 *
 * Cards have Yoto-card corners ([CARD_RADIUS]). Covers are painted through a [BitmapShader]
 * into a round rect rather than clipped, so the corners stay anti-aliased.
 */
class CardRenderer {
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artMatrix = Matrix()
    private val shaders = HashMap<Bitmap, BitmapShader>()
    private val placeholder = Ui.fill(Ui.inkDim)
    private val band = Ui.fill(Ui.bg)
    private val bandPath = Path()
    private val title = Ui.text(TITLE_SIZE, Ui.ink)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillFill = Ui.fill(Ui.accent)
    private val pillText = Ui.text(Ui.TEXT, Ui.bg)
    private val glyph = Ui.fill(Ui.bg)
    private val batteryText = Ui.text(Ui.TEXT, Ui.inkDim)
    private val groupText = Ui.text(Ui.TEXT, Ui.inkDim)
    private val batteryShell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val batteryLevel = Ui.fill(Ui.inkDim)
    private val progressTrack = Ui.fill(0xFF000000.toInt())
    private val progressFill = Ui.fill(Ui.accent)
    private val tickDisc = Ui.fill(Ui.good)
    private val tickStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = TICK_STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Ui.ink
    }
    private val tickPath = Path()
    private val rect = RectF()
    private val bitmapRect = RectF()
    private val triangle = Path()
    private val bandRadii = floatArrayOf(0f, 0f, 0f, 0f, CARD_RADIUS, CARD_RADIUS, CARD_RADIUS, CARD_RADIUS)
    private val fitted = HashMap<String, Fitted>()

    init {
        ring.style = Paint.Style.STROKE; ring.strokeWidth = RING_WIDTH; ring.color = Ui.accent
        batteryShell.style = Paint.Style.STROKE; batteryShell.strokeWidth = BATT_STROKE; batteryShell.color = Ui.inkDim
    }

    /**
     * [p]'s card with its left edge at [left]: cover (or placeholder), title band, selection ring, and
     * a thin bar along the bottom of the cover, above the title band, when [progress] is given: full
     * and green with a tick in the corner once the playlist has been finished.
     */
    fun card(c: Canvas, p: LoadedPlaylist, left: Float, alpha: Int, selected: Boolean, progress: CoverProgress? = null) {
        rect.set(left, LibraryScreen.CARD_TOP, left + LibraryScreen.CARD_W, LibraryScreen.CARD_BOTTOM)
        val bitmap: Bitmap? = Artwork.bitmap(p)
        if (bitmap != null) {
            val shader = shaderFor(bitmap)
            bitmapRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            artMatrix.setRectToRect(bitmapRect, rect, Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(artMatrix)
            art.shader = shader
            art.alpha = alpha
            c.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, art)
        } else {
            placeholder.alpha = scale(PLACEHOLDER_ALPHA, alpha)
            c.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, placeholder)
        }
        rect.set(left, LibraryScreen.CARD_BOTTOM - BAND_H, left + LibraryScreen.CARD_W, LibraryScreen.CARD_BOTTOM)
        band.alpha = scale(BAND_ALPHA, alpha)
        bandPath.rewind()
        bandPath.addRoundRect(rect, bandRadii, Path.Direction.CW)
        c.drawPath(bandPath, band)
        title.alpha = alpha
        val text = fit(title, p.id, p.title, LibraryScreen.CARD_W - 2 * TITLE_PAD)
        c.drawText(text, left + TITLE_PAD, rect.centerY() - (title.ascent() + title.descent()) / 2f, title)
        if (progress != null) {
            progressBar(c, left, progress.fraction, progress.finished, alpha)
            if (progress.finished) finishedTick(c, left, alpha)
        }
        if (selected) ringAround(c, left)
    }

    /** Full width of the card, sitting on the title band: a dark track and an accent fill. */
    private fun progressBar(c: Canvas, left: Float, progress: Float, finished: Boolean, alpha: Int) {
        val bottom = LibraryScreen.CARD_BOTTOM - BAND_H
        progressTrack.alpha = scale(PROGRESS_TRACK_ALPHA, alpha)
        c.drawRect(left, bottom - PROGRESS_H, left + LibraryScreen.CARD_W, bottom, progressTrack)
        progressFill.color = if (finished) Ui.good else Ui.accent
        progressFill.alpha = alpha
        c.drawRect(left, bottom - PROGRESS_H, left + LibraryScreen.CARD_W * progress.coerceIn(0f, 1f), bottom, progressFill)
    }

    /** A green disc with a white tick, in the cover's top-right corner. */
    private fun finishedTick(c: Canvas, left: Float, alpha: Int) {
        val cx = left + LibraryScreen.CARD_W - TICK_INSET - TICK_R
        val cy = LibraryScreen.CARD_TOP + TICK_INSET + TICK_R
        tickDisc.alpha = alpha
        c.drawCircle(cx, cy, TICK_R, tickDisc)
        tickPath.rewind()
        tickPath.moveTo(cx - TICK_R * 0.45f, cy + TICK_R * 0.02f)
        tickPath.lineTo(cx - TICK_R * 0.1f, cy + TICK_R * 0.38f)
        tickPath.lineTo(cx + TICK_R * 0.48f, cy - TICK_R * 0.32f)
        tickStroke.alpha = alpha
        c.drawPath(tickPath, tickStroke)
    }

    /** The selection ring sits just outside the card, its corners concentric with the card's. */
    private fun ringAround(c: Canvas, left: Float) {
        val out = RING_GAP + RING_WIDTH / 2f
        rect.set(left - out, LibraryScreen.CARD_TOP - out, left + LibraryScreen.CARD_W + out, LibraryScreen.CARD_BOTTOM + out)
        c.drawRoundRect(rect, CARD_RADIUS + out, CARD_RADIUS + out, ring)
    }

    /** One shader per decoded cover; [Artwork] holds a handful, so this stays small. */
    private fun shaderFor(bitmap: Bitmap): BitmapShader {
        shaders[bitmap]?.let { return it }
        if (shaders.size >= MAX_SHADERS) shaders.clear()
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaders[bitmap] = it }
    }

    /** The now-playing badge over the top of the cards: a play/pause glyph and the playlist title. Returns its left edge. */
    fun pill(c: Canvas, w: Int, playlistTitle: String, playing: Boolean): Float {
        val text = fit(pillText, PILL_KEY, playlistTitle, PILL_MAX_TEXT)
        val width = PILL_PAD * 2 + GLYPH_W + GLYPH_GAP + pillText.measureText(text)
        val left = (w - width) / 2f
        rect.set(left, PILL_TOP, left + width, PILL_TOP + PILL_H)
        pillFill.color = if (playing) Ui.accent else Ui.inkDim
        c.drawRoundRect(rect, PILL_H / 2f, PILL_H / 2f, pillFill)
        val mid = PILL_TOP + PILL_H / 2f
        val gx = left + PILL_PAD
        if (playing) {
            triangle.reset()
            triangle.moveTo(gx, mid - GLYPH_H / 2f); triangle.lineTo(gx + GLYPH_W, mid); triangle.lineTo(gx, mid + GLYPH_H / 2f); triangle.close()
            c.drawPath(triangle, glyph)
        } else {
            val barW = (GLYPH_W - GLYPH_GAP / 2f) / 2f
            c.drawRect(gx, mid - GLYPH_H / 2f, gx + barW, mid + GLYPH_H / 2f, glyph)
            c.drawRect(gx + GLYPH_W - barW, mid - GLYPH_H / 2f, gx + GLYPH_W, mid + GLYPH_H / 2f, glyph)
        }
        c.drawText(text, gx + GLYPH_W + GLYPH_GAP, mid - (pillText.ascent() + pillText.descent()) / 2f, pillText)
        return left
    }

    /** Where the child is: the open group's name, small and dim at the top left from [x], at most [maxW] wide. */
    fun groupLabel(c: Canvas, label: String, maxW: Float, x: Float = Ui.MARGIN) {
        val text = fit(groupText, GROUP_KEY, label, maxW)
        c.drawText(text, x, BATT_BASELINE, groupText)
    }

    /**
     * The clock top left ("9:36"), on the battery's baseline in the same small dim text. Returns where
     * the next item on the top line starts: after the clock and a gap, or the margin when [time] is null.
     */
    fun clock(c: Canvas, time: String?): Float {
        if (time == null) return Ui.MARGIN
        c.drawText(time, Ui.MARGIN, BATT_BASELINE, batteryText.also { it.color = Ui.inkDim })
        return Ui.MARGIN + batteryText.measureText(time) + CLOCK_GAP
    }

    /** Battery readout top-right: "84%" and a cell filled green, amber or red by level, accent while charging. Only when a level is known. */
    fun battery(c: Canvas, w: Int, percent: Int, charging: Boolean) {
        val pct = percent.coerceIn(0, 100)
        val right = w - Ui.MARGIN
        val bodyLeft = right - BATT_CAP_W - BATT_W
        rect.set(bodyLeft, BATT_TOP, bodyLeft + BATT_W, BATT_TOP + BATT_H)
        c.drawRoundRect(rect, BATT_RADIUS, BATT_RADIUS, batteryShell)
        val mid = BATT_TOP + BATT_H / 2f
        c.drawRect(right - BATT_CAP_W, mid - BATT_CAP_H / 2f, right, mid + BATT_CAP_H / 2f, batteryShell)
        batteryLevel.color = LowBattery.cellColour(pct, charging)
        val inner = BATT_W - 2 * (BATT_STROKE + BATT_PAD)
        val fillLeft = bodyLeft + BATT_STROKE + BATT_PAD
        c.drawRect(fillLeft, BATT_TOP + BATT_STROKE + BATT_PAD, fillLeft + inner * pct / 100f, BATT_TOP + BATT_H - BATT_STROKE - BATT_PAD, batteryLevel)
        val label = "$pct%"
        batteryText.color = if (charging) Ui.accent else Ui.inkDim
        c.drawText(label, bodyLeft - BATT_GAP - batteryText.measureText(label), BATT_BASELINE, batteryText)
    }

    private fun fit(paint: Paint, key: String, s: String, maxW: Float): String {
        val hit = fitted[key]
        if (hit != null && hit.source == s && hit.maxW == maxW && hit.size == paint.textSize) return hit.text
        val text = Ui.ellipsize(paint, s, maxW)
        if (fitted.size >= MAX_FITTED) fitted.clear()
        fitted[key] = Fitted(s, maxW, paint.textSize, text)
        return text
    }

    private class Fitted(val source: String, val maxW: Float, val size: Float, val text: String)

    private fun scale(a: Int, alpha: Int): Int = a * alpha / 255

    companion object {
        private const val PILL_KEY = "\u0000pill"
        private const val GROUP_KEY = "\u0000group"
        private const val MAX_FITTED = 32
        const val TITLE_SIZE = 22f
        const val BAND_H = 40f
        const val PROGRESS_H = 5f
        const val PROGRESS_TRACK_ALPHA = 140
        const val TICK_R = 13f
        const val TICK_INSET = 8f
        const val TICK_STROKE = 3f
        const val TITLE_PAD = 8f
        const val BAND_ALPHA = 179
        const val PLACEHOLDER_ALPHA = 51
        private const val MAX_SHADERS = 16
        /** ID-1 card corner (3.18 mm on a 54 mm-wide Yoto card) at our card width. */
        const val CARD_RADIUS = 12f
        const val RING_WIDTH = 3f
        const val RING_GAP = 3f
        const val PILL_TOP = 6f
        const val PILL_H = 28f
        const val PILL_PAD = 12f
        const val PILL_MAX_TEXT = 220f
        const val GLYPH_W = 12f
        const val GLYPH_H = 14f
        const val GLYPH_GAP = 8f
        const val BATT_TOP = 7f
        const val BATT_W = 24f
        const val BATT_H = 14f
        const val BATT_RADIUS = 3f
        const val BATT_STROKE = 2f
        const val BATT_PAD = 1.5f
        const val BATT_CAP_W = 3f
        const val BATT_CAP_H = 7f
        const val BATT_GAP = 7f
        const val BATT_BASELINE = 20f
        /** Between the clock and whatever follows it on the top line. */
        const val CLOCK_GAP = 12f
    }
}
