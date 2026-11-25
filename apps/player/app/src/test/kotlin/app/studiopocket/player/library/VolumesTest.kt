// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VolumesTest {
    private val temp: File = Files.createTempDirectory("volumes").toFile()

    @After fun cleanUp() { temp.deleteRecursively() }

    private fun dir(name: String) = File(temp, name).apply { mkdirs() }

    // --- discovery ---

    @Test fun `candidates are SECONDARY_STORAGE in order, then the fallback, without repeats`() {
        assertEquals(listOf("/storage/sdcard1"), Volumes.cardCandidates(null).map { it.path })
        assertEquals(listOf("/storage/sdcard1"), Volumes.cardCandidates("").map { it.path })
        assertEquals(listOf("/storage/sdcard1"), Volumes.cardCandidates("/storage/sdcard1").map { it.path })
        assertEquals(listOf("/storage/ext", "/storage/usbotg", "/storage/sdcard1"), Volumes.cardCandidates("/storage/ext::/storage/usbotg ").map { it.path })
    }

    @Test fun `the first mounted candidate that is not internal is the card`() {
        val internal = dir("sdcard0")
        val a = dir("sdcard1")
        val b = dir("usbotg")
        val states = mutableMapOf(a.path to "removed", b.path to "mounted")
        val card = Volumes.findCard(internal, listOf(internal, a, b)) { states[it.path] }
        assertEquals(b, card.path)
        assertTrue(card.mounted)
        assertEquals(File(b, "STUDIOPOCKET"), card.root)
        states[a.path] = "mounted_ro"
        assertEquals(a, Volumes.findCard(internal, listOf(a, b)) { states[it.path] }.path)
    }

    @Test fun `a candidate that resolves to internal is never the card`() {
        val internal = dir("sdcard0")
        val link = File(temp, "link-to-internal")
        Files.createSymbolicLink(link.toPath(), internal.toPath())
        val card = Volumes.findCard(internal, listOf(link, internal)) { "mounted" }
        assertNull(card.path)
        assertFalse(card.mounted)
        assertNull(card.root)
        assertEquals("absent", card.state)
    }

    @Test fun `no mounted card still names the first candidate and its state`() {
        val internal = dir("sdcard0")
        val a = File(temp, "sdcard1")
        val card = Volumes.findCard(internal, listOf(a)) { if (it == a) "unmounted" else null }
        assertEquals(a, card.path)
        assertEquals("unmounted", card.state)
        assertFalse(card.mounted)
        assertNull(card.root)
        assertEquals("unknown", Volumes.findCard(internal, listOf(a)) { null }.state)
    }

    @Test fun `isUnder compares whole path segments`() {
        val card = File("/storage/sdcard1")
        assertTrue(Volumes.isUnder(File("/storage/sdcard1/STUDIOPOCKET/playlists/a"), card))
        assertTrue(Volumes.isUnder(card, card))
        assertTrue(Volumes.isUnder(File("/storage/sdcard1/x"), File("/storage/sdcard1/")))
        assertFalse(Volumes.isUnder(File("/storage/sdcard10/STUDIOPOCKET"), card))
        assertFalse(Volumes.isUnder(File("/storage/sdcard0/STUDIOPOCKET"), card))
    }

    // --- pairing ---

    private val internalMarker = """{"format":"studiopocket-device","formatVersion":1,"deviceId":"abc-123","createdAt":"2026-10-18T12:00:00Z","createdBy":"studio-pocket-desktop/0.1.0","playerVersion":"0.10.2"}"""
    private fun cardMarker(id: String, storage: String = "card") = """{"format":"studiopocket-device","formatVersion":1,"deviceId":"$id","createdAt":"2026-11-01T12:00:00Z","createdBy":"studio-pocket-desktop/0.10.2","storage":"$storage"}"""

    private fun internalRoot(marker: String? = internalMarker) = dir("internal/STUDIOPOCKET").also { root -> marker?.let { File(root, "device.json").writeText(it) } }

    @Test fun `a card without a marker is marked as this player's`() {
        val internal = internalRoot()
        val card = File(temp, "card/STUDIOPOCKET")   // the folder does not exist yet
        assertEquals(Pairing.MARKED, CardPairing.pair(internal, card))
        val marker = JSONObject(File(card, "device.json").readText())
        assertEquals("studiopocket-device", marker.getString("format"))
        assertEquals(1, marker.getInt("formatVersion"))
        assertEquals("abc-123", marker.getString("deviceId"))
        assertEquals("card", marker.getString("storage"))
        assertEquals("studio-pocket-player", marker.getString("createdBy"))
        assertTrue(marker.getString("createdAt").matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")))
        assertFalse(marker.has("playerVersion"))
        assertFalse(File(card, "device.json.part").exists())
        // Next time it is simply paired, and the marker is not rewritten.
        val written = File(card, "device.json").readText()
        assertEquals(Pairing.PAIRED, CardPairing.pair(internal, card))
        assertEquals(written, File(card, "device.json").readText())
        assertTrue(Pairing.MARKED.paired && Pairing.PAIRED.paired)
    }

    @Test fun `a matching marker pairs and is left alone`() {
        val card = dir("card/STUDIOPOCKET")
        File(card, "device.json").writeText(cardMarker("abc-123"))
        assertEquals(Pairing.PAIRED, CardPairing.pair(internalRoot(), card))
        assertEquals(cardMarker("abc-123"), File(card, "device.json").readText())
    }

    @Test fun `a foreign, unreadable or non-card marker is ignored and never changed`() {
        val internal = internalRoot()
        val card = dir("card/STUDIOPOCKET")
        val file = File(card, "device.json")
        for ((text, expected) in listOf(
            cardMarker("someone-else") to Pairing.FOREIGN,
            "{ nope" to Pairing.UNREADABLE,
            """{"format":"studiopocket-device","formatVersion":2,"deviceId":"abc-123"}""" to Pairing.UNREADABLE,
            cardMarker("abc-123", storage = "internal") to Pairing.NOT_A_CARD,
            cardMarker("abc-123", storage = "sd") to Pairing.UNREADABLE,
        )) {
            file.writeText(text)
            val result = CardPairing.pair(internal, card)
            assertEquals(text, expected, result)
            assertFalse(result.paired)
            assertEquals(text, file.readText())
        }
        // A directory in the marker's place reads as unreadable, and is not replaced.
        file.delete(); file.mkdirs()
        assertEquals(Pairing.UNREADABLE, CardPairing.pair(internal, card))
        assertTrue(file.isDirectory)
    }

    @Test fun `without a readable internal marker the card is neither paired nor marked`() {
        val card = dir("card/STUDIOPOCKET")
        assertEquals(Pairing.NO_INTERNAL_MARKER, CardPairing.pair(internalRoot(marker = null), card))
        assertFalse(File(card, "device.json").exists())
        assertEquals(Pairing.NO_INTERNAL_MARKER, CardPairing.pair(internalRoot(marker = "{ nope"), card))
        assertFalse(File(card, "device.json").exists())
        // An internal marker that claims to be a card's is not this player's identity.
        assertEquals(Pairing.NO_INTERNAL_MARKER, CardPairing.pair(internalRoot(marker = cardMarker("abc-123")), card))
        assertFalse(File(card, "device.json").exists())
        File(card, "device.json").writeText(cardMarker("abc-123"))
        assertEquals(Pairing.NO_INTERNAL_MARKER, CardPairing.pair(internalRoot(marker = null).also { File(it, "device.json").delete() }, card))
    }

    @Test fun `a failed write is logged and leaves the card unpaired`() {
        val internal = internalRoot()
        val volume = dir("card")
        val card = File(volume, "STUDIOPOCKET").apply { writeText("a file where the folder should be") }
        val log = mutableListOf<String>()
        assertEquals(Pairing.WRITE_FAILED, CardPairing.pair(internal, card) { log += it })
        assertEquals(1, log.size)
        assertTrue(card.isFile)
        // A read-only folder (a write-protected card) refuses the .part file; skipped where the OS lets us write anyway.
        card.delete(); card.mkdirs(); card.setWritable(false)
        try {
            if (!File(card, "probe").let { runCatching { it.writeText("x") }.isSuccess }) {
                assertEquals(Pairing.WRITE_FAILED, CardPairing.pair(internal, card) { log += it })
                assertFalse(File(card, "device.json").exists())
                assertFalse(File(card, "device.json.part").exists())
                assertEquals(2, log.size)
            }
        } finally {
            card.setWritable(true)
        }
    }

    // --- merged library ---

    /** Writes a complete STUDIOPOCKET/ with one single-track playlist per id. */
    private fun library(name: String, vararg ids: String): File {
        val root = dir("$name/STUDIOPOCKET")
        val sha = "a".repeat(64)
        val entries = ids.joinToString(",") { id ->
            val pdir = File(root, "playlists/$id/tracks").apply { mkdirs() }.parentFile
            File(pdir, "tracks/t1.mp3").writeText("abc")
            File(pdir, "manifest.json").writeText("""{"format":"studiopocket-playlist","formatVersion":1,"id":"$id","title":"$name $id","cover":null,"icon":null,"tracks":[{"id":"t1","title":"T","file":"tracks/t1.mp3","durationMs":1,"sizeBytes":3,"sha256":"$sha","mimeType":"audio/mpeg"}]}""")
            """{"id":"$id","title":"$name $id","dir":"playlists/$id","trackCount":1,"totalBytes":3,"manifestSha256":"$sha"}"""
        }
        File(root, "library.json").writeText("""{"format":"studiopocket-library","formatVersion":1,"generatedAt":"2026-11-14T09:00:00Z","generator":"test","playlists":[$entries]}""")
        return root
    }

    @Test fun `internal playlists come first in index order, then the card's, and a duplicate keeps internal`() {
        val internal = library("internal", "zebra", "apple")
        val card = library("card", "mango", "apple", "kiwi")
        val merged = MergedLibrary.load(internal, card)
        assertEquals(listOf("zebra", "apple", "mango", "kiwi"), merged.playlists.map { it.id })
        assertEquals("internal apple", merged.playlists[1].title)
        assertTrue(Volumes.isUnder(merged.playlists[1].dir, internal))
        assertTrue(Volumes.isUnder(merged.playlists[2].dir, card))
        assertNull(merged.problem)
        assertFalse(merged.syncInProgress)
    }

    @Test fun `without a card the library is internal's alone`() {
        val internal = library("internal", "zebra")
        assertEquals(LibraryLoader(internal).load(), MergedLibrary.load(internal, null))
    }

    @Test fun `hidden playlists from both roots are listed, the card's marked`() {
        val internal = library("internal", "a")
        val card = library("card", "b", "c")
        File(internal, "playlists/a/tracks/t1.mp3").writeText("ab")
        File(card, "playlists/c/update.lock").writeText("x")
        val merged = MergedLibrary.load(internal, card)
        assertEquals(listOf("b"), merged.playlists.map { it.id })
        assertEquals(listOf("a", "c"), merged.hidden.map { it.first })
        assertEquals("card: update interrupted", merged.hidden[1].second)
    }

    @Test fun `a sync lock on either root is a sync in progress, even before the card has a library`() {
        val internal = library("internal", "a")
        val card = dir("card/STUDIOPOCKET")
        assertFalse(MergedLibrary.load(internal, card).syncInProgress)
        File(card, "sync.lock").writeText("x")
        assertTrue(MergedLibrary.load(internal, card).syncInProgress)
        File(card, "sync.lock").delete()
        File(internal, "sync.lock").writeText("x")
        assertTrue(MergedLibrary.load(internal, card).syncInProgress)
    }

    @Test fun `with nothing shown the problem is internal's, else the card's`() {
        val freshInternal = dir("fresh/STUDIOPOCKET")
        val freshCard = dir("freshcard/STUDIOPOCKET")
        // A fresh player, with or without a fresh card: "No library yet" as before.
        assertEquals(LibraryLoader.NO_LIBRARY_YET, MergedLibrary.load(freshInternal, null).problem)
        assertEquals(LibraryLoader.NO_LIBRARY_YET, MergedLibrary.load(freshInternal, freshCard).problem)
        assertEquals(LibraryLoader.NO_LIBRARY_YET, MergedLibrary.load(File(temp, "missing/STUDIOPOCKET"), freshCard).problem)
        // A broken internal index wins over the card's problem.
        val badCard = dir("badcard/STUDIOPOCKET").also { File(it, "library.json").writeText("{ nope") }
        val badInternal = dir("bad/STUDIOPOCKET").also { File(it, "library.json").writeText("{ nope") }
        assertTrue(MergedLibrary.load(badInternal, badCard).problem!!.startsWith("library: invalid JSON"))
        // Internal has a library with nothing showable: the card's problem, but not "No library yet".
        val emptyInternal = library("empty")
        assertTrue(MergedLibrary.load(emptyInternal, badCard).problem!!.startsWith("library: invalid JSON"))
        assertNull(MergedLibrary.load(emptyInternal, freshCard).problem)
        // Anything shown means no problem, whichever root is broken.
        assertNull(MergedLibrary.load(badInternal, library("card", "a")).problem)
        assertEquals(listOf("a"), MergedLibrary.load(freshInternal, library("card2", "a")).playlists.map { it.id })
    }
}
