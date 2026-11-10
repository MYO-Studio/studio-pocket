// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The player's own volume readout, drawn by [PlayerView] over whatever screen is showing, in place
 * of Android's volume popup: a pill just above Now Playing's progress bar with a speaker and one
 * segment per music-stream step, lit from green through yellow and orange to red
 * ([VolumeColours]). Steps above the parent-set cap are drawn darker, as out of reach.
 * [MainActivity] shows it on each Volume key press and hides it [SHOW_MS] after the last.
 */
class VolumeOverlay {
    var visible = false
        private set
    private var level = 0
    private var max = 1
    private var cap = -1
    private var colours = IntArray(0)

    private val fill = Ui.fill(Ui.blend(Ui.bg, 0xFF000000.toInt(), FILL_DARKEN))
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = BORDER; color = Ui.blend(Ui.bg, Ui.inkDim, BORDER_MIX)
    }
    private val shadow = Ui.fill(0xFF000000.toInt())
    private val lit = Ui.fill(Ui.ink)
    private val off = Ui.fill(Ui.blend(Ui.bg, Ui.inkDim, OFF_MIX))
    private val locked = Ui.fill(Ui.blend(Ui.bg, Ui.inkDim, LOCKED_MIX))
    private val iconFill = Ui.fill(Ui.ink)
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ICON_STROKE; strokeCap = Paint.Cap.ROUND; color = Ui.ink
    }
    private val rect = RectF()
    private val cone = Path()

    /** Shows [level] of [max]; [cap] is the volume cap in steps, or negative for none. */
    fun show(level: Int, max: Int, cap: Int) {
        this.max = maxOf(1, max)
        this.level = level.coerceIn(0, this.max)
        this.cap = cap
        if (colours.size != this.max) colours = IntArray(this.max) { VolumeColours.segment(it, this.max) }
        visible = true
    }

    fun hide() { visible = false }

    fun draw(c: Canvas, w: Int, h: Int) {
        if (!visible) return
        val left = (w - PANEL_W) / 2f
        val right = left + PANEL_W
        val bottom = PANEL_TOP + PANEL_H
        val r = PANEL_H / 2f

        // A soft drop shadow: a few widening, fading pills under the panel.
        for (i in SHADOW_ALPHAS.indices) {
            val grow = (i + 1) * SHADOW_STEP
            shadow.alpha = SHADOW_ALPHAS[i]
            rect.set(left - grow, PANEL_TOP - grow + SHADOW_DY, right + grow, bottom + grow + SHADOW_DY)
            c.drawRoundRect(rect, r + grow, r + grow, shadow)
        }
        rect.set(left, PANEL_TOP, right, bottom)
        c.drawRoundRect(rect, r, r, fill)
        c.drawRoundRect(rect, r, r, border)

        val mid = PANEL_TOP + PANEL_H / 2f
        speaker(c, left + PAD, mid)

        val barLeft = left + PAD + ICON_W + ICON_GAP
        val barRight = right - PAD
        val seg = (barRight - barLeft - SEG_GAP * (max - 1)) / max
        for (i in 0 until max) {
            val step = i + 1
            val x = barLeft + i * (seg + SEG_GAP)
            val paint = when {
                step <= level -> lit.also { it.color = colours[i] }
                cap >= 0 && step > cap -> locked
                else -> off
            }
            rect.set(x, mid - SEG_H / 2f, x + seg, mid + SEG_H / 2f)
            c.drawRoundRect(rect, SEG_RADIUS, SEG_RADIUS, paint)
        }
    }

    /** The speaker, [ICON_W] wide with its left edge at [x], centred on [cy]: two sound waves, or a cross at 0. */
    private fun speaker(c: Canvas, x: Float, cy: Float) {
        val top = cy - ICON_H / 2f
        rect.set(x, cy - BOX_H / 2f, x + BOX_W, cy + BOX_H / 2f)
        c.drawRoundRect(rect, BOX_RADIUS, BOX_RADIUS, iconFill)
        val coneRight = x + BOX_W + CONE_W
        cone.rewind()
        cone.moveTo(x + BOX_W - 1f, cy - BOX_H / 2f)
        cone.lineTo(coneRight, top)
        cone.lineTo(coneRight, top + ICON_H)
        cone.lineTo(x + BOX_W - 1f, cy + BOX_H / 2f)
        cone.close()
        c.drawPath(cone, iconFill)
        if (level == 0) {
            val l = coneRight + WAVE_GAP; val s = CROSS
            c.drawLine(l, cy - s / 2f, l + s, cy + s / 2f, iconStroke)
            c.drawLine(l, cy + s / 2f, l + s, cy - s / 2f, iconStroke)
        } else {
            for (radius in WAVES) {
                rect.set(coneRight - radius + WAVE_GAP, cy - radius, coneRight + radius + WAVE_GAP, cy + radius)
                c.drawArc(rect, -WAVE_SWEEP / 2f, WAVE_SWEEP, false, iconStroke)
            }
        }
    }

    companion object {
        /** How long the readout stays after the last Volume key press. */
        const val SHOW_MS = 1_500L
        const val PANEL_W = 288f
        const val PANEL_H = 40f
        /** Just above Now Playing's progress bar, over the time line. */
        const val PANEL_TOP = 244f
        const val BORDER = 1.5f
        const val PAD = 15f
        const val SEG_H = 17f
        const val SEG_GAP = 3f
        const val SEG_RADIUS = 4f
        const val ICON_W = 24f
        const val ICON_H = 18f
        const val ICON_GAP = 12f
        const val ICON_STROKE = 2f
        const val BOX_W = 5f
        const val BOX_H = 8f
        const val BOX_RADIUS = 1.5f
        const val CONE_W = 6f
        const val WAVE_GAP = 1f
        const val WAVE_SWEEP = 100f
        val WAVES = floatArrayOf(4f, 8f)
        const val CROSS = 8f
        const val SHADOW_DY = 3f
        const val SHADOW_STEP = 2f
        val SHADOW_ALPHAS = intArrayOf(60, 35, 18)
        private const val FILL_DARKEN = 0.15f
        private const val BORDER_MIX = 0.45f
        private const val OFF_MIX = 0.3f
        private const val LOCKED_MIX = 0.12f
    }
}

/**
 * The lit segments' colours: fixed per position, so a step always has the same colour, running from
 * green at the bottom through yellow and orange to red at [RED_AT] of the way up, and red above
 * that. Pure, so it is unit-tested on the JVM.
 */
object VolumeColours {
    const val GREEN_HUE = 120f
    const val RED_HUE = 5f
    /** The fraction of the scale where the colour reaches red. */
    const val RED_AT = 0.78f
    const val SATURATION = 0.8f
    const val VALUE = 0.93f

    /** The hue of segment [index] (0-based) of [count]. */
    fun hue(index: Int, count: Int): Float {
        val t = if (count <= 1) 0f else index / (count - 1).toFloat()
        return GREEN_HUE - (GREEN_HUE - RED_HUE) * minOf(1f, t / RED_AT)
    }

    /** Segment [index] of [count] as an opaque ARGB colour. */
    fun segment(index: Int, count: Int): Int = hsv(hue(index, count), SATURATION, VALUE)

    /** HSV (hue in degrees, saturation and value 0..1) to opaque ARGB. */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - Math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun ch(f: Float) = Math.round((f + m) * 255f).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}
