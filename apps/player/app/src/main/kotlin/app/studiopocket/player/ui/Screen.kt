// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View

/** One full-screen scene drawn onto the 480x360 logical canvas. */
abstract class Screen {
    var host: PlayerView? = null
    abstract fun draw(c: Canvas, w: Int, h: Int)
    open fun onShow() {}
    open fun onHide() {}
    protected fun invalidate() { host?.invalidate() }
    protected fun invalidateDelayed(ms: Long) { host?.postInvalidateDelayed(ms) }
}

/**
 * The app's drawing surface: paints the current [screen] into a fixed 480x360 space, scaled
 * uniformly and centred, so screens never deal with the real panel size (on the Y2 the scale is 1).
 */
@SuppressLint("ViewConstructor")
class PlayerView(ctx: Context) : View(ctx) {
    private val bgPaint = Ui.fill(Ui.bg)
    private val letterbox = Ui.fill(0xFF000000.toInt())

    /** Drawn over [screen] when visible: the volume readout. */
    val volume = VolumeOverlay()

    var screen: Screen? = null
        set(value) {
            if (field === value) return
            field?.onHide()
            field?.host = null
            field = value
            value?.host = this
            value?.onShow()
            invalidate()
        }

    init {
        setBackgroundColor(0xFF000000.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width; val vh = height
        if (vw <= 0 || vh <= 0) return
        canvas.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), letterbox)
        val scale = minOf(vw / Ui.W.toFloat(), vh / Ui.H.toFloat())
        val saved = canvas.save()
        canvas.translate((vw - Ui.W * scale) / 2f, (vh - Ui.H * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, Ui.W.toFloat(), Ui.H.toFloat())
        canvas.drawRect(0f, 0f, Ui.W.toFloat(), Ui.H.toFloat(), bgPaint)
        screen?.draw(canvas, Ui.W, Ui.H)
        volume.draw(canvas, Ui.W, Ui.H)
        canvas.restoreToCount(saved)
    }
}
