// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import app.studiopocket.player.ui.PlayerNav.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNavTest {
    private val CENTRE = "KEYCODE_DPAD_CENTER"
    private val NEXT = "KEYCODE_DPAD_DOWN"
    private val PREV = "KEYCODE_DPAD_UP"
    private val BACK = "KEYCODE_BACK"
    private val PLAY_PAUSE = "KEYCODE_MEDIA_PLAY_PAUSE"

    /** Recent (with [recent] playlists), two user groups of 2 and 1, and All of 5. */
    private fun grouped(recent: Int = 2): PlayerNav = PlayerNav(0).apply {
        setGroups(listOf(Group("recent", recent), Group("g-a", 2), Group("g-b", 1), Group("all", 5)), flat = false)
    }

    // --- flat mode: no groups yet, the cover flow as it always was ---

    @Test
    fun ringMovesThroughPlaylistsAndStopsAtEitherEnd() {
        val nav = PlayerNav(2)
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        assertEquals(2, nav.itemCount)
        assertEquals(PlayerNav.Action.None, nav.onKey(PREV)); assertEquals(0, nav.selected)
        nav.onKey(NEXT); assertEquals(1, nav.selected)
        assertEquals(PlayerNav.Action.None, nav.onKey(NEXT)); assertEquals(1, nav.selected)
        nav.onKey(PREV); assertEquals(0, nav.selected)
    }

    @Test
    fun centrePlaysTheSelectedPlaylistAndBackReturnsHome() {
        val nav = PlayerNav(2)
        nav.onKey("KEYCODE_DPAD_RIGHT")
        assertEquals(PlayerNav.Action.PlaySelected(1), nav.onKey(CENTRE))
        assertEquals(PlayerNav.Screen.PLAYER, nav.screen)
        assertEquals(PlayerNav.Action.TogglePause, nav.onKey("KEYCODE_MEDIA_PLAY_PAUSE"))
        assertEquals(PlayerNav.Action.NextTrack, nav.onKey("KEYCODE_DPAD_RIGHT"))
        assertEquals(PlayerNav.Action.PreviousTrack, nav.onKey("KEYCODE_DPAD_LEFT"))
        assertEquals(PlayerNav.Action.Redraw, nav.onKey(BACK))
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        assertEquals(1, nav.selected)
        assertEquals(PlayerNav.Action.None, nav.onKey(BACK))
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
    }

    @Test
    fun emptyLibraryHasNoItemsAndCentreDoesNothing() {
        val nav = PlayerNav(0)
        assertEquals(0, nav.itemCount)
        assertEquals(PlayerNav.Action.None, nav.onKey(NEXT))
        assertEquals(PlayerNav.Action.None, nav.onKey("KEYCODE_ENTER"))
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        nav.setGroups(listOf(Group("all", 0)), flat = true)   // must not throw
    }

    @Test
    fun settingsPassesPlayPauseAndDiagnosticsReturnsToSettings() {
        val nav = PlayerNav(1)
        nav.openSettings(); assertEquals(PlayerNav.Screen.SETTINGS, nav.screen)
        assertEquals(PlayerNav.Action.TogglePause, nav.onKey("KEYCODE_MEDIA_PLAY_PAUSE"))
        assertEquals(PlayerNav.Action.None, nav.onKey("KEYCODE_DPAD_DOWN"))
        nav.showDiagnostics(); nav.onKey("KEYCODE_BACK")
        assertEquals(PlayerNav.Screen.SETTINGS, nav.screen)
        nav.closeSettings(); assertEquals(PlayerNav.Screen.HOME, nav.screen)
    }

    @Test
    fun aReloadInSettingsKeepsTheFlatSelection() {
        val nav = PlayerNav(3)
        repeat(2) { nav.onKey(NEXT) }
        nav.openSettings()
        nav.setGroups(listOf(Group("all", 3)), flat = true)
        nav.closeSettings()
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        assertEquals(2, nav.selected)
    }

    @Test
    fun selectionSurvivesALibraryShrinking() {
        val nav = PlayerNav(3)
        repeat(3) { nav.onKey(NEXT) }
        assertEquals(2, nav.selected)
        nav.setGroups(listOf(Group("all", 1)), flat = true)
        assertEquals(0, nav.selected)
        nav.setGroups(listOf(Group("all", 0)), flat = true)
        assertEquals(0, nav.selected)
        assertEquals(PlayerNav.Action.None, nav.onKey(CENTRE))
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
    }

    // --- groups ---

    @Test
    fun theGroupsScreenMovesBetweenSquaresAndStopsAtTheEnds() {
        val nav = grouped()
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        assertEquals(0, nav.group)
        assertEquals(PlayerNav.Action.None, nav.onKey(PREV))
        repeat(3) { assertEquals(PlayerNav.Action.Redraw, nav.onKey(NEXT)) }
        assertEquals(3, nav.group)
        assertEquals(PlayerNav.Action.None, nav.onKey(NEXT))
        assertEquals(PlayerNav.Action.None, nav.onKey(BACK))
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
    }

    @Test
    fun centreOpensAGroupAndBackReturnsToTheSameSquare() {
        val nav = grouped()
        nav.onKey(NEXT)
        assertEquals(PlayerNav.Action.OpenedGroup(1), nav.onKey(CENTRE))
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        assertEquals(0, nav.selected)
        nav.onKey(NEXT)
        assertEquals(PlayerNav.Action.PlaySelected(1), nav.onKey(CENTRE))
        assertEquals(PlayerNav.Action.Redraw, nav.onKey(BACK))
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        assertEquals(1, nav.group)
        assertEquals(1, nav.selected)
        assertEquals(PlayerNav.Action.BackToGroups, nav.onKey(BACK))
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        assertEquals(1, nav.group)
    }

    @Test
    fun aCoverFlowHoldsOnlyItsGroupsPlaylists() {
        val nav = grouped()
        nav.onKey(NEXT); nav.onKey(CENTRE)
        assertEquals(2, nav.itemCount)
        nav.onKey(NEXT)
        assertEquals(PlayerNav.Action.None, nav.onKey(NEXT))
        nav.onKey(BACK)
        repeat(2) { nav.onKey(NEXT) }
        nav.onKey(CENTRE)
        assertEquals(3, nav.group)
        assertEquals(5, nav.itemCount)
        repeat(5) { nav.onKey(NEXT) }
        assertEquals(4, nav.selected)
        assertEquals(PlayerNav.Action.PlaySelected(4), nav.onKey(CENTRE))
    }

    // --- settings with groups: opens from the groups screen and closes back to it ---

    @Test
    fun settingsOpensOnlyFromTheGroupsScreenAndClosesBackToIt() {
        val nav = grouped()
        assertTrue(nav.isRoot)
        nav.onKey(CENTRE)
        assertFalse(nav.isRoot)
        nav.openSettings(); assertEquals(PlayerNav.Screen.HOME, nav.screen)
        nav.onKey(CENTRE)
        nav.openSettings(); assertEquals(PlayerNav.Screen.PLAYER, nav.screen)
        nav.onKey(BACK); nav.onKey(BACK); nav.onKey(NEXT)
        nav.openSettings(); assertEquals(PlayerNav.Screen.SETTINGS, nav.screen)
        assertEquals(PlayerNav.Action.None, nav.onKey(BACK))
        nav.showDiagnostics(); nav.onKey(BACK)
        assertEquals(PlayerNav.Screen.SETTINGS, nav.screen)
        nav.closeSettings()
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        assertEquals(1, nav.group)
    }

    @Test
    fun aReloadInSettingsLeavesTheSquaresPositionsAlone() {
        // The selection left over from All's cover flow must not land on g-a's remembered position.
        val nav = grouped()
        repeat(3) { nav.onKey(NEXT) }
        nav.onKey(CENTRE); repeat(3) { nav.onKey(NEXT) }; nav.onKey(BACK)
        nav.onKey(PREV); nav.onKey(PREV)
        nav.openSettings()
        nav.setGroups(listOf(Group("recent", 2), Group("g-a", 2), Group("g-b", 1), Group("all", 5)), flat = false)
        nav.closeSettings()
        assertEquals(1, nav.group)
        nav.onKey(CENTRE)
        assertEquals(0, nav.selected)
    }

    @Test
    fun anEmptyRecentIsInertAndNotTheFirstSelection() {
        val nav = grouped(recent = 0)
        assertEquals(1, nav.group)
        nav.onKey(PREV)
        assertEquals(0, nav.group)
        assertEquals(PlayerNav.Action.None, nav.onKey(CENTRE))
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        assertEquals(0, grouped(recent = 3).group)
    }

    @Test
    fun aReloadWhileOnAnEmptyRecentKeepsItSelected() {
        // The screen times out on the dimmed Recent; the resume reloads the same groups.
        val nav = grouped(recent = 0)
        nav.onKey(PREV)
        nav.setGroups(listOf(Group("recent", 0), Group("g-a", 2), Group("g-b", 1), Group("all", 5)), flat = false)
        assertEquals(0, nav.group)
        assertEquals(0, nav.selected)
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
    }

    @Test
    fun settingsClosesToTheNewRootAfterTheModeChanges() {
        // Flat until the first group is saved; the reload comes while diagnostics is open.
        val nav = PlayerNav(2)
        nav.openSettings(); nav.showDiagnostics()
        nav.setGroups(listOf(Group("recent", 0), Group("g-a", 1), Group("all", 2)), flat = false)
        assertEquals(PlayerNav.Screen.DIAGNOSTICS, nav.screen)
        nav.onKey(BACK)
        assertEquals(PlayerNav.Screen.SETTINGS, nav.screen)
        nav.closeSettings()
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        assertEquals(1, nav.group)
    }

    @Test
    fun eachGroupRemembersItsPositionAndOpensOnThePlayingPlaylist() {
        val nav = grouped()
        nav.onKey(NEXT); nav.onKey(CENTRE); nav.onKey(NEXT)
        nav.onKey(BACK); nav.onKey(NEXT); nav.onKey(NEXT)
        nav.onKey(CENTRE)
        assertEquals(0, nav.selected)
        repeat(3) { nav.onKey(NEXT) }
        assertEquals(3, nav.selected)
        nav.onKey(BACK); nav.onKey(PREV); nav.onKey(PREV)
        nav.onKey(CENTRE)
        assertEquals(1, nav.selected)
        nav.focusPlaylist(0)
        assertEquals(0, nav.selected)
        nav.focusPlaylist(7)
        assertEquals(0, nav.selected)
    }

    @Test
    fun aReloadClampsPositionsAndLeavesAGroupThatHasGone() {
        val nav = grouped()
        repeat(3) { nav.onKey(NEXT) }
        nav.onKey(CENTRE)
        repeat(5) { nav.onKey(NEXT) }
        nav.setGroups(listOf(Group("recent", 2), Group("g-a", 2), Group("g-b", 1), Group("all", 3)), flat = false)
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        assertEquals(2, nav.selected)

        nav.onKey(BACK); nav.onKey(PREV); nav.onKey(PREV); nav.onKey(CENTRE)
        nav.onKey(NEXT)
        nav.setGroups(listOf(Group("recent", 2), Group("g-b", 1), Group("all", 3)), flat = false)
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        assertEquals(0, nav.group)
    }

    @Test
    fun recentGrowingOrShrinkingKeepsTheSelectedSquare() {
        val nav = grouped(recent = 0)
        nav.onKey(NEXT)
        assertEquals(2, nav.group)
        nav.setGroups(listOf(Group("recent", 1), Group("g-a", 2), Group("g-b", 1), Group("all", 5)), flat = false)
        assertEquals(2, nav.group)
    }

    @Test
    fun switchingBetweenFlatAndGroupsGoesToTheNewHome() {
        val nav = PlayerNav(5)
        nav.onKey(NEXT)
        nav.setGroups(listOf(Group("recent", 0), Group("g-a", 2), Group("all", 5)), flat = false)
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        assertEquals(1, nav.group)
        nav.setGroups(listOf(Group("all", 5)), flat = true)
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        assertEquals(0, nav.group)
        assertTrue(nav.selected in 0 until nav.itemCount)
    }

    @Test
    fun showRootLeavesThePlayerForTheRightHome() {
        val nav = grouped()
        nav.onKey(CENTRE); nav.onKey(CENTRE)
        assertEquals(PlayerNav.Screen.PLAYER, nav.screen)
        nav.showRoot()
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        val flat = PlayerNav(2)
        flat.onKey(CENTRE)
        flat.showRoot()
        assertEquals(PlayerNav.Screen.HOME, flat.screen)
    }

    // --- the play/pause button: pauses what is loaded from anywhere, otherwise acts as centre ---

    @Test
    fun playPauseTogglesLoadedPlaybackWithoutMovingFromGroupsOrCoverFlow() {
        val nav = grouped()
        assertEquals(PlayerNav.Action.TogglePause, nav.onKey(PLAY_PAUSE, hasPlayback = true))
        assertEquals(PlayerNav.Screen.GROUPS, nav.screen)
        nav.onKey(CENTRE)
        nav.onKey(NEXT)
        assertEquals(PlayerNav.Action.TogglePause, nav.onKey(PLAY_PAUSE, hasPlayback = true))
        assertEquals(PlayerNav.Screen.HOME, nav.screen)
        assertEquals(1, nav.selected)
        val flat = PlayerNav(2)
        assertEquals(PlayerNav.Action.TogglePause, flat.onKey(PLAY_PAUSE, hasPlayback = true))
        assertEquals(PlayerNav.Screen.HOME, flat.screen)
    }

    @Test
    fun playPauseActsAsCentreWhenNothingIsLoaded() {
        val nav = grouped()
        assertEquals(PlayerNav.Action.OpenedGroup(0), nav.onKey(PLAY_PAUSE))
        assertEquals(PlayerNav.Action.PlaySelected(0), nav.onKey(PLAY_PAUSE))
        assertEquals(PlayerNav.Screen.PLAYER, nav.screen)
        assertEquals(PlayerNav.Action.TogglePause, nav.onKey(PLAY_PAUSE))
    }

    @Test
    fun centreStillNavigatesWhilePlaying() {
        val nav = grouped()
        assertEquals(PlayerNav.Action.OpenedGroup(0), nav.onKey(CENTRE, hasPlayback = true))
        assertEquals(PlayerNav.Action.PlaySelected(0), nav.onKey(CENTRE, hasPlayback = true))
    }
}
