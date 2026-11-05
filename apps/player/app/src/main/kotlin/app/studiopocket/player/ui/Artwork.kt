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
import app.studiopocket.player.library.LoadedPlaylist

/**
 * The decoded-cover cache (ported from the author's earlier Y1 player). Nothing decodes on the main thread, a
 * cover is decoded at most once, and at most [CACHE_SIZE] are held. `draw()` calls [bitmap] every
 * frame and gets the cached bitmap or `null`; a `null` queues one decode and the screen paints
 * its placeholder until [Listener.onArtworkDecoded] says the real one has landed. The file is
 * decoded by content, not by name: a PNG under `cover.jpg` decodes fine.
 */
object Artwork {
    interface Listener {
        fun onArtworkDecoded(id: String)
    }

    const val NO_COLOUR = 0

    private const val TAG = "Artwork"
    private const val MIN_HEIGHT = 320
    private const val CACHE_SIZE = 6
    private const val SAMPLE_STEP = 8

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()

    private class Entry(val bitmap: Bitmap) {
        var colour: Int = NO_COLOUR
    }

    private val cache = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > CACHE_SIZE
    }

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** The cover for [p], or `null` while it is being decoded (or if it never can be). */
    fun bitmap(p: LoadedPlaylist): Bitmap? {
        val id = p.id
        cache[id]?.let { return it.bitmap }
        if (failed.contains(id) || !pending.add(id)) return null
        val file = p.coverFile
        if (file == null) { pending.remove(id); failed.add(id); return null }
        Decoders.io.execute { decodeInBackground(id, file.path) }
        return null
    }

    fun averageColour(p: LoadedPlaylist): Int {
        val entry = cache[p.id] ?: return NO_COLOUR
        if (entry.colour == NO_COLOUR) entry.colour = average(entry.bitmap)
        return entry.colour
    }

    /** Forgets everything, so a re-synced library is decoded afresh. */
    fun clear() { cache.clear(); failed.clear() }

    private fun decodeInBackground(id: String, path: String) {
        val bitmap = try { decode(path) } catch (t: Throwable) { Log.e(TAG, "decode of $id failed", t); null }
        main.post {
            pending.remove(id)
            if (bitmap == null) failed.add(id) else cache[id] = Entry(bitmap)
            for (l in ArrayList(listeners)) l.onArtworkDecoded(id)
        }
    }

    private fun decode(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outHeight); inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeFile(path, options)
    }

    fun sampleSize(height: Int): Int {
        var sample = 1
        while (height / (sample * 2) >= MIN_HEIGHT) sample *= 2
        return sample
    }

    private fun average(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF; n++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        if (n == 0) return NO_COLOUR
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
    }
}
