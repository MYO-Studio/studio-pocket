// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Canvas
import app.studiopocket.player.settings.TextLayout

/**
 * A scrolling page of plain text under the settings title: About > Licences shows the APK's licence
 * texts on it. [wrap] lays a text out to the screen's width with the same paint it is drawn with;
 * [app.studiopocket.player.settings.SettingsController] keeps the lines and the scroll position and
 * hands them over through [update]. The footnote shows where in the text the screen is.
 */
class TextScreen : Screen() {
    class State(val title: String, val lines: List<String>, val top: Int, val footnote: String?)

    private val titlePaint = Ui.text(SettingsScreen.TITLE_SIZE, Ui.ink, bold = true)
    private val body = Ui.text(Ui.TEXT, Ui.ink)
    private val foot = Ui.text(Ui.TEXT, Ui.inkDim)
    private var state = State("", emptyList(), 0, null)

    fun wrap(text: String): List<String> = TextLayout.wrap(text, Ui.W - 2 * Ui.MARGIN) { body.measureText(it) }

    fun update(s: State) {
        state = s
        invalidate()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        c.drawColor(Ui.bg)
        val s = state
        c.drawText(Ui.ellipsize(titlePaint, s.title, w - 2 * Ui.MARGIN), Ui.MARGIN, SettingsScreen.TITLE_BASELINE, titlePaint)
        val end = minOf(s.lines.size, s.top + VISIBLE)
        for (i in s.top until end) {
            c.drawText(s.lines[i], Ui.MARGIN, TEXT_TOP + (i - s.top + 1) * LINE_H, body)
        }
        s.footnote?.let { c.drawText(Ui.ellipsize(foot, it, w - 2 * Ui.MARGIN), Ui.MARGIN, h - SettingsScreen.FOOT_BOTTOM, foot) }
    }

    companion object {
        /** Lines on one screen: from under the title to above the footnote. */
        const val VISIBLE = 11
        const val TEXT_TOP = 46f
        const val LINE_H = 24f
    }
}
