// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** One square on the groups screen and the cover flow it opens onto. */
class PlaylistGroup(val kind: Kind, val key: String, val title: String, val iconFile: File?, val playlists: List<LoadedPlaylist>) {
    enum class Kind { RECENT, USER, ALL }
}

/** What the home screen shows. [flat] means no groups screen: just All's cover flow, as before groups existed. */
class GroupList(val groups: List<PlaylistGroup>, val flat: Boolean) {
    val all: PlaylistGroup get() = groups.last()

    companion object {
        val EMPTY = GroupList(listOf(PlaylistGroup(PlaylistGroup.Kind.ALL, Groups.ALL_KEY, Groups.ALL_TITLE, null, emptyList())), true)
    }
}

/**
 * Reads groups.json and turns it, the library and recent.json into the groups the child sees:
 * Recent, the user's groups in file order, then All. Pure apart from [load] and the icon file
 * check, so it is JVM-tested on the shared fixtures.
 *
 * The desktop validates strictly (packages/library-format validateGroups); the player is
 * tolerant instead, because a bad groups.json must never hide a playlist: a malformed group or
 * id is skipped, a bad icon path means the folder glyph, a too-long title is cut, and a
 * playlist listed in two groups stays in the first. A file it cannot read at all means no groups.
 */
object Groups {
    /** Show the groups screen (Recent and All only) even when no user group has anything in it. */
    const val SHOW_GROUPS_WITHOUT_USER_GROUPS = false

    const val RECENT_KEY = "recent"
    const val ALL_KEY = "all"
    const val RECENT_TITLE = "Recent"
    const val ALL_TITLE = "All"

    fun load(root: File): List<GroupDef> {
        val file = File(root, LibraryFormat.GROUPS_FILE)
        if (!file.isFile) return emptyList()
        return try { parse(file.readText()) } catch (e: Exception) { emptyList() }
    }

    fun parse(text: String): List<GroupDef> {
        val o = try { JSONObject(text) } catch (e: JSONException) { return emptyList() }
        if (!LibraryParser.hasHeader(o, LibraryFormat.GROUPS)) return emptyList()
        val raw = o.optJSONArray("groups") ?: return emptyList()
        val defs = ArrayList<GroupDef>()
        val ids = HashSet<String>()
        for (i in 0 until raw.length()) {
            if (defs.size == LibraryFormat.MAX_GROUPS) break
            val g = raw.optJSONObject(i) ?: continue
            val id = g.opt("id")
            if (!LibraryParser.isId(id) || id in LibraryFormat.RESERVED_GROUP_IDS || !ids.add(id as String)) continue
            val title = (g.opt("title") as? String)?.takeIf { it.isNotEmpty() } ?: continue
            val icon = g.opt("icon").takeIf { LibraryParser.isFileIn(it, LibraryFormat.GROUPS_DIR) } as String?
            defs += GroupDef(id, title.take(LibraryFormat.MAX_GROUP_TITLE), icon, idList(g.optJSONArray("playlists"), Int.MAX_VALUE))
        }
        return defs
    }

    /** The valid, distinct ids among the first [max] entries of [raw]. */
    internal fun idList(raw: JSONArray?, max: Int): List<String> {
        if (raw == null) return emptyList()
        val ids = LinkedHashSet<String>()
        for (i in 0 until minOf(raw.length(), max)) {
            val v = raw.opt(i)
            if (LibraryParser.isId(v)) ids += v as String
        }
        return ids.toList()
    }

    /**
     * The visible groups. User groups keep only playlists that are on the player (first group
     * wins), in file order, and are hidden when that leaves nothing. Recent is [recent] filtered
     * to the player and capped at [LibraryFormat.RECENT_KEEP]. All is the library in order.
     */
    fun build(playlists: List<LoadedPlaylist>, defs: List<GroupDef>, recent: List<String>, root: File): GroupList {
        val byId = HashMap<String, LoadedPlaylist>()
        for (p in playlists) byId[p.id] = p
        val claimed = HashSet<String>()
        val user = ArrayList<PlaylistGroup>()
        for (def in defs) {
            val members = def.playlists.mapNotNull { id -> byId[id]?.takeIf { claimed.add(id) } }
            if (members.isEmpty()) continue
            val icon = def.icon?.let { File(root, it) }?.takeIf { it.isFile }
            user += PlaylistGroup(PlaylistGroup.Kind.USER, def.id, def.title, icon, members)
        }
        val all = PlaylistGroup(PlaylistGroup.Kind.ALL, ALL_KEY, ALL_TITLE, null, playlists)
        if (user.isEmpty() && !SHOW_GROUPS_WITHOUT_USER_GROUPS) return GroupList(listOf(all), true)
        val recentPlaylists = recent.distinct().mapNotNull { byId[it] }.take(LibraryFormat.RECENT_KEEP)
        return GroupList(listOf(PlaylistGroup(PlaylistGroup.Kind.RECENT, RECENT_KEY, RECENT_TITLE, null, recentPlaylists)) + user + all, false)
    }
}
