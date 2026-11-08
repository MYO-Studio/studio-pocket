// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log

/**
 * The player's tiny design system, ported from the author's earlier Y1 player: a fixed 480x360
 * logical canvas, five colours, one typeface and the drawing helpers every screen needs.
 * Screens draw in logical units only; [PlayerView] scales and letterboxes to the real panel.
 * Nothing is smaller than [TEXT]: the Y2's 2.4" panel is read at arm's length.
 */
object Ui {
    const val W = 480
    const val H = 360

    val bg = 0xFF1C1B22.toInt()
    val ink = 0xFFF5F4F8.toInt()
    val inkDim = 0xFF9C9AAB.toInt()
    /** Brand purple (the desktop's brand-400; lighter than brand-500 so it reads on the dark panel). */
    val accent = 0xFF9467E0.toInt()
    /** Warnings stay amber, as on the desktop. */
    val warn = 0xFFF2B33D.toInt()

    const val TEXT = 18f
    const val LINE = 20f
    const val MARGIN = 12f

    private const val TAG = "Ui"
    private const val FONT_ASSET = "fonts/Nunito-SemiBold.ttf"
    private const val ELLIPSIS = "…"

    @Volatile
    private var typeface: Typeface? = null

    /** Nunito from assets, loaded once; falls back to the platform sans-serif. Call at startup. */
    fun font(ctx: Context): Typeface {
        typeface?.let { return it }
        val loaded = try {
            Typeface.createFromAsset(ctx.applicationContext.assets, FONT_ASSET)
        } catch (e: Exception) {
            Log.w(TAG, "font asset $FONT_ASSET unavailable: $e")
            null
        }
        val result = loaded ?: Typeface.create("sans-serif", Typeface.BOLD)
        typeface = result
        return result
    }

    fun text(size: Float, color: Int, bold: Boolean = false): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.textSize = if (size < TEXT) TEXT else size
        p.typeface = typeface ?: Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        p.isFakeBoldText = bold
        return p
    }

    /** [a] mixed [fraction] (0..1) of the way towards [b], opaque. */
    fun blend(a: Int, b: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val r = ((a shr 16) and 0xFF) + (((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * f).toInt()
        val g = ((a shr 8) and 0xFF) + (((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * f).toInt()
        val bl = (a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun fill(color: Int): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.style = Paint.Style.FILL
        return p
    }

    /** [s] shortened with a trailing ellipsis until it measures at most [maxW] under [p]. */
    fun ellipsize(p: Paint, s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 0) {
            end--
            val candidate = s.substring(0, end) + ELLIPSIS
            if (p.measureText(candidate) <= maxW) return candidate
        }
        return ""
    }

    fun centred(c: Canvas, w: Int, s: String, p: Paint, y: Float) {
        if (s.isEmpty()) return
        c.drawText(s, (w - p.measureText(s)) / 2f, y, p)
    }
}
