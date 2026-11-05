// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import java.io.File
import java.io.IOException

/**
 * Reads STUDIOPOCKET/ from the external storage root and returns only the playlists that are
 * complete: manifest parses, every track exists with exactly the declared size. Mirrors
 * packages/library-format/src/complete.ts. Hashes are not verified on the device: it is
 * slow on this CPU and the size check already catches interrupted copies.
 */
class LibraryLoader(private val root: File) {
    companion object {
        const val NO_LIBRARY_YET = "No library yet; sync from the computer"
    }

    fun load(): LoadedLibrary {
        // A fresh player has neither the folder nor library.json; it creates the folder itself moments
        // later when it saves its state, so both cases read the same and never sound like a fault.
        val indexFile = File(root, LibraryFormat.LIBRARY_FILE)
        if (!root.isDirectory || !indexFile.exists()) return LoadedLibrary.empty(NO_LIBRARY_YET)
        val index = try {
            LibraryParser.parseLibraryIndex(indexFile.readText())
        } catch (e: FormatException) {
            return LoadedLibrary.empty(e.message)
        } catch (e: IOException) {
            return LoadedLibrary.empty("could not read library.json: ${e.message}")
        }
        val playlists = mutableListOf<LoadedPlaylist>()
        val hidden = mutableListOf<Pair<String, String>>()
        for (entry in index.playlists) {
            val dir = File(root, entry.dir)
            when (val result = checkPlaylist(dir, entry)) {
                is Check.Ok -> playlists += LoadedPlaylist(dir, result.manifest)
                is Check.Bad -> hidden += entry.id to result.reason
            }
        }
        return LoadedLibrary(playlists, hidden, File(root, LibraryFormat.SYNC_LOCK_FILE).exists(), null)
    }

    sealed class Check {
        data class Ok(val manifest: PlaylistManifest) : Check()
        data class Bad(val reason: String) : Check()
    }

    fun checkPlaylist(dir: File, entry: LibraryIndexEntry): Check {
        if (File(dir, LibraryFormat.UPDATE_LOCK_FILE).exists()) return Check.Bad("update interrupted")
        val manifestFile = File(dir, LibraryFormat.MANIFEST_FILE)
        if (!manifestFile.exists()) return Check.Bad("missing manifest.json")
        val manifest = try {
            LibraryParser.parsePlaylistManifest(manifestFile.readText())
        } catch (e: FormatException) {
            return Check.Bad(e.message ?: "bad manifest")
        } catch (e: IOException) {
            return Check.Bad("cannot read manifest.json")
        }
        if (manifest.id != entry.id) return Check.Bad("manifest id ${manifest.id} != ${entry.id}")
        if (manifest.tracks.size != entry.trackCount) return Check.Bad("trackCount ${entry.trackCount} != ${manifest.tracks.size}")
        for (track in manifest.tracks) {
            val file = File(dir, track.file)
            if (!file.isFile) return Check.Bad("missing ${track.file}")
            if (file.length() != track.sizeBytes) return Check.Bad("${track.file} is ${file.length()} B, expected ${track.sizeBytes}")
        }
        return Check.Ok(manifest)
    }
}
