// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

/**
 * Pure navigation model for the ring + buttons. The activity feeds it key names and
 * applies the returned action. Kept free of Android types so it is unit-tested on the JVM.
 *
 * Groups: ring/arrows move between the squares (Recent, the user's groups, All), stopping at
 * either end; centre opens the selected group's cover flow, except an empty one (Recent before
 * anything has been played). In flat mode (nobody has made a group yet) there is no groups
 * screen and Home is All's cover flow, exactly as before groups existed.
 *
 * Home: the open group's cover flow. Ring/arrows move between its playlists, stopping at either
 * end rather than wrapping, since the ring overshoots easily. Centre or play starts the selected
 * playlist; back returns to the groups screen on the same square. Each group remembers its
 * position while its key is still there.
 *
 * Player: centre/play toggles, arrows skip tracks, back returns to the cover flow it came from
 * while playback continues.
 *
 * Settings (opened by holding Back on the root screen, see [BackHold]) is driven by its own
 * controller; the nav only passes play/pause through so playback stays controllable. Diagnostics
 * hangs off Settings.
 *
 * The play/pause button (and headset/media keys) pauses or resumes whatever is loaded from any
 * screen, without moving; only when nothing is loaded does it act as centre. Centre always navigates.
 */
class PlayerNav(playlistCount: Int) {
    enum class Screen { GROUPS, HOME, PLAYER, SETTINGS, DIAGNOSTICS }

    /** What navigation needs to know about one square: a stable key and how many playlists it opens onto. */
    data class Group(val key: String, val size: Int)

    sealed class Action {
        object None : Action()
        data class PlaySelected(val index: Int) : Action()
        data class OpenedGroup(val group: Int) : Action()
        object BackToGroups : Action()
        object TogglePause : Action()
        object NextTrack : Action()
        object PreviousTrack : Action()
        object Redraw : Action()
    }

    var screen = Screen.HOME; private set
    var groups: List<Group> = emptyList(); private set
    var flat = true; private set
    /** The selected square on the groups screen, and the group whose cover flow is open. */
    var group = 0; private set
    /** Position in the open cover flow. */
    var selected = 0; private set
    private val positions = HashMap<String, Int>()

    init {
        setGroups(listOf(Group(ALL_KEY, playlistCount)), flat = true)
    }

    /** All is always the last group. */
    val isAllOpen: Boolean get() = group == groups.lastIndex
    /** The open group's playlists. */
    val itemCount: Int get() = groups[group].size
    /** Where settings opens from and closes to: the groups screen, or the cover flow in flat mode. */
    val isRoot: Boolean get() = screen == if (flat) Screen.HOME else Screen.GROUPS

    /**
     * Called whenever the groups are rebuilt (library reload, Recent refreshed). Keeps the
     * selection on the same key, clamps every remembered position, and goes back to the groups
     * screen if the open group has gone. Switching between flat and grouped goes to the new home.
     */
    fun setGroups(next: List<Group>, flat: Boolean) {
        require(next.isNotEmpty()) { "there is always an All group" }
        val openKey = groups.getOrNull(group)?.key
        val modeChanged = groups.isEmpty() || flat != this.flat
        // [selected] belongs to the open group in a cover flow or the player, and always in flat mode
        // (settings opens from the cover flow there); on the groups screen and settings it is stale.
        val selectionLive = this.flat || screen == Screen.HOME || screen == Screen.PLAYER
        if (selectionLive && openKey != null) positions[openKey] = selected
        groups = next
        this.flat = flat
        positions.keys.retainAll(next.map { it.key }.toSet())
        next.forEach { g -> positions[g.key]?.let { positions[g.key] = it.coerceIn(0, maxOf(0, g.size - 1)) } }
        val same = if (modeChanged) -1 else next.indexOfFirst { it.key == openKey }
        when {
            flat -> { group = next.lastIndex; if (screen == Screen.GROUPS) screen = Screen.HOME }
            same >= 0 -> group = same
            else -> { group = initialGroup(); if (screen == Screen.HOME) screen = Screen.GROUPS }
        }
        // An empty Recent has no items: clamp to 0 rather than to an empty range.
        selected = (positions[groups[group].key] ?: 0).coerceIn(0, maxOf(0, itemCount - 1))
    }

    /** [hasPlayback]: a playlist is loaded (playing or paused), so the play/pause button toggles it in place. */
    fun onKey(keyName: String, hasPlayback: Boolean = false): Action =
        if (hasPlayback && keyName in MEDIA_KEYS) Action.TogglePause else navigate(keyName)

    private fun navigate(keyName: String): Action = when (screen) {
        Screen.GROUPS -> when (keyName) {
            "KEYCODE_DPAD_UP", "KEYCODE_DPAD_LEFT" -> moveGroup(-1)
            "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_RIGHT" -> moveGroup(1)
            "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_PLAY", "KEYCODE_HEADSETHOOK" -> openGroup()
            else -> Action.None
        }
        Screen.HOME -> when (keyName) {
            "KEYCODE_DPAD_UP", "KEYCODE_DPAD_LEFT" -> moveSelection(-1)
            "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_RIGHT" -> moveSelection(1)
            "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_PLAY", "KEYCODE_HEADSETHOOK" ->
                if (groups[group].size == 0) Action.None else { screen = Screen.PLAYER; Action.PlaySelected(selected) }
            "KEYCODE_BACK" -> if (flat) Action.None else { leaveCoverFlow(); Action.BackToGroups }
            else -> Action.None
        }
        Screen.PLAYER -> when (keyName) {
            "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_PLAY", "KEYCODE_MEDIA_PAUSE", "KEYCODE_HEADSETHOOK" -> Action.TogglePause
            "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_DOWN", "KEYCODE_MEDIA_NEXT" -> Action.NextTrack
            "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_UP", "KEYCODE_MEDIA_PREVIOUS" -> Action.PreviousTrack
            "KEYCODE_BACK" -> { screen = Screen.HOME; Action.Redraw }
            else -> Action.None
        }
        Screen.SETTINGS -> when (keyName) {
            "KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_PLAY", "KEYCODE_MEDIA_PAUSE", "KEYCODE_HEADSETHOOK" -> Action.TogglePause
            else -> Action.None
        }
        Screen.DIAGNOSTICS -> when (keyName) {
            "KEYCODE_BACK", "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER" -> { screen = Screen.SETTINGS; Action.Redraw }
            else -> Action.None
        }
    }

    /** After [Action.OpenedGroup]: start on the playing playlist when it is in the group. */
    fun focusPlaylist(index: Int) {
        if (screen == Screen.HOME && index in 0 until groups[group].size) selected = index
    }

    /** Home for this mode: the groups screen, or the flat cover flow. Used when storage goes away and when settings closes. */
    fun showRoot() {
        if (flat) { screen = Screen.HOME; return }
        if (screen == Screen.HOME) leaveCoverFlow() else screen = Screen.GROUPS
    }

    fun openSettings() { if (isRoot) screen = Screen.SETTINGS }
    fun closeSettings() = showRoot()
    fun showDiagnostics() { screen = Screen.DIAGNOSTICS }

    private fun openGroup(): Action {
        if (groups[group].size == 0 && !isAllOpen) return Action.None
        screen = Screen.HOME
        selected = (positions[groups[group].key] ?: 0).coerceIn(0, maxOf(0, itemCount - 1))
        return Action.OpenedGroup(group)
    }

    private fun leaveCoverFlow() {
        positions[groups[group].key] = selected
        screen = Screen.GROUPS
    }

    /** Recent first unless it is empty, so the first centre press always does something. */
    private fun initialGroup(): Int = if (groups[0].size > 0 || groups.size == 1) 0 else 1

    private fun moveGroup(delta: Int): Action {
        val target = (group + delta).coerceIn(0, groups.size - 1)
        if (target == group) return Action.None
        group = target
        return Action.Redraw
    }

    private fun moveSelection(delta: Int): Action {
        val target = (selected + delta).coerceIn(0, maxOf(0, itemCount - 1))
        if (target == selected) return Action.None
        selected = target
        return Action.Redraw
    }

    companion object {
        const val ALL_KEY = "all"
        private val MEDIA_KEYS = setOf("KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_PLAY", "KEYCODE_MEDIA_PAUSE", "KEYCODE_HEADSETHOOK")
    }
}
