// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * The decoded-icon cache behind [NowPlayingScreen] and [GroupsScreen], and the default note glyph (ported from
 * the author's earlier Y1 player). Icons stay 16x16; the screen scales them up with filtering off so the child
 * sees the same hard pixel edges a Yoto shows.
 */
object PixelIcon {
    interface Listener {
        fun onIconDecoded(path: String)
    }

    private val NOTE = arrayOf(
        "................", ".....#########..", ".....#########..", ".....##.....##..", ".....##.....##..",
        ".....##.....##..", ".....##.....##..", ".....##.....##..", ".....##.....##..", ".....##.....##..",
        "..#####.....##..", ".######.....##..", ".######..#####..", "..####..######..", ".........#####..", "..........###...",
    )

    private const val TAG = "PixelIcon"
    // 16x16 icons are about 1 KB each: room for every group icon (max 30) plus a playlist's worth of track icons.
    private const val CACHE_SIZE = 48
    private const val GLYPH_SIZE = 16

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > CACHE_SIZE
    }
    private var glyph: Bitmap? = null

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** [file]'s icon, the default glyph when there is none or it is unreadable, or `null` while decoding. */
    fun bitmap(file: File?): Bitmap? {
        val path = file?.path ?: return defaultGlyph()
        cache[path]?.let { return it }
        if (failed.contains(path)) return defaultGlyph()
        if (!pending.add(path)) return null
        Decoders.io.execute { decodeInBackground(path) }
        return null
    }

    /** [file]'s decoded icon, or `null` while it decodes or when it cannot be read, for callers with their own fallback glyph. */
    fun decoded(file: File): Bitmap? {
        val path = file.path
        cache[path]?.let { return it }
        if (failed.contains(path) || !pending.add(path)) return null
        Decoders.io.execute { decodeInBackground(path) }
        return null
    }

    /** True once [file] has been tried and could not be decoded; cleared with the cache. */
    fun hasFailed(file: File): Boolean = failed.contains(file.path)

    fun defaultGlyph(): Bitmap = glyph ?: glyph(NOTE).also { glyph = it }

    /** A 16x16 bitmap from rows of '#' (ink) and '.' (transparent). Callers keep the result. */
    fun glyph(rows: Array<String>): Bitmap {
        val b = Bitmap.createBitmap(GLYPH_SIZE, GLYPH_SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until GLYPH_SIZE) {
            val row = rows[y]
            for (x in 0 until GLYPH_SIZE) if (x < row.length && row[x] == '#') b.setPixel(x, y, Ui.ink)
        }
        return b
    }

    fun clear() { cache.clear(); failed.clear() }

    private fun decodeInBackground(path: String) {
        val bitmap = try { BitmapFactory.decodeFile(path) } catch (t: Throwable) { Log.e(TAG, "decode of $path failed", t); null }
        main.post {
            pending.remove(path)
            if (bitmap == null) failed.add(path) else cache[path] = bitmap
            for (l in ArrayList(listeners)) l.onIconDecoded(path)
        }
    }
}
