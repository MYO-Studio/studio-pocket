// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecentStoreTest {
    @Test
    fun readsTheSharedFixture() {
        assertEquals(listOf("songs", "gone-away", "bedtime"), RecentStore(Fixtures.root).read())
    }

    @Test
    fun readsUpToTwentyValidIdsAndToleratesBadFiles() {
        assertEquals(listOf("songs", "bedtime"), RecentStore.parse(File(Fixtures.invalid, "recent-bad-id.json").readText()))
        assertEquals(LibraryFormat.MAX_RECENT, RecentStore.parse(File(Fixtures.invalid, "recent-too-many.json").readText()).size)
        assertTrue(RecentStore.parse("{ nope").isEmpty())
        assertTrue(RecentStore.parse("""{"format":"studiopocket-recent","formatVersion":2,"updatedAt":"x","playlists":["a"]}""").isEmpty())
        assertEquals(listOf("a"), RecentStore.parse("""{"format":"studiopocket-recent","formatVersion":1,"updatedAt":"x","playlists":["a","a"]}"""))
    }

    @Test
    fun recordPlayedMovesToTheFrontKeepsFiveAndWritesAtomically() {
        val root = Files.createTempDirectory("myo").toFile()
        val store = RecentStore(root)
        assertTrue(store.read().isEmpty())
        for (id in listOf("a", "b", "c", "d", "e", "f")) assertTrue(store.recordPlayed(id))
        assertEquals(listOf("f", "e", "d", "c", "b"), store.read())
        assertTrue(store.recordPlayed("c"))
        assertEquals(listOf("c", "f", "e", "d", "b"), store.read())
        // Already first: the file isn't rewritten.
        File(root, "recent.json").setLastModified(1_000_000L)
        assertTrue(store.recordPlayed("c"))
        assertEquals(listOf("c", "f", "e", "d", "b"), store.read())
        assertEquals(1_000_000L, File(root, "recent.json").lastModified())
        assertFalse(File(root, "recent.json.part").exists())
        val text = File(root, "recent.json").readText()
        assertTrue(text, text.contains("\"studiopocket-recent\""))
        root.deleteRecursively()
    }

    @Test
    fun recordPlayedRecoversFromACorruptFile() {
        val root = Files.createTempDirectory("myo").toFile()
        File(root, "recent.json").writeText("{ nope")
        assertTrue(RecentStore(root).recordPlayed("a"))
        assertEquals(listOf("a"), RecentStore(root).read())
        root.deleteRecursively()
    }
}
