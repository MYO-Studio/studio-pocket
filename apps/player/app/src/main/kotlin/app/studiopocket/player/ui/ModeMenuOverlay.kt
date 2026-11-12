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
 * Draws [menu] over the screen when it is open, in the volume readout's style: a dark rounded panel
 * with a soft shadow, a dim "Play mode" heading, one row per mode (its icon, as Now Playing shows it,
 * and its name), the selected row in a purple pill, and a tick on the playlist's current mode.
 */
class ModeMenuOverlay(private val menu: ModeMenu) {
    private val fill = Ui.fill(Ui.blend(Ui.bg, 0xFF000000.toInt(), 0.15f))
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Ui.blend(Ui.bg, Ui.inkDim, 0.45f)
    }
    private val shadow = Ui.fill(0xFF000000.toInt())
    private val highlight = Ui.fill(Ui.accent)
    private val heading = Ui.text(Ui.TEXT, Ui.inkDim)
    private val label = Ui.text(ROW_TEXT, Ui.ink)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Ui.ink
    }
    private val tickPath = Path()
    private val icons = ModeIcons()
    private val rect = RectF()

    fun draw(c: Canvas, w: Int, h: Int) {
        if (!menu.isOpen) return
        val rows = ModeMenu.OPTIONS.size
        val panelH = TOP_PAD + HEADING_H + rows * ROW_H + BOTTOM_PAD
        val left = (w - PANEL_W) / 2f
        val top = (h - panelH) / 2f
        val right = left + PANEL_W
        val bottom = top + panelH
        for (i in SHADOW_ALPHAS.indices) {
            val grow = (i + 1) * 2f
            shadow.alpha = SHADOW_ALPHAS[i]
            rect.set(left - grow, top - grow + 3f, right + grow, bottom + grow + 3f)
            c.drawRoundRect(rect, RADIUS + grow, RADIUS + grow, shadow)
        }
        rect.set(left, top, right, bottom)
        c.drawRoundRect(rect, RADIUS, RADIUS, fill)
        c.drawRoundRect(rect, RADIUS, RADIUS, border)

        Ui.centred(c, w, "Play mode", heading, top + TOP_PAD + Ui.TEXT)
        val rowsTop = top + TOP_PAD + HEADING_H
        ModeMenu.OPTIONS.forEachIndexed { i, mode ->
            val rowTop = rowsTop + i * ROW_H
            if (i == menu.selected) {
                rect.set(left + ROW_INSET, rowTop + 2f, right - ROW_INSET, rowTop + ROW_H - 2f)
                c.drawRoundRect(rect, (ROW_H - 4f) / 2f, (ROW_H - 4f) / 2f, highlight)
            }
            val baseline = rowTop + ROW_H / 2f - (label.ascent() + label.descent()) / 2f
            // Each mode's icon (Normal has none: its space stays empty so the words line up), then its name.
            icons.draw(c, mode, left + ROW_INSET + TEXT_PAD, rowTop + ROW_H / 2f, Ui.ink)
            c.drawText(ModeMenu.label(mode), left + ROW_INSET + TEXT_PAD + ModeIcons.W + ICON_GAP, baseline, label)
            if (mode == menu.current) drawTick(c, right - ROW_INSET - TEXT_PAD - TICK_W, rowTop + ROW_H / 2f)
        }
    }

    private fun drawTick(c: Canvas, x: Float, cy: Float) {
        tickPath.rewind()
        tickPath.moveTo(x, cy)
        tickPath.lineTo(x + TICK_W * 0.38f, cy + TICK_W * 0.36f)
        tickPath.lineTo(x + TICK_W, cy - TICK_W * 0.42f)
        c.drawPath(tickPath, tick)
    }

    companion object {
        const val PANEL_W = 260f
        const val RADIUS = 16f
        const val TOP_PAD = 10f
        const val HEADING_H = 30f
        const val BOTTOM_PAD = 10f
        const val ROW_H = 40f
        const val ROW_INSET = 10f
        const val TEXT_PAD = 14f
        const val ROW_TEXT = 22f
        const val TICK_W = 16f
        const val ICON_GAP = 12f
        val SHADOW_ALPHAS = intArrayOf(60, 35, 18)
    }
}
