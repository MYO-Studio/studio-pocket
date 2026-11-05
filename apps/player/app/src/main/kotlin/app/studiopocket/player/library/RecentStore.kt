// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * STUDIOPOCKET/recent.json: the playlists played last, most recent first. A list rather than a sort
 * of state/ timestamps because the Y2 has no network time and its clock can go backwards after
 * a flat battery. Player-owned; the desktop only reads it. Written atomically (.part + rename).
 */
class RecentStore(private val root: File) {
    private val file = File(root, LibraryFormat.RECENT_FILE)

    fun read(): List<String> {
        if (!file.isFile) return emptyList()
        return try { parse(file.readText()) } catch (e: Exception) { emptyList() }
    }

    /** Moves [playlistId] to the front and keeps [LibraryFormat.RECENT_KEEP] entries. */
    fun recordPlayed(playlistId: String): Boolean {
        val current = read()
        if (current.firstOrNull() == playlistId) return true // already first: no FAT write on the key press
        val next = (listOf(playlistId) + current.filter { it != playlistId }).take(LibraryFormat.RECENT_KEEP)
        if (!root.isDirectory && !root.mkdirs()) return false
        val partial = File(root, LibraryFormat.RECENT_FILE + LibraryFormat.PARTIAL_SUFFIX)
        return try {
            partial.writeText(serialize(next, StateStore.now()))
            if (file.exists()) file.delete()
            partial.renameTo(file)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** Tolerant: up to [LibraryFormat.MAX_RECENT] entries are read, invalid and repeated ids dropped. */
        fun parse(text: String): List<String> {
            val o = try { JSONObject(text) } catch (e: JSONException) { return emptyList() }
            if (!LibraryParser.hasHeader(o, LibraryFormat.RECENT)) return emptyList()
            return Groups.idList(o.optJSONArray("playlists"), LibraryFormat.MAX_RECENT)
        }

        fun serialize(playlists: List<String>, updatedAt: String): String = JSONObject().apply {
            put("format", LibraryFormat.RECENT)
            put("formatVersion", LibraryFormat.VERSION)
            put("updatedAt", updatedAt)
            put("playlists", JSONArray(playlists))
        }.toString(2) + "\n"
    }
}
