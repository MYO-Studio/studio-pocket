// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import app.studiopocket.player.library.PlayMode

/**
 * The play-mode icons, drawn with lines in one colour so they sit beside text: repeat (two arrows
 * chasing round a loop), repeat one (the same with a 1 inside) and shuffle (two crossing arrows).
 * Normal has none. [W] wide and [H] tall, left edge at x, centred on cy.
 */
class ModeIcons {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val one = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ONE_SIZE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val oneBounds = Rect()

    /** True when [mode] has an icon (everything but Normal). */
    fun has(mode: PlayMode) = mode != PlayMode.NORMAL

    fun draw(c: Canvas, mode: PlayMode, x: Float, cy: Float, color: Int, alpha: Int = 255) {
        stroke.color = color; stroke.alpha = alpha
        fill.color = color; fill.alpha = alpha
        one.color = color; one.alpha = alpha
        when (mode) {
            PlayMode.NORMAL -> Unit
            PlayMode.REPEAT_ALL -> repeat(c, x, cy)
            PlayMode.REPEAT_ONE -> {
                repeat(c, x, cy)
                // Centred on the glyph's own bounds: font metrics would sit a digit too high.
                one.getTextBounds("1", 0, 1, oneBounds)
                c.drawText("1", x + W / 2f, cy - oneBounds.exactCenterY(), one)
            }
            PlayMode.SHUFFLE -> shuffle(c, x, cy)
        }
    }

    /** A loop: the top runs right to an arrowhead, the bottom runs left to one. */
    private fun repeat(c: Canvas, x: Float, cy: Float) {
        val l = x + STROKE / 2f; val r = x + W - STROKE / 2f
        val t = cy - H / 2f + HEAD; val b = cy + H / 2f - HEAD
        path.rewind()
        path.moveTo(l, cy + 1f); path.lineTo(l, t); path.lineTo(r - HEAD, t)
        path.moveTo(r, cy - 1f); path.lineTo(r, b); path.lineTo(l + HEAD, b)
        c.drawPath(path, stroke)
        head(c, r - HEAD + 1f, t, 1f)
        head(c, l + HEAD - 1f, b, -1f)
    }

    /** Two lines crossing in the middle, each ending in an arrowhead on the right. */
    private fun shuffle(c: Canvas, x: Float, cy: Float) {
        val l = x + STROKE / 2f; val r = x + W - HEAD
        val t = cy - H / 2f + HEAD; val b = cy + H / 2f - HEAD
        val a = x + W * 0.3f; val z = x + W * 0.62f
        path.rewind()
        path.moveTo(l, t); path.lineTo(a, t); path.lineTo(z, b); path.lineTo(r, b)
        path.moveTo(l, b); path.lineTo(a, b); path.lineTo(z, t); path.lineTo(r, t)
        c.drawPath(path, stroke)
        head(c, r - 1f, t, 1f)
        head(c, r - 1f, b, 1f)
    }

    /** A filled arrowhead with its base at [x], pointing right ([dir] 1) or left (-1), centred on [y]. */
    private fun head(c: Canvas, x: Float, y: Float, dir: Float) {
        path.rewind()
        path.moveTo(x, y - HEAD)
        path.lineTo(x + dir * HEAD * 1.3f, y)
        path.lineTo(x, y + HEAD)
        path.close()
        c.drawPath(path, fill)
    }

    companion object {
        const val W = 24f
        const val H = 22f
        const val STROKE = 2.2f
        const val HEAD = 4f
        const val ONE_SIZE = 9f
    }
}
