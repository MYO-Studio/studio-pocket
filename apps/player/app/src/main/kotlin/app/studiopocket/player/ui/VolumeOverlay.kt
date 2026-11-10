// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * The player's own volume readout, drawn by [PlayerView] over whatever screen is showing, in place
 * of Android's volume popup: a speaker, one segment per music-stream step and the level. Steps above
 * the parent-set cap are drawn darker, as out of reach. [MainActivity] shows it on each Volume key
 * press and hides it [SHOW_MS] after the last.
 */
class VolumeOverlay {
    var visible = false
        private set
    private var level = 0
    private var max = 1
    private var cap = -1

    private val panel = Ui.fill(Ui.blend(Ui.bg, Ui.ink, PANEL_LIFT))
    private val border = Paint(Paint.ANTI_ALIAS_FLAG)
    private val on = Ui.fill(Ui.accent)
    private val off = Ui.fill(Ui.blend(Ui.bg, Ui.inkDim, OFF_MIX))
    private val locked = Ui.fill(Ui.blend(Ui.bg, Ui.inkDim, LOCKED_MIX))
    private val number = Ui.text(NUMBER_SIZE, Ui.ink)
    private val iconPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val speaker: Bitmap = PixelIcon.glyph(Glyphs.SPEAKER)
    private val muted: Bitmap = PixelIcon.glyph(Glyphs.SPEAKER_MUTED)
    private val rect = RectF()
    private val iconRect = RectF()

    init {
        border.style = Paint.Style.STROKE; border.strokeWidth = BORDER; border.color = Ui.blend(Ui.bg, Ui.inkDim, BORDER_MIX)
        number.textAlign = Paint.Align.RIGHT
    }

    /** Shows [level] of [max]; [cap] is the volume cap in steps, or negative for none. */
    fun show(level: Int, max: Int, cap: Int) {
        this.max = maxOf(1, max)
        this.level = level.coerceIn(0, this.max)
        this.cap = cap
        visible = true
    }

    fun hide() { visible = false }

    fun draw(c: Canvas, w: Int, h: Int) {
        if (!visible) return
        val left = (w - PANEL_W) / 2f
        val top = (h - PANEL_H) / 2f
        rect.set(left, top, left + PANEL_W, top + PANEL_H)
        c.drawRoundRect(rect, RADIUS, RADIUS, panel)
        c.drawRoundRect(rect, RADIUS, RADIUS, border)

        val mid = top + PANEL_H / 2f
        iconRect.set(left + PAD, mid - ICON / 2f, left + PAD + ICON, mid + ICON / 2f)
        c.drawBitmap(if (level == 0) muted else speaker, null, iconRect, iconPaint)

        val numberRight = left + PANEL_W - PAD
        c.drawText(level.toString(), numberRight, mid + NUMBER_SIZE * 0.36f, number)

        val barLeft = iconRect.right + GAP
        val barRight = numberRight - NUMBER_W - GAP
        val seg = (barRight - barLeft - SEG_GAP * (max - 1)) / max
        for (i in 0 until max) {
            val step = i + 1
            val x = barLeft + i * (seg + SEG_GAP)
            val paint = when {
                step <= level -> on
                cap >= 0 && step > cap -> locked
                else -> off
            }
            rect.set(x, mid - SEG_H / 2f, x + seg, mid + SEG_H / 2f)
            c.drawRoundRect(rect, SEG_RADIUS, SEG_RADIUS, paint)
        }
    }

    companion object {
        /** How long the readout stays after the last Volume key press. */
        const val SHOW_MS = 1_500L
        const val PANEL_W = 340f
        const val PANEL_H = 72f
        const val RADIUS = 16f
        const val BORDER = 2f
        const val PAD = 18f
        const val ICON = 32f
        const val GAP = 14f
        const val NUMBER_SIZE = 26f
        const val NUMBER_W = 30f
        const val SEG_H = 22f
        const val SEG_GAP = 3f
        const val SEG_RADIUS = 3f
        private const val PANEL_LIFT = 0.08f
        private const val BORDER_MIX = 0.35f
        private const val OFF_MIX = 0.45f
        private const val LOCKED_MIX = 0.15f
    }
}
