// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import java.io.File

/** Mirrors packages/library-format/src/types.ts, format version 1. */
object LibraryFormat {
    const val VERSION = 1
    const val LIBRARY = "studiopocket-library"
    const val PLAYLIST = "studiopocket-playlist"
    const val DEVICE = "studiopocket-device"
    const val STATE = "studiopocket-state"
    const val ROOT_DIR = "STUDIOPOCKET"
    const val LIBRARY_FILE = "library.json"
    const val PLAYLISTS_DIR = "playlists"
    const val MANIFEST_FILE = "manifest.json"
    const val STATE_DIR = "state"
    const val DEVICE_FILE = "device.json"
    const val SYNC_LOCK_FILE = "sync.lock"
    const val UPDATE_LOCK_FILE = "update.lock"
    const val PARTIAL_SUFFIX = ".part"
    const val ICONS_DIR = "icons"
    // Playlist groups (still format version 1): optional, additive root files.
    const val GROUPS = "studiopocket-groups"
    const val RECENT = "studiopocket-recent"
    const val GROUPS_FILE = "groups.json"
    const val GROUPS_DIR = "groups"
    const val RECENT_FILE = "recent.json"
    const val MAX_GROUPS = 30
    const val MAX_GROUP_TITLE = 40
    val RESERVED_GROUP_IDS = setOf("recent", "all")
    const val RECENT_KEEP = 5
    const val MAX_RECENT = 20
}

data class LibraryIndexEntry(val id: String, val title: String, val dir: String, val trackCount: Int, val totalBytes: Long, val manifestSha256: String)

data class LibraryIndex(val generatedAt: String, val generator: String, val playlists: List<LibraryIndexEntry>)

data class Track(val id: String, val title: String, val file: String, val durationMs: Long, val sizeBytes: Long, val sha256: String, val mimeType: String, val icon: String? = null)

data class PlaylistManifest(val id: String, val title: String, val author: String?, val cover: String?, val icon: String?, val tracks: List<Track>)

/** A playlist that passed the completeness check and can be shown. */
data class LoadedPlaylist(val dir: File, val manifest: PlaylistManifest) {
    val id get() = manifest.id
    val title get() = manifest.title
    val coverFile: File? get() = manifest.cover?.let { File(dir, it) }?.takeIf { it.isFile }
    val iconFile: File? get() = manifest.icon?.let { File(dir, it) }?.takeIf { it.isFile }
    fun trackFile(index: Int): File = File(dir, manifest.tracks[index].file)
    /** The track's 16x16 icon if the manifest names one and the file exists. */
    fun trackIconFile(index: Int): File? = manifest.tracks.getOrNull(index)?.icon?.let { File(dir, it) }?.takeIf { it.isFile }
}

data class LoadedLibrary(val playlists: List<LoadedPlaylist>, val hidden: List<Pair<String, String>>, val syncInProgress: Boolean, val problem: String?) {
    companion object {
        fun empty(problem: String?) = LoadedLibrary(emptyList(), emptyList(), false, problem)
    }
}

/**
 * A playlist's resume position. [finished]: it last played through to the end (the position is then
 * the top, ready to start again); any later save while playing clears it.
 */
data class PlaybackState(val playlistId: String, val trackIndex: Int, val positionMs: Long, val updatedAt: String, val finished: Boolean = false)

/** One group as groups.json defines it, after tolerant parsing ([Groups.parse]). */
data class GroupDef(
    val id: String,
    val title: String,
    val icon: String?,
    val playlists: List<String>,
    /** How the tile looks: "icon", "image", "covers" or "recent"; anything else reads as "icon". */
    val display: String = Groups.DISPLAY_ICON,
    /** The custom front-card picture, "groups/<file>", or null. */
    val image: String? = null,
    /** Up to 3 playlist ids for "covers", first = top card. */
    val covers: List<String> = emptyList(),
)
