// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Per-playlist resume positions under STUDIOPOCKET/state/, written atomically (.part + rename)
 * so a power loss mid-write never leaves a half file. The desktop preserves this folder.
 */
class StateStore(private val root: File) {
    private val dir = File(root, LibraryFormat.STATE_DIR)

    fun read(playlistId: String): PlaybackState? {
        val file = File(dir, "$playlistId.json")
        if (!file.isFile) return null
        return try {
            LibraryParser.parsePlaybackState(file.readText()).takeIf { it.playlistId == playlistId }
        } catch (e: FormatException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    fun write(state: PlaybackState): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val target = File(dir, "${state.playlistId}.json")
        val partial = File(dir, "${state.playlistId}.json${LibraryFormat.PARTIAL_SUFFIX}")
        return try {
            partial.writeText(LibraryParser.serializePlaybackState(state))
            // rename(2) replaces atomically, so the saved position is never missing mid-write. Only
            // where the filesystem refuses to replace do we fall back to delete-then-rename.
            partial.renameTo(target) || run {
                target.delete()
                partial.renameTo(target)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates device.json if the desktop has not already, so the computer can recognise the player,
     * and keeps its playerVersion current so the desktop can tell what the player understands (groups
     * need 0.5.0). An existing marker keeps its identity; one that cannot be read is left alone.
     */
    fun ensureDeviceMarker(playerVersion: String): Boolean {
        val file = File(root, LibraryFormat.DEVICE_FILE)
        if (file.isFile) return refreshPlayerVersion(file, playerVersion)
        if (!root.isDirectory && !root.mkdirs()) return false
        return writeMarker(file, LibraryParser.serializeDeviceMarker(UUID.randomUUID().toString(), now(), playerVersion))
    }

    private fun refreshPlayerVersion(file: File, playerVersion: String): Boolean {
        val marker = try { JSONObject(file.readText()) } catch (e: Exception) { return true }
        // "?" is what a failed version lookup gives; it must never replace a real version.
        if (playerVersion == "?" || !LibraryParser.hasHeader(marker, LibraryFormat.DEVICE) || marker.optString("playerVersion") == playerVersion) return true
        marker.put("playerVersion", playerVersion)
        return writeMarker(file, marker.toString(2) + "\n")
    }

    /**
     * Renames over the old marker and never deletes it first, which would risk a new identity after a
     * power cut. If the rename can't replace it, the old marker stays and the refresh is tried again
     * at the next start.
     */
    private fun writeMarker(file: File, text: String): Boolean = try {
        val partial = File(root, LibraryFormat.DEVICE_FILE + LibraryFormat.PARTIAL_SUFFIX)
        partial.writeText(text)
        partial.renameTo(file) || run { partial.delete(); false }
    } catch (e: Exception) {
        false
    }

    companion object {
        fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    }
}
