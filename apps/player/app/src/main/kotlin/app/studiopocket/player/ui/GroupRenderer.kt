// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.studiopocket.player.library.PlaylistGroup

/**
 * The painter for [GroupsScreen]: one group's square (surface, pixel icon, selection ring) with
 * its label underneath. Like [CardRenderer], every paint and rect is allocated once and reused,
 * and the built-in glyphs are made once. The icon is the 16x16 drawn at [ICON_SIZE] with
 * filtering off, so it keeps the hard pixel edges a Yoto shows.
 */
class GroupRenderer {
    private val surface = Ui.fill(SURFACE)
    private val iconPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Ui.text(LABEL_SIZE, Ui.ink)
    private val sub = Ui.text(Ui.TEXT, Ui.inkDim)
    private val rect = RectF()
    private val fitted = HashMap<String, Fitted>()
    // Built with the renderer, not in the first draw() that needs them.
    private val clock: Bitmap = PixelIcon.glyph(Glyphs.CLOCK)
    private val grid: Bitmap = PixelIcon.glyph(Glyphs.GRID)
    private val folder: Bitmap = PixelIcon.glyph(Glyphs.FOLDER)

    init {
        ring.style = Paint.Style.STROKE; ring.strokeWidth = CardRenderer.RING_WIDTH; ring.color = Ui.accent
    }

    /**
     * [g]'s square with its left edge at [left], and the label under it. [subtitle] is the dim
     * second line ("4 playlists"), drawn only for the selected square; [inert] dims an empty Recent.
     */
    fun square(c: Canvas, g: PlaylistGroup, left: Float, alpha: Int, selected: Boolean, inert: Boolean, subtitle: String?) {
        val a = if (inert) alpha * INERT_ALPHA / 255 else alpha
        rect.set(left, SQUARE_TOP, left + SQUARE, SQUARE_TOP + SQUARE)
        surface.alpha = a
        c.drawRoundRect(rect, CardRenderer.CARD_RADIUS, CardRenderer.CARD_RADIUS, surface)
        val icon = iconFor(g)
        if (icon != null) {
            val inset = (SQUARE - ICON_SIZE) / 2f
            rect.set(left + inset, SQUARE_TOP + inset, left + inset + ICON_SIZE, SQUARE_TOP + inset + ICON_SIZE)
            iconPaint.alpha = a
            c.drawBitmap(icon, null, rect, iconPaint)
        }
        if (selected) {
            val out = CardRenderer.RING_GAP + CardRenderer.RING_WIDTH / 2f
            rect.set(left - out, SQUARE_TOP - out, left + SQUARE + out, SQUARE_TOP + SQUARE + out)
            c.drawRoundRect(rect, CardRenderer.CARD_RADIUS + out, CardRenderer.CARD_RADIUS + out, ring)
        }
        val mid = left + SQUARE / 2f
        label.alpha = a
        val t = fit(label, g.key, g.title, LABEL_MAX)
        c.drawText(t, mid - label.measureText(t) / 2f, LABEL_BASELINE, label)
        if (subtitle != null) {
            sub.alpha = a
            c.drawText(subtitle, mid - sub.measureText(subtitle) / 2f, SUB_BASELINE, sub)
        }
    }

    /** The group's own icon once decoded (nothing while it decodes), else the built-in glyph for its kind. */
    private fun iconFor(g: PlaylistGroup): Bitmap? {
        val file = g.iconFile
        if (file != null) {
            PixelIcon.decoded(file)?.let { return it }
            if (!PixelIcon.hasFailed(file)) return null
        }
        return when (g.kind) {
            PlaylistGroup.Kind.RECENT -> clock
            PlaylistGroup.Kind.ALL -> grid
            PlaylistGroup.Kind.USER -> folder
        }
    }

    private fun fit(paint: Paint, key: String, s: String, maxW: Float): String {
        val hit = fitted[key]
        if (hit != null && hit.source == s) return hit.text
        val text = Ui.ellipsize(paint, s, maxW)
        if (fitted.size >= MAX_FITTED) fitted.clear()
        fitted[key] = Fitted(s, text)
        return text
    }

    private class Fitted(val source: String, val text: String)

    companion object {
        val SURFACE: Int = Ui.blend(Ui.bg, Ui.ink, 0.10f)
        const val SQUARE = 160f
        const val SQUARE_TOP = 52f
        val SQUARE_LEFT: Float = (Ui.W - SQUARE) / 2f
        /** The 16x16 icon at 7x. */
        const val ICON_SIZE = 112f
        const val LABEL_SIZE = 22f
        const val LABEL_MAX = 220f
        const val LABEL_BASELINE = SQUARE_TOP + SQUARE + 36f
        const val SUB_BASELINE = LABEL_BASELINE + 26f
        const val INERT_ALPHA = 110
        private const val MAX_FITTED = 40
    }
}
