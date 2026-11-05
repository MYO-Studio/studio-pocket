// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryLoaderTest {
    @Test
    fun showsCompletePlaylistsAndHidesTheTruncatedOne() {
        val library = LibraryLoader(Fixtures.root).load()
        assertNull(library.problem)
        assertEquals(listOf("bedtime", "songs"), library.playlists.map { it.id })
        assertEquals(1, library.hidden.size)
        assertEquals("broken", library.hidden[0].first)
        assertTrue(library.hidden[0].second, library.hidden[0].second.contains("tracks/t2.mp3"))
        assertFalse(library.syncInProgress)
        assertNotNull(library.playlists[0].coverFile)
        assertTrue(library.playlists[0].trackFile(2).isFile)
    }

    @Test
    fun aMissingFolderAndAMissingIndexReadTheSame() {
        val missing = LibraryLoader(File("/definitely/not/here")).load()
        assertTrue(missing.playlists.isEmpty())
        assertEquals(LibraryLoader.NO_LIBRARY_YET, missing.problem)
        val empty = Files.createTempDirectory("studiopocket").toFile()
        assertEquals(LibraryLoader.NO_LIBRARY_YET, LibraryLoader(empty).load().problem)
        empty.deleteRecursively()
    }

    @Test
    fun loadReportsAnUnreadableLibraryJson() {
        val dir = Files.createTempDirectory("myo").toFile()
        File(dir, "library.json").mkdirs()
        val library = LibraryLoader(dir).load()
        assertTrue(library.playlists.isEmpty())
        assertTrue(library.problem, library.problem!!.startsWith("could not read library.json"))
        dir.deleteRecursively()
    }

    @Test
    fun aPlaylistWhoseManifestCannotBeReadIsHidden() {
        val dir = Files.createTempDirectory("myo").toFile()
        Fixtures.root.copyRecursively(dir)
        val manifest = File(dir, "playlists/bedtime/manifest.json")
        assertTrue(manifest.delete())
        assertTrue(manifest.mkdirs())
        val library = LibraryLoader(dir).load()
        assertNull(library.problem)
        assertFalse(library.playlists.any { it.id == "bedtime" })
        assertEquals("cannot read manifest.json", library.hidden.first { it.first == "bedtime" }.second)
        dir.deleteRecursively()
    }

    @Test
    fun flagsASyncInProgress() {
        val dir = Files.createTempDirectory("myo").toFile()
        Fixtures.root.copyRecursively(dir)
        File(dir, "sync.lock").writeText("x")
        assertTrue(LibraryLoader(dir).load().syncInProgress)
        dir.deleteRecursively()
    }

    @Test
    fun aPlaylistWithAnUpdateLockIsHidden() {
        val dir = Files.createTempDirectory("myo").toFile()
        Fixtures.root.copyRecursively(dir)
        File(dir, "playlists/bedtime/update.lock").writeText("x")
        val library = LibraryLoader(dir).load()
        assertFalse(library.playlists.any { it.id == "bedtime" })
        assertEquals("update interrupted", library.hidden.first { it.first == "bedtime" }.second)
        dir.deleteRecursively()
    }
}
