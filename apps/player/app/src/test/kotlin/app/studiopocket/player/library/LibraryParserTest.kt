// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LibraryParserTest {
    @Test
    fun parsesTheSharedLibraryIndex() {
        val index = LibraryParser.parseLibraryIndex(File(Fixtures.root, "library.json").readText())
        assertEquals(listOf("bedtime", "songs", "broken"), index.playlists.map { it.id })
        assertEquals(3, index.playlists[0].trackCount)
        assertEquals("playlists/bedtime", index.playlists[0].dir)
    }

    @Test
    fun parsesEveryManifest() {
        for (id in listOf("bedtime", "songs", "broken")) {
            val manifest = LibraryParser.parsePlaylistManifest(File(Fixtures.root, "playlists/$id/manifest.json").readText())
            assertEquals(id, manifest.id)
            assertTrue(manifest.tracks.isNotEmpty())
        }
        val songs = LibraryParser.parsePlaylistManifest(File(Fixtures.root, "playlists/songs/manifest.json").readText())
        assertEquals("cover.jpg", songs.cover)
        assertNull(songs.icon)
        assertEquals("audio/mp4", songs.tracks[1].mimeType)
    }

    @Test
    fun rejectsEveryInvalidFixtureWithThePath() {
        // groups-* and recent-* are read tolerantly on the player; GroupsTest and RecentStoreTest cover them.
        val files = Fixtures.invalid.listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("groups") && !f.name.startsWith("recent") }!!.sortedBy { it.name }
        assertTrue(files.size >= 6)
        for (file in files) {
            try {
                if (file.name.startsWith("library")) LibraryParser.parseLibraryIndex(file.readText())
                else LibraryParser.parsePlaylistManifest(file.readText())
                fail("${file.name} should have been rejected")
            } catch (e: FormatException) {
                assertTrue("${file.name}: ${e.message}", e.message!!.startsWith("library") || e.message!!.startsWith("manifest"))
            }
        }
    }

    @Test
    fun stateRoundTrips() {
        val state = PlaybackState("bedtime", 2, 12345L, "2026-10-18T12:00:00Z")
        assertEquals(state, LibraryParser.parsePlaybackState(LibraryParser.serializePlaybackState(state)))
        assertFalse(LibraryParser.serializePlaybackState(state).contains("finished"))
        val finished = PlaybackState("bedtime", 0, 0L, "2026-10-18T12:00:00Z", finished = true)
        assertEquals(finished, LibraryParser.parsePlaybackState(LibraryParser.serializePlaybackState(finished)))
        assertFalse(LibraryParser.serializePlaybackState(state).contains("mode"))
        for (mode in PlayMode.values()) {
            val withMode = state.copy(mode = mode)
            assertEquals(withMode, LibraryParser.parsePlaybackState(LibraryParser.serializePlaybackState(withMode)))
        }
        val unknown = LibraryParser.serializePlaybackState(state).replace("\"updatedAt\"", "\"mode\": \"sideways\", \"updatedAt\"")
        assertEquals(PlayMode.NORMAL, LibraryParser.parsePlaybackState(unknown).mode)
        val fixture = LibraryParser.parsePlaybackState(File(Fixtures.root, "state/bedtime.json").readText())
        assertEquals(1, fixture.trackIndex)
        assertEquals(1500L, fixture.positionMs)
    }

    @Test fun `track icon is optional and must live under icons`() {
        val base = """{"id":"a","title":"A","file":"tracks/a.mp3","durationMs":1,"sizeBytes":1,"sha256":"${"a".repeat(64)}","mimeType":"audio/mpeg""""
        val manifest = { track: String -> """{"format":"studiopocket-playlist","formatVersion":1,"id":"p","title":"P","cover":null,"icon":null,"tracks":[$track]}""" }
        assertEquals(null, LibraryParser.parsePlaylistManifest(manifest("$base}")).tracks[0].icon)
        assertEquals(null, LibraryParser.parsePlaylistManifest(manifest("$base,\"icon\":null}")).tracks[0].icon)
        assertEquals("icons/a.png", LibraryParser.parsePlaylistManifest(manifest("$base,\"icon\":\"icons/a.png\"}")).tracks[0].icon)
        assertThrows(FormatException::class.java) { LibraryParser.parsePlaylistManifest(manifest("$base,\"icon\":\"tracks/a.png\"}")) }
    }
}
