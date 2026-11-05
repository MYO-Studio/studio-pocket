// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class FormatException(message: String) : Exception(message)

/**
 * One-to-one mirror of packages/library-format/src/validate.ts. Uses org.json because it is
 * part of the Android 4.4 platform; no other JSON library is available without a dependency.
 */
object LibraryParser {
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    fun isId(value: Any?): Boolean = value is String && ID.matches(value)

    /** True for `<dir>/<file name>` under the same file-name rules as covers and icons. */
    fun isFileIn(value: Any?, dir: String): Boolean {
        if (value !is String || value.length > 160) return false
        val parts = value.split('/')
        return parts.size == 2 && parts[0] == dir && FILE_NAME.matches(parts[1])
    }

    /** True when [o] carries [format] at the version this build understands; for the tolerant readers. */
    fun hasHeader(o: JSONObject, format: String): Boolean = o.optString("format") == format && o.opt("formatVersion").let { it is Int && it == LibraryFormat.VERSION }
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val MIME_TYPES = setOf("audio/mpeg", "audio/mp4", "audio/aac")
    private const val MAX_TITLE = 200
    private const val MAX_TRACKS = 500

    fun parseLibraryIndex(text: String): LibraryIndex {
        val o = obj(text, "library")
        header(o, LibraryFormat.LIBRARY, "library")
        val raw = o.optJSONArray("playlists") ?: throw FormatException("library.playlists: playlists must be an array")
        val playlists = (0 until raw.length()).map { i -> parseIndexEntry(raw.optJSONObject(i) ?: throw FormatException("library.playlists[$i]: expected an object"), "library.playlists[$i]") }
        if (playlists.map { it.id }.toSet().size != playlists.size) throw FormatException("library.playlists: playlist ids must be unique")
        return LibraryIndex(str(o, "generatedAt", "library", 40), str(o, "generator", "library", 100), playlists)
    }

    private fun parseIndexEntry(o: JSONObject, path: String): LibraryIndexEntry {
        val id = id(o, "id", path)
        val dir = str(o, "dir", path, 60)
        if (dir != "${LibraryFormat.PLAYLISTS_DIR}/$id") throw FormatException("$path.dir: dir must be playlists/$id")
        val sha = str(o, "manifestSha256", path, 64)
        if (!SHA256.matches(sha)) throw FormatException("$path.manifestSha256: expected hex SHA-256")
        return LibraryIndexEntry(id, str(o, "title", path), dir, int(o, "trackCount", path, 1), long(o, "totalBytes", path, 1), sha)
    }

    fun parsePlaylistManifest(text: String): PlaylistManifest {
        val path = "manifest"
        val o = obj(text, path)
        header(o, LibraryFormat.PLAYLIST, path)
        val raw = o.optJSONArray("tracks")
        if (raw == null || raw.length() == 0 || raw.length() > MAX_TRACKS) throw FormatException("$path.tracks: tracks must be a non-empty array (max $MAX_TRACKS)")
        val tracks = (0 until raw.length()).map { i -> parseTrack(raw.optJSONObject(i) ?: throw FormatException("$path.tracks[$i]: expected an object"), "$path.tracks[$i]") }
        if (tracks.map { it.id }.toSet().size != tracks.size) throw FormatException("$path.tracks: track ids must be unique")
        if (tracks.map { it.file }.toSet().size != tracks.size) throw FormatException("$path.tracks: track files must be unique")
        return PlaylistManifest(
            id = id(o, "id", path),
            title = str(o, "title", path),
            author = if (o.has("author") && !o.isNull("author")) str(o, "author", path) else null,
            cover = optRelFile(o, "cover", path),
            icon = optRelFile(o, "icon", path),
            tracks = tracks
        )
    }

    private fun parseTrack(o: JSONObject, path: String): Track {
        val mime = str(o, "mimeType", path, 32)
        if (mime !in MIME_TYPES) throw FormatException("$path.mimeType: unsupported mimeType $mime")
        val sha = str(o, "sha256", path, 64)
        if (!SHA256.matches(sha)) throw FormatException("$path.sha256: expected hex SHA-256")
        val icon = if (!o.has("icon") || o.isNull("icon")) null else relFile(o, "icon", path, "icons")
        return Track(id(o, "id", path), str(o, "title", path), relFile(o, "file", path, "tracks"), long(o, "durationMs", path, 0), long(o, "sizeBytes", path, 1), sha, mime, icon)
    }

    fun parsePlaybackState(text: String): PlaybackState {
        val path = "state"
        val o = obj(text, path)
        header(o, LibraryFormat.STATE, path)
        return PlaybackState(id(o, "playlistId", path), int(o, "trackIndex", path, 0), long(o, "positionMs", path, 0), str(o, "updatedAt", path, 40))
    }

    fun serializePlaybackState(state: PlaybackState): String = JSONObject().apply {
        put("format", LibraryFormat.STATE)
        put("formatVersion", LibraryFormat.VERSION)
        put("playlistId", state.playlistId)
        put("trackIndex", state.trackIndex)
        put("positionMs", state.positionMs)
        put("updatedAt", state.updatedAt)
    }.toString(2) + "\n"

    fun serializeDeviceMarker(deviceId: String, createdAt: String, playerVersion: String): String = JSONObject().apply {
        put("format", LibraryFormat.DEVICE)
        put("formatVersion", LibraryFormat.VERSION)
        put("deviceId", deviceId)
        put("createdAt", createdAt)
        put("createdBy", "studio-pocket-player")
        put("playerVersion", playerVersion)
    }.toString(2) + "\n"

    // --- helpers mirroring validate.ts ---

    private fun obj(text: String, path: String): JSONObject = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw FormatException("$path: invalid JSON: ${e.message}")
    }

    private fun header(o: JSONObject, format: String, path: String) {
        if (o.optString("format") != format) throw FormatException("$path.format: format must be \"$format\"")
        val version = o.opt("formatVersion")
        if (version !is Int || version != LibraryFormat.VERSION) throw FormatException("$path.formatVersion: unsupported formatVersion $version (this build understands ${LibraryFormat.VERSION})")
    }

    private fun str(o: JSONObject, key: String, path: String, max: Int = MAX_TITLE): String {
        val v = o.opt(key)
        if (v !is String || v.isEmpty() || v.length > max) throw FormatException("$path.$key: expected a non-empty string (max $max)")
        return v
    }

    private fun int(o: JSONObject, key: String, path: String, min: Int): Int {
        val v = o.opt(key)
        if (v !is Int || v < min) throw FormatException("$path.$key: expected an integer >= $min")
        return v
    }

    private fun long(o: JSONObject, key: String, path: String, min: Long): Long {
        val v = o.opt(key)
        val n = when (v) { is Int -> v.toLong(); is Long -> v; else -> null }
        if (n == null || n < min) throw FormatException("$path.$key: expected an integer >= $min")
        return n
    }

    private fun id(o: JSONObject, key: String, path: String): String {
        val v = str(o, key, path, 40)
        if (!ID.matches(v)) throw FormatException("$path.$key: invalid id (a-z, 0-9, \"-\", max 40)")
        return v
    }

    private fun relFile(o: JSONObject, key: String, path: String, requiredDir: String?): String {
        val v = str(o, key, path, 160)
        val parts = v.split('/')
        if (parts.any { !FILE_NAME.matches(it) }) throw FormatException("$path.$key: invalid relative file path")
        if (requiredDir != null && (parts.size != 2 || parts[0] != requiredDir)) throw FormatException("$path.$key: must be $requiredDir/<name>")
        return v
    }

    private fun optRelFile(o: JSONObject, key: String, path: String): String? =
        if (!o.has(key) || o.isNull(key)) null else relFile(o, key, path, null)
}
