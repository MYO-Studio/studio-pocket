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
import android.graphics.RectF
import android.graphics.Shader
import app.studiopocket.player.library.PlaylistGroup
import app.studiopocket.player.library.PlaylistGroup.Face
import java.io.File

/**
 * The painter for [GroupsScreen]: one group's tile, with the label underneath. A tile is one
 * card (an icon or picture on its own) or a stack of 2-3 real pictures. A stack is closed (the
 * cards behind peek out a little, one each side for three, to the right for two) or, as [open]
 * goes to 1, fanned: the front card stays upright and the cards behind swing further out the same
 * way about their bottom centres. A single card never fans.
 *
 * Every paint and rect is allocated once and reused, and the built-in glyphs are made once.
 * Pixel icons are drawn with filtering off so they keep their hard edges. Covers and custom
 * images come from [CoverThumbs]; a card whose picture is still decoding is drawn plain until it lands.
 * Faded tiles are drawn as opaque cards with a background-coloured veil on each, so the cards
 * of a dimmed stack still hide one another.
 */
class GroupRenderer {
    private val surface = Ui.fill(SURFACE)
    private val blank = Ui.fill(BLANK)
    private val shadow = Ui.fill(0xFF000000.toInt())
    private val veil = Ui.fill(Ui.bg)
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artMatrix = Matrix()
    private val shaders = HashMap<Bitmap, BitmapShader>()
    private val iconPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
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
        halo.style = Paint.Style.STROKE; halo.strokeWidth = CardRenderer.RING_GAP + 1f; halo.color = Ui.bg
        shadow.alpha = SHADOW_ALPHA
    }

    /**
     * [g]'s stack with its front card centred on [cx], and the label under it. [open] is the fan,
     * 0 (closed) to 1 (fanned). [subtitle] is the dim second line ("4 playlists"), drawn only for
     * the selected tile; [inert] dims an empty Recent.
     */
    fun tile(c: Canvas, g: PlaylistGroup, cx: Float, alpha: Int, selected: Boolean, inert: Boolean, subtitle: String?, open: Float) {
        val a = if (inert) alpha * INERT_ALPHA / 255 else alpha
        val faces = g.faces
        val t = open.coerceIn(0f, 1f)
        val n = minOf(faces.size, STACK_CARDS)
        // Three cards: one peeks out each side. Two: the one behind peeks out to the right.
        val sides = if (n == 3) THREE_SIDES else TWO_SIDES
        for (k in n - 1 downTo 1) {
            val side = sides[k]
            val dx = side * lerp(CLOSED_DX, OPEN_DX, t)
            val angle = side * lerp(CLOSED_ANGLE, OPEN_ANGLE, t)
            val save = c.save()
            c.translate(dx, 0f)
            c.rotate(angle, cx, CARD_BOTTOM)
            card(c, g, faces[k], cx, a)
            c.restoreToCount(save)
        }
        if (faces.isNotEmpty()) card(c, g, faces[0], cx, a)
        if (selected) {
            // Clear the gap between card and ring, so the fanned cards do not show through it.
            val gap = CardRenderer.RING_GAP / 2f
            rect.set(cx - CARD_W / 2f - gap, CARD_TOP - gap, cx + CARD_W / 2f + gap, CARD_BOTTOM + gap)
            c.drawRoundRect(rect, CARD_RADIUS + gap, CARD_RADIUS + gap, halo)
            val out = CardRenderer.RING_GAP + CardRenderer.RING_WIDTH / 2f
            rect.set(cx - CARD_W / 2f - out, CARD_TOP - out, cx + CARD_W / 2f + out, CARD_BOTTOM + out)
            ring.alpha = alpha
            c.drawRoundRect(rect, CARD_RADIUS + out, CARD_RADIUS + out, ring)
        }
        label.alpha = a
        val text = fit(label, g.key, g.title, LABEL_MAX)
        c.drawText(text, cx - label.measureText(text) / 2f, LABEL_BASELINE, label)
        if (subtitle != null) {
            sub.alpha = a
            c.drawText(subtitle, cx - sub.measureText(subtitle) / 2f, SUB_BASELINE, sub)
        }
    }

    /** One upright card centred on [cx]; the caller has already turned the canvas for cards behind. */
    private fun card(c: Canvas, g: PlaylistGroup, face: Face, cx: Float, alpha: Int) {
        rect.set(cx - CARD_W / 2f, CARD_TOP + SHADOW_DY, cx + CARD_W / 2f, CARD_BOTTOM + SHADOW_DY)
        shadow.alpha = SHADOW_ALPHA * alpha / 255
        c.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, shadow)
        rect.set(cx - CARD_W / 2f, CARD_TOP, cx + CARD_W / 2f, CARD_BOTTOM)
        when (face) {
            is Face.Icon -> {
                c.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, surface)
                val icon = iconFor(g)
                if (icon != null) {
                    val top = CARD_TOP + (CARD_H - ICON_SIZE) / 2f
                    rect.set(cx - ICON_SIZE / 2f, top, cx + ICON_SIZE / 2f, top + ICON_SIZE)
                    c.drawBitmap(icon, null, rect, iconPaint)
                    rect.set(cx - CARD_W / 2f, CARD_TOP, cx + CARD_W / 2f, CARD_BOTTOM)
                }
            }
            is Face.Image -> picture(c, face.file)
            is Face.Cover -> picture(c, face.playlist.coverFile)
        }
        if (alpha < 255) {
            veil.alpha = 255 - alpha
            c.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, veil)
        }
    }

    /** [file] centre-cropped into [rect], or a blank card while it decodes or when there is none. */
    private fun picture(c: Canvas, file: File?) {
        val bitmap = file?.let { CoverThumbs.bitmap(it) }
        if (bitmap == null) { c.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, blank); return }
        val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
        val scale = maxOf(rect.width() / bw, rect.height() / bh)
        artMatrix.setScale(scale, scale)
        artMatrix.postTranslate(rect.centerX() - bw * scale / 2f, rect.centerY() - bh * scale / 2f)
        val shader = shaderFor(bitmap)
        shader.setLocalMatrix(artMatrix)
        art.shader = shader
        c.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, art)
    }

    private fun shaderFor(bitmap: Bitmap): BitmapShader {
        shaders[bitmap]?.let { return it }
        if (shaders.size >= MAX_SHADERS) shaders.clear()
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaders[bitmap] = it }
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

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        /** The front card of an icon tile. */
        val SURFACE: Int = Ui.blend(Ui.bg, Ui.ink, 0.075f)
        /** A picture card while it decodes: a shade lighter than [SURFACE] so it reads in a stack. */
        val BLANK: Int = Ui.blend(Ui.bg, Ui.ink, 0.11f)
        const val STACK_CARDS = 3
        const val CARD_W = 100f
        val CARD_H: Float = Math.round(CARD_W / LibraryScreen.CARD_RATIO).toFloat()
        const val CARD_BOTTOM = 230f
        val CARD_TOP: Float = CARD_BOTTOM - CARD_H
        /** Where neighbours scale about: the front card's centre. */
        val CARD_MID_Y: Float = CARD_BOTTOM - CARD_H / 2f
        /** [CardRenderer.CARD_RADIUS] at this card width. */
        const val CARD_RADIUS = 7f
        /** The 16x16 icon at 4x. */
        const val ICON_SIZE = 64f
        const val LABEL_SIZE = 22f
        const val LABEL_MAX = 220f
        const val LABEL_BASELINE = 282f
        const val SUB_BASELINE = 308f
        const val INERT_ALPHA = 110
        const val SHADOW_ALPHA = 110
        const val SHADOW_DY = 2f
        // Cards behind the front: how far they peek out closed, and swing out fanned. Index 0 is the front.
        private const val CLOSED_DX = 5f
        private const val CLOSED_ANGLE = 5f
        private const val OPEN_DX = 14f
        private const val OPEN_ANGLE = 14f
        private val THREE_SIDES = floatArrayOf(0f, -1f, 1f)
        private val TWO_SIDES = floatArrayOf(0f, 1f)
        private const val MAX_FITTED = 40
        private const val MAX_SHADERS = 48
    }
}
