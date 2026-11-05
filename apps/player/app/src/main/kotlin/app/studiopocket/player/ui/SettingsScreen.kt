// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.studiopocket.player.settings.SettingsRow

/**
 * The hidden settings list: a title, up to [VISIBLE] rows scrolled to keep the selection in view,
 * and an optional footnote. The selected row sits on an accent bar; while a value is being edited
 * the bar becomes an outline and the value turns accent between ‹ ›. Disabled rows are dimmed.
 * What to show comes from [app.studiopocket.player.settings.SettingsController] via [update].
 */
class SettingsScreen : Screen() {
    class State(
        val title: String,
        val rows: List<SettingsRow>,
        val selected: Int,
        val editing: Boolean,
        val editIndex: Int,
        val footnote: String?,
    )

    private val titlePaint = Ui.text(TITLE_SIZE, Ui.ink, bold = true)
    private val label = Ui.text(Ui.TEXT, Ui.ink)
    private val value = Ui.text(Ui.TEXT, Ui.inkDim)
    private val foot = Ui.text(Ui.TEXT, Ui.inkDim)
    private val bar = Ui.fill(Ui.accent)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var state = State("", emptyList(), 0, false, 0, null)
    private var top = 0

    init {
        outline.style = Paint.Style.STROKE; outline.strokeWidth = OUTLINE_WIDTH; outline.color = Ui.accent
    }

    fun update(s: State) {
        if (s.title != state.title) top = 0
        state = s
        val selected = s.selected.coerceIn(0, maxOf(0, s.rows.lastIndex))
        if (selected < top) top = selected
        if (selected >= top + VISIBLE) top = selected - VISIBLE + 1
        top = top.coerceIn(0, maxOf(0, s.rows.size - VISIBLE))
        invalidate()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        c.drawColor(Ui.bg)
        val s = state
        c.drawText(Ui.ellipsize(titlePaint, s.title, w - 2 * Ui.MARGIN), Ui.MARGIN, TITLE_BASELINE, titlePaint)
        val end = minOf(s.rows.size, top + VISIBLE)
        for (i in top until end) drawRow(c, w, s.rows[i], ROWS_TOP + (i - top) * ROW_H, i == s.selected, s)
        s.footnote?.let { c.drawText(Ui.ellipsize(foot, it, w - 2 * Ui.MARGIN), Ui.MARGIN, h - FOOT_BOTTOM, foot) }
    }

    private fun drawRow(c: Canvas, w: Int, row: SettingsRow, rowTop: Float, selected: Boolean, s: State) {
        val editing = selected && s.editing && row.value != null
        rect.set(Ui.MARGIN, rowTop + BAR_INSET, w - Ui.MARGIN, rowTop + ROW_H - BAR_INSET)
        if (editing) c.drawRoundRect(rect, BAR_RADIUS, BAR_RADIUS, outline)
        else if (selected) c.drawRoundRect(rect, BAR_RADIUS, BAR_RADIUS, bar)
        val onBar = selected && !editing
        label.color = when {
            onBar -> Ui.bg
            row.enabled -> Ui.ink
            else -> Ui.inkDim
        }
        val baseline = rowTop + ROW_H / 2f - (label.ascent() + label.descent()) / 2f
        val left = Ui.MARGIN + TEXT_PAD
        val right = w - Ui.MARGIN - TEXT_PAD
        var labelMax = right - left
        row.value?.let { v ->
            val text = v.labels.getOrNull(if (editing) s.editIndex else v.index) ?: ""
            val shown = if (editing) "‹ $text ›" else text
            value.color = when {
                editing -> Ui.accent
                onBar -> Ui.bg
                else -> Ui.inkDim
            }
            val vw = value.measureText(shown)
            c.drawText(shown, right - vw, baseline, value)
            labelMax = right - left - vw - VALUE_GAP
        }
        c.drawText(Ui.ellipsize(label, row.label, labelMax), left, baseline, label)
    }

    companion object {
        const val VISIBLE = 6
        const val TITLE_SIZE = 22f
        const val TITLE_BASELINE = 34f
        const val ROWS_TOP = 50f
        const val ROW_H = 44f
        const val BAR_INSET = 3f
        const val BAR_RADIUS = 8f
        const val OUTLINE_WIDTH = 2f
        const val TEXT_PAD = 10f
        const val VALUE_GAP = 16f
        const val FOOT_BOTTOM = 10f
    }
}
