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
 * Small decoded pictures for the groups screen's stacks: playlist covers and custom group
 * images, sampled down to about tile height so up to [CACHE_SIZE] fit in a few MB. Separate from
 * [Artwork], whose handful of full-height covers belong to the cover flow. Same rules: nothing
 * decodes on the main thread, [bitmap] returns `null` until [Listener.onThumbDecoded] fires, and
 * nothing is ever decoded at full size.
 */
object CoverThumbs {
    interface Listener {
        fun onThumbDecoded(path: String)
    }

    private const val TAG = "CoverThumbs"
    /** Tall enough for the selected tile's 158 px front card without upscaling much. */
    private const val MIN_HEIGHT = 170
    private const val CACHE_SIZE = 40

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > CACHE_SIZE
    }

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** [file]'s thumbnail, or `null` while it decodes or if it cannot be read. */
    fun bitmap(file: File): Bitmap? {
        val path = file.path
        cache[path]?.let { return it }
        if (failed.contains(path) || !pending.add(path)) return null
        Decoders.io.execute { decodeInBackground(path) }
        return null
    }

    fun clear() { cache.clear(); failed.clear() }

    private fun decodeInBackground(path: String) {
        val bitmap = try { decode(path) } catch (t: Throwable) { Log.e(TAG, "decode of $path failed", t); null }
        main.post {
            pending.remove(path)
            if (bitmap == null) failed.add(path) else cache[path] = bitmap
            for (l in ArrayList(listeners)) l.onThumbDecoded(path)
        }
    }

    private fun decode(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, options)
    }

    /** The largest power of two that keeps [height] at or above [MIN_HEIGHT]. */
    fun sampleSize(height: Int): Int {
        var sample = 1
        while (height / (sample * 2) >= MIN_HEIGHT) sample *= 2
        return sample
    }
}
